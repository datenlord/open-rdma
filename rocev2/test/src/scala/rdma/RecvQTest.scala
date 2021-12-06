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
      qpAttrUpdate: QpAttrUpdateNotifier,
      clockDomain: ClockDomain
  ) {
    def set(psn: Int): Unit = {
      qpAttr.epsn #= psn
      qpAttrUpdate.pulseRqPsnReset #= true
      clockDomain.waitRisingEdge()
      qpAttrUpdate.pulseRqPsnReset #= false
    }
  }

  class PsnGenerator {
    var PSN: Int = -1
    def setPsn(psn: Int): this.type = {
      PSN = psn
      this
    }

    def genOrderedNext(n: Int): List[Int] = {
      val psnList = (PSN until PSN + n).toList
      PSN += n
      psnList
    }
  }

  class TargetPsnWaiter(dut: SeqOut) {
    var psnAll: List[Int] = List()

    fork {
      while (true) {
        dut.io.tx.ready #= !dut.io.tx.ready.toBoolean
        dut.clockDomain.waitRisingEdge(Random.nextInt(8))
      }
    }

    def add(psnList: List[Int]): Unit = {
      psnAll ++= psnList
    }

    def start(): Unit = {
      for (psn <- psnAll) {
        while (
          !(dut.io.tx.bth.psn.toInt == psn &&
            dut.io.tx.valid.toBoolean &&
            dut.io.tx.ready.toBoolean &&
            dut.io.tx.last.toBoolean)
        ) {
          dut.clockDomain.waitRisingEdge()
        }
      }
      psnAll = List()
    }
  }

  def setInputDrivers(
      dut: SeqOut
  ): (Seq[RxDriver], PsnSetter, TargetPsnWaiter) = {
    val rxErrReqDriver =
      new RxDriver(dut.io.rxErrReqResp, dut.clockDomain, OpCode.ACKNOWLEDGE, 1)
    val rxDupReqDriver =
      new RxDriver(dut.io.rxDupReqResp, dut.clockDomain, OpCode.ACKNOWLEDGE, 1)
    val rxSendDriver =
      new RxDriver(dut.io.rxSendResp, dut.clockDomain, OpCode.ACKNOWLEDGE, 1)
    val rxAtomicDriver = new RxDriver(
      dut.io.rxAtomicResp,
      dut.clockDomain,
      OpCode.ATOMIC_ACKNOWLEDGE,
      1
    )
    val rxWriteDriver =
      new RxDriver(dut.io.rxWriteResp, dut.clockDomain, OpCode.ACKNOWLEDGE, 1)
    val rxReadDriver = new RxDriver(
      dut.io.rxReadResp,
      dut.clockDomain,
      OpCode.RDMA_READ_RESPONSE_ONLY,
      8
    )
    val psnSetter = {
      new PsnSetter(dut.io.qpAttr, dut.io.qpAttrUpdate, dut.clockDomain)
    }
    val targetPsnWaiter = new TargetPsnWaiter(dut)
    val rxDrivers = Seq(
      rxErrReqDriver,
      rxDupReqDriver,
      rxSendDriver,
      rxAtomicDriver,
      rxWriteDriver,
      rxReadDriver
    )
    (rxDrivers, psnSetter, targetPsnWaiter)
  }

  def simSeqOut(dut: SeqOut): Unit = {
    dut.clockDomain.forkStimulus(2)
    val (rxDrivers, psnSetter, targetPsnWaiter) = setInputDrivers(dut)
    val psnGen = new PsnGenerator
    val maxPSN = (1 << PSN_WIDTH) - 1
    val maxPacketNum = 0xff

    // test 1, case for psn wrap
    psnSetter.set(maxPSN - 10)
    psnGen.setPsn(maxPSN - 10)
    val psnList1 = (maxPSN - 10 to maxPSN).toList
    val psnList2 = (0 to 10).toList
    rxDrivers(1).push(psnList1)
    rxDrivers(2).push(psnList2)
    targetPsnWaiter.add(psnList1 ++ psnList2)
    targetPsnWaiter.start()

    // test 2, random test
    for (psn <- Seq.fill(64)(Random.nextInt(maxPSN - maxPacketNum))) {
      psnSetter.set(psn)
      psnGen.setPsn(psn)
      for (_ <- 0 to 8) {
        val rxDriver = rxDrivers(Random.nextInt(rxDrivers.length))
        val psnList = psnGen.genOrderedNext(Random.nextInt(maxPacketNum))
        rxDriver.push(psnList)
        targetPsnWaiter.add(psnList)
      }
      targetPsnWaiter.start()
    }
  }

  test("SeqOut test") {
    SimConfig.withWave
      .compile(new SeqOut(BusWidth.W512))
      .doSim { dut => simSeqOut(dut) }
  }
}
