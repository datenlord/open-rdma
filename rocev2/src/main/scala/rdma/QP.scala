package rdma

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._
import StreamVec._

class ReqRespSplitter(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(RdmaDataBus(busWidth))
    val txReq = master(RdmaDataBus(busWidth))
    val txResp = master(RdmaDataBus(busWidth))
  }

  // val isReq = OpCode.isReqPkt(io.rx.pktFrag.bth.opcode)
  val isResp = OpCode.isRespPkt(io.rx.pktFrag.bth.opcode) || OpCode.isCnpPkt(
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
class FlowCtrl(busWidth: BusWidth) extends Component {
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
    val retryFlushDone = in(Bool())
    val qpCreateOrModify = slave(QpCreateOrModifyBus())
    val qpAttr = out(QpAttrData())
    val recvQCtrl = out(RecvQCtrl())
    val sendQCtrl = out(SendQCtrl())
    val workReqCacheScanBus = master(
      CamFifoScanBus(CachedWorkReq(), PENDING_REQ_NUM)
    )
    val retryWorkReq = master(Stream(CachedWorkReq()))
  }
  val qpAttr = RegInit(QpAttrData().initOrReset())
  io.qpAttr := qpAttr

  io.qpCreateOrModify.req.ready := True
  when(io.qpCreateOrModify.req.valid) {
    // TODO: modify QP attributes by mask
    qpAttr := io.qpCreateOrModify.req.qpAttr
  }
  io.qpCreateOrModify.resp.valid := io.qpCreateOrModify.req.valid
  io.qpCreateOrModify.resp.successOrFailure := True

  // retryDone just means finishing re-send requests, not means all retry responses received
  val retryDone = False

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
          io.qpCreateOrModify.req.valid &&
            io.qpCreateOrModify.req.qpAttr.modifyMask === QpAttrMask.QP_STATE.id &&
            io.qpCreateOrModify.req.qpAttr.state === QpState.RESET.id
        ) {
          exit()
        }
      }
    }
  }

  def sqRetryStateFsm() = new StateMachine {
    val RETRY_FLUSH: State = new State with EntryPoint {
      onEntry {
        qpAttr.retryReason := io.sqNotifier.retry.reason
        qpAttr.retryStartPsn := io.sqNotifier.retry.psnStart
      }
      whenIsActive {
        // retryFlushDone just means first retry WR sent, it needs to wait for new responses, stop flushing responses
        when(io.retryFlushDone) {
          goto(RETRY)
        }
      }
    }

    val RETRY: State = new State {
      whenIsActive {
        when(retryDone) {
          exit()
        }
      }
    }
  }

  val fenceRetryFsm = sqRetryStateFsm()
  def fenceStateFsm() = new StateMachine {
    val FENCE: State = new State with EntryPoint {
      whenIsActive {
        when(io.sqNotifier.workReqCacheEmpty) {
          exit()
        }
      }
    }

    val FENCE_RETRY: State = new StateFsm(fsm = fenceRetryFsm) {
      whenCompleted {
        goto(FENCE)
      }
    }
  }

  // TODO: does SQD need to handle retry?
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
          io.qpCreateOrModify.req.valid &&
            io.qpCreateOrModify.req.qpAttr.modifyMask === QpAttrMask.QP_STATE.id &&
            io.qpCreateOrModify.req.qpAttr.state === QpState.RTS.id
        ) {
          exit()
        }
      }
    }
  }

  def rqInternalFsm() = new StateMachine {
    // TODO: set RNR timer according to QP attributes
    // MIN_RNR_TIMER = 0.01ms, freq = 200MHz, timer count = 2000
    val rnrTimer = Timeout(time = 0.01 ms)

    val NORMAL: State = new State with EntryPoint {
      whenIsActive {
        when(io.rqNotifier.nak.seqErr.pulse) {
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
        when(io.rqNotifier.clearRnrOrNakSeq.pulse) {
          goto(NORMAL)
        }
      }
    }

    val RNR_TIMEOUT: State = new State {
      onEntry {
        rnrTimer.clear()
      }
      whenIsActive {
        when(rnrTimer.state) {
          goto(RNR)
        }
      }
    }

    val RNR: State = new State {
      whenIsActive {
        when(io.rqNotifier.clearRnrOrNakSeq.pulse) {
          goto(NORMAL)
        }
      }
    }

    when(this.isActive(NAK_SEQ)) {
      assert(
        assertion = !io.rqNotifier.nak.rnr.pulse,
        message =
          L"there's already a NAK SQK sent PSN=${qpAttr.epsn}, but there's another RNR NAK to send: io.rqNotifier.nak.rnr.pulse=${io.rqNotifier.nak.rnr.pulse}, io.rqNotifier.nak.rnr.psn=${io.rqNotifier.nak.rnr.psn}",
        severity = FAILURE
      )
    }
    when(this.isActive(RNR_TIMEOUT) || this.isActive(RNR)) {
      assert(
        assertion = !io.rqNotifier.nak.rnr.pulse,
        message =
          L"there's already a RNR NAK sent PSN=${qpAttr.epsn}, but there's another RNR NAK to send: io.rqNotifier.nak.rnr.pulse=${io.rqNotifier.nak.rnr.pulse}, io.rqNotifier.nak.rnr.psn=${io.rqNotifier.nak.rnr.psn}",
        severity = FAILURE
      )
    }
    when(this.isActive(RNR_TIMEOUT)) {
      assert(
        assertion = Formal.stable(io.rqNotifier.clearRnrOrNakSeq.pulse),
        message =
          L"rnr timer is not out but receive rnr clear pulse, rnrTimer.state=${rnrTimer.state}, io.rqNotifier.clearRnrOrNakSeq.pulse=${io.rqNotifier.clearRnrOrNakSeq.pulse}",
        severity = FAILURE
      )
    }
  }

  val sqRetryFsm = sqRetryStateFsm()
  val fenceFsm = fenceStateFsm()
  def sqInternalFsm() = new StateMachine {
    val NORMAL: State = new State with EntryPoint {
      whenIsActive {
        when(io.sqNotifier.retry.pulse) {
          goto(RETRY)
        } elsewhen (io.sqNotifier.workReqHasFence && !io.sqNotifier.workReqCacheEmpty) {
          goto(FENCE)
        }
      }
    }

    val RETRY: State = new StateFsm(fsm = sqRetryFsm) {
      whenCompleted {
        goto(NORMAL)
      }
    }

    val FENCE: State = new StateFsm(fsm = fenceFsm) {
      whenCompleted {
        goto(NORMAL)
      }
    }
  }

  val sqFsm = sqInternalFsm()
  val rqFsm = rqInternalFsm()
  val errFsm = errStateFsm()
  // QP FSM
  // https://www.rdmamojo.com/2012/05/05/qp-state-machine/
  // TODO: modify QP attributes according to state change requirements
  // https://www.rdmamojo.com/2013/01/12/ibv_modify_qp/
  val fsm = new StateMachine {
    val RESET = new State with EntryPoint
    val INIT = new State
    val ERR = new StateFsm(fsm = errFsm)
    val RTR = new State
    // TODO: how to stop internal state FSM?
    val RTS = new StateParallelFsm(sqFsm, rqFsm)
    // TODO: SQD needs to handle retry?
    val SQD = new StateFsm(fsm = drainStateFsm())
    // val SQE = new State // Not used in RC

    // TODO: clear WR queue
    RESET
      .onEntry {
        qpAttr.state := QpState.RESET.id
      }
      .whenIsActive {
        when(
          io.qpCreateOrModify.req.valid &&
            io.qpCreateOrModify.req.qpAttr.modifyMask === QpAttrMask.QP_STATE.id &&
            io.qpCreateOrModify.req.qpAttr.state === QpState.INIT.id
        ) {
          goto(INIT)
        }
      }

    INIT
      .onEntry {
        qpAttr.state := QpState.INIT.id
      }
      .whenIsActive {
        when(
          io.qpCreateOrModify.req.valid &&
            io.qpCreateOrModify.req.qpAttr.modifyMask === QpAttrMask.QP_STATE.id &&
            io.qpCreateOrModify.req.qpAttr.state === QpState.RTR.id
        ) {
          goto(RTR)
        }

        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalNak()) {
          goto(ERR)
        }
      }

    RTR
      .onEntry {
        qpAttr.state := QpState.RTR.id
      }
      .whenIsActive {
        when(
          io.qpCreateOrModify.req.valid &&
            io.qpCreateOrModify.req.qpAttr.modifyMask === QpAttrMask.QP_STATE.id &&
            io.qpCreateOrModify.req.qpAttr.state === QpState.RTS.id
        ) {
          goto(RTS)
        }

        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalNak()) {
          goto(ERR)
        }
      }

    RTS
      .onEntry {
        qpAttr.state := QpState.RTS.id
      }
      .whenIsActive {
        when(
          io.qpCreateOrModify.req.valid &&
            io.qpCreateOrModify.req.qpAttr.modifyMask === QpAttrMask.QP_STATE.id &&
            io.qpCreateOrModify.req.qpAttr.state === QpState.SQD.id
        ) {
          goto(SQD)
        }

        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalNak()) {
          RTS.fsms.foreach(_.exitFsm()) // Exit internal FSMs
          goto(ERR)
        }
      }

    SQD
      .onEntry {
        qpAttr.state := QpState.SQD.id
      }
      .whenIsActive {
        when(io.rqNotifier.hasFatalNak() || io.sqNotifier.hasFatalNak()) {
          SQD.fsm.exitFsm() // Exit internal FSM
          goto(ERR)
        }
      }
      .whenCompleted {
        goto(RTS)
      }

    ERR
      .onEntry {
        qpAttr.state := QpState.ERR.id
      }
      .whenCompleted {
        goto(RESET)
      }
  }

  val psnCtrl = new Area {
    // Increase PSN
    when(io.psnInc.rq.epsn.inc) {
      qpAttr.epsn := qpAttr.epsn + io.psnInc.rq.epsn.incVal
      // Update RQ previous received request opcode
      qpAttr.rqPreReqOpCode := io.psnInc.rq.epsn.preReqOpCode
    }
    when(io.psnInc.rq.opsn.inc) {
      qpAttr.rqOutPsn := io.psnInc.rq.opsn.psnVal
    }
    when(io.psnInc.sq.npsn.inc) {
      qpAttr.npsn := qpAttr.npsn + io.psnInc.sq.npsn.incVal
    }
    when(io.psnInc.sq.opsn.inc) {
      qpAttr.sqOutPsn := io.psnInc.sq.opsn.psnVal
    }
  }
  val sqRetryCtrl = new Area {
    // TODO: consider better setup instead of PENDING_REQ_NUM + 1
    val curPtr = Counter(PENDING_REQ_NUM)

    val fsmInRetryState = sqFsm.isActive(sqFsm.RETRY) ||
      fenceFsm.isActive(fenceFsm.FENCE_RETRY)
    val retryFlushState =
      sqRetryFsm.isActive(sqRetryFsm.RETRY_FLUSH) ||
        fenceRetryFsm.isActive(fenceRetryFsm.RETRY_FLUSH)

    io.workReqCacheScanBus.scanPtr := curPtr
    val retryWorkReqPop = cloneOf(io.retryWorkReq)
    retryWorkReqPop.valid := !io.workReqCacheScanBus.empty & fsmInRetryState
    retryWorkReqPop.payload := io.workReqCacheScanBus.value
    io.retryWorkReq <-/< retryWorkReqPop

    when(io.sendQCtrl.retry) {
      assert(
        assertion = Formal.stable(io.workReqCacheScanBus.pushPtr),
        message = L"during retry, no new WR can be added",
        severity = FAILURE
      )
    }
    when(io.retryWorkReq.fire) {
      curPtr.increment()
      when(curPtr.value === io.workReqCacheScanBus.pushPtr) {
//        curPtr.clear()
        retryDone := True
      }
    }
    when(io.sendQCtrl.wrongStateFlush) {
      curPtr.clear()
    }
    when(io.sqNotifier.retry.pulse && !io.workReqCacheScanBus.empty) {
      // Start to retry all pending WRs
      // TODO: verify counter can be assigned value
      curPtr.valueNext := io.workReqCacheScanBus.popPtr
    }
    when(io.sqNotifier.retry.pulse && io.workReqCacheScanBus.empty) {
      report(
        message =
          L"received retry ACK with PSN=${io.sqNotifier.retry.psnStart}, but no WR to retry",
        severity = FAILURE
      )
    }

    // Handle WR partial retry
    val retryFromFirstReq =
      (qpAttr.retryReason === RetryReason.RETRY_ACK) ? (io.sqNotifier.retry.psnStart === io.workReqCacheScanBus.value.psnStart) | True
    // For partial read retry, compute the partial read DMA length
    val psnDiff = PsnUtil.diff(
      io.sqNotifier.retry.psnStart,
      io.workReqCacheScanBus.value.psnStart
    )
    // psnDiff << io.qpAttr.pmtu.asUInt === psnDiff * pmtuPktLenBytes(io.qpAttr.pmtu)
    val dmaReadLenBytes =
      io.workReqCacheScanBus.value.workReq.lenBytes - (psnDiff << qpAttr.pmtu.asUInt)
    when(io.sqNotifier.retry.pulse && !retryFromFirstReq) {
      assert(
        assertion = PsnUtil.gt(
          io.sqNotifier.retry.psnStart,
          io.workReqCacheScanBus.value.psnStart,
          io.qpAttr.npsn
        ),
        message =
          L"io.retryNotify.psnStart=${io.sqNotifier.retry.psnStart} should > curWorkReqToRetry.psnStart=${io.workReqCacheScanBus.value.psnStart} in PSN order",
        severity = FAILURE
      )

      assert(
        assertion = psnDiff < computePktNum(
          io.workReqCacheScanBus.value.workReq.lenBytes,
          io.qpAttr.pmtu
        ),
        message =
          L"psnDiff=${psnDiff} should < packet num=${computePktNum(io.workReqCacheScanBus.value.workReq.lenBytes, io.qpAttr.pmtu)}",
        severity = FAILURE
      )
      retryWorkReqPop.psnStart := io.sqNotifier.retry.psnStart
      retryWorkReqPop.workReq.lenBytes := dmaReadLenBytes.resize(
        RDMA_MAX_LEN_WIDTH
      )
    }
  }

  // Flush RQ if state error or RNR sent in next cycle
  val isQpStateWrong = fsm.isActive(fsm.ERR) || fsm.isActive(fsm.RESET) ||
    fsm.isActive(fsm.INIT)
  io.sendQCtrl.errorFlush := errFsm.isActive(errFsm.ERR_FLUSH)
  io.sendQCtrl.retryFlush := sqRetryCtrl.retryFlushState
  io.sendQCtrl.retry := sqRetryCtrl.fsmInRetryState
  io.sendQCtrl.fencePulse := False
  io.sendQCtrl.fence := fsm.isActive(fsm.SQD) || sqFsm.isActive(sqFsm.FENCE)
  io.sendQCtrl.fenceOrRetry := io.sendQCtrl.fence || io.sendQCtrl.retry
  io.sendQCtrl.wrongStateFlush := isQpStateWrong

  // RQ flush
  io.recvQCtrl.stateErrFlush := isQpStateWrong
  io.recvQCtrl.nakSeqTrigger := rqFsm.isActive(rqFsm.NAK_SEQ)
  io.recvQCtrl.rnrFlush := rqFsm.isActive(rqFsm.RNR) ||
    rqFsm.isActive(rqFsm.RNR)
  io.recvQCtrl.rnrTimeOut := rqFsm.isActive(rqFsm.RNR_TIMEOUT)
  io.recvQCtrl.flush := io.recvQCtrl.stateErrFlush || io.recvQCtrl.rnrFlush || io.recvQCtrl.nakSeqTrigger
}

class QP(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = out(QpAttrData())
    val qpCreateOrModify = slave(QpCreateOrModifyBus())
    val workReq = slave(Stream(WorkReq()))
    val recvWorkReq = slave(Stream(RecvWorkReq()))
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
  qpCtrl.io.retryFlushDone := sq.io.retryFlushDone
  sq.io.workReqCacheScanBus << qpCtrl.io.workReqCacheScanBus
  sq.io.retryWorkReq << qpCtrl.io.retryWorkReq

  // Separate incoming requests and responses
  val reqRespSplitter = new ReqRespSplitter(busWidth)
  reqRespSplitter.io.rx << io.rx

  sq.io.qpAttr := io.qpAttr
  sq.io.sendQCtrl := qpCtrl.io.sendQCtrl
  sq.io.workReq << io.workReq
  sq.io.rxResp << reqRespSplitter.io.txResp

  rq.io.qpAttr := io.qpAttr
  rq.io.recvQCtrl := qpCtrl.io.recvQCtrl
  rq.io.rx << reqRespSplitter.io.txReq
  rq.io.recvWorkReq << io.recvWorkReq

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
    rqDup = rq.io.dma.dupRead.resp,
    rqAtomicRead = rq.io.dma.atomic.rd.resp,
    sqRead = sq.io.dma.reqSender.resp,
    sqDup = sq.io.dma.retry.resp
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
