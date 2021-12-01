package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._

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

object PsnComp {
  def apply(psnA: UInt, psnB: UInt, curPsn: UInt) = new Composite(curPsn) {
    val rslt = UInt(PSN_COMP_RESULT_WIDTH bits)
    val oldestPSN = (curPsn - HALF_MAX_PSN) & PSN_MASK

    when(psnA === psnB) {
      rslt := PsnCompResult.EQUAL.id
    } elsewhen (psnA < psnB) {
      when(oldestPSN <= psnA) {
        rslt := PsnCompResult.LESSER.id
      } elsewhen (psnB <= oldestPSN) {
        rslt := PsnCompResult.LESSER.id
      } otherwise {
        rslt := PsnCompResult.GREATER.id
      }
    } otherwise { // psnA > psnB
      when(psnA <= oldestPSN) {
        rslt := PsnCompResult.GREATER.id
      } elsewhen (oldestPSN <= psnB) {
        rslt := PsnCompResult.GREATER.id
      } otherwise {
        rslt := PsnCompResult.LESSER.id
      }
    }
  }.rslt
}
