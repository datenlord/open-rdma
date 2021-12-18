package rdma

import org.scalatest.funsuite.AnyFunSuite
import rdma.RdmaConstants.PSN_WIDTH
import spinal.core.sim._

import scala.util.Random
import scala.language.reflectiveCalls

class SeqOutTest extends AnyFunSuite {

  class SeqOutAgents(dut: SeqOut) {
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
    val rxReadDriver = new RdmaDataBusDriver(dut.io.rxReadResp, dut.clockDomain)
    val rxDupReqDriver =
      new RdmaDataBusDriver(dut.io.rxDupReqResp, dut.clockDomain)
    // set default opcode
    rxAtomicDriver.setOpcode(OpCode.ATOMIC_ACKNOWLEDGE)
    rxSendDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxWriteDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxPreCheckErrDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxSendWriteErrDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxReadAtomicErrDriver.setOpcode(OpCode.ACKNOWLEDGE)
    rxReadDriver.setOpcode(OpCode.RDMA_READ_RESPONSE_ONLY)
    rxDupReqDriver.setOpcode(OpCode.RDMA_READ_RESPONSE_ONLY)

    val txMonitor = new RdmaDataBusMonitor(dut.io.tx, dut.clockDomain)

    def rxNonReadDrivers = Seq(
      rxAtomicDriver,
      rxSendDriver,
      rxWriteDriver,
      rxPreCheckErrDriver,
      rxSendWriteErrDriver,
      rxReadAtomicErrDriver
    )

    def rxReadRespDrivers = Seq(
      rxReadDriver,
      rxDupReqDriver
    )

    def setPsn(psn: Int): Unit = {
      dut.io.qpAttr.epsn #= psn
      dut.io.qpAttrUpdate.pulseRqPsnReset #= true
      dut.clockDomain.waitRisingEdge()
      dut.io.qpAttrUpdate.pulseRqPsnReset #= false
    }

    def drivers = rxNonReadDrivers ++ rxReadRespDrivers
  }

  def simPsnWarp(dut: SeqOut): Unit = {
    SimTimeout(10000)
    dut.clockDomain.forkStimulus(2)
    val agent = new SeqOutAgents(dut)
    val maxPsn = (1 << PSN_WIDTH) - 1
    val startPsn = maxPsn - 10

    for (rxDriver <- agent.drivers) {
      agent.setPsn(startPsn)
      val injectedPsnList = rxDriver.injectPacketsByPsn(startPsn, 10)
      agent.txMonitor.addTargetPsn(injectedPsnList)
      waitUntil(agent.txMonitor.allPsnReceived)
    }
  }

  def simDuplicatePsn(dut: SeqOut): Unit = {
    SimTimeout(100)
    dut.clockDomain.forkStimulus(2)
    val agent = new SeqOutAgents(dut)
    val psn = 1024
    agent.setPsn(psn)

    // select any two rx to trigger the assertion
    agent.rxNonReadDrivers(1).injectPacketsByPsn(psn, psn)
    agent.rxNonReadDrivers(2).injectPacketsByPsn(psn, psn)
    agent.txMonitor.addTargetPsn(List(psn))
    waitUntil(agent.txMonitor.allPsnReceived)
  }

  def simStuckByPsn(dut: SeqOut): Unit = {
    SimTimeout(100)
    dut.clockDomain.forkStimulus(2)
    val agent = new SeqOutAgents(dut)
    val targetPsn = 2021
    agent.setPsn(targetPsn)
    // assign any psn except targetPsn to all rxs
    // this case, input psn is selected start from targetPsn + 1
    val psnBase = targetPsn + 1
    for ((driver, id) <- agent.drivers.zipWithIndex) {
      val psn = psnBase + id
      driver.injectPacketsByPsn(psn, psn)
    }
    agent.txMonitor.addTargetPsn(List(targetPsn))
    waitUntil(agent.txMonitor.allPsnReceived)
  }

  def simSeqOutRandom(dut: SeqOut): Unit = {
    SimTimeout(1000000)
    dut.clockDomain.forkStimulus(2)
    val agent = new SeqOutAgents(dut)
    val maxPsn = (1 << PSN_WIDTH) - 1
    val maxPacketsPerSegment = 128
    val nSegments = 16
    val nTestCases = 64

    for (startPsn <- Seq.fill(nTestCases)(Random.nextInt(maxPsn))) {
      agent.setPsn(startPsn)
      // divide a continuous psn sequence into many segments
      // each segment is pushed into a randomly selected rx
      val nPacketsPerSegment =
        Seq.fill(nSegments)(Random.nextInt(maxPacketsPerSegment))

      nPacketsPerSegment.foldLeft(startPsn)({ (currentPsn, nPackets) =>
        val endPsn = (currentPsn + nPackets) % maxPsn
        val selectedDriver = agent.drivers(
          Random.nextInt(agent.drivers.length)
        )
        val injectedPsnList =
          selectedDriver.injectPacketsByPsn(currentPsn, endPsn)
        agent.txMonitor.addTargetPsn(injectedPsnList)
        (endPsn + 1) % maxPsn
      })

      waitUntil(agent.txMonitor.allPsnReceived)
    }
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
