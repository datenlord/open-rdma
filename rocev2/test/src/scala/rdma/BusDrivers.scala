package rdma

import rdma.RdmaConstants.PSN_WIDTH
import spinal.core._
import spinal.core.sim._
import spinal.lib._
import spinal.lib.sim._

import scala.collection.mutable

abstract class RdmaBasePacketDriver[T <: RdmaBasePacket](payload: T) {
  val cmdQueue = mutable.Queue[T => Unit]()

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

  def setOpcode(opCode: OpCode.OpCode): Unit = {
    payload.bth.opcode #= opCode.id
  }

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
}

abstract class RdmaDataPacketDriver[T <: RdmaDataPacket](payload: T)
    extends RdmaBasePacketDriver(payload) {
  payload.data #= 0
  payload.mty #= 0
}

class RdmaDataBusDriver(
    bus: Stream[Fragment[RdmaDataBus]],
    clockDomain: ClockDomain
) extends RdmaDataPacketDriver(bus.fragment) {

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

  fork {
    while (true) {
      if (cmdQueue.nonEmpty) {
        cmdQueue.dequeue()(bus.payload)
        bus.valid #= true
        waitPacketSendOver()
      } else {
        bus.valid #= false
        clockDomain.waitRisingEdge()
      }
    }
  }
}

class RdmaNonReadRespBusDriver(
    bus: Stream[RdmaNonReadRespBus],
    clockDomain: ClockDomain
) extends RdmaBasePacketDriver(bus.payload) {

  fork {
    while (true) {
      if (cmdQueue.nonEmpty) {
        cmdQueue.dequeue()(bus.payload)
        bus.valid #= true
        clockDomain.waitSamplingWhere(bus.ready.toBoolean)
      } else {
        bus.valid #= false
        clockDomain.waitRisingEdge()
      }
    }
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
