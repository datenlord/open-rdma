package rdma

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

import spinal.core._
import spinal.core.sim._
import StreamSimUtil._
import RdmaTypeReDef._
import SimSettings._

abstract class SendWriteReqGeneratorTest[T <: SendWriteReqGenerator]
    extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U1024

  def simCfg: SimCompiled[T]
  def workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]

  test("zero DMA length send/write request test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      val inputPsnQueue = mutable.Queue[PSN]()
      val outputPsnQueue = mutable.Queue[PSN]()
      val naturalNumItr = NaturalNumber.from(1).iterator

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.retryFlush #= false

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

      MiscUtils.checkExpectedOutputMatch(
        dut.clockDomain,
        inputPsnQueue,
        outputPsnQueue,
        MATCH_CNT
      )
    }
  }

  test("non-zero DMA length send/write request test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(SIM_CYCLE_TIME)

      val inputDataQueue =
        mutable.Queue[(PktFragData, MTY, PktNum, PSN, PktLen, FragLast)]()
      val outputDataQueue = mutable.Queue[(PktFragData, MTY, PSN, FragLast)]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.retryFlush #= false
      dut.io.cachedWorkReqAndDmaReadResp.valid #= false
      dut.clockDomain.waitSampling()

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)

      DmaReadRespSim.pktFragStreamMasterDriver(
        dut.io.cachedWorkReqAndDmaReadResp,
        dut.clockDomain,
        getDmaReadRespPktDataFunc =
          (cachedWorkReqAndDmaReadResp: CachedWorkReqAndDmaReadResp) =>
            cachedWorkReqAndDmaReadResp.dmaReadResp,
        segmentRespByPmtu = true
      ) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val payloadLenBytes = payloadLenItr.next()

        (
          psnStart,
          totalFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          payloadLenBytes.toLong
        )
      } {
        (
            _, // psn,
            psnStart,
            _, // fragLast,
            _, // fragIdx,
            _, // totalFragNum,
            _, // pktIdx,
            pktNum,
            payloadLenBytes
        ) =>
//        println(
//          f"${simTime()} time: fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, fragLast=${fragLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )

          dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.psnStart #= psnStart
          dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.lenBytes #= payloadLenBytes
          dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode #= workReqOpCode
          dut.io.cachedWorkReqAndDmaReadResp.cachedWorkReq.pktNum #= pktNum
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
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new SendReqGenerator(busWidth))
  override val workReqOpCode = WorkReqOpCode.SEND_WITH_INV
}

class WriteReqGeneratorTest
    extends SendWriteReqGeneratorTest[WriteReqGenerator] {
  override val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new WriteReqGenerator(busWidth))
  override val workReqOpCode = WorkReqOpCode.RDMA_WRITE_WITH_IMM
}
