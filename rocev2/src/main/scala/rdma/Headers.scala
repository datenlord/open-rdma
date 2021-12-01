package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._

//----------RDMA defined headers----------//

// 48 bytes
case class BTH() extends Bundle {
  // val transport = Bits(TRANSPORT_WIDTH bits)
  val opcode = Bits(OPCODE_WIDTH bits)
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

  def transport = opcode(OPCODE_WIDTH - TRANSPORT_WIDTH, TRANSPORT_WIDTH bits)

  def setDefaultVal(): this.type = {
    // transport := Transports.RC.id
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
case class AETH() extends Bundle {
  val rsvd = Bits(1 bit)
  val code = Bits(2 bits)
  val value = Bits(AETH_VALUE_WIDTH bits)
  val msn = Bits(MSN_WIDTH bits)

  def setDefaultVal(): this.type = {
    rsvd := 0
    code := 0
    value := 0
    msn := 0
    this
  }
}

// 16 bytes
case class RETH() extends Bundle {
  val va = Bits(LONG_WIDTH bits)
  val rkey = Bits(INT_WIDTH bits)
  val dlen = UInt(INT_WIDTH bits)

  def setDefaultVal(): this.type = {
    va := 0
    rkey := 0
    dlen := 0
    this
  }
}

// 28 bytes
case class AtomicETH() extends Bundle {
  val va = Bits(LONG_WIDTH bits)
  val rkey = Bits(INT_WIDTH bits)
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
case class AtomicAckETH() extends Bundle {
  val orig = Bits(LONG_WIDTH bits)

  def setDefaultVal(): this.type = {
    orig := 0
    this
  }
}

// 4 bytes
case class ImmDt() extends Bundle {
  val data = Bits(INT_WIDTH bits)

  def setDefaultVal(): this.type = {
    data := 0
    this
  }
}

// 4 bytes
case class IETH() extends Bundle {
  val rkey = Bits(INT_WIDTH bits)

  def setDefaultVal(): this.type = {
    rkey := 0
    this
  }
}

// 16 bytes
case class CNPPadding() extends Bundle {
  val rsvd1 = Bits(LONG_WIDTH bits)
  val rsvd2 = Bits(LONG_WIDTH bits)

  def setDefaultVal(): this.type = {
    rsvd1 := 0
    rsvd2 := 0
    this
  }
}

//----------Combined headers----------//
case class ACK() extends Bundle {
  val bth = BTH()
  val aeth = AETH()

  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    aeth.setDefaultVal()
    this
  }

  def asRdmaDataBus(busWidth: BusWidth): RdmaDataBus = {
    require(busWidth.id > widthOf(this), "bus width must > ACK width")

    val rdmaData = RdmaDataBus(busWidth)
    rdmaData.data.assignFromBits(this.asBits.resize(busWidth.id))
    // TODO: check MTY validity
    rdmaData.mty := log2Up((busWidth.id - widthOf(this)) / 8)
    rdmaData
  }

//  def set(ackType: Bits, psn: UInt, dqpn: UInt): this.type = {
//    val msn = B(0, MSN_WIDTH bits)
//    val creditCnt = B(0, AETH_VALUE_WIDTH bits)
//    val rnrTimeOut = B(MIN_RNR_TIMEOUT, RNR_TIMER_WIDTH bits)
//    set(ackType, psn, dqpn, msn, creditCnt, rnrTimeOut)
//  }

  def set(
      ackType: Bits,
      psn: UInt,
      dqpn: UInt,
      msn: Bits = 0,
      creditCnt: Bits = 0,
      rnrTimeOut: Bits = MIN_RNR_TIMEOUT
  ): this.type = {
//    bth.setDefaultVal()
//    aeth.rsvd := 0
//
//    bth.dqpn.removeAssignments()
//    bth.opcode.removeAssignments()
//    bth.psn.removeAssignments()
//    aeth.msn.removeAssignments()
    bth.dqpn := dqpn
    bth.opcode := OpCode.ACKNOWLEDGE.id
    bth.psn := psn
    aeth.msn := msn

    switch(ackType) {
      is(AckType.NORMAL.id) {
        aeth.code := AethCode.ACK.id
        aeth.value := creditCnt
      }
      is(AckType.NAK_SEQ.id) {
        aeth.code := AethCode.NAK.id
        aeth.value := NakCode.SEQ.id
      }
      is(AckType.NAK_INV.id) {
        aeth.code := AethCode.NAK.id
        aeth.value := NakCode.INV.id
      }
      is(AckType.NAK_RMT_ACC.id) {
        aeth.code := AethCode.NAK.id
        aeth.value := NakCode.RMT_ACC.id
      }
      is(AckType.NAK_RMT_OP.id) {
        aeth.code := AethCode.NAK.id
        aeth.value := NakCode.RMT_OP.id
      }
      is(AckType.NAK_RNR.id) {
        aeth.code := AethCode.RNR.id
        aeth.value := rnrTimeOut
      }
      default {
        assert(
          assertion = False,
          message = L"invalid AckType=$ackType",
          severity = ERROR
        )
      }
    }
    this
  }
}
