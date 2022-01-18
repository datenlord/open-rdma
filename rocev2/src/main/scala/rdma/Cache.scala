package rdma

import spinal.core._
import spinal.lib._
// import ConstantSettings._
// import RdmaConstants._

case class QueryCacheResp[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv]
) extends Bundle {
  val key = keyType()
  val value = valueType()
  val found = Bool()
}

case class QueryCacheBus[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv]
) extends Bundle
    with IMasterSlave {
  val req = Stream(keyType())
  val resp = Stream(QueryCacheResp(keyType, valueType))

//  def >>(that: QueryCacheBus[Tk, Tv]): Unit = {
//    this.req >> that.req
//    this.resp << that.resp
//  }
//
//  def <<(that: QueryCacheBus[Tk, Tv]): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class QueryCacheScanBus[Tv <: Data](valueType: HardType[Tv], depth: Int)
    extends Bundle
    with IMasterSlave {
  val scanPtr = UInt(log2Up(depth) bits)
  val popPtr = UInt(log2Up(depth) bits)
  val pushPtr = UInt(log2Up(depth) bits)
  val empty = Bool()
  val value = valueType()

  def >>(that: QueryCacheScanBus[Tv]): Unit = {
    that.scanPtr := this.scanPtr
    this.popPtr := that.popPtr
    this.pushPtr := that.pushPtr
    this.value := that.value
    this.empty := that.empty
  }

  def <<(that: QueryCacheScanBus[Tv]): Unit = that >> this

  override def asMaster(): Unit = {
    out(scanPtr)
    in(value, popPtr, pushPtr, empty)
  }
}

class QueryCache[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv],
    queryFunc: (Tk, Tv) => Bool,
    // initialValue: Tv,
    depth: Int,
    portCount: Int,
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
    val queryBusVec = Vec(slave(QueryCacheBus(keyType, valueType)), portCount)
    val scanBus = slave(QueryCacheScanBus(valueType, depth))
  }

  // Copied code from StreamFifoLowLatency
  val fifo = new Area {
    val logic = new Area {

      val ram = Mem(valueType, depth)
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
        io.pop.payload := ram.readAsync(
          popPtr.value,
          readUnderWrite = writeFirst
        )
      } otherwise {
        io.pop.valid := io.push.valid
        io.pop.payload := io.push.payload
      }

      when(pushing =/= popping) {
        risingOccupancy := pushing
      }
      when(pushing) {
        ram(pushPtr.value) := io.push.payload
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

    val scan = supportScan generate new Area {
      io.scanBus.popPtr := logic.popPtr
      io.scanBus.pushPtr := logic.pushPtr
      io.scanBus.empty := logic.empty
      io.scanBus.value := logic.ram.readAsync(io.scanBus.scanPtr)
    }
  }

  def search(queryReq: Stream[TupleBundle2[Tk, UInt]]) =
    new Composite(queryReq) {
      val queryKey = queryReq._1
      val queryIdx = queryReq._2
      val idxOH = Vec((0 until depth).map(idx => {
        val v = fifo.logic.ram.readAsync(
          address = U(idx, log2Up(depth) bits),
          // The read will get the new value (provided by the write)
          readUnderWrite = writeFirst
        )
        queryFunc(queryKey, v)
      }))
      val found = idxOH.asBits.andR
      val idxBinary = OHToUInt(idxOH)
      val ramIdxStream = queryReq.translateWith(idxBinary)
      // TODO: check the timing of PSN search in FIFO
      // ramIdxStream cannot be staged, to prevent FIFO write change to void the idxOH
      val queryResultStream = fifo.logic.ram
        .streamReadSync(
          ramIdxStream,
          linkedData = TupleBundle(queryIdx, queryKey, found)
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
      val rslt = QueryCacheResp(keyType, valueType)
      rslt.value := queryResultStream.payload.value
      rslt.key := queryResultStream.payload.linked._2
      rslt.found := queryResultStream.payload.linked._3
      rslt
    }
  } else { // More than 2 query port, need arbitration
    val queryReqVec =
      for ((queryBus, idx) <- io.queryBusVec.zipWithIndex)
        yield {
          val queryIdx = U(idx, log2Up(portCount) bits)
          queryBus.req.translateWith(
            TupleBundle(queryBus.req.payload, queryIdx)
          )
        }

    val queryArbitrated = StreamArbiterFactory.roundRobin.on(queryReqVec)
    val queryResp = search(queryArbitrated)
    val queryRespVec = StreamDemux(
      queryResp,
      select = queryResp.linked._1,
      portCount = portCount
    )
    for (idx <- 0 until portCount) {
      io.queryBusVec(idx).resp.arbitrationFrom(queryRespVec(idx))
      io.queryBusVec(idx).resp.value := queryRespVec(idx).value
      io.queryBusVec(idx).resp.key := queryRespVec(idx).linked._2
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
class ReadAtomicResultCache(depth: Int) extends Component {
  val io = new Bundle {
    val push = slave(Stream(ReadAtomicResultCacheData()))
    val pop = master(Stream(ReadAtomicResultCacheData()))
    val occupancy = out(UInt(log2Up(depth + 1) bits))
    val flush = in(Bool())
    val queryPort4DmaReadResp = slave(ReadAtomicResultCacheQueryBus())
    val queryPort4DupReq = slave(ReadAtomicResultCacheQueryBus())
    val queryPort4DupReqDmaRead = slave(ReadAtomicResultCacheQueryBus())
  }

  val cache = new QueryCache(
    ReadAtomicResultCacheReq(),
    ReadAtomicResultCacheData(),
    queryFunc = (k: ReadAtomicResultCacheReq, v: ReadAtomicResultCacheData) =>
      v.psnStart <= k.psn && k.psn < (v.psnStart + v.pktNum),
//    initialValue = ReadAtomicResultCacheData().setDefaultVal(),
    depth = depth,
    portCount = 3,
    supportScan = false
  )
  cache.io.push << io.push
  io.pop << cache.io.pop
  io.occupancy := cache.io.occupancy
  cache.io.flush := io.flush

  val queryPortVec = Vec(
    io.queryPort4DmaReadResp,
    io.queryPort4DupReq,
    io.queryPort4DupReqDmaRead
  )
  for ((queryPort, portIdx) <- queryPortVec.zipWithIndex) {
    cache.io.queryBusVec(portIdx).req << queryPort.req
    queryPort.resp << cache.io
      .queryBusVec(portIdx)
      .resp
      .translateWith {
        val rslt = cloneOf(queryPort.resp.payloadType)
        rslt.cachedData := cache.io.queryBusVec(portIdx).resp.value
        rslt.query := cache.io.queryBusVec(portIdx).resp.key
        rslt.found := cache.io.queryBusVec(portIdx).resp.found
        rslt
      }
  }
//  cache.io.queryBusVec(0).req << io.queryPort4DmaReadResp.req
//  io.queryPort4DmaReadResp.resp << cache.io.queryBusVec(0).resp.translateWith {
//    val rslt = ReadAtomicResultCacheResp()
//    rslt.cachedData := cache.io.queryBusVec(0).resp.value
//    rslt.query := cache.io.queryBusVec(0).resp.key
//    rslt.found := cache.io.queryBusVec(0).resp.found
//    rslt
//  }
//  cache.io.queryBusVec(1).req << io.queryPort4DupReq.req
//  io.queryPort4DupReq.resp << cache.io.queryBusVec(1).resp.translateWith {
//    val rslt = ReadAtomicResultCacheResp()
//    rslt.cachedData := cache.io.queryBusVec(1).resp.value
//    rslt.query := cache.io.queryBusVec(1).resp.key
//    rslt.found := cache.io.queryBusVec(1).resp.found
//    rslt
//  }
//  cache.io.queryBusVec(2).req << io.queryPort4DupReqDmaRead.req
//  io.queryPort4DupReqDmaRead.resp << cache.io
//    .queryBusVec(2)
//    .resp
//    .translateWith {
//      val rslt = ReadAtomicResultCacheResp()
//      rslt.cachedData := cache.io.queryBusVec(2).resp.value
//      rslt.query := cache.io.queryBusVec(2).resp.key
//      rslt.found := cache.io.queryBusVec(2).resp.found
//      rslt
//    }
}

class WorkReqCache(depth: Int) extends Component {
  val io = new Bundle {
    val push = slave(Stream(CachedWorkReq()))
    val pop = master(Stream(CachedWorkReq()))
//    val flush = in(Bool())
    val occupancy = out(UInt(log2Up(depth + 1) bits))
    val empty = out(Bool())
    val scanBus = slave(QueryCacheScanBus(CachedWorkReq(), depth))
//    val retryStartPulse = in(Bool())
//    val retrying = out(Bool())
//    val retryPop = master(Stream(CachedWorkReq()))
    val queryPort4SqReqDmaRead = slave(WorkReqCacheQueryBus())
    val queryPort4SqRespDmaWrite = slave(WorkReqCacheQueryBus())
    val queryPort4DupReqDmaRead = slave(WorkReqCacheQueryBus())
  }

  val cache = new QueryCache(
    WorkReqCacheReq(),
    CachedWorkReq(),
    queryFunc = (k: WorkReqCacheReq, v: CachedWorkReq) =>
      // TODO: psnComp(v.psnStart, k.psn)
      v.psnStart <= k.psn && k.psn < (v.psnStart + v.pktNum),
//    initialValue = CachedWorkReq().setInitVal(),
    depth = depth,
    portCount = 3,
    supportScan = true
  )
  cache.io.push << io.push
  io.pop << cache.io.pop
  // WR cache flush is handled by RespHandler, cannot directly be flushed
  cache.io.flush := False
  io.occupancy := cache.io.occupancy
  io.empty := cache.io.empty
  cache.io.scanBus << io.scanBus

  val queryPortVec = Vec(
    io.queryPort4SqReqDmaRead,
    io.queryPort4SqRespDmaWrite,
    io.queryPort4DupReqDmaRead
  )
  for ((queryPort, portIdx) <- queryPortVec.zipWithIndex) {
    cache.io.queryBusVec(portIdx).req << queryPort.req
    queryPort.resp << cache.io
      .queryBusVec(portIdx)
      .resp
      .translateWith {
        val rslt = cloneOf(queryPort.resp.payloadType)
        rslt.cachedWorkReq := cache.io.queryBusVec(portIdx).resp.value
        rslt.query := cache.io.queryBusVec(portIdx).resp.key
        rslt.found := cache.io.queryBusVec(portIdx).resp.found
        rslt
      }
  }
//  cache.io.queryBusVec(0).req << io.queryPort4SqReqDmaRead.req
//  io.queryPort4SqReqDmaRead.resp << cache.io
//    .queryBusVec(0)
//    .resp
//    .translateWith {
//      val rslt = WorkReqCacheResp()
//      rslt.cachedWorkReq := cache.io.queryBusVec(0).resp.value
//      rslt.query := cache.io.queryBusVec(0).resp.key
//      rslt.found := cache.io.queryBusVec(0).resp.found
//      rslt
//    }
//  cache.io.queryBusVec(1).req << io.queryPort4DupReq.req
//  io.queryPort4DupReq.resp << cache.io.queryBusVec(1).resp.translateWith {
//    val rslt = WorkReqCacheResp()
//    rslt.cachedWorkReq := cache.io.queryBusVec(1).resp.value
//    rslt.query := cache.io.queryBusVec(1).resp.key
//    rslt.found := cache.io.queryBusVec(1).resp.found
//    rslt
//  }
//  cache.io.queryBusVec(1).req << io.queryPort4SqRespDmaWrite.req
//  io.queryPort4SqRespDmaWrite.resp << cache.io
//    .queryBusVec(1)
//    .resp
//    .translateWith {
//      val rslt = WorkReqCacheResp()
//      rslt.cachedWorkReq := cache.io.queryBusVec(1).resp.value
//      rslt.query := cache.io.queryBusVec(1).resp.key
//      rslt.found := cache.io.queryBusVec(1).resp.found
//      rslt
//    }
//  cache.io.queryBusVec(2).req << io.queryPort4DupReqDmaRead.req
//  io.queryPort4DupReqDmaRead.resp << cache.io
//    .queryBusVec(2)
//    .resp
//    .translateWith {
//      val rslt = WorkReqCacheResp()
//      rslt.cachedWorkReq := cache.io.queryBusVec(2).resp.value
//      rslt.query := cache.io.queryBusVec(2).resp.key
//      rslt.found := cache.io.queryBusVec(2).resp.found
//      rslt
//    }
}

// When allocate MR, it needs to update AddrCache with PD, MR key, physical/virtual address, size;
// TODO: change AddrCache to be not per QP structure, better be per PD structure
// TODO: handle zero DMA length key check
class AddrCache extends Component {
  val io = new Bundle {
    val rqCacheRead = slave(AddrCacheReadBus())
    val sqReqCacheRead = slave(AddrCacheReadBus())
    val sqRespCacheRead = slave(AddrCacheReadBus())
//    val retryCacheRead = slave(AddrCacheReadBus())
    // TODO: AddrCache content input
  }

  // TODO: implementation
  io.rqCacheRead.resp <-/< io.rqCacheRead.req
    .translateWith {
      AddrCacheReadResp().setDefaultVal()
    }
//  io.rqCacheRead.sendWrite.resp <-/< io.rqCacheRead.sendWrite.req
//    .translateWith {
//      AddrCacheReadResp().setDefaultVal()
//    }
//  io.rqCacheRead.write.resp <-/< io.rqCacheRead.write.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }
//  io.rqCacheRead.read.resp <-/< io.rqCacheRead.read.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }
//  io.rqCacheRead.atomic.resp <-/< io.rqCacheRead.atomic.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }

  io.sqReqCacheRead.resp <-/< io.sqReqCacheRead.req.translateWith {
    AddrCacheReadResp().setDefaultVal()
  }
  io.sqRespCacheRead.resp <-/< io.sqRespCacheRead.req.translateWith {
    AddrCacheReadResp().setDefaultVal()
  }
//  io.sqCacheRead.send.resp <-/< io.sqCacheRead.send.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }
//  io.sqCacheRead.write.resp <-/< io.sqCacheRead.write.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }

//  io.respCacheRead.resp <-/< io.respCacheRead.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }
//
//  io.retryCacheRead.resp <-/< io.retryCacheRead.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }
//  io.retryCacheRead.send.resp <-/< io.retryCacheRead.send.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }
//  io.retryCacheRead.write.resp <-/< io.retryCacheRead.write.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }

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

//class SqPktCache(depth: Int) extends Component {
//  val io = new Bundle {
//    val push = slave(Stream(SqPktCacheData()))
//    val pop = master(Stream(SqPktCacheData()))
//    val occupancy = out(UInt(log2Up(depth + 1) bits))
//    val flush = in(Bool())
//    val queryPort4Resp = slave(SqPktCacheQueryBus())
//    val queryPort4Retry = slave(SqPktCacheQueryBus())
//  }
//
//  val cache = new QueryCache(
//    SqPktCacheQueryReq(),
//    SqPktCacheData(),
//    queryFunc = (k: SqPktCacheQueryReq, v: SqPktCacheData) => v.psn === k.psn,
//    initialValue = SqPktCacheData().setDefaultVal(),
//    depth = depth,
//    portCount = 2
//  )
//  cache.io.push << io.push
//  io.pop << cache.io.pop
//  io.occupancy := cache.io.occupancy
//  cache.io.flush := io.flush
//  cache.io.queryBusVec(0).req << io.queryPort4Resp.req
//  io.queryPort4Resp.resp << cache.io.queryBusVec(0).resp.translateWith {
//    val rslt = SqPktCacheQueryResp()
//    rslt.cachedPkt := cache.io.queryBusVec(0).resp.value
//    rslt.query := cache.io.queryBusVec(0).resp.key
//    rslt.found := cache.io.queryBusVec(0).resp.found
//    rslt
//  }
//
//  cache.io.queryBusVec(1).req << io.queryPort4Retry.req
//  io.queryPort4Retry.resp << cache.io.queryBusVec(1).resp.translateWith {
//    val rslt = SqPktCacheQueryResp()
//    rslt.cachedPkt := cache.io.queryBusVec(1).resp.value
//    rslt.query := cache.io.queryBusVec(1).resp.key
//    rslt.found := cache.io.queryBusVec(1).resp.found
//    rslt
//  }
//}
