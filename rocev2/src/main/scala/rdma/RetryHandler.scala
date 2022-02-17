package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._
// pp.282 spec 1.4
// The retried request may only reread those portions that were not
// successfully responded to the first time.
//
// Any retried request must correspond exactly to a subset of the
// original RDMA READ request in such a manner that all potential
// duplicate response packets must have identical payload data and PSNs
//
// INCONSISTENT: retried requests are not in PSN order
class RetryHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val retryWorkReq = slave(Stream(CachedWorkReq()))
//    val workReqQueryPort4DupDmaReadResp = master(WorkReqCacheQueryBus())
    val dmaRead = master(DmaReadBus(busWidth))
    val txSendReq = master(RdmaDataBus(busWidth))
    val txWriteReq = master(RdmaDataBus(busWidth))
    val txReadReq = master(Stream(ReadReq()))
    val txAtomicReq = master(Stream(AtomicReq()))
  }

  // TODO: handle go-back-to-N retry, not just retry one
  val readAtomicRetryHandlerAndDmaReadInitiator =
    new ReadAtomicRetryHandlerAndDmaReadInitiator
  readAtomicRetryHandlerAndDmaReadInitiator.io.qpAttr := io.qpAttr
  readAtomicRetryHandlerAndDmaReadInitiator.io.sendQCtrl := io.sendQCtrl
  readAtomicRetryHandlerAndDmaReadInitiator.io.retryWorkReq << io.retryWorkReq
//  readAtomicRetryHandlerAndDmaReadInitiator.io.workReqCacheEmpty := io.workReqCacheEmpty
//  readAtomicRetryHandlerAndDmaReadInitiator.io.rx << io.rx
//  io.workReqQueryPort4DupReq << readAtomicRetryHandlerAndDmaReadInitiator.io.workReqQuery
  io.dmaRead.req << readAtomicRetryHandlerAndDmaReadInitiator.io.dmaRead.req

  val sqDmaReadRespHandler = new SqDmaReadRespHandler(busWidth)
  sqDmaReadRespHandler.io.sendQCtrl := io.sendQCtrl
  sqDmaReadRespHandler.io.dmaReadResp.resp << io.dmaRead.resp
  sqDmaReadRespHandler.io.cachedWorkReq << readAtomicRetryHandlerAndDmaReadInitiator.io.outRetryWorkReq
//  io.workReqQueryPort4DupDmaReadResp << sqDmaReadRespHandler.io.workReqQuery

  val sendWriteReqSegment = new SendWriteReqSegment(busWidth)
  sendWriteReqSegment.io.qpAttr := io.qpAttr
  sendWriteReqSegment.io.sendQCtrl := io.sendQCtrl
  sendWriteReqSegment.io.cachedWorkReqAndDmaReadResp << sqDmaReadRespHandler.io.cachedWorkReqAndDmaReadResp

  val sendReqGenerator = new SendReqGenerator(busWidth)
  sendReqGenerator.io.qpAttr := io.qpAttr
  sendReqGenerator.io.sendQCtrl := io.sendQCtrl
  sendReqGenerator.io.cachedWorkReqAndDmaReadResp << sendWriteReqSegment.io.sendCachedWorkReqAndDmaReadResp

  val writeReqGenerator = new WriteReqGenerator(busWidth)
  writeReqGenerator.io.qpAttr := io.qpAttr
  writeReqGenerator.io.sendQCtrl := io.sendQCtrl
  writeReqGenerator.io.cachedWorkReqAndDmaReadResp << sendWriteReqSegment.io.writeCachedWorkReqAndDmaReadResp

  io.txSendReq << sendReqGenerator.io.txReq
  io.txWriteReq << writeReqGenerator.io.txReq
  io.txAtomicReq << readAtomicRetryHandlerAndDmaReadInitiator.io.txAtomicReqRetry
  io.txReadReq << readAtomicRetryHandlerAndDmaReadInitiator.io.txReadReqRetry
}

// TODO: retry does not support fence
class ReadAtomicRetryHandlerAndDmaReadInitiator extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val retryWorkReq = slave(Stream(CachedWorkReq()))
    val txReadReqRetry = master(Stream(ReadReq()))
    val txAtomicReqRetry = master(Stream(AtomicReq()))
    val dmaRead = master(DmaReadReqBus())
    val outRetryWorkReq = master(Stream(CachedWorkReq()))
  }

  val retryWorkReqValid = io.retryWorkReq.valid
  val retryWorkReq = io.retryWorkReq.payload
  val isSendWorkReq = WorkReqOpCode.isSendReq(retryWorkReq.workReq.opcode)
  val isWriteWorkReq = WorkReqOpCode.isWriteReq(retryWorkReq.workReq.opcode)
  val isReadWorkReq = WorkReqOpCode.isReadReq(retryWorkReq.workReq.opcode)
  val isAtomicWorkReq = WorkReqOpCode.isAtomicReq(retryWorkReq.workReq.opcode)

  val (retryWorkReq4Out, retryWork4Dma) = StreamFork2(
    io.retryWorkReq.throwWhen(io.sendQCtrl.wrongStateFlush)
  )
  io.outRetryWorkReq <-/< retryWorkReq4Out

  val (sendWriteWorkReqIdx, readWorkReqIdx, atomicWorkReqIdx, otherWorkReqIdx) =
    (0, 1, 2, 3)
  val txSel = UInt(2 bits)
  when(isSendWorkReq || isWriteWorkReq) {
    txSel := sendWriteWorkReqIdx
  } elsewhen (isReadWorkReq) {
    txSel := readWorkReqIdx
  } elsewhen (isAtomicWorkReq) {
    txSel := atomicWorkReqIdx
  } otherwise {
    txSel := otherWorkReqIdx
  }
  when(retryWorkReqValid) {
    assert(
      assertion =
        isSendWorkReq || isWriteWorkReq || isReadWorkReq || isAtomicWorkReq,
      message =
        L"${REPORT_TIME} time: the work request to retry is not send/write/read/atomic, WR opcode=${retryWorkReq.workReq.opcode}",
      severity = FAILURE
    )
  }

  val fourStreams = StreamDemux(retryWork4Dma, select = txSel, portCount = 4)
  // Just discard non-send/write/read/atomic WR
  StreamSink(NoData) << fourStreams(otherWorkReqIdx).translateWith(NoData)

  io.txAtomicReqRetry <-/< fourStreams(atomicWorkReqIdx).translateWith {
    val isCompSwap =
      retryWorkReq.workReq.opcode === WorkReqOpCode.ATOMIC_CMP_AND_SWP
    val rslt = AtomicReq().set(
      isCompSwap,
      dqpn = io.qpAttr.dqpn,
      psn = retryWorkReq.psnStart,
      va = retryWorkReq.workReq.raddr,
      rkey = retryWorkReq.workReq.rkey,
      comp = retryWorkReq.workReq.comp,
      swap = retryWorkReq.workReq.swap
    )
    rslt
  }

  val isRetryWholeWorkReq = PsnUtil
    .lte(io.qpAttr.retryStartPsn, retryWorkReq.psnStart, io.qpAttr.npsn)
  val retryFromBeginning =
    (io.qpAttr.retryReason === RetryReason.RETRY_ACK) ? isRetryWholeWorkReq | True
  // For partial read retry, compute the partial read DMA length
  val psnDiff = PsnUtil.diff(io.qpAttr.retryStartPsn, retryWorkReq.psnStart)
  val retryStartPsn = cloneOf(retryWorkReq.psnStart)
  retryStartPsn := retryWorkReq.psnStart
  val retryDmaReadStartAddr = cloneOf(retryWorkReq.pa)
  retryDmaReadStartAddr := retryWorkReq.pa
  val retryReadReqStartAddr = cloneOf(retryWorkReq.workReq.raddr)
  retryReadReqStartAddr := retryWorkReq.workReq.raddr
  val retryDmaReadLenBytes = cloneOf(retryWorkReq.workReq.lenBytes)
  retryDmaReadLenBytes := retryWorkReq.workReq.lenBytes
  when(retryWorkReqValid && !retryFromBeginning) {
    assert(
      assertion = PsnUtil
        .lt(
          io.qpAttr.retryStartPsn,
          retryWorkReq.psnStart + retryWorkReq.pktNum,
          io.qpAttr.npsn
        ),
      message =
        L"${REPORT_TIME} time: io.qpAttr.retryStartPsn=${io.qpAttr.retryStartPsn} should < retryWorkReq.psnStart=${retryWorkReq.psnStart} + retryWorkReq.pktNum=${retryWorkReq.pktNum} = ${retryWorkReq.psnStart + retryWorkReq.pktNum} in PSN order",
      severity = FAILURE
    )
    val retryDmaReadOffset =
      (psnDiff << io.qpAttr.pmtu.asUInt).resize(RDMA_MAX_LEN_WIDTH)

    // Support partial retry
    retryStartPsn := io.qpAttr.retryStartPsn
    // TODO: handle PA offset with scatter-gather
    retryDmaReadStartAddr := retryWorkReq.pa + retryDmaReadOffset
    retryReadReqStartAddr := retryWorkReq.workReq.raddr + retryDmaReadOffset
    retryDmaReadLenBytes := retryWorkReq.workReq.lenBytes - retryDmaReadOffset
//    val pktNum = computePktNum(retryWorkReq.workReq.lenBytes, io.qpAttr.pmtu)
    val pktNum = retryWorkReq.pktNum
    assert(
      assertion = psnDiff < pktNum,
      message =
        L"${REPORT_TIME} time: psnDiff=${psnDiff} should < pktNum=${pktNum}, io.qpAttr.retryStartPsn=${io.qpAttr.retryStartPsn}, retryWorkReq.psnStart=${retryWorkReq.psnStart}, io.qpAttr.npsn=${io.qpAttr.npsn}, io.retryWorkReq.workReq.opcode=${io.retryWorkReq.workReq.opcode}",
      severity = FAILURE
    )
  }
  io.txReadReqRetry <-/< fourStreams(readWorkReqIdx).translateWith {
    val rslt = ReadReq().set(
      dqpn = io.qpAttr.dqpn,
      psn = retryStartPsn,
      va = retryReadReqStartAddr,
      rkey = retryWorkReq.workReq.rkey,
      dlen = retryDmaReadLenBytes
    )
    rslt
  }

  io.dmaRead.req <-/< fourStreams(sendWriteWorkReqIdx).translateWith {
    val rslt = cloneOf(io.dmaRead.req.payloadType)
    rslt.set(
      initiator = DmaInitiator.SQ_DUP,
      sqpn = retryWorkReq.workReq.sqpn,
      psnStart = retryStartPsn,
      addr = retryDmaReadStartAddr,
      lenBytes = retryDmaReadLenBytes
    )
  }
}

class SqDmaReadRespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    // val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val cachedWorkReq = slave(Stream(CachedWorkReq()))
//    val workReqQuery = master(WorkReqCacheQueryBus())
    val dmaReadResp = slave(DmaReadRespBus(busWidth))
    val cachedWorkReqAndDmaReadResp = master(
      Stream(Fragment(CachedWorkReqAndDmaReadResp(busWidth)))
    )
  }

  val handlerOutput = DmaReadRespHandler(
    io.cachedWorkReq,
    io.dmaReadResp,
    io.sendQCtrl.wrongStateFlush,
    busWidth,
    isReqZeroDmaLen = (req: CachedWorkReq) => req.workReq.lenBytes === 0
  )
  io.cachedWorkReqAndDmaReadResp <-/< handlerOutput.translateWith {
    val rslt = cloneOf(io.cachedWorkReqAndDmaReadResp)
    rslt.dmaReadResp := handlerOutput.dmaReadResp
    rslt.cachedWorkReq := handlerOutput.req
    rslt.last := handlerOutput.isLast
    rslt
  }
}

class SendWriteReqSegment(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val cachedWorkReqAndDmaReadResp = slave(
      Stream(Fragment(CachedWorkReqAndDmaReadResp(busWidth)))
    )
    val sendCachedWorkReqAndDmaReadResp = master(
      Stream(Fragment(CachedWorkReqAndDmaReadResp(busWidth)))
    )
    val writeCachedWorkReqAndDmaReadResp = master(
      Stream(Fragment(CachedWorkReqAndDmaReadResp(busWidth)))
    )
  }

  val segmentOut = DmaReadRespSegment(
    io.cachedWorkReqAndDmaReadResp,
    io.sendQCtrl.wrongStateFlush,
    io.qpAttr.pmtu,
    busWidth,
    isReqZeroDmaLen = (reqAndDmaReadResp: CachedWorkReqAndDmaReadResp) =>
      reqAndDmaReadResp.cachedWorkReq.workReq.lenBytes === 0
  )
  val cachedWorkReq = segmentOut.cachedWorkReq
  val isSendWorkReq = WorkReqOpCode.isSendReq(cachedWorkReq.workReq.opcode)
  val isWriteWorkReq = WorkReqOpCode.isWriteReq(cachedWorkReq.workReq.opcode)

  val (sendWorkReqIdx, writeWorkReqIdx, otherWorkReqIdx) =
    (0, 1, 2)
  val txSel = UInt(2 bits)
  when(isSendWorkReq) {
    txSel := sendWorkReqIdx
  } elsewhen (isWriteWorkReq) {
    txSel := writeWorkReqIdx
  } otherwise {
    txSel := otherWorkReqIdx
  }
  when(segmentOut.valid) {
    assert(
      assertion = isSendWorkReq || isWriteWorkReq,
      message =
        L"${REPORT_TIME} time: the WR for retry here should be send/write, but WR opcode=${cachedWorkReq.workReq.opcode}",
      severity = FAILURE
    )
  }

  val threeStreams = StreamDemux(segmentOut, select = txSel, portCount = 3)
  // Just discard non-send/write WR
  StreamSink(NoData) << threeStreams(otherWorkReqIdx).translateWith(NoData)

  io.sendCachedWorkReqAndDmaReadResp <-/< threeStreams(sendWorkReqIdx)
    .throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt = cloneOf(io.sendCachedWorkReqAndDmaReadResp.payloadType)
      rslt.dmaReadResp := threeStreams(sendWorkReqIdx).dmaReadResp
      rslt.cachedWorkReq := threeStreams(sendWorkReqIdx).cachedWorkReq
      rslt.last := threeStreams(sendWorkReqIdx).isLast
      rslt
    }
  io.writeCachedWorkReqAndDmaReadResp <-/< threeStreams(writeWorkReqIdx)
    .throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt = cloneOf(io.writeCachedWorkReqAndDmaReadResp.payloadType)
      rslt.dmaReadResp := threeStreams(writeWorkReqIdx).dmaReadResp
      rslt.cachedWorkReq := threeStreams(writeWorkReqIdx).cachedWorkReq
      rslt.last := threeStreams(writeWorkReqIdx).isLast
      rslt
    }
}

abstract class SendWriteReqGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val txReq = master(RdmaDataBus(busWidth))
    val cachedWorkReqAndDmaReadResp = slave(
      Stream(Fragment(CachedWorkReqAndDmaReadResp(busWidth)))
    )
  }

  val busWidthBytes = busWidth.id / BYTE_WIDTH

  val input = io.cachedWorkReqAndDmaReadResp
  when(input.valid) {
    assert(
      assertion =
        input.cachedWorkReq.workReq.lenBytes === input.dmaReadResp.lenBytes,
      message =
        L"${REPORT_TIME} time: input.cachedWorkReq.workReq.lenBytes=${input.cachedWorkReq.workReq.lenBytes} should equal input.dmaReadResp.lenBytes=${input.dmaReadResp.lenBytes}",
      severity = FAILURE
    )
  }

  val reqAndDmaReadRespSegment = input.translateWith {
    val rslt =
      Fragment(ReqAndDmaReadResp(CachedWorkReq(), busWidth))
    rslt.dmaReadResp := input.dmaReadResp
    rslt.req := input.cachedWorkReq
    rslt.last := input.isLast
    rslt
  }

  def headerGenFunc(
      inputReq: CachedWorkReq,
      inputDmaDataFrag: DmaReadResp,
      curReqPktCntVal: UInt,
      qpAttr: QpAttrData
  ): CombineHeaderAndDmaRespInternalRslt

  val combinerOutput = CombineHeaderAndDmaResponse(
    reqAndDmaReadRespSegment,
    io.qpAttr,
    io.sendQCtrl.wrongStateFlush,
    busWidth,
    headerGenFunc
  )
  io.txReq.pktFrag <-/< combinerOutput.pktFrag
}

class SendReqGenerator(busWidth: BusWidth)
    extends SendWriteReqGenerator(busWidth) {
  val isSendReq = WorkReqOpCode.isSendReq(
    io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode
  )
  when(io.cachedWorkReqAndDmaReadResp.valid) {
    assert(
      assertion = isSendReq,
      message =
        L"${REPORT_TIME} time: the WR opcode=${io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode} should be send request",
      severity = FAILURE
    )
  }

  override def headerGenFunc(
      inputReq: CachedWorkReq,
      inputDmaDataFrag: DmaReadResp,
      curReqPktCntVal: UInt,
      qpAttr: QpAttrData
  ) =
    new Composite(this) {
      val inputCachedWorkReq = inputReq.workReq

      val numReqPkt = inputReq.pktNum
      val lastOrOnlyReqPktLenBytes =
        moduloByPmtu(inputDmaDataFrag.lenBytes, qpAttr.pmtu)

      val workReqHasImmDt = WorkReqOpCode.hasImmDt(inputCachedWorkReq.opcode)
      val workReqHasIeth = WorkReqOpCode.hasIeth(inputCachedWorkReq.opcode)
      val isFromFirstReqPkt =
        inputDmaDataFrag.psnStart === inputReq.psnStart

      val curPsn = inputDmaDataFrag.psnStart + curReqPktCntVal
      val opcode = Bits(OPCODE_WIDTH bits)
      val padcount = U(0, PADCOUNT_WIDTH bits)

      val bth = BTH().set(
        opcode = opcode,
        padcount = padcount,
        dqpn = qpAttr.dqpn,
        psn = curPsn
      )
      val immDt = ImmDt()
      immDt.data := inputCachedWorkReq.immDtOrRmtKeyToInv
      val ieth = IETH()
      ieth.rkey := inputCachedWorkReq.immDtOrRmtKeyToInv
      require(
        widthOf(immDt) == widthOf(ieth),
        s"widthOf(immDt)=${widthOf(immDt)} should equal widthOf(ieth)=${widthOf(ieth)}"
      )
      val bthMty = Bits(widthOf(bth) / BYTE_WIDTH bits).setAll()
      val sendEthMty = Bits(widthOf(immDt) / BYTE_WIDTH bits).setAll()

      val headerBits = Bits(busWidth.id bits)
      val headerMtyBits = Bits(busWidthBytes bits)
      // TODO: verify endian
      headerBits := bth.asBits.resize(busWidth.id)
      headerMtyBits := bthMty.resize(busWidthBytes)
      when(numReqPkt > 1) {
        when(curReqPktCntVal === 0) {
          when(isFromFirstReqPkt) {
            opcode := OpCode.SEND_FIRST.id
          } otherwise {
            opcode := OpCode.SEND_MIDDLE.id
          }

        } elsewhen (curReqPktCntVal === numReqPkt - 1) { // Last request
          opcode := OpCode.SEND_LAST.id

          when(workReqHasImmDt) {
            opcode := OpCode.SEND_LAST_WITH_IMMEDIATE.id

            // TODO: verify endian
            headerBits := (bth ## immDt).resize(busWidth.id)
            headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
          } elsewhen (workReqHasIeth) {
            opcode := OpCode.SEND_LAST_WITH_INVALIDATE.id

            // TODO: verify endian
            headerBits := (bth ## ieth).resize(busWidth.id)
            headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
          }

          padcount := (PADCOUNT_FULL -
            lastOrOnlyReqPktLenBytes(0, PADCOUNT_WIDTH bits))
            .resize(PADCOUNT_WIDTH)
        } otherwise { // Middle request
          opcode := OpCode.SEND_MIDDLE.id
        }
      } otherwise { // Last or only request
        when(isFromFirstReqPkt) {
          opcode := OpCode.SEND_ONLY.id
        } otherwise {
          opcode := OpCode.SEND_LAST.id
        }

        when(workReqHasImmDt) {
          when(isFromFirstReqPkt) {
            opcode := OpCode.SEND_ONLY_WITH_IMMEDIATE.id
          } otherwise {
            opcode := OpCode.SEND_LAST_WITH_IMMEDIATE.id
          }

          // TODO: verify endian
          headerBits := (bth ## immDt).resize(busWidth.id)
          headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
        } elsewhen (workReqHasIeth) {
          when(isFromFirstReqPkt) {
            opcode := OpCode.SEND_ONLY_WITH_INVALIDATE.id
          } otherwise {
            opcode := OpCode.SEND_LAST_WITH_INVALIDATE.id
          }

          // TODO: verify endian
          headerBits := (bth ## ieth).resize(busWidth.id)
          headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
        }

        padcount := (PADCOUNT_FULL -
          lastOrOnlyReqPktLenBytes(0, PADCOUNT_WIDTH bits))
          .resize(PADCOUNT_WIDTH)
      }

      val rslt = CombineHeaderAndDmaRespInternalRslt(busWidth).set(
        numReqPkt,
        bth,
        headerBits,
        headerMtyBits
      )
    }.rslt
}

class WriteReqGenerator(busWidth: BusWidth)
    extends SendWriteReqGenerator(busWidth) {
  val isWriteReq = WorkReqOpCode.isWriteReq(
    io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode
  )
  when(io.cachedWorkReqAndDmaReadResp.valid) {
    assert(
      assertion = isWriteReq,
      message =
        L"${REPORT_TIME} time: the WR opcode=${io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode} should be write request",
      severity = FAILURE
    )
  }

  override def headerGenFunc(
      inputReq: CachedWorkReq,
      inputDmaDataFrag: DmaReadResp,
      curReqPktCntVal: UInt,
      qpAttr: QpAttrData
  ): CombineHeaderAndDmaRespInternalRslt =
    new Composite(this) {
      val inputCachedWorkReq = inputReq.workReq

      val numReqPkt = inputReq.pktNum
      val lastOrOnlyReqPktLenBytes =
        moduloByPmtu(inputDmaDataFrag.lenBytes, qpAttr.pmtu)

      val workReqHasImmDt = WorkReqOpCode.hasImmDt(inputCachedWorkReq.opcode)
      val isFromFirstReqPkt =
        inputDmaDataFrag.psnStart === inputReq.psnStart

      val curPsn = inputDmaDataFrag.psnStart + curReqPktCntVal
      val opcode = Bits(OPCODE_WIDTH bits)
      val padcount = U(0, PADCOUNT_WIDTH bits)

      val bth = BTH().set(
        opcode = opcode,
        padcount = padcount,
        dqpn = qpAttr.dqpn,
        psn = curPsn
      )

      val reth = RETH()
      reth.va := inputCachedWorkReq.raddr
      reth.rkey := inputCachedWorkReq.rkey
      reth.dlen := inputCachedWorkReq.lenBytes
      val immDt = ImmDt()
      immDt.data := inputCachedWorkReq.immDtOrRmtKeyToInv

      val bthMty = Bits(widthOf(bth) / BYTE_WIDTH bits).setAll()
      val rethMty = Bits(widthOf(reth) / BYTE_WIDTH bits).setAll()
      val immDtMty = Bits(widthOf(immDt) / BYTE_WIDTH bits).setAll()

      val headerBits = Bits(busWidth.id bits)
      val headerMtyBits = Bits(busWidthBytes bits)
      // TODO: verify endian
      headerBits := bth.asBits.resize(busWidth.id)
      headerMtyBits := bthMty.resize(busWidthBytes)
      when(numReqPkt > 1) {
        when(curReqPktCntVal === 0) {
          when(isFromFirstReqPkt) {
            opcode := OpCode.RDMA_WRITE_FIRST.id

            // TODO: verify endian
            headerBits := (bth ## reth).resize(busWidth.id)
            headerMtyBits := (bthMty ## rethMty).resize(busWidthBytes)
          } otherwise {
            opcode := OpCode.RDMA_WRITE_MIDDLE.id
          }
        } elsewhen (curReqPktCntVal === numReqPkt - 1) {
          opcode := OpCode.RDMA_WRITE_LAST.id

          when(workReqHasImmDt) {
            opcode := OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE.id

            // TODO: verify endian
            headerBits := (bth ## immDt).resize(busWidth.id)
            headerMtyBits := (bthMty ## immDtMty).resize(busWidthBytes)
          }
          padcount := (PADCOUNT_FULL -
            lastOrOnlyReqPktLenBytes(0, PADCOUNT_WIDTH bits))
            .resize(PADCOUNT_WIDTH)
        } otherwise { // Middle request
          opcode := OpCode.RDMA_WRITE_MIDDLE.id
        }
      } otherwise { // Last or only request
        when(isFromFirstReqPkt) {
          opcode := OpCode.RDMA_WRITE_ONLY.id

          // TODO: verify endian
          headerBits := (bth ## reth).resize(busWidth.id)
          headerMtyBits := (bthMty ## rethMty).resize(busWidthBytes)
        } otherwise {
          opcode := OpCode.RDMA_WRITE_LAST.id
        }

        when(workReqHasImmDt) {
          when(isFromFirstReqPkt) {
            opcode := OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE.id

            // TODO: verify endian
            headerBits := (bth ## reth ## immDt).resize(busWidth.id)
            headerMtyBits := (bthMty ## rethMty ## immDtMty)
              .resize(busWidthBytes)
          } otherwise {
            opcode := OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE.id

            // TODO: verify endian
            headerBits := (bth ## immDt).resize(busWidth.id)
            headerMtyBits := (bthMty ## immDtMty).resize(busWidthBytes)
          }
        }
        padcount := (PADCOUNT_FULL -
          lastOrOnlyReqPktLenBytes(0, PADCOUNT_WIDTH bits))
          .resize(PADCOUNT_WIDTH)
      }

      val rslt = CombineHeaderAndDmaRespInternalRslt(busWidth).set(
        numReqPkt,
        bth,
        headerBits,
        headerMtyBits
      )
    }.rslt
}
