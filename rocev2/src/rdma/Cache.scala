package rdma

import spinal.core._
import spinal.lib._
import ConstantSettings._
import RdmaConstants._
import StreamVec._

case class CamFifoQueryResp[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv]
) extends Bundle {
  val queryKey = keyType()
  val respValue = valueType()
  val found = Bool()
}

case class CamFifoQueryBus[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv]
) extends Bundle
    with IMasterSlave {
  val req = Stream(keyType())
  val resp = Stream(CamFifoQueryResp(keyType, valueType))

//  def >>(that: CamFifoQueryBus[Tk, Tv]): Unit = {
//    this.req >> that.req
//    this.resp << that.resp
//  }
//
//  def <<(that: CamFifoQueryBus[Tk, Tv]): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class CamFifoRetryScanReq(depth: Int) extends Bundle {
  //  val scanPtr = Stream(UInt(log2Up(depth) bits))
  val ptr = UInt(log2Up(depth) bits)
  val retryReason = RetryReason()
  val retryStartPsn = UInt(PSN_WIDTH bits)
}

case class CamFifoRetryScanResp[Tv <: Data](valueType: HardType[Tv], depth: Int)
    extends Bundle {
  val data = valueType()
}

case class CamFifoScanBus[Tv <: Data](valueType: HardType[Tv], depth: Int)
    extends Bundle
    with IMasterSlave {
  val empty = Bool()
  val popPtr = UInt(log2Up(depth) bits)
  val pushPtr = UInt(log2Up(depth) bits)

  val scanReq = Stream(CamFifoRetryScanReq(depth))
  val scanResp = Stream(CamFifoRetryScanResp(valueType, depth))

  def >>(that: CamFifoScanBus[Tv]): Unit = {
    that.scanReq << this.scanReq
    this.scanResp << that.scanResp

    this.popPtr := that.popPtr
    this.pushPtr := that.pushPtr
    this.empty := that.empty
  }

  def <<(that: CamFifoScanBus[Tv]): Unit = that >> this

  override def asMaster(): Unit = {
    in(popPtr, pushPtr, empty)
    master(scanReq)
    slave(scanResp)
  }
}

case class CachedValue[T <: Data](dataType: HardType[T]) extends Bundle {
  val valid = Bool()
  val data = dataType()
}

class CamFifo[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv],
    queryFunc: (Tk, Tv) => Bool,
    depth: Int,
    portCount: Int,
    scanRespOnFireModifyFunc: Option[(CamFifoRetryScanReq, Tv) => Tv],
    supportScan: Boolean
) extends Component {
  val io = new Bundle {
    val push = slave(Stream(valueType))
    val pop = master(Stream(valueType))
    val flush = in(Bool())
    val occupancy = out(UInt(log2Up(depth + 1) bits))
//    val availability = out(UInt(log2Up(depth + 1) bits))
    val empty = out(Bool())
    val full = out(Bool())
    val queryBusVec = Vec(slave(CamFifoQueryBus(keyType, valueType)), portCount)
    val scanBus = slave(CamFifoScanBus(valueType, depth))
  }

  // Copied code from StreamFifoLowLatency
  val fifo = new Area {
    val logic = new Area {
      val ram = Mem(CachedValue(valueType), depth)
      val pushPtr = Counter(depth)
      val popPtr = Counter(depth)
      val ptrMatch = pushPtr === popPtr
      val risingOccupancy = RegInit(False)
      val empty = ptrMatch & !risingOccupancy
      val full = ptrMatch & risingOccupancy

      val pushing = io.push.fire
      val popping = io.pop.fire

      io.push.ready := !full
      io.empty := empty
      io.full := full

      when(!empty) {
        io.pop.valid := True
        val popValue = ram.readAsync(popPtr.value, readUnderWrite = writeFirst)
        io.pop.payload := popValue.data
        // TODO: verify that can we update RAM like this?
        popValue.valid := !io.pop.fire
//        ram(popPtr.value).valid := !io.pop.fire
      } otherwise {
        io.pop.valid := io.push.valid
        io.pop.payload := io.push.payload
      }

      when(pushing =/= popping) {
        risingOccupancy := pushing
      }
      when(pushing) {
        val pushValue = CachedValue(valueType)
        pushValue.data := io.push.payload
        pushValue.valid := io.push.valid
        ram(pushPtr.value) := pushValue
        pushPtr.increment()
      }
      when(popping) {
        popPtr.increment()
      }

      val ptrDif = pushPtr - popPtr
      if (isPow2(depth))
        io.occupancy := ((risingOccupancy && ptrMatch) ## ptrDif).asUInt
      else {
        when(ptrMatch) {
          io.occupancy := Mux(risingOccupancy, U(depth), U(0))
        } otherwise {
          io.occupancy := Mux(pushPtr > popPtr, ptrDif, U(depth) + ptrDif)
        }
      }

      when(io.flush) {
        pushPtr.clear()
        popPtr.clear()
        risingOccupancy := False
      }
    }

    val scanModifyLogic = supportScan generate new Area {
      io.scanBus.empty := logic.empty
      io.scanBus.popPtr := logic.popPtr
      io.scanBus.pushPtr := logic.pushPtr

      val scanRespStream = logic.ram.streamReadSync(
        io.scanBus.scanReq ~~ (scanRequest => scanRequest.ptr),
        linkedData = io.scanBus.scanReq
      )
      io.scanBus.scanResp <-/< scanRespStream ~~ { scanResponse =>
        val result = cloneOf(io.scanBus.scanResp.payloadType)
        result.data := scanResponse.value.data
        result
      }
      // Increase retry count
      for (modifyFunc <- scanRespOnFireModifyFunc) {
        when(scanRespStream.fire) {
          logic.ram(scanRespStream.linked.ptr).data := modifyFunc(
            scanRespStream.linked,
            logic.ram(scanRespStream.linked.ptr).data
          )
        }
      }

//      io.retryScanBus.modifyReq.valid := False
//      when(io.retryScanBus.modifyReq.valid) {
//        // TODO: should consider CachedValue.valid?
//        logic
//          .ram(io.retryScanBus.modifyReq.ptr)
//          .data := io.retryScanBus.modifyReq.data
//        io.retryScanBus.modifyReq.valid := True
//      }

//      io.retryScanBus.popPtr := logic.popPtr
//      io.retryScanBus.pushPtr := logic.pushPtr
//      io.retryScanBus.empty := logic.empty
//      io.retryScanBus.value := logic.ram
//        .readAsync(io.retryScanBus.scanPtr)
//        .data
//      // Modify Mem data
//      when(io.retryScanBus.modifyPulse) {
//        logic
//          .ram(io.retryScanBus.modifyPtr)
//          .data := io.retryScanBus.modifiedValue
//      }
    }
  }

  def search(queryReq: Stream[TupleBundle2[Tk, UInt]]) =
    new Composite(queryReq, "CamFifo_search") {
      val queryKey = queryReq._1
      val queryPortIdx = queryReq._2
      val idxOH = Vec((0 until depth).map(idx => {
        val v = fifo.logic.ram.readAsync(
          address = U(idx, log2Up(depth) bits),
          // The read will get the new value (provided by the write)
          readUnderWrite = writeFirst
        )
        queryFunc(queryKey, v.data) && v.valid
      }))
      val found = idxOH.asBits.andR
      val idxBinary = OHToUInt(idxOH)
      val ramIdxStream = queryReq.translateWith(idxBinary)
      // TODO: check the timing of RAM search
      // ramIdxStream cannot be staged, to prevent FIFO write to RAM to invalidate the idxOH
      val queryResultStream = fifo.logic.ram
        .streamReadSync(
          ramIdxStream,
          linkedData = TupleBundle(queryPortIdx, queryKey, found)
        )
    }.queryResultStream

  if (portCount == 1) {
    val busIdx = 0
    val queryIdx = U(0, portCount bits)
    val queryResultStream = search(
      io.queryBusVec(busIdx)
        .req
        .translateWith(
          TupleBundle(io.queryBusVec(busIdx).req.payload, queryIdx)
        )
    )
    io.queryBusVec(busIdx).resp <-/< queryResultStream.translateWith {
      val result = CamFifoQueryResp(keyType, valueType)
      result.respValue := queryResultStream.value.data
      result.queryKey := queryResultStream.linked._2
      result.found := queryResultStream.linked._3
      result
    }
  } else { // More than 2 query port, need arbitration
    val queryReqVec =
      for ((queryBus, idx) <- io.queryBusVec.zipWithIndex)
        yield {
          val queryPortIdx = U(idx, log2Up(portCount) bits)
          queryBus.req.translateWith(
            TupleBundle(queryBus.req.payload, queryPortIdx)
          )
        }

    val queryArbitrated =
      StreamArbiterFactory.roundRobin.transactionLock.on(queryReqVec)
    val queryResp = search(queryArbitrated)
    val queryRespVec = StreamDemux(
      queryResp,
      select = queryResp.linked._1,
      portCount = portCount
    )
    for (idx <- 0 until portCount) {
      io.queryBusVec(idx).resp.arbitrationFrom(queryRespVec(idx))
      io.queryBusVec(idx).resp.respValue := queryRespVec(idx).value.data
      io.queryBusVec(idx).resp.queryKey := queryRespVec(idx).linked._2
      io.queryBusVec(idx).resp.found := queryRespVec(idx).linked._3
    }
  }
}

// pp. 287 spec 1.4
// If the responder QP supports multiple outstanding ATOMIC Operations
// and RDMA READ Operations, the information on each valid request is
// saved in FIFO order. The FIFO depth is the same as the maximum number
// of outstanding ATOMIC Operations and RDMA READ requests negotiated
// on a per QP basis at connection setup.
class ReadAtomicRstCache(depth: Int) extends Component {
  val io = new Bundle {
    val push = slave(Stream(ReadAtomicRstCacheData()))
    val pop = master(Stream(ReadAtomicRstCacheData()))
    val occupancy = out(UInt(log2Up(depth + 1) bits))
    val flush = in(Bool())
//    val queryPort4DmaReadResp = slave(ReadAtomicRstCacheQueryBus())
    val queryPort4DupReq = slave(ReadAtomicRstCacheQueryBus())
//    val queryPort4DupReqDmaRead = slave(ReadAtomicRstCacheQueryBus())
  }

  val cache = new CamFifo(
    ReadAtomicRstCacheReq(),
    ReadAtomicRstCacheData(),
    queryFunc = (k: ReadAtomicRstCacheReq, v: ReadAtomicRstCacheData) =>
      v.psnStart <= k.psn && k.psn < (v.psnStart + v.pktNum),
    depth = depth,
    portCount = 1,
    scanRespOnFireModifyFunc = None,
    supportScan = false
  )
  cache.io.push << io.push
  io.pop << cache.io.pop
  io.occupancy := cache.io.occupancy
  cache.io.flush := io.flush

  val queryPortVec = Vec(
//    io.queryPort4DmaReadResp,
    io.queryPort4DupReq
//    io.queryPort4DupReqDmaRead
  )
  for ((queryPort, portIdx) <- queryPortVec.zipWithIndex) {
    cache.io.queryBusVec(portIdx).req << queryPort.req
    queryPort.resp << cache.io
      .queryBusVec(portIdx)
      .resp
      .translateWith {
        val result = cloneOf(queryPort.resp.payloadType)
        result.rstCacheData := cache.io.queryBusVec(portIdx).resp.respValue
        result.query := cache.io.queryBusVec(portIdx).resp.queryKey
        result.found := cache.io.queryBusVec(portIdx).resp.found
        result
      }
  }
}

class WorkReqCache(depth: Int) extends Component {
  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
    val push = slave(Stream(CachedWorkReq()))
    val pop = master(Stream(CachedWorkReq()))
    val occupancy = out(UInt(log2Up(depth + 1) bits))
    val empty = out(Bool())
    val retryScanBus = slave(CamFifoScanBus(CachedWorkReq(), depth))
//    val queryPort4SqReqDmaRead = slave(WorkReqCacheQueryBus())
    val queryPort4SqRespDmaWrite = slave(WorkReqCacheQueryBus())
//    val queryPort4DupReqDmaRead = slave(WorkReqCacheQueryBus())
  }

  val cache = new CamFifo(
    WorkReqCacheQueryReq(),
    CachedWorkReq(),
    queryFunc = (k: WorkReqCacheQueryReq, v: CachedWorkReq) =>
      // TODO: verify PSN comparison correctness
      PsnUtil.lte(v.psnStart, k.psn, v.psnStart) &&
        PsnUtil.lt(k.psn, v.psnStart + v.pktNum, v.psnStart),
    depth = depth,
    portCount = 1,
    scanRespOnFireModifyFunc =
      Some((scanReq: CamFifoRetryScanReq, v: CachedWorkReq) => {
        val result = cloneOf(v)
        result := v
        // TODO: which retry counter to increase after the very first retry WR
        result.incRnrOrRetryCnt(scanReq.retryReason)
        result
      }),
    supportScan = true
  )
  cache.io.push << io.push
  io.pop << cache.io.pop
  // WR cache flush is handled by RespHandler, cannot directly be flushed
  cache.io.flush := False
  io.occupancy := cache.io.occupancy
  io.empty := cache.io.empty
  cache.io.scanBus << io.retryScanBus

  val queryPortVec = Vec(
//    io.queryPort4SqReqDmaRead,
    io.queryPort4SqRespDmaWrite
//    io.queryPort4DupReqDmaRead
  )
  for ((queryPort, portIdx) <- queryPortVec.zipWithIndex) {
    cache.io.queryBusVec(portIdx).req << queryPort.req
    queryPort.resp << cache.io
      .queryBusVec(portIdx)
      .resp
      .translateWith {
        val result = cloneOf(queryPort.resp.payloadType)
        result.cachedWorkReq := cache.io.queryBusVec(portIdx).resp.respValue
        result.query := cache.io.queryBusVec(portIdx).resp.queryKey
        result.found := cache.io.queryBusVec(portIdx).resp.found
        result
      }
  }
}

class PdInternalAddrCache(depth: Int) extends Component {
  val io = new Bundle {
    val addrCreateOrDelete = slave(PdAddrDataCreateOrDeleteBus())
    val query = slave(PdAddrCacheReadBus())
    val full = out(Bool())
  }

  val addrCacheMem = Mem(CachedValue(AddrData()), depth).init(List.fill(depth) {
    val result = CachedValue(AddrData())
    result.valid := False
    result.data.init()
    result
  })

  val addrCreateOrDelete = new Area {
    val isAddrDataCreation =
      io.addrCreateOrDelete.req.createOrDelete === CRUD.CREATE
    val ramAvailable = Vec((0 until depth).map(idx => {
      val v = addrCacheMem.readAsync(
        address = U(idx, log2Up(depth) bits),
        // The read will get the new value (provided by the write)
        readUnderWrite = writeFirst
      )
      v.valid
    }))
    val foundRamAvailable = ramAvailable.orR
    io.full := !foundRamAvailable
    val addrDataCreateIdxOH = OHMasking.first(ramAvailable)

    val isAddrDataDeletion =
      io.addrCreateOrDelete.req.createOrDelete === CRUD.DELETE
    val addrDataDeleteIdxOH = Vec((0 until depth).map(idx => {
      val deleteReq = io.addrCreateOrDelete.req
      val v = addrCacheMem.readAsync(
        address = U(idx, log2Up(depth) bits),
        // The read will get the new value (provided by the write)
        readUnderWrite = writeFirst
      )
      deleteReq.addrData.lkey === v.data.lkey &&
      deleteReq.addrData.rkey === v.data.rkey && v.valid
    }))
    val foundAddrDataDelete = addrDataDeleteIdxOH.orR

    val addrDataSelIdx = OHToUInt(
      isAddrDataCreation ? addrDataCreateIdxOH | addrDataDeleteIdxOH
    )
    when(io.addrCreateOrDelete.req.valid) {
      when(isAddrDataCreation && foundRamAvailable) {
        addrCacheMem(addrDataSelIdx).valid := io.addrCreateOrDelete.req.fire
      } elsewhen (isAddrDataDeletion && foundAddrDataDelete) {
        addrCacheMem(addrDataSelIdx).valid := !io.addrCreateOrDelete.req.fire
      }
    }
    io.addrCreateOrDelete.resp <-/< io.addrCreateOrDelete.req.translateWith {
      val result = cloneOf(io.addrCreateOrDelete.resp.payloadType)
      result.successOrFailure := (isAddrDataCreation && foundRamAvailable) || (isAddrDataDeletion && foundAddrDataDelete)
      result
    }
  }

  val search = new Area {
    val (queryReq4Queue, queryReq4Cache) = StreamFork2(io.query.req)
    val reqQueue = StreamFifoLowLatency(
      dataType = io.query.req.payloadType(),
      depth = ADDR_CACHE_QUERY_DELAY_CYCLE
    )
    reqQueue.io.push << queryReq4Queue

    val idxOH = Vec((0 until depth).map(idx => {
      val queryReq = queryReq4Cache
      val v = addrCacheMem.readAsync(
        address = U(idx, log2Up(depth) bits),
        // The read will get the new value (provided by the write)
        readUnderWrite = writeFirst
      )
      (queryReq.key === v.data.lkey && !queryReq.remoteOrLocalKey) ||
      (queryReq.key === v.data.rkey && queryReq.remoteOrLocalKey) && v.valid
    }))
    val found = idxOH.asBits.andR
    val idxBinary = OHToUInt(idxOH)
    val ramIdxStream = queryReq4Cache.translateWith(idxBinary)
    // TODO: check the timing of RAM search
    // ramIdxStream cannot be staged, to prevent RAM write change to invalidate the idxOH
    val queryResultStream = addrCacheMem
      .streamReadSync(
        ramIdxStream,
        linkedData = TupleBundle(queryReq4Cache.key, found)
      )

    val joinStream = StreamJoin(reqQueue.io.pop, queryResultStream)
    val (originalReq, cacheResp) = (joinStream._1, joinStream._2.value.data)
    val reqAddrWithBoundary =
      cacheResp.va <= originalReq.va && originalReq.va <= cacheResp.va + cacheResp.dataLenBytes
    val reqDataSizeValid =
      originalReq.va + originalReq.dataLenBytes <= cacheResp.va + cacheResp.dataLenBytes
    val pa = UInt(MEM_ADDR_WIDTH bits)
    when(reqAddrWithBoundary) {
      pa := cacheResp.pa + originalReq.va - cacheResp.va
    } otherwise {
      pa := 0 // Invalid
    }
    val accessValid =
      (originalReq.accessType.asBits | cacheResp.accessType.asBits).orR
    io.query.resp << joinStream
      .translateWith {
        val result = cloneOf(io.query.resp.payloadType)
        result.initiator := originalReq.initiator
        result.sqpn := originalReq.sqpn
        result.psn := originalReq.psn
        result.pa := pa
        result.accessValid := accessValid
        result.sizeValid := reqAddrWithBoundary && reqDataSizeValid
        result.keyValid := joinStream._2.linked._2 // Found
        result
      }
  }
}

// When allocate MR, it needs to update AddrCache with PD, MR key, physical/virtual address, size;
// TODO: handle zero DMA length key check
class QpAddrCacheAgent extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rqCacheRead = slave(QpAddrCacheAgentReadBus())
    val sqReqCacheRead = slave(QpAddrCacheAgentReadBus())
    val sqRespCacheRead = slave(QpAddrCacheAgentReadBus())
    val pdAddrCacheQuery = master(PdAddrCacheReadBus())
  }

  io.pdAddrCacheQuery.req <-/< StreamArbiterFactory.roundRobin.transactionLock
    .on(
      Vec(
        io.rqCacheRead.req.translateWith {
          val result = cloneOf(io.pdAddrCacheQuery.req.payloadType)
//      result.pdId := io.qpAttr.pdId
          result.initiator := AddrQueryInitiator.RQ
          result.assignSomeByName(io.rqCacheRead.req.payload)
          result
        },
        io.sqReqCacheRead.req.translateWith {
          val result = cloneOf(io.pdAddrCacheQuery.req.payloadType)
//      result.pdId := io.qpAttr.pdId
          result.initiator := AddrQueryInitiator.SQ_REQ
          result.assignSomeByName(io.sqReqCacheRead.req.payload)
          result
        },
        io.sqRespCacheRead.req.translateWith {
          val result = cloneOf(io.pdAddrCacheQuery.req.payloadType)
//      result.pdId := io.qpAttr.pdId
          result.initiator := AddrQueryInitiator.SQ_RESP
          result.assignSomeByName(io.sqRespCacheRead.req.payload)
          result
        }
      )
    )

  val txSel = UInt(2 bits)
  val (rqIdx, sqReqIdx, sqRespIdx) = (0, 1, 2)
  switch(io.pdAddrCacheQuery.resp.initiator) {
    is(AddrQueryInitiator.RQ) {
      txSel := rqIdx
    }
    is(AddrQueryInitiator.SQ_REQ) {
      txSel := sqReqIdx
    }
    is(AddrQueryInitiator.SQ_RESP) {
      txSel := sqRespIdx
    }
//    default {
//      report(
//        message =
//          L"${REPORT_TIME} time: unknown AddrQueryInitiator=${io.pdAddrCacheQuery.resp.initiator}",
//        severity = FAILURE
//      )
//      txSel := otherIdx
//    }
  }
  Vec(
    io.rqCacheRead.resp,
    io.sqReqCacheRead.resp,
    io.sqRespCacheRead.resp
  ) <-/< StreamDemux(
    io.pdAddrCacheQuery.resp.translateWith {
      val result = cloneOf(io.rqCacheRead.resp.payloadType)
      result.assignSomeByName(io.pdAddrCacheQuery.resp.payload)
//      result.sqpn := io.pdAddrCacheQuery.resp.sqpn
//      result.psn := io.pdAddrCacheQuery.resp.psn
//      result.keyValid := io.pdAddrCacheQuery.resp.keyValid
//      result.sizeValid := io.pdAddrCacheQuery.resp.sizeValid
//      result.accessValid := io.pdAddrCacheQuery.resp.accessValid
//      result.pa := io.pdAddrCacheQuery.resp.pa
      result
    },
    select = txSel,
    portCount = 3
  )

  // TODO: check MR size and permission
  //
  // pp. 264, spec 1.4
  // A responder that supports RDMA and / or ATOMIC Operations shall verify
  // the R_Key, the associated access rights, and the specified virtual address.
  // The responder must also perform bounds checking (i.e. verify that
  // the length of the data being referenced does not cross the associated
  // memory start and end addresses). Any violation must result in the packet
  // being discarded and for reliable services, the generation of a NAK.
}
