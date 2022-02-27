package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._

class RespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val nakNotifier = out(SqErrNotifier())
    val retryNotifier = out(SqRetryNotifier())
    val coalesceAckDone = out(Bool())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
    val workReqQuery = master(WorkReqCacheQueryBus())
    val workComp = master(Stream(WorkComp()))
    // Save read/atomic response data to main memory
    val readRespDmaWrite = master(DmaWriteBus(busWidth))
    val atomicRespDmaWrite = master(DmaWriteBus(busWidth))
  }

  val respVerifierAndFatalNakHandler = new RespVerifierAndFatalNakHandler(
    busWidth
  )
  respVerifierAndFatalNakHandler.io.qpAttr := io.qpAttr
  respVerifierAndFatalNakHandler.io.sendQCtrl := io.sendQCtrl
  respVerifierAndFatalNakHandler.io.rx << io.rx
  respVerifierAndFatalNakHandler.io.cachedWorkReq << io.cachedWorkReqPop.asFlow

  val coalesceAndNormalAndRetryNakHandler =
    new CoalesceAndNormalAndRetryNakHandler
  coalesceAndNormalAndRetryNakHandler.io.qpAttr := io.qpAttr
  coalesceAndNormalAndRetryNakHandler.io.sendQCtrl := io.sendQCtrl
  coalesceAndNormalAndRetryNakHandler.io.rx << respVerifierAndFatalNakHandler.io.txRespWithAeth
  coalesceAndNormalAndRetryNakHandler.io.cachedWorkReqPop << io.cachedWorkReqPop
  io.coalesceAckDone := coalesceAndNormalAndRetryNakHandler.io.coalesceAckDone
  io.retryNotifier := coalesceAndNormalAndRetryNakHandler.io.retryNotifier
  io.nakNotifier := coalesceAndNormalAndRetryNakHandler.io.nakNotifier
//  io.retryNotifier := respVerifierAndFatalNakHandler.io.respTimeOutRetryNotifier
//    .merge(ackHandler.io.retryNotifier, io.qpAttr.npsn)

  val readAtomicRespDmaReqInitiator =
    new ReadAtomicRespDmaReqInitiator(busWidth)
  readAtomicRespDmaReqInitiator.io.qpAttr := io.qpAttr
  readAtomicRespDmaReqInitiator.io.sendQCtrl := io.sendQCtrl
  readAtomicRespDmaReqInitiator.io.rx << respVerifierAndFatalNakHandler.io.txReadAtomicResp
  io.workReqQuery << readAtomicRespDmaReqInitiator.io.workReqQuery
  io.addrCacheRead << readAtomicRespDmaReqInitiator.io.addrCacheRead
  io.readRespDmaWrite.req << readAtomicRespDmaReqInitiator.io.readRespDmaWriteReq.req
  io.atomicRespDmaWrite.req << readAtomicRespDmaReqInitiator.io.atomicRespDmaWriteReq.req

  val workCompGen = new WorkCompGen
  workCompGen.io.qpAttr := io.qpAttr
  workCompGen.io.sendQCtrl := io.sendQCtrl
  workCompGen.io.workCompAndAck << coalesceAndNormalAndRetryNakHandler.io.workCompAndAck
  workCompGen.io.readRespDmaWriteResp.resp << io.readRespDmaWrite.resp
  workCompGen.io.atomicRespDmaWriteResp.resp << io.atomicRespDmaWrite.resp
  io.workComp << workCompGen.io.workCompPush
}

// Discard all invalid responses:
// - NAK with reserved code;
// - Target QP not exists; dropped by head verifier
// - Ghost ACK;
class RespVerifierAndFatalNakHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val cachedWorkReq = slave(Flow(CachedWorkReq()))
//    val respTimeOutRetryNotifier = out(SqRetryNotifier())
    val txRespWithAeth = master(Stream(Acknowledge()))
    val txReadAtomicResp = master(RdmaDataBus(busWidth))
  }

  val inputValid = io.rx.pktFrag.valid
  val inputPktFrag = io.rx.pktFrag.payload
  when(inputValid) {
    assert(
      assertion = OpCode.isRespPkt(inputPktFrag.bth.opcode),
      message =
        L"${REPORT_TIME} time: RespVerifier received non-response packet with opcode=${inputPktFrag.bth.opcode}, PSN=${inputPktFrag.bth.psn}",
      severity = FAILURE
    )
  }

  val hasReservedCode = False
  val acknowledge = Acknowledge()
  acknowledge.bth := inputPktFrag.bth
  acknowledge.aeth.assignFromBits(
    inputPktFrag.data(
      (busWidth.id - widthOf(BTH()) - widthOf(AETH())) until
        (busWidth.id - widthOf(BTH()))
    )
  )
//  val isNormalAck = acknowledge.aeth.isNormalAck()
//  val isRetryNak = acknowledge.aeth.isRetryNak()
//  val isErrAck = acknowledge.aeth.isErrAck()

  val hasAeth = OpCode.respPktHasAeth(inputPktFrag.bth.opcode)
  when(inputValid && hasAeth) {
    assert(
      assertion = !acknowledge.aeth.isReserved(),
      message =
        L"${REPORT_TIME} time: acknowledge has reserved code or value, PSN=${acknowledge.bth.psn}, aeth.code=${acknowledge.aeth.code}, aeth.value=${acknowledge.aeth.value}",
      severity = FAILURE
    )
  }

  val isReadResp =
    OpCode.isReadRespPkt(io.rx.pktFrag.bth.opcode)
  val isAtomicResp =
    OpCode.isAtomicRespPkt(io.rx.pktFrag.bth.opcode)

  val (resp4ReadAtomic, resp4OtherAck) = StreamFork2(
    io.rx.pktFrag.throwWhen(io.sendQCtrl.wrongStateFlush || hasReservedCode)
  )
  io.txReadAtomicResp.pktFrag <-/< resp4ReadAtomic.takeWhen(
    isReadResp || isAtomicResp
  )

  // TODO: does it need to flush whole SQ when retry triggered?
  val aethQueue =
    StreamFifoLowLatency(Acknowledge(), depth = MAX_COALESCE_ACK_NUM)
  aethQueue.io.push << resp4OtherAck
    .takeWhen(hasAeth)
    .translateWith(acknowledge)
  // TODO: verify that once retry started, discard all responses before send out the retry request
  aethQueue.io.flush := io.sendQCtrl.retryFlush || io.sendQCtrl.wrongStateFlush
  io.txRespWithAeth <-/< aethQueue.io.pop
    .takeWhen(OpCode.isNonReadAtomicRespPkt(inputPktFrag.bth.opcode))
    .translateWith(acknowledge)
}

// Handle coalesce ACK, normal ACK and retry ACK
class CoalesceAndNormalAndRetryNakHandler extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val rx = slave(Stream(Acknowledge()))
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
    val coalesceAckDone = out(Bool())
    val workCompAndAck = master(Stream(WorkCompAndAck()))
    val nakNotifier = out(SqErrNotifier())
    val retryNotifier = out(SqRetryNotifier())
//    val tx = slave(Stream(Acknowledge()))
  }

  val inputAckValid = io.rx.valid
  val inputCachedWorkReqValid = io.cachedWorkReqPop.valid

  val isDupAck =
    PsnUtil.lt(io.rx.bth.psn, io.cachedWorkReqPop.psnStart, io.qpAttr.npsn)
  val isGhostAck = inputAckValid && !inputCachedWorkReqValid

  val isNormalAck = io.rx.aeth.isNormalAck()
  val isRetryNak = io.rx.aeth.isRetryNak()
  val isErrAck = io.rx.aeth.isErrAck()

  val isReadWorkReq =
    WorkReqOpCode.isReadReq(io.cachedWorkReqPop.workReq.opcode)
  val isAtomicWorkReq =
    WorkReqOpCode.isAtomicReq(io.cachedWorkReqPop.workReq.opcode)

  val cachedWorkReqPsnEnd =
    io.cachedWorkReqPop.psnStart + (io.cachedWorkReqPop.pktNum - 1)
  val isTargetWorkReq =
    PsnUtil.gte(io.rx.bth.psn, io.cachedWorkReqPop.psnStart, io.qpAttr.npsn) &&
      PsnUtil.lte(io.rx.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val isWholeWorkReqAck = io.rx.bth.psn === cachedWorkReqPsnEnd
  val isPartialTargetWorkReqAck = isTargetWorkReq && !isWholeWorkReqAck
  val isWholeTargetWorkReqAck = isTargetWorkReq && isWholeWorkReqAck

  val hasCoalesceAck =
    PsnUtil.gt(io.rx.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val hasImplicitRetry = !isTargetWorkReq && (isReadWorkReq || isAtomicWorkReq)
  val hasExplicitRetry = isTargetWorkReq && isRetryNak

  // For the following fire condition, it must make sure inputAckValid or inputCachedWorkReqValid
  val fireBothCachedWorkReqAndAck =
    (inputAckValid && inputCachedWorkReqValid && ((isWholeTargetWorkReqAck && isNormalAck) || (isTargetWorkReq && isErrAck)))
  val fireCachedWorkReqOnly =
    (inputAckValid && inputCachedWorkReqValid && hasCoalesceAck && !hasImplicitRetry) || io.sendQCtrl.errorFlush
  val fireAckOnly =
    isGhostAck || (inputAckValid && inputCachedWorkReqValid && (isDupAck || (isTargetWorkReq && isRetryNak) || (isPartialTargetWorkReqAck && isNormalAck)))
  val zipCachedWorkReqAndAck = StreamZipByCondition(
    leftInputStream = io.cachedWorkReqPop,
    // Only retryFlush io.rx, no need to errorFlush
    rightInputStream = io.rx.throwWhen(io.sendQCtrl.retryFlush),
    // Coalesce ACK pending WR, or errorFlush
    leftFireCond = fireCachedWorkReqOnly,
    // Discard duplicated ACK or ghost ACK if no pending WR
    rightFireCond = fireAckOnly,
    bothFireCond = fireBothCachedWorkReqAndAck
  )
  when(inputAckValid || inputCachedWorkReqValid) {
    assert(
      assertion = CountOne(
        fireBothCachedWorkReqAndAck ## fireCachedWorkReqOnly ## fireAckOnly
      ) <= 1,
      message =
        L"${REPORT_TIME} time: fire CachedWorkReq, fire ACK, and fire both should be mutually exclusive, but fireBothCachedWorkReqAndAck=${fireBothCachedWorkReqAndAck}, fireCachedWorkReqOnly=${fireCachedWorkReqOnly}, fireAckOnly=${fireAckOnly}",
      severity = FAILURE
    )
  }

  val workCompFlushStatus = WorkCompStatus() // Bits(WC_STATUS_WIDTH bits)
  // TODO: what status should the read/atomic requests before error ACK have?
  workCompFlushStatus := WorkCompStatus.WR_FLUSH_ERR
  val zipCachedWorkReqValid = zipCachedWorkReqAndAck._1
  io.workCompAndAck <-/< zipCachedWorkReqAndAck
    .takeWhen(zipCachedWorkReqValid)
    .translateWith {
      val rslt = cloneOf(io.workCompAndAck.payloadType)
      when(isTargetWorkReq && isErrAck) {
        // Handle fatal NAK
        rslt.workComp.setFromWorkReq(
          workReq = zipCachedWorkReqAndAck._2.workReq,
          dqpn = io.qpAttr.dqpn,
          status = zipCachedWorkReqAndAck._4.aeth.toWorkCompStatus()
        )
      } elsewhen (io.sendQCtrl.errorFlush) {
        // Handle errorFlush
        rslt.workComp.setFromWorkReq(
          workReq = zipCachedWorkReqAndAck._2.workReq,
          dqpn = io.qpAttr.dqpn,
          status = workCompFlushStatus
        )
      } otherwise {
        // Handle coalesce and normal ACK
        rslt.workComp.setSuccessFromWorkReq(
          workReq = zipCachedWorkReqAndAck._2.workReq,
          dqpn = io.qpAttr.dqpn
        )
      }
      // TODO: verify when errorFlush, is zipCachedWorkReqAndAck still valid?
      rslt.ackValid := zipCachedWorkReqAndAck._3
      rslt.ack := zipCachedWorkReqAndAck._4
      rslt
    }

  // Handle response timeout retry
  val respTimer = Timeout(MAX_RESP_TIMEOUT)
  val respTimeOutThreshold = io.qpAttr.getRespTimeOut()
  val respTimeOutRetry = respTimeOutThreshold =/= INFINITE_RESP_TIMEOUT &&
    respTimer.counter.value > respTimeOutThreshold
  // TODO: should use io.rx.fire or io.rx.valid to clear response timer?
  when(
    io.sendQCtrl.wrongStateFlush || inputAckValid || !io.cachedWorkReqPop.valid || io.sendQCtrl.retryFlush
  ) {
    respTimer.clear()
  }

  // SQ into ERR state if fatal error
  io.nakNotifier.setNoErr()
  when(!io.sendQCtrl.wrongStateFlush && inputAckValid && isErrAck) {
    io.nakNotifier.setFromAeth(io.rx.aeth)
  }
  io.coalesceAckDone := io.sendQCtrl.wrongStateFlush && io.rx.fire

  // Retry notification
  io.retryNotifier.pulse := (respTimeOutRetry || hasExplicitRetry || hasImplicitRetry) && io.rx.fire
  io.retryNotifier.psnStart := io.rx.bth.psn
  io.retryNotifier.reason := RetryReason.NO_RETRY
  when(hasImplicitRetry) {
    io.retryNotifier.psnStart := io.cachedWorkReqPop.psnStart
    io.retryNotifier.reason := RetryReason.IMPLICIT_ACK
  } elsewhen (respTimeOutRetry) {
    io.retryNotifier.psnStart := io.cachedWorkReqPop.psnStart
    io.retryNotifier.reason := RetryReason.RESP_TIMEOUT
  } elsewhen (io.rx.aeth.isRnrNak()) {
    io.retryNotifier.reason := RetryReason.RNR
  } elsewhen (io.rx.aeth.isSeqNak()) {
    io.retryNotifier.reason := RetryReason.SEQ_ERR
  }
}

// TODO: check read response opcode sequence
// TODO: handle read/atomic WC
class ReadAtomicRespDmaReqInitiator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val workReqQuery = master(WorkReqCacheQueryBus())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val readRespDmaWriteReq = master(DmaWriteReqBus(busWidth))
    val atomicRespDmaWriteReq = master(DmaWriteReqBus(busWidth))
  }

  // Only send out WorkReqCache query when the input data is the first fragment of
  // the only or first read responses or atomic responses
  val workReqCacheQueryCond = (pktFragStream: Stream[Fragment[RdmaDataPkt]]) =>
    new Composite(pktFragStream, "queryCond") {
      val isReadFirstOrOnlyRespOrAtomicResp = (opcode: Bits) => {
        OpCode.isFirstOrOnlyReadRespPkt(opcode) ||
          OpCode.isAtomicRespPkt(opcode)
      }
      val rslt = pktFragStream.valid && pktFragStream.isFirst &&
        isReadFirstOrOnlyRespOrAtomicResp(pktFragStream.bth.opcode)
    }.rslt
  // Only join AddrCache query response when the input data is the last fragment of
  // the only or last read responses or atomic responses
  val addrCacheQueryRespJoinCond =
    (pktFragStream: Stream[Fragment[RdmaDataPkt]]) =>
      new Composite(pktFragStream, "queryCond") {
        val isReadLastOrOnlyRespOrAtomicResp = (opcode: Bits) => {
          OpCode.isLastOrOnlyReadRespPkt(opcode) ||
            OpCode.isAtomicRespPkt(opcode)
        }
        val rslt = pktFragStream.valid && pktFragStream.isLast &&
          isReadLastOrOnlyRespOrAtomicResp(pktFragStream.bth.opcode)
      }.rslt

  val (everyFirstInputPktFragStream, allInputPktFragStream) =
    ConditionalStreamFork2(
      io.rx.pktFrag.throwWhen(io.sendQCtrl.wrongStateFlush),
      forkCond = workReqCacheQueryCond(io.rx.pktFrag)
    )
  val rxAllInputPkgFragQueue = allInputPktFragStream
    .queueLowLatency(
      ADDR_CACHE_QUERY_DELAY_CYCLE + WORK_REQ_CACHE_QUERY_DELAY_CYCLE
    )
  val inputValid = rxAllInputPkgFragQueue.valid
  val inputPktFrag = rxAllInputPkgFragQueue.payload
  val isLastFrag = rxAllInputPkgFragQueue.isLast

  val rxEveryFirstInputPktFragQueue = everyFirstInputPktFragStream
    .queueLowLatency(ADDR_CACHE_QUERY_DELAY_CYCLE)
  io.workReqQuery.req << rxEveryFirstInputPktFragQueue
    .translateWith {
      val rslt = cloneOf(io.workReqQuery.req.payloadType)
      rslt.psn := rxEveryFirstInputPktFragQueue.bth.psn
      rslt
    }

  // TODO: verify it's correct
  val workReqIdReg = RegNextWhen(
    io.workReqQuery.resp.cachedWorkReq.workReq.id,
    cond = io.workReqQuery.resp.fire
  )
  val cachedWorkReq = io.workReqQuery.resp.cachedWorkReq
  io.addrCacheRead.req <-/< io.workReqQuery.resp
    .throwWhen(io.sendQCtrl.wrongStateFlush)
//    .continueWhen(io.rx.pktFrag.fire && (isReadFirstOrOnlyResp || isAtomicResp))
    .translateWith {
      val addrCacheReadReq = QpAddrCacheAgentReadReq()
      addrCacheReadReq.sqpn := io.qpAttr.sqpn
      addrCacheReadReq.psn := cachedWorkReq.psnStart
      addrCacheReadReq.key := cachedWorkReq.workReq.lkey
      addrCacheReadReq.pdId := io.qpAttr.pdId
      addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = False)
      addrCacheReadReq.accessType := AccessType.LOCAL_WRITE
      addrCacheReadReq.va := cachedWorkReq.workReq.laddr
      addrCacheReadReq.dataLenBytes := cachedWorkReq.workReq.lenBytes
      addrCacheReadReq
    }

  val joinStream =
    FragmentStreamConditionalJoinStream(
      rxAllInputPkgFragQueue,
      io.addrCacheRead.resp,
      joinCond = addrCacheQueryRespJoinCond(rxAllInputPkgFragQueue)
    )
  val txSel = UInt(2 bits)
  val (readRespIdx, atomicRespIdx, otherRespIdx) = (0, 1, 2)
  when(OpCode.isReadRespPkt(joinStream._1.bth.opcode)) {
    txSel := readRespIdx
  } elsewhen (OpCode.isAtomicRespPkt(joinStream._1.bth.opcode)) {
    txSel := atomicRespIdx
  } otherwise {
    txSel := otherRespIdx
    when(inputValid) {
      report(
        message =
          L"${REPORT_TIME} time: input packet should be read/atomic response, but opcode=${inputPktFrag.bth.opcode}",
        severity = FAILURE
      )
    }
  }
  val threeStreams = StreamDemux(joinStream, select = txSel, portCount = 3)
  StreamSink(NoData) << threeStreams(otherRespIdx).translateWith(NoData)

  io.readRespDmaWriteReq.req <-/< threeStreams(readRespIdx)
    .throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt =
        cloneOf(io.readRespDmaWriteReq.req.payloadType) //DmaWriteReq(busWidth)
      rslt.last := isLastFrag
      rslt.set(
        initiator = DmaInitiator.SQ_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        addr = joinStream._2.pa,
        workReqId = workReqIdReg,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      rslt
    }

  io.atomicRespDmaWriteReq.req <-/< threeStreams(atomicRespIdx)
    .throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt = cloneOf(io.readRespDmaWriteReq.req.payloadType)
      rslt.last := isLastFrag
      rslt.set(
        initiator = DmaInitiator.SQ_ATOMIC_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        addr = joinStream._2.pa,
        workReqId = workReqIdReg,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      rslt
    }
}

class WorkCompGen extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val workCompAndAck = slave(Stream(WorkCompAndAck()))
    val workCompPush = master(Stream(WorkComp()))
    val readRespDmaWriteResp = slave(DmaWriteRespBus())
    val atomicRespDmaWriteResp = slave(DmaWriteRespBus())
  }

  val inputWorkCompAndAckQueue =
    io.workCompAndAck.queueLowLatency(DMA_WRITE_DELAY_CYCLE)

  val inputWorkCompValid = inputWorkCompAndAckQueue.valid
  val inputDmaWriteRespValid = io.readRespDmaWriteResp.resp.valid

  val isReadWorkComp =
    WorkCompOpCode.isReadComp(inputWorkCompAndAckQueue.workComp.opcode)
  val isAtomicWorkComp =
    WorkCompOpCode.isAtomicComp(inputWorkCompAndAckQueue.workComp.opcode)

  io.workCompPush.valid := False
  io.workCompPush.payload := inputWorkCompAndAckQueue.payload.workComp
  inputWorkCompAndAckQueue.ready := io.workCompPush.fire
  io.readRespDmaWriteResp.resp.ready := False
  io.atomicRespDmaWriteResp.resp.ready := False

  val dmaWriteRespTimer = Timeout(DMA_WRITE_DELAY_CYCLE)
  when(io.sendQCtrl.errorFlush || io.workCompPush.fire || !inputWorkCompValid) {
    dmaWriteRespTimer.clear()
  }
  when(io.sendQCtrl.errorFlush) {
    io.workCompPush.valid := inputWorkCompValid
    io.readRespDmaWriteResp.resp.ready := io.readRespDmaWriteResp.resp.valid
  } elsewhen (isReadWorkComp) {
    // Read WC need to join with DMA write response
    io.workCompPush.valid := inputWorkCompValid && io.readRespDmaWriteResp.resp.psn === inputWorkCompAndAckQueue.ack.bth.psn
    io.readRespDmaWriteResp.resp.ready := io.workCompPush.fire

    when(
      PsnUtil.lt(
        io.readRespDmaWriteResp.resp.psn,
        inputWorkCompAndAckQueue.ack.bth.psn,
        io.qpAttr.npsn
      )
    ) {
      io.readRespDmaWriteResp.resp.ready := inputWorkCompValid
    }
    when(inputWorkCompValid) {
      assert(
        assertion = !dmaWriteRespTimer.state,
        message =
          L"${REPORT_TIME} time: DMA write for read response timeout, response PSN=${io.workCompAndAck.ack.bth.psn}",
        severity = FAILURE
      )
    }
  } elsewhen (isAtomicWorkComp) {
    // Atomic WC need to join with DMA write response
    io.workCompPush.valid := inputWorkCompValid && io.readRespDmaWriteResp.resp.psn === inputWorkCompAndAckQueue.ack.bth.psn
    io.atomicRespDmaWriteResp.resp.ready := io.workCompPush.fire

    when(inputWorkCompValid) {
      assert(
        assertion = !dmaWriteRespTimer.state,
        message =
          L"${REPORT_TIME} time: DMA write for atomic response timeout, response PSN=${io.workCompAndAck.ack.bth.psn}",
        severity = FAILURE
      )
    }
  } otherwise {
    io.workCompPush.valid := inputWorkCompValid
  }
}
