package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
// import ConstantSettings._
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
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
  }

  val rdmaData = io.rx.payload

  val validHeader =
    Transports.isSupportedType(rdmaData.bth.transport) && OpCode.isValidCode(
      rdmaData.bth.opcode
    )
  val qpStateValid = io.qpAttrVec.map(qpAttr =>
    qpAttr.sqpn === rdmaData.bth.dqpn && QpState
      .allowRecv(rdmaData.bth.opcode, qpAttr.state)
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
    val qpAttrUpdate = Vec(out(QpAttrUpdateNotifier()), numMaxQPs)
    val qpStateChange = Vec(slave(Stream(QpStateChange())), numMaxQPs)
    val full = out(Bool())
  }

  val qpIdxVec = (0 until numMaxQPs)
  val qpAttrVec = Vec(qpIdxVec.map(_ => {
    // RegInit(QpAttrData().setDefaultVal())
    Reg(QpAttrData()).setDefaultVal()
  }))
  io.qpAttrVec := qpAttrVec

  for (qpIdx <- qpIdxVec) {
    io.qpAttrUpdate(qpIdx).pulseRqPsnReset := False
    io.qpAttrUpdate(qpIdx).pulseSqPsnReset := False

    // Change QPS from RQ or SQ
    io.qpStateChange(qpIdx).ready := True
    when(io.qpStateChange(qpIdx).valid) {
      qpAttrVec(qpIdx).state := io.qpStateChange(qpIdx).changeToState
    }
  }

  val qpVecAvailable = qpAttrVec.map(_.isValid === False)
  io.full := !(qpVecAvailable.asBits().orR)

  val findQpStageOut =
    StreamPipeStage(input = io.qpCreateOrModify)((inputPayload, inputValid) => {
      val qpCreation =
        inputValid && inputPayload.modifyMask === QpAttrMask.QP_CREATE.id
      val rqPsnReset =
        inputValid && inputPayload.modifyMask === QpAttrMask.QP_RQ_PSN.id
      val sqPsnReset =
        inputValid && inputPayload.modifyMask === QpAttrMask.QP_SQ_PSN.id
      val availableQpOH = OHMasking.first(qpVecAvailable)
      val modifyQpOH =
        Vec(qpAttrVec.map(_.sQPN === inputPayload.sqpn))
      val qpSelIdx = OHToUInt(qpCreation ? availableQpOH | modifyQpOH)

      val rslt = TupleBundle(qpCreation, rqPsnReset, sqPsnReset, qpSelIdx)
      rslt
    })

  val notifyStage =
    StreamPipeStageSink(input = findQpStageOut)((inputPayload, inputValid) => {
      val (qpCreation, rqPsnReset, sqPsnReset, qpSelIdx) = asTuple(inputPayload)
      io.qpAttrUpdate(qpSelIdx)
        .pulseRqPsnReset := inputValid && (qpCreation || rqPsnReset)
      io.qpAttrUpdate(qpSelIdx)
        .pulseSqPsnReset := inputValid && (qpCreation || sqPsnReset)

      NoData
    })(sink = StreamSink(NoData))
}

// TODO: RoCE should have QP1?
class AllQpModules(numMaxQPs: Int, busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val workReq = slave(Stream(WorkReq()))
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
    val dma = master(DmaBus(busWidth))
  }

  val qpIdxVec = (0 until numMaxQPs)
  val qpCtrl = new QpCtrl(numMaxQPs)
  qpCtrl.io.qpCreateOrModify << io.qpCreateOrModify

  val headVerifier = new HeadVerifier(numMaxQPs, busWidth)
  headVerifier.io.qpAttrVec := qpCtrl.io.qpAttrVec
  headVerifier.io.rx << io.rx
  val rdmaRx = headVerifier.io.tx

  val qpVec = qpIdxVec.map(qpIdx => {
    val qp = new QP(busWidth)
    qp.io.qpAttr := qpCtrl.io.qpAttrVec(qpIdx)
    qpCtrl.io.qpStateChange(qpIdx) << qp.io.qpStateChange
    qp.io.qpAttrUpdate := qpCtrl.io.qpAttrUpdate(qpIdx)
    qp
  })

  val sqSelOH = qpCtrl.io.qpAttrVec.map(_.sQPN === io.workReq.sqpn)
  val sqSelIdx = OHToUInt(sqSelOH)
  Vec(qpVec.map(_.io.workReq)) <-/< StreamDemux(io.workReq, sqSelIdx, numMaxQPs)

  val rqSelOH = qpCtrl.io.qpAttrVec.map(_.sQPN === rdmaRx.bth.dqpn)
  val rqSelIdx = OHToUInt(rqSelOH)
  Vec(qpVec.map(_.io.rx)) <-/< StreamDemux(rdmaRx, rqSelIdx, numMaxQPs)

  val rqRecvWorkReqSelOH =
    qpCtrl.io.qpAttrVec.map(_.sQPN === io.recvWorkReq.sqpn)
  val rqRecvWorkReqSelIdx = OHToUInt(rqRecvWorkReqSelOH)
  Vec(qpVec.map(_.io.recvWorkReq)) <-/< StreamDemux(
    io.recvWorkReq,
    rqRecvWorkReqSelIdx,
    numMaxQPs
  )

  val txVec = qpVec.map(_.io.tx)
  val txSel = StreamArbiterFactory.roundRobin.on(txVec)
  io.tx <-/< txSel

  val dmaWrReqVec = qpVec.map(_.io.dma.wr.req)
  val dmaWrReqSel = StreamArbiterFactory.roundRobin.fragmentLock.on(dmaWrReqVec)
  io.dma.wr.req <-/< dmaWrReqSel

  val dmaWrRespOH =
    qpCtrl.io.qpAttrVec.map(_.sQPN === io.dma.wr.resp.qpn)
  val dmaWrRespIdx = OHToUInt(dmaWrRespOH)
  Vec(qpVec.map(_.io.dma.wr.resp)) <> StreamDemux(
    io.dma.wr.resp,
    dmaWrRespIdx,
    numMaxQPs
  )

  val dmaRdReqVec = qpVec.map(_.io.dma.rd.req)
  val dmaRdReqSel = StreamArbiterFactory.roundRobin.on(dmaRdReqVec)
  io.dma.rd.req <-/< dmaRdReqSel

  val dmaRdRespOH =
    qpCtrl.io.qpAttrVec.map(_.sQPN === io.dma.rd.resp.qpn)
  val dmaRdRespIdx = OHToUInt(dmaRdRespOH)
  Vec(qpVec.map(_.io.dma.rd.resp)) <-/< StreamDemux(
    io.dma.rd.resp,
    dmaRdRespIdx,
    numMaxQPs
  )
}

class RoCEv2(numMaxQPs: Int, busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val workReq = slave(Stream(WorkReq()))
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
  }

  val allQpModules = new AllQpModules(numMaxQPs, busWidth)
  allQpModules.io.qpCreateOrModify << io.qpCreateOrModify
  allQpModules.io.workReq << io.workReq
  allQpModules.io.recvWorkReq << io.recvWorkReq
  allQpModules.io.rx << io.rx

  val dma = new DmaHandler(busWidth)
  dma.io.dma.rd.req << allQpModules.io.dma.rd.req
  allQpModules.io.dma.rd.resp << dma.io.dma.rd.resp
  dma.io.dma.wr.req << allQpModules.io.dma.wr.req
  allQpModules.io.dma.wr.resp << dma.io.dma.wr.resp

  io.tx <-/< allQpModules.io.tx
}

object RoCEv2 {
  def main(args: Array[String]): Unit = {
    SpinalVerilog(new RoCEv2(numMaxQPs = 4, BusWidth.W512))
      .printPrunedIo()
      .printPruned()
  }
}
