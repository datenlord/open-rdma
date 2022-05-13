package rdma

import spinal.core._
import spinal.lib._

import ConstantSettings._
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
class HeadVerifier(numMaxQPs: Int, busWidth: BusWidth.Value) extends Component {
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
      L"${REPORT_TIME} time: HeadVerifier dropped one packet, PSN=${rdmaData.bth.psn}, opcode=${rdmaData.bth.opcode}, dqpn=${rdmaData.bth.dqpn}, dqpIdxOH=${dqpIdxOH}, dqpState=${dqpState}"
    )
  }
  io.tx.pktFrag <-/< io.rx.pktFrag.throwWhen(cond).translateWith(rdmaData)
}

class AllQpCtrl(numMaxQPs: Int) extends Component {
  val io = new Bundle {
    val qpAttrVec = in(Vec(QpAttrData(), numMaxQPs))
    val qpCreateOrModify = slave(QpCreateOrModifyBus())
    val qpCreateOrModifyVec = Vec(master(QpCreateOrModifyBus()), numMaxQPs)
    val full = out(Bool())
  }

  // QP0 and QP1 are reserved QPN
  val nextQpnReg = RegInit(U(2, QPN_WIDTH bits))

  val qpVecAvailable = io.qpAttrVec.map(_.isValid === False)
  val availableQpOH = OHMasking.first(qpVecAvailable)
  val foundQpAvailable = qpVecAvailable.orR
  io.full := !foundQpAvailable

  val modifyQpOH =
    Vec(io.qpAttrVec.map(_.sqpn === io.qpCreateOrModify.req.qpAttr.sqpn))
  val foundQpModify = modifyQpOH.orR

  val isQpCreation =
    io.qpCreateOrModify.req.modifyMask.include(QpAttrMaskEnum.QP_CREATE)
  val qpSelIdxOH = isQpCreation ? availableQpOH | modifyQpOH
  when(io.qpCreateOrModify.req.valid) {
    when(isQpCreation) {
      assert(
        assertion = foundQpAvailable,
        message = L"${REPORT_TIME} time: failed to create QP, no QP available",
        severity = FAILURE
      )
    } otherwise {
      assert(
        assertion = foundQpModify,
        message =
          L"${REPORT_TIME} time: failed to find QP with QPN=${io.qpCreateOrModify.req.qpAttr.sqpn} to modify",
        severity = FAILURE
      )
    }
  }

  when(io.qpCreateOrModify.req.fire && isQpCreation && foundQpAvailable) {
    nextQpnReg := nextQpnReg + 1
    when(nextQpnReg + 1 === 0) {
      // QP0 and QP1 are reserved QPN
      nextQpnReg := 2
    }
  }

  Vec(io.qpCreateOrModifyVec.map(_.req)) <-/< StreamOneHotDeMux(
    io.qpCreateOrModify.req.translateWith {
      val result = cloneOf(io.qpCreateOrModify.req.payloadType)
      result.qpAttr := io.qpCreateOrModify.req.qpAttr
      result.modifyMask := io.qpCreateOrModify.req.modifyMask
      when(io.qpCreateOrModify.req.valid && isQpCreation && foundQpAvailable) {
        result.qpAttr.sqpn := nextQpnReg
      }
      result
    },
    select = qpSelIdxOH.asBits
  )

  io.qpCreateOrModify.resp <-/< StreamArbiterFactory.roundRobin.transactionLock
    .on(Vec(io.qpCreateOrModifyVec.map(_.resp)))
}

class UdpRdmaPktConverter(numMaxQPs: Int, busWidth: BusWidth.Value)
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

class AllAddrCache(numMaxPDs: Int, numMaxMRsPerPD: Int) extends Component {
  val io = new Bundle {
    val pdCreateOrDelete = slave(PdCreateOrDeleteBus())
    val pdAddrCreateOrDelete = slave(PdAddrDataCreateOrDeleteBus())
    val query = slave(PdAddrCacheReadBus())
    val full = out(Bool())
  }

  val pdIdxVec = (0 until numMaxPDs)
  val pdAddrCacheVec = pdIdxVec.map(_ => {
    val pdAddrCache = new PdAddrCache(numMaxMRsPerPD)
    pdAddrCache
  })

  val addrCreateOrDelete = new Area {
    val pdAddrCreateOrDeleteIdxOH = Vec(
      pdIdxVec.map(io.pdAddrCreateOrDelete.req.pdId === _)
    )
    val foundPdAddrCreateOrDeleteIdx = pdAddrCreateOrDeleteIdxOH.orR
    when(io.pdAddrCreateOrDelete.req.valid) {
      assert(
        assertion = foundPdAddrCreateOrDeleteIdx,
        message =
          L"${REPORT_TIME} time: failed to find PD with ID=${io.pdAddrCreateOrDelete.req.pdId}",
        severity = FAILURE
      )
    }

    Vec(pdAddrCacheVec.map(_.io.addrCreateOrDelete.req)) <-/< StreamOneHotDeMux(
      io.pdAddrCreateOrDelete.req,
      select = pdAddrCreateOrDeleteIdxOH.asBits
    )

    io.pdAddrCreateOrDelete.resp <-/< StreamArbiterFactory.roundRobin.transactionLock
      .on(Vec(pdAddrCacheVec.map(_.io.addrCreateOrDelete.resp)))
  }

  val query = new Area {
    val pdAddrCacheQueryIdxOH = Vec(pdIdxVec.map(io.query.req.pdId === _))
    val foundPdAddrCacheQueryIdx = pdAddrCacheQueryIdxOH.orR
    when(io.pdAddrCreateOrDelete.req.valid) {
      assert(
        assertion = foundPdAddrCacheQueryIdx,
        message =
          L"${REPORT_TIME} time: failed to find PD with ID=${io.query.req.pdId}",
        severity = FAILURE
      )
    }

    Vec(pdAddrCacheVec.map(_.io.query.req)) <-/< StreamOneHotDeMux(
      io.query.req,
      select = pdAddrCacheQueryIdxOH.asBits
    )

    io.query.resp <-/< StreamArbiterFactory.roundRobin.transactionLock
      .on(Vec(pdAddrCacheVec.map(_.io.query.resp)))
  }

  val pdCreateOrDelete = new Area {
    val pdVec = Vec(pdIdxVec.map(_ => {
      val pdValidReg = RegInit(False)
      pdValidReg
    }))

    val isPdCreation = io.pdCreateOrDelete.req.createOrDelete === CRUD.CREATE
    val pdVecAvailable = pdVec.map(_ === False)
    val foundPdAvailable = pdVecAvailable.orR
    io.full := !foundPdAvailable
    val pdCreateIdxOH = OHMasking.first(pdVecAvailable)

    val isPdDeletion = io.pdCreateOrDelete.req.createOrDelete === CRUD.DELETE
    val pdDeleteIdxOH = Vec(pdIdxVec.map(io.pdCreateOrDelete.req.pdId === _))
    val foundPdDelete = pdDeleteIdxOH.orR

    val pdSelIdxOH = isPdCreation ? pdCreateIdxOH | pdDeleteIdxOH
    when(io.pdCreateOrDelete.req.valid) {
      when(isPdCreation && foundPdAvailable) {
        pdVec.oneHotAccess(pdSelIdxOH.asBits) := io.pdCreateOrDelete.req.fire
      } elsewhen (isPdDeletion && foundPdDelete) {
        pdVec.oneHotAccess(pdSelIdxOH.asBits) := !io.pdCreateOrDelete.req.fire
      }
    }
    io.pdCreateOrDelete.resp <-/< io.pdCreateOrDelete.req.translateWith {
      val result = cloneOf(io.pdCreateOrDelete.resp.payloadType)
      result.successOrFailure := (isPdCreation && foundPdAvailable) || (isPdDeletion && foundPdDelete)
      result.pdId := OHToUInt(pdSelIdxOH).resize(PD_ID_WIDTH).asBits
      result
    }
  }
}

// TODO: RoCE should have QP1?
class AllQpModules(numMaxQPs: Int, busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpCreateOrModify = slave(QpCreateOrModifyBus())
    val pdCreateOrDelete = slave(PdCreateOrDeleteBus())
    val pdAddrCreateOrDelete = slave(PdAddrDataCreateOrDeleteBus())
    val workReq = Vec(slave(Stream(WorkReq())), numMaxQPs)
    val rxWorkReq = Vec(slave(Stream(RxWorkReq())), numMaxQPs)
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
    qp.io.workReq << io.workReq(qpIdx)
    qp.io.rxWorkReq << io.rxWorkReq(qpIdx)
    io.workComp(qpIdx) << qp.io.workComp
    qp
  })

  val rqSelOH = allQpCtrl.io.qpAttrVec.map(_.sqpn === rdmaRx.bth.dqpn)
  val rqSelIdx = OHToUInt(rqSelOH)
  Vec(qpVec.map(_.io.rx.pktFrag)) <-/< StreamDemux(rdmaRx, rqSelIdx, numMaxQPs)

  val txVec = qpVec.map(_.io.tx.pktFrag)
  val txSel = StreamArbiterFactory.roundRobin.transactionLock.on(txVec)
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

  val allAddrCache = new AllAddrCache(MAX_PD, MAX_MR_PER_PD)
  allAddrCache.io.pdCreateOrDelete << io.pdCreateOrDelete
  allAddrCache.io.pdAddrCreateOrDelete << io.pdAddrCreateOrDelete
  allAddrCache.io.query.req <-/< StreamArbiterFactory.roundRobin.transactionLock
    .on(qpVec.map(_.io.pdAddrCacheQuery.req))
  val addrCacheRespTargetQpIdxOH =
    qpVec.map(_.io.qpAttr.sqpn === allAddrCache.io.query.resp.sqpn)
  Vec(qpVec.map(_.io.pdAddrCacheQuery.resp)) <-/< StreamOneHotDeMux(
    allAddrCache.io.query.resp,
    select = addrCacheRespTargetQpIdxOH.asBits()
  )
}

class RoCEv2(numMaxQPs: Int, busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpCreateOrModify = slave(QpCreateOrModifyBus())
    val pdCreateOrDelete = slave(PdCreateOrDeleteBus())
    val pdAddrCreateOrDelete = slave(PdAddrDataCreateOrDeleteBus())
    val workReq = Vec(slave(Stream(WorkReq())), numMaxQPs)
    val rxWorkReq = Vec(slave(Stream(RxWorkReq())), numMaxQPs)
    val workComp = Vec(master(Stream(WorkComp())), numMaxQPs)
    val rx = slave(UdpDataBus(busWidth))
    val tx = master(UdpDataBus(busWidth))
  }

  val allQpModules = new AllQpModules(numMaxQPs, busWidth)
  allQpModules.io.qpCreateOrModify << io.qpCreateOrModify
  allQpModules.io.pdCreateOrDelete << io.pdCreateOrDelete
  allQpModules.io.pdAddrCreateOrDelete << io.pdAddrCreateOrDelete
  allQpModules.io.workReq << io.workReq
  allQpModules.io.rxWorkReq << io.rxWorkReq
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
      defaultClockDomainFrequency = FixedFrequency(200 MHz),
      defaultConfigForClockDomains = ClockDomainConfig(resetKind = SYNC),
      mode = SystemVerilog,
      oneFilePerComponent = true,
      targetDirectory = "./rtl",
//      romReuse = true, // TODO: turn on once 1.6.5 is released
      verbose = true
    ).generate(new RoCEv2(numMaxQPs = 4, BusWidth.W512))
//      .printPruned()
//      .printPrunedIo()
//      .printZeroWidth()
  }
}
