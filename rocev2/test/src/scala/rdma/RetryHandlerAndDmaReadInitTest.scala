package rdma

import ConstantSettings._
import RdmaConstants._
import StreamSimUtil._

import spinal.core.sim._
import scala.collection.mutable

import org.scalatest.funsuite.AnyFunSuite

class RetryHandlerAndDmaReadInitTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  def busWidthBytes: Int = busWidth.id / BYTE_WIDTH

  val workReqSend = Seq(
    WorkReqOpCode.SEND.id,
    WorkReqOpCode.SEND_WITH_IMM.id,
    WorkReqOpCode.SEND_WITH_INV.id
  )
  val workReqWrite =
    Seq(WorkReqOpCode.RDMA_WRITE.id, WorkReqOpCode.RDMA_WRITE_WITH_IMM.id)
  val workReqRead = Seq(WorkReqOpCode.RDMA_READ.id)
  val workReqAtomic = Seq(
    WorkReqOpCode.ATOMIC_CMP_AND_SWP.id,
    WorkReqOpCode.ATOMIC_FETCH_AND_ADD.id
  )

  def isSendReq(workReqOpCode: Int): Boolean = {
    workReqSend.contains(workReqOpCode)
  }

  def isWriteReq(workReqOpCode: Int): Boolean = {
    workReqWrite.contains(workReqOpCode)
  }

  def isReadReq(workReqOpCode: Int): Boolean = {
    workReqRead.contains(workReqOpCode)
  }

  def isAtomicReq(workReqOpCode: Int): Boolean = {
    workReqAtomic.contains(workReqOpCode)
  }

  def randomReadAtomicOpCode(): Int = {
    val opCodes = WorkReqOpCode.RDMA_READ.id +: workReqAtomic
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val rslt = opCodes(randIdx)
    assert(opCodes.contains(rslt))
    rslt
  }

  def randomSendWriteOpCode(): Int = {
    val opCodes = workReqSend ++ workReqWrite
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val rslt = opCodes(randIdx)
    assert(opCodes.contains(rslt))
    rslt
  }

  def randomDmaLength(): Long = {
    // RDMA max packet length 2GB=2^31
    scala.util.Random.nextLong(1L << (RDMA_MAX_LEN_WIDTH - 1))
  }

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadAtomicRetryHandlerAndDmaReadInitiator)

  test("read/atomic request test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[(Int, Long, Int, BigInt, Long)]()
      val outputReadQueue = mutable.Queue[(Int, Long, Long, BigInt)]()
      val outputAtomicQueue = mutable.Queue[(Int, Long, BigInt)]()
      val matchQueue = mutable.Queue[Int]()

      var nextPsn = 0L
      val pmtuBytes = 256
      dut.io.qpAttr.pmtu #= PMTU.U256.id
      dut.io.qpAttr.retryReason #= RetryReason.RETRY_ACK
      dut.io.sendQCtrl.wrongStateFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.retryWorkReq, dut.clockDomain) {
        val curPsn = nextPsn
        dut.io.qpAttr.retryPsnStart #= curPsn
        dut.io.qpAttr.npsn #= curPsn
        dut.io.retryWorkReq.psnStart #= curPsn
        val workReqOpCode = randomReadAtomicOpCode()
        dut.io.retryWorkReq.workReq.opcode #= workReqOpCode
        val pktLen = if (isAtomicReq(workReqOpCode)) {
          dut.io.retryWorkReq.workReq.lenBytes #= ATOMIC_DATA_LEN
          ATOMIC_DATA_LEN.toLong
        } else {
          val randomPktLen = randomDmaLength()
          dut.io.retryWorkReq.workReq.lenBytes #= randomPktLen
          randomPktLen
        }
        val pktNum = MiscUtils.dividedByUp(pktLen, pmtuBytes)
        dut.io.retryWorkReq.pktNum #= pktNum
        nextPsn = (nextPsn + pktNum) % TOTAL_PSN
//        println(
//          f"the input WR opcode=${workReqOpCode}%X, curPsn=${curPsn}%X, nextPsn=${nextPsn}%X, pktLen=${pktLen}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.retryWorkReq, dut.clockDomain) {
        inputQueue.enqueue(
          (
            dut.io.retryWorkReq.psnStart.toInt,
            dut.io.retryWorkReq.workReq.lenBytes.toLong,
            dut.io.retryWorkReq.workReq.opcode.toInt,
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
          if (isReadReq(opCodeIn)) {
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
//            f"output PSN=${psnOut}%X not match input PSN=${psnIn}%X"
//        )
          assert(
            psnOut == psnIn,
            f"output PSN=${psnOut}%X not match input PSN=${psnIn}%X"
          )

//        println(
//            f"output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
//        )
          assert(
            lenOut == lenIn,
            f"output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
          )

          assert(
            rKeyOut == rKeyIn,
            f"output rkey=${rKeyOut}%X not match input rkey=${rKeyIn}%X"
          )
          assert(
            vaOut == vaIn,
            f"output remote VA=${vaOut}%X not match input remote VA=${vaIn}%X"
          )

          matchQueue.enqueue(psnOut)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("send/write request test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputQueue = mutable.Queue[(BigInt, Int, Long, Int)]()
      val outputQueue = mutable.Queue[(BigInt, Int, BigInt, Int)]()
      val matchQueue = mutable.Queue[Int]()

      var nextPsn = 0L
      val pmtuBytes = 256
      dut.io.qpAttr.pmtu #= PMTU.U256.id
      dut.io.qpAttr.retryReason #= RetryReason.RETRY_ACK
      dut.io.sendQCtrl.wrongStateFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.retryWorkReq, dut.clockDomain) {
        val curPsn = nextPsn
        dut.io.qpAttr.retryPsnStart #= curPsn
        dut.io.qpAttr.npsn #= curPsn
        dut.io.retryWorkReq.psnStart #= curPsn
        val workReqOpCode = randomSendWriteOpCode()
        dut.io.retryWorkReq.workReq.opcode #= workReqOpCode
        val pktLen = randomDmaLength()
        dut.io.retryWorkReq.workReq.lenBytes #= pktLen
        val pktNum = MiscUtils.dividedByUp(pktLen, pmtuBytes)
        dut.io.retryWorkReq.pktNum #= pktNum
        nextPsn = (nextPsn + pktNum) % TOTAL_PSN
//        println(
//          f"the input WR opcode=${workReqOpCode}%X, curPsn=${curPsn}%X, nextPsn=${nextPsn}%X, pktLen=${pktLen}%X, pktNum=${pktNum}%X"
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
//            f"output PSN=${psnOut}%X not match input PSN=${psnIn}%X"
//        )
          assert(
            psnOut == psnIn,
            f"output PSN=${psnOut}%X not match input PSN=${psnIn}%X"
          )

//        println(
//            f"output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
//        )
          assert(
            lenOut == lenIn,
            f"output lenBytes=${lenOut}%X not match input lenBytes=${lenIn}%X"
          )

          assert(
            sqpnOut == sqpnIn,
            f"output sqpnOut=${sqpnOut}%X not match input sqpnIn=${sqpnIn}%X"
          )
          assert(
            paOut == paIn,
            f"output local PA=${paOut}%X not match input local PA=${paIn}%X"
          )

          matchQueue.enqueue(psnOut)
        }
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}
