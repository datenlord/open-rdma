package rdma

//import spinal.core._
import spinal.core.sim._
import ConstantSettings._
import StreamSimUtil._
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

object ReadRespInput {
  def getItr(pmtuBytes: Int, busWidthBytes: Int) = {
    val dmaRespIdxGen = NaturalNumber.from(0)
    val fragNumGen = dmaRespIdxGen.map(_ * 2 + 1)
    val totalLenGen =
      fragNumGen.map(_ * busWidthBytes) // Assume all bits are valid
    val pktNumGen = totalLenGen.map(MiscUtils.dividedByUp(_, pmtuBytes))
    val psnGen = pktNumGen.scan(0)(_ + _)
    val fragNumItr = fragNumGen.iterator
    val pktNumItr = pktNumGen.iterator
    val psnItr = psnGen.iterator
    val totalLenItr = totalLenGen.iterator
//    for (idx <- 0 until 3) {
//      println(f"idx=$idx, fragNum=${fragNumItr.next()}, pktNum=${pktNumItr
//        .next()}, psnStart=${psnItr.next()}, totalLenBytes=${totalLenItr.next()}")
//    }
    (fragNumItr, pktNumItr, psnItr, totalLenItr)
  }
}

class RqReadDmaRespHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  def busWidthBytes: Int = busWidth.id / BYTE_WIDTH

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqReadDmaRespHandler(busWidth))

  test("zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val psnQueue = mutable.Queue[Int]()
      val matchQueue = mutable.Queue[Int]()

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
//          f"the read request has zero DMA length, but dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen.toLong}%X"
//        )
        assert(
          dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen.toLong == 0,
          f"the read request has zero DMA length, but dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.dlen.toLong}%X"
        )

        val inputPsnStart = psnQueue.dequeue()
//        println(
//          f"output PSN io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt}%X not match input PSN io.readResultCacheData.psnStart=${inputPsnStart}%X"
//        )
        assert(
          inputPsnStart == dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt,
          f"output PSN io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.psnStart.toInt}%X not match input PSN io.readResultCacheData.psnStart=${inputPsnStart}%X"
        )

        matchQueue.enqueue(inputPsnStart)
      }

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("non-zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val cacheDataQueue = mutable.Queue[(Int, Int, Long)]()
      val dmaRespQueue = mutable.Queue[(BigInt, Int, Long, Boolean)]()
      val outputQueue =
        mutable.Queue[(BigInt, Int, Int, Long, Long, Int, Boolean)]()
      val matchQueue = mutable.Queue[Int]()

      val pmtuBytes = 256
      dut.io.recvQCtrl.stateErrFlush #= false

      // Input to DUT
      val (_, pktNumItr4CacheData, psnItr4CacheData, totalLenItr4CacheData) =
        ReadRespInput.getItr(pmtuBytes, busWidthBytes)
      streamMasterDriver(dut.io.readResultCacheData, dut.clockDomain) {
        val pktNum = pktNumItr4CacheData.next()
        val psnStart = psnItr4CacheData.next()
        val totalLenBytes = totalLenItr4CacheData.next()

        dut.io.readResultCacheData.dlen #= totalLenBytes
        dut.io.readResultCacheData.psnStart #= psnStart
        dut.io.readResultCacheData.pktNum #= pktNum
      }
      onStreamFire(dut.io.readResultCacheData, dut.clockDomain) {
        println(
          f"dut.io.readResultCacheData.psnStart=${dut.io.readResultCacheData.psnStart.toInt}, dut.io.readResultCacheData.pktNum=${dut.io.readResultCacheData.pktNum.toInt}, dut.io.readResultCacheData.dlen=${dut.io.readResultCacheData.dlen.toLong}"
        )
        cacheDataQueue.enqueue(
          (
            dut.io.readResultCacheData.psnStart.toInt,
            dut.io.readResultCacheData.pktNum.toInt,
            dut.io.readResultCacheData.dlen.toLong
          )
        )
      }

      // Functional way to generate sequences
      val (fragNumItr4DmaResp, _, psnItr4DmaResp, totalLenItr4DmaResp) =
        ReadRespInput.getItr(pmtuBytes, busWidthBytes)
      fragmentStreamMasterDriver(dut.io.dmaReadResp.resp, dut.clockDomain) {
        val fragNum = fragNumItr4DmaResp.next()
        val totalLenBytes = totalLenItr4DmaResp.next()
        val psnStart = psnItr4DmaResp.next()
        (fragNum, (psnStart, totalLenBytes))
      } { (fragIdx, outerLoopRslt) =>
        val (fragNum, (psnStart, totalLenBytes)) = outerLoopRslt
        dut.io.dmaReadResp.resp.psnStart #= psnStart
        dut.io.dmaReadResp.resp.lenBytes #= totalLenBytes
        dut.io.dmaReadResp.resp.last #= (fragIdx == fragNum - 1)
//            println(
//              f"input DMA response psnStart=${psnStart}%X, lenBytes=${respTotalBytes}%X, last=${last}, fragNum=${fragNumVec(
//                idx
//              )}, fragIdx=${fragIdx}, idx=${idx}"
//            )
      }
      onStreamFire(dut.io.dmaReadResp.resp, dut.clockDomain) {
        println(
          f"dut.io.dmaReadResp.resp.psnStart=${dut.io.dmaReadResp.resp.psnStart.toInt}, dut.io.dmaReadResp.resp.lenBytes=${dut.io.dmaReadResp.resp.lenBytes.toLong}, dut.io.dmaReadResp.resp.last=${dut.io.dmaReadResp.resp.last.toBoolean}"
        )
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
//              f"psnStartInCache=${psnStartInCache} == psnStartOutCache=${psnStartOutCache}, psnStartInDmaResp=${psnStartInDmaResp} == psnStartOutDmaResp=${psnStartOutDmaResp}, psnStartInCache=${psnStartInCache} == psnStartInDmaResp=${psnStartInDmaResp}"
//            )
            assert(
              psnStartInCache == psnStartOutCache &&
                psnStartInDmaResp == psnStartOutDmaResp &&
                psnStartInCache == psnStartInDmaResp,
              f"psnStartInCache=${psnStartInCache} == psnStartOutCache=${psnStartOutCache}, psnStartInDmaResp=${psnStartInDmaResp} == psnStartOutDmaResp=${psnStartOutDmaResp}, psnStartInCache=${psnStartInCache} == psnStartInDmaResp=${psnStartInDmaResp}"
            )

//          println(
//            f"output packet num=${dut.io.readResultCacheDataAndDmaReadResp.resultCacheData.pktNum.toInt} not match input packet num=${pktNumIn}%X"
//          )
            assert(
              pktNumIn == pktNumOut,
              f"output packet num=${pktNumOut} not match input packet num=${pktNumIn}%X"
            )

//            println(
//              f"respLenInDmaResp=${respLenInDmaResp} == respLenOutDmaResp=${respLenOutDmaResp}, respLenInCache=${respLenInCache} == respLenOutCache=${respLenOutCache}, respLenInDmaResp=${respLenInDmaResp} == respLenInCache=${respLenInCache}"
//            )
            assert(
              respLenInDmaResp == respLenOutDmaResp &&
                respLenInCache == respLenOutCache &&
                respLenInDmaResp == respLenInCache,
              f"respLenInDmaResp=${respLenInDmaResp} == respLenOutDmaResp=${respLenOutDmaResp}, respLenInCache=${respLenInCache} == respLenOutCache=${respLenOutCache}, respLenInDmaResp=${respLenInDmaResp} == respLenInCache=${respLenInCache}"
            )

//          println(
//            f"output response data io.readResultCacheDataAndDmaReadResp.dmaReadResp.data=${dut.io.readResultCacheDataAndDmaReadResp.dmaReadResp.data.toBigInt}%X not match input response data io.dmaReadResp.resp.data=${dataIn}%X"
//          )
            assert(
              dmaRespDataIn.toString(16) == dmaRespDataOut.toString(16),
              f"output response data io.readResultCacheDataAndDmaReadResp.dmaReadResp.data=${dmaRespDataOut}%X not match input response data io.dmaReadResp.resp.data=${dmaRespDataIn}%X"
            )

            assert(
              isLastIn == isLastOut,
              f"output dut.io.readResultCacheDataAndDmaReadResp.last=${isLastOut} not match input dut.io.dmaReadResp.resp.last=${isLastIn}"
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
