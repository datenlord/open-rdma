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

      val mtyWidth = busWidth.id / BYTE_WIDTH
      val fixedPsn = 1
      dut.io.qpAttr.epsn #= fixedPsn
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.flush #= false
      dut.io.readAtomicRstCacheOccupancy #= 0

      streamMasterDriver(dut.io.rx.pktFrag, dut.clockDomain) {
        val workReqOpCode = WorkReqSim.randomReadAtomicOpCode()
        val opcode =
          WorkReqSim.assignOpCode(workReqOpCode, pktIdx = 0, pktNum = 1)
        dut.io.rx.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.pktFrag.bth.psn #= fixedPsn
        dut.io.rx.pktFrag.last #= true
        RdmaDataPktSim.setMtyAndPadCnt(
          dut.io.rx.pktFrag,
          fragIdx = 0,
          fragNum = 1,
          totalLenBytes = mtyWidth.toLong, // Does not matter
          busWidth
        )
      }
      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
        val opcode = OpCode(dut.io.rx.pktFrag.bth.opcodeFull.toInt)
        if (opcode.isReadReqPkt()) {
          val (_, _, pktLenBytes) =
            RethSim.extract(dut.io.rx.pktFrag.data.toBigInt, busWidth)
          val pktNum = MiscUtils.computePktNum(pktLenBytes, pmtuLen)
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
        val opcode = WorkReqSim.assignOpCode(workReqOpCode, pktIdx, pktNum)
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
            f"${simTime()} time: dut.io.epsnInc.incVal=${dut.io.epsnInc.incVal.toInt} should == singlePktNum=${singlePktNum}"
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

  def testFunc(inputNormalOrNot: Boolean, inputHasFatalNakOrSeqNak: Boolean) = {
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

      RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
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

  def testFunc(inputHasNak: Boolean) = {
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
//            VirtualAddr,
//            LRKey,
//            PktLen,
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
//            VirtualAddr,
//            LRKey,
//            PktLen,
            FragLast
        )
      ]()
//      val inputReadReqPsnRangeQueue = mutable.Queue[(PsnStart, PktLen)]()
      val inputPsnRangeQueue = mutable.Queue[(OpCode.Value, PsnStart, PsnEnd)]()
      val outputPsnRangeQueue =
        mutable.Queue[(OpCode.Value, PsnStart, PsnEnd)]()
//      val matchQueue = mutable.Queue[FragLast]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
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
//            addr,
//            rkey,
//            pktLen,
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
//        val (addr, rkey, pktLen) = RethSim.extract(pktFragData, busWidth)
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
//            addr,
//            rkey,
//            pktLen
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

  def testFunc(addrCacheQuerySuccess: Boolean, inputHasNak: Boolean) = {
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
        val opcode = WorkReqSim.assignOpCode(workReqOpCode, pktIdx, pktNum)
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
            f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut}"

          opCodeOut shouldBe opCodeIn withClue
            f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut}"

          fragDataOut shouldBe fragDataIn withClue
            f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut}"

          fragLastOut shouldBe fragLastIn withClue
            f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut}"
          if (addrCacheQuerySuccess || inputHasNak) {
            hasNakOut shouldBe hasNakIn withClue
              f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when addrCacheQuerySuccess=${addrCacheQuerySuccess} or inputHasNak=${inputHasNak}"
          } else {
            hasNakIn shouldBe false withClue
              f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when addrCacheQuerySuccess=${addrCacheQuerySuccess} and inputHasNak=${inputHasNak}"

            hasNakOut shouldBe true withClue
              f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when addrCacheQuerySuccess=${addrCacheQuerySuccess} and inputHasNak=${inputHasNak}"
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
                f"${simTime()} time: psnFirstOrOnly=${psnFirstOrOnly} should == psnOut=${psnOut}"

              paOut shouldBe paAddrCacheResp withClue
                f"${simTime()} time: paAddrCacheResp=${paAddrCacheResp} should == paOut=${paOut}"
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

  def testFunc(inputHasNak: Boolean, hasLenCheckErr: Boolean) = {
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

      RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
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
            f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut}"

          opCodeOut shouldBe opCodeIn withClue
            f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut}"

          fragDataOut shouldBe fragDataIn withClue
            f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut}"

          fragLastOut shouldBe fragLastIn withClue
            f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut}"

          if (fragLastOut) { // Length check error is at the last fragment
            if (inputHasNak) {
              hasNakOut shouldBe hasNakIn withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} or inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
            } else if (hasLenCheckErr) {
              hasNakIn shouldBe false withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"

              hasNakOut shouldBe true withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
            } else {
              hasNakIn shouldBe false withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"

              hasNakOut shouldBe false withClue
                f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
            }
          }
          matchQueue.enqueue(fragLastOut)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
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
//      val workCompQueue = mutable.Queue[
//        (
//            SpinalEnumElement[WorkCompOpCode.type],
//            SpinalEnumElement[WorkCompStatus.type],
//            PSN,
//            SpinalEnumElement[AckType.type]
//        )
//      ]()
      val matchQueue = mutable.Queue[FragLast]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
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

//      streamSlaveRandomizer(dut.io.sendWriteWorkCompErrAndNak, dut.clockDomain)
//      onStreamFire(dut.io.sendWriteWorkCompErrAndNak, dut.clockDomain) {
//        workCompQueue.enqueue(
//          (
//            dut.io.sendWriteWorkCompErrAndNak.workComp.opcode.toEnum,
//            dut.io.sendWriteWorkCompErrAndNak.workComp.status.toEnum,
//            dut.io.sendWriteWorkCompErrAndNak.ack.bth.psn.toInt,
//            AckTypeSim
//              .decodeFromAeth(dut.io.sendWriteWorkCompErrAndNak.ack.aeth)
//          )
//        )
//      }

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
            f"${simTime()} time: inputHasNak=${inputHasNak} should == hasNakIn=${hasNakIn}"
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
                f"${simTime()} time: ackTypeOut=${nakTypeOut} should == inputNakType=${inputNakType}"

              nakPsnOut shouldBe psnIn withClue
                f"${simTime()} time: psnIn=${psnIn} should == nakPsnOut=${nakPsnOut} when inputHasNak=${inputHasNak} and fragLastIn=${fragLastIn} for PSN=${psnIn}%X"

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
              f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut} for opCodeIn=${opCodeIn}, opCodeOut=${opCodeOut}"

            opCodeOut shouldBe opCodeIn withClue
              f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut} for PSN=${psnOut}"

            fragDataOut shouldBe fragDataIn withClue
              f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut} for opcode=${opCodeOut}, PSN=${psnOut}"

            fragLastOut shouldBe fragLastIn withClue
              f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut} for opcode=${opCodeOut}, PSN=${psnOut}"
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

  def testFunc(inputDupReq: Boolean) =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val inputQueue4DmaReq = mutable.Queue[(PsnStart, PhysicalAddr, PktLen)]()
      val inputQueue4RstCache =
        mutable.Queue[(PsnStart, OpCode.Value, VirtualAddr, DupReq)]()
      val dmaReadReqQueue = mutable.Queue[(PsnStart, PhysicalAddr, PktLen)]()
      val readRstCacheDataQueue =
        mutable.Queue[(PsnStart, OpCode.Value, VirtualAddr, DupReq)]()

//      dut.io.qpAttr.pmtu #= pmtuLen.id
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
        inputReadReqStream.cachedData.duplicate #= inputDupReq
        inputReadReqStream.cachedData.opcode #= OpCode.RDMA_READ_REQUEST.id
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
            inputReadReqStream.cachedData.psnStart.toInt,
            OpCode(inputReadReqStream.cachedData.opcode.toInt),
            inputReadReqStream.cachedData.va.toBigInt,
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

  def testFunc(inputReadOrAtomicReq: Boolean) = {
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

  def testFunc(hasNak: Boolean) = simCfg.doSim { dut =>
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
//      val dmaReadReqQueue = mutable.Queue[(PSN, PhysicalAddr, PktLen)]()
    val dmaWriteReqQueue =
      mutable.Queue[(PSN, PhysicalAddr, WorkReqId, PktFragData, FragLast)]()
    val matchQueue = mutable.Queue[FragLast]()

    dut.io.qpAttr.pmtu #= pmtuLen.id
    dut.io.rxQCtrl.stateErrFlush #= false

    RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
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
//            dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen.toLong,
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
//            dmaLenIn,
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
          f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut} for opCodeIn=${opCodeIn}, opCodeOut=${opCodeOut}"

        opCodeOut shouldBe opCodeIn withClue
          f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut} for PSN=${psnOut}"

        fragDataOut shouldBe fragDataIn withClue
          f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut} for opcode=${opCodeOut}, PSN=${psnOut}"

        fragLastOut shouldBe fragLastIn withClue
          f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut} for opcode=${opCodeOut}, PSN=${psnOut}"
        if (!hasNak) {
          val (
            dmaWritePsnOut,
            dmaWriteAddrOut,
            dmaWriteWorkReqIdOut,
            dmaWriteDataOut,
            dmaWriteFragLastOut
          ) = MiscUtils.safeDeQueue(dmaWriteReqQueue, dut.clockDomain)

          dmaWritePsnOut shouldBe psnIn withClue
            f"${simTime()} time: psnIn=${psnIn} should == dmaWritePsnOut=${dmaWritePsnOut} for opcode=${opCodeIn}"

          dmaWriteAddrOut shouldBe paIn withClue
            f"${simTime()} time: paIn=${paIn} should == dmaWriteAddrOut=${dmaWriteAddrOut} for PSN=${psnIn}, opcode=${opCodeIn}"

          dmaWriteWorkReqIdOut shouldBe workReqIdIn withClue
            f"${simTime()} time: workReqIdIn=${workReqIdIn} should == dmaWriteWorkReqIdOut=${dmaWriteWorkReqIdOut} for PSN=${psnIn}, opcode=${opCodeIn}"

          dmaWriteDataOut shouldBe fragDataIn withClue
            f"${simTime()} time: fragDataIn=${fragDataIn} should == dmaWriteDataOut=${dmaWriteDataOut} for PSN=${psnIn}, opcode=${opCodeIn}"

          dmaWriteFragLastOut shouldBe fragLastIn withClue
            f"${simTime()} time: fragLastIn=${fragLastIn} should == dmaWriteFragLastOut=${dmaWriteFragLastOut} for PSN=${psnIn}, opcode=${opCodeIn}"
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
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new SendWriteRespGenerator(busWidth))

  def testFunc(hasNak: Boolean) = simCfg.doSim { dut =>
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

    RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
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
//          f"${simTime()} time: psnOut=${psnOut}%X should == psnIn=${psnIn}%X"
//        )
        psnOut shouldBe psnIn withClue f"${simTime()} time: psnOut=${psnOut}%X should == psnIn=${psnIn}%X"
//        println(
//          f"${simTime()} time: pktLenOut=${pktLenOut}%X should == pktLenIn=${pktLenIn}%X"
//        )
        pktLenOut shouldBe pktLenIn withClue f"${simTime()} time: pktLenOut=${pktLenOut}%X should == pktLenIn=${pktLenIn}%X"
//        println(
//          f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should == workReqIdIn=${workReqIdIn}%X"
//        )
        workReqIdOut shouldBe workReqIdIn withClue f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should == workReqIdIn=${workReqIdIn}%X"
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

  def testFunc(hasNak: Boolean) = simCfg.doSim { dut =>
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

    // dut.io.sendWriteWorkCompAndAck must be always valid to wait for DMA response
    streamMasterDriverAlwaysValid(
      dut.io.sendWriteWorkCompAndAck,
      dut.clockDomain
    ) {
      val psn = psn4WorkCompQueue.dequeue()
      dut.io.qpAttr.epsn #= psn
      dut.io.sendWriteWorkCompAndAck.ack.bth.psn #= psn
      dut.io.sendWriteWorkCompAndAck.ackValid #= true
      if (hasNak) {
        dut.io.sendWriteWorkCompAndAck.ack.aeth.setAsInvReqNak()
      } else {
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

    streamMasterDriver(dut.io.dmaWriteResp.resp, dut.clockDomain) {
      val psn = psn4DmaWriteRespQueue.dequeue()
      dut.io.dmaWriteResp.resp.psn #= psn
    }
    if (hasNak) {
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.dmaWriteResp.resp.ready.toBoolean
      )
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
            dut.io.dupReadReqAndRstCacheData.cachedData.psnStart.toInt,
            dut.io.dupReadReqAndRstCacheData.cachedData.pa.toBigInt,
            dut.io.dupReadReqAndRstCacheData.cachedData.pktNum.toInt,
            dut.io.dupReadReqAndRstCacheData.cachedData.dlen.toLong
          )
        )

        assert(
          dut.io.dupReadReqAndRstCacheData.cachedData.duplicate.toBoolean,
          f"${simTime()} time: dut.io.dupReadReqAndRstCacheData.cachedData.duplicate=${dut.io.dupReadReqAndRstCacheData.cachedData.duplicate.toBoolean} should be true for duplicate read request"
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
//      dut.io.readAtomicRstCache.req.ready #= false
//      dut.io.readAtomicRstCache.resp.valid #= false

      RdmaDataPktSim.pktFragStreamMasterDriverAlwaysValid(
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

  def testFunc(isPartialRetry: Boolean) =
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
        dut.io.rxDupReadReqAndRstCacheData.cachedData.psnStart #= curPsn
        dut.io.rxDupReadReqAndRstCacheData.cachedData.opcode #= opcode.id
        val pktLen = WorkReqSim.randomDmaLength()
        dut.io.rxDupReadReqAndRstCacheData.cachedData.dlen #= pktLen
        val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
        dut.io.rxDupReadReqAndRstCacheData.cachedData.pktNum #= pktNum
        dut.io.rxDupReadReqAndRstCacheData.cachedData.duplicate #= true

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
          dut.io.rxDupReadReqAndRstCacheData.cachedData.psnStart.toInt
        val origReqLenBytes =
          dut.io.rxDupReadReqAndRstCacheData.cachedData.dlen.toLong
        val origPhysicalAddr =
          dut.io.rxDupReadReqAndRstCacheData.cachedData.pa.toBigInt
        val psnDiff = MiscUtils.psnDiff(retryStartPsn, origReqPsnStart)
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
//          L"${simTime()} time: psnDiff=${psnDiff}%X < origReqPktNum=${dut.io.rxDupReadReqAndRstCacheData.cachedData.pktNum.toInt}%X, retryReqPsn=${retryStartPsn}, retryPhysicalAddr=${retryPhysicalAddr} = origReqPsnStart=${origReqPsnStart} + retryDmaReadOffset=${dmaReadOffset}",
//        )
        inputQueue.enqueue(
          (
            retryStartPsn,
            retryPhysicalAddr,
            dmaLenBytes,
            origReqPsnStart,
            OpCode(dut.io.rxDupReadReqAndRstCacheData.cachedData.opcode.toInt),
            dut.io.rxDupReadReqAndRstCacheData.cachedData.va.toBigInt,
            dut.io.rxDupReadReqAndRstCacheData.cachedData.rkey.toLong
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
            dut.io.dupReadDmaReqAndRstCacheData.cachedData.psnStart.toInt,
            OpCode(dut.io.dupReadDmaReqAndRstCacheData.cachedData.opcode.toInt),
            dut.io.dupReadDmaReqAndRstCacheData.cachedData.va.toBigInt,
            dut.io.dupReadDmaReqAndRstCacheData.cachedData.rkey.toLong
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

  test("zero DMA length read response test") {
    simCfg.doSim { dut =>
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
        dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.psnStart #= psn
        dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.pktNum #= 0
        dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.dlen #= 0
        dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes #= 0
        dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.mty #= 0
        dut.io.readRstCacheDataAndDmaReadRespSegment.last #= true
      }
      onStreamFire(
        dut.io.readRstCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        inputPsnQueue.enqueue(
          dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.psnStart.toInt
        )
      }

      // Check DUT output
      streamSlaveRandomizer(dut.io.txReadResp.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txReadResp.pktFrag, dut.clockDomain) {
        outputPsnQueue.enqueue(dut.io.txReadResp.pktFrag.bth.psn.toInt)
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputPsnQueue,
        outputPsnQueue,
        MATCH_CNT
      )
    }
  }

  test("non-zero DMA length read response test") {
    simCfg.doSim { dut =>
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
          dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.psnStart #= psnStart
          dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.dlen #= totalLenBytes
          dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.pktNum #= pktNum
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
            dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.pktNum.toInt,
            dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.psnStart.toInt,
            dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes.toLong,
            dut.io.readRstCacheDataAndDmaReadRespSegment.last.toBoolean
          )
        )
      }

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

      MiscUtils.checkSendWriteReqReadResp(
        dut.clockDomain,
        inputDataQueue,
        outputDataQueue,
        busWidth
      )
    }
  }
}

class RqReadDmaRespHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512

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
//          f"${simTime()} time: the read request has zero DMA length, but dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.dlen=${dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.dlen.toLong}%X"
//        )
        assert(
          dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.dlen.toLong == 0,
          f"${simTime()} time: the read request has zero DMA length, but dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.dlen=${dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.dlen.toLong}%X"
        )

        val inputPsnStart = psnQueue.dequeue()
//        println(
//          f"${simTime()} time: output PSN io.readRstCacheDataAndDmaReadResp.resultCacheData.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt}%X not match input PSN io.readRstCacheData.psnStart=${inputPsnStart}%X"
//        )
        assert(
          dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt == inputPsnStart,
          f"${simTime()} time: output PSN io.readRstCacheDataAndDmaReadResp.resultCacheData.psnStart=${dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt}%X not match input PSN io.readRstCacheData.psnStart=${inputPsnStart}%X"
        )

        matchQueue.enqueue(inputPsnStart)
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("non-zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val cacheDataQueue = mutable.Queue[(PsnStart, PktNum, PktLen)]()
      val dmaRespQueue =
        mutable.Queue[(PktFragData, PsnStart, PktLen, FragLast)]()
      val outputQueue = mutable.Queue[
        (PktFragData, PsnStart, PsnStart, PktLen, PktLen, FragNum, FragLast)
      ]()
      val matchQueue = mutable.Queue[PsnStart]()

      val randSeed = scala.util.Random.nextInt()
      dut.io.rxQCtrl.stateErrFlush #= false

      // Input to DUT
      val (
        totalFragNumItr4CacheData,
        pktNumItr4CacheData,
        psnStartItr4CacheData,
        totalLenItr4CacheData
      ) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth, randSeed)
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
        cacheDataQueue.enqueue(
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
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth, randSeed)
      pktFragStreamMasterDriver(dut.io.dmaReadResp.resp, dut.clockDomain) {
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
        dmaRespQueue.enqueue(
          (
            dut.io.dmaReadResp.resp.data.toBigInt,
            dut.io.dmaReadResp.resp.psnStart.toInt,
            dut.io.dmaReadResp.resp.lenBytes.toLong,
            dut.io.dmaReadResp.resp.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(
        dut.io.readRstCacheDataAndDmaReadResp,
        dut.clockDomain
      )
      onStreamFire(dut.io.readRstCacheDataAndDmaReadResp, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.data.toBigInt,
            dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt,
            dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.psnStart.toInt,
            dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.dlen.toLong,
            dut.io.readRstCacheDataAndDmaReadResp.dmaReadResp.lenBytes.toLong,
            dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.pktNum.toInt,
            dut.io.readRstCacheDataAndDmaReadResp.last.toBoolean
          )
        )
      }

      // Check DUT output
      fork {
        while (true) {
          val (psnStartInCache, pktNumIn, respLenInCache) =
            MiscUtils.safeDeQueue(cacheDataQueue, dut.clockDomain)

          var isFragEnd = false
          do {
            val (dmaRespDataIn, psnStartInDmaResp, respLenInDmaResp, isLastIn) =
              MiscUtils.safeDeQueue(dmaRespQueue, dut.clockDomain)
            val (
              dmaRespDataOut,
              psnStartOutCache,
              psnStartOutDmaResp,
              respLenOutCache,
              respLenOutDmaResp,
              pktNumOut,
              isLastOut
            ) = MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)

//            println(
//              f"${simTime()} time: psnStartInCache=${psnStartInCache} == psnStartOutCache=${psnStartOutCache}, psnStartInDmaResp=${psnStartInDmaResp} == psnStartOutDmaResp=${psnStartOutDmaResp}, psnStartInCache=${psnStartInCache} == psnStartInDmaResp=${psnStartInDmaResp}"
//            )
            assert(
              psnStartInCache == psnStartOutCache &&
                psnStartInDmaResp == psnStartOutDmaResp &&
                psnStartInCache == psnStartInDmaResp,
              f"${simTime()} time: psnStartInCache=${psnStartInCache}%X == psnStartOutCache=${psnStartOutCache}%X, psnStartInDmaResp=${psnStartInDmaResp}%X == psnStartOutDmaResp=${psnStartOutDmaResp}%X, psnStartInCache=${psnStartInCache}%X == psnStartInDmaResp=${psnStartInDmaResp}%X"
            )

//          println(
//            f"${simTime()} time: output packet num=${dut.io.readRstCacheDataAndDmaReadResp.resultCacheData.pktNum.toInt} not match input packet num=${pktNumIn}%X"
//          )
            assert(
              pktNumIn == pktNumOut,
              f"${simTime()} time: output packet num=${pktNumOut} not match input packet num=${pktNumIn}%X"
            )

//            println(
//              f"${simTime()} time: respLenInDmaResp=${respLenInDmaResp}%X == respLenOutDmaResp=${respLenOutDmaResp}%X, respLenInCache=${respLenInCache}%X == respLenOutCache=${respLenOutCache}%X, respLenInDmaResp=${respLenInDmaResp}%X == respLenInCache=${respLenInCache}%X"
//            )
            assert(
              respLenOutDmaResp == respLenInDmaResp &&
                respLenOutCache == respLenInCache &&
                respLenInDmaResp == respLenInCache,
              f"${simTime()} time: respLenInDmaResp=${respLenInDmaResp}%X == respLenOutDmaResp=${respLenOutDmaResp}%X, respLenInCache=${respLenInCache}%X == respLenOutCache=${respLenOutCache}%X, respLenInDmaResp=${respLenInDmaResp}%X == respLenInCache=${respLenInCache}%X"
            )

//            println(
//              f"${simTime()} time: output response data io.readRstCacheDataAndDmaReadResp.dmaReadResp.data=${dmaRespDataOut}%X not match input response data io.dmaReadResp.resp.data=${dmaRespDataIn}%X"
//            )
            assert(
              dmaRespDataOut.toString(16) == dmaRespDataIn.toString(16),
              f"${simTime()} time: output response data io.readRstCacheDataAndDmaReadResp.dmaReadResp.data=${dmaRespDataOut}%X not match input response data io.dmaReadResp.resp.data=${dmaRespDataIn}%X"
            )

            assert(
              isLastOut == isLastIn,
              f"${simTime()} time: output dut.io.readRstCacheDataAndDmaReadResp.last=${isLastOut} not match input dut.io.dmaReadResp.resp.last=${isLastIn}"
            )

            matchQueue.enqueue(psnStartInDmaResp)
            isFragEnd = isLastOut
          } while (!isFragEnd)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}
