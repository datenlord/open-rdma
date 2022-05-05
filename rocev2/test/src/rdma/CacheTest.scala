package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._
import scala.collection.mutable

import ConstantSettings._
import StreamSimUtil._
import RdmaTypeReDef._
import PsnSim._

//import java.util.concurrent.Semaphore

class ReadAtomicRstCacheTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val depth = 8

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadAtomicRstCache(depth))

  def testQueryFunc(): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.flush #= false
    dut.io.queryPort4DupReq.req.valid #= false

    val tmpQueryReqQueue = mutable.Queue[
      (
          PsnStart,
          OpCode.Value,
          LRKey,
          PsnExpected
      )
    ]()
    val queryReqQueue = mutable.Queue[
      (
          PsnStart,
          OpCode.Value,
          LRKey,
          PsnExpected
      )
    ]()
    val queryRespQueue = mutable.Queue[
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

    val inputReqQueue =
      mutable.Queue[
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
    val allInputReqQueue = mutable.Queue[
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
    val allOutputReqQueue = mutable.Queue[
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
    val allExpectedQueryRespQueue = mutable.Queue[
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
    val allQueryRespQueue = mutable.Queue[
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

    fork {
      var nextPsn = 0
      while (true) {
        dut.io.pop.ready #= false
        for (_ <- 0 until depth) {
          dut.io.push.payload.randomize()
          sleep(0)
          val curPsn = nextPsn
          val opcode = OpCodeSim.randomReadAtomicOpCode()
          val pktLen = WorkReqSim.randomDmaLength()
          val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
          val rmtKey = dut.io.push.rkey.toLong
          nextPsn = nextPsn +% pktNum
          inputReqQueue.enqueue(
            (
              dut.io.push.pa.toBigInt,
              curPsn,
              pktNum,
              pktLen,
              opcode,
              dut.io.push.va.toBigInt,
              rmtKey
            )
          )
          tmpQueryReqQueue.enqueue(
            (
              curPsn,
              opcode,
              rmtKey,
              nextPsn
            )
          )
//          println(
//            f"${simTime()} time: push request with PSN=${curPsn}%X, opcode=${opcode}, pktNum=${pktNum}%X, rkey=${rmtKey}%X, ePSN=${nextPsn}%X"
//          )
        }
        // Wait until ReadAtomicRstCache is full
        dut.clockDomain.waitSamplingWhere(dut.io.full.toBoolean)

        // Then wait until the CAM query is done
        for (_ <- 0 until depth) {
          val queryReq = tmpQueryReqQueue.dequeue()
          queryReqQueue.enqueue(queryReq)
        }
        waitUntil(queryRespQueue.size >= depth)
        for (_ <- 0 until depth) {
          val queryResp = queryRespQueue.dequeue()
          allQueryRespQueue.enqueue(queryResp)
        }

        // Clear ReadAtomicRstCache by popping
        dut.io.pop.ready #= true
        dut.clockDomain.waitSamplingWhere(dut.io.empty.toBoolean)
      }
    }

    streamMasterPayloadFromQueue(
      dut.io.push,
      dut.clockDomain,
      inputReqQueue,
      payloadAssignFunc = (
          reqData: ReadAtomicRstCacheData,
          payloadData: (
              PhysicalAddr,
              PsnStart,
              PktNum,
              PktLen,
              OpCode.Value,
              VirtualAddr,
              LRKey
          )
      ) => {
        val (
          physicalAddr,
          psnStart,
          pktNum,
          pktLen,
          opcode,
          virtualAddr,
          rmtKey
        ) = payloadData
        reqData.pa #= physicalAddr
        reqData.psnStart #= psnStart
        reqData.pktNum #= pktNum
        reqData.dlen #= pktLen
        reqData.opcode #= opcode.id
        reqData.va #= virtualAddr
        reqData.rkey #= rmtKey
      }
    )
    onStreamFire(dut.io.push, dut.clockDomain) {
      allInputReqQueue.enqueue(
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
      allExpectedQueryRespQueue.enqueue(
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

    streamMasterPayloadFromQueue(
      dut.io.queryPort4DupReq.req,
      dut.clockDomain,
      queryReqQueue,
      payloadAssignFunc = (
          queryReq: ReadAtomicRstCacheReq,
          payloadData: (
              PsnStart,
              OpCode.Value,
              LRKey,
              PsnExpected
          )
      ) => {
        val (queryPsn, opcode, rmtKey, nextPsn) = payloadData
        queryReq.queryPsn #= queryPsn
        queryReq.opcode #= opcode.id
        queryReq.rkey #= rmtKey
        queryReq.epsn #= nextPsn

//        println(
//          f"${simTime()} time: CAM query request with queryPsn=${queryPsn}%X, opcode=${opcode}, rkey=${rmtKey}%X, ePSN=${nextPsn}%X"
//        )
      }
    )

    streamSlaveRandomizer(dut.io.queryPort4DupReq.resp, dut.clockDomain)
    onStreamFire(dut.io.queryPort4DupReq.resp, dut.clockDomain) {
      val queryPsn = dut.io.queryPort4DupReq.resp.queryKey.queryPsn.toInt
      val opcode = OpCode(dut.io.queryPort4DupReq.resp.queryKey.opcode.toInt)
      val rmtKey = dut.io.queryPort4DupReq.resp.queryKey.rkey.toLong
      val nextPsn = dut.io.queryPort4DupReq.resp.queryKey.epsn.toInt
      val found = dut.io.queryPort4DupReq.resp.found.toBoolean

      found shouldBe true withClue f"${simTime()} time: CAM query response with queryPsn=${queryPsn}%X, opcode=${opcode}, rmtKey=${rmtKey}%X, ePSN=${nextPsn}%X, found=${found}"

//      println(
//        f"${simTime()} time: CAM query response with queryPsn=${queryPsn}%X, ePSN=${nextPsn}%X, opcode=${opcode}, rmtKey=${rmtKey}%X, found=${found}"
//      )

      queryRespQueue.enqueue(
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

    onStreamFire(dut.io.pop, dut.clockDomain) {
      allOutputReqQueue.enqueue(
        (
          dut.io.pop.pa.toBigInt,
          dut.io.pop.psnStart.toInt,
          dut.io.pop.pktNum.toInt,
          dut.io.pop.dlen.toLong,
          OpCode(dut.io.pop.opcode.toInt),
          dut.io.pop.va.toBigInt,
          dut.io.pop.rkey.toLong
        )
      )
    }

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      allInputReqQueue,
      allOutputReqQueue,
      MATCH_CNT
    )
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      allExpectedQueryRespQueue,
      allQueryRespQueue,
      MATCH_CNT
    )
  }

  test("ReadAtomicRstCache query case") {
    testQueryFunc()
  }
}

class FifoTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val depth = 32

  class FifoInst(busWidth: BusWidth.Value, depth: Int) extends Component {
    val ptrWidth = log2Up(depth) + 1
    val io = new Bundle {
      val write = slave(Stream(Bits(busWidth.id bits)))
      val read = master(Stream(Bits(busWidth.id bits)))
      val empty = out(Bool())
      val full = out(Bool())
      val occupancy = out(UInt(ptrWidth bits))
    }

    val fifo = new Fifo(
      io.write.payloadType,
      initDataVal = B(0, busWidth.id bits),
      depth = depth
    )
    fifo.io.push << io.write
    io.read << fifo.io.pop
    io.empty := fifo.io.empty
    io.full := fifo.io.full
    io.occupancy := fifo.io.occupancy
    fifo.io.flush := False
  }

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new FifoInst(busWidth, depth))

  test("Fifo full and empty test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[BigInt]()
      val outputQueue = mutable.Queue[BigInt]()

      dut.io.read.ready #= false
      fork {
        while (true) {
          dut.io.write.valid #= true
          // Push to FIFO until full
          while (!dut.io.full.toBoolean) {
            dut.io.write.payload.randomize()
            dut.clockDomain.waitSampling()
          }
          dut.io.write.valid #= false
          dut.clockDomain.waitSampling()

          println(
            f"${simTime()} time: push to FIFO until full=${dut.io.full.toBoolean}"
          )

          // Clear FIFO
          dut.io.read.ready #= true
          dut.clockDomain.waitSamplingWhere(dut.io.empty.toBoolean)
          dut.io.read.ready #= false

          println(
            f"${simTime()} time: pop from FIFO until empty=${dut.io.empty.toBoolean}"
          )
        }
      }

      onStreamFire(dut.io.write, dut.clockDomain) {
        inputQueue.enqueue(dut.io.write.payload.toBigInt)
      }

      onStreamFire(dut.io.read, dut.clockDomain) {
        outputQueue.enqueue(dut.io.read.payload.toBigInt)
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }

  test("Fifo normal test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[BigInt]()
      val outputQueue = mutable.Queue[BigInt]()
      streamMasterDriver(dut.io.write, dut.clockDomain) {
        inputQueue.enqueue(dut.io.write.payload.toBigInt)
      }

      streamSlaveRandomizer(dut.io.read, dut.clockDomain)
      onStreamFire(dut.io.read, dut.clockDomain) {
        outputQueue.enqueue(dut.io.read.payload.toBigInt)
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

class WorkReqCacheTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024
  val depth = 8

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new WorkReqCache(depth))

  def testRetryScan(retryReason: SpinalEnumElement[RetryReason.type]): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.txQCtrl.retry #= false
      dut.io.txQCtrl.wrongStateFlush #= false

      val retryTimes = 5

      val pushReqQueue = mutable.Queue[
        (
            WorkReqId,
            PhysicalAddr,
            PsnStart,
            PktNum,
            PktLen,
            SpinalEnumElement[WorkReqOpCode.type],
            VirtualAddr,
            LRKey
        )
      ]()
      val allPushReqQueue = mutable.Queue[
        (
            WorkReqId,
            PhysicalAddr,
            PsnStart,
            PktNum,
            PktLen,
            SpinalEnumElement[WorkReqOpCode.type],
            VirtualAddr,
            LRKey
        )
      ]()
      val allPopRespQueue = mutable.Queue[
        (
            WorkReqId,
            PhysicalAddr,
            PsnStart,
            PktNum,
            PktLen,
            SpinalEnumElement[WorkReqOpCode.type],
            VirtualAddr,
            LRKey
        )
      ]()
      val expectedRetryQueue = mutable.Queue[
        (
            WorkReqId,
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
            WorkReqId,
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
//        dut.io.push.valid #= false
        dut.io.pop.ready #= false
        dut.io.retryScanCtrlBus.startPulse #= false
        dut.io.retryScanCtrlBus.retryReason #= retryReason
//        dut.clockDomain.waitSampling()

        while (true) {
          // Push to WorkReqCache until full
          for (_ <- 0 until depth) {
            dut.io.push.payload.randomize()
            sleep(0)
            pushReqQueue.enqueue(
              (
                dut.io.push.workReq.id.toBigInt,
                dut.io.push.pa.toBigInt,
                dut.io.push.psnStart.toInt,
                dut.io.push.pktNum.toInt,
                dut.io.push.workReq.lenBytes.toLong,
                WorkReqSim.randomSendWriteReadAtomicOpCode(),
                dut.io.push.workReq.raddr.toBigInt,
                dut.io.push.workReq.rkey.toLong
              )
            )
          }
          dut.clockDomain.waitSamplingWhere(dut.io.full.toBoolean)

          // Retry multiple times
          for (_ <- 0 until retryTimes) {
            // Set retry start pulse
            dut.io.retryScanCtrlBus.startPulse #= true
            dut.clockDomain.waitSampling()
            dut.io.retryScanCtrlBus.startPulse #= false

            // Set retry state and wait for retry done
            dut.io.txQCtrl.retry #= true
            dut.clockDomain.waitSamplingWhere(
              dut.io.retryScanCtrlBus.donePulse.toBoolean
            )
            dut.io.txQCtrl.retry #= false
            dut.clockDomain.waitSampling()
          }
//          println(
//            f"${simTime()} time: retry done=${dut.io.retryScanCtrlBus.donePulse.toBoolean}"
//          )

          // Clear WorkReqCache
          dut.io.pop.ready #= true
          dut.clockDomain.waitSamplingWhere(dut.io.empty.toBoolean)
          dut.io.pop.ready #= false
//          println(
//            f"${simTime()} time: pop from WR cache until empty=${dut.io.empty.toBoolean}"
//          )
        }
      }

      streamMasterPayloadFromQueue(
        dut.io.push,
        dut.clockDomain,
        pushReqQueue,
        payloadAssignFunc = (
            cachedWorkReq: CachedWorkReq,
            payloadData: (
                WorkReqId,
                PhysicalAddr,
                PsnStart,
                PktNum,
                PktLen,
                SpinalEnumElement[WorkReqOpCode.type],
                VirtualAddr,
                LRKey
            )
        ) => {
          val (
            workReqId,
            physicalAddr,
            psnStart,
            pktNum,
            pktLen,
            workReqOpCode,
            virtualAddr,
            rmtKey
          ) = payloadData
          cachedWorkReq.workReq.id #= workReqId
          cachedWorkReq.pa #= physicalAddr
          cachedWorkReq.psnStart #= psnStart
          cachedWorkReq.pktNum #= pktNum
          cachedWorkReq.workReq.lenBytes #= pktLen
          cachedWorkReq.workReq.opcode #= workReqOpCode
          cachedWorkReq.workReq.raddr #= virtualAddr
          cachedWorkReq.workReq.rkey #= rmtKey
        }
      )
      onStreamFire(dut.io.push, dut.clockDomain) {
//        println(
//          f"${simTime()} time, dut.io.push.workReq.id=${dut.io.push.workReq.id.toBigInt}%X"
//        )
        allPushReqQueue.enqueue(
          (
            dut.io.push.workReq.id.toBigInt,
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
        expectedRetryQueue.enqueue(
          (
            dut.io.push.workReq.id.toBigInt,
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
        allPopRespQueue.enqueue(
          (
            dut.io.pop.workReq.id.toBigInt,
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
              dut.io.retryWorkReq.scanOutData.workReq.id.toBigInt,
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
        expectedRetryQueue,
        retryOutQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        allPushReqQueue,
        allPopRespQueue,
        MATCH_CNT
      )
    }

  def testPushPopFunc(): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.txQCtrl.retry #= false
    dut.io.txQCtrl.wrongStateFlush #= false
    dut.io.retryWorkReq.ready #= false
    dut.io.retryScanCtrlBus.startPulse #= false

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
    testPushPopFunc()
  }

  test("WorkReqCache RNR retry case") {
    testRetryScan(retryReason = RetryReason.RNR)
  }

  test("WorkReqCache other retry case") {
    testRetryScan(retryReason = RetryReason.SEQ_ERR)
  }
}

class PdAddrCacheTest extends AnyFunSuite {
  val depth = 8

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new PdAddrCache(depth))

  def testFunc(querySuccess: Boolean, hasPermissionErr: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

//      dut.io.flush #= false
      val allCreateOrDeleteReqQueue = mutable.Queue[
        (
            Boolean,
            SpinalEnumElement[CRUD.type],
            AccessPermissionType,
            LRKey,
            LRKey,
            VirtualAddr,
            PhysicalAddr,
            PktLen
        )
      ]()
      val allCreateOrDeleteRespQueue = mutable.Queue[
        (
            Boolean,
            SpinalEnumElement[CRUD.type],
            AccessPermissionType,
            LRKey,
            LRKey,
            VirtualAddr,
            PhysicalAddr,
            PktLen
        )
      ]()
      val reqQueue = mutable.Queue[
        (
            SpinalEnumElement[CRUD.type],
            AccessPermissionType,
            LRKey,
            LRKey,
            VirtualAddr,
            PhysicalAddr,
            PktLen
        )
      ]()
      val addrData4DeleteQueue = mutable
        .Queue[
          (
              AccessPermissionType,
              LRKey,
              LRKey,
              VirtualAddr,
              PhysicalAddr,
              PktLen
          )
        ]()
      val addrData4QueryReqQueue = mutable
        .Queue[
          (AccessPermissionType, LRKey, VirtualAddr, PhysicalAddr, PktLen)
        ]()
      val queryRespQueue = mutable.Queue[
        (
            KeyValid,
            SizeValid,
            AccessValid,
            PSN,
            PhysicalAddr
        )
      ]()
      val allQueryRespQueue = mutable.Queue[
        (
            KeyValid,
            SizeValid,
            AccessValid,
            PSN,
            PhysicalAddr
        )
      ]()
      val expectedAllQueryRespQueue = mutable.Queue[
        (
            KeyValid,
            SizeValid,
            AccessValid,
            PSN,
            PhysicalAddr
        )
      ]()

      streamMasterPayloadFromQueue(
        dut.io.addrCreateOrDelete.req,
        dut.clockDomain,
        reqQueue,
        payloadAssignFunc = (
            pdAddrDataReq: PdAddrDataCreateOrDeleteReq,
            payloadData: (
                SpinalEnumElement[CRUD.type],
                AccessPermissionType,
                LRKey,
                LRKey,
                VirtualAddr,
                PhysicalAddr,
                PktLen
            )
        ) => {
          val (
            createOrDelete,
            accessPermissionType,
            rmtKey,
            localKey,
            virtualAddr,
            physicalAddr,
            pktLen
          ) =
            payloadData
          pdAddrDataReq.createOrDelete #= createOrDelete
          pdAddrDataReq.addrData.accessType.accessBits #= accessPermissionType
          pdAddrDataReq.addrData.rkey #= rmtKey
          pdAddrDataReq.addrData.lkey #= localKey
          pdAddrDataReq.addrData.va #= virtualAddr
          pdAddrDataReq.addrData.pa #= physicalAddr
          pdAddrDataReq.addrData.dataLenBytes #= pktLen

//            println(f"${simTime()} time: dut.io.addrCreateOrDelete.req.createOrDelete=${createOrDelete}, dut.io.addrCreateOrDelete.req.addrData.pa=${physicalAddr}, full=${dut.io.full.toBoolean}")
        }
      )

      onStreamFire(dut.io.addrCreateOrDelete.req, dut.clockDomain) {
        val isSuccess = true
        allCreateOrDeleteReqQueue.enqueue(
          (
            isSuccess,
            dut.io.addrCreateOrDelete.req.createOrDelete.toEnum,
            dut.io.addrCreateOrDelete.req.addrData.accessType.accessBits.toInt,
            dut.io.addrCreateOrDelete.req.addrData.rkey.toLong,
            dut.io.addrCreateOrDelete.req.addrData.lkey.toLong,
            dut.io.addrCreateOrDelete.req.addrData.va.toBigInt,
            dut.io.addrCreateOrDelete.req.addrData.pa.toBigInt,
            dut.io.addrCreateOrDelete.req.addrData.dataLenBytes.toLong
          )
        )
      }

      streamSlaveRandomizer(dut.io.addrCreateOrDelete.resp, dut.clockDomain)
      onStreamFire(dut.io.addrCreateOrDelete.resp, dut.clockDomain) {
        val createOrDelete =
          dut.io.addrCreateOrDelete.resp.createOrDelete.toEnum
        val isSuccess = dut.io.addrCreateOrDelete.resp.isSuccess.toBoolean
        allCreateOrDeleteRespQueue.enqueue(
          (
            isSuccess,
            dut.io.addrCreateOrDelete.resp.createOrDelete.toEnum,
            dut.io.addrCreateOrDelete.resp.addrData.accessType.accessBits.toInt,
            dut.io.addrCreateOrDelete.resp.addrData.rkey.toLong,
            dut.io.addrCreateOrDelete.resp.addrData.lkey.toLong,
            dut.io.addrCreateOrDelete.resp.addrData.va.toBigInt,
            dut.io.addrCreateOrDelete.resp.addrData.pa.toBigInt,
            dut.io.addrCreateOrDelete.resp.addrData.dataLenBytes.toLong
          )
        )

//        println(f"${simTime()} time: createOrDelete=${createOrDelete}, dut.io.addrCreateOrDelete.resp.addrData.pa=${dut.io.addrCreateOrDelete.resp.addrData.pa.toBigInt}, isSuccess=${isSuccess}")
        if (createOrDelete == CRUD.CREATE && isSuccess) {
          addrData4DeleteQueue.enqueue(
            (
              dut.io.addrCreateOrDelete.resp.addrData.accessType.accessBits.toInt,
              dut.io.addrCreateOrDelete.resp.addrData.rkey.toLong,
              dut.io.addrCreateOrDelete.resp.addrData.lkey.toLong,
              dut.io.addrCreateOrDelete.resp.addrData.va.toBigInt,
              dut.io.addrCreateOrDelete.resp.addrData.pa.toBigInt,
              dut.io.addrCreateOrDelete.resp.addrData.dataLenBytes.toLong
            )
          )
          addrData4QueryReqQueue.enqueue(
            (
              dut.io.addrCreateOrDelete.resp.addrData.accessType.accessBits.toInt,
              dut.io.addrCreateOrDelete.resp.addrData.rkey.toLong,
//              dut.io.addrCreateOrDelete.resp.addrData.lkey.toLong
              dut.io.addrCreateOrDelete.resp.addrData.va.toBigInt,
              dut.io.addrCreateOrDelete.resp.addrData.pa.toBigInt,
              dut.io.addrCreateOrDelete.resp.addrData.dataLenBytes.toLong
            )
          )
        }
      }

      fork {
        while (true) {
          // Insert until full
          for (_ <- 0 until depth) {
            dut.io.addrCreateOrDelete.req.payload.randomize()
            sleep(0)
            reqQueue.enqueue(
              (
                CRUD.CREATE,
                dut.io.addrCreateOrDelete.req.addrData.accessType.accessBits.toInt,
                dut.io.addrCreateOrDelete.req.addrData.rkey.toLong,
                dut.io.addrCreateOrDelete.req.addrData.lkey.toLong,
                dut.io.addrCreateOrDelete.req.addrData.va.toBigInt,
                dut.io.addrCreateOrDelete.req.addrData.pa.toBigInt,
                dut.io.addrCreateOrDelete.req.addrData.dataLenBytes.toLong
              )
            )
          }
          waitUntil(addrData4DeleteQueue.size >= depth)
          dut.io.full.toBoolean shouldBe true withClue
            f"${simTime()} time: dut.io.full=${dut.io.full.toBoolean} should be true"
//          println(
//            f"${simTime()} time: push to PdAddrCache until dut.io.full=${dut.io.full.toBoolean}"
//          )

          // Wait until query finish
          waitUntil(queryRespQueue.size >= depth)
          for (_ <- 0 until depth) {
            val queryResp = queryRespQueue.dequeue()
            allQueryRespQueue.enqueue(queryResp)
          }
//          println(
//            f"${simTime()} time: search PdAddrCache finished, addrData4QueryReqQueue.size=${addrData4QueryReqQueue.size}"
//          )

          // Delete until empty
          addrData4DeleteQueue.size shouldBe depth withClue
            f"${simTime()} time: addrData4DeleteQueue.size=${addrData4DeleteQueue.size} should == depth=${depth}"

          for (_ <- 0 until depth) {
            val data2Delete = addrData4DeleteQueue.dequeue()

            val (
              accessPermissionType,
              rmtKey,
              localKey,
              virtualAddr,
              physicalAddr,
              pktLen
            ) =
              data2Delete
            val reqDelete = (
              CRUD.DELETE,
              accessPermissionType,
              rmtKey,
              localKey,
              virtualAddr,
              physicalAddr,
              pktLen
            )
            reqQueue.enqueue(reqDelete)
          }
          dut.clockDomain.waitSamplingWhere(dut.io.empty.toBoolean)
//          println(
//            f"${simTime()} time: pop from PdAddrCache until dut.io.empty=${dut.io.empty.toBoolean}"
//          )
        }
      }

      streamMasterPayloadFromQueueRandomInterval(
        dut.io.query.req,
        dut.clockDomain,
        addrData4QueryReqQueue,
        maxIntervalCycles = ADDR_CACHE_QUERY_DELAY_CYCLE,
        payloadAssignFunc = (
            queryReq: PdAddrCacheReadReq,
            payloadData: (
                AccessPermissionType,
                LRKey,
                VirtualAddr,
                PhysicalAddr,
                PktLen
            )
        ) => {
          val (
            accessPermissionType,
            rmtKey,
            virtualAddr,
            physicalAddr,
            pktLen
          ) =
            payloadData
          val noAccessPermission = 0
          val accessBits =
            if (!querySuccess && hasPermissionErr) noAccessPermission
            else accessPermissionType
          queryReq.accessType.accessBits #= accessBits
          queryReq.key #= rmtKey
          queryReq.remoteOrLocalKey #= true
          queryReq.va #= virtualAddr
          queryReq.dataLenBytes #= pktLen

          val keyValid = true
          val sizeValid = true
          val accessValid = if (querySuccess) true else hasPermissionErr
          expectedAllQueryRespQueue.enqueue(
            (
              keyValid,
              sizeValid,
              accessValid,
              queryReq.psn.toInt,
              physicalAddr
            )
          )
        }
      )

      streamSlaveRandomizer(dut.io.query.resp, dut.clockDomain)
      onStreamFire(dut.io.query.resp, dut.clockDomain) {
        queryRespQueue.enqueue(
          (
            dut.io.query.resp.keyValid.toBoolean,
            dut.io.query.resp.sizeValid.toBoolean,
            dut.io.query.resp.accessValid.toBoolean,
            dut.io.query.resp.psn.toInt,
            dut.io.query.resp.pa.toBigInt
          )
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        expectedAllQueryRespQueue,
        allQueryRespQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        allCreateOrDeleteReqQueue,
        allCreateOrDeleteRespQueue,
        MATCH_CNT
      )
    }

  test("PdAddrCache normal case") {
    testFunc(querySuccess = true, hasPermissionErr = false)
  }

  test("PdAddrCache error case") {
    testFunc(querySuccess = false, hasPermissionErr = true)
  }
}
