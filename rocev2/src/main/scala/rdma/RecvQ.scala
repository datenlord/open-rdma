package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._

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
class ReqVerifier(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val epsn = in(UInt(PSN_WIDTH bits))
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
    val txDupResp = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val txErrResp = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val reqCommValidator = new ReqCommValidator(busWidth)
  reqCommValidator.io.epsn := io.epsn
  reqCommValidator.io.qpAttrUpdate := io.qpAttrUpdate
  reqCommValidator.io.rx << io.rx
  io.tx << reqCommValidator.io.tx

  val errResqHandler = new ErrResqHandler(busWidth)
  errResqHandler.io.epsnInc := False // TODO: add ePSN increment notifier
  errResqHandler.io.qpAttrUpdate := io.qpAttrUpdate
  errResqHandler.io.rxCommCheckErr << reqCommValidator.io.txErrResp
  io.txErrResp << errResqHandler.io.tx

  val dupReqHandler = new DupReqHandler(busWidth)
  dupReqHandler.io.rx << reqCommValidator.io.txDupReq
  io.txDupResp << dupReqHandler.io.tx
}

class ReqCommValidator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val epsn = in(UInt(PSN_WIDTH bits))
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
    val txDupReq = master(Stream(RdmaDataBus(busWidth)))
    val txErrResp = master(Stream(RdmaDataBus(busWidth)))
  }

  val preOpCodeReg = Reg(Bits(OPCODE_WIDTH bits)) init (OpCode.SEND_FIRST.id)
  when(io.qpAttrUpdate.pulseRqPsnReset) {
    // When RQ PSN reset, assume previous opcode is SEND_ONLY
    preOpCodeReg := OpCode.SEND_ONLY.id
  }
  when(io.rx.fire) {
    preOpCodeReg := io.rx.bth.opcode
  }

  // Pipeline for request common checks
  case class ReqCommCheckResult(busWidth: BusWidth) extends Bundle {
    val checkPass = Bool()
    val psnCheckRslt = Bool()
    val dupReq = Bool()
    val opSeqCheckRslt = Bool()
    val pktLenCheckRslt = Bool()
    val nak = RdmaDataBus(busWidth)
    val rdmaData = RdmaDataBus(busWidth)
  }

  val checkStage = new Area {
    val input = io.rx.asFlow
    val output = input
      .combStage()
      .translateWith {
        // PSN sequence check
        val psnCheckRslt = Bool()
        val dupReq = Bool()
        val cmpRslt = psnComp(io.rx.bth.psn, io.epsn, curPsn = io.epsn)
        switch(cmpRslt) {
          is(PsnCompResult.GREATER.id) {
            psnCheckRslt := False
            dupReq := False
          }
          is(PsnCompResult.LESSER.id) {
            psnCheckRslt := True
            dupReq := True
          }
          default { // PsnCompResult.EQUAL
            psnCheckRslt := True
            dupReq := False
          }
        }

        // OpCode sequence check
        val opSeqCheckRslt =
          OpCodeSeq.checkReqSeq(preOpCodeReg, io.rx.bth.opcode)

        // Packet length check
        val pktLenCheckRslt = pktLengthCheck(io.rx.payload, busWidth)

        val rslt = ReqCommCheckResult(busWidth)
        rslt.psnCheckRslt := psnCheckRslt
        rslt.dupReq := dupReq
        rslt.pktLenCheckRslt := pktLenCheckRslt
        rslt.opSeqCheckRslt := opSeqCheckRslt
        rslt.nak.setDefaultVal()
        rslt.rdmaData := io.rx.payload
        rslt.checkPass := False
        rslt
      }
  }

  val dataBuildStage = new Area {
    val input = checkStage.output.m2sPipe()
    val output = input
      .combStage()
      .translateWith {
        val rslt = cloneOf(input.payload)
        rslt.assignAllByName(input.payload)

        val ackType = Bits(ACK_TYPE_WIDTH bits)
        when(!rslt.psnCheckRslt) {
          ackType := AckType.NAK_SEQ.id
          rslt.nak.setAck(ackType, io.epsn, rslt.rdmaData.bth.dqpn)
          rslt.checkPass := False
        } elsewhen (rslt.dupReq) {
          ackType := AckType.NORMAL.id
          rslt.checkPass := True
        } elsewhen (!rslt.opSeqCheckRslt || !rslt.pktLenCheckRslt) {
          ackType := AckType.NAK_INV.id
          rslt.nak.setAck(
            ackType,
            rslt.rdmaData.bth.psn,
            rslt.rdmaData.bth.dqpn
          )
          rslt.checkPass := False
        } otherwise {
          ackType := AckType.NORMAL.id
          rslt.checkPass := True
        }

        rslt
      }
  }

  val outputStage = new Area {
    val input = dataBuildStage.output.m2sPipe()
    // TODO: check correctness, TX and RX fire at same time, and pay attention to duplicate data
    io.tx <-/< input.toStream
      .throwWhen(
        input.rdmaData.bth.psn =/= io.epsn
      )
      .translateWith(input.rdmaData)
    // TODO: pay attention to duplicate data
    io.txDupReq <-/< input.toStream
      .continueWhen(
        input.checkPass && input.dupReq
      )
      .translateWith(input.rdmaData)
    // TODO: pay attention to duplicate data
    io.txErrResp <-/< input.toStream
      .continueWhen(!input.checkPass)
      .translateWith(input.nak)
    io.rx.ready := io.tx.fire
  }
}

// If multiple duplicate reqeusts received, also ACK in PSN order;
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

  // TODO: implementation
  io.tx <-/< io.rx.addFragmentLast(False)
}

class ErrResqHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val epsnInc = in(Bool())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val rxCommCheckErr = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val nakSentReg = Reg(Bool()) init (False)
  when(io.qpAttrUpdate.pulseRqPsnReset) {
    nakSentReg := False
  } elsewhen (io.epsnInc) {
    nakSentReg := False
  } elsewhen (io.tx.fire) {
    nakSentReg := True
  }
  // TODO: implementation
  val errResp = io.rxCommCheckErr
  io.tx <-/< errResp.throwWhen(nakSentReg).addFragmentLast(True)
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
class ReqHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val dmaWriteReq = master(Stream(Fragment(DmaWriteReq())))
    val dmaWriteResp = slave(Stream(DmaWriteResp()))
  }

  // TODO: implementation
  io.tx <-/< io.rx.addFragmentLast(False)
}

class SendReqHandler(busWidth: BusWidth) extends ReqHandler(busWidth) {}

class WriteReqHandler(busWidth: BusWidth) extends ReqHandler(busWidth) {}

class ReadReqHandler(busWidth: BusWidth) extends ReqHandler(busWidth) {}

class AtomicReqHandler(busWidth: BusWidth) extends ReqHandler(busWidth) {}

// RQ executes Send, Write, Atomic in order;
// RQ can delay Read execution;
// Completion of Send and Write at RQ is in PSN order, but not imply previous Read is complete unless fenced;
// RQ saves Atomic (Req & Result) and Read (Req only) in Queue Context, size as # pending Read/Atomic;
class RecvQ(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val dmaWriteReq = master(Stream(Fragment(DmaWriteReq())))
    val dmaWriteResp = slave(Stream(DmaWriteResp()))
  }

  val epsnReg = Reg(UInt(PSN_WIDTH bits)) init (0)

  val reqVerifier = new ReqVerifier(busWidth)
  reqVerifier.io.qpAttrUpdate := io.qpAttrUpdate
  reqVerifier.io.epsn := epsnReg
  reqVerifier.io.rx <-/< io.rx
  val normReq = reqVerifier.io.tx

  // TODO: connect to DMA controller
  val sendReqHandler = new SendReqHandler(busWidth)
  val writeReqHandler = new WriteReqHandler(busWidth)
  val readReqHandler = new ReadReqHandler(busWidth)
  val atomicReqHandler = new AtomicReqHandler(busWidth)

  val reqHandlers =
    List(sendReqHandler, writeReqHandler, readReqHandler, atomicReqHandler)
  val reqTypeFuncs = List(
    OpCode.isSendReqPkt(_),
    OpCode.isWriteReqPkt(_),
    OpCode.isReadReqPkt(_),
    OpCode.isAtomicReqPkt(_)
  )

  val reqHandlerSel = reqTypeFuncs.map(typeFunc => typeFunc(io.rx.bth.opcode))
  val reqHandlerIdx = OHToUInt(reqHandlerSel)
  Vec(reqHandlers.map(_.io.rx)) <> StreamDemux(
    normReq.pipelined(m2s = true, s2m = true),
    reqHandlerIdx,
    reqHandlers.size
  )

  val seqOut = new SeqOut(busWidth)
  seqOut.io.qpAttr := io.qpAttr
  seqOut.io.rxSendResp <-/< sendReqHandler.io.tx
  seqOut.io.rxWriteResp <-/< writeReqHandler.io.tx
  seqOut.io.rxReadResp <-/< readReqHandler.io.tx
  seqOut.io.rxAtomicResp <-/< atomicReqHandler.io.tx
  seqOut.io.rxDupReqResp <-/< reqVerifier.io.txErrResp
  seqOut.io.rxErrReqResp <-/< reqVerifier.io.txDupResp
  io.tx <-/< seqOut.io.tx

  when(io.qpAttrUpdate.pulseRqPsnReset) {
    epsnReg := io.qpAttr.epsn
  }
}

class SeqOut(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val rxAtomicResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val rxReadResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val rxSendResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val rxWriteResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val rxDupReqResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val rxErrReqResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val opsnReg = Reg(UInt(PSN_WIDTH bits)) // init (0)
  when(io.qpAttrUpdate.pulseRqPsnReset) {
    opsnReg := io.qpAttr.epsn
  }

  // TODO: select output by PSN order
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock
    .onArgs(
      io.rxAtomicResp,
      io.rxReadResp,
      io.rxSendResp,
      io.rxWriteResp,
      io.rxDupReqResp,
      io.rxErrReqResp
    )
  when(txSel.valid) {
    assert(
      assertion = OpCode.isRespPkt(txSel.bth.opcode),
      message =
        L"SeqOut can only output response packet, but with invalid opcode=${txSel.bth.opcode}",
      severity = ERROR
    )
  }
  io.tx <-/< txSel
}
