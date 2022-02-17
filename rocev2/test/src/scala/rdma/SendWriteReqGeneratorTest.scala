package rdma

import spinal.core.sim._
import ConstantSettings._
import StreamSimUtil._

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite
import spinal.core.SpinalEnumElement

abstract class SendWriteReqGeneratorTest[T <: SendWriteReqGenerator]
    extends AnyFunSuite {
  val busWidth = BusWidth.W512

  def simCfg: SimCompiled[T]
  def workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]

  test("zero DMA length send/write request test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val inputPsnQueue = mutable.Queue[Int]()
      val outputPsnQueue = mutable.Queue[Int]()
      val naturalNumItr = NaturalNumber.from(1).iterator

      val pmtuLen = PMTU.U1024
      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.sendQCtrl.wrongStateFlush #= false

      // Input to DUT
      streamMasterDriver(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain) {
        val psn = naturalNumItr.next()
        dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.psnStart #= psn
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart #= psn
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.pktNum #= 0
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes #= 0
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode #= workReqOpCode
        dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.lenBytes #= 0
        dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.mty #= 0
        dut.io.cachedWorkReqAndDmaReadResp.last #= true
      }
      onStreamFire(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain) {
        inputPsnQueue.enqueue(
          dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt
        )
      }

      // Check DUT output
      streamSlaveRandomizer(dut.io.txReq.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txReq.pktFrag, dut.clockDomain) {
        outputPsnQueue.enqueue(dut.io.txReq.pktFrag.bth.psn.toInt)
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputPsnQueue,
        outputPsnQueue,
        MATCH_CNT
      )
    }
  }

  test("non-zero DMA length send/write request test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      val pmtuLen = PMTU.U1024
      val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)
      val fragNumPerPkt =
        SendWriteReqReadRespInputGen.maxFragNumPerPkt(pmtuLen, busWidth)

      val inputDataQueue =
        mutable.Queue[(BigInt, BigInt, Int, Int, Long, Boolean)]()
      val outputDataQueue = mutable.Queue[(BigInt, BigInt, Int, Boolean)]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.sendQCtrl.wrongStateFlush #= false
      dut.io.cachedWorkReqAndDmaReadResp.valid #= false
      dut.clockDomain.waitSampling()

      // Input to DUT
      val (fragNumItr, pktNumItr, psnItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)
      fragmentStreamMasterDriver(
        dut.io.cachedWorkReqAndDmaReadResp,
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
//          f"${simTime()} time: fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, isLast=${isLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )

        dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.psnStart #= psnStart
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart #= psnStart
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes #= totalLenBytes
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode #= workReqOpCode
        dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.pktNum #= pktNum
        dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.lenBytes #= totalLenBytes
        dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.mty #= mty
        dut.io.cachedWorkReqAndDmaReadResp.last #= isLast
      }

      onStreamFire(dut.io.cachedWorkReqAndDmaReadResp, dut.clockDomain) {
        inputDataQueue.enqueue(
          (
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.data.toBigInt,
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.mty.toBigInt,
            dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.pktNum.toInt,
            dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart.toInt,
            dut.io.cachedWorkReqAndDmaReadResp.dmaReadResp.lenBytes.toLong,
            dut.io.cachedWorkReqAndDmaReadResp.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.txReq.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.txReq.pktFrag, dut.clockDomain) {
        outputDataQueue.enqueue(
          (
            dut.io.txReq.pktFrag.data.toBigInt,
            dut.io.txReq.pktFrag.mty.toBigInt,
            dut.io.txReq.pktFrag.bth.psn.toInt,
            dut.io.txReq.pktFrag.last.toBoolean
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

class SendReqGeneratorTest extends SendWriteReqGeneratorTest[SendReqGenerator] {
  override val simCfg = SimConfig.allOptimisation.withWave
    .compile(new SendReqGenerator(busWidth))
  override val workReqOpCode = WorkReqOpCode.SEND_WITH_INV
}

class WriteReqGeneratorTest
    extends SendWriteReqGeneratorTest[WriteReqGenerator] {
  override val simCfg = SimConfig.allOptimisation.withWave
    .compile(new WriteReqGenerator(busWidth))
  override val workReqOpCode = WorkReqOpCode.RDMA_WRITE_WITH_IMM
}
