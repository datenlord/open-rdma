package rdma

import spinal.core.sim._
import ConstantSettings._
import StreamSimUtil._
import TypeReDef._
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

class RqReadDmaRespHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqReadDmaRespHandler(busWidth))

  test("zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val psnQueue = mutable.Queue[PSN]()
      val matchQueue = mutable.Queue[PSN]()

      dut.io.recvQCtrl.stateErrFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.readResultCacheData, dut.clockDomain) {
        dut.io.readResultCacheData.dlen #= 0
      }
      onStreamFire(dut.io.readResultCacheData, dut.clockDomain) {
        psnQueue.enqueue(dut.io.readResultCacheData.psnStart.toInt)
      }

      // Check DUT output
      MiscUtils.checkConditionAlways(dut.clockDomain) {
        dut.io.dmaReadResp.resp.ready.toBoolean == false
      }
      streamSlaveRandomizer(
        dut.io.readResultCacheDataAndDmaReadResp,
        dut.clockDomain
      )
      onStreamFire(dut.io.readResultCacheDataAndDmaReadResp, dut.clockDomain) {
//        println(
//          f"${simTime()} time: the read request has zero DMA length, but dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen.toLong}%X"
//        )
        assert(
          dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen.toLong == 0,
          f"${simTime()} time: the read request has zero DMA length, but dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen.toLong}%X"
        )

        val inputPsnStart = psnQueue.dequeue()
//        println(
//          f"${simTime()} time: output PSN io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt}%X not match input PSN io.readResultCacheData.psnStart=${inputPsnStart}%X"
//        )
        assert(
          inputPsnStart == dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt,
          f"${simTime()} time: output PSN io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt}%X not match input PSN io.readResultCacheData.psnStart=${inputPsnStart}%X"
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
        mutable.Queue[(RdmaFragData, PsnStart, PktLen, FragLast)]()
      val outputQueue = mutable.Queue[
        (RdmaFragData, PsnStart, PsnStart, PktLen, PktLen, FragNum, FragLast)
      ]()
      val matchQueue = mutable.Queue[PsnStart]()

      val randSeed = scala.util.Random.nextInt()
      val pmtuLen = PMTU.U1024
      dut.io.recvQCtrl.stateErrFlush #= false

      // Input to DUT
      val (
        totalFragNumItr4CacheData,
        pktNumItr4CacheData,
        psnStartItr4CacheData,
        totalLenItr4CacheData
      ) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth, randSeed)
      streamMasterDriver(dut.io.readResultCacheData, dut.clockDomain) {
        val _ = totalFragNumItr4CacheData.next()
        val pktNum = pktNumItr4CacheData.next()
        val psnStart = psnStartItr4CacheData.next()
        val totalLenBytes = totalLenItr4CacheData.next()

        dut.io.readResultCacheData.dlen #= totalLenBytes
        dut.io.readResultCacheData.psnStart #= psnStart
        dut.io.readResultCacheData.pktNum #= pktNum
      }
      onStreamFire(dut.io.readResultCacheData, dut.clockDomain) {
//        println(
//          f"${simTime()} time: dut.io.readResultCacheData.psnStart=${dut.io.readResultCacheData.psnStart.toInt}, dut.io.readResultCacheData.pktNum=${dut.io.readResultCacheData.pktNum.toInt}, dut.io.readResultCacheData.dlen=${dut.io.readResultCacheData.dlen.toLong}"
//        )
        cacheDataQueue.enqueue(
          (
            dut.io.readResultCacheData.psnStart.toInt,
            dut.io.readResultCacheData.pktNum.toInt,
            dut.io.readResultCacheData.dlen.toLong
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
        dut.io.readResultCacheDataAndDmaReadResp,
        dut.clockDomain
      )
      onStreamFire(dut.io.readResultCacheDataAndDmaReadResp, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.readResultCacheDataAndDmaReadResp.dmaReadResp.data.toBigInt,
            dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt,
            dut.io.readResultCacheDataAndDmaReadResp.dmaReadResp.psnStart.toInt,
            dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen.toLong,
            dut.io.readResultCacheDataAndDmaReadResp.dmaReadResp.lenBytes.toLong,
            dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.pktNum.toInt,
            dut.io.readResultCacheDataAndDmaReadResp.last.toBoolean
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
//            f"${simTime()} time: output packet num=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.pktNum.toInt} not match input packet num=${pktNumIn}%X"
//          )
            assert(
              pktNumIn == pktNumOut,
              f"${simTime()} time: output packet num=${pktNumOut} not match input packet num=${pktNumIn}%X"
            )

//            println(
//              f"${simTime()} time: respLenInDmaResp=${respLenInDmaResp}%X == respLenOutDmaResp=${respLenOutDmaResp}%X, respLenInCache=${respLenInCache}%X == respLenOutCache=${respLenOutCache}%X, respLenInDmaResp=${respLenInDmaResp}%X == respLenInCache=${respLenInCache}%X"
//            )
            assert(
              respLenInDmaResp == respLenOutDmaResp &&
                respLenInCache == respLenOutCache &&
                respLenInDmaResp == respLenInCache,
              f"${simTime()} time: respLenInDmaResp=${respLenInDmaResp}%X == respLenOutDmaResp=${respLenOutDmaResp}%X, respLenInCache=${respLenInCache}%X == respLenOutCache=${respLenOutCache}%X, respLenInDmaResp=${respLenInDmaResp}%X == respLenInCache=${respLenInCache}%X"
            )

//            println(
//              f"${simTime()} time: output response data io.readResultCacheDataAndDmaReadResp.dmaReadResp.data=${dmaRespDataOut}%X not match input response data io.dmaReadResp.resp.data=${dmaRespDataIn}%X"
//            )
            assert(
              dmaRespDataIn.toString(16) == dmaRespDataOut.toString(16),
              f"${simTime()} time: output response data io.readResultCacheDataAndDmaReadResp.dmaReadResp.data=${dmaRespDataOut}%X not match input response data io.dmaReadResp.resp.data=${dmaRespDataIn}%X"
            )

            assert(
              isLastIn == isLastOut,
              f"${simTime()} time: output dut.io.readResultCacheDataAndDmaReadResp.last=${isLastOut} not match input dut.io.dmaReadResp.resp.last=${isLastIn}"
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
