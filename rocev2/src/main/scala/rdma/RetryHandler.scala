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
    val workReqQueryPort4DupDmaReadResp = master(WorkReqCacheQueryBus())
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
  io.workReqQueryPort4DupDmaReadResp << sqDmaReadRespHandler.io.workReqQuery

  val sendReqGenerator = new SendReqGenerator(busWidth)
  sendReqGenerator.io.qpAttr := io.qpAttr
  sendReqGenerator.io.sendQCtrl := io.sendQCtrl
  sendReqGenerator.io.workReqCacheRespAndDmaReadResp << sqDmaReadRespHandler.io.sendWorkReqCacheRespAndDmaReadResp

  val writeReqGenerator = new WriteReqGenerator(busWidth)
  writeReqGenerator.io.qpAttr := io.qpAttr
  writeReqGenerator.io.sendQCtrl := io.sendQCtrl
  writeReqGenerator.io.workReqCacheRespAndDmaReadResp << sqDmaReadRespHandler.io.writeWorkReqCacheRespAndDmaReadResp

  io.txSendReq << sendReqGenerator.io.txSendReq
  io.txWriteReq << writeReqGenerator.io.txWriteReq
  io.txAtomicReq << readAtomicRetryHandlerAndDmaReadInitiator.io.txAtomicReqRetry
  io.txReadReq << readAtomicRetryHandlerAndDmaReadInitiator.io.txReadReqRetry
}

// TODO: retry does not support fence
class ReadAtomicRetryHandlerAndDmaReadInitiator extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val txReadReqRetry = master(Stream(ReadReq()))
    val txAtomicReqRetry = master(Stream(AtomicReq()))
    val retryWorkReq = slave(Stream(CachedWorkReq()))
    val dmaRead = master(DmaReadReqBus())
  }

  val retryWorkReqValid = io.retryWorkReq.valid
  val retryWorkReq = io.retryWorkReq.payload
  val isSendWorkReq = WorkReqOpCode.isSendReq(retryWorkReq.workReq.opcode)
  val isWriteWorkReq = WorkReqOpCode.isWriteReq(retryWorkReq.workReq.opcode)
  val isReadWorkReq = WorkReqOpCode.isReadReq(retryWorkReq.workReq.opcode)
  val isAtomicWorkReq = WorkReqOpCode.isAtomicReq(retryWorkReq.workReq.opcode)
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
        L"the work request to retry is not send/write/read/atomic, WR opcode=${retryWorkReq.workReq.opcode}",
      severity = FAILURE
    )
  }

  val fourStreams = StreamDemux(
    io.retryWorkReq.throwWhen(io.sendQCtrl.wrongStateFlush),
    select = txSel,
    portCount = 4
  )
  // Just discard non-send/write/read/atomic WR
  StreamSink(NoData) << fourStreams(otherWorkReqIdx).translateWith(NoData)

  io.txAtomicReqRetry <-/< fourStreams(atomicWorkReqIdx).translateWith {
    val isCompSwap =
      retryWorkReq.workReq.opcode === WorkReqOpCode.ATOMIC_CMP_AND_SWP.id
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

  val retryFromFirstReq =
    (io.qpAttr.retryReason === RetryReason.RETRY_ACK) ? (io.qpAttr.retryPsnStart === retryWorkReq.psnStart) | True
  // For partial read retry, compute the partial read DMA length
  val psnDiff = PsnUtil.diff(io.qpAttr.retryPsnStart, retryWorkReq.psnStart)
  // psnDiff << io.qpAttr.pmtu.asUInt === psnDiff * pmtuPktLenBytes(io.qpAttr.pmtu)
  val dmaReadLenBytes =
    retryWorkReq.workReq.lenBytes - (psnDiff << io.qpAttr.pmtu.asUInt)
  when(!retryFromFirstReq) {
    assert(
      assertion = PsnUtil
        .gt(io.qpAttr.retryPsnStart, retryWorkReq.psnStart, io.qpAttr.npsn),
      message =
        L"io.qpAttr.retryPsnStart=${io.qpAttr.retryPsnStart} should > retryWorkReq.psnStart=${retryWorkReq.psnStart} in PSN order",
      severity = FAILURE
    )

    val pktNum = computePktNum(retryWorkReq.workReq.lenBytes, io.qpAttr.pmtu)
    assert(
      assertion = psnDiff < pktNum,
      message = L"psnDiff=${psnDiff} should < pktNum=${pktNum}",
      severity = FAILURE
    )
  }
  io.txReadReqRetry <-/< fourStreams(readWorkReqIdx).translateWith {
    val rslt = ReadReq().set(
      dqpn = io.qpAttr.dqpn,
      psn = retryWorkReq.psnStart,
      va = retryWorkReq.workReq.raddr,
      rkey = retryWorkReq.workReq.rkey,
      dlen =
        dmaReadLenBytes.resize(RDMA_MAX_LEN_WIDTH) // Support partial read retry
    )
    rslt
  }

  io.dmaRead.req <-/< fourStreams(sendWriteWorkReqIdx).translateWith {
    val rslt = cloneOf(io.dmaRead.req.payloadType)
    rslt.set(
      initiator = DmaInitiator.SQ_DUP,
      sqpn = io.qpAttr.sqpn,
      psnStart = retryWorkReq.psnStart,
      addr = retryWorkReq.pa,
      lenBytes = dmaReadLenBytes.resize(RDMA_MAX_LEN_WIDTH)
    )
  }
}

class SqDmaReadRespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    // val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val workReqQuery = master(WorkReqCacheQueryBus())
    val dmaReadResp = slave(DmaReadRespBus(busWidth))
    val sendWorkReqCacheRespAndDmaReadResp = master(
      Stream(Fragment(WorkReqCacheRespAndDmaReadResp(busWidth)))
    )
    val writeWorkReqCacheRespAndDmaReadResp = master(
      Stream(Fragment(WorkReqCacheRespAndDmaReadResp(busWidth)))
    )
  }

  val dmaReadRespValid = io.dmaReadResp.resp.valid
  val isFirstDmaReadResp = io.dmaReadResp.resp.isFirst
  val isLastDmaReadResp = io.dmaReadResp.resp.isLast

  // Send out WorkReqCache query request
  io.workReqQuery.req <-/< SignalEdgeDrivenStream(
    dmaReadRespValid && isFirstDmaReadResp
  ).throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt = cloneOf(io.workReqQuery.req.payloadType)
      rslt.psn := io.dmaReadResp.resp.psnStart
      rslt
    }

  val dmaReadRespQueue =
    io.dmaReadResp.resp.queueLowLatency(WORK_REQ_CACHE_QUERY_DELAY_CYCLE)
  // Join ReadAtomicResultCache query response with DmaReadResp
  val joinStream =
    FragmentStreamJoinStream(dmaReadRespQueue, io.workReqQuery.resp)
  val cachedWorkReq = joinStream._2.cachedWorkReq
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
  when(joinStream.valid) {
    assert(
      assertion = isSendWorkReq || isWriteWorkReq,
      message =
        L"the WR for retry here should be send/write, but WR opcode=${cachedWorkReq.workReq.opcode}",
      severity = FAILURE
    )
  }

  val threeStreams = StreamDemux(joinStream, select = txSel, portCount = 3)
  // Just discard non-send/write WR
  StreamSink(NoData) << threeStreams(otherWorkReqIdx).translateWith(NoData)

  io.sendWorkReqCacheRespAndDmaReadResp <-/< threeStreams(sendWorkReqIdx)
    .throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt = cloneOf(io.sendWorkReqCacheRespAndDmaReadResp.payloadType)
      rslt.dmaReadResp := threeStreams(sendWorkReqIdx)._1
      rslt.workReqCacheResp := threeStreams(sendWorkReqIdx)._2
      rslt.last := threeStreams(sendWorkReqIdx).isLast
      rslt
    }
  io.writeWorkReqCacheRespAndDmaReadResp <-/< threeStreams(writeWorkReqIdx)
    .throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt = cloneOf(io.writeWorkReqCacheRespAndDmaReadResp.payloadType)
      rslt.dmaReadResp := threeStreams(writeWorkReqIdx)._1
      rslt.workReqCacheResp := threeStreams(writeWorkReqIdx)._2
      rslt.last := threeStreams(writeWorkReqIdx).isLast
      rslt
    }
}

// TODO: handle send size = 0
class SendReqGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val txSendReq = master(RdmaDataBus(busWidth))
    // val txErrResp = master(Stream(Acknowledge()))
    val workReqCacheRespAndDmaReadResp = slave(
      Stream(Fragment(WorkReqCacheRespAndDmaReadResp(busWidth)))
    )
  }

  val bthHeaderLenBytes = widthOf(BTH()) / BYTE_WIDTH
  val rethHeaderLenBytes = widthOf(RETH()) / BYTE_WIDTH
  val immDtHeaderLenBytes = widthOf(ImmDt()) / BYTE_WIDTH
  val iethheaderLenBytes = widthOf(IETH()) / BYTE_WIDTH
  val busWidthBytes = busWidth.id / BYTE_WIDTH

  val pmtuFragNum = pmtuPktLenBytes(io.qpAttr.pmtu) >> log2Up(
    busWidth.id / BYTE_WIDTH
  )
  val segmentStream = StreamSegment(
    io.workReqCacheRespAndDmaReadResp
      .throwWhen(io.sendQCtrl.wrongStateFlush),
    fragmentNum = pmtuFragNum.resize(PMTU_FRAG_NUM_WIDTH)
  )
  val input = cloneOf(io.workReqCacheRespAndDmaReadResp)
  input <-/< segmentStream

  val inputValid = input.valid
  val inputDmaDataFrag = input.dmaReadResp
  val inputCachedWorkReq = input.workReqCacheResp.cachedWorkReq.workReq
  val isFirstDataFrag = input.isFirst
  val isLastDataFrag = input.isLast

  val isSendReq = WorkReqOpCode.isSendReq(inputCachedWorkReq.opcode)
  when(inputValid) {
    assert(
      assertion = isSendReq,
      message =
        L"the WR opcode=${inputCachedWorkReq.opcode} should be send request",
      severity = FAILURE
    )
  }

  val numReqPkt =
    divideByPmtuUp(inputDmaDataFrag.lenBytes, io.qpAttr.pmtu)
  val lastOrOnlyReqPktLenBytes =
    moduloByPmtu(inputDmaDataFrag.lenBytes, io.qpAttr.pmtu)

  // Count the number of send request packet processed
  val reqPktCntr = Counter(
    // RDMA_MAX_LEN_WIDTH >> PMTU.U256.id = RDMA_MAX_LEN_WIDTH / 256
    // This is the max number of packets of a request.
    bitCount = (RDMA_MAX_LEN_WIDTH >> PMTU.U256.id) bits,
    inc = input.lastFire
  )

  val workReqHasImmDt = WorkReqOpCode.hasImmDt(inputCachedWorkReq.opcode)
  val workReqHasIeth = WorkReqOpCode.hasIeth(inputCachedWorkReq.opcode)
  val isFromFirstResp =
    inputDmaDataFrag.psnStart === input.workReqCacheResp.cachedWorkReq.psnStart

  val curPsn = inputDmaDataFrag.psnStart + reqPktCntr.value
  val opcode = Bits(OPCODE_WIDTH bits)
  val padcount = U(0, PADCOUNT_WIDTH bits)

  val bth = BTH().set(
    opcode = opcode,
    padcount = padcount,
    dqpn = io.qpAttr.dqpn,
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
    when(reqPktCntr.value === 0) {
      when(isFromFirstResp) {
        opcode := OpCode.SEND_FIRST.id
      } otherwise {
        opcode := OpCode.SEND_MIDDLE.id
      }

    } elsewhen (reqPktCntr.value === numReqPkt - 1) { // Last request
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
    when(isFromFirstResp) {
      opcode := OpCode.SEND_ONLY.id
    } otherwise {
      opcode := OpCode.SEND_LAST.id
    }

    when(workReqHasImmDt) {
      when(isFromFirstResp) {
        opcode := OpCode.SEND_ONLY_WITH_IMMEDIATE.id
      } otherwise {
        opcode := OpCode.SEND_LAST_WITH_IMMEDIATE.id
      }

      // TODO: verify endian
      headerBits := (bth ## immDt).resize(busWidth.id)
      headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
    } elsewhen (workReqHasIeth) {
      when(isFromFirstResp) {
        opcode := OpCode.SEND_ONLY_WITH_INVALIDATE.id
      } otherwise {
        opcode := OpCode.SEND_LAST_WITH_INVALIDATE.id
      }

      // TODO: verify endian
      headerBits := (bth ## ieth).resize(busWidth.id)
      headerMtyBits := (bthMty ## sendEthMty).resize(busWidthBytes)
    }

    padcount := (PADCOUNT_FULL -
      lastOrOnlyReqPktLenBytes(0, PADCOUNT_WIDTH bits)).resize(PADCOUNT_WIDTH)
  }

  val headerStream = SignalEdgeDrivenStream(isFirstDataFrag)
    .throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt = HeaderDataAndMty(BTH(), busWidth.id)
      rslt.header := bth
      rslt.data := headerBits
      rslt.mty := headerMtyBits
      rslt
    }
  val addHeaderStream = StreamAddHeader(
    input
      .throwWhen(io.sendQCtrl.wrongStateFlush)
      .translateWith {
        val rslt = Fragment(DataAndMty(busWidth.id))
        rslt.data := inputDmaDataFrag.data
        rslt.mty := inputDmaDataFrag.mty
        rslt.last := isLastDataFrag
        rslt
      },
    headerStream
  )
  io.txSendReq.pktFrag <-/< addHeaderStream.translateWith {
    val rslt = Fragment(RdmaDataPkt(busWidth))
    rslt.bth := addHeaderStream.header
    rslt.data := addHeaderStream.data
    rslt.mty := addHeaderStream.mty
    rslt.last := addHeaderStream.last
    rslt
  }
}

// TODO: handle write size = 0
class WriteReqGenerator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val txWriteReq = master(RdmaDataBus(busWidth))
    // val txErrResp = master(Stream(Acknowledge()))
    val workReqCacheRespAndDmaReadResp = slave(
      Stream(Fragment(WorkReqCacheRespAndDmaReadResp(busWidth)))
    )
  }

  val bthHeaderLenBytes = widthOf(BTH()) / BYTE_WIDTH
  val rethHeaderLenBytes = widthOf(RETH()) / BYTE_WIDTH
  val immDtHeaderLenBytes = widthOf(ImmDt()) / BYTE_WIDTH
  val busWidthBytes = busWidth.id / BYTE_WIDTH

  val pmtuFragNum = pmtuPktLenBytes(io.qpAttr.pmtu) >> log2Up(
    busWidth.id / BYTE_WIDTH
  )
  val segmentStream = StreamSegment(
    io.workReqCacheRespAndDmaReadResp
      .throwWhen(io.sendQCtrl.wrongStateFlush),
    fragmentNum = pmtuFragNum.resize(PMTU_FRAG_NUM_WIDTH)
  )
  val input = cloneOf(io.workReqCacheRespAndDmaReadResp)
  input <-/< segmentStream

  val inputValid = input.valid
  val inputDmaDataFrag = input.dmaReadResp
  val inputCachedWorkReq = input.workReqCacheResp.cachedWorkReq.workReq
  val isFirstDataFrag = input.isFirst
  val isLastDataFrag = input.isLast

  val isWriteReq = WorkReqOpCode.isWriteReq(inputCachedWorkReq.opcode)
  when(inputValid) {
    assert(
      assertion = isWriteReq,
      message =
        L"the WR opcode=${inputCachedWorkReq.opcode} should be write request",
      severity = FAILURE
    )
  }

  val numReqPkt =
    divideByPmtuUp(inputDmaDataFrag.lenBytes, io.qpAttr.pmtu)
  val lastOrOnlyReqPktLenBytes =
    moduloByPmtu(inputDmaDataFrag.lenBytes, io.qpAttr.pmtu)

  // Count the number of read response packet processed
  val reqPktCnt = Counter(
    // RDMA_MAX_LEN_WIDTH >> PMTU.U256.id = RDMA_MAX_LEN_WIDTH / 256
    // This is the max number of packets of a request.
    bitCount = (RDMA_MAX_LEN_WIDTH >> PMTU.U256.id) bits,
    inc = input.lastFire
  )

  val workReqHasImmDt = WorkReqOpCode.hasImmDt(inputCachedWorkReq.opcode)
  val isFromFirstResp =
    inputDmaDataFrag.psnStart === input.workReqCacheResp.cachedWorkReq.psnStart

  val curPsn = inputDmaDataFrag.psnStart + reqPktCnt.value
  val opcode = Bits(OPCODE_WIDTH bits)
  val padcount = U(0, PADCOUNT_WIDTH bits)

  val bth = BTH().set(
    opcode = opcode,
    padcount = padcount,
    dqpn = io.qpAttr.dqpn,
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
    when(reqPktCnt.value === 0) {
      when(isFromFirstResp) {
        opcode := OpCode.RDMA_WRITE_FIRST.id

        // TODO: verify endian
        headerBits := (bth ## reth).resize(busWidth.id)
        headerMtyBits := (bthMty ## rethMty).resize(busWidthBytes)
      } otherwise {
        opcode := OpCode.RDMA_WRITE_MIDDLE.id
      }
    } elsewhen (reqPktCnt.value === numReqPkt - 1) {
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
    when(isFromFirstResp) {
      opcode := OpCode.RDMA_WRITE_ONLY.id

      // TODO: verify endian
      headerBits := (bth ## reth).resize(busWidth.id)
      headerMtyBits := (bthMty ## rethMty).resize(busWidthBytes)
    } otherwise {
      opcode := OpCode.RDMA_WRITE_LAST.id
    }

    when(workReqHasImmDt) {
      when(isFromFirstResp) {
        opcode := OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE.id

        // TODO: verify endian
        headerBits := (bth ## reth ## immDt).resize(busWidth.id)
        headerMtyBits := (bthMty ## rethMty ## immDtMty).resize(busWidthBytes)
      } otherwise {
        opcode := OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE.id

        // TODO: verify endian
        headerBits := (bth ## immDt).resize(busWidth.id)
        headerMtyBits := (bthMty ## immDtMty).resize(busWidthBytes)
      }
    }
    padcount := (PADCOUNT_FULL -
      lastOrOnlyReqPktLenBytes(0, PADCOUNT_WIDTH bits)).resize(PADCOUNT_WIDTH)
  }

  val headerStream = SignalEdgeDrivenStream(isFirstDataFrag)
    .throwWhen(io.sendQCtrl.wrongStateFlush)
    .translateWith {
      val rslt = HeaderDataAndMty(BTH(), busWidth.id)
      rslt.header := bth
      rslt.data := headerBits
      rslt.mty := headerMtyBits
      rslt
    }
  val addHeaderStream = StreamAddHeader(
    input
      .throwWhen(io.sendQCtrl.wrongStateFlush)
      .translateWith {
        val rslt = Fragment(DataAndMty(busWidth.id))
        rslt.data := inputDmaDataFrag.data
        rslt.mty := inputDmaDataFrag.mty
        rslt.last := isLastDataFrag
        rslt
      },
    headerStream
  )
  io.txWriteReq.pktFrag <-/< addHeaderStream.translateWith {
    val rslt = Fragment(RdmaDataPkt(busWidth))
    rslt.bth := addHeaderStream.header
    rslt.data := addHeaderStream.data
    rslt.mty := addHeaderStream.mty
    rslt.last := addHeaderStream.last
    rslt
  }
}
