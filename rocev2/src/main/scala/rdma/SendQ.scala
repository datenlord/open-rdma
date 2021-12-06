package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import RdmaConstants._

class ReqBuilder(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val workReqPSN = slave(Stream(WorkReqPSN()))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val npsnReg = Reg(UInt(PSN_WIDTH bits)) init (io.workReqPSN.psnStart)
  when(io.tx.fire && io.tx.last) {
    npsnReg := npsnReg + 1
  }

  io.dmaReadReq <-/< io.dmaReadResp.translateWith {
    val dmaReadReq = new DmaReadReq()
    dmaReadReq.setDefaultVal()
    dmaReadReq
  }

  // TODO: implement SQ logic
  io.tx <-/< io.workReqPSN.translateWith {
    val frag = Fragment(RdmaDataBus(busWidth))
    frag.setDefaultVal()
    // TODO: WR opcode to RC opcode
    frag.bth.opcode := io.workReqPSN.workReq.opcode.resize(OPCODE_WIDTH)
    frag.last := False
    frag
  }
}

class SendReqBuilder(busWidth: BusWidth) extends ReqBuilder(busWidth) {}

class WriteReqBuilder(busWidth: BusWidth) extends ReqBuilder(busWidth) {}

class ReadReqBuilder(busWidth: BusWidth) extends ReqBuilder(busWidth) {}

class AtomicReqBuilder(busWidth: BusWidth) extends ReqBuilder(busWidth) {}

// Send a request
class SqLogic(busWidth: BusWidth, retry: Boolean = false) extends Component {
  val io = new Bundle {
    val workReqPSN = slave(Stream(WorkReqPSN()))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val sendReqBuilder = new SendReqBuilder(busWidth)
  val writeReqBuilder = new WriteReqBuilder(busWidth)
  val readReqBuilder = new ReadReqBuilder(busWidth)
  val atomicReqBuilder = new AtomicReqBuilder(busWidth)

  val reqBuilders =
    List(sendReqBuilder, writeReqBuilder, readReqBuilder, atomicReqBuilder)
  //reqBuilders.foreach(_.io.npsn := io.npsn)
  val reqTypeFuncs = List(
    WorkReqOpCode.isSendReq(_),
    WorkReqOpCode.isWriteReq(_),
    WorkReqOpCode.isReadReq(_),
    WorkReqOpCode.isAtomicReq(_)
  )

  // TODO: support fence
  val allBuilderReady = RegNext(
    reqBuilders.map(_.io.workReqPSN.ready).reduceBalancedTree(_ || _)
  )
  // TODO: do retry requests need to keep order?
  val continueCond = if (retry) True else allBuilderReady

  val reqBuilderSel =
    reqTypeFuncs.map(typeFunc => typeFunc(io.workReqPSN.workReq.opcode))
  val reqBuilderIdx = OHToUInt(reqBuilderSel)
  Vec(reqBuilders.map(_.io.workReqPSN)) <> StreamDemux(
    io.workReqPSN.continueWhen(continueCond),
    reqBuilderIdx,
    reqBuilders.size
  )

  val txVec = Vec(reqBuilders.map(_.io.tx))
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock.on(txVec)
  io.tx <-/< txSel

  io.dmaReadReq <-/< io.dmaReadResp.translateWith {
    DmaReadReq().setDefaultVal()
  }
}

class SendQ(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val npsn = out(UInt(PSN_WIDTH bits))
    val workReq = slave(Stream(WorkReq()))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val cacheWriteReq = master(Stream(CacheData()))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
  }

  val npsnReg = Reg(UInt(PSN_WIDTH bits)) init (0)
  io.npsn := npsnReg

  when(io.qpAttrUpdate.pulseSqPsnReset) {
    npsnReg := io.qpAttr.npsn
  }

  val (workReq0, workReq1) = StreamFork2(io.workReq, synchronous = false)

  val sqLogic = new SqLogic(busWidth)
  io.dmaReadReq <-/< sqLogic.io.dmaReadReq
  sqLogic.io.dmaReadResp <-/< io.dmaReadResp
  sqLogic.io.workReqPSN <-/< workReq0.translateWith {
    val workReqPSN = WorkReqPSN()
    workReqPSN.workReq := workReq0.payload
    workReqPSN.psnStart := npsnReg
    workReqPSN
  }
  io.tx <-/< sqLogic.io.tx

  // TODO: verify nPSN works
  when(sqLogic.io.tx.fire && sqLogic.io.tx.last) {
    assert(
      assertion = npsnReg === sqLogic.io.tx.bth.psn,
      message =
        L"nPSN=${npsnReg} should match sqLogic.io.tx.bth.psn=${sqLogic.io.tx.bth.psn}",
      severity = ERROR
    )

    npsnReg := sqLogic.io.tx.bth.psn + 1
  }

  // TODO: set PSN start/end
  io.cacheWriteReq <-/< workReq1.translateWith {
    CacheData().setDefaultVal()
  }
}
