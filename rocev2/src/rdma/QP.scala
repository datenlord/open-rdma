package rdma

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

//import ConstantSettings._
import RdmaConstants._
import StreamVec._

class ReqRespSplitter(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val rx = slave(RdmaDataBus(busWidth))
    val txReq = master(RdmaDataBus(busWidth))
    val txResp = master(RdmaDataBus(busWidth))
  }

  // val isReq = OpCode.isReqPkt(io.rx.pktFrag.bth.opcode)
  val isResp = OpCode.isRespPkt(io.rx.pktFrag.bth.opcode) ||
    OpCode.isCnpPkt(
      io.rx.pktFrag.bth.transport,
      io.rx.pktFrag.bth.opcode
    )
  Vec(io.txReq.pktFrag, io.txResp.pktFrag) <-/< StreamDemux(
    io.rx.pktFrag,
    select = isResp.asUInt,
    portCount = 2
  )
}

// CNP format, Figure 349, pp. 1948, spec 1.4
class FlowCtrl(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val resp = slave(Flow(Fragment(RdmaDataPkt(busWidth))))
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

// TODO: implement FSM to control RNR, retry status
class QpCtrl extends Component {
  val io = new Bundle {
    val psnInc = in(PsnIncNotifier())
    val sqNotifier = in(SqNotifier())
    val rqNotifier = in(RqNotifier())
    val qpCreateOrModify = slave(QpCreateOrModifyBus())
    val qpAttr = out(QpAttrData())
    val rxQCtrl = out(RxQCtrl())
    val txQCtrl = out(TxQCtrl())
  }

  val qpAttr = RegInit(QpAttrData().init())
  io.qpAttr := qpAttr

  when(io.qpCreateOrModify.req.fire) {
    // TODO: modify QP attributes by mask
    // TODO: set QP_MAX_DEST_RD_ATOMIC
    // TODO: set response timeout
    qpAttr := io.qpCreateOrModify.req.qpAttr
  }
  // io.qpCreateOrModify.resp fires one cycle after io.qpCreateOrModify.req fires
  io.qpCreateOrModify.resp <-/< io.qpCreateOrModify.req.translateWith {
    val result = cloneOf(io.qpCreateOrModify.resp.payloadType)
    result.successOrFailure := True
    result
  }
  when(io.qpCreateOrModify.req.fire) {
    when(
      io.qpCreateOrModify.req.modifyMask.include(QpAttrMaskEnum.QP_DEST_QPN)
    ) {
      qpAttr.dqpn := io.qpCreateOrModify.req.qpAttr.dqpn
    }

    when(
      io.qpCreateOrModify.req.modifyMask.include(QpAttrMaskEnum.QP_TIMEOUT)
    ) {
      qpAttr.respTimeOut := io.qpCreateOrModify.req.qpAttr.respTimeOut
    }

    when(
      io.qpCreateOrModify.req.modifyMask
        .include(QpAttrMaskEnum.QP_MIN_RNR_TIMER)
    ) {
      qpAttr.minRnrTimeOut := io.qpCreateOrModify.req.qpAttr.minRnrTimeOut
    }

    when(
      io.qpCreateOrModify.req.modifyMask.include(QpAttrMaskEnum.QP_RETRY_CNT)
    ) {
      qpAttr.maxRetryCnt := io.qpCreateOrModify.req.qpAttr.maxRetryCnt
    }

    when(
      io.qpCreateOrModify.req.modifyMask.include(QpAttrMaskEnum.QP_RNR_RETRY)
    ) {
      qpAttr.maxRnrRetryCnt := io.qpCreateOrModify.req.qpAttr.maxRnrRetryCnt
    }

    when(io.qpCreateOrModify.req.modifyMask.include(QpAttrMaskEnum.QP_RQ_PSN)) {
      qpAttr.epsn := io.qpCreateOrModify.req.qpAttr.epsn
    }

    when(io.qpCreateOrModify.req.modifyMask.include(QpAttrMaskEnum.QP_SQ_PSN)) {
      qpAttr.npsn := io.qpCreateOrModify.req.qpAttr.npsn
    }
  }

  def errStateFsm() = new StateMachine {
    val COALESCE: State = new State with EntryPoint {
      whenIsActive {
        when(io.sqNotifier.coalesceAckDone) {
          goto(ERR_FLUSH)
        }
      }
    }

    val ERR_FLUSH: State = new State {
      whenIsActive {
        when(
          io.qpCreateOrModify.req.fire &&
            io.qpCreateOrModify.req.changeToState(QpState.RESET)
        ) {
          // TODO: check everything flushed before exit
          exit()
        }
      }
    }
  }

//  def sqRetryStateFsm() = new StateMachine {
//    val RETRY_FLUSH: State = new State with EntryPoint {
//      onEntry {
//        qpAttr.retryReason := io.sqNotifier.retry.reason
//        qpAttr.retryStartPsn := io.sqNotifier.retry.psnStart
//      }
//      whenIsActive {
//        // retryFlushDone just means first retry WR sent, it needs to wait for new responses, stop flushing responses
//        when(io.sqNotifier.retryClear.retryFlushDone) {
//          goto(RETRY)
//        }
//      }
//    }
//
//    val RETRY: State = new State {
//      whenIsActive {
//        when(io.sqNotifier.retryClear.retryWorkReqDone) {
//          exit()
//        }
//      }
//    }
//  }

//  val fenceRetryFsm = sqRetryStateFsm()
//  def fenceStateFsm() = new StateMachine {
//    val FENCE: State = new State with EntryPoint {
//      whenIsActive {
//        when(io.sqNotifier.workReqCacheEmpty) {
//          exit()
//        }
//      }
//    }
//
//    val FENCE_RETRY: State = new StateFsm(fenceRetryFsm) {
//      whenCompleted {
//        goto(FENCE)
//      }
//    }
//  }

  // SQD needs to handle retry
  def drainStateFsm() = new StateMachine {
    val DRAINING: State = new State with EntryPoint {
      whenIsActive {
        when(io.sqNotifier.workReqCacheEmpty) {
          goto(DRAINED)
        }
      }
    }

    val DRAINED: State = new State {
      whenIsActive {
        when(
          io.qpCreateOrModify.req.fire &&
            io.qpCreateOrModify.req.changeToState(QpState.RTS)
        ) {
          exit()
        }
      }
    }
  }

  val sqdFsm = drainStateFsm()
  val errFsm = errStateFsm()
  // QP FSM
  // https://www.rdmamojo.com/2012/05/05/qp-state-machine/
  // TODO: modify QP attributes according to state change requirements
  // https://www.rdmamojo.com/2013/01/12/ibv_modify_qp/
  val mainFsm = new StateMachine {
    val RESET = new State with EntryPoint
    val INIT = new State
    val ERR = new StateFsm(errFsm)
    val RTR = new State
    // TODO: how to stop internal state FSM?
    val RTS = new State // ParallelFsm(sqFsm, rqFsm)
    // SQD needs to handle retry
    val SQD = new StateFsm(sqdFsm)
    // val SQE = new State // Not used in RC

    // TODO: clear WR queue
    RESET
      .onEntry {
        qpAttr.state := QpState.RESET
      }
      .whenIsActive {
        when(
          io.qpCreateOrModify.req.fire &&
            io.qpCreateOrModify.req.changeToState(QpState.INIT)
        ) {
          goto(INIT)
        }
      }

    INIT
      .onEntry {
        qpAttr.state := QpState.INIT
      }
      .whenIsActive {
        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalErr()) {
          goto(ERR)
        } elsewhen (
          io.qpCreateOrModify.req.fire &&
            io.qpCreateOrModify.req.changeToState(QpState.RTR)
        ) {
          goto(RTR)
        }
      }

    RTR
      .onEntry {
        qpAttr.state := QpState.RTR
      }
      .whenIsActive {
        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalErr()) {
          goto(ERR)
        } elsewhen (
          io.qpCreateOrModify.req.fire &&
            io.qpCreateOrModify.req.changeToState(QpState.RTS)
        ) {
          goto(RTS)
        }
      }

    RTS
      .onEntry {
        qpAttr.state := QpState.RTS
      }
      .whenIsActive {
        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalErr()) {
          goto(ERR)
        } elsewhen (
          io.qpCreateOrModify.req.fire &&
            io.qpCreateOrModify.req.changeToState(QpState.SQD)
        ) {
          goto(SQD)
        }
      }

    SQD
      .onEntry {
        qpAttr.state := QpState.SQD
      }
      .whenIsActive {
        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalErr()) {
          // TODO: check if it needs explicit exit internal FSM
//          SQD.fsm.exitFsm() // Exit internal FSM
          goto(ERR)
        }
      }
      .whenCompleted {
        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalErr()) {
          goto(ERR)
        } otherwise {
          goto(RTS)
        }
      }

    ERR
      .onEntry {
        qpAttr.state := QpState.ERR
      }
      .whenCompleted {
        goto(RESET)
      }
  }

  val isQpErr = mainFsm.isEntering(mainFsm.ERR)

  def rqInternalFsm() = new StateMachine {
    // TODO: set RNR timer according to QP attributes
    // MIN_RNR_TIMER = 0.01ms, freq = 200MHz, timer count = 2000
    val rnrTimer =
      Timeout(time = rnrTimeOutOptionToTimeNum(MAX_RNR_TIMEOUT_OPTION))
    val rnrTimeOutThreshold = io.qpAttr.getRnrTimeOutCycleNum()
    val isRnrTimeOut = rnrTimeOutThreshold > rnrTimer.counter.value

    val isRqWorking = mainFsm.isEntering(mainFsm.RTR)

    val WAITING: State = new State with EntryPoint {
      whenIsActive {
        when(isRqWorking) {
          goto(NORMAL)
        }
      }
    }

    val NORMAL: State = new State {
      whenIsActive {
        when(isQpErr) {
          goto(WAITING)
        } elsewhen (io.rqNotifier.nak.seqErr.pulse) {
          qpAttr.rqPreReqOpCode := io.rqNotifier.nak.seqErr.preOpCode
          qpAttr.epsn := io.rqNotifier.nak.seqErr.psn
          goto(NAK_SEQ)
        } elsewhen (io.rqNotifier.nak.rnr.pulse) {
          qpAttr.rqPreReqOpCode := io.rqNotifier.nak.rnr.preOpCode
          qpAttr.epsn := io.rqNotifier.nak.rnr.psn
          goto(RNR_TIMEOUT)
        }
      }
    }

    val NAK_SEQ: State = new State {
      whenIsActive {
        when(isQpErr) {
          goto(WAITING)
        } elsewhen (io.rqNotifier.clearRnrOrNakSeq.pulse) {
          goto(NORMAL)
        }
      }
    }

    val RNR_TIMEOUT: State = new State {
      whenIsActive {
        when(isQpErr) {
          goto(WAITING)
        } elsewhen (isRnrTimeOut) {
          goto(RNR)
        }
      }
    }

    val RNR: State = new State {
      whenIsActive {
        when(isQpErr) {
          goto(WAITING)
        } elsewhen (io.rqNotifier.clearRnrOrNakSeq.pulse) {
          goto(NORMAL)
        }
      }
    }

    // No need to run RNR timer when not in RNR_TIMEOUT state
    when(!this.isActive(RNR_TIMEOUT)) {
      rnrTimer.clear()
    }

    when(this.isActive(NAK_SEQ)) {
      assert(
        assertion = !io.rqNotifier.nak.rnr.pulse,
        message =
          L"${REPORT_TIME} time: there's already a NAK SQK sent PSN=${qpAttr.epsn}, but there's another RNR NAK to send: io.rqNotifier.nak.rnr.pulse=${io.rqNotifier.nak.rnr.pulse}, io.rqNotifier.nak.rnr.psn=${io.rqNotifier.nak.rnr.psn}",
        severity = FAILURE
      )
    }
    when(this.isActive(RNR_TIMEOUT) || this.isActive(RNR)) {
      assert(
        assertion = !io.rqNotifier.nak.rnr.pulse,
        message =
          L"${REPORT_TIME} time: there's already a RNR NAK sent PSN=${qpAttr.epsn}, but there's another RNR NAK to send: io.rqNotifier.nak.rnr.pulse=${io.rqNotifier.nak.rnr.pulse}, io.rqNotifier.nak.rnr.psn=${io.rqNotifier.nak.rnr.psn}",
        severity = FAILURE
      )
    }
    when(this.isActive(RNR_TIMEOUT) && !isRnrTimeOut) {
      assert(
        assertion = Formal.stable(io.rqNotifier.clearRnrOrNakSeq.pulse),
        message =
          L"${REPORT_TIME} time: rnr timer is not timeout but receive RNR clear signal, rnrTimer.state=${rnrTimer.state}, io.rqNotifier.clearRnrOrNakSeq.pulse=${io.rqNotifier.clearRnrOrNakSeq.pulse}",
        severity = FAILURE
      )
    }
  }

//  val sqRetryFsm = sqRetryStateFsm()
  //  val fenceFsm = fenceStateFsm()
  def sqInternalFsm() = new StateMachine {
    val isSqWorking = mainFsm.isEntering(mainFsm.RTS)

    val WAITING: State = new State with EntryPoint {
      whenIsActive {
        when(isSqWorking) {
          goto(NORMAL)
        }
      }
    }

    val NORMAL: State = new State {
      whenIsActive {
        when(isQpErr) {
          goto(WAITING)
        } elsewhen (io.sqNotifier.retry.needRetry()) {
          goto(RETRY_FLUSH)
//      } elsewhen (io.sqNotifier.workReqHasFence && !io.sqNotifier.workReqCacheEmpty) {
//        goto(FENCE)
        }
      }
    }

    val RETRY_FLUSH: State = new State {
      onEntry {
        qpAttr.retryReason := io.sqNotifier.retry.reason
        qpAttr.retryStartPsn := io.sqNotifier.retry.psnStart
        qpAttr.receivedRnrTimeOut := io.sqNotifier.retry.receivedRnrTimeOut
      }
      whenIsActive {
        // retryFlushDone just means first retry WR sent, it needs to wait for new responses, stop flushing responses
        when(isQpErr) {
          goto(WAITING)
        } elsewhen (io.sqNotifier.retryClear.retryFlushDone) {
          goto(RETRY_WORK_REQ)
        }
      }
    }

    val RETRY_WORK_REQ: State = new State {
      whenIsActive {
        when(isQpErr) {
          goto(WAITING)
        } elsewhen (io.sqNotifier.retryClear.retryWorkReqDone) {
          goto(NORMAL)
        }
      }
    }

//    val RETRY: State = new StateFsm(sqRetryFsm) {
//      whenIsActive {
//        when(isQpErr) {
//          // TODO: check if it needs to exit the internal FSM
//          sqRetryFsm.exitFsm()
//          goto(WAITING)
//        }
//      }
//      whenCompleted {
//        when(isQpErr) {
//          goto(WAITING)
//        } otherwise {
//          goto(NORMAL)
//        }
//      }
//    }

//    val FENCE: State = new StateFsm(fenceFsm) {
//      whenIsActive {
//        when(!isSqWorking) {
//          fenceFsm.exitFsm()
//          goto(WAITING)
//        }
//      }
//      whenCompleted {
//        when(!isSqWorking) {
//          goto(WAITING)
//        } otherwise {
//          goto(NORMAL)
//        }
//      }
//    }
  }

  val sqFsm = sqInternalFsm()
  val rqFsm = rqInternalFsm()

  val psnCtrl = new Area {
    // TODO: check increase PSN under normal state?
    // Increase PSN
    when(mainFsm.isActive(mainFsm.RTR) || mainFsm.isActive(mainFsm.RTS)) {
      when(io.psnInc.rq.epsn.inc) {
        qpAttr.epsn := qpAttr.epsn + io.psnInc.rq.epsn.incVal
        // Update RQ previous received request opcode
        qpAttr.rqPreReqOpCode := io.psnInc.rq.epsn.preReqOpCode
      }
      when(io.psnInc.rq.opsn.inc) {
        qpAttr.rqOutPsn := io.psnInc.rq.opsn.psnVal
      }
    }
    when(mainFsm.isActive(mainFsm.RTS)) {
      when(io.psnInc.sq.npsn.inc) {
        qpAttr.npsn := qpAttr.npsn + io.psnInc.sq.npsn.incVal
      }
      when(io.psnInc.sq.opsn.inc) {
        qpAttr.sqOutPsn := io.psnInc.sq.opsn.psnVal
      }
    }
  }

  val fsmInRetryState = sqFsm.isActive(sqFsm.RETRY_FLUSH) ||
    sqFsm.isActive(sqFsm.RETRY_WORK_REQ)
  val retryFlushState = sqFsm.isActive(sqFsm.RETRY_FLUSH)

  // Flush RQ if state error or RNR sent in next cycle
  val isQpStateWrong = mainFsm.isActive(mainFsm.ERR) ||
    mainFsm.isActive(mainFsm.RESET) || mainFsm.isActive(mainFsm.INIT)
  io.txQCtrl.errorFlush := errFsm.isActive(errFsm.ERR_FLUSH)
  io.txQCtrl.retry := fsmInRetryState
  io.txQCtrl.retryStartPulse := io.sqNotifier.retry.needRetry()
  io.txQCtrl.retryFlush := retryFlushState
  io.txQCtrl.wrongStateFlush := isQpStateWrong

  // RQ flush
  io.rxQCtrl.stateErrFlush := isQpStateWrong
  io.rxQCtrl.nakSeqTrigger := rqFsm.isActive(rqFsm.NAK_SEQ)
  io.rxQCtrl.rnrFlush := rqFsm.isActive(rqFsm.RNR) ||
    rqFsm.isActive(rqFsm.RNR)
  io.rxQCtrl.rnrTimeOut := rqFsm.isActive(rqFsm.RNR_TIMEOUT)
  io.rxQCtrl.flush := io.rxQCtrl.stateErrFlush || io.rxQCtrl.rnrFlush || io.rxQCtrl.nakSeqTrigger
}

class QP(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = out(QpAttrData())
    val qpCreateOrModify = slave(QpCreateOrModifyBus())
    val workReq = slave(Stream(WorkReq()))
    val rxWorkReq = slave(Stream(RxWorkReq()))
    val workComp = master(Stream(WorkComp()))
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val dma = master(DmaBus(busWidth))
    val pdAddrCacheQuery = master(PdAddrCacheReadBus())
  }

  val sq = new SendQ(busWidth)
  val rq = new RecvQ(busWidth)

  val qpCtrl = new QpCtrl
  io.qpAttr := qpCtrl.io.qpAttr
  qpCtrl.io.qpCreateOrModify << io.qpCreateOrModify
  qpCtrl.io.psnInc.rq := rq.io.psnInc
  qpCtrl.io.psnInc.sq := sq.io.psnInc
  qpCtrl.io.rqNotifier := rq.io.notifier
  qpCtrl.io.sqNotifier := sq.io.notifier

  // Separate incoming requests and responses
  val reqRespSplitter = new ReqRespSplitter(busWidth)
  reqRespSplitter.io.rx << io.rx

  sq.io.qpAttr := io.qpAttr
  sq.io.txQCtrl := qpCtrl.io.txQCtrl
  sq.io.workReq << io.workReq
  sq.io.rxResp << reqRespSplitter.io.txResp

  rq.io.qpAttr := io.qpAttr
  rq.io.rxQCtrl := qpCtrl.io.rxQCtrl
  rq.io.rx << reqRespSplitter.io.txReq
  rq.io.rxWorkReq << io.rxWorkReq

  // TODO: connect WC
  val workCompOut = new WorkCompOut()
  workCompOut.io.rqSendWriteWorkComp << rq.io.sendWriteWorkComp
  workCompOut.io.sqWorkComp << sq.io.workComp
  workCompOut.io.sqWorkCompErr << sq.io.workCompErr
  io.workComp << workCompOut.io.workCompPush

  // TODO: QpAddrCacheAgent should not be per QP structure
  val addrCacheAgent = new QpAddrCacheAgent()
  addrCacheAgent.io.qpAttr := io.qpAttr
  addrCacheAgent.io.rqCacheRead << rq.io.addrCacheRead
  addrCacheAgent.io.sqReqCacheRead << sq.io.addrCacheRead4Req
  addrCacheAgent.io.sqRespCacheRead << sq.io.addrCacheRead4Resp
  io.pdAddrCacheQuery << addrCacheAgent.io.pdAddrCacheQuery

  val dmaRdReqVec = Vec(sq.io.dma.dmaRdReqVec ++ rq.io.dma.dmaRdReqVec)
  val dmaWrReqVec = Vec(rq.io.dma.dmaWrReqVec ++ sq.io.dma.dmaWrReqVec)
  io.dma.rd.arbitReq(dmaRdReqVec)
  io.dma.rd.deMuxRespByInitiator(
    rqRead = rq.io.dma.read.resp,
//    rqDup = rq.io.dma.dupRead.resp,
    rqAtomicRead = rq.io.dma.atomic.rd.resp,
    sqRead = sq.io.dma.reqOut.resp
//    sqDup = sq.io.dma.retry.resp
  )
  io.dma.wr.arbitReq(dmaWrReqVec)
  io.dma.wr.deMuxRespByInitiator(
    rqWrite = rq.io.dma.sendWrite.resp,
    rqAtomicWr = rq.io.dma.atomic.wr.resp,
    sqWrite = sq.io.dma.readResp.resp,
    sqAtomicWr = sq.io.dma.atomic.resp
  )

  val flowCtrl = new FlowCtrl(busWidth)
  flowCtrl.io.qpAttr := io.qpAttr
  flowCtrl.io.resp := reqRespSplitter.io.txResp.pktFrag.asFlow
  val txVec = Vec(sq.io.tx.pktFrag, rq.io.tx.pktFrag)
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock.on(txVec)
  flowCtrl.io.rx.pktFrag <-/< txSel

  io.tx.pktFrag <-/< flowCtrl.io.tx.pktFrag
}
