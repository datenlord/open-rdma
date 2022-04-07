package rdma

import spinal.core._
import spinal.core.sim._

import org.scalatest.funsuite.AnyFunSuite
//import org.scalatest.matchers.should.Matchers._
//import org.scalatest.AppendedClues._
import scala.collection.mutable

import ConstantSettings._
import RdmaConstants._
import StreamSimUtil._
import RdmaTypeReDef._
import PsnSim._
import WorkReqSim._

class WorkReqCacheTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val depth = 32

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new WorkReqCache(depth))

  test("WorkReqCache normal case") {
    testFunc()
  }

  test("WorkReqCache retry case") {
    testRetryScan()
  }

  def testRetryScan(): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.txQCtrl.retry #= false
    dut.io.txQCtrl.wrongStateFlush #= false
    dut.io.pop.ready #= false
    dut.io.retryScanCtrlBus.startPulse #= false
    dut.io.retryScanCtrlBus.retryReason #= RetryReason.SEQ_ERR
    dut.io.queryPort4SqRespDmaWrite.req.valid #= false

    val inputQueue = mutable.Queue[
      (
          PhysicalAddr,
          PsnStart,
          PktNum,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type],
          VirtualAddr,
          LRKey
      )
    ]()
    val outputQueue = mutable.Queue[
      (
          PhysicalAddr,
          PsnStart,
          PktNum,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type],
          VirtualAddr,
          LRKey
      )
    ]()
    val retryQueue = mutable.Queue[
      (
          PhysicalAddr,
          PsnStart,
          PktNum,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type],
          VirtualAddr,
          LRKey
      )
    ]()

    streamMasterDriver(dut.io.push, dut.clockDomain) {
      // Just random assignment to dut.io.push
    }
    onStreamFire(dut.io.push, dut.clockDomain) {
//      println(f"${simTime()} time, dut.io.push.workReq.id=${dut.io.push.workReq.id.toBigInt}%X")
      inputQueue.enqueue(
        (
          dut.io.push.pa.toBigInt,
          dut.io.push.psnStart.toInt,
          dut.io.push.pktNum.toInt,
          dut.io.push.workReq.lenBytes.toLong,
          dut.io.push.workReq.opcode.toEnum,
          dut.io.push.workReq.raddr.toBigInt,
          dut.io.push.workReq.rkey.toLong
        )
      )
    }
    onStreamFire(dut.io.pop, dut.clockDomain) {
      outputQueue.enqueue(
        (
          dut.io.pop.pa.toBigInt,
          dut.io.pop.psnStart.toInt,
          dut.io.pop.pktNum.toInt,
          dut.io.pop.workReq.lenBytes.toLong,
          dut.io.pop.workReq.opcode.toEnum,
          dut.io.pop.workReq.raddr.toBigInt,
          dut.io.pop.workReq.rkey.toLong
        )
      )
    }

    streamSlaveRandomizer(dut.io.retryWorkReq, dut.clockDomain)
    onStreamFire(dut.io.retryWorkReq, dut.clockDomain) {
//      println(f"${simTime()} time, dut.io.retryWorkReq.workReq.id=${dut.io.retryWorkReq.workReq.id.toBigInt}%X")
      retryQueue.enqueue(
        (
          dut.io.retryWorkReq.pa.toBigInt,
          dut.io.retryWorkReq.psnStart.toInt,
          dut.io.retryWorkReq.pktNum.toInt,
          dut.io.retryWorkReq.workReq.lenBytes.toLong,
          dut.io.retryWorkReq.workReq.opcode.toEnum,
          dut.io.retryWorkReq.workReq.raddr.toBigInt,
          dut.io.retryWorkReq.workReq.rkey.toLong
        )
      )
    }

    fork {
      while (true) {
        waitUntil(dut.io.full.toBoolean)
        dut.clockDomain.waitSampling()
        dut.io.txQCtrl.retry #= false
        dut.io.retryScanCtrlBus.startPulse #= true
        dut.clockDomain.waitSampling()
        dut.io.retryScanCtrlBus.startPulse #= false
        waitUntil(dut.io.retryScanCtrlBus.donePulse.toBoolean)
        dut.io.txQCtrl.retry #= false
        dut.io.pop.ready #= true
        waitUntil(outputQueue.size >= depth)
        dut.io.pop.ready #= false
        outputQueue.clear()
      }
    }
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputQueue,
      retryQueue,
      MATCH_CNT
    )
  }

  def testFunc(): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.txQCtrl.retry #= false
    dut.io.txQCtrl.wrongStateFlush #= false
    dut.io.retryWorkReq.ready #= false
    dut.io.retryScanCtrlBus.startPulse #= false
    dut.io.queryPort4SqRespDmaWrite.req.valid #= false

    val inputQueue = mutable.Queue[
      (
          PhysicalAddr,
          PsnStart,
          PktNum,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type],
          VirtualAddr,
          LRKey
      )
    ]()
    val outputQueue = mutable.Queue[
      (
          PhysicalAddr,
          PsnStart,
          PktNum,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type],
          VirtualAddr,
          LRKey
      )
    ]()

    streamMasterDriver(dut.io.push, dut.clockDomain) {
      // Just random assignment to dut.io.push
    }
    streamSlaveRandomizer(dut.io.pop, dut.clockDomain)
    onStreamFire(dut.io.push, dut.clockDomain) {
      inputQueue.enqueue(
        (
          dut.io.push.pa.toBigInt,
          dut.io.push.psnStart.toInt,
          dut.io.push.pktNum.toInt,
          dut.io.push.workReq.lenBytes.toLong,
          dut.io.push.workReq.opcode.toEnum,
          dut.io.push.workReq.raddr.toBigInt,
          dut.io.push.workReq.rkey.toLong
        )
      )
    }
    onStreamFire(dut.io.pop, dut.clockDomain) {
      outputQueue.enqueue(
        (
          dut.io.pop.pa.toBigInt,
          dut.io.pop.psnStart.toInt,
          dut.io.pop.pktNum.toInt,
          dut.io.pop.workReq.lenBytes.toLong,
          dut.io.pop.workReq.opcode.toEnum,
          dut.io.pop.workReq.raddr.toBigInt,
          dut.io.pop.workReq.rkey.toLong
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

class RetryHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new RetryHandler)

  def randomRetryStartPsn(psnStart: PsnStart, pktNum: PktNum): PSN = {
    // RDMA max packet length 2GB=2^31
    psnStart +% scala.util.Random.nextInt(pktNum)
  }

  def testFunc(isRetryOverLimit: Boolean, isPartialRetry: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val retryLimit = 3
      dut.io.qpAttr.maxRetryCnt #= retryLimit
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.retryScanCtrlBus.donePulse #= false
      if (isPartialRetry) {
        dut.io.qpAttr.retryReason #= RetryReason.SEQ_ERR
      } else {
        dut.io.qpAttr.retryReason #= RetryReason.RNR
      }
      dut.io.txQCtrl.wrongStateFlush #= false

      val inputQueue = mutable.Queue[
        (
            PhysicalAddr,
            PsnStart,
            PktNum,
            PktLen,
            SpinalEnumElement[WorkReqOpCode.type],
            VirtualAddr,
            VirtualAddr,
            LRKey
        )
      ]()
      val outputQueue = mutable.Queue[
        (
            PhysicalAddr,
            PsnStart,
            PktNum,
            PktLen,
            SpinalEnumElement[WorkReqOpCode.type],
            VirtualAddr,
            VirtualAddr,
            LRKey
        )
      ]()

      var nextPsn = 0
      streamMasterDriver(dut.io.retryWorkReqIn, dut.clockDomain) {
        val curPsn = nextPsn
        dut.io.retryWorkReqIn.psnStart #= curPsn
        val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
        dut.io.retryWorkReqIn.workReq.opcode #= workReqOpCode
        val pktLen = if (workReqOpCode.isAtomicReq()) {
          ATOMIC_DATA_LEN.toLong
        } else {
          WorkReqSim.randomDmaLength()
        }
        dut.io.retryWorkReqIn.workReq.lenBytes #= pktLen
        val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
        dut.io.retryWorkReqIn.pktNum #= pktNum

        val retryStartPsn = if (isPartialRetry) {
          randomRetryStartPsn(curPsn, pktNum)
        } else {
          curPsn
        }
        nextPsn = nextPsn +% pktNum
        dut.io.qpAttr.retryStartPsn #= retryStartPsn
        dut.io.qpAttr.npsn #= nextPsn

        if (isRetryOverLimit) {
          dut.io.retryWorkReqIn.rnrCnt #= retryLimit + 1
          dut.io.retryWorkReqIn.retryCnt #= retryLimit + 1
        } else {
          dut.io.retryWorkReqIn.rnrCnt #= 0
          dut.io.retryWorkReqIn.retryCnt #= 0
        }

        val workReqPsnStart = curPsn
        val pa = dut.io.retryWorkReqIn.pa.toBigInt
        val rmtAddr = dut.io.retryWorkReqIn.workReq.raddr.toBigInt
        val localAddr = dut.io.retryWorkReqIn.workReq.laddr.toBigInt
        val rmtKey = dut.io.retryWorkReqIn.workReq.rkey.toLong
        val (
          retryWorkReqPsnStart,
          retryWorkReqLenBytes,
          retryWorkReqPhysicalAddr,
          retryWorkReqRmtAddr,
          retryWorkReqLocalAddr,
          retryWorkReqPktNum
        ) = if (isPartialRetry) {
          val psnDiff = PsnSim.psnDiff(retryStartPsn, workReqPsnStart)
          val pktLenDiff = psnDiff << pmtuLen.id
          (
            retryStartPsn,
            pktLen - pktLenDiff,
            pa + pktLenDiff,
            rmtAddr + pktLenDiff,
            localAddr + pktLenDiff,
            pktNum - psnDiff
          )
        } else {
          (workReqPsnStart, pktLen, pa, rmtAddr, localAddr, pktNum)
        }
//          println(
//            f"${simTime()} time: nPSN=${nextPsn}%X, retryStartPsn=${retryStartPsn}%X=${retryStartPsn}, workReqPsnStart=${workReqPsnStart}%X=${workReqPsnStart}, pmtuLen=${pmtuLen.id}%X, pktLen=${pktLen}%X=${pktLen}, pa=${pa}%X=${pa}, rmtAddr=${rmtAddr}%X=${rmtAddr}, retryWorkReqLenBytes=${retryWorkReqLenBytes}%X=${retryWorkReqLenBytes}, retryWorkReqPhysicalAddr=${retryWorkReqPhysicalAddr}%X=${retryWorkReqPhysicalAddr}, retryWorkReqRmtAddr=${retryWorkReqRmtAddr}%X=${retryWorkReqRmtAddr}, retryWorkReqPktNum=${retryWorkReqPktNum}%X=${retryWorkReqPktNum}"
//          )
        inputQueue.enqueue(
          (
            retryWorkReqPhysicalAddr,
            retryWorkReqPsnStart,
            retryWorkReqPktNum,
            retryWorkReqLenBytes,
            workReqOpCode,
            retryWorkReqRmtAddr,
            retryWorkReqLocalAddr,
            rmtKey
          )
        )
      }
//      val camFifoRespQueue = CamFifoSim.queryAndResp(
//        dut.io.workReqCacheScanBus,
//        dut.io.qpAttr,
//        dut.clockDomain,
//        pmtuLen,
//        isPartialRetry,
//        isRetryOverLimit
//      )

//      fork {
//        while (true) {
//          val (
//            retryStartPsn,
////            nextPsn,
//            pa,
//            workReqPsnStart,
//            pktNum,
//            pktLen,
//            workReqOpCode,
//            rmtAddr,
//            rmtKey
//          ) = MiscUtils.safeDeQueue(camFifoRespQueue, dut.clockDomain)
//          val (
//            retryWorkReqPsnStart,
//            retryWorkReqLenBytes,
//            retryWorkReqPhysicalAddr,
//            retryWorkReqRmtAddr,
//            retryWorkReqPktNum
//          ) = if (isPartialRetry) {
//            val psnDiff = PsnSim.psnDiff(retryStartPsn, workReqPsnStart)
//            val pktLenDiff = psnDiff << pmtuLen.id
//            (
//              retryStartPsn,
//              pktLen - pktLenDiff,
//              pa + pktLenDiff,
//              rmtAddr + pktLenDiff,
//              pktNum - psnDiff
//            )
//          } else {
//            (workReqPsnStart, pktLen, pa, rmtAddr, pktNum)
//          }
////          println(
////            f"${simTime()} time: nPSN=${nextPsn}%X, retryStartPsn=${retryStartPsn}%X=${retryStartPsn}, workReqPsnStart=${workReqPsnStart}%X=${workReqPsnStart}, pmtuLen=${pmtuLen.id}%X, pktLen=${pktLen}%X=${pktLen}, pa=${pa}%X=${pa}, rmtAddr=${rmtAddr}%X=${rmtAddr}, retryWorkReqLenBytes=${retryWorkReqLenBytes}%X=${retryWorkReqLenBytes}, retryWorkReqPhysicalAddr=${retryWorkReqPhysicalAddr}%X=${retryWorkReqPhysicalAddr}, retryWorkReqRmtAddr=${retryWorkReqRmtAddr}%X=${retryWorkReqRmtAddr}, retryWorkReqPktNum=${retryWorkReqPktNum}%X=${retryWorkReqPktNum}"
////          )
//          inputQueue.enqueue(
//            (
//              retryWorkReqPhysicalAddr,
//              retryWorkReqPsnStart,
//              retryWorkReqPktNum,
//              retryWorkReqLenBytes,
//              workReqOpCode,
//              retryWorkReqRmtAddr,
//              rmtKey
//            )
//          )
//        }
//      }

      streamSlaveAlwaysReady(dut.io.retryWorkReqOut, dut.clockDomain)
      onStreamFire(dut.io.retryWorkReqOut, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.retryWorkReqOut.pa.toBigInt,
            dut.io.retryWorkReqOut.psnStart.toInt,
            dut.io.retryWorkReqOut.pktNum.toInt,
            dut.io.retryWorkReqOut.workReq.lenBytes.toLong,
            dut.io.retryWorkReqOut.workReq.opcode.toEnum,
            dut.io.retryWorkReqOut.workReq.raddr.toBigInt,
            dut.io.retryWorkReqOut.workReq.laddr.toBigInt,
            dut.io.retryWorkReqOut.workReq.rkey.toLong
          )
        )
      }

      if (isRetryOverLimit) {
        MiscUtils.checkSignalWhen(
          dut.clockDomain,
          when = dut.io.retryWorkReqIn.valid.toBoolean,
          signal = dut.io.errNotifier.pulse.toBoolean,
          clue =
            f"${simTime()} time: dut.io.errNotifier.pulse=${dut.io.errNotifier.pulse.toBoolean} should be true when dut.io.retryWorkReqIn.valid=${dut.io.retryWorkReqIn.valid.toBoolean}"
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }

  test("RetryHandler normal case") {
    testFunc(isRetryOverLimit = false, isPartialRetry = false)
  }

  test("RetryHandler partial retry case") {
    testFunc(isRetryOverLimit = false, isPartialRetry = true)
  }

  test("RetryHandler retry limit exceed case") {
    testFunc(isRetryOverLimit = true, isPartialRetry = true)
  }
}
