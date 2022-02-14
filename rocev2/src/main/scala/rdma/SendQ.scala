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
    val sendQCtrl = in(SendQCtrl())
    val psnInc = out(SqPsnInc())
    val notifier = out(SqNotifier())
    val retryFlushDone = out(Bool())
    val workReq = slave(Stream(WorkReq()))
    val retryWorkReq = slave(Stream(CachedWorkReq()))
    val addrCacheRead4Req = master(QpAddrCacheAgentReadBus())
    val addrCacheRead4Resp = master(QpAddrCacheAgentReadBus())
    val rxResp = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val dma = master(SqDmaBus(busWidth))
    val workComp = master(Stream(WorkComp()))
    val workCompErr = master(Stream(WorkComp())) // TODO: remove
    val workReqCacheScanBus = slave(
      CamFifoScanBus(CachedWorkReq(), PENDING_REQ_NUM)
    )
  }

  val workReqCache = new WorkReqCache(depth = PENDING_REQ_NUM)
//  workReqCache.io.qpAttr := io.qpAttr
  io.notifier.workReqCacheEmpty := workReqCache.io.empty
  workReqCache.io.scanBus << io.workReqCacheScanBus

  val reqSender = new ReqSender(busWidth)
  reqSender.io.qpAttr := io.qpAttr
  reqSender.io.sendQCtrl := io.sendQCtrl
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

  val respHandler = new RespHandler(busWidth)
  respHandler.io.qpAttr := io.qpAttr
  respHandler.io.sendQCtrl := io.sendQCtrl
  respHandler.io.rx << io.rxResp
  workReqCache.io.queryPort4SqRespDmaWrite << respHandler.io.workReqQuery
  respHandler.io.cachedWorkReqPop << workReqCache.io.pop
  io.notifier.retry := respHandler.io.retryNotify
  io.addrCacheRead4Resp << respHandler.io.addrCacheRead
  io.workComp << respHandler.io.workComp
  io.dma.readResp << respHandler.io.readRespDmaWrite
  io.dma.atomic << respHandler.io.atomicRespDmaWrite

  val retryHandler = new RetryHandler(busWidth)
  retryHandler.io.qpAttr := io.qpAttr
  retryHandler.io.sendQCtrl := io.sendQCtrl
  retryHandler.io.retryWorkReq << io.retryWorkReq
//  workReqCache.io.queryPort4DupReqDmaRead << retryHandler.io.workReqQueryPort4DupDmaReadResp
  io.dma.retry << retryHandler.io.dmaRead

  io.notifier.nak := reqSender.io.nakNotify || respHandler.io.nakNotify
  io.notifier.coalesceAckDone := respHandler.io.coalesceAckDone

  val sqOut = new SqOut(busWidth)
  sqOut.io.qpAttr := io.qpAttr
  sqOut.io.sendQCtrl := io.sendQCtrl
  sqOut.io.rxSendReq << reqSender.io.txSendReq
  sqOut.io.rxWriteReq << reqSender.io.txWriteReq
  sqOut.io.rxAtomicReq << reqSender.io.txAtomicReq
  sqOut.io.rxReadReq << reqSender.io.txReadReq
  sqOut.io.rxSendReqRetry << retryHandler.io.txSendReq
  sqOut.io.rxWriteReqRetry << retryHandler.io.txWriteReq
  sqOut.io.rxAtomicReqRetry << retryHandler.io.txAtomicReq
  sqOut.io.rxReadReqRetry << retryHandler.io.txReadReq
  sqOut.io.outPsnRangeFifoPush << reqSender.io.sqOutPsnRangeFifoPush
  io.retryFlushDone := sqOut.io.retryFlushDone
  io.psnInc.opsn := sqOut.io.opsnInc

  io.tx << sqOut.io.tx
}

class ReqSender(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val workReqCacheEmpty = in(Bool())
    val npsnInc = out(NPsnInc())
    val nakNotify = out(SqNakNotifier())
    val workReq = slave(Stream(WorkReq()))
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val sqOutPsnRangeFifoPush = master(Stream(ReqPsnRange()))
    val workReqCachePush = master(Stream(CachedWorkReq()))
//    val workReqQueryPort4SqDmaReadResp = master(WorkReqCacheQueryBus())
    val workReqHasFence = out(Bool())
    val dmaRead = master(DmaReadBus(busWidth))
    val workCompErr = master(Stream(WorkComp()))
    val txSendReq = master(RdmaDataBus(busWidth))
    val txWriteReq = master(RdmaDataBus(busWidth))
    val txReadReq = master(Stream(ReadReq()))
    val txAtomicReq = master(Stream(AtomicReq()))
  }

  val workReqValidator = new WorkReqValidator
  workReqValidator.io.qpAttr := io.qpAttr
  workReqValidator.io.sendQCtrl := io.sendQCtrl
  workReqValidator.io.workReq << io.workReq
  io.addrCacheRead << workReqValidator.io.addrCacheRead
  io.workCompErr << workReqValidator.io.workCompErr
  io.sqOutPsnRangeFifoPush << workReqValidator.io.sqOutPsnRangeFifoPush
  io.npsnInc := workReqValidator.io.npsnInc
  io.nakNotify := workReqValidator.io.nakNotify

  val workReqCachePushAndReadAtomicHandler =
    new WorkReqCachePushAndReadAtomicHandler
  workReqCachePushAndReadAtomicHandler.io.qpAttr := io.qpAttr
  workReqCachePushAndReadAtomicHandler.io.sendQCtrl := io.sendQCtrl
  workReqCachePushAndReadAtomicHandler.io.workReqCacheEmpty := io.workReqCacheEmpty
  workReqCachePushAndReadAtomicHandler.io.workReqToCache << workReqValidator.io.workReqToCache
  io.workReqCachePush << workReqCachePushAndReadAtomicHandler.io.workReqCachePush
  io.workReqHasFence := workReqCachePushAndReadAtomicHandler.io.workReqHasFence
//  io.psnInc := workReqCachePushAndReadAtomicHandler.io.psnInc
  io.dmaRead.req << workReqCachePushAndReadAtomicHandler.io.dmaRead.req

  val sqDmaReadRespHandler = new SqDmaReadRespHandler(busWidth)
  sqDmaReadRespHandler.io.sendQCtrl := io.sendQCtrl
  sqDmaReadRespHandler.io.dmaReadResp.resp << io.dmaRead.resp
  sqDmaReadRespHandler.io.cachedWorkReq << workReqCachePushAndReadAtomicHandler.io.cachedWorkReqOut
//  io.workReqQueryPort4SqDmaReadResp << sqDmaReadRespHandler.io.workReqQuery

  val sendWriteReqSegment = new SendWriteReqSegment(busWidth)
  sendWriteReqSegment.io.qpAttr := io.qpAttr
  sendWriteReqSegment.io.sendQCtrl := io.sendQCtrl
  sendWriteReqSegment.io.cachedWorkReqAndDmaReadResp << sqDmaReadRespHandler.io.cachedWorkReqAndDmaReadResp

  val sendReqGenerator = new SendReqGenerator(busWidth)
  sendReqGenerator.io.qpAttr := io.qpAttr
  sendReqGenerator.io.sendQCtrl := io.sendQCtrl
  sendReqGenerator.io.cachedWorkReqAndDmaReadResp << sendWriteReqSegment.io.sendCachedWorkReqAndDmaReadResp

  val writeReqGenerator = new WriteReqGenerator(busWidth)
  writeReqGenerator.io.qpAttr := io.qpAttr
  writeReqGenerator.io.sendQCtrl := io.sendQCtrl
  writeReqGenerator.io.cachedWorkReqAndDmaReadResp << sendWriteReqSegment.io.writeCachedWorkReqAndDmaReadResp

  io.txSendReq << sendReqGenerator.io.txReq
  io.txWriteReq << writeReqGenerator.io.txReq
  io.txAtomicReq << workReqCachePushAndReadAtomicHandler.io.txAtomicReq
  io.txReadReq << workReqCachePushAndReadAtomicHandler.io.txReadReq
}

class WorkReqValidator extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val npsnInc = out(NPsnInc())
    val nakNotify = out(SqNakNotifier())
    val workReq = slave(Stream(WorkReq()))
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val workReqToCache = master(Stream(CachedWorkReq()))
    val sqOutPsnRangeFifoPush = master(Stream(ReqPsnRange()))
    val workCompErr = master(Stream(WorkComp()))
  }
  val addrCacheQueryBuilder = new Area {
    val workReq = io.workReq.payload

    val (workReq4Queue, sqOutPsnRangeFifoPush) = StreamFork2(io.workReq)
    // Update nPSN each time io.workReq fires
    val numReqPkt = divideByPmtuUp(workReq.lenBytes, io.qpAttr.pmtu)
    val psnEnd = io.qpAttr.npsn + numReqPkt - 1
    io.npsnInc.incVal := numReqPkt.resize(PSN_WIDTH)
    io.npsnInc.inc := io.workReq.fire

    io.sqOutPsnRangeFifoPush <-/< sqOutPsnRangeFifoPush
      .throwWhen(io.sendQCtrl.wrongStateFlush)
      .translateWith {
        val rslt = cloneOf(io.sqOutPsnRangeFifoPush.payloadType)
        rslt.opcode := workReq.opcode
        rslt.start := io.qpAttr.npsn
        rslt.end := psnEnd.resize(PSN_WIDTH)
        rslt
      }

    // To query AddCache, it needs several cycle delay.
    // In order to not block pipeline, use a FIFO to cache incoming data.
    val inputWorkReqQueue = workReq4Queue
      .throwWhen(io.sendQCtrl.wrongStateFlush)
      .haltWhen(io.sendQCtrl.fenceOrRetry)
      .translateWith {
        val rslt = CachedWorkReq()
        rslt.workReq := workReq
        rslt.psnStart := io.qpAttr.npsn
        rslt.pktNum := numReqPkt.resize(PSN_WIDTH)
        rslt.pa := 0 // PA will be updated after QpAddrCacheAgent query
        rslt
      }
      .queueLowLatency(ADDR_CACHE_QUERY_DELAY_CYCLE)

    io.addrCacheRead.req <-/< SignalEdgeDrivenStream(io.workReq.fire)
      .throwWhen(io.sendQCtrl.wrongStateFlush)
      .translateWith {
        val addrCacheReadReq = QpAddrCacheAgentReadReq()
        addrCacheReadReq.sqpn := io.qpAttr.sqpn
        addrCacheReadReq.psn := io.qpAttr.npsn
        addrCacheReadReq.key := workReq.lkey // Local read does not need key
        addrCacheReadReq.pdId := io.qpAttr.pdId
        addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = False)
        addrCacheReadReq.accessType := AccessType.LOCAL_READ.id
        addrCacheReadReq.va := workReq.laddr
        addrCacheReadReq.dataLenBytes := workReq.lenBytes
        addrCacheReadReq
      }
  }

  val workReqValidator = new Area {
    val inputValid = addrCacheQueryBuilder.inputWorkReqQueue.valid
    val inputCachedWorkReq = addrCacheQueryBuilder.inputWorkReqQueue.payload

    val addrCacheRespValid = io.addrCacheRead.resp.valid
    val sizeValid = io.addrCacheRead.resp.sizeValid
    val keyValid = io.addrCacheRead.resp.keyValid
    val accessValid = io.addrCacheRead.resp.accessValid
    when(addrCacheRespValid && inputValid) {
      assert(
        assertion = inputCachedWorkReq.psnStart === io.addrCacheRead.resp.psn,
        message =
          L"addrCacheReadResp.resp has PSN=${io.addrCacheRead.resp.psn} not match input PSN=${inputCachedWorkReq.psnStart}",
        severity = FAILURE
      )
    }

    val bufLenErr = inputValid && addrCacheRespValid && !sizeValid
    val keyErr = inputValid && addrCacheRespValid && !keyValid
    val accessErr = inputValid && addrCacheRespValid && !accessValid
    val checkPass =
      inputValid && addrCacheRespValid && !keyErr && !bufLenErr && !accessErr

    val joinStream =
      StreamJoin(addrCacheQueryBuilder.inputWorkReqQueue, io.addrCacheRead.resp)
    val txSel = UInt(1 bit)
    val (errIdx, succIdx) = (0, 1)
    when(checkPass) {
      txSel := succIdx
    } elsewhen (io.sendQCtrl.wrongStateFlush) {
      txSel := errIdx
    } otherwise {
      txSel := errIdx
    }
    val twoStreams = StreamDemux(joinStream, select = txSel, portCount = 2)

    val workCompErrStatus = Bits(WC_STATUS_WIDTH bits)
    when(bufLenErr) {
      workCompErrStatus := WorkCompStatus.LOC_LEN_ERR.id
    } elsewhen (keyErr || accessErr) {
      // TODO: what status should WC have if keyErr, LOC_ACCESS_ERR or LOC_PROT_ERR
      workCompErrStatus := WorkCompStatus.LOC_ACCESS_ERR.id
    } elsewhen (io.sendQCtrl.wrongStateFlush) {
      // Set WC status to flush if QP is in error state and flushed
      workCompErrStatus := WorkCompStatus.WR_FLUSH_ERR.id
    } otherwise {
      workCompErrStatus := WorkCompStatus.SUCCESS.id
    }

    io.workCompErr <-/< twoStreams(errIdx).translateWith {
      val rslt = cloneOf(io.workCompErr.payloadType)
      // Set WC error due to invalid local virtual address or DMA length in WR, or being flushed
      rslt.setFromWorkReq(
        workReq = twoStreams(errIdx)._1.workReq,
        dqpn = io.qpAttr.dqpn,
        status = workCompErrStatus
      )
      rslt
    }
    io.nakNotify.setNoErr()
    when(twoStreams(errIdx).fire) {
      io.nakNotify.setLocalErr()

      assert(
        assertion = workCompErrStatus =/= WorkCompStatus.SUCCESS.id,
        message =
          L"workCompErrStatus=${workCompErrStatus} should not be success, checkPass=${checkPass}, io.sendQCtrl.wrongStateFlush=${io.sendQCtrl.wrongStateFlush}",
        severity = FAILURE
      )
    }

    io.workReqToCache <-/< twoStreams(succIdx).translateWith(
      twoStreams(succIdx)._1
    )
  }
}

// TODO: keep track of pending read/atomic requests
class WorkReqCachePushAndReadAtomicHandler extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val workReqToCache = slave(Stream(CachedWorkReq()))
    val workReqHasFence = out(Bool())
    val workReqCacheEmpty = in(Bool())
    val workReqCachePush = master(Stream(CachedWorkReq()))
    val cachedWorkReqOut = master(Stream(CachedWorkReq()))
    val dmaRead = master(DmaReadReqBus())
    val txReadReq = master(Stream(ReadReq()))
    val txAtomicReq = master(Stream(AtomicReq()))
  }

  val cachedWorkReqValid = io.workReqToCache.valid
  val cachedWorkReq = io.workReqToCache.payload
  val isSendWorkReq = WorkReqOpCode.isSendReq(cachedWorkReq.workReq.opcode)
  val isWriteWorkReq = WorkReqOpCode.isWriteReq(cachedWorkReq.workReq.opcode)
  val isReadWorkReq = WorkReqOpCode.isReadReq(cachedWorkReq.workReq.opcode)
  val isAtomicWorkReq = WorkReqOpCode.isAtomicReq(cachedWorkReq.workReq.opcode)
  val (sendWriteWorkReqIdx, readWorkReqIdx, atomicWorkReqIdx, otherWorkReqIdx) =
    (0, 1, 2, 3)
  val txSel = UInt(2 bits)
  when(isSendWorkReq || isWriteWorkReq) {
    txSel := sendWriteWorkReqIdx
  } elsewhen (isReadWorkReq) {
    txSel := readWorkReqIdx
  } elsewhen (isAtomicWorkReq) {
    txSel := atomicWorkReqIdx
  } otherwise {
    txSel := otherWorkReqIdx
  }
  when(cachedWorkReqValid) {
    assert(
      assertion =
        isSendWorkReq || isWriteWorkReq || isReadWorkReq || isAtomicWorkReq,
      message =
        L"the WR is not send/write/read/atomic, WR opcode=${cachedWorkReq.workReq.opcode}",
      severity = FAILURE
    )
  }

  // Handle fence
  io.workReqHasFence := cachedWorkReqValid && cachedWorkReq.workReq.fence
  val (workReq4CachePush, workReq4DownStream, workReq4Output) = StreamFork3(
    io.workReqToCache
      .throwWhen(io.sendQCtrl.wrongStateFlush)
      // (cachedWorkReq.workReq.fence && !io.workReqCacheEmpty) is also FENCE state trigger condition
      // TODO: consider remove FENCE state from QP state machine?
      .haltWhen(
        io.sendQCtrl.fenceOrRetry || (cachedWorkReq.workReq.fence && !io.workReqCacheEmpty)
      )
  )

  io.workReqCachePush <-/< workReq4CachePush
  io.cachedWorkReqOut <-/< workReq4DownStream

  val fourStreams = StreamDemux(workReq4Output, select = txSel, portCount = 4)
  // Just discard non-send/write/read/atomic WR
  StreamSink(NoData) << fourStreams(otherWorkReqIdx).translateWith(NoData)

  io.txAtomicReq <-/< fourStreams(atomicWorkReqIdx).translateWith {
    val isCompSwap =
      cachedWorkReq.workReq.opcode === WorkReqOpCode.ATOMIC_CMP_AND_SWP.id
    val rslt = AtomicReq().set(
      isCompSwap,
      dqpn = io.qpAttr.dqpn,
      psn = cachedWorkReq.psnStart,
      va = cachedWorkReq.workReq.raddr,
      rkey = cachedWorkReq.workReq.rkey,
      comp = cachedWorkReq.workReq.comp,
      swap = cachedWorkReq.workReq.swap
    )
    rslt
  }

  io.txReadReq <-/< fourStreams(readWorkReqIdx).translateWith {
    val rslt = ReadReq().set(
      dqpn = io.qpAttr.dqpn,
      psn = cachedWorkReq.psnStart,
      va = cachedWorkReq.workReq.raddr,
      rkey = cachedWorkReq.workReq.rkey,
      dlen = cachedWorkReq.workReq.lenBytes
    )
    rslt
  }

  io.dmaRead.req <-/< fourStreams(sendWriteWorkReqIdx).translateWith {
    val rslt = cloneOf(io.dmaRead.req.payloadType)
    rslt.set(
      initiator = DmaInitiator.SQ_RD,
      sqpn = io.qpAttr.sqpn,
      psnStart = cachedWorkReq.psnStart,
      addr = cachedWorkReq.pa,
      lenBytes = cachedWorkReq.workReq.lenBytes
    )
  }
}

// TODO: SQ output should output retry requests first
class SqOut(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val opsnInc = out(OPsnInc())
    val outPsnRangeFifoPush = slave(Stream(ReqPsnRange()))
    val rxSendReq = slave(RdmaDataBus(busWidth))
    val rxWriteReq = slave(RdmaDataBus(busWidth))
    val rxReadReq = slave(Stream(ReadReq()))
    val rxAtomicReq = slave(Stream(AtomicReq()))
    val rxSendReqRetry = slave(RdmaDataBus(busWidth))
    val rxWriteReqRetry = slave(RdmaDataBus(busWidth))
    val rxReadReqRetry = slave(Stream(ReadReq()))
    val rxAtomicReqRetry = slave(Stream(AtomicReq()))
    val retryFlushDone = out(Bool())
    val tx = master(RdmaDataBus(busWidth))
  }

  // TODO: set max pending request number using QpAttrData
  val psnOutRangeFifo = StreamFifoLowLatency(
    io.outPsnRangeFifoPush.payloadType(),
    depth = PENDING_REQ_NUM
  )
  psnOutRangeFifo.io.push << io.outPsnRangeFifoPush

  val rxReadReq =
    io.rxReadReq.translateWith(io.rxReadReq.toRdmaDataPktFrag(busWidth))
  val rxReadReqRetry =
    io.rxReadReqRetry.translateWith(
      io.rxReadReqRetry.toRdmaDataPktFrag(busWidth)
    )
  val rxAtomicReq =
    io.rxAtomicReq.translateWith(io.rxAtomicReq.toRdmaDataPktFrag(busWidth))
  val rxAtomicReqRetry =
    io.rxAtomicReqRetry.translateWith(
      io.rxAtomicReqRetry.toRdmaDataPktFrag(busWidth)
    )

  val txVec =
    Vec(io.rxSendReq.pktFrag, io.rxWriteReq.pktFrag, rxReadReq, rxAtomicReq)
  val txSelOH = txVec.map(req => {
    val psnRangeMatch =
      psnOutRangeFifo.io.pop.start <= req.bth.psn && req.bth.psn <= psnOutRangeFifo.io.pop.end
    when(psnRangeMatch && psnOutRangeFifo.io.pop.valid) {
      assert(
        assertion = checkWorkReqOpCodeMatch(
          psnOutRangeFifo.io.pop.opcode,
          req.bth.opcode
        ),
        message =
          L"WR opcode=${psnOutRangeFifo.io.pop.opcode} does not match request opcode=${req.bth.opcode}",
        severity = FAILURE
      )
    }
    psnRangeMatch
  })
  val hasPktToOutput = !txSelOH.orR
  when(psnOutRangeFifo.io.pop.valid && !hasPktToOutput) {
    // TODO: no output in OutPsnRange should be normal case
    report(
      message =
        L"no output packet in OutPsnRange: startPsn=${psnOutRangeFifo.io.pop.start}, endPsn=${psnOutRangeFifo.io.pop.end}, psnOutRangeFifo.io.pop.valid=${psnOutRangeFifo.io.pop.valid}",
      severity = FAILURE
    )
  }
  val txOutputSel = StreamOneHotMux(select = txSelOH.asBits(), inputs = txVec)
  psnOutRangeFifo.io.pop.ready := txOutputSel.bth.psn === psnOutRangeFifo.io.pop.end && txOutputSel.fire
  io.opsnInc.inc := txOutputSel.fire
  io.opsnInc.psnVal := txOutputSel.bth.psn

  val txRetryVec = Vec(
    io.rxSendReqRetry.pktFrag,
    io.rxWriteReqRetry.pktFrag,
    rxReadReqRetry,
    rxAtomicReqRetry
  )
  val txOutput = StreamArbiterFactory.roundRobin.fragmentLock
    .on(txRetryVec :+ txOutputSel.continueWhen(hasPktToOutput))
  io.tx.pktFrag <-/< txOutput.throwWhen(io.sendQCtrl.wrongStateFlush)

  io.retryFlushDone := io.sendQCtrl.retry && (io.rxSendReqRetry.pktFrag.fire || io.rxWriteReqRetry.pktFrag.fire || io.rxReadReqRetry.fire || io.rxAtomicReqRetry.fire)
}
