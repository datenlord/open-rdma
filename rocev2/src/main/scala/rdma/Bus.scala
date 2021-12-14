package rdma

import spinal.core._
import spinal.lib._
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

case class NakNotifier() extends Bundle {
  val rnrNakPsn = UInt(PSN_WIDTH bits)
  val rnrPulse = Bool()
  val nakPulse = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    rnrNakPsn := 0
    rnrPulse := False
    nakPulse := False
    this
  }
}

case class QpFlushNotifier() extends Bundle {
  val stateErrFlush = Bool()
  val rnrFlush = Bool()
  val flush = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    stateErrFlush := False
    rnrFlush := False
    flush := False
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
  val pd = Bits(PD_ID_WIDTH bits)
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
    pd := 0
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

  def ePSN = epsn
  def nPSN = npsn
  def sQPN = sqpn
  def dQPN = dqpn
  def isValid = valid
}

case class QpStateChange() extends Bundle {
  val changeToState = Bits(QP_STATE_WIDTH bits)
  val changePulse = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    changeToState := QpState.ERR.id
    changePulse := False
    this
  }
}

case class DmaReadReq() extends Bundle {
  val qpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val addr = UInt(MEM_ADDR_WIDTH bits)
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    qpn := 0
    psn := 0
    addr := 0
    len := 0
    this
  }
}

case class DmaReadResp(busWidth: BusWidth) extends Bundle {
  val qpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val data = Bits(busWidth.id bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    qpn := 0
    psn := 0
    data := 0
    this
  }
}

case class DmaReadBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val req = Stream(DmaReadReq())
  val resp = Stream(Fragment(DmaReadResp(busWidth)))

  def >>(that: DmaReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: DmaReadBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class DmaWriteReq(busWidth: BusWidth) extends Bundle {
  val qpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val wrId = Bits(WR_ID_WIDTH bits)
  val wrIdValid = Bool()
  val addr = UInt(MEM_ADDR_WIDTH bits)
  val mty = Bits(log2Up(busWidth.id / 8) bits)
  val data = Bits(busWidth.id bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    qpn := 0
    psn := 0
    wrId := 0
    wrIdValid := False
    addr := 0
    mty := 0
    data := 0
    this
  }
}

case class DmaWriteResp() extends Bundle {
  val qpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val wrId = Bits(WR_ID_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    qpn := 0
    psn := 0
    wrId := 0
    this
  }
}

case class DmaWriteReqBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val req = Stream(Fragment(DmaWriteReq(busWidth)))

  def >>(that: DmaWriteReqBus): Unit = {
    this.req >> that.req
  }

  def <<(that: DmaWriteReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class DmaWriteRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(DmaWriteResp())

  def >>(that: DmaWriteRespBus): Unit = {
    this.resp >> that.resp
  }

  def <<(that: DmaWriteRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class DmaWriteBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val req = Stream(Fragment(DmaWriteReq(busWidth)))
  val resp = Stream(DmaWriteResp())

  def >>(that: DmaWriteBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: DmaWriteBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class DmaBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val rd = master(DmaReadBus(busWidth))
  val wr = master(DmaWriteBus(busWidth))

  def >>(that: DmaBus): Unit = {
    this.rd >> that.rd
    this.wr >> that.wr
  }

  def <<(that: DmaBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(rd, wr)
  }
}

case class WorkReq() extends Bundle {
  val id = Bits(WR_ID_WIDTH bits)
  val opcode = Bits(WR_OPCODE_WIDTH bits)
  val addr = UInt(MEM_ADDR_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val solicited = Bool()
  val sqpn = UInt(QPN_WIDTH bits)
  val ackreq = Bool()
  val fence = Bool()
  // TODO: assume single SG
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    id := 0
    opcode := 0
    addr := 0
    rkey := 0
    lkey := 0
    solicited := False
    sqpn := 0
    ackreq := False
    fence := False
    len := 0
    this
  }
}

case class RecvWorkReq() extends Bundle {
  val sqpn = UInt(QPN_WIDTH bits)
  val id = Bits(WR_ID_WIDTH bits)
  val addr = UInt(MEM_ADDR_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  // TODO: assume single SG
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    sqpn := 0
    id := 0
    addr := 0
    lkey := 0
    len := 0
    this
  }
}

case class WorkReqCached() extends Bundle {
  val workReq = WorkReq()
  val psnStart = UInt(PSN_WIDTH bits)

//  // TODO: remove this
//  def toRcReq(): RcReq = {
//    val rcReq = RcReq()
//    rcReq.psn := psnStart
//    rcReq.rnrCnt := 0
//    rcReq.rtyCnt := 0
//    rcReq.opcode := workReq.opcode.resize(OPCODE_WIDTH)
//    rcReq.solicited := workReq.solicited
//    rcReq.sqpn := workReq.sqpn
//    rcReq.ackreq := workReq.ackreq
//    rcReq.len := workReq.len
//    rcReq
//  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    workReq.setDefaultVal()
    psnStart := 0
    this
  }
}

case class WorkComp() extends Bundle {
  val id = Bits(WR_ID_WIDTH bits)
  val opcode = Bits(WC_OPCODE_WIDTH bits)
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val dqpn = UInt(QPN_WIDTH bits)
  val flags = Bits(WC_FLAG_WIDTH bits)
  val immDataOrInvRkey = Bits(LRKEY_IMM_DATA_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    id := 0
    opcode := 0
    len := 0
    sqpn := 0
    dqpn := 0
    flags := 0
    immDataOrInvRkey := 0
    this
  }
}

//case class RcReq() extends Bundle {
//  val psn = UInt(PSN_WIDTH bits)
//  val rnrCnt = UInt(RETRY_CNT_WIDTH bits)
//  val rtyCnt = UInt(RETRY_CNT_WIDTH bits)
//  val opcode = Bits(OPCODE_WIDTH bits)
//  val solicited = Bool()
//  val sqpn = UInt(QPN_WIDTH bits)
//  val ackreq = Bool()
//  val len = UInt(RDMA_MAX_LEN_WIDTH bits)
//}

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

case class CacheResp() extends Bundle {
  val workReqCached = WorkReqCached()
  val psnEnd = UInt(PSN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    workReqCached.setDefaultVal()
    psnEnd := 0
    this
  }
}

case class ReqCacheBus() extends Bundle with IMasterSlave {
  val req = Stream(CacheReq())
  val resp = Stream(CacheResp())

  def >>(that: ReqCacheBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: ReqCacheBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class AddrReadReq() extends Bundle {
  val key = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val pd = Bits(PD_ID_WIDTH bits)
  val remoteOrLocalKey = Bool() // True: remote, False: local
  val accessType = Bits(ACCESS_TYPE_WIDTH bits)
  val va = UInt(MEM_ADDR_WIDTH bits)
  val dataSize = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    key := 0
    pd := 0
    remoteOrLocalKey := True
    accessType := 0
    va := 0
    dataSize := 0
    this
  }
}

case class AddrReadResp() extends Bundle {
  val keyValid = Bool()
  val sizeValid = Bool()
  val pa = UInt(MEM_ADDR_WIDTH bits)
  // val len = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    keyValid := False
    sizeValid := False
    pa := 0
    this
  }
}

case class AddrCacheReadBus() extends Bundle with IMasterSlave {
  val req = Stream(AddrReadReq())
  val resp = Stream(AddrReadResp())

  def >>(that: AddrCacheReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: AddrCacheReadBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }

  def sendAddrCacheReq(
      reqValid: Bool,
      accessKey: Bits,
      accessType: Bits,
      pd: Bits,
      remoteOrLocalKey: Bool,
      va: UInt,
      dataSize: UInt
  ) = new Composite(accessKey) {
    req <-/< StreamSource()
      .continueWhen(reqValid)
      .translateWith {
        val addrReadReq = AddrReadReq()
        addrReadReq.key := accessKey
        addrReadReq.pd := pd
        addrReadReq.remoteOrLocalKey := remoteOrLocalKey
        addrReadReq.accessType := accessType
        addrReadReq.va := va
        addrReadReq.dataSize := dataSize
        addrReadReq
      }
  }

  def joinWithAddrCacheRespStream[T <: Data](
      streamIn: Stream[T],
      joinCond: Bool = True
  ) =
    new Composite(resp) {
      val addrCacheRespStream =
        joinCond ? resp | StreamSource()
          .translateWith(AddrReadResp().setDefaultVal())
      val joinedStream = StreamJoin(streamIn, addrCacheRespStream)
        .pipelined(m2s = true, s2m = true)
    }.joinedStream
}

case class UdpMetaData() extends Bundle {
  val ip = Bits(IPV4_WIDTH bits) // IPv4 only
  val len = UInt(RDMA_MAX_LEN_WIDTH bits)
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

case class RdmaNonReadRespBus()
    extends RdmaBasePacket
    with Acknowlege
    with AtomicResp
    with CNP {

  def toRdmaDataBus(busWidth: BusWidth): RdmaDataBus = {
    val rslt = RdmaDataBus(busWidth)
    rslt.bth := this.bth
    rslt.eth := this.eth
    rslt.data := 0
    rslt.mty := 0
    rslt
  }
}
