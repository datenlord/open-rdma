package rdma

import spinal.core._
import spinal.core.sim._

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

import ConstantSettings._
import AethSim._
import OpCodeSim._
import PsnSim._
//import RdmaConstants._
import StreamSimUtil._
import RdmaTypeReDef._

class ReqCommCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
//    .withConfig(
//      new SpinalConfig(
//        defaultClockDomainFrequency = FixedFrequency(FREQUENCY MHz)
//      )
//    )
    .compile(new ReqCommCheck(busWidth))

  def testFunc(isNormalCaseOrDupCase: Boolean) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[(PSN, PktFragData, FragLast)]()
      val outputQueue = mutable.Queue[(PSN, PktFragData, FragLast)]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.rxQCtrl.flush #= false
      dut.io.readAtomicRstCacheOccupancy #= 0

      pktFragStreamMasterDriver(dut.io.rx.pktFrag, dut.clockDomain) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val totalLenBytes = totalLenItr.next()
        val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
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
//        if (fragIdx == fragNum - 1) {
//          val finalFragValidBytes = totalLenBytes % mtyWidth
//          val leftShiftAmt = mtyWidth - finalFragValidBytes
//          dut.io.rx.pktFrag.mty #=
//            setAllBits(finalFragValidBytes) << leftShiftAmt
//          dut.io.rx.pktFrag.bth.padCnt #= (PAD_COUNT_FULL - (totalLenBytes % PAD_COUNT_FULL)) % PAD_COUNT_FULL
//        } else {
//          dut.io.rx.pktFrag.mty #= setAllBits(mtyWidth)
//          dut.io.rx.pktFrag.bth.padCnt #= 0
//        }
        RdmaDataPktSim.setMtyAndPadCnt(
          dut.io.rx.pktFrag,
          fragIdx,
          fragNum,
          totalLenBytes.toLong,
          busWidth
        )
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        inputQueue.enqueue(
          (
            dut.io.rx.pktFrag.bth.psn.toInt,
            dut.io.rx.pktFrag.data.toBigInt,
            dut.io.rx.pktFrag.last.toBoolean
          )
        )
        if (isNormalCaseOrDupCase && dut.io.rx.pktFrag.last.toBoolean) {
          assert(
            dut.io.epsnInc.inc.toBoolean,
            f"${simTime()} time: dut.io.epsnInc.inc=${dut.io.epsnInc.inc.toBoolean} should be true when dut.io.rx last fire"
          )
        }
      }

      if (isNormalCaseOrDupCase) { // Normal request case
        streamSlaveRandomizer(dut.io.tx.checkRst, dut.clockDomain)
        onStreamFire(dut.io.tx.checkRst, dut.clockDomain) {
          outputQueue.enqueue(
            (
              dut.io.tx.checkRst.pktFrag.bth.psn.toInt,
              dut.io.tx.checkRst.pktFrag.data.toBigInt,
              dut.io.tx.checkRst.last.toBoolean
            )
          )
          assert(
            !dut.io.tx.checkRst.hasNak.toBoolean,
            f"${simTime()} time: dut.io.tx.checkRst.hasNak=${dut.io.tx.checkRst.hasNak.toBoolean} should be false"
          )
        }
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.txDupReq.pktFrag.valid.toBoolean
        )
      } else { // Duplicate request case
        streamSlaveRandomizer(dut.io.txDupReq.pktFrag, dut.clockDomain)
        onStreamFire(dut.io.txDupReq.pktFrag, dut.clockDomain) {
          outputQueue.enqueue(
            (
              dut.io.txDupReq.pktFrag.bth.psn.toInt,
              dut.io.txDupReq.pktFrag.data.toBigInt,
              dut.io.txDupReq.pktFrag.last.toBoolean
            )
          )
        }
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.tx.checkRst.valid.toBoolean
        )
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

  test("ReqCommCheck normal case test") {
    testFunc(isNormalCaseOrDupCase = true)
  }

  test("ReqCommCheck duplicate request case test") {
    testFunc(isNormalCaseOrDupCase = false)
  }
}

class ReqRnrCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
//    .withConfig(
//      new SpinalConfig(
//        defaultClockDomainFrequency = FixedFrequency(FREQUENCY MHz)
//      )
//    )
    .compile(new ReqRnrCheck(busWidth))

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
//        if (fragIdx == fragNum - 1) {
//          val finalFragValidBytes = totalLenBytes % mtyWidth
//          val leftShiftAmt = mtyWidth - finalFragValidBytes
//          dut.io.rx.checkRst.pktFrag.mty #= setAllBits(finalFragValidBytes) << leftShiftAmt
//          dut.io.rx.checkRst.pktFrag.bth.padCnt #= (PAD_COUNT_FULL - (totalLenBytes % PAD_COUNT_FULL)) % PAD_COUNT_FULL
//        } else {
//          dut.io.rx.checkRst.pktFrag.mty #= setAllBits(mtyWidth)
//          dut.io.rx.checkRst.pktFrag.bth.padCnt #= 0
//        }
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

  test("ReqRnrCheck input hasNak case") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
//      val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue =
        mutable.Queue[
          (PSN, OpCode.Value, PktFragData, FragLast, HasNak, RxBufValid)
        ]()
//      val rxWorkReqQueue = mutable.Queue[WorkReqId]()
      val outputQueue =
        mutable.Queue[
          (PSN, OpCode.Value, PktFragData, FragLast, HasNak, RxBufValid)
        ]()
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
            dut.io.rx.checkRst.last.toBoolean,
            dut.io.rx.checkRst.hasNak.toBoolean,
            !dut.io.rx.checkRst.hasNak.toBoolean // To Match dut.io.tx.reqWithRxBuf.rxBufValid
          )
        )
      }

      // Input hasNak is true, no need RxWorkReq
      dut.io.rxWorkReq.valid #= false

      streamSlaveRandomizer(dut.io.tx.reqWithRxBuf, dut.clockDomain)
      onStreamFire(dut.io.tx.reqWithRxBuf, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.tx.reqWithRxBuf.pktFrag.bth.psn.toInt,
            OpCode(dut.io.tx.reqWithRxBuf.pktFrag.bth.opcodeFull.toInt),
            dut.io.tx.reqWithRxBuf.pktFrag.data.toBigInt,
            dut.io.tx.reqWithRxBuf.last.toBoolean,
            dut.io.tx.reqWithRxBuf.hasNak.toBoolean,
            dut.io.tx.reqWithRxBuf.rxBufValid.toBoolean
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
  }
}

class ReqDmaInfoExtractorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
//    .withConfig(
//      new SpinalConfig(
//        defaultClockDomainFrequency = FixedFrequency(FREQUENCY MHz)
//      )
//    )
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
            Addr,
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
            Addr,
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
//    .withConfig(
//      new SpinalConfig(
//        defaultClockDomainFrequency = FixedFrequency(FREQUENCY MHz)
//      )
//    )
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
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast, Addr, HasNak)]()
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
    .withConfig(new SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new PktLenCheck(busWidth))

  def testFunc(inputHasNak: Boolean, hasLenCheckErr: Boolean) = {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)
//      val pmtuLenBytes = SendWriteReqReadRespInputGen.pmtuLenBytes(pmtuLen)

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
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.isSendReqPkt()
          dut.io.rx.reqWithRxBufAndDmaInfo.rxBuf.lenBytes #= payloadLenBytes
          dut.io.rx.reqWithRxBufAndDmaInfo.dmaInfo.dlen #= payloadLenBytes

          dut.io.rx.reqWithRxBufAndDmaInfo.hasNak #= inputHasNak
          if (inputHasNak) {
            dut.io.rx.reqWithRxBufAndDmaInfo.nakAeth.setAsRnrNak()
            dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= false
          } else {
            dut.io.rx.reqWithRxBufAndDmaInfo.rxBufValid #= opcode.needRxBuf()
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
//            dut.io.tx.reqWithRxBufAndDmaInfo.dmaInfo.pa.toBigInt,
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
//          if (!inputHasNak) {
//            if (opCodeOut.isFirstOrOnlyReqPkt() && fragLastOut) {
//              val (
//                psnFirstOrOnly,
//                keyValid,
//                sizeValid,
//                accessValid,
//                paAddrCacheResp
//              ) =
//                MiscUtils.safeDeQueue(addrCacheRespQueue, dut.clockDomain)
//              assert(
//                psnFirstOrOnly == psnOut,
//                f"${simTime()} time: psnFirstOrOnly=${psnFirstOrOnly} should == psnOut=${psnOut}"
//              )
//              assert(
//                paAddrCacheResp == paOut,
//                f"${simTime()} time: paAddrCacheResp=${paAddrCacheResp} should == paOut=${paOut}"
//              )
//              if (addrCacheQuerySuccess) {
//                assert(
//                  keyValid && sizeValid && accessValid,
//                  f"${simTime()} time: keyValid=${keyValid} && sizeValid=${sizeValid} && accessValid=${accessValid} should be true, when addrCacheQuerySuccess=${addrCacheQuerySuccess}"
//                )
//              } else {
//                assert(
//                  !(keyValid && sizeValid && accessValid),
//                  f"${simTime()} time: !(keyValid=${keyValid} && sizeValid=${sizeValid} && accessValid=${accessValid}) should be true, when addrCacheQuerySuccess=${addrCacheQuerySuccess}"
//                )
//              }
//            }
//          }
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
}
