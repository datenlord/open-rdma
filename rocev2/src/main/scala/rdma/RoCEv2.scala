package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import Constants._

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
    val rx = slave(Stream(UdpDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
  }

  val rdmaData = RdmaDataBus(busWidth)
  rdmaData.data := io.rx.data
  rdmaData.mty := io.rx.mty

  val validHeader =
    Transports.isSupportedType(rdmaData.bth.transport) && OpCode.isValidCode(
      rdmaData.bth.opcode
    )
  val qpStateValid = io.qpAttrVec.map(qpAttr =>
    qpAttr.sqpn === rdmaData.bth.dqpn && QpState.allowRecv(
      rdmaData.bth.opcode,
      qpAttr.state
    )
  )
  val cond = !validHeader || !(qpStateValid.asBits().orR)
  when(io.rx.valid && cond) {
    report(
      L"HeadVerifier dropped one packet, psn=${rdmaData.bth.psn}, opcode=${rdmaData.bth.opcode}, dqpn=${rdmaData.bth.dqpn}"
    )
  }
  io.tx <-/< io.rx.throwWhen(cond).translateWith(rdmaData)
}

class QpCtrl(numMaxQPs: Int) extends Component {
  val io = new Bundle {
    val qpAttrVec = out(Vec(QpAttrData(), numMaxQPs))
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val qpStateUpdate = Vec(slave(Stream(Bits(QP_STATE_WIDTH bits))), numMaxQPs)
    val qpAttrUpdate =
      Vec(master(Stream(Bits(QP_ATTR_MASK_WIDTH bits))), numMaxQPs)
  }

  val qpIdxVec = (0 until numMaxQPs)
  val qpAttrVec = Vec(qpIdxVec.map(_ => {
    // RegInit(QpAttrData().setDefaultVal())
    Reg(QpAttrData()).setDefaultVal()
  }))
  io.qpAttrVec := qpAttrVec

  val qpCreation = io.qpCreateOrModify.modifyMask === QpAttrMask.QP_CREATE.id
  val availableIdx = OHMasking.first(qpAttrVec.map(_.isValid() === False))
  val modifyQpIdx = Vec(qpAttrVec.map(_.sQPN() === io.qpCreateOrModify.sqpn))
  val qpSel = OHToUInt(qpCreation ? availableIdx | modifyQpIdx)
  io.qpAttrUpdate <> StreamDemux(
    io.qpCreateOrModify.translateWith(io.qpCreateOrModify.modifyMask),
    qpSel,
    numMaxQPs
  )

  for (qpIdx <- qpIdxVec) {
    io.qpStateUpdate(qpIdx).ready := False
    when(io.qpStateUpdate(qpIdx).valid) {
      qpAttrVec(qpIdx).state := io.qpStateUpdate(qpIdx).payload
      io.qpStateUpdate(qpIdx).ready := True
    }
  }
}

// TODO: RoCE should have QP1?
class QPs(numMaxQPs: Int, busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val workReq = slave(Stream(WorkReq()))
    val rx = slave(Stream(UdpDataBus(busWidth)))
    val tx = master(Stream(UdpDataBus(busWidth)))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val dmaWriteReq = master(Stream(Fragment(DmaWriteReq())))
    val dmaWriteResp = slave(Stream(DmaWriteResp()))
  }

  val qpIdxVec = (0 until numMaxQPs)
  val qpCtrl = new QpCtrl(numMaxQPs)
  qpCtrl.io.qpCreateOrModify <-/< io.qpCreateOrModify

  val headVerifier = new HeadVerifier(numMaxQPs, busWidth)
  headVerifier.io.qpAttrVec := qpCtrl.io.qpAttrVec
  headVerifier.io.rx <-/< io.rx
  val rdmaRx = headVerifier.io.tx

  val qpVec = qpIdxVec.map(qpIdx => {
    val qp = new QP(busWidth)
    qp.io.qpAttr := qpCtrl.io.qpAttrVec(qpIdx)
    qpCtrl.io.qpStateUpdate(qpIdx) <-/< qp.io.qpStateUpdate
    qp.io.qpAttrUpdate <-/< qpCtrl.io.qpAttrUpdate(qpIdx)
    qp
  })

  val sqSelOH = qpCtrl.io.qpAttrVec.map(_.sQPN() === io.workReq.sqpn)
  val sqSelIdx = OHToUInt(sqSelOH)
  Vec(qpVec.map(_.io.workReq)) <> StreamDemux(io.workReq, sqSelIdx, numMaxQPs)

  val rqSelOH = qpCtrl.io.qpAttrVec.map(_.sQPN() === rdmaRx.bth.dqpn)
  val rqSelIdx = OHToUInt(rqSelOH)
  Vec(qpVec.map(_.io.rx)) <> StreamDemux(rdmaRx, rqSelIdx, numMaxQPs)

  val txVec = qpVec.map(_.io.tx)
  val txSel = StreamArbiterFactory.roundRobin.on(txVec)
  io.tx <-/< txSel

  val dmaWrReqVec = qpVec.map(_.io.dmaWriteReq)
  val dmaWrReqSel = StreamArbiterFactory.roundRobin.fragmentLock.on(dmaWrReqVec)
  io.dmaWriteReq <-/< dmaWrReqSel

  val dmaWrRespOH =
    qpCtrl.io.qpAttrVec.map(_.sQPN() === io.dmaWriteResp.qpn)
  val dmaWrRespIdx = OHToUInt(dmaWrRespOH)
  Vec(qpVec.map(_.io.dmaWriteResp)) <> StreamDemux(
    io.dmaWriteResp,
    dmaWrRespIdx,
    numMaxQPs
  )

  val dmaRdReqVec = qpVec.map(_.io.dmaReadReq)
  val dmaRdReqSel = StreamArbiterFactory.roundRobin.on(dmaRdReqVec)
  io.dmaReadReq <-/< dmaRdReqSel

  val dmaRdRespOH =
    qpCtrl.io.qpAttrVec.map(_.sQPN() === io.dmaReadResp.qpn)
  val dmaRdRespIdx = OHToUInt(dmaRdRespOH)
  Vec(qpVec.map(_.io.dmaReadResp)) <> StreamDemux(
    io.dmaReadResp,
    dmaRdRespIdx,
    numMaxQPs
  )
}

class RoCEv2(numMaxQPs: Int, busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val workReq = slave(Stream(WorkReq()))
    val rx = slave(Stream(UdpDataBus(busWidth)))
    val tx = master(Stream(UdpDataBus(busWidth)))
  }

  val qpModule = new QPs(numMaxQPs, busWidth)
  qpModule.io.qpCreateOrModify <-/< io.qpCreateOrModify
  qpModule.io.workReq <-/< io.workReq
  qpModule.io.rx <-/< io.rx
  io.tx <-/< qpModule.io.tx

  val dma = new DmaHandler()
  dma.io.dmaReadReq <-/< qpModule.io.dmaReadReq
  qpModule.io.dmaReadResp <-/< dma.io.dmaReadResp
  dma.io.dmaWriteReq <-/< qpModule.io.dmaWriteReq
  qpModule.io.dmaWriteResp <-/< dma.io.dmaWriteResp
}

object RoCEv2 {
  def main(args: Array[String]): Unit = {
    SpinalVerilog(new RoCEv2(numMaxQPs = 4, BusWidth.W512))
      .printPrunedIo()
      .printPruned()
  }
}
