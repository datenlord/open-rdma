package rdma

import rdma.RdmaConstants.PSN_WIDTH
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._

import scala.collection.mutable

abstract class RdmaPacketDriver[T <: Data, P <: RdmaBasePacket](
    bus: Stream[T],
    payload: P,
    clockDomain: ClockDomain
) {
  val cmdQueue = mutable.Queue[P => Unit]()
  def waitPacketSendOver(): Unit

  def setOpcode(opCode: OpCode.OpCode): Unit = {
    payload.bth.opcode #= opCode.id
  }

  def injectPacketsByPsn(startPsn: Int, endPsn: Int): List[Int] = {
    val maxPsn = (1 << PSN_WIDTH) - 1
    assert(startPsn <= maxPsn)
    assert(endPsn <= maxPsn)

    val psnList = if (startPsn <= endPsn) {
      (startPsn to endPsn).toList
    } else {
      (startPsn to maxPsn).toList ++ (0 to endPsn).toList
    }
    for (psn <- psnList) {
      cmdQueue.enqueue(b => b.bth.psn #= psn)
    }
    psnList
  }

  bus.valid #= false
  payload.bth.transport #= Transports.RC.id
  payload.bth.opcode #= 0
  payload.bth.solicited #= false
  payload.bth.migreq #= false
  payload.bth.padcount #= 0
  payload.bth.version #= 0
  payload.bth.pkey #= 0xffff // Default PKEY
  payload.bth.fecn #= false
  payload.bth.becn #= false
  payload.bth.resv6 #= 0
  payload.bth.dqpn #= 0
  payload.bth.ackreq #= false
  payload.bth.resv7 #= 0
  payload.bth.psn #= 0
  payload.eth #= 0

  fork {
    while (true) {
      if (cmdQueue.nonEmpty) {
        cmdQueue.dequeue().apply(payload)
        bus.valid #= true
        waitPacketSendOver()
      } else {
        bus.valid #= false
        clockDomain.waitRisingEdge()
      }
    }
  }
}

class RdmaDataBusDriver(
    bus: Stream[Fragment[RdmaDataBus]],
    clockDomain: ClockDomain
) extends RdmaPacketDriver(bus, bus.fragment, clockDomain) {
  bus.last #= false

  def waitPacketSendOver() = {
    val fragmentsPerReadRespPacket = 4 * 1024 / BusWidth.W512.id
    for (i <- 1 to fragmentsPerReadRespPacket) {
      if (i == fragmentsPerReadRespPacket) {
        bus.last #= true
      }
      clockDomain.waitSamplingWhere(bus.ready.toBoolean)
      bus.last #= false
    }
  }
}

class RdmaNonReadRespBusDriver(
    bus: Stream[RdmaNonReadRespBus],
    clockDomain: ClockDomain
) extends RdmaPacketDriver(bus, bus.payload, clockDomain) {
  def waitPacketSendOver() = {
    clockDomain.waitSamplingWhere(bus.ready.toBoolean)
  }
}

class RdmaDataBusMonitor(
    bus: Stream[Fragment[RdmaDataBus]],
    clockDomain: ClockDomain
) {
  val targetPsnQueue = mutable.Queue[Int]()

  StreamReadyRandomizer(bus, clockDomain)
  StreamMonitor(bus, clockDomain) { tx =>
    if (tx.last.toBoolean) {
      assert(targetPsnQueue.dequeue() == tx.bth.psn.toInt)
    }
  }

  def addTargetPsn(psnList: List[Int]): Unit = {
    targetPsnQueue.enqueueAll(psnList)
  }

  def clearResidualPsn() = targetPsnQueue.clear()

  def allPsnReceived = targetPsnQueue.isEmpty
}
