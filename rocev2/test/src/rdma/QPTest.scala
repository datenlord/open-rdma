package rdma

import spinal.core._
import spinal.core.sim._
//import spinal.lib._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._
import scala.collection.mutable

import ConstantSettings._
import StreamSimUtil._
import SimSettings._

class QpCtrlTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(
      SpinalConfig(
        defaultClockDomainFrequency = FixedFrequency(200 MHz),
        anonymSignalPrefix = "tmp"
      )
    )
    .compile {
      val dut = new QpCtrl
      dut.mainFsm.stateReg.simPublic()
      dut.sqFsm.stateReg.simPublic()
      dut.rqFsm.stateReg.simPublic()
      dut
    }

  test("QpCtrl state change test") {
    testStateChangeFunc()
  }

  def clearStateChangeConditions(dut: QpCtrl): Unit = {
    dut.io.psnInc.rq.epsn.inc #= false
    dut.io.psnInc.rq.opsn.inc #= false
    dut.io.psnInc.sq.npsn.inc #= false
    dut.io.psnInc.sq.opsn.inc #= false

    dut.io.rqNotifier.nak.rnr.pulse #= false
    dut.io.rqNotifier.nak.seqErr.pulse #= false
    dut.io.rqNotifier.nak.invReq #= false
    dut.io.rqNotifier.nak.rmtAcc #= false
    dut.io.rqNotifier.nak.rmtOp #= false

    dut.io.rqNotifier.clearRnrOrNakSeq.pulse #= false

    dut.io.sqNotifier.err.pulse #= false

    // For ERR state
    dut.io.sqNotifier.coalesceAckDone #= false
    // For SQD state
    dut.io.sqNotifier.workReqCacheEmpty #= false

    // For RTS nested retry states
    dut.io.sqNotifier.retry.pulse #= false
    dut.io.sqNotifier.retryClear.retryFlushDone #= false
    dut.io.sqNotifier.retryClear.retryWorkReqDone #= false
  }

  def testStateChangeFunc(): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    def mainFsmState2QpState(
        fsmState: SpinalEnumElement[dut.mainFsm.enumDef.type]
    ): SpinalEnumElement[QpState.type] = {
      val mainFsmStateReset = dut.mainFsm.enumOf(dut.mainFsm.RESET)
      val mainFsmStateINIT = dut.mainFsm.enumOf(dut.mainFsm.INIT)
      val mainFsmStateRTR = dut.mainFsm.enumOf(dut.mainFsm.RTR)
      val mainFsmStateRTS = dut.mainFsm.enumOf(dut.mainFsm.RTS)
      val mainFsmStateSQD = dut.mainFsm.enumOf(dut.mainFsm.SQD)
      val mainFsmStateERR = dut.mainFsm.enumOf(dut.mainFsm.ERR)

      fsmState match {
        case _ if (fsmState == mainFsmStateReset) => QpState.RESET
        case _ if (fsmState == mainFsmStateINIT)  => QpState.INIT
        case _ if (fsmState == mainFsmStateRTR)   => QpState.RTR
        case _ if (fsmState == mainFsmStateRTS)   => QpState.RTS
        case _ if (fsmState == mainFsmStateSQD)   => QpState.SQD
        case _ if (fsmState == mainFsmStateERR)   => QpState.ERR
        case _ => {
          println(
            f"${simTime()} time: invalid FSM state=${fsmState}, no match QpState"
          )
          ???
        }
      }
    }

    clearStateChangeConditions(dut)
    val qpStateChangeSeq = Seq(
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.INIT),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.WAITING)
        ),
        () => { clearStateChangeConditions(dut) }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTR),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ),
        () => { clearStateChangeConditions(dut) }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTR),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.NAK_SEQ)
        ), // RTR state NAK_SEQ internal state
        () => {
          clearStateChangeConditions(dut)
          dut.io.rqNotifier.nak.seqErr.pulse #= true
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTR),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ), // RTR state NORMAL internal state
        () => {
          clearStateChangeConditions(dut)
          dut.io.rqNotifier.clearRnrOrNakSeq.pulse #= true
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTR),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.RNR_TIMEOUT)
        ), // RTR state RNR_TIMEOUT internal state
        () => {
          clearStateChangeConditions(dut)
          MIN_RNR_TIMEOUT * 10 shouldBe SIM_CYCLE_TIME withClue
            f"${simTime()} time: MIN_RNR_TIMEOUT=${MIN_RNR_TIMEOUT}, which means RNR timeout is 10ns, should == SIM_CYCLE_TIME=${SIM_CYCLE_TIME}ns"

          dut.io.rqNotifier.nak.rnr.pulse #= true
          dut.io.qpAttr.rnrTimeOut #= MIN_RNR_TIMEOUT
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTR),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.RNR)
        ), // RTR state RNR internal state
        () => {
          clearStateChangeConditions(dut)
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTR),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ), // RTR state NORMAL state
        () => {
          clearStateChangeConditions(dut)
          dut.io.rqNotifier.clearRnrOrNakSeq.pulse #= true
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTS),
          dut.sqFsm.enumOf(dut.sqFsm.NORMAL),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ),
        () => { clearStateChangeConditions(dut) }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTS),
          dut.sqFsm.enumOf(dut.sqFsm.NORMAL),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ),
        () => { clearStateChangeConditions(dut) }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.SQD),
          dut.sqFsm.enumOf(dut.sqFsm.NORMAL),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ), // SQD state DRAINING sub-state
        () => { clearStateChangeConditions(dut) }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.SQD),
          dut.sqFsm.enumOf(dut.sqFsm.NORMAL),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ), // SQD state DRAINED sub-state
        () => {
          clearStateChangeConditions(dut)
          dut.io.sqNotifier.workReqCacheEmpty #= true
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTS),
          dut.sqFsm.enumOf(dut.sqFsm.RETRY_FLUSH),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ), // RTS state RETRY_FLUSH internal state
        () => {
          clearStateChangeConditions(dut)
          dut.io.sqNotifier.retry.pulse #= true
          dut.io.sqNotifier.retry.reason #= RetryReason.SEQ_ERR
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTS),
          dut.sqFsm.enumOf(dut.sqFsm.RETRY_WORK_REQ),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ), // RTS state RETRY_WORK_REQ internal state
        () => {
          clearStateChangeConditions(dut)
          dut.io.sqNotifier.retryClear.retryFlushDone #= true
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTS),
          dut.sqFsm.enumOf(dut.sqFsm.RETRY_WORK_REQ),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ), // RTS state RETRY_WORK_REQ internal state
        () => {
          clearStateChangeConditions(dut)
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RTS),
          dut.sqFsm.enumOf(dut.sqFsm.NORMAL),
          dut.rqFsm.enumOf(dut.rqFsm.NORMAL)
        ), // RTS state retry done
        () => {
          clearStateChangeConditions(dut)
          dut.io.sqNotifier.retryClear.retryWorkReqDone #= true
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.ERR),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.WAITING)
        ), // ERR state COALESCE sub-state
        () => {
          clearStateChangeConditions(dut)
//          dut.io.sqNotifier.err.pulse #= true
//          dut.io.sqNotifier.err.errType #= SqErrType.INV_REQ
          dut.io.rqNotifier.nak.invReq #= true
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.ERR),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.WAITING)
        ), // ERR state ERR_FLUSH sub-state
        () => {
          clearStateChangeConditions(dut)
          dut.io.sqNotifier.coalesceAckDone #= true
        }
      ),
      (
        QpAttrMaskEnum.QP_STATE,
        (
          dut.mainFsm.enumOf(dut.mainFsm.RESET),
          dut.sqFsm.enumOf(dut.sqFsm.WAITING),
          dut.rqFsm.enumOf(dut.rqFsm.WAITING)
        ),
        () => { clearStateChangeConditions(dut) }
      )
    )

    val stateChangeReqQueue = mutable.Queue[
      (
          SpinalEnumElement[QpAttrMaskEnum.type],
          (
              SpinalEnumElement[dut.mainFsm.enumDef.type],
              SpinalEnumElement[dut.sqFsm.enumDef.type],
              SpinalEnumElement[dut.rqFsm.enumDef.type]
          ),
          () => Unit
      )
    ]()

    val inputQueue = mutable.Queue[
      (
          SpinalEnumElement[dut.mainFsm.enumDef.type],
          SpinalEnumElement[dut.sqFsm.enumDef.type],
          SpinalEnumElement[dut.rqFsm.enumDef.type]
      )
    ]()
    val outputQueue = mutable.Queue[
      (
          SpinalEnumElement[dut.mainFsm.enumDef.type],
          SpinalEnumElement[dut.sqFsm.enumDef.type],
          SpinalEnumElement[dut.rqFsm.enumDef.type]
      )
    ]()

    fork {
      while (true) {
        for (changeAction <- qpStateChangeSeq) {
          stateChangeReqQueue.enqueue(changeAction)
        }
        waitUntil(stateChangeReqQueue.isEmpty)
      }
    }

    streamMasterPayloadFromQueueAlwaysValid(
      dut.io.qpCreateOrModify.req,
      dut.clockDomain,
      stateChangeReqQueue,
      payloadAssignFunc = (
          req: QpCreateOrModifyReq,
          payloadData: (
              SpinalEnumElement[QpAttrMaskEnum.type],
              (
                  SpinalEnumElement[dut.mainFsm.enumDef.type],
                  SpinalEnumElement[dut.sqFsm.enumDef.type],
                  SpinalEnumElement[dut.rqFsm.enumDef.type]
              ),
              () => Unit
          )
      ) => {
        val (modifyMask, qpFsmStates, assignFunc) = payloadData
        assignFunc()
        req.qpAttr.modifyMask.maskBits #= QpAttrMaskEnum.defaultEncoding
          .getValue(modifyMask)
        val (mainFsmState, sqFsmState, rqFsmState) = qpFsmStates
        val qpState = mainFsmState2QpState(mainFsmState)
        req.qpAttr.state #= qpState

        inputQueue.enqueue((mainFsmState, sqFsmState, rqFsmState))
      }
    )
//    onStreamFire(dut.io.qpCreateOrModify.req, dut.clockDomain) {
//      inputQueue.enqueue(dut.io.qpCreateOrModify.req.qpAttr.state.toEnum)
//    }

    streamSlaveAlwaysReady(dut.io.qpCreateOrModify.resp, dut.clockDomain)
    onStreamFire(dut.io.qpCreateOrModify.resp, dut.clockDomain) {
      dut.io.qpCreateOrModify.resp.successOrFailure.toBoolean shouldBe true withClue
        f"${simTime()} time: dut.io.qpCreateOrModify.resp.successOrFailure=${dut.io.qpCreateOrModify.resp.successOrFailure.toBoolean} should be true"

      val mainFsmState = dut.mainFsm.stateReg.toEnum
      val sqFsmState = dut.sqFsm.stateReg.toEnum
      val rqFsmState = dut.rqFsm.stateReg.toEnum
//      println(
//        f"${simTime()} time: mainFsmState=${mainFsmState}, sqFsmState=${sqFsmState}, rqFsmState=${rqFsmState}"
//      )
      outputQueue.enqueue((mainFsmState, sqFsmState, rqFsmState))
    }

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputQueue,
      outputQueue,
      MATCH_CNT
    )
  }
}
