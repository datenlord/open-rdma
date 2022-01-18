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
    val addrCacheRead = master(AddrCacheReadBus())
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

    val dupRqDmaReadRespHandler = new RqDmaReadRespHandler(busWidth)
    dupRqDmaReadRespHandler.io.qpAttr := io.qpAttr
    dupRqDmaReadRespHandler.io.recvQCtrl := io.recvQCtrl
    dupRqDmaReadRespHandler.io.dmaReadResp.resp << io.dma.dupRead.resp
    readAtomicResultCache.io.queryPort4DupReqDmaRead << dupRqDmaReadRespHandler.io.readAtomicResultCacheQuery

    val dupReadRespGenerator = new ReadRespGenerator(busWidth)
    dupReadRespGenerator.io.qpAttr := io.qpAttr
    dupReadRespGenerator.io.recvQCtrl := io.recvQCtrl
    dupReadRespGenerator.io.readAtomicResultCacheRespAndDmaReadResp << dupRqDmaReadRespHandler.io.readAtomicResultCacheRespAndDmaReadResp
  }

  // TODO: make sure that when retry occurred, it only needs to flush reqValidateLogic
  // TODO: make sure that when error occurred, it needs to flush both reqCommCheck and reqValidateLogic
  val reqValidateLogic = new Area {
    val reqRnrCheck = new ReqRnrCheck(busWidth)
    reqRnrCheck.io.qpAttr := io.qpAttr
    reqRnrCheck.io.recvQCtrl := io.recvQCtrl
    reqRnrCheck.io.recvWorkReq << io.recvWorkReq
    reqRnrCheck.io.rx << reqCommCheck.io.tx
    reqRnrCheck.io.occupancy := readAtomicResultCache.io.occupancy
    io.notifier.rnr := reqRnrCheck.io.rnrNotify

    val reqDmaCommHeaderExtractor = new ReqDmaCommHeaderExtractor(busWidth)
    reqDmaCommHeaderExtractor.io.recvQCtrl := io.recvQCtrl
    reqDmaCommHeaderExtractor.io.rx << reqRnrCheck.io.tx

    val reqAddrValidator = new ReqAddrValidator(busWidth)
    reqAddrValidator.io.qpAttr := io.qpAttr
    reqAddrValidator.io.recvQCtrl := io.recvQCtrl
    reqAddrValidator.io.rx <<
      reqDmaCommHeaderExtractor.io.txWithRecvBufAndDmaCommHeader
    io.addrCacheRead << reqAddrValidator.io.addrCacheRead

    val pktLenCheck = new PktLenCheck(busWidth)
    pktLenCheck.io.qpAttr := io.qpAttr
    pktLenCheck.io.recvQCtrl := io.recvQCtrl
    pktLenCheck.io.rx << reqAddrValidator.io.tx
  }

  val dmaReqLogic = new Area {
    val dmaReqInitiator = new RqDmaReqInitiator(busWidth)
    dmaReqInitiator.io.qpAttr := io.qpAttr
    dmaReqInitiator.io.recvQCtrl := io.recvQCtrl
    dmaReqInitiator.io.rx << reqValidateLogic.pktLenCheck.io.tx
    io.dma.read.req << dmaReqInitiator.io.dma.rd.req
    io.dma.sendWrite.req << dmaReqInitiator.io.dma.wr.req

    val readAtomicReqExtractor = new ReadAtomicReqExtractor(busWidth)
    readAtomicReqExtractor.io.qpAttr := io.qpAttr
    readAtomicReqExtractor.io.recvQCtrl := io.recvQCtrl
    readAtomicReqExtractor.io.rx << dmaReqInitiator.io.txReadAtomic
    readAtomicResultCache.io.push << readAtomicReqExtractor.io.txSaveToCacheReq
  }

  val respGenLogic = new Area {
    val sendWriteRespGenerator = new SendWriteRespGenerator(busWidth)
    sendWriteRespGenerator.io.recvQCtrl := io.recvQCtrl
    sendWriteRespGenerator.io.rx << dmaReqLogic.dmaReqInitiator.io.txSendWrite

    val rqDmaReadRespHandler = new RqDmaReadRespHandler(busWidth)
    rqDmaReadRespHandler.io.qpAttr := io.qpAttr
    rqDmaReadRespHandler.io.recvQCtrl := io.recvQCtrl
    rqDmaReadRespHandler.io.dmaReadResp.resp << io.dma.read.resp
    readAtomicResultCache.io.queryPort4DmaReadResp << rqDmaReadRespHandler.io.readAtomicResultCacheQuery

    val readRespGenerator = new ReadRespGenerator(busWidth)
    readRespGenerator.io.qpAttr := io.qpAttr
    readRespGenerator.io.recvQCtrl := io.recvQCtrl
    readRespGenerator.io.readAtomicResultCacheRespAndDmaReadResp << rqDmaReadRespHandler.io.readAtomicResultCacheRespAndDmaReadResp

    val atomicRespGenerator = new AtomicRespGenerator(busWidth)
    atomicRespGenerator.io.qpAttr := io.qpAttr
    atomicRespGenerator.io.recvQCtrl := io.recvQCtrl
    io.dma.atomic << atomicRespGenerator.io.dma

    val rqSendWriteWorkCompGenerator = new RqSendWriteWorkCompGenerator
    rqSendWriteWorkCompGenerator.io.dmaWriteResp.resp << io.dma.sendWrite.resp
    io.sendWriteWorkComp << rqSendWriteWorkCompGenerator.io.sendWriteWorkComp
  }

  val rqOut = new RqOut(busWidth)
  rqOut.io.qpAttr := io.qpAttr
  rqOut.io.psnRangePush << reqValidateLogic.reqDmaCommHeaderExtractor.io.rqOutPsnRangeFifoPush
  rqOut.io.rxSendWriteResp << respGenLogic.sendWriteRespGenerator.io.tx
  rqOut.io.rxReadResp << respGenLogic.readRespGenerator.io.txReadResp
  rqOut.io.rxAtomicResp << respGenLogic.atomicRespGenerator.io.tx
  rqOut.io.rxRnrResp << reqValidateLogic.reqRnrCheck.io.txRnrResp
  rqOut.io.rxDupSendWriteResp << dupReqLogic.dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.txDupSendWriteResp
  rqOut.io.rxDupReadResp << dupReqLogic.dupReadRespGenerator.io.txReadResp
  rqOut.io.rxDupAtomicResp << dupReqLogic.dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.txDupAtomicResp
  rqOut.io.rxCommCheckErrResp << reqCommCheck.io.txErrResp
  rqOut.io.rxPktLenCheckErrResp << reqValidateLogic.pktLenCheck.io.txErrResp
  rqOut.io.rxAddrValidatorErrResp << reqValidateLogic.reqAddrValidator.io.txErrResp
  rqOut.io.readAtomicResultCachePop << readAtomicResultCache.io.pop
  io.psnInc.opsn := rqOut.io.opsnInc
  io.tx << rqOut.io.tx

  io.notifier.nak := reqCommCheck.io.nakNotify || reqValidateLogic.reqAddrValidator.io.nakNotify || reqValidateLogic.pktLenCheck.io.nakNotify
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
    val nakNotify = out(NakNotifier())
    val clearRnrOrNakSeq = out(RnrNakSeqClear())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val txDupReq = master(RdmaDataBus(busWidth))
    val txErrResp = master(Stream(Acknowledge()))
  }

  val checkStage = new Area {
    val inputValid = io.rx.pktFrag.valid
    val inputPktFrag = io.rx.pktFrag.fragment
    val isLastFrag = io.rx.pktFrag.last

    // PSN sequence check
    val psnCheckRslt = Bool()
    val isDupReq = Bool()
    val cmpRslt = PsnUtil.cmp(
      psnA = inputPktFrag.bth.psn,
      psnB = io.qpAttr.epsn,
      curPsn = io.qpAttr.epsn
    )
    switch(cmpRslt) {
      is(PsnCompResult.GREATER.id) {
        psnCheckRslt := False
        isDupReq := False
      }
      is(PsnCompResult.LESSER.id) {
        psnCheckRslt := inputValid
        isDupReq := inputValid
      }
      default { // PsnCompResult.EQUAL
        psnCheckRslt := inputValid
        isDupReq := False
      }
    }
    // Increase ePSN
    val isExpectedPkt = psnCheckRslt && !isDupReq
    io.epsnInc.inc := isExpectedPkt && isLastFrag
    io.epsnInc.incVal := computeEPsnInc(inputPktFrag, io.qpAttr.pmtu, busWidth)
    io.epsnInc.preReqOpCode := inputPktFrag.bth.opcode

    // OpCode sequence check
    val opSeqCheckRslt = !psnCheckRslt &&
      OpCodeSeq.checkReqSeq(io.qpAttr.rqPreReqOpCode, inputPktFrag.bth.opcode)
    // Is valid request opcode?
    val isSupportedOpCode = !psnCheckRslt &&
      OpCode.isValidCode(inputPktFrag.bth.opcode) &&
      Transports.isSupportedType(inputPktFrag.bth.transport)
    // Packet padding count check
    val padCntCheckRslt = !psnCheckRslt && reqPadCountCheck(
      inputPktFrag.bth.opcode,
      inputPktFrag.bth.padcount,
      inputPktFrag.mty,
      isLastFrag,
      busWidth
    )

    val throwCond =
      io.recvQCtrl.stateErrFlush || io.recvQCtrl.rnrFlush || io.recvQCtrl.nakSeqTrigger
//    when(isExpectedPkt) {
//      // RNR or NAK SEQ retry is cleared, do not throw due to retry
//      throwCond := io.recvQCtrl.stateErrFlush
//    }
    val isInvReq = opSeqCheckRslt || isSupportedOpCode || padCntCheckRslt
    val output = io.rx.pktFrag.throwWhen(throwCond).translateWith {
      val rslt = Fragment(RqReqCheckOutput(busWidth))
      rslt.pktFrag := io.rx.pktFrag
      rslt.checkRslt.psnCheckRslt := psnCheckRslt
      rslt.checkRslt.isDupReq := isDupReq
      rslt.checkRslt.isInvReq := isInvReq
      rslt.checkRslt.epsn := io.qpAttr.epsn
      rslt.last := io.rx.pktFrag.last
      rslt
    }

    // Clear RNR or NAK SEQ if any
    io.clearRnrOrNakSeq.pulse := (io.recvQCtrl.rnrFlush || io.recvQCtrl.nakSeqTrigger) && isExpectedPkt

    // NAK notification
    io.nakNotify.setNoErr()
    when(output.fire) {
      when(!psnCheckRslt) {
        io.nakNotify.setSeqErr()
      } elsewhen isInvReq {
        io.nakNotify.setInvReq()
      }
    }
  }

  val outputStage = new Area {
    val input = cloneOf(checkStage.output)
    input <-/< checkStage.output

    val psnCheckRslt = input.checkRslt.psnCheckRslt
    val isDupReq = input.checkRslt.isDupReq
    val isInvReq = input.checkRslt.isInvReq
    val epsn = input.checkRslt.epsn

    val rdmaAck =
      Acknowledge().setAck(AckType.NORMAL, epsn, io.qpAttr.dqpn)
    val txSel = UInt(2 bits)
    val (nakIdx, dupIdx, normalIdx) = (0, 1, 2)
    when(!psnCheckRslt) {
      txSel := nakIdx
      rdmaAck.setAck(AckType.NAK_SEQ, epsn, io.qpAttr.dqpn)
    } elsewhen isInvReq {
      txSel := nakIdx
      rdmaAck.setAck(AckType.NAK_INV, epsn, io.qpAttr.dqpn)
    } elsewhen isDupReq {
      txSel := dupIdx
    } otherwise {
      txSel := normalIdx
    }

    val threeStreams = StreamDemux(
      input.throwWhen(io.recvQCtrl.stateErrFlush || io.recvQCtrl.rnrFlush),
      select = txSel,
      portCount = 3
    )
    io.txErrResp <-/< threeStreams(nakIdx).translateWith(rdmaAck)

    val rslt = cloneOf(io.tx.pktFrag.payloadType)
    rslt.fragment := input.pktFrag
    rslt.last := input.last
    io.txDupReq.pktFrag <-/< threeStreams(dupIdx).translateWith(rslt)
    io.tx.pktFrag <-/< threeStreams(normalIdx).translateWith(rslt)
  }
}

class ReqRnrCheck(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    //val epsnInc = out(EPsnInc())
    //val nakNotify = out(NakNotifier())
    val rnrNotify = out(RnrNak())
    val recvQCtrl = in(RecvQCtrl())
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RqReqWithRecvBufBus(busWidth))
    val txRnrResp = master(Stream(Acknowledge()))
    val occupancy = in(UInt(log2Up(MAX_PENDING_READ_ATOMIC_REQ_NUM + 1) bits))
  }

  val inputValid = io.rx.pktFrag.valid
  val inputPktFrag = io.rx.pktFrag.fragment
  val isFirstFrag = io.rx.pktFrag.isFirst
  val isLastFrag = io.rx.pktFrag.isLast

  val isSendOrWriteReq =
    OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteWithImmReqPkt(inputPktFrag.bth.opcode)
  val isReadOrAtomicReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
    OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)

  // RNR check for send/write
  val needRecvBuffer = inputValid && isFirstFrag && isSendOrWriteReq
  io.recvWorkReq.ready := needRecvBuffer
  val recvBuffer = io.recvWorkReq.payload
  val recvBufValid = needRecvBuffer && io.recvWorkReq.fire

  // RNR check for read/atomic
  val readAtomicResultCacheFull = inputValid &&
    io.occupancy >= io.qpAttr.maxPendingReadAtomicReqNum && isReadOrAtomicReq
  val rnrErr =
    (needRecvBuffer && !io.recvWorkReq.valid) || readAtomicResultCacheFull
  val txSel = UInt(1 bit)
  val (errIdx, normalIdx) = (0, 1)
  when(rnrErr) {
    txSel := errIdx
  } otherwise {
    txSel := normalIdx
  }

  val rnrNak = Acknowledge().setAck(
    AckType.NAK_RNR,
    inputPktFrag.bth.psn,
    io.qpAttr.dqpn,
    io.qpAttr.rnrTimeOut
  )

  val twoStreams = StreamDemux(
    io.rx.pktFrag
      .throwWhen(io.recvQCtrl.stateErrFlush || io.recvQCtrl.rnrFlush),
    select = txSel,
    portCount = 2
  )
  io.txRnrResp <-/< twoStreams(errIdx).translateWith(rnrNak)
  // RNR notification
  io.rnrNotify.preOpCode := io.rnrNotify.findRnrPreOpCode(
    inputPktFrag.bth.opcode
  )
  io.rnrNotify.psn := inputPktFrag.bth.psn
  // TODO: verify the case that rnrErr is true but twoStreams(errIdx) not fired
  io.rnrNotify.pulse := rnrErr && twoStreams(errIdx).fire

  val txNormal = twoStreams(normalIdx)
  io.tx.reqWithRecvBuf <-/< txNormal.translateWith {
    val rslt = cloneOf(io.tx.reqWithRecvBuf.payloadType)
    rslt.pktFrag := txNormal.fragment
    rslt.recvBufValid := recvBufValid
    rslt.recvBuffer := recvBuffer
    rslt.last := txNormal.last
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

  val readAtomicResultNotFound =
    readAtomicResultCacheRespValid && !readAtomicResultCacheRespFound
  val readAtomicRequestNotDone =
    readAtomicResultCacheRespValid && readAtomicResultCacheRespValid && !readAtomicResultCacheRespData.done
  val atomicResultThrowCond =
    readAtomicResultNotFound || readAtomicRequestNotDone
  io.txDupAtomicResp <-/< threeStreams(atomicReqResultCacheIdx)
    .throwWhen(io.recvQCtrl.stateErrFlush || atomicResultThrowCond)
    .translateWith {
      val rslt = AtomicResp()
      rslt.set(
        dqpn = io.qpAttr.dqpn,
        psn = readAtomicResultCacheRespData.psnStart,
        orig = readAtomicResultCacheRespData.atomicRslt
      )
      rslt
    }
  when(readAtomicResultCacheRespValid) {
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

  io.dmaReadReq.req <-/< threeStreams(readReqResultCacheIdx)
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
}

class ReqDmaCommHeaderExtractor(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufBus(busWidth))
    // val tx = master(RdmaDataBus(busWidth))
    val txWithRecvBufAndDmaCommHeader = master(
      RqReqWithRecvBufAndDmaCommHeaderBus(busWidth)
    )
    val rqOutPsnRangeFifoPush = master(Stream(RespPsnRange()))
  }

  val inputValid = io.rx.reqWithRecvBuf.valid
  val inputPktFrag = io.rx.reqWithRecvBuf.fragment
  val isLastFrag = io.rx.reqWithRecvBuf.last
  val isFirstFrag = io.rx.reqWithRecvBuf.isFirst
  val recvBuffer = io.rx.reqWithRecvBuf.recvBuffer

  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.pktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.pktFrag.bth.opcode)
  // TODO: verify inputPktFrag.data is big endian
  val dmaCommHeader = DmaCommHeader().setDefaultVal()
  val dmaHeaderValid = False
  when(OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
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
    val reth = RETH()
    reth.assignFromBits(
      inputPktFrag.pktFrag.data(
        (busWidth.id - widthOf(BTH()) - widthOf(RETH())) until
          (busWidth.id - widthOf(BTH()))
      )
    )

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
  val numPkt =
    divideByPmtuUp(dmaCommHeader.dlen, io.qpAttr.pmtu).resize(PSN_WIDTH)

  val (txNormal, rqOutPsnRangeFifoPush) = StreamFork2(
    io.rx.reqWithRecvBuf.throwWhen(io.recvQCtrl.stateErrFlush)
  )
  io.txWithRecvBufAndDmaCommHeader.reqWithRecvBufAndDmaCommHeader <-/< txNormal
    .translateWith {
      val rslt = cloneOf(
        io.txWithRecvBufAndDmaCommHeader.reqWithRecvBufAndDmaCommHeader.payloadType
      )
      rslt.pktFrag := txNormal.pktFrag
      rslt.recvBufValid := txNormal.recvBufValid
      rslt.recvBuffer := recvBuffer
      rslt.dmaHeaderValid := dmaHeaderValid
      rslt.dmaCommHeader := dmaCommHeader
      rslt.last := txNormal.last
      rslt
    }

  val isSendOrWriteReq =
    OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode) ||
      OpCode.isWriteWithImmReqPkt(inputPktFrag.pktFrag.bth.opcode)

  io.rqOutPsnRangeFifoPush <-/< rqOutPsnRangeFifoPush
    .throwWhen(isSendOrWriteReq && !inputPktFrag.pktFrag.bth.ackreq)
    .translateWith {
      val rslt = RespPsnRange()
      rslt.opcode := inputPktFrag.pktFrag.bth.opcode
      rslt.start := inputPktFrag.pktFrag.bth.psn
      rslt.end := inputPktFrag.pktFrag.bth.psn
      when(isReadReq) {
        rslt.end := inputPktFrag.pktFrag.bth.psn + numPkt - 1
      }
      rslt
    }
}

class ReqAddrValidator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val nakNotify = out(NakNotifier())
    val addrCacheRead = master(AddrCacheReadBus())
    val txErrResp = master(Stream(Acknowledge()))
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val tx = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
  }

  val addrCacheReadReqBuilder = new Area {
    val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
    val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
    val isFirstFrag = io.rx.reqWithRecvBufAndDmaCommHeader.isFirst
    val inputHeader = io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader

    val accessKey = inputHeader.lrkey
    val va = inputHeader.va

    val accessType = Bits(ACCESS_TYPE_WIDTH bits)
    val pd = io.qpAttr.pd
    val remoteOrLocalKey = True // True: remote, False: local
    val dataLenBytes = inputHeader.dlen
    // Only send
    val invalidAddrCacheQuery =
      !io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid
    when(OpCode.isSendReqPkt(inputPktFrag.bth.opcode)) {
      accessType := AccessType.LOCAL_WRITE.id
    } elsewhen (OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)) {
      accessType := AccessType.REMOTE_WRITE.id
    } elsewhen (OpCode.isReadReqPkt(inputPktFrag.bth.opcode)) {
      accessType := AccessType.REMOTE_READ.id
    } elsewhen (OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)) {
      accessType := AccessType.REMOTE_ATOMIC.id
    } otherwise {
      invalidAddrCacheQuery := True
      accessType := 0 // 0 is invalid AccessType
      report(
        message =
          L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic",
        severity = FAILURE
      )
    }
    // TODO: verify SignalEdgeDrivenStream triggers one AddrCacheRead query
    io.addrCacheRead.req <-/< SignalEdgeDrivenStream(
      io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid && isFirstFrag
    ).throwWhen(io.recvQCtrl.stateErrFlush)
      .translateWith {
        val addrCacheReadReq = AddrCacheReadReq()
        addrCacheReadReq.sqpn := io.qpAttr.sqpn
        addrCacheReadReq.psn := inputPktFrag.bth.psn
        addrCacheReadReq.key := accessKey
        addrCacheReadReq.pd := pd
        addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = remoteOrLocalKey)
        addrCacheReadReq.accessType := accessType
        addrCacheReadReq.va := va
        addrCacheReadReq.dataLenBytes := dataLenBytes
        addrCacheReadReq
      }

    // To query AddCache, it needs several cycle delay.
    // In order to not block pipeline, use a FIFO to cache incoming data.
    val inputReqQueue = io.rx.reqWithRecvBufAndDmaCommHeader
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
    val checkPass =
      inputValid && dmaHeaderValid && addrCacheRespValid && !keyErr && !bufLenErr

    val rdmaNak = Acknowledge().setDefaultVal()
    when(bufLenErr) {
      rdmaNak
        .setAck(AckType.NAK_INV, inputPktFrag.bth.psn, io.qpAttr.dqpn)
    } elsewhen (keyErr) {
      rdmaNak
        .setAck(AckType.NAK_RMT_ACC, inputPktFrag.bth.psn, io.qpAttr.dqpn)
    }

    val joinStream =
      FragmentStreamJoinStream(
        addrCacheReadReqBuilder.inputReqQueue,
        io.addrCacheRead.resp
      )
    val txSel = UInt(1 bit)
    val (errIdx, succIdx) = (0, 1)
    when(checkPass) {
      txSel := succIdx
    } otherwise {
      txSel := errIdx
    }
    val twoStreams = StreamDemux(joinStream, select = txSel, portCount = 2)

    io.txErrResp <-/< twoStreams(errIdx)
      .throwWhen(io.recvQCtrl.stateErrFlush)
      .continueWhen(!checkPass)
      .translateWith(rdmaNak)
    io.nakNotify.setNoErr()
    when(twoStreams(errIdx).fire) {
      io.nakNotify.setInvReq()
    }

    io.tx.reqWithRecvBufAndDmaCommHeader <-/< twoStreams(succIdx)
      .throwWhen(io.recvQCtrl.stateErrFlush)
      .translateWith {
        val rslt = cloneOf(io.tx.reqWithRecvBufAndDmaCommHeader.payloadType)
        rslt.fragment := twoStreams(succIdx)._1
        rslt.dmaCommHeader.pa.removeAssignments()
        rslt.dmaCommHeader.pa := twoStreams(succIdx)._2.pa
        rslt.last := twoStreams(succIdx).isLast
        rslt
      }

  }
}

class PktLenCheck(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val nakNotify = out(NakNotifier())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val tx = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txErrResp = master(Stream(Acknowledge()))
  }

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.fragment
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last
  val isFirstFrag = io.rx.reqWithRecvBufAndDmaCommHeader.isFirst

  val pktFragLenBytes =
    CountOne(inputPktFrag.pktFrag.mty).resize(RDMA_MAX_LEN_WIDTH)
  val bthLenBytes = widthOf(BTH()) / BYTE_WIDTH
  val rethLenBytes = widthOf(RETH()) / BYTE_WIDTH
  val immDtLenBytes = widthOf(ImmDt()) / BYTE_WIDTH
  val iethLenBytes = widthOf(IETH()) / BYTE_WIDTH

  // recvBufValid and dmaHeaderValid is only valid for the first fragment of first or only request packet
  val dmaTargetLenBytesReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  when(inputPktFrag.recvBufValid) {
    dmaTargetLenBytesReg := inputPktFrag.recvBuffer.len

    assert(
      assertion = OpCode.isSendReqPkt(inputPktFrag.pktFrag.bth.opcode),
      message =
        L"it should be send requests that require receive buffer, but opcode=${inputPktFrag.pktFrag.bth.opcode}",
      severity = FAILURE
    )
  } elsewhen (inputPktFrag.dmaHeaderValid) {
    dmaTargetLenBytesReg := inputPktFrag.dmaCommHeader.dlen

    assert(
      assertion = OpCode.isWriteReqPkt(inputPktFrag.pktFrag.bth.opcode),
      message =
        L"it should be write requests here, but opcode=${inputPktFrag.pktFrag.bth.opcode}",
      severity = FAILURE
    )
  }
  when(inputValid) {
    assert(
      assertion = inputPktFrag.dmaHeaderValid === inputPktFrag.recvBufValid,
      message =
        L"dmaHeaderValid=${inputPktFrag.dmaHeaderValid} should equal recvBufValid=${inputPktFrag.recvBufValid}",
      severity = FAILURE
    )
  }

  val rethLenAdjust = U(0, log2Up(rethLenBytes) + 1 bits)
  when(
    OpCode.isWriteFirstOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode) || OpCode
      .isReadReqPkt(inputPktFrag.pktFrag.bth.opcode)
  ) {
    rethLenAdjust := rethLenBytes
  }
  val immDtLenAdjust = U(0, log2Up(immDtLenBytes) + 1 bits)
  when(OpCode.hasImmDt(inputPktFrag.pktFrag.bth.opcode)) {
    immDtLenAdjust := immDtLenBytes
  }
  val iethLenAdjust = U(0, log2Up(iethLenBytes) + 1 bits)
  when(OpCode.hasIeth(inputPktFrag.pktFrag.bth.opcode)) {
    iethLenAdjust := iethLenBytes
  }

  val pktLenCheckErr = False
  val pktLenBytesReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  val reqTotalLenBytesReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  when(inputValid) {
    when(isFirstFrag && isLastFrag) {
      pktLenBytesReg := 0
      reqTotalLenBytesReg := 0
      dmaTargetLenBytesReg := 0

      when(OpCode.isWriteOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
        pktLenCheckErr := pktFragLenBytes =/= inputPktFrag.dmaCommHeader.dlen
      } elsewhen (
        OpCode.isSendOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode)
      ) {
        pktLenCheckErr := pktFragLenBytes > inputPktFrag.recvBuffer.len
      } elsewhen (OpCode.isWriteLastReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
        pktLenCheckErr := (reqTotalLenBytesReg + pktFragLenBytes) =/= dmaTargetLenBytesReg
      } elsewhen (
        OpCode.isSendLastReqPkt(inputPktFrag.pktFrag.bth.opcode)
      ) {
        pktLenCheckErr := (reqTotalLenBytesReg + pktFragLenBytes) > dmaTargetLenBytesReg
      }
    } elsewhen (isFirstFrag && !isLastFrag) {
      when(OpCode.isFirstReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
        pktLenBytesReg := pktFragLenBytes - bthLenBytes - rethLenAdjust
        reqTotalLenBytesReg := 0
      } elsewhen (OpCode.isMidReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
        pktLenBytesReg := pktLenBytesReg + pktFragLenBytes - bthLenBytes
      } elsewhen (OpCode.isLastReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
        pktLenBytesReg := pktLenBytesReg + pktFragLenBytes - bthLenBytes - immDtLenAdjust - iethLenAdjust
      } elsewhen (OpCode.isOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
        pktLenBytesReg := pktFragLenBytes - bthLenBytes - rethLenAdjust - immDtLenAdjust - iethLenAdjust
        reqTotalLenBytesReg := 0
      }
    } elsewhen (!isFirstFrag && isLastFrag) {
      pktLenBytesReg := 0
      reqTotalLenBytesReg := reqTotalLenBytesReg + pktLenBytesReg + pktFragLenBytes

      when(OpCode.isWriteLastOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
        pktLenCheckErr := reqTotalLenBytesReg + pktFragLenBytes =/= dmaTargetLenBytesReg
        reqTotalLenBytesReg := 0
        dmaTargetLenBytesReg := 0
      } elsewhen (
        OpCode.isSendLastOrOnlyReqPkt(inputPktFrag.pktFrag.bth.opcode)
      ) {
        pktLenCheckErr := reqTotalLenBytesReg + pktFragLenBytes > dmaTargetLenBytesReg
        reqTotalLenBytesReg := 0
        dmaTargetLenBytesReg := 0
      } elsewhen (OpCode.isFirstOrMidReqPkt(inputPktFrag.pktFrag.bth.opcode)) {
        pktLenCheckErr := (pktLenBytesReg + pktFragLenBytes) =/= pmtuPktLenBytes(
          io.qpAttr.pmtu
        )
      }
    } otherwise {
      pktLenBytesReg := pktLenBytesReg + pktFragLenBytes
    }
  }

  val rdmaNak = Acknowledge()
    .setAck(AckType.NAK_RMT_ACC, inputPktFrag.pktFrag.bth.psn, io.qpAttr.dqpn)
  io.txErrResp <-/< StreamSource()
    .continueWhen(pktLenCheckErr)
    .translateWith(rdmaNak)
  io.tx.reqWithRecvBufAndDmaCommHeader <-/< io.rx.reqWithRecvBufAndDmaCommHeader
    .throwWhen(pktLenCheckErr || io.recvQCtrl.stateErrFlush)

  io.nakNotify.setNoErr()
  when(inputValid && pktLenCheckErr) {
    io.nakNotify.setInvReq()
  }
}

class RqDmaReqInitiator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txReadAtomic = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txSendWrite = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val dma = master(DmaBus(busWidth))
  }

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last
  val inputDmaHeader = io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader

  val isSendReq = OpCode.isSendReqPkt(inputPktFrag.bth.opcode)
  val isWriteReq = OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
  val txSel = Bool()
  when(isSendReq || isWriteReq) {
    txSel := True
  } elsewhen (isReadReq || isAtomicReq) {
    txSel := False
  } otherwise {
    txSel := False
    report(
      message =
        L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic requests",
      severity = FAILURE
    )
  }
  val twoStreams = StreamDemux(
    io.rx.reqWithRecvBufAndDmaCommHeader,
    select = txSel.asUInt,
    portCount = 2
  )

  val readAtomicReqStream = twoStreams(0)
  val (forkReadAtomicReqStream1, forkReadAtomicReqStream2) = StreamFork2(
    readAtomicReqStream
  )

  io.dma.rd.req <-/< forkReadAtomicReqStream1
    .throwWhen(io.recvQCtrl.stateErrFlush)
    .translateWith {
      val rslt = cloneOf(io.dma.rd.req.payloadType)
      rslt.set(
        initiator = DmaInitiator.RQ_RD,
        sqpn = io.qpAttr.sqpn,
        psnStart = inputPktFrag.bth.psn,
        addr = inputDmaHeader.pa,
        lenBytes = inputDmaHeader.dlen
      )
    }
  io.txReadAtomic.reqWithRecvBufAndDmaCommHeader <-/< forkReadAtomicReqStream2

  val dmaAddrReg = Reg(UInt(MEM_ADDR_WIDTH bits))
  when(inputValid && io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid) {
    dmaAddrReg := inputDmaHeader.pa
  }
  val sendWriteReqStream = twoStreams(1)
  val (forkSendWriteReqStream1, forkSendWriteReqStream2) = StreamFork2(
    sendWriteReqStream
  )
  io.dma.wr.req <-/< forkSendWriteReqStream1
    .throwWhen(io.recvQCtrl.stateErrFlush)
    .translateWith {
      val rslt = cloneOf(io.dma.wr.req.payloadType) //DmaWriteReq(busWidth)
      rslt.last := isLastFrag
      rslt.set(
        initiator = DmaInitiator.RQ_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        addr =
          (inputValid && io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid) ? inputDmaHeader.pa | dmaAddrReg,
        workReqId = io.rx.reqWithRecvBufAndDmaCommHeader.recvBuffer.id,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      rslt
    }
  io.txSendWrite.reqWithRecvBufAndDmaCommHeader <-/< forkSendWriteReqStream2
}

/** Used to extract full atomic request when bus width < widthOf(BTH + AtomicEth) */
/** DO NOT DELETE */
//class AtomicReqExtractor(busWidth: BusWidth) extends Component {
//  val io = new Bundle {
//    // val qpAttr = in(QpAttrData())
//    val recvQCtrl = in(RecvQCtrl())
//    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
//    // val txAtomicReq = master(Stream(AtomicReq()))
//    val txAtomicReqSaveToCache = master(Stream(ReadAtomicResultCacheData()))
//  }
//
//  val busWidthBytes = busWidth.id / BYTE_WIDTH
//  val bthWidth = widthOf(BTH())
//  val bthLenBytes = bthWidth / BYTE_WIDTH
//  val atomicEthLenBytes = widthOf(AtomicEth()) / BYTE_WIDTH
//
//  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
//  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
//  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last
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
//  io.txAtomicReqSaveToCache <-/< io.rx.reqWithRecvBufAndDmaCommHeader
//    .throwWhen(io.recvQCtrl.stateErrFlush || !isLastFrag)
//    .translateWith {
//      val rslt = cloneOf(io.txAtomicReqSaveToCache.payloadType)
//      rslt.psnStart := inputPktFrag.bth.psn
//      rslt.pktNum := 1 // Atomic response has only one packet
//      rslt.opcode := inputPktFrag.bth.opcode
//      rslt.pa := io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader.pa
//      rslt.va := atomicEth.va
//      rslt.rkey := atomicEth.rkey
//      rslt.dlen := io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader.dlen
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
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txSaveToCacheReq = master(Stream(ReadAtomicResultCacheData()))
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

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last

  val isReadReq = OpCode.isReadReqPkt(inputPktFrag.bth.opcode)
  val isAtomicReq = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
  assert(
    assertion = isReadReq ^ isReadReq,
    message =
      L"ReadAtomicReqExtractor can only handle read/atomic request, but opcode=${inputPktFrag.bth.opcode}",
    severity = FAILURE
  )

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

  io.txSaveToCacheReq <-/< io.rx.reqWithRecvBufAndDmaCommHeader
    .throwWhen(io.recvQCtrl.stateErrFlush)
    .translateWith {
      val rslt = cloneOf(io.txSaveToCacheReq.payloadType)
      rslt.psnStart := inputPktFrag.bth.psn
      rslt.pktNum := computePktNum(reth.dlen, io.qpAttr.pmtu)
      rslt.opcode := inputPktFrag.bth.opcode
      rslt.pa := io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader.pa
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
}

// TODO: prevent coalesce response to too many send/write requests
class SendWriteRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    // val nakNotify = out(NakNotifier())
    // val addrCacheReadResp = slave(AddrCacheReadRespBus())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val tx = master(Stream(Acknowledge()))
  }

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last
  val inputDmaHeader = io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader

  assert(
    assertion = OpCode.isSendReqPkt(inputPktFrag.bth.opcode) || OpCode
      .isWriteReqPkt(inputPktFrag.bth.opcode),
    message =
      L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write requests",
    severity = FAILURE
  )

  val isFirstFragReg = RegInit(True)
  when(inputValid) {
    isFirstFragReg := isLastFrag
  }
  when(
    OpCode.isSendFirstOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteFirstOrOnlyReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)
  ) {
    when(inputValid && isFirstFragReg) {
      assert(
        assertion = io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid,
        message =
          L"io.rx.dmaHeaderValid=${io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid} should be true for the first fragment of a request",
        severity = FAILURE
      )
    }
  }
  val dmaRegionSizeReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  val curSendWritePktFragLen =
    CountOne(inputPktFrag.mty).resize(RDMA_MAX_LEN_WIDTH)
  val totalDataLenReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  val lenCheckErr = False
  when(inputValid) {
    when(io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid) {
      totalDataLenReg := curSendWritePktFragLen
      dmaRegionSizeReg := io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader.dlen
      lenCheckErr := curSendWritePktFragLen > io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader.dlen
    } otherwise {
      val dataLenUpToNow = totalDataLenReg + curSendWritePktFragLen
      totalDataLenReg := dataLenUpToNow
      lenCheckErr := dataLenUpToNow > dmaRegionSizeReg
    }

    // Check write total size equals write DMA size
    when(OpCode.isWriteLastReqPkt(inputPktFrag.bth.opcode)) {
      lenCheckErr := (totalDataLenReg + curSendWritePktFragLen) =/= dmaRegionSizeReg
    } elsewhen (OpCode.isWriteOnlyReqPkt(inputPktFrag.bth.opcode)) {
      lenCheckErr := curSendWritePktFragLen =/= dmaRegionSizeReg
    }
  }

  val rdmaAck =
    Acknowledge().setAck(AckType.NORMAL, inputPktFrag.bth.psn, io.qpAttr.dqpn)
  val rdmaNak =
    Acknowledge().setAck(AckType.NAK_INV, inputPktFrag.bth.psn, io.qpAttr.dqpn)

  io.tx <-/< io.rx.reqWithRecvBufAndDmaCommHeader
    .throwWhen(
      io.recvQCtrl.stateErrFlush ||
        !lenCheckErr ||
        !inputPktFrag.bth.ackreq
    )
    .translateWith {
      lenCheckErr ? rdmaNak | rdmaAck
    }
}

/** When received a new DMA response, query ReadAtomicResultCache,
  * and then combine the DMA response and ReadAtomicResultCache query response
  * to downstream.
  */
class RqDmaReadRespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val readAtomicResultCacheQuery = master(ReadAtomicResultCacheQueryBus())
    val dmaReadResp = slave(DmaReadRespBus(busWidth))
    val readAtomicResultCacheRespAndDmaReadResp = master(
      Stream(Fragment(ReadAtomicResultCacheRespAndDmaReadResp(busWidth)))
    )
  }

  val dmaReadRespValid = io.dmaReadResp.resp.valid
  val isFirstDmaReadResp = io.dmaReadResp.resp.isFirst
  val isLastDmaReadResp = io.dmaReadResp.resp.isLast

  // Send out ReadAtomicResultCache query request
  // TODO: verify first DMA response is only one cycle, that dmaReadRespQueue has enough space
  io.readAtomicResultCacheQuery.req <-/< SignalEdgeDrivenStream(
    isFirstDmaReadResp
  ).translateWith {
    val rslt = cloneOf(io.readAtomicResultCacheQuery.req.payloadType)
    rslt.psn := io.dmaReadResp.resp.psnStart
    rslt
  }

  val dmaReadRespQueue = io.dmaReadResp.resp
    .throwWhen(io.recvQCtrl.stateErrFlush)
    .queueLowLatency(READ_ATOMIC_RESULT_CACHE_QUERY_DELAY_CYCLE)
  when(dmaReadRespValid && isFirstDmaReadResp) {
    assert(
      assertion = dmaReadRespQueue.ready,
      message =
        L"when receive first DMA response, dmaReadRespQueue must have space to accept the first DMA response, try increase READ_ATOMIC_RESULT_CACHE_QUERY_DELAY_CYCLE",
      severity = FAILURE
    )
  }

  // Join ReadAtomicResultCache query response with DmaReadResp
  val joinStream = FragmentStreamJoinStream(
    dmaReadRespQueue,
    io.readAtomicResultCacheQuery.resp
  )
  io.readAtomicResultCacheRespAndDmaReadResp <-/< joinStream.translateWith {
    val rslt = cloneOf(io.readAtomicResultCacheRespAndDmaReadResp.payloadType)
    rslt.dmaReadResp := joinStream._1
    rslt.resultCacheResp := joinStream._2
    rslt.last := joinStream.isLast
    rslt
  }
}

// TODO: handle read DMA size = 0
class ReadRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val txReadResp = master(RdmaDataBus(busWidth))
    // val txErrResp = master(Stream(Acknowledge()))
    val readAtomicResultCacheRespAndDmaReadResp = slave(
      Stream(Fragment(ReadAtomicResultCacheRespAndDmaReadResp(busWidth)))
    )
  }

  val bthHeaderLenBytes = widthOf(BTH()) / BYTE_WIDTH
  val busWidthBytes = busWidth.id / BYTE_WIDTH

  val pmtuFragNum = pmtuPktLenBytes(io.qpAttr.pmtu) >> log2Up(
    busWidth.id / BYTE_WIDTH
  )
  val segmentStream = StreamSegment(
    io.readAtomicResultCacheRespAndDmaReadResp
      .throwWhen(io.recvQCtrl.stateErrFlush),
    fragmentNum = pmtuFragNum.resize(PMTU_FRAG_NUM_WIDTH)
  )
  val input = cloneOf(io.readAtomicResultCacheRespAndDmaReadResp)
  input <-/< segmentStream

  val inputDmaDataFrag = input.dmaReadResp
  val inputResultCacheData = input.resultCacheResp.cachedData
  val isFirstDataFrag = input.isFirst
  val isLastDataFrag = input.isLast

  // Count the number of read response packet processed
  val readRespPktCntr = Counter(
    // RDMA_MAX_LEN_WIDTH >> PMTU.U256.id = RDMA_MAX_LEN_WIDTH / 256
    // This is the max number of packets of a request.
    bitCount = (RDMA_MAX_LEN_WIDTH >> PMTU.U256.id) bits,
    inc = input.lastFire
  )

  val numReadRespPkt =
    divideByPmtuUp(inputDmaDataFrag.lenBytes, io.qpAttr.pmtu)
  val lastOrOnlyReadRespPktLenBytes =
    moduloByPmtu(inputDmaDataFrag.lenBytes, io.qpAttr.pmtu)
  when(readRespPktCntr.value === numReadRespPkt - 1) {
    readRespPktCntr.clear()
  }

  val isFromFirstResp =
    inputDmaDataFrag.psnStart === inputResultCacheData.psnStart
  val curPsn = inputDmaDataFrag.psnStart + readRespPktCntr.value
  val opcode = Bits(OPCODE_WIDTH bits)
  val padcount = U(0, PADCOUNT_WIDTH bits)

  val bth = BTH().set(
    opcode = opcode,
    padcount = padcount,
    dqpn = io.qpAttr.dqpn,
    psn = curPsn
  )
  val aeth = AETH().set(AckType.NORMAL)
  val bthMty = Bits(widthOf(bth) / BYTE_WIDTH bits).setAll()
  val aethMty = Bits(widthOf(aeth) / BYTE_WIDTH bits).setAll()

  val headerBits = Bits(busWidth.id bits)
  val headerMtyBits = Bits(busWidthBytes bits)
  when(numReadRespPkt > 1) {
    when(readRespPktCntr.value === 0) {
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
    } elsewhen (readRespPktCntr.value === numReadRespPkt - 1) {
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

  val headerStream = StreamSource()
    .throwWhen(io.recvQCtrl.stateErrFlush)
    .continueWhen(isFirstDataFrag)
    .translateWith {
      val rslt = HeaderDataAndMty(BTH(), busWidth.id)
      rslt.header := bth
      rslt.data := headerBits
      rslt.mty := headerMtyBits
      rslt
    }
  val addHeaderStream = StreamAddHeader(
    input
      .throwWhen(io.recvQCtrl.stateErrFlush)
      .translateWith {
        val rslt = Fragment(DataAndMty(busWidth.id))
        rslt.data := inputDmaDataFrag.data
        rslt.mty := inputDmaDataFrag.mty
        rslt.last := isLastDataFrag
        rslt
      },
    headerStream
  )
  io.txReadResp.pktFrag <-/< addHeaderStream.translateWith {
    val rslt = Fragment(RdmaDataPacket(busWidth))
    rslt.bth := addHeaderStream.header
    rslt.data := addHeaderStream.data
    rslt.mty := addHeaderStream.mty
    rslt.last := addHeaderStream.last
    rslt
  }
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
    val tx = master(Stream(AtomicResp()))
    val dma = master(DmaBus(busWidth))
  }

  // TODO: implemntation
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
    val dmaWriteResp = slave(DmaWriteRespBus())
    val sendWriteWorkComp = master(Stream(WorkComp()))
  }

  // TODO: implement this
  io.sendWriteWorkComp <-/< io.dmaWriteResp.resp
    .translateWith(WorkComp().setDefaultVal())
}

// For duplicated requests, also return ACK in PSN order
// TODO: after RNR and NAK SEQ returned, no other nested NAK send
class RqOut(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val opsnInc = out(PsnInc())
    val psnRangePush = slave(Stream(RespPsnRange()))
    val readAtomicResultCachePop = slave(Stream(ReadAtomicResultCacheData()))
    val rxSendWriteResp = slave(Stream(Acknowledge()))
    val rxReadResp = slave(RdmaDataBus(busWidth))
    val rxAtomicResp = slave(Stream(AtomicResp()))
    val rxRnrResp = slave(Stream(Acknowledge()))
    val rxDupSendWriteResp = slave(Stream(Acknowledge()))
    val rxDupReadResp = slave(RdmaDataBus(busWidth))
    val rxDupAtomicResp = slave(Stream(AtomicResp()))
    val rxCommCheckErrResp = slave(Stream(Acknowledge()))
    val rxPktLenCheckErrResp = slave(Stream(Acknowledge()))
    val rxAddrValidatorErrResp = slave(Stream(Acknowledge()))
    val tx = master(RdmaDataBus(busWidth))
  }

  // TODO: implementation
  io.opsnInc.inc := False
  io.opsnInc.incVal := 0
  io.tx.pktFrag <-/< StreamSource().translateWith(
    RdmaDataBus(busWidth).setDefaultVal()
  )

  // TODO: check pending request number defined in QpAttrData
  val psnOutRangeFifo =
    StreamFifoLowLatency(RespPsnRange(), depth = PENDING_REQ_NUM)
  psnOutRangeFifo.io.push << io.psnRangePush

  StreamSink(RespPsnRange()) << psnOutRangeFifo.io.pop
  StreamSink(ReadAtomicResultCacheData()) << io.readAtomicResultCachePop
  StreamSink(Acknowledge()) << io.rxSendWriteResp
  StreamSink(NoData) << io.rxReadResp.pktFrag.translateWith(NoData)
  StreamSink(AtomicResp()) << io.rxAtomicResp
  StreamSink(Acknowledge()) << io.rxRnrResp
  StreamSink(Acknowledge()) << io.rxDupSendWriteResp
  StreamSink(NoData) << io.rxDupReadResp.pktFrag.translateWith(NoData)
  StreamSink(AtomicResp()) << io.rxDupAtomicResp
  StreamSink(Acknowledge()) << io.rxCommCheckErrResp
  StreamSink(Acknowledge()) << io.rxPktLenCheckErrResp
  StreamSink(Acknowledge()) << io.rxAddrValidatorErrResp
}
