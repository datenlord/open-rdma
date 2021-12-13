package rdma

import spinal.core._
// import spinal.lib._

//import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._

//----------RDMA defined headers----------//
sealed abstract class RdmaHeader() extends Bundle {
//  // TODO: refactor assign() to apply()
//  def assign(input: Bits): this.type = {
//    this.assignFromBits(input)
//    this
//  }

  // TODO: to remove
  def setDefaultVal(): this.type
}

// 12 bytes
case class BTH() extends RdmaHeader {
  val opcodeFull = Bits(TRANSPORT_WIDTH + OPCODE_WIDTH bits)
  val solicited = Bool()
  val migreq = Bool()
  val padcount = UInt(PADCOUNT_WIDTH bits)
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

  def set(opcode: Bits, dqpn: UInt, psn: UInt): this.type = {
    val padCnt = U(0, PADCOUNT_WIDTH bits)
    set(opcode = opcode, padcount = padCnt, dqpn = dqpn, psn = psn)
  }

  def set(opcode: Bits, padcount: UInt, dqpn: UInt, psn: UInt): this.type = {
    set(
      opcode = opcode,
      padcount = padcount,
      dqpn = dqpn,
      ackReq = False,
      psn = psn
    )
  }

  def set(
      opcode: Bits,
      padcount: UInt,
      dqpn: UInt,
      ackReq: Bool,
      psn: UInt
  ): this.type = {
    transport := Transports.RC.id
    this.opcode := opcode
    solicited := False
    migreq := False
    this.padcount := padcount
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
    padcount := 0
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
  val rsvd = Bits(1 bit)
  val code = Bits(2 bits)
  val value = Bits(AETH_VALUE_WIDTH bits)
  val msn = UInt(MSN_WIDTH bits)

  def set(ackType: AckType.AckType): this.type = {
    require(
      ackType != AckType.NAK_RNR,
      "RNR NAK type requires rnrTimeOut as input"
    )

    val rnrTimeOut = Bits(AETH_VALUE_WIDTH bits)
    rnrTimeOut := 0
    set(ackType, msn = 0, creditCnt = 0, rnrTimeOut = rnrTimeOut)
  }

  def set(
      ackType: AckType.AckType,
      msn: Int,
      creditCnt: Int,
      rnrTimeOut: Bits
  ): this.type = {
    val ackTypeBits = Bits(ACK_TYPE_WIDTH bits)
    ackTypeBits := ackType.id
    val msnBits = UInt(MSN_WIDTH bits)
    msnBits := msn
    val creditCntBits = Bits(AETH_VALUE_WIDTH bits)
    creditCntBits := creditCnt

    setHelper(ackTypeBits, msnBits, creditCntBits, rnrTimeOut)
  }

  def setHelper(
      ackType: Bits,
      msn: UInt,
      creditCnt: Bits,
      rnrTimeOut: Bits
  ): this.type = {
    rsvd := 0
    this.msn := msn

    switch(ackType) {
      is(AckType.NORMAL.id) {
        code := AethCode.ACK.id
        value := creditCnt
      }
      is(AckType.NAK_SEQ.id) {
        code := AethCode.NAK.id
        value := NakCode.SEQ.id
      }
      is(AckType.NAK_INV.id) {
        code := AethCode.NAK.id
        value := NakCode.INV.id
      }
      is(AckType.NAK_RMT_ACC.id) {
        code := AethCode.NAK.id
        value := NakCode.RMT_ACC.id
      }
      is(AckType.NAK_RMT_OP.id) {
        code := AethCode.NAK.id
        value := NakCode.RMT_OP.id
      }
      is(AckType.NAK_RNR.id) {
        code := AethCode.RNR.id
        value := rnrTimeOut
      }
      default {
        code := AethCode.RSVD.id
        value := 0
        assert(
          assertion = False,
          message = L"invalid AckType=$ackType",
          severity = FAILURE
        )
      }
    }
    this
  }

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

  def setDefaultVal(): this.type = {
    va := 0
    rkey := 0
    swap := 0
    comp := 0
    this
  }
}

// 8 bytes
case class AtomicAckETH() extends RdmaHeader {
  val orig = Bits(LONG_WIDTH bits)

  def setDefaultVal(): this.type = {
    orig := 0
    this
  }
}

// 4 bytes
case class ImmDt() extends RdmaHeader {
  val data = Bits(LRKEY_IMM_DATA_WIDTH bits)

  def setDefaultVal(): this.type = {
    data := 0
    this
  }
}

// 4 bytes
case class IETH() extends RdmaHeader {
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)

  def setDefaultVal(): this.type = {
    rkey := 0
    this
  }
}

// 16 bytes
case class CNPPadding() extends RdmaHeader {
  val rsvd1 = Bits(LONG_WIDTH bits)
  val rsvd2 = Bits(LONG_WIDTH bits)

  def setDefaultVal(): this.type = {
    rsvd1 := 0
    rsvd2 := 0
    this
  }
}
