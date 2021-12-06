package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
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
//    val strParts = ipv4Str.split('.')
//    assert(
//      strParts.length == 4,
//      s"string split parts length=${strParts.length}, invalid IPv4 addr=$ipv4Str"
//    )
//    val intParts = strParts.map(_.toInt)
//    intParts.foreach(i =>
//      assert(i < 256 && i >= 0, s"invalid IPv4 addr=$ipv4Str")
//    )
//    val ipBits = intParts.map(a => B(a & 0xff, 8 bits)).reduce(_ ## _)
//    assert(ipBits.getWidth == 32, s"invalid IPv4 addr=$ipv4Str")
//
//    ipBits
  }
}

object psnComp {
  def apply(psnA: UInt, psnB: UInt, curPsn: UInt) =
    new Composite(curPsn) {
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

object StreamSink {
  def apply(): Stream[NoData] = {
    Stream(NoData).freeRun()
  }
}

object OpCodeSeq {
  import OpCode._

  def checkReqSeq(preOpCode: Bits, curOpCode: Bits) =
    new Composite(
      curOpCode
    ) {
      val rslt = Bool()

      switch(preOpCode) {
        is(SEND_FIRST.id, SEND_MIDDLE.id) {
          rslt := curOpCode === SEND_MIDDLE.id || OpCode.isSendLastReqPkt(
            curOpCode
          )
        }
        is(RDMA_WRITE_FIRST.id, RDMA_WRITE_MIDDLE.id) {
          rslt := curOpCode === RDMA_WRITE_MIDDLE.id || OpCode
            .isWriteLastReqPkt(
              curOpCode
            )
        }
        is(
          SEND_ONLY.id,
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_ONLY_WITH_INVALIDATE.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id,
          RDMA_READ_REQUEST.id,
          COMPARE_SWAP.id,
          FETCH_ADD.id
        ) {
          rslt := OpCode.isFirstOrOnlyReqPkt(curOpCode)
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def checkReadRespSeq(preOpCode: Bits, curOpCode: Bits) =
    new Composite(
      curOpCode
    ) {
      val rslt = Bool()

      switch(preOpCode) {
        is(RDMA_READ_RESPONSE_FIRST.id, RDMA_READ_RESPONSE_MIDDLE.id) {
          rslt := curOpCode === RDMA_READ_RESPONSE_MIDDLE.id || curOpCode === RDMA_READ_RESPONSE_LAST.id
        }
        is(RDMA_READ_RESPONSE_ONLY.id, RDMA_READ_RESPONSE_LAST.id) {
          rslt := curOpCode === RDMA_READ_RESPONSE_FIRST.id || curOpCode === RDMA_READ_RESPONSE_ONLY.id
        }
        default {
          rslt := False
        }
      }
    }.rslt
}

object pktLengthCheck {
  def apply(pkt: RdmaDataBus, busWidth: BusWidth) =
    new Composite(pkt) {
      val opcode = pkt.bth.opcode
      val paddingCheck = True
      val lengthCheck = Bool()
      when(
        OpCode.isFirstReqPkt(opcode) || OpCode.isMidReqPkt(opcode) || OpCode
          .isMidReadRespPkt(opcode)
      ) {
        paddingCheck := pkt.bth.padcount === 0
        lengthCheck := pkt.mty === ((1 << busWidth.id) - 1)
      } elsewhen (OpCode.isLastReqPkt(opcode) || OpCode.isLastReadRespPkt(
        opcode
      )) {
        lengthCheck := pkt.mty.orR
      } otherwise {
        lengthCheck := True
      }
      val rslt = lengthCheck && paddingCheck
    }.rslt
}
