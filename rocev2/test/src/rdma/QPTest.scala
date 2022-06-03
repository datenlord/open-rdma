package rdma

import spinal.core._
import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._

import scala.collection.mutable
import ConstantSettings._
import RdmaConstants._
import PsnSim._
import StreamSimUtil._
import SimSettings._
import RdmaTypeReDef._
import WorkReqSim._

import scala.language.postfixOps

class QPTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(
      SpinalConfig(
        defaultClockDomainFrequency = FixedFrequency(200 MHz),
        anonymSignalPrefix = "tmp"
      )
    )
    .compile {
      val dut = new QP(busWidth)
      dut
    }

  test("QP rx only case") {
    noInput()
//    testFunc(recvOnly = true, noDmaRead = true, noDmaWrite = false)
  }

  def noInput(): Unit = simCfg.doSim(127257803) { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    dut.io.qpCreateOrModify.req.valid #= false
    dut.io.qpCreateOrModify.resp.ready #= true
    dut.io.workReq.valid #= false
    dut.io.rxWorkReq.valid #= false
    dut.io.workComp.ready #= true
    dut.io.rx.pktFrag.valid #= false
    dut.io.tx.pktFrag.ready #= true
    dut.io.dma.rd.req.ready #= true
    dut.io.dma.rd.resp.valid #= false
    dut.io.dma.wr.req.ready #= true
    dut.io.dma.wr.resp.valid #= false
    dut.io.pdAddrCacheQuery.req.ready #= true
    dut.io.pdAddrCacheQuery.resp.valid #= false

    dut.clockDomain.waitSampling(100)
  }

  def testFunc(
      recvOnly: Boolean,
      noDmaRead: Boolean,
      noDmaWrite: Boolean
  ): Unit = simCfg.doSim(127257803) { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    val ePsnQueue = mutable.Queue[PSN]()
    val inputWorkReqQueue = mutable.Queue[
      (
          WorkReqId,
          SpinalEnumElement[WorkReqOpCode.type],
          VirtualAddr,
          LRKey,
          PktLen
      )
    ]()
    val inputRecvWorkReqQueue = mutable.Queue[
      (
          WorkReqId,
          VirtualAddr,
          LRKey,
          PktLen
      )
    ]()
    val inputReqQueue = mutable.Queue[
      (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type]
      )
    ]()

    val expectedAckQueue = mutable.Queue[
      (
          PSN,
          OpCode.Value,
          SpinalEnumElement[AckType.type],
          FragLast
      )
    ]()
    val outputAckQueue = mutable.Queue[
      (
          PSN,
          OpCode.Value,
          SpinalEnumElement[AckType.type],
          FragLast
      )
    ]()
    val outputReadAtomicRespQueue = mutable.Queue[
      (
          PSN,
          OpCode.Value,
          PktFragData,
          MTY,
          FragLast
      )
    ]()

    // Input to DUT
    if (recvOnly) {
      dut.io.workReq.valid #= false
    } else {
      streamMasterDriverAlwaysValid(dut.io.workReq, dut.clockDomain) {
        // TODO: assign WR
        val workReqOpCode = if (noDmaWrite) {
          WorkReqSim.randomSendWriteOpCode()
        } else if (noDmaRead) {
          WorkReqSim.randomReadAtomicOpCode()
        } else {
          WorkReqSim.randomSendWriteReadAtomicOpCode()
        }
        val pktLen = if (workReqOpCode.isAtomicReq()) {
          ATOMIC_DATA_LEN.toLong
        } else {
          WorkReqSim.randomDmaLength()
        }
        dut.io.workReq.opcode #= workReqOpCode
        dut.io.workReq.lenBytes #= pktLen
//        val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
      }
    }
    onStreamFire(dut.io.workReq, dut.clockDomain) {
      inputWorkReqQueue.enqueue(
        (
          dut.io.workReq.id.toBigInt,
          dut.io.workReq.opcode.toEnum,
          dut.io.workReq.laddr.toBigInt,
          dut.io.workReq.lkey.toLong,
          dut.io.workReq.lenBytes.toLong
        )
      )
    }

    streamMasterDriverAlwaysValid(dut.io.rxWorkReq, dut.clockDomain) {
      // Just random assign receive WR
    }
    onStreamFire(dut.io.rxWorkReq, dut.clockDomain) {
      inputRecvWorkReqQueue.enqueue(
        (
          dut.io.rxWorkReq.id.toBigInt,
          dut.io.rxWorkReq.laddr.toBigInt,
          dut.io.rxWorkReq.lkey.toLong,
          dut.io.rxWorkReq.lenBytes.toLong
        )
      )
    }

    streamMasterPayloadFromQueueAlwaysValid(
      dut.io.qpCreateOrModify.req,
      dut.clockDomain,
      ePsnQueue,
      payloadAssignFunc = (req: QpCreateOrModifyReq, epsn: PSN) => {
        req.modifyMask.maskBits #=
          QpAttrMaskEnum.defaultEncoding.getValue(QpAttrMaskEnum.QP_RQ_PSN) +
            QpAttrMaskEnum.defaultEncoding.getValue(QpAttrMaskEnum.QP_PATH_MTU)
        req.qpAttr.epsn #= epsn
        req.qpAttr.pmtu #= pmtuLen.id

        val respValid = true
        respValid
      }
    )

    // dmaWriteRespQueue
    val _ = DmaWriteBusSim.reqStreamAlwaysFireAndRespSuccess(
      dut.io.dma.wr,
      dut.clockDomain
    )
    // dmaReadRespQueue
    val _ =
      DmaReadBusSim(busWidth).reqStreamAlwaysFireAndRespSuccess(
        dut.io.dma.rd,
        dut.clockDomain
      )
    // pdAddrCacheRespQueue
    val _ = PdAddrCacheSim.reqStreamAlwaysFireAndRespSuccess(
      dut.io.pdAddrCacheQuery,
      dut.clockDomain
    )

    dut.io.dma.rd.resp.valid #= false
    sleep(0)

    if (noDmaRead) {
      // No DMA read request and response
      dut.io.dma.rd.req.ready.toBoolean shouldBe false withClue
        f"${simTime()} time: dut.io.dma.rd.req.ready=${dut.io.dma.rd.req.ready.toBoolean} should be false, since noDmaRead=${noDmaRead}"
      dut.io.dma.rd.resp.valid.toBoolean shouldBe false withClue
        f"${simTime()} time: dut.io.dma.rd.resp.valid=${dut.io.dma.rd.resp.valid.toBoolean} should be false, since noDmaRead=${noDmaRead}"
    }
    if (noDmaWrite) {
      // No DMA write request and response
      dut.io.dma.wr.req.ready.toBoolean shouldBe false withClue
        f"${simTime()} time: dut.io.dma.wr.req.ready=${dut.io.dma.wr.req.ready.toBoolean} should be false, since noDmaWrite=${noDmaWrite}"
      dut.io.dma.wr.resp.valid.toBoolean shouldBe false withClue
        f"${simTime()} time: dut.io.dma.wr.resp.valid=${dut.io.dma.wr.resp.valid.toBoolean} should be false, since noDmaWrite=${noDmaWrite}"
    }

    // Input to RX
    if (recvOnly) {
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      fork {
//        var epsn = 0
        while (true) {
          dut.clockDomain.waitSampling()

          val payloadFragNum = payloadFragNumItr.next()
          val pktNum = pktNumItr.next()
          val psnStart = psnStartItr.next()
          val payloadLenBytes = payloadLenItr.next()
//          val payloadFragNum = 4
//          val pktNum = 1
//          val psnStart = epsn
//          val payloadLenBytes = payloadFragNum * busWidth.id / BYTE_WIDTH
          val workReqOpCode = if (noDmaRead) {
            val ackPsn = psnStart +% (pktNum - 1)
            val ackType = AckType.NORMAL
            val fragLast = true
            expectedAckQueue.enqueue(
              (
                ackPsn,
                OpCode.ACKNOWLEDGE,
                ackType,
                fragLast
              )
            )

            WorkReqSim.randomSendWriteImmOpCode()
          } else if (noDmaWrite) {
            WorkReqOpCode.RDMA_READ
          } else {
            // TODO: check if RQ supports atomic
            WorkReqSim.randomSendWriteReadAtomicOpCode()
          }
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
          inputReqQueue.enqueue(
            (
              psnStart,
              payloadFragNum,
              pktNum,
              pmtuLen,
              busWidth,
              payloadLenBytes.toLong,
              workReqOpCode
            )
          )
        }
      }
    }
    RdmaDataPktSim.pktFragStreamMasterDriver(
      dut.io.rx.pktFrag,
      (rdmaDataPkt: RdmaDataPkt) => rdmaDataPkt,
//      getRdmaPktDataFunc = identity,
      dut.clockDomain
    ) {
      val (
        psnStart,
        payloadFragNum,
        pktNum,
        pmtuLen,
        busWidth,
        payloadLenBytes,
        workReqOpCode
      ) = MiscUtils.safeDeQueue(inputReqQueue, dut.clockDomain)
      (
        psnStart,
        payloadFragNum,
        pktNum,
        pmtuLen,
        busWidth,
        payloadLenBytes,
        workReqOpCode
      )
    } {
      (
          _, // psn,
          _, // psnStart
          _, // fragLast,
          _, // fragIdx,
          _, // pktFragNum,
          _, // pktIdx,
          _, // pktNum,
          _, // payloadLenBytes,
          _, // headerLenBytes,
          _ // opcode
      ) =>
        {
          //
        }
    }

    streamSlaveAlwaysReady(dut.io.tx.pktFrag, dut.clockDomain)
    onStreamFire(dut.io.tx.pktFrag, dut.clockDomain) {
      if (noDmaRead) {
        val (_, aethCode, aethValue, _) =
          AethSim.extract(dut.io.tx.pktFrag.data.toBigInt, busWidth)
        val ackType = AckTypeSim.decodeFromCodeAndValue(aethCode, aethValue)
        outputAckQueue.enqueue(
          (
            dut.io.tx.pktFrag.bth.psn.toInt,
            OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt),
            ackType,
            dut.io.tx.pktFrag.last.toBoolean
          )
        )
      }

      if (noDmaWrite) {
        outputReadAtomicRespQueue.enqueue(
          (
            dut.io.tx.pktFrag.bth.psn.toInt,
            OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt),
            dut.io.tx.pktFrag.data.toBigInt,
            dut.io.tx.pktFrag.mty.toBigInt,
            dut.io.tx.pktFrag.last.toBoolean
          )
        )
      }
    }

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      expectedAckQueue,
      outputAckQueue,
      MATCH_CNT
    )
  }
}

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
        mainFsmState: SpinalEnumElement[dut.mainFsm.enumDef.type]
    ): SpinalEnumElement[QpState.type] = {
      val mainFsmStateReset = dut.mainFsm.enumOf(dut.mainFsm.RESET)
      val mainFsmStateINIT = dut.mainFsm.enumOf(dut.mainFsm.INIT)
      val mainFsmStateRTR = dut.mainFsm.enumOf(dut.mainFsm.RTR)
      val mainFsmStateRTS = dut.mainFsm.enumOf(dut.mainFsm.RTS)
      val mainFsmStateSQD = dut.mainFsm.enumOf(dut.mainFsm.SQD)
      val mainFsmStateERR = dut.mainFsm.enumOf(dut.mainFsm.ERR)

      mainFsmState match {
        case _ if (mainFsmState == mainFsmStateReset) => QpState.RESET
        case _ if (mainFsmState == mainFsmStateINIT)  => QpState.INIT
        case _ if (mainFsmState == mainFsmStateRTR)   => QpState.RTR
        case _ if (mainFsmState == mainFsmStateRTS)   => QpState.RTS
        case _ if (mainFsmState == mainFsmStateSQD)   => QpState.SQD
        case _ if (mainFsmState == mainFsmStateERR)   => QpState.ERR
        case _ =>
          SpinalExit(
            s"${simTime()} time: invalid QP FSM state=${mainFsmState}, no match QpState"
          )
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
          dut.io.qpAttr.receivedRnrTimeOut #= MIN_RNR_TIMEOUT
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
        dut.clockDomain.waitSamplingWhere(stateChangeReqQueue.isEmpty)
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
        req.modifyMask.maskBits #= QpAttrMaskEnum.defaultEncoding
          .getValue(modifyMask)
        val (mainFsmState, sqFsmState, rqFsmState) = qpFsmStates
        val qpState = mainFsmState2QpState(mainFsmState)
        req.qpAttr.state #= qpState

        inputQueue.enqueue((mainFsmState, sqFsmState, rqFsmState))

        val respValid = true
        respValid
      }
    )

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

  test("QpCtrl state change test") {
    testStateChangeFunc()
  }
}
