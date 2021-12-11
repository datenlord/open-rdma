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

object bufSizeCheck {
  def apply(
      targetPhysicalAddr: UInt,
      dataSize: UInt,
      bufPhysicalAddr: UInt,
      bufSize: UInt
  ) =
    new Composite(dataSize) {
      val bufEndAddr = bufPhysicalAddr + bufSize
      val targetEndAddr = targetPhysicalAddr + dataSize
      val rslt =
        bufPhysicalAddr <= targetPhysicalAddr && targetEndAddr <= bufEndAddr
    }.rslt
}

object StreamSource {
  def apply(): Stream[NoData] = {
    val rslt = Stream(NoData)
    rslt.valid := True
    rslt
  }
}

object StreamSink {
  def apply[T <: Data](payloadType: HardType[T]): Stream[T] =
    Stream(payloadType).freeRun()
}

object StreamPipeStage {
  def apply[T1 <: Data, T2 <: Data](
      input: Stream[T1],
      continue: Bool = True,
      flush: Bool = False
  )(f: (T1, Bool) => T2) =
    new Composite(input) {
      val inputStreamRegistered = input
        .continueWhen(continue)
        .throwWhen(flush)
      val output = inputStreamRegistered
        .combStage()
        .translateWith(
          f(inputStreamRegistered.payload, inputStreamRegistered.valid)
        )
        .pipelined(m2s = true, s2m = true)
    }.output
}

object StreamPipeStageSink {
  def apply[T1 <: Data, T2 <: Data](
      input: Stream[T1],
      continue: Bool = True,
      flush: Bool = False
  )(f: (T1, Bool) => T2)(sink: Stream[T2]) =
    new Area {
      sink << StreamPipeStage(input, continue, flush)(f)
    }
}

/*
object SingleValidDataFlow {
  def apply[T <: Data](inputStream: Stream[T]) =
    new Composite(inputStream) {

      // Insert bubble to input flow every first valid RX, clear after each RX fire
      val insertInvalid = Reg(Bool()) init (False)
      when(inputStream.valid) {
        insertInvalid := True
      } elsewhen (inputStream.fire) {
        insertInvalid := False
      }

      val inputFlow = inputStream.asFlow.throwWhen(insertInvalid)
    }.inputFlow
}

// SingleValidDataStream will take one valid data from inputStream,
// once it's fired, the one valid datum is consumed and it has no more valid data,
// and it will take a valid one again from inputStream if takeOneCond is true
object SingleValidDataStream {
  def apply[T <: Data](inputStream: Stream[T])(takeOneCond: Bool): Stream[T] = {
    val validReg = RegInit(False)
    val payloadReg = RegInit(inputStream.payload)

    val singleValidDataStream = Stream(inputStream.payloadType)
    singleValidDataStream.payload := payloadReg
    singleValidDataStream.valid := validReg

    // When fired, the single valid datum is consumed
    when(singleValidDataStream.fire) {
      validReg := False
    }

    val takeOneDoneReg = RegInit(False)
    when(takeOneCond) {
      takeOneDoneReg := False
    } elsewhen (validReg) {
      takeOneDoneReg := True
    }

    // Take next one valid datum, it'll keep taking if the inputStream valid is low
    when(!takeOneDoneReg) {
      validReg := inputStream.valid // One cycle delay
    }

    singleValidDataStream
  }
}
 */

object OpCodeSeq {
  import OpCode._

  def checkReqSeq(preOpCode: Bits, curOpCode: Bits) =
    new Composite(curOpCode) {
      val rslt = Bool()

      switch(preOpCode) {
        is(SEND_FIRST.id, SEND_MIDDLE.id) {
          rslt := curOpCode === SEND_MIDDLE.id || OpCode.isSendLastReqPkt(
            curOpCode
          )
        }
        is(RDMA_WRITE_FIRST.id, RDMA_WRITE_MIDDLE.id) {
          rslt := curOpCode === RDMA_WRITE_MIDDLE.id || OpCode
            .isWriteLastReqPkt(curOpCode)
        }
        is(
          SEND_ONLY.id,
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_ONLY_WITH_INVALIDATE.id,
          SEND_LAST.id,
          SEND_LAST_WITH_IMMEDIATE.id,
          SEND_LAST_WITH_INVALIDATE.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id,
          RDMA_WRITE_LAST.id,
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id,
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
    new Composite(curOpCode) {
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
        // TODO: verify length check correctness
        lengthCheck := pkt.mty === ((1 << (busWidth.id / 8)) - 1)
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

class StreamVec[T <: Data](val strmVec: Vec[Stream[T]]) {
  def <-/<(that: Vec[Stream[T]]) = new Area {
    require(
      strmVec.length equals that.length,
      s"StreamVec size not match, this.len=${strmVec.length} != that.len=${that.length}"
    )
    for (idx <- 0 until that.length) {
      strmVec(idx) <-/< that(idx)
    }
  }
}

object StreamVec {
  implicit def buildStreamVec[T <: Data](that: Vec[Stream[T]]): StreamVec[T] = {
    new StreamVec(that)
  }
}

object asTuple {
  def apply[T1 <: Data, T2 <: Data](t2: TupleBundle2[T1, T2]) =
    (t2._1, t2._2)

  def apply[T1 <: Data, T2 <: Data, T3 <: Data](t3: TupleBundle3[T1, T2, T3]) =
    (t3._1, t3._2, t3._3)

  def apply[T1 <: Data, T2 <: Data, T3 <: Data, T4 <: Data](
      t4: TupleBundle4[T1, T2, T3, T4]
  ) =
    (t4._1, t4._2, t4._3, t4._4)

  def apply[T1 <: Data, T2 <: Data, T3 <: Data, T4 <: Data, T5 <: Data](
      t5: TupleBundle5[T1, T2, T3, T4, T5]
  ) =
    (t5._1, t5._2, t5._3, t5._4, t5._5)

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data
  ](t6: TupleBundle6[T1, T2, T3, T4, T5, T6]) =
    (t6._1, t6._2, t6._3, t6._4, t6._5, t6._6)

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data
  ](t7: TupleBundle7[T1, T2, T3, T4, T5, T6, T7]) =
    (t7._1, t7._2, t7._3, t7._4, t7._5, t7._6, t7._7)

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data
  ](t8: TupleBundle8[T1, T2, T3, T4, T5, T6, T7, T8]) =
    (t8._1, t8._2, t8._3, t8._4, t8._5, t8._6, t8._7, t8._8)

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data
  ](t9: TupleBundle9[T1, T2, T3, T4, T5, T6, T7, T8, T9]) =
    (t9._1, t9._2, t9._3, t9._4, t9._5, t9._6, t9._7, t9._8, t9._9)

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data
  ](
      t10: TupleBundle10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10]
  ) =
    (
      t10._1,
      t10._2,
      t10._3,
      t10._4,
      t10._5,
      t10._6,
      t10._7,
      t10._8,
      t10._9,
      t10._10
    )

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data
  ](
      t11: TupleBundle11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11]
  ) =
    (
      t11._1,
      t11._2,
      t11._3,
      t11._4,
      t11._5,
      t11._6,
      t11._7,
      t11._8,
      t11._9,
      t11._10,
      t11._11
    )

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data,
      T12 <: Data
  ](
      t12: TupleBundle12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12]
  ) =
    (
      t12._1,
      t12._2,
      t12._3,
      t12._4,
      t12._5,
      t12._6,
      t12._7,
      t12._8,
      t12._9,
      t12._10,
      t12._11,
      t12._12
    )

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data,
      T12 <: Data,
      T13 <: Data
  ](
      t13: TupleBundle13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13]
  ) =
    (
      t13._1,
      t13._2,
      t13._3,
      t13._4,
      t13._5,
      t13._6,
      t13._7,
      t13._8,
      t13._9,
      t13._10,
      t13._11,
      t13._12,
      t13._13
    )

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data,
      T12 <: Data,
      T13 <: Data,
      T14 <: Data
  ](
      t14: TupleBundle14[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14
      ]
  ) =
    (
      t14._1,
      t14._2,
      t14._3,
      t14._4,
      t14._5,
      t14._6,
      t14._7,
      t14._8,
      t14._9,
      t14._10,
      t14._11,
      t14._12,
      t14._13,
      t14._14
    )

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data,
      T12 <: Data,
      T13 <: Data,
      T14 <: Data,
      T15 <: Data
  ](
      t15: TupleBundle15[
        T1,
        T2,
        T3,
        T4,
        T5,
        T6,
        T7,
        T8,
        T9,
        T10,
        T11,
        T12,
        T13,
        T14,
        T15
      ]
  ) =
    (
      t15._1,
      t15._2,
      t15._3,
      t15._4,
      t15._5,
      t15._6,
      t15._7,
      t15._8,
      t15._9,
      t15._10,
      t15._11,
      t15._12,
      t15._13,
      t15._14,
      t15._15
    )
}

object TupleBundle {
  def apply[T1 <: Data, T2 <: Data](input1: T1, input2: T2) = {
    val t2 = TupleBundle2(HardType(input1), HardType(input2))
    t2._1 := input1
    t2._2 := input2
    t2
  }

  def apply[T1 <: Data, T2 <: Data, T3 <: Data](
      input1: T1,
      input2: T2,
      input3: T3
  ) = {
    val t3 = TupleBundle3(HardType(input1), HardType(input2), HardType(input3))
    t3._1 := input1
    t3._2 := input2
    t3._3 := input3
    t3
  }

  def apply[T1 <: Data, T2 <: Data, T3 <: Data, T4 <: Data](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4
  ) = {
    val t4 = TupleBundle4(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4)
    )
    t4._1 := input1
    t4._2 := input2
    t4._3 := input3
    t4._4 := input4
    t4
  }

  def apply[T1 <: Data, T2 <: Data, T3 <: Data, T4 <: Data, T5 <: Data](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5
  ) = {
    val t5 = TupleBundle5(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5)
    )
    t5._1 := input1
    t5._2 := input2
    t5._3 := input3
    t5._4 := input4
    t5._5 := input5
    t5
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data
  ](input1: T1, input2: T2, input3: T3, input4: T4, input5: T5, input6: T6) = {
    val t6 = TupleBundle6(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6)
    )
    t6._1 := input1
    t6._2 := input2
    t6._3 := input3
    t6._4 := input4
    t6._5 := input5
    t6._6 := input6
    t6
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7
  ) = {
    val t7 = TupleBundle7(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7)
    )
    t7._1 := input1
    t7._2 := input2
    t7._3 := input3
    t7._4 := input4
    t7._5 := input5
    t7._6 := input6
    t7._7 := input7
    t7
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7,
      input8: T8
  ) = {
    val t8 = TupleBundle8(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7),
      HardType(input8)
    )
    t8._1 := input1
    t8._2 := input2
    t8._3 := input3
    t8._4 := input4
    t8._5 := input5
    t8._6 := input6
    t8._7 := input7
    t8._8 := input8
    t8
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7,
      input8: T8,
      input9: T9
  ) = {
    val t9 = TupleBundle9(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7),
      HardType(input8),
      HardType(input9)
    )
    t9._1 := input1
    t9._2 := input2
    t9._3 := input3
    t9._4 := input4
    t9._5 := input5
    t9._6 := input6
    t9._7 := input7
    t9._8 := input8
    t9._9 := input9
    t9
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7,
      input8: T8,
      input9: T9,
      input10: T10
  ) = {
    val t10 = TupleBundle10(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7),
      HardType(input8),
      HardType(input9),
      HardType(input10)
    )
    t10._1 := input1
    t10._2 := input2
    t10._3 := input3
    t10._4 := input4
    t10._5 := input5
    t10._6 := input6
    t10._7 := input7
    t10._8 := input8
    t10._9 := input9
    t10._10 := input10
    t10
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7,
      input8: T8,
      input9: T9,
      input10: T10,
      input11: T11
  ) = {
    val t11 = TupleBundle11(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7),
      HardType(input8),
      HardType(input9),
      HardType(input10),
      HardType(input11)
    )
    t11._1 := input1
    t11._2 := input2
    t11._3 := input3
    t11._4 := input4
    t11._5 := input5
    t11._6 := input6
    t11._7 := input7
    t11._8 := input8
    t11._9 := input9
    t11._10 := input10
    t11._11 := input11
    t11
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data,
      T12 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7,
      input8: T8,
      input9: T9,
      input10: T10,
      input11: T11,
      input12: T12
  ) = {
    val t12 = TupleBundle12(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7),
      HardType(input8),
      HardType(input9),
      HardType(input10),
      HardType(input11),
      HardType(input12)
    )
    t12._1 := input1
    t12._2 := input2
    t12._3 := input3
    t12._4 := input4
    t12._5 := input5
    t12._6 := input6
    t12._7 := input7
    t12._8 := input8
    t12._9 := input9
    t12._10 := input10
    t12._11 := input11
    t12._12 := input12
    t12
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data,
      T12 <: Data,
      T13 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7,
      input8: T8,
      input9: T9,
      input10: T10,
      input11: T11,
      input12: T12,
      input13: T13
  ) = {
    val t13 = TupleBundle13(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7),
      HardType(input8),
      HardType(input9),
      HardType(input10),
      HardType(input11),
      HardType(input12),
      HardType(input13)
    )
    t13._1 := input1
    t13._2 := input2
    t13._3 := input3
    t13._4 := input4
    t13._5 := input5
    t13._6 := input6
    t13._7 := input7
    t13._8 := input8
    t13._9 := input9
    t13._10 := input10
    t13._11 := input11
    t13._12 := input12
    t13._13 := input13
    t13
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data,
      T12 <: Data,
      T13 <: Data,
      T14 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7,
      input8: T8,
      input9: T9,
      input10: T10,
      input11: T11,
      input12: T12,
      input13: T13,
      input14: T14
  ) = {
    val t14 = TupleBundle14(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7),
      HardType(input8),
      HardType(input9),
      HardType(input10),
      HardType(input11),
      HardType(input12),
      HardType(input13),
      HardType(input14)
    )
    t14._1 := input1
    t14._2 := input2
    t14._3 := input3
    t14._4 := input4
    t14._5 := input5
    t14._6 := input6
    t14._7 := input7
    t14._8 := input8
    t14._9 := input9
    t14._10 := input10
    t14._11 := input11
    t14._12 := input12
    t14._13 := input13
    t14._14 := input14
    t14
  }

  def apply[
      T1 <: Data,
      T2 <: Data,
      T3 <: Data,
      T4 <: Data,
      T5 <: Data,
      T6 <: Data,
      T7 <: Data,
      T8 <: Data,
      T9 <: Data,
      T10 <: Data,
      T11 <: Data,
      T12 <: Data,
      T13 <: Data,
      T14 <: Data,
      T15 <: Data
  ](
      input1: T1,
      input2: T2,
      input3: T3,
      input4: T4,
      input5: T5,
      input6: T6,
      input7: T7,
      input8: T8,
      input9: T9,
      input10: T10,
      input11: T11,
      input12: T12,
      input13: T13,
      input14: T14,
      input15: T15
  ) = {
    val t15 = TupleBundle15(
      HardType(input1),
      HardType(input2),
      HardType(input3),
      HardType(input4),
      HardType(input5),
      HardType(input6),
      HardType(input7),
      HardType(input8),
      HardType(input9),
      HardType(input10),
      HardType(input11),
      HardType(input12),
      HardType(input13),
      HardType(input14),
      HardType(input15)
    )
    t15._1 := input1
    t15._2 := input2
    t15._3 := input3
    t15._4 := input4
    t15._5 := input5
    t15._6 := input6
    t15._7 := input7
    t15._8 := input8
    t15._9 := input9
    t15._10 := input10
    t15._11 := input11
    t15._12 := input12
    t15._13 := input13
    t15._14 := input14
    t15._15 := input15
    t15
  }
}
