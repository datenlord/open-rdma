package rdma

import spinal.core._
import spinal.lib._
import BusWidth.BusWidth
import RdmaConstants._
// import ConstantSettings._
import rdma.StreamVec.buildStreamVec

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
class ReqCommValidator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val rnrNotify = in(NakNotifier())
    val rnrClear = out(Bool())
    val recvQFlush = in(QpFlushNotifier())
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
    val txDupReq = master(Stream(RdmaDataBus(busWidth)))
    val txErrResp = master(Stream(RdmaNonReadRespBus()))
  }
  // TODO: implementation
  io.rnrClear := False
  val tmpStream = Stream(RdmaDataBus(busWidth))
  Vec(io.tx, io.txDupReq, tmpStream) <-/< StreamFork(io.rx, portCount = 3)
  io.txErrResp << tmpStream.translateWith(RdmaNonReadRespBus().setDefaultVal())
}

// If multiple duplicate requests received, also ACK in PSN order;
// RQ will return ACK with the latest PSN for duplicate Send/Write, but this will NAK the following duplicate Read/Atomic???
// No NAK for duplicate requests if error detected;
// Duplicate Read is not valid if not with its original PSN and DMA range;
// Duplicate request with earlier PSN might interrupt processing of new request or duplicate request with later PSN;
// RQ does not re-execute the interrupted request, SQ will retry it;
// Discard duplicate Atomic if not match original PSN (should not happen);
class DupReqHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  // TODO: check false duplicate requests, e.g. duplicate requests in pipeline, not executed yet
  io.tx <-/< io.rx.addFragmentLast(False)
}

//class ErrResqHandler extends Component {
//  val io = new Bundle {
//    val retryNakSentClear = in(Bool())
//    val qpAttrUpdate = in(QpAttrUpdateNotifier())
//    val rxCommCheckErr = slave(Stream(RdmaNonReadRespBus()))
//    val tx = master(Stream(RdmaNonReadRespBus()))
//    val qpStateChange = master(Stream(QpStateChange()))
//  }
//
//  val nakSentReg = Reg(Bool()) init (False)
//  when(io.qpAttrUpdate.pulseRqPsnReset) {
//    nakSentReg := False
//  } elsewhen (io.retryNakSentClear) {
//    nakSentReg := False
//  } elsewhen (io.tx.fire) {
//    nakSentReg := True
//  }
//  // TODO: implementation
//  val errResp = io.rxCommCheckErr
//  io.tx <-/< errResp.throwWhen(nakSentReg)
//
//  io.qpStateChange.setDefaultVal()
//  io.qpStateChange.valid := False
//}

class RecvQFlushHandler extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendWriteNakNotify = in(NakNotifier())
    val readAtomicNakNotify = in(NakNotifier())
    val rnrClear = in(Bool())
    val rnrNotify = out(NakNotifier())
    val recvQFlush = out(QpFlushNotifier())
    val qpStateChange = master(Stream(QpStateChange()))
  }

  io.rnrNotify := io.sendWriteNakNotify

  val rnrSentReg = RegInit(False)
  val rnrNakPsnReg = Reg(UInt(PSN_WIDTH bits))

  when(io.rnrClear) {
    rnrSentReg := False
  } elsewhen (io.sendWriteNakNotify.rnrPulse) {
    rnrSentReg := True
    rnrNakPsnReg := io.sendWriteNakNotify.rnrNakPsn

    assert(
      assertion = rnrSentReg && io.sendWriteNakNotify.rnrPulse,
      message = L"""there's already a RNR NAK sent PSN=${rnrNakPsnReg},
        rnrSentReg=${rnrSentReg},
        io.sendWriteNakNotify.rnrPulse=${io.sendWriteNakNotify.rnrPulse},
        io.sendWriteNakNotify.rnrNakPsn=${io.sendWriteNakNotify.rnrNakPsn}""",
      severity = ERROR
    )

    // TODO: Read/Atomic has RNR?
    //  } elsewhen (io.readAtomicNakNotify.rnrPulse && !rnrSentReg) {
    //    rnrSentReg := True
    //    rnrNakPsnReg := io.readAtomicNakNotify.rnrNakPsn
  }

  io.qpStateChange << StreamSource()
    .continueWhen(
      io.sendWriteNakNotify.nakPulse || io.readAtomicNakNotify.nakPulse
    )
    .translateWith {
      val rslt = QpStateChange()
      rslt.changePulse := True
      rslt.changeToState := QpState.ERR.id
      rslt
    }
  assert(
    assertion =
      io.qpAttr.state === QpState.ERR.id && (io.sendWriteNakNotify.nakPulse || io.readAtomicNakNotify.nakPulse),
    message = L"""QP state is already ERROR, io.qpAttr.state=${io.qpAttr.state},
        io.sendWriteNakNotify.nakPulse=${io.sendWriteNakNotify.nakPulse},
        io.readAtomicNakNotify.nakPulse=${io.readAtomicNakNotify.nakPulse}""",
    severity = ERROR
  )

  val isQpStateErrorReg = RegNext(io.qpAttr.state === QpState.ERR.id)
  // Flush RQ if state error or RNR sent in next cycle
  io.recvQFlush.flush := isQpStateErrorReg || rnrSentReg
  io.recvQFlush.stateErrFlush := isQpStateErrorReg
  io.recvQFlush.rnrFlush := rnrSentReg

  // TODO: RNR wait timer
}

// RQ must complete all previous requests before sending an NAK,
// since it acts as an implicit ACK for prior outstanding SEND or RDMA WRITE requests,
// and as an implicit NAK for outstanding RDMA READ or ATOMIC Operation requests.
//
// The ACK to Send/Write has the most recent completed request PSN;
// If Read response detected error, premature termination of Read response with NAK;
//
// Read relaxed order, as long Read is valid, RQ can execute requests after Read first, but always ACK in PSN order;
//
// RQ could return ACK before Send/Write finish saving data to main memory;
trait ReqHandler extends Component

class SendReqHandler(busWidth: BusWidth) extends ReqHandler {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpFlushNotify = in(QpFlushNotifier())
    val nakNotify = out(NakNotifier())
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val workComp = master(Stream(WorkComp()))
    val addrCacheBus = master(AddrCacheReadBus())
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaNonReadRespBus()))
    val txErrResp = master(Stream(RdmaNonReadRespBus()))
    val dmaWrite = master(DmaWriteReqBus(busWidth))
  }
  // TODO: implementation
  io.nakNotify.setDefaultVal()

  io.tx <-/< io.rx.translateWith(RdmaNonReadRespBus().setDefaultVal())
  io.dmaWrite.req <-/< StreamSource().translateWith {
    val frag = Fragment(DmaWriteReq(busWidth))
    frag.setDefaultVal()
    frag.last := False
    frag
  }
  io.txErrResp <-/< io.recvWorkReq.translateWith(
    RdmaNonReadRespBus().setDefaultVal()
  )
}

class WriteReqHandler(busWidth: BusWidth) extends ReqHandler {
  val io = new Bundle {
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val workComp = master(Stream(WorkComp()))
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaNonReadRespBus()))
    val txErrResp = master(Stream(RdmaNonReadRespBus()))
    val dmaWrite = master(DmaWriteReqBus(busWidth))
  }
  // TODO: implementation
  io.tx <-/< io.rx.translateWith(RdmaNonReadRespBus().setDefaultVal())
  io.dmaWrite.req <-/< StreamSource().translateWith {
    val frag = Fragment(DmaWriteReq(busWidth))
    frag.setDefaultVal()
    frag.last := False
    frag
  }
  io.txErrResp <-/< io.recvWorkReq.translateWith(
    RdmaNonReadRespBus().setDefaultVal()
  )
}

class ReadReqHandler(busWidth: BusWidth) extends ReqHandler {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val txErrResp = master(Stream(RdmaNonReadRespBus()))
    val dmaRead = master(DmaReadBus(busWidth))
    val nakNotify = out(NakNotifier())
  }

  // TODO: implementation
  io.nakNotify.setDefaultVal()
  io.tx <-/< io.rx.addFragmentLast(False)
  io.dmaRead.req <-/< io.dmaRead.resp.translateWith {
    DmaReadReq().setDefaultVal()
  }
  io.txErrResp <-/< StreamSource().translateWith(
    RdmaNonReadRespBus().setDefaultVal()
  )
}

class AtomicReqHandler(busWidth: BusWidth) extends ReqHandler {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaNonReadRespBus()))
    val txErrResp = master(Stream(RdmaNonReadRespBus()))
    val dmaRead = master(DmaReadBus(busWidth))
    val dmaWrite = master(DmaWriteReqBus(busWidth))
  }

  // TODO: implementation
  io.tx <-/< io.rx.translateWith(RdmaNonReadRespBus().setDefaultVal())
  io.dmaRead.req <-/< io.dmaRead.resp.translateWith {
    DmaReadReq().setDefaultVal()
  }
  io.dmaWrite.req <-/< StreamSource().translateWith {
    val frag = Fragment(DmaWriteReq(busWidth))
    frag.setDefaultVal()
    frag.last := False
    frag
  }
  io.txErrResp <-/< StreamSource().translateWith(
    RdmaNonReadRespBus().setDefaultVal()
  )
}

// RQ executes Send, Write, Atomic in order;
// RQ can delay Read execution;
// Completion of Send and Write at RQ is in PSN order, but not imply previous Read is complete unless fenced;
// RQ saves Atomic (Req & Result) and Read (Req only) in Queue Context, size as # pending Read/Atomic;
class RecvQ(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val workComp = master(Stream(WorkComp()))
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val qpStateChange = master(Stream(QpStateChange()))
    val dma = master(DmaBus(busWidth))
  }

  // TODO: connect to DMA controller
  val sendReqHandler = new SendReqHandler(busWidth)
  val writeReqHandler = new WriteReqHandler(busWidth)
  val readReqHandler = new ReadReqHandler(busWidth)
  val atomicReqHandler = new AtomicReqHandler(busWidth)

  sendReqHandler.io.recvWorkReq << io.recvWorkReq
  io.workComp << sendReqHandler.io.workComp

  val reqVerifier = new Area {
    val reqCommValidator = new ReqCommValidator(busWidth)
    reqCommValidator.io.qpAttr := io.qpAttr
    reqCommValidator.io.qpAttrUpdate := io.qpAttrUpdate
    reqCommValidator.io.rnrNotify := sendReqHandler.io.nakNotify
    reqCommValidator.io.rx << io.rx
    val normalReq = reqCommValidator.io.tx
    val txErrResp = reqCommValidator.io.txErrResp

    val errRespHandler = new RecvQFlushHandler()
    errRespHandler.io.qpAttr := io.qpAttr
    errRespHandler.io.rnrClear := reqCommValidator.io.rnrClear
    errRespHandler.io.sendWriteNakNotify := sendReqHandler.io.nakNotify
    errRespHandler.io.readAtomicNakNotify := readReqHandler.io.nakNotify
    io.qpStateChange << errRespHandler.io.qpStateChange

    val dupReqHandler = new DupReqHandler(busWidth)
    dupReqHandler.io.rx << reqCommValidator.io.txDupReq
    val txDupResp = dupReqHandler.io.tx
  }

  val reqHandlerRxs = Vec(
    sendReqHandler.io.rx,
    writeReqHandler.io.rx,
    readReqHandler.io.rx,
    atomicReqHandler.io.rx
  )
  val reqTypeFuncs = List(
    OpCode.isSendReqPkt(_),
    OpCode.isWriteReqPkt(_),
    OpCode.isReadReqPkt(_),
    OpCode.isAtomicReqPkt(_)
  )

  val reqHandlerSel = reqTypeFuncs.map(typeFunc => typeFunc(io.rx.bth.opcode))
  val reqHandlerIdx = OHToUInt(reqHandlerSel)
  reqHandlerRxs <> StreamDemux(
    reqVerifier.normalReq.pipelined(m2s = true, s2m = true),
    select = reqHandlerIdx,
    portCount = reqHandlerRxs.length
  )

  val seqOut = new SeqOut(busWidth)
  seqOut.io.qpAttr := io.qpAttr
  seqOut.io.qpAttrUpdate := io.qpAttrUpdate
  seqOut.io.rxSendResp << sendReqHandler.io.tx
  seqOut.io.rxWriteResp << writeReqHandler.io.tx
  seqOut.io.rxReadResp << readReqHandler.io.tx
  seqOut.io.rxAtomicResp << atomicReqHandler.io.tx
  seqOut.io.rxDupReqResp << reqVerifier.txDupResp
  seqOut.io.rxPreCheckErrResp << reqVerifier.txErrResp
  seqOut.io.rxSendWriteErrResp << sendReqHandler.io.txErrResp
  seqOut.io.rxReadAtomicErrResp << readReqHandler.io.txErrResp
  io.tx << seqOut.io.tx
}

class SeqOut(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val rxAtomicResp = slave(Stream(RdmaNonReadRespBus()))
    val rxReadResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val rxSendResp = slave(Stream(RdmaNonReadRespBus()))
    val rxWriteResp = slave(Stream(RdmaNonReadRespBus()))
    val rxDupReqResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val rxPreCheckErrResp = slave(Stream(RdmaNonReadRespBus()))
    val rxSendWriteErrResp = slave(Stream(RdmaNonReadRespBus()))
    val rxReadAtomicErrResp = slave(Stream(RdmaNonReadRespBus()))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val opsnIncrease = Bool()

  val opsnReg = Reg(UInt(PSN_WIDTH bits)) // init (0)
  when(io.qpAttrUpdate.pulseRqPsnReset) {
    opsnReg := io.qpAttr.epsn
  } elsewhen (opsnIncrease) {
    opsnReg := opsnReg + 1
  }

  val rxSeq = Seq(
    io.rxAtomicResp
      .translateWith(io.rxAtomicResp.toRdmaDataBus(busWidth))
      .addFragmentLast(True),
    io.rxReadResp,
    io.rxSendResp
      .translateWith(io.rxSendResp.toRdmaDataBus(busWidth))
      .addFragmentLast(True),
    io.rxWriteResp
      .translateWith(io.rxWriteResp.toRdmaDataBus(busWidth))
      .addFragmentLast(True),
    io.rxDupReqResp,
    io.rxPreCheckErrResp
      .translateWith(io.rxPreCheckErrResp.toRdmaDataBus(busWidth))
      .addFragmentLast(True),
    io.rxSendWriteErrResp
      .translateWith(io.rxSendWriteErrResp.toRdmaDataBus(busWidth))
      .addFragmentLast(True),
    io.rxReadAtomicErrResp
      .translateWith(io.rxReadAtomicErrResp.toRdmaDataBus(busWidth))
      .addFragmentLast(True)
  )
  val rxFiltered = rxSeq.map(rx => rx.haltWhen(rx.bth.psn =/= opsnReg))
  val txSel = StreamArbiterFactory.fragmentLock.on(rxFiltered)

  opsnIncrease := txSel.fire && txSel.isLast
  io.tx <-/< txSel

  when(txSel.valid) {
    assert(
      assertion = OpCode.isRespPkt(txSel.bth.opcode),
      message =
        L"SeqOut can only output response packet, but with invalid opcode=${txSel.bth.opcode}",
      severity = FAILURE
    )

    assert(
      assertion = CountOne(rxFiltered.map(_.valid)) <= 1,
      message = L"SeqOut has duplicate PSN input, oPSN=${opsnReg}",
      severity = FAILURE
    )
  }

  val rxAllValid = Vec(rxSeq.map(_.valid)).asBits.andR
  when(rxAllValid) {
    assert(
      assertion = txSel.valid,
      message =
        L"SeqOut stuck, because all rx are valid but no one's PSNs is equal to oPSN=${opsnReg}",
      severity = FAILURE
    )
  }
}
