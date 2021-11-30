package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import Constants._

object IPv4Addr {
  def apply(ipv4Str: String): Bits = {
    ipv4Str match {
      case s"$a.$b.$c.$d" => {
        val parts = List(a, b, c, d).map(_.toInt)
        assert(!parts.exists(_ > 255), s"invalid IPv4 addr=$ipv4Str")
        parts.map(i => B(i & 0xff, 8 bits)).reduce(_ ## _)
      }
    }
    // val strParts = ipv4Str.split('.')
    // assert(strParts.length == 4, s"string split parts length=${strParts.length}, invalid IPv4 addr=$ipv4Str")
    // val intParts = strParts.map(_.toInt)
    // intParts.foreach(i => assert(i < 256 && i >= 0, s"invalid IPv4 addr=$ipv4Str"))
    // val ipBits = intParts.map(a => B(a & 0xFF, 8 bits)).reduce(_ ## _)
    // assert(ipBits.getWidth == 32, s"invalid IPv4 addr=$ipv4Str")

    // ipBits
  }
}

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
  // TODO: remove this
//  def setDefaultVal(): this.type = {
//    transport := Transports.RC.id
//    opcode := 0
//    solicited := False
//    migreq := False
//    padcount := 0
//    version := 0
//    pkey := 0xffff // Default PKEY
//    fecn := False
//    becn := False
//    resv6 := 0
//    dqpn := 0
//    ackreq := False
//    resv7 := 0
//    psn := 0
//    this
//  }
}

case class AETH() extends Bundle {
  val rsvd = Bits(1 bit)
  val code = Bits(2 bits)
  val value = Bits(5 bits)
  val msn = Bits(24 bits)

  def setDefaultVal(): this.type = {
    rsvd := 0
    code := 0
    value := 0
    msn := 0
    this
  }
}

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

case class AtomicAckETH() extends Bundle {
  val orig = Bits(LONG_WIDTH bits)

  def setDefaultVal(): this.type = {
    orig := 0
    this
  }
}

case class ImmDt() extends Bundle {
  val data = Bits(INT_WIDTH bits)

  def setDefaultVal(): this.type = {
    data := 0
    this
  }
}

case class IETH() extends Bundle {
  val rkey = Bits(INT_WIDTH bits)

  def setDefaultVal(): this.type = {
    rkey := 0
    this
  }
}

case class CNPPadding() extends Bundle {
  val rsvd1 = Bits(LONG_WIDTH bits)
  val rsvd2 = Bits(LONG_WIDTH bits)

  def setDefaultVal(): this.type = {
    rsvd1 := 0
    rsvd2 := 0
    this
  }
}
