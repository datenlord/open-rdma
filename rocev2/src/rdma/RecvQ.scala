package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._

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

// INCONSISTENT: atomic might not access memory in PSN order w.r.t. send and write

// There is no alignment requirement for the source or
// destination buffers of an RDMA READ message.

// RQ executes Send, Write, Atomic in order;
// RQ can delay Read execution;
// Completion of Send and Write at RQ is in PSN order,
// but not imply previous Read is complete unless fenced;
// RQ saves Atomic (Req & Result) and Read (Req only) in
// Queue Context, size as # pending Read/Atomic;
// TODO: RQ should send explicit ACK to SQ if it received many un-signaled requests
class RecvQ(busWidth: BusWidth) extends Component {
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
    val reqDmaInfoExtractor = new ReqDmaInfoExtractor(busWidth)
    reqDmaInfoExtractor.io.qpAttr := io.qpAttr
    reqDmaInfoExtractor.io.rxQCtrl := io.rxQCtrl
    reqDmaInfoExtractor.io.rx << reqRnrCheck.io.tx

    val reqAddrValidator = new ReqAddrValidator(busWidth)
    reqAddrValidator.io.qpAttr := io.qpAttr
    reqAddrValidator.io.rxQCtrl := io.rxQCtrl
    reqAddrValidator.io.rx << reqDmaInfoExtractor.io.tx
    io.addrCacheRead << reqAddrValidator.io.addrCacheRead

    val pktLenCheck = new PktLenCheck(busWidth)
    pktLenCheck.io.qpAttr := io.qpAttr
    pktLenCheck.io.rxQCtrl := io.rxQCtrl
    pktLenCheck.io.rx << reqAddrValidator.io.tx

    val reqSplitterAndNakGen = new ReqSplitterAndNakGen(busWidth)
    reqSplitterAndNakGen.io.qpAttr := io.qpAttr
    reqSplitterAndNakGen.io.rxQCtrl := io.rxQCtrl
    reqSplitterAndNakGen.io.rx << pktLenCheck.io.tx
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

  val rqSendWriteWorkCompGenerator = new RqSendWriteWorkCompGenerator
  rqSendWriteWorkCompGenerator.io.qpAttr := io.qpAttr
  rqSendWriteWorkCompGenerator.io.rxQCtrl := io.rxQCtrl
  rqSendWriteWorkCompGenerator.io.dmaWriteResp.resp << io.dma.sendWrite.resp
  rqSendWriteWorkCompGenerator.io.sendWriteWorkCompAndAck << respGenLogic.sendWriteRespGenerator.io.sendWriteWorkCompAndAck
//  rqSendWriteWorkCompGenerator.io.sendWriteWorkCompErr << reqValidateLogic.reqSplitterAndNakGen.io.sendWriteWorkCompErrAndNak
  io.sendWriteWorkComp << rqSendWriteWorkCompGenerator.io.sendWriteWorkCompOut

  val rqOut = new RqOut(busWidth)
  rqOut.io.qpAttr := io.qpAttr
  rqOut.io.rxQCtrl := io.rxQCtrl
  rqOut.io.outPsnRangeFifoPush << reqValidateLogic.reqDmaInfoExtractor.io.rqOutPsnRangeFifoPush
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
class ReqCommCheck(busWidth: BusWidth) extends Component {
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

    // PSN sequence check
    val isPsnCheckPass = Bool()
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
        isPsnCheckPass := False
        isDupReq := False
      }
      is(PsnCompResult.LESSER) {
        isPsnCheckPass := inputValid
        isDupPendingReq := inputValid && isLargerThanOPsn
        isDupReq := inputValid && !isLargerThanOPsn
      }
      default { // PsnCompResult.EQUAL
        isPsnCheckPass := inputValid
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

    // Check for # of pending read/atomic
    val isReadOrAtomicReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
    val isReadAtomicRstCacheFull = inputValid && isReadOrAtomicReq &&
      io.readAtomicRstCacheOccupancy >= io.qpAttr.maxPendingReadAtomicReqNum

    // TODO: should discard duplicate pending requests?
    val throwCond = isDupPendingReq
    when(isDupPendingReq) {
      report(
        message =
          L"${REPORT_TIME} time: duplicated pending request received with PSN=${inputPktFrag.bth.psn} and opcode=${inputPktFrag.bth.opcode}, RQ opsn=${io.qpAttr.rqOutPsn}, epsn=${io.qpAttr.epsn}, maybe it should increase SQ timeout threshold",
        severity = FAILURE
      )
    }
    val output =
      io.rx.pktFrag.throwWhen(throwCond).translateWith { // No flush this stage
        val result = Fragment(RqReqCheckInternalOutput(busWidth))
        result.pktFrag := inputPktFrag
        result.checkRst.isPsnCheckPass := isPsnCheckPass
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
    val isExpectedPkt = inputValid && isPsnCheckPass && !isDupReq
    io.epsnInc.inc := isExpectedPkt && output.lastFire // Only update ePSN when each request packet ended
    io.epsnInc.incVal := ePsnIncrease(inputPktFrag, io.qpAttr.pmtu)
    io.epsnInc.preReqOpCode := inputPktFrag.bth.opcode

    // Clear RNR or NAK SEQ if any
    io.clearRnrOrNakSeq.pulse := (io.rxQCtrl.rnrFlush || io.rxQCtrl.nakSeqTrigger) && isExpectedPkt
  }

  val outputStage = new Area {
    val input = cloneOf(checkStage.output)
    input <-/< checkStage.output

    val isPsnCheckPass = input.checkRst.isPsnCheckPass
    val isDupReq = input.checkRst.isDupReq
    val isInvReq =
      input.checkRst.isOpSeqCheckPass || input.checkRst.isSupportedOpCode ||
        input.checkRst.isPadCntCheckPass || input.checkRst.isReadAtomicRstCacheFull

    val hasNak = Bool()
    val nakAeth = AETH().set(AckType.NORMAL)
    when(isPsnCheckPass) {
      hasNak := False
      when(isDupReq) {
        nakAeth.set(AckType.NAK_SEQ)
      }
    } elsewhen (!isInvReq) {
      hasNak := True
      nakAeth.set(AckType.NAK_SEQ)
    } otherwise { // NAK_INV
      hasNak := True
      nakAeth.set(AckType.NAK_INV)
    }

    io.tx.checkRst <-/< input.throwWhen(io.rxQCtrl.flush).translateWith {
      val result = cloneOf(io.tx.checkRst.payloadType)
      result.pktFrag := input.pktFrag
      result.preOpCode := io.qpAttr.rqPreReqOpCode
      result.hasNak := hasNak
      result.nakAeth := nakAeth
      result.last := input.last
      result
    }
  }
}

class ReqRnrCheck(busWidth: BusWidth) extends Component {
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
        L"${REPORT_TIME} time: io.rxWorkReq.fire=${io.rxWorkReq.fire} should fire at the same cycle as io.rx.checkRst.lastFire=${io.rx.checkRst.lastFire} when needRxBuf=${needRxBuf}",
      severity = FAILURE
    )
  }

  val rnrAeth = AETH().set(AckType.NAK_RNR, io.qpAttr.rnrTimeOut)
  val nakAeth = cloneOf(io.rx.checkRst.nakAeth)
  nakAeth := io.rx.checkRst.nakAeth
  when(!inputHasNak && hasRnrErr) {
    nakAeth := rnrAeth
  }

  val (otherReqStream, dupReqStream) = StreamDeMuxByOneCondition(
    io.rx.checkRst.throwWhen(io.rxQCtrl.flush),
    nakAeth.isSeqNak()
  )
  io.txDupReq.checkRst <-/< dupReqStream

  io.tx.reqWithRxBuf <-/< otherReqStream
    .translateWith {
      val result = cloneOf(io.tx.reqWithRxBuf.payloadType)
      result.pktFrag := inputPktFrag
      result.preOpCode := io.rx.checkRst.preOpCode
      result.hasNak := inputHasNak || hasRnrErr
      result.nakAeth := nakAeth
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
    busWidth: BusWidth
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
  val nakAeth = io.rx.checkRst.nakAeth
  val isLastFrag = io.rx.checkRst.isLast

  when(io.rx.checkRst.valid) {
    assert(
      assertion = hasNak && nakAeth.isSeqNak(),
      message =
        L"duplicate request should have hasNak=${hasNak} is true, and nakAeth with code=${nakAeth.code} and value=${nakAeth.value} is NAK SEQ",
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
        val result = pktFragStream.hasNak && pktFragStream.nakAeth.isSeqNak()
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
    waitQueueDepth = READ_ATOMIC_RESULT_CACHE_QUERY_DELAY_CYCLE,
    buildQuery = buildReadAtomicRstCacheQuery,
    queryCond = readAtomicRstCacheQueryCond,
    expectResp = expectReadAtomicRstCacheResp,
    joinRespCond = readAtomicRstCacheQueryRespJoinCond
  )

  val readAtomicRstCacheRespValid = joinStream.valid
  val readAtomicRstCacheRespData = joinStream._2.respValue
  val readAtomicRstCacheRespFound = joinStream._2.found
  val readAtomicResultNotFound =
    readAtomicRstCacheRespValid && !readAtomicRstCacheRespFound

  val isAtomicReq = OpCode.isAtomicReqPkt(readAtomicRstCacheRespData.opcode)
  val isReadReq = OpCode.isReadReqPkt(readAtomicRstCacheRespData.opcode)
  when(readAtomicRstCacheRespValid) {
    // Duplicate requests of pending requests are already discarded by ReqCommCheck
    assert(
      assertion = readAtomicRstCacheRespFound,
      message =
        L"${REPORT_TIME} time: duplicated read/atomic request with PSN=${joinStream._2.queryKey.queryPsn} not found, readAtomicRstCacheRespValid=${readAtomicRstCacheRespValid}, but readAtomicRstCacheRespFound=${readAtomicRstCacheRespFound}",
      severity = ERROR
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
    .takeWhen(isAtomicReq)
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
    .takeWhen(isReadReq)
    .translateWith {
      val result = cloneOf(io.dupReadReqAndRstCacheData.payloadType)
      result.pktFrag := forkJoinStream4Read._1.pktFrag
      result.rstCacheData := forkJoinStream4Read._2.respValue
      when(isReadReq) {
        result.rstCacheData.duplicate := True
      }
      result
    }
}

class DupReadDmaReqBuilder(busWidth: BusWidth) extends Component {
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
      assertion = inputRstCacheData.duplicate,
      message =
        L"inputRstCacheData.duplicate=${inputRstCacheData.duplicate} should be true",
      severity = FAILURE
    )
  }
}

class ReqDmaInfoExtractor(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufBus(busWidth))
    val tx = master(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val rqOutPsnRangeFifoPush = master(Stream(RespPsnRange()))
  }

  val inputValid = io.rx.reqWithRxBuf.valid
  val inputPktFrag = io.rx.reqWithRxBuf.fragment
  val inputHasNak = io.rx.reqWithRxBuf.hasNak
  val isLastFrag = io.rx.reqWithRxBuf.last
  val isFirstFrag = io.rx.reqWithRxBuf.isFirst
  val rxBuf = io.rx.reqWithRxBuf.rxBuf

  val isSendOrWriteReq =
    OpCode.isSendReqPkt(inputPktFrag.pktFrag.bth.opcode) ||
      OpCode.isWriteReqPkt(inputPktFrag.pktFrag.bth.opcode)
  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.pktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.pktFrag.bth.opcode)

  // TODO: verify inputPktFrag.data is big endian
  val dmaInfo = DmaInfo().init()
  when(OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
    // Extract DMA info for send requests
    dmaInfo.va := rxBuf.laddr
    dmaInfo.lrkey := rxBuf.lkey
    dmaInfo.dlen := rxBuf.lenBytes

    when(inputValid) {
      assert(
        assertion = inputHasNak =/= io.rx.reqWithRxBuf.rxBufValid,
        message =
          L"${REPORT_TIME} time: inputHasNak=${inputHasNak} should != io.rx.reqWithRxBuf.rxBufValid=${io.rx.reqWithRxBuf.rxBufValid}",
        severity = FAILURE
      )
    }
  } elsewhen (OpCode.hasRethOrAtomicEth(inputPktFrag.pktFrag.bth.opcode)) {
    // Extract DMA info for write/read/atomic requests
    // AtomicEth has the same va, lrkey and dlen field with RETH
    val reth = inputPktFrag.pktFrag.extractReth()

    dmaInfo.va := reth.va
    dmaInfo.lrkey := reth.rkey
    dmaInfo.dlen := reth.dlen
    when(isAtomicReq) {
      dmaInfo.dlen := ATOMIC_DATA_LEN
    }
  }

  // Since the max length of a request is 2^31, and the min PMTU is 256=2^8
  // So the max number of packet is 2^23 < 2^PSN_WIDTH
  val numRespPkt =
    divideByPmtuUp(dmaInfo.dlen, io.qpAttr.pmtu).resize(PSN_WIDTH)
  val ackReq =
    (isSendOrWriteReq && inputPktFrag.pktFrag.bth.ackreq) || isReadReq || isAtomicReq
  val rqOutPsnRangeFifoPushCond =
    io.rx.reqWithRxBuf.isFirst && (inputHasNak || ackReq)
  val (rqOutPsnRangeFifoPush, txNormal) = StreamConditionalFork2(
    // Not flush when RNR
    io.rx.reqWithRxBuf.throwWhen(io.rxQCtrl.stateErrFlush),
    // The condition to send out RQ response
    forkCond = rqOutPsnRangeFifoPushCond
  )
  io.tx.reqWithRxBufAndDmaInfo <-/< txNormal
    .translateWith {
      val result = cloneOf(io.tx.reqWithRxBufAndDmaInfo.payloadType)
      result.pktFrag := txNormal.pktFrag
      result.preOpCode := txNormal.preOpCode
      result.hasNak := txNormal.hasNak
      result.nakAeth := txNormal.nakAeth
      result.reqTotalLenValid := False
      result.reqTotalLenBytes
        .assignDontCare() // This field will be updated in PktLenCheck
      result.rxBufValid := txNormal.rxBufValid
      result.rxBuf := rxBuf
      result.dmaInfo := dmaInfo
      result.last := txNormal.last
      result
    }

  // Update output FIFO to keep output PSN order
  io.rqOutPsnRangeFifoPush <-/< rqOutPsnRangeFifoPush.translateWith {
    val result = cloneOf(io.rqOutPsnRangeFifoPush.payloadType)
    result.opcode := inputPktFrag.pktFrag.bth.opcode
    result.start := inputPktFrag.pktFrag.bth.psn
    result.end := inputPktFrag.pktFrag.bth.psn
    when(isReadReq) {
      result.end := inputPktFrag.pktFrag.bth.psn + numRespPkt - 1
    }
    result
  }
}

class ReqAddrValidator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val rx = slave(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val tx = master(RqReqWithRxBufAndDmaInfoBus(busWidth))
  }

  // Build QpAddrCacheAgentReadReq according to RqReqWithRxBufAndDmaInfo
  val buildAddrCacheQuery =
    (pktFragStream: Stream[Fragment[RqReqWithRxBufAndDmaInfo]]) =>
      new Composite(pktFragStream, "ReqAddrValidator_buildAddrCacheQuery") {
        val inputValid = pktFragStream.valid
        val inputPktFrag = pktFragStream.pktFrag
        val inputHeader = pktFragStream.dmaInfo

        val accessKey = inputHeader.lrkey
        val va = inputHeader.va

        val accessType = AccessType()
        val pdId = io.qpAttr.pdId
        val remoteOrLocalKey = True // True: remote, False: local
        val dataLenBytes = inputHeader.dlen
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
                L"${REPORT_TIME} time: invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic",
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
    (pktFragStream: Stream[Fragment[RqReqWithRxBufAndDmaInfo]]) =>
      new Composite(pktFragStream, "ReqAddrValidator_expectAddrCacheResp") {
        val result = pktFragStream.valid && !pktFragStream.hasNak
      }.result

  // Only send out AddrCache query when the input data is the first fragment of
  // send/write/read/atomic request
  val addrCacheQueryCond =
    (pktFragStream: Stream[Fragment[RqReqWithRxBufAndDmaInfo]]) =>
      new Composite(pktFragStream, "ReqAddrValidator_addrCacheQueryCond") {
        val result = !pktFragStream.hasNak && pktFragStream.isFirst &&
          OpCode.isFirstOrOnlyReqPkt(pktFragStream.pktFrag.bth.opcode)
      }.result

  // Only join AddrCache query response when the input data is the last fragment of
  // the only or last send/write/read/atomic request
  val addrCacheQueryRespJoinCond =
    (pktFragStream: Stream[Fragment[RqReqWithRxBufAndDmaInfo]]) =>
      new Composite(
        pktFragStream,
        "ReqAddrValidator_addrCacheQueryRespJoinCond"
      ) {
        val result = pktFragStream.isLast &&
          OpCode.isLastOrOnlyReqPkt(pktFragStream.pktFrag.bth.opcode)
      }.result

  val joinStream = FragmentStreamForkQueryJoinResp(
    io.rx.reqWithRxBufAndDmaInfo.throwWhen(io.rxQCtrl.stateErrFlush),
    io.addrCacheRead.req,
    io.addrCacheRead.resp,
    waitQueueDepth = ADDR_CACHE_QUERY_DELAY_CYCLE,
    buildQuery = buildAddrCacheQuery,
    queryCond = addrCacheQueryCond,
    expectResp = expectAddrCacheResp,
    joinRespCond = addrCacheQueryRespJoinCond
  )

  val inputValid = joinStream.valid
  val inputPktFrag = joinStream._1.pktFrag
  val inputHasNak = joinStream._1.hasNak

  val addrCacheQueryResp = joinStream._2
  val sizeValid = addrCacheQueryResp.sizeValid
  val keyValid = addrCacheQueryResp.keyValid
  val accessValid = addrCacheQueryResp.accessValid
  val checkAddrCacheRespMatchCond =
    joinStream.isFirst && !joinStream._1.hasNak && OpCode
      .isFirstOrOnlyReqPkt(inputPktFrag.bth.opcode)
  when(inputValid && checkAddrCacheRespMatchCond) {
    assert(
      assertion = inputPktFrag.bth.psn === addrCacheQueryResp.psn,
      message =
        L"${REPORT_TIME} time: addrCacheReadResp.resp has PSN=${addrCacheQueryResp.psn} not match RQ query PSN=${inputPktFrag.bth.psn}",
      severity = FAILURE
    )
  }

  val bufLenErr = inputValid && !inputHasNak && !sizeValid
  val keyErr = inputValid && !inputHasNak && !keyValid
  val accessErr = inputValid && !inputHasNak && !accessValid
  val checkPass =
    inputValid && !inputHasNak && !keyErr && !bufLenErr && !accessErr

  val nakInvOrRmtAccAeth = AETH().setDefaultVal()
  when(bufLenErr) {
    nakInvOrRmtAccAeth.set(AckType.NAK_INV)
  } elsewhen (keyErr || accessErr) {
    nakInvOrRmtAccAeth.set(AckType.NAK_RMT_ACC)
  }

  val outputHasNak = !checkPass || inputHasNak
  val outputNakAeth = cloneOf(joinStream._1.nakAeth)
  outputNakAeth := joinStream._1.nakAeth
  when(!checkPass && !inputHasNak) {
    // TODO: if rdmaAck is retry NAK, should it be replaced by this NAK_INV or NAK_RMT_ACC?
    outputNakAeth := nakInvOrRmtAccAeth
  }

  io.tx.reqWithRxBufAndDmaInfo <-/< joinStream
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith {
      val result = cloneOf(io.tx.reqWithRxBufAndDmaInfo.payloadType)
      result.pktFrag := joinStream._1.pktFrag
      result.preOpCode := joinStream._1.preOpCode
      result.hasNak := outputHasNak
      result.nakAeth := outputNakAeth
      result.reqTotalLenValid := False
      result.reqTotalLenBytes
        .assignDontCare() // This field will be updated in PktLenCheck
      result.rxBufValid := joinStream._1.rxBufValid
      result.rxBuf := joinStream._1.rxBuf
      result.dmaInfo.pa := joinStream._2.pa
      result.dmaInfo.lrkey := joinStream._1.dmaInfo.lrkey
      result.dmaInfo.va := joinStream._1.dmaInfo.va
      result.dmaInfo.dlen := joinStream._1.dmaInfo.dlen
      result.last := joinStream.isLast
      result
    }
}

// TODO: verify ICRC has been stripped off
class PktLenCheck(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val tx = master(RqReqWithRxBufAndDmaInfoBus(busWidth))
  }

  val inputHasNak = io.rx.reqWithRxBufAndDmaInfo.hasNak
  val inputValid = io.rx.reqWithRxBufAndDmaInfo.valid
  val inputFire = io.rx.reqWithRxBufAndDmaInfo.fire
  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfo.pktFrag
  val inputRxBuf = io.rx.reqWithRxBufAndDmaInfo.rxBuf
  val inputDmaInfo = io.rx.reqWithRxBufAndDmaInfo.dmaInfo
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfo.isLast
  val isFirstFrag = io.rx.reqWithRxBufAndDmaInfo.isFirst

  val pktFragLenBytes =
    CountOne(inputPktFrag.mty).resize(RDMA_MAX_LEN_WIDTH)
  when(inputValid) {
    assert(
      assertion = inputPktFrag.mty =/= 0,
      message =
        L"invalid MTY=${inputPktFrag.mty}, opcode=${inputPktFrag.bth.opcode}, PSN=${inputPktFrag.bth.psn}, isFirstFrag=${isFirstFrag}, isLastFrag=${isLastFrag}",
      severity = FAILURE
    )
  }
  val dmaTargetLenBytes = io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen
  val bthLenBytes = widthOf(BTH()) / BYTE_WIDTH
  val rethLenBytes = widthOf(RETH()) / BYTE_WIDTH
  val immDtLenBytes = widthOf(ImmDt()) / BYTE_WIDTH
  val iethLenBytes = widthOf(IETH()) / BYTE_WIDTH

  when(inputValid && io.rx.reqWithRxBufAndDmaInfo.rxBufValid) {
    assert(
      assertion = OpCode.isSendReqPkt(inputPktFrag.bth.opcode) ||
        OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode),
      message =
        L"${REPORT_TIME} time: it should be send requests that require receive buffer, but opcode=${inputPktFrag.bth.opcode}",
      severity = FAILURE
    )
  }

  val padCntAdjust = inputPktFrag.bth.padCnt
  val rethLenAdjust = U(0, log2Up(rethLenBytes) + 1 bits)
  when(OpCode.isWriteFirstOrOnlyReqPkt(inputPktFrag.bth.opcode)) {
    rethLenAdjust := rethLenBytes
  }
  val immDtLenAdjust = U(0, log2Up(immDtLenBytes) + 1 bits)
  when(OpCode.hasImmDt(inputPktFrag.bth.opcode)) {
    immDtLenAdjust := immDtLenBytes
  }
  val iethLenAdjust = U(0, log2Up(iethLenBytes) + 1 bits)
  when(OpCode.hasIeth(inputPktFrag.bth.opcode)) {
    iethLenAdjust := iethLenBytes
  }

  val pktLenBytesReg = RegInit(U(0, RDMA_MAX_LEN_WIDTH bits))
  val reqTotalLenBytesReg = RegInit(U(0, RDMA_MAX_LEN_WIDTH bits))
  when(io.rxQCtrl.stateErrFlush) {
    pktLenBytesReg := 0
    reqTotalLenBytesReg := 0
  }

  // All the packet length adjustments used when isFirstFrag
  // NOTE: use +^ to avoid addition overflow
  val firstPktAdjust = bthLenBytes +^ rethLenAdjust
  val midPktAdjust = U(bthLenBytes)
  val lastPktEthAdjust = padCntAdjust +^ immDtLenAdjust +^ iethLenAdjust
  val lastPktAdjust = bthLenBytes +^ lastPktEthAdjust
  val onlyPktAdjust = firstPktAdjust +^ lastPktEthAdjust

  val curPktFragLenBytesFirstPktAdjust = pktFragLenBytes - firstPktAdjust
  val curPktFragLenBytesMidPktAdjust = pktFragLenBytes - midPktAdjust
  val curPktFragLenBytesLastPktAdjust = pktFragLenBytes - lastPktAdjust
  val curPktFragLenBytesOnlyPktAdjust = pktFragLenBytes - onlyPktAdjust

  val isSendReq = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
  val isWriteReq = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
  // TODO: verify reqTotalLenValid and reqTotalLenBytes have correct value and timing
  val curPktLenBytes = pktLenBytesReg + pktFragLenBytes
  val curReqTotalLenBytes = reqTotalLenBytesReg + curPktLenBytes
  // Used when last request packet and isFirstFrag && isLastFrag
  val curReqTotalLenBytesLastPktAdjust =
    reqTotalLenBytesReg + curPktFragLenBytesLastPktAdjust

  val isReqTotalLenCheckErr = False
  val isPktLenCheckErr = False
  val reqTotalLenValid = False
  val reqTotalLenBytes = U(0, RDMA_MAX_LEN_WIDTH bits)
  when(inputFire && (isSendReq || isWriteReq)) {
    switch(isFirstFrag ## isLastFrag) {
      is(True ## False) { // isFirstFrag && !isLastFrag // First fragment should consider RDMA header adjustment
        when(OpCode.isFirstReqPkt(inputPktFrag.bth.opcode)) {
          pktLenBytesReg := curPktFragLenBytesFirstPktAdjust
        } elsewhen (OpCode.isLastReqPkt(inputPktFrag.bth.opcode)) {
          pktLenBytesReg := curPktFragLenBytesLastPktAdjust
        } elsewhen (OpCode.isOnlyReqPkt(inputPktFrag.bth.opcode)) {
          pktLenBytesReg := curPktFragLenBytesOnlyPktAdjust
        } elsewhen (OpCode.isMidReqPkt(inputPktFrag.bth.opcode)) {
          pktLenBytesReg := curPktFragLenBytesMidPktAdjust
        } otherwise {
          report(
            message =
              L"${REPORT_TIME} time: illegal opcode=${inputPktFrag.bth.opcode} when isFirstFrag=${isFirstFrag} and isLastFrag=${isLastFrag}",
            severity = FAILURE
          )
        }
      }
      is(False ## True) { // !isFirstFrag && isLastFrag // Last packet
        pktLenBytesReg := 0

        when(OpCode.isLastOrOnlyReqPkt(inputPktFrag.bth.opcode)) {
          reqTotalLenBytesReg := 0
          reqTotalLenValid := True
          reqTotalLenBytes := curReqTotalLenBytes
        } elsewhen (OpCode.isFirstReqPkt(inputPktFrag.bth.opcode)) {
          reqTotalLenBytesReg := curReqTotalLenBytes

          isPktLenCheckErr :=
            curPktLenBytes =/= pmtuPktLenBytes(io.qpAttr.pmtu) ||
              padCntAdjust =/= 0
          assert(
            assertion = !isPktLenCheckErr,
            message =
              L"${REPORT_TIME} time: first request packet length check failed, opcode=${inputPktFrag.bth.opcode}, PSN=${inputPktFrag.bth.psn}, curPktLenBytes=${curPktLenBytes}, padCnt=${padCntAdjust}",
            severity = NOTE
          )
        } elsewhen (OpCode.isMidReqPkt(inputPktFrag.bth.opcode)) {
          reqTotalLenBytesReg := curReqTotalLenBytes

          isPktLenCheckErr :=
            curPktLenBytes =/= pmtuPktLenBytes(io.qpAttr.pmtu) ||
              padCntAdjust =/= 0
          assert(
            assertion = !isPktLenCheckErr,
            message =
              L"${REPORT_TIME} time: middle request packet length check failed, opcode=${inputPktFrag.bth.opcode}, PSN=${inputPktFrag.bth.psn}, curPktLenBytes=${curPktLenBytes}, padCnt=${padCntAdjust}",
            severity = NOTE
          )
        }
      }
      is(True ## True) { // isFirstFrag && isLastFrag // Only one fragment
        pktLenBytesReg := 0

        when(OpCode.isOnlyReqPkt(inputPktFrag.bth.opcode)) {
          reqTotalLenBytesReg := 0
          reqTotalLenValid := True
          reqTotalLenBytes := curPktFragLenBytesOnlyPktAdjust
        } elsewhen (OpCode.isLastReqPkt(inputPktFrag.bth.opcode)) {
          reqTotalLenBytesReg := 0
          reqTotalLenValid := True
          reqTotalLenBytes := curReqTotalLenBytesLastPktAdjust
        } elsewhen (OpCode.isFirstOrMidReqPkt(inputPktFrag.bth.opcode)) {
          report(
            message =
              L"${REPORT_TIME} time: first or middle request packet length check failed, opcode=${inputPktFrag.bth.opcode}, when isFirstFrag=${isFirstFrag} and isLastFrag=${isLastFrag}",
            severity = FAILURE // TODO: change to NOTE
          )

          isPktLenCheckErr := True
        }
      }
      is(False ## False) { // !isFirstFrag && !isLastFrag // Middle fragment
        pktLenBytesReg := curPktLenBytes
      }
    }
  }

  when(reqTotalLenValid) {
    isReqTotalLenCheckErr :=
      (isSendReq && reqTotalLenBytes > dmaTargetLenBytes) ||
        (isWriteReq && reqTotalLenBytes =/= dmaTargetLenBytes)
    assert(
      assertion = !isReqTotalLenCheckErr,
      message =
        L"${REPORT_TIME} time: request total length check failed, opcode=${inputPktFrag.bth.opcode}, PSN=${inputPktFrag.bth.psn}, dmaTargetLenBytes=${dmaTargetLenBytes}, reqTotalLenBytes=${reqTotalLenBytes} = (reqTotalLenBytesReg=${reqTotalLenBytesReg} + pktLenBytesReg=${pktLenBytesReg} + pktFragLenBytes=${pktFragLenBytes}), inputPktFrag.mty=${inputPktFrag.mty}, CountOne(inputPktFrag.mty)=${CountOne(
          inputPktFrag.mty
        )}, bthLenBytes=${U(bthLenBytes)}, rethLenAdjust=${rethLenAdjust}, immDtLenAdjust=${immDtLenAdjust}, iethLenAdjust=${iethLenAdjust}, padCntAdjust=${padCntAdjust}, isFirstFrag=${isFirstFrag}, isLastFrag=${isLastFrag}",
      severity = NOTE
    )
  }

  val hasLenCheckErr = isReqTotalLenCheckErr || isPktLenCheckErr
  val nakInvAeth = AETH().set(AckType.NAK_INV)
  val nakAeth = cloneOf(io.rx.reqWithRxBufAndDmaInfo.nakAeth)
  val outputHasNak = inputHasNak || hasLenCheckErr
  nakAeth := io.rx.reqWithRxBufAndDmaInfo.nakAeth
  when(hasLenCheckErr && !inputHasNak) {
    // TODO: if rdmaAck is retry NAK, should it be replaced by this NAK_INV or NAK_RMT_ACC?
    nakAeth := nakInvAeth
  }

  io.tx.reqWithRxBufAndDmaInfo <-/< io.rx.reqWithRxBufAndDmaInfo
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith {
      val result = cloneOf(io.rx.reqWithRxBufAndDmaInfo.payloadType)
      result.pktFrag := inputPktFrag
      result.preOpCode := io.rx.reqWithRxBufAndDmaInfo.preOpCode
      result.hasNak := outputHasNak
      result.nakAeth := nakAeth
      result.rxBufValid := io.rx.reqWithRxBufAndDmaInfo.rxBufValid
      result.rxBuf := inputRxBuf
      result.reqTotalLenValid := reqTotalLenValid
      result.reqTotalLenBytes := reqTotalLenBytes
      result.dmaInfo := inputDmaInfo
      result.last := isLastFrag
      result
    }
}

class ReqSplitterAndNakGen(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val txSendWrite = master(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val txReadAtomic = master(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val nakNotifier = out(RqNakNotifier())
    val txErrResp = master(Stream(Acknowledge()))
  }

  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfo.pktFrag
  val inputHasNak = io.rx.reqWithRxBufAndDmaInfo.hasNak
  val isRetryNak = io.rx.reqWithRxBufAndDmaInfo.nakAeth.isRetryNak()
  val isSendReq = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
  val isWriteReq = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)

  val (reqHasNakStream, allReqStream) = StreamConditionalFork2(
    io.rx.reqWithRxBufAndDmaInfo.throwWhen(io.rxQCtrl.stateErrFlush),
    forkCond = inputHasNak
  )

  val (sendWriteIdx, readAtomicIdx) = (0, 1)
  val twoStreams = StreamDeMuxByConditions(
    allReqStream,
    isSendReq || isWriteReq,
    isReadReq || isAtomicReq
  )
  io.txSendWrite.reqWithRxBufAndDmaInfo <-/< twoStreams(sendWriteIdx).throwWhen(
    isRetryNak
  )
  io.txReadAtomic.reqWithRxBufAndDmaInfo <-/< twoStreams(readAtomicIdx)
    .throwWhen(inputHasNak)

  // NAK generation
  val nakResp = Acknowledge().setAck(
    io.rx.reqWithRxBufAndDmaInfo.nakAeth,
    inputPktFrag.bth.psn,
    io.qpAttr.dqpn
  )
  io.txErrResp <-/< reqHasNakStream
    // TODO: better to response at the first fragment when error
    .takeWhen(reqHasNakStream.isLast)
    .translateWith(nakResp)

  io.nakNotifier.setFromAeth(
    aeth = io.rx.reqWithRxBufAndDmaInfo.nakAeth,
    pulse = reqHasNakStream.isLast,
    preOpCode = io.rx.reqWithRxBufAndDmaInfo.preOpCode,
    psn = inputPktFrag.bth.psn
  )
}

class RqSendWriteDmaReqInitiator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val txSendWrite = master(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val sendWriteDmaReq = master(DmaWriteReqBus(busWidth))
  }

  val inputHasNak = io.rx.reqWithRxBufAndDmaInfo.hasNak
  val inputValid = io.rx.reqWithRxBufAndDmaInfo.valid
  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfo.pktFrag
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfo.last
  val inputDmaHeader = io.rx.reqWithRxBufAndDmaInfo.dmaInfo
  val isEmptyReq = inputValid && inputDmaHeader.dlen === 0

//  when(inputValid) {
//    assert(
//      assertion = !inputHasNak,
//      message =
//        L"inputHasNak=${inputHasNak} should be false in RqSendWriteDmaReqInitiator",
//      severity = FAILURE
//    )
//  }

  val (forkSendWriteReqStream4DmaWrite, forkSendWriteReqStream4Output) =
    StreamFork2(
      io.rx.reqWithRxBufAndDmaInfo.throwWhen(io.rxQCtrl.stateErrFlush)
    )
  io.sendWriteDmaReq.req <-/< forkSendWriteReqStream4DmaWrite
    .throwWhen(io.rxQCtrl.stateErrFlush || inputHasNak || isEmptyReq)
    .translateWith {
      val result = cloneOf(io.sendWriteDmaReq.req.payloadType)
      result.last := isLastFrag
      result.set(
        initiator = DmaInitiator.RQ_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        pa = inputDmaHeader.pa,
        workReqId = io.rx.reqWithRxBufAndDmaInfo.rxBuf.id,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      result
    }
  io.txSendWrite.reqWithRxBufAndDmaInfo <-/< forkSendWriteReqStream4Output
}

class RqReadAtomicDmaReqBuilder(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val readAtomicRstCachePush = master(Stream(ReadAtomicRstCacheData()))
    val readDmaReqAndRstCacheData = master(
      Stream(RqDmaReadReqAndRstCacheData())
    )
    val atomicDmaReqAndRstCacheData = master(
      Stream(RqDmaReadReqAndRstCacheData())
    )
  }

  val inputHasNak = io.rx.reqWithRxBufAndDmaInfo.hasNak
  val inputValid = io.rx.reqWithRxBufAndDmaInfo.valid
  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfo.pktFrag
  val inputDmaHeader = io.rx.reqWithRxBufAndDmaInfo.dmaInfo
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfo.last

  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
  when(inputValid) {
    assert(
      assertion = isReadReq || isAtomicReq,
      message =
        L"${REPORT_TIME} time: ReadAtomicDmaReqBuilder can only handle read/atomic request, but opcode=${inputPktFrag.bth.opcode}",
      severity = FAILURE
    )
    assert(
      assertion = !inputHasNak,
      message =
        L"inputHasNak=${inputHasNak} should be false in RqReadAtomicDmaReqBuilder",
      severity = FAILURE
    )
  }

  val reth = inputPktFrag.extractReth()
  val atomicEth = inputPktFrag.extractAtomicEth()

  val readAtomicDmaReqAndRstCacheData = io.rx.reqWithRxBufAndDmaInfo
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
      rstCacheData.pktNum := computePktNum(reth.dlen, io.qpAttr.pmtu)
      rstCacheData.opcode := inputPktFrag.bth.opcode
      rstCacheData.pa := io.rx.reqWithRxBufAndDmaInfo.dmaInfo.pa
      when(isReadReq) {
        rstCacheData.va := reth.va
        rstCacheData.rkey := reth.rkey
        rstCacheData.dlen := reth.dlen
        rstCacheData.swap.assignDontCare()
        rstCacheData.comp.assignDontCare()
      } otherwise {
        rstCacheData.va := atomicEth.va
        rstCacheData.rkey := atomicEth.rkey
        rstCacheData.dlen := ATOMIC_DATA_LEN
        rstCacheData.swap := atomicEth.swap
        rstCacheData.comp := atomicEth.comp
      }
      rstCacheData.atomicRst.assignDontCare()
      rstCacheData.duplicate := False
      result
    }

  val (rstCacheDataPush, readAtomicReqOutput) =
    StreamFork2(readAtomicDmaReqAndRstCacheData)
  io.readAtomicRstCachePush <-/< rstCacheDataPush.translateWith(
    rstCacheDataPush.rstCacheData
  )

  val (readIdx, atomicIdx) = (0, 1)
  val twoStreams =
    StreamDeMuxByConditions(readAtomicReqOutput, isReadReq, isAtomicReq)

  io.readDmaReqAndRstCacheData <-/< twoStreams(readIdx)
  io.atomicDmaReqAndRstCacheData <-/< twoStreams(atomicIdx)
}

class ReadDmaReqInitiator() extends Component {
  val io = new Bundle {
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
      assertion = io.dupReadDmaReqAndRstCacheData.rstCacheData.duplicate,
      message =
        L"io.dupReadDmaReqAndRstCacheData.rstCacheData.duplicate=${io.dupReadDmaReqAndRstCacheData.rstCacheData.duplicate} should be true when io.dupReadDmaReqAndRstCacheData.valid=${io.dupReadDmaReqAndRstCacheData.valid}",
      severity = FAILURE
    )
  }

  val readDmaReqArbitration = StreamArbiterFactory.roundRobin.transactionLock
    .onArgs(
      io.readDmaReqAndRstCacheData.throwWhen(io.rxQCtrl.stateErrFlush),
      io.dupReadDmaReqAndRstCacheData.throwWhen(io.rxQCtrl.stateErrFlush)
    )

  val inputValid = readDmaReqArbitration.valid
  val inputDmaHeader = readDmaReqArbitration.dmaReadReq
  val isEmptyReq = inputValid && inputDmaHeader.lenBytes === 0

  val (readDmaReq, readRstCacheData) = StreamFork2(readDmaReqArbitration)
  io.readDmaReq.req <-/< readDmaReq
    .throwWhen(isEmptyReq)
    .translateWith(readDmaReq.dmaReadReq)
  io.readRstCacheData <-/< readRstCacheData.translateWith(
    readRstCacheData.rstCacheData
  )
}

// TODO: limit coalesce response to some max number of send/write requests
class SendWriteRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val rx = slave(RqReqWithRxBufAndDmaInfoBus(busWidth))
    val tx = master(Stream(Acknowledge()))
    val sendWriteWorkCompAndAck = master(Stream(WorkCompAndAck()))
  }

  val inputValid = io.rx.reqWithRxBufAndDmaInfo.valid
  val inputPktFrag = io.rx.reqWithRxBufAndDmaInfo.pktFrag
  val inputHasNak = io.rx.reqWithRxBufAndDmaInfo.hasNak
  val isRetryNak = io.rx.reqWithRxBufAndDmaInfo.nakAeth.isRetryNak()
  val isFirstFrag = io.rx.reqWithRxBufAndDmaInfo.isFirst
  val isLastFrag = io.rx.reqWithRxBufAndDmaInfo.isLast
  val inputDmaHeader = io.rx.reqWithRxBufAndDmaInfo.dmaInfo
  val isLastPkt = OpCode.isSendWriteLastOrOnlyReqPkt(inputPktFrag.bth.opcode)
  val isLastFragOfLastPkt = isLastPkt && isLastFrag

  val isSendReqPkt = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
  val isWriteReqPkt = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
  val isWriteWithImmReqPkt =
    OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)
  val isSendOrWriteReq = isSendReqPkt || isWriteReqPkt
  val isSendOrWriteImmReq = isSendReqPkt || isWriteWithImmReqPkt
  when(inputValid) {
    assert(
      assertion = isSendOrWriteReq,
      message =
        L"${REPORT_TIME} time: invalid opcode=${inputPktFrag.bth.opcode}, should be send/write requests",
      severity = FAILURE
    )
    when(inputHasNak) {
      assert(
        assertion = !isRetryNak,
        message =
          L"illegal NAK for WC, retry NAK cannot generate WC, but NAK code=${io.rx.reqWithRxBufAndDmaInfo.nakAeth.code} and value=${io.rx.reqWithRxBufAndDmaInfo.nakAeth.value}",
        severity = FAILURE
      )
    }
  }

  when(inputValid && isSendOrWriteImmReq) {
    assert(
      assertion = io.rx.reqWithRxBufAndDmaInfo.rxBufValid,
      message =
        L"${REPORT_TIME} time: io.rx.reqWithRxBufAndDmaInfo.rxBufValid=${io.rx.reqWithRxBufAndDmaInfo.rxBufValid} should be true, when isSendOrWriteImmReq=${isSendOrWriteImmReq}",
      severity = FAILURE
    )
  }

  val sendWriteAck = Acknowledge().setAck(
    io.rx.reqWithRxBufAndDmaInfo.nakAeth,
    inputPktFrag.bth.psn,
    io.qpAttr.dqpn
  )

  val needAckCond = inputPktFrag.bth.ackreq && isLastFrag
  val needWorkCompCond = isSendOrWriteImmReq && isLastFragOfLastPkt
  val (req4Ack, req4WorkComp) = StreamFork2(
    io.rx.reqWithRxBufAndDmaInfo
      .throwWhen(io.rxQCtrl.stateErrFlush)
      .takeWhen(needAckCond || needWorkCompCond)
  )

  // Responses to send/write do not wait for DMA write responses
  io.tx <-/< req4Ack
    .takeWhen(needAckCond)
    .translateWith(sendWriteAck)

  when(isLastFragOfLastPkt) {
    assert(
      assertion = req4WorkComp.reqTotalLenValid,
      message =
        L"invalid send/write request, reqTotalLenValid=${req4WorkComp.reqTotalLenValid} should be true",
      severity = FAILURE
    )
  }

  io.sendWriteWorkCompAndAck <-/< req4WorkComp
    // TODO: better to response at the first fragment when error
    .takeWhen(needWorkCompCond)
    .translateWith {
      val result = cloneOf(io.sendWriteWorkCompAndAck.payloadType)
      result.workComp.setFromRxWorkReq(
        req4WorkComp.rxBuf,
        req4WorkComp.pktFrag.bth.opcode,
        io.qpAttr.dqpn,
        sendWriteAck.aeth.toWorkCompStatus(),
        req4WorkComp.reqTotalLenBytes, // for error WC, reqTotalLenBytes does not matter here
        req4WorkComp.pktFrag.data
      )
      result.ackValid := req4WorkComp.valid
      result.ack := sendWriteAck
      result
    }
}

class RqSendWriteWorkCompGenerator extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val dmaWriteResp = slave(DmaWriteRespBus())
    val sendWriteWorkCompAndAck = slave(Stream(WorkCompAndAck()))
    val sendWriteWorkCompOut = master(Stream(WorkComp()))
  }

  val workCompAndAckQueuePop =
    io.sendWriteWorkCompAndAck.queueLowLatency(DMA_WRITE_DELAY_CYCLE)
  val isReqZeroDmaLen = workCompAndAckQueuePop.workComp.lenBytes === 0
  val inputHasNak = !io.sendWriteWorkCompAndAck.ack.aeth.isNormalAck()

  val workCompQueueValid = workCompAndAckQueuePop.valid
  val dmaWriteRespValid = io.dmaWriteResp.resp.valid
  when(workCompQueueValid) {
    assert(
      assertion = workCompAndAckQueuePop.ackValid,
      message =
        L"invalid WorkCompAndAck in RQ, ackValid=${workCompAndAckQueuePop.ackValid} should be true when workCompQueueValid=${workCompQueueValid}",
      severity = FAILURE
    )
  }
  when(workCompQueueValid && dmaWriteRespValid) {
    assert(
      assertion = PsnUtil.lte(
        io.dmaWriteResp.resp.psn,
        workCompAndAckQueuePop.ack.bth.psn,
        io.qpAttr.epsn
      ),
      message =
        L"invalid DMA write response in RQ, io.dmaWriteResp.resp.psn=${io.dmaWriteResp.resp.psn} should <= workCompAndAckQueuePop.ack.bth.psn=${workCompAndAckQueuePop.ack.bth.psn} in PSN order, curPsn=${io.qpAttr.epsn}",
      severity = FAILURE
    )
  }
  when(dmaWriteRespValid) {
    assert(
      assertion = workCompQueueValid,
      message =
        L"workCompQueueValid=${workCompQueueValid} must be true when dmaWriteRespValid=${dmaWriteRespValid}, since it must have some WC waiting for DMA response",
      severity = FAILURE
    )
  }

  val dmaWriteRespPnsLessThanWorkCompPsn = PsnUtil.lt(
    io.dmaWriteResp.resp.psn,
    workCompAndAckQueuePop.ack.bth.psn,
    io.qpAttr.epsn
  )
  val dmaWriteRespPnsEqualWorkCompPsn =
    io.dmaWriteResp.resp.psn === workCompAndAckQueuePop.ack.bth.psn

  val shouldFireWorkCompQueueOnlyCond =
    isReqZeroDmaLen || inputHasNak || io.rxQCtrl.stateErrFlush
  // For the following fire condition, it must make sure inputAckValid or inputCachedWorkReqValid
  val fireBothWorkCompQueueAndDmaWriteResp =
    workCompQueueValid && dmaWriteRespValid && dmaWriteRespPnsEqualWorkCompPsn && !shouldFireWorkCompQueueOnlyCond
  val fireWorkCompQueueOnly =
    workCompQueueValid && shouldFireWorkCompQueueOnlyCond
  val fireDmaWriteRespOnly =
    dmaWriteRespValid && workCompQueueValid && dmaWriteRespPnsLessThanWorkCompPsn && !shouldFireWorkCompQueueOnlyCond
  val zipWorkCompAndAckAndDmaWriteResp = StreamZipByCondition(
    leftInputStream = workCompAndAckQueuePop,
    // Flush io.dmaWriteResp.resp when error
    rightInputStream = io.dmaWriteResp.resp.throwWhen(io.rxQCtrl.stateErrFlush),
    // Coalesce ACK pending WR, or errorFlush
    leftFireCond = fireWorkCompQueueOnly,
    rightFireCond = fireDmaWriteRespOnly,
    bothFireCond = fireBothWorkCompQueueAndDmaWriteResp
  )
  when(workCompQueueValid || dmaWriteRespValid) {
    assert(
      assertion = CountOne(
        fireBothWorkCompQueueAndDmaWriteResp ## fireWorkCompQueueOnly ## fireDmaWriteRespOnly
      ) <= 1,
      message =
        L"${REPORT_TIME} time: fire WorkCompQueue only, fire DmaWriteResp only, and fire both should be mutually exclusive, but fireBothWorkCompQueueAndDmaWriteResp=${fireBothWorkCompQueueAndDmaWriteResp}, fireWorkCompQueueOnly=${fireWorkCompQueueOnly}, fireDmaWriteRespOnly=${fireDmaWriteRespOnly}",
      severity = FAILURE
    )
  }

  io.sendWriteWorkCompOut <-/< zipWorkCompAndAckAndDmaWriteResp.translateWith(
    zipWorkCompAndAckAndDmaWriteResp._2.workComp
  )
}

/** When received a new DMA response, combine the DMA response and
  * ReadAtomicRstCacheData to downstream, also handle
  * zero DMA length read request.
  */
class RqReadDmaRespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
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

class ReadRespSegment(busWidth: BusWidth) extends Component {
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

class ReadRespGenerator(busWidth: BusWidth) extends Component {
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
      assertion = input.rstCacheData.dlen === input.dmaReadResp.lenBytes,
      message =
        L"${REPORT_TIME} time: input.rstCacheData.dlen=${input.rstCacheData.dlen} should equal input.dmaReadResp.lenBytes=${input.dmaReadResp.lenBytes}",
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
    select = inputReqAndDmaReadRespSegment.req.duplicate.asUInt,
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
class AtomicRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rxQCtrl = in(RxQCtrl())
    val atomicDmaReqAndRstCacheData = slave(
      Stream(RqDmaReadReqAndRstCacheData())
    )
    val tx = master(Stream(AtomicResp()))
    val dma = master(DmaBus(busWidth))
  }

//  val (atomicDmaReq, atomicRstCacheData) = StreamFork2(io.atomicDmaReqAndRstCacheData.throwWhen(io.rxQCtrl.stateErrFlush))
//  io.atomicDmaReq.req <-/< atomicDmaReq.translateWith(atomicDmaReq.dmaReadReq)
//  io.atomicRstCacheData <-/< atomicRstCacheData.translateWith(atomicRstCacheData.rstCacheData)

  // TODO: support atomic request
  StreamSink(NoData) << io.atomicDmaReqAndRstCacheData.translateWith(NoData)
  val atomicResp = AtomicResp().setDefaultVal()

  io.dma.rd.req << io.dma.rd.resp.translateWith(
    DmaReadReq().set(
      initiator = DmaInitiator.RQ_ATOMIC_RD,
      psnStart = atomicResp.bth.psn,
      sqpn = io.qpAttr.sqpn,
      pa = 0,
      lenBytes = ATOMIC_DATA_LEN
    )
  )
  io.dma.wr.req << io.dma.wr.resp.translateWith {
    val result = Fragment(DmaWriteReq(busWidth))
    result.set(
      initiator = DmaInitiator.RQ_ATOMIC_WR,
      sqpn = io.qpAttr.sqpn,
      psn = atomicResp.bth.psn,
      // TODO: set the following fields with actual value
      pa = 0,
      workReqId = 0, // TODO: RQ has no WR ID, need refactor
      data = 0,
      mty = 0
    )
    result.last := True
    result
  }
  io.tx << StreamSource().translateWith(atomicResp)
}

// For duplicated requests, also return ACK in PSN order
// TODO: after RNR and NAK SEQ returned, no other nested NAK send
class RqOut(busWidth: BusWidth) extends Component {
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

  val rxSendWriteResp = io.rxSendWriteResp
    .throwWhen(io.rxQCtrl.stateErrFlush)
    .translateWith(
      io.rxSendWriteResp.asRdmaDataPktFrag(busWidth)
    )
  val rxAtomicResp =
    io.rxAtomicResp
      .throwWhen(io.rxQCtrl.stateErrFlush)
      .translateWith(io.rxAtomicResp.asRdmaDataPktFrag(busWidth))
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
  val (normalRespOutSel, psnOutRangeFifo) = SeqOut(
    curPsn = io.qpAttr.epsn,
    flush = io.rxQCtrl.stateErrFlush,
    outPsnRangeFifoPush = io.outPsnRangeFifoPush,
    outDataStreamVec = normalRespVec,
    outputValidateFunc =
      (psnOutRangeFifoPop: RespPsnRange, resp: RdmaDataPkt) => {
        assert(
          assertion = checkRespOpCodeMatch(
            reqOpCode = psnOutRangeFifoPop.opcode,
            respOpCode = resp.bth.opcode
          ),
          message =
            L"${REPORT_TIME} time: request opcode=${psnOutRangeFifoPop.opcode} and response opcode=${resp.bth.opcode} not match, resp.bth.psn=${resp.bth.psn}, psnOutRangeFifo.io.pop.start=${psnOutRangeFifoPop.start}, psnOutRangeFifo.io.pop.end=${psnOutRangeFifoPop.end}",
          severity = FAILURE
        )
      },
    hasPktToOutput = hasPktToOutput,
    opsnInc = io.opsnInc
  )
  val (normalRespOut, normalRespOut4ReadAtomicRstCache) =
    StreamFork2(normalRespOutSel)

  val dupRespVec =
    Vec(rxDupSendWriteResp, io.rxDupReadResp.pktFrag, rxDupAtomicResp)
  // TODO: duplicate output also needs to keep PSN order
  val dupRespOut =
    StreamArbiterFactory.roundRobin.fragmentLock.on(dupRespVec)

  val finalOutStream = StreamArbiterFactory.roundRobin.fragmentLock
    .onArgs(
      dupRespOut,
      normalRespOut
    )
  io.tx.pktFrag <-/< finalOutStream.throwWhen(io.rxQCtrl.stateErrFlush)

  val isReadReq = OpCode.isReadReqPkt(psnOutRangeFifo.io.pop.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(psnOutRangeFifo.io.pop.opcode)
  when(hasPktToOutput && (isReadReq || isAtomicReq)) {
    assert(
      assertion = io.readAtomicRstCachePop.valid,
      message =
        L"${REPORT_TIME} time: io.readAtomicRstCachePop.valid=${io.readAtomicRstCachePop.valid} should be true when has read/atomic response, hasPktToOutput=${hasPktToOutput}, isReadReq=${isReadReq}, isAtomicReq=${isAtomicReq}",
      severity = FAILURE
    )
  }

  val normalRespOutJoinReadAtomicRstCacheCond = (isReadReq || isAtomicReq) &&
    (normalRespOut4ReadAtomicRstCache.bth.psn === (io.readAtomicRstCachePop.psnStart + (io.readAtomicRstCachePop.pktNum - 1)))
  val normalRespOutJoinReadAtomicRstCache = FragmentStreamConditionalJoinStream(
    inputFragmentStream = normalRespOut4ReadAtomicRstCache,
    inputStream = io.readAtomicRstCachePop,
    joinCond = normalRespOutJoinReadAtomicRstCacheCond
  )
  StreamSink(NoData) << normalRespOutJoinReadAtomicRstCache.translateWith(
    NoData
  )
//  io.readAtomicRstCachePop.ready := txOutputSel.lastFire && txOutputSel.bth.psn === (io.readAtomicRstCachePop.psnStart + (io.readAtomicRstCachePop.pktNum - 1))
  when(io.readAtomicRstCachePop.fire) {
//    assert(
//      assertion = isReadReq || isAtomicReq,
//      message =
//        L"${REPORT_TIME} time: the output should correspond to read/atomic resp, io.readAtomicRstCachePop.fire=${io.readAtomicRstCachePop.fire}, but psnOutRangeFifo.io.pop.opcode=${psnOutRangeFifo.io.pop.opcode}",
//      severity = FAILURE
//    )
//    assert(
//      assertion = psnOutRangeFifo.io.pop.fire,
//      message =
//        L"${REPORT_TIME} time: io.readAtomicRstCachePop.fire=${io.readAtomicRstCachePop.fire} and psnOutRangeFifo.io.pop.fire=${psnOutRangeFifo.io.pop.fire} should fire at the same time",
//      severity = FAILURE
//    )
    assert(
      assertion = checkRespOpCodeMatch(
        reqOpCode = io.readAtomicRstCachePop.opcode,
        respOpCode = normalRespOut4ReadAtomicRstCache.bth.opcode
      ),
      message =
        L"${REPORT_TIME} time: io.readAtomicRstCachePop.opcode=${io.readAtomicRstCachePop.opcode} should match normalRespOut4ReadAtomicRstCache.bth.opcode=${normalRespOut4ReadAtomicRstCache.bth.opcode}, normalRespOut4ReadAtomicRstCache.bth.psn=${normalRespOut4ReadAtomicRstCache.bth.psn}, io.readAtomicRstCachePop.psnStart=${io.readAtomicRstCachePop.psnStart}, io.readAtomicRstCachePop.pktNum=${io.readAtomicRstCachePop.pktNum}",
      severity = FAILURE
    )
  }
}
