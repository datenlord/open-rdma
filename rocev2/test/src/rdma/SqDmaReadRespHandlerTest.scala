package rdma

import spinal.core.sim._

import ConstantSettings._
import StreamSimUtil._
import RdmaTypeReDef._
import SimSettings._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._
import scala.collection.mutable

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
      /*
      pktFragStreamMasterDriverAlwaysValid(
        dut.io.dmaReadResp.resp,
        dut.clockDomain
      ) {
        val (psnStart, payloadLenBytes, totalFragNum, pktNum) =
          MiscUtils.safeDeQueue(dmaRespMetaDataQueue, dut.clockDomain)
        (totalFragNum, (psnStart, payloadLenBytes, pktNum))
      } {
        (
            dmaReadResp: Fragment[DmaReadResp],
            fragLast: FragLast,
            _: FragIdx,
            _: FragNum,
            internalData: (PsnStart, PktLen, PktNum)
        ) =>
          val (psnStart, payloadLenBytes, pktNum) = internalData

          dmaReadResp.initiator #= DmaInitiator.SQ_RD
          dmaReadResp.psnStart #= psnStart
          dmaReadResp.lenBytes #= payloadLenBytes
          dmaReadResp.last #= fragLast

          expectedOutputQueue.enqueue(
            (
              dmaReadResp.data.toBigInt,
              psnStart,
              payloadLenBytes,
              pktNum,
              fragLast
            )
          )
      }
       */
      streamMasterPayloadFromQueueAlwaysValid(
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
