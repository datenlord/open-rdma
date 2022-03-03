package rdma

import spinal.core._
import spinal.core.sim._
import ConstantSettings._
import RdmaConstants._
import StreamSimUtil._
//import AethSim._
//import BthSim._
import TypeReDef._

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

class ReqCommCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(
      new SpinalConfig(defaultClockDomainFrequency = FixedFrequency(200 MHz))
    )
    .compile(new ReqCommCheck(busWidth))

  test("ReqCommCheck normal case test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val pmtuLen = PMTU.U256
      val maxFragNum = 17
      val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputQueue = mutable.Queue[(PSN, RdmaFragData, FragLast)]()
      val outputQueue = mutable.Queue[(PSN, RdmaFragData, FragLast)]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.qpAttr.rqOutPsn #= 0
      dut.io.rxQCtrl.flush #= false

      pktFragStreamMasterDriver(dut.io.rx.pktFrag, dut.clockDomain) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val totalLenBytes = totalLenItr.next()
        val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
//        println(
//          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        (
          psnStart,
          totalFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          (totalLenBytes, workReqOpCode)
        )
      } { (psn, _, fragLast, fragIdx, fragNum, pktIdx, pktNum, internalData) =>
        // ePSN match input request packet PSN
        dut.io.qpAttr.epsn #= psn

        val (totalLenBytes, workReqOpCode) = internalData
        // Only RC is supported
//        dut.io.rx.pktFrag.bth.transport #= Transports.RC.id
        dut.io.rx.pktFrag.bth.opcodeFull #= WorkReqSim.assignOpCode(
          workReqOpCode,
          pktIdx,
          pktNum
        )
        dut.io.rx.pktFrag.bth.psn #= psn
        dut.io.rx.pktFrag.last #= fragLast
        if (fragIdx == fragNum - 1) {
          val finalFragValidBytes = totalLenBytes % mtyWidth
          val leftShiftAmt = mtyWidth - finalFragValidBytes
          dut.io.rx.pktFrag.mty #= setAllBits(
            finalFragValidBytes
          ) << leftShiftAmt
          dut.io.rx.pktFrag.bth.padCnt #= (PAD_COUNT_FULL - (totalLenBytes % PAD_COUNT_FULL)) % PAD_COUNT_FULL
        } else {
          dut.io.rx.pktFrag.mty #= setAllBits(mtyWidth)
          dut.io.rx.pktFrag.bth.padCnt #= 0
        }
//        println(
//          f"${simTime()} time: WR opcode=${workReqOpCode}, fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        inputQueue.enqueue(
          (
            dut.io.rx.pktFrag.bth.psn.toInt,
            dut.io.rx.pktFrag.data.toBigInt,
            dut.io.rx.pktFrag.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.tx.checkOutput, dut.clockDomain)
      onStreamFire(dut.io.tx.checkOutput, dut.clockDomain) {
        outputQueue.enqueue(
          (
            dut.io.tx.checkOutput.pktFrag.bth.psn.toInt,
            dut.io.tx.checkOutput.pktFrag.data.toBigInt,
            dut.io.tx.checkOutput.last.toBoolean
          )
        )
      }

      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.txDupReq.pktFrag.valid.toBoolean
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
    }
  }
}
