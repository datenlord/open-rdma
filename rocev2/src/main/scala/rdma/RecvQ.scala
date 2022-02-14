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
    val recvQCtrl = in(RecvQCtrl())
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val dma = master(RqDmaBus(busWidth))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val sendWriteWorkComp = master(Stream(WorkComp()))
  }

  val readAtomicResultCache =
    new ReadAtomicResultCache(MAX_PENDING_READ_ATOMIC_REQ_NUM)
  readAtomicResultCache.io.flush := io.recvQCtrl.stateErrFlush

  val reqCommCheck = new ReqCommCheck(busWidth)
  reqCommCheck.io.qpAttr := io.qpAttr
  reqCommCheck.io.recvQCtrl := io.recvQCtrl
  reqCommCheck.io.readAtomicRsltCacheOccupancy := readAtomicResultCache.io.occupancy
  reqCommCheck.io.rx << io.rx
  io.psnInc.epsn := reqCommCheck.io.epsnInc
  io.notifier.clearRnrOrNakSeq := reqCommCheck.io.clearRnrOrNakSeq

  val dupReqLogic = new Area {
    val dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder =
      new DupSendWriteReqHandlerAndDupReadAtomicResultCacheQueryBuilder(
        busWidth
      )
    dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.qpAttr := io.qpAttr
    dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.recvQCtrl := io.recvQCtrl
    dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.rx << reqCommCheck.io.txDupReq
    readAtomicResultCache.io.queryPort4DupReq.req << dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.readAtomicResultCacheReq.req

    val dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator =
      new DupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator
    dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.qpAttr := io.qpAttr
    dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.recvQCtrl := io.recvQCtrl
    dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.readAtomicResultCacheResp.resp << readAtomicResultCache.io.queryPort4DupReq.resp
    io.dma.dupRead.req << dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.dmaReadReq.req

    val dupRqReadDmaRespHandler = new RqReadDmaRespHandler(busWidth)
//    dupRqReadDmaRespHandler.io.qpAttr := io.qpAttr
    dupRqReadDmaRespHandler.io.recvQCtrl := io.recvQCtrl
    dupRqReadDmaRespHandler.io.dmaReadResp.resp << io.dma.dupRead.resp
    dupRqReadDmaRespHandler.io.readResultCacheData << dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.readResultCacheData
//    readAtomicResultCache.io.queryPort4DupReqDmaRead << dupRqReadDmaRespHandler.io.readAtomicResultCacheQuery

    val dupReadRespSegment = new ReadRespSegment(busWidth)
    dupReadRespSegment.io.qpAttr := io.qpAttr
    dupReadRespSegment.io.recvQCtrl := io.recvQCtrl
    dupReadRespSegment.io.readResultCacheDataAndDmaReadResp << dupRqReadDmaRespHandler.io.readResultCacheDataAndDmaReadResp

    val dupReadRespGenerator = new ReadRespGenerator(busWidth)
    dupReadRespGenerator.io.qpAttr := io.qpAttr
    dupReadRespGenerator.io.recvQCtrl := io.recvQCtrl
    dupReadRespGenerator.io.readResultCacheDataAndDmaReadRespSegment << dupReadRespSegment.io.readResultCacheDataAndDmaReadRespSegment
  }

  // TODO: make sure that when retry occurred, it only needs to flush reqValidateLogic
  // TODO: make sure that when error occurred, it needs to flush both reqCommCheck and reqValidateLogic
  val reqValidateLogic = new Area {
    val reqRnrCheck = new ReqRnrCheck(busWidth)
    reqRnrCheck.io.qpAttr := io.qpAttr
    reqRnrCheck.io.recvQCtrl := io.recvQCtrl
    reqRnrCheck.io.recvWorkReq << io.recvWorkReq
    reqRnrCheck.io.rx << reqCommCheck.io.tx

    val reqDmaCommHeaderExtractor = new ReqDmaCommHeaderExtractor(busWidth)
    reqDmaCommHeaderExtractor.io.qpAttr := io.qpAttr
    reqDmaCommHeaderExtractor.io.recvQCtrl := io.recvQCtrl
    reqDmaCommHeaderExtractor.io.rx << reqRnrCheck.io.tx

    val reqAddrValidator = new ReqAddrValidator(busWidth)
    reqAddrValidator.io.qpAttr := io.qpAttr
    reqAddrValidator.io.recvQCtrl := io.recvQCtrl
    reqAddrValidator.io.rx << reqDmaCommHeaderExtractor.io.tx
    io.addrCacheRead << reqAddrValidator.io.addrCacheRead

    val pktLenCheck = new PktLenCheck(busWidth)
    pktLenCheck.io.qpAttr := io.qpAttr
    pktLenCheck.io.recvQCtrl := io.recvQCtrl
    pktLenCheck.io.rx << reqAddrValidator.io.tx
  }

  val dmaReqLogic = new Area {
    val rqDmaReqInitiatorAndNakGen = new RqDmaReqInitiatorAndNakGen(busWidth)
    rqDmaReqInitiatorAndNakGen.io.qpAttr := io.qpAttr
    rqDmaReqInitiatorAndNakGen.io.recvQCtrl := io.recvQCtrl
    rqDmaReqInitiatorAndNakGen.io.rx << reqValidateLogic.pktLenCheck.io.tx
    io.dma.read.req << rqDmaReqInitiatorAndNakGen.io.readDmaReq.req
    io.dma.sendWrite.req << rqDmaReqInitiatorAndNakGen.io.sendWriteDmaReq.req
    io.notifier.nak := rqDmaReqInitiatorAndNakGen.io.nakNotify

    val readAtomicReqExtractor = new ReadAtomicReqExtractor(busWidth)
    readAtomicReqExtractor.io.qpAttr := io.qpAttr
    readAtomicReqExtractor.io.recvQCtrl := io.recvQCtrl
    readAtomicReqExtractor.io.rx << rqDmaReqInitiatorAndNakGen.io.txReadAtomic
    readAtomicResultCache.io.push << readAtomicReqExtractor.io.readAtomicResultCachePush
  }

  val respGenLogic = new Area {
    val sendWriteRespGenerator = new SendWriteRespGenerator(busWidth)
    sendWriteRespGenerator.io.qpAttr := io.qpAttr
    sendWriteRespGenerator.io.recvQCtrl := io.recvQCtrl
    sendWriteRespGenerator.io.rx << dmaReqLogic.rqDmaReqInitiatorAndNakGen.io.txSendWrite

    val rqReadDmaRespHandler = new RqReadDmaRespHandler(busWidth)
//    rqReadDmaRespHandler.io.qpAttr := io.qpAttr
    rqReadDmaRespHandler.io.recvQCtrl := io.recvQCtrl
//    rqReadDmaRespHandler.io.rx << dmaReqLogic.readAtomicReqExtractor.io.tx
    rqReadDmaRespHandler.io.dmaReadResp.resp << io.dma.read.resp
    rqReadDmaRespHandler.io.readResultCacheData << dmaReqLogic.readAtomicReqExtractor.io.readResultCacheData
//    readAtomicResultCache.io.queryPort4DmaReadResp << rqReadDmaRespHandler.io.readAtomicResultCacheQuery

    val readRespSegment = new ReadRespSegment(busWidth)
    readRespSegment.io.qpAttr := io.qpAttr
    readRespSegment.io.recvQCtrl := io.recvQCtrl
    readRespSegment.io.readResultCacheDataAndDmaReadResp << rqReadDmaRespHandler.io.readResultCacheDataAndDmaReadResp

    val readRespGenerator = new ReadRespGenerator(busWidth)
    readRespGenerator.io.qpAttr := io.qpAttr
    readRespGenerator.io.recvQCtrl := io.recvQCtrl
    readRespGenerator.io.readResultCacheDataAndDmaReadRespSegment << readRespSegment.io.readResultCacheDataAndDmaReadRespSegment

    val atomicRespGenerator = new AtomicRespGenerator(busWidth)
    atomicRespGenerator.io.qpAttr := io.qpAttr
    atomicRespGenerator.io.recvQCtrl := io.recvQCtrl
    atomicRespGenerator.io.atomicResultCacheData << dmaReqLogic.readAtomicReqExtractor.io.atomicResultCacheData
    io.dma.atomic << atomicRespGenerator.io.dma
  }

  val rqSendWriteWorkCompGenerator = new RqSendWriteWorkCompGenerator
  rqSendWriteWorkCompGenerator.io.qpAttr := io.qpAttr
  rqSendWriteWorkCompGenerator.io.recvQCtrl := io.recvQCtrl
  rqSendWriteWorkCompGenerator.io.dmaWriteResp.resp << io.dma.sendWrite.resp
  rqSendWriteWorkCompGenerator.io.sendWriteWorkCompNormal << respGenLogic.sendWriteRespGenerator.io.sendWriteWorkCompAndAck
  rqSendWriteWorkCompGenerator.io.sendWriteWorkCompErr << dmaReqLogic.rqDmaReqInitiatorAndNakGen.io.sendWriteWorkCompAndNak
  io.sendWriteWorkComp << rqSendWriteWorkCompGenerator.io.sendWriteWorkCompOut

  val rqOut = new RqOut(busWidth)
  rqOut.io.qpAttr := io.qpAttr
  rqOut.io.outPsnRangeFifoPush << reqValidateLogic.reqDmaCommHeaderExtractor.io.rqOutPsnRangeFifoPush
  rqOut.io.rxSendWriteResp << respGenLogic.sendWriteRespGenerator.io.tx
  rqOut.io.rxReadResp << respGenLogic.readRespGenerator.io.txReadResp
  rqOut.io.rxAtomicResp << respGenLogic.atomicRespGenerator.io.tx
  rqOut.io.rxDupSendWriteResp << dupReqLogic.dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.txDupSendWriteResp
  rqOut.io.rxDupReadResp << dupReqLogic.dupReadRespGenerator.io.txReadResp
  rqOut.io.rxDupAtomicResp << dupReqLogic.dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.txDupAtomicResp
  rqOut.io.rxErrResp << dmaReqLogic.rqDmaReqInitiatorAndNakGen.io.txErrResp
  rqOut.io.readAtomicResultCachePop << readAtomicResultCache.io.pop
  io.psnInc.opsn := rqOut.io.opsnInc
  io.tx << rqOut.io.tx
}

// PSN == ePSN, otherwise NAK-Seq;
// OpCode sequence, otherwise NAK-Inv Req;
// OpCode functionality is supported, otherwise NAK-Inv Req;
// First/Middle packets have padcount == 0, otherwise NAK-Inv Req;
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
//    val nakNotify = out(NakNotifier())
    val clearRnrOrNakSeq = out(RnrNakSeqClear())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RqReqCommCheckStageOutputBus(busWidth))
    val txDupReq = master(RdmaDataBus(busWidth))
//    val txErrResp = master(Stream(Acknowledge()))
    val readAtomicRsltCacheOccupancy = in(
      UInt(log2Up(MAX_PENDING_READ_ATOMIC_REQ_NUM + 1) bits)
    )
  }

  val checkStage = new Area {
    val inputValid = io.rx.pktFrag.valid
    val inputPktFrag = io.rx.pktFrag.fragment
    val isLastFrag = io.rx.pktFrag.last

    // PSN sequence check
    val isPsnCheckPass = Bool()
    val isDupReq = Bool()
    val epsnCmpRslt = PsnUtil.cmp(
      psnA = inputPktFrag.bth.psn,
      psnB = io.qpAttr.epsn,
      curPsn = io.qpAttr.epsn
    )
    // The pending request is opsn < PSN < ePsn
    val isDupPendingReq = False
    val opsnCmpRslt = PsnUtil.cmp(
      psnA = inputPktFrag.bth.psn,
      psnB = io.qpAttr.rqOutPsn,
      curPsn = io.qpAttr.epsn
    )
    switch(epsnCmpRslt) {
      is(PsnCompResult.GREATER) {
        isPsnCheckPass := False
        isDupReq := False
      }
      is(PsnCompResult.LESSER) {
        isPsnCheckPass := inputValid
        isDupPendingReq := inputValid && opsnCmpRslt === PsnCompResult.GREATER
        isDupReq := inputValid && (opsnCmpRslt === PsnCompResult.LESSER || opsnCmpRslt === PsnCompResult.EQUAL)
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
      inputPktFrag.bth.padcount,
      inputPktFrag.mty,
      isLastFrag,
      busWidth
    )

    // Check for # of pending read/atomic
    val isReadOrAtomicReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
    val isReadAtomicResultCacheFull = inputValid && isReadOrAtomicReq &&
      io.readAtomicRsltCacheOccupancy >= io.qpAttr.maxPendingReadAtomicReqNum

    // TODO: should discard duplicate pending requests?
    val throwCond = isDupPendingReq
    when(isDupPendingReq) {
      report(
        message =
          L"duplicated pending request received with PSN=${inputPktFrag.bth.psn} and opcode=${inputPktFrag.bth.opcode}, RQ opsn=${io.qpAttr.rqOutPsn}, epsn=${io.qpAttr.epsn}, maybe it should increase SQ timeout threshold",
        severity = FAILURE
      )
    }
    val output =
      io.rx.pktFrag.throwWhen(throwCond).translateWith { // No flush this stage
        val rslt = Fragment(RqReqCheckInternalOutput(busWidth))
        rslt.pktFrag := inputPktFrag
        rslt.checkRslt.isPsnCheckPass := isPsnCheckPass
        rslt.checkRslt.isDupReq := isDupReq
        rslt.checkRslt.isOpSeqCheckPass := isOpSeqCheckPass
        rslt.checkRslt.isSupportedOpCode := isSupportedOpCode
        rslt.checkRslt.isPadCntCheckPass := isPadCntCheckPass
        rslt.checkRslt.isReadAtomicResultCacheFull := isReadAtomicResultCacheFull
        rslt.checkRslt.epsn := io.qpAttr.epsn
        rslt.last := io.rx.pktFrag.last
        rslt
      }

    // Increase ePSN
    val isExpectedPkt = inputValid && isPsnCheckPass && !isDupReq
    io.epsnInc.inc := isExpectedPkt && isLastFrag && output.fire // Only update ePSN when each request packet ended
    io.epsnInc.incVal := ePsnIncrease(inputPktFrag, io.qpAttr.pmtu, busWidth)
    io.epsnInc.preReqOpCode := inputPktFrag.bth.opcode

    // Clear RNR or NAK SEQ if any
    io.clearRnrOrNakSeq.pulse := (io.recvQCtrl.rnrFlush || io.recvQCtrl.nakSeqTrigger) && isExpectedPkt
//    // NAK notification
//    io.nakNotify.setNoErr()
//    when(output.fire) {
//      when(!isPsnCheckPass) {
//        io.nakNotify.setSeqErr()
//      } elsewhen isInvReq {
//        io.nakNotify.setInvReq()
//      }
//    }
  }

  val outputStage = new Area {
    val input = cloneOf(checkStage.output)
    input <-/< checkStage.output

    val inputValid = input.valid
    val inputPktFrag = input.pktFrag
    val isLastFrag = input.last

    val isPsnCheckPass = input.checkRslt.isPsnCheckPass
    val isDupReq = input.checkRslt.isDupReq
    val isInvReq =
      input.checkRslt.isOpSeqCheckPass || input.checkRslt.isSupportedOpCode ||
        input.checkRslt.isPadCntCheckPass || input.checkRslt.isReadAtomicResultCacheFull
//    val epsn = input.checkRslt.epsn

    val hasNak = False
    val nakAeth = AETH().set(AckType.NORMAL)
    val txSel = UInt(1 bits)
    val (dupIdx, otherIdx) = (0, 1)
    when(isPsnCheckPass) {
      txSel := otherIdx
      hasNak := False
      when(isDupReq) {
        txSel := dupIdx
      }
    } elsewhen (!isInvReq) {
      hasNak := True
      nakAeth.set(AckType.NAK_SEQ)
      txSel := otherIdx
    } otherwise { // NAK_INV
      txSel := otherIdx
      hasNak := True
      nakAeth.set(AckType.NAK_INV)
    }

    val twoStreams = StreamDemux(
      input.throwWhen(io.recvQCtrl.flush),
      select = txSel,
      portCount = 2
    )

    io.txDupReq.pktFrag <-/< twoStreams(dupIdx).translateWith {
      val rslt = cloneOf(io.txDupReq.pktFrag.payloadType)
      rslt.fragment := input.pktFrag
      rslt.last := input.last
      rslt
    }
    io.tx.checkOutput <-/< twoStreams(otherIdx).translateWith {
      val rslt = cloneOf(io.tx.checkOutput.payloadType)
      rslt.pktFrag := input.pktFrag
      rslt.preOpCode := io.qpAttr.rqPreReqOpCode
      rslt.hasNak := hasNak
      rslt.nakAeth := nakAeth
      rslt.last := input.last
      rslt
    }
  }
}

class ReqRnrCheck(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    //val epsnInc = out(EPsnInc())
    //val nakNotify = out(NakNotifier())
//    val rnrNotify = out(RetryNak())
    val recvQCtrl = in(RecvQCtrl())
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val rx = slave(RqReqCommCheckStageOutputBus(busWidth))
    val tx = master(RqReqWithRecvBufBus(busWidth))
//    val txRnrResp = master(Stream(Acknowledge()))
  }

  val inputValid = io.rx.checkOutput.valid
  val inputPktFrag = io.rx.checkOutput.pktFrag
  val isFirstFrag = io.rx.checkOutput.isFirst
  val isLastFrag = io.rx.checkOutput.isLast

  val isSendOrWriteImmReq =
    OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)
  val isLastOrOnlySendOrWriteImmReqEnd =
    OpCode.isSendLastOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)

  // RNR check for send/write
  val needRecvBuffer = inputValid && isFirstFrag && isSendOrWriteImmReq
  io.recvWorkReq.ready := needRecvBuffer && io.rx.checkOutput.fire
  val recvBuffer = io.recvWorkReq.payload
  val recvBufValid = needRecvBuffer && io.recvWorkReq.fire
  val hasRnrErr = needRecvBuffer && !io.recvWorkReq.valid
  when(recvBufValid) {
    assert(
      assertion = io.rx.checkOutput.fire,
      message =
        L"io.rx.checkOutput.fire=${io.rx.checkOutput.fire} should fire at the same cycle as io.recvWorkReq.fire=${io.recvWorkReq.fire} when needRecvBuffer=${needRecvBuffer}",
      severity = FAILURE
    )
  }

  val recvBufValidReg = Reg(Bool())
    .setWhen(needRecvBuffer)
    .clearWhen(inputValid && isLastFrag && isLastOrOnlySendOrWriteImmReqEnd)
  val recvBufferReg =
    RegNextWhen(io.recvWorkReq.payload, cond = io.recvWorkReq.fire)

  val rnrAeth = AETH().set(AckType.NAK_RNR, io.qpAttr.rnrTimeOut)
  val nakAeth = cloneOf(io.rx.checkOutput.nakAeth)
  nakAeth := io.rx.checkOutput.nakAeth
  when(!io.rx.checkOutput.hasNak && hasRnrErr) {
    nakAeth := rnrAeth
  }

  io.tx.reqWithRecvBuf <-/< io.rx.checkOutput
    .throwWhen(io.recvQCtrl.flush)
    .translateWith {
      val rslt = cloneOf(io.tx.reqWithRecvBuf.payloadType)
      rslt.pktFrag := inputPktFrag
      rslt.preOpCode := io.rx.checkOutput.preOpCode
      rslt.hasNak := hasRnrErr
      rslt.nakAeth := nakAeth
      rslt.recvBufValid := needRecvBuffer ? recvBufValid | recvBufValidReg
      rslt.recvBuffer := needRecvBuffer ? recvBuffer | recvBufferReg
      rslt.last := isLastFrag
      rslt
    }
}

// If multiple duplicate requests received, also ACK in PSN order;
// RQ will return ACK with the latest PSN for duplicate Send/Write, but this will NAK the following duplicate Read/Atomic???
// No NAK for duplicate requests if error detected;
// Duplicate Read is not valid if not with its original PSN and DMA range;
// Duplicate request with earlier PSN might interrupt processing of new request or duplicate request with later PSN;
// RQ does not re-execute the interrupted request, SQ will retry it;
// Discard duplicate Atomic if not match original PSN (should not happen);
class DupSendWriteReqHandlerAndDupReadAtomicResultCacheQueryBuilder(
    busWidth: BusWidth
) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val readAtomicResultCacheReq = master(ReadAtomicResultCacheReqBus())
    val rx = slave(RdmaDataBus(busWidth))
    val txDupSendWriteResp = master(Stream(Acknowledge()))
    val txDupAtomicResp = master(Stream(AtomicResp()))
    val dmaReadReq = master(DmaReadReqBus())
//    val txDupAtomicResp = master(Stream(AtomicResp()))
//    val txDmaReadReq = master(DmaReadReqBus())
  }

  val dupSendWriteRespHandlerAndReadAtomicResultCacheQueryBuilder = new Area {
    val inputPktFrag = io.rx.pktFrag.fragment

    val rdmaAck = Acknowledge()
      .setAck(
        AckType.NORMAL,
        io.qpAttr.epsn, // TODO: verify the ePSN is confirmed, will not retry later
        io.qpAttr.dqpn
      )

    val (sendWriteReqIdx, readAtomicReqIdx, otherReqIdx) = (0, 1, 2)
    val txSel = UInt(2 bits)
    when(
      OpCode.isSendReqPkt(inputPktFrag.bth.opcode) ||
        OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
    ) {
      txSel := sendWriteReqIdx
    } elsewhen (OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)) {
      txSel := readAtomicReqIdx
    } otherwise {
      txSel := otherReqIdx
      report(
        message =
          L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic requests",
        severity = FAILURE
      )
    }
    val threeStreams = StreamDemux(
      // For duplicate requests, it's not affected by RNR NAK and NAK SEQ
      io.rx.pktFrag.throwWhen(io.recvQCtrl.stateErrFlush),
      select = txSel,
      portCount = 3
    )
    // Just discard non-send/write/read/atomic requests
    StreamSink(NoData) << threeStreams(otherReqIdx).translateWith(NoData)
    io.txDupSendWriteResp <-/< threeStreams(sendWriteReqIdx).translateWith(
      rdmaAck
    )
    io.readAtomicResultCacheReq.req <-/< threeStreams(readAtomicReqIdx)
      .translateWith {
        val rslt = ReadAtomicResultCacheReq()
        rslt.psn := inputPktFrag.bth.psn
        rslt
      }
  }
}

class DupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator
    extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val readAtomicResultCacheResp = slave(ReadAtomicResultCacheRespBus())
    val readResultCacheData = master(Stream(ReadAtomicResultCacheData()))
    val txDupAtomicResp = master(Stream(AtomicResp()))
    val dmaReadReq = master(DmaReadReqBus())
  }

  val readAtomicResultCacheRespValid =
    io.readAtomicResultCacheResp.resp.valid
  val readAtomicResultCacheRespData =
    io.readAtomicResultCacheResp.resp.cachedData
  val readAtomicResultCacheRespFound =
    io.readAtomicResultCacheResp.resp.found

  val (readReqResultCacheIdx, atomicReqResultCacheIdx, otherReqResultCacheIdx) =
    (0, 1, 2)
  val txSel = UInt(2 bits)
  when(OpCode.isReadReqPkt(readAtomicResultCacheRespData.opcode)) {
    txSel := readReqResultCacheIdx
  } elsewhen (OpCode
    .isAtomicReqPkt(readAtomicResultCacheRespData.opcode)) {
    txSel := atomicReqResultCacheIdx
  } otherwise {
    txSel := otherReqResultCacheIdx
    report(
      message =
        L"invalid opcode=${readAtomicResultCacheRespData.opcode}, should be read/atomic requests",
      severity = FAILURE
    )
  }
  val threeStreams = StreamDemux(
    io.readAtomicResultCacheResp.resp.throwWhen(io.recvQCtrl.stateErrFlush),
    select = txSel,
    portCount = 3
  )
  // TODO: to remove this extra logic
  StreamSink(NoData) << threeStreams(otherReqResultCacheIdx).translateWith(
    NoData
  )

  //  val atomicResultThrowCond =
  //    readAtomicResultNotFound || readAtomicRequestNotDone
  val readAtomicResultNotFound =
    readAtomicResultCacheRespValid && !readAtomicResultCacheRespFound
  io.txDupAtomicResp <-/< threeStreams(atomicReqResultCacheIdx)
    .throwWhen(io.recvQCtrl.stateErrFlush || readAtomicResultNotFound)
    .translateWith {
      val rslt = cloneOf(io.txDupAtomicResp.payloadType)
      rslt.set(
        dqpn = io.qpAttr.dqpn,
        psn = readAtomicResultCacheRespData.psnStart,
        orig = readAtomicResultCacheRespData.atomicRslt
      )
      rslt
    }
  when(readAtomicResultCacheRespValid) {
    // Duplicate requests of pending requests are already discarded by ReqCommCheck
    val readAtomicRequestNotDone =
      readAtomicResultCacheRespValid && readAtomicResultCacheRespValid && !readAtomicResultCacheRespData.done

    assert(
      assertion = readAtomicResultCacheRespFound,
      message =
        L"duplicated atomic request with PSN=${io.readAtomicResultCacheResp.resp.query.psn} not found, readAtomicResultCacheRespValid=${readAtomicResultCacheRespValid}, but readAtomicResultCacheRespFound=${readAtomicResultCacheRespFound}",
      severity = FAILURE
    )
    assert(
      assertion = readAtomicRequestNotDone,
      message =
        L"duplicated atomic request with PSN=${io.readAtomicResultCacheResp.resp.query.psn} not done yet, readAtomicResultCacheRespValid=${readAtomicResultCacheRespValid}, readAtomicResultCacheRespFound=${readAtomicResultCacheRespFound}, but readAtomicResultCacheRespData=${readAtomicResultCacheRespData.done}",
      severity = FAILURE
    )
  }

  val retryFromFirstReadResp =
    io.readAtomicResultCacheResp.resp.query.psn === readAtomicResultCacheRespData.psnStart
  // For partial read retry, compute the partial read DMA length
  val psnDiff =
    io.readAtomicResultCacheResp.resp.query.psn - readAtomicResultCacheRespData.psnStart
  // psnDiff << io.qpAttr.pmtu.asUInt === psnDiff * pmtuPktLenBytes(io.qpAttr.pmtu)
  val dmaReadLenBytes =
    readAtomicResultCacheRespData.dlen - (psnDiff << io.qpAttr.pmtu.asUInt)
  when(!retryFromFirstReadResp) {
    assert(
      assertion =
        io.readAtomicResultCacheResp.resp.query.psn > readAtomicResultCacheRespData.psnStart,
      message =
        L"io.readAtomicResultCacheResp.resp.query.psn=${io.readAtomicResultCacheResp.resp.query.psn} should > readAtomicResultCacheRespData.psnStart=${readAtomicResultCacheRespData.psnStart}",
      severity = FAILURE
    )

    assert(
      assertion = psnDiff < computePktNum(
        readAtomicResultCacheRespData.dlen,
        io.qpAttr.pmtu
      ),
      message =
        L"psnDiff=${psnDiff} should < packet num=${computePktNum(readAtomicResultCacheRespData.dlen, io.qpAttr.pmtu)}",
      severity = FAILURE
    )
  }

  val (readResultCacheData4DmaReq, readResultCacheData4Output) = StreamFork2(
    threeStreams(readReqResultCacheIdx)
  )
  io.dmaReadReq.req <-/< readResultCacheData4DmaReq
    .throwWhen(io.recvQCtrl.stateErrFlush)
    .translateWith {
      val rslt = cloneOf(io.dmaReadReq.req.payloadType)
      rslt.set(
        initiator = DmaInitiator.RQ_DUP,
        sqpn = io.qpAttr.sqpn,
        psnStart = io.readAtomicResultCacheResp.resp.query.psn,
        addr = readAtomicResultCacheRespData.pa,
        lenBytes = dmaReadLenBytes.resize(RDMA_MAX_LEN_WIDTH)
      )
    }
  io.readResultCacheData <-/< readResultCacheData4Output.translateWith(
    readResultCacheData4Output.cachedData
  )
}

class ReqDmaCommHeaderExtractor(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufBus(busWidth))
    val tx = master(RqReqWithRecvBufAndDmaInfoBus(busWidth))
    val rqOutPsnRangeFifoPush = master(Stream(RespPsnRange()))
  }

  val inputHasNak = io.rx.reqWithRecvBuf.hasNak
  val inputValid = io.rx.reqWithRecvBuf.valid
  val inputPktFrag = io.rx.reqWithRecvBuf.fragment
  val isLastFrag = io.rx.reqWithRecvBuf.last
  val isFirstFrag = io.rx.reqWithRecvBuf.isFirst
  val recvBuffer = io.rx.reqWithRecvBuf.recvBuffer

  val isSendOrWriteReq =
    OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode) ||
      OpCode.isWriteWithImmReqPkt(inputPktFrag.pktFrag.bth.opcode)
  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.pktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.pktFrag.bth.opcode)
  // TODO: verify inputPktFrag.data is big endian
  val dmaCommHeader = DmaCommHeader().init()
  val dmaHeaderValid = False
  when(OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
    // Extract DMA info for send requests
    dmaCommHeader.va := recvBuffer.addr
    dmaCommHeader.lrkey := recvBuffer.lkey
    dmaCommHeader.dlen := recvBuffer.len

    dmaHeaderValid := inputValid && isFirstFrag

    assert(
      assertion =
        (inputValid && isFirstFrag) === io.rx.reqWithRecvBuf.recvBufValid,
      message =
        L"invalid RecvWorkReq, it should be valid for the first send request fragment",
      severity = FAILURE
    )
  } elsewhen (
    OpCode.isWriteFirstOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode) ||
      isReadReq || isAtomicReq
  ) {
    // Extract DMA info for write/read/atomic requests
    // TODO: verify inputPktFrag.data is big endian
    val rethBits = inputPktFrag.pktFrag.data(
      (busWidth.id - widthOf(BTH()) - widthOf(RETH())) until
        (busWidth.id - widthOf(BTH()))
    )
    val reth = RETH()
    reth.assignFromBits(rethBits)

    dmaCommHeader.va := reth.va
    dmaCommHeader.lrkey := reth.rkey
    dmaCommHeader.dlen := reth.dlen
    when(isAtomicReq) {
      dmaCommHeader.dlen := ATOMIC_DATA_LEN
    }
    dmaHeaderValid := inputValid
  }

  // Since the max length of a request is 2^31, and the min PMTU is 256=2^8
  // So the max number of packet is 2^23 < 2^PSN_WIDTH
  val numRespPkt =
    divideByPmtuUp(dmaCommHeader.dlen, io.qpAttr.pmtu).resize(PSN_WIDTH)

  val (txNormal, rqOutPsnRangeFifoPush) = StreamFork2(
    io.rx.reqWithRecvBuf.throwWhen(io.recvQCtrl.stateErrFlush)
  )
  io.tx.reqWithRecvBufAndDmaInfo <-/< txNormal
    .translateWith {
      val rslt = cloneOf(io.tx.reqWithRecvBufAndDmaInfo.payloadType)
      rslt.pktFrag := txNormal.pktFrag
      rslt.preOpCode := txNormal.preOpCode
      rslt.hasNak := txNormal.hasNak
      rslt.nakAeth := txNormal.nakAeth
      rslt.reqTotalLenValid := False
      rslt.reqTotalLenBytes := 0
      rslt.recvBufValid := txNormal.recvBufValid
      rslt.recvBuffer := recvBuffer
      rslt.dmaHeaderValid := dmaHeaderValid
      rslt.dmaCommHeader := dmaCommHeader
      rslt.last := txNormal.last
      rslt
    }

  // Update output FIFO to keep output PSN order
  io.rqOutPsnRangeFifoPush <-/< rqOutPsnRangeFifoPush
    .takeWhen(
      inputHasNak || (isSendOrWriteReq && !inputPktFrag.pktFrag.bth.ackreq) || isReadReq || isAtomicReq
    )
    .translateWith {
      val rslt = cloneOf(io.rqOutPsnRangeFifoPush.payloadType)
      rslt.opcode := inputPktFrag.pktFrag.bth.opcode
      rslt.start := inputPktFrag.pktFrag.bth.psn
      rslt.end := inputPktFrag.pktFrag.bth.psn
      when(isReadReq) {
        rslt.end := inputPktFrag.pktFrag.bth.psn + numRespPkt - 1
      }
      rslt
    }
}

class ReqAddrValidator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val rx = slave(RqReqWithRecvBufAndDmaInfoBus(busWidth))
    val tx = master(RqReqWithRecvBufAndDmaInfoBus(busWidth))
  }

  val addrCacheReadReqBuilder = new Area {
    val inputValid = io.rx.reqWithRecvBufAndDmaInfo.valid
    val inputPktFrag = io.rx.reqWithRecvBufAndDmaInfo.pktFrag
    val isFirstFrag = io.rx.reqWithRecvBufAndDmaInfo.isFirst
    val inputHeader = io.rx.reqWithRecvBufAndDmaInfo.dmaCommHeader

    val accessKey = inputHeader.lrkey
    val va = inputHeader.va

    val accessType = Bits(ACCESS_TYPE_WIDTH bits)
    val pdId = io.qpAttr.pdId
    val remoteOrLocalKey = True // True: remote, False: local
    val dataLenBytes = inputHeader.dlen
    // Only send
    val invalidQpAddrCacheAgentQuery =
      !io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid
    when(OpCode.isSendReqPkt(inputPktFrag.bth.opcode)) {
      accessType := AccessType.LOCAL_WRITE.id
    } elsewhen (OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)) {
      accessType := AccessType.REMOTE_WRITE.id
    } elsewhen (OpCode.isReadReqPkt(inputPktFrag.bth.opcode)) {
      accessType := AccessType.REMOTE_READ.id
    } elsewhen (OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)) {
      accessType := AccessType.REMOTE_ATOMIC.id
    } otherwise {
      invalidQpAddrCacheAgentQuery := True
      accessType := 0 // 0 is invalid AccessType
      report(
        message =
          L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic",
        severity = FAILURE
      )
    }
    // TODO: verify SignalEdgeDrivenStream triggers one QpAddrCacheAgentRead query
    io.addrCacheRead.req <-/< SignalEdgeDrivenStream(
      io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid && isFirstFrag
    ).throwWhen(io.recvQCtrl.stateErrFlush)
      .translateWith {
        val addrCacheReadReq = QpAddrCacheAgentReadReq()
        addrCacheReadReq.sqpn := io.qpAttr.sqpn
        addrCacheReadReq.psn := inputPktFrag.bth.psn
        addrCacheReadReq.key := accessKey
        addrCacheReadReq.pdId := pdId
        addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = remoteOrLocalKey)
        addrCacheReadReq.accessType := accessType
        addrCacheReadReq.va := va
        addrCacheReadReq.dataLenBytes := dataLenBytes
        addrCacheReadReq
      }

    // To query AddCache, it needs several cycle delay.
    // In order to not block pipeline, use a FIFO to cache incoming data.
    val inputReqQueue = io.rx.reqWithRecvBufAndDmaInfo
      .throwWhen(io.recvQCtrl.stateErrFlush)
      .queueLowLatency(ADDR_CACHE_QUERY_DELAY_CYCLE)
    when(inputValid && isFirstFrag) {
      assert(
        assertion = inputReqQueue.ready,
        message =
          L"when receive first request, inputReqQueue must have space to accept the first request, try increase ADDR_CACHE_QUERY_DELAY_CYCLE",
        severity = FAILURE
      )
    }
  }

  val reqAddrValidator = new Area {
    val inputValid = addrCacheReadReqBuilder.inputReqQueue.valid
    val inputPktFrag = addrCacheReadReqBuilder.inputReqQueue.pktFrag
    val dmaHeaderValid = addrCacheReadReqBuilder.inputReqQueue.dmaHeaderValid

    val addrCacheRespValid = io.addrCacheRead.resp.valid
    val sizeValid = io.addrCacheRead.resp.sizeValid
    val keyValid = io.addrCacheRead.resp.keyValid
    val accessValid = io.addrCacheRead.resp.accessValid
    when(addrCacheRespValid && inputValid) {
      assert(
        assertion = inputPktFrag.bth.psn === io.addrCacheRead.resp.psn,
        message =
          L"addrCacheReadResp.resp has PSN=${io.addrCacheRead.resp.psn} not match input PSN=${inputPktFrag.bth.psn}",
        severity = FAILURE
      )
    }

    val bufLenErr =
      inputValid && dmaHeaderValid && addrCacheRespValid && !sizeValid
    val keyErr =
      inputValid && dmaHeaderValid && addrCacheRespValid && !keyValid
    val accessErr =
      inputValid && dmaHeaderValid && addrCacheRespValid && !accessValid
    val checkPass =
      inputValid && dmaHeaderValid && addrCacheRespValid && !keyErr && !bufLenErr && !accessErr

    val nakInvOrRmtAccAeth = AETH().setDefaultVal()
    when(bufLenErr) {
      nakInvOrRmtAccAeth.set(AckType.NAK_INV)
    } elsewhen (keyErr || accessErr) {
      nakInvOrRmtAccAeth.set(AckType.NAK_RMT_ACC)
    }

    val joinStream = FragmentStreamJoinStream(
      addrCacheReadReqBuilder.inputReqQueue,
      io.addrCacheRead.resp
    )
    val inputHasNak = joinStream._1.hasNak
    val hasNak = !checkPass || inputHasNak
    val nakAeth = cloneOf(joinStream._1.nakAeth)
    nakAeth := joinStream._1.nakAeth
    when(!checkPass && !inputHasNak) {
      // TODO: if rdmaAck is retry NAK, should it be replaced by this NAK_INV or NAK_RMT_ACC?
      nakAeth := nakInvOrRmtAccAeth
    }

    io.tx.reqWithRecvBufAndDmaInfo <-/< joinStream
      .throwWhen(io.recvQCtrl.stateErrFlush)
      .translateWith {
        val rslt = cloneOf(io.tx.reqWithRecvBufAndDmaInfo.payloadType)
        rslt.pktFrag := joinStream._1.pktFrag
        rslt.preOpCode := joinStream._1.preOpCode
        rslt.hasNak := hasNak
        rslt.nakAeth := nakAeth
        rslt.reqTotalLenValid := False
        rslt.reqTotalLenBytes := 0
        rslt.recvBufValid := joinStream._1.recvBufValid
        rslt.recvBuffer := joinStream._1.recvBuffer
        rslt.dmaHeaderValid := joinStream._1.dmaHeaderValid
        rslt.dmaCommHeader.pa := joinStream._2.pa
        rslt.dmaCommHeader.lrkey := joinStream._1.dmaCommHeader.lrkey
        rslt.dmaCommHeader.va := joinStream._1.dmaCommHeader.va
        rslt.dmaCommHeader.dlen := joinStream._1.dmaCommHeader.dlen
        rslt.last := joinStream.isLast
        rslt
      }
  }
}

class PktLenCheck(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufAndDmaInfoBus(busWidth))
    val tx = master(RqReqWithRecvBufAndDmaInfoBus(busWidth))
  }

  val inputHasNak = io.rx.reqWithRecvBufAndDmaInfo.hasNak
  val inputValid = io.rx.reqWithRecvBufAndDmaInfo.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaInfo.pktFrag
  val inputRecvBuffer = io.rx.reqWithRecvBufAndDmaInfo.recvBuffer
  val inputDmaCommHeader = io.rx.reqWithRecvBufAndDmaInfo.dmaCommHeader
  val isLastFrag = io.rx.reqWithRecvBufAndDmaInfo.last
  val isFirstFrag = io.rx.reqWithRecvBufAndDmaInfo.isFirst

  val pktFragLenBytes =
    CountOne(inputPktFrag.mty).resize(RDMA_MAX_LEN_WIDTH)
  val bthLenBytes = widthOf(BTH()) / BYTE_WIDTH
  val rethLenBytes = widthOf(RETH()) / BYTE_WIDTH
  val immDtLenBytes = widthOf(ImmDt()) / BYTE_WIDTH
  val iethLenBytes = widthOf(IETH()) / BYTE_WIDTH

  // recvBufValid and dmaHeaderValid is only valid for the first fragment of first or only request packet
  val dmaTargetLenBytesReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  when(io.rx.reqWithRecvBufAndDmaInfo.recvBufValid) {
    dmaTargetLenBytesReg := inputRecvBuffer.len

    assert(
      assertion = OpCode.isSendReqPkt(inputPktFrag.bth.opcode),
      message =
        L"it should be send requests that require receive buffer, but opcode=${inputPktFrag.bth.opcode}",
      severity = FAILURE
    )
  } elsewhen (io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid) {
    dmaTargetLenBytesReg := inputDmaCommHeader.dlen

    assert(
      assertion = isFirstFrag,
      message =
        L"only first fragment can have dmaCommHeader, but dmaHeaderValid={io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid}, isFirstFrag=${isFirstFrag}",
      severity = FAILURE
    )
  }
  when(inputValid) {
    assert(
      assertion =
        io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid === io.rx.reqWithRecvBufAndDmaInfo.recvBufValid,
      message =
        L"dmaHeaderValid=${io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid} should equal recvBufValid=${io.rx.reqWithRecvBufAndDmaInfo.recvBufValid}",
      severity = FAILURE
    )
  }

  val padCntAdjust = inputPktFrag.bth.padcount
  val rethLenAdjust = U(0, log2Up(rethLenBytes) + 1 bits)
  when(
    OpCode.isWriteFirstOrOnlyReqPkt(inputPktFrag.bth.opcode) || OpCode
      .isReadReqPkt(inputPktFrag.bth.opcode)
  ) {
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
  val sendExtHeaderLenAdjust = immDtLenAdjust + iethLenAdjust
  val writeExtHeaderLenAdjust = rethLenAdjust + immDtLenAdjust

  val isReqTotalLenCheckErr = False
  val isPktLenCheckErr = False
  val pktLenBytesReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  val reqTotalLenBytesReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  // TODO: verify reqTotalLenValid and reqTotalLenBytes have correct value and timing
  val reqTotalLenValid = False
  val reqTotalLenBytes = U(0, RDMA_MAX_LEN_WIDTH bits)
  when(inputValid) {
    switch(isFirstFrag ## isLastFrag) {
      is(True ## True) {
        //    when(isFirstFrag && isLastFrag) {
        pktLenBytesReg := 0
        reqTotalLenBytesReg := 0
        dmaTargetLenBytesReg := 0

        when(OpCode.isWriteOnlyReqPkt(inputPktFrag.bth.opcode)) {
          isReqTotalLenCheckErr := (pktFragLenBytes - bthLenBytes - writeExtHeaderLenAdjust - padCntAdjust) =/= inputDmaCommHeader.dlen
        } elsewhen (
          OpCode.isSendOnlyReqPkt(inputPktFrag.bth.opcode)
        ) {
          isReqTotalLenCheckErr := (pktFragLenBytes - bthLenBytes - sendExtHeaderLenAdjust - padCntAdjust) > inputRecvBuffer.len
        } elsewhen (OpCode.isWriteLastReqPkt(inputPktFrag.bth.opcode)) {
          isReqTotalLenCheckErr := (reqTotalLenBytesReg + pktFragLenBytes - bthLenBytes - writeExtHeaderLenAdjust - padCntAdjust) =/= dmaTargetLenBytesReg
        } elsewhen (
          OpCode.isSendLastReqPkt(inputPktFrag.bth.opcode)
        ) { // The send last request packet has only one fragment
          isReqTotalLenCheckErr := (reqTotalLenBytesReg + pktFragLenBytes - bthLenBytes - sendExtHeaderLenAdjust - padCntAdjust) > dmaTargetLenBytesReg
        }
      }
      is(True ## False) {
        //    } elsewhen (isFirstFrag && !isLastFrag) { // First fragment should consider RDMA header adjustment
        when(OpCode.isFirstReqPkt(inputPktFrag.bth.opcode)) {
          pktLenBytesReg := pktFragLenBytes - bthLenBytes - rethLenAdjust
          reqTotalLenBytesReg := 0
        } elsewhen (OpCode.isMidReqPkt(inputPktFrag.bth.opcode)) {
          pktLenBytesReg := pktLenBytesReg + pktFragLenBytes - bthLenBytes
        } elsewhen (OpCode.isLastReqPkt(inputPktFrag.bth.opcode)) {
          pktLenBytesReg := pktLenBytesReg + pktFragLenBytes - bthLenBytes - immDtLenAdjust - iethLenAdjust
        } elsewhen (OpCode.isOnlyReqPkt(inputPktFrag.bth.opcode)) {
          pktLenBytesReg := pktFragLenBytes - bthLenBytes - rethLenAdjust - immDtLenAdjust - iethLenAdjust
          reqTotalLenBytesReg := 0
        }
      }
      is(False ## True) {
        //    } elsewhen (!isFirstFrag && isLastFrag) { // Last fragment should consider pad count adjustment
        pktLenBytesReg := 0
        reqTotalLenBytes := reqTotalLenBytesReg + pktLenBytesReg + pktFragLenBytes
        reqTotalLenBytesReg := reqTotalLenBytes

        when(OpCode.isWriteLastOrOnlyReqPkt(inputPktFrag.bth.opcode)) {
          reqTotalLenValid := True
          reqTotalLenBytes := reqTotalLenBytesReg + pktLenBytesReg + pktFragLenBytes - padCntAdjust
          isReqTotalLenCheckErr := reqTotalLenBytes =/= dmaTargetLenBytesReg
          reqTotalLenBytesReg := 0
          dmaTargetLenBytesReg := 0
        } elsewhen (
          OpCode.isSendLastOrOnlyReqPkt(inputPktFrag.bth.opcode)
        ) {
          reqTotalLenValid := True
          reqTotalLenBytes := reqTotalLenBytesReg + pktLenBytesReg + pktFragLenBytes - padCntAdjust
          isReqTotalLenCheckErr := reqTotalLenBytes > dmaTargetLenBytesReg
          reqTotalLenBytesReg := 0
          dmaTargetLenBytesReg := 0
        } elsewhen (OpCode.isFirstOrMidReqPkt(inputPktFrag.bth.opcode)) {
          isPktLenCheckErr := (pktLenBytesReg + pktFragLenBytes) =/=
            pmtuPktLenBytes(io.qpAttr.pmtu)
        }
      }
      is(False ## False) {
        //    } otherwise { // !isFirstFrag && !isLastFrag
        pktLenBytesReg := pktLenBytesReg + pktFragLenBytes
      }
    }
  }

  val isLenCheckErr = isReqTotalLenCheckErr || isPktLenCheckErr
  val nakInvAeth = AETH().set(AckType.NAK_INV)
  val nakAeth = cloneOf(io.rx.reqWithRecvBufAndDmaInfo.nakAeth)
  nakAeth := io.rx.reqWithRecvBufAndDmaInfo.nakAeth
  when(isLenCheckErr && !inputHasNak) {
    // TODO: if rdmaAck is retry NAK, should it be replaced by this NAK_INV or NAK_RMT_ACC?
    nakAeth := nakInvAeth
  }

  io.tx.reqWithRecvBufAndDmaInfo <-/< io.rx.reqWithRecvBufAndDmaInfo
    .throwWhen(io.recvQCtrl.stateErrFlush)
    .translateWith {
      val rslt = cloneOf(io.rx.reqWithRecvBufAndDmaInfo.payloadType)
      rslt.pktFrag := inputPktFrag
      rslt.preOpCode := io.rx.reqWithRecvBufAndDmaInfo.preOpCode
      rslt.hasNak := isLenCheckErr
      rslt.nakAeth := nakAeth
      rslt.recvBufValid := io.rx.reqWithRecvBufAndDmaInfo.recvBufValid
      rslt.recvBuffer := inputRecvBuffer
      rslt.reqTotalLenValid := reqTotalLenValid
      rslt.reqTotalLenBytes := reqTotalLenBytes
      rslt.dmaHeaderValid := io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid
      rslt.dmaCommHeader := inputDmaCommHeader
      rslt.last := isLastFrag
      rslt
    }
}

class RqDmaReqInitiatorAndNakGen(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufAndDmaInfoBus(busWidth))
    val txReadAtomic = master(RqReqWithRecvBufAndDmaInfoBus(busWidth))
    val txSendWrite = master(RqReqWithRecvBufAndDmaInfoBus(busWidth))
    val readDmaReq = master(DmaReadReqBus())
    val sendWriteDmaReq = master(DmaWriteReqBus(busWidth))
//    val dma = master(DmaBus(busWidth))
    val nakNotify = out(RqNakNotifier())
    val txErrResp = master(Stream(Acknowledge()))
    val sendWriteWorkCompAndNak = master(Stream(WorkCompAndAck()))
  }

  val inputHasNak = io.rx.reqWithRecvBufAndDmaInfo.hasNak
  val inputValid = io.rx.reqWithRecvBufAndDmaInfo.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaInfo.pktFrag
  val isLastFrag = io.rx.reqWithRecvBufAndDmaInfo.last
  val inputDmaHeader = io.rx.reqWithRecvBufAndDmaInfo.dmaCommHeader
  val isEmptyReq =
    io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid && inputDmaHeader.dlen === 0

  val isSendReq = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
  val isWriteReq = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)

  val txSel = UInt(2 bits)
  val (sendWriteIdx, readAtomicIdx, errRespIdx, otherIdx) = (0, 1, 2, 3)
  when(inputHasNak) {
    txSel := errRespIdx
  } elsewhen (isSendReq || isWriteReq) {
    txSel := sendWriteIdx
  } elsewhen (isReadReq || isAtomicReq) {
    txSel := readAtomicIdx
  } otherwise {
    report(
      message =
        L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic requests",
      severity = FAILURE
    )
    txSel := otherIdx
  }
  val fourStreams = StreamDemux(
    io.rx.reqWithRecvBufAndDmaInfo.throwWhen(io.recvQCtrl.stateErrFlush),
    select = txSel,
    portCount = 4
  )
  StreamSink(NoData) << fourStreams(otherIdx).translateWith(NoData)

  val readAtomicReqStream = fourStreams(readAtomicIdx)
  val (forkReadAtomicReqStream4DmaRead, forkReadAtomicReqStream4Output) =
    StreamFork2(readAtomicReqStream)

  io.readDmaReq.req <-/< forkReadAtomicReqStream4DmaRead
    .throwWhen(io.recvQCtrl.stateErrFlush || isEmptyReq)
    .translateWith {
      val rslt = cloneOf(io.readDmaReq.req.payloadType)
      rslt.set(
        initiator = DmaInitiator.RQ_RD,
        sqpn = io.qpAttr.sqpn,
        psnStart = inputPktFrag.bth.psn,
        addr = inputDmaHeader.pa,
        lenBytes = inputDmaHeader.dlen
      )
    }
  io.txReadAtomic.reqWithRecvBufAndDmaInfo <-/< forkReadAtomicReqStream4Output

  val dmaAddrReg = Reg(UInt(MEM_ADDR_WIDTH bits))
  when(inputValid && io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid) {
    dmaAddrReg := inputDmaHeader.pa
  }
  val sendWriteReqStream = fourStreams(sendWriteIdx)
  val (forkSendWriteReqStream4DmaWrite, forkSendWriteReqStream4Output) =
    StreamFork2(sendWriteReqStream)
  io.sendWriteDmaReq.req <-/< forkSendWriteReqStream4DmaWrite
    .throwWhen(io.recvQCtrl.stateErrFlush || isEmptyReq)
    .translateWith {
      val rslt = cloneOf(io.sendWriteDmaReq.req.payloadType)
      rslt.last := isLastFrag
      rslt.set(
        initiator = DmaInitiator.RQ_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        addr =
          (inputValid && io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid) ? inputDmaHeader.pa | dmaAddrReg,
        workReqId = io.rx.reqWithRecvBufAndDmaInfo.recvBuffer.id,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      rslt
    }
  io.txSendWrite.reqWithRecvBufAndDmaInfo <-/< forkSendWriteReqStream4Output

  val nakResp = Acknowledge().setAck(
    io.rx.reqWithRecvBufAndDmaInfo.nakAeth,
    inputPktFrag.bth.psn,
    io.qpAttr.dqpn
  )
  val (errResp4Tx, errResp4WorkComp) = StreamFork2(fourStreams(errRespIdx))
  io.txErrResp <-/< errResp4Tx.translateWith(nakResp)
  io.sendWriteWorkCompAndNak <-/< errResp4WorkComp
    .throwWhen(io.recvQCtrl.stateErrFlush || nakResp.aeth.isRetryNak())
    // TODO: verify it only send out one WC when NAK response
    .takeWhen(errResp4WorkComp.recvBufValid)
    .translateWith {
      val rslt = cloneOf(io.sendWriteWorkCompAndNak.payloadType)
      rslt.workComp.setFromRecvWorkReq(
        errResp4WorkComp.recvBuffer,
        errResp4WorkComp.pktFrag.bth.opcode,
        io.qpAttr.dqpn,
        nakResp.aeth.toWorkCompStatus(),
        errResp4WorkComp.reqTotalLenBytes, // reqTotalLenBytes does not matter here
        errResp4WorkComp.pktFrag.data
      )
      rslt.ackValid := errResp4WorkComp.valid
      rslt.ack := nakResp
      rslt
    }

  io.nakNotify.setFromAeth(
    aeth = io.rx.reqWithRecvBufAndDmaInfo.nakAeth,
    pulse = fourStreams(errRespIdx).fire,
    preOpCode = io.rx.reqWithRecvBufAndDmaInfo.preOpCode,
    psn = inputPktFrag.bth.psn
  )
}

/** DO NOT DELETE
  * Used to extract full atomic request when bus width < widthOf(BTH + AtomicEth)
  */
//class AtomicReqExtractor(busWidth: BusWidth) extends Component {
//  val io = new Bundle {
//    // val qpAttr = in(QpAttrData())
//    val recvQCtrl = in(RecvQCtrl())
//    val rx = slave(RqReqWithRecvBufAndDmaInfoBus(busWidth))
//    // val txAtomicReq = master(Stream(AtomicReq()))
//    val txAtomicReqSaveToCache = master(Stream(ReadAtomicResultCacheData()))
//  }
//
//  val busWidthBytes = busWidth.id / BYTE_WIDTH
//  val bthWidth = widthOf(BTH())
//  val bthLenBytes = bthWidth / BYTE_WIDTH
//  val atomicEthLenBytes = widthOf(AtomicEth()) / BYTE_WIDTH
//
//  val inputValid = io.rx.reqWithRecvBufAndDmaInfo.valid
//  val inputPktFrag = io.rx.reqWithRecvBufAndDmaInfo.pktFrag
//  val isLastFrag = io.rx.reqWithRecvBufAndDmaInfo.last
//
//  require(bthWidth == 12, s"bthWidth=${bthWidth} should be 12 bytes")
//  // For easiness, bus width should be larger than BTH width,
//  // Therefore, it's convenient to read BTH in the first packet
//  require(
//    busWidthBytes >= bthLenBytes,
//    s"busWidthBytes=${busWidthBytes} should be larger than BTH width=${bthLenBytes} bytes"
//  )
//  require(
//    atomicEthLenBytes + bthLenBytes > busWidthBytes,
//    s"must have AtomicEth width=${atomicEthLenBytes} bytes + BTH width=${bthLenBytes} bytes > busWidthBytes=${busWidthBytes} bytes"
//  )
//
//  assert(
//    assertion = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode),
//    message =
//      L"AtomicReqExtractor can only handle atomic request, but opcode=${inputPktFrag.bth.opcode}",
//    severity = FAILURE
//  )
//
//  // Handle bus width smaller than atomic request length
//  val atomicReqLen = widthOf(BTH()) + widthOf(AtomicEth())
//  // Atomic request length: BTH 12 bytes + AtomicEth 28 bytes = 40 bytes
//  // For 256 bits (32 bytes) bus width, residue = 8 bytes, left over of AtomicEth = 20 bytes
//  val residueLen = atomicReqLen - busWidth.id
//  val leftOverLen = if (atomicReqLen > busWidth.id) {
//    widthOf(AtomicEth()) - residueLen
//  } else {
//    0 // Bus width larger than atomicReqLen, no left over bits
//  }
//
//  val atomicEth = AtomicEth()
//  if (residueLen > 0) { // Bus width less than atomicReqLen
//    if (leftOverLen > 0) { // AtomicEth spans two consecutive fragments
//      val isFirstFragReg = RegInit(True)
//      when(inputValid) {
//        isFirstFragReg := isLastFrag
//      }
//
//      val atomicLeftOverReg = Reg(Bits(leftOverLen bits))
//      when(inputValid && isFirstFragReg) {
//        atomicLeftOverReg := inputPktFrag.data(0, leftOverLen bits)
//      }
//
//      // TODO: verify inputPktFrag.data is big endian
//      atomicEth.assignFromBits(
//        atomicLeftOverReg ## inputPktFrag
//          .data((busWidth.id - residueLen) until busWidth.id)
//      )
//    } else { // AtomicEth is within the last fragment, two fragments total
//      // TODO: verify inputPktFrag.data is big endian
//      atomicEth.assignFromBits(
//        inputPktFrag
//          .data((busWidth.id - widthOf(AtomicEth())) until busWidth.id)
//      )
//
//    }
//  } else { // Bus width greater than atomicReqLen
//    // TODO: verify inputPktFrag.data is big endian
//    atomicEth.assignFromBits(
//      inputPktFrag.data(
//        (busWidth.id - widthOf(BTH()) - widthOf(AtomicEth())) until
//          (busWidth.id - widthOf(BTH()))
//      )
//    )
//  }
//
//  io.txAtomicReqSaveToCache <-/< io.rx.reqWithRecvBufAndDmaInfo
//    .throwWhen(io.recvQCtrl.stateErrFlush || !isLastFrag)
//    .translateWith {
//      val rslt = cloneOf(io.txAtomicReqSaveToCache.payloadType)
//      rslt.psnStart := inputPktFrag.bth.psn
//      rslt.pktNum := 1 // Atomic response has only one packet
//      rslt.opcode := inputPktFrag.bth.opcode
//      rslt.pa := io.rx.reqWithRecvBufAndDmaInfo.dmaCommHeader.pa
//      rslt.va := atomicEth.va
//      rslt.rkey := atomicEth.rkey
//      rslt.dlen := io.rx.reqWithRecvBufAndDmaInfo.dmaCommHeader.dlen
//      rslt.swap := atomicEth.swap
//      rslt.comp := atomicEth.comp
//      rslt.atomicRslt := 0
//      rslt.done := False
//      rslt
//    }
//}

class ReadAtomicReqExtractor(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufAndDmaInfoBus(busWidth))
    val readAtomicResultCachePush = master(Stream(ReadAtomicResultCacheData()))
    val readResultCacheData = master(Stream(ReadAtomicResultCacheData()))
    val atomicResultCacheData = master(Stream(ReadAtomicResultCacheData()))
  }

  val busWidthBytes = busWidth.id / BYTE_WIDTH
  val bthLenBytes = widthOf(BTH()) / BYTE_WIDTH
  val rethLenBytes = widthOf(RETH()) / BYTE_WIDTH
  val atomicEthLenBytes = widthOf(AtomicEth()) / BYTE_WIDTH

  // Bus width should be larger than BTH + AtomicEth width
  require(
    atomicEthLenBytes + bthLenBytes <= busWidthBytes,
    s"must have AtomicEth width=${atomicEthLenBytes} bytes + BTH width=${bthLenBytes} bytes <= busWidthBytes=${busWidthBytes} bytes"
  )
  // For easiness, bus width should be larger than BTH + RETH width
  require(
    bthLenBytes + rethLenBytes <= busWidthBytes,
    s"busWidthBytes=${busWidthBytes} should be larger than BTH width=${bthLenBytes} bytes + RETH width=${rethLenBytes} btyes"
  )

  val inputValid = io.rx.reqWithRecvBufAndDmaInfo.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaInfo.pktFrag
  val isLastFrag = io.rx.reqWithRecvBufAndDmaInfo.last

  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
  when(inputValid) {
    assert(
      assertion = isReadReq ^ isReadReq,
      message =
        L"ReadAtomicReqExtractor can only handle read/atomic request, but opcode=${inputPktFrag.bth.opcode}",
      severity = FAILURE
    )
  }

  // BTH is included in inputPktFrag.data
  // TODO: verify inputPktFrag.data is big endian
  val rethBits = inputPktFrag.data(
    (busWidth.id - widthOf(BTH()) - widthOf(RETH())) until
      (busWidth.id - widthOf(BTH()))
  )
  val reth = RETH()
  reth.assignFromBits(rethBits)

  // BTH is included in inputPktFrag.data
  // TODO: verify inputPktFrag.data is big endian
  val atomicEthBits = inputPktFrag.data(
    (busWidth.id - widthOf(BTH()) - widthOf(AtomicEth())) until
      (busWidth.id - widthOf(BTH()))
  )
  val atomicEth = AtomicEth()
  atomicEth.assignFromBits(atomicEthBits)

//  val (rx4Tx, rx4ReadAtomicResultCache) = StreamFork2(io.rx.reqWithRecvBufAndDmaInfo
//    .throwWhen(io.recvQCtrl.stateErrFlush))
//  io.tx.reqWithRecvBufAndDmaInfo <-/< rx4Tx
  val readAtomicResultCacheData = io.rx.reqWithRecvBufAndDmaInfo
    .throwWhen(io.recvQCtrl.stateErrFlush)
    .translateWith {
      val rslt = cloneOf(io.readAtomicResultCachePush.payloadType)
      rslt.psnStart := inputPktFrag.bth.psn
      rslt.pktNum := computePktNum(reth.dlen, io.qpAttr.pmtu)
      rslt.opcode := inputPktFrag.bth.opcode
      rslt.pa := io.rx.reqWithRecvBufAndDmaInfo.dmaCommHeader.pa
      when(isReadReq) {
        rslt.va := reth.va
        rslt.rkey := reth.rkey
        rslt.dlen := reth.dlen
        rslt.swap := 0
        rslt.comp := 0
      } otherwise {
        rslt.va := atomicEth.va
        rslt.rkey := atomicEth.rkey
        rslt.dlen := ATOMIC_DATA_LEN
        rslt.swap := atomicEth.swap
        rslt.comp := atomicEth.comp
      }
      rslt.atomicRslt := 0
      rslt.done := False
      rslt
    }

  val (readAtomicResultCacheData4Push, readAtomicResultCacheData4Output) =
    StreamFork2(readAtomicResultCacheData)
  io.readAtomicResultCachePush <-/< readAtomicResultCacheData4Push

  val txSel = UInt(2 bits)
  val (readIdx, atomicIdx, otherIdx) = (0, 1, 2)
  when(isReadReq) {
    txSel := readIdx
  } elsewhen (isAtomicReq) {
    txSel := atomicIdx
  } otherwise {
    report(
      message =
        L"invalid opcode=${inputPktFrag.bth.opcode}, should be read/atomic request",
      severity = FAILURE
    )
    txSel := otherIdx
  }
  val threeStreams =
    StreamDemux(
      input = readAtomicResultCacheData4Output,
      select = txSel,
      portCount = 3
    )
  io.readResultCacheData <-/< threeStreams(readIdx)
  io.atomicResultCacheData <-/< threeStreams(atomicIdx)
  StreamSink(NoData) << threeStreams(otherIdx).translateWith(NoData)
}

// TODO: prevent coalesce response to too many send/write requests
class SendWriteRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufAndDmaInfoBus(busWidth))
    val tx = master(Stream(Acknowledge()))
    val sendWriteWorkCompAndAck = master(Stream(WorkCompAndAck()))
  }

  val inputValid = io.rx.reqWithRecvBufAndDmaInfo.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaInfo.pktFrag
  val isFirstFrag = io.rx.reqWithRecvBufAndDmaInfo.isFirst
  val isLastFrag = io.rx.reqWithRecvBufAndDmaInfo.isLast
  val inputDmaHeader = io.rx.reqWithRecvBufAndDmaInfo.dmaCommHeader
//  val isEmptyReq = io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid && inputDmaHeader.dlen === 0

  val isSendOrWriteImmReq =
    OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)
  val inputShouldHaveRecvWorkReq =
    inputValid && isFirstFrag && isSendOrWriteImmReq
  when(inputShouldHaveRecvWorkReq) {
    assert(
      assertion = io.rx.reqWithRecvBufAndDmaInfo.recvBufValid,
      message =
        L"both inputShouldHaveRecvWorkReq=${inputShouldHaveRecvWorkReq} and io.rx.reqWithRecvBufAndDmaInfo.recvBufValid=${io.rx.reqWithRecvBufAndDmaInfo.recvBufValid} should be true",
      severity = FAILURE
    )
  }

  when(io.rx.reqWithRecvBufAndDmaInfo.valid) {
    assert(
      assertion = OpCode.isSendReqPkt(inputPktFrag.bth.opcode) ||
        OpCode.isWriteReqPkt(inputPktFrag.bth.opcode),
      message =
        L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write requests",
      severity = FAILURE
    )
  }

  when(
    OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteFirstOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
  ) {
    when(inputValid && isFirstFrag) {
      assert(
        assertion = io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid,
        message =
          L"io.rx.dmaHeaderValid=${io.rx.reqWithRecvBufAndDmaInfo.dmaHeaderValid} should be true for the first fragment of a request",
        severity = FAILURE
      )
    }
  }

  val rdmaAck =
    Acknowledge().setAck(AckType.NORMAL, inputPktFrag.bth.psn, io.qpAttr.dqpn)
  val (req4Tx, req4WorkComp) = StreamFork2(
    io.rx.reqWithRecvBufAndDmaInfo
      .throwWhen(io.recvQCtrl.stateErrFlush || !inputPktFrag.bth.ackreq)
  )

  // Responses to send/write do not wait for DMA write responses
  io.tx <-/< req4Tx.translateWith(rdmaAck)
  io.sendWriteWorkCompAndAck <-/< req4WorkComp
    .takeWhen(req4WorkComp.reqTotalLenValid)
    .translateWith {
      val rslt = cloneOf(io.sendWriteWorkCompAndAck.payloadType)
      rslt.workComp.setSuccessFromRecvWorkReq(
        req4WorkComp.recvBuffer,
        req4WorkComp.pktFrag.bth.opcode,
        io.qpAttr.dqpn,
        req4WorkComp.reqTotalLenBytes,
        req4WorkComp.pktFrag.data
      )
      rslt.ackValid := req4WorkComp.valid
      rslt.ack := rdmaAck
      rslt
    }
}

/** When received a new DMA response, combine the DMA response and
  * ReadAtomicResultCacheData to downstream, also handle
  * zero DMA length read request.
  */
class RqReadDmaRespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val readResultCacheData = slave(Stream(ReadAtomicResultCacheData()))
    val dmaReadResp = slave(DmaReadRespBus(busWidth))
    val readResultCacheDataAndDmaReadResp = master(
      Stream(Fragment(ReadAtomicResultCacheDataAndDmaReadResp(busWidth)))
    )
  }

  val handlerOutput = DmaReadRespHandler(
    io.readResultCacheData,
    io.dmaReadResp,
    io.recvQCtrl.stateErrFlush,
    busWidth,
    isReqZeroDmaLen = (req: ReadAtomicResultCacheData) => req.dlen === 0
  )

  io.readResultCacheDataAndDmaReadResp <-/< handlerOutput.translateWith {
    val rslt = cloneOf(io.readResultCacheDataAndDmaReadResp.payloadType)
    rslt.dmaReadResp := handlerOutput.dmaReadResp
    rslt.resultCacheData := handlerOutput.req
    rslt.last := handlerOutput.last
    rslt
  }
}

class ReadRespSegment(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val readResultCacheDataAndDmaReadResp = slave(
      Stream(Fragment(ReadAtomicResultCacheDataAndDmaReadResp(busWidth)))
    )
    val readResultCacheDataAndDmaReadRespSegment = master(
      Stream(Fragment(ReadAtomicResultCacheDataAndDmaReadResp(busWidth)))
    )
  }

  val segmentOut = DmaReadRespSegment(
    io.readResultCacheDataAndDmaReadResp,
    io.recvQCtrl.stateErrFlush,
    io.qpAttr.pmtu,
    busWidth,
    isReqZeroDmaLen =
      (reqAndDmaReadResp: ReadAtomicResultCacheDataAndDmaReadResp) =>
        reqAndDmaReadResp.resultCacheData.dlen === 0
  )
  io.readResultCacheDataAndDmaReadRespSegment <-/< segmentOut
}

class ReadRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val readResultCacheDataAndDmaReadRespSegment = slave(
      Stream(Fragment(ReadAtomicResultCacheDataAndDmaReadResp(busWidth)))
    )
    val txReadResp = master(RdmaDataBus(busWidth))
  }

  val busWidthBytes = busWidth.id / BYTE_WIDTH

  val input = io.readResultCacheDataAndDmaReadRespSegment
  when(input.valid) {
    assert(
      assertion = input.resultCacheData.dlen === input.dmaReadResp.lenBytes,
      message =
        L"input.resultCacheData.dlen=${input.resultCacheData.dlen} should equal input.dmaReadResp.lenBytes=${input.dmaReadResp.lenBytes}",
      severity = FAILURE
    )
  }

  val reqAndDmaReadRespSegment = input.translateWith {
    val rslt =
      Fragment(ReqAndDmaReadResp(ReadAtomicResultCacheData(), busWidth))
    rslt.dmaReadResp := input.dmaReadResp
    rslt.req := input.resultCacheData
    rslt.last := input.isLast
    rslt
  }

  val combinerOutput = CombineHeaderAndDmaResponse(
    reqAndDmaReadRespSegment,
    io.qpAttr,
    io.recvQCtrl.stateErrFlush,
    busWidth,
//    pktNumFunc = (req: ReadAtomicResultCacheData, _: QpAttrData) => req.pktNum,
    headerGenFunc = (
        inputResultCacheData: ReadAtomicResultCacheData,
        inputDmaDataFrag: DmaReadResp,
        curReadRespPktCntVal: UInt,
        qpAttr: QpAttrData
    ) =>
      new Composite(this) {
        val numReadRespPkt = inputResultCacheData.pktNum
        val lastOrOnlyReadRespPktLenBytes =
          moduloByPmtu(inputDmaDataFrag.lenBytes, qpAttr.pmtu)

        val isFromFirstResp =
          inputDmaDataFrag.psnStart === inputResultCacheData.psnStart
        val curPsn = inputDmaDataFrag.psnStart + curReadRespPktCntVal
        val opcode = Bits(OPCODE_WIDTH bits)
        val padcount = U(0, PADCOUNT_WIDTH bits)

        val bth = BTH().set(
          opcode = opcode,
          padcount = padcount,
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

              // TODO: verify endian
              headerBits := (bth ## aeth).resize(busWidth.id)
              headerMtyBits := (bthMty ## aethMty).resize(busWidthBytes)
            } otherwise {
              opcode := OpCode.RDMA_READ_RESPONSE_MIDDLE.id

              // TODO: verify endian
              headerBits := bth.asBits.resize(busWidth.id)
              headerMtyBits := bthMty.resize(busWidthBytes)
            }
          } elsewhen (curReadRespPktCntVal === numReadRespPkt - 1) {
            opcode := OpCode.RDMA_READ_RESPONSE_LAST.id
            padcount := (PADCOUNT_FULL -
              lastOrOnlyReadRespPktLenBytes(0, PADCOUNT_WIDTH bits))
              .resize(PADCOUNT_WIDTH)

            // TODO: verify endian
            headerBits := (bth ## aeth).resize(busWidth.id)
            headerMtyBits := (bthMty ## aethMty).resize(busWidthBytes)
          } otherwise {
            opcode := OpCode.RDMA_READ_RESPONSE_MIDDLE.id

            // TODO: verify endian
            headerBits := bth.asBits.resize(busWidth.id)
            headerMtyBits := bthMty.resize(busWidthBytes)
          }
        } otherwise {
          when(isFromFirstResp) {
            opcode := OpCode.RDMA_READ_RESPONSE_ONLY.id
          } otherwise {
            opcode := OpCode.RDMA_READ_RESPONSE_LAST.id
          }
          padcount := (PADCOUNT_FULL -
            lastOrOnlyReadRespPktLenBytes(0, PADCOUNT_WIDTH bits))
            .resize(PADCOUNT_WIDTH)

          // TODO: verify endian
          headerBits := (bth ## aeth).resize(busWidth.id)
          headerMtyBits := (bthMty ## aethMty).resize(busWidthBytes)
        }

        val rslt = CombineHeaderAndDmaRespInternalRslt(busWidth)
          .set(inputResultCacheData.pktNum, bth, headerBits, headerMtyBits)
      }.rslt
  )
  io.txReadResp.pktFrag <-/< combinerOutput.pktFrag
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
// TODO: handle duplicated atomic request
class AtomicRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val atomicResultCacheData = slave(Stream(ReadAtomicResultCacheData()))
    val tx = master(Stream(AtomicResp()))
    val dma = master(DmaBus(busWidth))
  }

  // TODO: implemntation
  StreamSink(NoData) << io.atomicResultCacheData.translateWith(NoData)
  val atomicResp = AtomicResp().setDefaultVal()

  io.dma.rd.req << io.dma.rd.resp.translateWith(
    DmaReadReq().set(
      initiator = DmaInitiator.RQ_ATOMIC_RD,
      psnStart = atomicResp.bth.psn,
      sqpn = io.qpAttr.sqpn,
      addr = 0,
      lenBytes = ATOMIC_DATA_LEN
    )
  )
  io.dma.wr.req << io.dma.wr.resp.translateWith {
    val rslt = Fragment(DmaWriteReq(busWidth))
    rslt.set(
      initiator = DmaInitiator.RQ_ATOMIC_WR,
      sqpn = io.qpAttr.sqpn,
      psn = atomicResp.bth.psn,
      addr = 0,
      workReqId = 0, // TODO: RQ has no WR ID, need refactor
      data = 0,
      mty = 0
    )
    rslt.last := True
    rslt
  }
  io.tx << StreamSource().translateWith(atomicResp)
}

class RqSendWriteWorkCompGenerator extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val dmaWriteResp = slave(DmaWriteRespBus())
    val sendWriteWorkCompNormal = slave(Stream(WorkCompAndAck()))
    val sendWriteWorkCompErr = slave(Stream(WorkCompAndAck()))
    val sendWriteWorkCompOut = master(Stream(WorkComp()))
  }

  val workCompAndAckQueuePop =
    io.sendWriteWorkCompNormal.queueLowLatency(DMA_WRITE_DELAY_CYCLE)

  val dmaWriteRespPnsLessThanWorkCompPsn = PsnUtil.lt(
    io.dmaWriteResp.resp.psn,
    workCompAndAckQueuePop.ack.bth.psn,
    io.qpAttr.epsn
  )
  val dmaWriteRespPnsEqualWorkCompPsn =
    io.dmaWriteResp.resp.psn === workCompAndAckQueuePop.ack.bth.psn
  io.dmaWriteResp.resp.ready := !workCompAndAckQueuePop.valid
  when(workCompAndAckQueuePop.valid) {
    when(dmaWriteRespPnsLessThanWorkCompPsn) {
      io.dmaWriteResp.resp.ready := io.dmaWriteResp.resp.valid
    } elsewhen (dmaWriteRespPnsEqualWorkCompPsn) {
      io.dmaWriteResp.resp.ready := io.dmaWriteResp.resp.valid && io.sendWriteWorkCompOut.fire
    }
  }
  val continueCond =
    io.dmaWriteResp.resp.valid && dmaWriteRespPnsEqualWorkCompPsn
  io.sendWriteWorkCompOut <-/< workCompAndAckQueuePop
    .continueWhen(continueCond)
    .translateWith(workCompAndAckQueuePop.workComp)

  // TODO: output io.sendWriteWorkCompErr
  StreamSink(NoData) << io.sendWriteWorkCompErr.translateWith(NoData)
}

// For duplicated requests, also return ACK in PSN order
// TODO: after RNR and NAK SEQ returned, no other nested NAK send
class RqOut(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val opsnInc = out(OPsnInc())
    val outPsnRangeFifoPush = slave(Stream(RespPsnRange()))
    val readAtomicResultCachePop = slave(Stream(ReadAtomicResultCacheData()))
    val rxSendWriteResp = slave(Stream(Acknowledge()))
    val rxReadResp = slave(RdmaDataBus(busWidth))
    val rxAtomicResp = slave(Stream(AtomicResp()))
    val rxErrResp = slave(Stream(Acknowledge()))
    val rxDupSendWriteResp = slave(Stream(Acknowledge()))
    val rxDupReadResp = slave(RdmaDataBus(busWidth))
    val rxDupAtomicResp = slave(Stream(AtomicResp()))
    val tx = master(RdmaDataBus(busWidth))
  }

  // TODO: set max pending request number using QpAttrData
  val psnOutRangeFifo = StreamFifoLowLatency(
    io.outPsnRangeFifoPush.payloadType(),
    depth = PENDING_REQ_NUM
  )
  psnOutRangeFifo.io.push << io.outPsnRangeFifoPush

  val rxSendWriteResp = io.rxSendWriteResp.translateWith(
    io.rxSendWriteResp.toRdmaDataPktFrag(busWidth)
  )
  val rxAtomicResp =
    io.rxAtomicResp.translateWith(io.rxAtomicResp.toRdmaDataPktFrag(busWidth))
  val rxErrResp =
    io.rxErrResp.translateWith(io.rxErrResp.toRdmaDataPktFrag(busWidth))
  val rxDupSendWriteResp = io.rxDupSendWriteResp.translateWith(
    io.rxDupSendWriteResp.toRdmaDataPktFrag(busWidth)
  )
  val rxDupAtomicResp =
    io.rxDupAtomicResp.translateWith(
      io.rxDupAtomicResp.toRdmaDataPktFrag(busWidth)
    )
  val txVec =
    Vec(rxSendWriteResp, io.rxReadResp.pktFrag, rxAtomicResp, rxErrResp)
  val txSelOH = txVec.map(resp => {
    val psnRangeMatch =
      psnOutRangeFifo.io.pop.start <= resp.bth.psn && resp.bth.psn <= psnOutRangeFifo.io.pop.end
    when(psnRangeMatch && psnOutRangeFifo.io.pop.valid) {
      assert(
        assertion =
          checkRespOpCodeMatch(psnOutRangeFifo.io.pop.opcode, resp.bth.opcode),
        message =
          L"request opcode=${psnOutRangeFifo.io.pop.opcode} and response opcode=${resp.bth.opcode} not match",
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

  val txDupRespVec =
    Vec(rxDupSendWriteResp, io.rxDupReadResp.pktFrag, rxDupAtomicResp)
  io.tx.pktFrag <-/< StreamArbiterFactory.roundRobin.fragmentLock
    .on(txDupRespVec :+ txOutputSel.continueWhen(hasPktToOutput))

  val isReadReq = OpCode.isReadReqPkt(psnOutRangeFifo.io.pop.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(psnOutRangeFifo.io.pop.opcode)
  io.readAtomicResultCachePop.ready := txOutputSel.fire && txOutputSel.bth.psn === (io.readAtomicResultCachePop.psnStart + io.readAtomicResultCachePop.pktNum - 1)
  when(io.readAtomicResultCachePop.fire) {
    assert(
      assertion = isReadReq || isAtomicReq,
      message =
        L"the output should correspond to read/atomic resp, io.readAtomicResultCachePop.fire=${io.readAtomicResultCachePop.fire}, but psnOutRangeFifo.io.pop.opcode=${psnOutRangeFifo.io.pop.opcode}",
      severity = FAILURE
    )
    assert(
      assertion = psnOutRangeFifo.io.pop.fire,
      message =
        L"io.readAtomicResultCachePop.fire=${io.readAtomicResultCachePop.fire} and psnOutRangeFifo.io.pop.fire=${psnOutRangeFifo.io.pop.fire} should fire at the same time",
      severity = FAILURE
    )
  }
}
