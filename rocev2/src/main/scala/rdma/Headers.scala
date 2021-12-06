package rdma

import spinal.core._
// import spinal.lib._

import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._

//----------RDMA defined headers----------//
abstract class RdmaHeader() extends Bundle {
  // TODO: refactor assign() to apply()
  def assign(input: Bits): this.type = {
    this.assignFromBits(input)
    this
  }

  // TODO: to remove
  def setDefaultVal(): this.type
}

// 48 bytes
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
case class RETH() extends RdmaHeader {
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
case class AtomicETH() extends RdmaHeader {
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
case class AtomicAckETH() extends RdmaHeader {
  val orig = Bits(LONG_WIDTH bits)

  def setDefaultVal(): this.type = {
    orig := 0
    this
  }
}

// 4 bytes
case class ImmDt() extends RdmaHeader {
  val data = Bits(INT_WIDTH bits)

  def setDefaultVal(): this.type = {
    data := 0
    this
  }
}

// 4 bytes
case class IETH() extends RdmaHeader {
  val rkey = Bits(INT_WIDTH bits)

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

//----------Combined packets----------//

trait RdmaPacket extends Bundle {
  // this: Bundle => // RdmaDataPacket must be of Bundle class
  val bth = BTH()
  val eth = Bits(ETH_WIDTH bits)
}

abstract class RdmaDataPacket(busWidth: BusWidth) extends RdmaPacket {
  val data = Bits(busWidth.id bits)
  val mty = Bits(log2Up(busWidth.id / 8) bits)

  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    eth := 0
    data := 0
    mty := 0
    this
  }

  def mtuWidth(pmtuEnum: Bits): Bits = {
    val pmtuBytes = Bits(log2Up(busWidth.id) bits)
    switch(pmtuEnum) {
      is(PMTU.U256.id) { pmtuBytes := 256 / 8 } // 32B
      is(PMTU.U512.id) { pmtuBytes := 512 / 8 } // 64B
      is(PMTU.U1024.id) { pmtuBytes := 1024 / 8 } // 128B
      is(PMTU.U2048.id) { pmtuBytes := 2048 / 8 } // 256B
      is(PMTU.U4096.id) { pmtuBytes := 4096 / 8 } // 512B
    }
    pmtuBytes
  }
}

trait ImmDtReq extends RdmaPacket {
  def immdt = ImmDt().assign(eth(0, widthOf(ImmDt()) bits))
}

trait RdmaReq extends RdmaPacket {
  def reth = RETH().assign(eth(0, widthOf(RETH()) bits))
}

trait Response extends RdmaPacket {
  def aeth = AETH().assign(eth(0, widthOf(AETH()) bits))
}

trait SendReq extends ImmDtReq {
  def ieth = IETH().assign(eth(0, widthOf(IETH()) bits))
}

trait WriteReq extends RdmaReq with ImmDtReq {}

trait ReadReq extends RdmaReq {}

trait ReadResp extends Response {}

trait Acknowlege extends Response {
  def setAck(
      ackType: Bits,
      psn: UInt,
      dqpn: UInt,
      msn: Bits = 0,
      creditCnt: Bits = 0,
      rnrTimeOut: Bits = MIN_RNR_TIMEOUT
  ): this.type = {
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

trait AtomicReq extends RdmaPacket {
  def atomicETH = AtomicETH().assign(eth(0, widthOf(AtomicETH()) bits))
}

trait AtomicResp extends Response {
  def atomicAckETH =
    AtomicAckETH().assign(eth(widthOf(AETH()), widthOf(AtomicAckETH()) bits))
}

trait CNP extends RdmaPacket {
  def padding = CNPPadding().assign(eth(0, widthOf(CNPPadding()) bits))
}
