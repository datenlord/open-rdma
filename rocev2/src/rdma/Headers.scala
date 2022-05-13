package rdma

import spinal.core._

import ConstantSettings._
import RdmaConstants._

//----------RDMA defined headers----------//
sealed abstract class RdmaHeader extends Bundle {
  def asBigEndianBits(): Bits

  // TODO: to remove
  def setDefaultVal(): this.type
}

// 12 bytes
case class BTH() extends RdmaHeader {
  val opcodeFull = Bits(TRANSPORT_WIDTH + OPCODE_WIDTH bits)
  val solicited = Bool()
  val migreq = Bool()
  val padCnt = UInt(PAD_COUNT_WIDTH bits)
  val version = Bits(VERSION_WIDTH bits)
  val pkey = Bits(PKEY_WIDTH bits)
  val fecn = Bool()
  val becn = Bool()
  val resv6 = Bits(6 bits)
  val dqpn = UInt(QPN_WIDTH bits)
  val ackreq = Bool()
  val resv7 = Bits(7 bits)
  val psn = UInt(PSN_WIDTH bits)

  def transport = opcodeFull(OPCODE_WIDTH, TRANSPORT_WIDTH bits)
  def opcode = opcodeFull(0, OPCODE_WIDTH bits)

  override def asBigEndianBits(): Bits = {
    opcodeFull ## solicited ## migreq ## padCnt ## version ## pkey ## fecn ## becn ## resv6 ## dqpn ## ackreq ## resv7 ## psn
  }

  def set(opcode: Bits, dqpn: UInt, psn: UInt): this.type = {
    val padCnt = U(0, PAD_COUNT_WIDTH bits)
    set(opcode = opcode, padCnt = padCnt, dqpn = dqpn, psn = psn)
  }

  def set(opcode: Bits, padCnt: UInt, dqpn: UInt, psn: UInt): this.type = {
    set(
      opcode = opcode,
      padCnt = padCnt,
      dqpn = dqpn,
      ackReq = False,
      psn = psn
    )
  }

  def set(
      opcode: Bits,
      padCnt: UInt,
      dqpn: UInt,
      ackReq: Bool,
      psn: UInt
  ): this.type = {
    transport := Transports.RC.id
    this.opcode := opcode
    solicited := False
    migreq := False
    this.padCnt := padCnt
    version := 0
    pkey := 0xffff // Default PKEY
    fecn := False
    becn := False
    resv6 := 0
    this.dqpn := dqpn
    this.ackreq := ackReq
    resv7 := 0
    this.psn := psn
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    transport := Transports.RC.id
    opcode := 0
    solicited := False
    migreq := False
    padCnt := 0
    version := 0
    pkey := 0xffff // Default PKEY
    fecn := False
    becn := False
    resv6 := 0
    dqpn := 0
    ackreq := False
    resv7 := 0
    psn := 0
    this
  }
}

// 4 bytes
case class AETH() extends RdmaHeader {
  val rsvd = Bits(AETH_RSVD_WIDTH bit)
  val code = Bits(AETH_CODE_WIDTH bits)
  val value = Bits(AETH_VALUE_WIDTH bits)
  val msn = UInt(MSN_WIDTH bits)

  override def asBigEndianBits(): Bits = {
    rsvd ## code ## value ## msn
  }

  def toWorkCompStatus(): SpinalEnumCraft[WorkCompStatus.type] =
    new Composite(this, "AETH_toWorkCompStatus") {
      val workCompStatus = WorkCompStatus() // Bits(WC_STATUS_WIDTH bits)
      when(isNormalAck()) {
        workCompStatus := WorkCompStatus.SUCCESS
      } elsewhen (isInvReqNak()) {
        workCompStatus := WorkCompStatus.REM_INV_REQ_ERR
      } elsewhen (isRmtAccNak()) {
        workCompStatus := WorkCompStatus.REM_ACCESS_ERR
      } elsewhen (isRmtOpNak()) {
        workCompStatus := WorkCompStatus.REM_OP_ERR
      } elsewhen (isSeqNak()) {
        workCompStatus := WorkCompStatus.RETRY_EXC_ERR
      } elsewhen (isRnrNak()) {
        workCompStatus := WorkCompStatus.RNR_RETRY_EXC_ERR
      } otherwise {
        report(
          message =
            L"${REPORT_TIME} time: illegal AETH to WC state, code=${code}, value=${value}",
          severity = ERROR
        )
        workCompStatus.assignDontCare()
      }
    }.workCompStatus

  def set(ackType: SpinalEnumCraft[AckType.type]): this.type = {
    require(
      ackType != AckType.NAK_RNR,
      "RNR NAK type requires rnrTimeOut as input"
    )

    val rnrTimeOut = Bits(AETH_VALUE_WIDTH bits)
    rnrTimeOut := DEFAULT_MIN_RNR_TIME_OUT
    set(ackType, msn = 0, creditCnt = 0, rnrTimeOut = rnrTimeOut)
  }

  def set(
      ackType: SpinalEnumCraft[AckType.type],
      rnrTimeOut: Bits
  ): this.type = {
    set(ackType, msn = 0, creditCnt = 0, rnrTimeOut = rnrTimeOut)
  }

  def set(
      ackType: SpinalEnumCraft[AckType.type],
      msn: Int,
      creditCnt: Int,
      rnrTimeOut: Bits
  ): this.type = {
    val msnBits = UInt(MSN_WIDTH bits)
    msnBits := msn
    val creditCntBits = Bits(AETH_VALUE_WIDTH bits)
    creditCntBits := creditCnt

    setHelper(ackType, msnBits, creditCntBits, rnrTimeOut)
  }

  def setHelper(
      ackType: SpinalEnumCraft[AckType.type],
      msn: UInt,
      creditCnt: Bits,
      rnrTimeOut: Bits
  ): this.type = {
    rsvd := 0
    this.msn := msn

    switch(ackType) {
      is(AckType.NORMAL) {
        code := AethCode.ACK.id
        value := creditCnt
      }
      is(AckType.NAK_SEQ) {
        code := AethCode.NAK.id
        value := NakCode.SEQ.id
      }
      is(AckType.NAK_INV) {
        code := AethCode.NAK.id
        value := NakCode.INV.id
      }
      is(AckType.NAK_RMT_ACC) {
        code := AethCode.NAK.id
        value := NakCode.RMT_ACC.id
      }
      is(AckType.NAK_RMT_OP) {
        code := AethCode.NAK.id
        value := NakCode.RMT_OP.id
      }
      is(AckType.NAK_RNR) {
        code := AethCode.RNR.id
        value := rnrTimeOut
      }
      // UNREACHABLE DEFAULT STATEMENT
//      default {
//        code := AethCode.RSVD.id
//        value := 0
//        report(
//          message = L"${REPORT_TIME} time: invalid AckType=$ackType",
//          severity = FAILURE
//        )
//      }
    }
    this
  }

  def isNormalAck(): Bool =
    new Composite(this, "AETH_isNormalAck") {
      val result = code === AethCode.ACK.id
    }.result

  def isRetryNak(): Bool =
    new Composite(this, "AETH_isRetryNak") {
      val result =
        code === AethCode.RNR.id || (code === AethCode.NAK.id && value === NakCode.SEQ.id)
    }.result

  def isRnrNak(): Bool =
    new Composite(this, "AETH_isRnrNak") {
      val result = code === AethCode.RNR.id
    }.result

  def isSeqNak(): Bool =
    new Composite(this, "AETH_isSeqNak") {
      val result = code === AethCode.NAK.id && value === NakCode.SEQ.id
    }.result

  def isErrAck(): Bool =
    new Composite(this, "AETH_isErrAck") {
      val result = code === AethCode.NAK.id && value =/= NakCode.SEQ.id &&
        !NakCode.isReserved(value)
    }.result

  def isInvReqNak(): Bool =
    new Composite(this, "AETH_isInvReqNak") {
      val result = code === AethCode.NAK.id && value === NakCode.INV.id
    }.result

  def isRmtAccNak(): Bool =
    new Composite(this, "AETH_isRmtAccNak") {
      val result = code === AethCode.NAK.id && value === NakCode.RMT_ACC.id
    }.result

  def isRmtOpNak(): Bool =
    new Composite(this, "AETH_isRmtOpNak") {
      val result = code === AethCode.NAK.id && value === NakCode.RMT_OP.id
    }.result

  def isReserved(): Bool =
    new Composite(this, "AETH_isReserved") {
      val result = code === AethCode.RSVD.id ||
        (code === AethCode.NAK.id && NakCode.isReserved(value))
    }.result

  // TODO: remove this
  def setDefaultVal(): this.type = {
    rsvd := 0
    code := 0
    value := 0
    msn := 0
    this
  }
}

// 16 bytes
case class RETH() extends RdmaHeader {
  val va = UInt(MEM_ADDR_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val dlen = UInt(RDMA_MAX_LEN_WIDTH bits)

  override def asBigEndianBits(): Bits = {
    va ## rkey ## dlen
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    va := 0
    rkey := 0
    dlen := 0
    this
  }
}

// 28 bytes
case class AtomicEth() extends RdmaHeader {
  val va = UInt(MEM_ADDR_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val swap = Bits(LONG_WIDTH bits)
  val comp = Bits(LONG_WIDTH bits)

  override def asBigEndianBits(): Bits = {
    va ## rkey ## swap ## comp
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    va := 0
    rkey := 0
    swap := 0
    comp := 0
    this
  }
}

// 8 bytes
case class AtomicAckEth() extends RdmaHeader {
  val orig = Bits(LONG_WIDTH bits)

  override def asBigEndianBits(): Bits = {
    orig
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    orig := 0
    this
  }
}

// 4 bytes
case class ImmDt() extends RdmaHeader {
  val data = Bits(LRKEY_IMM_DATA_WIDTH bits)

  override def asBigEndianBits(): Bits = {
    data
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    data := 0
    this
  }
}

// 4 bytes
case class IETH() extends RdmaHeader {
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)

  override def asBigEndianBits(): Bits = {
    rkey
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    rkey := 0
    this
  }
}

// 16 bytes
case class CNPPadding() extends RdmaHeader {
  val rsvd1 = Bits(LONG_WIDTH bits)
  val rsvd2 = Bits(LONG_WIDTH bits)

  override def asBigEndianBits(): Bits = {
    rsvd1 ## rsvd2
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    rsvd1 := 0
    rsvd2 := 0
    this
  }
}
