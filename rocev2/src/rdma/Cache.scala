package rdma

import spinal.core._
import spinal.lib._
import ConstantSettings._
import RdmaConstants._
import StreamVec._

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

//case class CamFifoRetryScanReq(depth: Int) extends Bundle {
//  //  val scanPtr = Stream(UInt(log2Up(depth) bits))
//  val ptr = UInt(log2Up(depth) bits)
//  val retryReason = RetryReason()
//  val retryStartPsn = UInt(PSN_WIDTH bits)
//}
//
//case class CamFifoRetryScanResp[Tv <: Data](valueType: HardType[Tv], depth: Int)
//    extends Bundle {
//  val data = valueType()
//}

//case class CamFifoScanBus[Tv <: Data](valueType: HardType[Tv], depth: Int)
//    extends Bundle
//    with IMasterSlave {
//  val empty = Bool()
//  val popPtr = UInt(log2Up(depth) bits)
//  val pushPtr = UInt(log2Up(depth) bits)
//
//  val scanReq = Stream(CamFifoRetryScanReq(depth))
//  val scanResp = Stream(CamFifoRetryScanResp(valueType, depth))
//
//  def >>(that: CamFifoScanBus[Tv]): Unit = {
//    that.scanReq << this.scanReq
//    this.scanResp << that.scanResp
//
//    this.popPtr := that.popPtr
//    this.pushPtr := that.pushPtr
//    this.empty := that.empty
//  }
//
//  def <<(that: CamFifoScanBus[Tv]): Unit = that >> this
//
//  override def asMaster(): Unit = {
//    in(popPtr, pushPtr, empty)
//    master(scanReq)
//    slave(scanResp)
//  }
//}

case class CachedValue[T <: Data](dataType: HardType[T]) extends Bundle {
  val valid = Bool()
  val data = dataType()
}

/*
class CamFifo[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv],
    queryFunc: (Tk, Tv) => Bool,
    depth: Int,
    portCount: Int
//    scanRespOnFireModifyFunc: Option[(CamFifoRetryScanReq, Tv) => Tv],
//    supportScan: Boolean
) extends Component {
  require(isPow2(depth), f"CamFifo depth=${depth} must be power of 2")

  val io = new Bundle {
    val push = slave(Stream(valueType))
    val pop = master(Stream(valueType))
    val flush = in(Bool())
    val occupancy = out(UInt(log2Up(depth + 1) bits))
//    val availability = out(UInt(log2Up(depth + 1) bits))
    val empty = out(Bool())
    val full = out(Bool())
    val queryBusVec = Vec(slave(CamQueryBus(keyType, valueType)), portCount)
//    val scanBus = slave(CamFifoScanBus(valueType, depth))
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

//    val scanModifyLogic = supportScan generate new Area {
//      io.scanBus.empty := logic.empty
//      io.scanBus.popPtr := logic.popPtr
//      io.scanBus.pushPtr := logic.pushPtr
//
//      val scanRespStream = logic.ram.streamReadSync(
//        io.scanBus.scanReq ~~ (scanRequest => scanRequest.ptr),
//        linkedData = io.scanBus.scanReq
//      )
//      io.scanBus.scanResp <-/< scanRespStream ~~ { scanResponse =>
//        val result = cloneOf(io.scanBus.scanResp.payloadType)
//        result.data := scanResponse.value.data
//        result
//      }
//      // Increase retry count
//      for (modifyFunc <- scanRespOnFireModifyFunc) {
//        when(scanRespStream.fire) {
//          logic.ram(scanRespStream.linked.ptr).data := modifyFunc(
//            scanRespStream.linked,
//            logic.ram(scanRespStream.linked.ptr).data
//          )
//        }
//      }
//    }
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
      val result = CamQueryResp(keyType, valueType)
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
 */

class Fifo[T <: Data](valueType: HardType[T], initDataVal: => T, depth: Int)
    extends Component {
  require(depth >= 4, f"Fifo minimum depth=${depth} is 4")
  require(isPow2(depth), f"Fifo depth=${depth} must be power of 2")
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

  io.push.ready := !full
  io.empty := empty
  io.full := full
  io.pushPtr := pushPtr.value
  io.popPtr := popPtr.value
  io.pushing := pushing
  io.popping := popping
  io.ram := ram
//  for(idx <- 0 until depth) {
//    val index = U(idx, depthWidth bits)
//    io.ram(index) := ram.readAsync(index, readUnderWrite = dontCare)
//  }

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
  require(depth >= 4, f"RamScan minimum depth=${depth} is 4")
  require(isPow2(depth), f"RamScan depth=${depth} must be power of 2")
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
      assertion = Formal.stable(io.pushPtr) && !io.pushing,
      message = L"${REPORT_TIME} time: during scan, no push to FIFO",
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
  require(isPow2(depth), f"Cam depth=${depth} must be power of 2")
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
            L"${REPORT_TIME} time: itemIdxOH=${itemIdxOH} is not one hot when found=${found}, itemIdxBinary=${itemIdxBinary}",
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
      StreamArbiterFactory.roundRobin.transactionLock.on(queryReqVec)
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
      respStreamPiped ~~ (_._1),
      select = respStreamPiped._2,
      portCount = portCount
    )

    for (idx <- 0 until portCount) {
      io.queryBusVec(idx).resp <-/< queryRespStreamVec(idx)
    }
  }
}

//class CamScanFifo[Tk <: Data, Tv <: Data](
//    keyType: HardType[Tk],
//    valueType: HardType[Tv],
//    queryFunc: Option[(Tk, Tv) => Bool],
//    depth: Int,
//    portCount: Int,
//    scanRespOnFireModifyFunc: Option[(SpinalEnumCraft[RetryReason.type], Tv) => Unit]
//) extends Component {
//  require(isPow2(depth), f"CamScanFifo depth=${depth} must be power of 2")
//  val depthWidth = log2Up(depth)
//  val ptrWidth = depthWidth + 1
//  val twiceDepth = depth << 1
//
//  val supportQuery = queryFunc.isDefined
//  val supportScan = scanRespOnFireModifyFunc.isDefined
//
//  val io = new Bundle {
//    val push = slave(Stream(valueType))
//    val pop = master(Stream(valueType))
//    val flush = in(Bool())
//    val occupancy = out(UInt(ptrWidth bits))
////    val availability = out(UInt(log2Up(depth + 1) bits))
//    val pushPtr = out(UInt(ptrWidth bits))
//    val popPtr = out(UInt(ptrWidth bits))
//    val empty = out(Bool())
//    val full = out(Bool())
//    val queryBusVec = Vec(slave(CamFifoQueryBus(keyType, valueType)), portCount)
////    val scanBus = slave(CamFifoScanBus(valueType, depth))
//    val retryScanCtrlBus = slave(RamScanCtrlBus())
//    val retryScanOut = master(Stream(valueType))
//  }
//
//  // Copied code from StreamFifoLowLatency
//  val logic = new Area {
//    val fifo = new Area {
//      val ram = Vec(Reg(valueType()), depth)
////      val validVec = Vec(RegInit(False), depth)
//      val pushPtr = Counter(ptrWidth bits)
//      val popPtr = Counter(ptrWidth bits)
//      val ptrMatch = pushPtr === popPtr
////      val risingOccupancy = RegInit(False)
//      val empty = ptrMatch
//      val full = pushPtr.msb =/= popPtr.msb &&
//        pushPtr(ptrWidth - 2 downto 0) ===
//          popPtr(ptrWidth - 2 downto 0)
//
//      val pushing = io.push.fire
//      val popping = io.pop.fire
//
//      io.push.ready := !full
//      io.empty := empty
//      io.full := full
//      io.pushPtr := pushPtr.value
//      io.popPtr := popPtr.value
//
//      when(!empty) {
//        io.pop.valid := True
//        val idx = popPtr.value.resize(depthWidth)
//        io.pop.payload := ram(idx)
//      } otherwise {
//        io.pop.valid := io.push.valid
//        io.pop.payload := io.push.payload
//      }
//
//      when(pushing) {
//        val idx = pushPtr.value.resize(depthWidth)
//        ram(idx) := io.push.payload
////        validVec(idx) := True
//        pushPtr.increment()
//      }
//      when(popping) {
//        val idx = popPtr.value.resize(depthWidth)
//        // TODO: verify valid bit update policy for each RAM element is writeFirst
////        validVec(idx) := False
//        popPtr.increment()
//      }
//
////      when(pushPtr >= popPtr) {
////        io.occupancy := pushPtr - popPtr
////      } otherwise {
//        io.occupancy := ((twiceDepth + pushPtr.value) - popPtr.value).resize(ptrWidth)
////      }
//
//      when(io.flush) {
//        pushPtr.clear()
//        popPtr.clear()
////        for (idx <- 0 until depth) {
////          validVec(idx) := False
////        }
//      }
//    }
//
//    val scan = supportScan generate new Area {
//      val scanPtr = Counter(depthWidth bits)
//      when(io.flush) {
//        scanPtr.clear()
//      }
//
//      val (scanReqStream, lastReq) = StreamCounterSource(
//        io.retryScanCtrlBus.startPulse,
//        fifo.popPtr,
//        fifo.pushPtr,
//        io.flush,
//        twiceDepth
//      )
//      io.retryScanCtrlBus.donePulse := lastReq
//
//      val scanRespStream = fifo.ram.streamReadSync(
//        scanReqStream ~~ { _.resize(depthWidth) },
//        linkedData = TupleBundle(
//          scanReqStream.payload,
//          io.retryScanCtrlBus.retryReason,
//          io.retryScanCtrlBus.retryStartPsn
//        )
//      )
//      io.retryScanOut <-/< scanRespStream ~~ { scanResponse =>
//        val result = cloneOf(io.retryScanOut.payloadType)
//        result := scanResponse.value
//        result
//      }
//      // Increase retry count
//      when(scanRespStream.fire) {
//        val idx = scanRespStream.linked._1.resize(depthWidth)
//        for (scanRespOnFireModify <- scanRespOnFireModifyFunc) {
//          scanRespOnFireModify(
//            scanRespStream.linked._2, // RetryReason
//            fifo.ram(idx)
////          scanRespStream.value // Scan output data
//          )
//        }
//      }
//    }
//  }
//
//  val query = supportQuery generate new Area {
//    def search(queryReq: Stream[TupleBundle2[Tk, UInt]]) =
//      new Composite(queryReq, "CamScanFifo_search") {
//        val queryKey = queryReq._1
//        val queryPortIdx = queryReq._2
//        val idxOH = Vec((0 until depth).map(idx => {
//          val v = logic.fifo.ram.readAsync(
//            address = U(idx, log2Up(depth) bits),
//            // The read will get the new value (provided by the write)
//            readUnderWrite = writeFirst
//          )
//          val queryResult = for (queryF <- queryFunc) yield
//            queryF(queryKey, v) && logic.fifo.validVec(idx)
//          queryResult.getOrElse(False)
//        })).asBits
//        val found = idxOH.orR
//        val idxBinary = OHToUInt(idxOH)
//        val idxOhLsb = OHMasking.first(idxOH)
//        val idxOhLsbBinary = OHToUInt(idxOhLsb)
//        val ramIdxStream = queryReq.translateWith(idxOhLsbBinary)
//
//        when(queryReq.valid) {
//          assert(
//            assertion = CountOne(idxOH) === found.asUInt,
//            message = L"${REPORT_TIME} time: idxOH=${idxOH} is not one hot when found=${found}, idxBinary=${idxBinary}, idxOhLsbBinary=${idxOhLsbBinary}",
//            severity = ERROR
//          )
//        }
//        // TODO: check the timing of RAM search
//        // ramIdxStream cannot be staged, to prevent FIFO write to RAM to invalidate the idxOH
//        val queryResultStream = logic.fifo.ram
//          .streamReadSync(
//            ramIdxStream,
//            linkedData = TupleBundle(queryPortIdx, queryKey, found)
//          )
//      }.queryResultStream
//
//    if (portCount == 1) {
//      val busIdx = 0
//      val queryIdx = U(0, portCount bits)
//      val queryResultStream = search(
//        io.queryBusVec(busIdx)
//          .req
//          .translateWith(
//            TupleBundle(io.queryBusVec(busIdx).req.payload, queryIdx)
//          )
//      )
//      io.queryBusVec(busIdx).resp <-/< queryResultStream.translateWith {
//        val result = CamFifoQueryResp(keyType, valueType)
//        result.respValue := queryResultStream.value
//        result.queryKey := queryResultStream.linked._2
//        result.found := queryResultStream.linked._3
//        result
//      }
//    } else { // More than 2 query port, need arbitration
//      val queryReqVec =
//        for ((queryBus, idx) <- io.queryBusVec.zipWithIndex)
//          yield {
//            val queryPortIdx = U(idx, log2Up(portCount) bits)
//            queryBus.req.translateWith(
//              TupleBundle(queryBus.req.payload, queryPortIdx)
//            )
//          }
//
//      val queryArbitrated =
//        StreamArbiterFactory.roundRobin.transactionLock.on(queryReqVec)
//      val queryResp = search(queryArbitrated)
//      val queryRespVec = StreamDemux(
//        queryResp,
//        select = queryResp.linked._1,
//        portCount = portCount
//      )
//      for (idx <- 0 until portCount) {
//        io.queryBusVec(idx).resp.arbitrationFrom(queryRespVec(idx))
//        io.queryBusVec(idx).resp.respValue := queryRespVec(idx).value
//        io.queryBusVec(idx).resp.queryKey := queryRespVec(idx).linked._2
//        io.queryBusVec(idx).resp.found := queryRespVec(idx).linked._3
//      }
//    }
//  }
//}

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
//    queryPort.resp << cam.io
//      .queryBusVec(portIdx)
//      .resp
//      .translateWith {
//        val result = cloneOf(queryPort.resp.payloadType)
//        result.rstCacheData := cam.io.queryBusVec(portIdx).resp.respValue
//        result.query := cam.io.queryBusVec(portIdx).resp.queryKey
//        result.found := cam.io.queryBusVec(portIdx).resp.found
//        result
//      }
  }
}

class WorkReqCache(depth: Int) extends Component {
  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
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
  fifo.io.push << io.push
  io.pop << fifo.io.pop
  io.full := fifo.io.full
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
      assertion = Formal.stable(fifo.io.pushPtr),
      message = L"${REPORT_TIME} time: during retry, no new WR can be added",
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
    val addrCreateOrDelete = slave(PdAddrDataCreateOrDeleteBus())
    val query = slave(PdAddrCacheReadBus())
    val full = out(Bool())
  }

  // TODO: add initial values to Mem
  val addrCacheMem = Vec(
    RegInit {
      val result = CachedValue(AddrData())
      result.valid := False
      result.data.init()
      result
    },
    depth
  )

  val addrCreateOrDelete = new Area {
    val isAddrDataCreation =
      io.addrCreateOrDelete.req.createOrDelete === CRUD.CREATE
    val ramAvailable = Vec((0 until depth).map(idx => {
//      val v = addrCacheMem.readAsync(
//        address = U(idx, log2Up(depth) bits),
//        // The read will get the new value (provided by the write)
//        readUnderWrite = writeFirst
//      )
      val v = addrCacheMem(idx)
      v.valid
    })).asBits
    val foundRamAvailable = ramAvailable.orR
    io.full := !foundRamAvailable
    val addrDataCreateIdxOH = OHMasking.first(ramAvailable)

    val isAddrDataDeletion =
      io.addrCreateOrDelete.req.createOrDelete === CRUD.DELETE
    val addrDataDeleteIdxOH = Vec((0 until depth).map(idx => {
      val deleteReq = io.addrCreateOrDelete.req
//      val v = addrCacheMem.readAsync(
//        address = U(idx, log2Up(depth) bits),
//        // The read will get the new value (provided by the write)
//        readUnderWrite = writeFirst
//      )
      val v = addrCacheMem(idx)
      deleteReq.addrData.lkey === v.data.lkey &&
      deleteReq.addrData.rkey === v.data.rkey && v.valid
    })).asBits
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
    val cam = new Cam(
      PdAddrCacheReadReq(),
      CachedValue(AddrData()),
      queryFunc = (k: PdAddrCacheReadReq, v: CachedValue[AddrData]) =>
        v.valid &&
          ((k.key === v.data.lkey && !k.remoteOrLocalKey) ||
            (k.key === v.data.rkey && k.remoteOrLocalKey)),
      depth = depth,
      portCount = 1
    )
    cam.io.ram := Vec((0 until depth).map(idx => addrCacheMem(idx)))
//    cam.io.ram := Vec((0 until depth).map(idx => addrCacheMem.readAsync(idx, readUnderWrite = writeFirst)))

//    val (queryReq4Queue, queryReq4Cache) = StreamFork2(io.query.req)
//    val reqQueue = StreamFifoLowLatency(
//      dataType = io.query.req.payloadType(),
//      depth = ADDR_CACHE_QUERY_DELAY_CYCLE
//    )
//    reqQueue.io.push << queryReq4Queue

    val reqQueue = io.query.req.queueLowLatency(ADDR_CACHE_QUERY_DELAY_CYCLE)
    val queryPortVec = Vec(reqQueue)
    val respPortVec = Vec(io.query.resp)
    for ((queryPort, portIdx) <- queryPortVec.zipWithIndex) {
      cam.io.queryBusVec(portIdx).req << queryPort
      respPortVec(portIdx) <-/< cam.io.queryBusVec(portIdx).resp ~~ {
        payloadData =>
          val originalReq = payloadData.queryKey
          val cacheResp = payloadData.respValue.data
          val reqSizeValid = cacheResp.va <= originalReq.va &&
            (originalReq.va + originalReq.dataLenBytes <= cacheResp.va + cacheResp.dataLenBytes)
          val pa = UInt(MEM_ADDR_WIDTH bits)
          when(reqSizeValid) {
            pa := cacheResp.pa + originalReq.va - cacheResp.va
          } otherwise {
            pa.assignDontCare() // Invalid PhysicalAddr
          }
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
    /*
    val idxOH = Vec((0 until depth).map(idx => {
      val queryReq = queryReq4Cache
      val v = addrCacheMem.readAsync(
        address = U(idx, log2Up(depth) bits),
        // The read will get the new value (provided by the write)
        readUnderWrite = writeFirst
      )
      v.valid &&
      ((queryReq.key === v.data.lkey && !queryReq.remoteOrLocalKey) ||
        (queryReq.key === v.data.rkey && queryReq.remoteOrLocalKey))
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
    val reqSizeValid = cacheResp.va <= originalReq.va &&
      (originalReq.va + originalReq.dataLenBytes <= cacheResp.va + cacheResp.dataLenBytes)
    val pa = UInt(MEM_ADDR_WIDTH bits)
    when(reqSizeValid) {
      pa := cacheResp.pa + originalReq.va - cacheResp.va
    } otherwise {
      pa := 0 // Invalid
    }
    val accessValid = cacheResp.accessType.permit(originalReq.accessType)

    io.query.resp << joinStream
      .translateWith {
        val result = cloneOf(io.query.resp.payloadType)
        result.initiator := originalReq.initiator
        result.sqpn := originalReq.sqpn
        result.psn := originalReq.psn
        result.pa := pa
        result.accessValid := accessValid
        result.sizeValid := reqSizeValid
        result.keyValid := joinStream._2.linked._2 // Found
        result
      }
     */
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
    // UNREACHABLE DEFAULT STATEMENT
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
}
