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
      dut.io.rxQCtrl.stateErrFlush #= false

      // Input to DUT
      streamMasterDriver(
        dut.io.readRstCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        val psn = naturalNumItr.next()
        dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.psnStart #= psn
        dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.psnStart #= psn
        dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.pktNum #= 0
        dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.dlen #= 0
        dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes #= 0
        dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.mty #= 0
        dut.io.readRstCacheDataAndDmaReadRespSegment.last #= true
      }
      onStreamFire(
        dut.io.readRstCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        inputPsnQueue.enqueue(
          dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.psnStart.toInt
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
      dut.io.rxQCtrl.stateErrFlush #= false
      dut.io.readRstCacheDataAndDmaReadRespSegment.valid #= false
//      dut.clockDomain.waitSampling()

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)
      pktFragStreamMasterDriver(
        dut.io.readRstCacheDataAndDmaReadRespSegment,
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

          dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.psnStart #= psnStart
          dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.psnStart #= psnStart
          dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.dlen #= totalLenBytes
          dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.pktNum #= pktNum
          dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes #= totalLenBytes
          dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.mty #= mty
          dut.io.readRstCacheDataAndDmaReadRespSegment.last #= fragLast
      }
      onStreamFire(
        dut.io.readRstCacheDataAndDmaReadRespSegment,
        dut.clockDomain
      ) {
        inputDataQueue.enqueue(
          (
            dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.data.toBigInt,
            dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.mty.toBigInt,
            dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.pktNum.toInt,
            dut.io.readRstCacheDataAndDmaReadRespSegment.resultCacheData.psnStart.toInt,
            dut.io.readRstCacheDataAndDmaReadRespSegment.dmaReadResp.lenBytes.toLong,
            dut.io.readRstCacheDataAndDmaReadRespSegment.last.toBoolean
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
