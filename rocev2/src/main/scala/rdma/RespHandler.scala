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
    val nakNotify = out(SqNakNotifier())
    val retryNotify = out(SqRetryNotifier())
    val coalesceAckDone = out(Bool())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
    val workReqQuery = master(WorkReqCacheQueryBus())
    val workComp = master(Stream(WorkComp()))
    // Save read/atomic response data to main memory
    val readRespDmaWrite = master(DmaWriteBus(busWidth))
    val atomicRespDmaWrite = master(DmaWriteBus(busWidth))
  }

  val respVerifier = new RespVerifier(busWidth)
  respVerifier.io.qpAttr := io.qpAttr
  respVerifier.io.sendQCtrl := io.sendQCtrl
  respVerifier.io.rx << io.rx
  respVerifier.io.cachedWorkReq << io.cachedWorkReqPop.asFlow
  io.nakNotify := respVerifier.io.nakNotify

  val ackHandler = new AckHandler
  ackHandler.io.qpAttr := io.qpAttr
  ackHandler.io.sendQCtrl := io.sendQCtrl
  ackHandler.io.rx << respVerifier.io.txRespWithAeth
  ackHandler.io.cachedWorkReqPop << io.cachedWorkReqPop
  io.coalesceAckDone := ackHandler.io.coalesceAckDone

  io.retryNotify := respVerifier.io.respTimeOutRetryNotify
    .merge(ackHandler.io.retryNotify, io.qpAttr.npsn)

  val readAtomicRespDmaReqInitiator =
    new ReadAtomicRespDmaReqInitiator(busWidth)
  readAtomicRespDmaReqInitiator.io.qpAttr := io.qpAttr
  readAtomicRespDmaReqInitiator.io.sendQCtrl := io.sendQCtrl
  readAtomicRespDmaReqInitiator.io.rx << respVerifier.io.txReadAtomicResp
  io.workReqQuery << readAtomicRespDmaReqInitiator.io.workReqQuery
  io.addrCacheRead << readAtomicRespDmaReqInitiator.io.addrCacheRead
  io.readRespDmaWrite.req << readAtomicRespDmaReqInitiator.io.readRespDmaWriteReq.req
  io.atomicRespDmaWrite.req << readAtomicRespDmaReqInitiator.io.atomicRespDmaWriteReq.req

  val workCompGen = new WorkCompGen
  workCompGen.io.qpAttr := io.qpAttr
  workCompGen.io.sendQCtrl := io.sendQCtrl
  workCompGen.io.workCompAndAck << ackHandler.io.workCompAndAck
  workCompGen.io.readRespDmaWriteResp.resp << io.readRespDmaWrite.resp
  workCompGen.io.atomicRespDmaWriteResp.resp << io.atomicRespDmaWrite.resp
  io.workComp << workCompGen.io.workCompPush
}

// Discard all invalid responses:
// - NAK with reserved code;
// - Target QP not exists; dropped by head verifier
// - Ghost ACK;
class RespVerifier(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val cachedWorkReq = slave(Flow(CachedWorkReq()))
    val respTimeOutRetryNotify = out(SqRetryNotifier())
    val nakNotify = out(SqNakNotifier())
    val txRespWithAeth = master(Stream(Acknowledge()))
    val txReadAtomicResp = master(RdmaDataBus(busWidth))
  }

  val inputValid = io.rx.pktFrag.valid
  val inputPktFrag = io.rx.pktFrag.payload
  when(inputValid) {
    assert(
      assertion = OpCode.isRespPkt(inputPktFrag.bth.opcode),
      message =
        L"RespVerifier received non-response packet with opcode=${inputPktFrag.bth.opcode}, PSN=${inputPktFrag.bth.psn}",
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
  val isNormalAck = acknowledge.aeth.isNormalAck()
  val isRetryNak = acknowledge.aeth.isRetryNak()
  val isErrAck = acknowledge.aeth.isErrAck()

  val hasAeth = OpCode.respPktHasAeth(inputPktFrag.bth.opcode)
  when(hasAeth) {
    hasReservedCode := inputValid && acknowledge.aeth.isReserved()
    when(hasReservedCode) {
      report(
        message =
          L"acknowledge has reserved code or value, PSN=${acknowledge.bth.psn}, aeth.code=${acknowledge.aeth.code}, aeth.value=${acknowledge.aeth.value}",
        severity = FAILURE
      )
    }
  }

  val isReadResp =
    OpCode.isReadRespPkt(io.rx.pktFrag.bth.opcode)
  val isAtomicResp =
    OpCode.isAtomicRespPkt(io.rx.pktFrag.bth.opcode)

//  val txSel = UInt(1 bit)
//  val (readAtomicAckIdx, otherAckIdx) = (0, 1)
//  when(isReadResp || isAtomicResp) {
//    txSel := readAtomicAckIdx
//  } otherwise {
//    txSel := otherAckIdx
//  }
//  val twoStreams = StreamDemux(
//    io.rx.pktFrag.throwWhen(io.sendQCtrl.wrongStateFlush || hasReservedCode),
//    select = txSel,
//    portCount = 2
//  )
  val (resp4ReadAtomic, respWithAeth) = StreamFork2(
    io.rx.pktFrag.throwWhen(io.sendQCtrl.wrongStateFlush || hasReservedCode)
  )
  io.txReadAtomicResp.pktFrag <-/< resp4ReadAtomic.takeWhen(
    isReadResp || isAtomicResp
  )

  // TODO: does it need to flush whole SQ when retry triggered?
  val aethQueue =
    StreamFifoLowLatency(Acknowledge(), depth = MAX_COALESCE_ACK_NUM)
  aethQueue.io.push << respWithAeth
    .takeWhen(hasAeth)
    .translateWith(acknowledge)
  // TODO: verify that once retry started, discard all responses before send out the retry request
  aethQueue.io.flush := io.sendQCtrl.retryFlush || io.sendQCtrl.wrongStateFlush
  io.txRespWithAeth <-/< aethQueue.io.pop
    .takeWhen(OpCode.isNonReadAtomicRespPkt(inputPktFrag.bth.opcode))
    .translateWith(acknowledge)

  io.nakNotify.setNoErr()
  when(hasAeth && isErrAck && respWithAeth.fire) {
    io.nakNotify.setFromAeth(acknowledge.aeth)
  }

  // Handle response timeout retry
  val respTimer = Timeout(MAX_RESP_TIMEOUT)
  val respTimeOutRetry = respTimer.counter.value > io.qpAttr.getRespTimeOut()
  // TODO: should use io.rx.fire or io.rx.valid to clear response timer?
  when(
    io.sendQCtrl.wrongStateFlush || inputValid || !io.cachedWorkReq.valid || io.sendQCtrl.retryFlush
  ) {
    respTimer.clear()
  }

  io.respTimeOutRetryNotify.pulse := respTimeOutRetry
  io.respTimeOutRetryNotify.psnStart := io.cachedWorkReq.psnStart
  io.respTimeOutRetryNotify.reason := RetryReason.RESP_TIMEOUT
}

class AckHandler extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val rx = slave(Stream(Acknowledge()))
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
    val retryNotify = out(SqRetryNotifier())
    val coalesceAckDone = out(Bool())
//    val nakNotify = out(NakNotifier())
    val workCompAndAck = master(Stream(WorkCompAndAck()))
  }
  val inputAckValid = io.rx.valid
  val inputCachedWorkReqValid = io.cachedWorkReqPop.valid

  val isNormalAck = io.rx.aeth.isNormalAck()
  val isRetryNak = io.rx.aeth.isRetryNak()
  val isErrAck = io.rx.aeth.isErrAck()
  val isDupAck = {
    PsnUtil.lt(io.rx.bth.psn, io.cachedWorkReqPop.psnStart, io.qpAttr.npsn)
  }

  val cachedWorkReqPsnEnd =
    io.cachedWorkReqPop.psnStart + io.cachedWorkReqPop.pktNum - 1
  val isTargetWorkReq =
    PsnUtil.gte(io.rx.bth.psn, io.cachedWorkReqPop.psnStart, io.qpAttr.npsn) &&
      PsnUtil.lte(io.rx.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val isWholeWorkReqAcked = io.rx.bth.psn === cachedWorkReqPsnEnd

  val isReadWorkReq =
    WorkReqOpCode.isReadReq(io.cachedWorkReqPop.workReq.opcode)
  val isAtomicWorkReq =
    WorkReqOpCode.isAtomicReq(io.cachedWorkReqPop.workReq.opcode)

  val needCoalesceAck =
    PsnUtil.gt(io.rx.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val implicitRetry = (isReadWorkReq || isAtomicWorkReq) && needCoalesceAck
  val explicitRetry = isTargetWorkReq && isRetryNak
//  val needRetry = implicitRetry || explicitRetry

  val workCompAndAck = cloneOf(io.workCompAndAck)
  workCompAndAck.valid := False
  workCompAndAck.ackValid := inputAckValid
  workCompAndAck.ack := io.rx
  workCompAndAck.workComp.setSuccessFromWorkReq(
    workReq = io.cachedWorkReqPop.workReq,
    dqpn = io.qpAttr.dqpn
  )

  val workCompFlushStatus = Bits(WC_STATUS_WIDTH bits)
  // TODO: what status should the read/atomic requests before error ACK have?
  workCompFlushStatus := WorkCompStatus.WR_FLUSH_ERR.id

  io.rx.ready := False
  io.cachedWorkReqPop.ready := False
  io.coalesceAckDone := (isTargetWorkReq || implicitRetry) && io.rx.fire
  io.retryNotify.pulse := (explicitRetry || implicitRetry) && io.rx.fire
  io.retryNotify.psnStart := io.rx.bth.psn
  io.retryNotify.reason := RetryReason.RETRY_ACK
  when(implicitRetry) {
    io.retryNotify.psnStart := io.cachedWorkReqPop.psnStart
    io.retryNotify.reason := RetryReason.IMPLICIT_ACK
  }

  when(io.retryNotify.pulse) {
    assert(
      assertion = io.coalesceAckDone,
      message =
        L"when start to retry, no matter explicitRetry=${explicitRetry} or implicitRetry=${implicitRetry}, io.retryNotify.pulse=${io.retryNotify.pulse} must have coalesce ACK done, io.coalesceAckDone=${io.coalesceAckDone}",
      severity = FAILURE
    )
  }

  // Handle error ACK received case
  when(io.sendQCtrl.wrongStateFlush) {
    when(isTargetWorkReq) {
      // Handle error ACK
      workCompAndAck.workComp.setFromWorkReq(
        workReq = io.cachedWorkReqPop.workReq,
        dqpn = io.qpAttr.dqpn,
        status = io.rx.aeth.toWorkCompStatus()
      )
      workCompAndAck.valid := io.cachedWorkReqPop.valid
      io.rx.ready := workCompAndAck.fire
      io.cachedWorkReqPop.ready := workCompAndAck.fire

      when(inputAckValid) {
        assert(
          assertion = isErrAck,
          message =
            L"should handle error ACK when io.sendQCtrl.wrongStateFlush=${io.sendQCtrl.wrongStateFlush}, isTargetWorkReq=${isTargetWorkReq}",
          severity = FAILURE
        )
      }
    } elsewhen (needCoalesceAck && !implicitRetry) {
      // Coalesce ACK, only generate WC if WR needs signaled
      workCompAndAck.valid := io.cachedWorkReqPop.valid && io.cachedWorkReqPop.workReq.signaled
      io.cachedWorkReqPop.ready := io.cachedWorkReqPop.workReq.signaled ? workCompAndAck.fire | io.cachedWorkReqPop.valid
    }
  }
  // Flush pending WR after coalesce ACK or error ACK handled
  when(io.sendQCtrl.errorFlush) {
    workCompAndAck.workComp.setFromWorkReq(
      workReq = io.cachedWorkReqPop.workReq,
      dqpn = io.qpAttr.dqpn,
      status = workCompFlushStatus
    )
    io.rx.ready := inputAckValid // Discard all ACKs
    workCompAndAck.ackValid := False
    workCompAndAck.valid := inputCachedWorkReqValid
    io.cachedWorkReqPop.ready := workCompAndAck.fire
  }

  // Handle normal and retry ACKs
  when(isTargetWorkReq) {
    when(isNormalAck) {
      // Handle normal ACK, distinguish ACK to whole WR or partial WR
      io.rx.ready := isWholeWorkReqAcked ? workCompAndAck.fire | inputAckValid
      workCompAndAck.valid := inputAckValid && isWholeWorkReqAcked
      io.cachedWorkReqPop.ready := isWholeWorkReqAcked ? workCompAndAck.fire | False
    } elsewhen (isRetryNak) {
      // Handle explicit retry, send out retry notification to QpCtrl if no error ACK
      // TODO: handle retry number exceeds 3
      io.rx.ready := inputAckValid && explicitRetry
    }
  } elsewhen (needCoalesceAck && !implicitRetry) {
    // Coalesce ACK, only generate WC if WR needs signaled
    workCompAndAck.valid := inputAckValid && inputCachedWorkReqValid && io.cachedWorkReqPop.workReq.signaled
    io.cachedWorkReqPop.ready := io.cachedWorkReqPop.workReq.signaled ? workCompAndAck.fire | inputCachedWorkReqValid
  } elsewhen (implicitRetry && !io.sendQCtrl.wrongStateFlush) {
    // Handle implicit retry, send out retry notification to QpCtrl if no error ACK
    io.rx.ready := inputAckValid && implicitRetry
    // TODO: handle retry number exceeds 3
  }

  // Discard ACK because of either no pending WR or duplicate ACK
  when(!inputCachedWorkReqValid || isDupAck) {
    // TODO: should discard duplicate NAK?
    io.rx.ready := inputAckValid
    when(inputAckValid) {
      assert(
        assertion = isErrAck || isRetryNak,
        message =
          L"received duplicate error ACK or retry ACK, PSN=${io.rx.bth.psn}, opcode=${io.rx.bth.opcode}",
        severity = FAILURE
      )
    }
  }

  io.workCompAndAck <-/< workCompAndAck
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

  val rxQueue = io.rx.pktFrag
    .throwWhen(io.sendQCtrl.errorFlush)
    .queueLowLatency(
      ADDR_CACHE_QUERY_DELAY_CYCLE + WORK_REQ_CACHE_QUERY_DELAY_CYCLE
    )
  val inputValid = rxQueue.valid
  val inputPktFrag = rxQueue.payload
  val isLastFrag = rxQueue.isLast

  val isReadFirstOrOnlyResp =
    OpCode.isFirstOrOnlyReadRespPkt(inputPktFrag.bth.opcode)
  val isAtomicResp = OpCode.isAtomicRespPkt(inputPktFrag.bth.opcode)

  io.workReqQuery.req <-/< SignalEdgeDrivenStream(
    inputValid && (isReadFirstOrOnlyResp || isAtomicResp)
  ).throwWhen(io.sendQCtrl.errorFlush)
    .translateWith {
      val rslt = cloneOf(io.workReqQuery.req.payloadType)
      rslt.psn := inputPktFrag.bth.psn
      rslt
    }

  // TODO: verify it's correct
  val workReqIdReg = RegNextWhen(
    io.workReqQuery.resp.cachedWorkReq.workReq.id,
    cond = io.workReqQuery.resp.fire
  )
  val cachedWorkReq = io.workReqQuery.resp.cachedWorkReq
  io.addrCacheRead.req <-/< io.workReqQuery.resp
    .throwWhen(io.sendQCtrl.errorFlush)
    .continueWhen(io.rx.pktFrag.fire && (isReadFirstOrOnlyResp || isAtomicResp))
    .translateWith {
      val addrCacheReadReq = QpAddrCacheAgentReadReq()
      addrCacheReadReq.sqpn := io.qpAttr.sqpn
      addrCacheReadReq.psn := inputPktFrag.bth.psn
      addrCacheReadReq.key := cachedWorkReq.workReq.lkey
      addrCacheReadReq.pdId := io.qpAttr.pdId
      addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = False)
      addrCacheReadReq.accessType := AccessType.LOCAL_WRITE.id
      addrCacheReadReq.va := cachedWorkReq.workReq.laddr
      addrCacheReadReq.dataLenBytes := cachedWorkReq.workReq.lenBytes
      addrCacheReadReq
    }

  val joinStream = FragmentStreamJoinStream(rxQueue, io.addrCacheRead.resp)
  val txSel = UInt(2 bits)
  val (readRespIdx, atomicRespIdx, otherRespIdx) = (0, 1, 2)
  when(OpCode.isReadRespPkt(joinStream._1.bth.opcode)) {
    txSel := readRespIdx
  } elsewhen (OpCode.isAtomicRespPkt(joinStream._1.bth.opcode)) {
    txSel := atomicRespIdx
  } otherwise {
    txSel := otherRespIdx
    report(
      message =
        L"input packet should be read/atomic response, but opcode=${inputPktFrag.bth.opcode}",
      severity = FAILURE
    )
  }
  val threeStreams = StreamDemux(joinStream, select = txSel, portCount = 3)
  StreamSink(NoData) << threeStreams(otherRespIdx).translateWith(NoData)

  io.readRespDmaWriteReq.req <-/< threeStreams(readRespIdx)
    .throwWhen(io.sendQCtrl.errorFlush)
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
    .throwWhen(io.sendQCtrl.errorFlush)
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
          L"DMA write for read response timeout, response PSN=${io.workCompAndAck.ack.bth.psn}",
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
          L"DMA write for atomic response timeout, response PSN=${io.workCompAndAck.ack.bth.psn}",
        severity = FAILURE
      )
    }
  } otherwise {
    io.workCompPush.valid := inputWorkCompValid
  }
}
