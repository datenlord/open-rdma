package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._

import scala.collection.mutable
import ConstantSettings._
import AethSim._
import OpCodeSim._
import PsnSim._
import RdmaConstants._
import StreamSimUtil._
import RdmaTypeReDef._
import WorkReqSim._
import SimSettings._

class ReqCommCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 537

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new ReqCommCheck(busWidth))

  test("ReqCommCheck normal case") {
    testFunc(normalOrNot = true, dupReqOrSeqNak = false)
  }

  test("ReqCommCheck duplicated request case") {
    testFunc(normalOrNot = false, dupReqOrSeqNak = true)
  }

  test("ReqCommCheck NAK SEQ request case") {
    testFunc(normalOrNot = false, dupReqOrSeqNak = false)
  }

  def testFunc(normalOrNot: Boolean, dupReqOrSeqNak: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )
      QpCtrlSim.connectExpectedPsnInc(
        dut.io.qpAttr,
        dut.io.epsnInc,
        dut.clockDomain
      )
      dut.io.readAtomicRstCacheOccupancy #= 0

      val rdmaReqQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()
      val inputQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()
      val seqErrExpectedOutputQueue = mutable.Queue[(OpCode.Value, PSN)]()
      val seqErrActualOutputQueue = mutable.Queue[(OpCode.Value, PSN)]()
      val expectedOutputQueue =
        mutable.Queue[
          (
              PSN,
              OpCode.Value,
              PadCnt,
              AckReq,
              FragLast,
              PktFragData,
              MTY,
              HasNak,
              SpinalEnumElement[AckType.type]
          )
        ]()
      val actualOutputQueue =
        mutable.Queue[
          (
              PSN,
              OpCode.Value,
              PadCnt,
              AckReq,
              FragLast,
              PktFragData,
              MTY,
              HasNak,
              SpinalEnumElement[AckType.type]
          )
        ]()

      val expectedDupOutputQueue =
        mutable.Queue[
          (
              PSN,
              OpCode.Value,
              PadCnt,
              AckReq,
              FragLast,
              PktFragData,
              MTY
          )
        ]()
      val actualDupOutputQueue =
        mutable.Queue[
          (
              PSN,
              OpCode.Value,
              PadCnt,
              AckReq,
              FragLast,
              PktFragData,
              MTY
          )
        ]()

      fork {
        dut.clockDomain.waitSampling()
        // No more than one request can be RNR
        val pendingReqLimit = 1

        // Retry control loop
        while (true) {
          val expectedPsn = dut.io.qpAttr.epsn.toInt

          // Generate send/write requests
          RdmaDataPktSim.rdmaReqPktFragStreamMasterGen(
            dut.io.rx.pktFrag,
            expectedPsn,
            pendingReqLimit,
            pmtuLen,
            busWidth,
            maxFragNum
          ) {
            (
                psn,
                _, // psnStart
                fragLast,
                _, // fragIdx,
                _, // pktFragNum,
                _, // pktIdx,
                _, // reqPktNum,
                _, // respPktNum,
                _, // payloadLenBytes,
                _, // headerLenBytes,
                opcode
            ) =>
//              sleep(0)
//              println(
//                f"${simTime()} time: generate request, PSN=${psn}%X, opcode=${opcode}, fragLast=${fragLast}, data=${dut.io.rx.pktFrag.data.toBigInt}%X"
//              )
              rdmaReqQueue.enqueue(
                (
                  psn,
                  opcode,
                  dut.io.rx.pktFrag.bth.padCnt.toInt,
                  dut.io.rx.pktFrag.bth.ackreq.toBoolean,
                  fragLast,
                  dut.io.rx.pktFrag.data.toBigInt,
                  dut.io.rx.pktFrag.mty.toBigInt
                )
              )
          }

          if (normalOrNot) {
            inputQueue.appendAll(rdmaReqQueue)
            expectedOutputQueue.appendAll {
              rdmaReqQueue.map { payloadData =>
                val (psn, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
                  payloadData
                val hasNak = false
                val ackType = AckType.NORMAL
                (
                  psn,
                  opcode,
                  padCnt,
                  ackreq,
                  fragLast,
                  pktFragData,
                  mty,
                  hasNak,
                  ackType
                )
              }
            }

            // Wait until normal requests processed
            dut.clockDomain.waitSamplingWhere(expectedOutputQueue.isEmpty)
            dut.io.qpAttr.rqOutPsn #= dut.io.qpAttr.epsn.toInt -% 1
          } else {
            if (dupReqOrSeqNak) {
              val lastReqPktFrag = rdmaReqQueue.last
              val lastReqPsn = lastReqPktFrag._1
              dut.io.qpAttr.epsn #= lastReqPsn +% 1
              dut.io.qpAttr.rqOutPsn #= lastReqPsn

              inputQueue.appendAll(rdmaReqQueue)
              expectedDupOutputQueue.appendAll(rdmaReqQueue)

              // Wait until duplicate requests processed
              dut.clockDomain.waitSamplingWhere(expectedDupOutputQueue.isEmpty)
            } else { // NAK SEQ requests
              inputQueue.appendAll {
                rdmaReqQueue.map { payloadData =>
                  val (
                    psn,
                    opcode,
                    padCnt,
                    ackreq,
                    fragLast,
                    pktFragData,
                    mty
                  ) =
                    payloadData
                  val seqErrPsn = psn +% dut.io.qpAttr.epsn.toInt +% 1
//                  println(f"${simTime()} time: PSN=${psn}, seqErrPsn=${seqErrPsn}")

                  (
                    seqErrPsn,
                    opcode,
                    padCnt,
                    ackreq,
                    fragLast,
                    pktFragData,
                    mty
                  )
                }
              }

              expectedOutputQueue.appendAll {
                rdmaReqQueue.map { payloadData =>
                  val (_, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
                    payloadData
                  val hasNak = true
                  val ackType = AckType.NAK_SEQ
                  val expectedPsn = dut.io.qpAttr.epsn.toInt
                  (
                    expectedPsn,
                    opcode,
                    padCnt,
                    ackreq,
                    fragLast,
                    pktFragData,
                    mty,
                    hasNak,
                    ackType
                  )
                }
              }

              // Wait until NAK SEQ requests processed
              dut.clockDomain.waitSamplingWhere(expectedOutputQueue.isEmpty)
            }
          }

          inputQueue shouldBe empty withClue f"${simTime()} time: inputQueue.isEmpty=${inputQueue.isEmpty} should be true after expected output queue is empty"
//          println(
//            f"${simTime()} time: dut.io.qpAttr.epsn=${dut.io.qpAttr.epsn.toInt}%X, dut.io.qpAttr.rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt}%X"
//          )
          rdmaReqQueue.clear()
        }
      }

      streamMasterPayloadFromQueue(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        inputQueue,
        payloadAssignFunc = (
            pktFrag: Fragment[RdmaDataPkt],
            payloadData: (
                PSN,
                OpCode.Value,
                PadCnt,
                AckReq,
                FragLast,
                PktFragData,
                MTY
            )
        ) => {
          val (psn, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
            payloadData
          pktFrag.bth.psn #= psn
          pktFrag.bth.opcodeFull #= opcode.id
          pktFrag.bth.padCnt #= padCnt
          pktFrag.bth.ackreq #= ackreq
          pktFrag.data #= pktFragData
          pktFrag.mty #= mty
          pktFrag.last #= fragLast

//          sleep(0)
//          println(
//            f"${simTime()} time: input opcode=${opcode}, PSN=${dut.io.rx.pktFrag.bth.psn.toInt}%X, ePSN=${dut.io.qpAttr.epsn.toInt}%X, fragLast=${fragLast}, data=${dut.io.rx.pktFrag.data.toBigInt}%X"
//          )

          val reqValid = true
          reqValid
        }
      )
      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
        val psn = dut.io.rx.pktFrag.bth.psn.toInt
        val fragLast = dut.io.rx.pktFrag.last.toBoolean

        if (!normalOrNot && !dupReqOrSeqNak && fragLast) {
          val rqPreOpCode = OpCode(dut.io.qpAttr.rqPreReqOpCode.toInt)
          seqErrExpectedOutputQueue.enqueue((rqPreOpCode, psn))
        }

//        val opcode = OpCode(dut.io.rx.pktFrag.bth.opcodeFull.toInt)
//        println(
//          f"${simTime()} time: input opcode=${opcode}, PSN=${dut.io.rx.pktFrag.bth.psn.toInt}%X, ePSN=${dut.io.qpAttr.epsn.toInt}%X, fragLast=${fragLast}, data=${dut.io.rx.pktFrag.data.toBigInt}%X"
//        )
      }

      streamSlaveRandomizer(dut.io.txDupReq.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txDupReq.pktFrag, dut.clockDomain) {
        val opcode = OpCode(dut.io.txDupReq.pktFrag.bth.opcodeFull.toInt)
        val psn = dut.io.txDupReq.pktFrag.bth.psn.toInt
        val fragLast = dut.io.txDupReq.pktFrag.last.toBoolean
        actualDupOutputQueue.enqueue(
          (
            psn,
            opcode,
            dut.io.txDupReq.pktFrag.bth.padCnt.toInt,
            dut.io.txDupReq.pktFrag.bth.ackreq.toBoolean,
            fragLast,
            dut.io.txDupReq.pktFrag.data.toBigInt,
            dut.io.txDupReq.pktFrag.mty.toBigInt
          )
        )

//        println(
//          f"${simTime()} time: receive response, PSN=${psn}%X, opcode=${opcode}, fragLast=${fragLast}, data=${dut.io.txDupReq.pktFrag.data.toBigInt}%X"
//        )
      }

      streamSlaveRandomizer(dut.io.tx.checkRst, dut.clockDomain)
      onStreamFire(dut.io.tx.checkRst, dut.clockDomain) {
        val opcode = OpCode(dut.io.tx.checkRst.pktFrag.bth.opcodeFull.toInt)
        val psn = dut.io.tx.checkRst.pktFrag.bth.psn.toInt
        val fragLast = dut.io.tx.checkRst.last.toBoolean
        val hasNak = dut.io.tx.checkRst.hasNak.toBoolean
        val ackType = AckTypeSim.decodeFromAeth(dut.io.tx.checkRst.ackAeth)

        actualOutputQueue.enqueue(
          (
            psn,
            opcode,
            dut.io.tx.checkRst.pktFrag.bth.padCnt.toInt,
            dut.io.tx.checkRst.pktFrag.bth.ackreq.toBoolean,
            fragLast,
            dut.io.tx.checkRst.pktFrag.data.toBigInt,
            dut.io.tx.checkRst.pktFrag.mty.toBigInt,
            hasNak,
            ackType
          )
        )

//        println(
//          f"${simTime()} time: receive response, PSN=${psn}%X, opcode=${opcode}, ackType=${ackType}, fragLast=${fragLast}"
//        )
      }

      if (!normalOrNot && !dupReqOrSeqNak) {
        MiscUtils.checkExpectedOutputMatchAlways(
          dut.clockDomain,
          seqErrExpectedOutputQueue,
          seqErrActualOutputQueue
        )
      }
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.io.seqErrNotifier.pulse.toBoolean) {
            seqErrActualOutputQueue.enqueue(
              (
                OpCode(dut.io.seqErrNotifier.preOpCode.toInt),
                dut.io.seqErrNotifier.psn.toInt
              )
            )
          }
        }
      }
      if (!normalOrNot && dupReqOrSeqNak) { // Duplicate request case
        MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
          cond = !dut.io.tx.checkRst.valid.toBoolean,
          clue =
            f"${simTime()} time: dut.io.tx.checkRst.valid=${dut.io.tx.checkRst.valid.toBoolean} should be false when duplicate input"
        )

        MiscUtils.checkExpectedOutputMatch(
          dut.clockDomain,
          expectedDupOutputQueue,
          actualDupOutputQueue,
          MATCH_CNT
        )
      } else {
        MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
          cond = !dut.io.txDupReq.pktFrag.valid.toBoolean,
          clue =
            f"${simTime()} time: dut.io.txDupReq.pktFrag.valid=${dut.io.txDupReq.pktFrag.valid.toBoolean} should be false when input normal or hasNak"
        )

        MiscUtils.checkExpectedOutputMatch(
          dut.clockDomain,
          expectedOutputQueue,
          actualOutputQueue,
          MATCH_CNT
        )
      }
    }
}

class ReqRnrCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReqRnrCheck(busWidth))

  def testFunc(
      inputNormalOrNot: Boolean,
      rnrOrOtherNak: Boolean
  ): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    // TODO: check why it makes this test slow?
    QpCtrlSim.assignDefaultQpAttrAndFlush(
      dut.io.qpAttr,
      pmtuLen,
      dut.io.flush
    )

    // Input to DUT
    val inputQueue =
      mutable.Queue[
        (
            PSN,
            OpCode.Value,
            PktFragData,
            FragLast,
            HasNak,
            SpinalEnumElement[AckType.type]
        )
      ]()
    val inputWorkReqQueue = mutable.Queue[(WorkReqValid, WorkReqId)]()
    val outputWorkReqQueue = mutable.Queue[(WorkReqValid, WorkReqId)]()
    val outputQueue =
      mutable.Queue[
        (
            PSN,
            OpCode.Value,
            PktFragData,
            FragLast,
            HasNak,
            SpinalEnumElement[AckType.type]
        )
      ]()

    RdmaDataPktSim.sendWriteImmReqPktFragStreamMasterDriver(
      dut.io.rx.checkRst,
      dut.clockDomain,
      getRdmaPktDataFunc = (r: RqReqCheckStageOutput) => r.pktFrag,
      pendingReqNumExceed = false,
      pmtuLen,
      busWidth,
      maxFragNum
    )(
      innerLoopFunc = (
          _, // psn,
          _, // psnStart
          _, // fragLast,
          _, // fragIdx,
          _, // pktFragNum,
          _, // pktIdx,
          _, // reqPktNum,
          _, // respPktNum
          _, // payloadLenBytes,
          _, // headerLenBytes,
          _ // opcode
      ) => {
        dut.io.rx.checkRst.hasNak #= !inputNormalOrNot && !rnrOrOtherNak
//        dut.io.rx.checkRst.isDupReq #= !inputNormalOrNot && !rnrOrNot && !inputHasNakOrDupReq

        if (!inputNormalOrNot && !rnrOrOtherNak) {
          dut.io.rx.checkRst.ackAeth.setAs(AckType.NAK_INV)
        } else {
          dut.io.rx.checkRst.ackAeth.setAs(AckType.NORMAL)
        }
      }
    )
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )

    onStreamFire(dut.io.rx.checkRst, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
      val opcode = OpCode(dut.io.rx.checkRst.pktFrag.bth.opcodeFull.toInt)
      val psn = dut.io.rx.checkRst.pktFrag.bth.psn.toInt
      val isLast = dut.io.rx.checkRst.last.toBoolean
      val (hasNak, ackType) =
        if (!inputNormalOrNot && rnrOrOtherNak && opcode.needRxBuf()) {
          (true, AckType.NAK_RNR)
        } else {
          (
            dut.io.rx.checkRst.hasNak.toBoolean,
            AckTypeSim.decodeFromAeth(dut.io.rx.checkRst.ackAeth)
          )
        }
      inputQueue.enqueue(
        (
          psn,
          opcode,
          dut.io.rx.checkRst.pktFrag.data.toBigInt,
          isLast,
          hasNak,
          ackType
//          dut.io.rx.checkRst.isDupReq.toBoolean
        )
      )

      val needRxBuf = opcode.needRxBuf()
      dut.io.rnrNotifier.pulse.toBoolean shouldBe (!inputNormalOrNot && rnrOrOtherNak && needRxBuf && isLast) withClue
        f"${simTime()} time: dut.io.rnrNotifier.pulse=${dut.io.rnrNotifier.pulse.toBoolean} should match inputNormalOrNot=${inputNormalOrNot}, rnrOrOtherNak=${rnrOrOtherNak}, needRxBuf=${needRxBuf}"
      if (!inputNormalOrNot && rnrOrOtherNak) {
        dut.io.rnrNotifier.psn.toInt shouldBe psn withClue
          f"${simTime()} time: dut.io.rnrNotifier.psn=${dut.io.rnrNotifier.psn.toInt} should be ${psn}"

        val rqPreOpCode = dut.io.rx.checkRst.preOpCode.toInt
        dut.io.rnrNotifier.preOpCode.toInt shouldBe rqPreOpCode withClue
          f"${simTime()} time: dut.io.rnrNotifier.preOpCode=${dut.io.rnrNotifier.preOpCode.toInt} should be ${rqPreOpCode}"
      }
    }

    if (inputNormalOrNot) { // Normal input case
      streamMasterDriverAlwaysValid(dut.io.rxWorkReq, dut.clockDomain) {
        // dut.io.rxWorkReq must be always valid to avoid RNR
      }
      onStreamFire(dut.io.rxWorkReq, dut.clockDomain) {
        inputWorkReqQueue.enqueue(
          (dut.io.rxWorkReq.valid.toBoolean, dut.io.rxWorkReq.id.toBigInt)
        )

//          println(f"${simTime()} time: dut.io.rxWorkReq.id=${dut.io.rxWorkReq.id.toBigInt}%X")
      }
    } else { // RNR and NAK input case
      dut.io.rxWorkReq.valid #= false
    }

//    if (inputNormalOrNot || rnrOrNot || inputHasNakOrDupReq) { // Normal input or NAK input case
    streamSlaveRandomizer(dut.io.tx.reqWithRxBuf, dut.clockDomain)
    onStreamFire(dut.io.tx.reqWithRxBuf, dut.clockDomain) {
      val ackType =
        AckTypeSim.decodeFromAeth(dut.io.tx.reqWithRxBuf.ackAeth)
      val opcode =
        OpCode(dut.io.tx.reqWithRxBuf.pktFrag.bth.opcodeFull.toInt)
      val psn = dut.io.tx.reqWithRxBuf.pktFrag.bth.psn.toInt
      val hasNak = dut.io.tx.reqWithRxBuf.hasNak.toBoolean
      val isLastFrag = dut.io.tx.reqWithRxBuf.last.toBoolean
//        val isDupReq = false
      outputQueue.enqueue(
        (
          psn,
          opcode,
          dut.io.tx.reqWithRxBuf.pktFrag.data.toBigInt,
          isLastFrag,
          hasNak,
          ackType
//            isDupReq
        )
      )

      val needRxBuf = opcode.needRxBuf()
      val isLastOrOnlyReqPkt = opcode.isLastOrOnlyReqPkt()
      val rxBufValid = dut.io.tx.reqWithRxBuf.rxBufValid.toBoolean
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, rxBufValid=${rxBufValid}, dut.io.tx.reqWithRxBuf.rxBuf.id=${dut.io.tx.reqWithRxBuf.rxBuf.id.toBigInt}%X, needRxBuf=${needRxBuf}, isLastOrOnlyReqPkt=${isLastOrOnlyReqPkt}, isLastFrag=${isLastFrag}"
//          )
      if (inputNormalOrNot && needRxBuf && isLastOrOnlyReqPkt && isLastFrag) {
        outputWorkReqQueue.enqueue(
          (
            rxBufValid,
            dut.io.tx.reqWithRxBuf.rxBuf.id.toBigInt
          )
        )
      }
    }

    if (inputNormalOrNot) {
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputWorkReqQueue,
        outputWorkReqQueue,
        MATCH_CNT
      )
    } else {
      MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
        cond =
          !(dut.io.rxWorkReq.valid.toBoolean && dut.io.rxWorkReq.ready.toBoolean),
        clue =
          f"${simTime()} time: dut.io.rxWorkReq.fire=${dut.io.rxWorkReq.valid.toBoolean && dut.io.rxWorkReq.ready.toBoolean} should be false when input not normal"
      )
    }
    MiscUtils.checkExpectedOutputMatch(
      dut.clockDomain,
      inputQueue,
      outputQueue,
      MATCH_CNT
    )
  }

  test("ReqRnrCheck normal case") {
    testFunc(
      inputNormalOrNot = true,
      rnrOrOtherNak = false
    )
  }

  test("ReqRnrCheck RNR case") {
    testFunc(
      inputNormalOrNot = false,
      rnrOrOtherNak = true
    )
  }

  test("ReqRnrCheck input has NAK case") {
    testFunc(
      inputNormalOrNot = false,
      rnrOrOtherNak = false
    )
  }

//  test("ReqRnrCheck duplicated request case") {
//    testFunc(
//      inputNormalOrNot = false,
//      rnrOrNot = false,
//      inputHasNakOrDupReq = false
//    )
//  }
}

class ReqAddrInfoExtractorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReqAddrInfoExtractor(busWidth))

  def testFunc(inputHasNak: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//      dut.io.qpAttr.pmtu #= pmtuLen.id
//      dut.io.rxQCtrl.stateErrFlush #= false
      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[
        (
            PSN,
            OpCode.Value,
            AckReq,
            PktFragData,
            PktLen,
            RxBufValid,
            HasNak,
            FragLast
        )
      ]()
      val outputQueue = mutable.Queue[
        (
            PSN,
            OpCode.Value,
            AckReq,
            PktFragData,
            PktLen,
            RxBufValid,
            HasNak,
            FragLast
        )
      ]()
      val inputPsnRangeQueue = mutable.Queue[(OpCode.Value, PsnStart, PsnEnd)]()
      val outputPsnRangeQueue =
        mutable.Queue[(OpCode.Value, PsnStart, PsnEnd)]()

      RdmaDataPktSim.pktFragStreamMasterDriver(
        dut.io.rx.reqWithRxBuf,
        dut.clockDomain,
        getRdmaPktDataFunc = (r: RqReqWithRxBuf) => r.pktFrag
      ) {
        val payloadFragNum = payloadFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val payloadLenBytes = payloadLenItr.next()
        val workReqOpCode = WorkReqSim.randomSendWriteOpCode()
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, pktNum=${pktNum}, payloadFragNum=${payloadFragNum}, psnStart=${psnStart}, payloadLenBytes=${payloadLenBytes}"
//        )
        (
          psnStart,
          payloadFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          payloadLenBytes.toLong,
          workReqOpCode
        )
      } {
        (
            _, // psn,
            _, // psnStart
            _, // fragLast,
            fragIdx,
            _, // pktFragNum,
            _, // pktIdx,
            _, // pktNum,
            payloadLenBytes,
            _, // headerLenBytes,
            opcode
        ) =>
          if (opcode.hasReth() && fragIdx == 0) {
            val pktFragData = dut.io.rx.reqWithRxBuf.pktFrag.data.toBigInt
            dut.io.rx.reqWithRxBuf.pktFrag.data #= RethSim.setDlen(
              pktFragData,
              payloadLenBytes,
              busWidth
            )
          }

          dut.io.rx.reqWithRxBuf.rxBufValid #= opcode.needRxBuf()
          dut.io.rx.reqWithRxBuf.rxBuf.lenBytes #= payloadLenBytes
          dut.io.rx.reqWithRxBuf.pktFrag.bth.ackreq #=
            opcode.isLastOrOnlyReqPkt()
          dut.io.rx.reqWithRxBuf.hasNak #= inputHasNak
          if (inputHasNak) {
            dut.io.rx.reqWithRxBuf.ackAeth.setAsRnrNak()
            dut.io.rx.reqWithRxBuf.rxBufValid #= false
          }
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx.reqWithRxBuf, dut.clockDomain) {
        val psn = dut.io.rx.reqWithRxBuf.pktFrag.bth.psn.toInt
        val ackReq = dut.io.rx.reqWithRxBuf.pktFrag.bth.ackreq.toBoolean
        val pktFragData = dut.io.rx.reqWithRxBuf.pktFrag.data.toBigInt
        val opcode = OpCode(dut.io.rx.reqWithRxBuf.pktFrag.bth.opcodeFull.toInt)
        val isLastFrag = dut.io.rx.reqWithRxBuf.last.toBoolean
        val pktLenBytes = dut.io.rx.reqWithRxBuf.rxBuf.lenBytes.toLong
//        val (_, _, pktLen) = RethSim.extract(pktFragData, busWidth)

//        println(f"${simTime()} time: PSN=${psn}, opcode=${opcode}, ackReq=${ackReq}, isLastFrag=${isLastFrag}, pktLenBytes=${pktLenBytes}%X")
        inputQueue.enqueue(
          (
            psn,
            opcode,
            ackReq,
            pktFragData,
            pktLenBytes,
            dut.io.rx.reqWithRxBuf.rxBufValid.toBoolean,
            dut.io.rx.reqWithRxBuf.hasNak.toBoolean,
            isLastFrag
          )
        )
        if (
          isLastFrag && (inputHasNak || ackReq ||
            opcode.isReadReqPkt() || opcode.isAtomicReqPkt())
        ) {
          val singlePktNum = 1
          val pktNum = if (opcode.isReadReqPkt()) {
            MiscUtils.computePktNum(pktLenBytes, pmtuLen)
          } else {
            singlePktNum
          }
          val psnEnd = psn + pktNum - 1
          inputPsnRangeQueue.enqueue(
            (opcode, psn, psnEnd)
          )
//          println(f"${simTime()} time: opcode=${opcode}, psn=${psn}, psnEnd=${psnEnd}")
        }
      }

      streamSlaveRandomizer(
        dut.io.tx.reqWithRxBufAndVirtualAddrInfo,
        dut.clockDomain
      )
      onStreamFire(dut.io.tx.reqWithRxBufAndVirtualAddrInfo, dut.clockDomain) {
        val pktFragData =
          dut.io.tx.reqWithRxBufAndVirtualAddrInfo.pktFrag.data.toBigInt
        val opcode =
          OpCode(
            dut.io.tx.reqWithRxBufAndVirtualAddrInfo.pktFrag.bth.opcodeFull.toInt
          )
        outputQueue.enqueue(
          (
            dut.io.tx.reqWithRxBufAndVirtualAddrInfo.pktFrag.bth.psn.toInt,
            opcode,
            dut.io.tx.reqWithRxBufAndVirtualAddrInfo.pktFrag.bth.ackreq.toBoolean,
            pktFragData,
            dut.io.tx.reqWithRxBufAndVirtualAddrInfo.virtualAddrInfo.dlen.toLong,
            dut.io.tx.reqWithRxBufAndVirtualAddrInfo.rxBufValid.toBoolean,
            dut.io.tx.reqWithRxBufAndVirtualAddrInfo.hasNak.toBoolean,
            dut.io.tx.reqWithRxBufAndVirtualAddrInfo.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.rqOutPsnRangeFifoPush, dut.clockDomain)
      onStreamFire(dut.io.rqOutPsnRangeFifoPush, dut.clockDomain) {
        val opcode = OpCode(dut.io.rqOutPsnRangeFifoPush.opcode.toInt)
        outputPsnRangeQueue.enqueue(
          (
            opcode,
            dut.io.rqOutPsnRangeFifoPush.start.toInt,
            dut.io.rqOutPsnRangeFifoPush.end.toInt
          )
        )
//        println(f"${simTime()} time: opcode=${opcode}, dut.io.rqOutPsnRangeFifoPush.start=${dut.io.rqOutPsnRangeFifoPush.start.toInt}, dut.io.rqOutPsnRangeFifoPush.end=${dut.io.rqOutPsnRangeFifoPush.end.toInt}")
      }

      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputPsnRangeQueue,
        outputPsnRangeQueue,
        MATCH_CNT
      )
    }

  test("ReqAddrInfoExtractor normal case") {
    testFunc(inputHasNak = false)
  }

  test("ReqAddrInfoExtractor invalid input case") {
    testFunc(inputHasNak = true)
  }
}

class ReqAddrValidatorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 537

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new ReqAddrValidator(busWidth))

  def testFunc(addrCacheQuerySuccess: Boolean, inputHasNak: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//      dut.io.qpAttr.pmtu #= pmtuLen.id
//      dut.io.rxQCtrl.stateErrFlush #= false
      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue =
        mutable.Queue[(PSN, OpCode.Value, PktFragData, HasNak, FragLast)]()
      val outputQueue = mutable
        .Queue[
          (PSN, OpCode.Value, PktFragData, FragLast, PhysicalAddr, HasNak)
        ]()
      val matchQueue = mutable.Queue[FragLast]()

      RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
        dut.io.rx.reqWithRxBufAndVirtualAddrInfo,
        dut.clockDomain,
        getRdmaPktDataFunc =
          (reqPktFrag: RqReqWithRxBufAndVirtualAddrInfo) => reqPktFrag.pktFrag
      ) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val payloadLenBytes = payloadLenItr.next()
        val workReqOpCode = WorkReqSim.randomSendWriteImmOpCode()
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        (
          psnStart,
          totalFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          payloadLenBytes.toLong,
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
            opcode
        ) =>
          dut.io.rx.reqWithRxBufAndVirtualAddrInfo.hasNak #= inputHasNak
          if (inputHasNak) {
            dut.io.rx.reqWithRxBufAndVirtualAddrInfo.ackAeth.setAsRnrNak()
            dut.io.rx.reqWithRxBufAndVirtualAddrInfo.rxBufValid #= false
          } else {
            dut.io.rx.reqWithRxBufAndVirtualAddrInfo.rxBufValid #= opcode
              .needRxBuf()
          }
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx.reqWithRxBufAndVirtualAddrInfo, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val pktFragData =
          dut.io.rx.reqWithRxBufAndVirtualAddrInfo.pktFrag.data.toBigInt
        val opcode =
          OpCode(
            dut.io.rx.reqWithRxBufAndVirtualAddrInfo.pktFrag.bth.opcodeFull.toInt
          )
        inputQueue.enqueue(
          (
            dut.io.rx.reqWithRxBufAndVirtualAddrInfo.pktFrag.bth.psn.toInt,
            opcode,
            pktFragData,
            dut.io.rx.reqWithRxBufAndVirtualAddrInfo.hasNak.toBoolean,
            dut.io.rx.reqWithRxBufAndVirtualAddrInfo.last.toBoolean
          )
        )
      }

      val addrCacheRespQueue = if (addrCacheQuerySuccess) {
        AddrCacheSim.reqStreamFixedDelayAndRespSuccess(
          dut.io.addrCacheRead,
          dut.clockDomain,
          fixedRespDelayCycles = ADDR_CACHE_QUERY_DELAY_CYCLES
        )
      } else {
        AddrCacheSim.reqStreamFixedDelayAndRespFailure(
          dut.io.addrCacheRead,
          dut.clockDomain,
          fixedRespDelayCycles = ADDR_CACHE_QUERY_DELAY_CYCLES
        )
      }

      streamSlaveAlwaysReady(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain)
      onStreamFire(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
        val pktFragData = dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt
        outputQueue.enqueue(
          (
            dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            OpCode(
              dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt
            ),
            pktFragData,
            dut.io.tx.reqWithRxBufAndDmaInfo.last.toBoolean,
            dut.io.tx.reqWithRxBufAndDmaInfo.dmaInfo.pa.toBigInt,
            dut.io.tx.reqWithRxBufAndDmaInfo.hasNak.toBoolean
          )
        )
      }

      fork {
        while (true) {
          val (psnIn, opCodeIn, fragDataIn, hasNakIn, fragLastIn) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)

          val (psnOut, opCodeOut, fragDataOut, fragLastOut, paOut, hasNakOut) =
            MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)

          psnOut shouldBe psnIn withClue
            f"${simTime()} time: psnIn=${psnIn} should equal psnOut=${psnOut}"

          opCodeOut shouldBe opCodeIn withClue
            f"${simTime()} time: opCodeIn=${opCodeIn} should equal opCodeOut=${opCodeOut}"

          fragDataOut shouldBe fragDataIn withClue
            f"${simTime()} time: fragDataIn=${fragDataIn} should equal fragDataOut=${fragDataOut}"

          fragLastOut shouldBe fragLastIn withClue
            f"${simTime()} time: fragLastIn=${fragLastIn} should equal fragLastOut=${fragLastOut}"
          if (addrCacheQuerySuccess || inputHasNak) {
            hasNakOut shouldBe hasNakIn withClue
              f"${simTime()} time: hasNakIn=${hasNakIn} should equal hasNakOut=${hasNakOut} when addrCacheQuerySuccess=${addrCacheQuerySuccess} or inputHasNak=${inputHasNak}"
          } else {
            hasNakIn shouldBe false withClue
              f"${simTime()} time: hasNakIn=${hasNakIn} should be false when addrCacheQuerySuccess=${addrCacheQuerySuccess} and inputHasNak=${inputHasNak}"

            hasNakOut shouldBe true withClue
              f"${simTime()} time: hasNakOut=${hasNakIn} should be true when addrCacheQuerySuccess=${addrCacheQuerySuccess} and inputHasNak=${inputHasNak}"
          }
          if (!inputHasNak) {
            if (opCodeOut.isFirstOrOnlyReqPkt() && fragLastOut) {
              val (
                psnFirstOrOnly,
                keyValid,
                sizeValid,
                accessValid,
                paAddrCacheResp
              ) =
                MiscUtils.safeDeQueue(addrCacheRespQueue, dut.clockDomain)

              psnOut shouldBe psnFirstOrOnly withClue
                f"${simTime()} time: psnFirstOrOnly=${psnFirstOrOnly} should equal psnOut=${psnOut}"

              paOut shouldBe paAddrCacheResp withClue
                f"${simTime()} time: paAddrCacheResp=${paAddrCacheResp} should equal paOut=${paOut}"
              if (addrCacheQuerySuccess) {
                (keyValid && sizeValid && accessValid) shouldBe true withClue
                  f"${simTime()} time: keyValid=${keyValid} && sizeValid=${sizeValid} && accessValid=${accessValid} should be true, when addrCacheQuerySuccess=${addrCacheQuerySuccess}"
              } else {
                (keyValid && sizeValid && accessValid) shouldBe false withClue
                  f"${simTime()} time: !(keyValid=${keyValid} && sizeValid=${sizeValid} && accessValid=${accessValid}) should be true, when addrCacheQuerySuccess=${addrCacheQuerySuccess}"
              }
            }
          }
          matchQueue.enqueue(fragLastOut)
        }
      }

      MiscUtils.checkCondChangeOnceAndHoldAfterwards(
        dut.clockDomain,
        cond =
          dut.io.rx.reqWithRxBufAndVirtualAddrInfo.valid.toBoolean && dut.io.rx.reqWithRxBufAndVirtualAddrInfo.ready.toBoolean,
        clue =
          f"${simTime()} time: dut.io.rx.reqWithRxBufAndVirtualAddrInfo.fire=${dut.io.rx.reqWithRxBufAndVirtualAddrInfo.valid.toBoolean && dut.io.rx.reqWithRxBufAndVirtualAddrInfo.ready.toBoolean} should be true always"
      )
      MiscUtils.checkCondChangeOnceAndHoldAfterwards(
        dut.clockDomain,
        cond = dut.io.tx.reqWithRxBufAndDmaInfo.valid.toBoolean,
        clue =
          f"${simTime()} time: dut.io.tx.reqWithRxBufAndDmaInfo.valid=${dut.io.tx.reqWithRxBufAndDmaInfo.valid.toBoolean} should be true always"
      )

      waitUntil(matchQueue.size > MATCH_CNT)
    }

  test("ReqAddrValidator normal case") {
    testFunc(addrCacheQuerySuccess = true, inputHasNak = false)
  }

  test("ReqAddrValidator query fail case") {
    testFunc(addrCacheQuerySuccess = false, inputHasNak = false)
  }
}

class ReqPktLenCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 537

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReqPktLenCheck(busWidth))

  def testFunc(inputHasNak: Boolean, hasLenCheckErr: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue =
        mutable.Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()

      RdmaDataPktSim.pktFragStreamMasterDriver(
        dut.io.rx.reqWithRxBufAndDmaInfo,
        dut.clockDomain,
        getRdmaPktDataFunc = (r: RqReqWithRxBufAndDmaInfo) => r.pktFrag
      ) {
        val payloadFragNum = payloadFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val payloadLenBytes = payloadLenItr.next()
        val workReqOpCode = WorkReqSim.randomSendWriteOpCode()

//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        (
          psnStart,
          payloadFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          payloadLenBytes.toLong,
          workReqOpCode
        )
      } {
        (
            _, // psn,
            _, // psnStart
            fragLast,
            _, // fragIdx,
            _, // pktFragNum,
            pktIdx,
            pktNum,
            payloadLenBytes,
            _, // headerLenBytes,
            opcode
        ) =>
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.lenBytes #= payloadLenBytes
          dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= payloadLenBytes

          dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= inputHasNak
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.needRxBuf()
          if (inputHasNak) {
            dut.io.rx.reqWithRxBufAndDmaInfo.ackAeth.setAsRnrNak()
          }

          if (hasLenCheckErr) {
            if (pktIdx == pktNum - 1) { // Last or only packet
              if (fragLast) {
                // Wrong RxBuf length or DMA length for request total length check error
                dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.lenBytes #= payloadLenBytes - 1
                dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= payloadLenBytes - 1
              }
            } else { // First or middle packet
              if (fragLast) {
                // Wrong padCnt for packet length check error
                val wrongPadCnt = 1
                dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.padCnt #= wrongPadCnt
              }
            }
          }
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
      }
      onStreamFire(dut.io.rx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
        val psn = dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt
        val opcode =
          OpCode(dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt)
        val fragLast = dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
//        println(f"${simTime()} time: PSN=${psn}")
        inputQueue.enqueue(
          (
            psn,
            opcode,
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt,
            fragLast
          )
        )

        val hasNakIn = dut.io.rx.reqWithRxBufAndDmaInfo.hasNak.toBoolean
        hasNakIn shouldBe inputHasNak withClue
          f"${simTime()} time: hasNakIn=${hasNakIn} should equal inputHasNak=${inputHasNak} when hasLenCheckErr=${hasLenCheckErr} or inputHasNak=${inputHasNak} for PSN=${psn}%X and fragLast=${fragLast}"
      }

      streamSlaveRandomizer(
        dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck,
        dut.clockDomain
      )
      onStreamFire(
        dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck,
        dut.clockDomain
      ) {
        val psn =
          dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt
        val fragLast =
          dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
        outputQueue.enqueue(
          (
            psn,
            OpCode(
              dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
            ),
            dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.data.toBigInt,
            fragLast
          )
        )

        val hasNakOut =
          dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean
        if (fragLast) { // Length check error is at the last fragment
          if (inputHasNak) {
            hasNakOut shouldBe true withClue
              f"${simTime()} time: hasNakOut=${hasNakOut} should equal inputHasNak=${inputHasNak} when hasLenCheckErr=${hasLenCheckErr} or inputHasNak=${inputHasNak} for PSN=${psn}%X and fragLast=${fragLast}"
          } else if (hasLenCheckErr) {
            hasNakOut shouldBe true withClue
              f"${simTime()} time: hasNakOut=${hasNakOut} should equal hasLenCheckErr=${hasLenCheckErr} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psn}%X and fragLast=${fragLast}"
          } else {
            hasNakOut shouldBe false withClue
              f"${simTime()} time: hasNakOut=${hasNakOut} should be false when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psn}%X and fragLast=${fragLast}"
          }
        }
      }

      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }

  test("PktLenCheck normal case") {
    testFunc(inputHasNak = false, hasLenCheckErr = false)
  }

  test("PktLenCheck inputHasNak case") {
    testFunc(inputHasNak = true, hasLenCheckErr = true)
  }

  test("PktLenCheck zero length case") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      // Input to DUT
      val nextPsnItr = NaturalNumber.from(SimSettings.INIT_PSN).iterator

      val inputQueue =
        mutable.Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()

      streamMasterDriver(
        dut.io.rx.reqWithRxBufAndDmaInfo,
        dut.clockDomain
      ) {
        val psn = nextPsnItr.next()
        val opcode = OpCodeSim.randomSendOnlyOrWriteOnlyOpCode()
        val headerLenBytes = opcode.getPktHeaderLenBytes()
        val mty = setAllBits(headerLenBytes)

        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn #= psn
        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.mty #= mty

        dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= false
        dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.needRxBuf()
        dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.lenBytes #= 0
        dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= 0

        dut.io.rx.reqWithRxBufAndDmaInfo.ackAeth.setAsNormalAck()
        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.padCnt #= 0

        dut.io.rx.reqWithRxBufAndDmaInfo.last #= true
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
      }
      onStreamFire(dut.io.rx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
        val psn = dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt
        val opcode =
          OpCode(dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt)
        val fragLast = dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
        //        println(f"${simTime()} time: PSN=${psn}")
        inputQueue.enqueue(
          (
            psn,
            opcode,
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt,
            fragLast
          )
        )

        val hasNakIn = dut.io.rx.reqWithRxBufAndDmaInfo.hasNak.toBoolean
        hasNakIn shouldBe false withClue
          f"${simTime()} time: hasNakIn=${hasNakIn} should equal false when zero payload length"
      }

      streamSlaveRandomizer(
        dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck,
        dut.clockDomain
      )
      onStreamFire(
        dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck,
        dut.clockDomain
      ) {
        val psn =
          dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt
        val fragLast =
          dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
        outputQueue.enqueue(
          (
            psn,
            OpCode(
              dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
            ),
            dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.data.toBigInt,
            fragLast
          )
        )

        val hasNakOut =
          dut.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean
        hasNakOut shouldBe false withClue
          f"${simTime()} time: hasNakOut=${hasNakOut} should be false when zero payload length"
      }

      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }

  // NOTE: since this test case expects exception, it must be the last test case
  test("PktLenCheck check fail case") {
    testFunc(inputHasNak = false, hasLenCheckErr = true)
  }
}

class ReqSplitterAndNakGenTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 537

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReqSplitterAndNakGen(busWidth))

  def testFunc(
      inputHasNak: Boolean,
      inputNakType: SpinalEnumElement[AckType.type]
  ) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//      dut.io.qpAttr.pmtu #= pmtuLen.id
//      dut.io.rxQCtrl.stateErrFlush #= false
      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)
      val inputQueue = mutable.Queue[
        (
            PSN,
            OpCode.Value,
            PktFragData,
            HasNak,
            FragLast
        )
      ]()
      val outputReadAtomicQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputSendWriteQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputErrRespQueue =
        mutable.Queue[(PSN, OpCode.Value, SpinalEnumElement[AckType.type])]()
      val matchQueue = mutable.Queue[FragLast]()

      RdmaDataPktSim.pktFragStreamMasterDriver(
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
        dut.clockDomain,
        getRdmaPktDataFunc =
          (r: RqReqWithRxBufAndDmaInfoWithLenCheck) => r.pktFrag
      ) {
        val payloadFragNum = payloadFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val payloadLenBytes = payloadLenItr.next()
        val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        (
          psnStart,
          payloadFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          payloadLenBytes.toLong,
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
            payloadLenBytes,
            _, // headerLenBytes,
            opcode
        ) =>
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.lenBytes #= payloadLenBytes
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.dlen #= payloadLenBytes

          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak #= inputHasNak
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBufValid #= opcode
            .needRxBuf()
          if (inputHasNak) {
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth
              .setAs(inputNakType)
            if (AckTypeSim.isRetryNak(inputNakType)) {
              dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBufValid #= false
            }
          }
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
      }
      onStreamFire(
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
        dut.clockDomain
      ) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val opcode =
          OpCode(
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
          )
//        val isLastFrag =
//          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
        inputQueue.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt,
            opcode,
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.data.toBigInt,
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean,
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
          )
        )

//        if (inputHasNak && isLastFrag) {
//          RqNakSim.matchNakType(dut.io.nakNotifier, inputNakType)
//        }
      }

      val dutOutputs = List(
        (dut.io.txReadAtomic, outputReadAtomicQueue),
        (dut.io.txSendWrite, outputSendWriteQueue)
      )
      for ((dutOut, outputQueue) <- dutOutputs) {
        val dutOutStream = dutOut.reqWithRxBufAndDmaInfoWithLenCheck
        streamSlaveRandomizer(dutOutStream, dut.clockDomain)
        onStreamFire(dutOutStream, dut.clockDomain) {
          outputQueue.enqueue(
            (
              dutOutStream.pktFrag.bth.psn.toInt,
              OpCode(dutOutStream.pktFrag.bth.opcodeFull.toInt),
              dutOutStream.pktFrag.data.toBigInt,
              dutOutStream.last.toBoolean
            )
          )
        }
      }

      streamSlaveRandomizer(dut.io.txErrResp, dut.clockDomain)
      onStreamFire(dut.io.txErrResp, dut.clockDomain) {
        outputErrRespQueue.enqueue(
          (
            dut.io.txErrResp.bth.psn.toInt,
            OpCode(dut.io.txErrResp.bth.opcodeFull.toInt),
            AckTypeSim.decodeFromAeth(dut.io.txErrResp.aeth)
          )
        )
      }

      if (inputHasNak) {
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean
        )
        if (AckTypeSim.isRetryNak(inputNakType)) {
//          MiscUtils.checkConditionAlways(dut.clockDomain)(
//            !dut.io.sendWriteWorkCompErrAndNak.valid.toBoolean
//          )
        }
      } else {
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txErrResp.valid.toBoolean
        )
//        MiscUtils.checkConditionAlways(dut.clockDomain)(
//          !dut.io.sendWriteWorkCompErrAndNak.valid.toBoolean
//        )
      }

      fork {
        while (true) {
          val (
            psnIn,
            opCodeIn,
            fragDataIn,
            hasNakIn,
            fragLastIn
          ) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)

          inputHasNak shouldBe hasNakIn withClue
            f"${simTime()} time: inputHasNak=${inputHasNak} should equal hasNakIn=${hasNakIn}"
          if (inputHasNak) {
            if (fragLastIn) { // Length check error is at the last fragment
//              println(
//                f"${simTime()} time: opCodeIn=${opCodeIn} matchQueue.size=${matchQueue.size}, outputErrRespQueue.size=${outputErrRespQueue.size}, workCompQueue.size=${workCompQueue.size}"
//              )

              val (nakPsnOut, nakOpCodeOut, nakTypeOut) =
                MiscUtils.safeDeQueue(outputErrRespQueue, dut.clockDomain)

              nakOpCodeOut shouldBe OpCode.ACKNOWLEDGE withClue
                f"${simTime()} time: invalid error response opcode=${nakOpCodeOut} for PSN=${nakPsnOut}"

              nakTypeOut shouldBe inputNakType withClue
                f"${simTime()} time: ackTypeOut=${nakTypeOut} should equal inputNakType=${inputNakType}"

              nakPsnOut shouldBe psnIn withClue
                f"${simTime()} time: psnIn=${psnIn} should equal nakPsnOut=${nakPsnOut} when inputHasNak=${inputHasNak} and fragLastIn=${fragLastIn} for PSN=${psnIn}%X"

              matchQueue.enqueue(fragLastIn)
            }
          } else {
//            assert(
            opCodeIn.isReadReqPkt() ||
            opCodeIn.isAtomicReqPkt() ||
            opCodeIn.isSendReqPkt() ||
            opCodeIn.isWriteReqPkt() shouldBe true withClue
              f"${simTime()} time: invalid opCodeIn=${opCodeIn}, should be send/write requests"
//            )
            val (psnOut, opCodeOut, fragDataOut, fragLastOut) =
              if (opCodeIn.isSendReqPkt() || opCodeIn.isWriteReqPkt()) {
                MiscUtils.safeDeQueue(outputSendWriteQueue, dut.clockDomain)
              } else {
                MiscUtils.safeDeQueue(outputReadAtomicQueue, dut.clockDomain)
              }

            psnOut shouldBe psnIn withClue
              f"${simTime()} time: psnIn=${psnIn} should equal psnOut=${psnOut} for opCodeIn=${opCodeIn}, opCodeOut=${opCodeOut}"

            opCodeOut shouldBe opCodeIn withClue
              f"${simTime()} time: opCodeIn=${opCodeIn} should equal opCodeOut=${opCodeOut} for PSN=${psnOut}"

            fragDataOut shouldBe fragDataIn withClue
              f"${simTime()} time: fragDataIn=${fragDataIn} should equal fragDataOut=${fragDataOut} for opcode=${opCodeOut}, PSN=${psnOut}"

            fragLastOut shouldBe fragLastIn withClue
              f"${simTime()} time: fragLastIn=${fragLastIn} should equal fragLastOut=${fragLastOut} for opcode=${opCodeOut}, PSN=${psnOut}"
          }
        }

        waitUntil(matchQueue.size > MATCH_CNT)
      }
    }
  }

  test("ReqSplitterAndNakGenTest normal case") {
    testFunc(inputHasNak = false, inputNakType = AckType.NAK_INV)
  }

  test("ReqSplitterAndNakGenTest input fatal NAK case") {
    testFunc(inputHasNak = true, inputNakType = AckType.NAK_INV)
  }

  test("ReqSplitterAndNakGenTest input retry NAK case") {
    testFunc(inputHasNak = true, inputNakType = AckType.NAK_RNR)
  }
}

class ReadDmaReqInitiatorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 337

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadDmaReqInitiator)

  def testFunc(inputDupReq: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//      dut.io.rxQCtrl.stateErrFlush #= false
      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      // Input to DUT
      val inputQueue4DmaReq = mutable.Queue[(PsnStart, PhysicalAddr, PktLen)]()
      val inputQueue4RstCache =
        mutable.Queue[(PsnStart, OpCode.Value, VirtualAddr, DupReq)]()
      val dmaReadReqQueue = mutable.Queue[(PsnStart, PhysicalAddr, PktLen)]()
      val readRstCacheDataQueue =
        mutable.Queue[(PsnStart, OpCode.Value, VirtualAddr, DupReq)]()

      val inputReadReqStream = if (inputDupReq) {
        dut.io.readDmaReqAndRstCacheData.valid #= false
        dut.io.dupReadDmaReqAndRstCacheData
      } else {
        dut.io.dupReadDmaReqAndRstCacheData.valid #= false
        dut.io.readDmaReqAndRstCacheData
      }
      streamMasterDriver(
        inputReadReqStream,
        dut.clockDomain
      ) {
        inputReadReqStream.rstCacheData.dupReq #= inputDupReq
        inputReadReqStream.rstCacheData.opcode #= OpCode.RDMA_READ_REQUEST.id
//      println(
//        f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//      )
      }
      onStreamFire(inputReadReqStream, dut.clockDomain) {
        inputQueue4DmaReq.enqueue(
          (
            inputReadReqStream.dmaReadReq.psnStart.toInt,
            inputReadReqStream.dmaReadReq.pa.toBigInt,
            inputReadReqStream.dmaReadReq.lenBytes.toLong
          )
        )
        val isDupReq = inputDupReq
        inputQueue4RstCache.enqueue(
          (
            inputReadReqStream.rstCacheData.psnStart.toInt,
            OpCode(inputReadReqStream.rstCacheData.opcode.toInt),
            inputReadReqStream.rstCacheData.va.toBigInt,
            isDupReq
          )
        )
      }
      if (inputDupReq) {
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.readDmaReqAndRstCacheData.valid.toBoolean
        )
      } else {
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.dupReadDmaReqAndRstCacheData.valid.toBoolean
        )
      }

      streamSlaveRandomizer(dut.io.readDmaReq.req, dut.clockDomain)
      onStreamFire(dut.io.readDmaReq.req, dut.clockDomain) {
        dmaReadReqQueue.enqueue(
          (
            dut.io.readDmaReq.req.psnStart.toInt,
            dut.io.readDmaReq.req.pa.toBigInt,
            dut.io.readDmaReq.req.lenBytes.toLong
          )
        )
      }

      streamSlaveRandomizer(dut.io.readRstCacheData, dut.clockDomain)
      onStreamFire(dut.io.readRstCacheData, dut.clockDomain) {
        readRstCacheDataQueue.enqueue(
          (
            dut.io.readRstCacheData.psnStart.toInt,
            OpCode(dut.io.readRstCacheData.opcode.toInt),
            dut.io.readRstCacheData.va.toBigInt,
            dut.io.readRstCacheData.dupReq.toBoolean
          )
        )
      }

      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue4DmaReq,
        dmaReadReqQueue,
        MATCH_CNT
      )
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue4RstCache,
        readRstCacheDataQueue,
        MATCH_CNT
      )
    }

  test("ReadDmaReqInitiator normal case") {
    testFunc(inputDupReq = false)
  }

  test("ReadDmaReqInitiator duplicate read request case") {
    testFunc(inputDupReq = true)
  }
}

class RqReadAtomicDmaReqBuilderTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 337

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqReadAtomicDmaReqBuilder(busWidth))

  def testFunc(inputReadOrAtomicReq: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//      dut.io.qpAttr.pmtu #= pmtuLen.id
//      dut.io.rxQCtrl.stateErrFlush #= false
      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      // Input to DUT
      val opcode = if (inputReadOrAtomicReq) {
        OpCode.RDMA_READ_REQUEST
      } else {
        OpCodeSim.randomAtomicOpCode()
      }

      val inputQueue4DmaReq = mutable.Queue[(PsnStart, PhysicalAddr, PktLen)]()
      val inputQueue4RstCache =
        mutable.Queue[(PsnStart, OpCode.Value, PhysicalAddr, DupReq)]()
      val dmaReadReqQueue = mutable.Queue[(PsnStart, PhysicalAddr, PktLen)]()
      val readAtomicRstCacheDataQueue =
        mutable.Queue[(PsnStart, OpCode.Value, PhysicalAddr, DupReq)]()

      streamMasterDriver(
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
        dut.clockDomain
      ) {
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak #= false
        if (opcode.isAtomicReqPkt()) {
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.dlen #= ATOMIC_DATA_LEN
        }
//      println(
//        f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//      )
      }
      onStreamFire(
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
        dut.clockDomain
      ) {
        inputQueue4DmaReq.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt,
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.pa.toBigInt,
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.dlen.toLong
          )
        )
        val isDupReq = false
        inputQueue4RstCache.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt,
            OpCode(
              dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
            ),
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.pa.toBigInt,
            isDupReq
          )
        )
      }

      val output4DmaStream = if (opcode.isReadReqPkt()) {
        dut.io.readDmaReqAndRstCacheData
      } else {
        dut.io.atomicDmaReqAndRstCacheData
      }
      streamSlaveRandomizer(output4DmaStream, dut.clockDomain)
      onStreamFire(output4DmaStream, dut.clockDomain) {
        dmaReadReqQueue.enqueue(
          (
            output4DmaStream.dmaReadReq.psnStart.toInt,
            output4DmaStream.dmaReadReq.pa.toBigInt,
            output4DmaStream.dmaReadReq.lenBytes.toLong
          )
        )
      }

      streamSlaveRandomizer(dut.io.readAtomicRstCachePush, dut.clockDomain)
      onStreamFire(dut.io.readAtomicRstCachePush, dut.clockDomain) {
        readAtomicRstCacheDataQueue.enqueue(
          (
            dut.io.readAtomicRstCachePush.psnStart.toInt,
            OpCode(dut.io.readAtomicRstCachePush.opcode.toInt),
            dut.io.readAtomicRstCachePush.pa.toBigInt,
            dut.io.readAtomicRstCachePush.dupReq.toBoolean
          )
        )
      }

      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue4DmaReq,
        dmaReadReqQueue,
        MATCH_CNT
      )
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue4RstCache,
        readAtomicRstCacheDataQueue,
        MATCH_CNT
      )
    }

  test("RqReadAtomicDmaReqBuilder read request case") {
    testFunc(inputReadOrAtomicReq = true)
  }

  test("RqReadAtomicDmaReqBuilder atomic request case") {
    testFunc(inputReadOrAtomicReq = false)
  }
}

class RqSendWriteDmaReqInitiatorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 337

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqSendWriteDmaReqInitiator(busWidth))

  def testFunc(hasNak: Boolean): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//    dut.io.qpAttr.pmtu #= pmtuLen.id
//    dut.io.rxQCtrl.stateErrFlush #= false
    QpCtrlSim.assignDefaultQpAttrAndFlush(
      dut.io.qpAttr,
      pmtuLen,
      dut.io.flush
    )

    // Input to DUT
    val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    val inputQueue = mutable.Queue[
      (
          PSN,
          OpCode.Value,
          PktFragData,
          PhysicalAddr,
//            PktLen,
          WorkReqId,
          FragLast
      )
    ]()
    val outputSendWriteQueue = mutable
      .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
    val dmaWriteReqQueue =
      mutable.Queue[(PSN, PhysicalAddr, WorkReqId, PktFragData, FragLast)]()
    val matchQueue = mutable.Queue[FragLast]()

    RdmaDataPktSim.pktFragStreamMasterDriver(
      dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain,
      getRdmaPktDataFunc =
        (r: RqReqWithRxBufAndDmaInfoWithLenCheck) => r.pktFrag
    ) {
      val payloadFragNum = payloadFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()
      val workReqOpCode = WorkReqSim.randomSendWriteOpCode()
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
      (
        psnStart,
        payloadFragNum,
        pktNum,
        pmtuLen,
        busWidth,
        payloadLenBytes.toLong,
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
          payloadLenBytes,
          _, // headerLenBytes,
          opcode
      ) =>
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.lenBytes #= payloadLenBytes
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.dlen #= payloadLenBytes

        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak #= hasNak
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBufValid #= opcode
          .needRxBuf()
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
    }
    onStreamFire(
      dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain
    ) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
      val opcode =
        OpCode(
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
        )
      inputQueue.enqueue(
        (
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt,
          opcode,
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.data.toBigInt,
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.pa.toBigInt,
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.id.toBigInt,
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
        )
      )
    }

    streamSlaveRandomizer(dut.io.sendWriteDmaReq.req, dut.clockDomain)
    if (hasNak) {
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.sendWriteDmaReq.req.valid.toBoolean
      )
    } else {
      onStreamFire(dut.io.sendWriteDmaReq.req, dut.clockDomain) {
        dmaWriteReqQueue.enqueue(
          (
            dut.io.sendWriteDmaReq.req.psn.toInt,
            dut.io.sendWriteDmaReq.req.pa.toBigInt,
            dut.io.sendWriteDmaReq.req.workReqId.toBigInt,
            dut.io.sendWriteDmaReq.req.data.toBigInt,
            dut.io.sendWriteDmaReq.req.last.toBoolean
          )
        )
      }
    }

    streamSlaveRandomizer(
      dut.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain
    )
    onStreamFire(
      dut.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain
    ) {
      outputSendWriteQueue.enqueue(
        (
          dut.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt,
          OpCode(
            dut.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
          ),
          dut.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.data.toBigInt,
          dut.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
        )
      )
    }

    fork {
      while (true) {
        val (
          psnIn,
          opCodeIn,
          fragDataIn,
          paIn,
          workReqIdIn,
          fragLastIn
        ) =
          MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)

//        assert(
        opCodeIn.isSendReqPkt() || opCodeIn
          .isWriteReqPkt() shouldBe true withClue
          f"${simTime()} time: invalid opCodeIn=${opCodeIn}, should be send/write requests"
//        )
        val (psnOut, opCodeOut, fragDataOut, fragLastOut) =
          MiscUtils.safeDeQueue(outputSendWriteQueue, dut.clockDomain)

        psnOut shouldBe psnIn withClue
          f"${simTime()} time: psnIn=${psnIn} should equal psnOut=${psnOut} for opCodeIn=${opCodeIn}, opCodeOut=${opCodeOut}"

        opCodeOut shouldBe opCodeIn withClue
          f"${simTime()} time: opCodeIn=${opCodeIn} should equal opCodeOut=${opCodeOut} for PSN=${psnOut}"

        fragDataOut shouldBe fragDataIn withClue
          f"${simTime()} time: fragDataIn=${fragDataIn} should equal fragDataOut=${fragDataOut} for opcode=${opCodeOut}, PSN=${psnOut}"

        fragLastOut shouldBe fragLastIn withClue
          f"${simTime()} time: fragLastIn=${fragLastIn} should equal fragLastOut=${fragLastOut} for opcode=${opCodeOut}, PSN=${psnOut}"
        if (!hasNak) {
          val (
            dmaWritePsnOut,
            dmaWriteAddrOut,
            dmaWriteWorkReqIdOut,
            dmaWriteDataOut,
            dmaWriteFragLastOut
          ) = MiscUtils.safeDeQueue(dmaWriteReqQueue, dut.clockDomain)

          dmaWritePsnOut shouldBe psnIn withClue
            f"${simTime()} time: psnIn=${psnIn} should equal dmaWritePsnOut=${dmaWritePsnOut} for opcode=${opCodeIn}"

          dmaWriteAddrOut shouldBe paIn withClue
            f"${simTime()} time: paIn=${paIn} should equal dmaWriteAddrOut=${dmaWriteAddrOut} for PSN=${psnIn}, opcode=${opCodeIn}"

          dmaWriteWorkReqIdOut shouldBe workReqIdIn withClue
            f"${simTime()} time: workReqIdIn=${workReqIdIn} should equal dmaWriteWorkReqIdOut=${dmaWriteWorkReqIdOut} for PSN=${psnIn}, opcode=${opCodeIn}"

          dmaWriteDataOut shouldBe fragDataIn withClue
            f"${simTime()} time: fragDataIn=${fragDataIn} should equal dmaWriteDataOut=${dmaWriteDataOut} for PSN=${psnIn}, opcode=${opCodeIn}"

          dmaWriteFragLastOut shouldBe fragLastIn withClue
            f"${simTime()} time: fragLastIn=${fragLastIn} should equal dmaWriteFragLastOut=${dmaWriteFragLastOut} for PSN=${psnIn}, opcode=${opCodeIn}"
        }

        matchQueue.enqueue(fragLastIn)
      }
    }

    waitUntil(matchQueue.size > MATCH_CNT)
  }

  test("RqSendWriteDmaReqInitiator normal case") {
    testFunc(hasNak = false)
  }

  test("RqSendWriteDmaReqInitiator input has NAK case") {
    testFunc(hasNak = true)
  }
}

class SendWriteRespGeneratorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val maxFragNum = 337

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new SendWriteRespGenerator(busWidth))

  def testFunc(hasNak: Boolean): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//    dut.io.qpAttr.pmtu #= pmtuLen.id
//    dut.io.rxQCtrl.stateErrFlush #= false
    QpCtrlSim.assignDefaultQpAttrAndFlush(
      dut.io.qpAttr,
      pmtuLen,
      dut.io.flush
    )

    // Input to DUT
    val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    val inputQueue =
      mutable.Queue[(PSN, OpCode.Value, PktFragData, PktLen, WorkReqId)]()
    val outputQueue =
      mutable.Queue[(PSN, OpCode.Value, PktFragData, PktLen, WorkReqId)]()
    val expectedAckQueue =
      mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
    val outputAckQueue = mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
//    val outputWorkCompQueue =
//      mutable.Queue[
//        (
//            SpinalEnumElement[WorkCompOpCode.type],
//            SpinalEnumElement[WorkCompStatus.type],
//            WorkReqId,
//            PktLen
//        )
//      ]()

    RdmaDataPktSim.pktFragStreamMasterDriver(
      dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain,
      getRdmaPktDataFunc =
        (r: RqReqWithRxBufAndDmaInfoWithLenCheck) => r.pktFrag
    ) {
      val payloadFragNum = payloadFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()
      val workReqOpCode = WorkReqSim.randomSendWriteImmOpCode()
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
      (
        psnStart,
        payloadFragNum,
        pktNum,
        pmtuLen,
        busWidth,
        payloadLenBytes.toLong,
        workReqOpCode
      )
    } {
      (
          _, // psn,
          _, // psnStart
          fragLast,
          _, // fragIdx,
          _, // pktFragNum,
          pktIdx,
          pktNum,
          payloadLenBytes,
          _, // headerLenBytes,
          opcode
      ) =>
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.lenBytes #= payloadLenBytes
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.dlen #= payloadLenBytes

        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenValid #= fragLast
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak #= hasNak
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBufValid #= opcode
          .needRxBuf()

        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.ackreq #= (pktIdx == pktNum - 1)
        if (hasNak) {
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth
            .setAs(AckTypeSim.randomFatalNak())
        } else {
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth.setAsNormalAck()
        }
//        println(
//          f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
    }
    onStreamFire(
      dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain
    ) {
      val isLastFrag =
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
      val ackReq =
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.ackreq.toBoolean
      val psn =
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt
//      println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt}, isLastFrag=${isLastFrag}, ackReq=${ackReq}")
      if (ackReq && isLastFrag && !hasNak) {
        expectedAckQueue.enqueue((psn, AckType.NORMAL))
      }
      inputQueue.enqueue(
        (
          psn,
          OpCode(
            dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
          ),
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.data.toBigInt,
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenBytes.toLong,
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.id.toBigInt
        )
      )
    }
    streamSlaveRandomizer(dut.io.tx, dut.clockDomain)
    onStreamFire(dut.io.tx, dut.clockDomain) {
      outputAckQueue.enqueue(
        (
          dut.io.tx.bth.psn.toInt,
          AckTypeSim.decodeFromAeth(dut.io.tx.aeth)
        )
      )
    }

    streamSlaveRandomizer(
      dut.io.txSendWriteReq.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain
    )
    onStreamFire(
      dut.io.txSendWriteReq.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain
    ) {
      outputQueue.enqueue(
        (
          dut.io.txSendWriteReq.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt,
          OpCode(
            dut.io.txSendWriteReq.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
          ),
          dut.io.txSendWriteReq.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.data.toBigInt,
          dut.io.txSendWriteReq.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenBytes.toLong,
          dut.io.txSendWriteReq.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.id.toBigInt
        )
      )
    }

    if (hasNak) {
      MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
        !dut.io.tx.valid.toBoolean,
        clue =
          f"${simTime()} time: when hasNak=${hasNak}, SendWriteRespGenerator should not response ACK, since it only responses normal ACK"
      )
    } else {
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        expectedAckQueue,
        outputAckQueue,
        MATCH_CNT
      )
    }

    MiscUtils.checkExpectedOutputMatch(
      dut.clockDomain,
      inputQueue,
      outputQueue,
      MATCH_CNT
    )
  /*
    streamSlaveRandomizer(
      dut.io.sendWriteWorkCompAndAck,
      dut.clockDomain
    )
    onStreamFire(dut.io.sendWriteWorkCompAndAck, dut.clockDomain) {
      outputWorkCompQueue.enqueue(
        (
          dut.io.sendWriteWorkCompAndAck.workComp.opcode.toEnum,
          dut.io.sendWriteWorkCompAndAck.workComp.status.toEnum,
          dut.io.sendWriteWorkCompAndAck.workComp.id.toBigInt,
          dut.io.sendWriteWorkCompAndAck.workComp.lenBytes.toLong
        )
      )
    }

    fork {
      while (true) {
        val (psnIn, opCodeIn, pktLenIn, workReqIdIn) =
          MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)
        val (psnOut, ackTypeOut) =
          MiscUtils.safeDeQueue(outputAckQueue, dut.clockDomain)
        val (workCompOpCodeOut, workCompStatusOut, workReqIdOut, pktLenOut) =
          MiscUtils.safeDeQueue(outputWorkCompQueue, dut.clockDomain)

        println(
          f"${simTime()} time: psnOut=${psnOut}%X should equal psnIn=${psnIn}%X"
        )
        psnOut shouldBe psnIn withClue f"${simTime()} time: psnOut=${psnOut}%X should equal psnIn=${psnIn}%X"
        println(
          f"${simTime()} time: pktLenOut=${pktLenOut}%X should equal pktLenIn=${pktLenIn}%X"
        )
        pktLenOut shouldBe pktLenIn withClue f"${simTime()} time: pktLenOut=${pktLenOut}%X should equal pktLenIn=${pktLenIn}%X"
        println(
          f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should equal workReqIdIn=${workReqIdIn}%X"
        )
        workReqIdOut shouldBe workReqIdIn withClue f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should equal workReqIdIn=${workReqIdIn}%X"
        println(
          f"${simTime()} time: ackTypeOut=${ackTypeOut}, workCompStatusOut=${workCompStatusOut}, opCodeIn=${opCodeIn}, workCompOpCodeOut=${workCompOpCodeOut}"
        )
        WorkCompSim.rqCheckWorkCompStatus(ackTypeOut, workCompStatusOut)
        WorkCompSim.rqCheckWorkCompOpCode(opCodeIn, workCompOpCodeOut)

        matchQueue.enqueue(psnOut)
      }
    }
    waitUntil(matchQueue.size > MATCH_CNT)
   */
  }

  test("SendWriteRespGenerator normal case") {
    testFunc(hasNak = false)
  }

  test("SendWriteRespGenerator fatal error case") {
    testFunc(hasNak = true)
  }
}

class RqSendWriteWorkCompGeneratorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val maxFragNum = 37

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new RqSendWriteWorkCompGenerator(busWidth))

  def testFunc(hasNak: Boolean): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//    dut.io.qpAttr.pmtu #= pmtuLen.id
//    dut.io.rxQCtrl.stateErrFlush #= false
    QpCtrlSim.assignDefaultQpAttrAndFlush(
      dut.io.qpAttr,
      pmtuLen,
      dut.io.flush
    )

    // Input to DUT
    val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    val inputQueue = mutable.Queue[
      (OpCode.Value, SpinalEnumElement[AckType.type], WorkReqId, PktLen)
    ]()
    val outputQueue =
      mutable.Queue[
        (
            SpinalEnumElement[WorkCompOpCode.type],
            SpinalEnumElement[WorkCompStatus.type],
            WorkReqId,
            PktLen
        )
      ]()
//    val psn4DmaWriteRespQueue = mutable.Queue[PSN]()
    val psn4DmaWriteRespQueue =
      DelayedQueue[PSN](dut.clockDomain, DMA_WRITE_DELAY_CYCLES)
    val matchQueue = mutable.Queue[WorkReqId]()

    RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
      dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain,
      getRdmaPktDataFunc =
        (r: RqReqWithRxBufAndDmaInfoWithLenCheck) => r.pktFrag
    ) {
      val payloadFragNum = payloadFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()
      val workReqOpCode = WorkReqSim.randomSendWriteImmOpCode()
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
      (
        psnStart,
        payloadFragNum,
        pktNum,
        pmtuLen,
        busWidth,
        payloadLenBytes.toLong,
        workReqOpCode
      )
    } {
      (
          _, // psn,
          _, // psnStart
          _, // fragLast,
          _, // fragIdx,
          _, // pktFragNum,
          pktIdx,
          pktNum,
          payloadLenBytes,
          _, // headerLenBytes,
          opcode
      ) =>
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.lenBytes #= payloadLenBytes
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.dmaInfo.dlen #= payloadLenBytes

        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenValid #= true
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak #= hasNak
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBufValid #=
          opcode.needRxBuf()

        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.ackreq #= (pktIdx == pktNum - 1)
        if (hasNak) {
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth
            .setAs(AckTypeSim.randomFatalNak())
        } else {
          dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth.setAsNormalAck()
        }
//        println(
//          f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
    }
    onStreamFire(
      dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck,
      dut.clockDomain
    ) {
      val isLastFrag =
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
      val ackReq =
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.ackreq.toBoolean
      val psn =
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn.toInt
      val ackType = AckTypeSim.decodeFromAeth(
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ackAeth
      )
      if (isLastFrag) {
        if (!hasNak) {
          psn4DmaWriteRespQueue.enqueue(psn)
        }
        if (ackReq) {
//          println(f"${simTime()} time: dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.psn=${psn}, isLastFrag=${isLastFrag}, ackReq=${ackReq}, ackType=${ackType}")

          inputQueue.enqueue(
            (
              OpCode(
                dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
              ),
              ackType,
              dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.rxBuf.id.toBigInt,
              dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenBytes.toLong
            )
          )
        }
      }
    }
    /*
    val psnStart = 0
    val psnItr = NaturalNumber.from(psnStart).iterator
    val psn4WorkCompQueue = mutable.Queue[PSN]()

    fork {
      while (true) {
        for (_ <- 0 until PENDING_REQ_NUM) {
          val psn = psnItr.next()
          psn4WorkCompQueue.enqueue(psn)
          psn4DmaWriteRespQueue.enqueue(psn)
        }

        waitUntil(psn4WorkCompQueue.isEmpty)
      }
    }

    if (hasNak) {
      streamMasterDriver(
        dut.io.sendWriteWorkCompAndAck,
        dut.clockDomain
      ) {
        val psn = psn4WorkCompQueue.dequeue()
        dut.io.qpAttr.epsn #= psn
        dut.io.sendWriteWorkCompAndAck.ack.bth.psn #= psn
        dut.io.sendWriteWorkCompAndAck.ackValid #= true
        dut.io.sendWriteWorkCompAndAck.ack.aeth.setAsInvReqNak()
      }
    } else {
      // dut.io.sendWriteWorkCompAndAck must be always valid to wait for DMA response
      streamMasterDriverAlwaysValid(
        dut.io.sendWriteWorkCompAndAck,
        dut.clockDomain
      ) {
        val psn = psn4WorkCompQueue.dequeue()
        dut.io.qpAttr.epsn #= psn
        dut.io.sendWriteWorkCompAndAck.ack.bth.psn #= psn
        dut.io.sendWriteWorkCompAndAck.ackValid #= true
        dut.io.sendWriteWorkCompAndAck.ack.aeth.setAsNormalAck()
      }
    }
    onStreamFire(dut.io.sendWriteWorkCompAndAck, dut.clockDomain) {
      inputQueue.enqueue(
        (
          dut.io.sendWriteWorkCompAndAck.workComp.id.toBigInt,
          dut.io.sendWriteWorkCompAndAck.workComp.lenBytes.toLong
        )
      )
    }
     */
    streamMasterPayloadFromQueueNoRandomDelay(
      dut.io.dmaWriteResp.resp,
      dut.clockDomain,
      psn4DmaWriteRespQueue.toMutableQueue(),
      payloadAssignFunc = (payloadData: DmaWriteResp, psn: PSN) => {
        payloadData.psn #= psn

        val respValid = true
        respValid
      }
    )
//    if (hasNak) {
//      dut.io.dmaWriteResp.resp.valid #= false
//      MiscUtils.checkConditionAlways(dut.clockDomain)(
//        !dut.io.dmaWriteResp.resp.ready.toBoolean
//      )
//    } else {
//      streamMasterDriver(dut.io.dmaWriteResp.resp, dut.clockDomain) {
//        val psn = psn4DmaWriteRespQueue.dequeue()
//        dut.io.dmaWriteResp.resp.psn #= psn
//      }
//    }

    streamSlaveAlwaysReady(dut.io.sendWriteWorkCompOut, dut.clockDomain)
    onStreamFire(dut.io.sendWriteWorkCompOut, dut.clockDomain) {
      outputQueue.enqueue(
        (
          dut.io.sendWriteWorkCompOut.opcode.toEnum,
          dut.io.sendWriteWorkCompOut.status.toEnum,
          dut.io.sendWriteWorkCompOut.id.toBigInt,
          dut.io.sendWriteWorkCompOut.lenBytes.toLong
        )
      )
    }
//    streamSlaveRandomizer(dut.io.sendWriteWorkCompOut, dut.clockDomain)
//    onStreamFire(dut.io.sendWriteWorkCompOut, dut.clockDomain) {
//      outputQueue.enqueue(
//        (
//          dut.io.sendWriteWorkCompOut.id.toBigInt,
//          dut.io.sendWriteWorkCompOut.lenBytes.toLong
//        )
//      )
//    }

    fork {
      while (true) {
        val (opCodeIn, ackTypeIn, workReqIdIn, pktLenIn) =
          MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)
        val (workCompOpCodeOut, workCompStatusOut, workReqIdOut, pktLenOut) =
          MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)

//        println(
//          f"${simTime()} time: pktLenOut=${pktLenOut}%X should equal pktLenIn=${pktLenIn}%X"
//        )
        pktLenOut shouldBe pktLenIn withClue f"${simTime()} time: pktLenOut=${pktLenOut}%X should equal pktLenIn=${pktLenIn}%X"
//        println(
//          f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should equal workReqIdIn=${workReqIdIn}%X"
//        )
        workReqIdOut shouldBe workReqIdIn withClue f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should equal workReqIdIn=${workReqIdIn}%X"
//        println(
//          f"${simTime()} time: ackTypeIn=${ackTypeIn}, workCompStatusOut=${workCompStatusOut}, opCodeIn=${opCodeIn}, workCompOpCodeOut=${workCompOpCodeOut}"
//        )
        WorkCompSim.rqCheckWorkCompStatus(ackTypeIn, workCompStatusOut)
        WorkCompSim.rqCheckWorkCompOpCode(opCodeIn, workCompOpCodeOut)

        matchQueue.enqueue(workReqIdIn)
      }
    }

    MiscUtils.checkCondChangeOnceAndHoldAfterwards(
      dut.clockDomain,
      cond =
        dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean && dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ready.toBoolean,
      clue =
        f"${simTime()} time: dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.fire=${dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean && dut.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.ready.toBoolean} should be true always"
    )
    waitUntil(matchQueue.size > MATCH_CNT)
  }

  test("RqSendWriteWorkCompGenerator normal case") {
    testFunc(hasNak = false)
  }

  test("RqSendWriteWorkCompGenerator fatal error case") {
    testFunc(hasNak = true)
  }
}

class DupReqHandlerAndReadAtomicRstCacheQueryTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new DupReqHandlerAndReadAtomicRstCacheQuery(busWidth))

  def testFunc(readAtomicRstCacheQuerySuccess: Boolean) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      val expectedDupReadReqQueue = mutable
        .Queue[(PSN, VirtualAddr, PktLen, PktNum)]()
      val outputDupReadReqQueue = mutable
        .Queue[(PsnStart, VirtualAddr, PktLen, PktNum)]()

      val expectedDupAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outputDupAtomicReqQueue = mutable.Queue[(PSN, OpCode.Value)]()

      val readAtomicRstMap = mutable.Map[
        PSN,
        (PsnStart, VirtualAddr, PktLen, PktNum, AtomicSwap, AtomicComp)
      ]()
      val readAtomicRstCacheSim = ReadAtomicRstCacheSim(readAtomicRstMap)

      RdmaDataPktSim.readAtomicReqStreamMasterDriverAlwaysValid(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        getRdmaPktDataFunc = (r: RdmaDataPkt) => r,
        readAtomicRstCacheFull = false,
        pmtuLen,
        busWidth,
        maxFragNum
      ) {
        (
            psn,
            _, // psnStart
            _, // fragLast,
            _, // fragIdx,
            _, // pktFragNum,
            _, // pktIdx,
            _, // reqPktNum,
            respPktNum,
            payloadLenBytes,
            _, // headerLenBytes,
            _ // opcode
        ) =>
          val pktFragData = dut.io.rx.pktFrag.data.toBigInt
          // NOTE: AtomicETH and RETH have the same VA and RKEY structure
          val (virtualAddr, _, atomicSwap, atomicComp) =
            AtomicEthSim.extract(pktFragData, busWidth)

          readAtomicRstMap.put(
            psn,
            (
              psn,
              virtualAddr,
              payloadLenBytes,
              respPktNum,
              atomicSwap,
              atomicComp
            )
          )

//              println(f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, virtualAddr=${virtualAddr}%X")
      }

      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
        val opcode = OpCode(dut.io.rx.pktFrag.bth.opcodeFull.toInt)
        val psn = dut.io.rx.pktFrag.bth.psn.toInt
        if (opcode.isAtomicReqPkt()) {
          expectedDupAtomicRespQueue.enqueue(
            (
              psn,
              OpCode.ATOMIC_ACKNOWLEDGE
            )
          )
        } else if (opcode.isReadReqPkt()) {
          val (virtualAddr, _, respPktLen) =
            RethSim.extract(dut.io.rx.pktFrag.data.toBigInt, busWidth)
          val respPktNum = MiscUtils.computePktNum(respPktLen, pmtuLen)
          expectedDupReadReqQueue.enqueue(
            (psn, virtualAddr, respPktLen, respPktNum)
          )
        }

//        println(f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}")
      }

      // readRstCacheRespQueue
      val _ = if (readAtomicRstCacheQuerySuccess) {
        readAtomicRstCacheSim.reqStreamFixedDelayAndRespSuccess(
          dut.io.readAtomicRstCache,
          dut.clockDomain,
          fixedRespDelayCycles = READ_ATOMIC_RESULT_CACHE_QUERY_DELAY_CYCLES
        )
      } else {
        readAtomicRstCacheSim.reqStreamFixedDelayAndRespFailure(
          dut.io.readAtomicRstCache,
          dut.clockDomain,
          fixedRespDelayCycles = READ_ATOMIC_RESULT_CACHE_QUERY_DELAY_CYCLES
        )
      }

      streamSlaveAlwaysReady(
        dut.io.dupReadReqAndRstCacheData,
        dut.clockDomain
      )
      onStreamFire(dut.io.dupReadReqAndRstCacheData, dut.clockDomain) {
//        expectedReadRstCacheRespQueue.enqueue(
//          (
//            dut.io.dupReadReqAndRstCacheData.pktFrag.bth.psn.toInt,
//            readAtomicRstCacheQuerySuccess,
//            dut.io.dupReadReqAndRstCacheData.rstCacheData.psnStart.toInt,
//            dut.io.dupReadReqAndRstCacheData.rstCacheData.pa.toBigInt,
//            dut.io.dupReadReqAndRstCacheData.rstCacheData.pktNum.toInt,
//            dut.io.dupReadReqAndRstCacheData.rstCacheData.dlen.toLong,
//          )
//        )

        outputDupReadReqQueue.enqueue(
          (
            dut.io.dupReadReqAndRstCacheData.rstCacheData.psnStart.toInt,
            dut.io.dupReadReqAndRstCacheData.rstCacheData.va.toBigInt,
            dut.io.dupReadReqAndRstCacheData.rstCacheData.dlen.toLong,
            dut.io.dupReadReqAndRstCacheData.rstCacheData.pktNum.toInt
          )
        )

        dut.io.dupReadReqAndRstCacheData.rstCacheData.dupReq.toBoolean shouldBe true withClue
          f"${simTime()} time: dut.io.dupReadReqAndRstCacheData.rstCacheData.dupReq=${dut.io.dupReadReqAndRstCacheData.rstCacheData.dupReq.toBoolean} should be true for duplicate read request"
      }

      streamSlaveAlwaysReady(dut.io.txDupAtomicResp, dut.clockDomain)
      onStreamFire(dut.io.txDupAtomicResp, dut.clockDomain) {
        outputDupAtomicReqQueue.enqueue(
          (
            dut.io.txDupAtomicResp.bth.psn.toInt,
            OpCode(dut.io.txDupAtomicResp.bth.opcodeFull.toInt)
          )
        )
      }

      MiscUtils.checkCondChangeOnceAndHoldAfterwards(
        dut.clockDomain,
        cond =
          dut.io.rx.pktFrag.valid.toBoolean && dut.io.rx.pktFrag.ready.toBoolean,
        clue =
          f"${simTime()} time: dut.io.rx.pktFrag.fire=${dut.io.rx.pktFrag.valid.toBoolean && dut.io.rx.pktFrag.ready.toBoolean} should be true always"
      )
      if (readAtomicRstCacheQuerySuccess) {
//          MiscUtils.checkCondChangeOnceAndHoldAfterwards(
//            dut.clockDomain,
//            cond = dut.io.dupReadReqAndRstCacheData.valid.toBoolean,
//            clue =
//              f"${simTime()} time: dut.io.dupReadReqAndRstCacheData.valid=${dut.io.dupReadReqAndRstCacheData.valid.toBoolean} should be true always"
//          )

//          println(
//            f"${simTime()} time: dut.io.dupReadReqAndRstCacheData.valid=${dut.io.dupReadReqAndRstCacheData.valid.toBoolean} should be true always"
//          )

//          MiscUtils.checkExpectedOutputMatch(
//            dut.clockDomain,
//            expectedReadRstCacheRespQueue,
//            readRstCacheRespQueue,
//            MATCH_CNT
//          )
        MiscUtils.checkExpectedOutputMatch(
          dut.clockDomain,
          expectedDupReadReqQueue,
          outputDupReadReqQueue,
          MATCH_CNT
        )
//          MiscUtils.checkCondChangeOnceAndHoldAfterwards(
//            dut.clockDomain,
//            cond = dut.io.txDupAtomicResp.valid.toBoolean,
//            clue =
//              f"${simTime()} time: dut.io.txDupAtomicResp.valid=${dut.io.txDupAtomicResp.valid.toBoolean} should be true always"
//          )

        MiscUtils.checkExpectedOutputMatch(
          dut.clockDomain,
          expectedDupAtomicRespQueue,
          outputDupAtomicReqQueue,
          MATCH_CNT
        )
      } else {
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.dupReadReqAndRstCacheData.valid.toBoolean
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txDupAtomicResp.valid.toBoolean
        )
      }
    }
  }

  test(
    "DupReqHandlerAndReadAtomicRstCacheQuery duplicate read/atomic request query success"
  ) {
    testFunc(readAtomicRstCacheQuerySuccess = true)
  }

  test(
    "DupReqHandlerAndReadAtomicRstCacheQuery duplicate read/atomic request query failure"
  ) {
    testFunc(readAtomicRstCacheQuerySuccess = false)
  }

  test("DupReqHandlerAndReadAtomicRstCacheQuery duplicate send/write request") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      val inputQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outputQueue = mutable.Queue[(PSN, OpCode.Value)]()

      RdmaDataPktSim.sendWriteReqPktFragStreamMasterDriver(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        getRdmaPktDataFunc = (r: RdmaDataPkt) => r,
        pendingReqNumExceed = false,
        pmtuLen,
        busWidth,
        maxFragNum
      ) {
        (
            _, // PSN,
            _, // PsnStart,
            _, // FragLast,
            _, // FragIdx,
            _, // FragNum,
            _, // PktIdx,
            _, // ReqPktNum,
            _, // RespPktNum,
            _, // PktLen,
            _, // HeaderLen,
            _ // OpCode.Value
        ) =>
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
      }
      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val opcode = OpCode.ACKNOWLEDGE
        val isLastFrag = dut.io.rx.pktFrag.last.toBoolean
        if (isLastFrag) {
          inputQueue.enqueue(
            (
              dut.io.qpAttr.rqOutPsn.toInt,
              opcode
            )
          )
        }
      }

      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.readAtomicRstCache.req.valid.toBoolean
      )
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.readAtomicRstCache.resp.ready.toBoolean
      )
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.txDupAtomicResp.valid.toBoolean
      )
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.dupReadReqAndRstCacheData.valid.toBoolean
      )

      streamSlaveRandomizer(dut.io.txDupSendWriteResp, dut.clockDomain)
      onStreamFire(dut.io.txDupSendWriteResp, dut.clockDomain) {
        val opcode =
          OpCode(dut.io.txDupSendWriteResp.bth.opcodeFull.toInt)
        outputQueue.enqueue((dut.io.txDupSendWriteResp.bth.psn.toInt, opcode))
      }

      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }
}

class DupReadDmaReqBuilderTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 337

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new DupReadDmaReqBuilder(busWidth))

  def randomRetryStartPsn(psnStart: PsnStart, pktNum: PktNum): PSN = {
    // RDMA max packet length 2GB=2^31
    psnStart +% scala.util.Random.nextInt(pktNum)
  }

  def testFunc(isPartialRetry: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//      dut.io.qpAttr.pmtu #= pmtuLen.id
//      dut.io.rxQCtrl.stateErrFlush #= false
      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      val inputQueue = mutable.Queue[
        (
            PsnStart,
            PhysicalAddr,
            PktLen,
            PsnStart,
            OpCode.Value,
            VirtualAddr,
            LRKey
        )
      ]()
      val outputDmaReqQueue = mutable.Queue[
        (
            PsnStart,
            PhysicalAddr,
            PktLen,
            PsnStart,
            OpCode.Value,
            VirtualAddr,
            LRKey
        )
      ]()

      var nextPsn = 0
      val opcode = OpCode.RDMA_READ_REQUEST

      // Input to DUT
      streamMasterDriver(dut.io.rxDupReadReqAndRstCacheData, dut.clockDomain) {
        val curPsn = nextPsn
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.psnStart #= curPsn
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.opcode #= opcode.id
        val pktLen = WorkReqSim.randomDmaLength()
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.dlen #= pktLen
        val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.pktNum #= pktNum
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.dupReq #= true

        val retryStartPsn = if (isPartialRetry) {
          randomRetryStartPsn(curPsn, pktNum)
        } else {
          curPsn
        }
        dut.io.rxDupReadReqAndRstCacheData.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rxDupReadReqAndRstCacheData.pktFrag.bth.psn #= retryStartPsn
        nextPsn = nextPsn +% pktNum
        dut.io.qpAttr.epsn #= nextPsn
//        println(
//          f"${simTime()} time: the input opcode=${opcode}, curPsn=${curPsn}%X, nextPsn=${nextPsn}%X, retryStartPsn=${retryStartPsn}%X, pktLen=${pktLen}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rxDupReadReqAndRstCacheData, dut.clockDomain) {
        val retryStartPsn =
          dut.io.rxDupReadReqAndRstCacheData.pktFrag.bth.psn.toInt
        val origReqPsnStart =
          dut.io.rxDupReadReqAndRstCacheData.rstCacheData.psnStart.toInt
        val origReqLenBytes =
          dut.io.rxDupReadReqAndRstCacheData.rstCacheData.dlen.toLong
        val origPhysicalAddr =
          dut.io.rxDupReadReqAndRstCacheData.rstCacheData.pa.toBigInt
        val psnDiff = PsnSim.psnDiff(retryStartPsn, origReqPsnStart)
        val dmaReadOffset = psnDiff << pmtuLen.id
        val dmaLenBytes = if (isPartialRetry) {
          origReqLenBytes - dmaReadOffset
        } else {
          origReqLenBytes
        }
        val retryPhysicalAddr = if (isPartialRetry) {
          origPhysicalAddr + dmaReadOffset
        } else {
          origPhysicalAddr
        }

//        println(
//          L"${simTime()} time: psnDiff=${psnDiff}%X < origReqPktNum=${dut.io.rxDupReadReqAndRstCacheData.rstCacheData.pktNum.toInt}%X, retryReqPsn=${retryStartPsn}, retryPhysicalAddr=${retryPhysicalAddr} = origReqPsnStart=${origReqPsnStart} + retryDmaReadOffset=${dmaReadOffset}",
//        )
        inputQueue.enqueue(
          (
            retryStartPsn,
            retryPhysicalAddr,
            dmaLenBytes,
            origReqPsnStart,
            OpCode(
              dut.io.rxDupReadReqAndRstCacheData.rstCacheData.opcode.toInt
            ),
            dut.io.rxDupReadReqAndRstCacheData.rstCacheData.va.toBigInt,
            dut.io.rxDupReadReqAndRstCacheData.rstCacheData.rkey.toLong
          )
        )
      }

      // Check DUT output
      streamSlaveRandomizer(
        dut.io.dupReadDmaReqAndRstCacheData,
        dut.clockDomain
      )
      onStreamFire(dut.io.dupReadDmaReqAndRstCacheData, dut.clockDomain) {
        outputDmaReqQueue.enqueue(
          (
            dut.io.dupReadDmaReqAndRstCacheData.dmaReadReq.psnStart.toInt,
            dut.io.dupReadDmaReqAndRstCacheData.dmaReadReq.pa.toBigInt,
            dut.io.dupReadDmaReqAndRstCacheData.dmaReadReq.lenBytes.toLong,
            dut.io.dupReadDmaReqAndRstCacheData.rstCacheData.psnStart.toInt,
            OpCode(
              dut.io.dupReadDmaReqAndRstCacheData.rstCacheData.opcode.toInt
            ),
            dut.io.dupReadDmaReqAndRstCacheData.rstCacheData.va.toBigInt,
            dut.io.dupReadDmaReqAndRstCacheData.rstCacheData.rkey.toLong
          )
        )
      }

      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputQueue,
        outputDmaReqQueue,
        MATCH_CNT
      )
    }

  test("DupReadDmaReqBuilder full retry case") {
    testFunc(isPartialRetry = false)
  }

  test("DupReadDmaReqBuilder partial retry case") {
    testFunc(isPartialRetry = true)
  }
}

class ReadRespGeneratorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadRespGenerator(busWidth))

  test("input zero DMA length normal read req") {
    zeroDmaLenTestFunc(inputDupReq = false)
  }

  test("input zero DMA length duplicate read req") {
    zeroDmaLenTestFunc(inputDupReq = true)
  }

  def zeroDmaLenTestFunc(inputDupReq: Boolean) = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    val inputPsnQueue = mutable.Queue[PSN]()
    val outputPsnQueue = mutable.Queue[PSN]()
    val naturalNumItr = NaturalNumber.from(1).iterator

//    dut.io.qpAttr.pmtu #= pmtuLen.id
//    dut.io.rxQCtrl.stateErrFlush #= false
    QpCtrlSim.assignDefaultQpAttrAndFlush(
      dut.io.qpAttr,
      pmtuLen,
      dut.io.flush
    )

    // Input to DUT
    streamMasterDriver(
      dut.io.readRstCacheDataAndDmaReadRespSegment,
      dut.clockDomain
    ) {
      val psn = naturalNumItr.next()
      dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.psnStart #= psn
      dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.psnStart #= psn
      dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.pktNum #= 0
      dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.dlen #= 0
      dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.dupReq #= inputDupReq
      dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes #= 0
      dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.mty #= 0
      dut.io.readRstCacheDataAndDmaReadRespSegment.last #= true
    }
    onStreamFire(
      dut.io.readRstCacheDataAndDmaReadRespSegment,
      dut.clockDomain
    ) {
      inputPsnQueue.enqueue(
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.psnStart.toInt
      )
    }

    // Check DUT output
    if (inputDupReq) {
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.txReadResp.pktFrag.valid.toBoolean
      )

      streamSlaveRandomizer(dut.io.txDupReadResp.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txDupReadResp.pktFrag, dut.clockDomain) {
        outputPsnQueue.enqueue(dut.io.txDupReadResp.pktFrag.bth.psn.toInt)
      }
    } else {
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.txDupReadResp.pktFrag.valid.toBoolean
      )

      streamSlaveRandomizer(dut.io.txReadResp.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txReadResp.pktFrag, dut.clockDomain) {
        outputPsnQueue.enqueue(dut.io.txReadResp.pktFrag.bth.psn.toInt)
      }
    }
    MiscUtils.checkExpectedOutputMatch(
      dut.clockDomain,
      inputPsnQueue,
      outputPsnQueue,
      MATCH_CNT
    )
  }

  test("input non-zero DMA length normal read req") {
    testFunc(inputDupReq = false)
  }

  test("input non-zero DMA length duplicate read req") {
    testFunc(inputDupReq = true)
  }

  def testFunc(inputDupReq: Boolean) = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    val inputDataQueue =
      mutable.Queue[(PktFragData, MTY, PktNum, PsnStart, PktLen, FragLast)]()
    val outputDataQueue = mutable.Queue[(PktFragData, MTY, PSN, FragLast)]()

//    dut.io.qpAttr.pmtu #= pmtuLen.id
//    dut.io.rxQCtrl.stateErrFlush #= false
    QpCtrlSim.assignDefaultQpAttrAndFlush(
      dut.io.qpAttr,
      pmtuLen,
      dut.io.flush
    )

    // Input to DUT
    val (totalFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
      SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)

    DmaReadRespSim.pktFragStreamMasterDriver(
      dut.io.readRstCacheDataAndDmaReadRespSegment,
      dut.clockDomain,
      getDmaReadRespPktDataFunc =
        (rstCacheDataAndDmaReadResp: ReadAtomicRstCacheDataAndDmaReadResp) =>
          rstCacheDataAndDmaReadResp.dmaReadResp,
      segmentRespByPmtu = true
    ) {
      val totalFragNum = totalFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()

      (
        psnStart,
        totalFragNum,
        pktNum,
        pmtuLen,
        busWidth,
        payloadLenBytes.toLong
      )
    } {
      (
          _, // psn,
          psnStart,
          fragLast,
          _, // fragIdx,
          _, // totalFragNum,
          _, // pktIdx,
          pktNum,
          payloadLenBytes
      ) =>
//        println(
//          f"${simTime()} time: fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, fragLast=${fragLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )

        dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.psnStart #= psnStart
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.psnStart #= psnStart
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.dlen #= payloadLenBytes
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.pktNum #= pktNum
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.dupReq #= inputDupReq
        dut.io.readRstCacheDataAndDmaReadRespSegment.last #= fragLast
    }
    onStreamFire(
      dut.io.readRstCacheDataAndDmaReadRespSegment,
      dut.clockDomain
    ) {
      inputDataQueue.enqueue(
        (
          dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.data.toBigInt,
          dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.mty.toBigInt,
          dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.pktNum.toInt,
          dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.psnStart.toInt,
          dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes.toLong,
          dut.io.readRstCacheDataAndDmaReadRespSegment.last.toBoolean
        )
      )
    }

    if (inputDupReq) {
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.txReadResp.pktFrag.valid.toBoolean
      )

      streamSlaveRandomizer(dut.io.txDupReadResp.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txDupReadResp.pktFrag, dut.clockDomain) {
        outputDataQueue.enqueue(
          (
            dut.io.txDupReadResp.pktFrag.data.toBigInt,
            dut.io.txDupReadResp.pktFrag.mty.toBigInt,
            dut.io.txDupReadResp.pktFrag.bth.psn.toInt,
            dut.io.txDupReadResp.pktFrag.last.toBoolean
          )
        )
      }
    } else {
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.txDupReadResp.pktFrag.valid.toBoolean
      )

      streamSlaveRandomizer(dut.io.txReadResp.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txReadResp.pktFrag, dut.clockDomain) {
        outputDataQueue.enqueue(
          (
            dut.io.txReadResp.pktFrag.data.toBigInt,
            dut.io.txReadResp.pktFrag.mty.toBigInt,
            dut.io.txReadResp.pktFrag.bth.psn.toInt,
            dut.io.txReadResp.pktFrag.last.toBoolean
          )
        )
      }
    }
    MiscUtils.checkSendWriteReqReadResp(
      dut.clockDomain,
      inputDataQueue,
      outputDataQueue,
      busWidth
    )
  }
}

class RqReadDmaRespHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new RqReadDmaRespHandler(busWidth))

  test("RqReadDmaRespHandler zero DMA length read response case") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//      dut.io.rxQCtrl.stateErrFlush #= false
      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      val psnQueue = mutable.Queue[PSN]()
      val matchQueue = mutable.Queue[PSN]()

      // Input to DUT
      streamMasterDriverAlwaysValid(dut.io.readRstCacheData, dut.clockDomain) {
        dut.io.readRstCacheData.dlen #= 0
      }
      onStreamFire(dut.io.readRstCacheData, dut.clockDomain) {
        psnQueue.enqueue(dut.io.readRstCacheData.psnStart.toInt)
      }

      // Check DUT output
      MiscUtils.checkConditionAlways(dut.clockDomain) {
        !dut.io.dmaReadResp.resp.ready.toBoolean
      }
      streamSlaveAlwaysReady(
        dut.io.readRstCacheDataAndDmaReadResp,
        dut.clockDomain
      )
      onStreamFire(dut.io.readRstCacheDataAndDmaReadResp, dut.clockDomain) {
//        println(
//          f"${simTime()} time: the read request has zero DMA length, but dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen.toLong}%X"
//        )

        dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen.toLong shouldBe 0 withClue
          f"${simTime()} time: the read request has zero DMA length, but dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen.toLong}%X"

        val inputPsnStart = psnQueue.dequeue()
//        println(
//          f"${simTime()} time: output PSN io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt}%X not match input PSN io.readRstCacheData.psnStart=${inputPsnStart}%X"
//        )

        dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt shouldBe inputPsnStart withClue
          f"${simTime()} time: output PSN io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt}%X not match input PSN io.readRstCacheData.psnStart=${inputPsnStart}%X"

        matchQueue.enqueue(inputPsnStart)
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("RqReadDmaRespHandler non-zero DMA length read response case") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

//      dut.io.rxQCtrl.stateErrFlush #= false
      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      val inputReadReqQueue = mutable.Queue[(PsnStart, PktNum, PktLen)]()
      val inputDmaRespQueue =
        mutable.Queue[(PktFragData, PsnStart, PktLen, FragLast)]()

      val outputReadReqQueue = mutable.Queue[(PsnStart, PktNum, PktLen)]()
      val outputDmaRespQueue =
        mutable.Queue[(PktFragData, PsnStart, PktLen, FragLast)]()

      val readReqMetaDataQueue = mutable.Queue[(PktNum, PsnStart, PktLen)]()
      val readRespMetaDataQueue =
        mutable.Queue[(FragNum, PktNum, PsnStart, PktLen)]()

      // Input to DUT
      fork {
        val (
          totalFragNumItr,
          pktNumItr,
          psnStartItr,
          payloadLenItr
        ) =
          SendWriteReqReadRespInputGen.getItr(
            maxFragNum,
            pmtuLen,
            busWidth
          )

        while (true) {
          dut.clockDomain.waitSamplingWhere(readRespMetaDataQueue.isEmpty)

          for (_ <- 0 until MAX_PENDING_READ_ATOMIC_REQ_NUM) {
            val totalFragNum = totalFragNumItr.next()
            val pktNum = pktNumItr.next()
            val psnStart = psnStartItr.next()
            val payloadLenBytes = payloadLenItr.next()

            readReqMetaDataQueue.enqueue(
              (pktNum, psnStart, payloadLenBytes.toLong)
            )
            readRespMetaDataQueue.enqueue(
              (totalFragNum, pktNum, psnStart, payloadLenBytes.toLong)
            )
          }
        }
      }

      streamMasterPayloadFromQueueNoRandomDelay(
        dut.io.readRstCacheData,
        dut.clockDomain,
        readReqMetaDataQueue,
        payloadAssignFunc = (
            readReq: ReadAtomicRstCacheData,
            payloadData: (PktNum, PsnStart, PktLen)
        ) => {
          val (pktNum, psnStart, totalLenBytes) = payloadData
          readReq.dlen #= totalLenBytes
          readReq.psnStart #= psnStart
          readReq.pktNum #= pktNum

          val reqValid = true
          reqValid
        }
      )
      onStreamFire(dut.io.readRstCacheData, dut.clockDomain) {
//        println(
//          f"${simTime()} time: dut.io.readRstCacheData.psnStart=${dut.io.readRstCacheData.psnStart.toInt}%X, dut.io.readRstCacheData.pktNum=${dut.io.readRstCacheData.pktNum.toInt}, dut.io.readRstCacheData.dlen=${dut.io.readRstCacheData.dlen.toLong}%X"
//        )
        inputReadReqQueue.enqueue(
          (
            dut.io.readRstCacheData.psnStart.toInt,
            dut.io.readRstCacheData.pktNum.toInt,
            dut.io.readRstCacheData.dlen.toLong
          )
        )
      }

      DmaReadRespSim.pktFragStreamMasterDriverAlwaysValid(
        dut.io.dmaReadResp.resp,
        dut.clockDomain,
        getDmaReadRespPktDataFunc = (r: DmaReadResp) => r,
        segmentRespByPmtu = false
      ) {
        val (totalFragNum, pktNum, psnStart, payloadLenBytes) =
          MiscUtils.safeDeQueue(readRespMetaDataQueue, dut.clockDomain)

        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, payloadLenBytes)
      } {
        (
            _, // psn,
            _, // psnStart,
            _, // fragLast,
            _, // fragIdx,
            _, // totalFragNum,
            _, // pktIdx,
            _, // pktNum,
            _ // payloadLenBytes
        ) =>
      }
      onStreamFire(dut.io.dmaReadResp.resp, dut.clockDomain) {
//        println(
//          f"${simTime()} time: dut.io.dmaReadResp.resp.psnStart=${dut.io.dmaReadResp.resp.psnStart.toInt}, dut.io.dmaReadResp.resp.lenBytes=${dut.io.dmaReadResp.resp.lenBytes.toLong}, dut.io.dmaReadResp.resp.last=${dut.io.dmaReadResp.resp.last.toBoolean}"
//        )
        inputDmaRespQueue.enqueue(
          (
            dut.io.dmaReadResp.resp.data.toBigInt,
            dut.io.dmaReadResp.resp.psnStart.toInt,
            dut.io.dmaReadResp.resp.lenBytes.toLong,
            dut.io.dmaReadResp.resp.last.toBoolean
          )
        )
      }

      streamSlaveAlwaysReady(
        dut.io.readRstCacheDataAndDmaReadResp,
        dut.clockDomain
      )
      onStreamFire(dut.io.readRstCacheDataAndDmaReadResp, dut.clockDomain) {
        dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt shouldBe
          dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.psnStart.toInt withClue
          f"${simTime()} time:  dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt}%X should equal dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.psnStart.toInt}%X"

        val isLastFrag = dut.io.readRstCacheDataAndDmaReadResp.last.toBoolean
        if (isLastFrag) {
          outputReadReqQueue.enqueue(
            (
              dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt,
              dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.pktNum.toInt,
              dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen.toLong
            )
          )
        }
        outputDmaRespQueue.enqueue(
          (
            dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.data.toBigInt,
            dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.psnStart.toInt,
            dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.lenBytes.toLong,
            isLastFrag
          )
        )
      }

      MiscUtils.checkCondChangeOnceAndHoldAfterwards(
        dut.clockDomain,
        cond =
          dut.io.dmaReadResp.resp.valid.toBoolean && dut.io.dmaReadResp.resp.ready.toBoolean,
        clue =
          f"${simTime()} time: dut.io.dmaReadResp.resp.fire=${dut.io.dmaReadResp.resp.valid.toBoolean && dut.io.dmaReadResp.resp.ready.toBoolean} should be true always"
      )
      MiscUtils.checkCondChangeOnceAndHoldAfterwards(
        dut.clockDomain,
        cond = dut.io.readRstCacheDataAndDmaReadResp.valid.toBoolean,
        clue =
          f"${simTime()} time: dut.io.readRstCacheDataAndDmaReadResp.valid=${dut.io.readRstCacheDataAndDmaReadResp.valid.toBoolean} should be true always"
      )

      // Check DUT output
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputDmaRespQueue,
        outputDmaRespQueue,
        MATCH_CNT
      )
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputReadReqQueue,
        outputReadReqQueue,
        MATCH_CNT
      )
    }
  }
}

class RqOutTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile {
      val dut = new RqOut(busWidth)
      dut.psnOutRangeQueuePop.simPublic()
      dut
    }

  def insertToOutPsnRangeQueue(
      payloadFragNumItr: PayloadFragNumItr,
      pktNumItr: PktNumItr,
      psnStartItr: PsnStartItr,
      payloadLenItr: PayloadLenItr,
      inputSendWriteRespOrErrRespMetaDataQueue: mutable.Queue[
        (PSN, OpCode.Value)
      ],
      inputReadRespMetaDataQueue: mutable.Queue[(PSN, OpCode.Value, FragLast)],
      inputAtomicRespMetaDataQueue: mutable.Queue[(PSN, OpCode.Value)],
      outPsnRangeQueue: mutable.Queue[(OpCode.Value, PsnStart, PsnEnd)],
      readAtomicRstCachePopQueue: mutable.Queue[
        (OpCode.Value, PsnStart, PktNum)
      ],
      inputOutPsnQueue: mutable.Queue[PSN],
      readRespOnly: Boolean
  ): Unit =
    for (_ <- 0 until MAX_PENDING_REQ_NUM) {
      val _ = payloadFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()
      val workReqOpCode = if (readRespOnly) {
        WorkReqOpCode.RDMA_READ
      } else {
        WorkReqSim.randomSendWriteReadAtomicOpCode()
//        WorkReqOpCode.RDMA_WRITE_WITH_IMM
      }
      val psnEnd = psnStart +% (pktNum - 1)

      val isReadReq = workReqOpCode.isReadReq()
      val isAtomicReq = workReqOpCode.isAtomicReq()
      val isSendReq = workReqOpCode.isSendReq()
      val isWriteReq = workReqOpCode.isWriteReq()
//      println(
//        f"${simTime()} time: psnStart=${psnStart}%X, psnEnd=${psnEnd}%X, workReqOpCode: isSendReq=${isSendReq}, isWriteReq=${isWriteReq} isReadReq=${isReadReq}, isAtomicReq=${isAtomicReq}"
//      )

      if (isReadReq) {
        outPsnRangeQueue.enqueue(
          (OpCode.RDMA_READ_REQUEST, psnStart, psnEnd)
        )
        readAtomicRstCachePopQueue.enqueue(
          (OpCode.RDMA_READ_REQUEST, psnStart, pktNum)
        )
        inputOutPsnQueue.enqueue(psnEnd)
        for (pktIdx <- 0 until pktNum) {
          val psn = psnStart +% pktIdx
          val readRespOpCode =
            WorkReqSim.assignReadRespOpCode(pktIdx, pktNum)
          val pktFragNum = RdmaDataPktSim.computePktFragNum(
            pmtuLen,
            busWidth,
            readRespOpCode,
            payloadLenBytes.toLong,
            pktIdx,
            pktNum
          )

          for (fragIdx <- 0 until pktFragNum) {
            val isLastFrag = fragIdx == pktFragNum - 1
            inputReadRespMetaDataQueue.enqueue(
              (
                psn,
                readRespOpCode,
                isLastFrag
              )
            )
          }
        }
      } else if (isAtomicReq) {
        val singlePktNum = 1
        outPsnRangeQueue.enqueue((OpCode.COMPARE_SWAP, psnEnd, psnEnd))
        readAtomicRstCachePopQueue.enqueue(
          (OpCode.COMPARE_SWAP, psnStart, singlePktNum)
        )
        inputOutPsnQueue.enqueue(psnEnd)
        inputAtomicRespMetaDataQueue.enqueue(
          (
            psnEnd,
            OpCode.ATOMIC_ACKNOWLEDGE
          )
        )
      } else {
        if (isWriteReq) {
          outPsnRangeQueue.enqueue((OpCode.RDMA_WRITE_LAST, psnEnd, psnEnd))
        } else if (isSendReq) {
          outPsnRangeQueue.enqueue((OpCode.SEND_LAST, psnEnd, psnEnd))
        } else {
          isSendReq || isWriteReq || isReadReq || isAtomicReq shouldBe true withClue
            f"${simTime()} time: invalid WR opcode=${workReqOpCode}, must be send/write/read/atomic"
        }
        inputOutPsnQueue.enqueue(psnEnd)
        inputSendWriteRespOrErrRespMetaDataQueue.enqueue(
          (
            psnEnd,
            OpCode.ACKNOWLEDGE
          )
        )
//        println(f"${simTime()} time: input send/write psnEnd=${psnEnd}%X")
      }
    }

  // hasErrResp only works when normalOrDupResp is true,
  // Since if duplicate response has error, it will be ignored.
  def testFunc(
      normalOrDupResp: Boolean,
      readRespOnly: Boolean,
      hasErrResp: Boolean
  ): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.assignDefaultQpAttrAndFlush(
        dut.io.qpAttr,
        pmtuLen,
        dut.io.flush
      )

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputSendWriteRespOrErrRespMetaDataQueue =
        mutable.Queue[(PSN, OpCode.Value)]()
      val inputReadRespMetaDataQueue =
        mutable.Queue[(PSN, OpCode.Value, FragLast)]()
      val inputAtomicRespMetaDataQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outPsnRangeQueue = mutable.Queue[(OpCode.Value, PsnStart, PsnEnd)]()
      val readAtomicRstCachePopQueue =
        mutable.Queue[(OpCode.Value, PsnStart, PktNum)]()

      val inputOutPsnQueue = mutable.Queue[PSN]()
      val outputOutPsnQueue = mutable.Queue[PSN]()

      val inputSendWriteRespOrErrRespQueue = mutable
        .Queue[(PSN, OpCode.Value, AethRsvd, AethCode, AethValue, AethMsn)]()
      val inputReadRespQueue =
        mutable.Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val inputAtomicRespQueue = mutable.Queue[
        (
            PSN,
            OpCode.Value,
            AethRsvd,
            AethCode,
            AethValue,
            AethMsn,
            AtomicOrig
        )
      ]()

      val outputSendWriteRespOrErrRespQueue = mutable
        .Queue[(PSN, OpCode.Value, AethRsvd, AethCode, AethValue, AethMsn)]()
      val outputReadRespQueue =
        mutable.Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputAtomicRespQueue = mutable.Queue[
        (
            PSN,
            OpCode.Value,
            AethRsvd,
            AethCode,
            AethValue,
            AethMsn,
            AtomicOrig
        )
      ]()

      // Disable normal/error responses or duplicate responses
      if (normalOrDupResp) { // Normal responses
        dut.io.rxDupSendWriteResp.valid #= false
        dut.io.rxDupReadResp.pktFrag.valid #= false
        dut.io.rxDupAtomicResp.valid #= false

        // When hasErrResp, all send/write responses are NAK
        if (hasErrResp) {
          dut.io.rxSendWriteResp.valid #= false
        } else {
          dut.io.rxErrResp.valid #= false
        }
      } else { // Duplicate responses
        dut.io.readAtomicRstCachePop.valid #= false
        dut.io.outPsnRangeFifoPush.valid #= false

        dut.io.rxSendWriteResp.valid #= false
        dut.io.rxReadResp.pktFrag.valid #= false
        dut.io.rxAtomicResp.valid #= false
        dut.io.rxErrResp.valid #= false
      }

      if (normalOrDupResp) {
        // io.outPsnRangeFifoPush must always be valid when output normal responses
        streamMasterPayloadFromQueueNoRandomDelay(
          dut.io.outPsnRangeFifoPush,
          dut.clockDomain,
          outPsnRangeQueue,
          payloadAssignFunc = (
              pushReq: RespPsnRange,
              payloadData: (OpCode.Value, PsnStart, PsnEnd)
          ) => {
            val (opcode, psnStart, psnEnd) = payloadData
            pushReq.opcode #= opcode.id
            pushReq.start #= psnStart
            pushReq.end #= psnEnd
            dut.io.qpAttr.epsn #= psnEnd

            val respValid = true
            respValid
          }
        )

        // io.readAtomicRstCachePop must always be valid when output normal responses
        streamMasterPayloadFromQueueNoRandomDelay(
          dut.io.readAtomicRstCachePop,
          dut.clockDomain,
          readAtomicRstCachePopQueue,
          payloadAssignFunc = (
              rstCachePop: ReadAtomicRstCacheData,
              payloadData: (OpCode.Value, PsnStart, PktNum)
          ) => {
            val (opcode, psnStart, pktNum) = payloadData
            rstCachePop.psnStart #= psnStart
            rstCachePop.opcode #= opcode.id
            rstCachePop.pktNum #= pktNum

            val respValid = true
            respValid
          }
        )
      }

      fork {
        insertToOutPsnRangeQueue(
          payloadFragNumItr,
          pktNumItr,
          psnStartItr,
          payloadLenItr,
          inputSendWriteRespOrErrRespMetaDataQueue,
          inputReadRespMetaDataQueue,
          inputAtomicRespMetaDataQueue,
          outPsnRangeQueue,
          readAtomicRstCachePopQueue,
          inputOutPsnQueue,
          readRespOnly
        )

        while (true) {
          // Wait until no more responses to output
          dut.clockDomain.waitSamplingWhere(
            inputSendWriteRespOrErrRespMetaDataQueue.isEmpty && inputReadRespMetaDataQueue.isEmpty &&
              inputAtomicRespMetaDataQueue.isEmpty
          )

          insertToOutPsnRangeQueue(
            payloadFragNumItr,
            pktNumItr,
            psnStartItr,
            payloadLenItr,
            inputSendWriteRespOrErrRespMetaDataQueue,
            inputReadRespMetaDataQueue,
            inputAtomicRespMetaDataQueue,
            outPsnRangeQueue,
            readAtomicRstCachePopQueue,
            inputOutPsnQueue,
            readRespOnly
          )
        }
      }

      // Either send/write responses or error responses
      val sendWriteRespOrErrRespIn = if (normalOrDupResp) {
        if (hasErrResp) {
          dut.io.rxErrResp
        } else {
          dut.io.rxSendWriteResp
        }
      } else {
        dut.io.rxDupSendWriteResp
      }

      streamMasterPayloadFromQueueNoRandomDelay(
        sendWriteRespOrErrRespIn,
        dut.clockDomain,
        inputSendWriteRespOrErrRespMetaDataQueue,
        payloadAssignFunc =
          (respAck: Acknowledge, payloadData: (PSN, OpCode.Value)) => {
            val (psnEnd, opcode) = payloadData
            respAck.bth.psn #= psnEnd
            respAck.bth.opcodeFull #= opcode.id

            if (normalOrDupResp && hasErrResp) {
              respAck.aeth.setAs(AckTypeSim.randomRetryOrFatalNak())
            }
//          println(f"${simTime()} time: send/write response to psnEnd=${psnEnd}%X and opcode=${opcode}")

            val respValid = true
            respValid
          }
      )
      onStreamFire(sendWriteRespOrErrRespIn, dut.clockDomain) {
        val psn = sendWriteRespOrErrRespIn.bth.psn.toInt
        val opcode = OpCode(sendWriteRespOrErrRespIn.bth.opcodeFull.toInt)
        inputSendWriteRespOrErrRespQueue.enqueue(
          (
            psn,
            opcode,
            sendWriteRespOrErrRespIn.aeth.rsvd.toInt,
            sendWriteRespOrErrRespIn.aeth.code.toInt,
            sendWriteRespOrErrRespIn.aeth.value.toInt,
            sendWriteRespOrErrRespIn.aeth.msn.toInt
          )
        )
//        println(
//          f"${simTime()} time: sendWriteRespOrErrRespIn has opcode=${opcode}, PSN=${psn}, when normalOrDupResp=${normalOrDupResp} and hasErrResp=${hasErrResp}"
//        )
      }

      val readRespIn = if (normalOrDupResp) {
        dut.io.rxReadResp.pktFrag
      } else {
        dut.io.rxDupReadResp.pktFrag
      }
      streamMasterPayloadFromQueueNoRandomDelay(
        readRespIn,
        dut.clockDomain,
        inputReadRespMetaDataQueue,
        payloadAssignFunc = (
            readResp: Fragment[RdmaDataPkt],
            payloadData: (PSN, OpCode.Value, FragLast)
        ) => {
          val (psnEnd, opcode, isLastFrag) = payloadData
          readResp.bth.psn #= psnEnd
          readResp.bth.opcodeFull #= opcode.id
          readResp.last #= isLastFrag

          val respValid = true
          respValid
        }
      )
      onStreamFire(readRespIn, dut.clockDomain) {
        val psn = readRespIn.bth.psn.toInt
        val opcode = OpCode(readRespIn.bth.opcodeFull.toInt)
        inputReadRespQueue.enqueue(
          (
            psn,
            opcode,
            readRespIn.data.toBigInt,
            readRespIn.last.toBoolean
          )
        )
//        println(
//          f"${simTime()} time: readRespIn has opcode=${opcode}, PSN=${psn}, when normalOrDupResp=${normalOrDupResp}"
//        )
      }

      val atomicRespIn = if (normalOrDupResp) {
        dut.io.rxAtomicResp
      } else {
        dut.io.rxDupAtomicResp
      }
      streamMasterPayloadFromQueueNoRandomDelay(
        atomicRespIn,
        dut.clockDomain,
        inputAtomicRespMetaDataQueue,
        payloadAssignFunc =
          (atomicResp: AtomicResp, payloadData: (PSN, OpCode.Value)) => {
            val (psnEnd, opcode) = payloadData
            atomicResp.bth.psn #= psnEnd
            atomicResp.bth.opcodeFull #= opcode.id

            val respValid = true
            respValid
          }
      )
      onStreamFire(atomicRespIn, dut.clockDomain) {
        val psn = atomicRespIn.bth.psn.toInt
        val opcode = OpCode(atomicRespIn.bth.opcodeFull.toInt)
        inputAtomicRespQueue.enqueue(
          (
            psn,
            opcode,
            atomicRespIn.aeth.rsvd.toInt,
            atomicRespIn.aeth.code.toInt,
            atomicRespIn.aeth.value.toInt,
            atomicRespIn.aeth.msn.toInt,
            atomicRespIn.atomicAckEth.orig.toBigInt
          )
        )
//        println(
//          f"${simTime()} time: atomicRespIn has opcode=${opcode}, PSN=${psn}, rsvd=${atomicRespIn.aeth.rsvd.toInt}%X, code=${atomicRespIn.aeth.code.toInt}%X, value=${atomicRespIn.aeth.value.toInt}%X, msn=${atomicRespIn.aeth.msn.toInt}%X, orig=${atomicRespIn.atomicAckEth.orig.toBigInt}%X, when normalOrDupResp=${normalOrDupResp}"
//        )
      }

      var prePsn = -1
      streamSlaveAlwaysReady(dut.io.tx.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.tx.pktFrag, dut.clockDomain) {
        val psn = dut.io.tx.pktFrag.bth.psn.toInt
        val opcode = OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt)
        val pktFragData = dut.io.tx.pktFrag.data.toBigInt
        val isLastFrag = dut.io.tx.pktFrag.last.toBoolean

        if (normalOrDupResp && prePsn >= 0) { // Duplicate response might not in PSN order
          val curPsn = dut.io.qpAttr.epsn.toInt
          assert(
            PsnSim.psnCmp(prePsn, psn, curPsn) <= 0,
            f"${simTime()} time: prePsn=${prePsn} should < PSN=${psn} in PSN order, curPsn=dut.io.qpAttr.epsn=${curPsn}"
          )
        }
        prePsn = psn

        val (rsvd, code, value, msn) = AethSim.extract(pktFragData, busWidth)
        if (opcode.isReadRespPkt()) {
          outputReadRespQueue.enqueue((psn, opcode, pktFragData, isLastFrag))
        } else if (opcode.isAtomicRespPkt()) {
          val orig = AtomicAckEthSim.extract(pktFragData, busWidth)
          outputAtomicRespQueue.enqueue(
            (psn, opcode, rsvd, code, value, msn, orig)
          )
        } else {
          opcode shouldBe OpCode.ACKNOWLEDGE withClue
            f"${simTime()} time: opcode=${opcode} should be ACKNOWLEDGE"

          outputSendWriteRespOrErrRespQueue.enqueue(
            (psn, opcode, rsvd, code, value, msn)
          )
        }
//        println(
//          f"${simTime()} time: dut.io.tx has opcode=${opcode}, PSN=${psn}%X, isLastFrag=${isLastFrag}, rsvd=${rsvd}%X, code=${code}%X, value=${value}%X, msn=${msn}%X"
//        )
      }

      if (normalOrDupResp) {
        onStreamFire(dut.psnOutRangeQueuePop, dut.clockDomain) {
//            println(f"${simTime()} time: inputSendWriteRespOrErrRespQueue.size=${inputSendWriteRespOrErrRespQueue.size}")
//            println(f"${simTime()} time: inputAtomicRespQueue.size=${inputAtomicRespQueue.size}")
//            println(f"${simTime()} time: inputReadRespQueue.size=${inputReadRespQueue.size}")

          outputOutPsnQueue.enqueue(dut.io.opsnInc.psnVal.toInt)

//          println(f"${simTime()} time: dut.io.opsnInc.psnVal=${dut.io.opsnInc.psnVal.toInt}%X")
        }
      }

      MiscUtils.checkExpectedOutputMatchAlways(
        dut.clockDomain,
        inputOutPsnQueue,
        outputOutPsnQueue
      )
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputReadRespQueue,
        outputReadRespQueue,
        MATCH_CNT
      )
      if (readRespOnly) {
        MiscUtils.checkCondChangeOnceAndHoldAfterwards(
          dut.clockDomain,
          cond = dut.io.tx.pktFrag.valid.toBoolean,
          clue =
            f"${simTime()} time: dut.io.tx.pktFrag.valid=${dut.io.tx.pktFrag.valid.toBoolean} should be true always when readRespOnly=${readRespOnly}"
        )
      } else {
        MiscUtils.checkExpectedOutputMatch(
          dut.clockDomain,
          inputSendWriteRespOrErrRespQueue,
          outputSendWriteRespOrErrRespQueue,
          MATCH_CNT
        )
        MiscUtils.checkExpectedOutputMatch(
          dut.clockDomain,
          inputAtomicRespQueue,
          outputAtomicRespQueue,
          MATCH_CNT
        )
      }
    }

  test("RqOut read response only case") {
    testFunc(normalOrDupResp = true, readRespOnly = true, hasErrResp = false)
  }

  test("RqOut normal response only case") {
    testFunc(normalOrDupResp = true, readRespOnly = false, hasErrResp = false)
  }

  test("RqOut normal and error response case") {
    testFunc(normalOrDupResp = true, readRespOnly = false, hasErrResp = true)
  }

  test("RqOut duplicate response case") {
    testFunc(normalOrDupResp = false, readRespOnly = false, hasErrResp = false)
  }
}

class RecvQTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile {
      val dut = new RecvQ(busWidth)

      dut.readAtomicRstCache.io.full.simPublic()
      dut.readAtomicRstCache.io.occupancy.simPublic()

      dut.reqCommCheck.io.tx.checkRst.valid.simPublic()
      dut.reqCommCheck.io.tx.checkRst.hasNak.simPublic()
      dut.reqCommCheck.clearFlushStage.isReadAtomicRstCacheFull.simPublic()
      dut.reqCommCheck.io.txDupReq.pktFrag.valid.simPublic()
//      dut.reqCommCheck.checkStage.pendingReqNum.simPublic()

      dut.reqRnrCheck.io.tx.reqWithRxBuf.valid.simPublic()
      dut.reqRnrCheck.io.tx.reqWithRxBuf.hasNak.simPublic()

      dut.reqValidateLogic.reqAddrInfoExtractor.io.tx.reqWithRxBufAndVirtualAddrInfo.valid
        .simPublic()
      dut.reqValidateLogic.reqAddrInfoExtractor.io.tx.reqWithRxBufAndVirtualAddrInfo.hasNak
        .simPublic()

      dut.reqValidateLogic.reqAddrValidator.io.tx.reqWithRxBufAndDmaInfo.valid
        .simPublic()
      dut.reqValidateLogic.reqAddrValidator.io.tx.reqWithRxBufAndDmaInfo.hasNak
        .simPublic()

      dut.reqValidateLogic.reqPktLenCheck.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.valid
        .simPublic()
      dut.reqValidateLogic.reqPktLenCheck.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak
        .simPublic()

      dut.reqValidateLogic.reqSplitterAndNakGen.io.txErrResp.valid.simPublic()
      dut.reqValidateLogic.reqSplitterAndNakGen.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.valid
        .simPublic()
      dut.reqValidateLogic.reqSplitterAndNakGen.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.hasNak
        .simPublic()
      dut.reqValidateLogic.reqSplitterAndNakGen.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.valid
        .simPublic()
      dut.reqValidateLogic.reqSplitterAndNakGen.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.hasNak
        .simPublic()

      dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.valid
        .simPublic()
      dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last
        .simPublic()
      dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull
        .simPublic()
      dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenValid
        .simPublic()

      dut
    }

  test("RecvQ empty send/write/read request case") {
    // TODO: check RQ support empty requests
  }

  test("RecvQ fatal error case") {
    // TODO: check RQ output fatal error
  }

  test("RecvQ send/write only normal case") {
    normalTestFunc(allKindReq = false, sendWriteOrReadAtomic = true)
  }

  test("RecvQ read/atomic only normal case") {
    normalTestFunc(allKindReq = false, sendWriteOrReadAtomic = false)
  }

  test("RecvQ normal case") {
    normalTestFunc(allKindReq = true, sendWriteOrReadAtomic = false)
  }

  def normalTestFunc(
      allKindReq: Boolean,
      sendWriteOrReadAtomic: Boolean
  ): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    QpCtrlSim.connectRecvQ(
      dut.clockDomain,
      pmtuLen,
      dut.io.psnInc,
      dut.io.notifier,
      dut.io.qpAttr,
      dut.io.rxQCtrl
    )

    val recvWorkReqIdQueue = mutable.Queue[WorkReqId]()
    val expectedWorkCompQueue =
      mutable.Queue[(WorkReqId, SpinalEnumElement[WorkCompStatus.type])]()
    val outputWorkCompQueue =
      mutable.Queue[(WorkReqId, SpinalEnumElement[WorkCompStatus.type])]()

    val expectedReadAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
    val outputReadAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()

    val expectedAckQueue =
      mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
    val outputAckQueue =
      mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()

    // Input to DUT
    val _ = RqDmaBusSim.reqStreamAlwaysFireAndRespSuccess(
      dut.io.dma,
      dut.clockDomain,
      busWidth
    )
    // addrCacheRespQueue
    val _ = AddrCacheSim.reqStreamAlwaysFireAndRespSuccess(
      dut.io.addrCacheRead,
      dut.clockDomain
    )

    // Check no any error
    fork {
      while (true) {
        dut.clockDomain.waitSampling()

        if (dut.reqCommCheck.io.tx.checkRst.valid.toBoolean) {
          dut.reqCommCheck.io.tx.checkRst.hasNak.toBoolean shouldBe false withClue
            f"${simTime()} time: dut.reqCommCheck.io.tx.checkRst.hasNak=${dut.reqCommCheck.io.tx.checkRst.hasNak.toBoolean} should be false, when dut.reqCommCheck.io.tx.checkRst.valid=${dut.reqCommCheck.io.tx.checkRst.valid.toBoolean}"
        }

        dut.reqCommCheck.io.txDupReq.pktFrag.valid.toBoolean shouldBe false withClue
          f"${simTime()} time: dut.reqCommCheck.io.txDupReq.pktFrag.valid=${dut.reqCommCheck.io.txDupReq.pktFrag.valid.toBoolean} should be false"
        if (dut.reqRnrCheck.io.tx.reqWithRxBuf.valid.toBoolean) {
          dut.reqRnrCheck.io.tx.reqWithRxBuf.hasNak.toBoolean shouldBe false withClue
            f"${simTime()} time: dut.reqRnrCheck.io.tx.reqWithRxBuf.hasNak=${dut.reqRnrCheck.io.tx.reqWithRxBuf.hasNak.toBoolean} should be false, when dut.reqRnrCheck.io.tx.reqWithRxBuf.valid=${dut.reqRnrCheck.io.tx.reqWithRxBuf.valid.toBoolean}"
        }

        if (
          dut.reqValidateLogic.reqAddrInfoExtractor.io.tx.reqWithRxBufAndVirtualAddrInfo.valid.toBoolean
        ) {
          dut.reqValidateLogic.reqAddrInfoExtractor.io.tx.reqWithRxBufAndVirtualAddrInfo.hasNak.toBoolean shouldBe false withClue
            f"${simTime()} time: dut.reqValidateLogic.reqAddrInfoExtractor.io.tx.reqWithRxBufAndVirtualAddrInfo.hasNak=${dut.reqValidateLogic.reqAddrInfoExtractor.io.tx.reqWithRxBufAndVirtualAddrInfo.hasNak.toBoolean} should be false, when dut.reqValidateLogic.reqAddrInfoExtractor.io.tx.reqWithRxBufAndVirtualAddrInfo.valid=${dut.reqValidateLogic.reqAddrInfoExtractor.io.tx.reqWithRxBufAndVirtualAddrInfo.valid.toBoolean}"
        }

        if (
          dut.reqValidateLogic.reqAddrValidator.io.tx.reqWithRxBufAndDmaInfo.valid.toBoolean
        ) {
          dut.reqValidateLogic.reqAddrValidator.io.tx.reqWithRxBufAndDmaInfo.hasNak.toBoolean shouldBe false withClue
            f"${simTime()} time: dut.reqValidateLogic.reqAddrValidator.io.tx.reqWithRxBufAndDmaInfo.hasNak=${dut.reqValidateLogic.reqAddrValidator.io.tx.reqWithRxBufAndDmaInfo.hasNak.toBoolean} should be false when dut.reqValidateLogic.reqAddrValidator.io.tx.reqWithRxBufAndDmaInfo.valid=${dut.reqValidateLogic.reqAddrValidator.io.tx.reqWithRxBufAndDmaInfo.valid.toBoolean}"
        }

        if (
          dut.reqValidateLogic.reqPktLenCheck.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean
        ) {
          dut.reqValidateLogic.reqPktLenCheck.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean shouldBe false withClue
            f"${simTime()} time: dut.reqPktLenCheck.reqAddrValidator.io.reqWithRxBufAndDmaInfoWithLenCheck.hasNak=${dut.reqValidateLogic.reqPktLenCheck.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean} should be false when dut.reqValidateLogic.reqPktLenCheck.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.valid=${dut.reqValidateLogic.reqPktLenCheck.io.tx.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean}"
        }

        dut.reqValidateLogic.reqSplitterAndNakGen.io.txErrResp.valid.toBoolean shouldBe false withClue
          f"${simTime()} time dut.reqValidateLogic.reqSplitterAndNakGen.io.txErrResp.valid=${dut.reqValidateLogic.reqSplitterAndNakGen.io.txErrResp.valid.toBoolean} should be false"
        if (
          (allKindReq || sendWriteOrReadAtomic) && dut.reqValidateLogic.reqSplitterAndNakGen.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean
        ) {
          dut.reqValidateLogic.reqSplitterAndNakGen.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean shouldBe false withClue
            f"${simTime()} time: dut.reqPktLenCheck.reqSplitterAndNakGen.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.hasNak=${dut.reqValidateLogic.reqSplitterAndNakGen.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean} should be false when dut.reqValidateLogic.reqSplitterAndNakGen.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.valid=${dut.reqValidateLogic.reqSplitterAndNakGen.io.txSendWrite.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean}"
        }
        if (
          (allKindReq || !sendWriteOrReadAtomic) && dut.reqValidateLogic.reqSplitterAndNakGen.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean
        ) {
          dut.reqValidateLogic.reqSplitterAndNakGen.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean shouldBe false withClue
            f"${simTime()} time: dut.reqPktLenCheck.reqSplitterAndNakGen.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.hasNak=${dut.reqValidateLogic.reqSplitterAndNakGen.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.hasNak.toBoolean} should be false when dut.reqValidateLogic.reqSplitterAndNakGen.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.valid=${dut.reqValidateLogic.reqSplitterAndNakGen.io.txReadAtomic.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean}"
        }

        if (
          dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.valid.toBoolean
        ) {
          val opcode = OpCode(
            dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.pktFrag.bth.opcodeFull.toInt
          )
          val isLastFrag =
            dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last.toBoolean
          val reqTotalLenValid =
            dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenValid.toBoolean
          val lastOrOnlyReqPkt = opcode.isLastOrOnlyReqPkt()
          reqTotalLenValid shouldBe (lastOrOnlyReqPkt && isLastFrag) withClue
            f"${simTime()} time dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.reqTotalLenValid=${reqTotalLenValid} should be true when dut.rqSendWriteWorkCompGenerator.io.rx.reqWithRxBufAndDmaInfoWithLenCheck.last=${isLastFrag} and lastOrOnlyReqPkt=${lastOrOnlyReqPkt}"
        }
      }
    }

    if (allKindReq) {
      RdmaDataPktSim.rdmaReqPktFragStreamMasterDriverAlwaysValid(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        getRdmaPktDataFunc = (r: RdmaDataPkt) => r,
        pendingReqNumExceed = {
          // TODO: refactor pendingReqNumExceed logic
//            val isExceeded = dut.reqCommCheck.checkStage.pendingReqNum.toInt + 1 >=
//              QpCtrlSim.getMaxPendingReadAtomicWorkReqNum(dut.io.qpAttr)
//            isExceeded
          false
        },
        readAtomicRstCacheFull = {
          // TODO: refactor readAtomicRstCacheFull logic
          // dut.io.rx.pktFrag.valid must be false when readAtomicRstCacheFull is true
//          dut.reqCommCheck.checkStage.isReadAtomicRstCacheFull.toBoolean
          val isFull =
            dut.readAtomicRstCache.io.occupancy.toInt + 1 >=
              QpCtrlSim.getMaxPendingReadAtomicWorkReqNum(dut.io.qpAttr)
//          println(
//            f"${simTime()} time: dut.readAtomicRstCache.io.occupancy=${dut.readAtomicRstCache.io.occupancy.toInt} is full"
//          )
          isFull
        },
        pmtuLen,
        busWidth,
        maxFragNum
      ) {
        (
            psn,
            psnStart,
            _, // fragLast,
            _, // fragIdx,
            _, // pktFragNum,
            _, // pktIdx,
            _, // reqPktNum,
            respPktNum,
            _, // payloadLenBytes,
            _, // headerLenBytes,
            opcode
        ) =>
//          println(
//            f"${simTime()} time: PSN=${readAtomicReqPsn}%X, opcode=${opcode}, pktNum=${pktNum}%X, payloadLenBytes=${payloadLenBytes}%X"
//          )
          if (opcode.isReadReqPkt()) {
            // TODO: check read response data
            for (pktIdx <- 0 until respPktNum) {
              val readRespOpCode =
                WorkReqSim.assignReadRespOpCode(pktIdx, respPktNum)
              val readRespPsn = psnStart +% pktIdx
              expectedReadAtomicRespQueue.enqueue(
                (readRespPsn, readRespOpCode)
              )
            }
          } else if (opcode.isAtomicReqPkt()) {
            expectedReadAtomicRespQueue.enqueue(
              (psn, OpCode.ATOMIC_ACKNOWLEDGE)
            )
          }
      }
    } else if (sendWriteOrReadAtomic) {
      RdmaDataPktSim.sendWriteReqPktFragStreamMasterDriverAlwaysValid(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        getRdmaPktDataFunc = (r: RdmaDataPkt) => r,
        pendingReqNumExceed = false,
        pmtuLen,
        busWidth,
        maxFragNum
      ) {
        (
            _, // psn,
            _, // psnStart
            _, // fragLast,
            _, // fragIdx,
            _, // pktFragNum,
            _, // pktIdx,
            _, // reqPktNum,
            _, // respPktNum,
            _, // payloadLenBytes,
            _, // headerLenBytes,
            _ // opcode
        ) =>
      }
    } else { // For read/atomic requests
      RdmaDataPktSim.readAtomicReqStreamMasterDriverAlwaysValid(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        getRdmaPktDataFunc = (r: RdmaDataPkt) => r,
        readAtomicRstCacheFull = {
          // TODO: refactor readAtomicRstCacheFull logic
          // dut.io.rx.pktFrag.valid must be false when readAtomicRstCacheFull is true
//          dut.reqCommCheck.checkStage.isReadAtomicRstCacheFull.toBoolean
          val isFull =
            dut.readAtomicRstCache.io.occupancy.toInt + 1 >= QpCtrlSim
              .getMaxPendingReadAtomicWorkReqNum(dut.io.qpAttr)
//            val isFull = dut.readAtomicRstCache.io.full.toBoolean
//          println(
//            f"${simTime()} time: dut.readAtomicRstCache.io.occupancy=${dut.readAtomicRstCache.io.occupancy.toInt} is full"
//          )
          isFull
        },
        pmtuLen,
        busWidth,
        maxFragNum
      ) {
        (
            psn,
            _, // psnStart
            _, // fragLast,
            _, // fragIdx,
            _, // pktFragNum,
            _, // pktIdx,
            _, // reqPktNum,
            respPktNum,
            _, // payloadLenBytes,
            _, // headerLenBytes,
            opcode
        ) =>
//          println(
//            f"${simTime()} time: PSN=${readAtomicReqPsn}%X, opcode=${opcode}, pktNum=${pktNum}%X, payloadLenBytes=${payloadLenBytes}%X"
//          )
          if (opcode.isReadReqPkt()) {
            // TODO: check read response data
            for (pktIdx <- 0 until respPktNum) {
              val readRespOpCode =
                WorkReqSim.assignReadRespOpCode(pktIdx, respPktNum)
              val readRespPsn = psn +% pktIdx
              expectedReadAtomicRespQueue.enqueue(
                (readRespPsn, readRespOpCode)
              )
            }
          } else if (opcode.isAtomicReqPkt()) {
            expectedReadAtomicRespQueue.enqueue(
              (psn, OpCode.ATOMIC_ACKNOWLEDGE)
            )
          }
      }
    }
    onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
      val opcode = OpCode(dut.io.rx.pktFrag.bth.opcodeFull.toInt)
      val ackReq = dut.io.rx.pktFrag.bth.ackreq.toBoolean
      val isLast = dut.io.rx.pktFrag.last.toBoolean
//      println(
//        f"${simTime()} time: input opcode=${dut.io.rx.pktFrag.bth.opcodeFull.toInt}%X, PSN=${dut.io.rx.pktFrag.bth.psn.toInt}%X, ePSN=${dut.io.qpAttr.epsn.toInt}%X"
//      )
      if (
        (opcode.isSendReqPkt() || opcode.isWriteReqPkt()) && ackReq && isLast
      ) {
        expectedAckQueue.enqueue(
          (
            dut.io.rx.pktFrag.bth.psn.toInt,
            AckType.NORMAL
          )
        )
      }
    }

    streamMasterDriverAlwaysValid(dut.io.rxWorkReq, dut.clockDomain) {
      // Just random generate RR
    }
    onStreamFire(dut.io.rxWorkReq, dut.clockDomain) {
      recvWorkReqIdQueue.enqueue(dut.io.rxWorkReq.id.toBigInt)
      expectedWorkCompQueue.enqueue(
        (dut.io.rxWorkReq.id.toBigInt, WorkCompStatus.SUCCESS)
      )
    }

    streamSlaveAlwaysReady(dut.io.tx.pktFrag, dut.clockDomain)
    onStreamFire(dut.io.tx.pktFrag, dut.clockDomain) {
      val opcode = OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt)
      val psn = dut.io.tx.pktFrag.bth.psn.toInt
      val fragLast = dut.io.tx.pktFrag.last.toBoolean
      if (opcode.isAckRespPkt()) {
        val (_, aethCode, aethValue, _) =
          AethSim.extract(dut.io.tx.pktFrag.data.toBigInt, busWidth)
        val ackType = AckTypeSim.decodeFromCodeAndValue(aethCode, aethValue)
        outputAckQueue.enqueue((psn, ackType))

        fragLast shouldBe true withClue
          f"${simTime()} time: dut.io.tx.pktFrag.last=${fragLast} should be true when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
      } else if (opcode.isReadRespPkt()) {
        if (fragLast) {
          outputReadAtomicRespQueue.enqueue((psn, opcode))
        }
      } else if (opcode.isAtomicRespPkt()) {
        outputReadAtomicRespQueue.enqueue((psn, opcode))
      } else {
        SpinalExit(f"${simTime()} time: invalid opcode=${opcode}, PSN=${psn}")
      }
    }

    streamSlaveAlwaysReady(dut.io.sendWriteWorkComp, dut.clockDomain)
    onStreamFire(dut.io.sendWriteWorkComp, dut.clockDomain) {
      List(
        WorkCompOpCode.RECV,
        WorkCompOpCode.RECV_RDMA_WITH_IMM
      ) should contain(dut.io.sendWriteWorkComp.opcode.toEnum) withClue
        f"${simTime()} time: dut.io.sendWriteWorkComp.opcode=${dut.io.sendWriteWorkComp.opcode.toEnum} should be RECV or RECV_RDMA_WITH_IMM"
      outputWorkCompQueue.enqueue(
        (
          dut.io.sendWriteWorkComp.id.toBigInt,
          dut.io.sendWriteWorkComp.status.toEnum
        )
      )
    }

    MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
      cond =
        !(dut.io.dma.atomic.wr.req.valid.toBoolean || dut.io.dma.atomic.rd.req.valid.toBoolean),
      clue =
        f"${simTime()} time: dut.io.dma.atomic.wr.req.valid or dut.io.dma.atomic.rd.req.valid=${dut.io.dma.atomic.wr.req.valid.toBoolean || dut.io.dma.atomic.rd.req.valid.toBoolean} should be false"
    )
    if (allKindReq) {
//        MiscUtils.checkCondChangeOnceAndHoldAfterwards(
//          dut.clockDomain,
//          cond =
//            dut.io.tx.pktFrag.valid.toBoolean && dut.io.tx.pktFrag.ready.toBoolean,
//          clue =
//            f"${simTime()} time: dut.io.tx.pktFrag.fire=${dut.io.tx.pktFrag.valid.toBoolean && dut.io.tx.pktFrag.ready.toBoolean} should be true always when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
//        )
    } else {
      if (sendWriteOrReadAtomic) {
        MiscUtils.checkCondChangeOnceAndHoldAfterwards(
          dut.clockDomain,
          cond =
            dut.io.rx.pktFrag.valid.toBoolean && dut.io.rx.pktFrag.ready.toBoolean,
          clue =
            f"${simTime()} time: dut.io.rx.pktFrag.fire=${dut.io.rx.pktFrag.valid.toBoolean && dut.io.rx.pktFrag.ready.toBoolean} should be true always when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
        )
      } else { // For read/atomic requests
        MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
          cond = !dut.io.sendWriteWorkComp.valid.toBoolean,
          clue =
            f"${simTime()} time: dut.io.sendWriteWorkComp.valid=${dut.io.sendWriteWorkComp.valid.toBoolean} should be false when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
        )
//          MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
//            cond = outputWorkCompQueue.isEmpty,
//            clue =
//              f"${simTime()} time: outputWorkCompQueue.isEmpty=${outputWorkCompQueue.isEmpty} should be true when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
//          )
      }
    }

    if (allKindReq || !sendWriteOrReadAtomic) {
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        expectedReadAtomicRespQueue,
        outputReadAtomicRespQueue,
        MATCH_CNT
      )
    }
    if (allKindReq || sendWriteOrReadAtomic) {
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        expectedWorkCompQueue,
        outputWorkCompQueue,
        MATCH_CNT
      )
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        expectedAckQueue,
        outputAckQueue,
        MATCH_CNT
      )
    }
  }

  test("RecvQ duplicate send/write request case") {
    dupTestFunc(sendWriteOrReadAtomic = true)
  }

  test("RecvQ duplicate read/atomic request case") {
    dupTestFunc(sendWriteOrReadAtomic = false)
  }

  def dupTestFunc(sendWriteOrReadAtomic: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.connectRecvQ(
        dut.clockDomain,
        pmtuLen,
        dut.io.psnInc,
        dut.io.notifier,
        dut.io.qpAttr,
        dut.io.rxQCtrl
      )

      val normalReqQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()
      val bothNormalAndRetryReqQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()

      val normalReadRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val expectedReadRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outputReadRespQueue = mutable.Queue[(PSN, OpCode.Value)]()

      val normalAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val expectedAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outputAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()

      val normalAckQueue =
        mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
      val expectedAckQueue =
        mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
      val outputAckQueue =
        mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()

      // Input to DUT
      val _ = RqDmaBusSim.reqStreamAlwaysFireAndRespSuccess(
        dut.io.dma,
        dut.clockDomain,
        busWidth
      )
      // addrCacheRespQueue
      val _ = AddrCacheSim.reqStreamAlwaysFireAndRespSuccess(
        dut.io.addrCacheRead,
        dut.clockDomain
      )

      fork {
        val pendingReqLimit = if (sendWriteOrReadAtomic) {
          QpCtrlSim.getMaxPendingWorkReqNum(dut.io.qpAttr)
        } else {
          QpCtrlSim.getMaxPendingReadAtomicWorkReqNum(dut.io.qpAttr)
        }

        // Retry control loop
        while (true) {
          val expectedPsn = dut.io.qpAttr.epsn.toInt
          if (sendWriteOrReadAtomic) {
            // Generate send/write requests
            RdmaDataPktSim.sendWriteReqPktFragStreamMasterGen(
              dut.io.rx.pktFrag,
              expectedPsn,
              pendingReqLimit,
              pmtuLen,
              busWidth,
              maxFragNum
            ) {
              (
                  psn,
                  _, // psnStart
                  fragLast,
                  _, // fragIdx,
                  _, // pktFragNum,
                  _, // pktIdx,
                  _, // reqPktNum,
                  _, // respPktNum,
                  _, // payloadLenBytes,
                  _, // headerLenBytes,
                  opcode
              ) =>
//                  println(
//                    f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, fragLast=${fragLast}"
//                  )
                normalReqQueue.enqueue(
                  (
                    psn,
                    opcode,
                    dut.io.rx.pktFrag.bth.padCnt.toInt,
                    dut.io.rx.pktFrag.bth.ackreq.toBoolean,
                    fragLast,
                    dut.io.rx.pktFrag.data.toBigInt,
                    dut.io.rx.pktFrag.mty.toBigInt
                  )
                )
                if (opcode.isLastOrOnlyReqPkt() && fragLast) {
                  normalAckQueue.enqueue((psn, AckType.NORMAL))
                }
            }
          } else { // Generate read/atomic requests
            RdmaDataPktSim.readAtomicReqStreamMasterGen(
              dut.io.rx.pktFrag,
              expectedPsn,
              pendingReqLimit,
              pmtuLen,
              busWidth,
              maxFragNum
            ) {
              (
                  psn,
                  _, // psnStart
                  fragLast,
                  _, // fragIdx,
                  _, // pktFragNum,
                  _, // pktIdx,
                  _, // reqPktNum,
                  respPktNum,
                  _, // payloadLenBytes,
                  _, // headerLenBytes,
                  opcode
              ) =>
//          println(
//            f"${simTime()} time: PSN=${readAtomicReqPsn}%X, opcode=${opcode}, pktNum=${pktNum}%X, payloadLenBytes=${payloadLenBytes}%X"
//          )
                normalReqQueue.enqueue(
                  (
                    psn,
                    opcode,
                    dut.io.rx.pktFrag.bth.padCnt.toInt,
                    dut.io.rx.pktFrag.bth.ackreq.toBoolean,
                    fragLast,
                    dut.io.rx.pktFrag.data.toBigInt,
                    dut.io.rx.pktFrag.mty.toBigInt
                  )
                )

                if (opcode.isReadReqPkt()) {
                  // TODO: check read response data
                  for (pktIdx <- 0 until respPktNum) {
                    val readRespOpCode =
                      WorkReqSim.assignReadRespOpCode(pktIdx, respPktNum)
                    val readRespPsn = psn +% pktIdx
                    normalReadRespQueue.enqueue(
                      (readRespPsn, readRespOpCode)
                    )
                  }
                } else if (opcode.isAtomicReqPkt()) {
                  normalAtomicRespQueue.enqueue(
                    (psn, OpCode.ATOMIC_ACKNOWLEDGE)
                  )
                }
            }
          }

          // Append normal requests from reqPktFrag4RetryQueue to bothNormalAndRetryReqQueue
          bothNormalAndRetryReqQueue.appendAll(normalReqQueue)
          if (sendWriteOrReadAtomic) {
            // Append normal send/write responses
            expectedAckQueue.appendAll(normalAckQueue)

            dut.clockDomain.waitSamplingWhere(expectedAckQueue.isEmpty)
          } else {
            // Append normal read responses
            expectedReadRespQueue.appendAll(normalReadRespQueue)
            // Append normal atomic responses
            expectedAtomicRespQueue.appendAll(normalAtomicRespQueue)

            dut.clockDomain.waitSamplingWhere {
//              println(f"${simTime()} time: expectedReadRespQueue.size=${expectedReadRespQueue.size}, expectedAtomicRespQueue.size=${expectedAtomicRespQueue.size}")
              expectedReadRespQueue.isEmpty && expectedAtomicRespQueue.isEmpty
            }
          }
//          println(
//            f"${simTime()} time: normal responses done, dut.io.qpAttr.epsn=${dut.io.qpAttr.epsn.toInt}%X, dut.io.qpAttr.rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt}%X, start retry"
//          )

          val ePsn = dut.io.qpAttr.epsn.toInt
          val rqOutPsn = dut.io.qpAttr.rqOutPsn.toInt
          // Wait until all normal requests processed
          // Append duplicated requests from reqPktFrag4RetryQueue to bothNormalAndRetryReqQueue
          bothNormalAndRetryReqQueue.appendAll(normalReqQueue)

          if (sendWriteOrReadAtomic) {
            val latestReqPsn = normalAckQueue.last._1
            latestReqPsn shouldBe dut.io.qpAttr.rqOutPsn.toInt withClue
              f"${simTime()} time: latestReqPsn=${latestReqPsn}%X should == dut.io.qpAttr.rqOutPsn.toInt=${dut.io.qpAttr.rqOutPsn.toInt}%X, when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
            // Append duplicated send/write responses
            expectedAckQueue.appendAll(
              normalAckQueue.map(psnAckTypePair =>
                (latestReqPsn, psnAckTypePair._2)
              )
            )

            dut.clockDomain.waitSamplingWhere(expectedAckQueue.isEmpty)
          } else {
            // Append duplicated read responses
            expectedReadRespQueue.appendAll(normalReadRespQueue)
            // Append duplicated atomic responses
            expectedAtomicRespQueue.appendAll(normalAtomicRespQueue)

            dut.clockDomain.waitSamplingWhere {
//              println(f"${simTime()} time: expectedReadRespQueue.size=${expectedReadRespQueue.size}, expectedAtomicRespQueue.size=${expectedAtomicRespQueue.size}")
              expectedReadRespQueue.isEmpty && expectedAtomicRespQueue.isEmpty
            }
          }
          dut.io.qpAttr.epsn.toInt shouldBe ePsn withClue
            f"${simTime()} time: after retry, ePSN=${dut.io.qpAttr.epsn.toInt} should remain @ ${ePsn}"
          dut.io.qpAttr.rqOutPsn.toInt shouldBe rqOutPsn withClue
            f"${simTime()} time: after retry, rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt} should remain @ ${rqOutPsn}"

//          println(
//            f"${simTime()} time: retry responses done, dut.io.qpAttr.epsn=${dut.io.qpAttr.epsn.toInt}%X, dut.io.qpAttr.rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt}%X, finish retry"
//          )
          normalReqQueue.clear()
          normalAckQueue.clear()
          normalReadRespQueue.clear()
          normalAtomicRespQueue.clear()
        }
      }

      streamMasterPayloadFromQueueNoRandomDelay(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        bothNormalAndRetryReqQueue,
        payloadAssignFunc = (
            pktFrag: Fragment[RdmaDataPkt],
            payloadData: (
                PSN,
                OpCode.Value,
                PadCnt,
                AckReq,
                FragLast,
                PktFragData,
                MTY
            )
        ) => {
          val (psn, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
            payloadData
          pktFrag.bth.psn #= psn
          pktFrag.bth.opcodeFull #= opcode.id
          pktFrag.bth.padCnt #= padCnt
          pktFrag.bth.ackreq #= ackreq
          pktFrag.data #= pktFragData
          pktFrag.mty #= mty
          pktFrag.last #= fragLast

//          if (opcode.isReadReqPkt()) {
//            val (virtualAddr, rKey, pktLen) =
//              RethSim.extract(pktFragData, busWidth)
//            println(
//              f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, virtualAddr=${virtualAddr}%X, rKey=${rKey}%X, pktLen=${pktLen}%X"
//            )
//          }

          val reqValid = true
          reqValid
        }
      )

      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
//        val opcode = OpCode(dut.io.rx.pktFrag.bth.opcodeFull.toInt)
//        val ackreq = dut.io.rx.pktFrag.bth.ackreq.toBoolean
//        val fragLast = dut.io.rx.pktFrag.last.toBoolean
//        println(
//          f"${simTime()} time: input opcode=${opcode}, PSN=${dut.io.rx.pktFrag.bth.psn.toInt}%X, ePSN=${dut.io.qpAttr.epsn.toInt}%X, fragLast=${fragLast}, ackreq=${ackreq}"
//        )
      }

      streamMasterDriverAlwaysValid(dut.io.rxWorkReq, dut.clockDomain) {
        // Just random generate RR
      }
      streamSlaveAlwaysReady(dut.io.sendWriteWorkComp, dut.clockDomain)

      streamSlaveAlwaysReady(dut.io.tx.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.tx.pktFrag, dut.clockDomain) {
        val opcode = OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt)
        val psn = dut.io.tx.pktFrag.bth.psn.toInt
        val fragLast = dut.io.tx.pktFrag.last.toBoolean

        if (opcode.isAckRespPkt()) {
          val (_, aethCode, aethValue, _) =
            AethSim.extract(dut.io.tx.pktFrag.data.toBigInt, busWidth)
          val ackType = AckTypeSim.decodeFromCodeAndValue(aethCode, aethValue)
          outputAckQueue.enqueue((psn, ackType))

          fragLast shouldBe true withClue
            f"${simTime()} time: dut.io.tx.pktFrag.last=${fragLast} should be true when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
        } else if (opcode.isReadRespPkt()) {
          if (fragLast) {
            outputReadRespQueue.enqueue((psn, opcode))
          }
        } else if (opcode.isAtomicRespPkt()) {
          outputAtomicRespQueue.enqueue((psn, opcode))

          fragLast shouldBe true withClue
            f"${simTime()} time: dut.io.tx.pktFrag.last=${fragLast} should be true when opcode=${opcode} and sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
        } else {
          SpinalExit(f"${simTime()} time: invalid opcode=${opcode}, PSN=${psn}")
        }

//        println(
//          f"${simTime()} time: response: opcode=${opcode}, PSN=${psn}"
//        )
      }

      // TODO: support atomic requests
      MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
        cond =
          !(dut.io.dma.atomic.wr.req.valid.toBoolean || dut.io.dma.atomic.rd.req.valid.toBoolean),
        clue =
          f"${simTime()} time: dut.io.dma.atomic.wr.req.valid or dut.io.dma.atomic.rd.req.valid=${dut.io.dma.atomic.wr.req.valid.toBoolean || dut.io.dma.atomic.rd.req.valid.toBoolean} should be false"
      )

      if (sendWriteOrReadAtomic) {
//          MiscUtils.checkCondChangeOnceAndHoldAfterwards(
//            dut.clockDomain,
//            cond =
//              dut.io.rx.pktFrag.valid.toBoolean && dut.io.rx.pktFrag.ready.toBoolean,
//            clue =
//              f"${simTime()} time: dut.io.rx.pktFrag.fire=${dut.io.rx.pktFrag.valid.toBoolean && dut.io.rx.pktFrag.ready.toBoolean} should be true always when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
//          )
      } else { // For read/atomic requests
        MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
          cond = !dut.io.sendWriteWorkComp.valid.toBoolean,
          clue =
            f"${simTime()} time: dut.io.sendWriteWorkComp.valid=${dut.io.sendWriteWorkComp.valid.toBoolean} should be false when sendWriteOrReadAtomic=${sendWriteOrReadAtomic}"
        )
      }

//      waitUntil(req4RetryQueue.size > MATCH_CNT)

      if (sendWriteOrReadAtomic) {
        MiscUtils.checkExpectedOutputMatch(
          dut.clockDomain,
          expectedAckQueue,
          outputAckQueue,
          MATCH_CNT
        )
      } else {
        MiscUtils.checkExpectedOutputMatchAlways(
          dut.clockDomain,
          expectedReadRespQueue,
          outputReadRespQueue
        )
        MiscUtils.checkExpectedOutputMatch(
          dut.clockDomain,
          expectedAtomicRespQueue,
          outputAtomicRespQueue,
          MATCH_CNT
        )
      }
    }

  test(
    "RecvQ duplicate read partial retry and wrong atomic retry request case"
  ) {
    partialReadRetryAndWrongAtomicRetryTestFunc()
  }

  def partialReadRetryAndWrongAtomicRetryTestFunc(): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.connectRecvQ(
        dut.clockDomain,
        pmtuLen,
        dut.io.psnInc,
        dut.io.notifier,
        dut.io.qpAttr,
        dut.io.rxQCtrl
      )

      val normalReqQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()
      val partialRetryReadAndMaybeWrongRetryAtomicReqQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()
      val bothNormalAndRetryReqQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()

      val normalReadRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val partialRetryReadRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val expectedReadRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outputReadRespQueue = mutable.Queue[(PSN, OpCode.Value)]()

      val normalAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val correctDupAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val expectedAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outputAtomicRespQueue = mutable.Queue[(PSN, OpCode.Value)]()

      dut.io.rxWorkReq.valid #= false
      dut.io.sendWriteWorkComp.ready #= false

      // Input to DUT
      val _ = RqDmaBusSim.reqStreamAlwaysFireAndRespSuccess(
        dut.io.dma,
        dut.clockDomain,
        busWidth
      )
      // addrCacheRespQueue
      val _ = AddrCacheSim.reqStreamAlwaysFireAndRespSuccess(
        dut.io.addrCacheRead,
        dut.clockDomain
      )

      fork {
        val pendingReqLimit =
          QpCtrlSim.getMaxPendingReadAtomicWorkReqNum(dut.io.qpAttr)

        // Retry control loop
        while (true) {
          val expectedPsn = dut.io.qpAttr.epsn.toInt
          // Generate read/atomic requests
          RdmaDataPktSim.readAtomicReqStreamMasterGen(
            dut.io.rx.pktFrag,
            expectedPsn,
            pendingReqLimit,
            pmtuLen,
            busWidth,
            maxFragNum
          ) {
            (
                psn,
                _, // psnStart
                fragLast,
                _, // fragIdx,
                _, // pktFragNum,
                _, // pktIdx,
                _, // reqPktNum,
                respPktNum,
                _, // payloadLenBytes,
                _, // headerLenBytes,
                opcode
            ) =>
//              println(
//                f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, respPktNum=${respPktNum}%X"
//              )

              val pktFragData = dut.io.rx.pktFrag.data.toBigInt
              normalReqQueue.enqueue(
                (
                  psn,
                  opcode,
                  dut.io.rx.pktFrag.bth.padCnt.toInt,
                  dut.io.rx.pktFrag.bth.ackreq.toBoolean,
                  fragLast,
                  pktFragData,
                  dut.io.rx.pktFrag.mty.toBigInt
                )
              )

              if (opcode.isReadReqPkt()) {
                // TODO: check read response data
                for (pktIdx <- 0 until respPktNum) {
                  val readRespOpCode =
                    WorkReqSim.assignReadRespOpCode(pktIdx, respPktNum)
                  val readRespPsn = psn +% pktIdx
                  normalReadRespQueue.enqueue(
                    (readRespPsn, readRespOpCode)
                  )
                }

                // Prepare partial retry read requests and responses
                val readPartialRetryPsnOffset = if (respPktNum > 2) {
                  2 // Partial retry read request skip the first two packets
                } else {
                  0 // Full retry read request
                }
                val pmtuLenBytes = MiscUtils.getPmtuPktLenBytes(pmtuLen)
                val readPartialRetryLenOffset =
                  readPartialRetryPsnOffset * pmtuLenBytes
                val partialRetryReadReqPsn = psn +% readPartialRetryPsnOffset
                val (virtualAddr, rKey, pktLen) =
                  RethSim.extract(pktFragData, busWidth)
                val partialRetryPktLen = pktLen - readPartialRetryLenOffset
                val partialRetryPktNum =
                  MiscUtils.computePktNum(partialRetryPktLen, pmtuLen)
                val partialRetryReadAddr =
                  virtualAddr + readPartialRetryLenOffset
                val partialRetryReadReqPktFragData = RethSim.set(
                  partialRetryReadAddr,
                  rKey,
                  partialRetryPktLen,
                  busWidth
                )

                partialRetryReadAndMaybeWrongRetryAtomicReqQueue.enqueue(
                  (
                    partialRetryReadReqPsn,
                    opcode,
                    dut.io.rx.pktFrag.bth.padCnt.toInt,
                    dut.io.rx.pktFrag.bth.ackreq.toBoolean,
                    fragLast,
                    partialRetryReadReqPktFragData,
                    dut.io.rx.pktFrag.mty.toBigInt
                  )
                )

                partialRetryPktNum shouldBe (respPktNum - readPartialRetryPsnOffset) withClue
                  f"${simTime()} time: partialRetryPktNum=${partialRetryPktNum}%X should == (respPktNum=${respPktNum}%X - readPartialRetryPsnOffset=${readPartialRetryPsnOffset}%X)"

                // TODO: check partial retry read response data
                for (partialRetryReadRespPktIdx <- 0 until partialRetryPktNum) {
                  val partialRetryReadRespOpCode =
                    WorkReqSim.assignReadRespOpCode(
                      partialRetryReadRespPktIdx,
                      partialRetryPktNum
                    )
                  val partialRetryReadRespPsn =
                    partialRetryReadReqPsn +% partialRetryReadRespPktIdx
                  partialRetryReadRespQueue.enqueue(
                    (partialRetryReadRespPsn, partialRetryReadRespOpCode)
                  )
                }
              } else if (opcode.isAtomicReqPkt()) {
                normalAtomicRespQueue.enqueue(
                  (psn, OpCode.ATOMIC_ACKNOWLEDGE)
                )

                val isWrongRetryAtomicReq = scala.util.Random.nextBoolean()
                if (isWrongRetryAtomicReq) {
                  val (virtualAddr, rKey, atomicSwap, atomicComp) =
                    AtomicEthSim.extract(pktFragData, busWidth)
//                  println(
//                    f"${simTime()} time: virtualAddr=${virtualAddr}%X, rKey=${rKey}%X, atomicSwap=${atomicSwap}%X, atomicComp=${atomicComp}%X"
//                  )

                  // Duplicated atomic requests not match original ones have no responses
                  val wrongRetryAtomicReqPktFragData = AtomicEthSim.set(
                    addr = atomicSwap,
                    rkey = rKey,
                    swap = atomicComp,
                    comp = virtualAddr,
                    busWidth
                  )
                  partialRetryReadAndMaybeWrongRetryAtomicReqQueue.enqueue(
                    (
                      psn,
                      opcode,
                      dut.io.rx.pktFrag.bth.padCnt.toInt,
                      dut.io.rx.pktFrag.bth.ackreq.toBoolean,
                      fragLast,
                      wrongRetryAtomicReqPktFragData,
                      dut.io.rx.pktFrag.mty.toBigInt
                    )
                  )
                } else {
                  correctDupAtomicRespQueue.enqueue(
                    (psn, OpCode.ATOMIC_ACKNOWLEDGE)
                  )

                  partialRetryReadAndMaybeWrongRetryAtomicReqQueue.enqueue(
                    (
                      psn,
                      opcode,
                      dut.io.rx.pktFrag.bth.padCnt.toInt,
                      dut.io.rx.pktFrag.bth.ackreq.toBoolean,
                      fragLast,
                      pktFragData,
                      dut.io.rx.pktFrag.mty.toBigInt
                    )
                  )
                }
              }
          }

          // Append normal requests from reqPktFrag4RetryQueue to bothNormalAndRetryReqQueue
          bothNormalAndRetryReqQueue.appendAll(normalReqQueue)
          // Append normal read responses
          expectedReadRespQueue.appendAll(normalReadRespQueue)
          // Append normal atomic responses
          expectedAtomicRespQueue.appendAll(normalAtomicRespQueue)

          dut.clockDomain.waitSamplingWhere {
//              println(f"${simTime()} time: expectedReadRespQueue.size=${expectedReadRespQueue.size}, expectedAtomicRespQueue.size=${expectedAtomicRespQueue.size}")
            expectedReadRespQueue.isEmpty && expectedAtomicRespQueue.isEmpty
          }

          val ePsn = dut.io.qpAttr.epsn.toInt
          val rqOutPsn = dut.io.qpAttr.rqOutPsn.toInt
          // Wait until all normal requests processed
          println(
            f"${simTime()} time: normal responses done, dut.io.qpAttr.epsn=${dut.io.qpAttr.epsn.toInt}%X, dut.io.qpAttr.rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt}%X, start retry"
          )
          // Append duplicated requests from partialRetryReadAndAtomicReqQueue to bothNormalAndRetryReqQueue
          bothNormalAndRetryReqQueue.appendAll(
            partialRetryReadAndMaybeWrongRetryAtomicReqQueue
          )

          // Append duplicated read responses
          expectedReadRespQueue.appendAll(partialRetryReadRespQueue)
          // Append duplicated atomic responses
          expectedAtomicRespQueue.appendAll(correctDupAtomicRespQueue)

          dut.clockDomain.waitSamplingWhere {
//              println(f"${simTime()} time: expectedReadRespQueue.size=${expectedReadRespQueue.size}, expectedAtomicRespQueue.size=${expectedAtomicRespQueue.size}")
            expectedReadRespQueue.isEmpty && expectedAtomicRespQueue.isEmpty
          }

          dut.io.qpAttr.epsn.toInt shouldBe ePsn withClue
            f"${simTime()} time: after retry, ePSN=${dut.io.qpAttr.epsn.toInt} should remain @ ${ePsn}"
          dut.io.qpAttr.rqOutPsn.toInt shouldBe rqOutPsn withClue
            f"${simTime()} time: after retry, rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt} should remain @ ${rqOutPsn}"

          println(
            f"${simTime()} time: retry responses done, dut.io.qpAttr.epsn=${dut.io.qpAttr.epsn.toInt}%X, dut.io.qpAttr.rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt}%X, finish retry"
          )
          normalReqQueue.clear()
          partialRetryReadAndMaybeWrongRetryAtomicReqQueue.clear()
          normalReadRespQueue.clear()
          partialRetryReadRespQueue.clear()
          normalAtomicRespQueue.clear()
          correctDupAtomicRespQueue.clear()
        }
      }

      streamMasterPayloadFromQueueNoRandomDelay(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        bothNormalAndRetryReqQueue,
        payloadAssignFunc = (
            pktFrag: Fragment[RdmaDataPkt],
            payloadData: (
                PSN,
                OpCode.Value,
                PadCnt,
                AckReq,
                FragLast,
                PktFragData,
                MTY
            )
        ) => {
          val (psn, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
            payloadData
          pktFrag.bth.psn #= psn
          pktFrag.bth.opcodeFull #= opcode.id
          pktFrag.bth.padCnt #= padCnt
          pktFrag.bth.ackreq #= ackreq
          pktFrag.data #= pktFragData
          pktFrag.mty #= mty
          pktFrag.last #= fragLast

//          if (opcode.isReadReqPkt()) {
//            val (virtualAddr, rKey, pktLen) =
//              RethSim.extract(pktFragData, busWidth)
//            println(
//              f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, virtualAddr=${virtualAddr}%X, rKey=${rKey}%X, pktLen=${pktLen}%X"
//            )
//          } else if (opcode.isAtomicReqPkt()) {
//            val (virtualAddr, rKey, atomicSwap, atomicComp) = AtomicEthSim.extract(pktFragData, busWidth)
//            println(
//              f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, virtualAddr=${virtualAddr}%X, rKey=${rKey}%X, atomicSwap=${atomicSwap}%X, atomicComp=${atomicComp}%X"
//            )
//          }

          val reqValid = true
          reqValid
        }
      )

      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
//        val opcode = OpCode(dut.io.rx.pktFrag.bth.opcodeFull.toInt)
//        val ackreq = dut.io.rx.pktFrag.bth.ackreq.toBoolean
//        val fragLast = dut.io.rx.pktFrag.last.toBoolean
//        println(
//          f"${simTime()} time: input opcode=${opcode}, PSN=${dut.io.rx.pktFrag.bth.psn.toInt}%X, ePSN=${dut.io.qpAttr.epsn.toInt}%X, fragLast=${fragLast}, ackreq=${ackreq}"
//        )
      }

      streamSlaveAlwaysReady(dut.io.tx.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.tx.pktFrag, dut.clockDomain) {
        val opcode = OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt)
        val psn = dut.io.tx.pktFrag.bth.psn.toInt
        val fragLast = dut.io.tx.pktFrag.last.toBoolean

        if (opcode.isReadRespPkt()) {
          if (fragLast) {
            outputReadRespQueue.enqueue((psn, opcode))
          }
        } else if (opcode.isAtomicRespPkt()) {
          outputAtomicRespQueue.enqueue((psn, opcode))

          fragLast shouldBe true withClue
            f"${simTime()} time: dut.io.tx.pktFrag.last=${fragLast} should be true when opcode=${opcode}"
        } else {
          SpinalExit(f"${simTime()} time: invalid opcode=${opcode}, PSN=${psn}")
        }

//        println(
//          f"${simTime()} time: response: PSN=${psn}%X, opcode=${opcode}, fragLast=${fragLast}"
//        )
      }

      // TODO: support atomic requests
      MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
        cond =
          !(dut.io.dma.atomic.wr.req.valid.toBoolean || dut.io.dma.atomic.rd.req.valid.toBoolean),
        clue =
          f"${simTime()} time: dut.io.dma.atomic.wr.req.valid or dut.io.dma.atomic.rd.req.valid=${dut.io.dma.atomic.wr.req.valid.toBoolean || dut.io.dma.atomic.rd.req.valid.toBoolean} should be false"
      )

      MiscUtils.checkExpectedOutputMatchAlways(
        dut.clockDomain,
        expectedReadRespQueue,
        outputReadRespQueue
      )
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        expectedAtomicRespQueue,
        outputAtomicRespQueue,
        MATCH_CNT
      )
    }

  test("RecvQ RNR retry case") {
    retryTestFunc(rnrRetryOrSeqNakRetry = true)
  }

  test("RecvQ SEQ NAK retry case") {
    retryTestFunc(rnrRetryOrSeqNakRetry = false)
  }

  def retryTestFunc(rnrRetryOrSeqNakRetry: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      QpCtrlSim.connectRecvQ(
        dut.clockDomain,
        pmtuLen,
        dut.io.psnInc,
        dut.io.notifier,
        dut.io.qpAttr,
        dut.io.rxQCtrl
      )

      val sendWriteImmReqQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()
      val bothNormalAndRetryReqQueue = mutable.Queue[
        (PSN, OpCode.Value, PadCnt, AckReq, FragLast, PktFragData, MTY)
      ]()

      val normalAckQueue =
        mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
      val retryAckQueue =
        mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
      val expectedAckQueue =
        mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
      val outputAckQueue =
        mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()

      val expectedWorkCompQueue =
        mutable.Queue[
          (
              WorkReqId,
              SpinalEnumElement[WorkCompStatus.type]
          )
        ]()
      val outputWorkCompQueue =
        mutable.Queue[
          (
              WorkReqId,
              SpinalEnumElement[WorkCompStatus.type]
          )
        ]()

      // Input to DUT
      val _ = RqDmaBusSim.reqStreamAlwaysFireAndRespSuccess(
        dut.io.dma,
        dut.clockDomain,
        busWidth
      )
      // addrCacheRespQueue
      val _ = AddrCacheSim.reqStreamAlwaysFireAndRespSuccess(
        dut.io.addrCacheRead,
        dut.clockDomain
      )

      fork {
        val pendingReqLimit = 10

        // Retry control loop
        while (true) {
          val expectedPsn = dut.io.qpAttr.epsn.toInt
          // Recv WR not ready for RNR retry case
          dut.io.rxWorkReq.valid #= !rnrRetryOrSeqNakRetry

          // Generate send/write requests
          RdmaDataPktSim.sendWriteImmReqPktFragStreamMasterGen(
            dut.io.rx.pktFrag,
            expectedPsn,
            pendingReqLimit,
            pmtuLen,
            busWidth,
            maxFragNum
          ) {
            (
                psn,
                _, // psnStart
                fragLast,
                _, // fragIdx,
                _, // pktFragNum,
                _, // pktIdx,
                _, // reqPktNum,
                _, // respPktNum,
                _, // payloadLenBytes,
                _, // headerLenBytes,
                opcode
            ) =>
//              println(
//                f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, payloadLenBytes=${payloadLenBytes}%X, fragLast=${fragLast}"
//              )

              sendWriteImmReqQueue.enqueue(
                (
                  psn,
                  opcode,
                  dut.io.rx.pktFrag.bth.padCnt.toInt,
                  dut.io.rx.pktFrag.bth.ackreq.toBoolean,
                  fragLast,
                  dut.io.rx.pktFrag.data.toBigInt,
                  dut.io.rx.pktFrag.mty.toBigInt
                )
              )

              if (fragLast) {
                if (opcode.isLastOrOnlyReqPkt()) {
                  normalAckQueue.enqueue((psn, AckType.NORMAL))
                }

                // For retry NAK, only response to the first error
                if (rnrRetryOrSeqNakRetry) {
                  if (opcode.needRxBuf() && retryAckQueue.isEmpty) {
                    retryAckQueue.enqueue((psn, AckType.NAK_RNR))
                  }
                } else {
                  val ePsn = dut.io.qpAttr.epsn.toInt
                  if (retryAckQueue.isEmpty) {
                    retryAckQueue.enqueue((ePsn, AckType.NAK_SEQ))
                  }
                }
              }

          }

          // Append requests trigger retry NAK
          if (rnrRetryOrSeqNakRetry) { // RNR retry case
            bothNormalAndRetryReqQueue.appendAll(sendWriteImmReqQueue)
          } else { // SEQ NAK retry case
            val sendWriteImmReqWrongPsnQueue = sendWriteImmReqQueue.map {
              reqData =>
                val (psn, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
                  reqData
                val wrongPsn = psn +% 1 // Make wrong PSN for SEQ NAK
                (wrongPsn, opcode, padCnt, ackreq, fragLast, pktFragData, mty)
            }
            bothNormalAndRetryReqQueue.appendAll(sendWriteImmReqWrongPsnQueue)
          }
          expectedAckQueue.appendAll(retryAckQueue)
          /*
          val needRetry = if (rnrRetryOrSeqNakRetry) { // RNR retry case
            bothNormalAndRetryReqQueue.appendAll(sendWriteImmReqQueue)
            val sendWriteReqOpCode = sendWriteImmReqQueue.last._2
            if (sendWriteReqOpCode.needRxBuf()) {
              // Append retry responses
              expectedAckQueue.appendAll(retryAckQueue)
              true // Need retry
            } else {
              // Append normal write responses
              expectedAckQueue.appendAll(normalAckQueue)
              false // No need retry
            }
          } else { // SEQ NAK retry case
            val sendWriteReqWrongPsnQueue = sendWriteImmReqQueue.map { reqData =>
              val (psn, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
                reqData
              val wrongPsn = psn +% 1 // Make wrong PSN for SEQ NAK
              (wrongPsn, opcode, padCnt, ackreq, fragLast, pktFragData, mty)
            }
            bothNormalAndRetryReqQueue.appendAll(sendWriteReqWrongPsnQueue)
            // Append retry responses
            expectedAckQueue.appendAll(retryAckQueue)
            true // Need retry
          }
           */
          // Wait until requests processed
          dut.clockDomain.waitSamplingWhere(
            bothNormalAndRetryReqQueue.isEmpty && expectedAckQueue.isEmpty
          )

          println(
            f"${simTime()} time: requests with retry NAK done, dut.io.qpAttr.epsn=${dut.io.qpAttr.epsn.toInt}%X, dut.io.qpAttr.rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt}%X, start retry"
          )

          // Recv WR ready
          dut.io.rxWorkReq.randomize()
          dut.io.rxWorkReq.valid #= true

          // Append normal requests
          val headPktFrag = sendWriteImmReqQueue.head
          val (_, headOpCode, _, _, _, _, _) = headPktFrag
          if (!rnrRetryOrSeqNakRetry || headOpCode.needRxBuf()) {
            bothNormalAndRetryReqQueue.appendAll(sendWriteImmReqQueue)
          } else {
            val sendWriteImmReqQueueWithoutDupReq =
              sendWriteImmReqQueue.dropWhile { queueElem =>
                val (_, opcode, _, _, _, _, _) = queueElem
                !opcode.needRxBuf()
              }
            val firstPktNeedRxBuf = sendWriteImmReqQueueWithoutDupReq.dequeue()
            val (psn, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
              firstPktNeedRxBuf
            val (_, _, pktLen) = RethSim.extract(pktFragData, busWidth)
            val remainingPktLen = pktLen % MiscUtils.getPmtuPktLenBytes(pmtuLen)
            val pktFragDataWithModifiedPktLen =
              RethSim.setDlen(pktFragData, remainingPktLen, busWidth)
            bothNormalAndRetryReqQueue.enqueue(
              (
                psn,
                opcode,
                padCnt,
                ackreq,
                fragLast,
                pktFragDataWithModifiedPktLen,
                mty
              )
            )
            bothNormalAndRetryReqQueue.appendAll(
              sendWriteImmReqQueueWithoutDupReq
            )

            println(
              f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, pktLen=${pktLen}%X, remainingPktLen=${remainingPktLen}%X "
            )
          }

//          val ePsn = dut.io.qpAttr.epsn.toInt
//          val rqOutPsn = dut.io.qpAttr.rqOutPsn.toInt
//        val latestReqPsn = normalAckQueue.last._1
//        latestReqPsn shouldBe dut.io.qpAttr.rqOutPsn.toInt withClue
//          f"${simTime()} time: latestReqPsn=${latestReqPsn}%X should == dut.io.qpAttr.rqOutPsn.toInt=${dut.io.qpAttr.rqOutPsn.toInt}%X"

          // Append normal send/write responses
          expectedAckQueue.appendAll(normalAckQueue)

          dut.clockDomain.waitSamplingWhere(expectedAckQueue.isEmpty)
//          dut.io.qpAttr.epsn.toInt shouldBe ePsn withClue
//            f"${simTime()} time: after retry, ePSN=${dut.io.qpAttr.epsn.toInt} should remain @ ${ePsn}"
//          dut.io.qpAttr.rqOutPsn.toInt shouldBe rqOutPsn withClue
//            f"${simTime()} time: after retry, rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt} should remain @ ${rqOutPsn}"

          println(
            f"${simTime()} time: retry requests done, dut.io.qpAttr.epsn=${dut.io.qpAttr.epsn.toInt}%X, dut.io.qpAttr.rqOutPsn=${dut.io.qpAttr.rqOutPsn.toInt}%X, finish retry"
          )
          sendWriteImmReqQueue.clear()
          retryAckQueue.clear()
          normalAckQueue.clear()
        }
      }

      streamMasterPayloadFromQueueNoRandomDelay(
        dut.io.rx.pktFrag,
        dut.clockDomain,
        bothNormalAndRetryReqQueue,
        payloadAssignFunc = (
            pktFrag: Fragment[RdmaDataPkt],
            payloadData: (
                PSN,
                OpCode.Value,
                PadCnt,
                AckReq,
                FragLast,
                PktFragData,
                MTY
            )
        ) => {
          val (psn, opcode, padCnt, ackreq, fragLast, pktFragData, mty) =
            payloadData
          pktFrag.bth.psn #= psn
          pktFrag.bth.opcodeFull #= opcode.id
          pktFrag.bth.padCnt #= padCnt
          pktFrag.bth.ackreq #= ackreq
          pktFrag.data #= pktFragData
          pktFrag.mty #= mty
          pktFrag.last #= fragLast

//          if (opcode.isWriteReqPkt()) {
//            val (virtualAddr, rKey, pktLen) =
//              RethSim.extract(pktFragData, busWidth)
//            println(
//              f"${simTime()} time: PSN=${psn}%X, opcode=${opcode}, virtualAddr=${virtualAddr}%X, rKey=${rKey}%X, pktLen=${pktLen}%X"
//            )
//          }

          val reqValid = true
          reqValid
        }
      )

      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
//        val opcode = OpCode(dut.io.rx.pktFrag.bth.opcodeFull.toInt)
//        val ackreq = dut.io.rx.pktFrag.bth.ackreq.toBoolean
//        val fragLast = dut.io.rx.pktFrag.last.toBoolean
//        println(
//          f"${simTime()} time: input opcode=${opcode}, PSN=${dut.io.rx.pktFrag.bth.psn.toInt}%X, ePSN=${dut.io.qpAttr.epsn.toInt}%X, fragLast=${fragLast}, ackreq=${ackreq}"
//        )
      }

      onStreamFire(dut.io.rxWorkReq, dut.clockDomain) {
        val workCompId = dut.io.rxWorkReq.id.toBigInt
        val workCompStatus = WorkCompStatus.SUCCESS
        expectedWorkCompQueue.enqueue(
          (workCompId, workCompStatus)
        )

//      println(
//        f"${simTime()} time: workCompId=${workCompId}%X, workCompOpCode=${workCompOpCode}, workCompStatus=${workCompStatus}"
//      )
      }

      streamSlaveAlwaysReady(dut.io.sendWriteWorkComp, dut.clockDomain)
      onStreamFire(dut.io.sendWriteWorkComp, dut.clockDomain) {
        val workCompId = dut.io.sendWriteWorkComp.id.toBigInt
        val workCompStatus = dut.io.sendWriteWorkComp.status.toEnum
        List(
          WorkCompOpCode.RECV,
          WorkCompOpCode.RECV_RDMA_WITH_IMM
        ) should contain(dut.io.sendWriteWorkComp.opcode.toEnum) withClue
          f"${simTime()} time: dut.io.sendWriteWorkComp.opcode=${dut.io.sendWriteWorkComp.opcode.toEnum} should be RECV or RECV_RDMA_WITH_IMM"

        outputWorkCompQueue.enqueue((workCompId, workCompStatus))

//      println(
//        f"${simTime()} time: workCompId=${workCompId}%X, workCompOpCode=${workCompOpCode}, workCompStatus=${workCompStatus}"
//      )
      }

      streamSlaveAlwaysReady(dut.io.tx.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.tx.pktFrag, dut.clockDomain) {
        val opcode = OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt)
        val psn = dut.io.tx.pktFrag.bth.psn.toInt
        val fragLast = dut.io.tx.pktFrag.last.toBoolean

        if (opcode.isAckRespPkt()) {
          val (_, aethCode, aethValue, _) =
            AethSim.extract(dut.io.tx.pktFrag.data.toBigInt, busWidth)
          val ackType = AckTypeSim.decodeFromCodeAndValue(aethCode, aethValue)
          outputAckQueue.enqueue((psn, ackType))

//          println(
//            f"${simTime()} time: response: PSN=${psn}%X, opcode=${opcode}, ackType=${ackType}, fragLast=${fragLast}"
//          )

          fragLast shouldBe true withClue
            f"${simTime()} time: dut.io.tx.pktFrag.last=${fragLast} should be true"
        } else {
          SpinalExit(f"${simTime()} time: invalid opcode=${opcode}, PSN=${psn}")
        }

//        println(
//          f"${simTime()} time: response: opcode=${opcode}, PSN=${psn}%X, fragLast=${fragLast}"
//        )
      }

      // TODO: support atomic requests
      MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
        cond =
          !(dut.io.dma.atomic.wr.req.valid.toBoolean || dut.io.dma.atomic.rd.req.valid.toBoolean),
        clue =
          f"${simTime()} time: dut.io.dma.atomic.wr.req.valid or dut.io.dma.atomic.rd.req.valid=${dut.io.dma.atomic.wr.req.valid.toBoolean || dut.io.dma.atomic.rd.req.valid.toBoolean} should be false"
      )

      MiscUtils.checkExpectedOutputMatchAlways(
        dut.clockDomain,
        expectedWorkCompQueue,
        outputWorkCompQueue
      )
      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        expectedAckQueue,
        outputAckQueue,
        MATCH_CNT
      )
    }
}
