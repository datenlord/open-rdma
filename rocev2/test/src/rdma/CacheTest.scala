package rdma

import spinal.core._
import spinal.core.sim._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._
import scala.collection.mutable

import ConstantSettings._
import StreamSimUtil._
import RdmaTypeReDef._
import PsnSim._

// TODO: PdAddrCache

class ReadAtomicRstCacheTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val depth = 32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadAtomicRstCache(depth))

  def testQueryFunc(): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.flush #= false
    dut.io.queryPort4DupReq.req.valid #= false

    val inputWorkReqQueue = mutable.Queue[
      (
          PhysicalAddr,
          PsnStart,
          PktNum,
          PktLen,
          OpCode.Value,
          VirtualAddr,
          LRKey
      )
    ]()
    val inputQueryQueue =
      mutable.Queue[(OpCode.Value, PsnStart, LRKey, PsnNext)]()
    val outputQueryQueue = mutable.Queue[
      (
          PhysicalAddr,
          PsnStart,
          PktNum,
          PktLen,
          OpCode.Value,
          VirtualAddr,
          LRKey
      )
    ]()

    var nextPsn = 0
    streamMasterDriverAlwaysValid(dut.io.push, dut.clockDomain) {
      val curPsn = nextPsn
      val opcode = OpCodeSim.randomReadAtomicOpCode()
      dut.io.push.opcode #= opcode.id
      val pktLen = WorkReqSim.randomDmaLength()
      dut.io.push.dlen #= pktLen
      val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
      dut.io.push.pktNum #= pktNum
      dut.io.push.psnStart #= curPsn
      nextPsn = nextPsn +% pktNum
      val rmtKey = dut.io.push.rkey.toLong
      inputQueryQueue.enqueue((opcode, curPsn, rmtKey, nextPsn))

//      println(
//        f"${simTime()} time: push WR PSN=${curPsn}%X, ePSN=${nextPsn}%X, opcode=${opcode}, rmtKey=${rmtKey}%X, pktNum=${pktNum}%X"
//      )
    }
    onStreamFire(dut.io.push, dut.clockDomain) {
      inputWorkReqQueue.enqueue(
        (
          dut.io.push.pa.toBigInt,
          dut.io.push.psnStart.toInt,
          dut.io.push.pktNum.toInt,
          dut.io.push.dlen.toLong,
          OpCode(dut.io.push.opcode.toInt),
          dut.io.push.va.toBigInt,
          dut.io.push.rkey.toLong
        )
      )
    }
    fork {
      dut.io.queryPort4DupReq.req.valid #= false
      dut.clockDomain.waitSampling()

      waitUntil(dut.io.full.toBoolean)
      while (true) {
        dut.io.queryPort4DupReq.req.valid #= false
        val (opcode, queryPsn, rmtKey, nextPsn) =
          MiscUtils.safeDeQueue(inputQueryQueue, dut.clockDomain)
        dut.io.queryPort4DupReq.req.valid #= true
        dut.io.queryPort4DupReq.req.opcode #= opcode.id
        dut.io.queryPort4DupReq.req.queryPsn #= queryPsn
        dut.io.queryPort4DupReq.req.rkey #= rmtKey
        dut.io.queryPort4DupReq.req.epsn #= nextPsn

//        println(
//          f"${simTime()} time: CAM query request with queryPsn=${queryPsn}%X, ePSN=${nextPsn}%X, opcode=${opcode}"
//        )

        dut.clockDomain.waitSampling()
        waitUntil(
          dut.io.queryPort4DupReq.req.valid.toBoolean &&
            dut.io.queryPort4DupReq.req.ready.toBoolean
        )
      }
    }
    fork {
      while (true) {
        dut.io.pop.ready #= false
        dut.clockDomain.waitSampling()

        // Wait until ReadAtomicRstCache is full
        waitUntil(dut.io.full.toBoolean)
        // Then wait until the CAM query is done
        waitUntil(outputQueryQueue.isEmpty)
        // Clear ReadAtomicRstCache by popping
        dut.io.pop.ready #= true
        waitUntil(dut.io.empty.toBoolean)
      }
    }
    streamSlaveAlwaysReady(dut.io.queryPort4DupReq.resp, dut.clockDomain)
    onStreamFire(dut.io.queryPort4DupReq.resp, dut.clockDomain) {
      val queryPsn = dut.io.queryPort4DupReq.resp.queryKey.queryPsn.toInt
      val opcode = OpCode(dut.io.queryPort4DupReq.resp.queryKey.opcode.toInt)
      val rmtKey = dut.io.queryPort4DupReq.resp.queryKey.rkey.toLong
      val nextPsn = dut.io.queryPort4DupReq.resp.queryKey.epsn.toInt
      val found = dut.io.queryPort4DupReq.resp.found.toBoolean

      found shouldBe true withClue f"${simTime()} time: CAM query response with queryPsn=${queryPsn}%X, ePSN=${nextPsn}%X, opcode=${opcode}, rmtKey=${rmtKey}%X, found=${found}"

//      println(
//        f"${simTime()} time: CAM query response with queryPsn=${queryPsn}%X, ePSN=${nextPsn}%X, opcode=${opcode}, rmtKey=${rmtKey}%X, found=${found}"
//      )

      outputQueryQueue.enqueue(
        (
          dut.io.queryPort4DupReq.resp.respValue.pa.toBigInt,
          dut.io.queryPort4DupReq.resp.respValue.psnStart.toInt,
          dut.io.queryPort4DupReq.resp.respValue.pktNum.toInt,
          dut.io.queryPort4DupReq.resp.respValue.dlen.toLong,
          opcode,
          dut.io.queryPort4DupReq.resp.respValue.va.toBigInt,
          dut.io.queryPort4DupReq.resp.respValue.rkey.toLong
        )
      )
    }

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputWorkReqQueue,
      outputQueryQueue,
      MATCH_CNT // PENDING_REQ_NUM
    )
  }

  test("ReadAtomicRstCache query case") {
    testQueryFunc()
  }
}

class WorkReqCacheTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val depth = 32

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new WorkReqCache(depth))

  def testRetryScan(retryReason: SpinalEnumElement[RetryReason.type]): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.txQCtrl.retry #= false
      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.push.valid #= false
      dut.io.pop.ready #= false
      dut.io.retryScanCtrlBus.startPulse #= false
      dut.io.retryScanCtrlBus.retryReason #= retryReason
//      dut.io.queryPort4SqRespDmaWrite.req.valid #= false

      val retryTimes = 3

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
      val retryExpectedQueue = mutable.Queue[
        (
            PhysicalAddr,
            PsnStart,
            PktNum,
            PktLen,
            SpinalEnumElement[WorkReqOpCode.type],
            VirtualAddr,
            LRKey,
            RnrCnt,
            RetryCnt
        )
      ]()
      val retryOutQueue = mutable.Queue[
        (
            PhysicalAddr,
            PsnStart,
            PktNum,
            PktLen,
            SpinalEnumElement[WorkReqOpCode.type],
            VirtualAddr,
            LRKey,
            RnrCnt,
            RetryCnt
        )
      ]()

      fork {
        while (true) {
          // Push to WorkReqCache until full
          dut.io.push.valid #= true
          // Assign valid WR opcode
          while (!dut.io.full.toBoolean) {
            dut.io.push.workReq.opcode #=
              WorkReqSim.randomSendWriteReadAtomicOpCode()
            dut.clockDomain.waitSampling()
          }
          dut.io.push.valid #= false
          dut.clockDomain.waitSampling()

          // Retry multiple times
          for (_ <- 0 until retryTimes) {
            // Set retry start pulse
            dut.io.retryScanCtrlBus.startPulse #= true
            dut.clockDomain.waitSampling()
            dut.io.retryScanCtrlBus.startPulse #= false

            // Set retry state and wait for retry done
            dut.io.txQCtrl.retry #= true
            waitUntil(dut.io.retryScanCtrlBus.donePulse.toBoolean)
            dut.io.txQCtrl.retry #= false
            dut.clockDomain.waitSampling()
          }

          // Clear WorkReqCache
          dut.io.pop.ready #= true
          waitUntil(dut.io.empty.toBoolean)
          dut.io.pop.ready #= false
        }
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
        val (rnrCnt, retryCnt) = if (retryReason == RetryReason.RNR) {
          (retryTimes, 1)
        } else {
          (1, retryTimes)
        }
        retryExpectedQueue.enqueue(
          (
            dut.io.push.pa.toBigInt,
            dut.io.push.psnStart.toInt,
            dut.io.push.pktNum.toInt,
            dut.io.push.workReq.lenBytes.toLong,
            dut.io.push.workReq.opcode.toEnum,
            dut.io.push.workReq.raddr.toBigInt,
            dut.io.push.workReq.rkey.toLong,
            rnrCnt,
            retryCnt
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
        val rnrCnt = dut.io.retryWorkReq.rnrCnt.toInt
        val retryCnt = dut.io.retryWorkReq.retryCnt.toInt
        val psnStart = dut.io.retryWorkReq.scanOutData.psnStart.toInt
        val workReqOpCode =
          dut.io.retryWorkReq.scanOutData.workReq.opcode.toEnum
        if (rnrCnt >= retryTimes || retryCnt >= retryTimes) {
          //          println(f"${simTime()} time, dut.io.retryWorkReq.scanOutData.psnStart=${psnStart}%X, workReqOpCode=${workReqOpCode}, rnrCnt=${rnrCnt}, retryCnt=${retryCnt}")
          retryOutQueue.enqueue(
            (
              dut.io.retryWorkReq.scanOutData.pa.toBigInt,
              psnStart,
              dut.io.retryWorkReq.scanOutData.pktNum.toInt,
              dut.io.retryWorkReq.scanOutData.workReq.lenBytes.toLong,
              workReqOpCode,
              dut.io.retryWorkReq.scanOutData.workReq.raddr.toBigInt,
              dut.io.retryWorkReq.scanOutData.workReq.rkey.toLong,
              rnrCnt,
              retryCnt
            )
          )
        }
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        retryExpectedQueue,
        retryOutQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }

  def testFunc(): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.txQCtrl.retry #= false
    dut.io.txQCtrl.wrongStateFlush #= false
    dut.io.retryWorkReq.ready #= false
    dut.io.retryScanCtrlBus.startPulse #= false
//    dut.io.queryPort4SqRespDmaWrite.req.valid #= false

    val inputWorkReqQueue = mutable.Queue[
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
    val outputWorkReqQueue = mutable.Queue[
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
      dut.io.push.workReq.opcode #= WorkReqSim.randomSendWriteReadAtomicOpCode()
    }
    onStreamFire(dut.io.push, dut.clockDomain) {
      inputWorkReqQueue.enqueue(
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
    streamSlaveRandomizer(dut.io.pop, dut.clockDomain)
    onStreamFire(dut.io.pop, dut.clockDomain) {
      outputWorkReqQueue.enqueue(
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
      inputWorkReqQueue,
      outputWorkReqQueue,
      MATCH_CNT
    )
  }

  test("WorkReqCache normal case") {
    testFunc()
  }

  test("WorkReqCache RNR retry case") {
    testRetryScan(retryReason = RetryReason.RNR)
  }

  test("WorkReqCache other retry case") {
    testRetryScan(retryReason = RetryReason.SEQ_ERR)
  }
}
