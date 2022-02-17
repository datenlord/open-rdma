package rdma

import ConstantSettings._
import RdmaConstants._
import StreamSimUtil._
import spinal.core.sim._

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.SpinalEnumElement

class RetryHandlerAndDmaReadInitTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

//  def busWidthBytes: Int = busWidth.id / BYTE_WIDTH

  def randomRetryStartPsn(psnStart: Int, pktNum: Int): Int = {
    // RDMA max packet length 2GB=2^31
    (psnStart + scala.util.Random.nextInt(pktNum)) % TOTAL_PSN
  }

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadAtomicRetryHandlerAndDmaReadInitiator)

  test("send/write/read request partial retry test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue =
        mutable.Queue[
          (
              Int,
              BigInt,
              Int,
              Long,
              SpinalEnumElement[WorkReqOpCode.type],
              BigInt,
              Long
          )
        ]()
      val outputReadQueue = mutable.Queue[(Int, Long, Long, BigInt)]()
      val outputDmaReqQueue = mutable.Queue[(BigInt, Int, Long)]()
      val matchQueue = mutable.Queue[Int]()

      var nextPsn = 0
      val pmtuLen = PMTU.U1024
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.qpAttr.retryReason #= RetryReason.RETRY_ACK
      dut.io.sendQCtrl.wrongStateFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.retryWorkReq, dut.clockDomain) {
        val curPsn = nextPsn
        dut.io.retryWorkReq.psnStart #= curPsn
        val workReqOpCode = WorkReqSim.randomSendWriteReadOpCode()
        dut.io.retryWorkReq.workReq.opcode #= workReqOpCode
        val pktLen = WorkReqSim.randomDmaLength()
        dut.io.retryWorkReq.workReq.lenBytes #= pktLen
        val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
        dut.io.retryWorkReq.pktNum #= pktNum
        val retryStartPsn = randomRetryStartPsn(curPsn, pktNum)
        dut.io.qpAttr.retryStartPsn #= retryStartPsn
        nextPsn = MiscUtils.psnAdd(nextPsn, pktNum)
        dut.io.qpAttr.npsn #= nextPsn
//        println(
//          f"${simTime()} time: the input WR opcode=${workReqOpCode}%X, curPsn=${curPsn}%X, nextPsn=${nextPsn}%X, retryStartPsn=${retryStartPsn}%X, pktLen=${pktLen}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.retryWorkReq, dut.clockDomain) {
        inputQueue.enqueue(
          (
            dut.io.qpAttr.retryStartPsn.toInt,
            dut.io.retryWorkReq.pa.toBigInt,
            dut.io.retryWorkReq.psnStart.toInt,
            dut.io.retryWorkReq.workReq.lenBytes.toLong,
            dut.io.retryWorkReq.workReq.opcode.toEnum,
            dut.io.retryWorkReq.workReq.raddr.toBigInt,
            dut.io.retryWorkReq.workReq.rkey.toLong
          )
        )
      }

      // Check DUT output
      MiscUtils.checkConditionAlways(dut.clockDomain) {
        dut.io.txAtomicReqRetry.valid.toBoolean == false
      }
      streamSlaveRandomizer(dut.io.outRetryWorkReq, dut.clockDomain)
      streamSlaveRandomizer(dut.io.txReadReqRetry, dut.clockDomain)
      onStreamFire(dut.io.txReadReqRetry, dut.clockDomain) {
        outputReadQueue.enqueue(
          (
            dut.io.txReadReqRetry.bth.psn.toInt,
            dut.io.txReadReqRetry.reth.dlen.toLong,
            dut.io.txReadReqRetry.reth.rkey.toLong,
            dut.io.txReadReqRetry.reth.va.toBigInt
          )
        )
      }
      streamSlaveRandomizer(dut.io.dmaRead.req, dut.clockDomain)
      onStreamFire(dut.io.dmaRead.req, dut.clockDomain) {
        outputDmaReqQueue.enqueue(
          (
            dut.io.dmaRead.req.addr.toBigInt,
            dut.io.dmaRead.req.psnStart.toInt,
            dut.io.dmaRead.req.lenBytes.toLong
          )
        )
      }
      fork {
        var (paOut, psnOut, lenOut, rKeyOut, vaOut) =
          (BigInt(0), 0, 0L, 0L, BigInt(0))
        while (true) {
          val (retryStartPsnIn, paIn, psnIn, lenIn, opCodeIn, vaIn, rKeyIn) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)
          if (WorkReqSim.isReadReq(opCodeIn)) {
            val outReadReq =
              MiscUtils.safeDeQueue(outputReadQueue, dut.clockDomain)
            psnOut = outReadReq._1
            lenOut = outReadReq._2
            rKeyOut = outReadReq._3
            vaOut = outReadReq._4
          } else {
            val outAtomicReq =
              MiscUtils.safeDeQueue(outputDmaReqQueue, dut.clockDomain)
            paOut = outAtomicReq._1
            psnOut = outAtomicReq._2
            lenOut = outAtomicReq._3
          }
          assert(
            MiscUtils.psnCmp(retryStartPsnIn, psnIn, curPsn = psnIn) >= 0,
            f"${simTime()} time: retryStartPsnIn=${retryStartPsnIn}%X should >= psnIn=${psnIn}%X in PSN order"
          )
          val psnDiff = MiscUtils.psnDiff(retryStartPsnIn, psnIn)
          val dmaReadOffset = psnDiff << pmtuLen.id
//        println(
//            f"${simTime()} time: output PSN=${psnOut}%X not match input retryStartPsnIn=${retryStartPsnIn}%X"
//        )
          assert(
            psnOut == retryStartPsnIn,
            f"${simTime()} time: output PSN=${psnOut}%X not match input retryStartPsnIn=${retryStartPsnIn}%X"
          )

//        println(
//            f"${simTime()} time: output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
//        )
          assert(
            lenOut == lenIn - dmaReadOffset,
            f"${simTime()} time: output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X - dmaReadOffset=${dmaReadOffset}%X"
          )

          if (WorkReqSim.isReadReq(opCodeIn)) {
            assert(
              rKeyOut == rKeyIn,
              f"${simTime()} time: output rkey=${rKeyOut}%X not match input rkey=${rKeyIn}%X"
            )
            assert(
              vaOut == vaIn + dmaReadOffset,
              f"${simTime()} time: output remote VA=${vaOut}%X not match input remote VA=${vaIn}%X + dmaReadOffset=${dmaReadOffset}%X"
            )
          } else {
            assert(
              paOut == paIn + dmaReadOffset,
              f"${simTime()} time: output local PA=${paOut}%X not match input local PA=${paIn}%X + dmaReadOffset=${dmaReadOffset}%X"
            )
          }
          matchQueue.enqueue(psnOut)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("read/atomic request whole retry test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[
        (Int, Long, SpinalEnumElement[WorkReqOpCode.type], BigInt, Long)
      ]()
      val outputReadQueue = mutable.Queue[(Int, Long, Long, BigInt)]()
      val outputAtomicQueue = mutable.Queue[(Int, Long, BigInt)]()
      val matchQueue = mutable.Queue[Int]()

      var nextPsn = 0
      val pmtuLen = PMTU.U1024
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.qpAttr.retryReason #= RetryReason.RETRY_ACK
      dut.io.sendQCtrl.wrongStateFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.retryWorkReq, dut.clockDomain) {
        val curPsn = nextPsn
        dut.io.qpAttr.retryStartPsn #= curPsn
        dut.io.retryWorkReq.psnStart #= curPsn
        val workReqOpCode = WorkReqSim.randomReadAtomicOpCode()
        dut.io.retryWorkReq.workReq.opcode #= workReqOpCode
        val pktLen = if (WorkReqSim.isAtomicReq(workReqOpCode)) {
          dut.io.retryWorkReq.workReq.lenBytes #= ATOMIC_DATA_LEN
          ATOMIC_DATA_LEN.toLong
        } else {
          val randomPktLen = WorkReqSim.randomDmaLength()
          dut.io.retryWorkReq.workReq.lenBytes #= randomPktLen
          randomPktLen
        }
        val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
        dut.io.retryWorkReq.pktNum #= pktNum
        nextPsn = MiscUtils.psnAdd(nextPsn, pktNum)
        dut.io.qpAttr.npsn #= nextPsn
//        println(
//          f"${simTime()} time: the input WR opcode=${workReqOpCode}%X, curPsn=${curPsn}%X, nextPsn=${nextPsn}%X, pktLen=${pktLen}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.retryWorkReq, dut.clockDomain) {
        inputQueue.enqueue(
          (
            dut.io.retryWorkReq.psnStart.toInt,
            dut.io.retryWorkReq.workReq.lenBytes.toLong,
            dut.io.retryWorkReq.workReq.opcode.toEnum,
            dut.io.retryWorkReq.workReq.raddr.toBigInt,
            dut.io.retryWorkReq.workReq.rkey.toLong
          )
        )
      }

      // Check DUT output
      MiscUtils.checkConditionAlways(dut.clockDomain) {
        dut.io.dmaRead.req.valid.toBoolean == false
      }
      streamSlaveRandomizer(dut.io.outRetryWorkReq, dut.clockDomain)
      streamSlaveRandomizer(dut.io.txReadReqRetry, dut.clockDomain)
      onStreamFire(dut.io.txReadReqRetry, dut.clockDomain) {
        outputReadQueue.enqueue(
          (
            dut.io.txReadReqRetry.bth.psn.toInt,
            dut.io.txReadReqRetry.reth.dlen.toLong,
            dut.io.txReadReqRetry.reth.rkey.toLong,
            dut.io.txReadReqRetry.reth.va.toBigInt
          )
        )
      }
      streamSlaveRandomizer(dut.io.txAtomicReqRetry, dut.clockDomain)
      onStreamFire(dut.io.txAtomicReqRetry, dut.clockDomain) {
        outputAtomicQueue.enqueue(
          (
            dut.io.txAtomicReqRetry.bth.psn.toInt,
            dut.io.txAtomicReqRetry.atomicEth.rkey.toLong,
            dut.io.txAtomicReqRetry.atomicEth.va.toBigInt
          )
        )
      }
      fork {
        var (psnOut, lenOut, rKeyOut, vaOut) = (0, 0L, 0L, BigInt(0))
        while (true) {
          val (psnIn, lenIn, opCodeIn, vaIn, rKeyIn) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)
          if (WorkReqSim.isReadReq(opCodeIn)) {
            val outReadReq =
              MiscUtils.safeDeQueue(outputReadQueue, dut.clockDomain)
            psnOut = outReadReq._1
            lenOut = outReadReq._2
            rKeyOut = outReadReq._3
            vaOut = outReadReq._4
          } else {
            val outAtomicReq =
              MiscUtils.safeDeQueue(outputAtomicQueue, dut.clockDomain)
            psnOut = outAtomicReq._1
            rKeyOut = outAtomicReq._2
            vaOut = outAtomicReq._3
            lenOut = ATOMIC_DATA_LEN.toLong
          }
//        println(
//            f"${simTime()} time: output PSN=${psnOut}%X not match input PSN=${psnIn}%X"
//        )
          assert(
            psnOut == psnIn,
            f"${simTime()} time: output PSN=${psnOut}%X not match input PSN=${psnIn}%X"
          )

//        println(
//            f"${simTime()} time: output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
//        )
          assert(
            lenOut == lenIn,
            f"${simTime()} time: output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
          )

          assert(
            rKeyOut == rKeyIn,
            f"${simTime()} time: output rkey=${rKeyOut}%X not match input rkey=${rKeyIn}%X"
          )
          assert(
            vaOut == vaIn,
            f"${simTime()} time: output remote VA=${vaOut}%X not match input remote VA=${vaIn}%X"
          )

          matchQueue.enqueue(psnOut)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("send/write request whole retry test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[(BigInt, Int, Long, Int)]()
      val outputQueue = mutable.Queue[(BigInt, Int, BigInt, Int)]()
      val matchQueue = mutable.Queue[Int]()

      var nextPsn = 0
      val pmtuLen = PMTU.U1024
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.qpAttr.retryReason #= RetryReason.RETRY_ACK
      dut.io.sendQCtrl.wrongStateFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.retryWorkReq, dut.clockDomain) {
        val curPsn = nextPsn
        dut.io.qpAttr.retryStartPsn #= curPsn
        dut.io.retryWorkReq.psnStart #= curPsn
        val workReqOpCode = WorkReqSim.randomSendWriteOpCode()
        dut.io.retryWorkReq.workReq.opcode #= workReqOpCode
        val pktLen = WorkReqSim.randomDmaLength()
        dut.io.retryWorkReq.workReq.lenBytes #= pktLen
        val pktNum = MiscUtils.computePktNum(pktLen, pmtuLen)
        dut.io.retryWorkReq.pktNum #= pktNum
        nextPsn = MiscUtils.psnAdd(nextPsn, pktNum)
        dut.io.qpAttr.npsn #= nextPsn
//        println(
//          f"${simTime()} time: the input WR opcode=${workReqOpCode}%X, curPsn=${curPsn}%X, nextPsn=${nextPsn}%X, pktLen=${pktLen}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.retryWorkReq, dut.clockDomain) {
        inputQueue.enqueue(
          (
            dut.io.retryWorkReq.pa.toBigInt,
            dut.io.retryWorkReq.psnStart.toInt,
            dut.io.retryWorkReq.workReq.lenBytes.toLong,
            dut.io.retryWorkReq.workReq.sqpn.toInt
          )
        )
      }

      // Check DUT output
      MiscUtils.checkConditionAlways(dut.clockDomain) {
        dut.io.txAtomicReqRetry.valid.toBoolean == false && dut.io.txReadReqRetry.valid.toBoolean == false
      }
      streamSlaveRandomizer(dut.io.outRetryWorkReq, dut.clockDomain)
      streamSlaveRandomizer(dut.io.dmaRead.req, dut.clockDomain)
      onStreamFire(dut.io.dmaRead.req, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.dmaRead.req.addr.toBigInt,
            dut.io.dmaRead.req.psnStart.toInt,
            dut.io.dmaRead.req.lenBytes.toBigInt,
            dut.io.dmaRead.req.sqpn.toInt
          )
        )
      }
      fork {
        while (true) {
          val (paIn, psnIn, lenIn, sqpnIn) =
            MiscUtils.safeDeQueue(inputQueue, dut.clockDomain)
          val (paOut, psnOut, lenOut, sqpnOut) =
            MiscUtils.safeDeQueue(outputQueue, dut.clockDomain)
//        println(
//            f"${simTime()} time: output PSN=${psnOut}%X not match input PSN=${psnIn}%X"
//        )
          assert(
            psnOut == psnIn,
            f"${simTime()} time: output PSN=${psnOut}%X not match input PSN=${psnIn}%X"
          )

//        println(
//            f"${simTime()} time: output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
//        )
          assert(
            lenOut == lenIn,
            f"${simTime()} time: output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
          )

          assert(
            sqpnOut == sqpnIn,
            f"${simTime()} time: output sqpnOut=${sqpnOut}%X not match input sqpnIn=${sqpnIn}%X"
          )
          assert(
            paOut == paIn,
            f"${simTime()} time: output local PA=${paOut}%X not match input local PA=${paIn}%X"
          )

          matchQueue.enqueue(psnOut)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}
