package rdma

import spinal.core._

import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._

case class DevMetaData() extends Bundle {
  val maxPendingReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val minRnrTimeOut = UInt(RNR_TIMER_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    maxPendingReqNum := PENDING_REQ_NUM
    maxPendingReadAtomicReqNum := PENDING_READ_ATOMIC_REQ_NUM
    minRnrTimeOut := MIN_RNR_TIMEOUT
    this
  }
}

case class QpAttrUpdateNotifier() extends Bundle {
  val pulseRqPsnReset = Bool()
  val pulseSqPsnReset = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    pulseRqPsnReset := False
    pulseSqPsnReset := False
    this
  }
}

case class QpAttrData() extends Bundle {
  val ipv4Peer = Bits(IPV4_WIDTH bits) // IPv4 only
  val npsn = UInt(PSN_WIDTH bits)
  val epsn = UInt(PSN_WIDTH bits)
  val pmtu = Bits(PMTU_WIDTH bits)
  val maxPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxDstPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val dqpn = UInt(QPN_WIDTH bits)
  val state = Bits(QP_STATE_WIDTH bits)
  val modifyMask = Bits(QP_ATTR_MASK_WIDTH bits)
  val valid = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    ipv4Peer := 0
    npsn := 0
    epsn := 0
    pmtu := PMTU.U1024.id
    maxPendingReadAtomicReqNum := 0
    maxDstPendingReadAtomicReqNum := 0
    sqpn := 0
    dqpn := 0
    state := QpState.RESET.id
    modifyMask := 0
    valid := False
    this
  }

  def ePSN(): UInt = {
    epsn
  }

  def nPSN(): UInt = {
    npsn
  }

  def sQPN(): UInt = {
    sqpn
  }

  def dQPN(): UInt = {
    dqpn
  }

  def isValid(): Bool = {
    valid
  }
}

case class DmaReadReq() extends Bundle {
  val qpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val addr = UInt(MEM_ADDR_WIDTH bits)
  val len = UInt(DMA_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    qpn := 0
    psn := 0
    addr := 0
    len := 0
    this
  }
}

case class DmaReadResp() extends Bundle {
  val qpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val data = Bits(DMA_BUS_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    qpn := 0
    psn := 0
    data := 0
    this
  }
}

case class DmaWriteReq() extends Bundle {
  val qpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val addr = UInt(MEM_ADDR_WIDTH bits)
  val len = UInt(DMA_LEN_WIDTH bits)
  val data = Bits(DMA_BUS_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    qpn := 0
    psn := 0
    addr := 0
    len := 0
    data := 0
    this
  }
}

case class DmaWriteResp() extends Bundle {
  val qpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    qpn := 0
    psn := 0
    this
  }
}

case class WorkReq() extends Bundle {
  val opcode = Bits(WR_OPCODE_WIDTH bits)
  val solicited = Bool()
  val sqpn = UInt(QPN_WIDTH bits)
  val ackreq = Bool()
  val fence = Bool()
  val len = UInt(REQ_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    opcode := 0
    solicited := False
    sqpn := 0
    ackreq := False
    fence := False
    len := 0
    this
  }
}
case class WorkReqPSN() extends Bundle {
  val workReq = WorkReq()
  val psnStart = UInt(PSN_WIDTH bits)

  // TODO: remove this
  def toRcReq(): RcReq = {
    val rcReq = RcReq()
    rcReq.psn := psnStart
    rcReq.rnrCnt := 0
    rcReq.rtyCnt := 0
    rcReq.opcode := workReq.opcode.resize(OPCODE_WIDTH)
    rcReq.solicited := workReq.solicited
    rcReq.sqpn := workReq.sqpn
    rcReq.ackreq := workReq.ackreq
    rcReq.len := workReq.len
    rcReq
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    workReq.setDefaultVal()
    psnStart := 0
    this
  }
}

case class RcReq() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
  val rnrCnt = UInt(RETRY_CNT_WIDTH bits)
  val rtyCnt = UInt(RETRY_CNT_WIDTH bits)
  val opcode = Bits(OPCODE_WIDTH bits)
  val solicited = Bool()
  val sqpn = UInt(QPN_WIDTH bits)
  val ackreq = Bool()
  val len = UInt(REQ_LEN_WIDTH bits)
}

case class CacheReq() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
  val delete = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    psn := 0
    delete := False
    this
  }
}

case class CacheData() extends Bundle {
  val workReqPSN = WorkReqPSN()
  val psnEnd = UInt(PSN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    workReqPSN.setDefaultVal()
    psnEnd := 0
    this
  }
}

case class UdpMetaData() extends Bundle {
  val ip = Bits(IPV4_WIDTH bits) // IPv4 only
  val len = UInt(REQ_LEN_WIDTH bits)
}

case class UdpDataBus(busWidth: BusWidth) extends Bundle {
  val udp = UdpMetaData()
  val data = Bits(busWidth.id bits)
  val mty = Bits(log2Up(busWidth.id / 8) bits)
}

case class RdmaDataBus(busWidth: BusWidth)
    extends RdmaDataPacket(busWidth)
    with SendReq
    with WriteReq
    with ReadReq
    with AtomicReq
    with Acknowlege
    with ReadResp
    with AtomicResp
    with CNP {}
