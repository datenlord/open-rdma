package rdma

import spinal.core._
import spinal.core.sim._

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

class ReqCommCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReqCommCheck(busWidth))

  test("ReqCommCheck read atomic request") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputPktNumQueue = mutable.Queue[(OpCode.Value, PktNum)]()
      val outputPktNumQueue = mutable.Queue[(OpCode.Value, PktNum)]()

      val fixedPsn = 1
      dut.io.qpAttr.epsn #= fixedPsn
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.flush #= false
      dut.io.readAtomicRstCacheOccupancy #= 0

      streamMasterDriver(dut.io.rx.pktFrag, dut.clockDomain) {
        val workReqOpCode = WorkReqSim.randomReadAtomicOpCode()
        val opcode =
          WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx = 0, pktNum = 1)
        dut.io.rx.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.pktFrag.bth.psn #= fixedPsn
        dut.io.rx.pktFrag.last #= true
        val totalLenBytes = if (opcode.isReadReqPkt()) {
          widthOf(BTH()) + widthOf(RETH())
        } else {
          widthOf(BTH()) + widthOf(AtomicEth())
        }
        RdmaDataPktSim.setMtyAndPadCnt(
          dut.io.rx.pktFrag,
          fragIdx = 0,
          fragNum = 1,
          totalLenBytes = totalLenBytes.toLong,
          busWidth
        )
      }
      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
        val opcode = OpCode(dut.io.rx.pktFrag.bth.opcodeFull.toInt)
        if (opcode.isReadReqPkt()) {
          val (_, _, pktLenBytes) =
            RethSim.extract(dut.io.rx.pktFrag.data.toBigInt, busWidth)
          val pktNum = MiscUtils.computePktNum(pktLenBytes, pmtuLen)
//          println(f"${simTime()} time: pktLenBytes=${pktLenBytes}%X, pktNum=${pktNum}%X")
          inputPktNumQueue.enqueue((opcode, pktNum))
        } else {
          val singlePktNum = 1
          inputPktNumQueue.enqueue((opcode, singlePktNum))
        }

        if (dut.io.rx.pktFrag.last.toBoolean) {
          assert(
            dut.io.epsnInc.inc.toBoolean,
            f"${simTime()} time: dut.io.epsnInc.inc=${dut.io.epsnInc.inc.toBoolean} should be true when dut.io.rx last fire"
          )
          outputPktNumQueue.enqueue((opcode, dut.io.epsnInc.incVal.toInt))
        }
      }
      streamSlaveRandomizer(dut.io.tx.checkRst, dut.clockDomain)

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputPktNumQueue,
        outputPktNumQueue,
        MATCH_CNT
      )
    }
  }

  def testSendWriteFunc(isNormalCaseOrDupCase: Boolean) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[(PSN, PktFragData, FragLast, HasNakSeq)]()
      val outputQueue = mutable.Queue[(PSN, PktFragData, FragLast, HasNakSeq)]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.flush #= false
      dut.io.readAtomicRstCacheOccupancy #= 0

      pktFragStreamMasterDriver(dut.io.rx.pktFrag, dut.clockDomain) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val totalLenBytes = totalLenItr.next()
        val workReqOpCode = WorkReqSim.randomSendWriteOpCode()
//        println(
//          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        (
          psnStart,
          totalFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          (totalLenBytes, workReqOpCode)
        )
      } { (psn, _, fragLast, fragIdx, fragNum, pktIdx, pktNum, internalData) =>
        if (isNormalCaseOrDupCase) { // Normal case
          // ePSN matches input request packet PSN
          dut.io.qpAttr.epsn #= psn
          // oPSN sets to the previous PSN
          dut.io.qpAttr.rqOutPsn #= psn -% 1
        } else { // Duplicate request case
          // ePSN sets to the next PSN
          dut.io.qpAttr.epsn #= psn +% 1
          // oPSN sets to the current PSN
          dut.io.qpAttr.rqOutPsn #= psn
        }

        val (totalLenBytes, workReqOpCode) = internalData
        // Only RC is supported
//        dut.io.rx.pktFrag.bth.transport #= Transports.RC.id
        val opcode = WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx, pktNum)
        dut.io.rx.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.pktFrag.bth.psn #= psn
        dut.io.rx.pktFrag.last #= fragLast
        RdmaDataPktSim.setMtyAndPadCnt(
          dut.io.rx.pktFrag,
          fragIdx,
          fragNum,
          totalLenBytes.toLong,
          busWidth
        )
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, PSN=${psn}%X, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val isNakSeq = !isNormalCaseOrDupCase
        val isFragLast = dut.io.rx.pktFrag.last.toBoolean
        inputQueue.enqueue(
          (
            dut.io.rx.pktFrag.bth.psn.toInt,
            dut.io.rx.pktFrag.data.toBigInt,
            isFragLast,
            isNakSeq
          )
        )
        if (isNormalCaseOrDupCase && isFragLast) {
          assert(
            dut.io.epsnInc.inc.toBoolean,
            f"${simTime()} time: dut.io.epsnInc.inc=${dut.io.epsnInc.inc.toBoolean} should be true when dut.io.rx last fire"
          )
          val singlePktNum = 1
          dut.io.epsnInc.incVal.toInt shouldBe singlePktNum withClue
            f"${simTime()} time: dut.io.epsnInc.incVal=${dut.io.epsnInc.incVal.toInt} should equal singlePktNum=${singlePktNum}"
        }
      }

      streamSlaveRandomizer(dut.io.tx.checkRst, dut.clockDomain)
      onStreamFire(dut.io.tx.checkRst, dut.clockDomain) {
        val ackType = AckTypeSim.decodeFromAeth(dut.io.tx.checkRst.nakAeth)
        outputQueue.enqueue(
          (
            dut.io.tx.checkRst.pktFrag.bth.psn.toInt,
            dut.io.tx.checkRst.pktFrag.data.toBigInt,
            dut.io.tx.checkRst.last.toBoolean,
            AckTypeSim.isNakSeq(ackType)
          )
        )
        if (isNormalCaseOrDupCase) { // Normal request case
          assert(
            !dut.io.tx.checkRst.hasNak.toBoolean,
            f"${simTime()} time: dut.io.tx.checkRst.hasNak=${dut.io.tx.checkRst.hasNak.toBoolean} should be false"
          )
//          assert(
//            dut.io.epsnInc.inc.toBoolean,
//            f"${simTime()} time: dut.io.epsnInc.inc=${dut.io.epsnInc.inc.toBoolean} should be true, when isNormalCaseOrDupCase=${isNormalCaseOrDupCase}"
//          )
        } else { // Duplicate request case
          assert(
            AckTypeSim.isNakSeq(
              AckTypeSim.decodeFromAeth(dut.io.tx.checkRst.nakAeth)
            ),
            f"${simTime()} time: NAK type should be NAK SEQ, but NAK code=${dut.io.tx.checkRst.nakAeth.code.toInt} and value=${dut.io.tx.checkRst.nakAeth.value.toInt}, when isNormalCaseOrDupCase=${isNormalCaseOrDupCase}"
          )
        }
      }
      if (!isNormalCaseOrDupCase) { // Duplicate request case
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.epsnInc.inc.toBoolean
        )
      }
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }

  test("ReqCommCheck normal case") {
    testSendWriteFunc(isNormalCaseOrDupCase = true)
  }

  test("ReqCommCheck duplicate request case") {
    testSendWriteFunc(isNormalCaseOrDupCase = false)
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
      inputHasFatalNakOrSeqNak: Boolean
  ): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue =
        mutable.Queue[
          (PSN, OpCode.Value, PktFragData, FragLast, HasNak, HasNakSeq)
        ]()
      val inputWorkReqQueue = mutable.Queue[(WorkReqValid, WorkReqId)]()
      val outputWorkReqQueue = mutable.Queue[(WorkReqValid, WorkReqId)]()
      val outputQueue =
        mutable.Queue[
          (PSN, OpCode.Value, PktFragData, FragLast, HasNak, HasNakSeq)
        ]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.flush #= false

      RdmaDataPktSim.pktFragStreamMasterDriver(
        dut.io.rx.checkRst,
        (r: RqReqCheckStageOutput) => r.pktFrag,
        dut.clockDomain
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
            _, // pktIdx,
            _, // pktNum,
            _, // payloadLenBytes,
            _, // headerLenBytes,
            _ // opcode
        ) =>
          {
            dut.io.rx.checkRst.hasNak #= !inputNormalOrNot
            if (inputNormalOrNot) {
              dut.io.rx.checkRst.nakAeth.setAs((AckType.NORMAL))
            } else {
              if (inputHasFatalNakOrSeqNak) {
                dut.io.rx.checkRst.nakAeth.setAs(AckType.NAK_INV)
              } else {
                dut.io.rx.checkRst.nakAeth.setAs(AckType.NAK_SEQ)
              }
            }
          }
      }
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )

      onStreamFire(dut.io.rx.checkRst, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val ackType = AckTypeSim.decodeFromAeth(dut.io.rx.checkRst.nakAeth)
        inputQueue.enqueue(
          (
            dut.io.rx.checkRst.pktFrag.bth.psn.toInt,
            OpCode(dut.io.rx.checkRst.pktFrag.bth.opcodeFull.toInt),
            dut.io.rx.checkRst.pktFrag.data.toBigInt,
            dut.io.rx.checkRst.last.toBoolean,
            dut.io.rx.checkRst.hasNak.toBoolean,
            if (inputNormalOrNot) {
              val hasNak = false
              hasNak
            } else {
              if (inputHasFatalNakOrSeqNak) {
                AckTypeSim.isFatalNak(ackType)
              } else {
                AckTypeSim.isNakSeq(ackType)
              }
            }
          )
        )
      }

      if (inputNormalOrNot) { // Normal input case
        streamMasterDriverAlwaysValid(dut.io.rxWorkReq, dut.clockDomain) {
          // dut.io.rxWorkReq must be always valid to avoid RNR
        }
        onStreamFire(dut.io.rxWorkReq, dut.clockDomain) {
          inputWorkReqQueue.enqueue(
            (dut.io.rxWorkReq.valid.toBoolean, dut.io.rxWorkReq.id.toBigInt)
          )
        }
      } else { // NAK input case
        dut.io.rxWorkReq.valid #= false
      }

      if (inputNormalOrNot || inputHasFatalNakOrSeqNak) { // Normal input or fatal NAK input case
        streamSlaveRandomizer(dut.io.tx.reqWithRxBuf, dut.clockDomain)
        onStreamFire(dut.io.tx.reqWithRxBuf, dut.clockDomain) {
          val ackType =
            AckTypeSim.decodeFromAeth(dut.io.tx.reqWithRxBuf.nakAeth)
          val opcode =
            OpCode(dut.io.tx.reqWithRxBuf.pktFrag.bth.opcodeFull.toInt)
          val hasNak = dut.io.tx.reqWithRxBuf.hasNak.toBoolean
          val isLastFrag = dut.io.tx.reqWithRxBuf.last.toBoolean
          outputQueue.enqueue(
            (
              dut.io.tx.reqWithRxBuf.pktFrag.bth.psn.toInt,
              opcode,
              dut.io.tx.reqWithRxBuf.pktFrag.data.toBigInt,
              isLastFrag,
              dut.io.tx.reqWithRxBuf.hasNak.toBoolean,
              if (inputNormalOrNot) {
                hasNak
              } else {
                if (inputHasFatalNakOrSeqNak) {
                  AckTypeSim.isFatalNak(ackType)
                } else {
                  AckTypeSim.isNakSeq(ackType)
                }
              }
            )
          )

          if (
            inputNormalOrNot && opcode.needRxBuf() && opcode
              .isLastOrOnlyReqPkt() && isLastFrag
          ) {
            outputWorkReqQueue.enqueue(
              (
                dut.io.tx.reqWithRxBuf.rxBufValid.toBoolean,
                dut.io.tx.reqWithRxBuf.rxBuf.id.toBigInt
              )
            )
          }
        }

        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txDupReq.checkRst.valid.toBoolean
        )
        MiscUtils.checkInputOutputQueues(
          dut.clockDomain,
          inputQueue,
          outputQueue,
          MATCH_CNT
        )
        if (inputNormalOrNot) {
          MiscUtils.checkInputOutputQueues(
            dut.clockDomain,
            inputWorkReqQueue,
            outputWorkReqQueue,
            MATCH_CNT
          )
        }
      } else { // Input has NAK SEQ
        streamSlaveRandomizer(dut.io.txDupReq.checkRst, dut.clockDomain)
        onStreamFire(dut.io.txDupReq.checkRst, dut.clockDomain) {
          val hasNak = true
          val isNakSeq = true
          outputQueue.enqueue(
            (
              dut.io.txDupReq.checkRst.pktFrag.bth.psn.toInt,
              OpCode(dut.io.txDupReq.checkRst.pktFrag.bth.opcodeFull.toInt),
              dut.io.txDupReq.checkRst.pktFrag.data.toBigInt,
              dut.io.txDupReq.checkRst.last.toBoolean,
              hasNak,
              isNakSeq
            )
          )
        }

        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.tx.reqWithRxBuf.valid.toBoolean
        )
        MiscUtils.checkInputOutputQueues(
          dut.clockDomain,
          inputQueue,
          outputQueue,
          MATCH_CNT
        )
      }
    }

  test("ReqRnrCheck normal case") {
    testFunc(inputNormalOrNot = true, inputHasFatalNakOrSeqNak = true)
  }

  test("ReqRnrCheck input has fatal NAK case") {
    testFunc(inputNormalOrNot = false, inputHasFatalNakOrSeqNak = true)
  }

  test("ReqRnrCheck input has retry NAK case") {
    testFunc(inputNormalOrNot = false, inputHasFatalNakOrSeqNak = false)
  }
}

class ReqDmaInfoExtractorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReqDmaInfoExtractor(busWidth))

  def testFunc(inputHasNak: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[
        (
            PSN,
            OpCode.Value,
            AckReq,
            PktFragData,
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
            RxBufValid,
            HasNak,
            FragLast
        )
      ]()
      val inputPsnRangeQueue = mutable.Queue[(OpCode.Value, PsnStart, PsnEnd)]()
      val outputPsnRangeQueue =
        mutable.Queue[(OpCode.Value, PsnStart, PsnEnd)]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      RdmaDataPktSim.pktFragStreamMasterDriver(
        dut.io.rx.reqWithRxBuf,
        (r: RqReqWithRxBuf) => r.pktFrag,
        dut.clockDomain
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
          dut.io.rx.reqWithRxBuf.pktFrag.bth.ackreq #=
            opcode.isLastOrOnlyReqPkt()
          dut.io.rx.reqWithRxBuf.hasNak #= inputHasNak
          if (inputHasNak) {
            dut.io.rx.reqWithRxBuf.nakAeth.setAsRnrNak()
            dut.io.rx.reqWithRxBuf.rxBufValid #= false
          } else {
            dut.io.rx.reqWithRxBuf.rxBufValid #= opcode.needRxBuf()
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
        val (_, _, pktLen) = RethSim.extract(pktFragData, busWidth)
//        println(f"${simTime()} time: psn=${psn}, opcode=${opcode}, ackReq=${ackReq}, isLastFrag=${isLastFrag}")
        inputQueue.enqueue(
          (
            psn,
            opcode,
            ackReq,
            pktFragData,
            dut.io.rx.reqWithRxBuf.rxBufValid.toBoolean,
            dut.io.rx.reqWithRxBuf.hasNak.toBoolean,
            isLastFrag
          )
        )
        if (
          isLastFrag && (inputHasNak || (ackReq || opcode
            .isReadReqPkt() || opcode.isAtomicReqPkt()))
        ) {
          val singlePktNum = 1
          val pktNum = if (opcode.isReadReqPkt()) {
            MiscUtils.computePktNum(pktLen, pmtuLen)
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

      streamSlaveRandomizer(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain)
      onStreamFire(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
        val pktFragData = dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt
        val opcode =
          OpCode(dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt)
        outputQueue.enqueue(
          (
            dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            opcode,
            dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.ackreq.toBoolean,
            pktFragData,
            dut.io.tx.reqWithRxBufAndDmaInfo.rxBufValid.toBoolean,
            dut.io.tx.reqWithRxBufAndDmaInfo.hasNak.toBoolean,
            dut.io.tx.reqWithRxBufAndDmaInfo.last.toBoolean
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

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputPsnRangeQueue,
        outputPsnRangeQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }

  test("ReqDmaInfoExtractor normal case") {
    testFunc(inputHasNak = false)
  }

  test("ReqDmaInfoExtractor invalid input case") {
    testFunc(inputHasNak = true)
  }
}

class ReqAddrValidatorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReqAddrValidator(busWidth))

  def testFunc(addrCacheQuerySuccess: Boolean, inputHasNak: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue =
        mutable.Queue[(PSN, OpCode.Value, PktFragData, HasNak, FragLast)]()
      val outputQueue = mutable
        .Queue[
          (PSN, OpCode.Value, PktFragData, FragLast, PhysicalAddr, HasNak)
        ]()
      val matchQueue = mutable.Queue[FragLast]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      pktFragStreamMasterDriver(
        dut.io.rx.reqWithRxBufAndDmaInfo,
        dut.clockDomain
      ) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val totalLenBytes = totalLenItr.next()
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
          (totalLenBytes, workReqOpCode)
        )
      } { (psn, _, fragLast, fragIdx, _, pktIdx, pktNum, internalData) =>
        val (totalLenBytes, workReqOpCode) = internalData
        // Only RC is supported
//        dut.io.rx.checkRst.pktFrag.bth.transport #= Transports.RC.id
        val opcode = WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx, pktNum)
        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn #= psn
        dut.io.rx.reqWithRxBufAndDmaInfo.last #= fragLast

        if (opcode.hasReth() && fragIdx == 0) {
          val pktFragData =
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt
          dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.data #= RethSim.setDlen(
            pktFragData,
            totalLenBytes.toLong,
            busWidth
          )
        }
        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.ackreq #= opcode
          .isLastOrOnlyReqPkt()
        dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= inputHasNak
        if (inputHasNak) {
          dut.io.rx.reqWithRxBufAndDmaInfo.nakAeth.setAsRnrNak()
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= false
        } else {
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.needRxBuf()
        }
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val pktFragData = dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt
        val opcode =
          OpCode(dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt)
        inputQueue.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            opcode,
            pktFragData,
            dut.io.rx.reqWithRxBufAndDmaInfo.hasNak.toBoolean,
            dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
          )
        )
      }

      val addrCacheRespQueue = if (addrCacheQuerySuccess) {
        AddrCacheSim.alwaysStreamFireAndRespSuccess(
          dut.io.addrCacheRead,
          dut.clockDomain
        )
      } else {
        AddrCacheSim.alwaysStreamFireAndRespFailure(
          dut.io.addrCacheRead,
          dut.clockDomain
        )
      }

      streamSlaveRandomizer(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain)
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
              f"${simTime()} time: hasNakIn=${hasNakIn} should equal hasNakOut=${hasNakOut} when addrCacheQuerySuccess=${addrCacheQuerySuccess} and inputHasNak=${inputHasNak}"

            hasNakOut shouldBe true withClue
              f"${simTime()} time: hasNakIn=${hasNakIn} should equal hasNakOut=${hasNakOut} when addrCacheQuerySuccess=${addrCacheQuerySuccess} and inputHasNak=${inputHasNak}"
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

      waitUntil(matchQueue.size > MATCH_CNT)
    }

  test("ReqAddrValidator normal case") {
    testFunc(addrCacheQuerySuccess = true, inputHasNak = false)
  }

  test("ReqAddrValidator query fail case") {
    testFunc(addrCacheQuerySuccess = false, inputHasNak = false)
  }
}

class PktLenCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new PktLenCheck(busWidth))

  def testFunc(inputHasNak: Boolean, hasLenCheckErr: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue =
        mutable.Queue[(PSN, OpCode.Value, PktFragData, HasNak, FragLast)]()
      val outputQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast, HasNak)]()
      val matchQueue = mutable.Queue[FragLast]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      RdmaDataPktSim.pktFragStreamMasterDriver(
        dut.io.rx.reqWithRxBufAndDmaInfo,
        (r: RqReqWithRxBufAndDmaInfo) => r.pktFrag,
        dut.clockDomain
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
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.lenBytes #= payloadLenBytes
          dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= payloadLenBytes

          dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= inputHasNak
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.needRxBuf()
          if (inputHasNak) {
            dut.io.rx.reqWithRxBufAndDmaInfo.nakAeth.setAsRnrNak()
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
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val opcode =
          OpCode(dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt)
        inputQueue.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            opcode,
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt,
            dut.io.rx.reqWithRxBufAndDmaInfo.hasNak.toBoolean,
            dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain)
      onStreamFire(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            OpCode(
              dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt
            ),
            dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt,
            dut.io.tx.reqWithRxBufAndDmaInfo.last.toBoolean,
            dut.io.tx.reqWithRxBufAndDmaInfo.hasNak.toBoolean
          )
        )
      }

      fork {
        while (true) {
          val (psnIn, opCodeIn, fragDataIn, hasNakIn, fragLastIn) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)

          val (psnOut, opCodeOut, fragDataOut, fragLastOut, hasNakOut) =
            MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)

          psnOut shouldBe psnIn withClue
            f"${simTime()} time: psnIn=${psnIn} should equal psnOut=${psnOut}"

          opCodeOut shouldBe opCodeIn withClue
            f"${simTime()} time: opCodeIn=${opCodeIn} should equal opCodeOut=${opCodeOut}"

          fragDataOut shouldBe fragDataIn withClue
            f"${simTime()} time: fragDataIn=${fragDataIn} should equal fragDataOut=${fragDataOut}"

          fragLastOut shouldBe fragLastIn withClue
            f"${simTime()} time: fragLastIn=${fragLastIn} should equal fragLastOut=${fragLastOut}"

          if (fragLastOut) { // Length check error is at the last fragment
            if (inputHasNak) {
              hasNakOut shouldBe hasNakIn withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should equal hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} or inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
            } else if (hasLenCheckErr) {
              hasNakIn shouldBe false withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should equal hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"

              hasNakOut shouldBe true withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should equal hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
            } else {
              hasNakIn shouldBe false withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should equal hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"

              hasNakOut shouldBe false withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should equal hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
            }
          }
          matchQueue.enqueue(fragLastOut)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }

  test("PktLenCheck normal case") {
    testFunc(inputHasNak = false, hasLenCheckErr = false)
  }

  test("PktLenCheck check fail case") {
    testFunc(inputHasNak = false, hasLenCheckErr = true)
  }

  test("PktLenCheck inputHasNak case") {
    testFunc(inputHasNak = true, hasLenCheckErr = true)
  }
}

class ReqSplitterAndNakGenTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReqSplitterAndNakGen(busWidth))

  def testFunc(
      inputHasNak: Boolean,
      inputNakType: SpinalEnumElement[AckType.type]
  ) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

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

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      RdmaDataPktSim.pktFragStreamMasterDriver(
        dut.io.rx.reqWithRxBufAndDmaInfo,
        (r: RqReqWithRxBufAndDmaInfo) => r.pktFrag,
        dut.clockDomain
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
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.lenBytes #= payloadLenBytes
          dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= payloadLenBytes

          dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= inputHasNak
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.needRxBuf()
          if (inputHasNak) {
            dut.io.rx.reqWithRxBufAndDmaInfo.nakAeth.setAs(inputNakType)
            if (AckTypeSim.isRetryNak(inputNakType)) {
              dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= false
            }
          }
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
      }
      onStreamFire(dut.io.rx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val opcode =
          OpCode(dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt)
        val isLastFrag = dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
        inputQueue.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            opcode,
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt,
            dut.io.rx.reqWithRxBufAndDmaInfo.hasNak.toBoolean,
            dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
          )
        )

        if (inputHasNak && isLastFrag) {
          RqNakSim.matchNakType(dut.io.nakNotifier, inputNakType)
        }
      }

      val dutOutputs = List(
        (dut.io.txReadAtomic, outputReadAtomicQueue),
        (dut.io.txSendWrite, outputSendWriteQueue)
      )
      for ((dutOut, outputQueue) <- dutOutputs) {
        val dutOutStream = dutOut.reqWithRxBufAndDmaInfo
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
          !dut.io.txReadAtomic.reqWithRxBufAndDmaInfo.valid.toBoolean
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txSendWrite.reqWithRxBufAndDmaInfo.valid.toBoolean
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
            assert(
              opCodeIn.isReadReqPkt() ||
                opCodeIn.isAtomicReqPkt() ||
                opCodeIn.isSendReqPkt() ||
                opCodeIn.isWriteReqPkt(),
              f"${simTime()} time: invalid opCodeIn=${opCodeIn}, should be send/write requests"
            )
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
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadDmaReqInitiator)

  def testFunc(inputDupReq: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val inputQueue4DmaReq = mutable.Queue[(PsnStart, PhysicalAddr, PktLen)]()
      val inputQueue4RstCache =
        mutable.Queue[(PsnStart, OpCode.Value, VirtualAddr, DupReq)]()
      val dmaReadReqQueue = mutable.Queue[(PsnStart, PhysicalAddr, PktLen)]()
      val readRstCacheDataQueue =
        mutable.Queue[(PsnStart, OpCode.Value, VirtualAddr, DupReq)]()

      dut.io.rxQCtrl.stateErrFlush #= false

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
        inputReadReqStream.rstCacheData.duplicate #= inputDupReq
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
            dut.io.readRstCacheData.duplicate.toBoolean
          )
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue4DmaReq,
        dmaReadReqQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
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
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqReadAtomicDmaReqBuilder(busWidth))

  def testFunc(inputReadOrAtomicReq: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

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

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      streamMasterDriver(
        dut.io.rx.reqWithRxBufAndDmaInfo,
        dut.clockDomain
      ) {
        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= false
        if (opcode.isAtomicReqPkt()) {
          dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= ATOMIC_DATA_LEN
        }
//      println(
//        f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//      )
      }
      onStreamFire(dut.io.rx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
        inputQueue4DmaReq.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.pa.toBigInt,
            dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen.toLong
          )
        )
        val isDupReq = false
        inputQueue4RstCache.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            OpCode(
              dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt
            ),
            dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.pa.toBigInt,
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
            dut.io.readAtomicRstCachePush.duplicate.toBoolean
          )
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue4DmaReq,
        dmaReadReqQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
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
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqSendWriteDmaReqInitiator(busWidth))

  def testFunc(hasNak: Boolean): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

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

    dut.io.qpAttr.pmtu #= pmtuLen.id
    dut.io.rxQCtrl.stateErrFlush #= false

    RdmaDataPktSim.pktFragStreamMasterDriver(
      dut.io.rx.reqWithRxBufAndDmaInfo,
      (r: RqReqWithRxBufAndDmaInfo) => r.pktFrag,
      dut.clockDomain
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
        dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.lenBytes #= payloadLenBytes
        dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= payloadLenBytes

        dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= hasNak
        dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.needRxBuf()
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
    }
    onStreamFire(dut.io.rx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
      val opcode =
        OpCode(dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt)
      inputQueue.enqueue(
        (
          dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
          opcode,
          dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt,
          dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.pa.toBigInt,
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.id.toBigInt,
          dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
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
      dut.io.txSendWrite.reqWithRxBufAndDmaInfo,
      dut.clockDomain
    )
    onStreamFire(dut.io.txSendWrite.reqWithRxBufAndDmaInfo, dut.clockDomain) {
      outputSendWriteQueue.enqueue(
        (
          dut.io.txSendWrite.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
          OpCode(
            dut.io.txSendWrite.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt
          ),
          dut.io.txSendWrite.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt,
          dut.io.txSendWrite.reqWithRxBufAndDmaInfo.last.toBoolean
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

        assert(
          opCodeIn.isSendReqPkt() || opCodeIn.isWriteReqPkt(),
          f"${simTime()} time: invalid opCodeIn=${opCodeIn}, should be send/write requests"
        )
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
  val maxFragNum = 17

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new SendWriteRespGenerator(busWidth))

  def testFunc(hasNak: Boolean): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    // Input to DUT
    val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    val inputQueue = mutable.Queue[(PSN, OpCode.Value, PktLen, WorkReqId)]()
    val outputAckQueue = mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
    val outputWorkCompQueue =
      mutable.Queue[
        (
            SpinalEnumElement[WorkCompOpCode.type],
            SpinalEnumElement[WorkCompStatus.type],
            WorkReqId,
            PktLen
        )
      ]()
    val matchQueue = mutable.Queue[PSN]()

    dut.io.qpAttr.pmtu #= pmtuLen.id
    dut.io.rxQCtrl.stateErrFlush #= false

    RdmaDataPktSim.pktFragStreamMasterDriver(
      dut.io.rx.reqWithRxBufAndDmaInfo,
      (r: RqReqWithRxBufAndDmaInfo) => r.pktFrag,
      dut.clockDomain
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
        dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.lenBytes #= payloadLenBytes
        dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= payloadLenBytes

        dut.io.rx.reqWithRxBufAndDmaInfo.reqTotalLenValid #= true
        dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= hasNak
        dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.needRxBuf()

        dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.ackreq #= (pktIdx == pktNum - 1)
        if (hasNak) {
          dut.io.rx.reqWithRxBufAndDmaInfo.nakAeth
            .setAs(AckTypeSim.randomFatalNak())
        } else {
          dut.io.rx.reqWithRxBufAndDmaInfo.nakAeth.setAsNormalAck()
        }
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
    }
    onStreamFire(dut.io.rx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
      val isLastFrag = dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
      val ackReq = dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.ackreq.toBoolean
//      println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt}, isLastFrag=${isLastFrag}, ackReq=${ackReq}")
      if (ackReq && isLastFrag) {
        inputQueue.enqueue(
          (
            dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            OpCode(
              dut.io.rx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt
            ),
            dut.io.rx.reqWithRxBufAndDmaInfo.reqTotalLenBytes.toLong,
            dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.id.toBigInt
          )
        )
      }
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

//        println(
//          f"${simTime()} time: psnOut=${psnOut}%X should equal psnIn=${psnIn}%X"
//        )
        psnOut shouldBe psnIn withClue f"${simTime()} time: psnOut=${psnOut}%X should equal psnIn=${psnIn}%X"
//        println(
//          f"${simTime()} time: pktLenOut=${pktLenOut}%X should equal pktLenIn=${pktLenIn}%X"
//        )
        pktLenOut shouldBe pktLenIn withClue f"${simTime()} time: pktLenOut=${pktLenOut}%X should equal pktLenIn=${pktLenIn}%X"
//        println(
//          f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should equal workReqIdIn=${workReqIdIn}%X"
//        )
        workReqIdOut shouldBe workReqIdIn withClue f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should equal workReqIdIn=${workReqIdIn}%X"
//        println(
//          f"${simTime()} time: ackTypeOut=${ackTypeOut}, workCompStatusOut=${workCompStatusOut}, opCodeIn=${opCodeIn}, workCompOpCodeOut=${workCompOpCodeOut}"
//        )
        WorkCompSim.rqCheckWorkCompStatus(ackTypeOut, workCompStatusOut)
        WorkCompSim.rqCheckWorkCompOpCode(opCodeIn, workCompOpCodeOut)

        matchQueue.enqueue(psnOut)
      }
    }

    waitUntil(matchQueue.size > 10) // MATCH_CNT)
  }

  test("SendWriteRespGenerator normal case") {
    testFunc(hasNak = false)
  }

  test("SendWriteRespGenerator input has NAK case") {
    testFunc(hasNak = true)
  }
}

class RqSendWriteWorkCompGeneratorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val maxFragNum = 17

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqSendWriteWorkCompGenerator)

  def testFunc(hasNak: Boolean): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.rxQCtrl.stateErrFlush #= false

    val psnStart = 0
    val psnItr = NaturalNumber.from(psnStart).iterator
    val psn4WorkCompQueue = mutable.Queue[PSN]()
    val psn4DmaWriteRespQueue = mutable.Queue[PSN]()

    val inputQueue = mutable.Queue[(WorkReqId, PktLen)]()
    val outputQueue = mutable.Queue[(WorkReqId, PktLen)]()
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

    if (hasNak) {
      dut.io.dmaWriteResp.resp.valid #= false
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.dmaWriteResp.resp.ready.toBoolean
      )
    } else {
      streamMasterDriver(dut.io.dmaWriteResp.resp, dut.clockDomain) {
        val psn = psn4DmaWriteRespQueue.dequeue()
        dut.io.dmaWriteResp.resp.psn #= psn
      }
    }

    streamSlaveRandomizer(dut.io.sendWriteWorkCompOut, dut.clockDomain)
    onStreamFire(dut.io.sendWriteWorkCompOut, dut.clockDomain) {
      outputQueue.enqueue(
        (
          dut.io.sendWriteWorkCompOut.id.toBigInt,
          dut.io.sendWriteWorkCompOut.lenBytes.toLong
        )
      )
    }

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputQueue,
      outputQueue,
      MATCH_CNT
    )
  }

  test("RqSendWriteWorkCompGenerator normal case") {
    testFunc(hasNak = false)
  }

  test("RqSendWriteWorkCompGenerator has NAK case") {
    testFunc(hasNak = true)
  }
}

class DupReqHandlerAndReadAtomicRstCacheQueryTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new DupReqHandlerAndReadAtomicRstCacheQuery(busWidth))

  def testFunc(
      inputReadOrAtomicReq: Boolean,
      readAtomicRstCacheQuerySuccess: Boolean
  ) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val opcode = if (inputReadOrAtomicReq) {
        OpCode.RDMA_READ_REQUEST
      } else {
        OpCodeSim.randomAtomicOpCode()
      }

      val inputQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outputDupReadReqQueue = mutable
        .Queue[(PSN, QuerySuccess, PsnStart, PhysicalAddr, PktNum, PktLen)]()
      val outputDupAtomicReqQueue = mutable.Queue[(PSN, OpCode.Value)]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      streamMasterDriver(
        dut.io.rx.checkRst,
        dut.clockDomain
      ) {
        dut.io.rx.checkRst.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.checkRst.hasNak #= true
        dut.io.rx.checkRst.nakAeth.setAsSeqNak()
        dut.io.rx.checkRst.last #= true
//        println(f"${simTime()} time: opcode=${opcode}")
      }
      onStreamFire(dut.io.rx.checkRst, dut.clockDomain) {
        val opcode = if (inputReadOrAtomicReq) {
          OpCode(dut.io.rx.checkRst.pktFrag.bth.opcodeFull.toInt)
        } else {
          OpCode.ATOMIC_ACKNOWLEDGE
        }

        inputQueue.enqueue(
          (
            dut.io.rx.checkRst.pktFrag.bth.psn.toInt,
            opcode
          )
        )
      }

      val readAtomicRstCacheRespQueue = if (readAtomicRstCacheQuerySuccess) {
        ReadAtomicRstCacheSim.alwaysStreamFireAndRespSuccess(
          dut.io.readAtomicRstCache,
          opcode,
          dut.clockDomain
        )
      } else {
        ReadAtomicRstCacheSim.alwaysStreamFireAndRespFailure(
          dut.io.readAtomicRstCache,
          opcode,
          dut.clockDomain
        )
      }

      streamSlaveRandomizer(
        dut.io.dupReadReqAndRstCacheData,
        dut.clockDomain
      )
      onStreamFire(dut.io.dupReadReqAndRstCacheData, dut.clockDomain) {
        outputDupReadReqQueue.enqueue(
          (
            dut.io.dupReadReqAndRstCacheData.pktFrag.bth.psn.toInt,
            readAtomicRstCacheQuerySuccess,
            dut.io.dupReadReqAndRstCacheData.rstCacheData.psnStart.toInt,
            dut.io.dupReadReqAndRstCacheData.rstCacheData.pa.toBigInt,
            dut.io.dupReadReqAndRstCacheData.rstCacheData.pktNum.toInt,
            dut.io.dupReadReqAndRstCacheData.rstCacheData.dlen.toLong
          )
        )

        assert(
          dut.io.dupReadReqAndRstCacheData.rstCacheData.duplicate.toBoolean,
          f"${simTime()} time: dut.io.dupReadReqAndRstCacheData.rstCacheData.duplicate=${dut.io.dupReadReqAndRstCacheData.rstCacheData.duplicate.toBoolean} should be true for duplicate read request"
        )
      }

      streamSlaveRandomizer(dut.io.txDupAtomicResp, dut.clockDomain)
      onStreamFire(dut.io.txDupAtomicResp, dut.clockDomain) {
        outputDupAtomicReqQueue.enqueue(
          (
            dut.io.txDupAtomicResp.bth.psn.toInt,
            OpCode(dut.io.txDupAtomicResp.bth.opcodeFull.toInt)
          )
        )
      }

      if (readAtomicRstCacheQuerySuccess) {
        if (inputReadOrAtomicReq) {
          MiscUtils.checkInputOutputQueues(
            dut.clockDomain,
            readAtomicRstCacheRespQueue,
            outputDupReadReqQueue,
            MATCH_CNT
          )
        } else {
          MiscUtils.checkInputOutputQueues(
            dut.clockDomain,
            inputQueue,
            outputDupAtomicReqQueue,
            MATCH_CNT
          )
        }
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
    "DupReqHandlerAndReadAtomicRstCacheQuery duplicate read request query success"
  ) {
    testFunc(inputReadOrAtomicReq = true, readAtomicRstCacheQuerySuccess = true)
  }

  test(
    "DupReqHandlerAndReadAtomicRstCacheQuery duplicate atomic request query success"
  ) {
    testFunc(
      inputReadOrAtomicReq = false,
      readAtomicRstCacheQuerySuccess = true
    )
  }

  test(
    "DupReqHandlerAndReadAtomicRstCacheQuery duplicate read request query failure"
  ) {
    testFunc(
      inputReadOrAtomicReq = true,
      readAtomicRstCacheQuerySuccess = false
    )
  }

  test("DupReqHandlerAndReadAtomicRstCacheQuery duplicate send/write request") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outputQueue = mutable.Queue[(PSN, OpCode.Value)]()

      val fixedEPsn = 1
      dut.io.qpAttr.epsn #= fixedEPsn
      dut.io.rxQCtrl.stateErrFlush #= false

      RdmaDataPktSim.pktFragStreamMasterDriver(
        dut.io.rx.checkRst,
        (r: RqReqCheckStageOutput) => r.pktFrag,
        dut.clockDomain
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
            _, // payloadLenBytes,
            _, // headerLenBytes,
            _ // opcode
        ) =>
          dut.io.rx.checkRst.hasNak #= true
          dut.io.rx.checkRst.nakAeth.setAsSeqNak()
//          println(
//            f"${simTime()} time: opcode=${opcode}, PSN=${psn}, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, isLastFragPerPkt=${pktIdx == pktNum - 1}, payloadLenBytes=${payloadLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//          )
      }
      onStreamFire(dut.io.rx.checkRst, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val opcode = OpCode.ACKNOWLEDGE
        val isLastFrag = dut.io.rx.checkRst.last.toBoolean
        if (isLastFrag) {
          inputQueue.enqueue(
            (
              fixedEPsn,
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

      MiscUtils.checkInputOutputQueues(
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
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new DupReadDmaReqBuilder(busWidth))

  def randomRetryStartPsn(psnStart: PsnStart, pktNum: PktNum): PSN = {
    // RDMA max packet length 2GB=2^31
    psnStart +% scala.util.Random.nextInt(pktNum)
  }

  def testFunc(isPartialRetry: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

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
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.rxDupReadReqAndRstCacheData, dut.clockDomain) {
        val curPsn = nextPsn
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.psnStart #= curPsn
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.opcode #= opcode.id
        val pktLen = WorkReqSim.randomDmaLength()
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.dlen #= pktLen
        val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.pktNum #= pktNum
        dut.io.rxDupReadReqAndRstCacheData.rstCacheData.duplicate #= true

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

      MiscUtils.checkInputOutputQueues(
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
    dut.clockDomain.forkStimulus(10)

    val inputPsnQueue = mutable.Queue[PSN]()
    val outputPsnQueue = mutable.Queue[PSN]()
    val naturalNumItr = NaturalNumber.from(1).iterator

    dut.io.qpAttr.pmtu #= pmtuLen.id
    dut.io.rxQCtrl.stateErrFlush #= false

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
      dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.duplicate #= inputDupReq
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
    MiscUtils.checkInputOutputQueues(
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
    dut.clockDomain.forkStimulus(10)

    val inputDataQueue =
      mutable.Queue[(PktFragData, MTY, PktNum, PsnStart, PktLen, FragLast)]()
    val outputDataQueue = mutable.Queue[(PktFragData, MTY, PSN, FragLast)]()

    dut.io.qpAttr.pmtu #= pmtuLen.id
    dut.io.rxQCtrl.stateErrFlush #= false
    dut.io.readRstCacheDataAndDmaReadRespSegment.valid #= false

    // Input to DUT
    val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
      SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)
    pktFragStreamMasterDriver(
      dut.io.readRstCacheDataAndDmaReadRespSegment,
      dut.clockDomain
    ) {
      val totalFragNum = totalFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val totalLenBytes = totalLenItr.next()

      (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, totalLenBytes)
    } {
      (
          _,
          psnStart,
          fragLast,
          fragIdx,
          totalFragNum,
          _,
          pktNum,
          totalLenBytes
      ) =>
        DmaReadRespSim.setMtyAndLen(
          dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp,
          fragIdx,
          totalFragNum,
          totalLenBytes.toLong,
          busWidth
        )
//        println(
//          f"${simTime()} time: fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, fragLast=${fragLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )

        dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.psnStart #= psnStart
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.psnStart #= psnStart
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.dlen #= totalLenBytes
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.pktNum #= pktNum
        dut.io.readRstCacheDataAndDmaReadRespSegment.rstCacheData.duplicate #= inputDupReq
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
  val maxFragNum = 37

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqReadDmaRespHandler(busWidth))

  test("zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val psnQueue = mutable.Queue[PSN]()
      val matchQueue = mutable.Queue[PSN]()

      dut.io.rxQCtrl.stateErrFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.readRstCacheData, dut.clockDomain) {
        dut.io.readRstCacheData.dlen #= 0
      }
      onStreamFire(dut.io.readRstCacheData, dut.clockDomain) {
        psnQueue.enqueue(dut.io.readRstCacheData.psnStart.toInt)
      }

      // Check DUT output
      MiscUtils.checkConditionAlways(dut.clockDomain) {
        !dut.io.dmaReadResp.resp.ready.toBoolean
      }
      streamSlaveRandomizer(
        dut.io.readRstCacheDataAndDmaReadResp,
        dut.clockDomain
      )
      onStreamFire(dut.io.readRstCacheDataAndDmaReadResp, dut.clockDomain) {
//        println(
//          f"${simTime()} time: the read request has zero DMA length, but dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen.toLong}%X"
//        )

        dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen.toLong shouldBe 0 withClue {
          f"${simTime()} time: the read request has zero DMA length, but dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.dlen.toLong}%X"
        }

        val inputPsnStart = psnQueue.dequeue()
//        println(
//          f"${simTime()} time: output PSN io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt}%X not match input PSN io.readRstCacheData.psnStart=${inputPsnStart}%X"
//        )

        dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt shouldBe inputPsnStart withClue {
          f"${simTime()} time: output PSN io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt}%X not match input PSN io.readRstCacheData.psnStart=${inputPsnStart}%X"
        }

        matchQueue.enqueue(inputPsnStart)
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("non-zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputCacheDataQueue = mutable.Queue[(PsnStart, PktNum, PktLen)]()
      val inputDmaRespQueue =
        mutable.Queue[(PktFragData, PsnStart, PktLen, FragLast)]()

      val outputCacheDataQueue = mutable.Queue[(PsnStart, PktNum, PktLen)]()
      val outputDmaRespQueue =
        mutable.Queue[(PktFragData, PsnStart, PktLen, FragLast)]()

      val randSeed = scala.util.Random.nextInt()
      dut.io.rxQCtrl.stateErrFlush #= false

      // Input to DUT
      val (
        totalFragNumItr4CacheData,
        pktNumItr4CacheData,
        psnStartItr4CacheData,
        totalLenItr4CacheData
      ) =
        SendWriteReqReadRespInputGen.getItr(
          maxFragNum,
          pmtuLen,
          busWidth,
          randSeed
        )

      streamMasterDriver(dut.io.readRstCacheData, dut.clockDomain) {
        val _ = totalFragNumItr4CacheData.next()
        val pktNum = pktNumItr4CacheData.next()
        val psnStart = psnStartItr4CacheData.next()
        val totalLenBytes = totalLenItr4CacheData.next()

        dut.io.readRstCacheData.dlen #= totalLenBytes
        dut.io.readRstCacheData.psnStart #= psnStart
        dut.io.readRstCacheData.pktNum #= pktNum
      }
      onStreamFire(dut.io.readRstCacheData, dut.clockDomain) {
//        println(
//          f"${simTime()} time: dut.io.readRstCacheData.psnStart=${dut.io.readRstCacheData.psnStart.toInt}, dut.io.readRstCacheData.pktNum=${dut.io.readRstCacheData.pktNum.toInt}, dut.io.readRstCacheData.dlen=${dut.io.readRstCacheData.dlen.toLong}"
//        )
        inputCacheDataQueue.enqueue(
          (
            dut.io.readRstCacheData.psnStart.toInt,
            dut.io.readRstCacheData.pktNum.toInt,
            dut.io.readRstCacheData.dlen.toLong
          )
        )
      }

      // Functional way to generate sequences
      val (
        totalFragNumItr4DmaResp,
        pktNumItr4DmaResp,
        psnStartItr4DmaResp,
        totalLenItr4DmaResp
      ) =
        SendWriteReqReadRespInputGen.getItr(
          maxFragNum,
          pmtuLen,
          busWidth,
          randSeed
        )

      pktFragStreamMasterDriver(
        dut.io.dmaReadResp.resp,
        dut.clockDomain
      ) {
        val totalFragNum = totalFragNumItr4DmaResp.next()
        val totalLenBytes = totalLenItr4DmaResp.next()
        val psnStart = psnStartItr4DmaResp.next()
        val pktNum = pktNumItr4DmaResp.next()

        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, totalLenBytes)
      } { (_, psnStart, _, fragIdx, totalFragNum, _, _, totalLenBytes) =>
        dut.io.dmaReadResp.resp.psnStart #= psnStart
        dut.io.dmaReadResp.resp.lenBytes #= totalLenBytes
        dut.io.dmaReadResp.resp.last #= fragIdx == totalFragNum - 1
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
          dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.psnStart.toInt withClue {
            f"${simTime()} time:  dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.rstCacheData.psnStart.toInt}%X should equal dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.psnStart.toInt}%X"
          }

        val isLastFrag = dut.io.readRstCacheDataAndDmaReadResp.last.toBoolean
        if (isLastFrag) {
          outputCacheDataQueue.enqueue(
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

      // Check DUT output
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputDmaRespQueue,
        outputDmaRespQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputCacheDataQueue,
        outputCacheDataQueue,
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
    .compile(new RqOut(busWidth))

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
      inputOutPsnQueue: mutable.Queue[PSN]
  ) =
    for (_ <- 0 until PENDING_REQ_NUM) {
      val _ = payloadFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()
      val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
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
        for (pktIdx <- 0 until pktNum) {
          val psn = psnStart +% pktIdx
          inputOutPsnQueue.enqueue(psn)
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
          assert(
            isSendReq || isWriteReq || isReadReq || isAtomicReq,
            f"${simTime()} time: invalid WR opcode=${workReqOpCode}, must be send/write/read/atomic"
          )
        }
        inputOutPsnQueue.enqueue(psnEnd)
        inputSendWriteRespOrErrRespMetaDataQueue.enqueue(
          (
            psnEnd,
            OpCode.ACKNOWLEDGE
          )
        )
      }
    }

  // hasErrResp only works when normalOrDupResp is true,
  // Since if duplicate response has error, it will be ignored.
  def testFunc(normalOrDupResp: Boolean, hasErrResp: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

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

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

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
        inputOutPsnQueue
      )
      if (normalOrDupResp) {
        // io.outPsnRangeFifoPush must always be valid when output normal responses
        streamMasterDriverAlwaysValid(
          dut.io.outPsnRangeFifoPush,
          dut.clockDomain
        ) {
          val (opcode, psnStart, psnEnd) = outPsnRangeQueue.dequeue()
          dut.io.outPsnRangeFifoPush.opcode #= opcode.id
          dut.io.outPsnRangeFifoPush.start #= psnStart
          dut.io.outPsnRangeFifoPush.end #= psnEnd
          dut.io.qpAttr.epsn #= psnEnd
        }

        // io.readAtomicRstCachePop must always be valid when output normal responses
        streamMasterDriverAlwaysValid(
          dut.io.readAtomicRstCachePop,
          dut.clockDomain
        ) {
          val (opcode, psnStart, pktNum) = readAtomicRstCachePopQueue.dequeue()
          dut.io.readAtomicRstCachePop.psnStart #= psnStart
          dut.io.readAtomicRstCachePop.opcode #= opcode.id
          dut.io.readAtomicRstCachePop.pktNum #= pktNum
        }
      }
      fork {
        while (true) {
          waitUntil(
            inputSendWriteRespOrErrRespMetaDataQueue.isEmpty || inputReadRespMetaDataQueue.isEmpty ||
              inputAtomicRespMetaDataQueue.isEmpty || outPsnRangeQueue.isEmpty ||
              readAtomicRstCachePopQueue.isEmpty || inputOutPsnQueue.isEmpty
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
            inputOutPsnQueue
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

      streamMasterDriver(
        sendWriteRespOrErrRespIn,
        dut.clockDomain
      ) {
        val (psnEnd, opcode) =
          inputSendWriteRespOrErrRespMetaDataQueue.dequeue()
        sendWriteRespOrErrRespIn.bth.psn #= psnEnd
        sendWriteRespOrErrRespIn.bth.opcodeFull #= opcode.id
      }
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
      streamMasterDriver(readRespIn, dut.clockDomain) {
        val (psnEnd, opcode, isLastFrag) = inputReadRespMetaDataQueue.dequeue()
        readRespIn.bth.psn #= psnEnd
        readRespIn.bth.opcodeFull #= opcode.id
        readRespIn.last #= isLastFrag
      }
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
      streamMasterDriver(atomicRespIn, dut.clockDomain) {
        val (psnEnd, opcode) = inputAtomicRespMetaDataQueue.dequeue()
        atomicRespIn.bth.psn #= psnEnd
        atomicRespIn.bth.opcodeFull #= opcode.id
      }
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
      streamSlaveRandomizer(dut.io.tx.pktFrag, dut.clockDomain)
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
          opcode shouldBe OpCode.ACKNOWLEDGE withClue {
            f"${simTime()} time: opcode=${opcode} should be ACKNOWLEDGE"
          }
          outputSendWriteRespOrErrRespQueue.enqueue(
            (psn, opcode, rsvd, code, value, msn)
          )
        }
//      println(
//        f"${simTime()} time: dut.io.tx has opcode=${opcode}, PSN=${psn}, rsvd=${rsvd}%X, code=${code}%X, value=${value}%X, msn=${msn}%X, pktFragData=${pktFragData}%X"
//      )
      }

      if (normalOrDupResp) {
        fork {
          while (true) {
//            println(f"${simTime()} time: inputSendWriteRespOrErrRespQueue.size=${inputSendWriteRespOrErrRespQueue.size}")
//            println(f"${simTime()} time: inputAtomicRespQueue.size=${inputAtomicRespQueue.size}")
//            println(f"${simTime()} time: inputReadRespQueue.size=${inputReadRespQueue.size}")
            dut.clockDomain.waitSampling()
            if (dut.io.opsnInc.inc.toBoolean) {
              outputOutPsnQueue.enqueue(dut.io.opsnInc.psnVal.toInt)
            }
          }
        }
        MiscUtils.checkInputOutputQueues(
          dut.clockDomain,
          inputOutPsnQueue,
          outputOutPsnQueue,
          MATCH_CNT
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          dut.io.outPsnRangeFifoPush.valid.toBoolean
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputSendWriteRespOrErrRespQueue,
        outputSendWriteRespOrErrRespQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputReadRespQueue,
        outputReadRespQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputAtomicRespQueue,
        outputAtomicRespQueue,
        MATCH_CNT
      )
    }

  test("RqOut normal response only case") {
    testFunc(normalOrDupResp = true, hasErrResp = false)
  }

  test("RqOut normal and error response case") {
    testFunc(normalOrDupResp = true, hasErrResp = true)
  }

  test("RqOut duplicate response case") {
    testFunc(normalOrDupResp = false, hasErrResp = false)
  }
}
