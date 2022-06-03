package rdma

import spinal.core._
import spinal.core.formal._
import spinal.lib._

import scala.language.postfixOps
//import ConstantSettings._
import RdmaConstants._
//import StreamVec._

case class CamQueryResp[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv]
) extends Bundle {
  val queryKey = keyType()
  val respValue = valueType()
  val found = Bool()
}

case class CamQueryBus[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv]
) extends Bundle
    with IMasterSlave {
  val req = Stream(keyType())
  val resp = Stream(CamQueryResp(keyType, valueType))

  def >>(that: CamQueryBus[Tk, Tv]): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: CamQueryBus[Tk, Tv]): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class RamScanCtrlBus() extends Bundle with IMasterSlave {
  val startPulse = Bool()
  val donePulse = Bool()
  val retryReason = RetryReason()
  val retryStartPsn = UInt(PSN_WIDTH bits)

  def >>(that: RamScanCtrlBus): Unit = {
    that.startPulse := this.startPulse
    this.donePulse := that.donePulse
    that.retryReason := this.retryReason
    that.retryStartPsn := this.retryStartPsn
  }

  def <<(that: RamScanCtrlBus): Unit = that >> this

  override def asMaster(): Unit = {
    out(startPulse, retryReason, retryStartPsn)
    in(donePulse)
  }
}

case class CachedValue[T <: Data](dataType: HardType[T]) extends Bundle {
  val valid = Bool()
  val data = dataType()

  def init(initData: T): this.type = {
    valid := False
    data := initData
    this
  }
}

class Fifo[T <: Data](valueType: HardType[T], initDataVal: => T, depth: Int)
    extends Component {
  require(depth >= 4, s"Fifo minimum depth=${depth} is 4")
  require(isPow2(depth), s"Fifo depth=${depth} must be power of 2")
  val depthWidth = log2Up(depth)
  val ptrWidth = depthWidth + 1
  val twiceDepth = depth << 1

  val io = new Bundle {
    val flush = in(Bool())
    val push = slave(Stream(valueType))
    val pop = master(Stream(valueType))
    val empty = out(Bool())
    val full = out(Bool())
    val occupancy = out(UInt(ptrWidth bits))
    val pushPtr = out(UInt(ptrWidth bits))
    val popPtr = out(UInt(ptrWidth bits))
    val pushing = out(Bool())
    val popping = out(Bool())
//    val availability = out(UInt(log2Up(depth + 1) bits))
    val ram = out(Vec(valueType(), depth))
  }

  val ram = Vec(RegInit(initDataVal), depth)
//  val ram = Mem(valueType, depth) init(Seq.fill(depth)(initDataVal))
  val pushPtr = Counter(ptrWidth bits)
  val popPtr = Counter(ptrWidth bits)
  val ptrMatch = pushPtr === popPtr
  val empty = ptrMatch
  val full = pushPtr.msb =/= popPtr.msb &&
    pushPtr(ptrWidth - 2 downto 0) ===
    popPtr(ptrWidth - 2 downto 0)

  when(io.flush) {
    pushPtr.clear()
    popPtr.clear()
    for (idx <- 0 until depth) {
      ram(idx) := initDataVal
    }
  }

  val pushing = io.push.fire
  val popping = io.pop.fire

  io.push.ready := !io.full
  io.empty := empty
  io.full := full
  io.pushPtr := pushPtr.value
  io.popPtr := popPtr.value
  io.pushing := pushing
  io.popping := popping
  io.ram := ram

//  when(io.full) {
  assert(
    assertion = io.push.ready =/= io.full,
    message =
      L"${REPORT_TIME} time: io.push.ready=${io.push.ready} should =/= io.full=${io.full}".toSeq,
    severity = FAILURE
  )
//  }
//  when(io.empty) {
  assert(
    assertion = io.pop.valid =/= (io.empty && !io.push.valid),
    message =
      L"${REPORT_TIME} time: io.pop.valid=${io.pop.valid} should =/= (io.empty=${io.empty} and !(io.push.valid=${io.push.valid}))".toSeq,
    severity = FAILURE
  )
//  }

//  io.pop.valid := !io.empty
//  val popIdx = popPtr.value.resize(depthWidth)
//  io.pop.payload := ram(popIdx)
  when(!empty) {
    io.pop.valid := True
    val idx = popPtr.value.resize(depthWidth)
    io.pop.payload := ram(idx)
  } otherwise {
    io.pop.valid := io.push.valid
    io.pop.payload := io.push.payload
  }

  when(pushing) {
    val idx = pushPtr.value.resize(depthWidth)
    ram(idx) := io.push.payload
    pushPtr.increment()
  }
  when(popping) {
    popPtr.increment()
  }

  io.occupancy := ((twiceDepth + pushPtr.value) - popPtr.value).resize(ptrWidth)
}

case class RamScanOut[T <: Data](valueType: HardType[T]) extends Bundle {
  val scanOutData = valueType()
  val rnrCnt = UInt(RETRY_COUNT_WIDTH bits)
  val retryCnt = UInt(RETRY_COUNT_WIDTH bits)
}

class RamScan[T <: Data](valueType: HardType[T], depth: Int) extends Component {
  require(depth >= 4, s"RamScan minimum depth=${depth} is 4")
  require(isPow2(depth), s"RamScan depth=${depth} must be power of 2")
  val depthWidth = log2Up(depth)
  val ptrWidth = depthWidth + 1
  val twiceDepth = depth << 1

  val io = new Bundle {
    val flush = in(Bool())
    val pushPtr = in(UInt(ptrWidth bits))
    val popPtr = in(UInt(ptrWidth bits))
    val pushing = in(Bool())
    val popping = in(Bool())
    val ram = in(Vec(valueType(), depth))
    val scanCtrlBus = slave(RamScanCtrlBus())
    val scanOut = master(Stream(RamScanOut(valueType)))
  }

  val scanPtr = Counter(depthWidth bits)
  val rnrCntLst = Seq.fill(depth)(Counter(RETRY_COUNT_WIDTH bits))
  val retryCntLst = Seq.fill(depth)(Counter(RETRY_COUNT_WIDTH bits))

  when(io.flush) {
    scanPtr.clear()
    for (idx <- 0 until depth) {
      rnrCntLst(idx).clear()
      retryCntLst(idx).clear()
    }
  }

  val (scanReqStream, scanRunning, scanDone) = StreamCounterSource(
    startPulse = io.scanCtrlBus.startPulse,
    startValue = io.popPtr,
    stopValue = io.pushPtr,
    flush = io.flush,
    stateCount = twiceDepth
  )
  io.scanCtrlBus.donePulse := scanDone

  when(scanRunning) {
    assert(
      assertion = stable(io.pushPtr) && !io.pushing,
      message = L"${REPORT_TIME} time: during scan, no push to FIFO".toSeq,
      severity = FAILURE
    )
  }
  when(io.pushing) {
    switch(io.pushPtr.resize(depthWidth)) {
      for (idx <- 0 until depth) {
        is(idx) {
          rnrCntLst(idx).increment()
          retryCntLst(idx).increment()
//          val pushIdx = U(idx, depthWidth bits)
//          val rnrCntNextVal = rnrCntLst(idx).valueNext
//          val retryCntNextVal = retryCntLst(idx).valueNext
//          report(
//            message = L"${REPORT_TIME} time: pushIdx=${pushIdx}, rnrCntNextVal=${rnrCntNextVal}, retryCntNextVal=${retryCntNextVal}, io.pushPtr=${io.pushPtr}"
//          )
        }
      }
    }
  }
  when(io.popping) {
    switch(io.popPtr.resize(depthWidth)) {
      for (idx <- 0 until depth) {
        is(idx) {
          rnrCntLst(idx).clear()
          retryCntLst(idx).clear()
//          val popIdx = U(idx, depthWidth bits)
//          val rnrCntNextVal = rnrCntLst(idx).valueNext
//          val retryCntNextVal = retryCntLst(idx).valueNext
//          report(
//            message = L"${REPORT_TIME} time: popIdx=${popIdx}, rnrCntNextVal=${rnrCntNextVal}, retryCntNextVal=${retryCntNextVal}, io.popPtr=${io.popPtr}"
//          )
        }
      }
    }
  }

  when(scanReqStream.fire) {
    switch(scanReqStream.payload.resize(depthWidth)) {
      for (idx <- 0 until depth) {
        is(idx) {
          when(io.scanCtrlBus.retryReason === RetryReason.RNR) {
            rnrCntLst(idx).increment()
          } otherwise {
            retryCntLst(idx).increment()
          }
        }
      }
    }
  }

  io.scanOut <-/< scanReqStream.translateWith {
    val result = cloneOf(io.scanOut.payloadType)
    switch(scanReqStream.payload.resize(depthWidth)) {
      for (idx <- 0 until depth) {
        is(idx) {
          result.scanOutData := io.ram(idx)
          result.rnrCnt := rnrCntLst(idx).value
          result.retryCnt := retryCntLst(idx).value
        }
      }
//      default {
//        report(
//          message = L"ram scan index=${scanReqStream.payload} out of range(0, depth=${U(depth)})",
//          severity = FAILURE
//        )
//        result.assignDontCare()
//      }
    }
    result
  }
}

class Cam[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv],
    queryFunc: (Tk, Tv) => Bool,
    depth: Int,
    portCount: Int
) extends Component {
  require(isPow2(depth), s"Cam depth=${depth} must be power of 2")
  val depthWidth = log2Up(depth)

  val io = new Bundle {
    val ram = in(Vec(valueType(), depth))
    val queryBusVec = Vec(slave(CamQueryBus(keyType, valueType)), portCount)
  }

  def search(queryKey: Tk, queryValid: Bool, queryPortIdx: UInt) =
    new Composite(queryKey, "Cam_search") {
      val itemIdxOH = Vec((0 until depth).map(idx => {
        val v = io.ram(idx)
        val queryResult = queryFunc(queryKey, v)
        queryResult
      })).asBits
      val found = itemIdxOH.orR
      val itemIdxBinary = OHToUInt(itemIdxOH)
//      val idxOhLsb = OHMasking.first(idxOH)
//      val idxOhLsbBinary = OHToUInt(idxOhLsb)

      when(queryValid) {
        assert(
          assertion = CountOne(itemIdxOH) === found.asUInt,
          message =
            L"${REPORT_TIME} time: itemIdxOH=${itemIdxOH} is not one hot when found=${found}, itemIdxBinary=${itemIdxBinary}".toSeq,
          severity = ERROR
        )
      }
      val result = (itemIdxOH, queryPortIdx, found)
    }.result

  if (portCount == 1) {
    val busIdx = 0
    val queryPortIdx = U(busIdx, portCount bits)
    val queryResult = search(
      io.queryBusVec(busIdx).req.payload,
      io.queryBusVec(busIdx).req.valid,
      queryPortIdx
    )
    io.queryBusVec(busIdx).resp <-/< io.queryBusVec(busIdx).req.translateWith {
      val result = cloneOf(io.queryBusVec(busIdx).resp)
      val (idxOH, _, found) = queryResult
      result.respValue := io.ram.oneHotAccess(idxOH)
      result.queryKey := io.queryBusVec(busIdx).req.payload
      result.found := found
      result
    }
  } else { // More than 2 query port, need arbitration
    val queryIdxWidth = log2Up(portCount)
    val queryReqVec =
      for ((queryBus, idx) <- io.queryBusVec.zipWithIndex)
        yield {
          val queryPortIdx = U(idx, queryIdxWidth bits)
          queryBus.req.translateWith(
            TupleBundle(queryBus.req.payload, queryPortIdx)
          )
        }

    val queryStreamSelected =
      StreamArbiterFactory().roundRobin.transactionLock.on(queryReqVec)
    val queryStreamPiped = cloneOf(queryStreamSelected)
    queryStreamPiped <-/< queryStreamSelected

    val queryResult = search(
      queryKey = queryStreamPiped._1,
      queryValid = queryStreamPiped.valid,
      queryPortIdx = queryStreamPiped._2
    )
    val (idxOH, queryPortIdx, found) = queryResult
    val respStream = queryStreamPiped.translateWith {
      val queryResp = cloneOf(io.queryBusVec(0).resp.payloadType)
      queryResp.respValue := io.ram.oneHotAccess(idxOH)
      queryResp.queryKey := queryStreamPiped._1
      queryResp.found := found

      val result = TupleBundle(queryResp, queryPortIdx)
      result
    }

    val respStreamPiped = cloneOf(respStream)
    respStreamPiped <-/< respStream
    val queryRespStreamVec = StreamDemux(
      respStreamPiped.map(_._1),
      select = respStreamPiped._2,
      portCount = portCount
    )

    for (idx <- 0 until portCount) {
      io.queryBusVec(idx).resp <-/< queryRespStreamVec(idx)
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
    val full = out(Bool())
    val empty = out(Bool())
//    val queryPort4DmaReadResp = slave(ReadAtomicRstCacheQueryBus())
    val queryPort4DupReq = slave(ReadAtomicRstCacheQueryBus())
//    val queryPort4DupReqDmaRead = slave(ReadAtomicRstCacheQueryBus())
  }

  val fifo = new Fifo(
    valueType = ReadAtomicRstCacheData(),
    initDataVal = ReadAtomicRstCacheData().setInitVal(),
    depth = depth
  )
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  io.full := fifo.io.full
  io.empty := fifo.io.empty
  io.occupancy := fifo.io.occupancy
  fifo.io.flush := io.flush

  val cam = new Cam(
    keyType = ReadAtomicRstCacheReq(),
    valueType = ReadAtomicRstCacheData(),
    queryFunc = (k: ReadAtomicRstCacheReq, v: ReadAtomicRstCacheData) =>
      new Composite(k, "ReadAtomicRstCache_queryFunc") {
        // TODO: check only one entry match or not
        val psnMatch = OpCode.isReadReqPkt(k.opcode) ? (
          PsnUtil.lte(v.psnStart, k.queryPsn, k.epsn) &&
            PsnUtil.lte(k.queryPsn, v.psnStart + v.pktNum, k.epsn)
        ) | True
        val result = k.opcode === v.opcode && k.rkey === v.rkey && psnMatch
      }.result,
    depth = depth,
    portCount = 1
  )
  cam.io.ram := fifo.io.ram

  val queryPortVec = Vec(
//    io.queryPort4DmaReadResp,
    io.queryPort4DupReq
//    io.queryPort4DupReqDmaRead
  )
  for ((queryPort, portIdx) <- queryPortVec.zipWithIndex) {
    cam.io.queryBusVec(portIdx).req << queryPort.req
    queryPort.resp << cam.io.queryBusVec(portIdx).resp
  }
}

class WorkReqCache(depth: Int) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val push = slave(Stream(CachedWorkReq()))
    val pop = master(Stream(CachedWorkReq()))
    val occupancy = out(UInt(log2Up(depth + 1) bits))
    val empty = out(Bool())
    val full = out(Bool())
    val retryScanCtrlBus = slave(RamScanCtrlBus())
    val retryWorkReq = master(Stream(RamScanOut(CachedWorkReq())))
//    val queryPort4SqReqDmaRead = slave(WorkReqCacheQueryBus())
//    val queryPort4SqRespDmaWrite = slave(WorkReqCacheQueryBus())
//    val queryPort4DupReqDmaRead = slave(WorkReqCacheQueryBus())
  }

  val fifo = new Fifo(
    valueType = CachedWorkReq(),
    initDataVal = CachedWorkReq().setInitVal(),
    depth = depth
  )
  val isInputReadWorkReq = WorkReqOpCode.isReadReq(fifo.io.push.workReq.opcode)
  val isInputAtomicWorkReq =
    WorkReqOpCode.isAtomicReq(fifo.io.push.workReq.opcode)
  val isOutputReadWorkReq = WorkReqOpCode.isReadReq(fifo.io.pop.workReq.opcode)
  val isOutputAtomicWorkReq =
    WorkReqOpCode.isAtomicReq(fifo.io.pop.workReq.opcode)
  val incReadAtomicWorkReqCnt =
    (isInputReadWorkReq || isInputAtomicWorkReq) && fifo.io.push.fire
  val decReadAtomicWorkReqCnt =
    (isOutputReadWorkReq || isOutputAtomicWorkReq) && fifo.io.pop.fire

  val readAtomicWorkReqCnt = Counter(widthOf(fifo.io.occupancy) bits)
  when(io.txQCtrl.wrongStateFlush) {
    readAtomicWorkReqCnt := 0
  } elsewhen (incReadAtomicWorkReqCnt && !decReadAtomicWorkReqCnt) {
    readAtomicWorkReqCnt.increment()
  } elsewhen (!incReadAtomicWorkReqCnt && decReadAtomicWorkReqCnt) {
    readAtomicWorkReqCnt.valueNext := readAtomicWorkReqCnt - 1
  }

  val readAtomicWorkReqFull =
    readAtomicWorkReqCnt >= io.qpAttr.getMaxPendingReadAtomicWorkReqNum()
  val workReqCacheFull =
    fifo.io.occupancy >= io.qpAttr.getMaxPendingWorkReqNum()
  assert(
    assertion = io.qpAttr.getMaxPendingReadAtomicWorkReqNum() > 0,
    message =
      L"${REPORT_TIME} time: io.qpAttr.getMaxPendingReadAtomicWorkReqNum()=${io.qpAttr
        .getMaxPendingReadAtomicWorkReqNum()} should > 0".toSeq,
    severity = FAILURE
  )
  assert(
    assertion = io.qpAttr.getMaxPendingWorkReqNum() > 0,
    message =
      L"${REPORT_TIME} time: io.qpAttr.getMaxPendingWorkReqNum()=${io.qpAttr
        .getMaxPendingWorkReqNum()} should > 0".toSeq,
    severity = FAILURE
  )

  fifo.io.push << io.push.haltWhen(workReqCacheFull || readAtomicWorkReqFull)
  io.pop << fifo.io.pop
  io.full := fifo.io.full || workReqCacheFull || readAtomicWorkReqFull
  io.empty := fifo.io.empty
  io.occupancy := fifo.io.occupancy
  fifo.io.flush := io.txQCtrl.wrongStateFlush

  val scan = new RamScan(
    valueType = CachedWorkReq(),
    depth = depth
  )
  scan.io.flush := io.txQCtrl.wrongStateFlush
  scan.io.pushPtr := fifo.io.pushPtr
  scan.io.popPtr := fifo.io.popPtr
  scan.io.pushing := fifo.io.pushing
  scan.io.popping := fifo.io.popping
  scan.io.ram := fifo.io.ram
  scan.io.scanCtrlBus << io.retryScanCtrlBus
  io.retryWorkReq << scan.io.scanOut

  // TODO: remove cam from WorkReqCache
  val cam = new Cam(
    WorkReqCacheQueryReq(),
    CachedWorkReq(),
    queryFunc = (k: WorkReqCacheQueryReq, v: CachedWorkReq) =>
      // TODO: verify PSN comparison correctness
//        k.workReqOpCode === v.workReq.opcode &&
      PsnUtil.lte(v.psnStart, k.queryPsn, k.npsn) &&
        PsnUtil.lt(k.queryPsn, v.psnStart + v.pktNum, k.npsn),
    depth = depth,
    portCount = 1
  )
  cam.io.ram := fifo.io.ram

  when(io.txQCtrl.retry) {
    assert(
      assertion = stable(fifo.io.pushPtr),
      message =
        L"${REPORT_TIME} time: during retry, no new WR can be added".toSeq,
      severity = FAILURE
    )
  }
//  when(io.empty) {
//    assert(
//      assertion = !io.queryPort4SqRespDmaWrite.req.valid,
//      message = L"when io.empty=${io.empty}, no query request allowed, but io.queryPort4SqRespDmaWrite.req.valid=${io.queryPort4SqRespDmaWrite.req.valid}, and io.queryPort4SqRespDmaWrite.req.queryPsn=${io.queryPort4SqRespDmaWrite.req.queryPsn}",
//      severity = FAILURE
//    )
//  }

//  val queryPortVec = Vec(
////    io.queryPort4SqReqDmaRead,
//    io.queryPort4SqRespDmaWrite
////    io.queryPort4DupReqDmaRead
//  )
//  for ((queryPort, portIdx) <- queryPortVec.zipWithIndex) {
//    cam.io.queryBusVec(portIdx).req << queryPort.req
//    queryPort.resp << cam.io.queryBusVec(portIdx).resp
//  }
}

// TODO: check MR size and permission
//
// pp. 264, spec 1.4
// A responder that supports RDMA and / or ATOMIC Operations shall verify
// the R_Key, the associated access rights, and the specified virtual address.
// The responder must also perform bounds checking (i.e. verify that
// the length of the data being referenced does not cross the associated
// memory start and end addresses). Any violation must result in the packet
// being discarded and for reliable services, the generation of a NAK.
class PdAddrCache(depth: Int) extends Component {
  val io = new Bundle {
    // TODO: enable flush
//    val flush = in(Bool())
    val addrCreateOrDelete = slave(PdAddrDataCreateOrDeleteBus())
    val query = slave(PdAddrCacheReadBus())
    val full = out(Bool())
    val empty = out(Bool())
  }

  // TODO: add initial values to Mem
  val addrCacheMem = Vec(
    RegInit(CachedValue(AddrData()).init(AddrData().init())),
    depth
  )

  val addrCreateOrDelete = new Area {
    val isAddrDataCreation =
      io.addrCreateOrDelete.req.createOrDelete === CRUD.CREATE
    val isAddrDataDeletion =
      io.addrCreateOrDelete.req.createOrDelete === CRUD.DELETE

    val ramAvailableOH = Vec((0 until depth).map(idx => {
//      val v = addrCacheMem.readAsync(
//        address = U(idx, log2Up(depth) bits),
//        // The read will get the new value (provided by the write)
//        readUnderWrite = writeFirst
//      )
      val v = addrCacheMem(idx)
      ~v.valid // False means available, True means taken
    })).asBits
    io.full := !(ramAvailableOH.orR) // && !deleting
    io.empty := ramAvailableOH.andR // && !inserting

    val foundRamAvailable = !io.full
    val addrDataCreateIdxOH = OHMasking.first(ramAvailableOH)

    val addrDataDeleteIdxOH = Vec((0 until depth).map(idx => {
      val deleteReq = io.addrCreateOrDelete.req
//      val v = addrCacheMem.readAsync(
//        address = U(idx, log2Up(depth) bits),
//        // The read will get the new value (provided by the write)
//        readUnderWrite = writeFirst
//      )
      val v = addrCacheMem(idx)
      deleteReq.addrData.lkey === v.data.lkey && deleteReq.addrData.rkey === v.data.rkey && v.valid
    })).asBits
    val foundAddrDataDelete = addrDataDeleteIdxOH.orR

    val addrDataSelIdx = OHToUInt(
      isAddrDataCreation ? addrDataCreateIdxOH | addrDataDeleteIdxOH
    )
    when(io.addrCreateOrDelete.req.valid) {
      when(isAddrDataCreation && foundRamAvailable) {
        addrCacheMem(addrDataSelIdx).data := io.addrCreateOrDelete.req.addrData
        addrCacheMem(addrDataSelIdx).valid := io.addrCreateOrDelete.req.fire
      } elsewhen (isAddrDataDeletion && foundAddrDataDelete) {
        addrCacheMem(addrDataSelIdx).valid := !io.addrCreateOrDelete.req.fire
      }
    }
    io.addrCreateOrDelete.resp <-/< io.addrCreateOrDelete.req.translateWith {
      val result = cloneOf(io.addrCreateOrDelete.resp.payloadType)
      result.isSuccess := (isAddrDataCreation && foundRamAvailable) || (isAddrDataDeletion && foundAddrDataDelete)
      result.createOrDelete := io.addrCreateOrDelete.req.createOrDelete
      result.addrData := io.addrCreateOrDelete.req.addrData
      result
    }
  }

  val search = new Area {
    val cam = new Cam(
      PdAddrCacheReadReq(),
      CachedValue(AddrData()),
      queryFunc = (k: PdAddrCacheReadReq, v: CachedValue[AddrData]) =>
        new Composite(k, "PdAddrCache_queryFunc") {
          val result = v.valid &&
            ((k.key === v.data.lkey && !k.remoteOrLocalKey) ||
              (k.key === v.data.rkey && k.remoteOrLocalKey))
        }.result,
      depth = depth,
      portCount = 1
    )
    cam.io.ram := addrCacheMem // Vec((0 until depth).map(idx => addrCacheMem(idx)))

//    when(cam.io.queryBusVec(0).resp.fire) {
//      val payloadData = cam.io.queryBusVec(0).resp.payload
//      val found = payloadData.found
//      val originalReq = payloadData.queryKey
//      val cacheResp = payloadData.respValue.data
//      val pa = cacheResp.pa
//      val reqSizeValid = cacheResp.va <= originalReq.va &&
//        (originalReq.va + originalReq.dataLenBytes <= cacheResp.va + cacheResp.dataLenBytes)
//      report(L"${REPORT_TIME} time: PSN=${originalReq.psn}, found=${found}, PA=${pa}, keyValid=${payloadData.found}, reqSizeValid=${reqSizeValid}")
//    }

    val queryPortVec = Vec(io.query)
    for ((queryPort, portIdx) <- queryPortVec.zipWithIndex) {
      cam.io.queryBusVec(portIdx).req << queryPort.req

      queryPort.resp << cam.io.queryBusVec(portIdx).resp.map { payloadData =>
        val originalReq = payloadData.queryKey
        val cacheResp = payloadData.respValue.data
        val reqSizeValid = cacheResp.va <= originalReq.va &&
          (originalReq.va + originalReq.dataLenBytes <= cacheResp.va + cacheResp.dataLenBytes)

//        report(
//          L"${REPORT_TIME} time: cacheResp.pa=${cacheResp.pa}, originalReq.va=${originalReq.va}, cacheResp.va=${cacheResp.va}, originalReq.dataLenBytes=${originalReq.dataLenBytes}, cacheResp.dataLenBytes=${cacheResp.dataLenBytes}"
//        )

        val pa = UInt(MEM_ADDR_WIDTH bits)
        pa := cacheResp.pa + (originalReq.va - cacheResp.va)

        val accessValid = cacheResp.accessType.permit(originalReq.accessType)

        val result = cloneOf(io.query.resp.payloadType)
        result.initiator := originalReq.initiator
        result.sqpn := originalReq.sqpn
        result.psn := originalReq.psn
        result.pa := pa
        result.accessValid := accessValid
        result.sizeValid := reqSizeValid
        result.keyValid := payloadData.found
        result
      }
    }
  }

//  when(io.flush) {
//    for (idx <- 0 until depth) {
//      addrCacheMem(idx).data := AddrData().init()
//      addrCacheMem(idx).valid := False
//    }
//  }
}

// When allocate MR, it needs to update AddrCache with PD, MR key, physical/virtual address, size;
// TODO: handle zero DMA length key check
class QpAddrCacheAgent extends Component {
  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
    val rqCacheRead = slave(QpAddrCacheAgentReadBus())
    val sqReqCacheRead = slave(QpAddrCacheAgentReadBus())
    val sqRespCacheRead = slave(QpAddrCacheAgentReadBus())
    val pdAddrCacheQuery = master(PdAddrCacheReadBus())
  }

  io.pdAddrCacheQuery.req <-/< StreamArbiterFactory().roundRobin.transactionLock
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

  val (rqIdx, sqReqIdx, sqRespIdx) = (0, 1, 2)
  /*
  val txSel = UInt(2 bits)
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
   */
  val isInitiatorRQ =
    io.pdAddrCacheQuery.resp.initiator === AddrQueryInitiator.RQ
  val isInitiatorSqReq =
    io.pdAddrCacheQuery.resp.initiator === AddrQueryInitiator.SQ_REQ
  val isInitiatorSqResp =
    io.pdAddrCacheQuery.resp.initiator === AddrQueryInitiator.SQ_RESP
  val threeStreams = StreamDeMuxByConditions(
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
    isInitiatorRQ,
    isInitiatorSqReq,
    isInitiatorSqResp
  )

  io.rqCacheRead.resp <-/< threeStreams(rqIdx)
  io.sqReqCacheRead.resp <-/< threeStreams(sqReqIdx)
  io.sqRespCacheRead.resp <-/< threeStreams(sqRespIdx)
}
