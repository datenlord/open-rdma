package rdma

import spinal.core._
import spinal.core.sim._

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

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
          assert(
            singlePktNum == dut.io.epsnInc.incVal.toInt,
            f"${simTime()} time: dut.io.epsnInc.incVal=${dut.io.epsnInc.incVal.toInt} should == singlePktNum=${singlePktNum}"
          )
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

  def errInputTestFunc(inputHasFatalNak: Boolean) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
//      val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue =
        mutable.Queue[
          (PSN, OpCode.Value, PktFragData, FragLast, HasNak, HasNakSeq)
        ]()
//      val rxWorkReqQueue = mutable.Queue[WorkReqId]()
      val outputQueue =
        mutable.Queue[
          (PSN, OpCode.Value, PktFragData, FragLast, HasNak, HasNakSeq)
        ]()
//      val dupReqQueue = mutable.Queue[(PSN, PktFragData, FragLast, HasNak, HasNakSeq)]()
//      val matchQueue = mutable.Queue[BoolField]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.flush #= false

      pktFragStreamMasterDriver(dut.io.rx.checkRst, dut.clockDomain) {
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
      } { (psn, _, fragLast, _, _, pktIdx, pktNum, internalData) =>
        val (_, workReqOpCode) = internalData
        // Only RC is supported
//        dut.io.rx.checkRst.pktFrag.bth.transport #= Transports.RC.id
        val opcode = WorkReqSim.assignOpCode(workReqOpCode, pktIdx, pktNum)
        dut.io.rx.checkRst.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.checkRst.pktFrag.bth.psn #= psn
        dut.io.rx.checkRst.last #= fragLast
        dut.io.rx.checkRst.hasNak #= true
        if (inputHasFatalNak) {
          dut.io.rx.checkRst.nakAeth.setAs(AckType.NAK_INV)
        } else {
          dut.io.rx.checkRst.nakAeth.setAs(AckType.NAK_SEQ)
        }
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
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
            if (inputHasFatalNak) AckTypeSim.isFatalNak(ackType)
            else AckTypeSim.isNakSeq(ackType)
          )
        )
      }

      // Input hasNak is true, no need RxWorkReq
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.rxWorkReq.ready.toBoolean
      )
//      dut.io.rxWorkReq.valid #= false

      if (inputHasFatalNak) {
        streamSlaveRandomizer(dut.io.tx.reqWithRxBuf, dut.clockDomain)
        onStreamFire(dut.io.tx.reqWithRxBuf, dut.clockDomain) {
          val ackType = AckTypeSim.decodeFromAeth(dut.io.rx.checkRst.nakAeth)
          outputQueue.enqueue(
            (
              dut.io.tx.reqWithRxBuf.pktFrag.bth.psn.toInt,
              OpCode(dut.io.tx.reqWithRxBuf.pktFrag.bth.opcodeFull.toInt),
              dut.io.tx.reqWithRxBuf.pktFrag.data.toBigInt,
              dut.io.tx.reqWithRxBuf.last.toBoolean,
              dut.io.tx.reqWithRxBuf.hasNak.toBoolean,
              AckTypeSim.isFatalNak(ackType)
            )
          )
        }

        MiscUtils.checkInputOutputQueues(
          dut.clockDomain,
          inputQueue,
          outputQueue,
          MATCH_CNT
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txDupReq.checkRst.valid.toBoolean
        )
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
      }
    }
  }

  def testFunc(isNormalCaseOrRnrCase: Boolean) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue =
        mutable.Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val rxWorkReqQueue = mutable.Queue[WorkReqId]()
      val outputQueue =
        mutable.Queue[
          (
              PSN,
              OpCode.Value,
              PktFragData,
              FragLast,
              RxBufValid,
              WorkReqId,
              HasNak
          )
        ]()
      val matchQueue = mutable.Queue[FragLast]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.flush #= false

      pktFragStreamMasterDriver(dut.io.rx.checkRst, dut.clockDomain) {
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
      } { (psn, _, fragLast, fragIdx, fragNum, pktIdx, pktNum, internalData) =>
        val (totalLenBytes, workReqOpCode) = internalData
        // Only RC is supported
//        dut.io.rx.checkRst.pktFrag.bth.transport #= Transports.RC.id
        val opcode = WorkReqSim.assignOpCode(workReqOpCode, pktIdx, pktNum)
        dut.io.rx.checkRst.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.checkRst.pktFrag.bth.psn #= psn
        dut.io.rx.checkRst.last #= fragLast

        RdmaDataPktSim.setMtyAndPadCnt(
          dut.io.rx.checkRst.pktFrag,
          fragIdx,
          fragNum,
          totalLenBytes.toLong,
          busWidth
        )
        dut.io.rx.checkRst.hasNak #= false
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx.checkRst, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        inputQueue.enqueue(
          (
            dut.io.rx.checkRst.pktFrag.bth.psn.toInt,
            OpCode(dut.io.rx.checkRst.pktFrag.bth.opcodeFull.toInt),
            dut.io.rx.checkRst.pktFrag.data.toBigInt,
            dut.io.rx.checkRst.last.toBoolean
          )
        )
      }

      if (isNormalCaseOrRnrCase) { // Normal case
        streamMasterDriverAlwaysValid(dut.io.rxWorkReq, dut.clockDomain) {
          // dut.io.rxWorkReq always valid so as to avoid RNR
        }
        onStreamFire(dut.io.rxWorkReq, dut.clockDomain) {
          rxWorkReqQueue.enqueue(dut.io.rxWorkReq.id.toBigInt)
        }
      } else { // RNR case
        dut.io.rxWorkReq.valid #= false
      }

      streamSlaveRandomizer(dut.io.tx.reqWithRxBuf, dut.clockDomain)
      onStreamFire(dut.io.tx.reqWithRxBuf, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.tx.reqWithRxBuf.pktFrag.bth.psn.toInt,
            OpCode(dut.io.tx.reqWithRxBuf.pktFrag.bth.opcodeFull.toInt),
            dut.io.tx.reqWithRxBuf.pktFrag.data.toBigInt,
            dut.io.tx.reqWithRxBuf.last.toBoolean,
            dut.io.tx.reqWithRxBuf.rxBufValid.toBoolean,
            dut.io.tx.reqWithRxBuf.rxBuf.id.toBigInt,
            dut.io.tx.reqWithRxBuf.hasNak.toBoolean
          )
        )
      }

      fork {
        while (true) {
          val (psnIn, opCodeIn, fragDataIn, fragLastIn) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)
          val (
            psnOut,
            opCodeOut,
            fragDataOut,
            fragLastOut,
            rxBufValidOut,
            workReqIdOut,
            hasNakOut
          ) =
            MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)

          assert(
            psnIn == psnOut,
            f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut}"
          )
          assert(
            opCodeIn == opCodeOut,
            f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut}"
          )
          assert(
            fragDataIn == fragDataOut,
            f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut}"
          )
          assert(
            fragLastIn == fragLastOut,
            f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut}"
          )
          if (opCodeOut.needRxBuf()) {
            assert(
              hasNakOut != isNormalCaseOrRnrCase,
              f"${simTime()} time: hasNakOut=${hasNakOut} should != isNormalCaseOrRnrCase=${isNormalCaseOrRnrCase}"
            )
          }
          if (isNormalCaseOrRnrCase) {
//            println(
//              f"${simTime()} time: rxBufValidOut=${rxBufValidOut} should be true when no RNR and opcode=${opCodeOut}, opcode.isSendReqPkt()=${opCodeOut
//                .isSendReqPkt()} or opcode.isWriteWithImmReqPkt()=${opCodeOut.isWriteWithImmReqPkt()}"
//            )
            assert(
              rxBufValidOut == opCodeOut.needRxBuf(),
              f"${simTime()} time: rxBufValidOut=${rxBufValidOut} should be true when no RNR and opcode=${opCodeOut}, opcode.isSendReqPkt()=${opCodeOut
                .isSendReqPkt()} or opcode.isWriteWithImmReqPkt()=${opCodeOut.isWriteWithImmReqPkt()}"
            )

            if (opCodeOut.isLastOrOnlyReqPkt() && fragLastOut) {
              val workReqIdIn =
                MiscUtils.safeDeQueue(rxWorkReqQueue, dut.clockDomain)
              assert(
                workReqIdIn == workReqIdOut,
                f"${simTime()} time: workReqIdIn=${workReqIdIn} should == workReqIdOut=${workReqIdOut}"
              )

              matchQueue.enqueue(fragLastOut)
            }
          } else {
            assert(
              rxBufValidOut == false,
              f"${simTime()} time: rxBufValidOut=${rxBufValidOut} should be false when RNR"
            )

            matchQueue.enqueue(fragLastOut)
          }
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("ReqRnrCheck normal case") {
    testFunc(isNormalCaseOrRnrCase = true)
  }

  test("ReqRnrCheck RNR case") {
    testFunc(isNormalCaseOrRnrCase = false)
  }

  test("ReqRnrCheck input has fatal NAK case") {
    errInputTestFunc(inputHasFatalNak = true)
  }

  test("ReqRnrCheck input has retry NAK case") {
    errInputTestFunc(inputHasFatalNak = false)
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
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[
        (
            PSN,
            OpCode.Value,
            AckReq,
            PktFragData,
            HasNak,
            PhysicalAddr,
            LRKey,
            PktLen,
            FragLast
        )
      ]()
      val outputQueue = mutable.Queue[
        (
            PSN,
            OpCode.Value,
            PktFragData,
            FragLast,
            RxBufValid,
            HasNak,
            PhysicalAddr,
            LRKey,
            PktLen
        )
      ]()
      val inputReadReqPsnRangeQueue = mutable.Queue[(PsnStart, PktLen)]()
      val inputPsnRangeQueue = mutable.Queue[(OpCode.Value, PSN)]()
      val outputPsnRangeQueue = mutable.Queue[(OpCode.Value, PsnStart, PSN)]()
      val matchQueue = mutable.Queue[FragLast]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.stateErrFlush #= false

      pktFragStreamMasterDriverAlwaysValid(
        dut.io.rx.reqWithRxBuf,
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
        if (WorkReqSim.isReadReq(workReqOpCode)) {
          inputReadReqPsnRangeQueue.enqueue((psnStart, pktNum.toLong))
        }
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
        dut.io.rx.reqWithRxBuf.pktFrag.bth.opcodeFull #= opcode.id
        dut.io.rx.reqWithRxBuf.pktFrag.bth.psn #= psn
        dut.io.rx.reqWithRxBuf.last #= fragLast

        if (opcode.hasReth() && fragIdx == 0) {
          val pktFragData = dut.io.rx.reqWithRxBuf.pktFrag.data.toBigInt
          dut.io.rx.reqWithRxBuf.pktFrag.data #= RethSim.setDlen(
            pktFragData,
            totalLenBytes.toLong,
            busWidth
          )
        }
        dut.io.rx.reqWithRxBuf.pktFrag.bth.ackreq #= opcode.isLastOrOnlyReqPkt()
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
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        val pktFragData = dut.io.rx.reqWithRxBuf.pktFrag.data.toBigInt
        val opcode = OpCode(dut.io.rx.reqWithRxBuf.pktFrag.bth.opcodeFull.toInt)
        val (addr, rkey, pktLen) = RethSim.extract(pktFragData, busWidth)
        inputQueue.enqueue(
          (
            dut.io.rx.reqWithRxBuf.pktFrag.bth.psn.toInt,
            opcode,
            dut.io.rx.reqWithRxBuf.pktFrag.bth.ackreq.toBoolean,
            pktFragData,
            dut.io.rx.reqWithRxBuf.hasNak.toBoolean,
            addr,
            rkey,
            pktLen,
            dut.io.rx.reqWithRxBuf.last.toBoolean
          )
        )
        if (
          dut.io.rx.reqWithRxBuf.pktFrag.bth.ackreq.toBoolean || opcode
            .isReadReqPkt() || opcode.isAtomicReqPkt()
        ) {
          inputPsnRangeQueue.enqueue(
            (opcode, dut.io.rx.reqWithRxBuf.pktFrag.bth.psn.toInt)
          )
        }
      }

      streamSlaveAlwaysReady(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain)
      onStreamFire(dut.io.tx.reqWithRxBufAndDmaInfo, dut.clockDomain) {
        val pktFragData = dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.data.toBigInt
        val (addr, rkey, pktLen) = RethSim.extract(pktFragData, busWidth)
        outputQueue.enqueue(
          (
            dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.psn.toInt,
            OpCode(
              dut.io.tx.reqWithRxBufAndDmaInfo.pktFrag.bth.opcodeFull.toInt
            ),
            pktFragData,
            dut.io.tx.reqWithRxBufAndDmaInfo.last.toBoolean,
            dut.io.tx.reqWithRxBufAndDmaInfo.rxBufValid.toBoolean,
            dut.io.tx.reqWithRxBufAndDmaInfo.hasNak.toBoolean,
            addr,
            rkey,
            pktLen
          )
        )
      }

      streamSlaveAlwaysReady(dut.io.rqOutPsnRangeFifoPush, dut.clockDomain)
      onStreamFire(dut.io.rqOutPsnRangeFifoPush, dut.clockDomain) {
        outputPsnRangeQueue.enqueue(
          (
            OpCode(dut.io.rqOutPsnRangeFifoPush.opcode.toInt),
            dut.io.rqOutPsnRangeFifoPush.start.toInt,
            dut.io.rqOutPsnRangeFifoPush.end.toInt
          )
        )
      }

      fork {
        while (true) {
          val (
            psnIn,
            opCodeIn,
            ackReqIn,
            fragDataIn,
            hasNakIn,
            _, // addrIn,
            _, // rkeyIn,
            _, // pktLenIn,
            fragLastIn
          ) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)

          val (
            psnOut,
            opCodeOut,
            fragDataOut,
            fragLastOut,
            rxBufValidOut,
//            workReqIdOut,
            hasNakOut,
            _, // addrOut,
            _, // rkeyOut,
            _ // pktLenOut
          ) =
            MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)

          assert(
            psnIn == psnOut,
            f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut}"
          )
          assert(
            opCodeIn == opCodeOut,
            f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut}"
          )
          assert(
            fragDataIn == fragDataOut,
            f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut}"
          )
          assert(
            fragLastIn == fragLastOut,
            f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut}"
          )
//          assert(hasNakOut == opCodeOut.needRxBuf())
          assert(
            hasNakIn == hasNakOut,
            f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut}"
          )

          if (hasNakIn) {
            assert(
              rxBufValidOut == false,
              f"${simTime()} time: rxBufValidOut=${rxBufValidOut} should be false when RNR"
            )
          } else {
//            println(
//              f"${simTime()} time: rxBufValidOut=${rxBufValidOut} should be true when no RNR and opcode=${opCodeOut}, opcode.isSendReqPkt()=${opCodeOut
//                .isSendReqPkt()} or opcode.isWriteWithImmReqPkt()=${opCodeOut.isWriteWithImmReqPkt()}"
//            )
            assert(
              rxBufValidOut == opCodeOut.needRxBuf(),
              f"${simTime()} time: rxBufValidOut=${rxBufValidOut} should be true when opcode=${opCodeOut}, opcode.needRxBuf()=${opCodeOut.needRxBuf()}"
            )
          }

          // Validate dut.io.rqOutPsnRangeFifoPush output
          if (
            hasNakIn || ackReqIn || opCodeOut.isReadReqPkt() ||
            opCodeOut.isAtomicReqPkt()
          ) {
            val (opCodePsnRangeIn, psnRangeStartIn) =
              MiscUtils.safeDeQueue(inputPsnRangeQueue, dut.clockDomain)
            val (opCodePsnRangeOut, psnRangeStartOut, psnRangeEndOut) =
              MiscUtils.safeDeQueue(outputPsnRangeQueue, dut.clockDomain)
            assert(
              opCodePsnRangeIn == opCodePsnRangeOut,
              f"${simTime()} time: opCodePsnRangeIn=${opCodePsnRangeIn} should == opCodePsnRangeOut=${opCodePsnRangeOut}"
            )
            assert(
              psnRangeStartIn == psnRangeStartOut,
              f"${simTime()} time: psnRangeStartIn=${psnRangeStartIn} should == psnRangeStartOut=${psnRangeStartOut}"
            )
            if (opCodePsnRangeOut.isReadReqPkt()) {
              val (readReqPsnRangeStartIn, readReqPktNumIn) = MiscUtils
                .safeDeQueue(inputReadReqPsnRangeQueue, dut.clockDomain)
              assert(
                psnRangeStartIn == readReqPsnRangeStartIn,
                f"${simTime()} time: psnRangeStartIn=${psnRangeStartIn} should == readReqPsnRangeStartIn=${readReqPsnRangeStartIn}"
              )
              assert(
                readReqPsnRangeStartIn + readReqPktNumIn == psnRangeEndOut,
                f"${simTime()} time: readReqPsnRangeStartIn=${readReqPsnRangeStartIn} + readReqPktNumIn=${readReqPktNumIn} should == psnRangeEndOut=${psnRangeEndOut}"
              )
            } else {
              assert(
                psnRangeStartIn == psnRangeEndOut,
                f"${simTime()} time: psnRangeStartIn=${psnRangeStartIn} should == psnRangeEndOut=${psnRangeEndOut}"
              )
            }
          }

          matchQueue.enqueue(fragLastOut)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
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

      pktFragStreamMasterDriverAlwaysValid(
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

          assert(
            psnIn == psnOut,
            f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut}"
          )
          assert(
            opCodeIn == opCodeOut,
            f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut}"
          )
          assert(
            fragDataIn == fragDataOut,
            f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut}"
          )
          assert(
            fragLastIn == fragLastOut,
            f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut}"
          )

          if (addrCacheQuerySuccess || inputHasNak) {
            assert(
              hasNakIn == hasNakOut,
              f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when addrCacheQuerySuccess=${addrCacheQuerySuccess} or inputHasNak=${inputHasNak}"
            )
          } else {
            assert(
              hasNakIn == false && hasNakOut == true,
              f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when addrCacheQuerySuccess=${addrCacheQuerySuccess} and inputHasNak=${inputHasNak}"
            )
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
              assert(
                psnFirstOrOnly == psnOut,
                f"${simTime()} time: psnFirstOrOnly=${psnFirstOrOnly} should == psnOut=${psnOut}"
              )
              assert(
                paAddrCacheResp == paOut,
                f"${simTime()} time: paAddrCacheResp=${paAddrCacheResp} should == paOut=${paOut}"
              )
              if (addrCacheQuerySuccess) {
                assert(
                  keyValid && sizeValid && accessValid,
                  f"${simTime()} time: keyValid=${keyValid} && sizeValid=${sizeValid} && accessValid=${accessValid} should be true, when addrCacheQuerySuccess=${addrCacheQuerySuccess}"
                )
              } else {
                assert(
                  !(keyValid && sizeValid && accessValid),
                  f"${simTime()} time: !(keyValid=${keyValid} && sizeValid=${sizeValid} && accessValid=${accessValid}) should be true, when addrCacheQuerySuccess=${addrCacheQuerySuccess}"
                )
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

          assert(
            psnIn == psnOut,
            f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut}"
          )
          assert(
            opCodeIn == opCodeOut,
            f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut}"
          )
          assert(
            fragDataIn == fragDataOut,
            f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut}"
          )
          assert(
            fragLastIn == fragLastOut,
            f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut}"
          )

          if (fragLastOut) { // Length check error is at the last fragment
            if (inputHasNak) {
              assert(
                hasNakIn == hasNakOut,
                f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} or inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
              )
            } else if (hasLenCheckErr) {
              assert(
                hasNakIn == false && hasNakOut == true,
                f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
              )
            } else {
              assert(
                hasNakIn == false && hasNakOut == false,
                f"${simTime()} time: hasNakIn=${hasNakIn} should == hasNakOut=${hasNakOut} when hasLenCheckErr=${hasLenCheckErr} and inputHasNak=${inputHasNak} for PSN=${psnOut}%X and fragLastOut=${fragLastOut}"
              )
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
      val workCompQueue = mutable.Queue[
        (
            SpinalEnumElement[WorkCompOpCode.type],
            SpinalEnumElement[WorkCompStatus.type],
            PSN,
            SpinalEnumElement[AckType.type]
        )
      ]()
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

      streamSlaveRandomizer(dut.io.sendWriteWorkCompErrAndNak, dut.clockDomain)
      onStreamFire(dut.io.sendWriteWorkCompErrAndNak, dut.clockDomain) {
        workCompQueue.enqueue(
          (
            dut.io.sendWriteWorkCompErrAndNak.workComp.opcode.toEnum,
            dut.io.sendWriteWorkCompErrAndNak.workComp.status.toEnum,
            dut.io.sendWriteWorkCompErrAndNak.ack.bth.psn.toInt,
            AckTypeSim
              .decodeFromAeth(dut.io.sendWriteWorkCompErrAndNak.ack.aeth)
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
          MiscUtils.checkConditionAlways(dut.clockDomain)(
            !dut.io.sendWriteWorkCompErrAndNak.valid.toBoolean
          )
        }
      } else {
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txErrResp.valid.toBoolean
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.sendWriteWorkCompErrAndNak.valid.toBoolean
        )
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

          assert(
            inputHasNak == hasNakIn,
            f"${simTime()} time: inputHasNak=${inputHasNak} should == hasNakIn=${hasNakIn}"
          )
          if (inputHasNak) {
            if (fragLastIn) { // Length check error is at the last fragment
//              println(
//                f"${simTime()} time: opCodeIn=${opCodeIn} matchQueue.size=${matchQueue.size}, outputErrRespQueue.size=${outputErrRespQueue.size}, workCompQueue.size=${workCompQueue.size}"
//              )

              val (nakPsnOut, nakOpCodeOut, nakTypeOut) =
                MiscUtils.safeDeQueue(outputErrRespQueue, dut.clockDomain)

              assert(
                nakOpCodeOut == OpCode.ACKNOWLEDGE,
                f"${simTime()} time: invalid error response opcode=${nakOpCodeOut} for PSN=${nakPsnOut}"
              )
              assert(
                nakTypeOut == inputNakType,
                f"${simTime()} time: ackTypeOut=${nakTypeOut} should == inputNakType=${inputNakType}"
              )
              assert(
                psnIn == nakPsnOut,
                f"${simTime()} time: psnIn=${psnIn} should == nakPsnOut=${nakPsnOut} when inputHasNak=${inputHasNak} and fragLastIn=${fragLastIn} for PSN=${psnIn}%X"
              )

              if (
                opCodeIn.needRxBuf() &&
                !AckTypeSim.isRetryNak(inputNakType)
              ) {
                val (
                  workCompOpCode,
                  workCompStatus,
                  workCompNakPsnOut,
                  workCompNakTypeOut
                ) =
                  MiscUtils.safeDeQueue(workCompQueue, dut.clockDomain)

                WorkCompSim.rqCheckWorkCompOpCode(opCodeIn, workCompOpCode)
                WorkCompSim.rqCheckWorkCompStatus(inputNakType, workCompStatus)
                assert(
                  nakTypeOut == workCompNakTypeOut,
                  f"${simTime()} time: nakTypeOut=${nakTypeOut} should == workCompNakTypeOut=${workCompNakTypeOut} for PSN=${psnIn}, opcode=${opCodeIn}"
                )
                assert(
                  nakPsnOut == workCompNakPsnOut,
                  f"${simTime()} time: nakPsnOut=${nakPsnOut} should == workCompNakPsnOut=${workCompNakPsnOut} when inputHasNak=${inputHasNak} and fragLastIn=${fragLastIn} for PSN=${psnIn}%X, opcode=${opCodeIn}"
                )
              }

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

            assert(
              psnIn == psnOut,
              f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut} for opCodeIn=${opCodeIn}, opCodeOut=${opCodeOut}"
            )
            assert(
              opCodeIn == opCodeOut,
              f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut} for PSN=${psnOut}"
            )
            assert(
              fragDataIn == fragDataOut,
              f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut} for opcode=${opCodeOut}, PSN=${psnOut}"
            )
            assert(
              fragLastIn == fragLastOut,
              f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut} for opcode=${opCodeOut}, PSN=${psnOut}"
            )

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

  test("RqDmaReqInitiatorAndNakGen normal case") {
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
            PhysicalAddr,
            PktLen,
            WorkReqId,
            FragLast
        )
      ]()
      val outputSendWriteQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val dmaReadReqQueue = mutable.Queue[(PSN, PhysicalAddr, PktLen)]()
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

          dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= false
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
            dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen.toLong,
            dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.id.toBigInt,
            dut.io.rx.reqWithRxBufAndDmaInfo.last.toBoolean
          )
        )
      }

//      streamSlaveRandomizer(dut.io.readDmaReq.req, dut.clockDomain)
//      onStreamFire(dut.io.readDmaReq.req, dut.clockDomain) {
//        dmaReadReqQueue.enqueue(
//          (
//            dut.io.readDmaReq.req.psnStart.toInt,
//            dut.io.readDmaReq.req.addr.toBigInt,
//            dut.io.readDmaReq.req.lenBytes.toLong
//          )
//        )
//      }

      streamSlaveRandomizer(dut.io.sendWriteDmaReq.req, dut.clockDomain)
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
//            hasNakIn,
            paIn,
            dmaLenIn,
            workReqIdIn,
            fragLastIn
          ) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)

//          assert(
//            inputHasNak == hasNakIn,
//            f"${simTime()} time: inputHasNak=${inputHasNak} should == hasNakIn=${hasNakIn}"
//          )
//          if (inputHasNak) {
//            if (fragLastIn) { // Length check error is at the last fragment
////              println(
////                f"${simTime()} time: opCodeIn=${opCodeIn} matchQueue.size=${matchQueue.size}, outputErrRespQueue.size=${outputErrRespQueue.size}, workCompQueue.size=${workCompQueue.size}"
////              )
//
//              val (nakPsnOut, nakOpCodeOut, nakTypeOut) =
//                MiscUtils.safeDeQueue(outputErrRespQueue, dut.clockDomain)
//
//              assert(
//                nakOpCodeOut == OpCode.ACKNOWLEDGE,
//                f"${simTime()} time: invalid error response opcode=${nakOpCodeOut} for PSN=${nakPsnOut}"
//              )
//              assert(
//                nakTypeOut == inputNakType,
//                f"${simTime()} time: ackTypeOut=${nakTypeOut} should == inputNakType=${inputNakType}"
//              )
//              assert(
//                psnIn == nakPsnOut,
//                f"${simTime()} time: psnIn=${psnIn} should == nakPsnOut=${nakPsnOut} when inputHasNak=${inputHasNak} and fragLastIn=${fragLastIn} for PSN=${psnIn}%X"
//              )
//
//              if (
//                opCodeIn.needRxBuf() &&
//                !AckTypeSim.isRetryNak(inputNakType)
//              ) {
//                val (
//                  workCompOpCode,
//                  workCompStatus,
//                  workCompNakPsnOut,
//                  workCompNakTypeOut
//                ) =
//                  MiscUtils.safeDeQueue(workCompQueue, dut.clockDomain)
//                WorkCompSim.rqCheckWorkCompOpCode(opCodeIn, workCompOpCode)
//                assert(
//                  workCompStatus == WorkCompStatus.REM_INV_REQ_ERR,
//                  f"${simTime()} time: workCompStatus=${workCompStatus} should == WorkCompStatus.REM_INV_REQ_ERR=${WorkCompStatus.REM_INV_REQ_ERR} for PSN=${psnIn}%X, opcode=${opCodeIn}"
//                )
//                assert(
//                  nakTypeOut == workCompNakTypeOut,
//                  f"${simTime()} time: nakTypeOut=${nakTypeOut} should == workCompNakTypeOut=${workCompNakTypeOut} for PSN=${psnIn}, opcode=${opCodeIn}"
//                )
//                assert(
//                  nakPsnOut == workCompNakPsnOut,
//                  f"${simTime()} time: nakPsnOut=${nakPsnOut} should == workCompNakPsnOut=${workCompNakPsnOut} when inputHasNak=${inputHasNak} and fragLastIn=${fragLastIn} for PSN=${psnIn}%X, opcode=${opCodeIn}"
//                )
//              }
//
//              matchQueue.enqueue(fragLastIn)
//            }
//          } else {
          assert(
//                opCodeIn.isReadReqPkt() ||
//                opCodeIn.isAtomicReqPkt() ||
            opCodeIn.isSendReqPkt() ||
              opCodeIn.isWriteReqPkt(),
            f"${simTime()} time: invalid opCodeIn=${opCodeIn}, should be send/write requests"
          )
          val (psnOut, opCodeOut, fragDataOut, fragLastOut) =
//              if (opCodeIn.isSendReqPkt() || opCodeIn.isWriteReqPkt()) {
            MiscUtils.safeDeQueue(outputSendWriteQueue, dut.clockDomain)
//              } else {
//                MiscUtils.safeDeQueue(outputReadAtomicQueue, dut.clockDomain)
//              }

          assert(
            psnIn == psnOut,
            f"${simTime()} time: psnIn=${psnIn} should == psnOut=${psnOut} for opCodeIn=${opCodeIn}, opCodeOut=${opCodeOut}"
          )
          assert(
            opCodeIn == opCodeOut,
            f"${simTime()} time: opCodeIn=${opCodeIn} should == opCodeOut=${opCodeOut} for PSN=${psnOut}"
          )
          assert(
            fragDataIn == fragDataOut,
            f"${simTime()} time: fragDataIn=${fragDataIn} should == fragDataOut=${fragDataOut} for opcode=${opCodeOut}, PSN=${psnOut}"
          )
          assert(
            fragLastIn == fragLastOut,
            f"${simTime()} time: fragLastIn=${fragLastIn} should == fragLastOut=${fragLastOut} for opcode=${opCodeOut}, PSN=${psnOut}"
          )

          if (opCodeIn.isSendReqPkt() || opCodeIn.isWriteReqPkt()) {
            val (
              dmaWritePsnOut,
              dmaWriteAddrOut,
              dmaWriteWorkReqIdOut,
              dmaWriteDataOut,
              dmaWriteFragLastOut
            ) = MiscUtils.safeDeQueue(dmaWriteReqQueue, dut.clockDomain)

            assert(
              psnIn == dmaWritePsnOut,
              f"${simTime()} time: psnIn=${psnIn} should == dmaWritePsnOut=${dmaWritePsnOut} for opcode=${opCodeIn}"
            )
            assert(
              paIn == dmaWriteAddrOut,
              f"${simTime()} time: paIn=${paIn} should == dmaWriteAddrOut=${dmaWriteAddrOut} for PSN=${psnIn}, opcode=${opCodeIn}"
            )
            assert(
              workReqIdIn == dmaWriteWorkReqIdOut,
              f"${simTime()} time: workReqIdIn=${workReqIdIn} should == dmaWriteWorkReqIdOut=${dmaWriteWorkReqIdOut} for PSN=${psnIn}, opcode=${opCodeIn}"
            )
            assert(
              fragDataIn == dmaWriteDataOut,
              f"${simTime()} time: fragDataIn=${fragDataIn} should == dmaWriteDataOut=${dmaWriteDataOut} for PSN=${psnIn}, opcode=${opCodeIn}"
            )
            assert(
              fragLastIn == dmaWriteFragLastOut,
              f"${simTime()} time: fragLastIn=${fragLastIn} should == dmaWriteFragLastOut=${dmaWriteFragLastOut} for PSN=${psnIn}, opcode=${opCodeIn}"
            )
          } else {
            val (dmaReadPsnOut, dmaReadAddrOut, dmaReadLenOut) =
              MiscUtils.safeDeQueue(dmaReadReqQueue, dut.clockDomain)

            assert(
              psnIn == dmaReadPsnOut,
              f"${simTime()} time: psnIn=${psnIn} should == dmaReadPsnOut=${dmaReadPsnOut} for opcode=${opCodeIn}"
            )
            assert(
              paIn == dmaReadAddrOut,
              f"${simTime()} time: paIn=${paIn} should == dmaReadAddrOut=${dmaReadAddrOut} for PSN=${psnIn}, opcode=${opCodeIn}"
            )
            assert(
              dmaLenIn == dmaReadLenOut,
              f"${simTime()} time: dmaLenIn=${dmaLenIn} should == dmaReadLenOut=${dmaReadLenOut} for PSN=${psnIn}, opcode=${opCodeIn}"
            )
//            }
          }

          matchQueue.enqueue(fragLastIn)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
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
        //          OpCode(dut.io.rx.checkRst.pktFrag.bth.opcodeFull.toInt)
        val isLastFrag = dut.io.rx.checkRst.last.toBoolean
        if (isLastFrag) {
          inputQueue.enqueue(
            (
              fixedEPsn,
              // dut.io.rx.checkRst.pktFrag.bth.psn.toInt,
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

  test("DupReadDmaReqBuilder full retry case") {
    testFunc(isPartialRetry = false)
  }

  test("DupReadDmaReqBuilder partial retry case") {
    testFunc(isPartialRetry = true)
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
}
//2551955095132704159
//2551955096995189151
