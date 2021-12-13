package rdma

import spinal.core._
import spinal.lib._
import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._

object setAllBits {
  def apply(width: Int): BigInt = {
    val rslt: BigInt = (1 << width) - 1
    rslt
  }

  def apply(width: UInt): Bits =
    new Composite(width) {
      val one = U(1, 1 bit)
      val shift = (one << width) - 1
      val rslt = shift.asBits
    }.rslt
}

object IPv4Addr {
  def apply(ipv4Str: String): Bits = {
    ipv4Str match {
      case s"$a.$b.$c.$d" => {
        val parts = List(a, b, c, d).map(_.toInt)
        assert(!parts.exists(_ > 255), s"invalid IPv4 addr=$ipv4Str")
        parts.map(i => B(i & 0xff, 8 bits)).reduce(_ ## _)
      }
      case _ => {
        assert(False, s"illegal IPv4 string=${ipv4Str}")
        B(0, 32 bits)
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

/** Segment the inputStream into multiple pieces,
  * Each piece is at most segmentLenBytes long, and segmentLenBytes cannot be zero.
  * Each piece is indicated by fragment last.
  */
class StreamSegment[T <: Data](dataType: HardType[T]) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(dataType())))
    val fragmentNum = in(UInt(PMTU_FRAG_NUM_WIDTH bits))
    val outputStream = master(Stream(Fragment(dataType())))
  }

  val inputValid = io.inputStream.valid
  val inputData = io.inputStream.fragment
  val isFirstFrag = io.inputStream.isFirst
  val isLastFrag = io.inputStream.isLast

//  require(isPow2(inputWidth), s"inputWidth=${inputWidth} should be power of 2")
  assert(
    assertion = CountOne(io.fragmentNum) > 0,
    message = L"fragmentNum=${io.fragmentNum} should be larger than 0",
    severity = FAILURE
  )

  val inputFragCnt =
    Counter(bitCount = widthOf(io.fragmentNum) bits, inc = io.inputStream.fire)
  val isLastOutputFrag = inputFragCnt.value === (io.fragmentNum - 1)
  when(io.inputStream.fire && (isLastFrag || isLastOutputFrag)) {
    inputFragCnt.clear()
  }

  io.outputStream <-/< io.inputStream.translateWith {
    val rslt = cloneOf(io.inputStream.payload) // Fragment(dataType())
    rslt.fragment := inputData
    rslt.last := isLastOutputFrag
    rslt
  }
}

object StreamSegment {
  def apply[T <: Data](
      inputStream: Stream[Fragment[T]],
      fragmentNum: UInt
  ): Stream[Fragment[T]] = {
    val rslt = new StreamSegment(cloneOf(inputStream.payload.fragment))
    rslt.io.inputStream << inputStream
    rslt.io.fragmentNum := fragmentNum
    rslt.io.outputStream
  }
}

/** When adding header, the headerMty can only have consecutive zero bits from the left-most side.
  * Assume the header width is 4, the valid header Mty can only be 4'b0000, 4'b0001, 4'b0011,
  * 4'b0111, 4'b1111.
  * The output stream does not make any change to the header.
  */
class StreamAddHeader(width: Int, headerWidth: Int) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(width))))
    val header = in(Bits(headerWidth bits))
    val headerMty = in(Bits((headerWidth / 8) bits))
    val outputStream = master(Stream(Fragment(DataAndMty(width))))
  }

  val inputValid = io.inputStream.valid
  val inputData = io.inputStream.data
  val inputMty = io.inputStream.mty
  val isFirstFrag = io.inputStream.isFirst
  val isLastFrag = io.inputStream.isLast

  val inputWidth = widthOf(inputData)
  val inputMtyWidth = widthOf(inputMty)
  val inputWidthBytes = inputWidth / 8
  require(
    inputWidthBytes == inputMtyWidth,
    s"inputWidthBytes=${inputWidthBytes} should == inputMtyWidth=${inputMtyWidth}"
  )

  val headerLenBytes = widthOf(io.header) / 8
  val headerMtyWidth = widthOf(io.headerMty)
  require(
    headerLenBytes == headerMtyWidth,
    s"headerLenBytes=${headerLenBytes} should == headerMtyWidth=${headerMtyWidth}"
  )
  require(
    headerLenBytes <= inputWidthBytes,
    s"headerLenBytes=${headerLenBytes} should <= inputWidthBytes=${inputWidthBytes}"
  )

  when(inputValid) {
    when(!isLastFrag) {
      assert(
        assertion = inputMty.andR,
        message =
          L"the first or middle fragment should have MTY all set, but MTY=${inputMty}",
        severity = FAILURE
      )
    } otherwise {
      assert(
        assertion = inputMty.orR,
        message = L"the last fragment should have MTY=/=0, but MTY=${inputMty}",
        severity = FAILURE
      )
    }
  }
  when(io.headerMty.orR) {
    assert(
      assertion = OHMasking.first(io.headerMty) === 1,
      message =
        L"the headerMty should have consecutive valid bits from LSB side, but MTY=${inputMty}",
      severity = FAILURE
    )
  }

  val cacheValidReg = RegInit(False)
  val cachedDataReg = Reg(Bits(inputWidth bits))
  val cachedMtyReg = Reg(Bits(inputMtyWidth bits))

  val isOutputStreamLastResidue = RegInit(False)
  // If inputWidth is 32 bits, headerMty is 4'b0011,
  // If the inputMty of the last fragment is 4'b1100, then after the last input fragment,
  // There is no bytes remaining to output.
  // If the inputMty of the last fragment is 4'b1110, then after the last input fragment,
  // THere is one extra byte remaining to output.
  val lastFragHasResidue = (inputMty & io.headerMty.resize(inputMtyWidth)).orR

  val rightShiftBitAmt = inputWidth - headerWidth
  val rightShiftByteAmt = inputMtyWidth - headerMtyWidth
  val outputData = Bits(inputWidth bits)
  val outputMty = Bits(inputWidthBytes bits)
  when(isFirstFrag) { // isFirstFrag is true, concatenate header and inputData
    outputData := (io.header ## inputData).resizeLeft(inputWidth)
    outputMty := (io.headerMty ## inputMty).resizeLeft(inputMtyWidth)
  } otherwise {
    outputData := (cachedDataReg(0, headerWidth bits) ## inputData)
      .resizeLeft(inputWidth)
    outputMty := (cachedMtyReg(0, headerMtyWidth bits) ## inputMty)
      .resizeLeft(inputMtyWidth)
  }
  val inputStreamTranslate = io.inputStream.translateWith {
    val rslt = cloneOf(io.inputStream.payload)
    rslt.data := outputData
    rslt.mty := outputMty
    rslt.last := isLastFrag && !lastFragHasResidue
    rslt
  }

  // The extra last fragment
  val extraLastFragData = (cachedDataReg(0, headerWidth bits) ##
    B(0, inputWidth bits)).resizeLeft(inputWidth)
  // When output stream has residue, the last beat is only from cachedDataReg, not from inputData
  val extraLastFragMty = (cachedMtyReg(0, headerMtyWidth bits) ##
    B(0, inputMtyWidth bits)).resizeLeft(inputMtyWidth)
  val extraLastFragStream = cloneOf(io.inputStream)
  extraLastFragStream.valid := isOutputStreamLastResidue
  extraLastFragStream.data := extraLastFragData
  extraLastFragStream.mty := extraLastFragMty
  extraLastFragStream.last := True

  val outputStream = StreamMux(
    select = isOutputStreamLastResidue.asUInt,
    Vec(inputStreamTranslate, extraLastFragStream)
  )
  when(isOutputStreamLastResidue) {
    when(outputStream.fire) {
      isOutputStreamLastResidue := False
    }
  }
  when(outputStream.fire) {
    cachedDataReg := inputData
    cachedMtyReg := inputMty

    when(isLastFrag) {
      when(lastFragHasResidue) { // Input stream ends but output stream has one more beat
        cacheValidReg := True
        isOutputStreamLastResidue := True
      } otherwise { // Both input and output stream end
        cacheValidReg := False
        // outputStream.last := True
      }
    } otherwise {
      cacheValidReg := True
    }
  }

  io.outputStream <-/< outputStream
}

object StreamAddHeader {
  def apply(
      inputStream: Stream[Fragment[DataAndMty]],
      header: Bits,
      headerMty: Bits
  ): Stream[Fragment[DataAndMty]] = {
    val rslt =
      new StreamAddHeader(widthOf(inputStream.data), widthOf(header))
    rslt.io.inputStream << inputStream
    rslt.io.header := header
    rslt.io.headerMty := headerMty
    rslt.io.outputStream
  }
}

/** When remove header, it removes the first several bytes data from the first fragment,
  * StreamRemoveHeader will shift the remaining bits,
  * The output stream have valid data start from MSB for all fragments.
  */
class StreamRemoveHeader(width: Int) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(width))))
    val headerLenBytes = in(UInt(log2Up(width / 8) + 1 bits))
    val outputStream = master(Stream(Fragment(DataAndMty(width))))
  }

  val inputValid = io.inputStream.valid
  val inputData = io.inputStream.data
  val inputMty = io.inputStream.mty
  val isFirstFrag = io.inputStream.isFirst
  val isLastFrag = io.inputStream.isLast

  val inputWidth = widthOf(inputData)
  val inputMtyWidth = widthOf(inputMty)
  val inputWidthBytes = inputWidth / 8
  require(
    inputWidthBytes == inputMtyWidth,
    s"inputWidthBytes=${inputWidthBytes} should == inputMtyWidth=${inputMtyWidth}"
  )
  // TODO: assert message cannot use non-Spinal-Data variable
  assert(
    assertion = 0 < io.headerLenBytes && io.headerLenBytes <= inputWidthBytes,
    message =
      L"must have 0 < headerLenBytes=${io.headerLenBytes} <= inputWidthBytes=${U(inputWidthBytes, widthOf(io.headerLenBytes) bits)}",
    severity = FAILURE
  )

  val cacheValidReg = RegInit(False)
  val cachedDataReg = Reg(Bits(inputWidth bits))
  val cachedMtyReg = Reg(Bits(inputMtyWidth bits))

  val headerMty = setAllBits(io.headerLenBytes).resize(inputMtyWidth)
//  val headerMty = Bits(inputMtyWidth bits)
//  headerMty := setAllBits(io.headerLenBytes).resize(inputMtyWidth)

  val isOutputStreamLastResidue = RegInit(False)
  // If inputWidth is 32 bits, headerLenBytes is 2,
  // If the inputMty of the last fragment is 4'b1100, then after the last input fragment,
  // There is no bytes remaining to output.
  // If the inputMty of the last fragment is 4'b1110, then after the last input fragment,
  // THere is one extra byte remaining to output.
  val lastFragHasResidue = (inputMty & headerMty).orR

  val rightShiftBitAmt = UInt(log2Up(width) + 1 bits)
  rightShiftBitAmt := inputWidth - (io.headerLenBytes << 3) // left shift 3 bits mean multiply by 8
  val rightShiftByteAmt = UInt(widthOf(io.headerLenBytes) bits)
  rightShiftByteAmt := inputMtyWidth - io.headerLenBytes

  val outputData = Bits(inputWidth bits)
  val outputMty = Bits(inputWidthBytes bits)
  outputData := ((cachedDataReg ## inputData) >> rightShiftBitAmt)
    .resize(inputWidth)
  outputMty := ((cachedMtyReg ## inputMty) >> rightShiftByteAmt)
    .resize(inputMtyWidth)

  val throwCond =
    isFirstFrag // && !cacheValidReg // || isOutputStreamLastResidue)
  val inputStreamTranslate = io.inputStream
    .throwWhen(throwCond)
    .translateWith {
      val rslt = cloneOf(io.inputStream.payload)
      rslt.data := outputData
      rslt.mty := outputMty
      rslt.last := isLastFrag && !lastFragHasResidue
      rslt
    }

  // The extra last fragment
  val extraLastFragData =
    ((cachedDataReg ## B(0, inputWidth bits)) >> rightShiftBitAmt)
      .resize(inputWidth)
  // When output stream has residue, the last beat is only from cachedDataReg, not from inputData
  val extraLastFragMty =
    ((cachedMtyReg ## B(0, inputMtyWidth bits)) >> rightShiftByteAmt)
      .resize(inputMtyWidth)
  val extraLastFragStream = cloneOf(io.inputStream)
  extraLastFragStream.valid := isOutputStreamLastResidue
  extraLastFragStream.data := extraLastFragData
  extraLastFragStream.mty := extraLastFragMty
  extraLastFragStream.last := True

  val outputStream = StreamMux(
    select = isOutputStreamLastResidue.asUInt,
    Vec(inputStreamTranslate, extraLastFragStream)
  )
  when(isOutputStreamLastResidue) {
    when(outputStream.fire) {
      isOutputStreamLastResidue := False
    }
  }

  // For the very first fragment, save it
  when(isFirstFrag && !cacheValidReg) {
    cacheValidReg := inputValid
    cachedDataReg := inputData
    cachedMtyReg := inputMty
  }

  when(outputStream.fire) {
    cachedDataReg := inputData
    cachedMtyReg := inputMty

    when(isLastFrag) {
      when(lastFragHasResidue) { // Input stream ends but output stream has one more beat
        cacheValidReg := True
        isOutputStreamLastResidue := True
      } otherwise { // Both input and output stream end
        cacheValidReg := False
      }
    } otherwise {
      cacheValidReg := True
    }
  }

  io.outputStream <-/< outputStream
}

object StreamRemoveHeader {
  def apply(
      inputStream: Stream[Fragment[DataAndMty]],
      headerLenBytes: UInt,
      width: Int
  ): Stream[Fragment[DataAndMty]] = {
    val rslt = new StreamRemoveHeader(width)
    rslt.io.inputStream << inputStream
    rslt.io.headerLenBytes := headerLenBytes
    rslt.io.outputStream
  }
}

object psnComp {
  def apply(psnA: UInt, psnB: UInt, curPsn: UInt): UInt =
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

object computePktNum {
  def apply(len: UInt, pmtu: Bits): UInt =
    new Composite(len) {
      val rslt = UInt(PSN_WIDTH bits)
      val shiftAmt = UInt(PMTU_WIDTH bits)
      val residueMaxWidth = PMTU.U4096.id
      val residue = UInt(residueMaxWidth bits)
      switch(pmtu) {
        is(PMTU.U256.id) {
          shiftAmt := PMTU.U256.id
          residue := len(0, PMTU.U256.id bits).resize(residueMaxWidth)
        }
        is(PMTU.U512.id) {
          shiftAmt := PMTU.U512.id
          residue := len(0, PMTU.U512.id bits).resize(residueMaxWidth)
        }
        is(PMTU.U1024.id) {
          shiftAmt := PMTU.U1024.id
          residue := len(0, PMTU.U1024.id bits).resize(residueMaxWidth)
        }
        is(PMTU.U2048.id) {
          shiftAmt := PMTU.U2048.id
          residue := len(0, PMTU.U2048.id bits).resize(residueMaxWidth)
        }
        is(PMTU.U4096.id) {
          shiftAmt := PMTU.U4096.id
          residue := len(0, PMTU.U4096.id bits).resize(residueMaxWidth)
        }
        default {
          shiftAmt := 0
          residue := 0

          assert(
            assertion = False,
            message = L"invalid PMTU=$pmtu",
            severity = FAILURE
          )
        }
      }

      val pktNum = (len >> shiftAmt) + (residue.orR).asUInt
      // This downsize is safe, since max length is 2^32 bytes,
      // And the min packet size is 256=2^8 bytes,
      // So the max packet number is 2^PSN_WIDTH=2^24
      rslt := pktNum.resize(PSN_WIDTH)
//      when(residue.orR) {
//        rslt := (len >> shiftAmt) + 1
//      } otherwise {
//        rslt := (len >> shiftAmt)
//      }
    }.rslt
}

object bufSizeCheck {
  def apply(
      targetPhysicalAddr: UInt,
      dataLenBytes: UInt,
      bufPhysicalAddr: UInt,
      bufSize: UInt
  ): Bool =
    new Composite(dataLenBytes) {
      val bufEndAddr = bufPhysicalAddr + bufSize
      val targetEndAddr = targetPhysicalAddr + dataLenBytes
      val rslt =
        bufPhysicalAddr <= targetPhysicalAddr && targetEndAddr <= bufEndAddr
    }.rslt
}

object pmtuPktLenBytes {
  def apply(pmtu: Bits): UInt = {
    val rslt = UInt(PMTU_FRAG_NUM_WIDTH bits)
    switch(pmtu) {
      // The value means right shift amount of bits
      is(PMTU.U256.id) { // 8
        rslt := 256
      }
      is(PMTU.U512.id) { // 9
        rslt := 512
      }
      is(PMTU.U1024.id) { // 10
        rslt := 1024
      }
      is(PMTU.U2048.id) { // 11
        rslt := 2048
      }
      is(PMTU.U4096.id) { // 12
        rslt := 4096
      }
      default {
        rslt := 0
        assert(
          assertion = False,
          message = L"invalid PMTU Bits=${pmtu}",
          severity = FAILURE
        )
      }
    }
    rslt
  }
}

object pmtuLenMask {
  def apply(pmtu: Bits): Bits = {
    val rslt = Bits(PMTU_FRAG_NUM_WIDTH bits)
    switch(pmtu) {
      // The value means right shift amount of bits
      is(PMTU.U256.id) { // 8
        rslt := 256 - 1
      }
      is(PMTU.U512.id) { // 9
        rslt := 512 - 1
      }
      is(PMTU.U1024.id) { // 10
        rslt := 1024 - 1
      }
      is(PMTU.U2048.id) { // 11
        rslt := 2048 - 1
      }
      is(PMTU.U4096.id) { // 12
        rslt := 4096 - 1
      }
      default {
        rslt := 0
        assert(
          assertion = False,
          message = L"invalid PMTU Bits=${pmtu}",
          severity = FAILURE
        )
      }
    }
    rslt
  }
}

/** Divide the dividend by a divisor, and take the celling
  * NOTE: divisor must be power of 2
  */
object divideByPmtuUp {
  def apply(dividend: UInt, pmtu: Bits): UInt = {
    val shiftAmt = pmtuPktLenBytes(pmtu)
    dividend >> shiftAmt + (moduloByPmtu(dividend, pmtu).orR.asUInt
      .resize(widthOf(dividend)))
  }
}

/** Modulo of the dividend by a divisor.
  * NOTE: divisor must be power of 2
  */
object moduloByPmtu {
  def apply(dividend: UInt, pmtu: Bits): UInt = {
//    require(
//      widthOf(dividend) >= widthOf(divisor),
//      "width of dividend should >= that of divisor"
//    )
//    assert(
//      assertion = CountOne(divisor) === 1,
//      message = L"divisor=${divisor} should be power of 2",
//      severity = FAILURE
//    )
    val mask = pmtuLenMask(pmtu)
    dividend & mask.resize(widthOf(dividend)).asUInt
  }
}

object StreamSequentialFork {
  def apply[T <: Data](input: Stream[T], portCount: Int): Vec[Stream[T]] = {
    val fork = new StreamSequentialFork(input.payloadType, portCount)
      .setCompositeName(input, "seqfork", true)
    fork.io.input << input
    fork.io.outputs
  }
}

class StreamSequentialFork[T <: Data](dataType: HardType[T], portCount: Int)
    extends Component {
  val io = new Bundle {
    val input = slave(Stream(dataType))
    val outputs = Vec(master(Stream(dataType)), portCount)
  }

  // Store if an output stream already has taken its value or not
  val linkEnable = RegInit(Bits(portCount bits).setAll())

  // Ready is true when every output stream takes or has taken its value
  io.input.ready := True
  for (i <- 0 until portCount) {
    when(!io.outputs(i).ready && linkEnable(i)) {
      io.input.ready := False
    }
  }

  // Outputs are valid if the input is valid and
  // preceding output streams have taken their values,
  // When an output fires, mark its value as taken. */
  for (i <- 0 until portCount) {
    io.outputs(i).valid := io.input.valid && !(linkEnable(0, i bits).orR)
    io.outputs(i).payload := io.input.payload
    when(io.outputs(i).fire) {
      linkEnable(i) := False
    }
  }

  // Reset the storage for each new value
  when(io.input.ready) {
    linkEnable.setAll()
  }
}

object StreamSource {
  def apply(): Event = {
    val rslt = Event
    rslt.valid := True
    rslt
  }
}

object StreamSink {
  def apply[T <: Data](payloadType: HardType[T]): Stream[T] =
    Stream(payloadType).freeRun()
}

/*
object StreamPipeStage {
  def apply[T1 <: Data, T2 <: Data](input: Stream[T1],
                                    continue: Bool,
                                    flush: Bool)(f: (T1, Bool) => T2) =
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
    continue: Bool,
    flush: Bool
  )(f: (T1, Bool) => T2)(sink: Stream[T2]) =
    new Area {
      sink << StreamPipeStage(input, continue, flush)(f)
    }
}
 */

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

  def checkReqSeq(preOpCode: Bits, curOpCode: Bits): Bool =
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

  def checkReadRespSeq(preOpCode: Bits, curOpCode: Bits): Bool =
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

object padCount {
  def apply(pktLen: UInt): UInt =
    new Composite(pktLen) {
      val padCntWidth = 2 // Pad count is 0 ~ 3
      val padCnt = UInt(padCntWidth bits)
      val pktLenRightMost2Bits = pktLen.resize(padCntWidth)
      padCnt := (4 - pktLenRightMost2Bits).resize(padCntWidth)
    }.padCnt
}

object reqPadCountCheck {
  def apply(
      opcode: Bits,
      padCount: UInt,
      pktMty: Bits,
      isLastFrag: Bool,
      busWidth: BusWidth
  ): Bool =
    new Composite(padCount) {
      val paddingCheck = True
      val mtyCheck = Bool()
      val pktFragLen = CountOne(pktMty)
      when(OpCode.isFirstReqPkt(opcode) || OpCode.isMidReqPkt(opcode)) {
        paddingCheck := padCount === 0
      }
      when(!isLastFrag) {
        // For the non-last packet fragment, MTY should be all set
        mtyCheck := pktMty.andR
      } otherwise {
        // Last packet fragment should not be empty
        mtyCheck := pktMty.orR
        when(OpCode.isAtomicReqPkt(opcode)) {
          // Atomic request has BTH and AtomicEth, total 48 + 28 = 76 bytes
          // BTH is not included in packet payload
          mtyCheck := pktFragLen === ((widthOf(BTH()) + widthOf(
            AtomicEth()
          )) % busWidth.id)
        } elsewhen (OpCode.isReadReqPkt(opcode)) {
          // Read request has BTH and RETH, total 48 + 16 = 64 bytes
          // BTH is not included in packet payload
          // Assume RETH fits in bus width
          mtyCheck := pktFragLen === (widthOf(RETH()) % busWidth.id)
        }
      }
      val rslt = mtyCheck && paddingCheck
    }.rslt
}

object respPadCountCheck {
  def apply(
      opcode: Bits,
      padCount: UInt,
      pktMty: Bits,
      isLastFrag: Bool,
      busWidth: BusWidth
  ): Bool =
    new Composite(padCount) {
      val paddingCheck = True
      val mtyCheck = Bool()
      val pktFragLen = CountOne(pktMty)
      when(
        OpCode.isFirstReadRespPkt(opcode) ||
          OpCode.isMidReadRespPkt(opcode) ||
          OpCode.isSendOrWriteRespPkt(opcode) ||
          OpCode.isAtomicRespPkt(opcode)
      ) {
        paddingCheck := padCount === 0
      }
      when(!isLastFrag) {
        // For the non-last packet fragment, MTY should be all set
        mtyCheck := pktMty.andR
      } otherwise {
        // Last packet fragment should not be empty
        mtyCheck := pktMty.orR
        when(OpCode.isAtomicRespPkt(opcode)) {
          // Atomic response has BTH and AtomicAckETH, total 48 + 8 = 56 bytes
          // BTH is included in packet payload
          // Assume AtomicAckETH fits in bus width
          mtyCheck := pktFragLen === (widthOf(BTH()) + widthOf(
            AtomicAckETH()
          ) % busWidth.id)
        } elsewhen (OpCode.isSendOrWriteRespPkt(opcode)) {
          // Acknowledge has BTH and AETH, total 48 + 4 = 52 bytes
          // BTH is included in packet payload
          // Assume AETH fits in bus width
          mtyCheck := pktFragLen === (widthOf(BTH()) + widthOf(
            AETH()
          ) % busWidth.id)
        } elsewhen (OpCode.isLastOrOnlyReadRespPkt(opcode)) {
          // Last or Only read response has BTH and AETH and possible read data, at least 48 + 4 = 52 bytes
          // BTH is included in packet payload
          // Assume AETH fits in bus width
          mtyCheck := pktFragLen >= (widthOf(BTH()) + widthOf(
            AETH()
          ) % busWidth.id)
        }
      }
      val rslt = mtyCheck && paddingCheck
    }.rslt
}

//object pktLenCheck {
//  def pmtuLen(pmtu: Bits) = {
//    val rslt = UInt(RDMA_MAX_LEN_WIDTH bits)
//    switch(pmtu) {
//      is(PMTU.U256.id) {
//        rslt := 256
//      }
//      is(PMTU.U512.id) {
//        rslt := 512
//      }
//      is(PMTU.U1024.id) {
//        rslt := 1024
//      }
//      is(PMTU.U2048.id) {
//        rslt := 2048
//      }
//      is(PMTU.U4096.id) {
//        rslt := 4096
//      }
//      default {
//        assert(
//          assertion = False,
//          message = L"invalid PMTU=$pmtu",
//          severity = FAILURE
//        )
//        rslt := 0
//      }
//    }
//    rslt
//  }
//
//  def apply(pktTotalLen: UInt, opcode: Bits, pmtu: Bits, busWidth: BusWidth) =
//    new Composite(pktTotalLen) {
//      val lengthCheck = Bool()
//      when(
//        OpCode.isFirstReqPkt(opcode) || OpCode.isMidReqPkt(opcode) || OpCode
//          .isMidReadRespPkt(opcode)
//      ) {
//        // TODO: verify length check correctness
//        lengthCheck := pmtuLen(pmtu) === pktTotalLen
//      } elsewhen (OpCode.isLastReqPkt(opcode) || OpCode.isLastReadRespPkt(
//        opcode
//      )) {
//        lengthCheck := 1 <= pktTotalLen && pktTotalLen <= pmtuLen(pmtu)
//      } otherwise {
//        lengthCheck := True
//      }
//    }.lengthCheck
//}

object StreamVec {
  implicit def build[T <: Data](that: Vec[Stream[T]]): StreamVec[T] =
    new StreamVec(that)
}

class StreamVec[T <: Data](val strmVec: Vec[Stream[T]]) {
  def <-/<(that: Vec[Stream[T]]): Area = new Area {
    require(
      strmVec.length == that.length,
      s"StreamVec size not match, this.len=${strmVec.length} != that.len=${that.length}"
    )
    for (idx <- 0 until that.length) {
      strmVec(idx) <-/< that(idx)
    }
  }
  def <<(that: Vec[Stream[T]]): Area = new Area {
    require(
      strmVec.length == that.length,
      s"StreamVec size not match, this.len=${strmVec.length} != that.len=${that.length}"
    )
    for (idx <- 0 until that.length) {
      strmVec(idx) << that(idx)
    }
  }

//  def >-/>(that: Vec[Stream[T]]) = new Area {
//    require(
//      strmVec.length == that.length,
//      s"StreamVec size not match, this.len=${strmVec.length} != that.len=${that.length}"
//    )
//    for (idx <- 0 until that.length) {
//      that(idx) <-/< strmVec(idx)
//    }
//  }
}

object ComponentExtensions {
  implicit class ComponentExt[T <: Component](val that: T) {
    def stub(): T = that.rework {
      // step1: First remove all we don't want
      that.children.clear()
      that.dslBody.foreachStatements {
        case bt: BaseType if !bt.isDirectionLess =>
        case s                                   => s.removeStatement()
      }
      // step2: remove output and assign zero
      // this step can't merge into step1
      that.dslBody.foreachStatements {
        case bt: BaseType if bt.isOutput | bt.isInOut =>
          bt.removeAssignments()
          bt := bt.getZero
        case _ =>
      }
      that
    }
  }
}

//object asTuple {
//  def apply[T1 <: Data, T2 <: Data](t2: TupleBundle2[T1, T2]) =
//    (t2._1, t2._2)
//
//  def apply[T1 <: Data, T2 <: Data, T3 <: Data](t3: TupleBundle3[T1, T2, T3]) =
//    (t3._1, t3._2, t3._3)
//
//  def apply[T1 <: Data, T2 <: Data, T3 <: Data, T4 <: Data](
//    t4: TupleBundle4[T1, T2, T3, T4]
//  ) =
//    (t4._1, t4._2, t4._3, t4._4)
//
//  def apply[T1 <: Data, T2 <: Data, T3 <: Data, T4 <: Data, T5 <: Data](
//    t5: TupleBundle5[T1, T2, T3, T4, T5]
//  ) =
//    (t5._1, t5._2, t5._3, t5._4, t5._5)
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data](t6: TupleBundle6[T1, T2, T3, T4, T5, T6]) =
//    (t6._1, t6._2, t6._3, t6._4, t6._5, t6._6)
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data](t7: TupleBundle7[T1, T2, T3, T4, T5, T6, T7]) =
//    (t7._1, t7._2, t7._3, t7._4, t7._5, t7._6, t7._7)
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data](t8: TupleBundle8[T1, T2, T3, T4, T5, T6, T7, T8]) =
//    (t8._1, t8._2, t8._3, t8._4, t8._5, t8._6, t8._7, t8._8)
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data,
//            T9 <: Data](t9: TupleBundle9[T1, T2, T3, T4, T5, T6, T7, T8, T9]) =
//    (t9._1, t9._2, t9._3, t9._4, t9._5, t9._6, t9._7, t9._8, t9._9)
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data,
//            T9 <: Data,
//            T10 <: Data](
//    t10: TupleBundle10[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10]
//  ) =
//    (
//      t10._1,
//      t10._2,
//      t10._3,
//      t10._4,
//      t10._5,
//      t10._6,
//      t10._7,
//      t10._8,
//      t10._9,
//      t10._10
//    )
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data,
//            T9 <: Data,
//            T10 <: Data,
//            T11 <: Data](
//    t11: TupleBundle11[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11]
//  ) =
//    (
//      t11._1,
//      t11._2,
//      t11._3,
//      t11._4,
//      t11._5,
//      t11._6,
//      t11._7,
//      t11._8,
//      t11._9,
//      t11._10,
//      t11._11
//    )
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data,
//            T9 <: Data,
//            T10 <: Data,
//            T11 <: Data,
//            T12 <: Data](
//    t12: TupleBundle12[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12]
//  ) =
//    (
//      t12._1,
//      t12._2,
//      t12._3,
//      t12._4,
//      t12._5,
//      t12._6,
//      t12._7,
//      t12._8,
//      t12._9,
//      t12._10,
//      t12._11,
//      t12._12
//    )
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data,
//            T9 <: Data,
//            T10 <: Data,
//            T11 <: Data,
//            T12 <: Data,
//            T13 <: Data](
//    t13: TupleBundle13[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12, T13]
//  ) =
//    (
//      t13._1,
//      t13._2,
//      t13._3,
//      t13._4,
//      t13._5,
//      t13._6,
//      t13._7,
//      t13._8,
//      t13._9,
//      t13._10,
//      t13._11,
//      t13._12,
//      t13._13
//    )
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data,
//            T9 <: Data,
//            T10 <: Data,
//            T11 <: Data,
//            T12 <: Data,
//            T13 <: Data,
//            T14 <: Data](
//    t14: TupleBundle14[T1,
//                       T2,
//                       T3,
//                       T4,
//                       T5,
//                       T6,
//                       T7,
//                       T8,
//                       T9,
//                       T10,
//                       T11,
//                       T12,
//                       T13,
//                       T14]
//  ) =
//    (
//      t14._1,
//      t14._2,
//      t14._3,
//      t14._4,
//      t14._5,
//      t14._6,
//      t14._7,
//      t14._8,
//      t14._9,
//      t14._10,
//      t14._11,
//      t14._12,
//      t14._13,
//      t14._14
//    )
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data,
//            T9 <: Data,
//            T10 <: Data,
//            T11 <: Data,
//            T12 <: Data,
//            T13 <: Data,
//            T14 <: Data,
//            T15 <: Data](
//    t15: TupleBundle15[T1,
//                       T2,
//                       T3,
//                       T4,
//                       T5,
//                       T6,
//                       T7,
//                       T8,
//                       T9,
//                       T10,
//                       T11,
//                       T12,
//                       T13,
//                       T14,
//                       T15]
//  ) =
//    (
//      t15._1,
//      t15._2,
//      t15._3,
//      t15._4,
//      t15._5,
//      t15._6,
//      t15._7,
//      t15._8,
//      t15._9,
//      t15._10,
//      t15._11,
//      t15._12,
//      t15._13,
//      t15._14,
//      t15._15
//    )
//
//  def apply[T1 <: Data,
//            T2 <: Data,
//            T3 <: Data,
//            T4 <: Data,
//            T5 <: Data,
//            T6 <: Data,
//            T7 <: Data,
//            T8 <: Data,
//            T9 <: Data,
//            T10 <: Data,
//            T11 <: Data,
//            T12 <: Data,
//            T13 <: Data,
//            T14 <: Data,
//            T15 <: Data,
//            T16 <: Data](
//    t16: TupleBundle16[T1,
//                       T2,
//                       T3,
//                       T4,
//                       T5,
//                       T6,
//                       T7,
//                       T8,
//                       T9,
//                       T10,
//                       T11,
//                       T12,
//                       T13,
//                       T14,
//                       T15,
//                       T16]
//  ) =
//    (
//      t16._1,
//      t16._2,
//      t16._3,
//      t16._4,
//      t16._5,
//      t16._6,
//      t16._7,
//      t16._8,
//      t16._9,
//      t16._10,
//      t16._11,
//      t16._12,
//      t16._13,
//      t16._14,
//      t16._15,
//      t16._16
//    )
//}

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
      T15 <: Data,
      T16 <: Data
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
      input15: T15,
      input16: T15
  ) = {
    val t16 = TupleBundle16(
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
      HardType(input15),
      HardType(input16)
    )
    t16._1 := input1
    t16._2 := input2
    t16._3 := input3
    t16._4 := input4
    t16._5 := input5
    t16._6 := input6
    t16._7 := input7
    t16._8 := input8
    t16._9 := input9
    t16._10 := input10
    t16._11 := input11
    t16._12 := input12
    t16._13 := input13
    t16._14 := input14
    t16._15 := input15
    t16._16 := input16
    t16
  }
}
