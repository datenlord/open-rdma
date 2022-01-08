package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import ConstantSettings._
// import RdmaConstants._
import StreamVec._

class ReqRespSplitter(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(RdmaDataBus(busWidth))
    val reqTx = master(RdmaDataBus(busWidth))
    val respTx = master(RdmaDataBus(busWidth))
  }

  // val isReq = OpCode.isReqPkt(io.rx.pktFrag.bth.opcode)
  val isResp = OpCode.isRespPkt(io.rx.pktFrag.bth.opcode) || OpCode.isCnpPkt(
    io.rx.pktFrag.bth.transport,
    io.rx.pktFrag.bth.opcode
  )
  Vec(io.reqTx.pktFrag, io.respTx.pktFrag) <-/< StreamDemux(
    io.rx.pktFrag,
    select = isResp.asUInt,
    portCount = 2
  )
}

//class RetryRespSplitter(busWidth: BusWidth) extends Component {
//  val io = new Bundle {
//    val rx = slave(RdmaDataBus(busWidth))
//    val normalTx = master(RdmaDataBus(busWidth))
//    val retryTx = master(RdmaDataBus(busWidth))
//  }
//
//  val isNormalResp = True // TODO: check req or resp
//  Vec(io.normalTx.pktFrag, io.retryTx.pktFrag) <-/< StreamDemux(
//    io.rx.pktFrag,
//    select = isNormalResp.asUInt,
//    portCount = 2
//  )
//}

// CNP format, Figure 349, pp. 1948, spec 1.4
class FlowCtrl(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val resp = slave(Flow(Fragment(RdmaDataPacket(busWidth))))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
  }

  // TODO: send out CNP as soon as ECN tagged

  // TODO: Translate RdmaDataBus to UdpDataBus
  io.tx.pktFrag <-/< io.rx.pktFrag
//  io.tx <-/< io.rx.translateWith {
//    io.rx.payload
//    val udpData = UdpDataBus(busWidth)
//    udpData.assignSomeByName(io.rx.fragment)
//    udpData.udp.ip := io.qpAttr.ipv4Peer
//    udpData.udp.len := 1024 // TODO: actual packet length
//    udpData
//  }
}

class QpCtrl extends Component {
  val io = new Bundle {
    val psnInc = in(PsnIncNotifier())
    val nakNotify = in(NakNotifier())
    val rnrNakSeqClearNotifier = in(RnrNakSeqClear())
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val qpAttr = out(QpAttrData())
    val recvQCtrl = out(RecvQCtrl())
    val sendQCtrl = out(SendQCtrl())
  }
  val qpAttr = RegInit(QpAttrData().initOrReset())
  io.qpAttr := qpAttr

  io.qpCreateOrModify.ready := True
  when(io.qpCreateOrModify.valid) {
    // TODO: modify QP attributes by mask
    qpAttr := io.qpCreateOrModify
  }

  // Increase PSN
  when(io.psnInc.rq.epsn.inc) {
    qpAttr.epsn := qpAttr.epsn + io.psnInc.rq.epsn.incVal
    // Update RQ previous received request opcode
    qpAttr.rqPreReqOpCode := io.psnInc.rq.epsn.preReqOpCode
  }
  when(io.psnInc.sq.npsn.inc) {
    qpAttr.npsn := qpAttr.npsn + io.psnInc.sq.npsn.incVal
  }
  when(io.psnInc.rq.opsn.inc) {
    qpAttr.rqOutPsn := qpAttr.rqOutPsn + io.psnInc.rq.opsn.incVal
  }
  when(io.psnInc.sq.opsn.inc) {
    qpAttr.sqOutPsn := qpAttr.sqOutPsn + io.psnInc.sq.opsn.incVal
  }

  // Change QPS
  when(io.nakNotify.hasFatalNak()) {
    qpAttr.state := QpState.ERR.id
  }

  when(io.nakNotify.rq.reqCheck.seqErr) {
    qpAttr.nakSeqTrigger := True
  }

//  val rnrTimeOutReg = RegInit(True)
  when(io.nakNotify.rq.rnr.pulse) {
    // Set ePSN to RNR PSN
    qpAttr.rnrTrigger := True
//    rnrTimeOutReg := False
    qpAttr.rqPreReqOpCode := io.nakNotify.rq.rnr.preOpCode
    qpAttr.epsn := io.nakNotify.rq.rnr.psn

    assert(
      assertion = qpAttr.rnrTrigger && io.nakNotify.rq.rnr.pulse,
      message = L"""there's already a RNR NAK sent PSN=${qpAttr.epsn},
        rnrTriggerReg=${qpAttr.rnrTrigger}, but there's another RNR NAK to send:
        io.nakNotify.rq.rnr.pulse=${io.nakNotify.rq.rnr.pulse},
        io.nakNotify.rq.rnr.psn=${io.nakNotify.rq.rnr.psn}""",
      severity = FAILURE
    )
  }

  // TODO: RNR wait timer
  // MIN_RNR_TIMER = 0.01ms, freq = 200MHz, timer count = 2000
  val rnrTimer = Timeout(time = 0.01 ms)
  when(!qpAttr.rnrTrigger) {
    rnrTimer.clear()
  }
//  when(rnrTimer.state) {
//    rnrTimeOutReg := True
//  }
  when(io.rnrNakSeqClearNotifier.pulse) {
    // RNR is cleared
    qpAttr.rnrTrigger := False
    // NAK SEQ is cleared
    qpAttr.nakSeqTrigger := False

    assert(
      assertion = !rnrTimer.state,
      message =
        L"""rnr timer is not out but receive rnr clear pulse, rnrTimer.state=${rnrTimer.state},
          rnrNakSeqClearNotifier.pulse=${io.rnrNakSeqClearNotifier.pulse}""",
      severity = FAILURE
    )
  }

  // Flush RQ if state error or RNR sent in next cycle
  val isQpStateError = qpAttr.state === QpState.ERR.id
  io.sendQCtrl.flush := isQpStateError
  io.sendQCtrl.fence := qpAttr.fence
  io.sendQCtrl.psnBeforeFence := qpAttr.psnBeforeFence

  io.recvQCtrl.stateErrFlush := isQpStateError
  io.recvQCtrl.nakSeqTrigger := qpAttr.nakSeqTrigger
  io.recvQCtrl.rnrFlush := qpAttr.rnrTrigger
  io.recvQCtrl.rnrTimeOut := rnrTimer.state
  io.recvQCtrl.flush := io.recvQCtrl.stateErrFlush || io.recvQCtrl.rnrFlush
}

class QP(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = out(QpAttrData())
    val qpCreateOrModify = slave(Stream(QpAttrData()))
    val workReq = slave(Stream(WorkReq()))
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val workComp = master(Stream(WorkComp()))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val dma = master(DmaBus(busWidth))
  }

  val sq = new SendQ(busWidth)
  val rq = new RecvQ(busWidth)

  val qpCtrl = new QpCtrl
  io.qpAttr := qpCtrl.io.qpAttr
  qpCtrl.io.qpCreateOrModify << io.qpCreateOrModify
  qpCtrl.io.psnInc.rq := rq.io.psnInc
  qpCtrl.io.psnInc.sq := sq.io.psnInc
  qpCtrl.io.rnrNakSeqClearNotifier := rq.io.rnrNakSeqClearNotifier
  qpCtrl.io.nakNotify.rq := rq.io.nakNotifier
  qpCtrl.io.nakNotify.sq := sq.io.nakNotifier

  // Separate incoming requests and responses
  val reqRespSplitter = new ReqRespSplitter(busWidth)
  reqRespSplitter.io.rx << io.rx

  sq.io.qpAttr := io.qpAttr
  sq.io.workReq << io.workReq

  rq.io.qpAttr := io.qpAttr
  rq.io.recvQCtrl := qpCtrl.io.recvQCtrl
  rq.io.rx << reqRespSplitter.io.reqTx
  rq.io.recvWorkReq << io.recvWorkReq

  val respHandler = new RespHandler(busWidth)
  respHandler.io.qpAttr := qpCtrl.io.qpAttr
  respHandler.io.rx << reqRespSplitter.io.respTx

  val retryHandler = new RetryHandler(busWidth)
  retryHandler.io.rx << respHandler.io.txRetryResp

  // TODO: connect WC
  val workCompOut = new WorkCompOut()
  workCompOut.io.rqSendWriteWorkComp << rq.io.sendWriteWorkComp
  workCompOut.io.sqWorkComp << respHandler.io.workComp
  io.workComp << workCompOut.io.workCompTx

  // TODO: AddrCache should not be per QP structure
  val addrCache = new AddrCache()
  addrCache.io.rqCacheRead << rq.io.addrCacheRead
  addrCache.io.sqCacheRead << sq.io.addrCacheRead
  addrCache.io.respCacheRead << respHandler.io.addrCacheRead
//  addrCache.io.retryCacheRead << retryHandler.io.addrCacheRead

  val dmaRdReqVec = Vec(
    sq.io.dma.dmaRdReqVec ++ rq.io.dma.dmaRdReqVec ++ retryHandler.io.dma.dmaRdReqVec
  )
  val dmaRdRespVec = Vec(
    sq.io.dma.dmaRdRespVec ++ rq.io.dma.dmaRdRespVec ++ retryHandler.io.dma.dmaRdRespVec
  )
  val dmaWrReqVec = Vec(rq.io.dma.dmaWrReqVec :+ respHandler.io.dmaWrite.req)
  val dmaWrRespVec = Vec(rq.io.dma.atomic.wr.resp, workCompOut.io.dmaWrite.resp)
  io.dma.rd.arbitReq(dmaRdReqVec)
  io.dma.rd.forkResp(dmaRdRespVec) // TODO: use opcode to demux DMA response
  io.dma.wr.arbitReq(dmaWrReqVec)
  io.dma.wr.forkResp(dmaWrRespVec) // TODO: use opcode to demux DMA response

  // val workReqCache = new QueryCache(UInt(), CachedWorkReq(), depth = PENDING_REQ_NUM, portCount = 1)
  val workReqCache = new WorkReqCache(depth = PENDING_REQ_NUM)
  respHandler.io.cachedWorkReqPop << workReqCache.io.pop
  workReqCache.io.push << sq.io.workReqCachePush
  workReqCache.io.flush := qpCtrl.io.sendQCtrl.flush
  workReqCache.io.queryBus << retryHandler.io.workReqQuery

  val flowCtrl = new FlowCtrl(busWidth)
  flowCtrl.io.qpAttr := io.qpAttr
  flowCtrl.io.resp := reqRespSplitter.io.respTx.pktFrag.asFlow
  val txVec =
    Vec(sq.io.tx.pktFrag, rq.io.tx.pktFrag, retryHandler.io.tx.pktFrag)
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock.on(txVec)
  flowCtrl.io.rx.pktFrag <-/< txSel

  io.tx.pktFrag <-/< flowCtrl.io.tx.pktFrag
}
