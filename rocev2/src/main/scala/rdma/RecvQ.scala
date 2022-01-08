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

class RecvQ(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val psnInc = out(RqPsnInc())
    val nakNotifier = out(RqNakNotifier())
    val rnrNakSeqClearNotifier = out(RnrNakSeqClear())
    val recvQCtrl = in(RecvQCtrl())
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val addrCacheRead = master(AddrCacheReadBus())
    val dma = master(RqDmaBus(busWidth))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val sendWriteWorkComp = master(Stream(WorkComp()))
  }

  val readAtomicResultCache = new ReadAtomicResultCache(
    MAX_PENDING_READ_ATOMIC_REQ_NUM
  )
  readAtomicResultCache.io.flush := io.recvQCtrl.flush

  val reqCommCheck = new ReqCommCheck(busWidth)
  reqCommCheck.io.qpAttr := io.qpAttr
  reqCommCheck.io.recvQCtrl := io.recvQCtrl
  reqCommCheck.io.rx << io.rx
  io.psnInc.epsn := reqCommCheck.io.epsnInc
  io.rnrNakSeqClearNotifier := reqCommCheck.io.rnrNakSeqClearNotifier
  io.nakNotifier.reqCheck := reqCommCheck.io.nakNotify

  val dupReqLogic = new Area {
    val dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder =
      new DupSendWriteReqHandlerAndDupReadAtomicResultCacheQueryBuilder(
        busWidth
      )
    dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.qpAttr := io.qpAttr
    dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.recvQCtrl := io.recvQCtrl
    dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.rx << reqCommCheck.io.txDupReq
    readAtomicResultCache.io.queryPort4DupReq.req << dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.readAtomicResultCacheQueryReq.req

    val dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator =
      new DupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator
    dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.qpAttr := io.qpAttr
    dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.recvQCtrl := io.recvQCtrl
    dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.readAtomicResultCacheQueryResp.resp << readAtomicResultCache.io.queryPort4DupReq.resp
    io.dma.dupRead.req << dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.txDmaReadReq.req

    val dupReadRespGenerator = new ReadRespGenerator(busWidth)
    dupReadRespGenerator.io.qpAttr := io.qpAttr
    dupReadRespGenerator.io.recvQCtrl := io.recvQCtrl
    dupReadRespGenerator.io.dmaReadResp.resp << io.dma.dupRead.resp
  }

  val reqValidateLogic = new Area {
    val reqRnrCheck = new ReqRnrCheck(busWidth)
    reqRnrCheck.io.qpAttr := io.qpAttr
    reqRnrCheck.io.recvQCtrl := io.recvQCtrl
    reqRnrCheck.io.recvWorkReq << io.recvWorkReq
    reqRnrCheck.io.rx << reqCommCheck.io.tx
    reqRnrCheck.io.occupancy := readAtomicResultCache.io.occupancy
    io.nakNotifier.rnr := reqRnrCheck.io.rnrNotify

    val reqDmaCommHeaderExtractor = new ReqDmaCommHeaderExtractor(busWidth)
    reqDmaCommHeaderExtractor.io.recvQCtrl := io.recvQCtrl
    reqDmaCommHeaderExtractor.io.rx << reqRnrCheck.io.tx

    val reqAddrCacheQueryBuilder = new ReqAddrCacheQueryBuilder(busWidth)
    reqAddrCacheQueryBuilder.io.qpAttr := io.qpAttr
    reqAddrCacheQueryBuilder.io.recvQCtrl := io.recvQCtrl
    reqAddrCacheQueryBuilder.io.rx <<
      reqDmaCommHeaderExtractor.io.txWithRecvBufAndDmaCommHeader
    io.addrCacheRead.req << reqAddrCacheQueryBuilder.io.addrCacheReadReq.req

    val pktLenCheck = new PktLenCheck(busWidth)
    pktLenCheck.io.qpAttr := io.qpAttr
    pktLenCheck.io.recvQCtrl := io.recvQCtrl
    pktLenCheck.io.rx << reqAddrCacheQueryBuilder.io.tx
    io.nakNotifier.pktLen := pktLenCheck.io.nakNotify

    val reqAddrValidator = new ReqAddrValidator(busWidth)
    reqAddrValidator.io.recvQCtrl := io.recvQCtrl
    reqAddrValidator.io.rx << pktLenCheck.io.tx
    reqAddrValidator.io.addrCacheReadResp.resp << io.addrCacheRead.resp
    io.nakNotifier.addr := reqAddrValidator.io.nakNotify
  }

  val dmaReqLogic = new Area {
    val dmaReqInitiator = new DmaReqInitiator(busWidth)
    dmaReqInitiator.io.qpAttr := io.qpAttr
    dmaReqInitiator.io.recvQCtrl := io.recvQCtrl
    dmaReqInitiator.io.rx << reqValidateLogic.reqAddrValidator.io.tx
    io.dma.read.req << dmaReqInitiator.io.txDmaReadReq.req
    io.dma.sendWrite.req << dmaReqInitiator.io.txDmaWriteReq.req

    val readAtomicReqExtractor = new ReadAtomicReqExtractor(busWidth)
    readAtomicReqExtractor.io.recvQCtrl := io.recvQCtrl
    readAtomicReqExtractor.io.rx << dmaReqInitiator.io.txReadAtomic
    readAtomicResultCache.io.push << readAtomicReqExtractor.io.txSaveToCacheReq
  }

  val respGenLogic = new Area {
    val sendWriteRespGenerator = new SendWriteRespGenerator(busWidth)
    sendWriteRespGenerator.io.recvQCtrl := io.recvQCtrl
    sendWriteRespGenerator.io.rx << dmaReqLogic.dmaReqInitiator.io.txSendWrite

    val readRespGenerator = new ReadRespGenerator(busWidth)
    readRespGenerator.io.qpAttr := io.qpAttr
    readRespGenerator.io.recvQCtrl := io.recvQCtrl
    readRespGenerator.io.dmaReadResp.resp << io.dma.read.resp

    val atomicRespGenerator = new AtomicRespGenerator(busWidth)
    atomicRespGenerator.io.qpAttr := io.qpAttr
    atomicRespGenerator.io.recvQCtrl := io.recvQCtrl
    io.dma.atomic << atomicRespGenerator.io.dma

    val rqSendWriteWorkCompGenerator = new RqSendWriteWorkCompGenerator
    rqSendWriteWorkCompGenerator.io.dmaWriteResp.resp << io.dma.sendWrite.resp
    io.sendWriteWorkComp << rqSendWriteWorkCompGenerator.io.sendWriteWorkComp
  }

  val seqOut = new SeqOut(busWidth)
  seqOut.io.qpAttr := io.qpAttr
  seqOut.io.psnRangePush << reqValidateLogic.reqDmaCommHeaderExtractor.io.seqOutPsnRangeFifoPush
  seqOut.io.rxSendWriteResp << respGenLogic.sendWriteRespGenerator.io.tx
  seqOut.io.rxReadResp << respGenLogic.readRespGenerator.io.tx
  seqOut.io.rxAtomicResp << respGenLogic.atomicRespGenerator.io.tx
  seqOut.io.rxRnrResp << reqValidateLogic.reqRnrCheck.io.txRnrResp
  seqOut.io.rxDupSendWriteResp << dupReqLogic.dupSendWriteReqHandlerAndDupResultAtomicResultCacheQueryBuilder.io.txDupSendWriteResp
  seqOut.io.rxDupReadResp << dupReqLogic.dupReadRespGenerator.io.tx
  seqOut.io.rxDupAtomicResp << dupReqLogic.dupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator.io.txDupAtomicResp
  seqOut.io.rxCommCheckErrResp << reqCommCheck.io.txErrResp
  seqOut.io.rxPktLenCheckErrResp << reqValidateLogic.pktLenCheck.io.txErrResp
  seqOut.io.rxAddrValidatorErrResp << reqValidateLogic.reqAddrValidator.io.txErrResp
  seqOut.io.readAtomicResultCachePop << readAtomicResultCache.io.pop
  io.psnInc.opsn := seqOut.io.opsnInc
  io.tx << seqOut.io.tx
}

class ReqCommCheck(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val epsnInc = out(EPsnInc())
    val nakNotify = out(NakErr())
    val rnrNakSeqClearNotifier = out(RnrNakSeqClear())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val txDupReq = master(RdmaDataBus(busWidth))
    val txErrResp = master(Stream(Acknowlege()))
  }

  val checkStage = new Area {
    val inputValid = io.rx.pktFrag.valid
    val inputPktFrag = io.rx.pktFrag.fragment
    val isLastFrag = io.rx.pktFrag.last

    // PSN sequence check
    val psnCheckRslt = Bool()
    val isDupReq = Bool()
    val cmpRslt = psnComp(
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
    // NAK notification
    io.nakNotify.setNoErr()
    io.rnrNakSeqClearNotifier.pulse := False
    val isInvReq = opSeqCheckRslt || isSupportedOpCode || padCntCheckRslt
    when(inputValid) {
      when(!psnCheckRslt) {
        io.nakNotify.setSeqErr()
      } elsewhen (isInvReq) {
        io.nakNotify.setInvReq()
      } otherwise {
        // Clear RNR or NAK SEQ if any
        io.rnrNakSeqClearNotifier.pulse := (io.recvQCtrl.rnrFlush || io.recvQCtrl.nakSeqTrigger) && psnCheckRslt
      }
    }

    // val throwCond = io.recvQCtrl.flush // TODO: check why it does not work
    val throwCond =
      io.recvQCtrl.stateErrFlush || io.recvQCtrl.rnrFlush || io.recvQCtrl.nakSeqTrigger
    // when(isExpectedPkt && io.recvQCtrl.rnrFlush && io.recvQCtrl.rnrTimeOut) {
    when(isExpectedPkt) {
      // RNR is cleared, do not throw due to RNR
      throwCond := io.recvQCtrl.stateErrFlush
    }
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
  }

  val outputStage = new Area {
    val input = cloneOf(checkStage.output)
    input <-/< checkStage.output

    val psnCheckRslt = input.checkRslt.psnCheckRslt
    val isDupReq = input.checkRslt.isDupReq
    val isInvReq = input.checkRslt.isInvReq
    val epsn = input.checkRslt.epsn

    val rdmaAck =
      Acknowlege().setAck(AckType.NORMAL, epsn, io.qpAttr.dqpn)
    val txSel = UInt(2 bits)
    when(!psnCheckRslt) {
      txSel := 0
      rdmaAck.setAck(AckType.NAK_SEQ, epsn, io.qpAttr.dqpn)
    } elsewhen (isInvReq) {
      txSel := 0
      rdmaAck.setAck(AckType.NAK_INV, epsn, io.qpAttr.dqpn)
    } elsewhen (isDupReq) {
      txSel := 1
    } otherwise {
      txSel := 2
    }

    val multiStreams = StreamDemux(
      input.throwWhen(io.recvQCtrl.flush),
      select = txSel,
      portCount = 3
    )
    io.txErrResp <-/< multiStreams(0).translateWith(rdmaAck)

    val rslt = Fragment(RdmaDataPacket(busWidth))
    rslt.fragment := input.pktFrag
    rslt.last := input.last
    io.txDupReq.pktFrag <-/< multiStreams(1).translateWith(rslt)
    io.tx.pktFrag <-/< multiStreams(2).translateWith(rslt)
  }
}

class ReqRnrCheck(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    //val epsnInc = out(EPsnInc())
    //val nakNotify = out(NakErr())
    val rnrNotify = out(RnrNak())
    val recvQCtrl = in(RecvQCtrl())
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RqReqWithRecvBufBus(busWidth))
    val txRnrResp = master(Stream(Acknowlege()))
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

  val rnrNak = Acknowlege().setAck(
    AckType.NAK_RNR,
    inputPktFrag.bth.psn,
    io.qpAttr.dqpn,
    io.qpAttr.minRnrTimer
  )

  val twoStreams = StreamDemux(
    io.rx.pktFrag.throwWhen(io.recvQCtrl.flush),
    select = rnrErr.asUInt,
    portCount = 2
  )
  io.txRnrResp <-/< twoStreams(1).translateWith(rnrNak)
  // RNR notification
  io.rnrNotify.preOpCode := io.rnrNotify.findRnrPreOpCode(
    inputPktFrag.bth.opcode
  )
  io.rnrNotify.psn := inputPktFrag.bth.psn
  // TODO: verify the case that rnrErr is true but twoStreams(1) not fired
  io.rnrNotify.pulse := rnrErr && twoStreams(1).fire

  val txNormal = twoStreams(0)
  io.tx.reqWithRecvBuf <-/< txNormal.translateWith {
    val rslt = cloneOf(io.tx.reqWithRecvBuf.payload)
    rslt.pktFrag := txNormal.fragment
    rslt.recvBufValid := recvBufValid
    rslt.recvBuffer := recvBuffer
    rslt.last := txNormal.last
    rslt
  }
}

class DupSendWriteReqHandlerAndDupReadAtomicResultCacheQueryBuilder(
    busWidth: BusWidth
) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val readAtomicResultCacheQueryReq = master(
      ReadAtomicResultCacheQueryReqBus()
    )
    val rx = slave(RdmaDataBus(busWidth))
    val txDupSendWriteResp = master(Stream(Acknowlege()))
    val txDupAtomicResp = master(Stream(AtomicResp()))
    val txDmaReadReq = master(DmaReadReqBus())
  }

  val dupSendWriteRespHandlerAndReadAtomicResultCacheQueryBuilder = new Area {
    val inputPktFrag = io.rx.pktFrag.fragment

    val rdmaAck = Acknowlege()
      .setAck(
        AckType.NORMAL,
        io.qpAttr.epsn, // TODO: verify the ePSN is confirmed, will not retry later
        io.qpAttr.dqpn
      )

    val txSel = Bool()
    when(
      OpCode.isSendReqPkt(inputPktFrag.bth.opcode) ||
        OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
    ) {
      txSel := True
    } elsewhen (OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)) {
      txSel := False
    } otherwise {
      txSel := False
      assert(
        assertion = False,
        message =
          L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic requests",
        severity = FAILURE
      )
    }
    val twoStreams = StreamDemux(
      io.rx.pktFrag.throwWhen(io.recvQCtrl.flush),
      select = txSel.asUInt,
      portCount = 2
    )

    io.txDupSendWriteResp <-/< twoStreams(1).translateWith(rdmaAck)
    io.readAtomicResultCacheQueryReq.req <-/< twoStreams(0).translateWith {
      val rslt = ReadAtomicResultCacheQueryReq()
      rslt.psn := inputPktFrag.bth.psn
      rslt
    }
  }
}

// TODO: handle retried read from middle PSN not from the original PSN
class DupReadAtomicResultCacheRespHandlerAndDupReadDmaInitiator
    extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val readAtomicResultCacheQueryResp = slave(
      ReadAtomicResultCacheQueryRespBus()
    )
    val txDupAtomicResp = master(Stream(AtomicResp()))
    val txDmaReadReq = master(DmaReadReqBus())
  }
  val readAtomicResultCacheQueryRespHandler = new Area {
    val readAtomicResultCacheQueryRespValid =
      io.readAtomicResultCacheQueryResp.resp.valid
    val readAtomicResultCacheQueryRespData =
      io.readAtomicResultCacheQueryResp.resp.cachedData
    val readAtomicResultCacheQueryRespFound =
      io.readAtomicResultCacheQueryResp.resp.found

    val txSel = Bool()
    when(OpCode.isReadReqPkt(readAtomicResultCacheQueryRespData.opcode)) {
      txSel := True
    } elsewhen (OpCode
      .isAtomicReqPkt(readAtomicResultCacheQueryRespData.opcode)) {
      txSel := False
    } otherwise {
      txSel := False
      assert(
        assertion = False,
        message =
          L"invalid opcode=${readAtomicResultCacheQueryRespData.opcode}, should be read/atomic requests",
        severity = FAILURE
      )
    }
    val twoStreams = StreamDemux(
      io.readAtomicResultCacheQueryResp.resp.throwWhen(io.recvQCtrl.flush),
      select = txSel.asUInt,
      portCount = 2
    )

    val atomicResultNotFound =
      readAtomicResultCacheQueryRespValid && !readAtomicResultCacheQueryRespFound
    val atomicRequestNotDone =
      readAtomicResultCacheQueryRespValid && readAtomicResultCacheQueryRespValid && !readAtomicResultCacheQueryRespData.done
    val atomicResultThrowCond = atomicResultNotFound || atomicRequestNotDone
    io.txDupAtomicResp <-/< twoStreams(0)
      .throwWhen(io.recvQCtrl.flush || atomicResultThrowCond)
      .translateWith {
        val rslt = AtomicResp()
        rslt.set(
          dqpn = io.qpAttr.dqpn,
          psn = readAtomicResultCacheQueryRespData.psn,
          orig = readAtomicResultCacheQueryRespData.atomicRslt
        )
        rslt
      }
    assert(
      assertion = atomicResultNotFound,
      message =
        L"""duplicated atomic request with PSN=${io.readAtomicResultCacheQueryResp.resp.query.psn} not found,
           readAtomicResultCacheQueryRespValid=${readAtomicResultCacheQueryRespValid},
           but readAtomicResultCacheQueryRespFound=${readAtomicResultCacheQueryRespFound}""",
      severity = FAILURE
    )
    assert(
      assertion = atomicRequestNotDone,
      message =
        L"""duplicated atomic request with PSN=${io.readAtomicResultCacheQueryResp.resp.query.psn} not done yet,
           readAtomicResultCacheQueryRespValid=${readAtomicResultCacheQueryRespValid},
           readAtomicResultCacheQueryRespFound=${readAtomicResultCacheQueryRespFound},
           but readAtomicResultCacheQueryRespData=${readAtomicResultCacheQueryRespData.done}
           """,
      severity = FAILURE
    )

    // TODO: send out read DMA request
    io.txDmaReadReq.req <-/< twoStreams(1)
      .throwWhen(io.recvQCtrl.flush)
      .translateWith {
        val rslt = cloneOf(io.txDmaReadReq.req.payload)
        rslt.opcode := readAtomicResultCacheQueryRespData.opcode
        rslt.sqpn := io.qpAttr.sqpn
        rslt.psn := readAtomicResultCacheQueryRespData.psn
        rslt.addr := readAtomicResultCacheQueryRespData.pa
        rslt.len := readAtomicResultCacheQueryRespData.dlen
        rslt
      }
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
    val seqOutPsnRangeFifoPush = master(Stream(RespPsnRange()))
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

  val (txNormal, seqOutPsnRangeFifoPush) = StreamFork2(
    io.rx.reqWithRecvBuf.throwWhen(io.recvQCtrl.flush)
  )
  io.txWithRecvBufAndDmaCommHeader.reqWithRecvBufAndDmaCommHeader <-/< txNormal
    .translateWith {
      val rslt = cloneOf(
        io.txWithRecvBufAndDmaCommHeader.reqWithRecvBufAndDmaCommHeader.payload
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

  io.seqOutPsnRangeFifoPush <-/< seqOutPsnRangeFifoPush
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

class ReqAddrCacheQueryBuilder(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val addrCacheReadReq = master(AddrCacheReadReqBus())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val tx = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
  }

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last
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
    assert(
      assertion = False,
      message =
        L"invalid opcode=${inputPktFrag.bth.opcode}, should be send/write/read/atomic",
      severity = FAILURE
    )
  }

  io.addrCacheReadReq.req <-/< StreamSource()
    .throwWhen(invalidAddrCacheQuery || io.recvQCtrl.flush)
    .translateWith {
      val addrCacheReadReq = AddrCacheReadReq()
      addrCacheReadReq.key := accessKey
      addrCacheReadReq.pd := pd
      addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = remoteOrLocalKey)
      addrCacheReadReq.accessType := accessType
      addrCacheReadReq.va := va
      addrCacheReadReq.dataLenBytes := dataLenBytes
      addrCacheReadReq
    }
  io.tx.reqWithRecvBufAndDmaCommHeader <-/< io.rx.reqWithRecvBufAndDmaCommHeader
    .throwWhen(io.recvQCtrl.flush)
}

class PktLenCheck(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val nakNotify = out(NakErr())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val tx = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txErrResp = master(Stream(Acknowlege()))
  }

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.fragment
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last
  val isFirstFrag = io.rx.reqWithRecvBufAndDmaCommHeader.isFirst

  val pktFragLenBytes =
    CountOne(inputPktFrag.pktFrag.mty).resize(RDMA_MAX_LEN_WIDTH)
  val bthLenBytes = widthOf(BTH()) / 8
  val rethLenBytes = widthOf(RETH()) / 8
  val immDtLenBytes = widthOf(ImmDt()) / 8
  val iethLenBytes = widthOf(IETH()) / 8

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
  assert(
    assertion = inputPktFrag.dmaHeaderValid === inputPktFrag.recvBufValid,
    message =
      L"dmaHeaderValid=${inputPktFrag.dmaHeaderValid} should equal recvBufValid=${inputPktFrag.recvBufValid}",
    severity = FAILURE
  )

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

  val rdmaNak = Acknowlege()
    .setAck(AckType.NAK_RMT_ACC, inputPktFrag.pktFrag.bth.psn, io.qpAttr.dqpn)
  io.txErrResp <-/< StreamSource()
    .continueWhen(pktLenCheckErr)
    .translateWith(rdmaNak)
  io.tx.reqWithRecvBufAndDmaCommHeader <-/< io.rx.reqWithRecvBufAndDmaCommHeader
    .throwWhen(pktLenCheckErr || io.recvQCtrl.flush)

  io.nakNotify.setNoErr()
  when(inputValid && pktLenCheckErr) {
    io.nakNotify.setInvReq()
  }
}

class ReqAddrValidator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val nakNotify = out(NakErr())
    val addrCacheReadResp = slave(AddrCacheReadRespBus())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val tx = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txErrResp = master(Stream(Acknowlege()))
  }

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
  val isFirstFrag = io.rx.reqWithRecvBufAndDmaCommHeader.isFirst
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.isLast
  val inputHeader = io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader

  val accessKey = inputHeader.lrkey
  val va = inputHeader.va

  val sizeValid = io.addrCacheReadResp.resp.sizeValid
  val keyValid = io.addrCacheReadResp.resp.keyValid
  when(io.addrCacheReadResp.resp.fire) {
    assert(
      assertion = va === io.addrCacheReadResp.resp.va,
      message =
        L"addrCacheReadResp.resp has VA=${io.addrCacheReadResp.resp.va} not match input VA=${va}",
      severity = FAILURE
    )
  }

  val bufLenErr =
    inputValid && io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid && io.addrCacheReadResp.resp.valid && !sizeValid
  val keyErr =
    inputValid && io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid && io.addrCacheReadResp.resp.valid && !keyValid
  val checkPass =
    inputValid && io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid && io.addrCacheReadResp.resp.valid && !keyErr && !bufLenErr

  val rdmaNak = Acknowlege().setDefaultVal()
  when(bufLenErr) {
    rdmaNak
      .setAck(AckType.NAK_INV, inputPktFrag.bth.psn, io.qpAttr.dqpn)
  } elsewhen (keyErr) {
    rdmaNak
      .setAck(AckType.NAK_RMT_ACC, inputPktFrag.bth.psn, io.qpAttr.dqpn)
  }

  // TODO: verify io.addrCacheReadResp.resp fire within
  // TODO: the first fragment of io.rx if io.rx.dmaHeaderValid
  io.addrCacheReadResp.resp.ready := inputValid && io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid
  io.txErrResp <-/< StreamSource()
    .throwWhen(io.recvQCtrl.flush)
    .continueWhen(!checkPass)
    .translateWith(rdmaNak)

  // If io.rx.dmaHeaderValid is true, wait for checkPass
  val continueCond =
    io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid ? checkPass | True
  io.tx.reqWithRecvBufAndDmaCommHeader <-/< io.rx.reqWithRecvBufAndDmaCommHeader
    .throwWhen(io.recvQCtrl.flush)
    .continueWhen(continueCond)
    .translateWith {
      val rslt = cloneOf(io.rx.reqWithRecvBufAndDmaCommHeader.payload)
      rslt := io.rx.reqWithRecvBufAndDmaCommHeader
      when(inputValid && isFirstFrag) {
        rslt.dmaCommHeader.pa := io.addrCacheReadResp.resp.pa
      }
      rslt
    }

  io.nakNotify.setNoErr()
  when(inputValid && !checkPass) {
    io.nakNotify.setInvReq()
  }
}

class DmaReqInitiator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txReadAtomic = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txSendWrite = master(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val txDmaReadReq = master(DmaReadReqBus())
    val txDmaWriteReq = master(DmaWriteReqBus(busWidth))
  }

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last
  val inputDmaHeader = io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader

  val txSel = Bool()
  when(
    OpCode.isSendReqPkt(inputPktFrag.bth.opcode) ||
      OpCode.isWriteReqPkt(inputPktFrag.bth.opcode)
  ) {
    txSel := True
  } elsewhen (OpCode.isReadReqPkt(inputPktFrag.bth.opcode) ||
    OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)) {
    txSel := False
  } otherwise {
    txSel := False
    assert(
      assertion = False,
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
  io.txDmaReadReq.req <-/< forkReadAtomicReqStream1
    .throwWhen(io.recvQCtrl.flush)
    .translateWith {
      val rslt = cloneOf(io.txDmaReadReq.req.payload)
      rslt.opcode := inputPktFrag.bth.opcode
      rslt.sqpn := io.qpAttr.sqpn
      rslt.psn := inputPktFrag.bth.psn
      rslt.addr := inputDmaHeader.pa
      rslt.len := inputDmaHeader.dlen
      rslt
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
  io.txDmaWriteReq.req <-/< forkSendWriteReqStream1
    .throwWhen(io.recvQCtrl.flush)
    .translateWith {
      val rslt = cloneOf(io.txDmaWriteReq.req) //DmaWriteReq(busWidth)
      rslt.opcode := inputPktFrag.bth.opcode
      rslt.sqpn := io.qpAttr.sqpn
      rslt.psn := inputPktFrag.bth.psn
      rslt.addr := (inputValid && io.rx.reqWithRecvBufAndDmaCommHeader.dmaHeaderValid) ? inputDmaHeader.pa | dmaAddrReg
      rslt.wrId := io.rx.reqWithRecvBufAndDmaCommHeader.recvBuffer.id
      rslt.wrIdValid := io.rx.reqWithRecvBufAndDmaCommHeader.recvBufValid
      rslt.data := inputPktFrag.data
      rslt.mty := inputPktFrag.mty
      rslt.last := isLastFrag
      rslt
    }
  io.txSendWrite.reqWithRecvBufAndDmaCommHeader <-/< forkSendWriteReqStream2
}

/** Used to extract full atomic request when bus width < widthOf(BTH + AtomicEth) */
class AtomicReqExtractor(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    // val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    // val txAtomicReq = master(Stream(AtomicReq()))
    val txAtomicReqSaveToCache = master(Stream(ReadAtomicResultCacheData()))
  }

  val busWidthBytes = busWidth.id / 8
  val bthWidth = widthOf(BTH())
  val bthLenBytes = bthWidth / 8
  val atomicEthLenBytes = widthOf(AtomicEth()) / 8

  val inputValid = io.rx.reqWithRecvBufAndDmaCommHeader.valid
  val inputPktFrag = io.rx.reqWithRecvBufAndDmaCommHeader.pktFrag
  val isLastFrag = io.rx.reqWithRecvBufAndDmaCommHeader.last

  require(bthWidth == 12, s"bthWidth=${bthWidth} should be 12 bytes")
  // For easiness, bus width should be larger than BTH width,
  // Therefore, it's convenient to read BTH in the first packet
  require(
    busWidthBytes >= bthLenBytes,
    s"busWidthBytes=${busWidthBytes} should be larger than BTH width=${bthLenBytes} bytes"
  )
  require(
    atomicEthLenBytes + bthLenBytes > busWidthBytes,
    s"must have AtomicEth width=${atomicEthLenBytes} bytes + BTH width=${bthLenBytes} bytes > busWidthBytes=${busWidthBytes} bytes"
  )

  assert(
    assertion = OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode),
    message =
      L"AtomicReqExtractor can only handle atomic request, but opcode=${inputPktFrag.bth.opcode}",
    severity = FAILURE
  )

  // Handle bus width smaller than atomic request length
  val atomicReqLen = widthOf(BTH()) + widthOf(AtomicEth())
  // Atomic request length: BTH 12 bytes + AtomicEth 28 bytes = 40 bytes
  // For 256 bits (32 bytes) bus width, residue = 8 bytes, left over of AtomicEth = 20 bytes
  val residueLen = atomicReqLen - busWidth.id
  val leftOverLen = if (atomicReqLen > busWidth.id) {
    widthOf(AtomicEth()) - residueLen
  } else {
    0 // Bus width larger than atomicReqLen, no left over bits
  }

  val atomicEth = AtomicEth()
  if (residueLen > 0) { // Bus width less than atomicReqLen
    if (leftOverLen > 0) { // AtomicEth spans two consecutive fragments
      val isFirstFragReg = RegInit(True)
      when(inputValid) {
        isFirstFragReg := isLastFrag
      }

      val atomicLeftOverReg = Reg(Bits(leftOverLen bits))
      when(inputValid && isFirstFragReg) {
        atomicLeftOverReg := inputPktFrag.data(0, leftOverLen bits)
      }

      // TODO: verify inputPktFrag.data is big endian
      atomicEth.assignFromBits(
        atomicLeftOverReg ## inputPktFrag
          .data((busWidth.id - residueLen) until busWidth.id)
      )
    } else { // AtomicEth is within the last fragment, two fragments total
      // TODO: verify inputPktFrag.data is big endian
      atomicEth.assignFromBits(
        inputPktFrag
          .data((busWidth.id - widthOf(AtomicEth())) until busWidth.id)
      )

    }
  } else { // Bus width greater than atomicReqLen
    // TODO: verify inputPktFrag.data is big endian
    atomicEth.assignFromBits(
      inputPktFrag.data(
        (busWidth.id - widthOf(BTH()) - widthOf(AtomicEth())) until
          (busWidth.id - widthOf(BTH()))
      )
    )
  }

  io.txAtomicReqSaveToCache <-/< io.rx.reqWithRecvBufAndDmaCommHeader
    .throwWhen(io.recvQCtrl.flush || !isLastFrag)
    .translateWith {
      val rslt = cloneOf(io.txAtomicReqSaveToCache.payload)
//      rslt.bth.set(
//        opcode = inputPktFrag.bth.opcode,
//        psn = inputPktFrag.bth.psn,
//        dqpn = io.qpAttr.dqpn
//      )
//      rslt.atomicEth := atomicEth
//      rslt
      rslt.psn := inputPktFrag.bth.psn
      rslt.opcode := inputPktFrag.bth.opcode
      rslt.pa := io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader.pa
      rslt.va := atomicEth.va
      rslt.rkey := atomicEth.rkey
      rslt.dlen := io.rx.reqWithRecvBufAndDmaCommHeader.dmaCommHeader.dlen
      rslt.swap := atomicEth.swap
      rslt.comp := atomicEth.comp
      rslt.atomicRslt := 0
      rslt.done := False
      rslt
    }
}

class ReadAtomicReqExtractor(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val recvQCtrl = in(RecvQCtrl())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    // val rx = slave(RdmaDataBus(busWidth))
    // val tx = master(RdmaDataBus(busWidth))
    val txSaveToCacheReq = master(Stream(ReadAtomicResultCacheData()))
  }

  val busWidthBytes = busWidth.id / 8
  val bthLenBytes = widthOf(BTH()) / 8
  val rethLenBytes = widthOf(RETH()) / 8
  val atomicEthLenBytes = widthOf(AtomicEth()) / 8

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
    .throwWhen(io.recvQCtrl.flush)
    .translateWith {
      val rslt = cloneOf(io.txSaveToCacheReq.payload)
      rslt.psn := inputPktFrag.bth.psn
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

//class ReadAtomicReqSaveToCacheHandler(busWidth: BusWidth) extends Component {
//  val io = new Bundle {
//    val recvQCtrl = in(RecvQCtrl())
//    val rxReadAtomic = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
//    // val rxAtomicReq = slave(Stream(ReadAtomicResultCacheData()))
//    // val rxReadReq = slave(Stream(ReadAtomicResultCacheData()))
//    val tx = master(Stream(ReadAtomicResultCacheData()))
//  }
//
//  val inputValid = io.rxReadAtomic.reqWithRecvBufAndDmaCommHeader.valid
//  val inputPktFrag = io.rxReadAtomic.reqWithRecvBufAndDmaCommHeader.pktFrag
//
//  val txSel = Bool()
//  when(OpCode.isReadReqPkt(inputPktFrag.bth.opcode)) {
//    txSel := True
//  } elsewhen (OpCode.isAtomicReqPkt(inputPktFrag.bth.opcode)) {
//    txSel := False
//  } otherwise {
//    txSel := False
//    assert(
//      assertion = False,
//      message =
//        L"invalid opcode=${inputPktFrag.bth.opcode}, should be read/atomic requests",
//      severity = FAILURE
//    )
//  }
//  val twoStreams =
//    StreamDemux(
//      io.rxReadAtomic.reqWithRecvBufAndDmaCommHeader,
//      select = txSel.asUInt,
//      portCount = 2
//    )
//
//  val atomicReqExtractor = new AtomicReqExtractor(busWidth)
//  atomicReqExtractor.io.recvQCtrl := io.recvQCtrl
//  atomicReqExtractor.io.rx.reqWithRecvBufAndDmaCommHeader <-/< twoStreams(0)
//
//  val readValid = readReqExtractor.io.txReadReqSaveToCache.valid
//  val atomicValid = atomicReqExtractor.io.txAtomicReqSaveToCache.valid
//  assert(
//    assertion = !(readValid && atomicValid),
//    message =
//      L"readValid=${readValid} and atomicValid=${atomicValid} cannot be true at same time",
//    severity = FAILURE
//  )
//
//  val outputRead = readValid && !atomicValid
//  val outputAtomic = !readValid && atomicValid
//  val cacheData = ReadAtomicResultCacheData().setDefaultVal()
//  when(outputRead) {
//    cacheData := readReqExtractor.io.txReadReqSaveToCache
//  } elsewhen (outputAtomic) {
//    cacheData := atomicReqExtractor.io.txAtomicReqSaveToCache
//  }
//
//  io.tx <-/< StreamSource()
//    .throwWhen(io.recvQCtrl.flush)
//    .continueWhen(outputRead || outputAtomic)
//    .translateWith(cacheData)
//}

class SendWriteRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    // val nakNotify = out(NakErr())
    // val addrCacheReadResp = slave(AddrCacheReadRespBus())
    val rx = slave(RqReqWithRecvBufAndDmaCommHeaderBus(busWidth))
    val tx = master(Stream(Acknowlege()))
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
    Acknowlege().setAck(AckType.NORMAL, inputPktFrag.bth.psn, io.qpAttr.dqpn)
  val rdmaNak =
    Acknowlege().setAck(AckType.NAK_INV, inputPktFrag.bth.psn, io.qpAttr.dqpn)

  io.tx <-/< io.rx.reqWithRecvBufAndDmaCommHeader
    .throwWhen(
      io.recvQCtrl.flush ||
        !lenCheckErr ||
        !inputPktFrag.bth.ackreq
    )
    .translateWith {
      lenCheckErr ? rdmaNak | rdmaAck
    }
}

// TODO: handle read DMA size = 0
class ReadRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val tx = master(RdmaDataBus(busWidth))
    // val txErrResp = master(Stream(Acknowlege()))
    val dmaReadResp = slave(DmaReadRespBus(busWidth))
  }

  val readRespHeaderBitLen = widthOf(BTH()) + widthOf(AETH())
  val readRespHeaderLenBytes = readRespHeaderBitLen / 8
  val bthHeaderLenBytes = widthOf(BTH()) / 8
  val busWidthBytes = busWidth.id / 8

  val inputValid = io.dmaReadResp.resp.valid
  val inputDmaDataFrag = io.dmaReadResp.resp.fragment
  val isLastDmaDataFrag = io.dmaReadResp.resp.isLast
  val isFirstDmaDataFrag = io.dmaReadResp.resp.isFirst

  val numReadRespPkt =
    divideByPmtuUp(inputDmaDataFrag.totalLenBytes, io.qpAttr.pmtu)
  val lastOrOnlyReadRespPktLenBytes =
    moduloByPmtu(inputDmaDataFrag.totalLenBytes, io.qpAttr.pmtu)

  val pmtuFragNum = pmtuPktLenBytes(io.qpAttr.pmtu) >> log2Up(busWidth.id / 8)
  val segmentStream = StreamSegment(
    io.dmaReadResp.resp
      .throwWhen(io.recvQCtrl.flush)
      .translateWith {
        val rslt = Fragment(DataAndMty(busWidth.id))
        rslt.data := io.dmaReadResp.resp.data
        rslt.mty := io.dmaReadResp.resp.mty
        rslt.last := io.dmaReadResp.resp.last
        rslt
      },
    fragmentNum = pmtuFragNum.resize(PMTU_FRAG_NUM_WIDTH)
  )

  // Count the number of read response packet processed
  val readRespPktCnt = Counter(
    bitCount = (RDMA_MAX_LEN_WIDTH / busWidth.id) bits,
    inc = segmentStream.fire && segmentStream.fire
  )

  val curPsn = io.dmaReadResp.resp.psn + readRespPktCnt.value
  val opcode = Bits(OPCODE_WIDTH bits)
  val padcount = U(0, PADCOUNT_WIDTH bits)
  val isMidReadResp = False
  when(numReadRespPkt > 1) {
    when(readRespPktCnt.value === 0) {
      opcode := OpCode.RDMA_READ_RESPONSE_FIRST.id
    } elsewhen (readRespPktCnt.value === numReadRespPkt - 1) {
      opcode := OpCode.RDMA_READ_RESPONSE_LAST.id
      padcount := (4 - lastOrOnlyReadRespPktLenBytes(0, PADCOUNT_WIDTH bits))
        .resize(PADCOUNT_WIDTH)
    } otherwise {
      opcode := OpCode.RDMA_READ_RESPONSE_MIDDLE.id
      isMidReadResp := True
    }
  } otherwise {
    opcode := OpCode.RDMA_READ_RESPONSE_ONLY.id
    padcount := (4 - lastOrOnlyReadRespPktLenBytes(0, PADCOUNT_WIDTH bits))
      .resize(PADCOUNT_WIDTH)
  }

  val bth = BTH().set(
    opcode = opcode,
    padcount = padcount,
    dqpn = io.qpAttr.dqpn,
    psn = curPsn
  )
  val aeth = AETH().set(AckType.NORMAL)
  val bthMty = Bits(widthOf(bth) / 8 bits).setAll()
  val aethMty = Bits(widthOf(aeth) / 8 bits).setAll()

  val twoStreams = StreamFork(segmentStream, portCount = 2)

  // For read response other than middle, it adds both BTH and AETH
  val addBthAethHeaderStream =
    StreamAddHeader(
      twoStreams(0).throwWhen(isMidReadResp),
      bth ## aeth,
      bthMty ## aethMty
    )
  // For middle read response, it adds BTH only
  val addBthHeaderStream =
    StreamAddHeader(
      twoStreams(1).throwWhen(!isMidReadResp),
      bth ## aeth,
      bthMty ## aethMty
    )
  val streamSel = StreamMux(
    select = isMidReadResp.asUInt,
    Vec(addBthAethHeaderStream, addBthHeaderStream)
  )
  io.tx.pktFrag <-/< streamSel
    .throwWhen(io.recvQCtrl.flush)
    .translateWith {
      val rslt = Fragment(RdmaDataPacket(busWidth))
      rslt.data := streamSel.data
      rslt.mty := streamSel.mty
      rslt.last := False
      rslt
    }
}

// TODO: handle duplicated atomic request
class AtomicRespGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val tx = master(Stream(AtomicResp()))
    val dma = master(DmaBus(busWidth))
  }

  io.dma.rd.req << io.dma.rd.resp.translateWith(DmaReadReq().setDefaultVal())
  io.dma.wr.req << io.dma.wr.resp.translateWith {
    val rslt = Fragment(DmaWriteReq(busWidth))
    rslt.setDefaultVal()
    rslt.last := True
    rslt
  }
  io.tx << StreamSource().translateWith(AtomicResp().setDefaultVal())
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
class SeqOut(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val recvQCtrl = in(RecvQCtrl())
    val opsnInc = out(PsnInc())
    // val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val psnRangePush = slave(Stream(RespPsnRange()))
    val readAtomicResultCachePop = slave(Stream(ReadAtomicResultCacheData()))
    val rxSendWriteResp = slave(Stream(Acknowlege()))
    val rxReadResp = slave(RdmaDataBus(busWidth))
    val rxAtomicResp = slave(Stream(AtomicResp()))
    val rxRnrResp = slave(Stream(Acknowlege()))
    val rxDupSendWriteResp = slave(Stream(Acknowlege()))
    val rxDupReadResp = slave(RdmaDataBus(busWidth))
    val rxDupAtomicResp = slave(Stream(AtomicResp()))
    val rxCommCheckErrResp = slave(Stream(Acknowlege()))
    val rxPktLenCheckErrResp = slave(Stream(Acknowlege()))
    val rxAddrValidatorErrResp = slave(Stream(Acknowlege()))
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
  StreamSink(Acknowlege()) << io.rxSendWriteResp
  StreamSink(NoData) << io.rxReadResp.pktFrag.translateWith(NoData)
  StreamSink(AtomicResp()) << io.rxAtomicResp
  StreamSink(Acknowlege()) << io.rxRnrResp
  StreamSink(Acknowlege()) << io.rxDupSendWriteResp
  StreamSink(NoData) << io.rxDupReadResp.pktFrag.translateWith(NoData)
  StreamSink(AtomicResp()) << io.rxDupAtomicResp
  StreamSink(Acknowlege()) << io.rxCommCheckErrResp
  StreamSink(Acknowlege()) << io.rxPktLenCheckErrResp
  StreamSink(Acknowlege()) << io.rxAddrValidatorErrResp
}
