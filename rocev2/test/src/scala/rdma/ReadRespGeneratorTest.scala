package rdma

import spinal.core.sim._
import ConstantSettings._
import StreamSimUtil._
import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

class ReadRespGeneratorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadRespGenerator(busWidth))

  test("zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputPsnQueue = mutable.Queue[Int]()
      val outputPsnQueue = mutable.Queue[Int]()
      val naturalNumItr = NaturalNumber.from(1).iterator

      dut.io.qpAttr.pmtu #= PMTU.U256.id
      dut.io.recvQCtrl.stateErrFlush #= false

      // Input to DUT
      streamMasterDriverAlwaysValid(
        dut.io.readResultCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        val psn = naturalNumItr.next()
        dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.psnStart #= psn
        dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.psnStart #= psn
        dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.pktNum #= 0
        dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.dlen #= 0
        dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes #= 0
        dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.mty #= 0
        dut.io.readResultCacheDataAndDmaReadRespSegment.last #= true
      }
      onStreamFire(
        dut.io.readResultCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        inputPsnQueue.enqueue(
          dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.psnStart.toInt
        )
      }

      // Check DUT output
      streamSlaveAlwaysReady(dut.io.txReadResp.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txReadResp.pktFrag, dut.clockDomain) {
        outputPsnQueue.enqueue(dut.io.txReadResp.pktFrag.bth.psn.toInt)
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputPsnQueue,
        outputPsnQueue,
        MATCH_CNT
      )
    }
  }

  test("non-zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val pmtuBytes = 256
      val mtyWidth = busWidth.id / BYTE_WIDTH
      val fragNumPerPkt = pmtuBytes / mtyWidth

      val inputDataQueue =
        mutable.Queue[(BigInt, BigInt, Int, Int, Long, Boolean)]()
      val outputDataQueue = mutable.Queue[(BigInt, BigInt, Int, Boolean)]()
      val matchPsnQueue = mutable.Queue[Boolean]()

      dut.io.qpAttr.pmtu #= PMTU.U256.id
      dut.io.recvQCtrl.stateErrFlush #= false
      dut.io.readResultCacheDataAndDmaReadRespSegment.valid #= false
      dut.clockDomain.waitSampling()

      // Input to DUT
      val (fragNumItr, pktNumItr, psnItr, totalLenItr) =
        ReadRespInput.getItr(pmtuBytes, busWidthBytes = mtyWidth)
      fragmentStreamMasterDriverAlwaysValid(
        dut.io.readResultCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        val fragNum = fragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnItr.next()
        val totalLenBytes = totalLenItr.next()

        (fragNum, (pktNum, psnStart, totalLenBytes))
      } { (fragIdx, outerLoopRslt) =>
        val (fragNum, (pktNum, psnStart, totalLenBytes)) = outerLoopRslt
        val isLastInputFrag = fragIdx == fragNum - 1
        val isLastFragPerPkt = (fragIdx % fragNumPerPkt == fragNumPerPkt - 1)
        val isLast = isLastInputFrag || isLastFragPerPkt

        val mty = if (isLastInputFrag) {
          val residue = totalLenBytes % mtyWidth
          if (residue == 0) {
            setAllBits(mtyWidth) // Last fragment has full valid data
          } else {
            setAllBits(residue) // Last fragment has partial valid data
          }
        } else {
          setAllBits(mtyWidth)
        }
//        println(
//          f"fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, isLast=${isLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )

        dut.io.readResultCacheDataAndDmaReadRespSegment.valid #= true
        // dut.io.readResultCacheDataAndDmaReadRespSegment.valid.randomize()
        dut.io.readResultCacheDataAndDmaReadRespSegment.payload
          .randomize()

        dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.psnStart #= psnStart
        dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.psnStart #= psnStart
        dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.dlen #= totalLenBytes
        dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.pktNum #= pktNum
        dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes #= totalLenBytes
        dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.mty #= mty
        dut.io.readResultCacheDataAndDmaReadRespSegment.last #= isLast
      }

      onStreamFire(
        dut.io.readResultCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        inputDataQueue.enqueue(
          (
            dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.data.toBigInt,
            dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.mty.toBigInt,
            dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.pktNum.toInt,
            dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.psnStart.toInt,
            dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes.toLong,
            dut.io.readResultCacheDataAndDmaReadRespSegment.last.toBoolean
          )
        )
      }

      streamSlaveAlwaysReady(dut.io.txReadResp.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txReadResp.pktFrag, dut.clockDomain) {
        outputDataQueue.enqueue(
          (
            dut.io.txReadResp.pktFrag.data.toBigInt,
            dut.io.txReadResp.pktFrag.mty.toBigInt,
            dut.io.txReadResp.pktFrag.bth.psn.toInt,
            dut.io.txReadResp.pktFrag.last.toBoolean
          )
        )
      }

      fork {
        var nextPsn = 0
        while (true) {
          var (dataIn, mtyIn, pktNum, psnStart, totalLenBytes, isLastIn) =
            (BigInt(0), BigInt(0), 0, 0, 0L, false)
          do {
            val inputData =
              MiscUtils.safeDeQueue(inputDataQueue, dut.clockDomain)
            dataIn = inputData._1
            mtyIn = inputData._2
            pktNum = inputData._3
            psnStart = inputData._4
            totalLenBytes = inputData._5
            isLastIn = inputData._6
          } while (!isLastIn)

          var (dataOut, mtyOut, psnOut, isLastOut) =
            (BigInt(0), BigInt(0), 0, false)
          do {
            val outputData =
              MiscUtils.safeDeQueue(outputDataQueue, dut.clockDomain)
            dataOut = outputData._1
            mtyOut = outputData._2
            psnOut = outputData._3
            isLastOut = outputData._4

//            println(
//              f"pktNum=${pktNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}, isLastIn=${isLastIn}, psnOut=${psnOut}, isLastOut=${isLastOut}"
//            )
            matchPsnQueue.enqueue(isLastOut)
          } while (!isLastOut)

          val lastFragMtyInValidBytesNum = MiscUtils.countOnes(mtyIn, mtyWidth)
          val lastFragMtyOutValidBytesNum =
            MiscUtils.countOnes(mtyOut, mtyWidth)
          val lastFragMtyMinimumByteNum =
            lastFragMtyInValidBytesNum.min(lastFragMtyOutValidBytesNum)
          val lastFragMtyMinimumBitNum = lastFragMtyMinimumByteNum * BYTE_WIDTH
          val dataInLastFragRightShiftBitAmt =
            busWidth.id - (lastFragMtyInValidBytesNum * BYTE_WIDTH)
          val dataOutLastFragRightShiftBitAmt =
            busWidth.id - (lastFragMtyOutValidBytesNum * BYTE_WIDTH)

          val lastFragDataInValidBits = dataIn >> dataInLastFragRightShiftBitAmt
          val lastFragDataOutValidBits =
            dataOut >> dataOutLastFragRightShiftBitAmt
          val lastFragMtyMinimumBits = setAllBits(lastFragMtyMinimumBitNum)

//          println(
//            f"last fragment data=${lastFragDataOutValidBits}%X not match last fragment input data=${lastFragDataInValidBits}%X with minimum last fragment MTY=(${lastFragMtyMinimumByteNum}*BYTE_WIDTH)"
//          )
          assert(
            (lastFragDataOutValidBits & lastFragMtyMinimumBits)
              .toString(
                16
              ) == (lastFragDataInValidBits & lastFragMtyMinimumBits)
              .toString(16),
            f"last fragment data=${lastFragDataOutValidBits}%X not match last fragment input data=${lastFragDataInValidBits}%X with minimum last fragment out MTY=(${lastFragMtyMinimumByteNum}*BYTE_WIDTH)"
          )

//          println(
//            f"expected output PSN=${nextPsn} not match output PSN=${psnOut}"
//          )
          assert(
            psnOut == nextPsn,
            f"expected output PSN=${nextPsn} not match output PSN=${psnOut}"
          )
          nextPsn += 1
        }
      }

      waitUntil(matchPsnQueue.size > MATCH_CNT)
    }
  }
}
