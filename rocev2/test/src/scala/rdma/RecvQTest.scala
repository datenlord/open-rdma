package rdma

import org.scalatest.funsuite.AnyFunSuite
import rdma.RdmaConstants.PSN_WIDTH
import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable
import scala.util.Random

class SeqOutTest extends AnyFunSuite {

  class RxDriver(
      rx: Stream[Fragment[RdmaDataBus]],
      clockDomain: ClockDomain,
      opCode: OpCode.OpCode,
      fragments: Int
  ) {
    val psnQueue = new mutable.Queue[Int]
    rx.valid #= false
    rx.last #= false
    rx.bth.opcodeFull #= opCode.id

    def push(psnList: List[Int]): Unit = {
      psnQueue ++= psnList
    }

    def clear(): Unit = {
      psnQueue.clear()
    }

    fork {
      while (true) {
        if (psnQueue.nonEmpty) {
          rx.bth.psn #= psnQueue.dequeue()
          rx.valid #= true
          for (i <- 1 to fragments) {
            if (i == fragments) {
              rx.last #= true
            }
            clockDomain.waitSamplingWhere(rx.ready.toBoolean)
            rx.last #= false
          }
        } else {
          rx.valid #= false
          clockDomain.waitRisingEdge()
        }
      }
    }
  }

  def setPsn(dut: SeqOut, psn: Int): Unit = {
    dut.io.qpAttr.epsn #= psn
    dut.io.qpAttrUpdate.pulseRqPsnReset #= true
    dut.clockDomain.waitRisingEdge()
    dut.io.qpAttrUpdate.pulseRqPsnReset #= false
  }

  def psnGen(startPsn: Int, n: Int): (List[Int], Int) = {
    val maxPSN = (1 << PSN_WIDTH) - 1
    val startPsnNext = startPsn + n
    if (startPsnNext > maxPSN) {
      val startPsnNextWrapped = startPsnNext - maxPSN
      val psnList =
        (startPsn to maxPSN).toList ++ (0 until startPsnNextWrapped).toList
      (psnList, startPsnNextWrapped)
    } else {
      val psnList = (startPsn until startPsnNext).toList
      (psnList, startPsnNext)
    }
  }

  class TargetPsnWaiter(dut: SeqOut) {

    fork {
      val readyInterval = 8
      while (true) {
        dut.io.tx.ready #= !dut.io.tx.ready.toBoolean
        dut.clockDomain.waitRisingEdge(Random.nextInt(readyInterval))
      }
    }

    def wait(psnList: List[Int]): Unit = {
      for (psn <- psnList) {
        while (
          !(dut.io.tx.bth.psn.toInt == psn &&
            dut.io.tx.valid.toBoolean &&
            dut.io.tx.ready.toBoolean &&
            dut.io.tx.last.toBoolean)
        ) {
          dut.clockDomain.waitRisingEdge()
        }
      }
    }
  }

  def setInputDrivers(
      dut: SeqOut
  ): (Seq[RxDriver], TargetPsnWaiter) = {
    val rxReadRespFragmentsLen = 8
    val rxOtherRespFragmentsLen = 1

    val rxAtomicDriver = new RxDriver(
      dut.io.rxAtomicResp,
      dut.clockDomain,
      OpCode.ATOMIC_ACKNOWLEDGE,
      rxOtherRespFragmentsLen
    )
    val rxReadDriver = new RxDriver(
      dut.io.rxReadResp,
      dut.clockDomain,
      OpCode.RDMA_READ_RESPONSE_ONLY,
      rxReadRespFragmentsLen
    )
    val rxErrDriver = new RxDriver(
      dut.io.rxErrReqResp,
      dut.clockDomain,
      OpCode.ACKNOWLEDGE,
      rxOtherRespFragmentsLen
    )
    val rxDupReqDriver = new RxDriver(
      dut.io.rxDupReqResp,
      dut.clockDomain,
      OpCode.ACKNOWLEDGE,
      rxOtherRespFragmentsLen
    )
    val rxSendDriver = new RxDriver(
      dut.io.rxSendResp,
      dut.clockDomain,
      OpCode.ACKNOWLEDGE,
      rxOtherRespFragmentsLen
    )

    val rxDrivers = Seq(
      rxAtomicDriver,
      rxReadDriver,
      rxErrDriver,
      rxDupReqDriver,
      rxSendDriver
    )

    val targetPsnWaiter = new TargetPsnWaiter(dut)
    (rxDrivers, targetPsnWaiter)
  }

  def simPsnWarp(dut: SeqOut): Unit = {
    dut.clockDomain.forkStimulus(2)
    val (rxDrivers, targetPsnWaiter) = setInputDrivers(dut)
    val maxPSN = (1 << PSN_WIDTH) - 1
    val nPackets = 10
    val startPsn = maxPSN - nPackets / 2
    val (psnList, _) = psnGen(startPsn, nPackets)

    rxDrivers.foreach(driver => {
      setPsn(dut, startPsn)
      driver.push(psnList)
      targetPsnWaiter.wait(psnList)
    })
  }

  def simSeqOutRandom(dut: SeqOut): Unit = {
    dut.clockDomain.forkStimulus(2)
    val (rxDrivers, targetPsnWaiter) = setInputDrivers(dut)
    val maxPSN = (1 << PSN_WIDTH) - 1
    val maxPacketsNum = 256
    val nTestCases = 64

    for (psn <- Seq.fill(nTestCases)(Random.nextInt(maxPSN))) {
      setPsn(dut, psn)

      val endPsn = rxDrivers.indices
        .foldLeft(psn)((startPsn, _) => {
          val (psnList, startPsnNext) =
            psnGen(startPsn, Random.nextInt(maxPacketsNum))
          val rxDriver = rxDrivers(Random.nextInt(rxDrivers.length))
          rxDriver.push(psnList)
          startPsnNext
        })

      targetPsnWaiter.wait((psn until endPsn).toList)
    }
  }

  test("SeqOut test case for PSN warp") {
    SimConfig.withWave
      .compile(new SeqOut(BusWidth.W512))
      .doSim { dut => simPsnWarp(dut) }
  }

  test("SeqOut random test") {
    SimConfig.withWave
      .compile(new SeqOut(BusWidth.W512))
      .doSim { dut => simSeqOutRandom(dut) }
  }
}
