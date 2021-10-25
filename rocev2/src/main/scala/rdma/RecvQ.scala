package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import Constants._

// PSN == ePSN, or NAK-Seq;
// OpCode sequence, or NAK-Inv Req;
// OpCode functionality is supported, or NAK-Inv Req;
// First/Middle packets have padcount == 0, or NAK-Inv Req;
// Queue Context has resource for Read/Atomic, or NAK-Inv Req;
// RKey, virtual address, DMA length (or packet size) match MR range and access type, or NAK-Rmt Acc:
// - for Write, the length check is per packet basis, based on LRH:PktLen field;
// - for Read, the length check is based on RETH:DMA Length field;
// - no RKey check for 0-sized Write/Read;
// Length check, or NAK-Inv Req:
// - for Send, the length check is based on LRH:PktLen field;
// - First/Middle packet length == PMTU;
// - Only packet length 0 <= len <= PMTU;
// - Last packet length 1 <= len <= PMTU;
// - for Write, check received data size == DMALen at last packet;
// - for Write/Read, check 0 <= DMALen <= 2^31;
// RQ local error detected, NAK-Rmt Op;
class ReqVerifier(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = slave(Stream(Bits(QP_ATTR_MASK_WIDTH bits)))
    val rx = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val txErr = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val txDup = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val epsnReg = Reg(UInt(PSN_WIDTH bits)) init (0)

  io.qpAttrUpdate.ready := True
  when(
    io.qpAttrUpdate.valid && (
      io.qpAttrUpdate.payload === QpAttrMask.QP_RQ_PSN.id
        || io.qpAttrUpdate.payload === QpAttrMask.QP_CREATE.id
    )
  ) {
    epsnReg := io.qpAttr.epsn
  }

  val outSel = U(0, 2 bits)
  val outStreams =
    StreamDemux(io.rx, select = outSel, portCount = 3)
  io.tx <-/< outStreams(0)
  io.txErr <-/< outStreams(1)
  io.txDup <-/< outStreams(2)
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
    val rx = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  io.tx <-/< io.rx
}

class ErrReqHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  io.tx <-/< io.rx
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
    val rx = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val dmaWriteReq = master(Stream(Fragment(DmaWriteReq())))
    val dmaWriteResp = slave(Stream(DmaWriteResp()))
  }

  // TODO: remove this
  io.tx <-/< io.rx
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
    val qpAttrUpdate = slave(Stream(Bits(QP_ATTR_MASK_WIDTH bits)))
    val rx = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val dmaWriteReq = master(Stream(Fragment(DmaWriteReq())))
    val dmaWriteResp = slave(Stream(DmaWriteResp()))
  }
  val reqVerifier = new ReqVerifier(busWidth)
  reqVerifier.io.qpAttr := io.qpAttr
  reqVerifier.io.rx <-/< io.rx
  val normReq = reqVerifier.io.tx
  val errReqRespTx = reqVerifier.io.txErr
  val dupReq = reqVerifier.io.txDup

  val dupReqHandler = new DupReqHandler(busWidth)
  dupReqHandler.io.rx <-/< dupReq
  val dupReqRespTx = dupReqHandler.io.tx

  val sendReqHandler = new SendReqHandler(busWidth)
  val writeReqHandler = new WriteReqHandler(busWidth)
  val readReqHandler = new ReadReqHandler(busWidth)
  val atomicReqHandler = new AtomicReqHandler(busWidth)

  val reqHandlers =
    List(sendReqHandler, writeReqHandler, readReqHandler, atomicReqHandler)
  val reqTypeFuncs = List(
    OpCode.isSendReq(_),
    OpCode.isWriteReq(_),
    OpCode.isReadReq(_),
    OpCode.isAtomicReq(_)
  )

  val reqHandlerSel = reqTypeFuncs.map(typeFunc => typeFunc(io.rx.bth.opcode))
  val reqHandlerIdx = OHToUInt(reqHandlerSel)
  // TODO: handle output order
  Vec(reqHandlers.map(_.io.rx)) <> StreamDemux(
    normReq.pipelined(m2s = true, s2m = true),
    reqHandlerIdx,
    reqHandlers.size
  )

  val txVec = Vec(
    sendReqHandler.io.tx,
    writeReqHandler.io.tx,
    atomicReqHandler.io.tx,
    errReqRespTx,
    dupReqRespTx
  )
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock.on(txVec)
  val seqOut = new SeqOut(busWidth)
  seqOut.io.qpAttr := io.qpAttr
  seqOut.io.rxOtherResp <-/< txSel
  seqOut.io.rxReadResp <-/< readReqHandler.io.tx
  io.tx <-/< seqOut.io.tx

  Vec(reqVerifier.io.qpAttrUpdate, seqOut.io.qpAttrUpdate) <> StreamFork(
    io.qpAttrUpdate,
    portCount = 2
  )
}

class SeqOut(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = slave(Stream(Bits(QP_ATTR_MASK_WIDTH bits)))
    val rxReadResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val rxOtherResp = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val opsnReg = Reg(UInt(PSN_WIDTH bits)) init (0)

  io.qpAttrUpdate.ready := True
  when(
    io.qpAttrUpdate.valid && (
      io.qpAttrUpdate.payload === QpAttrMask.QP_RQ_PSN.id
        || io.qpAttrUpdate.payload === QpAttrMask.QP_CREATE.id
    )
  ) {
    opsnReg := io.qpAttr.epsn
  }

  // TODO: select output by PSN order
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock
    .onArgs(io.rxReadResp, io.rxOtherResp)
  io.tx <-/< txSel
}
