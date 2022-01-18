package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import RdmaConstants._
import StreamVec._

// Table 40, pp. 296, spec 1.4
// Silently drop illegal incoming packets
// Head verification does not consider: IETH, SOLICITED EVENT
// Head verification will check:
// - Target QP exists;
// - QP service type matches incoming packet opcode;
// - QP state is valid:
//   * for incoming requests, QP state should be RTS, SQ Drain, RTR, SQ Error;
//   * for incoming responses, QP state should be SQ Drain, RTR, SQ Error;
// - PKey matches.
// TODO: should support PKey?
// TODO: check whether it still needs to process pending requests/responses when QP state is ERR
class HeadVerifier(numMaxQPs: Int, busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttrVec = in(Vec(QpAttrData(), numMaxQPs))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
  }

  val rdmaData = io.rx.pktFrag

  val validHeader =
    Transports.isSupportedType(rdmaData.bth.transport) &&
      OpCode.isValidCode(rdmaData.bth.opcode)
  val dqpStateValid = io.qpAttrVec.map(qpAttr =>
    qpAttr.sqpn === rdmaData.bth.dqpn &&
      QpState.allowRecv(rdmaData.bth.opcode, qpAttr.state)
  )
  val cond = !validHeader || !dqpStateValid.asBits().orR
  when(io.rx.pktFrag.valid && cond) {
    val dqpIdxOH =
      io.qpAttrVec.map(qpAttr => qpAttr.sqpn === rdmaData.bth.dqpn).asBits()
    val dqpAttr = io.qpAttrVec.oneHotAccess(dqpIdxOH)
    val dqpState = dqpAttr.state
    report(
      L"HeadVerifier dropped one packet, psn=${rdmaData.bth.psn}, opcode=${rdmaData.bth.opcode}, dqpn=${rdmaData.bth.dqpn}, dqpIdxOH=${dqpIdxOH}, dqpState=${dqpState}"
    )
  }
  io.tx.pktFrag <-/< io.rx.pktFrag.throwWhen(cond).translateWith(rdmaData)
}

class AllQpCtrl(numMaxQPs: Int) extends Component {
  val io = new Bundle {
    val qpAttrVec = in(Vec(QpAttrData(), numMaxQPs))
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val qpCreateOrModifyVec = Vec(master(Stream(QpAttrData())), numMaxQPs)
    // val qpAttrUpdate = Vec(out(QpAttrUpdateNotifier()), numMaxQPs)
    // val qpStateChange = Vec(slave(Stream(QpStateChange())), numMaxQPs)
    val full = out(Bool())
  }

//  val qpIdxVec = (0 until numMaxQPs)
//  val qpAttrVec = Vec(qpIdxVec.map(_ => {
//    // RegInit(QpAttrData().setDefaultVal())
//    Reg(QpAttrData()).setDefaultVal()
//  }))
//  io.qpAttrVec := qpAttrVec

//  for (qpIdx <- qpIdxVec) {
//    io.qpAttrUpdate(qpIdx).pulseRqPsnReset := False
//    io.qpAttrUpdate(qpIdx).pulseSqPsnReset := False
//
//    // Change QPS from RQ or SQ
//    io.qpStateChange(qpIdx).ready := True
//    when(io.qpStateChange(qpIdx).valid) {
//      qpAttrVec(qpIdx).state := io.qpStateChange(qpIdx).changeToState
//    }
//  }

  // QP0 and QP1 are reserved QPN
  val nextQpnReg = Reg(UInt(QPN_WIDTH bits)) init (2)

  val qpVecAvailable = io.qpAttrVec.map(_.isValid === False)
  io.full := !(qpVecAvailable.asBits().orR)

  val availableQpOH = OHMasking.first(qpVecAvailable)
  val modifyQpOH =
    Vec(io.qpAttrVec.map(_.sqpn === io.qpCreateOrModify.sqpn))

  val qpCreation = io.qpCreateOrModify.modifyMask === QpAttrMask.QP_CREATE.id
  val qpSelIdx = OHToUInt(qpCreation ? availableQpOH | modifyQpOH)
  when(io.qpCreateOrModify.valid && qpCreation) {
    nextQpnReg := nextQpnReg + 1
    when(nextQpnReg + 1 === 0) {
      // QP0 and QP1 are reserved QPN
      nextQpnReg := 2
    }
  }

  io.qpCreateOrModifyVec <-/< StreamDemux(
    io.qpCreateOrModify.translateWith {
      val rslt = QpAttrData()
      rslt.assignAllByName(io.qpCreateOrModify.payload)
      when(io.qpCreateOrModify.valid && qpCreation) {
        rslt.sqpn := nextQpnReg
      }
      rslt
    },
    select = qpSelIdx,
    portCount = numMaxQPs
  )
}

class UdpRdmaPktConverter(numMaxQPs: Int, busWidth: BusWidth)
    extends Component {
  val io = new Bundle {
    val qpAttrVec = in(Vec(QpAttrData(), numMaxQPs))
    val udpRx = slave(UdpDataBus(busWidth))
    val rdmaRx = master(RdmaDataBus(busWidth))
    val udpTx = master(UdpDataBus(busWidth))
    val rdmaTx = slave(RdmaDataBus(busWidth))
  }

  // TODO: implementation
  io.udpTx.pktFrag <-/< io.udpRx.pktFrag
  io.rdmaRx.pktFrag <-/< io.rdmaTx.pktFrag
}

// TODO: RoCE should have QP1?
class AllQpModules(numMaxQPs: Int, busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val workReq = Vec(slave(Stream(WorkReq())), numMaxQPs)
    val recvWorkReq = Vec(slave(Stream(RecvWorkReq())), numMaxQPs)
    val workComp = Vec(master(Stream(WorkComp())), numMaxQPs)
    val rx = slave(UdpDataBus(busWidth))
    val tx = master(UdpDataBus(busWidth))
    val dma = master(DmaBus(busWidth))
  }

  val qpIdxVec = (0 until numMaxQPs)
  val allQpCtrl = new AllQpCtrl(numMaxQPs)
  allQpCtrl.io.qpCreateOrModify << io.qpCreateOrModify

  val udpRdmaPktConverter = new UdpRdmaPktConverter(numMaxQPs, busWidth)
  udpRdmaPktConverter.io.qpAttrVec := allQpCtrl.io.qpAttrVec
  udpRdmaPktConverter.io.udpRx << io.rx
  io.tx << udpRdmaPktConverter.io.udpTx

  val headVerifier = new HeadVerifier(numMaxQPs, busWidth)
  headVerifier.io.qpAttrVec := allQpCtrl.io.qpAttrVec
  headVerifier.io.rx << udpRdmaPktConverter.io.rdmaRx
  val rdmaRx = headVerifier.io.tx.pktFrag

  val qpVec = qpIdxVec.map(qpIdx => {
    val qp = new QP(busWidth)
    allQpCtrl.io.qpAttrVec(qpIdx) := qp.io.qpAttr
    qp.io.qpCreateOrModify << allQpCtrl.io.qpCreateOrModifyVec(qpIdx)
//    qpCtrl.io.qpStateChange(qpIdx) << qp.io.qpStateChange
//    qp.io.qpAttrUpdate := qpCtrl.io.qpAttrUpdate(qpIdx)
    qp.io.workReq << io.workReq(qpIdx)
    qp.io.recvWorkReq << io.recvWorkReq(qpIdx)
    io.workComp(qpIdx) << qp.io.workComp
    qp
  })

  val rqSelOH = allQpCtrl.io.qpAttrVec.map(_.sqpn === rdmaRx.bth.dqpn)
  val rqSelIdx = OHToUInt(rqSelOH)
  Vec(qpVec.map(_.io.rx.pktFrag)) <-/< StreamDemux(rdmaRx, rqSelIdx, numMaxQPs)

  val txVec = qpVec.map(_.io.tx.pktFrag)
  val txSel = StreamArbiterFactory.roundRobin.on(txVec)
  udpRdmaPktConverter.io.rdmaTx.pktFrag <-/< txSel

  val dmaWrReqVec = Vec(qpVec.map(_.io.dma.wr.req))
  val dmaWrRespVec = Vec(qpVec.map(_.io.dma.wr.resp))
  io.dma.wr
    .arbitReqAndDemuxRespByQpn(
      dmaWrReqVec,
      dmaWrRespVec,
      allQpCtrl.io.qpAttrVec
    )
  val dmaRdReqVec = Vec(qpVec.map(_.io.dma.rd.req))
  val dmaRdRespVec = Vec(qpVec.map(_.io.dma.rd.resp))
  io.dma.rd
    .arbitReqAndDemuxRespByQpn(
      dmaRdReqVec,
      dmaRdRespVec,
      allQpCtrl.io.qpAttrVec
    )
}

class RoCEv2(numMaxQPs: Int, busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val workReq = Vec(slave(Stream(WorkReq())), numMaxQPs)
    val recvWorkReq = Vec(slave(Stream(RecvWorkReq())), numMaxQPs)
    val workComp = Vec(master(Stream(WorkComp())), numMaxQPs)
    val rx = slave(UdpDataBus(busWidth))
    val tx = master(UdpDataBus(busWidth))
  }

  val allQpModules = new AllQpModules(numMaxQPs, busWidth)
  allQpModules.io.qpCreateOrModify << io.qpCreateOrModify
  allQpModules.io.workReq << io.workReq
  allQpModules.io.recvWorkReq << io.recvWorkReq
  io.workComp << allQpModules.io.workComp
  allQpModules.io.rx << io.rx

  val dma = new DmaHandler(busWidth)
  dma.io.dma.rd.req << allQpModules.io.dma.rd.req
  allQpModules.io.dma.rd.resp << dma.io.dma.rd.resp
  dma.io.dma.wr.req << allQpModules.io.dma.wr.req
  allQpModules.io.dma.wr.resp << dma.io.dma.wr.resp

  io.tx << allQpModules.io.tx
}

object RoCEv2 {
  def main(args: Array[String]): Unit = {
    new SpinalConfig(
      mode = Verilog,
      defaultClockDomainFrequency = FixedFrequency(200 MHz)
    ).generate(new RoCEv2(numMaxQPs = 4, BusWidth.W512))
      // SpinalVerilog
      .printPruned()
      .printPrunedIo()
  }
}
