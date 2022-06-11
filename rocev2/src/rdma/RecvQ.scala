package rdma

import spinal.core._
import spinal.lib._
import RdmaConstants._
import ConstantSettings._

import scala.language.postfixOps

// There is no alignment requirement for the source or
// destination buffers of a SEND message.
//
// pp. 291 spec 1.4
// A responder shall execute SEND requests, RDMA WRITE requests and ATOMIC
// Operation requests in the message order in which they are received.
// If the request is for an unsupported function or service,
// the appropriate response (for example, a NAK message, silent discard, or
// logging of the error) shall also be generated in the PSN order in which it
// was received.

// FIXME: atomic might not access memory in PSN order w.r.t. send and write

// There is no alignment requirement for the source or
// destination buffers of an RDMA READ message.

// RQ executes Send, Write, Atomic in order;
// RQ can delay Read execution;
// Completion of Send and Write at RQ is in PSN order,
// but not imply previous Read is complete unless fenced;
// RQ saves Atomic (Req & Result) and Read (Req only) in
// Queue Context, size as # pending Read/Atomic;
// TODO: RQ should send explicit ACK to SQ if it received many un-signaled requests
class RecvQ(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val psnInc = out(RqPsnInc())
    val notifier = out(RqNotifier())
    val rxQCtrl = in(RxQCtrl())
    val rxWorkReq = slave(Stream(RxWorkReq()))
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val dma = master(RqDmaBus(busWidth))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val sendWriteWorkComp = master(Stream(WorkComp()))
  }

  val readAtomicRstCache =
    new ReadAtomicRstCache(MAX_PENDING_READ_ATOMIC_REQ_NUM)
  readAtomicRstCache.io.flush := io.rxQCtrl.stateErrFlush

  val reqCommCheck = new ReqCommCheck(busWidth)
  reqCommCheck.io.qpAttr := io.qpAttr
  reqCommCheck.io.rxQCtrl := io.rxQCtrl
  reqCommCheck.io.readAtomicRstCacheOccupancy := readAtomicRstCache.io.occupancy
  reqCommCheck.io.rx << io.rx
  io.psnInc.epsn := reqCommCheck.io.epsnInc
  io.notifier.clearRnrOrNakSeq := reqCommCheck.io.clearRnrOrNakSeq

  val reqRnrCheck = new ReqRnrCheck(busWidth)
  reqRnrCheck.io.qpAttr := io.qpAttr
  reqRnrCheck.io.rxQCtrl := io.rxQCtrl
  reqRnrCheck.io.rxWorkReq << io.rxWorkReq
  reqRnrCheck.io.rx << reqCommCheck.io.tx

  val dupReqLogic = new Area {
    val dupReqHandlerAndReadAtomicRstCacheQuery =
      new DupReqHandlerAndReadAtomicRstCacheQuery(busWidth)
    dupReqHandlerAndReadAtomicRstCacheQuery.io.qpAttr := io.qpAttr
    dupReqHandlerAndReadAtomicRstCacheQuery.io.rxQCtrl := io.rxQCtrl
    dupReqHandlerAndReadAtomicRstCacheQuery.io.rx << reqRnrCheck.io.txDupReq
    readAtomicRstCache.io.queryPort4DupReq << dupReqHandlerAndReadAtomicRstCacheQuery.io.readAtomicRstCache

    val dupReadDmaReqBuilder = new DupReadDmaReqBuilder(busWidth)
    dupReadDmaReqBuilder.io.qpAttr := io.qpAttr
    dupReadDmaReqBuilder.io.rxQCtrl := io.rxQCtrl
    dupReadDmaReqBuilder.io.rxDupReadReqAndRstCacheData << dupReqHandlerAndReadAtomicRstCacheQuery.io.dupReadReqAndRstCacheData
  }

  // TODO: make sure that when retry occurred, it only needs to flush reqValidateLogic
  // TODO: make sure that when error occurred, it needs to flush both reqCommCheck and reqValidateLogic
  val reqValidateLogic = new Area {
    val reqAddrInfoExtractor = new ReqAddrInfoExtractor(busWidth)
    reqAddrInfoExtractor.io.qpAttr := io.qpAttr
    reqAddrInfoExtractor.io.rxQCtrl := io.rxQCtrl
    reqAddrInfoExtractor.io.rx << reqRnrCheck.io.tx

    val reqAddrValidator = new ReqAddrValidator(busWidth)
    reqAddrValidator.io.qpAttr := io.qpAttr
    reqAddrValidator.io.rxQCtrl := io.rxQCtrl
    reqAddrValidator.io.rx << reqAddrInfoExtractor.io.tx
    io.addrCacheRead << reqAddrValidator.io.addrCacheRead

    val reqPktLenCheck = new ReqPktLenCheck(busWidth)
    reqPktLenCheck.io.qpAttr := io.qpAttr
    reqPktLenCheck.io.rxQCtrl := io.rxQCtrl
    reqPktLenCheck.io.rx << reqAddrValidator.io.tx

    val reqSplitterAndNakGen = new ReqSplitterAndNakGen(busWidth)
    reqSplitterAndNakGen.io.qpAttr := io.qpAttr
    reqSplitterAndNakGen.io.rxQCtrl := io.rxQCtrl
    reqSplitterAndNakGen.io.rx << reqPktLenCheck.io.tx
    io.notifier.nak := reqSplitterAndNakGen.io.nakNotifier
  }

  val dmaReqLogic = new Area {
    val rqSendWriteDmaReqInitiator = new RqSendWriteDmaReqInitiator(busWidth)
    rqSendWriteDmaReqInitiator.io.qpAttr := io.qpAttr
    rqSendWriteDmaReqInitiator.io.rxQCtrl := io.rxQCtrl
    rqSendWriteDmaReqInitiator.io.rx << reqValidateLogic.reqSplitterAndNakGen.io.txSendWrite
    io.dma.sendWrite.req << rqSendWriteDmaReqInitiator.io.sendWriteDmaReq.req

    val rqReadAtomicDmaReqBuilder = new RqReadAtomicDmaReqBuilder(busWidth)
    rqReadAtomicDmaReqBuilder.io.qpAttr := io.qpAttr
    rqReadAtomicDmaReqBuilder.io.rxQCtrl := io.rxQCtrl
    rqReadAtomicDmaReqBuilder.io.rx << reqValidateLogic.reqSplitterAndNakGen.io.txReadAtomic
    readAtomicRstCache.io.push << rqReadAtomicDmaReqBuilder.io.readAtomicRstCachePush

    val readDmaReqInitiator = new ReadDmaReqInitiator
    readDmaReqInitiator.io.rxQCtrl := io.rxQCtrl
    readDmaReqInitiator.io.readDmaReqAndRstCacheData << rqReadAtomicDmaReqBuilder.io.readDmaReqAndRstCacheData
    readDmaReqInitiator.io.dupReadDmaReqAndRstCacheData << dupReqLogic.dupReadDmaReqBuilder.io.dupReadDmaReqAndRstCacheData
    io.dma.read.req << readDmaReqInitiator.io.readDmaReq.req
//    io.dma.atomic.rd.req << readAtomicDmaReqInitiator.io.atomicDmaReq.req
  }

  val respGenLogic = new Area {
    val sendWriteRespGenerator = new SendWriteRespGenerator(busWidth)
    sendWriteRespGenerator.io.qpAttr := io.qpAttr
    sendWriteRespGenerator.io.rxQCtrl := io.rxQCtrl
    sendWriteRespGenerator.io.rx << dmaReqLogic.rqSendWriteDmaReqInitiator.io.txSendWrite

    val rqReadDmaRespHandler = new RqReadDmaRespHandler(busWidth)
//    rqReadDmaRespHandler.io.qpAttr := io.qpAttr
    rqReadDmaRespHandler.io.rxQCtrl := io.rxQCtrl
//    rqReadDmaRespHandler.io.rx << dmaReqLogic.readAtomicReqExtractor.io.tx
    rqReadDmaRespHandler.io.dmaReadResp.resp << io.dma.read.resp
    rqReadDmaRespHandler.io.readRstCacheData << dmaReqLogic.readDmaReqInitiator.io.readRstCacheData
//    readAtomicRstCache.io.queryPort4DmaReadResp << rqReadDmaRespHandler.io.readAtomicRstCacheQuery

    val readRespSegment = new ReadRespSegment(busWidth)
    readRespSegment.io.qpAttr := io.qpAttr
    readRespSegment.io.rxQCtrl := io.rxQCtrl
    readRespSegment.io.readRstCacheDataAndDmaReadResp << rqReadDmaRespHandler.io.readRstCacheDataAndDmaReadResp

    val readRespGenerator = new ReadRespGenerator(busWidth)
    readRespGenerator.io.qpAttr := io.qpAttr
    readRespGenerator.io.rxQCtrl := io.rxQCtrl
    readRespGenerator.io.readRstCacheDataAndDmaReadRespSegment << readRespSegment.io.readRstCacheDataAndDmaReadRespSegment

    val atomicRespGenerator = new AtomicRespGenerator(busWidth)
    atomicRespGenerator.io.qpAttr := io.qpAttr
    atomicRespGenerator.io.rxQCtrl := io.rxQCtrl
    atomicRespGenerator.io.atomicDmaReqAndRstCacheData << dmaReqLogic.rqReadAtomicDmaReqBuilder.io.atomicDmaReqAndRstCacheData
    io.dma.atomic << atomicRespGenerator.io.dma
  }

  val rqSendWriteWorkCompGenerator = new RqSendWriteWorkCompGenerator(busWidth)
  rqSendWriteWorkCompGenerator.io.qpAttr := io.qpAttr
  rqSendWriteWorkCompGenerator.io.rxQCtrl := io.rxQCtrl
  rqSendWriteWorkCompGenerator.io.dmaWriteResp.resp << io.dma.sendWrite.resp
  rqSendWriteWorkCompGenerator.io.rx << respGenLogic.sendWriteRespGenerator.io.txSendWriteReq
//  rqSendWriteWorkCompGenerator.io.sendWriteWorkCompAndAck << respGenLogic.sendWriteRespGenerator.io.sendWriteWorkCompAndAck
  io.sendWriteWorkComp << rqSendWriteWorkCompGenerator.io.sendWriteWorkCompOut

  val rqOut = new RqOut(busWidth)
  rqOut.io.qpAttr := io.qpAttr
  rqOut.io.rxQCtrl := io.rxQCtrl
  rqOut.io.outPsnRangeFifoPush << reqValidateLogic.reqAddrInfoExtractor.io.rqOutPsnRangeFifoPush
  rqOut.io.rxSendWriteResp << respGenLogic.sendWriteRespGenerator.io.tx
  rqOut.io.rxReadResp << respGenLogic.readRespGenerator.io.txReadResp
  rqOut.io.rxAtomicResp << respGenLogic.atomicRespGenerator.io.tx
  rqOut.io.rxDupSendWriteResp << dupReqLogic.dupReqHandlerAndReadAtomicRstCacheQuery.io.txDupSendWriteResp
  rqOut.io.rxDupReadResp << respGenLogic.readRespGenerator.io.txDupReadResp
  rqOut.io.rxDupAtomicResp << dupReqLogic.dupReqHandlerAndReadAtomicRstCacheQuery.io.txDupAtomicResp
  rqOut.io.rxErrResp << reqValidateLogic.reqSplitterAndNakGen.io.txErrResp
  rqOut.io.readAtomicRstCachePop << readAtomicRstCache.io.pop
  io.psnInc.opsn := rqOut.io.opsnInc
  io.tx << rqOut.io.tx
}

// PSN == ePSN, otherwise NAK-Seq;
// OpCode sequence, otherwise NAK-Inv Req;
// OpCode functionality is supported, otherwise NAK-Inv Req;
// First/Middle packets have padCnt == 0, otherwise NAK-Inv Req;
// Queue Context has resource for Read/Atomic, otherwise NAK-Inv Req;
// RKey, virtual address, DMA length (or packet size) match MR range and access type, otherwise NAK-Rmt Acc:
// - for Write, the length check is per packet basis, based on LRH:PktLen field;
// - for Read, the length check is based on RETH:DMA Length field;
// - no RKey check for 0-sized Write/Read;
// Length check, otherwise NAK-Inv Req:
// - for Send, the length check is based on LRH:PktLen field;
// - First/Middle packet length == PMTU;
// - Only packet length 0 <= len <= PMTU;
// - Last packet length 1 <= len <= PMTU;
// - for Write, check received data size == DMALen at last packet;
// - for Write/Read, check 0 <= DMALen <= 2^31;
// RQ local error detected, NAK-Rmt Op;
class ReqCommCheck(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val epsnInc = out(EPsnInc())
//    val nakNotifier = out(NakNotifier())
    val clearRnrOrNakSeq = out(RnrNakSeqClear())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RqReqCommCheckRstBus(busWidth))
//    val txDupReq = master(RdmaDataBus(busWidth))
    val readAtomicRstCacheOccupancy = in(
      UInt(log2Up(MAX_PENDING_READ_ATOMIC_REQ_NUM + 1) bits)
    )
  }

  val checkStage = new Area {
    val inputValid = io.rx.pktFrag.valid
    val inputPktFrag = io.rx.pktFrag.fragment
    val isLastFrag = io.rx.pktFrag.last

    // The pending request is ePSN - oPSN
    val pendingReqNum = PsnUtil.diff(io.qpAttr.epsn, io.qpAttr.rqOutPsn)
//    when(inputValid) {
//      assert(
//        assertion = pendingReqNum <= io.qpAttr.getMaxPendingWorkReqNum(),
//        message = L"${REPORT_TIME} time: pendingReqNum=${pendingReqNum} exceeds io.qpAttr.getMaxPendingWorkReqNum()=${io.qpAttr.getMaxPendingWorkReqNum()}".toSeq,
//        severity = ERROR
//      )
//    }

    // PSN sequence check
    val isPsnExpected = Bool()
    // The duplicate request is PSN < ePSN
    val isDupReq = Bool()

    val ePsnCmpRst = PsnUtil.cmp(
      psnA = inputPktFrag.bth.psn,
      psnB = io.qpAttr.epsn,
      curPsn = io.qpAttr.epsn
    )
    // The pending request is oPSN < PSN < ePSN
    val isDupPendingReq = False
    val isLargerThanOPsn = PsnUtil.gt(
      psnA = inputPktFrag.bth.psn,
      psnB = io.qpAttr.rqOutPsn,
      curPsn = io.qpAttr.epsn
    )

    switch(ePsnCmpRst) {
      is(PsnCompResult.GREATER) {
        isPsnExpected := False
        isDupReq := False
      }
      is(PsnCompResult.LESSER) {
        isPsnExpected := False
        isDupPendingReq := inputValid && isLargerThanOPsn
        isDupReq := inputValid && !isLargerThanOPsn
      }
      default { // PsnCompResult.EQUAL
        isPsnExpected := inputValid
        isDupReq := False
      }
    }
    // OpCode sequence check
    val isOpSeqCheckPass =
      OpCodeSeq.checkReqSeq(io.qpAttr.rqPreReqOpCode, inputPktFrag.bth.opcode)
    // Is valid request opcode?
    val isSupportedOpCode = OpCode.isValidCode(inputPktFrag.bth.opcode) &&
      Transports.isSupportedType(inputPktFrag.bth.transport)
    // Packet padding count check
    val isPadCntCheckPass = reqPadCountCheck(
      inputPktFrag.bth.opcode,
      inputPktFrag.bth.padCnt,
      inputPktFrag.mty,
      isLastFrag,
      busWidth
    )

    // TODO: should RQ check for # of pending requests?
    // Check for # of pending read/atomic requests
    val isReadOrAtomicReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
    val isReadAtomicRstCacheFull = inputValid && isReadOrAtomicReq &&
      io.readAtomicRstCacheOccupancy >=
      io.qpAttr.getMaxPendingReadAtomicWorkReqNum()

    // TODO: should discard duplicate pending requests?
    when(isDupPendingReq) {
      report(
        message =
          L"${REPORT_TIME} time: duplicated pending request received with PSN=${inputPktFrag.bth.psn} and opcode=${inputPktFrag.bth.opcode}, RQ opsn=${io.qpAttr.rqOutPsn}, epsn=${io.qpAttr.epsn}, maybe it should increase SQ timeout threshold".toSeq,
        severity = FAILURE
      )
    }
    val output =
      io.rx.pktFrag
        .throwWhen(isDupPendingReq)
        .translateWith { // No flush this stage
          val result = Fragment(RqReqCheckInternalOutput(busWidth))
          result.pktFrag := inputPktFrag
          result.checkRst.isPsnExpected := isPsnExpected
          result.checkRst.isDupReq := isDupReq
          result.checkRst.isOpSeqCheckPass := isOpSeqCheckPass
          result.checkRst.isSupportedOpCode := isSupportedOpCode
          result.checkRst.isPadCntCheckPass := isPadCntCheckPass
          result.checkRst.isReadAtomicRstCacheFull := isReadAtomicRstCacheFull
          result.checkRst.epsn := io.qpAttr.epsn
          result.last := io.rx.pktFrag.last
          result
        }

    // Increase ePSN
//    val isExpectedPkt = inputValid && isPsnExpected && !isDupReq
    io.epsnInc.inc := isPsnExpected && output.lastFire // Only update ePSN when each request packet ended
    io.epsnInc.incVal := ePsnIncrease(inputPktFrag, io.qpAttr.pmtu)
    io.epsnInc.preReqOpCode := inputPktFrag.bth.opcode

    // Clear RNR or NAK SEQ if any
    io.clearRnrOrNakSeq.pulse := (io.rxQCtrl.rnrFlush || io.rxQCtrl.nakSeqTrigger) && isPsnExpected
  }

  val outputStage = new Area {
    val input = cloneOf(checkStage.output)
    input <-/< checkStage.output

    val isPsnExpected = input.checkRst.isPsnExpected
    val isDupReq = input.checkRst.isDupReq
    val isCheckPass =
      input.checkRst.isOpSeqCheckPass && input.checkRst.isSupportedOpCode &&
        input.checkRst.isPadCntCheckPass && !input.checkRst.isReadAtomicRstCacheFull

    val hasNak = False
    val ackAeth = AETH().set(AckType.NORMAL)
    when(input.valid) {
      when(!isPsnExpected || isDupReq) {
        hasNak := True
        ackAeth.set(AckType.NAK_SEQ)
      } elsewhen (!isCheckPass) {
        hasNak := True
        ackAeth.set(AckType.NAK_INV)
      } otherwise {
        assert(
          assertion = isPsnExpected && isCheckPass && !isDupReq,
          message =
            L"${REPORT_TIME} time: isPsnExpected=${isPsnExpected} should be true, isCheckPass=${isCheckPass} should be true, isDupReq=${isDupReq} should be false".toSeq,
          severity = FAILURE
        )
      }
    }

    io.tx.checkRst <-/< input.throwWhen(io.rxQCtrl.flush).translateWith {
      val result = cloneOf(io.tx.checkRst.payloadType)
      result.pktFrag := input.pktFrag
      result.preOpCode := io.qpAttr.rqPreReqOpCode
      result.hasNak := hasNak
      result.ackAeth := ackAeth
      result.last := input.last
      result
    }
  }
}

class ReqRnrCheck(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rxWorkReq = slave(Stream(RxWorkReq()))
    val rx = slave(RqReqCommCheckRstBus(busWidth))
    val tx = master(RqReqWithRxBufBus(busWidth))
    val txDupReq = master(RqReqCommCheckRstBus(busWidth))
  }

  val inputValid = io.rx.checkRst.valid
  val inputPktFrag = io.rx.checkRst.pktFrag
  val isLastFrag = io.rx.checkRst.isLast
  val inputHasNak = io.rx.checkRst.hasNak

  val isSendOrWriteImmReq =
    OpCode.isSendReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)
  val isLastOrOnlySendOrWriteImmReq =
    OpCode.isSendLastOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)

  // RNR check for send/write
  val needRxBuf = inputValid && isSendOrWriteImmReq && !inputHasNak
  io.rxWorkReq.ready := isLastOrOnlySendOrWriteImmReq && !inputHasNak && io.rx.checkRst.lastFire
  val rxBuf = io.rxWorkReq.payload
  val rxBufValid = needRxBuf && io.rxWorkReq.valid
  val hasRnrErr = needRxBuf && !io.rxWorkReq.valid
  when(io.rxWorkReq.fire) {
    assert(
      assertion = io.rx.checkRst.lastFire,
      message =
        L"${REPORT_TIME} time: io.rxWorkReq.fire=${io.rxWorkReq.fire} should fire at the same cycle as io.rx.checkRst.lastFire=${io.rx.checkRst.lastFire} when needRxBuf=${needRxBuf}".toSeq,
      severity = FAILURE
    )
  }

  val rnrAeth = AETH().set(AckType.NAK_RNR, io.qpAttr.negotiatedRnrTimeOut)
  val ackAeth = cloneOf(io.rx.checkRst.ackAeth)
  ackAeth := io.rx.checkRst.ackAeth
  when(!inputHasNak && hasRnrErr) {
    ackAeth := rnrAeth
  }

  val (otherReqStream, dupReqStream) = StreamDeMuxByOneCondition(
    io.rx.checkRst.throwWhen(io.rxQCtrl.flush),
    inputHasNak && ackAeth.isSeqNak()
  )
  io.txDupReq.checkRst <-/< dupReqStream

  io.tx.reqWithRxBuf <-/< otherReqStream
    .translateWith {
      val result = cloneOf(io.tx.reqWithRxBuf.payloadType)
      result.pktFrag := inputPktFrag
      result.preOpCode := io.rx.checkRst.preOpCode
      result.hasNak := inputHasNak || hasRnrErr
      result.ackAeth := ackAeth
      result.rxBufValid := rxBufValid
      result.rxBuf := rxBuf
      result.last := isLastFrag
      result
    }
}

// If multiple duplicate requests received, also ACK in PSN order;
// RQ will return ACK with the latest PSN for duplicate Send/Write, but this will NAK the following duplicate Read/Atomic???
// No NAK for duplicate requests if error detected;
// Duplicate Read is not valid if not with its original PSN and DMA range;
// Duplicate request with earlier PSN might interrupt processing of new request or duplicate request with later PSN;
// RQ does not re-execute the interrupted request, SQ will retry it;
// Discard duplicate Atomic if not match original PSN (should not happen);
class DupReqHandlerAndReadAtomicRstCacheQuery(
    busWidth: BusWidth.Value
) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val readAtomicRstCache = master(ReadAtomicRstCacheQueryBus())
    val rx = slave(RqReqCommCheckRstBus(busWidth))
    val txDupSendWriteResp = master(Stream(Acknowledge()))
    val txDupAtomicResp = master(Stream(AtomicResp()))
    val dupReadReqAndRstCacheData = master(
      Stream(RqDupReadReqAndRstCacheData(busWidth))
    )
  }

  val hasNak = io.rx.checkRst.hasNak
  val ackAeth = io.rx.checkRst.ackAeth
  val isLastFrag = io.rx.checkRst.isLast

  when(io.rx.checkRst.valid) {
    assert(
      assertion = hasNak && ackAeth.isSeqNak(),
      message =
        L"${REPORT_TIME} time: duplicate request should have hasNak=${hasNak} is true, and ackAeth with code=${ackAeth.code} and value=${ackAeth.value} is NAK SEQ".toSeq,
      severity = FAILURE
    )
  }

  val (forkInputForSendWrite, forkInputForReadAtomic) = StreamFork2(
    io.rx.checkRst.throwWhen(io.rxQCtrl.stateErrFlush)
  )

  val isSendReq = OpCode.isSendReqPkt(forkInputForSendWrite.pktFrag.bth.opcode)
  val isWriteReq =
    OpCode.isWriteReqPkt(forkInputForSendWrite.pktFrag.bth.opcode)
  io.txDupSendWriteResp <-/< forkInputForSendWrite
    .takeWhen(isLastFrag && (isSendReq || isWriteReq))
    .translateWith(
      Acknowledge().setAck(
        AckType.NORMAL,
        io.qpAttr.epsn, // TODO: verify the ePSN is confirmed, will not retry later
        io.qpAttr.dqpn
      )
    )

  val buildReadAtomicRstCacheQuery =
    (pktFragStream: Stream[Fragment[RqReqCheckStageOutput]]) =>
      new Composite(pktFragStream, "buildReadAtomicRstCacheQuery") {
        val readAtomicRstCacheReq = ReadAtomicRstCacheReq()
        val reth = pktFragStream.pktFrag.extractReth()
        readAtomicRstCacheReq.queryPsn := pktFragStream.pktFrag.bth.psn
        readAtomicRstCacheReq.opcode := pktFragStream.pktFrag.bth.opcode
        readAtomicRstCacheReq.rkey := reth.rkey
        readAtomicRstCacheReq.epsn := io.qpAttr.epsn
      }.readAtomicRstCacheReq

  // Always expect response from ReadAtomicRstCache
  val expectReadAtomicRstCacheResp =
    (_: Stream[Fragment[RqReqCheckStageOutput]]) => True

  // Only send out ReadAtomicRstCache query when the input is
  // duplicate request
  val readAtomicRstCacheQueryCond =
    (pktFragStream: Stream[Fragment[RqReqCheckStageOutput]]) =>
      new Composite(pktFragStream, "readAtomicRstCacheQueryCond") {
        val result = pktFragStream.hasNak && pktFragStream.ackAeth.isSeqNak()
      }.result

  // Only join ReadAtomicRstCache query response with valid read/atomic request
  val readAtomicRstCacheQueryRespJoinCond =
    (pktFragStream: Stream[Fragment[RqReqCheckStageOutput]]) =>
      new Composite(pktFragStream, "readAtomicRstCacheQueryRespJoinCond") {
        val result = pktFragStream.valid
      }.result

  val joinStream = FragmentStreamForkQueryJoinResp(
    forkInputForReadAtomic.throwWhen(isSendReq || isWriteReq),
    io.readAtomicRstCache.req,
    io.readAtomicRstCache.resp,
    waitQueueDepth = READ_ATOMIC_RESULT_CACHE_QUERY_FIFO_DEPTH,
    buildQuery = buildReadAtomicRstCacheQuery,
    queryCond = readAtomicRstCacheQueryCond,
    expectResp = expectReadAtomicRstCacheResp,
    joinRespCond = readAtomicRstCacheQueryRespJoinCond
  )

  val readAtomicRstCacheRespValid = joinStream.valid
  val readAtomicRstCacheRespData = joinStream._3.respValue
  val readAtomicRstCacheRespFound = joinStream._3.found
  val readAtomicResultNotFound =
    readAtomicRstCacheRespValid && !readAtomicRstCacheRespFound

  val isAtomicReq = OpCode.isAtomicReqPkt(readAtomicRstCacheRespData.opcode)
  val isReadReq = OpCode.isReadReqPkt(readAtomicRstCacheRespData.opcode)
  when(readAtomicRstCacheRespValid) {
    // TODO: check duplicate requests of pending requests are already discarded by ReqCommCheck
    // TODO: if duplicate requests are too old to be found in ReadAtomicRstCache, what to do?
    assert(
      assertion = readAtomicRstCacheRespFound,
      message =
        L"${REPORT_TIME} time: duplicated read/atomic request with PSN=${joinStream._3.queryKey.queryPsn} not found, readAtomicRstCacheRespValid=${readAtomicRstCacheRespValid}, but readAtomicRstCacheRespFound=${readAtomicRstCacheRespFound}".toSeq,
      severity = FAILURE
    )
//    assert(
//      assertion = readAtomicRequestNotDone,
//      message =
//        L"${REPORT_TIME} time: duplicated read/atomic request with PSN=${joinStream._2.query.psn} not done yet, readAtomicRstCacheRespValid=${readAtomicRstCacheRespValid}, readAtomicRstCacheRespFound=${readAtomicRstCacheRespFound}, but readAtomicRstCacheRespData=${readAtomicRstCacheRespData.done}",
//      severity = FAILURE
//    )
  }
  val (forkJoinStream4Atomic, forkJoinStream4Read) = StreamFork2(
    joinStream.throwWhen(readAtomicResultNotFound)
  )
  // TODO: check duplicate atomic request is identical to the original one
  io.txDupAtomicResp <-/< forkJoinStream4Atomic
    .takeWhen(isAtomicReq && readAtomicRstCacheRespFound)
    .translateWith {
      val result = cloneOf(io.txDupAtomicResp.payloadType)
      result.set(
        dqpn = io.qpAttr.dqpn,
        psn = readAtomicRstCacheRespData.psnStart,
        orig = readAtomicRstCacheRespData.atomicRst
      )
      result
    }

  io.dupReadReqAndRstCacheData <-/< forkJoinStream4Read
    .takeWhen(isReadReq && readAtomicRstCacheRespFound)
    .translateWith {
      val result = cloneOf(io.dupReadReqAndRstCacheData.payloadType)
      result.pktFrag := forkJoinStream4Read._1.pktFrag
      result.rstCacheData := forkJoinStream4Read._3.respValue
      when(isReadReq) {
        result.rstCacheData.dupReq := True
      }
      result
    }
}

class DupReadDmaReqBuilder(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rxDupReadReqAndRstCacheData = slave(
      Stream(RqDupReadReqAndRstCacheData(busWidth))
    )
    val dupReadDmaReqAndRstCacheData = master(
      Stream(RqDmaReadReqAndRstCacheData())
    )
  }

  val inputValid = io.rxDupReadReqAndRstCacheData.valid
  val inputPktFrag = io.rxDupReadReqAndRstCacheData.pktFrag
  val inputRstCacheData = io.rxDupReadReqAndRstCacheData.rstCacheData

  val (
    retryFromBeginning,
    retryStartPsn,
    retryDmaReadStartAddr,
    retryStartLocalAddr,
    retryDmaReadLenBytes
  ) = PartialRetry.readReqRetry(
    io.qpAttr,
    inputPktFrag,
    inputRstCacheData,
    inputValid
  )

  io.dupReadDmaReqAndRstCacheData <-/< io.rxDupReadReqAndRstCacheData
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith {
      val result = cloneOf(io.dupReadDmaReqAndRstCacheData.payloadType)
      result.dmaReadReq.set(
        initiator = DmaInitiator.RQ_DUP,
        sqpn = io.qpAttr.sqpn,
        psnStart = retryStartPsn,
        pa = retryDmaReadStartAddr,
        lenBytes = retryDmaReadLenBytes // .resize(RDMA_MAX_LEN_WIDTH)
      )
      result.rstCacheData := inputRstCacheData
      result
    }

  when(inputValid) {
    assert(
      assertion = inputRstCacheData.dupReq,
      message =
        L"${REPORT_TIME} time: inputRstCacheData.dupReq=${inputRstCacheData.dupReq} should be true".toSeq,
      severity = FAILURE
    )
  }
}

class ReqAddrInfoExtractor(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufBus(busWidth))
    val tx = master(RqReqWithRxBufAndVirtualAddrInfoBus(busWidth))
    val rqOutPsnRangeFifoPush = master(Stream(RespPsnRange()))
  }

  val inputValid = io.rx.reqWithRxBuf.valid
//  val inputPktFrag = io.rx.reqWithRxBuf.fragment
//  val isLastFrag = io.rx.reqWithRxBuf.last
//  val rxBuf = io.rx.reqWithRxBuf.rxBuf

  val opcode = io.rx.reqWithRxBuf.pktFrag.bth.opcode
  val isSendReq = OpCode.isSendReqPkt(opcode)
  val isWriteReq = OpCode.isWriteReqPkt(opcode)
  val isReadReq = OpCode.isReadReqPkt(opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(opcode)

  when(inputValid) {
    assert(
      assertion = isSendReq || isWriteReq || isReadReq || isAtomicReq,
      message =
        L"${REPORT_TIME} time: illegal input opcode=${opcode} must be send/write/read/atomic request, PSN=${io.rx.reqWithRxBuf.pktFrag.bth.psn}".toSeq,
      severity = FAILURE
    )
  }

  val isWriteFirstOrOnlyReq = OpCode.isWriteFirstOrOnlyReqPkt(opcode)
  val inputStreamWithAddrInfo = StreamExtractCompany(
    // Not flush when RNR
    io.rx.reqWithRxBuf.throwWhen(io.rxQCtrl.stateErrFlush),
    companyExtractFunc = (reqWithRxBuf: Stream[Fragment[RqReqWithRxBuf]]) =>
      new Composite(reqWithRxBuf, "ReqAddrInfoExtractor_companyExtractFunc") {
        // TODO: verify inputPktFrag.data is big endian
        val virtualAddrInfo = VirtualAddrInfo()

        val result = Flow(VirtualAddrInfo())
        result.valid := False
        result.payload := virtualAddrInfo

        val rxBuf = reqWithRxBuf.rxBuf
        val inputHasNak = reqWithRxBuf.hasNak
        val isFirstFrag = reqWithRxBuf.isFirst
        when(isSendReq) {
          result.valid := inputValid
          // Extract DMA info for send requests
          virtualAddrInfo.va := rxBuf.laddr
          virtualAddrInfo.lrkey := rxBuf.lkey
          virtualAddrInfo.dlen := rxBuf.lenBytes

//          report(L"${REPORT_TIME} time: PSN=${reqWithRxBuf.pktFrag.bth.psn}, opcode=${reqWithRxBuf.pktFrag.bth.opcode}, ackReq=${reqWithRxBuf.pktFrag.bth.ackreq}, isFirstFrag=${isFirstFrag}, rxBuf.lenBytes=${rxBuf.lenBytes}")

          when(inputValid) {
            assert(
              assertion = inputHasNak =/= reqWithRxBuf.rxBufValid,
              message =
                L"${REPORT_TIME} time: inputHasNak=${inputHasNak} should != reqWithRxBuf.rxBufValid=${reqWithRxBuf.rxBufValid}".toSeq,
              severity = FAILURE
            )
          }
          //  } elsewhen (OpCode.hasRethOrAtomicEth(opcode)) {
        } otherwise {
          // Only the first fragment of write/read/atomic requests has RETH
          result.valid := isFirstFrag && (isWriteFirstOrOnlyReq || isReadReq || isAtomicReq)

          // Extract DMA info for write/read/atomic requests
          // AtomicEth has the same va, lrkey and dlen field with RETH
          val reth = reqWithRxBuf.pktFrag.extractReth()

          virtualAddrInfo.va := reth.va
          virtualAddrInfo.lrkey := reth.rkey
          virtualAddrInfo.dlen := reth.dlen
          when(isAtomicReq) {
            virtualAddrInfo.dlen := ATOMIC_DATA_LEN
          }
        }
      }.result
  )

  val ackReq =
    ((isSendReq || isWriteReq) && io.rx.reqWithRxBuf.pktFrag.bth.ackreq) || isReadReq || isAtomicReq
  val rqOutPsnRangeFifoPushCond =
    io.rx.reqWithRxBuf.isFirst && (io.rx.reqWithRxBuf.hasNak || ackReq)
  val (rqOutPsnRangeFifoPush, txNormal) = StreamConditionalFork2(
    // Not flush when RNR
    inputStreamWithAddrInfo,
    // The condition to send out RQ response
    forkCond = rqOutPsnRangeFifoPushCond
  )
  io.tx.reqWithRxBufAndVirtualAddrInfo <-/< txNormal.map { payloadData =>
    val result = cloneOf(io.tx.reqWithRxBufAndVirtualAddrInfo.payloadType)
    result.pktFrag := payloadData._1.pktFrag
    result.preOpCode := payloadData._1.preOpCode
    result.hasNak := payloadData._1.hasNak
    result.ackAeth := payloadData._1.ackAeth
    result.rxBufValid := payloadData._1.rxBufValid
    result.rxBuf := payloadData._1.rxBuf
    result.virtualAddrInfo := payloadData._2
    result.last := payloadData._1.last
    result
  }

  // Update output FIFO to keep output PSN order
  io.rqOutPsnRangeFifoPush <-/< rqOutPsnRangeFifoPush.map { payloadData =>
    // Since the max length of a request is 2^31, and the min PMTU is 256=2^8
    // So the max number of packet is 2^23 < 2^PSN_WIDTH
    val numRespPkt =
      divideByPmtuUp(payloadData._2.dlen, io.qpAttr.pmtu).resize(PSN_WIDTH)
    val result = cloneOf(io.rqOutPsnRangeFifoPush.payloadType)
    result.opcode := payloadData._1.pktFrag.bth.opcode
    result.start := payloadData._1.pktFrag.bth.psn
    result.end := payloadData._1.pktFrag.bth.psn
    when(isReadReq) {
      result.end := payloadData._1.pktFrag.bth.psn + numRespPkt - 1
    }
    result
  }
}

class ReqAddrValidator(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val rx = slave(RqReqWithRxBufAndVirtualAddrInfoBus(busWidth))
    val tx = master(RqReqWithRxBufAndDmaInfoBus(busWidth))
  }

  // Build QpAddrCacheAgentReadReq according to RqReqWithRxBufAndDmaInfo
  val buildAddrCacheQuery =
    (pktFragStream: Stream[Fragment[RqReqWithRxBufAndVirtualAddrInfo]]) =>
      new Composite(pktFragStream, "ReqAddrValidator_buildAddrCacheQuery") {
        val inputValid = pktFragStream.valid
        val inputPktFrag = pktFragStream.pktFrag
        val inputVirtualAddrInfo = pktFragStream.virtualAddrInfo

        val accessKey = inputVirtualAddrInfo.lrkey
        val va = inputVirtualAddrInfo.va

        val accessType = AccessType()
        val pdId = io.qpAttr.pdId
        val remoteOrLocalKey = True // True: remote, False: local
        val dataLenBytes = inputVirtualAddrInfo.dlen
        // Only send
        when(OpCode.isSendReqPkt(inputPktFrag.bth.opcode)) {
          accessType.set(AccessPermission.LOCAL_WRITE)
        } elsewhen (OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)) {
          accessType.set(AccessPermission.REMOTE_WRITE)
        } elsewhen (OpCode.isReadReqPkt(inputPktFrag.bth.opcode)) {
          accessType.set(AccessPermission.REMOTE_READ)
        } elsewhen (OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)) {
          accessType.set(AccessPermission.REMOTE_ATOMIC)
        } otherwise {
          accessType.set(
            AccessPermission.LOCAL_READ
          ) // AccessType LOCAL_READ is not defined in spec. 1.4
          when(inputValid) {
            report(
              message =
                L"${REPORT_TIME} time: invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic".toSeq,
              severity = FAILURE
            )
          }
        }

        val addrCacheReadReq = QpAddrCacheAgentReadReq()
        addrCacheReadReq.sqpn := io.qpAttr.sqpn
        addrCacheReadReq.psn := inputPktFrag.bth.psn
        addrCacheReadReq.key := accessKey
        addrCacheReadReq.pdId := pdId
        addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = remoteOrLocalKey)
        addrCacheReadReq.accessType := accessType
        addrCacheReadReq.va := va
        addrCacheReadReq.dataLenBytes := dataLenBytes
      }.addrCacheReadReq

  // Only expect AddrCache query response when the input has no NAK
  val expectAddrCacheResp =
    (pktFragStream: Stream[Fragment[RqReqWithRxBufAndVirtualAddrInfo]]) =>
      new Composite(pktFragStream, "ReqAddrValidator_expectAddrCacheResp") {
        val result = !pktFragStream.hasNak // && pktFragStream.valid
      }.result

  // Only send out AddrCache query when the input data is the first fragment of
  // send/write/read/atomic request
  val addrCacheQueryCond =
    (pktFragStream: Stream[Fragment[RqReqWithRxBufAndVirtualAddrInfo]]) =>
      new Composite(pktFragStream, "ReqAddrValidator_addrCacheQueryCond") {
        val result = !pktFragStream.hasNak && pktFragStream.isFirst &&
          OpCode.isFirstOrOnlyReqPkt(pktFragStream.pktFrag.bth.opcode)
      }.result

  // Only join AddrCache query response when the input data is the last fragment of
  // the only or last send/write/read/atomic request
  val addrCacheQueryRespJoinCond =
    (pktFragStream: Stream[Fragment[RqReqWithRxBufAndVirtualAddrInfo]]) =>
      new Composite(
        pktFragStream,
        "ReqAddrValidator_addrCacheQueryRespJoinCond"
      ) {
        val result = pktFragStream.isLast &&
          OpCode.isLastOrOnlyReqPkt(pktFragStream.pktFrag.bth.opcode)
      }.result

  val (joinStream, bufLenErr, keyErr, accessErr, addrCheckErr) =
    AddrCacheQueryAndRespHandler(
      io.rx.reqWithRxBufAndVirtualAddrInfo.throwWhen(io.rxQCtrl.stateErrFlush),
      io.addrCacheRead,
      inputAsRdmaDataPktFrag =
        (reqWithRxBufAndVirtualAddrInfo: RqReqWithRxBufAndVirtualAddrInfo) =>
          reqWithRxBufAndVirtualAddrInfo.pktFrag,
      buildAddrCacheQuery = buildAddrCacheQuery,
      addrCacheQueryCond = addrCacheQueryCond,
      expectAddrCacheResp = expectAddrCacheResp,
      addrCacheQueryRespJoinCond = addrCacheQueryRespJoinCond
    )

  val nakInvOrRmtAccAeth = AETH().setDefaultVal()
  when(bufLenErr) {
    nakInvOrRmtAccAeth.set(AckType.NAK_INV)
  } elsewhen (keyErr || accessErr) {
    nakInvOrRmtAccAeth.set(AckType.NAK_RMT_ACC)
  }

  val inputHasNak = joinStream._1.hasNak
  val outputHasNak = addrCheckErr || inputHasNak
  val outputAckAeth = cloneOf(joinStream._1.ackAeth)
  outputAckAeth := joinStream._1.ackAeth
  when(addrCheckErr && !inputHasNak) {
    // TODO: if rdmaAck is retry NAK, should it be replaced by this NAK_INV or NAK_RMT_ACC?
    outputAckAeth := nakInvOrRmtAccAeth
  }

  io.tx.reqWithRxBufAndDmaInfo <-/< joinStream
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith {
      val result = cloneOf(io.tx.reqWithRxBufAndDmaInfo.payloadType)
      result.pktFrag := joinStream._1.pktFrag
      result.preOpCode := joinStream._1.preOpCode
      result.hasNak := outputHasNak
      result.ackAeth := outputAckAeth
      result.rxBufValid := joinStream._1.rxBufValid
      result.rxBuf := joinStream._1.rxBuf
      result.dmaInfo.pa := joinStream._3.pa
//      result.dmaInfo.lrkey := joinStream._1.dmaInfo.lrkey
//      result.dmaInfo.va := joinStream._1.dmaInfo.va
      result.dmaInfo.dlen := joinStream._1.virtualAddrInfo.dlen
      result.last := joinStream.isLast
      result
    }
}

// TODO: verify ICRC has been stripped off
class ReqPktLenCheck(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val tx = master(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
  }

  val inputHasNak = io.rx.reqWithRxBufAndDmaInfo.hasNak
  val inputValid = io.rx.reqWithRxBufAndDmaInfo.valid
//  val inputFire = io.rx.reqWithRxBufAndDmaInfo.fire
  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfo.pktFrag
  val inputRxBuf = io.rx.reqWithRxBufAndDmaInfo.rxBuf
  val inputDmaInfo = io.rx.reqWithRxBufAndDmaInfo.dmaInfo
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfo.isLast
  val isFirstFrag = io.rx.reqWithRxBufAndDmaInfo.isFirst

  val isSendReq = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
  val isWriteReq = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)

  val pmtuLenBytes = pmtuPktLenBytes(io.qpAttr.pmtu)
  val dmaTargetLenBytes = io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen

  when(inputValid) {
    assert(
      assertion = inputPktFrag.mty =/= 0,
      message =
        L"${REPORT_TIME} time: invalid MTY=${inputPktFrag.mty}, opcode=${inputPktFrag.bth.opcode}, PSN=${inputPktFrag.bth.psn}, isFirstFrag=${isFirstFrag}, isLastFrag=${isLastFrag}".toSeq,
      severity = FAILURE
    )
  }

  when(inputValid && io.rx.reqWithRxBufAndDmaInfo.rxBufValid) {
    assert(
      assertion = OpCode.isSendReqPkt(inputPktFrag.bth.opcode) ||
        OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode),
      message =
        L"${REPORT_TIME} time: it should be send requests that require receive buffer, but opcode=${inputPktFrag.bth.opcode}".toSeq,
      severity = FAILURE
    )
  }

  val reqTotalLenCheckFlow = io.rx.reqWithRxBufAndDmaInfo.toFlowFire
    .takeWhen(isSendReq || isWriteReq)
    .translateWith {
      val lenCheckElements = LenCheckElements(busWidth)
      lenCheckElements.opcode := io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcode
      lenCheckElements.psn := io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn
//      lenCheckElements.psnStart := io.rx.reqWithRxBufAndDmaInfo.cachedWorkReq.psnStart
      lenCheckElements.padCnt := io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.padCnt
      lenCheckElements.lenBytes := io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen
      lenCheckElements.mty := io.rx.reqWithRxBufAndDmaInfo.pktFrag.mty

      val result = Fragment(cloneOf(lenCheckElements))
      result.fragment := lenCheckElements
      result.last := io.rx.reqWithRxBufAndDmaInfo.last
      result
    }

  val reqTotalLenFlow = ReqRespTotalLenCalculator(
    flush = io.rxQCtrl.stateErrFlush,
    pktFireFlow = reqTotalLenCheckFlow,
    pmtuLenBytes = pmtuLenBytes
  )

  val reqTotalLenValid = reqTotalLenFlow.valid
  val reqTotalLenBytes = reqTotalLenFlow.totalLenOutput
  val isReqPktLenCheckErr = reqTotalLenFlow.isPktLenCheckErr

  val isReqTotalLenCheckErr = False
  when(reqTotalLenValid) {
    isReqTotalLenCheckErr :=
      (isSendReq && reqTotalLenBytes > dmaTargetLenBytes) ||
        (isWriteReq && reqTotalLenBytes =/= dmaTargetLenBytes)
    assert(
      assertion = !isReqTotalLenCheckErr,
      message =
        L"${REPORT_TIME} time: request total length check failed, opcode=${inputPktFrag.bth.opcode}, PSN=${inputPktFrag.bth.psn}, dmaTargetLenBytes=${dmaTargetLenBytes}, reqTotalLenBytes=${reqTotalLenBytes}, isFirstFrag=${isFirstFrag}, isLastFrag=${isLastFrag}".toSeq,
      severity = ERROR
    )
  }

  val hasLenCheckErr = isReqTotalLenCheckErr || isReqPktLenCheckErr
  val nakInvAeth = AETH().set(AckType.NAK_INV)
  val ackAeth = cloneOf(io.rx.reqWithRxBufAndDmaInfo.ackAeth)
  val outputHasNak = inputHasNak || hasLenCheckErr
  ackAeth := io.rx.reqWithRxBufAndDmaInfo.ackAeth
  when(hasLenCheckErr && !inputHasNak) {
    // TODO: if rdmaAck is retry NAK, should it be replaced by this NAK_INV or NAK_RMT_ACC?
    ackAeth := nakInvAeth
  }

  io.tx.reqWithRxBufAndDmaInfoWithLenCheck <-/< io.rx.reqWithRxBufAndDmaInfo
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith {
      val result = cloneOf(io.tx.reqWithRxBufAndDmaInfoWithLenCheck.payloadType)
      result.pktFrag := inputPktFrag
      result.preOpCode := io.rx.reqWithRxBufAndDmaInfo.preOpCode
      result.hasNak := outputHasNak
      result.ackAeth := ackAeth
      result.rxBufValid := io.rx.reqWithRxBufAndDmaInfo.rxBufValid
      result.rxBuf := inputRxBuf
      result.reqTotalLenValid := reqTotalLenValid
      result.reqTotalLenBytes := reqTotalLenBytes
      result.dmaInfo := inputDmaInfo
      result.last := isLastFrag
      result
    }
}

class ReqSplitterAndNakGen(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
    val txSendWrite = master(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
    val txReadAtomic = master(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
    val nakNotifier = out(RqNakNotifier())
    val txErrResp = master(Stream(Acknowledge()))
  }

  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag
  val inputHasNak = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak
  val isRetryNak = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth.isRetryNak()
  val isSendReq = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
  val isWriteReq = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)

  val (reqHasNakStream, reqNormalStream) = StreamConditionalFork2(
    io.rx.reqWithRxBufAndDmaInfoWithLenCheck
      .throwWhen(io.rxQCtrl.stateErrFlush),
    forkCond = inputHasNak
  )
//  val (reqNormalStream, reqHasNakStream) = StreamDeMuxByOneCondition(
//    io.rx.reqWithRxBufAndDmaInfoWithLenCheck
//      .throwWhen(io.rxQCtrl.stateErrFlush),
//    condition = inputHasNak
//  )

  val (sendWriteIdx, readAtomicIdx) = (0, 1)
  val twoStreams = StreamDeMuxByConditions(
    reqNormalStream,
    isSendReq || isWriteReq,
    isReadReq || isAtomicReq
  )
  io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck <-/<
    // The send/write imm requests need to generate WC no matter ACK or fatal NAK,
    // But no WC for retry NAK
    twoStreams(sendWriteIdx).throwWhen(isRetryNak)
  io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck <-/<
    twoStreams(readAtomicIdx).throwWhen(inputHasNak)

  // NAK generation
  val nakResp = Acknowledge().setAck(
    io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth,
    inputPktFrag.bth.psn,
    io.qpAttr.dqpn
  )
  io.txErrResp <-/< reqHasNakStream
    // TODO: better to response at the first fragment when error
    .takeWhen(reqHasNakStream.isLast)
    .translateWith(nakResp)

  io.nakNotifier.setFromAeth(
    aeth = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth,
    pulse = reqHasNakStream.isLast,
    preOpCode = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.preOpCode,
    psn = inputPktFrag.bth.psn
  )
}

// If hasNak or empty request, no DMA request initiated
class RqSendWriteDmaReqInitiator(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
    val txSendWrite = master(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
    val sendWriteDmaReq = master(DmaWriteReqBus(busWidth))
  }

  val inputHasNak = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak
  val inputValid = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.valid
  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last
  val inputDmaHeader = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo
  val isEmptyReq =
    inputValid && io.rx.reqWithRxBufAndDmaInfoWithLenCheck.isEmptyReq()
//    // Check empty write request
//    inputDmaHeader.dlen === 0 || (
//      // Check empty send request
//      io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenValid &&
//      io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenBytes === 0
//    )

//  when(inputValid) {
//    assert(
//      assertion = !inputHasNak,
//      message =
//        L"${REPORT_TIME} time: inputHasNak=${inputHasNak} should be false in RqSendWriteDmaReqInitiator".toSeq,
//      severity = FAILURE
//    )
//  }

  val (forkSendWriteReqStream4DmaWrite, forkSendWriteReqStream4Output) =
    StreamFork2(
      io.rx.reqWithRxBufAndDmaInfoWithLenCheck.throwWhen(
        io.rxQCtrl.stateErrFlush
      )
    )
  val noDmaReqCond = inputHasNak || isEmptyReq
  io.sendWriteDmaReq.req <-/< forkSendWriteReqStream4DmaWrite
    .throwWhen(noDmaReqCond)
    .translateWith {
      val result = cloneOf(io.sendWriteDmaReq.req.payloadType)
      result.last := isLastFrag
      result.set(
        initiator = DmaInitiator.RQ_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        pa = inputDmaHeader.pa,
        workReqId = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.id,
        // TODO: remove header before DMA write
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      result
    }
//  when(forkSendWriteReqStream4DmaWrite.fire) {
//    report(L"${REPORT_TIME} time: AddrCache request PSN=${inputPktFrag.bth.psn}".toSeq)
//  }

  io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck <-/< forkSendWriteReqStream4Output
}

class RqReadAtomicDmaReqBuilder(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
    val readAtomicRstCachePush = master(Stream(ReadAtomicRstCacheData()))
    val readDmaReqAndRstCacheData = master(
      Stream(RqDmaReadReqAndRstCacheData())
    )
    val atomicDmaReqAndRstCacheData = master(
      Stream(RqDmaReadReqAndRstCacheData())
    )
  }

  val inputHasNak = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak
  val inputValid = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.valid
  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag
  val inputDmaHeader = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last

  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
  when(inputValid) {
    assert(
      assertion = isReadReq || isAtomicReq,
      message =
        L"${REPORT_TIME} time: ReadAtomicDmaReqBuilder can only handle read/atomic request, but opcode=${inputPktFrag.bth.opcode}".toSeq,
      severity = FAILURE
    )
    assert(
      assertion = !inputHasNak,
      message =
        L"${REPORT_TIME} time: inputHasNak=${inputHasNak} should be false in RqReadAtomicDmaReqBuilder".toSeq,
      severity = FAILURE
    )
  }

  val reth = inputPktFrag.extractReth()
  val atomicEth = inputPktFrag.extractAtomicEth()

  val readAtomicDmaReqAndRstCacheData = io.rx.reqWithRxBufAndDmaInfoWithLenCheck
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith {
      val result = cloneOf(io.readDmaReqAndRstCacheData.payloadType)
      when(isReadReq) {
        result.dmaReadReq.set(
          initiator = DmaInitiator.RQ_RD,
          sqpn = io.qpAttr.sqpn,
          psnStart = inputPktFrag.bth.psn,
          pa = inputDmaHeader.pa,
          lenBytes = inputDmaHeader.dlen
        )
      } otherwise {
        result.dmaReadReq.set(
          initiator = DmaInitiator.RQ_RD,
          sqpn = io.qpAttr.sqpn,
          psnStart = inputPktFrag.bth.psn,
          pa = inputDmaHeader.pa,
          lenBytes = ATOMIC_DATA_LEN
        )
      }
      val rstCacheData = result.rstCacheData
      rstCacheData.psnStart := inputPktFrag.bth.psn
      rstCacheData.opcode := inputPktFrag.bth.opcode
      rstCacheData.pa := io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.pa
      when(isReadReq) {
        rstCacheData.pktNum := computePktNum(reth.dlen, io.qpAttr.pmtu)
        rstCacheData.va := reth.va
        rstCacheData.rkey := reth.rkey
        rstCacheData.dlen := reth.dlen
        rstCacheData.swap.assignDontCare()
        rstCacheData.comp.assignDontCare()
      } otherwise {
        rstCacheData.pktNum := 1 // Atomic request has only one packet
        rstCacheData.va := atomicEth.va
        rstCacheData.rkey := atomicEth.rkey
        rstCacheData.dlen := ATOMIC_DATA_LEN
        rstCacheData.swap := atomicEth.swap
        rstCacheData.comp := atomicEth.comp
      }
      rstCacheData.atomicRst.assignDontCare()
      rstCacheData.dupReq := False
      result
    }

  val (rstCacheDataPush, readAtomicReqOutput) =
    StreamFork2(readAtomicDmaReqAndRstCacheData)
  io.readAtomicRstCachePush <-/< rstCacheDataPush
    .translateWith(rstCacheDataPush.rstCacheData)

  val (readIdx, atomicIdx) = (0, 1)
  val twoStreams =
    StreamDeMuxByConditions(readAtomicReqOutput, isReadReq, isAtomicReq)

  io.readDmaReqAndRstCacheData <-/< twoStreams(readIdx)
  io.atomicDmaReqAndRstCacheData <-/< twoStreams(atomicIdx)
}

class ReadDmaReqInitiator() extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val readDmaReqAndRstCacheData = slave(Stream(RqDmaReadReqAndRstCacheData()))
    val dupReadDmaReqAndRstCacheData = slave(
      Stream(RqDmaReadReqAndRstCacheData())
    )
    val readDmaReq = master(DmaReadReqBus())
    val readRstCacheData = master(Stream(ReadAtomicRstCacheData()))
  }

  when(io.dupReadDmaReqAndRstCacheData.valid) {
    assert(
      assertion = io.dupReadDmaReqAndRstCacheData.rstCacheData.dupReq,
      message =
        L"${REPORT_TIME} time: io.dupReadDmaReqAndRstCacheData.rstCacheData.dupReq=${io.dupReadDmaReqAndRstCacheData.rstCacheData.dupReq} should be true when io.dupReadDmaReqAndRstCacheData.valid=${io.dupReadDmaReqAndRstCacheData.valid}".toSeq,
      severity = FAILURE
    )
  }

  val readDmaReqArbitration = StreamArbiterFactory().roundRobin.transactionLock
    .onArgs(
      io.readDmaReqAndRstCacheData.throwWhen(io.rxQCtrl.stateErrFlush),
      io.dupReadDmaReqAndRstCacheData.throwWhen(io.rxQCtrl.stateErrFlush)
    )

  val inputValid = readDmaReqArbitration.valid
  val inputDmaHeader = readDmaReqArbitration.dmaReadReq
  // Check empty read request
  val isEmptyReq =
    inputValid && readDmaReqArbitration
      .isEmptyReq() // inputDmaHeader.lenBytes === 0

  val (readDmaReq, readRstCacheData) = StreamFork2(readDmaReqArbitration)
  io.readDmaReq.req <-/< readDmaReq
    .throwWhen(isEmptyReq)
    .translateWith(readDmaReq.dmaReadReq)
  io.readRstCacheData <-/< readRstCacheData.translateWith(
    readRstCacheData.rstCacheData
  )
}

// SendWriteRespGenerator only generate normal ACK, since retry NAK and fatal NAK
// are handled by ReqSplitterAndNakGen
// TODO: limit coalesce response to some max number of send/write requests
class SendWriteRespGenerator(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
    val tx = master(Stream(Acknowledge()))
//    val sendWriteWorkCompAndAck = master(Stream(WorkCompAndAck()))
    val txSendWriteReq = master(
      RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth)
    )
  }

  val inputValid = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.valid
  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag
  val inputHasNak = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak
  val isRetryNak = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth.isRetryNak()
  val isFirstFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.isFirst
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.isLast
  val inputDmaHeader = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo
  val isLastOrOnlyPkt =
    OpCode.isSendWriteLastOrOnlyReqPkt(inputPktFrag.bth.opcode)
  val isLastFragOfLastOrOnlyPkt = isLastOrOnlyPkt && isLastFrag

  val isSendReqPkt = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
  val isWriteReqPkt = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
  val isSendOrWriteReq = isSendReqPkt || isWriteReqPkt
  val isWriteWithImmReqPkt =
    OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)
  val isSendOrWriteImmReq = isSendReqPkt || isWriteWithImmReqPkt
//  val needWorkCompCond = isSendOrWriteImmReq && isLastFragOfLastOrOnlyPkt
  when(inputValid) {
    assert(
      assertion = isSendOrWriteReq,
      message =
        L"${REPORT_TIME} time: invalid opcode=${inputPktFrag.bth.opcode}, should be send/write requests".toSeq,
      severity = FAILURE
    )
    // Retry NAK should already be replied by ReqSplitterAndNakGen
    when(inputHasNak) {
      assert(
        assertion = !isRetryNak,
        message =
          L"${REPORT_TIME} time: illegal NAK for WC, retry NAK cannot generate WC, but NAK code=${io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth.code} and value=${io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth.value}".toSeq,
        severity = FAILURE
      )
    }
  }
  when(inputValid && isSendOrWriteImmReq) {
    assert(
      assertion = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBufValid,
      message =
        L"${REPORT_TIME} time: io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBufValid=${io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBufValid} should be true, when isSendOrWriteImmReq=${isSendOrWriteImmReq}".toSeq,
      severity = FAILURE
    )
  }
  when(isLastFragOfLastOrOnlyPkt) {
    assert(
      assertion = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenValid,
      message =
        L"${REPORT_TIME} time: invalid send/write request, reqTotalLenValid=${io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenValid} should be true".toSeq,
      severity = FAILURE
    )
  }

  // Only response normal ACK
  val sendWriteNormalAck = Acknowledge().setAck(
    AckType.NORMAL,
//    io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth,
    inputPktFrag.bth.psn,
    io.qpAttr.dqpn
  )

  val needAckCond = inputPktFrag.bth.ackreq && isLastFrag && !inputHasNak
  val (req4Ack, req4WorkComp) = StreamFork2(
    io.rx.reqWithRxBufAndDmaInfoWithLenCheck
      .throwWhen(io.rxQCtrl.stateErrFlush)
//      .takeWhen(needAckCond || needWorkCompCond)
  )

  // Responses to send/write do not wait for DMA write responses
  io.tx <-/< req4Ack
    .takeWhen(needAckCond)
    .translateWith(sendWriteNormalAck)

  io.txSendWriteReq.reqWithRxBufAndDmaInfoWithLenCheck <-/< req4WorkComp
}

class RqSendWriteWorkCompGenerator(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val dmaWriteResp = slave(DmaWriteRespBus())
    //    val sendWriteWorkCompAndAck = slave(Stream(WorkCompAndAck()))
    val rx = slave(RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth))
    val sendWriteWorkCompOut = master(Stream(WorkComp()))
  }

  when(
    io.rx.reqWithRxBufAndDmaInfoWithLenCheck.valid && io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak
  ) {
    assert(
      assertion = !io.dmaWriteResp.resp.valid,
      message =
        L"${REPORT_TIME} time: when hasNak=${io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak}, it should have no DMA write response, but io.dmaWriteResp.resp.valid=${io.dmaWriteResp.resp.valid}".toSeq,
      severity = FAILURE
    )
  }

  // Each write request packet has a DMA write response
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfoWithLenCheck.isLast
  /*
  val sendWriteReqPktFragQueue = StreamFifoLowLatency(
    io.rx.reqWithRxBufAndDmaInfoWithLenCheck.payloadType(),
    DMA_WRITE_FIFO_DEPTH
  )
  // Generate WC only when the last fragment of the last or only packet is processed
  sendWriteReqPktFragQueue.io.push << io.rx.reqWithRxBufAndDmaInfoWithLenCheck
    .takeWhen(isLastFrag)
  sendWriteReqPktFragQueue.io.flush := io.rxQCtrl.stateErrFlush
  assert(
    assertion = sendWriteReqPktFragQueue.io.push.ready,
    message =
      L"${REPORT_TIME} time: sendWriteReqPktFragQueue is full, sendWriteReqPktFragQueue.io.push.ready=${sendWriteReqPktFragQueue.io.push.ready}, sendWriteReqPktFragQueue.io.occupancy=${sendWriteReqPktFragQueue.io.occupancy}, which is not allowed in RqSendWriteWorkCompGenerator".toSeq,
    severity = FAILURE
  )
  val sendWriteReqPktFragQueuePop =
    sendWriteReqPktFragQueue.io.pop.combStage()
   */
  val sendWriteReqPktFragQueuePop = FixedLenQueue(
    io.rx.reqWithRxBufAndDmaInfoWithLenCheck.payloadType(),
    depth = DMA_WRITE_FIFO_DEPTH,
    push = io.rx.reqWithRxBufAndDmaInfoWithLenCheck
      .takeWhen(isLastFrag),
    flush = io.rxQCtrl.stateErrFlush,
    queueName = "sendWriteReqPktFragQueue"
  )
  val sendWriteReqPktFragQueuePopValid =
    sendWriteReqPktFragQueuePop.valid
  val dmaWriteRespValid = io.dmaWriteResp.resp.valid

  val isReqZeroDmaLen = sendWriteReqPktFragQueuePopValid &&
    sendWriteReqPktFragQueuePop.isEmptyReq() // reqTotalLenBytes === 0
  //  val inputHasNak = !io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth.isNormalAck()
  val inputHasNak =
    sendWriteReqPktFragQueuePopValid && sendWriteReqPktFragQueuePop.hasNak

  when(dmaWriteRespValid) {
    assert(
      assertion = sendWriteReqPktFragQueuePopValid,
      message =
        L"${REPORT_TIME} time: sendWriteReqPktFragQueuePopValid=${sendWriteReqPktFragQueuePopValid} must be true when dmaWriteRespValid=${dmaWriteRespValid} and io.dmaWriteResp.resp.psn=${io.dmaWriteResp.resp.psn}, since it must have some send/write request waiting for DMA response".toSeq,
      severity = FAILURE
    )
  }
  when(sendWriteReqPktFragQueuePopValid && dmaWriteRespValid) {
    assert(
      assertion =
        io.dmaWriteResp.resp.psn === sendWriteReqPktFragQueuePop.pktFrag.bth.psn,
      message =
        L"${REPORT_TIME} time: invalid DMA write response in RQ, io.dmaWriteResp.resp.psn=${io.dmaWriteResp.resp.psn} should == sendWriteReqPktFragQueuePop.pktFrag.bth.psn=${sendWriteReqPktFragQueuePop.pktFrag.bth.psn}".toSeq,
      severity = FAILURE
    )
  }

  val workCompGen = new Area {
    val inputPktFrag = sendWriteReqPktFragQueuePop.pktFrag
    val isLastFrag = sendWriteReqPktFragQueuePop.isLast
    val isSendReqPkt = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
//    val isWriteReqPkt = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
//    val isSendOrWriteReq = isSendReqPkt || isWriteReqPkt
    val isWriteWithImmReqPkt =
      OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)
    val isSendOrWriteImmReq = isSendReqPkt || isWriteWithImmReqPkt
    val isLastOrOnlyPkt =
      OpCode.isSendWriteLastOrOnlyReqPkt(inputPktFrag.bth.opcode)
    val isLastFragOfLastOrOnlyPkt = isLastOrOnlyPkt && isLastFrag
    val needWorkCompCond =
      isSendOrWriteImmReq && isLastFragOfLastOrOnlyPkt // && sendWriteReqPktFragQueuePop.reqTotalLenValid

    val sendWriteWorkComp = WorkComp().setDefaultVal()
    when(needWorkCompCond) {
      sendWriteWorkComp.setFromRxWorkReq(
        sendWriteReqPktFragQueuePop.rxBuf,
        sendWriteReqPktFragQueuePop.pktFrag.bth.opcode,
        io.qpAttr.dqpn,
        sendWriteReqPktFragQueuePop.ackAeth.toWorkCompStatus(),
        sendWriteReqPktFragQueuePop.reqTotalLenBytes, // for error WC, reqTotalLenBytes does not matter here
        sendWriteReqPktFragQueuePop.pktFrag.data
      )
    }

    val dmaWriteRespPnsEqualWorkCompPsn =
      io.dmaWriteResp.resp.psn === sendWriteReqPktFragQueuePop.pktFrag.bth.psn

    // For the following fire condition, it must make sure inputAckValid or inputCachedWorkReqValid
    val fireBothSendWriteReqPktFragQueueAndDmaWriteResp =
      sendWriteReqPktFragQueuePopValid && dmaWriteRespValid && dmaWriteRespPnsEqualWorkCompPsn
    val fireSendWriteReqPktFragQueueOnly =
      isReqZeroDmaLen || inputHasNak || io.rxQCtrl.stateErrFlush
    // Impossible to fire DMA write response only
    val fireDmaWriteRespOnly = False
    //    dmaWriteRespValid && workCompQueueValid && dmaWriteRespPnsLessThanWorkCompPsn && !shouldFireSendWriteReqPktFragQueueOnly
    val zipWorkCompAndAckAndDmaWriteResp = StreamZipByCondition(
      leftInputStream = sendWriteReqPktFragQueuePop,
      // Flush io.dmaWriteResp.resp when error
      rightInputStream =
        io.dmaWriteResp.resp.throwWhen(io.rxQCtrl.stateErrFlush),
      // Coalesce ACK pending WR, or errorFlush
      leftFireCond = fireSendWriteReqPktFragQueueOnly,
      rightFireCond = fireDmaWriteRespOnly,
      bothFireCond = fireBothSendWriteReqPktFragQueueAndDmaWriteResp
    )
    when(sendWriteReqPktFragQueuePopValid || dmaWriteRespValid) {
      assert(
        assertion = CountOne(
          fireBothSendWriteReqPktFragQueueAndDmaWriteResp ## fireSendWriteReqPktFragQueueOnly ## fireDmaWriteRespOnly
        ) <= 1,
        message =
          L"${REPORT_TIME} time: fire WorkCompQueue only, fire DmaWriteResp only, and fire both should be mutually exclusive, but fireBothSendWriteReqPktFragQueueAndDmaWriteResp=${fireBothSendWriteReqPktFragQueueAndDmaWriteResp}, fireSendWriteReqPktFragQueueOnly=${fireSendWriteReqPktFragQueueOnly}, fireDmaWriteRespOnly=${fireDmaWriteRespOnly}".toSeq,
        severity = FAILURE
      )
    }

    io.sendWriteWorkCompOut <-/< zipWorkCompAndAckAndDmaWriteResp
      .takeWhen(needWorkCompCond)
      .translateWith(sendWriteWorkComp)
  }
}

/** When received a new DMA response, combine the DMA response and
  * ReadAtomicRstCacheData to downstream, also handle zero DMA length read
  * request.
  */
class RqReadDmaRespHandler(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val readRstCacheData = slave(Stream(ReadAtomicRstCacheData()))
    val dmaReadResp = slave(DmaReadRespBus(busWidth))
    val readRstCacheDataAndDmaReadResp = master(
      Stream(Fragment(ReadAtomicRstCacheDataAndDmaReadResp(busWidth)))
    )
  }

  val handlerOutput = DmaReadRespHandler(
    io.readRstCacheData,
    io.dmaReadResp,
    io.rxQCtrl.stateErrFlush,
    busWidth,
    reqQueueLen = PENDING_READ_ATOMIC_REQ_FIFO_DEPTH,
    isReqZeroDmaLen =
      (readRstCacheData: ReadAtomicRstCacheData) => readRstCacheData.dlen === 0
  )

  io.readRstCacheDataAndDmaReadResp <-/< handlerOutput.translateWith {
    val result = cloneOf(io.readRstCacheDataAndDmaReadResp.payloadType)
    result.dmaReadResp := handlerOutput.dmaReadResp
    result.rstCacheData := handlerOutput.req
    result.last := handlerOutput.last
    result
  }
}

class ReadRespSegment(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val readRstCacheDataAndDmaReadResp = slave(
      Stream(Fragment(ReadAtomicRstCacheDataAndDmaReadResp(busWidth)))
    )
    val readRstCacheDataAndDmaReadRespSegment = master(
      Stream(Fragment(ReadAtomicRstCacheDataAndDmaReadResp(busWidth)))
    )
  }

  val segmentOut = DmaReadRespSegment(
    io.readRstCacheDataAndDmaReadResp,
    io.rxQCtrl.stateErrFlush,
    io.qpAttr.pmtu,
    busWidth,
    isReqZeroDmaLen =
      (reqAndDmaReadResp: ReadAtomicRstCacheDataAndDmaReadResp) =>
        reqAndDmaReadResp.rstCacheData.dlen === 0
  )
  io.readRstCacheDataAndDmaReadRespSegment <-/< segmentOut
}

class ReadRespGenerator(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val readRstCacheDataAndDmaReadRespSegment = slave(
      Stream(Fragment(ReadAtomicRstCacheDataAndDmaReadResp(busWidth)))
    )
    val txReadResp = master(RdmaDataBus(busWidth))
    val txDupReadResp = master(RdmaDataBus(busWidth))
  }

  val busWidthBytes = busWidth.id / BYTE_WIDTH

  val input = io.readRstCacheDataAndDmaReadRespSegment
  when(input.valid) {
    assert(
      assertion = input.rstCacheData.psnStart === input.dmaReadResp.psnStart &&
        input.rstCacheData.dlen === input.dmaReadResp.lenBytes,
      message =
        L"${REPORT_TIME} time: rstCacheData.psnStart=${input.rstCacheData.psnStart} should equal dmaReadResp.psnStart=${input.dmaReadResp.psnStart}, input.rstCacheData.dlen=${input.rstCacheData.dlen} should equal input.dmaReadResp.lenBytes=${input.dmaReadResp.lenBytes}".toSeq,
      severity = FAILURE
    )
  }

  val inputReqAndDmaReadRespSegment = input.translateWith {
    val result =
      Fragment(ReqAndDmaReadResp(ReadAtomicRstCacheData(), busWidth))
    result.dmaReadResp := input.dmaReadResp
    result.req := input.rstCacheData
    result.last := input.isLast
    result
  }

  val (normalReqIdx, dupReqIdx) = (0, 1)
  val twoStreams = StreamDemux(
    inputReqAndDmaReadRespSegment,
    select = inputReqAndDmaReadRespSegment.req.dupReq.asUInt,
    portCount = 2
  )
  val (normalReqAndDmaReadRespSegment, dupReqAndDmaReadRespSegment) =
    (twoStreams(normalReqIdx), twoStreams(dupReqIdx))

  val readRespHeaderGenFunc = (
      inputRstCacheData: ReadAtomicRstCacheData,
      inputDmaDataFrag: DmaReadResp,
      curReadRespPktCntVal: UInt,
      qpAttr: QpAttrData
  ) =>
    new Composite(this, "readRespHeaderGenFunc") {
      val numReadRespPkt = inputRstCacheData.pktNum
      val lastOrOnlyReadRespPktLenBytes =
        moduloByPmtu(inputDmaDataFrag.lenBytes, qpAttr.pmtu)

      val isFromFirstResp =
        inputDmaDataFrag.psnStart === inputRstCacheData.psnStart
      val curPsn = inputDmaDataFrag.psnStart + curReadRespPktCntVal
      val opcode = Bits(OPCODE_WIDTH bits)
      val padCnt = U(0, PAD_COUNT_WIDTH bits)

      val bth = BTH().set(
        opcode = opcode,
        padCnt = padCnt,
        dqpn = qpAttr.dqpn,
        psn = curPsn
      )
      val aeth = AETH().set(AckType.NORMAL)
      val bthMty = Bits(widthOf(bth) / BYTE_WIDTH bits).setAll()
      val aethMty = Bits(widthOf(aeth) / BYTE_WIDTH bits).setAll()

      val headerBits = Bits(busWidth.id bits)
      val headerMtyBits = Bits(busWidthBytes bits)
      when(numReadRespPkt > 1) {
        when(curReadRespPktCntVal === 0) {
          when(isFromFirstResp) {
            opcode := OpCode.RDMA_READ_RESPONSE_FIRST.id

//            headerBits := (bth ## aeth).resize(busWidth.id)
//            headerMtyBits := (bthMty ## aethMty).resize(busWidthBytes)
            headerBits := mergeRdmaHeader(busWidth, bth, aeth)
            headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, aethMty)
          } otherwise {
            opcode := OpCode.RDMA_READ_RESPONSE_MIDDLE.id

//            headerBits := bth.asBits.resize(busWidth.id)
//            headerMtyBits := bthMty.resize(busWidthBytes)
            headerBits := mergeRdmaHeader(busWidth, bth)
            headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty)
          }
        } elsewhen (curReadRespPktCntVal === numReadRespPkt - 1) {
          opcode := OpCode.RDMA_READ_RESPONSE_LAST.id
          padCnt := (PAD_COUNT_FULL -
            lastOrOnlyReadRespPktLenBytes(0, PAD_COUNT_WIDTH bits))
            .resize(PAD_COUNT_WIDTH)

//          headerBits := (bth ## aeth).resize(busWidth.id)
//          headerMtyBits := (bthMty ## aethMty).resize(busWidthBytes)
          headerBits := mergeRdmaHeader(busWidth, bth, aeth)
          headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, aethMty)
        } otherwise {
          opcode := OpCode.RDMA_READ_RESPONSE_MIDDLE.id

          // TODO: verify endian
//          headerBits := bth.asBits.resize(busWidth.id)
//          headerMtyBits := bthMty.resize(busWidthBytes)
          headerBits := mergeRdmaHeader(busWidth, bth)
          headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty)
        }
      } otherwise {
        when(isFromFirstResp) {
          opcode := OpCode.RDMA_READ_RESPONSE_ONLY.id
        } otherwise {
          opcode := OpCode.RDMA_READ_RESPONSE_LAST.id
        }
        padCnt := (PAD_COUNT_FULL -
          lastOrOnlyReadRespPktLenBytes(0, PAD_COUNT_WIDTH bits))
          .resize(PAD_COUNT_WIDTH)

//        headerBits := (bth ## aeth).resize(busWidth.id)
//        headerMtyBits := (bthMty ## aethMty).resize(busWidthBytes)
        headerBits := mergeRdmaHeader(busWidth, bth, aeth)
        headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, aethMty)
      }

      val result = CombineHeaderAndDmaRespInternalRst(busWidth)
        .set(inputRstCacheData.pktNum, bth, headerBits, headerMtyBits)
    }.result

  val normalReqResp = CombineHeaderAndDmaResponse(
    normalReqAndDmaReadRespSegment,
    io.qpAttr,
    io.rxQCtrl.stateErrFlush,
    busWidth,
    headerGenFunc = readRespHeaderGenFunc
  )
  io.txReadResp.pktFrag <-/< normalReqResp.pktFrag

  val dupReqResp = CombineHeaderAndDmaResponse(
    dupReqAndDmaReadRespSegment,
    io.qpAttr,
    io.rxQCtrl.stateErrFlush,
    busWidth,
    headerGenFunc = readRespHeaderGenFunc
  )
  io.txDupReadResp.pktFrag <-/< dupReqResp.pktFrag
}

// pp. 286 spec 1.4
// If an RDMA READ work request is posted before an ATOMIC Operation
// work request then the atomic may execute its remote memory
// operations before the previous RDMA READ has read its data.
// This can occur because the responder is allowed to delay execution
// of the RDMA READ. Strict ordering can be assured by posting
// the ATOMIC Operation work request with the fence modifier.
//
// When a sequence of requests arrives at a QP, the ATOMIC Operation
// only accesses memory after prior (non-RDMA READ) requests
// access memory and before subsequent requests access memory.
// Since the responder takes time to issue the response to the atomic
// request, and this response takes more time to reach the requester
// and even more time for the requester to create a completion queue
// entry, requests after the atomic may access the responders memory
// before the requester writes the completion queue entry for the
// ATOMIC Operation request.
class AtomicRespGenerator(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val atomicDmaReqAndRstCacheData = slave(
      Stream(RqDmaReadReqAndRstCacheData())
    )
    val tx = master(Stream(AtomicResp()))
    val dma = master(DmaBus(busWidth))
  }

  // TODO: support atomic request
  val atomicReqPsn = io.atomicDmaReqAndRstCacheData.rstCacheData.psnStart
  io.dma.rd.req <-/< io.dma.rd.resp.translateWith(
    DmaReadReq().set(
      initiator = DmaInitiator.RQ_ATOMIC_RD,
      psnStart = atomicReqPsn,
      sqpn = io.qpAttr.sqpn,
      pa = 0,
      lenBytes = ATOMIC_DATA_LEN
    )
  )
  io.dma.wr.req <-/< io.dma.wr.resp.translateWith {
    val result = Fragment(DmaWriteReq(busWidth))
    result.set(
      initiator = DmaInitiator.RQ_ATOMIC_WR,
      sqpn = io.qpAttr.sqpn,
      psn = atomicReqPsn,
      // TODO: set the following fields with actual value
      pa = 0,
      workReqId = 0, // TODO: RQ has no WR ID, need refactor
      data = 0,
      mty = 0
    )
    result.last := True
    result
  }

  // TODO: support atomic request
  val atomicRespStream = io.atomicDmaReqAndRstCacheData.map { payloadData =>
    val atomicResp = cloneOf(io.tx.payloadType)
    atomicResp.setDefaultVal().allowOverride // TODO: remove this
    atomicResp.bth.opcode := OpCode.ATOMIC_ACKNOWLEDGE.id
    atomicResp.bth.psn := payloadData.rstCacheData.psnStart
    atomicResp.aeth.set(AckType.NORMAL)
    atomicResp.atomicAckEth.orig := payloadData.rstCacheData.comp

    atomicResp
  }

  val delayedAtomicRespStream = DelayedStream(
    atomicRespStream,
    DMA_WRITE_DELAY_CYCLES + DMA_READ_DELAY_CYCLES
  )
  io.tx <-/< delayedAtomicRespStream
}

// For duplicated requests, also return ACK in PSN order
// TODO: after RNR and NAK SEQ returned, no other nested NAK send
class RqOut(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val opsnInc = out(OPsnInc())
    val outPsnRangeFifoPush = slave(Stream(RespPsnRange()))
    val readAtomicRstCachePop = slave(Stream(ReadAtomicRstCacheData()))
    val rxSendWriteResp = slave(Stream(Acknowledge()))
    // Both normal and duplicate read response
    val rxReadResp = slave(RdmaDataBus(busWidth))
    val rxAtomicResp = slave(Stream(AtomicResp()))
    val rxErrResp = slave(Stream(Acknowledge()))
    val rxDupSendWriteResp = slave(Stream(Acknowledge()))
    val rxDupReadResp = slave(RdmaDataBus(busWidth))
    val rxDupAtomicResp = slave(Stream(AtomicResp()))
    val tx = master(RdmaDataBus(busWidth))
  }

  val rxSendWriteResp = FixedLenQueue(
    io.tx.pktFrag.payloadType(),
    depth = PENDING_REQ_FIFO_DEPTH,
    push = io.rxSendWriteResp
      .throwWhen(io.rxQCtrl.stateErrFlush)
      .translateWith(io.rxSendWriteResp.asRdmaDataPktFrag(busWidth)),
    flush = io.rxQCtrl.stateErrFlush,
    queueName = "rxSendWriteResp"
  )
  val rxAtomicResp = FixedLenQueue(
    io.tx.pktFrag.payloadType(),
    depth = PENDING_READ_ATOMIC_REQ_FIFO_DEPTH,
    push = io.rxAtomicResp
      .throwWhen(io.rxQCtrl.stateErrFlush)
      .translateWith(io.rxAtomicResp.asRdmaDataPktFrag(busWidth)),
    flush = io.rxQCtrl.stateErrFlush,
    queueName = "rxAtomicResp"
  )

  val rxErrResp =
    io.rxErrResp.translateWith(io.rxErrResp.asRdmaDataPktFrag(busWidth))
  val rxDupSendWriteResp = io.rxDupSendWriteResp
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith(
      io.rxDupSendWriteResp.asRdmaDataPktFrag(busWidth)
    )
  val rxDupAtomicResp = io.rxDupAtomicResp
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith(
      io.rxDupAtomicResp.asRdmaDataPktFrag(busWidth)
    )
  val normalRespVec =
    Vec(rxSendWriteResp, io.rxReadResp.pktFrag, rxAtomicResp, rxErrResp)

  val hasPktToOutput = Bool()
  val (normalRespOutSel, psnOutRangeQueuePop) = SeqOut(
    curPsn = io.qpAttr.epsn,
    flush = io.rxQCtrl.stateErrFlush,
    outPsnRangeQueuePush = io.outPsnRangeFifoPush,
    outDataStreamVec = normalRespVec,
    outputValidateFunc = (
        psnOutRangeFifoPop: RespPsnRange,
        resp: RdmaDataPkt
    ) => {
      assert(
        assertion = checkRespOpCodeMatch(
          reqOpCode = psnOutRangeFifoPop.opcode,
          respOpCode = resp.bth.opcode
        ),
        message =
          L"${REPORT_TIME} time: request opcode=${psnOutRangeFifoPop.opcode} and response opcode=${resp.bth.opcode} not match, resp.bth.psn=${resp.bth.psn}, psnOutRangeQueuePop.start=${psnOutRangeFifoPop.start}, psnOutRangeQueuePop.end=${psnOutRangeFifoPop.end}".toSeq,
        severity = FAILURE
      )
    },
    hasPktToOutput = hasPktToOutput,
    opsnInc = io.opsnInc
  )
//  when(psnOutRangeFifo.io.push.fire) {
//    report(
//      L"${REPORT_TIME} time: psnOutRangeFifo push PSN start=${psnOutRangeFifo.io.push.start}, end=${psnOutRangeFifo.io.push.end}".toSeq
//    )
//  }
//  when(psnOutRangeQueuePop.fire) {
//    report(
//      L"${REPORT_TIME} time: psnOutRangeFifo pop PSN start=${psnOutRangeQueuePop.start}, end=${psnOutRangeQueuePop.end}".toSeq
//    )
//  }
  val (normalRespOut, normalRespOut4ReadAtomicRstCache) =
    StreamFork2(normalRespOutSel)

  val dupRespVec =
    Vec(rxDupSendWriteResp, io.rxDupReadResp.pktFrag, rxDupAtomicResp)
  // TODO: duplicate output also needs to keep PSN order
  val dupRespOut =
    StreamArbiterFactory().roundRobin.fragmentLock.on(dupRespVec)

  val finalOutStream = StreamArbiterFactory().roundRobin.fragmentLock
    .onArgs(
      dupRespOut,
      normalRespOut
    )
  io.tx.pktFrag <-/< finalOutStream.throwWhen(io.rxQCtrl.stateErrFlush)

  val isReadReq = OpCode.isReadReqPkt(psnOutRangeQueuePop.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(psnOutRangeQueuePop.opcode)
  when(hasPktToOutput && (isReadReq || isAtomicReq)) {
    assert(
      assertion = io.readAtomicRstCachePop.valid,
      message =
        L"${REPORT_TIME} time: io.readAtomicRstCachePop.valid=${io.readAtomicRstCachePop.valid} should be true when has read/atomic response, hasPktToOutput=${hasPktToOutput}, isReadReq=${isReadReq}, isAtomicReq=${isAtomicReq}".toSeq,
      severity = FAILURE
    )
  }

  val normalRespOutJoinReadAtomicRstCachePopCond = (isReadReq || isAtomicReq) &&
    (normalRespOut4ReadAtomicRstCache.bth.psn === (io.readAtomicRstCachePop.psnStart + (io.readAtomicRstCachePop.pktNum - 1)))
  val normalRespOutJoinReadAtomicRstCache = FragmentStreamConditionalJoinStream(
    inputFragStream = normalRespOut4ReadAtomicRstCache,
    inputStream = io.readAtomicRstCachePop,
    joinCond = normalRespOutJoinReadAtomicRstCachePopCond
  )
  StreamSink(NoData()) <<
    normalRespOutJoinReadAtomicRstCache.translateWith(NoData())
  when(io.readAtomicRstCachePop.fire) {
//    assert(
//      assertion = isReadReq || isAtomicReq,
//      message =
//        L"${REPORT_TIME} time: the output should correspond to read/atomic resp, io.readAtomicRstCachePop.fire=${io.readAtomicRstCachePop.fire}, but psnOutRangeQueuePop.opcode=${psnOutRangeQueuePop.opcode}",
//      severity = FAILURE
//    )
//    assert(
//      assertion = psnOutRangeQueuePop.fire,
//      message =
//        L"${REPORT_TIME} time: io.readAtomicRstCachePop.fire=${io.readAtomicRstCachePop.fire} and psnOutRangeQueuePop.fire=${psnOutRangeQueuePop.fire} should fire at the same time",
//      severity = FAILURE
//    )
    assert(
      assertion = checkRespOpCodeMatch(
        reqOpCode = io.readAtomicRstCachePop.opcode,
        respOpCode = normalRespOut4ReadAtomicRstCache.bth.opcode
      ),
      message =
        L"${REPORT_TIME} time: io.readAtomicRstCachePop.opcode=${io.readAtomicRstCachePop.opcode} should match normalRespOut4ReadAtomicRstCache.bth.opcode=${normalRespOut4ReadAtomicRstCache.bth.opcode}, normalRespOut4ReadAtomicRstCache.bth.psn=${normalRespOut4ReadAtomicRstCache.bth.psn}, io.readAtomicRstCachePop.psnStart=${io.readAtomicRstCachePop.psnStart}, io.readAtomicRstCachePop.pktNum=${io.readAtomicRstCachePop.pktNum}".toSeq,
      severity = FAILURE
    )
  }
}
