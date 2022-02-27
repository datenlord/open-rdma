package rdma

import spinal.core.sim._
import ConstantSettings._
import StreamSimUtil._
import TypeReDef._

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

class ReadRespGeneratorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadRespGenerator(busWidth))

  test("zero DMA length read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputPsnQueue = mutable.Queue[PSN]()
      val outputPsnQueue = mutable.Queue[PSN]()
      val naturalNumItr = NaturalNumber.from(1).iterator

      val pmtuLen = PMTU.U1024
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.recvQCtrl.stateErrFlush #= false

      // Input to DUT
      streamMasterDriver(
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
      streamSlaveRandomizer(dut.io.txReadResp.pktFrag, dut.clockDomain)
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

      val pmtuLen = PMTU.U1024
      val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)

      val inputDataQueue =
        mutable.Queue[(RdmaFragData, MTY, PktNum, PsnStart, PktLen, FragLast)]()
      val outputDataQueue = mutable.Queue[(RdmaFragData, MTY, PSN, FragLast)]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.recvQCtrl.stateErrFlush #= false
      dut.io.readResultCacheDataAndDmaReadRespSegment.valid #= false
//      dut.clockDomain.waitSampling()

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)
      pktFragStreamMasterDriver(
        dut.io.readResultCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val totalLenBytes = totalLenItr.next()

        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, totalLenBytes)
      } {
        (
            _,
            psnStart,
            fragLast,
            fragIdx,
            totalFragNum,
            _,
            pktNum,
            totalLenBytes
        ) =>
          val isLastInputFrag = fragIdx == totalFragNum - 1

          val mty = if (isLastInputFrag) {
            val residue = (totalLenBytes % mtyWidth).toInt
            if (residue == 0) {
              setAllBits(mtyWidth) // Last fragment has full valid data
            } else {
              setAllBits(residue) // Last fragment has partial valid data
            }
          } else {
            setAllBits(mtyWidth)
          }
//        println(
//          f"${simTime()} time: fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, fragLast=${fragLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )

          dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.psnStart #= psnStart
          dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.psnStart #= psnStart
          dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.dlen #= totalLenBytes
          dut.io.readResultCacheDataAndDmaReadRespSegment.resultCacheData.pktNum #= pktNum
          dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes #= totalLenBytes
          dut.io.readResultCacheDataAndDmaReadRespSegment.dmaReadResp.mty #= mty
          dut.io.readResultCacheDataAndDmaReadRespSegment.last #= fragLast
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

      streamSlaveRandomizer(dut.io.txReadResp.pktFrag, dut.clockDomain)
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

      MiscUtils.checkSendWriteReqReadResp(
        dut.clockDomain,
        inputDataQueue,
        outputDataQueue,
        busWidth
      )
    }
  }
}
