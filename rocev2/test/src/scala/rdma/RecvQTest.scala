package rdma

import org.scalatest.funsuite.AnyFunSuite
import rdma.RdmaConstants.PSN_WIDTH
import spinal.core.sim._

import scala.util.Random

class SeqOutTest extends AnyFunSuite {

  def bindDrivers(dut: SeqOut): (
      Seq[RdmaNonReadRespBusDriver],
      Seq[RdmaDataBusDriver],
      RdmaDataBusMonitor
  ) = {
    val rxAtomicDriver =
      new RdmaNonReadRespBusDriver(dut.io.rxAtomicResp, dut.clockDomain)
    val rxSendDriver =
      new RdmaNonReadRespBusDriver(dut.io.rxSendResp, dut.clockDomain)
    val rxWriteDriver =
      new RdmaNonReadRespBusDriver(dut.io.rxWriteResp, dut.clockDomain)
    val rxPreCheckErrDriver =
      new RdmaNonReadRespBusDriver(dut.io.rxPreCheckErrResp, dut.clockDomain)
    val rxSendWriteErrDriver =
      new RdmaNonReadRespBusDriver(dut.io.rxSendWriteErrResp, dut.clockDomain)
    val rxReadAtomicErrDriver =
      new RdmaNonReadRespBusDriver(dut.io.rxReadAtomicErrResp, dut.clockDomain)
    rxAtomicDriver.setOpcode(OpCode.ATOMIC_ACKNOWLEDGE)
    rxSendDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxWriteDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxPreCheckErrDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxSendWriteErrDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxReadAtomicErrDriver.setOpcode(OpCode.ACKNOWLEDGE)

    val rxReadDriver = new RdmaDataBusDriver(dut.io.rxReadResp, dut.clockDomain)
    val rxDupReqDriver =
      new RdmaDataBusDriver(dut.io.rxDupReqResp, dut.clockDomain)
    rxReadDriver.setOpcode(OpCode.RDMA_READ_RESPONSE_ONLY)
    rxDupReqDriver.setOpcode(OpCode.RDMA_READ_RESPONSE_ONLY)

    val rxNonReadRespDrivers = Seq(
      rxAtomicDriver,
      rxSendDriver,
      rxWriteDriver,
      rxPreCheckErrDriver,
      rxSendWriteErrDriver,
      rxReadAtomicErrDriver
    )
    val rxReadRespDrivers = Seq(
      rxReadDriver,
      rxDupReqDriver
    )

    val txMonitor = new RdmaDataBusMonitor(dut.io.tx, dut.clockDomain)
    (rxNonReadRespDrivers, rxReadRespDrivers, txMonitor)
  }

  def setPsn(dut: SeqOut, psn: Int): Unit = {
    dut.io.qpAttr.epsn #= psn
    dut.io.qpAttrUpdate.pulseRqPsnReset #= true
    dut.clockDomain.waitRisingEdge()
    dut.io.qpAttrUpdate.pulseRqPsnReset #= false
  }

  def simPsnWarp(dut: SeqOut): Unit = {
    SimTimeout(10000)
    dut.clockDomain.forkStimulus(2)
    val (nonReadRespDrivers, readRespDrivers, txMonitor) = bindDrivers(dut)
    val maxPsn = (1 << PSN_WIDTH) - 1
    val startPsn = maxPsn - 10
    for (driver <- nonReadRespDrivers) {
      setPsn(dut, startPsn)
      val injectedPsnList = driver.injectPacketsByPsn(startPsn, 10)
      txMonitor.addTargetPsn(injectedPsnList)
      waitUntil(txMonitor.allPsnReceived)
    }
    for (driver <- readRespDrivers) {
      setPsn(dut, startPsn)
      val injectedPsnList = driver.injectPacketsByPsn(startPsn, 10)
      txMonitor.addTargetPsn(injectedPsnList)
      waitUntil(txMonitor.allPsnReceived)
    }
  }

  def simDuplicatePsn(dut: SeqOut): Unit = {
    SimTimeout(100)
    dut.clockDomain.forkStimulus(2)
    val psn = 1024
    setPsn(dut, psn)
    val (nonReadRespDrivers, _, txMonitor) = bindDrivers(dut)
    // select any two rx to trigger the assertion
    nonReadRespDrivers(1).injectPacketsByPsn(psn, psn)
    nonReadRespDrivers(2).injectPacketsByPsn(psn, psn)
    txMonitor.addTargetPsn(List(psn))
    waitUntil(txMonitor.allPsnReceived)
  }

  def simStuckByPsn(dut: SeqOut): Unit = {
    SimTimeout(100)
    dut.clockDomain.forkStimulus(2)
    val (nonReadRespDrivers, readRespDrivers, txMonitor) = bindDrivers(dut)
    val targetPsn = 2021
    setPsn(dut, targetPsn)
    // assign any psn except targetPsn to all rxs
    // this case, input psn is selected start from targetPsn + 1
    val psnBase1 = targetPsn + 1
    for ((driver, id) <- nonReadRespDrivers.zipWithIndex) {
      val psn = psnBase1 + id
      driver.injectPacketsByPsn(psn, psn)
    }
    val psnBase2 = psnBase1 + nonReadRespDrivers.length
    for ((driver, id) <- readRespDrivers.zipWithIndex) {
      val psn = psnBase2 + id
      driver.injectPacketsByPsn(psn, psn)
    }
    txMonitor.addTargetPsn(List(targetPsn))
    waitUntil(txMonitor.allPsnReceived)
  }

  def simSeqOutRandom(dut: SeqOut): Unit = {
    dut.clockDomain.forkStimulus(2)
    val (nonReadRespDrivers, readRespDrivers, txMonitor) = bindDrivers(dut)
    val maxPSN = (1 << PSN_WIDTH) - 1
    val maxPacketsNum = 1024
    val nTestCases = 64
    // TODO: random test
  }

  test("SeqOut test case for PSN warp") {
    SimConfig.withWave
      .compile(new SeqOut(BusWidth.W512))
      .doSim { dut => simPsnWarp(dut) }
  }

  test("cache duplicate psn input") {
    intercept[Throwable] {
      SimConfig.withWave
        .compile(new SeqOut(BusWidth.W512))
        .doSim { dut => simDuplicatePsn(dut) }
    }
  }

  test("cache SeqOut stuck error") {
    intercept[Throwable] {
      SimConfig.withWave
        .compile(new SeqOut(BusWidth.W512))
        .doSim { dut => simStuckByPsn(dut) }
    }
  }

  test("SeqOut random test") {
    SimConfig.withWave
      .compile(new SeqOut(BusWidth.W512))
      .doSim { dut => simSeqOutRandom(dut) }
  }
}
