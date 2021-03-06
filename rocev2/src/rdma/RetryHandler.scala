package rdma

import spinal.core._
import spinal.lib._
import ConstantSettings._
import RdmaConstants._

import scala.language.postfixOps
// pp.282 spec 1.4
// The retried request may only reread those portions that were not
// successfully responded to the first time.
//
// Any retried request must correspond exactly to a subset of the
// original RDMA READ request in such a manner that all potential
// duplicate response packets must have identical payload data and PSNs
class RetryHandler extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val retryScanCtrlBus = master(RamScanCtrlBus())
    val retryWorkReqIn = slave(Stream(RamScanOut(CachedWorkReq())))
    val retryWorkReqOut = master(Stream(CachedWorkReq()))
    val errNotifier = out(SqErrNotifier())
    val retryWorkReqDone = out(Bool())
  }

  // TODO: if retry count exceeds, should fire the retry WR or not?
  // SQ into ERR state if retry count exceeds qpAttr.maxRetryCnt

  io.errNotifier.setNoErr()
  when(io.retryWorkReqIn.valid) {
    when(io.retryWorkReqIn.rnrCnt > io.qpAttr.maxRnrRetryCnt) {
      io.errNotifier.setRnrExceed()
    } elsewhen (io.retryWorkReqIn.retryCnt > io.qpAttr.maxRetryCnt) {
      io.errNotifier.setRetryExceed()
    }
  }

  io.retryScanCtrlBus.startPulse := io.txQCtrl.retryStartPulse
  io.retryScanCtrlBus.retryReason := io.qpAttr.retryReason
  io.retryScanCtrlBus.retryStartPsn := io.qpAttr.retryStartPsn
  io.retryWorkReqDone := io.retryScanCtrlBus.donePulse

  // Handle WR partial retry
  // TODO: verify RNR has no partial retry
  val (
    isRetryWholeWorkReq,
    retryStartPsn,
    retryDmaReadStartAddr,
    retryWorkReqRemoteStartAddr,
    retryWorkReqLocalStartAddr,
    retryWorkReqPktNum,
    retryDmaReadLenBytes
  ) = PartialRetry.workReqRetry(
    io.qpAttr,
    retryWorkReq = io.retryWorkReqIn.scanOutData,
    retryWorkReqValid = io.retryWorkReqIn.valid
  )

  io.retryWorkReqOut <-/< io.retryWorkReqIn.map { payloadData =>
    val result = cloneOf(io.retryWorkReqOut.payloadType)
    result := payloadData.scanOutData

    when(!isRetryWholeWorkReq) {
      result.psnStart := retryStartPsn
      result.pa := retryDmaReadStartAddr
      result.pktNum := retryWorkReqPktNum
      result.workReq.raddr := retryWorkReqRemoteStartAddr
      result.workReq.laddr := retryWorkReqLocalStartAddr
      result.workReq.lenBytes := retryDmaReadLenBytes
    }
    result
  }
}

class SqReqGenerator(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val sendWriteNormalWorkReq = slave(Stream(CachedWorkReq()))
    val readNormalWorkReq = slave(Stream(CachedWorkReq()))
    val atomicNormalWorkReq = slave(Stream(CachedWorkReq()))
    val dmaRead = master(DmaReadBus(busWidth))
    val txSendReq = master(RdmaDataBus(busWidth))
    val txWriteReq = master(RdmaDataBus(busWidth))
    val txReadReq = master(Stream(ReadReq()))
    val txAtomicReq = master(Stream(AtomicReq()))
  }

  val readAtomicGeneratorAndDmaReadInitiator =
    new ReadAtomicGeneratorAndDmaReadInitiator
  readAtomicGeneratorAndDmaReadInitiator.io.qpAttr := io.qpAttr
  readAtomicGeneratorAndDmaReadInitiator.io.txQCtrl := io.txQCtrl
  readAtomicGeneratorAndDmaReadInitiator.io.sendWriteWorkReq << io.sendWriteNormalWorkReq
  readAtomicGeneratorAndDmaReadInitiator.io.readWorkReq << io.readNormalWorkReq
  readAtomicGeneratorAndDmaReadInitiator.io.atomicWorkReq << io.atomicNormalWorkReq
  io.dmaRead.req << readAtomicGeneratorAndDmaReadInitiator.io.dmaRead.req

  val sqDmaReadRespHandler = new SqDmaReadRespHandler(busWidth)
  sqDmaReadRespHandler.io.txQCtrl := io.txQCtrl
  sqDmaReadRespHandler.io.dmaReadResp.resp << io.dmaRead.resp
  sqDmaReadRespHandler.io.cachedWorkReq << readAtomicGeneratorAndDmaReadInitiator.io.outSendWriteWorkReq

  val sendWriteReqSegment = new SendWriteReqSegment(busWidth)
  sendWriteReqSegment.io.qpAttr := io.qpAttr
  sendWriteReqSegment.io.txQCtrl := io.txQCtrl
  sendWriteReqSegment.io.cachedWorkReqAndDmaReadResp << sqDmaReadRespHandler.io.cachedWorkReqAndDmaReadResp

  val sendReqGenerator = new SendReqGenerator(busWidth)
  sendReqGenerator.io.qpAttr := io.qpAttr
  sendReqGenerator.io.txQCtrl := io.txQCtrl
  sendReqGenerator.io.cachedWorkReqAndDmaReadResp << sendWriteReqSegment.io.sendCachedWorkReqAndDmaReadResp

  val writeReqGenerator = new WriteReqGenerator(busWidth)
  writeReqGenerator.io.qpAttr := io.qpAttr
  writeReqGenerator.io.txQCtrl := io.txQCtrl
  writeReqGenerator.io.cachedWorkReqAndDmaReadResp << sendWriteReqSegment.io.writeCachedWorkReqAndDmaReadResp

  io.txSendReq << sendReqGenerator.io.txReq
  io.txWriteReq << writeReqGenerator.io.txReq
  io.txReadReq << readAtomicGeneratorAndDmaReadInitiator.io.txReadReq
  io.txAtomicReq << readAtomicGeneratorAndDmaReadInitiator.io.txAtomicReq
}

class ReadAtomicGeneratorAndDmaReadInitiator extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val sendWriteWorkReq = slave(Stream(CachedWorkReq()))
    val readWorkReq = slave(Stream(CachedWorkReq()))
    val atomicWorkReq = slave(Stream(CachedWorkReq()))
    val txReadReq = master(Stream(ReadReq()))
    val txAtomicReq = master(Stream(AtomicReq()))
    val dmaRead = master(DmaReadReqBus())
    val outSendWriteWorkReq = master(Stream(CachedWorkReq()))
  }

  val (sendWriteWorkReq4Out, sendWriteWorkReq4Dma) = StreamFork2(
    io.sendWriteWorkReq.throwWhen(
      io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush
    )
  )
  io.outSendWriteWorkReq <-/< sendWriteWorkReq4Out

  io.dmaRead.req <-/< sendWriteWorkReq4Dma.map { payloadData =>
    val result = cloneOf(io.dmaRead.req.payloadType)
    result.set(
      initiator = DmaInitiator.SQ_RD,
      sqpn = payloadData.workReq.sqpn,
      psnStart = payloadData.psnStart,
      pa = payloadData.pa,
      lenBytes = payloadData.workReq.lenBytes
    )
    result
  }

  io.txReadReq <-/< io.readWorkReq
    .throwWhen(io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush)
    .map { payloadData =>
      val result = ReadReq().set(
        dqpn = io.qpAttr.dqpn,
        psn = payloadData.psnStart,
        va = payloadData.workReq.raddr,
        rkey = payloadData.workReq.rkey,
        dlen = payloadData.workReq.lenBytes
      )
      result
    }

  io.txAtomicReq <-/< io.atomicWorkReq
    .throwWhen(io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush)
    .map { payloadData =>
      val isCompSwap =
        payloadData.workReq.opcode === WorkReqOpCode.ATOMIC_CMP_AND_SWP
      val result = AtomicReq().set(
        isCompSwap,
        dqpn = io.qpAttr.dqpn,
        psn = payloadData.psnStart,
        va = payloadData.workReq.raddr,
        rkey = payloadData.workReq.rkey,
        comp = payloadData.workReq.comp,
        swap = payloadData.workReq.swap
      )
      result
    }
}

class SqDmaReadRespHandler(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    // val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
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
    // TODO: why flush by retry?
    io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush,
    busWidth,
    reqQueueLen = PENDING_REQ_FIFO_DEPTH,
    isReqZeroDmaLen = (req: CachedWorkReq) => req.workReq.lenBytes === 0
  )
  io.cachedWorkReqAndDmaReadResp <-/< handlerOutput
    .throwWhen(io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush)
    .translateWith {
      val result = cloneOf(io.cachedWorkReqAndDmaReadResp)
      result.dmaReadResp := handlerOutput.dmaReadResp
      result.cachedWorkReq := handlerOutput.req
      result.last := handlerOutput.isLast
      result
    }
}

class SendWriteReqSegment(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
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
    io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush,
    io.qpAttr.pmtu,
    busWidth,
    isReqZeroDmaLen = (reqAndDmaReadResp: CachedWorkReqAndDmaReadResp) =>
      reqAndDmaReadResp.cachedWorkReq.workReq.lenBytes === 0
  )
  val cachedWorkReq = segmentOut.cachedWorkReq
  val isSendWorkReq = WorkReqOpCode.isSendReq(cachedWorkReq.workReq.opcode)
  val isWriteWorkReq = WorkReqOpCode.isWriteReq(cachedWorkReq.workReq.opcode)

  when(segmentOut.valid) {
    assert(
      assertion = isSendWorkReq || isWriteWorkReq,
      message =
        L"${REPORT_TIME} time: the WR for retry here should be send/write, but WR opcode=${cachedWorkReq.workReq.opcode}".toSeq,
      severity = FAILURE
    )
  }

  val (sendWorkReqIdx, writeWorkReqIdx) = (0, 1)
  val twoStreams =
    StreamDeMuxByConditions(segmentOut, isSendWorkReq, isWriteWorkReq)

  io.sendCachedWorkReqAndDmaReadResp <-/< twoStreams(sendWorkReqIdx)
    .throwWhen(io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush)
    .translateWith {
      val result = cloneOf(io.sendCachedWorkReqAndDmaReadResp.payloadType)
      result.dmaReadResp := twoStreams(sendWorkReqIdx).dmaReadResp
      result.cachedWorkReq := twoStreams(sendWorkReqIdx).cachedWorkReq
      result.last := twoStreams(sendWorkReqIdx).isLast
      result
    }
  io.writeCachedWorkReqAndDmaReadResp <-/< twoStreams(writeWorkReqIdx)
    .throwWhen(io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush)
    .translateWith {
      val result = cloneOf(io.writeCachedWorkReqAndDmaReadResp.payloadType)
      result.dmaReadResp := twoStreams(writeWorkReqIdx).dmaReadResp
      result.cachedWorkReq := twoStreams(writeWorkReqIdx).cachedWorkReq
      result.last := twoStreams(writeWorkReqIdx).isLast
      result
    }
}

abstract class SendWriteReqGenerator(busWidth: BusWidth.Value)
    extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val txReq = master(RdmaDataBus(busWidth))
    val cachedWorkReqAndDmaReadResp = slave(
      Stream(Fragment(CachedWorkReqAndDmaReadResp(busWidth)))
    )
  }

  val busWidthBytes = busWidth.id / BYTE_WIDTH

  val input = io.cachedWorkReqAndDmaReadResp.throwWhen(
    io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush
  )
  when(input.valid) {
    assert(
      assertion =
        input.cachedWorkReq.workReq.lenBytes === input.dmaReadResp.lenBytes,
      message =
        L"${REPORT_TIME} time: input.cachedWorkReq.workReq.lenBytes=${input.cachedWorkReq.workReq.lenBytes} should equal input.dmaReadResp.lenBytes=${input.dmaReadResp.lenBytes}".toSeq,
      severity = FAILURE
    )
  }

  val reqAndDmaReadRespSegment = input.translateWith {
    val result =
      Fragment(ReqAndDmaReadResp(CachedWorkReq(), busWidth))
    result.dmaReadResp := input.dmaReadResp
    result.req := input.cachedWorkReq
    result.last := input.isLast
    result
  }

  def headerGenFunc(
      inputReq: CachedWorkReq,
      inputDmaDataFrag: DmaReadResp,
      curReqPktCntVal: UInt,
      qpAttr: QpAttrData
  ): CombineHeaderAndDmaRespInternalRst

  val combinerOutput = CombineHeaderAndDmaResponse(
    reqAndDmaReadRespSegment,
    io.qpAttr,
    io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush,
    busWidth,
    headerGenFunc
  )
  io.txReq.pktFrag <-/< combinerOutput.pktFrag.throwWhen(
    io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush
  )
}

class SendReqGenerator(busWidth: BusWidth.Value)
    extends SendWriteReqGenerator(busWidth) {
  val isSendWorkReq = WorkReqOpCode.isSendReq(
    io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode
  )
  when(io.cachedWorkReqAndDmaReadResp.valid) {
    assert(
      assertion = isSendWorkReq,
      message =
        L"${REPORT_TIME} time: the WR opcode=${io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode} should be send request".toSeq,
      severity = FAILURE
    )
  }

  override def headerGenFunc(
      inputReq: CachedWorkReq,
      inputDmaDataFrag: DmaReadResp,
      curReqPktCntVal: UInt,
      qpAttr: QpAttrData
  ) =
    new Composite(this, "SendReqGenerator_headerGenFunc") {
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
      val padCnt = U(0, PAD_COUNT_WIDTH bits)

      val bth = BTH().set(
        opcode = opcode,
        padCnt = padCnt,
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
//      headerBits := bth.asBits.resize(busWidth.id)
//      headerMtyBits := bthMty.resize(busWidthBytes)
      headerBits := mergeRdmaHeader(busWidth, bth)
      headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty)
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

//            headerBits := (bth ## immDt).resize(busWidth.id)
//            headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
            headerBits := mergeRdmaHeader(busWidth, bth, immDt)
            headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, sendEthMty)
          } elsewhen (workReqHasIeth) {
            opcode := OpCode.SEND_LAST_WITH_INVALIDATE.id

//            headerBits := (bth ## ieth).resize(busWidth.id)
//            headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
            headerBits := mergeRdmaHeader(busWidth, bth, ieth)
            headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, sendEthMty)
          }

          padCnt := (PAD_COUNT_FULL -
            lastOrOnlyReqPktLenBytes(0, PAD_COUNT_WIDTH bits))
            .resize(PAD_COUNT_WIDTH)
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

//          headerBits := (bth ## immDt).resize(busWidth.id)
//          headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
          headerBits := mergeRdmaHeader(busWidth, bth, immDt)
          headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, sendEthMty)
        } elsewhen (workReqHasIeth) {
          when(isFromFirstReqPkt) {
            opcode := OpCode.SEND_ONLY_WITH_INVALIDATE.id
          } otherwise {
            opcode := OpCode.SEND_LAST_WITH_INVALIDATE.id
          }

//          headerBits := (bth ## ieth).resize(busWidth.id)
//          headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
          headerBits := mergeRdmaHeader(busWidth, bth, ieth)
          headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, sendEthMty)
        }

        padCnt := (PAD_COUNT_FULL -
          lastOrOnlyReqPktLenBytes(0, PAD_COUNT_WIDTH bits))
          .resize(PAD_COUNT_WIDTH)
      }

      val result = CombineHeaderAndDmaRespInternalRst(busWidth).set(
        numReqPkt,
        bth,
        headerBits,
        headerMtyBits
      )
    }.result
}

class WriteReqGenerator(busWidth: BusWidth.Value)
    extends SendWriteReqGenerator(busWidth) {
  val isWriteWorkReq = WorkReqOpCode.isWriteReq(
    io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode
  )
  when(io.cachedWorkReqAndDmaReadResp.valid) {
    assert(
      assertion = isWriteWorkReq,
      message =
        L"${REPORT_TIME} time: the WR opcode=${io.cachedWorkReqAndDmaReadResp.cachedWorkReq.workReq.opcode} should be write request".toSeq,
      severity = FAILURE
    )
  }

  override def headerGenFunc(
      inputReq: CachedWorkReq,
      inputDmaDataFrag: DmaReadResp,
      curReqPktCntVal: UInt,
      qpAttr: QpAttrData
  ): CombineHeaderAndDmaRespInternalRst =
    new Composite(this, "WriteReqGenerator_headerGenFunc") {
      val inputCachedWorkReq = inputReq.workReq

      val numReqPkt = inputReq.pktNum
      val lastOrOnlyReqPktLenBytes =
        moduloByPmtu(inputDmaDataFrag.lenBytes, qpAttr.pmtu)

      val workReqHasImmDt = WorkReqOpCode.hasImmDt(inputCachedWorkReq.opcode)
      val isFromFirstReqPkt =
        inputDmaDataFrag.psnStart === inputReq.psnStart

      val curPsn = inputDmaDataFrag.psnStart + curReqPktCntVal
      val opcode = Bits(OPCODE_WIDTH bits)
      val padCnt = U(0, PAD_COUNT_WIDTH bits)

      val bth = BTH().set(
        opcode = opcode,
        padCnt = padCnt,
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
//      headerBits := bth.asBits.resize(busWidth.id)
//      headerMtyBits := bthMty.resize(busWidthBytes)
      headerBits := mergeRdmaHeader(busWidth, bth)
      headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty)
      when(numReqPkt > 1) {
        when(curReqPktCntVal === 0) {
          when(isFromFirstReqPkt) {
            opcode := OpCode.RDMA_WRITE_FIRST.id

//            headerBits := (bth ## reth).resize(busWidth.id)
//            headerMtyBits := (bthMty ## rethMty).resize(busWidthBytes)
            headerBits := mergeRdmaHeader(busWidth, bth, reth)
            headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, rethMty)
          } otherwise {
            opcode := OpCode.RDMA_WRITE_MIDDLE.id
          }
        } elsewhen (curReqPktCntVal === numReqPkt - 1) {
          opcode := OpCode.RDMA_WRITE_LAST.id

          when(workReqHasImmDt) {
            opcode := OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE.id

//            headerBits := (bth ## immDt).resize(busWidth.id)
//            headerMtyBits := (bthMty ## immDtMty).resize(busWidthBytes)
            headerBits := mergeRdmaHeader(busWidth, bth, immDt)
            headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, immDtMty)
          }
          padCnt := (PAD_COUNT_FULL -
            lastOrOnlyReqPktLenBytes(0, PAD_COUNT_WIDTH bits))
            .resize(PAD_COUNT_WIDTH)
        } otherwise { // Middle request
          opcode := OpCode.RDMA_WRITE_MIDDLE.id
        }
      } otherwise { // Last or only request
        when(isFromFirstReqPkt) {
          opcode := OpCode.RDMA_WRITE_ONLY.id

//          headerBits := (bth ## reth).resize(busWidth.id)
//          headerMtyBits := (bthMty ## rethMty).resize(busWidthBytes)
          headerBits := mergeRdmaHeader(busWidth, bth, reth)
          headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, rethMty)
        } otherwise {
          opcode := OpCode.RDMA_WRITE_LAST.id
        }

        when(workReqHasImmDt) {
          when(isFromFirstReqPkt) {
            opcode := OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE.id

//            headerBits := (bth ## reth ## immDt).resize(busWidth.id)
//            headerMtyBits := (bthMty ## rethMty ## immDtMty)
//              .resize(busWidthBytes)
            headerBits := mergeRdmaHeader(busWidth, bth, reth, immDt)
            headerMtyBits := mergeRdmaHeaderMty(
              busWidth,
              bthMty,
              rethMty,
              immDtMty
            )
          } otherwise {
            opcode := OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE.id

//            headerBits := (bth ## immDt).resize(busWidth.id)
//            headerMtyBits := (bthMty ## immDtMty).resize(busWidthBytes)
            headerBits := mergeRdmaHeader(busWidth, bth, immDt)
            headerMtyBits := mergeRdmaHeaderMty(busWidth, bthMty, immDtMty)
          }
        }
        padCnt := (PAD_COUNT_FULL -
          lastOrOnlyReqPktLenBytes(0, PAD_COUNT_WIDTH bits))
          .resize(PAD_COUNT_WIDTH)
      }

      val result = CombineHeaderAndDmaRespInternalRst(busWidth).set(
        numReqPkt,
        bth,
        headerBits,
        headerMtyBits
      )
    }.result
}
