package rdma

import spinal.core._
import spinal.lib._
import ConstantSettings._
import RdmaConstants._

import scala.language.postfixOps

object timeNumToCycleNum {
  def apply(timeNumber: TimeNumber): BigInt = {
    (timeNumber * ClockDomain.current.frequency.getValue).toBigInt
  }
}

object rnrTimeOutOptionToTimeNum {
  val maxRnrTimeOutOption = 1 << RNR_TIMEOUT_WIDTH

  def apply(rnrTimeOutOption: Int): TimeNumber = {
    require(
      0 <= rnrTimeOutOption && rnrTimeOutOption < maxRnrTimeOutOption,
      s"illegal rnrTimeOutOption=${rnrTimeOutOption}, should be 0 <= rnrTimeOutOption < ${maxRnrTimeOutOption}"
    )

    RNR_TIMEOUT(rnrTimeOutOption)
  }
}

// Response timeout settings:
//  0 - infinite
//  1 - 8.192 usec (0.000008 sec)
//  2 - 16.384 usec (0.000016 sec)
//  3 - 32.768 usec (0.000032 sec)
//  4 - 65.536 usec (0.000065 sec)
//  5 - 131.072 usec (0.000131 sec)
//  6 - 262.144 usec (0.000262 sec)
//  7 - 524.288 usec (0.000524 sec)
//  8 - 1048.576 usec (0.00104 sec)
//  9 - 2097.152 usec (0.00209 sec)
//  10 - 4194.304 usec (0.00419 sec)
//  11 - 8388.608 usec (0.00838 sec)
//  12 - 16777.22 usec (0.01677 sec)
//  13 - 33554.43 usec (0.0335 sec)
//  14 - 67108.86 usec (0.0671 sec)
//  15 - 134217.7 usec (0.134 sec)
//  16 - 268435.5 usec (0.268 sec)
//  17 - 536870.9 usec (0.536 sec)
//  18 - 1073742 usec (1.07 sec)
//  19 - 2147484 usec (2.14 sec)
//  20 - 4294967 usec (4.29 sec)
//  21 - 8589935 usec (8.58 sec)
//  22 - 17179869 usec (17.1 sec)
//  23 - 34359738 usec (34.3 sec)
//  24 - 68719477 usec (68.7 sec)
//  25 - 137000000 usec (137 sec)
//  26 - 275000000 usec (275 sec)
//  27 - 550000000 usec (550 sec)
//  28 - 1100000000 usec (1100 sec)
//  29 - 2200000000 usec (2200 sec)
//  30 - 4400000000 usec (4400 sec)
//  31 - 8800000000 usec (8800 sec)
object respTimeOutOptionToTimeNum {
  val maxTimeOutOption = 1 << RESP_TIMEOUT_WIDTH

  def apply(timeOutOption: Int): TimeNumber = {
    require(
      0 <= timeOutOption && timeOutOption < maxTimeOutOption,
      s"illegal timeOutOption=${timeOutOption}, should be 0 <= timeOutOption < ${maxTimeOutOption}"
    )

    timeOutOption match {
      case INFINITE_RESP_TIMEOUT => INFINITE_RESP_TIMEOUT ns
      case _ => BigDecimal(BigInt(8192) << (timeOutOption - 1)) ns
    }
  }
}

// DMA related utilities

object DmaReadRespHandler {
  def apply[T <: Data](
      inputReq: Stream[T],
      dmaReadResp: DmaReadRespBus,
      flush: Bool,
      busWidth: BusWidth.Value,
      reqQueueLen: Int,
      isReqZeroDmaLen: T => Bool
  ): Stream[Fragment[ReqAndDmaReadResp[T]]] = {
    val result =
      new DmaReadRespHandler(
        inputReq.payloadType,
        busWidth,
        reqQueueLen,
        isReqZeroDmaLen
      )
    result.io.flush := flush
    result.io.inputReq << inputReq
    result.io.dmaReadResp << dmaReadResp
    result.io.reqAndDmaReadResp
  }
}

class DmaReadRespHandler[T <: Data](
    reqType: HardType[T],
    busWidth: BusWidth.Value,
    reqQueueLen: Int,
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
  /*
  val inputReqQueue =
    StreamFifoLowLatency(io.inputReq.payloadType(), reqQueueLen)
  inputReqQueue.io.push << io.inputReq.throwWhen(io.flush)
  inputReqQueue.io.flush := io.flush
  assert(
    assertion = inputReqQueue.io.push.ready,
    message =
      L"${REPORT_TIME} time: inputReqQueue is full, inputReqQueue.io.push.ready=${inputReqQueue.io.push.ready}, inputReqQueue.io.occupancy=${inputReqQueue.io.occupancy}, which is not allowed in DmaReadRespHandler".toSeq,
    severity = FAILURE
  )
   */
  val inputReqQueuePop = FixedLenQueue(
    io.inputReq.payloadType(),
    depth = reqQueueLen,
    push = io.inputReq.throwWhen(io.flush),
    flush = io.flush,
    queueName = "inputReqQueue"
  )
  val isEmptyReadReq = isReqZeroDmaLen(inputReqQueuePop.payload)

//  val txSel = UInt(1 bits)
  val (nonEmptyReadIdx, emptyReadIdx) = (0, 1)
//  when(isEmptyReadReq) {
//    txSel := emptyReadIdx
//  } otherwise {
//    txSel := nonEmptyReadIdx
//  }
  val twoStreams = StreamDemux(
    select = isEmptyReadReq.asUInt,
    input = inputReqQueuePop.throwWhen(io.flush),
    portCount = 2
  )

  // Join inputReq with DmaReadResp
  val joinStream = FragmentStreamJoinStream(
    io.dmaReadResp.resp.throwWhen(io.flush),
    twoStreams(nonEmptyReadIdx)
  )

  val outputStream = StreamMux(
    select = isEmptyReadReq.asUInt,
    inputs = Vec(
      joinStream.map { payloadData =>
        val result =
          cloneOf(io.reqAndDmaReadResp.payloadType)
        result.dmaReadResp := payloadData._1
        result.req := payloadData._2
        result.last := payloadData.last
        result
      },
      twoStreams(emptyReadIdx).map { payloadData =>
        val result =
          cloneOf(io.reqAndDmaReadResp.payloadType)
        result.dmaReadResp
          .setDefaultVal() // TODO: dmaReadResp.lenBytes set to zero explicitly
        result.req := payloadData
        result.last := True
        result
      }
    )
  )
  io.reqAndDmaReadResp << outputStream.throwWhen(io.flush)

  when(isEmptyReadReq) {
    assert(
      assertion = !joinStream.valid,
      message =
        L"${REPORT_TIME} time: when request has zero DMA length, it should not handle DMA read response, that joinStream.valid should be false, but joinStream.valid=${joinStream.valid}".toSeq,
      severity = FAILURE
    )
  } otherwise {
    assert(
      assertion = !twoStreams(emptyReadIdx).valid,
      message =
        L"${REPORT_TIME} time: when request has non-zero DMA length, twoStreams(emptyReadIdx).valid should be false, but twoStreams(emptyReadIdx).valid=${twoStreams(emptyReadIdx).valid}".toSeq,
      severity = FAILURE
    )
  }
}

object DmaReadRespSegment {
  def apply[T <: Data](
      input: Stream[Fragment[T]],
      flush: Bool,
      pmtu: UInt,
      busWidth: BusWidth.Value,
      isReqZeroDmaLen: T => Bool
  ): Stream[Fragment[T]] = {
    val result =
      new DmaReadRespSegment(input.fragmentType, busWidth, isReqZeroDmaLen)
    result.io.flush := flush
    result.io.pmtu := pmtu
    result.io.reqAndDmaReadRespIn << input
    result.io.reqAndDmaReadRespOut
  }
}

// Handle zero DMA length request/response too
class DmaReadRespSegment[T <: Data](
    fragType: HardType[T],
    busWidth: BusWidth.Value,
    isReqZeroDmaLen: T => Bool
) extends Component {
  val io = new Bundle {
    val flush = in(Bool())
    val pmtu = in(UInt(PMTU_WIDTH bits))
    val reqAndDmaReadRespIn = slave(Stream(Fragment(fragType)))
    val reqAndDmaReadRespOut = master(Stream(Fragment(fragType)))
  }

  val pmtuMaxFragNum = pmtuPktLenBytes(io.pmtu) >>
    log2Up(busWidth.id / BYTE_WIDTH)
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
  io.reqAndDmaReadRespOut << output.throwWhen(io.flush)
}

object CombineHeaderAndDmaResponse {
  def apply[T <: Data](
      reqAndDmaReadRespSegment: Stream[Fragment[ReqAndDmaReadResp[T]]],
      qpAttr: QpAttrData,
      flush: Bool,
      busWidth: BusWidth.Value,
//      pktNumFunc: (T, QpAttrData) => UInt,
      headerGenFunc: (
          T,
          DmaReadResp,
          UInt,
          QpAttrData
      ) => CombineHeaderAndDmaRespInternalRst
  ): RdmaDataBus = {
    val result = new CombineHeaderAndDmaResponse(
      reqAndDmaReadRespSegment.reqType,
      busWidth,
//      pktNumFunc,
      headerGenFunc
    )
    result.io.qpAttr := qpAttr
    result.io.flush := flush
    result.io.reqAndDmaReadRespSegment << reqAndDmaReadRespSegment
    result.io.tx
  }
}

class CombineHeaderAndDmaResponse[T <: Data](
    reqType: HardType[T],
    busWidth: BusWidth.Value,
//    pktNumFunc: (T, QpAttrData) => UInt,
    headerGenFunc: (
        T,
        DmaReadResp,
        UInt,
        QpAttrData
    ) => CombineHeaderAndDmaRespInternalRst
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
      val result = HeaderDataAndMty(BTH(), busWidth)
      result.header := bth
      result.data := headerBits
      result.mty := headerMtyBits
      result
    }
  val addHeaderStream = StreamAddHeader(
    input
      .throwWhen(io.flush)
      .map { payloadData =>
        val result = Fragment(DataAndMty(busWidth))
        result.data := payloadData.dmaReadResp.data
        result.mty := payloadData.dmaReadResp.mty
        result.last := payloadData.last
        result
      },
    headerStream,
    busWidth
  )
  io.tx.pktFrag << addHeaderStream.map { payloadData =>
    val result = Fragment(RdmaDataPkt(busWidth))
    result.bth := payloadData.header
    result.data := payloadData.data
    result.mty := payloadData.mty
    result.last := payloadData.last
    result
  }
}

// Stream related utilities

object StreamSource {
  def apply(): Event = {
    val result = Event
    result.valid := True
    result.combStage()
  }
}

object StreamSink {
  def apply[T <: Data](payloadType: HardType[T]): Stream[T] = {
    // No need to add combStage() here, since StreamSink has no consumer
    Stream(payloadType).freeRun()
  }
}

// Zip two streams and fire by conditions.
// If bothFireCond satisfied, fire both streams together,
// Otherwise if either fire condition satisfied, fire them separately.
// bothFireCond, leftFireCond, rightFireCond are mutually exclusive.
object StreamZipByCondition {
  def apply[T1 <: Data, T2 <: Data](
      leftInputStream: Stream[T1],
      rightInputStream: Stream[T2],
      leftFireCond: Bool,
      rightFireCond: Bool,
      bothFireCond: Bool
  ): Stream[TupleBundle4[Bool, T1, Bool, T2]] = {
    //  ): (Stream[T1], Stream[T2]) = {
    val streamZipByCondition = new StreamZipByCondition(
      leftInputStream.payloadType,
      rightInputStream.payloadType
    )

    streamZipByCondition.io.leftInputStream << leftInputStream
    streamZipByCondition.io.rightInputStream << rightInputStream
    streamZipByCondition.io.leftFireCond := leftFireCond
    streamZipByCondition.io.rightFireCond := rightFireCond
    streamZipByCondition.io.bothFireCond := bothFireCond
    streamZipByCondition.io.zipOutputStream.combStage()
  }
}

class StreamZipByCondition[T1 <: Data, T2 <: Data](
    leftPayloadType: HardType[T1],
    rightPayloadType: HardType[T2]
) extends Component {
  val io = new Bundle {
    val leftInputStream = slave(Stream(leftPayloadType()))
    val rightInputStream = slave(Stream(rightPayloadType()))
    val leftFireCond = in(Bool())
    val rightFireCond = in(Bool())
    val bothFireCond = in(Bool())
    val zipOutputStream = master(
      Stream(TupleBundle(Bool(), leftPayloadType(), Bool(), rightPayloadType()))
    )
  }

  when(io.leftFireCond || io.rightFireCond || io.bothFireCond) {
    assert(
      assertion = CountOne(
        io.leftFireCond ## io.rightFireCond ## io.bothFireCond
      ) === 1,
      message =
        L"${REPORT_TIME} time: bothFireCond=${io.bothFireCond}, leftFireCond=${io.leftFireCond}, rightFireCond=${io.rightFireCond} should be mutually exclusive".toSeq,
      severity = FAILURE
    )
  }

  val bothValid = io.leftInputStream.valid && io.rightInputStream.valid

  io.zipOutputStream.valid := False
  io.zipOutputStream._1 := False
  io.zipOutputStream._2 := io.leftInputStream.payload
  io.zipOutputStream._3 := False
  io.zipOutputStream._4 := io.rightInputStream.payload
  io.leftInputStream.ready := False
  io.rightInputStream.ready := False

  when(bothValid && io.bothFireCond) {
    io.zipOutputStream.valid := io.leftInputStream.fire
    io.zipOutputStream._1 := io.leftInputStream.valid
    io.zipOutputStream._3 := io.rightInputStream.valid
    io.leftInputStream.ready := io.zipOutputStream.ready
    io.rightInputStream.ready := io.zipOutputStream.ready

    assert(
      assertion =
        io.zipOutputStream.fire === io.leftInputStream.fire && io.leftInputStream.fire === io.rightInputStream.fire,
      message =
        L"${REPORT_TIME} time: zipOutputStream.fire=${io.zipOutputStream.fire} should fire with leftInputStream.fire=${io.leftInputStream.fire} and rightInputStream.fire=${io.rightInputStream.fire}".toSeq,
      severity = FAILURE
    )
  } otherwise {
    when(io.leftFireCond) {
      io.zipOutputStream.valid := io.leftInputStream.valid
      io.zipOutputStream._1 := io.leftInputStream.valid
      io.leftInputStream.ready := io.zipOutputStream.ready

      assert(
        assertion = io.zipOutputStream.fire === io.leftInputStream.fire,
        message =
          L"${REPORT_TIME} time: zipOutputStream.fire=${io.zipOutputStream.fire} should fire with leftInputStream.fire=${io.leftInputStream.fire}".toSeq,
        severity = FAILURE
      )
    }

    when(io.rightFireCond) {
      io.zipOutputStream.valid := io.rightInputStream.valid
      io.zipOutputStream._3 := io.rightInputStream.valid
      io.rightInputStream.ready := io.zipOutputStream.ready

      assert(
        assertion = io.zipOutputStream.fire === io.rightInputStream.fire,
        message =
          L"${REPORT_TIME} time: zipOutputStream.fire=${io.zipOutputStream.fire} should fire with rightInputStream.fire=${io.rightInputStream.fire}".toSeq,
        severity = FAILURE
      )
    }
  }
}

/** Throw the first several fragments of the inputStream. The number of
  * fragments to throw is specified by headerFragNum. Note, headerFragNum should
  * keep stable during the first fragment of inputStream.
  */
object StreamDropHeader {
  def apply[T <: Data](
      inputStream: Stream[Fragment[T]],
      headerFragNum: UInt
  ): Stream[Fragment[T]] = {
    val result = new StreamDropHeader(cloneOf(inputStream.fragmentType))
    result.io.inputStream << inputStream
    result.io.headerFragNum := headerFragNum
    result.io.outputStream.combStage()
  }
}

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

/** Segment the inputStream into multiple pieces, Each piece is at most
  * segmentLenBytes long, and segmentLenBytes cannot be zero. Each piece is
  * indicated by fragment last.
  */
object StreamSegment {
  def apply[T <: Data](
      inputStream: Stream[Fragment[T]],
      segmentFragNum: UInt
  ): Stream[Fragment[T]] = {
    val result = new StreamSegment(cloneOf(inputStream.fragmentType))
    result.io.inputStream << inputStream
    result.io.segmentFragNum := segmentFragNum
    result.io.outputStream.combStage()
  }
}

class StreamSegment[T <: Data](dataType: HardType[T]) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(dataType())))
    // TODO: Stream(UInt())
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
      message =
        L"${REPORT_TIME} time: segmentFragNum=${io.segmentFragNum} should be larger than 0".toSeq,
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

  io.outputStream << io.inputStream.map { payloadData =>
    val result = cloneOf(io.outputStream.payloadType) // Fragment(dataType())
    result.fragment := payloadData.fragment
    result.last := setLast
    result
  }
}

/** When adding header, the headerMty can only have consecutive zero bits from
  * the left-most side. Assume the header width is 4, the valid header Mty can
  * only be 4'b0000, 4'b0001, 4'b0011, 4'b0111, 4'b1111. The output stream does
  * not make any change to the header.
  *
  * Each header is for a packet, from first fragment to last fragment.
  */
object StreamAddHeader {
  def apply[T <: Data](
      inputStream: Stream[Fragment[DataAndMty]],
      inputHeader: Stream[HeaderDataAndMty[T]],
      busWidth: BusWidth.Value
  ): Stream[Fragment[HeaderDataAndMty[T]]] = {
    require(
      widthOf(inputStream.data) == busWidth.id,
      s"widthOf(inputStream.data)=${widthOf(inputStream.data)} should equal busWidth.id=${busWidth.id}"
    )
    val result =
      new StreamAddHeader(inputHeader.headerType, busWidth)
    result.io.inputStream << inputStream
    result.io.inputHeader << inputHeader
    result.io.outputStream.combStage()
  }
}

class StreamAddHeader[T <: Data](
    headerType: HardType[T],
    busWidth: BusWidth.Value
) extends Component {
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
    s"inputWidthBytes=${inputWidthBytes} should equal inputMtyWidth=${inputMtyWidth}"
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
        L"${REPORT_TIME} time: invalid inputHeaderMty=${inputHeaderMty} with headerMtyWidth=${inputHeaderMtyWidth}, should be ${headerMtyComp}".toSeq,
      severity = FAILURE
    )
    when(inputHeaderMty.orR) {
      assert(
        // OHMasking.first() return an OH indicate the right most 1 bit.
        assertion = OHMasking.first(inputHeaderMty) === 1,
        message =
          L"${REPORT_TIME} time: the inputHeaderMty=${inputHeaderMty} should have consecutive valid bits from LSB side".toSeq,
        severity = FAILURE
      )
    }

    when(!isLastFrag) {
      assert(
        assertion = inputMty.andR,
        message =
          L"${REPORT_TIME} time: the first or middle fragment should have MTY all set, but MTY=${inputMty}".toSeq,
        severity = FAILURE
      )
    } otherwise { // Last fragment
      // For the case of only one fragment, MTY can be zero if the request is of zero DMA length
      when(!isFirstFrag) { // Check MTY of the last fragment, when there are more than one fragment
        assert(
          assertion = inputMty.orR,
          message =
            L"${REPORT_TIME} time: the last fragment should have MTY=/=0, but MTY=${inputMty}".toSeq,
          severity = FAILURE
        )
      }
    }
  }

  val cachedJoinStreamReg =
    RegNextWhen(joinStream.payload, cond = joinStream.fire)
  val rstCacheData = cachedJoinStreamReg._1.data
  val cachedMty = cachedJoinStreamReg._1.mty
  val cachedHeader = cachedJoinStreamReg._2.header

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
    // BTH 12, RETH 16, AtomicETH 28, AETH 4, AtomicAckEth 8, ImmDt 4, IETH 4.
    //
    // Send request headers: BTH, BTH + ImmDt, BTH + IETH, 12 or 16 bytes
    // Write request headers: BTH + RETH, BTH, BTH + ImmDt, BTH + RETH + ImmDt, 28 or 12 or 16 or 32 bytes
    // Send/write Ack: BTH + AETH, 16 bytes
    //
    // Read request header: BTH + RETH, 28 bytes
    // Read response header: BTH + AETH, BTH, 16 or 12 bytes
    //
    // Atomic request header: BTH + AtomicETH, 40 bytes
    // Atomic response header: BTH + AETH + AtomicAckEth, 24 bytes
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
          outputData := (rstCacheData(0, headerWidth bits) ## inputData)
            .resizeLeft(inputWidth)
          outputMty := (cachedMty(0, headerMtyWidth bits) ## inputMty)
            .resizeLeft(inputMtyWidth)
        }

        // The extra last fragment
        extraLastFragData := (rstCacheData(0, headerWidth bits) ##
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
            L"${REPORT_TIME} time: invalid inputHeaderMty=${inputHeaderMty} with MTY width=${inputHeaderMtyWidth} and inputHeaderData=${inputHeaderData}".toSeq,
          severity = FAILURE
        )
      }
    }
  }

  val inputStreamTranslate = joinStream.translateWith {
    val result = cloneOf(io.outputStream.payloadType)
    result.header := inputHeader
    result.data := outputData
    result.mty := outputMty
    result.last := isLastFrag && !lastFragHasResidue
    result
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

/** When remove header, it removes the first several bytes data from the first
  * fragment, StreamRemoveHeader will shift the remaining bits, The output
  * stream have valid data start from MSB for all fragments.
  */
object StreamRemoveHeader {
  def apply(
      inputStream: Stream[Fragment[DataAndMty]],
      headerLenBytes: UInt,
      busWidth: BusWidth.Value
  ): Stream[Fragment[DataAndMty]] = {
    val result = new StreamRemoveHeader(busWidth)
    result.io.inputStream << inputStream
    result.io.headerLenBytes := headerLenBytes
    result.io.outputStream.combStage()
  }
}

class StreamRemoveHeader(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val inputStream = slave(Stream(Fragment(DataAndMty(busWidth))))
    // Stream()
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
    s"inputWidthBytes=${inputWidthBytes} should equal inputMtyWidth=${inputMtyWidth}"
  )
  // TODO: assert message cannot use non-Spinal-Data variable
  assert(
    assertion = 0 < io.headerLenBytes && io.headerLenBytes < inputWidthBytes,
    message =
      L"${REPORT_TIME} time: must have 0 < headerLenBytes=${io.headerLenBytes} < inputWidthBytes=${U(inputWidthBytes, widthOf(io.headerLenBytes) bits)}".toSeq,
    severity = FAILURE
  )

  val delayedInputStream = io.inputStream.stage()
  val rstCacheData = delayedInputStream.data
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
  outputData := ((rstCacheData ## inputData) >> rightShiftBitAmt)
    .resize(inputWidth)
  outputMty := ((cachedMty ## inputMty) >> rightShiftByteAmt)
    .resize(inputMtyWidth)

  // The extra last fragment
  val extraLastFragData =
    ((rstCacheData ## B(0, inputWidth bits)) >> rightShiftBitAmt)
      .resize(inputWidth)
  // When output stream has residue, the last beat is only from cachedDataReg, not from inputData
  val extraLastFragMty =
    ((cachedMty ## B(0, inputMtyWidth bits)) >> rightShiftByteAmt)
      .resize(inputMtyWidth)

  val inputStreamTranslate = delayedInputStream
    .throwWhen(throwDelayedLastFrag)
    .continueWhen(io.inputStream.valid || needHandleLastFragResidue)
    .translateWith {
      val result = cloneOf(io.inputStream.payloadType)
      when(needHandleLastFragResidue) {
        result.data := extraLastFragData
        result.mty := extraLastFragMty
        result.last := True
      } otherwise {
        result.data := outputData
        result.mty := outputMty
        result.last := isLastFrag && !inputFragHasResidue
      }
      result
    }

  io.outputStream << inputStreamTranslate
}

/** Join a stream A with a stream B, and A is always fired, but B is only fired
  * when condition is satisfied.
  */
object StreamConditionalJoin {
  def apply[T1 <: Data, T2 <: Data](
      inputA: Stream[T1],
      inputB: Stream[T2],
      joinCond: Bool
  ): Stream[TupleBundle2[T1, T2]] = {
    val emptyStream =
      StreamSource().translateWith(inputB.payloadType().assignDontCare())
    val streamToJoin = StreamMux(joinCond.asUInt, Vec(emptyStream, inputB))
    when(joinCond) {
      assert(
        assertion = inputA.fire === inputB.fire,
        message =
          L"${REPORT_TIME} time, inputA.fire=${inputA.fire} should fire with inputB.fire=${inputB.fire} when joinCond=${joinCond}".toSeq,
        severity = FAILURE
      )
    }
    StreamJoin(inputA, streamToJoin).combStage()
  }
}

/** Join a stream A of fragments with a stream B, that B will fire only when the
  * last fragment of A fires.
  */
object FragmentStreamJoinStream {
  def apply[T1 <: Data, T2 <: Data](
      inputFragStream: Stream[Fragment[T1]],
      inputStream: Stream[T2]
  ): Stream[Fragment[TupleBundle2[T1, T2]]] =
    new Composite(inputFragStream, "FragmentStreamJoinStream") {
      val resultStream = FragmentStreamConditionalJoinStream(
        inputFragStream,
        inputStream,
        joinCond = True
      )
      /*
    val joinStream = FragmentStreamJoinStreamOnCondition(
      inputFragmentStream,
      inputStream,
      joinCond = True
    )
    val returnStream = joinStream.translateWith {
      val result = Fragment(
        TupleBundle2(inputFragmentStream.fragmentType, inputStream.payloadType)
      )
      result._1 := joinStream._1
      result._2 := joinStream._2
      result.last := joinStream.last
      result
    }
       */
    }.resultStream.combStage()
}

/** Join a fragment stream A of fragments with a stream B, that B will fire only
  * when the join condition is satisfied and last fragment of A fires; when the
  * join condition is not satisfied, A will continue fire.
  */
object FragmentStreamConditionalJoinStream {
  def apply[T1 <: Data, T2 <: Data](
      inputFragStream: Stream[Fragment[T1]],
      inputStream: Stream[T2],
      joinCond: Bool
  ): Stream[Fragment[TupleBundle2[T1, T2]]] =
    new Composite(inputFragStream, "FragmentStreamConditionalJoinStream") {
      inputStream.ready := inputFragStream.lastFire && inputStream.valid && joinCond
      when(inputStream.valid && joinCond) {
        assert(
          assertion = inputFragStream.lastFire === inputStream.fire,
          message =
            L"${REPORT_TIME} time: when joinCond=${joinCond} is true, during each last beat, inputFragStream and inputStream should fire together in FragmentStreamConditionalJoinStream".toSeq,
          severity = FAILURE
        )
      }

      val resultStream = inputFragStream
        .haltWhen(joinCond && !inputStream.valid)
        .map { payloadData =>
          val result = Fragment(
            TupleBundle2(inputFragStream.fragmentType, inputStream.payloadType)
          )
          result._1 := payloadData.fragment
          result._2 := inputStream.payload
          result.last := payloadData.last
          result
        }
    }.resultStream.combStage()
}

/** Join a stream A of fragments with a stream B, and only fire B when condition
  * is satisfied. When B fires if condition satisfied, it only fires at the same
  * time when the last fragment of A fires. If join condition is not satisfied,
  * stream A will be halted.
  */
object FragmentStreamJoinStreamOnCondition {
  def apply[T1 <: Data, T2 <: Data](
      inputFragmentStream: Stream[Fragment[T1]],
      inputStream: Stream[T2],
      joinCond: Bool
  ): Stream[Fragment[TupleBundle2[T1, T2]]] = {
    val join = new FragmentStreamJoinStreamOnCondition(
      inputFragmentStream.fragmentType,
      inputStream.payloadType
    )
    join.io.inputFragmentStream << inputFragmentStream
    join.io.inputStream << inputStream
    join.io.joinCond := joinCond
    join.io.outputJoinStream.combStage()
  }
}

class FragmentStreamJoinStreamOnCondition[T1 <: Data, T2 <: Data](
    dataType1: HardType[T1],
    dataType2: HardType[T2]
) extends Component {
  val io = new Bundle {
    val inputFragmentStream = slave(Stream(Fragment(dataType1)))
    val inputStream = slave(Stream(dataType2))
    val joinCond = in(Bool())
    val outputJoinStream = master(
      Stream(Fragment(TupleBundle2(dataType1, dataType2)))
    )
  }

  io.inputStream.ready := io.inputFragmentStream.lastFire && io.joinCond
  when(io.inputStream.valid && io.joinCond) {
    assert(
      assertion = io.inputFragmentStream.lastFire === io.inputStream.fire,
      message =
        L"${REPORT_TIME} time: when joinCond=${io.joinCond} is true, during each last beat, inputFragmentStream and inputStream should fire together".toSeq,
      severity = FAILURE
    )
  }

  io.outputJoinStream << io.inputFragmentStream
    .continueWhen(io.inputStream.valid)
    .map { payloadData =>
      val result = cloneOf(io.outputJoinStream.payloadType)
      result._1 := payloadData.fragment
      result._2 := io.inputStream.payload
      result.last := payloadData.last
      result
    }
}

/** StreamConditionalFork will fork the input based on the condition. If
  * condition is true, then fork the input, otherwise not fork. So the return
  * streams have the last one as the original input stream.
  */
object StreamConditionalFork {
  def apply[T <: Data](
      inputStream: Stream[T],
      forkCond: Bool,
      portCount: Int
  ): Vec[Stream[T]] = {
    val result = Vec(Stream(inputStream.payloadType), portCount + 1)
    val forkStreams = StreamFork(inputStream, portCount + 1)
    result(portCount) << forkStreams(portCount)
    for (idx <- 0 until portCount) {
      result(idx) << forkStreams(idx).takeWhen(forkCond)
    }
    result
  }
}

/** StreamConditionalFork2 will fork the input based on the condition. If
  * condition is true, then fork the input, otherwise not fork. So the first
  * return stream is the conditional forked input stream, the second return
  * stream is the original input stream.
  */
object StreamConditionalFork2 {
  def apply[T <: Data](
      inputStream: Stream[T],
      forkCond: Bool
  ): (Stream[T], Stream[T]) = {
    val forkStreams =
      StreamConditionalFork(inputStream, forkCond, portCount = 1)
    (forkStreams(0).combStage(), forkStreams(1).combStage())
  }
}

class StreamConditionalFork[T <: Data](payloadType: HardType[T], portCount: Int)
    extends Component {
  val io = new Bundle {
    val input = slave(Stream(payloadType()))
    val longOutStream = master(Stream(payloadType()))
    val shortOutStreams = Vec(master(Stream(payloadType())), portCount)
    val forkCond = in(Bool())
  }

  val forkStreams = StreamFork(io.input, portCount + 1)
  io.longOutStream << forkStreams(portCount)
  for (idx <- 0 until portCount) {
    io.shortOutStreams(idx) << forkStreams(idx).takeWhen(io.forkCond)
  }
}

/** FragmentStreamForkQueryJoinResp will send out query based on query
  * condition, and join the response based on join condition. To avoid
  * back-pressure / stall, FragmentStreamForkQueryJoinResp has internal queue to
  * cache the pending inputs waiting for response to join.
  *
  * The second field of the result joinStream is to indicate the third field,
  * the respStream is valid or not.
  *
  * NOTE: if not expect to join with response stream, the input stream just pass
  * through. It's better to always expect response and join with input stream.
  */
object FragmentStreamForkQueryJoinResp {
  def apply[Tin <: Data, Tquery <: Data, Tresp <: Data](
      inputFragStream: Stream[Fragment[Tin]],
      queryStream: Stream[Tquery],
      respStream: Stream[Tresp],
      waitQueueDepth: Int,
      buildQuery: Stream[Fragment[Tin]] => Tquery,
      queryCond: Stream[Fragment[Tin]] => Bool,
      expectResp: Stream[Fragment[Tin]] => Bool,
      joinRespCond: Stream[Fragment[Tin]] => Bool
  ): Stream[Fragment[TupleBundle3[Tin, Bool, Tresp]]] =
    new Composite(inputFragStream, "FragmentStreamForkQueryJoinResp") {

      val (input4QueryStream, input4Queue) =
        StreamConditionalFork2(
          inputFragStream,
          forkCond = queryCond(inputFragStream)
        )
      queryStream <-/< input4QueryStream
        .takeWhen(expectResp(input4QueryStream))
        .translateWith(buildQuery(input4QueryStream))
      /*
      val inputFragQueue =
        StreamFifoLowLatency(inputFragStream.payloadType(), waitQueueDepth)
//    inputFragQueue.setName("inputFragQueue", weak = true)
      inputFragQueue.io.push << input4Queue
      assert(
        assertion = inputFragQueue.io.push.ready,
        message =
          L"${REPORT_TIME} time: inputFragQueue is full, inputFragQueue.io.push.ready=${inputFragQueue.io.push.ready}, inputFragQueue.io.occupancy=${inputFragQueue.io.occupancy}, which is not allowed in FragmentStreamForkQueryJoinResp".toSeq,
        severity = FAILURE
      )
//    inputFragQueue.io.flush :=
      val inputFragQueuePop = inputFragQueue.io.pop.combStage()
       */
      val inputFragQueuePop = FixedLenQueue(
        inputFragStream.payloadType(),
        depth = waitQueueDepth,
        push = input4Queue,
        flush = False, // TODO: enable queue flush
        queueName = "inputFragQueue"
      )
      val emptyStream = StreamSource().translateWith(
        cloneOf(respStream.payloadType).assignDontCare()
      )
      // When not expect query response, join with StreamSource()
      val selectedStreamValid = expectResp(inputFragQueuePop)
      val selectedStream = StreamMux(
        select = selectedStreamValid.asUInt,
        inputs = Vec(emptyStream, respStream)
      )
      val stream4Join = selectedStream.translateWith {
        val result = TupleBundle(selectedStreamValid, selectedStream.payload)
        result
      }

      val joinStream = FragmentStreamJoinStreamOnCondition(
        inputFragQueuePop,
        stream4Join,
        joinCond = joinRespCond(inputFragQueuePop)
      )
      val resultStream = joinStream
        .translateWith {
          val result = Fragment(
            TupleBundle3(
              inputFragStream.fragmentType,
              Bool,
              respStream.payloadType
            )
          )
          result._1 := joinStream._1
          result._2 := joinStream._2._1
          result._3 := joinStream._2._2
          result.last := joinStream.last
          result
        }
        .combStage()
    }.resultStream
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
    arbiterTree.io.output.combStage()
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
        StreamArbiterFactory().roundRobin.fragmentLock
          .on(inputVec)
          .pipelined(m2s = streamPipelined, s2m = streamPipelined)
      } else {
        StreamArbiterFactory().roundRobin.transactionLock
          .on(inputVec)
          .pipelined(m2s = streamPipelined, s2m = streamPipelined)
      }
    } else {
      val segmentList = inputVec.grouped(arbiterPortCount).toList
      val nextTreeLayer = for (segment <- segmentList) yield {
        if (fragmentLockOn) {
          StreamArbiterFactory().roundRobin.fragmentLock
            .on(segment)
            .pipelined(m2s = streamPipelined, s2m = streamPipelined)
        } else {
          StreamArbiterFactory().roundRobin.transactionLock
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
      message =
        L"${REPORT_TIME} time: io.select=${io.select} is not one hot, io.select should have no more than one bit as true, when io.input is valid".toSeq,
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
    StreamOneHotMux(select, vec).combStage()
  }

  def apply[T <: Data](select: Bits, inputs: Vec[Stream[T]]): Stream[T] = {
    require(
      widthOf(select) == inputs.size,
      s"widthOf(select)=${widthOf(select)} should equal inputs.size=${inputs.size}"
    )

    val streamOneHotMux =
      new StreamOneHotMux(inputs(0).payloadType, inputs.length)
    streamOneHotMux.io.select := select
    for (idx <- 0 until widthOf(select)) {
      streamOneHotMux.io.inputs(idx) << inputs(idx)
    }
    streamOneHotMux.io.output.combStage()
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

  val inputValid = io.inputs.map(_.valid).reduceBalancedTree(_ || _)
  when(inputValid) {
    assert(
      assertion = CountOne(io.select) <= 1,
      message =
        L"${REPORT_TIME} time: de-mux conditions io.select=${io.select} should have no more than one true condition when input streams have valid input".toSeq,
      severity = FAILURE
    )
  }

  io.output.valid := False
  io.output.payload := io.inputs(0).payload
  for (idx <- 0 until portCount) {
    io.inputs(idx).ready := False
    when(io.select(idx)) {
      io.output.valid := io.inputs(idx).valid
      io.inputs(idx).ready := io.output.ready
      io.output.payload := io.inputs(idx).payload
    }
  }
}

object StreamDeMuxByConditions {
  def apply[T <: Data](input: Stream[T], conditions: Bool*): Vec[Stream[T]] = {
    val oneHot = conditions.asBits()
    when(input.valid) {
      assert(
        assertion = CountOne(oneHot) === 1,
        message =
          L"${REPORT_TIME} time: de-mux conditions=${oneHot} should have exact one true condition when input stream is valid".toSeq,
        severity = FAILURE
      )
    }
    StreamOneHotDeMux(input, oneHot)
  }
}

/** StreamDeMuxByOneCondition will de-multiplex input stream into two output
  * streams. The first output stream corresponds to the condition is false, the
  * second output stream corresponds to the condition is true.
  */
object StreamDeMuxByOneCondition {
  def apply[T <: Data](
      input: Stream[T],
      condition: Bool
  ): (Stream[T], Stream[T]) = {
    val (condFalseIdx, condTrueIdx) = (0, 1)
    val outputs = StreamDemux(input, select = condition.asUInt, portCount = 2)
    (outputs(condFalseIdx).combStage(), outputs(condTrueIdx).combStage())
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

// Add ID
// FIFO
// 1024 req -> 1 resp
// 1 resp -> 1 out of 1024 req, timing issue
// TODO: class StreamDeMuxTree[T <: Data]

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

/** StreamCounterSource generates a stream of counter value, from startValue to
  * stopValue (exclusive).
  */
object StreamCounterSource {
  def apply(
      startPulse: Bool,
      startValue: UInt,
      stopValue: UInt,
      flush: Bool,
      stateCount: Int
  ): (Stream[UInt], Bool, Bool) = {
    val cntWidth = log2Up(stateCount)
    val stream = Stream(UInt(cntWidth bits))
    val counter = Counter(stateCount = stateCount, inc = stream.fire)
    val running = RegInit(False)
    when(startPulse && !flush) {
      running := True
      counter.valueNext := startValue
    }
    val done = stream.fire && counter.valueNext === stopValue
    when(done || flush) {
      running := False
    }

    stream.valid := running
    stream.payload := counter.value
    (stream.combStage(), running, done)
  }
}

/** StreamExtractCompany extracts a company data from inputStream, and
  * outputStream contains inputStream payload and extracted company data
  */
object StreamExtractCompany {
  def apply[Tpay <: Data, Tcomp <: Data](
      inputStream: Stream[Tpay],
      companyExtractFunc: Stream[Tpay] => Flow[Tcomp]
  ): Stream[TupleBundle2[Tpay, Tcomp]] =
    new Composite(inputStream, "StreamExtractCompany") {
      val companyFlow = companyExtractFunc(inputStream)
      // Data register, no need to reset
      val companyReg = Reg(companyFlow.payloadType)

      val outputStream = inputStream.translateWith {
        val result =
          TupleBundle2(inputStream.payloadType, companyFlow.payloadType)
        result._1 := inputStream.payload
        when(companyFlow.valid) {
          result._2 := companyFlow.payload
          companyReg := companyFlow.payload
        } otherwise {
          result._2 := companyReg
        }
        result
      }
    }.outputStream
}

object FlowExtractCompany {
  def apply[Tpay <: Data, Tcomp <: Data](
      inputFlow: Flow[Tpay],
      companyExtractFunc: Flow[Tpay] => Flow[Tcomp]
  ): Flow[TupleBundle2[Tpay, Tcomp]] =
    new Composite(inputFlow, "FlowExtractCompany") {
      val companyFlow = companyExtractFunc(inputFlow)
      // Data register, no need to reset
      val companyReg = Reg(companyFlow.payloadType)

      val outputFlow = inputFlow.translateWith {
        val result =
          TupleBundle2(inputFlow.payloadType, companyFlow.payloadType)
        result._1 := inputFlow.payload
        when(companyFlow.valid) {
          result._2 := companyFlow.payload
          companyReg := companyFlow.payload
        } otherwise {
          result._2 := companyReg
        }
        result
      }
    }.outputFlow
}

object DelayedStream {
  def apply[T <: Data](inputStream: Stream[T], delayCycles: Int): Stream[T] =
    new Composite(inputStream, "DelayedStream") {
      require(
        delayCycles >= 0,
        s"${REPORT_TIME} time: delayCycles=${delayCycles} should >= 0"
      )

      // def unfold[A, S](init: S)(f: (S) => Option[(A, S)]): Iterator[A]
      val delayedStreamSeq = IndexedSeq.unfold((inputStream, delayCycles)) {
        state =>
          val (stream, delay) = state
          if (delay > 0) {
            val oneCycleDelayedStream = stream.stage()
            Some((oneCycleDelayedStream, (oneCycleDelayedStream, delay - 1)))
          } else {
            None
          }
      }
      val result = if (delayCycles > 0) delayedStreamSeq.last else inputStream
    }.result.combStage()
}

//========== Misc utilities ==========

object setAllBits {
  def apply(width: Int): BigInt = {
    require(
      width > 0,
      s"width=${width} should > 0 in setAllBits"
    )
    (BigInt(1) << width) - 1
  }

  def apply(width: UInt): Bits =
    new Composite(width, "setAllBits") {
      assert(
        assertion = width > 0,
        message =
          f"${REPORT_TIME} time: width=${width} should > 0 in setAllBits",
        severity = FAILURE
      )
      val one = U(1, 1 bit)
      val shift = (one << width) - 1
      val result = shift.asBits
    }.result
}

object mergeRdmaHeader {
  def apply(busWidth: BusWidth.Value, baseHeader: RdmaHeader): Bits =
    new Composite(baseHeader, "mergeRdmaHeader1") {
      val result = baseHeader.asBigEndianBits().resize(busWidth.id)
    }.result

  def apply(
      busWidth: BusWidth.Value,
      baseHeader: RdmaHeader,
      extHeader: RdmaHeader
  ): Bits = new Composite(baseHeader, "mergeRdmaHeader2") {
    val result = (baseHeader.asBigEndianBits() ## extHeader.asBigEndianBits())
      .resize(busWidth.id)
  }.result

  def apply(
      busWidth: BusWidth.Value,
      baseHeader: RdmaHeader,
      extHeader1: RdmaHeader,
      extHeader2: RdmaHeader
  ): Bits = new Composite(baseHeader, "mergeRdmaHeader3") {
    val result = (baseHeader.asBigEndianBits() ## extHeader1
      .asBigEndianBits() ## extHeader2.asBigEndianBits())
      .resize(busWidth.id)
  }.result
}

object mergeRdmaHeaderMty {
  def apply(busWidth: BusWidth.Value, baseHeaderMty: Bits): Bits =
    new Composite(baseHeaderMty, "mergeRdmaHeaderMty1") {
      val busWidthBytes = busWidth.id / BYTE_WIDTH
      val result = baseHeaderMty.resize(busWidthBytes)
    }.result

  def apply(
      busWidth: BusWidth.Value,
      baseHeaderMty: Bits,
      extHeaderMty: Bits
  ): Bits =
    new Composite(baseHeaderMty, "mergeRdmaHeaderMty2") {
      val busWidthBytes = busWidth.id / BYTE_WIDTH
      val result = (baseHeaderMty ## extHeaderMty).resize(busWidthBytes)
    }.result

  def apply(
      busWidth: BusWidth.Value,
      baseHeaderMty: Bits,
      extHeaderMty1: Bits,
      extHeaderMty2: Bits
  ): Bits = new Composite(baseHeaderMty, "mergeRdmaHeaderMty3") {
    val busWidthBytes = busWidth.id / BYTE_WIDTH
    val result = (baseHeaderMty ## extHeaderMty1 ## extHeaderMty2).resize(
      busWidthBytes
    )
  }.result
}

object FixedLenQueue {
  def apply[T <: Data](
      dataType: T,
      depth: Int,
      push: Stream[T],
      flush: => Bool,
      queueName: String
  ): Stream[T] = new Composite(push, "FixedLenQueue") {
    val queue = new StreamFifoLowLatency(dataType, depth).setName(queueName)
    queue.io.push << push
    queue.io.flush := flush
    assert(
      assertion = queue.io.push.ready,
      message =
        L"${REPORT_TIME} time: ${queueName} is full, queue.io.push.ready=${queue.io.push.ready}, queue.io.occupancy=${queue.io.occupancy}, which is not allowed".toSeq,
      severity = FAILURE
    )
  }.queue.io.pop.combStage()
}

object SeqOut {
  def apply[TPsnRange <: PsnRange](
      curPsn: UInt,
      flush: Bool,
      outPsnRangeQueuePush: Stream[TPsnRange],
      outDataStreamVec: Vec[Stream[Fragment[RdmaDataPkt]]],
//      psnOutRangeFifoDepth: Int,
      outputValidateFunc: (TPsnRange, RdmaDataPkt) => Unit,
      // Outputs:
      hasPktToOutput: Bool,
      opsnInc: OPsnInc
  ) = new Composite(curPsn, "SeqOut") {
    // TODO: set max pending request number using QpAttrData
    val psnOutRangeQueuePop = FixedLenQueue(
      outPsnRangeQueuePush.payloadType(),
      depth = PSN_OUT_RANGE_FIFO_DEPTH,
      push = outPsnRangeQueuePush,
      flush = flush,
      queueName = "outPsnRangeFifo"
    )
    /*
    val psnOutRangeFifo = StreamFifoLowLatency(
      outPsnRangeQueuePush.payloadType(),
      depth = PSN_OUT_RANGE_FIFO_DEPTH
    )
    psnOutRangeFifo.io.flush := flush
    psnOutRangeFifo.io.push << outPsnRangeQueuePush
    assert(
      assertion = psnOutRangeFifo.io.push.ready,
      message =
        L"${REPORT_TIME} time: psnOutRangeFifo is full, psnOutRangeFifo.io.push.ready=${psnOutRangeFifo.io.push.ready}, psnOutRangeFifo.io.occupancy=${psnOutRangeFifo.io.occupancy}, which is not allowed in SeqOut".toSeq,
      severity = FAILURE
    )
     */
    val outStreamVec = Vec(outDataStreamVec.map(_.throwWhen(flush)))
    val outSelOH = outStreamVec.map(resp =>
      new Composite(resp, "outSelOH") {
        val psnRangeMatch = resp.valid && psnOutRangeQueuePop.valid &&
          PsnUtil.lte(psnOutRangeQueuePop.start, resp.bth.psn, curPsn) &&
          PsnUtil.lte(resp.bth.psn, psnOutRangeQueuePop.end, curPsn)
        when(psnRangeMatch) {
          outputValidateFunc(psnOutRangeQueuePop, resp)
        }
      }.psnRangeMatch
    )

    hasPktToOutput := outSelOH.orR
    val outStreamSel = StreamOneHotMux(
      select = outSelOH.asBits(),
      inputs = outStreamVec
    )

    val joinCond =
      outStreamSel.bth.psn === psnOutRangeQueuePop.end && outStreamSel.isLast
    val outJoinStream = FragmentStreamJoinStreamOnCondition(
      inputFragmentStream = outStreamSel,
      inputStream = psnOutRangeQueuePop,
      joinCond = joinCond
    )
    val outStream = outJoinStream.translateWith {
      val result = cloneOf(outDataStreamVec(0).payloadType)
      result.fragment := outJoinStream._1
      result.last := outJoinStream.isLast
      result
    }

    opsnInc.inc := outStreamSel.lastFire
    opsnInc.psnVal := outStreamSel.bth.psn

    val result = (outStream.combStage(), psnOutRangeQueuePop)
  }.result
}

object IPv4Addr {
  def apply(ipv4Str: String): Bits = {
    ipv4Str match {
      case s"$a.$b.$c.$d" => {
        val parts = List(a, b, c, d).map(_.toInt)
        require(!parts.exists(_ > 255), s"invalid IPv4 addr=$ipv4Str")
        parts.map(i => B(i & 0xff, 8 bits)).reduce(_ ## _)
      }
      case _ => {
        assert(
          assertion = False,
          message = s"illegal IPv4 string=${ipv4Str}",
          severity = FAILURE
        )
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

//// TODO: remove this once Spinal HDL upgraded to 1.6.2
//object ComponentEnrichment {
//  implicit class ComponentExt[T <: Component](val that: T) {
//    def stub(): T = that.rework {
//      // step1: First remove all we don't want
//      that.children.clear()
//      that.dslBody.foreachStatements {
//        case bt: BaseType if !bt.isDirectionLess =>
//        case s                                   => s.removeStatement()
//      }
//      // step2: remove output and assign zero
//      // this step can't merge into step1
//      that.dslBody.foreachStatements {
//        case bt: BaseType if bt.isOutput | bt.isInOut =>
//          bt.removeAssignments()
//          bt := bt.getZero
//        case _ =>
//      }
//      that
//    }
//  }
//}

//========== Packet validation related utilities ==========

object PsnUtil {
  def cmp(
      psnA: UInt,
      psnB: UInt,
      curPsn: UInt
  ): SpinalEnumCraft[PsnCompResult.type] =
    new Composite(curPsn, "PsnUtil_cmp") {
      // TODO: check timing, maybe too many logic level here
      val result = PsnCompResult()
//      when(psnA === psnB) {
//        result := PsnCompResult.EQUAL
//      } elsewhen (psnA === curPsn) {
//        result := PsnCompResult.GREATER
//      } elsewhen (psnB === curPsn) {
//        result := PsnCompResult.LESSER
//      } elsewhen (psnA < psnB) {
//        when(curPsn <= psnA) {
//          result := PsnCompResult.LESSER
//        } elsewhen (psnB <= curPsn) {
//          result := PsnCompResult.LESSER
//        } otherwise {
//          result := PsnCompResult.GREATER
//        }
//      } otherwise { // psnA > psnB
//        when(psnA <= curPsn) {
//          result := PsnCompResult.GREATER
//        } elsewhen (curPsn <= psnB) {
//          result := PsnCompResult.GREATER
//        } otherwise {
//          result := PsnCompResult.LESSER
//        }
//      }

      val oldestPSN = (curPsn - HALF_MAX_PSN) & PSN_MASK
      when(psnA === psnB) {
        result := PsnCompResult.EQUAL
      } elsewhen (psnA < psnB) {
        when(oldestPSN <= psnA) {
          result := PsnCompResult.LESSER
        } elsewhen (psnB <= oldestPSN) {
          result := PsnCompResult.LESSER
        } otherwise {
          result := PsnCompResult.GREATER
        }
      } otherwise { // psnA > psnB
        when(psnA <= oldestPSN) {
          result := PsnCompResult.GREATER
        } elsewhen (oldestPSN <= psnB) {
          result := PsnCompResult.GREATER
        } otherwise {
          result := PsnCompResult.LESSER
        }
      }
    }.result

  /** The diff between two PSNs. Since valid PSNs are within nPSN + 2^23, So
    * psnA - psnB, always <= HALF_MAX_PSN
    */
  def diff(psnA: UInt, psnB: UInt): UInt =
    new Composite(psnA, "PsnUtil_diff") {
//      val min = UInt(PSN_WIDTH bits)
//      val max = UInt(PSN_WIDTH bits)
//      when(psnA > psnB) {
//        min := psnB
//        max := psnA
//      } otherwise {
//        min := psnA
//        max := psnB
//      }
//      val diff = max - min
      val diff = ((psnA +^ TOTAL_PSN) - psnB).resize(PSN_WIDTH)
      val result = UInt(PSN_WIDTH bits)
      when(diff > HALF_MAX_PSN) {
        result := (TOTAL_PSN - diff).resize(PSN_WIDTH)
      } otherwise {
        result := diff
      }
    }.result

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

  // Check if psn within range [start, start + pktNum) or not
  def withInRange(psn: UInt, start: UInt, pktNum: UInt, curPsn: UInt): Bool =
    new Composite(curPsn, "PsnUtil_withInRange") {
      val psnEnd = psn + pktNum
      val result = gte(psn, start, curPsn) && lt(psn, psnEnd, curPsn)
    }.result

  def psnDiffLenBytes(psnDiff: UInt, qpAttrPmtu: UInt): UInt =
    new Composite(psnDiff, "PsnUtil_psnDiffLenBytes") {
      val result =
        (psnDiff << qpAttrPmtu).resize(RDMA_MAX_LEN_WIDTH)
    }.result
}

object bufSizeCheck {
  def apply(
      targetPhysicalAddr: UInt,
      dataLenBytes: UInt,
      bufPhysicalAddr: UInt,
      bufSize: UInt
  ): Bool =
    new Composite(dataLenBytes, "bufSizeCheck") {
      val bufEndAddr = bufPhysicalAddr + bufSize
      val targetEndAddr = targetPhysicalAddr + dataLenBytes
      val result =
        bufPhysicalAddr <= targetPhysicalAddr && targetEndAddr <= bufEndAddr
    }.result
}

object OpCodeSeq {
  import OpCode._

  def checkReqSeq(preOpCode: Bits, curOpCode: Bits): Bool =
    new Composite(curOpCode, "OpCodeSeq_checkReqSeq") {
      val result = Bool()

      switch(preOpCode) {
        is(SEND_FIRST.id, SEND_MIDDLE.id) {
          result := curOpCode === SEND_MIDDLE.id ||
            OpCode.isSendLastReqPkt(curOpCode)
        }
        is(RDMA_WRITE_FIRST.id, RDMA_WRITE_MIDDLE.id) {
          result := curOpCode === RDMA_WRITE_MIDDLE.id ||
            OpCode.isWriteLastReqPkt(curOpCode)
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
          result := OpCode.isFirstOrOnlyReqPkt(curOpCode)
        }
        default {
          result := False
        }
      }
    }.result

  def checkReadRespSeq(preOpCode: Bits, curOpCode: Bits): Bool =
    new Composite(curOpCode, "OpCodeSeq_checkReadRespSeq") {
      val result = Bool()

      switch(preOpCode) {
        is(RDMA_READ_RESPONSE_FIRST.id, RDMA_READ_RESPONSE_MIDDLE.id) {
          result := curOpCode === RDMA_READ_RESPONSE_MIDDLE.id || curOpCode === RDMA_READ_RESPONSE_LAST.id
        }
        is(RDMA_READ_RESPONSE_ONLY.id, RDMA_READ_RESPONSE_LAST.id) {
          result := curOpCode === RDMA_READ_RESPONSE_FIRST.id || curOpCode === RDMA_READ_RESPONSE_ONLY.id
        }
        default {
          result := False
        }
      }
    }.result
}

object padCount {
  def apply(pktLen: UInt): UInt =
    new Composite(pktLen, "padCount") {
      val padCntWidth = 2 // Pad count is 0 ~ 3
      val padCnt = UInt(padCntWidth bits)
      val pktLenRightMost2Bits = pktLen.resize(padCntWidth)
      padCnt := (PAD_COUNT_FULL - pktLenRightMost2Bits).resize(padCntWidth)
    }.padCnt
}

object reqPadCountCheck {
  def apply(
      opcode: Bits,
      padCount: UInt,
      pktMty: Bits,
      isLastFrag: Bool,
      busWidth: BusWidth.Value
  ): Bool =
    new Composite(padCount, "reqPadCountCheck") {
      require(
        busWidth.id >= MAX_HEADER_WIDTH,
        s"busWidth=${busWidth.id} should >= MAX_HEADER_WIDTH=${MAX_HEADER_WIDTH}, in reqPadCountCheck"
      )

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
          mtyCheck := pktFragLen === (ATOMIC_REQ_WIDTH / BYTE_WIDTH)
        } elsewhen (OpCode.isReadReqPkt(opcode)) {
          // Read request has BTH and RETH, total 48 + 16 = 64 bytes
          // BTH is not included in packet payload
          // Assume RETH fits in bus width
          mtyCheck := pktFragLen === (READ_REQ_WIDTH / BYTE_WIDTH)
        }
      }
      val result = mtyCheck && paddingCheck
    }.result
}

object respPadCountCheck {
  def apply(
      opcode: Bits,
      padCount: UInt,
      pktMty: Bits,
      isLastFrag: Bool,
      busWidth: BusWidth.Value
  ): Bool =
    new Composite(padCount, "respPadCountCheck") {
      require(
        busWidth.id >= MAX_HEADER_WIDTH,
        s"busWidth=${busWidth.id} should >= MAX_HEADER_WIDTH=${MAX_HEADER_WIDTH}, in respPadCountCheck"
      )

      val paddingCheck = True
      val mtyCheck = Bool()
      val pktFragLenBytes = CountOne(pktMty)
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
          // Atomic response has BTH and AtomicAckEth, total 48 + 8 = 56 bytes
          // BTH is included in packet payload
          // Assume AtomicAckEth fits in bus width
          mtyCheck := pktFragLenBytes === (ATOMIC_RESP_WIDTH / BYTE_WIDTH)
        } elsewhen (OpCode.isNonReadAtomicRespPkt(opcode)) {
          // Acknowledge has BTH and AETH, total 48 + 4 = 52 bytes
          // BTH is included in packet payload
          // Assume AETH fits in bus width
          mtyCheck := pktFragLenBytes === (ACKNOWLEDGE_WIDTH / BYTE_WIDTH)
        } elsewhen (OpCode.isLastOrOnlyReadRespPkt(opcode)) {
          // Last or Only read response has BTH and AETH and possible read data, at least 48 + 4 = 52 bytes
          // BTH is included in packet payload
          // Assume AETH fits in bus width
          mtyCheck := pktFragLenBytes >= (ACKNOWLEDGE_WIDTH / BYTE_WIDTH)
        }
      }
      val result = mtyCheck && paddingCheck
    }.result
}

object ePsnIncrease {
  def apply(pktFrag: RdmaDataPkt, pmtu: UInt): UInt =
    new Composite(pktFrag, "ePsnIncrease") {
      val result = UInt(PSN_WIDTH bits)
      val isReadReq = OpCode.isReadReqPkt(pktFrag.bth.opcode)
      when(OpCode.isReqPkt(pktFrag.bth.opcode)) {
        when(isReadReq) {
          val reth = pktFrag.extractReth()
          result := computePktNum(reth.dlen, pmtu)
        } otherwise {
          result := 1
//          assert(
//            assertion = OpCode.isReqPkt(pktFrag.bth.opcode),
//            message =
//              L"${REPORT_TIME} time: ePsnIncrease() expects requests, but opcode=${pktFrag.bth.opcode} is not of request",
//            severity = FAILURE
//          )
        }
      } otherwise {
        result := 0
      }
    }.result
}

//========== Packet related utilities ==========

object computePktNum {
  def apply(lenBytes: UInt, pmtu: UInt): UInt =
    new Composite(lenBytes, "computePktNum") {
//      val result = UInt(PSN_WIDTH bits)
      val residueMaxWidth = PMTU.U4096.id

      val shiftAmt = UInt(PMTU_WIDTH bits)
      val residue = UInt(residueMaxWidth bits)
      switch(pmtu) {
        is(PMTU.U256.id) {
          shiftAmt := PMTU.U256.id
          residue := lenBytes(0, PMTU.U256.id bits).resize(residueMaxWidth)
        }
        is(PMTU.U512.id) {
          shiftAmt := PMTU.U512.id
          residue := lenBytes(0, PMTU.U512.id bits).resize(residueMaxWidth)
        }
        is(PMTU.U1024.id) {
          shiftAmt := PMTU.U1024.id
          residue := lenBytes(0, PMTU.U1024.id bits).resize(residueMaxWidth)
        }
        is(PMTU.U2048.id) {
          shiftAmt := PMTU.U2048.id
          residue := lenBytes(0, PMTU.U2048.id bits).resize(residueMaxWidth)
        }
        is(PMTU.U4096.id) {
          shiftAmt := PMTU.U4096.id
          residue := lenBytes(0, PMTU.U4096.id bits).resize(residueMaxWidth)
        }
        default {
          shiftAmt := 0
          residue := 0

          report(
            message =
              L"${REPORT_TIME} time: computePktNum encounters invalid PMTU=${pmtu}".toSeq,
            severity = FAILURE
          )
        }
      }
//      val (shiftAmt, residue) = pmtu.mux(
//        PMTU.U256.id -> (
//          U(PMTU.U256.id, PMTU_WIDTH bits),
//          lenBytes(0, PMTU.U256.id bits).resize(residueMaxWidth)
//        ),
//        PMTU.U512.id -> (
//          U(PMTU.U512.id, PMTU_WIDTH bits),
//          lenBytes(0, PMTU.U512.id bits).resize(residueMaxWidth)
//        ),
//        PMTU.U1024.id -> (
//          U(PMTU.U1024.id, PMTU_WIDTH bits),
//          lenBytes(0, PMTU.U1024.id bits).resize(residueMaxWidth)
//        ),
//        PMTU.U2048.id -> (
//          U(PMTU.U2048.id, PMTU_WIDTH bits),
//          lenBytes(0, PMTU.U2048.id bits).resize(residueMaxWidth)
//        ),
//        PMTU.U4096.id -> (
//          U(PMTU.U4096.id, PMTU_WIDTH bits),
//          lenBytes(0, PMTU.U4096.id bits).resize(residueMaxWidth)
//        ),
//        default -> {
//          report(
//            message =
//              L"${REPORT_TIME} time: computePktNum encounters invalid PMTU=${pmtu}",
//            severity = FAILURE
//          )
//
//          (U(0, PMTU_WIDTH bits), U(0, residueMaxWidth bits))
//        }
//      )

      val pktNum = (lenBytes >> shiftAmt) + (residue.orR).asUInt
      // This downsize is safe, since max length is 2^32 bytes,
      // And the min packet size is 256=2^8 bytes,
      // So the max packet number is 2^PSN_WIDTH=2^24
      val result = pktNum.resize(PSN_WIDTH)
    }.result
}

object pmtuPktLenBytes {
  def apply(pmtu: UInt): UInt = new Composite(pmtu, "pmtuPktLenBytes") {
    val result = UInt(PMTU_FRAG_NUM_WIDTH bits)
    switch(pmtu) {
      // The PMTU enum value itself means right shift amount of bits
      is(PMTU.U256.id) { // 8
        result := 256
      }
      is(PMTU.U512.id) { // 9
        result := 512
      }
      is(PMTU.U1024.id) { // 10
        result := 1024
      }
      is(PMTU.U2048.id) { // 11
        result := 2048
      }
      is(PMTU.U4096.id) { // 12
        result := 4096
      }
      default {
        result := 0
        report(
          message =
            L"${REPORT_TIME} time: pmtuPktLenBytes encounters invalid PMTU=${pmtu}".toSeq,
          severity = FAILURE
        )
      }
    }
  }.result
}

object pmtuLenMask {
  def apply(pmtu: UInt): Bits = new Composite(pmtu, "pmtuLenMask") {
    val result = Bits(PMTU_FRAG_NUM_WIDTH bits)
    switch(pmtu) {
      // The value means right shift amount of bits
      is(PMTU.U256.id) { // 8
        result := 256 - 1
      }
      is(PMTU.U512.id) { // 9
        result := 512 - 1
      }
      is(PMTU.U1024.id) { // 10
        result := 1024 - 1
      }
      is(PMTU.U2048.id) { // 11
        result := 2048 - 1
      }
      is(PMTU.U4096.id) { // 12
        result := 4096 - 1
      }
      default {
        result := 0
        report(
          message =
            L"${REPORT_TIME} time: pmtuLenMask encounters invalid PMTU=${pmtu}".toSeq,
          severity = FAILURE
        )
      }
    }
  }.result
}

/** Divide the dividend by a divisor, and take the celling NOTE: divisor must be
  * power of 2
  */
object divideByPmtuUp {
  private def checkPmtu(pmtu: UInt): Bool =
    new Composite(pmtu, "divideByPmtuUp_checkPmtu") {
      val result = pmtu.mux(
        PMTU.U256.id -> True,
        PMTU.U512.id -> True,
        PMTU.U1024.id -> True,
        PMTU.U2048.id -> True,
        PMTU.U4096.id -> True,
        default -> False
      )
    }.result

  def apply(dividend: UInt, pmtu: UInt): UInt =
    new Composite(dividend, "divideByPmtuUp") {
      assert(
        assertion = checkPmtu(pmtu),
        message =
          L"${REPORT_TIME} time: divideByPmtuUp encounters invalid PMTU=${pmtu}".toSeq,
        severity = FAILURE
      )

      val shiftAmt = pmtu
      val remainder = moduloByPmtu(dividend, pmtu)
      val result = (dividend >> shiftAmt) +
        (remainder.orR ? U(1) | U(0))
    }.result
}

/** Modulo of the dividend by a divisor. NOTE: divisor must be power of 2
  */
object moduloByPmtu {
  def apply(dividend: UInt, pmtu: UInt): UInt =
    new Composite(dividend, "moduloByPmtu") {
//    require(
//      widthOf(dividend) >= widthOf(divisor),
//      "width of dividend should >= that of divisor"
//    )
//    assert(
//      assertion = CountOne(divisor) === 1,
//      message = L"${REPORT_TIME} time: divisor=${divisor} should be power of 2",
//      severity = FAILURE
//    )
      val mask = pmtuLenMask(pmtu)
      val result = dividend & mask.resize(widthOf(dividend)).asUInt
    }.result
}

object checkWorkReqOpCodeMatch {
  def apply(
      workReqOpCode: SpinalEnumCraft[WorkReqOpCode.type],
      reqOpCode: Bits
  ): Bool =
    new Composite(reqOpCode, "checkWorkReqOpCodeMatch") {
      val result = (WorkReqOpCode.isSendReq(workReqOpCode) &&
        OpCode.isSendReqPkt(reqOpCode)) ||
        (WorkReqOpCode.isWriteReq(workReqOpCode) &&
          OpCode.isWriteReqPkt(reqOpCode)) ||
        (WorkReqOpCode.isReadReq(workReqOpCode) &&
          OpCode.isReadReqPkt(reqOpCode)) ||
        (WorkReqOpCode.isAtomicReq(workReqOpCode) &&
          OpCode.isAtomicReqPkt(reqOpCode))
    }.result
}

object checkRespOpCodeMatch {
  def apply(reqOpCode: Bits, respOpCode: Bits): Bool =
    new Composite(reqOpCode, "checkRespOpCodeMatch") {
      val result = Bool()
      when(OpCode.isSendReqPkt(reqOpCode) || OpCode.isWriteReqPkt(reqOpCode)) {
        result := OpCode.isNonReadAtomicRespPkt(respOpCode)
      } elsewhen (OpCode.isReadReqPkt(reqOpCode)) {
        result := OpCode.isReadRespPkt(respOpCode) ||
          OpCode.isNonReadAtomicRespPkt(respOpCode)
      } elsewhen (OpCode.isAtomicReqPkt(reqOpCode)) {
        result := OpCode.isAtomicRespPkt(respOpCode) ||
          OpCode.isNonReadAtomicRespPkt(respOpCode)
      } otherwise {
        report(
          message =
            L"${REPORT_TIME} time: reqOpCode=${reqOpCode} should be request opcode, respOpCode=${respOpCode}".toSeq,
          severity = FAILURE
        )
        result := False
      }
    }.result
}

//========== Partial retry related utilities ==========
object PartialRetry {
  def workReqRetry(
      qpAttr: QpAttrData,
      retryWorkReq: CachedWorkReq,
      retryWorkReqValid: Bool
  ) =
    new Composite(retryWorkReq, "PartialRetry_workReqRetry") {
      val curPsn = qpAttr.npsn // curPsn in SQ is nPSN
      val isRetryWholeWorkReq = PsnUtil
        .lte(qpAttr.retryStartPsn, retryWorkReq.psnStart, curPsn)
      // TODO: verify RNR will not partial retry
      val retryFromBeginning =
        (qpAttr.retryReason === RetryReason.SEQ_ERR) ? isRetryWholeWorkReq | True

      when(retryWorkReqValid && !retryFromBeginning) {
        assert(
          assertion = qpAttr.retryReason === RetryReason.SEQ_ERR,
          message =
            L"only NAK SEQ ERR can result in partial retry, but qpAttr.retryReason=${qpAttr.retryReason}".toSeq,
          severity = FAILURE
        )
      }

      val (
        retryStartPsn,
        retryDmaReadStartAddr,
        retryWorkReqRemoteStartAddr,
        retryWorkReqLocalStartAddr,
        retryDmaReadLenBytes,
        retryReqPktNum
      ) = retryHelper(
        qpAttr.pmtu,
        curPsn,
        retryWorkReqValid,
        retryFromBeginning,
        origReqPsnStart = retryWorkReq.psnStart,
        retryReqPsn = qpAttr.retryStartPsn,
        origReqPhysicalAddr = retryWorkReq.pa,
        origReqRemoteAddr = retryWorkReq.workReq.raddr,
        origReqLocalAddr = retryWorkReq.workReq.laddr,
        origReqLenBytes = retryWorkReq.workReq.lenBytes,
        origReqPktNum = retryWorkReq.pktNum
      )
      val result = (
        retryFromBeginning,
        retryStartPsn,
        retryDmaReadStartAddr,
        retryWorkReqRemoteStartAddr,
        retryWorkReqLocalStartAddr,
        retryReqPktNum,
        retryDmaReadLenBytes
      )
    }.result

  def readReqRetry(
      qpAttr: QpAttrData,
      retryReadReqPktFrag: RdmaDataPkt,
      retryReadReqRstCacheData: ReadAtomicRstCacheData,
      retryReadReqValid: Bool
  ) = new Composite(retryReadReqPktFrag, "PartialRetry_readReqRetry") {
    val curPsn = qpAttr.epsn // curPsn in RQ is ePSN
    val retryFromBeginning = PsnUtil.lte(
      retryReadReqPktFrag.bth.psn,
      retryReadReqRstCacheData.psnStart,
      curPsn
    )

    val (
      retryStartPsn,
      retryDmaReadStartAddr,
      _, // retryReqRemoteStartAddr,
      retryReqLocalStartAddr,
      retryDmaReadLenBytes,
      _ // retryReqPktNum
    ) = retryHelper(
      qpAttr.pmtu,
      curPsn,
      retryReadReqValid,
      retryFromBeginning,
      origReqPsnStart = retryReadReqRstCacheData.psnStart,
      retryReqPsn = retryReadReqPktFrag.bth.psn,
      origReqPhysicalAddr = retryReadReqRstCacheData.pa,
      origReqRemoteAddr = retryReadReqRstCacheData.va,
      origReqLocalAddr = retryReadReqRstCacheData.va,
      origReqLenBytes = retryReadReqRstCacheData.dlen,
      origReqPktNum = retryReadReqRstCacheData.pktNum
    )
    val result = (
      retryFromBeginning,
      retryStartPsn,
      retryDmaReadStartAddr,
//      retryReqRemoteStartAddr,
      retryReqLocalStartAddr,
      retryDmaReadLenBytes
    )
  }.result

  def retryHelper(
      qpAttrPmtu: UInt,
      curPsn: UInt,
      retryReqValid: Bool,
      retryFromBeginning: Bool,
      origReqPsnStart: UInt,
      retryReqPsn: UInt,
      origReqPhysicalAddr: UInt,
      origReqRemoteAddr: UInt,
      origReqLocalAddr: UInt,
      origReqLenBytes: UInt,
      origReqPktNum: UInt
  ) = new Composite(retryReqPsn, "PartialRetry_retryHelper") {
    val retryStartPsn = cloneOf(origReqPsnStart)
    retryStartPsn := origReqPsnStart
    val psnDiff = PsnUtil.diff(retryReqPsn, origReqPsnStart)
    val origReqPsnEndExclusive =
      origReqPsnStart + origReqPktNum // PSN module addition, overflow is fine
    val retryReqPktNum = cloneOf(origReqPktNum)
    retryReqPktNum := origReqPktNum - psnDiff
    val retryPhysicalAddr = cloneOf(origReqPhysicalAddr)
    retryPhysicalAddr := origReqPhysicalAddr
    val retryRemoteAddr = cloneOf(origReqRemoteAddr)
    retryRemoteAddr := origReqRemoteAddr
    val retryLocalAddr = cloneOf(origReqLocalAddr)
    retryLocalAddr := origReqLocalAddr
    val retryReqLenBytes = cloneOf(origReqLenBytes)
    retryReqLenBytes := origReqLenBytes
    when(retryReqValid && !retryFromBeginning) {
      assert(
        assertion = PsnUtil.lt(
          retryReqPsn,
          origReqPsnEndExclusive,
          curPsn
        ),
        message =
          L"${REPORT_TIME} time: retryReqPsn=${retryReqPsn} should < origReqPsnStart=${origReqPsnStart} + origReqPktNum=${origReqPktNum} = ${origReqPsnEndExclusive} in PSN order, curPsn=${curPsn}".toSeq,
        severity = FAILURE
      )
      val retryDmaReadOffset =
        (psnDiff << qpAttrPmtu).resize(RDMA_MAX_LEN_WIDTH)

      // Support partial retry
      retryStartPsn := retryReqPsn
      // TODO: handle PA offset with scatter-gather
      retryPhysicalAddr := origReqPhysicalAddr + retryDmaReadOffset
      retryRemoteAddr := origReqRemoteAddr + retryDmaReadOffset
      retryLocalAddr := origReqLocalAddr + retryDmaReadOffset
      retryReqLenBytes := origReqLenBytes - retryDmaReadOffset
//      val pktNum = computePktNum(retryWorkReq.workReq.lenBytes, qpAttr.pmtu)
      assert(
        assertion = psnDiff < origReqPktNum,
        message =
          L"${REPORT_TIME} time: psnDiff=${psnDiff} should < origReqPktNum=${origReqPktNum}, retryReqPsn=${retryReqPsn}, origReqPsnStart=${origReqPsnStart}, curPsn=${curPsn}".toSeq,
        severity = FAILURE
      )
//      report(
//        L"${REPORT_TIME} time: retryReqPsn=${retryReqPsn}, origReqPsnStart=${origReqPsnStart}, psnDiff=${psnDiff}, qpAttrPmtu=${qpAttrPmtu}, retryDmaReadOffset=${retryDmaReadOffset}, pktLen=${origReqLenBytes}, pa=${origReqPhysicalAddr}, rmtAddr=${origReqRemoteAddr}, retryReqLenBytes=${retryReqLenBytes}, retryPhysicalAddr=${retryPhysicalAddr}, retryRemoteAddr=${retryRemoteAddr}"
//      )
    }

    val result = (
      retryStartPsn,
      retryPhysicalAddr,
      retryRemoteAddr,
      retryLocalAddr,
      retryReqLenBytes,
      retryReqPktNum
    )
  }.result
}

object reqRespOpCodeHeaderLen {
  def apply(opcode: Bits): UInt =
    new Composite(opcode, "OpCodeHeaderLen") {
      val bthLenBytes = widthOf(BTH()) / BYTE_WIDTH
      val rethLenBytes = widthOf(RETH()) / BYTE_WIDTH
      val immDtLenBytes = widthOf(ImmDt()) / BYTE_WIDTH
      val iethLenBytes = widthOf(IETH()) / BYTE_WIDTH
      val atomicEthLenBytes = widthOf(AtomicEth()) / BYTE_WIDTH
      val aethLenBytes = widthOf(AETH()) / BYTE_WIDTH
      val atomicAckEthLenBytes = widthOf(AtomicAckEth()) / BYTE_WIDTH

      val result = UInt(MAX_HEADER_WIDTH bits)
      switch(opcode) {
        is(
          OpCode.SEND_FIRST.id,
          OpCode.SEND_MIDDLE.id,
          OpCode.SEND_LAST.id,
          OpCode.SEND_ONLY.id
        ) {
          result := bthLenBytes
        }
        is(
          OpCode.SEND_LAST_WITH_IMMEDIATE.id,
          OpCode.SEND_ONLY_WITH_IMMEDIATE.id
        ) {
          result := bthLenBytes + immDtLenBytes
        }
        is(
          OpCode.SEND_LAST_WITH_INVALIDATE.id,
          OpCode.SEND_ONLY_WITH_INVALIDATE.id
        ) {
          result := bthLenBytes + iethLenBytes
        }
        is(
          OpCode.RDMA_WRITE_FIRST.id,
          OpCode.RDMA_WRITE_ONLY.id
        ) {
          result := bthLenBytes + rethLenBytes
        }
        is(
          OpCode.RDMA_WRITE_MIDDLE.id,
          OpCode.RDMA_WRITE_LAST.id
        ) {
          result := bthLenBytes
        }
        is(OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE.id) {
          result := bthLenBytes + immDtLenBytes
        }
        is(OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE.id) {
          result := bthLenBytes + rethLenBytes + immDtLenBytes
        }
        is(OpCode.RDMA_READ_REQUEST.id) {
          result := bthLenBytes + rethLenBytes
        }
        is(OpCode.COMPARE_SWAP.id, OpCode.FETCH_ADD.id) {
          result := bthLenBytes + atomicEthLenBytes
        }
        is(OpCode.ACKNOWLEDGE.id) {
          result := bthLenBytes
        }
        is(
          OpCode.RDMA_READ_RESPONSE_FIRST.id,
          OpCode.RDMA_READ_RESPONSE_LAST.id,
          OpCode.RDMA_READ_RESPONSE_ONLY.id
        ) {
          result := bthLenBytes + aethLenBytes
        }
        is(OpCode.RDMA_READ_RESPONSE_MIDDLE.id) {
          result := bthLenBytes
        }
        is(OpCode.ATOMIC_ACKNOWLEDGE.id) {
          result := bthLenBytes + aethLenBytes + atomicAckEthLenBytes
        }
        default {
          report(
            message =
              L"${REPORT_TIME} time: illegal opcode=${opcode}, must be send/write/read/atomic request or response".toSeq,
            severity = ERROR
          )
          result := 0
        }
      }
    }.result
}

object ReqRespTotalLenCalculator {
  def apply(
      flush: Bool,
      pktFireFlow: Flow[Fragment[LenCheckElements]],
      pmtuLenBytes: UInt
  ) = new Composite(pktFireFlow, "ReqTotalLenCalculator") {
    val fire = pktFireFlow.valid
    val opcode = pktFireFlow.opcode
    val padCnt = pktFireFlow.padCnt
    val mty = pktFireFlow.mty
    val isFirstFrag = pktFireFlow.isFirst
    val isLastFrag = pktFireFlow.isLast

    val isFirstPkt =
      OpCode.isFirstReqPkt(opcode) || OpCode.isFirstReadRespPkt(opcode)
    val isMidPkt = OpCode.isMidReqPkt(opcode) || OpCode.isMidReadRespPkt(opcode)
    val isLastPkt =
      OpCode.isLastReqPkt(opcode) || OpCode.isLastReadRespPkt(opcode)
    val isOnlyPkt =
      OpCode.isOnlyReqPkt(opcode) || OpCode.isOnlyReadRespPkt(opcode)

    val concat = isFirstPkt ## isMidPkt ## isLastPkt ## isOnlyPkt
    val width = log2Up(widthOf(concat) + 1)
    when(fire) {
      assert(
        assertion = CountOne(concat) === 1,
        message =
          L"${REPORT_TIME} time: illegal opcode=${opcode}, isFirstPkt=${isFirstPkt}, isMidPkt=${isMidPkt}, isLastPkt=${isLastPkt}, isOnlyPkt=${isOnlyPkt}, should be first/middle/last/only request/response".toSeq,
        severity = FAILURE
      )

//      report(
//        L"${REPORT_TIME} time: opcode=${pktFireFlow.opcode}, PSN=${pktFireFlow.psn}, mty=${pktFireFlow.mty}, padCnt=${pktFireFlow.padCnt}"
//      )
    }

    val firstPkt = U(0, width bits)
    val midPkt = U(1, width bits)
    val lastPkt = U(2, width bits)
    val onlyPkt = U(3, width bits)
    val illegalPkt = U(4, width bits)
    val isFirstMidLastOnlyPkt = concat.mux(
      8 -> firstPkt,
      4 -> midPkt,
      2 -> lastPkt,
      1 -> onlyPkt,
      default -> illegalPkt
    )

    val pktHeaderLenBytes = reqRespOpCodeHeaderLen(opcode)
//    val firstPktLenAdjust = firstPktHeaderLenFunc(opcode)
//    val midPktLenAdjust = midPktHeaderLenFunc(opcode)
//    val lastPktLenAdjust = lastPktHeaderLenFunc(opcode)
//    val onlyPktLenAdjust = onlyPktHeaderLenFunc(opcode)

    val pktLenBytesReg = RegInit(U(0, RDMA_MAX_LEN_WIDTH bits))
    val totalLenBytesReg = RegInit(U(0, RDMA_MAX_LEN_WIDTH bits))

    val pktFragLenBytes = CountOne(mty).resize(RDMA_MAX_LEN_WIDTH)
    val isPktLenCheckErr = False

    val totalLenValid = False
    val totalLenOutput = UInt(RDMA_MAX_LEN_WIDTH bits)
    totalLenOutput := totalLenBytesReg
    val totalLenOutFlow = Flow(LenCheckResult())
    totalLenOutFlow.valid := totalLenValid
    totalLenOutFlow.totalLenOutput := totalLenOutput
    totalLenOutFlow.isPktLenCheckErr := isPktLenCheckErr

    def checkPmtuAndPadCnt4FirstOrMidPkt(pktLenBytes: UInt, padCnt: UInt) = {
      pktLenBytes === pmtuLenBytes &&
      padCnt === 0
    }
    def checkPadCnt4LastOrOnlyPkt(totalLenBytes: UInt, padCnt: UInt) = {
      totalLenBytes((PAD_COUNT_WIDTH - 1) downto 0) ===
        (PAD_COUNT_FULL - padCnt).resize(PAD_COUNT_WIDTH)
    }
    // Calculate request/response total length
    when(fire) {
      switch(isFirstFrag ## isLastFrag) {
        is(True ## False) { // First fragment
          switch(isFirstMidLastOnlyPkt) {
            is(firstPkt) {
              totalLenBytesReg := 0

              pktLenBytesReg := pktFragLenBytes - pktHeaderLenBytes
            }
            is(midPkt) {
              pktLenBytesReg := pktFragLenBytes - pktHeaderLenBytes
            }
            is(lastPkt) {
              pktLenBytesReg := pktFragLenBytes - pktHeaderLenBytes - padCnt
            }
            is(onlyPkt) {
              totalLenBytesReg := 0

              pktLenBytesReg := pktFragLenBytes - pktHeaderLenBytes - padCnt
            }
            default {
              report(
                message =
                  L"illegal packet opcode=${opcode}, should be first/middle/last/only read response, when fire=${fire}, isFirstFrag=${isFirstFrag}, isLastFrag=${isLastFrag}".toSeq,
                severity = FAILURE
              )
            }
          }
        }
        is(False ## True) { // Last fragment
          pktLenBytesReg := 0

          switch(isFirstMidLastOnlyPkt) {
            is(firstPkt) {
              val pktLenBytes = pktLenBytesReg + pktFragLenBytes
              totalLenBytesReg := pktLenBytes
              isPktLenCheckErr := !checkPmtuAndPadCnt4FirstOrMidPkt(
                pktLenBytes,
                padCnt
              )
            }
            is(midPkt) {
              val pktLenBytes = pktLenBytesReg + pktFragLenBytes
              totalLenBytesReg := totalLenBytesReg + pktLenBytes
              isPktLenCheckErr := !checkPmtuAndPadCnt4FirstOrMidPkt(
                pktLenBytes,
                padCnt
              )
            }
            is(lastPkt) {
              totalLenBytesReg := 0
              val totalLenBytes =
                totalLenBytesReg + pktLenBytesReg + pktFragLenBytes
              isPktLenCheckErr := !checkPadCnt4LastOrOnlyPkt(
                totalLenBytes,
                padCnt
              )
              totalLenValid := True
              totalLenOutput := totalLenBytes
            }
            is(onlyPkt) {
              totalLenBytesReg := 0
              val totalLenBytes = pktLenBytesReg + pktFragLenBytes
              isPktLenCheckErr := !checkPadCnt4LastOrOnlyPkt(
                totalLenBytes,
                padCnt
              )
              totalLenValid := True
              totalLenOutput := totalLenBytes
            }
            default {
              report(
                message =
                  L"illegal packet opcode=${opcode}, should be first/middle/last/only read response, when fire=${fire}, isFirstFrag=${isFirstFrag}, isLastFrag=${isLastFrag}".toSeq,
                severity = FAILURE
              )
            }
          }
        }
        is(True ## True) { // Single fragment, both first and last
          totalLenBytesReg := 0
          pktLenBytesReg := 0

          switch(isFirstMidLastOnlyPkt) {
            is(onlyPkt) {
              val totalLenBytes = pktFragLenBytes - pktHeaderLenBytes - padCnt
              isPktLenCheckErr := !checkPadCnt4LastOrOnlyPkt(
                totalLenBytes,
                padCnt
              )
              totalLenValid := True
              totalLenOutput := totalLenBytes
            }
            is(lastPkt) {
              val totalLenBytes =
                totalLenBytesReg + pktFragLenBytes - pktHeaderLenBytes - padCnt
              isPktLenCheckErr := !checkPadCnt4LastOrOnlyPkt(
                totalLenBytes,
                padCnt
              )
              totalLenValid := True
              totalLenOutput := totalLenBytes
            }
            is(firstPkt, midPkt) {
              report(
                message =
                  L"${REPORT_TIME} time: first or middle request/response packet length check failed, opcode=${opcode}, when isFirstFrag=${isFirstFrag} and isLastFrag=${isLastFrag}".toSeq,
                severity = FAILURE // TODO: change to NOTE
              )

              isPktLenCheckErr := True
            }
            default {
              report(
                message =
                  L"illegal packet opcode=${opcode}, should be first/middle/last/only read response, when fire=${fire}, isFirstFrag=${isFirstFrag}, isLastFrag=${isLastFrag}".toSeq,
                severity = FAILURE
              )
            }
          }
        }
        is(False ## False) { // Middle fragment
          pktLenBytesReg := pktLenBytesReg + pktFragLenBytes
        }
      }
    }
    val result = totalLenOutFlow

    when(flush) {
      pktLenBytesReg := 0
      totalLenBytesReg := 0
    }
  }.result
}

object AddrCacheQueryAndRespHandler {
  def apply[Tin <: Data](
      inputFragStream: Stream[Fragment[Tin]],
      addrCacheRead: QpAddrCacheAgentReadBus,
      inputAsRdmaDataPktFrag: Tin => RdmaDataPkt,
      buildAddrCacheQuery: Stream[Fragment[Tin]] => QpAddrCacheAgentReadReq,
      addrCacheQueryCond: Stream[Fragment[Tin]] => Bool,
      expectAddrCacheResp: Stream[Fragment[Tin]] => Bool,
      addrCacheQueryRespJoinCond: Stream[Fragment[Tin]] => Bool
  ) = new Composite(inputFragStream, "AddrCacheQueryAndRespHandler") {
    val joinStream = FragmentStreamForkQueryJoinResp(
      inputFragStream,
      addrCacheRead.req,
      addrCacheRead.resp,
      waitQueueDepth = ADDR_CACHE_QUERY_FIFO_DEPTH,
      buildQuery = buildAddrCacheQuery,
      queryCond = addrCacheQueryCond,
      expectResp = expectAddrCacheResp,
      joinRespCond = addrCacheQueryRespJoinCond
    )

    val inputValid = joinStream.valid
    val inputPktFrag = inputAsRdmaDataPktFrag(joinStream._1)

    val addrCacheRespValid = joinStream._2
    val addrCacheQueryResp = joinStream._3
    val sizeValid = addrCacheQueryResp.sizeValid
    val keyValid = addrCacheQueryResp.keyValid
    val accessValid = addrCacheQueryResp.accessValid
    val checkAddrCacheRespMatchCond =
      joinStream.isFirst && addrCacheRespValid &&
        OpCode.isFirstOrOnlyReqPkt(inputPktFrag.bth.opcode)
    when(inputValid && checkAddrCacheRespMatchCond) {
      assert(
        assertion = inputPktFrag.bth.psn === addrCacheQueryResp.psn,
        message =
          L"${REPORT_TIME} time: addrCacheReadResp.resp has PSN=${addrCacheQueryResp.psn} not match query PSN=${inputPktFrag.bth.psn}".toSeq,
        severity = FAILURE
      )
    }

    val bufLenErr = inputValid && addrCacheRespValid && !sizeValid
    val keyErr = inputValid && addrCacheRespValid && !keyValid
    val accessErr = inputValid && addrCacheRespValid && !accessValid
    val addrCheckErr =
      inputValid && addrCacheRespValid && (keyErr || bufLenErr || accessErr)

    val result = (joinStream, bufLenErr, keyErr, accessErr, addrCheckErr)
  }.result
}

//========== TupleBundle related utilities ==========

// TODO: remove TupleBundle, once SpinalHDL updated
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
