package rdma

import spinal.core._
import spinal.lib._

import ConstantSettings._
import RdmaConstants._

// TODO: if retrying, SQ should wait until retry go-back-to-N finished?
class SendQ(busWidth: BusWidth.Value) extends Component {
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
  }

  val workReqCache = new WorkReqCache(depth = MAX_PENDING_REQ_NUM)
  workReqCache.io.qpAttr := io.qpAttr
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
  io.notifier.retryClear := normalAndRetryWorkReqHandler.io.retryClear
  workReqCache.io.retryScanCtrlBus << normalAndRetryWorkReqHandler.io.retryScanCtrlBus
  // TODO: remove
  io.workCompErr << normalAndRetryWorkReqHandler.io.workCompErr

  val respHandler = new RespHandler(busWidth)
  respHandler.io.qpAttr := io.qpAttr
  respHandler.io.txQCtrl := io.txQCtrl
  respHandler.io.rx << io.rxResp
  respHandler.io.cachedWorkReqPop << workReqCache.io.pop
  io.notifier.retry := respHandler.io.retryNotifier
  io.addrCacheRead4Resp << respHandler.io.addrCacheRead
  io.workComp << respHandler.io.workComp
  io.dma.readResp << respHandler.io.readRespDmaWrite
  io.dma.atomic << respHandler.io.atomicRespDmaWrite

  // TODO: handle simultaneous fatal error
  io.notifier.err := normalAndRetryWorkReqHandler.io.errNotifier || respHandler.io.errNotifier
  io.notifier.coalesceAckDone := respHandler.io.coalesceAckDone

  io.tx << normalAndRetryWorkReqHandler.io.tx
}

class NormalAndRetryWorkReqHandler(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val workReqCacheEmpty = in(Bool())
    val psnInc = out(SqPsnInc())
    val errNotifier = out(SqErrNotifier())
    val retryClear = out(SqRetryClear())
    val workReq = slave(Stream(WorkReq()))
    val retryScanCtrlBus = master(RamScanCtrlBus())
    val retryWorkReq = slave(Stream(RamScanOut(CachedWorkReq())))
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val workReqCachePush = master(Stream(CachedWorkReq()))
    val dmaRead = master(DmaReadBus(busWidth))
    val workCompErr = master(Stream(WorkComp()))
    val tx = master(RdmaDataBus(busWidth))
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
  io.dmaRead << sqReqGenerator.io.dmaRead

  val sqOut = new SqOut(busWidth)
  sqOut.io.qpAttr := io.qpAttr
  sqOut.io.txQCtrl := io.txQCtrl
  sqOut.io.rxSendReq << sqReqGenerator.io.txSendReq
  sqOut.io.rxWriteReq << sqReqGenerator.io.txWriteReq
  sqOut.io.rxAtomicReq << sqReqGenerator.io.txAtomicReq
  sqOut.io.rxReadReq << sqReqGenerator.io.txReadReq
  sqOut.io.outPsnRangeFifoPush << workReqCacheAndOutPsnRangeHandler.io.sqOutPsnRangeFifoPush
//  io.retryFlushDone := sqOut.io.retryFlushDone
  io.psnInc.opsn := sqOut.io.opsnInc
  io.tx << sqOut.io.tx
}

// TODO: support MAX_PENDING_REQ_NUM limit
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

    // Update nPSN each time io.workReq fires
    val numReqPkt = divideByPmtuUp(workReq.lenBytes, io.qpAttr.pmtu)

    io.npsnInc.incVal := numReqPkt.resize(PSN_WIDTH)
    io.npsnInc.inc := io.workReq.fire && !io.txQCtrl.wrongStateFlush

    // To query AddCache, it needs several cycle delay.
    // In order to not block pipeline, use a FIFO to cache incoming data.
    val inputWorkReqQueuePop = FixedLenQueue(
      WorkReqAndMetaData(),
      depth = ADDR_CACHE_QUERY_FIFO_DEPTH,
      push = workReq4Queue.translateWith {
        val result = WorkReqAndMetaData()
        result.workReq := workReq
        result.psnStart := io.qpAttr.npsn
        result.pktNum := numReqPkt.resize(PSN_WIDTH)
        result
      },
      flush = io.txQCtrl.wrongStateFlush,
      queueName = "inputWorkReqQueue"
    )
    /*
        val inputWorkReqQueue =
          StreamFifoLowLatency(WorkReqAndMetaData(), ADDR_CACHE_QUERY_FIFO_DEPTH)
        inputWorkReqQueue.io.push << workReq4Queue
          .translateWith {
            val result = cloneOf(inputWorkReqQueue.dataType) // WorkReqAndMetaData()
            result.workReq := workReq
            result.psnStart := io.qpAttr.npsn
            result.pktNum := numReqPkt.resize(PSN_WIDTH)
            result
          }
        inputWorkReqQueue.io.flush := io.txQCtrl.wrongStateFlush
        assert(
          assertion = inputWorkReqQueue.io.push.ready,
          message =
            L"inputWorkReqQueue is full, inputWorkReqQueue.io.push.ready=${inputWorkReqQueue.io.push.ready}, inputWorkReqQueue.io.occupancy=${inputWorkReqQueue.io.occupancy}, which is not allowed in WorkReqValidator".toSeq,
          severity = FAILURE
        )
        val inputWorkReqQueuePop = inputWorkReqQueue.io.pop.combStage()
     */
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
    val inputValid = addrCacheQueryBuilder.inputWorkReqQueuePop.valid
    val inputCachedWorkReq = addrCacheQueryBuilder.inputWorkReqQueuePop.payload

    val addrCacheRespValid =
      io.addrCacheRead.resp.valid && !io.txQCtrl.wrongStateFlush
    val sizeValid = io.addrCacheRead.resp.sizeValid
    val keyValid = io.addrCacheRead.resp.keyValid
    val accessValid = io.addrCacheRead.resp.accessValid
    when(addrCacheRespValid && inputValid) {
      assert(
        assertion = inputCachedWorkReq.psnStart === io.addrCacheRead.resp.psn,
        message =
          L"${REPORT_TIME} time: addrCacheReadResp.resp has PSN=${io.addrCacheRead.resp.psn} not match SQ query PSN=${inputCachedWorkReq.psnStart}".toSeq,
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
      addrCacheQueryBuilder.inputWorkReqQueuePop,
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
          L"${REPORT_TIME} time: workCompErrStatus=${workCompErrStatus} should not be success, checkPass=${checkPass}, io.txQCtrl.wrongStateFlush=${io.txQCtrl.wrongStateFlush}".toSeq,
        severity = FAILURE
      )
    }

    io.workReqToCache <-/< normalStream.translateWith {
      val result = cloneOf(io.workReqToCache)
      result.workReq := normalStream._1.workReq
      result.psnStart := normalStream._1.psnStart
      result.pktNum := normalStream._1.pktNum
      result.pa := normalStream._2.pa
      result
    }
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

  io.workReqOut <-/< io.normalWorkReq
    .throwWhen(io.txQCtrl.wrongStateFlush)
    // TODO: consider remove FENCE state from QP state machine?
    .haltWhen(
      io.txQCtrl.retry || fenceWaitCond
    )
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
    .throwWhen(io.txQCtrl.wrongStateFlush)
    .map { payloadData =>
      val psnEnd = payloadData.psnStart + (payloadData.pktNum - 1)
//    report(L"${REPORT_TIME} time: psnStart=${payloadData.psnStart}, pktNum=${payloadData.pktNum}, psnEnd=${psnEnd}")
      val result = cloneOf(io.sqOutPsnRangeFifoPush.payloadType)
      result.workReqOpCode := payloadData.workReq.opcode
      result.start := payloadData.psnStart
      result.end := psnEnd.resize(PSN_WIDTH)
      result.isRetryWorkReq := io.txQCtrl.retry
      result
    }

  io.workReqCachePush <-/< workReq4CachePush
    // When retry state, no need to push to WR cache
    .throwWhen(io.txQCtrl.wrongStateFlush || io.txQCtrl.retry)

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

class SqOut(busWidth: BusWidth.Value) extends Component {
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
  val (normalReqOut, psnOutRangeQueuePop) = SeqOut(
    curPsn = io.qpAttr.npsn,
    flush = io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush,
    outPsnRangeQueuePush = io.outPsnRangeFifoPush,
    outDataStreamVec = normalReqVec,
    outputValidateFunc = (
        psnOutRangeFifoPop: ReqPsnRange,
        req: RdmaDataPkt
    ) => {
      assert(
        assertion = checkWorkReqOpCodeMatch(
          psnOutRangeFifoPop.workReqOpCode,
          req.bth.opcode
        ),
        message =
          // TODO: check SpinalEnumCraft print bug
          L"${REPORT_TIME} time: WR opcode does not match request opcode=${req.bth.opcode}, req.bth.psn=${req.bth.psn}, psnOutRangeQueuePop.start=${psnOutRangeFifoPop.start}, psnOutRangeQueuePop.end=${psnOutRangeFifoPop.end}".toSeq,
        severity = FAILURE
      )
    },
    hasPktToOutput = hasPktToOutput,
    opsnInc = opsnInc
  )
  io.opsnInc.inc := opsnInc.inc && !psnOutRangeQueuePop.isRetryWorkReq
  io.opsnInc.psnVal := opsnInc.psnVal

  io.tx.pktFrag <-/< normalReqOut.throwWhen(
    io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush
  )
  checkBthStable(normalReqOut.bth, normalReqOut.isFirst, normalReqOut.valid)
}
