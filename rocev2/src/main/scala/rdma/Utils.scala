package rdma

import spinal.core._
import spinal.lib._
import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._

object timeNumToCycleNum {
  def apply(timeNumber: TimeNumber): BigInt = {
    (timeNumber * ClockDomain.current.frequency.getValue).toBigInt
  }
}

// DMA related utilities

object DmaReadRespHandler {
  def apply[T <: Data](
      inputReq: Stream[T],
      dmaReadResp: DmaReadRespBus,
      flush: Bool,
      busWidth: BusWidth,
      isReqZeroDmaLen: T => Bool
  ): Stream[Fragment[ReqAndDmaReadResp[T]]] = {
    val rslt =
      new DmaReadRespHandler(inputReq.payloadType, busWidth, isReqZeroDmaLen)
    rslt.io.flush := flush
    rslt.io.inputReq << inputReq
    rslt.io.dmaReadResp << dmaReadResp
    rslt.io.reqAndDmaReadResp
  }
}

class DmaReadRespHandler[T <: Data](
    reqType: HardType[T],
    busWidth: BusWidth,
    isReqZeroDmaLen: T => Bool
) extends Component {
  val io = new Bundle {
    val flush = in(Bool())
    val inputReq = slave(Stream(reqType()))
    val dmaReadResp = slave(DmaReadRespBus(busWidth))
    val reqAndDmaReadResp = master(
      Stream(Fragment(ReqAndDmaReadResp(reqType, busWidth)))
    )
  }

  val dmaReadRespValid = io.dmaReadResp.resp.valid
  val isFirstDmaReadResp = io.dmaReadResp.resp.isFirst
  val isLastDmaReadResp = io.dmaReadResp.resp.isLast

  val inputReqQueue = io.inputReq
    .throwWhen(io.flush)
    .queueLowLatency(DMA_READ_DELAY_CYCLE)
  val isEmptyReadReq = isReqZeroDmaLen(inputReqQueue.payload)

  val txSel = UInt(1 bits)
  val (emptyReadIdx, nonEmptyReadIdx) = (0, 1)
  when(isEmptyReadReq) {
    txSel := emptyReadIdx
  } otherwise {
    txSel := nonEmptyReadIdx
  }
  val twoStreams =
    StreamDemux(select = txSel, input = inputReqQueue, portCount = 2)

  // Join inputReq with DmaReadResp
  val joinStream = FragmentStreamJoinStream(
    io.dmaReadResp.resp
      .throwWhen(io.flush),
    twoStreams(nonEmptyReadIdx)
  )

  io.reqAndDmaReadResp << StreamMux(
    select = txSel,
    inputs = Vec(
      twoStreams(emptyReadIdx).translateWith {
        val rslt =
          cloneOf(io.reqAndDmaReadResp.payloadType)
        rslt.dmaReadResp
          .setDefaultVal() // TODO: dmaReadResp.lenBytes set to zero explicitly
        rslt.req := twoStreams(emptyReadIdx).payload
        rslt.last := True
        rslt
      },
      joinStream.translateWith {
        val rslt =
          cloneOf(io.reqAndDmaReadResp.payloadType)
        rslt.dmaReadResp := joinStream._1
        rslt.req := joinStream._2
        rslt.last := joinStream.isLast
        rslt
      }
    )
  )
  when(txSel === emptyReadIdx) {
    assert(
      assertion = !joinStream.valid,
      message =
        L"when request has zero DMA length, it should not handle DMA read response, that joinStream.valid should be false, but joinStream.valid=${joinStream.valid}",
      severity = FAILURE
    )
  } elsewhen (txSel === nonEmptyReadIdx) {
    assert(
      assertion = !twoStreams(emptyReadIdx).valid,
      message =
        L"when request has non-zero DMA length, twoStreams(emptyReadIdx).valid should be false, but twoStreams(emptyReadIdx).valid=${twoStreams(emptyReadIdx).valid}",
      severity = FAILURE
    )
  }
}

object DmaReadRespSegment {
  def apply[T <: Data](
      input: Stream[Fragment[T]],
      flush: Bool,
      pmtu: Bits,
      busWidth: BusWidth,
      isReqZeroDmaLen: T => Bool
  ): Stream[Fragment[T]] = {
    val rslt =
      new DmaReadRespSegment(input.fragmentType, busWidth, isReqZeroDmaLen)
    rslt.io.flush := flush
    rslt.io.pmtu := pmtu
    rslt.io.reqAndDmaReadRespIn << input
    rslt.io.reqAndDmaReadRespOut
  }
}

// Handle zero DMA length request/response too
class DmaReadRespSegment[T <: Data](
    fragType: HardType[T],
    busWidth: BusWidth,
    isReqZeroDmaLen: T => Bool
) extends Component {
  val io = new Bundle {
    val flush = in(Bool())
    val pmtu = in(Bits(PMTU_WIDTH bits))
    val reqAndDmaReadRespIn = slave(Stream(Fragment(fragType)))
    val reqAndDmaReadRespOut = master(Stream(Fragment(fragType)))
  }

  val pmtuMaxFragNum = pmtuPktLenBytes(io.pmtu) >> log2Up(
    busWidth.id / BYTE_WIDTH
  )
  val isEmptyReadReq = isReqZeroDmaLen(io.reqAndDmaReadRespIn.fragment)

  val txSel = UInt(1 bits)
  val (emptyReadIdx, nonEmptyReadIdx) = (0, 1)
  when(isEmptyReadReq) {
    txSel := emptyReadIdx
  } otherwise {
    txSel := nonEmptyReadIdx
  }

  val twoStreams = StreamDemux(
    io.reqAndDmaReadRespIn.throwWhen(io.flush),
    select = txSel,
    portCount = 2
  )
  val segmentStream = StreamSegment(
    twoStreams(nonEmptyReadIdx),
    segmentFragNum = pmtuMaxFragNum.resize(PMTU_FRAG_NUM_WIDTH)
  )
  val output = StreamMux(
    select = txSel,
    inputs = Vec(twoStreams(emptyReadIdx), segmentStream)
  )
  io.reqAndDmaReadRespOut << output
}

object CombineHeaderAndDmaResponse {
  def apply[T <: Data](
      reqAndDmaReadRespSegment: Stream[Fragment[ReqAndDmaReadResp[T]]],
      qpAttr: QpAttrData,
      flush: Bool,
      busWidth: BusWidth,
//      pktNumFunc: (T, QpAttrData) => UInt,
      headerGenFunc: (
          T,
          DmaReadResp,
          UInt,
          QpAttrData
      ) => CombineHeaderAndDmaRespInternalRslt
  ): RdmaDataBus = {
    val rslt = new CombineHeaderAndDmaResponse(
      reqAndDmaReadRespSegment.reqType,
      busWidth,
//      pktNumFunc,
      headerGenFunc
    )
    rslt.io.qpAttr := qpAttr
    rslt.io.flush := flush
    rslt.io.reqAndDmaReadRespSegment << reqAndDmaReadRespSegment
    rslt.io.tx
  }
}

class CombineHeaderAndDmaResponse[T <: Data](
    reqType: HardType[T],
    busWidth: BusWidth,
//    pktNumFunc: (T, QpAttrData) => UInt,
    headerGenFunc: (
        T,
        DmaReadResp,
        UInt,
        QpAttrData
    ) => CombineHeaderAndDmaRespInternalRslt
) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val flush = in(Bool())
    val reqAndDmaReadRespSegment = slave(
      Stream(Fragment(ReqAndDmaReadResp(reqType, busWidth)))
    )
    val tx = master(RdmaDataBus(busWidth))
  }
  val input = io.reqAndDmaReadRespSegment

  val bthHeaderLenBytes = widthOf(BTH()) / BYTE_WIDTH
  val busWidthBytes = busWidth.id / BYTE_WIDTH

  val inputDmaDataFrag = input.dmaReadResp
  val inputReq = input.req
  val isFirstDataFrag = input.isFirst
  val isLastDataFrag = input.isLast

  // Count the number of read response packet processed
  val numPktCnt =
    Counter(bitCount = MAX_PKT_NUM_WIDTH bits, inc = input.lastFire)

  val (numPkt, bth, headerBits, headerMtyBits) =
    headerGenFunc(inputReq, inputDmaDataFrag, numPktCnt.value, io.qpAttr).get()
  // Handle the case that numPkt is zero.
  // It needs to reset numPktCnt by io.flush.
  when((numPktCnt.value + 1 >= numPkt && input.lastFire) || io.flush) {
    numPktCnt.clear()
  }

  val headerStream = StreamSource()
    .throwWhen(io.flush)
    .translateWith {
      val rslt = HeaderDataAndMty(BTH(), busWidth)
      rslt.header := bth
      rslt.data := headerBits
      rslt.mty := headerMtyBits
      rslt
    }
  val addHeaderStream = StreamAddHeader(
    input
      .throwWhen(io.flush)
      .translateWith {
        val rslt = Fragment(DataAndMty(busWidth))
        rslt.data := inputDmaDataFrag.data
        rslt.mty := inputDmaDataFrag.mty
        rslt.last := isLastDataFrag
        rslt
      },
    headerStream,
    busWidth
  )
  io.tx.pktFrag << addHeaderStream.translateWith {
    val rslt = Fragment(RdmaDataPkt(busWidth))
    rslt.bth := addHeaderStream.header
    rslt.data := addHeaderStream.data
    rslt.mty := addHeaderStream.mty
    rslt.last := addHeaderStream.last
    rslt
  }
}

// Stream related utilities

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

/** Throw the first several fragments of the inputStream.
  * The number of fragments to throw is specified by headerFragNum.
  * Note, headerFragNum should keep stable during the first fragment of inputStream.
  */
class StreamDropHeader[T <: Data](dataType: HardType[T]) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(dataType())))
    val headerFragNum = in(UInt(PMTU_FRAG_NUM_WIDTH bits))
    val outputStream = master(Stream(Fragment(dataType())))
  }

  val headerFragNumReg =
    RegNextWhen(io.headerFragNum, cond = io.inputStream.firstFire)
  val headerFragCnt = Counter(bitCount = MAX_PKT_NUM_WIDTH bits)
  val headerFragCntVal = headerFragCnt.value
  val throwCond =
    io.inputStream.isFirst ? (headerFragCntVal < io.headerFragNum) | (headerFragCntVal < headerFragNumReg)
  when(io.inputStream.fire && throwCond) {
    headerFragCnt.increment()
  }
  when(io.inputStream.lastFire) {
    headerFragCnt.clear()
    headerFragNumReg.clearAll() // Set to zero
  }

  io.outputStream << io.inputStream.throwWhen(throwCond)
}

object StreamDropHeader {
  def apply[T <: Data](
      inputStream: Stream[Fragment[T]],
      headerFragNum: UInt
  ): Stream[Fragment[T]] = {
    val rslt = new StreamDropHeader(cloneOf(inputStream.fragmentType))
    rslt.io.inputStream << inputStream
    rslt.io.headerFragNum := headerFragNum
    rslt.io.outputStream
  }
}

/** Segment the inputStream into multiple pieces,
  * Each piece is at most segmentLenBytes long, and segmentLenBytes cannot be zero.
  * Each piece is indicated by fragment last.
  */
class StreamSegment[T <: Data](dataType: HardType[T]) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(dataType())))
    val segmentFragNum = in(UInt(PMTU_FRAG_NUM_WIDTH bits))
    val outputStream = master(Stream(Fragment(dataType())))
  }

  val inputValid = io.inputStream.valid
  val inputData = io.inputStream.fragment
  val isFirstFrag = io.inputStream.isFirst
  val isLastFrag = io.inputStream.isLast

  when(inputValid) {
    assert(
      assertion = CountOne(io.segmentFragNum) > 0,
      message = L"segmentFragNum=${io.segmentFragNum} should be larger than 0",
      severity = FAILURE
    )
  }

  val inputFragCnt =
    Counter(
      bitCount = widthOf(io.segmentFragNum) bits,
      inc = io.inputStream.fire
    )
  val isCntLastFrag = inputFragCnt.value === (io.segmentFragNum - 1)
  val setLast = isLastFrag || isCntLastFrag
  when(io.inputStream.fire && setLast) {
    inputFragCnt.clear()
  }

  io.outputStream << io.inputStream.translateWith {
    val rslt = cloneOf(io.inputStream.payloadType) // Fragment(dataType())
    rslt.fragment := inputData
    rslt.last := setLast
    rslt
  }
}

object StreamSegment {
  def apply[T <: Data](
      inputStream: Stream[Fragment[T]],
      segmentFragNum: UInt
  ): Stream[Fragment[T]] = {
    val rslt = new StreamSegment(cloneOf(inputStream.fragmentType))
    rslt.io.inputStream << inputStream
    rslt.io.segmentFragNum := segmentFragNum
    rslt.io.outputStream
  }
}

/** When adding header, the headerMty can only have consecutive zero bits from the left-most side.
  * Assume the header width is 4, the valid header Mty can only be 4'b0000, 4'b0001, 4'b0011,
  * 4'b0111, 4'b1111.
  * The output stream does not make any change to the header.
  *
  * Each header is for a packet, from first fragment to last fragment.
  */
class StreamAddHeader[T <: Data](headerType: HardType[T], busWidth: BusWidth)
    extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(busWidth))))
    val inputHeader = slave(Stream(HeaderDataAndMty(headerType, busWidth)))
    val outputStream = master(
      Stream(Fragment(HeaderDataAndMty(headerType, busWidth)))
    )
  }

  val joinStream = FragmentStreamJoinStream(io.inputStream, io.inputHeader)

  val inputValid = joinStream.valid
  val inputData = joinStream._1.data
  val inputMty = joinStream._1.mty
  val inputHeader = joinStream._2.header
  val inputHeaderData = joinStream._2.data
  val inputHeaderMty = joinStream._2.mty
  val isFirstFrag = joinStream.isFirst
  val isLastFrag = joinStream.isLast

  val inputWidth = widthOf(inputData)
  val inputMtyWidth = widthOf(inputMty)
  val inputWidthBytes = inputWidth / BYTE_WIDTH
  require(
    inputWidthBytes == inputMtyWidth,
    s"inputWidthBytes=${inputWidthBytes} should == inputMtyWidth=${inputMtyWidth}"
  )
  require(
    busWidth.id % BYTE_WIDTH == 0,
    s"bus width=${busWidth.id} must be multiple of byte length=${BYTE_WIDTH}"
  )

  val inputHeaderMtyWidth = CountOne(inputHeaderMty)
  val headerMtyComp = setAllBits(inputHeaderMtyWidth)
  when(inputValid) {
    assert(
      assertion = inputHeaderMty === headerMtyComp.resize(inputMtyWidth),
      message =
        L"invalid inputHeaderMty=${inputHeaderMty} with headerMtyWidth=${inputHeaderMtyWidth}, should be ${headerMtyComp}",
      severity = FAILURE
    )
    when(inputHeaderMty.orR) {
      assert(
        // OHMasking.first() return an OH indicate the right most 1 bit.
        assertion = OHMasking.first(inputHeaderMty) === 1,
        message =
          L"the inputHeaderMty=${inputHeaderMty} should have consecutive valid bits from LSB side",
        severity = FAILURE
      )
    }

    when(!isLastFrag) {
      assert(
        assertion = inputMty.andR,
        message =
          L"the first or middle fragment should have MTY all set, but MTY=${inputMty}",
        severity = FAILURE
      )
    } otherwise { // Last fragment
      // For the case of only one fragment, MTY can be zero if the request is of zero DMA length
      when(!isFirstFrag) { // Check MTY of the last fragment, when there are more than one fragment
        assert(
          assertion = inputMty.orR,
          message =
            L"the last fragment should have MTY=/=0, but MTY=${inputMty}",
          severity = FAILURE
        )
      }
    }
  }

  val cachedJoinStream = RegNextWhen(joinStream.payload, cond = joinStream.fire)
  val cachedData = cachedJoinStream._1.data
  val cachedMty = cachedJoinStream._1.mty
  val cachedHeader = cachedJoinStream._2.header

  // If inputWidth is 32 bits, headerMty is 4'b0011,
  // If the inputMty of the last fragment is 4'b1100, then after the last input fragment,
  // There is no bytes remaining to output.
  // If the inputMty of the last fragment is 4'b1110, then after the last input fragment,
  // THere is one extra byte remaining to output.
  val lastFragHasResidue = isLastFrag && (inputMty & inputHeaderMty).orR

  val outputData = Bits(inputWidth bits)
  val outputMty = Bits(inputWidthBytes bits)
  val extraLastFragData = Bits(inputWidth bits)
  val extraLastFragMty = Bits(inputWidthBytes bits)

  val minHeaderWidthBytes = (widthOf(BTH()) / BYTE_WIDTH)
  val maxHeaderWidthBytes = (widthOf(BTH()) + widthOf(AtomicEth())) / BYTE_WIDTH
  switch(inputHeaderMtyWidth) {
    // Header length in bytes:
    // BTH 12, RETH 16, AtomicETH 28, AETH 4, AtomicAckETH 8, ImmDt 4, IETH 4.
    //
    // Send request headers: BTH, BTH + ImmDt, BTH + IETH, 12 or 16 bytes
    // Write request headers: BTH + RETH, BTH, BTH + ImmDt, BTH + RETH + ImmDt, 28 or 12 or 16 or 32 bytes
    // Send/write Ack: BTH + AETH, 16 bytes
    //
    // Read request header: BTH + RETH, 28 bytes
    // Read response header: BTH + AETH, BTH, 16 or 12 bytes
    //
    // Atomic request header: BTH + AtomicETH, 40 bytes
    // Atomic response header: BTH + AETH + AtomicAckETH, 24 bytes
    //
    // Headers have length of multiple of 4 bytes
    for (headerMtyWidth <- minHeaderWidthBytes to maxHeaderWidthBytes by 4) {
      // for (headerMtyWidth <- 1 until inputWidthBytes) {
      is(headerMtyWidth) {
        val headerWidth = headerMtyWidth * BYTE_WIDTH

        // TODO: check header valid bits are right-hand sided
        when(isFirstFrag) { // isFirstFrag is true, concatenate header and inputData
          outputData := (inputHeaderData(0, headerWidth bits) ## inputData)
            .resizeLeft(inputWidth)
          outputMty := (inputHeaderMty(0, headerMtyWidth bits) ## inputMty)
            .resizeLeft(inputMtyWidth)
        } otherwise {
          outputData := (cachedData(0, headerWidth bits) ## inputData)
            .resizeLeft(inputWidth)
          outputMty := (cachedMty(0, headerMtyWidth bits) ## inputMty)
            .resizeLeft(inputMtyWidth)
        }

        // The extra last fragment
        extraLastFragData := (cachedData(0, headerWidth bits) ##
          B(0, inputWidth bits)).resizeLeft(inputWidth)
        // When output stream has residue, the last beat is only from cachedDataReg, not from inputData
        extraLastFragMty := (cachedMty(0, headerMtyWidth bits) ##
          B(0, inputMtyWidth bits)).resizeLeft(inputMtyWidth)
      }
    }
    default {
      outputData := 0
      outputMty := 0
      extraLastFragData := 0
      extraLastFragMty := 0

      when(inputValid) {
        report(
          message =
            L"invalid inputHeaderMty=${inputHeaderMty} with MTY width=${inputHeaderMtyWidth} and inputHeaderData=${inputHeaderData}",
          severity = FAILURE
        )
      }
    }
  }

  val inputStreamTranslate = joinStream.translateWith {
    val rslt = cloneOf(io.outputStream.payloadType)
    rslt.header := inputHeader
    rslt.data := outputData
    rslt.mty := outputMty
    rslt.last := isLastFrag && !lastFragHasResidue
    rslt
  }

  // needHandleLastFragResidueReg is CSR.
  val needHandleLastFragResidueReg = RegInit(False)
  val extraLastFragStream = cloneOf(io.outputStream)
  extraLastFragStream.valid := needHandleLastFragResidueReg
  extraLastFragStream.header := cachedHeader
  extraLastFragStream.data := extraLastFragData
  extraLastFragStream.mty := extraLastFragMty
  extraLastFragStream.last := True

  // Output residue from the last fragment if any
  val outputStream = StreamMux(
    select = needHandleLastFragResidueReg.asUInt,
    Vec(inputStreamTranslate, extraLastFragStream)
  )
  when(extraLastFragStream.fire) {
    needHandleLastFragResidueReg := False
  }
  when(joinStream.lastFire) {
    needHandleLastFragResidueReg := lastFragHasResidue
  }

  io.outputStream << outputStream
}

object StreamAddHeader {
  def apply[T <: Data](
      inputStream: Stream[Fragment[DataAndMty]],
      inputHeader: Stream[HeaderDataAndMty[T]],
      busWidth: BusWidth
  ): Stream[Fragment[HeaderDataAndMty[T]]] = {
    require(
      widthOf(inputStream.data) == busWidth.id,
      s"widthOf(inputStream.data)=${widthOf(inputStream.data)} should == busWidth.id=${busWidth.id}"
    )
    val rslt =
      new StreamAddHeader(inputHeader.headerType, busWidth)
    rslt.io.inputStream << inputStream
    rslt.io.inputHeader << inputHeader
    rslt.io.outputStream
  }
}

/** When remove header, it removes the first several bytes data from the first fragment,
  * StreamRemoveHeader will shift the remaining bits,
  * The output stream have valid data start from MSB for all fragments.
  */
class StreamRemoveHeader(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(busWidth))))
    val headerLenBytes = in(UInt(log2Up(busWidth.id / BYTE_WIDTH) + 1 bits))
    val outputStream = master(Stream(Fragment(DataAndMty(busWidth))))
  }

  val inputValid = io.inputStream.valid
  val inputData = io.inputStream.data
  val inputMty = io.inputStream.mty
  val isFirstFrag = io.inputStream.isFirst
  val isLastFrag = io.inputStream.isLast

  val inputWidth = widthOf(inputData)
  val inputMtyWidth = widthOf(inputMty)
  val inputWidthBytes = inputWidth / BYTE_WIDTH
  require(
    inputWidthBytes == inputMtyWidth,
    s"inputWidthBytes=${inputWidthBytes} should == inputMtyWidth=${inputMtyWidth}"
  )
  // TODO: assert message cannot use non-Spinal-Data variable
  assert(
    assertion = 0 < io.headerLenBytes && io.headerLenBytes < inputWidthBytes,
    message =
      L"must have 0 < headerLenBytes=${io.headerLenBytes} < inputWidthBytes=${U(inputWidthBytes, widthOf(io.headerLenBytes) bits)}",
    severity = FAILURE
  )

  val delayedInputStream = io.inputStream.stage()
  val cachedData = delayedInputStream.data
  val cachedMty = delayedInputStream.mty
  val cachedIsLast = delayedInputStream.isLast

  val headerMty = setAllBits(io.headerLenBytes).resizeLeft(inputMtyWidth)
  // If inputWidth is 32 bits, headerLenBytes is 2,
  // If the inputMty of the last fragment is 4'b1100, then after the last input fragment,
  // There is no bytes remaining to output.
  // If the inputMty of the last fragment is 4'b1110, then after the last input fragment,
  // THere is one extra byte remaining to output.
  val inputFragHasResidue = CountOne(inputMty) > io.headerLenBytes
  val cachedFragHasResidue = CountOne(cachedMty) > io.headerLenBytes
  val needHandleLastFragResidue = cachedIsLast && cachedFragHasResidue
  val throwDelayedLastFrag = cachedIsLast && !cachedFragHasResidue

  val rightShiftBitAmt = UInt(log2Up(busWidth.id) + 1 bits)
  rightShiftBitAmt := inputWidth - (io.headerLenBytes << log2Up(
    BYTE_WIDTH
  )) // left shift 3 bits mean multiply by 8
  val rightShiftByteAmt = UInt(widthOf(io.headerLenBytes) bits)
  rightShiftByteAmt := inputMtyWidth - io.headerLenBytes

  val outputData = Bits(inputWidth bits)
  val outputMty = Bits(inputWidthBytes bits)
  outputData := ((cachedData ## inputData) >> rightShiftBitAmt)
    .resize(inputWidth)
  outputMty := ((cachedMty ## inputMty) >> rightShiftByteAmt)
    .resize(inputMtyWidth)

  // The extra last fragment
  val extraLastFragData =
    ((cachedData ## B(0, inputWidth bits)) >> rightShiftBitAmt)
      .resize(inputWidth)
  // When output stream has residue, the last beat is only from cachedDataReg, not from inputData
  val extraLastFragMty =
    ((cachedMty ## B(0, inputMtyWidth bits)) >> rightShiftByteAmt)
      .resize(inputMtyWidth)

  val inputStreamTranslate = delayedInputStream
    .throwWhen(throwDelayedLastFrag)
    .continueWhen(io.inputStream.valid || needHandleLastFragResidue)
    .translateWith {
      val rslt = cloneOf(io.inputStream.payloadType)
      when(needHandleLastFragResidue) {
        rslt.data := extraLastFragData
        rslt.mty := extraLastFragMty
        rslt.last := True
      } otherwise {
        rslt.data := outputData
        rslt.mty := outputMty
        rslt.last := isLastFrag && !inputFragHasResidue
      }
      rslt
    }

  io.outputStream << inputStreamTranslate
}

object StreamRemoveHeader {
  def apply(
      inputStream: Stream[Fragment[DataAndMty]],
      headerLenBytes: UInt,
      busWidth: BusWidth
  ): Stream[Fragment[DataAndMty]] = {
    val rslt = new StreamRemoveHeader(busWidth)
    rslt.io.inputStream << inputStream
    rslt.io.headerLenBytes := headerLenBytes
    rslt.io.outputStream
  }
}

/** Join a stream A of fragments with a stream B,
  * that B will fire only when the last fragment of A fires.
  */
class FragmentStreamJoinStream[T1 <: Data, T2 <: Data](
    dataType1: HardType[T1],
    dataType2: HardType[T2]
) extends Component {
  val io = new Bundle {
    val inputFragmentStream = slave(Stream(Fragment(dataType1)))
    val inputStream = slave(Stream(dataType2))
    val outputJoinStream = master(
      Stream(Fragment(TupleBundle2(dataType1, dataType2)))
    )
  }

  io.inputStream.ready := io.inputFragmentStream.lastFire
  when(io.inputStream.valid) {
    assert(
      assertion = io.inputFragmentStream.lastFire === io.inputStream.fire,
      message =
        L"during each first beat, inputFragmentStream and inputStream should fire together",
      severity = FAILURE
    )
  }
//  when(io.inputFragmentStream.valid) {
//    assert(
//      assertion = io.inputFragmentStream.valid === io.inputStream.valid,
//      message =
//        L"io.inputFragmentStream is waiting for io.inputStream, that io.inputFragmentStream.valid=${io.inputFragmentStream.valid} but io.inputStream.valid=${io.inputStream.valid}",
//      severity = FAILURE
//    )
//  }

  io.outputJoinStream << io.inputFragmentStream
    .continueWhen(io.inputStream.valid)
    .translateWith {
      val rslt = cloneOf(io.outputJoinStream.payloadType)
      rslt._1 := io.inputFragmentStream.fragment
      rslt._2 := io.inputStream.payload
      rslt.last := io.inputFragmentStream.isLast
      rslt
    }
}

object FragmentStreamJoinStream {
  def apply[T1 <: Data, T2 <: Data](
      inputFragmentStream: Stream[Fragment[T1]],
      inputStream: Stream[T2]
  ): Stream[Fragment[TupleBundle2[T1, T2]]] = {
    val join = new FragmentStreamJoinStream(
      inputFragmentStream.fragmentType,
      inputStream.payloadType
    )
    join.io.inputFragmentStream << inputFragmentStream
    join.io.inputStream << inputStream
    join.io.outputJoinStream
  }
}

/** Send a payload at each signal rise edge,
  * clear the payload after fire.
  * No more than one signal rise before fire.
  */
object SignalEdgeDrivenStream {
  def apply(signal: Bool): Stream[NoData] =
    new Composite(signal) {
      val rslt = Stream(NoData)
      val signalRiseEdge = signal.rise(initAt = False)
      val validReg = Reg(Bool())
        .setWhen(signalRiseEdge)
        .clearWhen(rslt.fire)
      when(validReg) {
        assert(
          assertion = !signalRiseEdge,
          message =
            L"previous signal edge triggered stream data hasn't fired yet, validReg=${validReg}, signalRiseEdge=${signalRiseEdge}",
          severity = FAILURE
        )
      }
      rslt.valid := signal.rise(initAt = False) || validReg
    }.rslt
}

/** Build an arbiter tree to arbitrate on many inputs.
  */
object StreamArbiterTree {
  def apply[T <: Data](
      inputVec: Vec[Stream[T]],
      arbiterPortCount: Int,
      fragmentLockOn: Boolean,
      streamPipelined: Boolean
  ): Stream[T] = {
    require(
      !inputVec.isEmpty,
      s"inputVec cannot be empty but size=${inputVec.size}"
    )
    val arbiterTree = new StreamArbiterTree(
      inputVec(0).payloadType,
      inputVec.size,
      arbiterPortCount,
      fragmentLockOn,
      streamPipelined
    )
    for (
      (arbiterTreeInput, originalInput) <- arbiterTree.io.inputs.zip(
        inputVec
      )
    ) {
      arbiterTreeInput << originalInput
    }
    arbiterTree.io.output
  }
}

class StreamArbiterTree[T <: Data](
    dataType: HardType[T],
    inputPortCount: Int,
    arbiterPortCount: Int,
    fragmentLockOn: Boolean,
    streamPipelined: Boolean
) extends Component {
  require(isPow2(inputPortCount))
  val io = new Bundle {
    val inputs = Vec(slave(Stream(dataType)), inputPortCount)
    val output = master(Stream(dataType))
  }

  @scala.annotation.tailrec
  private def buildArbiterTree(inputVec: Seq[Stream[T]]): Stream[T] = {
    if (inputVec.size <= arbiterPortCount) {
      if (fragmentLockOn) {
        StreamArbiterFactory.roundRobin.fragmentLock
          .on(inputVec)
          .pipelined(m2s = streamPipelined, s2m = streamPipelined)
      } else {
        StreamArbiterFactory.roundRobin.transactionLock
          .on(inputVec)
          .pipelined(m2s = streamPipelined, s2m = streamPipelined)
      }
    } else {
      val segmentList = inputVec.grouped(arbiterPortCount).toList
      val nextTreeLayer = for (segment <- segmentList) yield {
        if (fragmentLockOn) {
          StreamArbiterFactory.roundRobin.fragmentLock
            .on(segment)
            .pipelined(m2s = streamPipelined, s2m = streamPipelined)
        } else {
          StreamArbiterFactory.roundRobin.transactionLock
            .on(segment)
            .pipelined(m2s = streamPipelined, s2m = streamPipelined)
        }
      }
      buildArbiterTree(nextTreeLayer)
    }
  }

  if (streamPipelined) {
    io.output <-/< buildArbiterTree(io.inputs.toSeq)
  } else {
    io.output << buildArbiterTree(io.inputs.toSeq)
  }
}

object StreamOneHotDeMux {
  def apply[T <: Data](input: Stream[T], select: Bits): Vec[Stream[T]] = {
    val portCount = widthOf(select)
    when(input.valid) {
      assert(
        assertion = CountOne(select) <= 1,
        message = L"select=${select} should be one hot",
        severity = FAILURE
      )
    }

    val streamOneHotDeMux = new StreamOneHotDeMux(input.payload, portCount)
    streamOneHotDeMux.io.input << input
    streamOneHotDeMux.io.select := select
    streamOneHotDeMux.io.outputs
  }
}

class StreamOneHotDeMux[T <: Data](dataType: HardType[T], portCount: Int)
    extends Component {
  val io = new Bundle {
    val select = in(Bits(portCount bits))
    val input = slave(Stream(dataType()))
    val outputs = Vec(master(Stream(dataType())), portCount)
  }

  when(io.input.valid) {
    assert(
      assertion = CountOne(io.select) <= 1,
      message = L"io.select=${io.select} is not one hot",
      severity = FAILURE
    )
  }

  io.input.ready := False
  for (idx <- 0 until portCount) {
    io.outputs(idx).payload := io.input.payload
    io.outputs(idx).valid := io.select(idx) && io.input.valid
    when(io.select(idx)) {
      io.input.ready := io.outputs(idx).ready
    }
  }
}

object StreamOneHotMux {
  def apply[T <: Data](select: Bits, inputs: Seq[Stream[T]]): Stream[T] = {
    val vec = Vec(inputs)
    StreamOneHotMux(select, vec)
  }

  def apply[T <: Data](select: Bits, inputs: Vec[Stream[T]]): Stream[T] = {
    require(
      widthOf(select) == inputs.size,
      s"widthOf(select)=${widthOf(select)} should == inputs.size=${inputs.size}"
    )

    val streamOneHotMux =
      new StreamOneHotMux(inputs(0).payloadType, inputs.length)
    streamOneHotMux.io.select := select
    for (idx <- 0 until widthOf(select)) {
      streamOneHotMux.io.inputs(idx) << inputs(idx)
    }
    streamOneHotMux.io.output
  }
}

class StreamOneHotMux[T <: Data](dataType: HardType[T], portCount: Int)
    extends Component {
  val io = new Bundle {
    val select = in(Bits(portCount bit))
    val inputs = Vec(slave(Stream(dataType())), portCount)
    val output = master(Stream(dataType()))
  }
  require(portCount > 0, s"portCount=${portCount} must > 0")

  io.output.valid := False
  io.output.payload := io.inputs(0).payload
  for (idx <- 0 until portCount) {
    io.inputs(idx).ready := io.select(idx)
    when(io.select(idx)) {
      io.output.payload := io.inputs(idx).payload
    }
  }
}

/** Tree-structure StreamFork
  */
object StreamForkTree {
  def apply[T <: Data](
      input: Stream[T],
      outputPortCount: Int,
      forkPortCount: Int,
      streamPipelined: Boolean
  ): Vec[Stream[T]] = {
    val streamForkTree = new StreamForkTree(
      input.payloadType,
      outputPortCount,
      forkPortCount,
      streamPipelined
    )
    streamForkTree.io.input << input
    streamForkTree.io.outputs
  }
}

class StreamForkTree[T <: Data](
    dataType: HardType[T],
    outputPortCount: Int,
    forkPortCount: Int,
    streamPipelined: Boolean
) extends Component {
  val io = new Bundle {
    val input = slave(Stream(dataType()))
    val outputs = Vec(master(Stream(dataType())), outputPortCount)
  }

  require(
    isPow2(outputPortCount),
    s"outputPortCount=${outputPortCount} must be power of 2"
  )
  require(
    isPow2(forkPortCount),
    s"forkPortCount=${forkPortCount} must be power of 2"
  )

  @scala.annotation.tailrec
  private def buildForkTree(input: Vec[Stream[T]]): Vec[Stream[T]] = {
    if (input.size >= outputPortCount) input
    else {
      val inputFork = input.flatMap(StreamFork(_, portCount = forkPortCount))
      if (streamPipelined) {
        val inputForkPipelined =
          inputFork.map(
            _.pipelined(m2s = streamPipelined, s2m = streamPipelined)
          )
        buildForkTree(Vec(inputForkPipelined))
      } else {
        buildForkTree(Vec(inputFork))
      }
    }
  }

  import StreamVec._
  if (streamPipelined) {
    io.outputs <-/< buildForkTree(Vec(io.input))
  } else {
    io.outputs << buildForkTree(Vec(io.input))
  }
}

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

//========== Misc utilities ==========

object setAllBits {
  def apply(width: Int): BigInt = {
    (BigInt(1) << width) - 1
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

// TODO: remove this once Spinal HDL upgraded to 1.6.2
object ComponentEnrichment {
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

//========== Packet validation related utilities ==========

object PsnUtil {
  def cmp(
      psnA: UInt,
      psnB: UInt,
      curPsn: UInt
  ): SpinalEnumCraft[PsnCompResult.type] =
    new Composite(curPsn) {
      val rslt = PsnCompResult()
      val oldestPSN = (curPsn - HALF_MAX_PSN) & PSN_MASK

      when(psnA === psnB) {
        rslt := PsnCompResult.EQUAL
      } elsewhen (psnA < psnB) {
        when(oldestPSN <= psnA) {
          rslt := PsnCompResult.LESSER
        } elsewhen (psnB <= oldestPSN) {
          rslt := PsnCompResult.LESSER
        } otherwise {
          rslt := PsnCompResult.GREATER
        }
      } otherwise { // psnA > psnB
        when(psnA <= oldestPSN) {
          rslt := PsnCompResult.GREATER
        } elsewhen (oldestPSN <= psnB) {
          rslt := PsnCompResult.GREATER
        } otherwise {
          rslt := PsnCompResult.LESSER
        }
      }
    }.rslt

  /** psnA - psnB, always <= HALF_MAX_PSN
    */
  def diff(psnA: UInt, psnB: UInt): UInt =
    new Composite(psnA) {
      val min = UInt(PSN_WIDTH bits)
      val max = UInt(PSN_WIDTH bits)
      when(psnA > psnB) {
        min := psnB
        max := psnA
      } otherwise {
        min := psnA
        max := psnB
      }
      val diff = max - min
      val rslt = UInt(PSN_WIDTH bits)
      when(diff > HALF_MAX_PSN) {
        rslt := (TOTAL_PSN - diff).resize(PSN_WIDTH)
      } otherwise {
        rslt := diff
      }
    }.rslt

  def lt(psnA: UInt, psnB: UInt, curPsn: UInt): Bool =
    cmp(psnA, psnB, curPsn) === PsnCompResult.LESSER

  def gt(psnA: UInt, psnB: UInt, curPsn: UInt): Bool =
    cmp(psnA, psnB, curPsn) === PsnCompResult.GREATER

  def lte(psnA: UInt, psnB: UInt, curPsn: UInt): Bool =
    cmp(psnA, psnB, curPsn) === PsnCompResult.LESSER ||
      psnA === psnB

  def gte(psnA: UInt, psnB: UInt, curPsn: UInt): Bool =
    cmp(psnA, psnB, curPsn) === PsnCompResult.GREATER ||
      psnA === psnB
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
          OpCode.isNonReadAtomicRespPkt(opcode) ||
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
        } elsewhen (OpCode.isNonReadAtomicRespPkt(opcode)) {
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

object ePsnIncrease {
  def apply(pktFrag: RdmaDataPkt, pmtu: Bits, busWidth: BusWidth): UInt =
    new Composite(pktFrag) {
      val rslt = UInt(PSN_WIDTH bits)
      val isReadReq = OpCode.isReadReqPkt(pktFrag.bth.opcode)
      when(isReadReq) {
        // BTH is included in inputPktFrag.data
        // TODO: verify inputPktFrag.data is big endian
        val rethBits = pktFrag.data(
          (busWidth.id - widthOf(BTH()) - widthOf(RETH())) until
            (busWidth.id - widthOf(BTH()))
        )
        val reth = RETH()
        reth.assignFromBits(rethBits)

        rslt := computePktNum(reth.dlen, pmtu)
      } otherwise {
        rslt := 1

        assert(
          assertion = OpCode.isReqPkt(pktFrag.bth.opcode),
          message =
            L"ePsnIncrease() expects requests, but opcode=${pktFrag.bth.opcode} is not of request",
          severity = FAILURE
        )
      }
    }.rslt
}

//========== Packet related utilities ==========

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

          report(
            message = L"computePktNum encounters invalid PMTU=${pmtu}",
            severity = FAILURE
          )
        }
      }

      val pktNum = (len >> shiftAmt) + (residue.orR).asUInt
      // This downsize is safe, since max length is 2^32 bytes,
      // And the min packet size is 256=2^8 bytes,
      // So the max packet number is 2^PSN_WIDTH=2^24
      rslt := pktNum.resize(PSN_WIDTH)
    }.rslt
}

object pmtuPktLenBytes {
  def apply(pmtu: Bits): UInt = {
    val rslt = UInt(PMTU_FRAG_NUM_WIDTH bits)
    switch(pmtu) {
      // The PMTU enum value itself means right shift amount of bits
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
        report(
          message = L"pmtuPktLenBytes encounters invalid PMTU=${pmtu}",
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
        report(
          message = L"pmtuLenMask encounters invalid PMTU=${pmtu}",
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
    val remainder = moduloByPmtu(dividend, pmtu)
    dividend >> shiftAmt + remainder.orR.asUInt.resize(widthOf(dividend))
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

object checkWorkReqOpCodeMatch {
  def apply(workReqOpCode: Bits, reqOpCode: Bits): Bool =
    new Composite(reqOpCode) {
      val rslt = (WorkReqOpCode.isSendReq(workReqOpCode) &&
        OpCode.isSendReqPkt(reqOpCode)) ||
        (WorkReqOpCode.isWriteReq(workReqOpCode) &&
          OpCode.isWriteReqPkt(reqOpCode)) ||
        (WorkReqOpCode.isReadReq(workReqOpCode) &&
          OpCode.isReadReqPkt(reqOpCode)) ||
        (WorkReqOpCode.isAtomicReq(workReqOpCode) &&
          OpCode.isAtomicReqPkt(reqOpCode))
    }.rslt
}

object checkRespOpCodeMatch {
  def apply(reqOpCode: Bits, respOpCode: Bits): Bool =
    new Composite(reqOpCode) {
      val rslt = Bool()
      when(OpCode.isSendReqPkt(reqOpCode) || OpCode.isWriteReqPkt(reqOpCode)) {
        rslt := OpCode.isNonReadAtomicRespPkt(respOpCode)
      } elsewhen (OpCode.isReadReqPkt(reqOpCode)) {
        rslt := OpCode.isReadRespPkt(respOpCode) || OpCode
          .isNonReadAtomicRespPkt(respOpCode)
      } elsewhen (OpCode.isAtomicReqPkt(reqOpCode)) {
        rslt := OpCode.isAtomicRespPkt(respOpCode) || OpCode
          .isNonReadAtomicRespPkt(respOpCode)
      } otherwise {
        report(
          message =
            L"reqOpCode=${reqOpCode} should be request opcode, respOpCode=${respOpCode}",
          severity = FAILURE
        )
        rslt := False
      }
    }.rslt
}

//========== TupleBundle related utilities ==========

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
