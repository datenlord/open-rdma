package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._

// TODO: if retrying, SQ should wait until retry go-back-to-N finished?
class SendQ(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val psnInc = out(SqPsnInc())
    val notifier = out(SqNotifier())
    val workReq = slave(Stream(WorkReq()))
    val addrCacheRead4Req = master(QpAddrCacheAgentReadBus())
    val addrCacheRead4Resp = master(QpAddrCacheAgentReadBus())
    val rxResp = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val dma = master(SqDmaBus(busWidth))
    val workComp = master(Stream(WorkComp()))
    val workCompErr = master(Stream(WorkComp())) // TODO: remove
//    val workReqCacheScanBus = slave(
//      CamFifoScanBus(CachedWorkReq(), PENDING_REQ_NUM)
//    )
  }

  val workReqCache = new WorkReqCache(depth = PENDING_REQ_NUM)
  workReqCache.io.txQCtrl := io.txQCtrl
  io.notifier.workReqCacheEmpty := workReqCache.io.empty

  val normalAndRetryWorkReqHandler = new NormalAndRetryWorkReqHandler(busWidth)
  normalAndRetryWorkReqHandler.io.qpAttr := io.qpAttr
  normalAndRetryWorkReqHandler.io.txQCtrl := io.txQCtrl
  normalAndRetryWorkReqHandler.io.workReqCacheEmpty := workReqCache.io.empty
  normalAndRetryWorkReqHandler.io.workReq << io.workReq
  normalAndRetryWorkReqHandler.io.retryWorkReq << workReqCache.io.retryWorkReq
  workReqCache.io.push << normalAndRetryWorkReqHandler.io.workReqCachePush
  io.addrCacheRead4Req << normalAndRetryWorkReqHandler.io.addrCacheRead
  io.psnInc := normalAndRetryWorkReqHandler.io.psnInc
  io.dma.reqOut << normalAndRetryWorkReqHandler.io.dmaRead
//  io.notifier.workReqHasFence := normalAndRetryWorkReqHandler.io.workReqHasFence
  io.notifier.retryClear := normalAndRetryWorkReqHandler.io.retryClear
  workReqCache.io.retryScanCtrlBus << normalAndRetryWorkReqHandler.io.retryScanCtrlBus
  // TODO: remove
  io.workCompErr << normalAndRetryWorkReqHandler.io.workCompErr
  /*
  val reqSender = new ReqSender(busWidth)
  reqSender.io.qpAttr := io.qpAttr
  reqSender.io.txQCtrl := io.txQCtrl
  reqSender.io.workReqCacheEmpty := workReqCache.io.empty
  reqSender.io.workReq << io.workReq
  workReqCache.io.push << reqSender.io.workReqCachePush
//  workReqCache.io.queryPort4SqReqDmaRead << reqSender.io.workReqQueryPort4SqDmaReadResp
  io.addrCacheRead4Req << reqSender.io.addrCacheRead
  io.psnInc.npsn := reqSender.io.npsnInc
  io.dma.reqSender << reqSender.io.dmaRead
  io.notifier.workReqHasFence := reqSender.io.workReqHasFence
  // TODO: remove
  io.workCompErr << reqSender.io.workCompErr
   */
  val respHandler = new RespHandler(busWidth)
  respHandler.io.qpAttr := io.qpAttr
  respHandler.io.txQCtrl := io.txQCtrl
  respHandler.io.rx << io.rxResp
//  workReqCache.io.queryPort4SqRespDmaWrite << respHandler.io.workReqQuery
  respHandler.io.cachedWorkReqPop << workReqCache.io.pop
  io.notifier.retry := respHandler.io.retryNotifier
  io.addrCacheRead4Resp << respHandler.io.addrCacheRead
  io.workComp << respHandler.io.workComp
  io.dma.readResp << respHandler.io.readRespDmaWrite
  io.dma.atomic << respHandler.io.atomicRespDmaWrite
  /*
  val retryHandler = new RetryHandler(busWidth)
  retryHandler.io.qpAttr := io.qpAttr
  retryHandler.io.txQCtrl := io.txQCtrl
  retryHandler.io.retryWorkReq << io.retryWorkReq
//  workReqCache.io.queryPort4DupReqDmaRead << retryHandler.io.workReqQueryPort4DupDmaReadResp
  io.dma.retry << retryHandler.io.dmaRead
   */
  // TODO: handle simultaneous fatal error
  io.notifier.err := normalAndRetryWorkReqHandler.io.errNotifier || respHandler.io.errNotifier
  io.notifier.coalesceAckDone := respHandler.io.coalesceAckDone

//  val sqOut = new SqOut(busWidth)
//  sqOut.io.qpAttr := io.qpAttr
//  sqOut.io.txQCtrl := io.txQCtrl
//  sqOut.io.rxSendReq << normalAndRetryWorkReqHandler.io.txSendReq
//  sqOut.io.rxWriteReq << normalAndRetryWorkReqHandler.io.txWriteReq
//  sqOut.io.rxAtomicReq << normalAndRetryWorkReqHandler.io.txAtomicReq
//  sqOut.io.rxReadReq << normalAndRetryWorkReqHandler.io.txReadReq
//  sqOut.io.outPsnRangeFifoPush << normalAndRetryWorkReqHandler.io.sqOutPsnRangeFifoPush
//  io.psnInc.opsn := sqOut.io.opsnInc
//  io.tx << sqOut.io.tx

  io.tx << normalAndRetryWorkReqHandler.io.tx
}

class NormalAndRetryWorkReqHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val workReqCacheEmpty = in(Bool())
    val psnInc = out(SqPsnInc())
//    val opsnInc = out(OPsnInc())
//    val npsnInc = out(NPsnInc())
    val errNotifier = out(SqErrNotifier())
    val retryClear = out(SqRetryClear())
    val workReq = slave(Stream(WorkReq()))
    val retryScanCtrlBus = master(RamScanCtrlBus())
    val retryWorkReq = slave(Stream(RamScanOut(CachedWorkReq())))
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
//    val sqOutPsnRangeFifoPush = master(Stream(ReqPsnRange()))
    val workReqCachePush = master(Stream(CachedWorkReq()))
//    val workReqHasFence = out(Bool())
    val dmaRead = master(DmaReadBus(busWidth))
    val workCompErr = master(Stream(WorkComp()))
    val tx = master(RdmaDataBus(busWidth))
//    val txSendReq = master(RdmaDataBus(busWidth))
//    val txWriteReq = master(RdmaDataBus(busWidth))
//    val txReadReq = master(Stream(ReadReq()))
//    val txAtomicReq = master(Stream(AtomicReq()))
//    val workReqCacheScanBus = master(
//      CamFifoScanBus(CachedWorkReq(), PENDING_REQ_NUM)
//    )
  }

  val normalWorkReq = new Area {
    val workReqValidator = new WorkReqValidator
    workReqValidator.io.qpAttr := io.qpAttr
    workReqValidator.io.txQCtrl := io.txQCtrl
    workReqValidator.io.workReq << io.workReq
    io.addrCacheRead << workReqValidator.io.addrCacheRead
    io.workCompErr << workReqValidator.io.workCompErr
//    io.sqOutPsnRangeFifoPush << workReqValidator.io.sqOutPsnRangeFifoPush
    io.psnInc.npsn := workReqValidator.io.npsnInc

    val workReqFenceHandler = new WorkReqFenceHandler
    workReqFenceHandler.io.qpAttr := io.qpAttr
    workReqFenceHandler.io.txQCtrl := io.txQCtrl
    workReqFenceHandler.io.workReqCacheEmpty := io.workReqCacheEmpty
    workReqFenceHandler.io.normalWorkReq << workReqValidator.io.workReqToCache
//    io.workReqHasFence := workReqFenceHandler.io.workReqHasFence
  }

  val retryHandler = new RetryHandler
  retryHandler.io.qpAttr := io.qpAttr
  retryHandler.io.txQCtrl := io.txQCtrl
  retryHandler.io.retryWorkReqIn << io.retryWorkReq
  io.retryClear.retryWorkReqDone := retryHandler.io.retryWorkReqDone
  io.retryScanCtrlBus << retryHandler.io.retryScanCtrlBus

//  val retryWorkReqHandler = new RetryWorkReqHandler
//  retryWorkReqHandler.io.qpAttr := io.qpAttr
//  retryWorkReqHandler.io.txQCtrl := io.txQCtrl
//  retryWorkReqHandler.io.retryWorkReq << io.retryWorkReq

  io.errNotifier := normalWorkReq.workReqValidator.io.errNotifier || retryHandler.io.errNotifier

  val workReqCacheAndOutPsnRangeHandler = new WorkReqCacheAndOutPsnRangeHandler
//  workReqCacheAndOutPsnRangeHandler.io.qpAttr := io.qpAttr
  workReqCacheAndOutPsnRangeHandler.io.txQCtrl := io.txQCtrl
  workReqCacheAndOutPsnRangeHandler.io.normalWorkReq << normalWorkReq.workReqFenceHandler.io.workReqOut
  workReqCacheAndOutPsnRangeHandler.io.retryWorkReq << retryHandler.io.retryWorkReqOut
  io.retryClear.retryFlushDone := workReqCacheAndOutPsnRangeHandler.io.retryFlushDone
//  io.sqOutPsnRangeFifoPush << workReqCacheAndOutPsnRangeHandler.io.sqOutPsnRangeFifoPush
  io.workReqCachePush << workReqCacheAndOutPsnRangeHandler.io.workReqCachePush

  val sqReqGenerator = new SqReqGenerator(busWidth)
  sqReqGenerator.io.qpAttr := io.qpAttr
  sqReqGenerator.io.txQCtrl := io.txQCtrl
  sqReqGenerator.io.sendWriteNormalWorkReq << workReqCacheAndOutPsnRangeHandler.io.sendWriteWorkReqOut
  sqReqGenerator.io.readNormalWorkReq << workReqCacheAndOutPsnRangeHandler.io.readWorkReqOut
  sqReqGenerator.io.atomicNormalWorkReq << workReqCacheAndOutPsnRangeHandler.io.atomicWorkReqOut
//  sqReqGenerator.io.sendWriteRetryWorkReq << retryWorkReqHandler.io.sendWriteRetryWorkReq
//  sqReqGenerator.io.readRetryWorkReq << retryWorkReqHandler.io.readRetryWorkReq
//  sqReqGenerator.io.atomicRetryWorkReq << retryWorkReqHandler.io.atomicRetryWorkReq
  io.dmaRead << sqReqGenerator.io.dmaRead
//  io.txSendReq << sqReqGenerator.io.txSendReq
//  io.txWriteReq << sqReqGenerator.io.txWriteReq
//  io.txAtomicReq << sqReqGenerator.io.txAtomicReq
//  io.txReadReq << sqReqGenerator.io.txReadReq

  val sqOut = new SqOut(busWidth)
  sqOut.io.qpAttr := io.qpAttr
  sqOut.io.txQCtrl := io.txQCtrl
  sqOut.io.rxSendReq << sqReqGenerator.io.txSendReq
  sqOut.io.rxWriteReq << sqReqGenerator.io.txWriteReq
  sqOut.io.rxAtomicReq << sqReqGenerator.io.txAtomicReq
  sqOut.io.rxReadReq << sqReqGenerator.io.txReadReq
  //  sqOut.io.rxSendReqRetry << retryHandler.io.txSendReq
  //  sqOut.io.rxWriteReqRetry << retryHandler.io.txWriteReq
  //  sqOut.io.rxAtomicReqRetry << retryHandler.io.txAtomicReq
  //  sqOut.io.rxReadReqRetry << retryHandler.io.txReadReq
  sqOut.io.outPsnRangeFifoPush << workReqCacheAndOutPsnRangeHandler.io.sqOutPsnRangeFifoPush
  //  io.retryFlushDone := sqOut.io.retryFlushDone
  io.psnInc.opsn := sqOut.io.opsnInc
  io.tx << sqOut.io.tx
}

// TODO: support PENDING_REQ_NUM limit
// TODO: keep track of pending read/atomic requests
class WorkReqValidator extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val npsnInc = out(NPsnInc())
    val errNotifier = out(SqErrNotifier())
    val workReq = slave(Stream(WorkReq()))
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val workReqToCache = master(Stream(CachedWorkReq()))
//    val sqOutPsnRangeFifoPush = master(Stream(ReqPsnRange()))
    val workCompErr = master(Stream(WorkComp()))
  }

  val addrCacheQueryBuilder = new Area {
    val workReq = io.workReq.payload

    val (workReq4Queue, workReq4AddrCacheQuery) = StreamFork2(
      io.workReq
        // Cannot throw WR here, since it needs to generate error WC when flush
//        .throwWhen(io.txQCtrl.wrongStateFlush)
        .haltWhen(io.txQCtrl.retry)
    )
//    val forkStream = StreamFork(
//      // Should generate WC for the flushed WR
//      io.workReq.haltWhen(io.txQCtrl.fenceOrRetry),
//      portCount = 3
//    )
//    val (workReq4Queue, workReq4AddrCacheQuery, sqOutPsnRangeFifoPush) =
//      (forkStream(0), forkStream(1), forkStream(2))
    // Update nPSN each time io.workReq fires
    val numReqPkt = divideByPmtuUp(workReq.lenBytes, io.qpAttr.pmtu)
//    val psnEnd = io.qpAttr.npsn + (numReqPkt - 1)

    io.npsnInc.incVal := numReqPkt.resize(PSN_WIDTH)
    io.npsnInc.inc := io.workReq.fire && !io.txQCtrl.wrongStateFlush

    // To query AddCache, it needs several cycle delay.
    // In order to not block pipeline, use a FIFO to cache incoming data.
    val inputWorkReqQueue = workReq4Queue
      .translateWith {
        val result = CachedWorkReq()
        result.workReq := workReq
        result.psnStart := io.qpAttr.npsn
        result.pktNum := numReqPkt.resize(PSN_WIDTH)
        // PA will be updated after QpAddrCacheAgent query
        result.pa.assignDontCare()
//        result.rnrCnt := 0 // New WR has no RNR
//        result.retryCnt := 0 // New WR has no retry
        result
      }
      .queueLowLatency(ADDR_CACHE_QUERY_DELAY_CYCLE)

    io.addrCacheRead.req <-/< workReq4AddrCacheQuery
      .throwWhen(io.txQCtrl.wrongStateFlush)
      .translateWith {
        val addrCacheReadReq = QpAddrCacheAgentReadReq()
        addrCacheReadReq.sqpn := io.qpAttr.sqpn
        addrCacheReadReq.psn := io.qpAttr.npsn
        addrCacheReadReq.key := workReq.lkey // Local read does not need key
        addrCacheReadReq.pdId := io.qpAttr.pdId
        addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = False)
        addrCacheReadReq.accessType.set(AccessPermission.LOCAL_READ)
        addrCacheReadReq.va := workReq.laddr
        addrCacheReadReq.dataLenBytes := workReq.lenBytes
        addrCacheReadReq
      }
  }

  val workReqValidator = new Area {
    val inputValid = addrCacheQueryBuilder.inputWorkReqQueue.valid
    val inputCachedWorkReq = addrCacheQueryBuilder.inputWorkReqQueue.payload

    val addrCacheRespValid =
      io.addrCacheRead.resp.valid && !io.txQCtrl.wrongStateFlush
    val sizeValid = io.addrCacheRead.resp.sizeValid
    val keyValid = io.addrCacheRead.resp.keyValid
    val accessValid = io.addrCacheRead.resp.accessValid
    when(addrCacheRespValid && inputValid) {
      assert(
        assertion = inputCachedWorkReq.psnStart === io.addrCacheRead.resp.psn,
        message =
          L"${REPORT_TIME} time: addrCacheReadResp.resp has PSN=${io.addrCacheRead.resp.psn} not match SQ query PSN=${inputCachedWorkReq.psnStart}",
        severity = FAILURE
      )
    }

    val bufLenErr = inputValid && addrCacheRespValid && !sizeValid
    val keyErr = inputValid && addrCacheRespValid && !keyValid
    val accessErr = inputValid && addrCacheRespValid && !accessValid
    val checkPass =
      inputValid && addrCacheRespValid && !keyErr && !bufLenErr && !accessErr

    val addrCacheReadResp =
      io.addrCacheRead.resp.throwWhen(io.txQCtrl.wrongStateFlush)
    val joinStream = StreamConditionalJoin(
      addrCacheQueryBuilder.inputWorkReqQueue,
      addrCacheReadResp,
      joinCond = !io.txQCtrl.wrongStateFlush
    )

    val normalCond = !io.txQCtrl.wrongStateFlush && checkPass
    val (errorStream, normalStream) = StreamDeMuxByOneCondition(
      input = joinStream,
      condition = normalCond
    )

    val workCompErrStatus = WorkCompStatus()
    when(bufLenErr) {
      workCompErrStatus := WorkCompStatus.LOC_LEN_ERR
    } elsewhen (keyErr || accessErr) {
      // TODO: what status should WC have if keyErr, LOC_ACCESS_ERR or LOC_PROT_ERR
      workCompErrStatus := WorkCompStatus.LOC_ACCESS_ERR
    } elsewhen (io.txQCtrl.wrongStateFlush) {
      // Set WC status to flush if QP is in error state and flushed
      workCompErrStatus := WorkCompStatus.WR_FLUSH_ERR
    } otherwise {
      workCompErrStatus := WorkCompStatus.SUCCESS
    }

    io.workCompErr <-/< errorStream.translateWith {
      val result = cloneOf(io.workCompErr.payloadType)
      // Set WC error due to invalid local virtual address or DMA length in WR, or being flushed
      result.setFromWorkReq(
        workReq = errorStream._1.workReq,
        dqpn = io.qpAttr.dqpn,
        status = workCompErrStatus
      )
      result
    }
    io.errNotifier.setNoErr()
    when(errorStream.fire) {
      io.errNotifier.setLocalErr()

      assert(
        assertion = workCompErrStatus =/= WorkCompStatus.SUCCESS,
        message =
          L"${REPORT_TIME} time: workCompErrStatus=${workCompErrStatus} should not be success, checkPass=${checkPass}, io.txQCtrl.wrongStateFlush=${io.txQCtrl.wrongStateFlush}",
        severity = FAILURE
      )
    }

//    val (normalOut4WorkReqCache, normalOut4SqOutPsnRangeFifoPush) = StreamFork2(
//      normalStream
//    )
    io.workReqToCache <-/< normalStream.translateWith(
      normalStream._1
    )

//    io.sqOutPsnRangeFifoPush <-/< normalOut4SqOutPsnRangeFifoPush
//      .throwWhen(io.txQCtrl.wrongStateFlush) ~~ { payloadData =>
//      val psnEnd = payloadData._1.psnStart + (payloadData._1.pktNum - 1)
//      val result = cloneOf(io.sqOutPsnRangeFifoPush.payloadType)
//      result.workReqOpCode := payloadData._1.workReq.opcode
//      result.start := payloadData._1.psnStart
//      result.end := psnEnd.resize(PSN_WIDTH)
//      result
//    }
  }
}

class WorkReqFenceHandler extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val normalWorkReq = slave(Stream(CachedWorkReq()))
//    val workReqHasFence = out(Bool())
    val workReqCacheEmpty = in(Bool())
    val workReqOut = master(Stream(CachedWorkReq()))
//    val sendWriteWorkReqOut = master(Stream(CachedWorkReq()))
//    val readWorkReqOut = master(Stream(CachedWorkReq()))
//    val atomicWorkReqOut = master(Stream(CachedWorkReq()))
  }

  val cachedWorkReqValid = io.normalWorkReq.valid
  val cachedWorkReq = io.normalWorkReq.payload
  val isSendWorkReq = WorkReqOpCode.isSendReq(cachedWorkReq.workReq.opcode)
  val isWriteWorkReq = WorkReqOpCode.isWriteReq(cachedWorkReq.workReq.opcode)
  val isReadWorkReq = WorkReqOpCode.isReadReq(cachedWorkReq.workReq.opcode)
  val isAtomicWorkReq = WorkReqOpCode.isAtomicReq(cachedWorkReq.workReq.opcode)

  val fenceWaitCond = cachedWorkReq.workReq.flags.fence && !io.workReqCacheEmpty
  // FENCE state trigger condition
//  io.workReqHasFence := cachedWorkReqValid && fenceWaitCond
//  val (workReq4CachePush, workReq4Output) = StreamFork2(
//    io.normalWorkReq
//      .throwWhen(io.txQCtrl.wrongStateFlush)
//      // TODO: consider remove FENCE state from QP state machine?
//      .haltWhen(
//        io.txQCtrl.retry || fenceWaitCond
//      )
//  )

  io.workReqOut <-/< io.normalWorkReq
    .throwWhen(io.txQCtrl.wrongStateFlush)
    // TODO: consider remove FENCE state from QP state machine?
    .haltWhen(
      io.txQCtrl.retry || fenceWaitCond
    )

//  val (sendWriteWorkReqIdx, readWorkReqIdx, atomicWorkReqIdx) = (0, 1, 2)
//  val threeStreams = StreamDeMuxByConditions(
//    workReq4Output,
//    isSendWorkReq || isWriteWorkReq,
//    isReadWorkReq,
//    isAtomicWorkReq
//  )
//
//  io.sendWriteWorkReqOut <-/< threeStreams(sendWriteWorkReqIdx)
//  io.readWorkReqOut <-/< threeStreams(readWorkReqIdx)
//  io.atomicWorkReqOut <-/< threeStreams(atomicWorkReqIdx)
}

class WorkReqCacheAndOutPsnRangeHandler extends Component {
  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val normalWorkReq = slave(Stream(CachedWorkReq()))
    val retryWorkReq = slave(Stream(CachedWorkReq()))
    val workReqCachePush = master(Stream(CachedWorkReq()))
    val sendWriteWorkReqOut = master(Stream(CachedWorkReq()))
    val readWorkReqOut = master(Stream(CachedWorkReq()))
    val atomicWorkReqOut = master(Stream(CachedWorkReq()))
    val sqOutPsnRangeFifoPush = master(Stream(ReqPsnRange()))
    val retryFlushDone = out(Bool())
  }

  val selectedWorkReq = StreamMux(
    select = io.txQCtrl.retry.asUInt,
    inputs = Vec(
      io.normalWorkReq.throwWhen(io.txQCtrl.wrongStateFlush),
      io.retryWorkReq.throwWhen(io.txQCtrl.wrongStateFlush)
    )
  )
  io.retryFlushDone := selectedWorkReq.fire && io.txQCtrl.retry

  val (workReq4CachePush, workReq4Output, workReq4OutPsnRangeFifoPUsh) =
    StreamFork3(selectedWorkReq)

  io.sqOutPsnRangeFifoPush <-/< workReq4OutPsnRangeFifoPUsh
    .throwWhen(io.txQCtrl.wrongStateFlush) ~~ { payloadData =>
    val psnEnd = payloadData.psnStart + (payloadData.pktNum - 1)
//    report(L"${REPORT_TIME} time: psnStart=${payloadData.psnStart}, pktNum=${payloadData.pktNum}, psnEnd=${psnEnd}")
    val result = cloneOf(io.sqOutPsnRangeFifoPush.payloadType)
    result.workReqOpCode := payloadData.workReq.opcode
    result.start := payloadData.psnStart
    result.end := psnEnd.resize(PSN_WIDTH)
    result.isRetryWorkReq := io.txQCtrl.retry
    result
  }

  io.workReqCachePush <-/< workReq4CachePush.throwWhen(
    io.txQCtrl.wrongStateFlush || io.txQCtrl.retry
  )

  val isSendWorkReq = WorkReqOpCode.isSendReq(workReq4Output.workReq.opcode)
  val isWriteWorkReq = WorkReqOpCode.isWriteReq(workReq4Output.workReq.opcode)
  val isReadWorkReq = WorkReqOpCode.isReadReq(workReq4Output.workReq.opcode)
  val isAtomicWorkReq = WorkReqOpCode.isAtomicReq(workReq4Output.workReq.opcode)
  val (sendWriteWorkReqIdx, readWorkReqIdx, atomicWorkReqIdx) = (0, 1, 2)
  val threeStreams = StreamDeMuxByConditions(
    workReq4Output,
    isSendWorkReq || isWriteWorkReq,
    isReadWorkReq,
    isAtomicWorkReq
  )

  io.sendWriteWorkReqOut <-/< threeStreams(sendWriteWorkReqIdx)
  io.readWorkReqOut <-/< threeStreams(readWorkReqIdx)
  io.atomicWorkReqOut <-/< threeStreams(atomicWorkReqIdx)
}

class SqOut(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val opsnInc = out(OPsnInc())
    val outPsnRangeFifoPush = slave(Stream(ReqPsnRange()))
    val rxSendReq = slave(RdmaDataBus(busWidth))
    val rxWriteReq = slave(RdmaDataBus(busWidth))
    val rxReadReq = slave(Stream(ReadReq()))
    val rxAtomicReq = slave(Stream(AtomicReq()))
    val tx = master(RdmaDataBus(busWidth))
  }

  val rxReadReq =
    io.rxReadReq.translateWith(io.rxReadReq.asRdmaDataPktFrag(busWidth))
  val rxAtomicReq =
    io.rxAtomicReq.translateWith(io.rxAtomicReq.asRdmaDataPktFrag(busWidth))
  val normalReqVec =
    Vec(io.rxSendReq.pktFrag, io.rxWriteReq.pktFrag, rxReadReq, rxAtomicReq)

  val opsnInc = OPsnInc()
  val hasPktToOutput = Bool()
  val (normalReqOut, psnOutRangeFifo) = SeqOut(
    curPsn = io.qpAttr.npsn,
    flush = io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush,
    outPsnRangeFifoPush = io.outPsnRangeFifoPush,
    outDataStreamVec = normalReqVec,
    outputValidateFunc =
      (psnOutRangeFifoPop: ReqPsnRange, req: RdmaDataPkt) => {
        assert(
          assertion = checkWorkReqOpCodeMatch(
            psnOutRangeFifoPop.workReqOpCode,
            req.bth.opcode
          ),
          message =
            // TODO: check SpinalEnumCraft print bug
            L"${REPORT_TIME} time: WR opcode does not match request opcode=${req.bth.opcode}, req.bth.psn=${req.bth.psn}, psnOutRangeFifo.io.pop.start=${psnOutRangeFifoPop.start}, psnOutRangeFifo.io.pop.end=${psnOutRangeFifoPop.end}",
//            L"${REPORT_TIME} time: WR opcode=${psnOutRangeFifoPop.workReqOpCode} does not match request opcode=${req.bth.opcode}, req.bth.psn=${req.bth.psn}, psnOutRangeFifo.io.pop.start=${psnOutRangeFifoPop.start}, psnOutRangeFifo.io.pop.end=${psnOutRangeFifoPop.end}",
          severity = FAILURE
        )
      },
    hasPktToOutput = hasPktToOutput,
    opsnInc = opsnInc
  )
  io.opsnInc.inc := opsnInc.inc && !psnOutRangeFifo.io.pop.isRetryWorkReq
  io.opsnInc.psnVal := opsnInc.psnVal

  io.tx.pktFrag <-/< normalReqOut.throwWhen(
    io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush
  )
}
