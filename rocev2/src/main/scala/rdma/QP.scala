package rdma

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._

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
//  val reqTx = tx(0)
//  val respTx = tx(1)
//  io.reqTx <-/< reqTx
//  io.respTx <-/< respTx
}

class RetryRespSpliter(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val normalTx = master(Stream(RdmaDataBus(busWidth)))
    val retryTx = master(Stream(RdmaDataBus(busWidth)))
  }

  val isNormalResp = True // TODO: check req or resp
  Vec(io.normalTx, io.retryTx) <> StreamDemux(
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
    val tx = master(Stream(UdpDataBus(busWidth)))
  }

  // TODO: send out CNP as soon as ECN tagged

  // TODO: Translate RdmaDataBus to UdpDataBus
  io.tx <-/< io.rx.translateWith {
    val udpData = UdpDataBus(busWidth)
    udpData.assignSomeByName(io.rx.fragment)
    udpData.udp.ip := io.qpAttr.ipv4Peer
    udpData.udp.len := 1024 // TODO: actual packet length
    udpData
  }
}

class QP(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpStateUpdate = master(Stream(Bits(QP_STATE_WIDTH bits)))
    val qpAttrUpdate = slave(Stream(Bits(QP_ATTR_MASK_WIDTH bits)))
    val workReq = slave(Stream(WorkReq()))
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(UdpDataBus(busWidth)))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
    val dmaWriteReq = master(Stream(Fragment(DmaWriteReq())))
    val dmaWriteResp = slave(Stream(DmaWriteResp()))
  }

  // Seperate incoming requests and responses
  val reqRespSplitter = new ReqRespSplitter(busWidth)
  reqRespSplitter.io.rx <-/< io.rx
  val reqRx = reqRespSplitter.io.reqTx
  val respRx = reqRespSplitter.io.respTx

  val sq = new SendQ(busWidth)
  sq.io.qpAttr := io.qpAttr
  sq.io.workReq <-/< io.workReq

  val rq = new RecvQ(busWidth)
  rq.io.qpAttr := io.qpAttr
  rq.io.rx <-/< reqRx

  Vec(sq.io.qpAttrUpdate, rq.io.qpAttrUpdate) <> StreamFork(
    io.qpAttrUpdate,
    portCount = 2
  )

  // Seperate normal and retry responses
  val respSpliter = new RetryRespSpliter(busWidth)
  respSpliter.io.rx <-/< respRx
  val normalResp = respSpliter.io.normalTx
  val retryResp = respSpliter.io.retryTx

  val respHandler = new RespHandler(busWidth)
  respHandler.io.npsn := sq.io.npsn
  respHandler.io.rx <-/< normalResp
  io.dmaWriteReq <-/< respHandler.io.dmaWriteReq
  io.qpStateUpdate <-/< respHandler.io.qpStateUpdate
  respHandler.io.dmaWriteResp <-/< io.dmaWriteResp

  val retryHandler = new RetryHandler(busWidth)
  //retryHandler.io.qpAttr := io.qpAttr
  retryHandler.io.rx <-/< retryResp

  val dmaRdReqVec = Vec(sq.io.dmaReadReq, retryHandler.io.dmaReadReq)
  val dmaRdReqSel = StreamArbiterFactory.roundRobin.on(dmaRdReqVec)
  io.dmaReadReq <-/< dmaRdReqSel

  val dmaRdRespOH = dmaRdReqVec.map(_.psn === io.dmaReadResp.psn)
  val dmaRdRespIdx = OHToUInt(dmaRdRespOH)
  Vec(sq.io.dmaReadResp, retryHandler.io.dmaReadResp) <> StreamDemux(
    io.dmaReadResp,
    dmaRdRespIdx,
    portCount = 2
  )

  val reqCache = new ReqCache(busWidth)
  reqCache.io.portW.writeReq <-/< sq.io.cacheWriteReq
  reqCache.io.portRW.rwReq <-/< respHandler.io.cacheReq
  respHandler.io.cacheResp <-/< reqCache.io.portRW.readResp
  reqCache.io.portR.readReq <-/< retryHandler.io.cacheReadReq
  retryHandler.io.cacheReadResp <-/< reqCache.io.portR.readResp

  val flowCtrl = new FlowCtrl(busWidth)
  val txVec = Vec(sq.io.tx, rq.io.tx, retryHandler.io.tx)
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock.on(txVec)
  flowCtrl.io.qpAttr := io.qpAttr
  flowCtrl.io.resp := respRx.asFlow
  flowCtrl.io.rx <-/< txSel
  io.tx << flowCtrl.io.tx
}
