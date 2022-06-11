package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import org.scalatest.AppendedClues._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import scala.collection.mutable

import ConstantSettings._
import OpCodeSim._
import PsnSim._
import RdmaTypeReDef._
import StreamSimUtil._
import WorkReqSim._
import SimSettings._

class WorkReqValidatorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile {
      val dut = new WorkReqValidator
      dut.workReqValidator.errorStream.valid.simPublic()
      dut.workReqValidator.errorStream.ready.simPublic()
      dut.workReqValidator.addrCacheReadResp.valid.simPublic()
      dut.workReqValidator.addrCacheReadResp.ready.simPublic()
      dut
    }

  def testFunc(
      normalOrErrorCase: Boolean,
      addrCacheQueryErrOrFlushErr: Boolean
  ) = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    dut.io.qpAttr.pmtu #= pmtuLen.id
    dut.io.txQCtrl.retry #= false
    dut.io.txQCtrl.wrongStateFlush #= !normalOrErrorCase && !addrCacheQueryErrOrFlushErr

    val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
      SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)
    val input4WorkReqCacheQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val outputWorkReqCacheQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val inputWorkCompErrQueue = mutable.Queue[
      (
          SpinalEnumElement[WorkCompOpCode.type],
          SpinalEnumElement[WorkCompStatus.type],
          PktLen,
          WorkReqId
      )
    ]()
    val outputWorkCompErrQueue = mutable.Queue[
      (
          SpinalEnumElement[WorkCompOpCode.type],
          SpinalEnumElement[WorkCompStatus.type],
          PktLen,
          WorkReqId
      )
    ]()
//    val input4SqOutPsnRangeQueue =
//      mutable.Queue[(SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)]()
//    val outputSqOutPsnRangeQueue =
//      mutable.Queue[(SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)]()
    val inputPktNumQueue = mutable.Queue[PktNum]()
    val outputPktNumQueue = mutable.Queue[PktNum]()

    streamMasterDriverAlwaysValid(dut.io.workReq, dut.clockDomain) {
      val _ = totalFragNumItr.next()
      val _ = pktNumItr.next()
      val pktLen = totalLenItr.next()
      val psnStart = psnStartItr.next()
      dut.io.qpAttr.npsn #= psnStart
      val pktNum = MiscUtils.computePktNum(pktLen.toLong, pmtuLen)
//      val psnEnd = psnStart +% (pktNum - 1)

      val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
      dut.io.workReq.opcode #= workReqOpCode
      dut.io.workReq.lenBytes #= pktLen
      val noFlags = 0
      dut.io.workReq.flags.flagBits #= noFlags

//      input4SqOutPsnRangeQueue.enqueue(
//        (
//          workReqOpCode,
//          psnStart,
//          psnEnd
//        )
//      )
      input4WorkReqCacheQueue.enqueue(
        (
          workReqOpCode,
          psnStart,
          pktLen.toLong,
          dut.io.workReq.id.toBigInt
        )
      )
      inputPktNumQueue.enqueue(pktNum)
    }
    onStreamFire(dut.io.workReq, dut.clockDomain) {
      if (!normalOrErrorCase) { // Error case
        val workCompStatus = if (addrCacheQueryErrOrFlushErr) {
          // AddrCache query response error
          WorkCompStatus.LOC_LEN_ERR
        } else { // Flush error
          WorkCompStatus.WR_FLUSH_ERR
        }
        inputWorkCompErrQueue.enqueue(
          (
            WorkCompSim.fromSqWorkReqOpCode(
              dut.io.workReq.opcode.toEnum
            ),
            workCompStatus,
            dut.io.workReq.lenBytes.toLong,
            dut.io.workReq.id.toBigInt
          )
        )
      }
    }

    MiscUtils.checkCondChangeOnceAndHoldAfterwards(
      dut.clockDomain,
      cond = dut.io.workReq.valid.toBoolean && dut.io.workReq.ready.toBoolean,
      clue =
        f"${simTime()} time: dut.io.workReq.fire=${dut.io.workReq.valid.toBoolean && dut.io.workReq.ready.toBoolean} should be true always"
    )
    if (normalOrErrorCase) { // Normal case
      AddrCacheSim.reqStreamFixedDelayAndRespSuccess(
        dut.io.addrCacheRead,
        dut.clockDomain,
        fixedRespDelayCycles = ADDR_CACHE_QUERY_DELAY_CYCLES
      )

      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.workCompErr.valid.toBoolean
      )
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.errNotifier.pulse.toBoolean
      )

      streamSlaveAlwaysReady(dut.io.workReqToCache, dut.clockDomain)
      onStreamFire(dut.io.workReqToCache, dut.clockDomain) {
        outputWorkReqCacheQueue.enqueue(
          (
            dut.io.workReqToCache.workReq.opcode.toEnum,
            dut.io.workReqToCache.psnStart.toInt,
            dut.io.workReqToCache.workReq.lenBytes.toLong,
            dut.io.workReqToCache.workReq.id.toBigInt
          )
        )
      }
      MiscUtils.checkCondChangeOnceAndHoldAfterwards(
        dut.clockDomain,
        cond = dut.io.workReqToCache.valid.toBoolean,
        clue =
          f"${simTime()} time: dut.io.workReqToCache.valid=${dut.io.workReqToCache.valid.toBoolean} should be true always when normalOrErrorCase=${normalOrErrorCase}"
      )
      MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
        cond = !dut.io.workCompErr.valid.toBoolean,
        clue =
          f"${simTime()} time: dut.io.workCompErr.valid=${dut.io.workCompErr.valid.toBoolean} should be false always when normalOrErrorCase=${normalOrErrorCase}, addrCacheQueryErrOrFlushErr=${addrCacheQueryErrOrFlushErr}"
      )
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.io.npsnInc.inc.toBoolean) {
            outputPktNumQueue.enqueue(dut.io.npsnInc.incVal.toInt)
          }
        }
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        input4WorkReqCacheQueue,
        outputWorkReqCacheQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputPktNumQueue,
        outputPktNumQueue,
        MATCH_CNT
      )
    } else { // Error case
      val _ = if (addrCacheQueryErrOrFlushErr) { // addrCacheRespQueue
        // AddrCache query response error
        AddrCacheSim.reqStreamFixedDelayAndRespFailure(
          dut.io.addrCacheRead,
          dut.clockDomain,
          fixedRespDelayCycles = ADDR_CACHE_QUERY_DELAY_CYCLES
        )
      } else { // Error flush
        MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
          cond = !dut.workReqValidator.addrCacheReadResp.ready.toBoolean,
          clue =
            f"${simTime()} time: dut.workReqValidator.addrCacheReadResp.ready=${dut.workReqValidator.addrCacheReadResp.ready.toBoolean} should be false always when addrCacheQueryErrOrFlushErr=${addrCacheQueryErrOrFlushErr}"
        )
      }

      dut.io.errNotifier.pulse.toBoolean shouldBe
        (dut.workReqValidator.errorStream.valid.toBoolean && dut.workReqValidator.errorStream.ready.toBoolean) withClue
        f"${simTime()} time: dut.io.errNotifier.pulse=${dut.io.errNotifier.pulse.toBoolean} should == (dut.workReqValidator.errorStream.valid=${dut.workReqValidator.errorStream.valid.toBoolean} && dut.workReqValidator.errorStream.ready=${dut.workReqValidator.errorStream.ready.toBoolean})"

      MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
        cond = !dut.io.workReqToCache.valid.toBoolean,
        clue =
          f"${simTime()} time: dut.io.workReqToCache.valid=${dut.io.workReqToCache.valid.toBoolean} should be false always when normalOrErrorCase=${normalOrErrorCase}"
      )
      if (!addrCacheQueryErrOrFlushErr) { // Error flush
        MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
          cond = !dut.io.addrCacheRead.req.valid.toBoolean,
          clue =
            f"${simTime()} time: dut.io.addrCacheRead.req.valid=${dut.io.addrCacheRead.req.valid.toBoolean} should be false always when normalOrErrorCase=${normalOrErrorCase} and normalOrErrorCase=${normalOrErrorCase}"
        )
//        MiscUtils.checkConditionAlways(dut.clockDomain)(
//          !dut.io.sqOutPsnRangeFifoPush.valid.toBoolean
//        )
      }

      streamSlaveAlwaysReady(dut.io.workCompErr, dut.clockDomain)
      onStreamFire(dut.io.workCompErr, dut.clockDomain) {
        outputWorkCompErrQueue.enqueue(
          (
            dut.io.workCompErr.opcode.toEnum,
            dut.io.workCompErr.status.toEnum,
            dut.io.workCompErr.lenBytes.toLong,
            dut.io.workCompErr.id.toBigInt
          )
        )
      }

//      dut.clockDomain.waitSamplingWhere(dut.io.workCompErr.valid.toBoolean)
//      fork {
//        while(true) {
//          println(f"${simTime()} time: dut.io.workCompErr.valid=${dut.io.workCompErr.valid.toBoolean}")
//          assert(dut.io.workCompErr.valid.toBoolean)
//          dut.clockDomain.waitSampling()
//        }
//      }
      MiscUtils.checkCondChangeOnceAndHoldAfterwards(
        dut.clockDomain,
        cond = dut.io.workCompErr.valid.toBoolean,
        clue =
          f"${simTime()} time: dut.io.workCompErr.valid=${dut.io.workCompErr.valid.toBoolean} should be true always when normalOrErrorCase=${normalOrErrorCase}, addrCacheQueryErrOrFlushErr=${addrCacheQueryErrOrFlushErr}"
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputWorkCompErrQueue,
        outputWorkCompErrQueue,
        MATCH_CNT
      )
    }
  }

  test("WorkReqValidator input normal case") {
    testFunc(normalOrErrorCase = true, addrCacheQueryErrOrFlushErr = true)
  }

  test("WorkReqValidator AddrCache query response error case") {
    testFunc(normalOrErrorCase = false, addrCacheQueryErrOrFlushErr = true)
  }

  test("WorkReqValidator error state flush case") {
    testFunc(normalOrErrorCase = false, addrCacheQueryErrOrFlushErr = false)
  }
}

class WorkReqCacheAndOutPsnRangeHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new WorkReqCacheAndOutPsnRangeHandler)

  def testFunc(isRetryWorkReq: Boolean): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

    dut.io.txQCtrl.wrongStateFlush #= false
    dut.io.txQCtrl.retry #= isRetryWorkReq

    val inputSendWriteWorkReqOutQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val inputWorkReq4CachePushQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()

    val inputReadWorkReqOutQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PSN, PktLen, VirtualAddr, LRKey)
    ]()
    val inputAtomicWorkReqOutQueue = mutable.Queue[
      (
          SpinalEnumElement[WorkReqOpCode.type],
          PSN,
          VirtualAddr,
          LRKey,
          AtomicComp,
          AtomicSwap
      )
    ]()
    val outputWorkReqCachePushQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val outputSendWriteWorkReqOutQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val outputReadWorkReqOutQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PSN, PktLen, VirtualAddr, LRKey)
    ]()
    val outputAtomicWorkReqOutQueue = mutable.Queue[
      (
          SpinalEnumElement[WorkReqOpCode.type],
          PSN,
          VirtualAddr,
          LRKey,
          AtomicComp,
          AtomicSwap
      )
    ]()

    val input4SqOutPsnRangeQueue =
      mutable.Queue[(SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)]()
    val outputSqOutPsnRangeQueue =
      mutable.Queue[(SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)]()

    val inputWorkReqStream = if (isRetryWorkReq) {
      dut.io.normalWorkReq.valid #= false
      dut.io.retryWorkReq
    } else {
      dut.io.retryWorkReq.valid #= false
      dut.io.normalWorkReq
    }
    streamMasterDriver(inputWorkReqStream, dut.clockDomain) {
      val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
      inputWorkReqStream.workReq.opcode #= workReqOpCode
      val workReqLen = inputWorkReqStream.workReq.lenBytes.toLong
      val pktNum = MiscUtils.computePktNum(workReqLen, pmtuLen)
      inputWorkReqStream.pktNum #= pktNum
      val noFlags = 0
      inputWorkReqStream.workReq.flags.flagBits #= noFlags
    }
    onStreamFire(inputWorkReqStream, dut.clockDomain) {
      val workReqOpCode = inputWorkReqStream.workReq.opcode.toEnum
      val psnStart = inputWorkReqStream.psnStart.toInt
      val workReqId = inputWorkReqStream.workReq.id.toBigInt
      val workReqLen = inputWorkReqStream.workReq.lenBytes.toLong
      val pktNum = inputWorkReqStream.pktNum.toInt
      val psnEnd = psnStart +% (pktNum - 1)
//      println(f"${simTime()} time: psnStart=${psnStart}%X, pktNum=${pktNum}%X, psnEnd=${psnEnd}%X")

      inputWorkReq4CachePushQueue.enqueue(
        (
          workReqOpCode,
          psnStart,
          workReqLen,
          workReqId
        )
      )
      input4SqOutPsnRangeQueue.enqueue(
        (
          workReqOpCode,
          psnStart,
          psnEnd
        )
      )
      if (workReqOpCode.isReadReq()) {
        inputReadWorkReqOutQueue.enqueue(
          (
            workReqOpCode,
            psnStart,
            workReqLen,
            inputWorkReqStream.workReq.raddr.toBigInt,
            inputWorkReqStream.workReq.rkey.toLong
          )
        )
      } else if (workReqOpCode.isAtomicReq()) {
        inputAtomicWorkReqOutQueue.enqueue(
          (
            workReqOpCode,
            psnStart,
            inputWorkReqStream.workReq.raddr.toBigInt,
            inputWorkReqStream.workReq.rkey.toLong,
            inputWorkReqStream.workReq.comp.toBigInt,
            inputWorkReqStream.workReq.swap.toBigInt
          )
        )
      } else {
        inputSendWriteWorkReqOutQueue.enqueue(
          (
            workReqOpCode,
            psnStart,
            workReqLen,
            workReqId
          )
        )
      }
    }

    streamSlaveRandomizer(dut.io.workReqCachePush, dut.clockDomain)
    onStreamFire(dut.io.workReqCachePush, dut.clockDomain) {
      outputWorkReqCachePushQueue.enqueue(
        (
          dut.io.workReqCachePush.workReq.opcode.toEnum,
          dut.io.workReqCachePush.psnStart.toInt,
          dut.io.workReqCachePush.workReq.lenBytes.toLong,
          dut.io.workReqCachePush.workReq.id.toBigInt
        )
      )
    }
    streamSlaveRandomizer(dut.io.sendWriteWorkReqOut, dut.clockDomain)
    onStreamFire(dut.io.sendWriteWorkReqOut, dut.clockDomain) {
      outputSendWriteWorkReqOutQueue.enqueue(
        (
          dut.io.sendWriteWorkReqOut.workReq.opcode.toEnum,
          dut.io.sendWriteWorkReqOut.psnStart.toInt,
          dut.io.sendWriteWorkReqOut.workReq.lenBytes.toLong,
          dut.io.sendWriteWorkReqOut.workReq.id.toBigInt
        )
      )
    }

    streamSlaveRandomizer(dut.io.readWorkReqOut, dut.clockDomain)
    onStreamFire(dut.io.readWorkReqOut, dut.clockDomain) {
      outputReadWorkReqOutQueue.enqueue(
        (
          dut.io.readWorkReqOut.workReq.opcode.toEnum,
          dut.io.readWorkReqOut.psnStart.toInt,
          dut.io.readWorkReqOut.workReq.lenBytes.toLong,
          dut.io.readWorkReqOut.workReq.raddr.toBigInt,
          dut.io.readWorkReqOut.workReq.rkey.toLong
        )
      )
    }
    streamSlaveRandomizer(dut.io.atomicWorkReqOut, dut.clockDomain)
    onStreamFire(dut.io.atomicWorkReqOut, dut.clockDomain) {
      outputAtomicWorkReqOutQueue.enqueue(
        (
          dut.io.atomicWorkReqOut.workReq.opcode.toEnum,
          dut.io.atomicWorkReqOut.psnStart.toInt,
          dut.io.atomicWorkReqOut.workReq.raddr.toBigInt,
          dut.io.atomicWorkReqOut.workReq.rkey.toLong,
          dut.io.atomicWorkReqOut.workReq.comp.toBigInt,
          dut.io.atomicWorkReqOut.workReq.swap.toBigInt
        )
      )
    }
    streamSlaveRandomizer(dut.io.sqOutPsnRangeFifoPush, dut.clockDomain)
    onStreamFire(dut.io.sqOutPsnRangeFifoPush, dut.clockDomain) {
      outputSqOutPsnRangeQueue.enqueue(
        (
          dut.io.sqOutPsnRangeFifoPush.workReqOpCode.toEnum,
          dut.io.sqOutPsnRangeFifoPush.start.toInt,
          dut.io.sqOutPsnRangeFifoPush.end.toInt
        )
      )
    }

    MiscUtils.checkSignalWhen(
      dut.clockDomain,
      when =
        inputWorkReqStream.valid.toBoolean && inputWorkReqStream.ready.toBoolean && isRetryWorkReq,
      signal = dut.io.retryFlushDone.toBoolean,
      clue =
        f"${simTime()} time: dut.io.retryFlushDone=${dut.io.retryFlushDone.toBoolean} should be true when inputWorkReqStream.fire=${inputWorkReqStream.valid.toBoolean && inputWorkReqStream.ready.toBoolean} and isRetryWorkReq=${isRetryWorkReq}"
    )
    MiscUtils.checkSignalWhen(
      dut.clockDomain,
      when = isRetryWorkReq,
      signal = !dut.io.workReqCachePush.valid.toBoolean,
      clue =
        f"${simTime()} time: dut.io.workReqCachePush.valid=${dut.io.workReqCachePush.valid.toBoolean} should be false when isRetryWorkReq=${isRetryWorkReq}"
    )

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      input4SqOutPsnRangeQueue,
      outputSqOutPsnRangeQueue,
      MATCH_CNT
    )
    if (!isRetryWorkReq) {
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputWorkReq4CachePushQueue,
        outputWorkReqCachePushQueue,
        MATCH_CNT
      )
    }
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputSendWriteWorkReqOutQueue,
      outputSendWriteWorkReqOutQueue,
      MATCH_CNT
    )
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputReadWorkReqOutQueue,
      outputReadWorkReqOutQueue,
      MATCH_CNT
    )
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputAtomicWorkReqOutQueue,
      outputAtomicWorkReqOutQueue,
      MATCH_CNT
    )
  }

  test("WorkReqCachePushAndReadAtomicHandler normal case") {
    testFunc(isRetryWorkReq = false)
  }

  test("WorkReqCachePushAndReadAtomicHandler retry case") {
    testFunc(isRetryWorkReq = true)
  }
}

class SqDmaReadRespHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val maxFragNum = 37

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new SqDmaReadRespHandler(busWidth))

  test("zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      val psnQueue = mutable.Queue[PSN]()
      val matchQueue = mutable.Queue[PSN]()

      dut.io.txQCtrl.retryFlush #= false
      dut.io.txQCtrl.wrongStateFlush #= false

      // Input to DUT
      streamMasterDriverAlwaysValid(dut.io.cachedWorkReq, dut.clockDomain) {
        dut.io.cachedWorkReq.workReq.lenBytes #= 0
      }
      onStreamFire(dut.io.cachedWorkReq, dut.clockDomain) {
        psnQueue.enqueue(dut.io.cachedWorkReq.psnStart.toInt)
      }

      // Check DUT output
      MiscUtils.checkConditionAlwaysHold(dut.clockDomain)(
        !dut.io.dmaReadResp.resp.ready.toBoolean,
        f"${simTime()} time: dut.io.dmaReadResp.resp.ready=${dut.io.dmaReadResp.resp.ready.toBoolean} should be false for zero length read response"
      )
      streamSlaveAlwaysReady(
        dut.io.cachedWorkReqAndDmaReadResp,
        dut.clockDomain
      )
      onStreamFire(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain) {
        //        println(
        //            f"${simTime()} time: the read request has zero DMA length, but dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes=${dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes.toLong}%X"
        //        )

        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes.toLong shouldBe 0 withClue
          f"${simTime()} time: the read request has zero DMA length, but dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes=${dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes.toLong}%X"

        val inputPsnStart = psnQueue.dequeue()
        //        println(
        //            f"${simTime()} time: output PSN io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart=${dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt}%X not match input PSN io.cachedWorkReq.psnStart=${inputPsnStart}%X"
        //        )

        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt shouldBe inputPsnStart withClue
          f"${simTime()} time: output PSN io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart=${dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt}%X not match input PSN io.cachedWorkReq.psnStart=${inputPsnStart}%X"

        matchQueue.enqueue(inputPsnStart)
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("non-zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      val workReqMetaDataQueue = mutable.Queue[(PsnStart, PktNum, PktLen)]()
      val dmaRespMetaDataQueue =
        mutable.Queue[(PsnStart, PktLen, FragNum, PktNum)]()
      val expectedOutputQueue = mutable.Queue[
        (PktFragData, PsnStart, PktLen, PktNum, FragLast)
      ]()
      val outputQueue = mutable.Queue[
        (PktFragData, PsnStart, PktLen, PktNum, FragLast)
      ]()

      dut.io.txQCtrl.retryFlush #= false
      dut.io.txQCtrl.wrongStateFlush #= false

      fork {
        val (totalFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
          SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

        while (true) {
          dut.clockDomain.waitSamplingWhere(dmaRespMetaDataQueue.isEmpty)

          for (_ <- 0 until MAX_PENDING_READ_ATOMIC_REQ_NUM) {
            val totalFragNum = totalFragNumItr.next()
            val pktNum = pktNumItr.next()
            val psnStart = psnStartItr.next()
            val payloadLenBytes = payloadLenItr.next()

            workReqMetaDataQueue.enqueue(
              (psnStart, pktNum, payloadLenBytes.toLong)
            )
            dmaRespMetaDataQueue.enqueue(
              (psnStart, payloadLenBytes.toLong, totalFragNum, pktNum)
            )
          }
        }
      }

      DmaReadRespSim.pktFragStreamMasterDriverAlwaysValid(
        dut.io.dmaReadResp.resp,
        dut.clockDomain,
        getDmaReadRespPktDataFunc = (r: DmaReadResp) => r,
        segmentRespByPmtu = false
      ) {
        val (psnStart, payloadLenBytes, totalFragNum, pktNum) =
          MiscUtils.safeDeQueue(dmaRespMetaDataQueue, dut.clockDomain)

        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, payloadLenBytes)
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
          expectedOutputQueue.enqueue(
            (
              dut.io.dmaReadResp.resp.data.toBigInt,
              psnStart,
              payloadLenBytes,
              pktNum,
              fragLast
            )
          )
      }

      streamMasterPayloadFromQueueNoRandomDelay(
        dut.io.cachedWorkReq,
        dut.clockDomain,
        workReqMetaDataQueue,
        payloadAssignFunc = (
            cachedWorkReq: CachedWorkReq,
            payloadData: (PsnStart, PktNum, PktLen)
        ) => {
          val (
            psnStart,
            pktNum,
            payloadLenBytes
          ) = payloadData
          cachedWorkReq.psnStart #= psnStart
          cachedWorkReq.pktNum #= pktNum
          cachedWorkReq.workReq.lenBytes #= payloadLenBytes

          val reqValid = true
          reqValid
        }
      )

      streamSlaveAlwaysReady(
        dut.io.cachedWorkReqAndDmaReadResp,
        dut.clockDomain
      )
      onStreamFire(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain) {
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt shouldBe
          dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.psnStart.toInt
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes.toLong shouldBe
          dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.lenBytes.toLong
        outputQueue.enqueue(
          (
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.data.toBigInt,
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.psnStart.toInt,
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.lenBytes.toLong,
            dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.pktNum.toInt,
            dut.io.cachedWorkReqAndDmaReadResp.last.toBoolean
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
        cond = dut.io.cachedWorkReqAndDmaReadResp.valid.toBoolean,
        clue =
          f"${simTime()} time: dut.io.cachedWorkReqAndDmaReadResp.valid=${dut.io.cachedWorkReqAndDmaReadResp.valid.toBoolean} should be true always"
      )

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        expectedOutputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }
}

class SqOutTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512
  val maxFragNum = 37

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new SqOut(busWidth))

  def insertToOutPsnRangeQueue(
      payloadFragNumItr: PayloadFragNumItr,
      pktNumItr: PktNumItr,
      psnStartItr: PsnStartItr,
      payloadLenItr: PayloadLenItr,
      inputSendReqMetaDataQueue: mutable.Queue[(PSN, OpCode.Value, FragLast)],
      inputWriteReqMetaDataQueue: mutable.Queue[(PSN, OpCode.Value, FragLast)],
      inputReadReqMetaDataQueue: mutable.Queue[(PSN, OpCode.Value)],
      inputAtomicReqMetaDataQueue: mutable.Queue[(PSN, OpCode.Value)],
      outPsnRangeQueue: mutable.Queue[
        (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)
      ],
      inputOutPsnQueue: mutable.Queue[PSN]
  ) =
    for (_ <- 0 until MAX_PENDING_REQ_NUM) {
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

      isSendReq || isWriteReq || isReadReq || isAtomicReq shouldBe true withClue
        f"${simTime()} time: invalid WR opcode=${workReqOpCode}, must be send/write/read/atomic"

      outPsnRangeQueue.enqueue((workReqOpCode, psnStart, psnEnd))

      if (isSendReq || isWriteReq) {
        for (pktIdx <- 0 until pktNum) {
          val psn = psnStart +% pktIdx
          inputOutPsnQueue.enqueue(psn)
          val sendWriteReqOpCode =
            WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx, pktNum)
          val pktFragNum = RdmaDataPktSim.computePktFragNum(
            pmtuLen,
            busWidth,
            sendWriteReqOpCode,
            payloadLenBytes.toLong,
            pktIdx,
            pktNum
          )
//          println(
//            f"${simTime()} time: psn=${psn}%X, opcode=${sendWriteReqOpCode}, workReqOpCode=${workReqOpCode}, psnStart=${psnStart}%X, psnEnd=${psnEnd}%X, pktNum=${pktNum}, isLastFrag=${isLastFrag}"
//          )
          for (fragIdx <- 0 until pktFragNum) {
            val isLastFrag = fragIdx == pktFragNum - 1
            if (isSendReq) {
              inputSendReqMetaDataQueue.enqueue(
                (
                  psn,
                  sendWriteReqOpCode,
                  isLastFrag
                )
              )
            } else {
              inputWriteReqMetaDataQueue.enqueue(
                (
                  psn,
                  sendWriteReqOpCode,
                  isLastFrag
                )
              )
            }
          }
        }
      } else if (isAtomicReq) {
        inputOutPsnQueue.enqueue(psnEnd)
        val atomicReqOpCode =
          WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx = 0, pktNum = 1)
        inputAtomicReqMetaDataQueue.enqueue(
          (
            psnEnd,
            atomicReqOpCode
          )
        )
      } else {
        inputOutPsnQueue.enqueue(psnEnd)
        inputReadReqMetaDataQueue.enqueue(
          (
            psnEnd,
            OpCode.RDMA_READ_REQUEST
          )
        )
      }
    }

  def testFunc(): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputSendReqMetaDataQueue =
        mutable.Queue[(PSN, OpCode.Value, FragLast)]()
      val inputWriteReqMetaDataQueue =
        mutable.Queue[(PSN, OpCode.Value, FragLast)]()
      val inputReadReqMetaDataQueue =
        mutable.Queue[(PSN, OpCode.Value)]()
      val inputAtomicReqMetaDataQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outPsnRangeQueue = mutable
        .Queue[(SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)]()

      val inputOutPsnQueue = mutable.Queue[PSN]()
      val outputOutPsnQueue = mutable.Queue[PSN]()

      val inputSendReqQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val inputWriteReqQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val inputReadReqQueue =
        mutable.Queue[(PSN, OpCode.Value, VirtualAddr, LRKey, PktLen)]()
      val inputAtomicReqQueue =
        mutable.Queue[
          (PSN, OpCode.Value, VirtualAddr, LRKey, AtomicSwap, AtomicComp)
        ]()

      val outputSendReqQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputWriteReqQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputReadWorkReqOutQueue =
        mutable.Queue[(PSN, OpCode.Value, VirtualAddr, LRKey, PktLen)]()
      val outputAtomicWorkReqOutQueue =
        mutable.Queue[
          (PSN, OpCode.Value, VirtualAddr, LRKey, AtomicSwap, AtomicComp)
        ]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.retryFlush #= false
//      dut.io.txQCtrl.retry #= false

//      // Disable normal requests or duplicate requests
//      if (normalOrDupReq) { // Normal requests
//        dut.io.rxSendReqRetry.pktFrag.valid #= false
//        dut.io.rxWriteReqRetry.pktFrag.valid #= false
//        dut.io.rxReadReqRetry.valid #= false
//        dut.io.rxAtomicReqRetry.valid #= false
//      } else { // Duplicate requests
//        dut.io.outPsnRangeFifoPush.valid #= false

      dut.io.rxSendReq.pktFrag.valid #= false
      dut.io.rxWriteReq.pktFrag.valid #= false
      dut.io.rxReadReq.valid #= false
      dut.io.rxAtomicReq.valid #= false
//      }

      // io.outPsnRangeFifoPush must always be valid when output normal responses
      streamMasterPayloadFromQueueNoRandomDelay(
        dut.io.outPsnRangeFifoPush,
        dut.clockDomain,
        outPsnRangeQueue,
        payloadAssignFunc = (
            reqPsnRange: ReqPsnRange,
            payloadData: (
                SpinalEnumElement[WorkReqOpCode.type],
                PsnStart,
                PsnEnd
            )
        ) => {
          val (workReqOpCode, psnStart, psnEnd) = payloadData
          reqPsnRange.workReqOpCode #= workReqOpCode
          reqPsnRange.start #= psnStart
          reqPsnRange.end #= psnEnd
          reqPsnRange.isRetryWorkReq #= false
          dut.io.qpAttr.npsn #= psnEnd

          val reqValid = true
          reqValid
        }
      )
      fork {
        insertToOutPsnRangeQueue(
          payloadFragNumItr,
          pktNumItr,
          psnStartItr,
          payloadLenItr,
          inputSendReqMetaDataQueue,
          inputWriteReqMetaDataQueue,
          inputReadReqMetaDataQueue,
          inputAtomicReqMetaDataQueue,
          outPsnRangeQueue,
          inputOutPsnQueue
        )

        while (true) {
          dut.clockDomain.waitSamplingWhere(
            inputSendReqMetaDataQueue.isEmpty && inputWriteReqMetaDataQueue.isEmpty &&
              inputReadReqMetaDataQueue.isEmpty && inputAtomicReqMetaDataQueue.isEmpty
          )
          /*
          waitUntil(
            inputSendReqMetaDataQueue.isEmpty || inputWriteReqMetaDataQueue.isEmpty ||
              inputReadReqMetaDataQueue.isEmpty || inputAtomicReqMetaDataQueue.isEmpty ||
              outPsnRangeQueue.isEmpty || inputOutPsnQueue.isEmpty
          )
           */
          insertToOutPsnRangeQueue(
            payloadFragNumItr,
            pktNumItr,
            psnStartItr,
            payloadLenItr,
            inputSendReqMetaDataQueue,
            inputWriteReqMetaDataQueue,
            inputReadReqMetaDataQueue,
            inputAtomicReqMetaDataQueue,
            outPsnRangeQueue,
            inputOutPsnQueue
          )
        }
      }

      // Either send or write requests
      val sendReqIn = dut.io.rxSendReq
      val writeReqIn = dut.io.rxWriteReq
      val sendWriteReqIn = Seq(
        (sendReqIn, inputSendReqMetaDataQueue, inputSendReqQueue),
        (writeReqIn, inputWriteReqMetaDataQueue, inputWriteReqQueue)
      )
      for ((reqIn, inputMetaDataQueue, inputReqQueue) <- sendWriteReqIn) {
        streamMasterPayloadFromQueueNoRandomDelay(
          reqIn.pktFrag,
          dut.clockDomain,
          inputMetaDataQueue,
          payloadAssignFunc = (
              sendWriteReq: Fragment[RdmaDataPkt],
              payloadData: (PSN, OpCode.Value, FragLast)
          ) => {
            val (psnEnd, opcode, isLastFrag) = payloadData
            sendWriteReq.bth.psn #= psnEnd
            sendWriteReq.bth.opcodeFull #= opcode.id
            sendWriteReq.last #= isLastFrag

            val reqValid = true
            reqValid
          }
        )
        onStreamFire(reqIn.pktFrag, dut.clockDomain) {
          val psn = reqIn.pktFrag.bth.psn.toInt
          val opcode = OpCode(reqIn.pktFrag.bth.opcodeFull.toInt)
          inputReqQueue.enqueue(
            (
              psn,
              opcode,
              reqIn.pktFrag.data.toBigInt,
              reqIn.pktFrag.last.toBoolean
            )
          )
//        println(
//          f"${simTime()} time: sendWriteReqOrErrRespIn has opcode=${opcode}, PSN=${psn}, when normalOrDupReq=${normalOrDupReq} and hasErrResp=${hasErrResp}"
//        )
        }
      }
      val readReqIn = dut.io.rxReadReq
      streamMasterPayloadFromQueueNoRandomDelay(
        readReqIn,
        dut.clockDomain,
        inputReadReqMetaDataQueue,
        payloadAssignFunc =
          (readReq: ReadReq, payloadData: (PSN, OpCode.Value)) => {
            val (psnEnd, opcode) = payloadData
            readReq.bth.psn #= psnEnd
            readReq.bth.opcodeFull #= opcode.id

            val reqValid = true
            reqValid
          }
      )
      onStreamFire(readReqIn, dut.clockDomain) {
        val psn = readReqIn.bth.psn.toInt
        val opcode = OpCode(readReqIn.bth.opcodeFull.toInt)
        inputReadReqQueue.enqueue(
          (
            psn,
            opcode,
            readReqIn.reth.va.toBigInt,
            readReqIn.reth.rkey.toLong,
            readReqIn.reth.dlen.toLong
          )
        )
//        println(
//          f"${simTime()} time: readRespIn has opcode=${opcode}, PSN=${psn}, when normalOrDupReq=${normalOrDupReq}"
//        )
      }

      val atomicReqIn = dut.io.rxAtomicReq
      streamMasterPayloadFromQueueNoRandomDelay(
        atomicReqIn,
        dut.clockDomain,
        inputAtomicReqMetaDataQueue,
        payloadAssignFunc =
          (atomicReq: AtomicReq, payloadData: (PSN, OpCode.Value)) => {
            val (psnEnd, opcode) = payloadData
            atomicReq.bth.psn #= psnEnd
            atomicReq.bth.opcodeFull #= opcode.id

            val reqValid = true
            reqValid
          }
      )
      onStreamFire(atomicReqIn, dut.clockDomain) {
        val psn = atomicReqIn.bth.psn.toInt
        val opcode = OpCode(atomicReqIn.bth.opcodeFull.toInt)
        inputAtomicReqQueue.enqueue(
          (
            psn,
            opcode,
            atomicReqIn.atomicEth.va.toBigInt,
            atomicReqIn.atomicEth.rkey.toLong,
            atomicReqIn.atomicEth.swap.toBigInt,
            atomicReqIn.atomicEth.comp.toBigInt
          )
        )
//        println(
//          f"${simTime()} time: atomicRespIn has opcode=${opcode}, PSN=${psn}, rsvd=${atomicRespIn.aeth.rsvd.toInt}%X, code=${atomicRespIn.aeth.code.toInt}%X, value=${atomicRespIn.aeth.value.toInt}%X, msn=${atomicRespIn.aeth.msn.toInt}%X, orig=${atomicRespIn.atomicAckEth.orig.toBigInt}%X, when normalOrDupReq=${normalOrDupReq}"
//        )
      }

      var prePsn = -1
      streamSlaveAlwaysReady(dut.io.tx.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.tx.pktFrag, dut.clockDomain) {
        val psn = dut.io.tx.pktFrag.bth.psn.toInt
        val opcode = OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt)
        val pktFragData = dut.io.tx.pktFrag.data.toBigInt
        val isLastFrag = dut.io.tx.pktFrag.last.toBoolean

        if (prePsn >= 0) { // Duplicate request might not in PSN order
          val curPsn = dut.io.qpAttr.epsn.toInt
          assert(
            PsnSim.psnCmp(prePsn, psn, curPsn) <= 0,
            f"${simTime()} time: prePsn=${prePsn} should < PSN=${psn} in PSN order, curPsn=dut.io.qpAttr.epsn=${curPsn}"
          )
        }
        prePsn = psn

        if (opcode.isReadReqPkt()) {
          val (readAddr, rmtKey, pktLen) =
            RethSim.extract(pktFragData, busWidth)
          outputReadWorkReqOutQueue.enqueue(
            (psn, opcode, readAddr, rmtKey, pktLen)
          )
        } else if (opcode.isAtomicReqPkt()) {
          val (atomicAddr, rmtKey, atomicSwap, atomicComp) =
            AtomicEthSim.extract(pktFragData, busWidth)
          outputAtomicWorkReqOutQueue.enqueue(
            (psn, opcode, atomicAddr, rmtKey, atomicSwap, atomicComp)
          )
        } else if (opcode.isSendReqPkt()) {
          outputSendReqQueue.enqueue((psn, opcode, pktFragData, isLastFrag))
        } else if (opcode.isWriteReqPkt()) {
          outputWriteReqQueue.enqueue((psn, opcode, pktFragData, isLastFrag))
        }
//        println(
//          f"${simTime()} time: dut.io.tx has opcode=${opcode}, PSN=${psn}, isLastFrag=${isLastFrag}, pktFragData=${pktFragData}%X"
//        )
      }
      MiscUtils.checkCondChangeOnceAndHoldAfterwards(
        dut.clockDomain,
        cond = dut.io.tx.pktFrag.valid.toBoolean,
        clue =
          f"${simTime()} time: dut.io.tx.pktFrag.valid=${dut.io.tx.pktFrag.valid.toBoolean} should be true always"
      )
      fork {
        while (true) {
//            println(f"${simTime()} time: inputSendWriteReqOrErrRespQueue.size=${inputSendWriteReqOrErrRespQueue.size}")
//            println(f"${simTime()} time: inputAtomicReqQueue.size=${inputAtomicReqQueue.size}")
//            println(f"${simTime()} time: inputReadReqQueue.size=${inputReadReqQueue.size}")
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
//      MiscUtils.checkConditionAlways(dut.clockDomain)(
//        dut.io.outPsnRangeFifoPush.valid.toBoolean
//      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputReadReqQueue,
        outputReadWorkReqOutQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputAtomicReqQueue,
        outputAtomicWorkReqOutQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputSendReqQueue,
        outputSendReqQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputWriteReqQueue,
        outputWriteReqQueue,
        MATCH_CNT
      )
    }

  test("SqOut normal request only case") {
    testFunc()
  }
}
