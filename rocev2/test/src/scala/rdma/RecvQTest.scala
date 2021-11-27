package rdma

import org.scalatest.funsuite.AnyFunSuite
import spinal.core
import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable
import scala.tools.nsc.doc.base.comment.Body
import scala.util.Random

/** The SeqOut module can be divided into two channels
  * -- rxOtherRespSort channel sorts the packet form io.rxOtherResp. We can firstly verify
  *    this channel by set io.rxReadResp.valid false so that we can focus on sorting function.
  *
  * -- rxReadResp channel is simple. When above is verified, test the arbitration logic
  */
class SeqOutTest extends AnyFunSuite {

  class RxDriver(
      rx: Stream[Fragment[RdmaDataBus]],
      clockDomain: ClockDomain,
      fragments: Int
  ) {
    val psnQueue = new mutable.Queue[Int]
    rx.valid #= false
    rx.last #= false

    def push(psnList: List[Int]): Unit = {
      psnQueue ++= psnList
    }

    def clear(): Unit = {
      psnQueue.clear()
    }

    val driver = fork {
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

  class PsnSetter(
      qpAttr: QpAttrData,
      qpAttrUpdate: Stream[Bits],
      clockDomain: ClockDomain
  ) {
    qpAttrUpdate.valid #= false
    qpAttrUpdate.payload #= QpAttrMask.QP_RQ_PSN.id

    def set(psn: Int): Unit = {
      qpAttrUpdate.valid #= true
      qpAttr.epsn #= psn
      clockDomain.waitSamplingWhere(qpAttrUpdate.ready.toBoolean)
      qpAttrUpdate.valid #= false
      // println(s"psn is set to $psn")
    }
  }

  // use buffer depth as max distance
  class PsnGenerator(maxDistance: Int) {
    var PSN: Int = -1

    def setPsn(psn: Int): this.type = {
      PSN = psn
      this
    }

    def genNextDisorder(n: Int): List[Int] = {
      // generate disorder PSNs which do not contribute dead stuck
      assert(PSN != (-1))
      var psnList = (PSN until PSN + n).toList
      var moved = List.fill(n)(false)
      var distance = maxDistance / 2
      for (i <- psnList.indices) {
        val r = Random.nextInt(distance)
        val j = i + r
        if (j < psnList.length && !moved(i)) {
          val t = psnList(j)
          psnList = psnList.updated(j, psnList(i))
          psnList = psnList.updated(i, t)
          moved = moved.updated(j, true)
        }
      }
      // println(s"generate psn from $PSN until ${PSN + n}")
      PSN += n
      psnList
    }

    def genNextOrder(n: Int): List[Int] = {
      val psnList = (PSN until PSN + n).toList
      // println(s"generate psn from $PSN until ${PSN + n}")
      PSN += n
      psnList
    }
  }

  def setInputDrivers(dut: SeqOut): (RxDriver, RxDriver, PsnSetter) = {
    val rxOtherDriver = new RxDriver(dut.io.rxOtherResp, dut.clockDomain, 1)
    val rxReadDriver = new RxDriver(dut.io.rxReadResp, dut.clockDomain, 8)
    val psnSetter =
      new PsnSetter(dut.io.qpAttr, dut.io.qpAttrUpdate, dut.clockDomain)
    (rxOtherDriver, rxReadDriver, psnSetter)
  }

  def waitTargetPsn(dut: SeqOut, psnList: List[Int]): Unit = {
    for (psn <- psnList) {
      waitTargetPsn(dut, psn)
    }
  }

  def waitTargetPsn(dut: SeqOut, psn: Int): Unit = {
    var timeOut = 1000
    while (
      !(dut.io.tx.bth.psn.toInt == psn &&
        dut.io.tx.valid.toBoolean &&
        dut.io.tx.ready.toBoolean &&
        dut.io.tx.last.toBoolean)
    ) {
      timeOut -= 1
      dut.clockDomain.waitRisingEdge()
      if (timeOut == 0) {
        simFailure(
          Seq(
            s"sim time out when waiting psn = ${psn}",
            s"rxOtherResp.psn = ${dut.io.rxOtherResp.bth.psn.toInt}, valid ${dut.io.rxOtherResp.valid.toBoolean}, ready ${dut.io.rxOtherResp.ready.toBoolean}",
            s"rxReadResp.psn = ${dut.io.rxReadResp.bth.psn.toInt}, valid ${dut.io.rxReadResp.valid.toBoolean}, ready ${dut.io.rxReadResp.ready.toBoolean}",
            s"tx.psn          = ${dut.io.tx.bth.psn.toInt}, valid ${dut.io.tx.valid.toBoolean}, ready ${dut.io.tx.ready.toBoolean}"
          ).reduce((a, b) => a ++ "\n" ++ b)
        )
      }
    }
  }

  def simRxOtherRespSimple(dut: SeqOut, bufferDepth: Int): Unit = {
    SimTimeout(10000)
    dut.clockDomain.forkStimulus(2)
    dut.io.tx.ready #= true
    val (rxOtherDriver, _, psnSetter) = setInputDrivers(dut)
    // test 1 simple test
    psnSetter.set(200)
    rxOtherDriver.push((200 until 222).toList)
    rxOtherDriver.push((222 until 250).reverse.toList)
    rxOtherDriver.push((250 to 300).toList)
    waitTargetPsn(dut, 300)

    // test 2 with random tx.ready
    psnSetter.set(20230)
    rxOtherDriver.push((20230 to 20520).toList)
    var test3End = false
    fork {
      while (test3End) {
        dut.io.tx.ready #= !dut.io.tx.ready.toBoolean
        dut.clockDomain.waitRisingEdge(Random.nextInt(8))
      }
    }
    waitTargetPsn(dut, 20520)
    test3End = true

    // test 3 construct a stuck
    dut.io.tx.ready #= true
    psnSetter.set(1000)
    var stuckPsn = 1000 + bufferDepth
    rxOtherDriver.push(List(stuckPsn))
    // the 'ready' fall means stuck
    dut.clockDomain.waitRisingEdge()
    assert(!dut.io.rxOtherResp.ready.toBoolean)
    // let it pass
    psnSetter.set(stuckPsn)
    dut.clockDomain.waitRisingEdge(2)
    assert(dut.io.rxOtherResp.ready.toBoolean)

    // test 4 construct another stuck
    psnSetter.set(1000)
    stuckPsn = 1000 + bufferDepth * 2
    rxOtherDriver.push(List(stuckPsn))
    dut.clockDomain.waitRisingEdge()
    assert(!dut.io.rxOtherResp.ready.toBoolean)
    psnSetter.set(stuckPsn)
    dut.clockDomain.waitRisingEdge(2)
    assert(dut.io.rxOtherResp.ready.toBoolean)
  }

  def simRandomBase(
      dut: SeqOut,
      bufferDepth: Int,
      simTask: (RxDriver, RxDriver, PsnGenerator, Int) => Unit
  ): Unit = {
    dut.clockDomain.forkStimulus(2)
    fork {
      while (true) {
        dut.io.tx.ready #= !dut.io.tx.ready.toBoolean
        dut.clockDomain.waitRisingEdge(Random.nextInt(bufferDepth))
      }
    }
    val (rxOtherDriver, rxReadDriver, psnSetter) = setInputDrivers(dut)
    val psnGen = new PsnGenerator(bufferDepth)

    for (_ <- 0 to 100) {
      // the psn width is 24, so...
      val psn = Random.nextInt(0xffffff)
      var packetNum = Random.nextInt(0xfff)
      if (psn + packetNum > 0xffffff) {
        packetNum = 0xffffff - psn
      }
      psnSetter.set(psn)
      psnGen.setPsn(psn)
      simTask(rxOtherDriver, rxReadDriver, psnGen, packetNum)
      waitTargetPsn(dut, (psn until psn + packetNum).toList)
    }
  }

  def simRxOtherRespRandom(dut: SeqOut, bufferDepth: Int): Unit = {
    simRandomBase(
      dut,
      bufferDepth,
      (rxOtherDriver, rxReadRespDriver, psnGen, packetNum) => {
        rxOtherDriver.push(psnGen.genNextDisorder(packetNum))
      }
    )
  }

  def simSeqOut(dut: SeqOut, bufferDepth: Int): Unit = {
    dut.clockDomain.forkStimulus(2)
    val (rxOtherDriver, rxReadDriver, psnSetter) = setInputDrivers(dut)
    val psnGen = new PsnGenerator(bufferDepth)
    dut.io.tx.ready #= true

    // test 1
    psnSetter.set(0)
    rxOtherDriver.push((0 until 20).toList)
    rxReadDriver.push((20 until 30).toList)
    rxOtherDriver.push((30 until 50).toList)
    waitTargetPsn(dut, (0 until 50).toList)

    // test 2
    psnSetter.set(100)
    psnGen.setPsn(100)
    rxOtherDriver.push(psnGen.genNextDisorder(20))
    rxReadDriver.push(psnGen.genNextOrder(10))
    rxOtherDriver.push(psnGen.genNextDisorder(20))
    waitTargetPsn(dut, (100 until 150).toList)

    // test 3 bubble
    psnSetter.set(100)
    rxOtherDriver.push(List(100, 102))
    rxReadDriver.push(List(101))
    // switch rxOtherResp to rxReadResp, not bubble
    waitTargetPsn(dut, 100)
    dut.clockDomain.waitRisingEdge()
    assert(dut.io.tx.bth.psn.toInt == 101)
    assert(dut.io.tx.valid.toBoolean)
    // switch rxReadResp to rxOtherResp, a bubble
    waitTargetPsn(dut, 101)
    dut.clockDomain.waitRisingEdge()
    assert(!dut.io.tx.valid.toBoolean)
    dut.clockDomain.waitRisingEdge()
    assert(dut.io.tx.bth.psn.toInt == 102)
    assert(dut.io.tx.valid.toBoolean)
  }

  def simSeqOutRandom(dut: SeqOut, bufferDepth: Int): Unit = {
    simRandomBase(
      dut,
      bufferDepth,
      (rxOtherDriver, rxReadDriver, psnGen, packetNum) => {
        var nPacketGenerated = 0
        while (nPacketGenerated < packetNum) {
          var n, m = Random.nextInt(0xff)
          if (nPacketGenerated + n + m > packetNum) {
            n = packetNum - nPacketGenerated
            m = 0
          }
          rxOtherDriver.push(psnGen.genNextDisorder(n))
          rxReadDriver.push(psnGen.genNextOrder(m))
          nPacketGenerated += n + m
        }
      }
    )
  }

  test("rxOtherResp simple test") {
    val bufferDepth = 64
    SimConfig.withWave
      .compile(new SeqOut(BusWidth.W128, bufferDepth, false))
      .doSim(simRxOtherRespSimple(_, bufferDepth))
  }

  test("rxOtherResp random test") {
    for (bufferDepth <- List(32, 64, 128, 256, 512)) {
      SimConfig.withWave
        .compile(new SeqOut(BusWidth.W128, bufferDepth, false))
        .doSim { simRxOtherRespRandom(_, bufferDepth) }
    }
  }

  test("SeqOut test") {
    val bufferDepth = 64
    SimConfig.withWave
      .compile(new SeqOut(BusWidth.W128, bufferDepth, false))
      .doSim(simSeqOut(_, bufferDepth))
  }

  test("SeqOut test random") {
    for (bufferDepth <- List(32, 64, 128, 256, 512)) {
      SimConfig.withWave
        .compile(new SeqOut(BusWidth.W128, bufferDepth, false))
        .doSim { simSeqOutRandom(_, bufferDepth) }
    }
  }
}
