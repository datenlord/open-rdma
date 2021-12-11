package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
// import ConstantSettings._
import StreamVec._

class ReqRespSplitter(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val reqTx = master(Stream(RdmaDataBus(busWidth)))
    val respTx = master(Stream(RdmaDataBus(busWidth)))
  }

  val isReq = OpCode.isReqPkt(io.rx.bth.opcode)
  Vec(io.reqTx, io.respTx) <> StreamDemux(
    io.rx,
    select = isReq.asUInt,
    portCount = 2
  )
}

class RetryRespSpliter(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val normalTx = master(Stream(RdmaDataBus(busWidth)))
    val retryTx = master(Stream(RdmaDataBus(busWidth)))
  }

  val isNormalResp = True // TODO: check req or resp
  Vec(io.normalTx, io.retryTx) <-/< StreamDemux(
    io.rx,
    select = isNormalResp.asUInt,
    portCount = 2
  )
}

// CNP format, Figure 349, pp. 1948, spec 1.4
class FlowCtrl(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val resp = slave(Flow(RdmaDataBus(busWidth)))
    val rx = slave(Stream(Fragment(RdmaDataBus(busWidth))))
    val tx = master(Stream(RdmaDataBus(busWidth)))
  }

  // TODO: send out CNP as soon as ECN tagged

  // TODO: Translate RdmaDataBus to UdpDataBus
  io.tx <-/< io.rx.translateWith {
    io.rx.payload
//    val udpData = UdpDataBus(busWidth)
//    udpData.assignSomeByName(io.rx.fragment)
//    udpData.udp.ip := io.qpAttr.ipv4Peer
//    udpData.udp.len := 1024 // TODO: actual packet length
//    udpData
  }
}

class QP(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val qpStateChange = master(Stream(QpStateChange()))
    val workReq = slave(Stream(WorkReq()))
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
    val dma = master(DmaBus(busWidth))
  }

  // Separate incoming requests and responses
  val reqRespSplitter = new ReqRespSplitter(busWidth)
  reqRespSplitter.io.rx << io.rx
  // val reqRx = reqRespSplitter.io.reqTx
  // val respRx = reqRespSplitter.io.respTx

  val sq = new SendQ(busWidth)
  sq.io.qpAttr := io.qpAttr
  sq.io.qpAttrUpdate := io.qpAttrUpdate
  sq.io.workReq << io.workReq

  val rq = new RecvQ(busWidth)
  rq.io.qpAttr := io.qpAttr
  rq.io.qpAttrUpdate := io.qpAttrUpdate
  rq.io.rx << reqRespSplitter.io.reqTx
  rq.io.recvWorkReq << io.recvWorkReq

  // Separate normal and retry responses
  val respSplitter = new RetryRespSpliter(busWidth)
  respSplitter.io.rx << reqRespSplitter.io.respTx

  val respHandler = new RespHandler(busWidth)
  respHandler.io.npsn := sq.io.npsn
  respHandler.io.rx << respSplitter.io.normalTx
  io.dma.wr.req << respHandler.io.dmaWrite.req

  val qpStageChangeSel = StreamArbiterFactory.roundRobin
    .onArgs(rq.io.qpStateChange, respHandler.io.qpStateChange)
  io.qpStateChange <-/< qpStageChangeSel

  val retryHandler = new RetryHandler(busWidth)
  retryHandler.io.rx << respSplitter.io.retryTx

  val dmaRdReqVec = Vec(sq.io.dmaRead.req, retryHandler.io.dmaRead.req)
  val dmaRdReqSel = StreamArbiterFactory.roundRobin.on(dmaRdReqVec)
  io.dma.rd.req <-/< dmaRdReqSel

  val dmaRdRespOH = dmaRdReqVec.map(_.psn === io.dma.rd.resp.psn)
  val dmaRdRespIdx = OHToUInt(dmaRdRespOH)
  Vec(sq.io.dmaRead.resp, retryHandler.io.dmaRead.resp) <-/< StreamDemux(
    io.dma.rd.resp,
    dmaRdRespIdx,
    portCount = 2
  )

  val reqCache = new ReqCache
  reqCache.io.portW << sq.io.reqCacheBus
  reqCache.io.portRW << respHandler.io.reqCacheBus
  reqCache.io.portR << retryHandler.io.reqCacheBus

  val flowCtrl = new FlowCtrl(busWidth)
  flowCtrl.io.qpAttr := io.qpAttr
  flowCtrl.io.resp := reqRespSplitter.io.respTx.asFlow
  val txVec = Vec(sq.io.tx, rq.io.tx, retryHandler.io.tx)
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock.on(txVec)
  flowCtrl.io.rx <-/< txSel

  io.tx <-/< flowCtrl.io.tx
}
