package rdma

import spinal.core._
import spinal.lib._
import ConstantSettings._
import RdmaConstants._

import scala.language.postfixOps

class RespHandler(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val errNotifier = out(SqErrNotifier())
    val retryNotifier = out(SqRetryNotifier())
    val coalesceAckDone = out(Bool())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
//    val workReqQuery = master(WorkReqCacheQueryBus())
    val workComp = master(Stream(WorkComp()))
    // Save read/atomic response data to main memory
    val readRespDmaWrite = master(DmaWriteBus(busWidth))
    val atomicRespDmaWrite = master(DmaWriteBus(busWidth))
  }

  val respVerifier = new RespVerifier(busWidth)
//  respAckExtractor.io.qpAttr := io.qpAttr
  respVerifier.io.txQCtrl := io.txQCtrl
  respVerifier.io.rx << io.rx

  val coalesceAndNormalAndRetryNakHandler =
    new CoalesceAndNormalAndRetryNakHandler(busWidth)
  coalesceAndNormalAndRetryNakHandler.io.qpAttr := io.qpAttr
  coalesceAndNormalAndRetryNakHandler.io.txQCtrl := io.txQCtrl
  coalesceAndNormalAndRetryNakHandler.io.rx << respVerifier.io.tx
  coalesceAndNormalAndRetryNakHandler.io.cachedWorkReqPop << io.cachedWorkReqPop
  io.coalesceAckDone := coalesceAndNormalAndRetryNakHandler.io.coalesceAckDone
  io.retryNotifier := coalesceAndNormalAndRetryNakHandler.io.retryNotifier

  val readRespLenCheck = new ReadRespLenCheck(busWidth: BusWidth.Value)
  readRespLenCheck.io.qpAttr := io.qpAttr
  readRespLenCheck.io.txQCtrl := io.txQCtrl
  readRespLenCheck.io.cachedWorkReqAndRespWithAethIn << coalesceAndNormalAndRetryNakHandler.io.cachedWorkReqAndRespWithAeth

  val readAtomicRespVerifierAndFatalNakNotifier =
    new ReadAtomicRespVerifierAndFatalNakNotifier(busWidth)
  readAtomicRespVerifierAndFatalNakNotifier.io.qpAttr := io.qpAttr
  readAtomicRespVerifierAndFatalNakNotifier.io.txQCtrl := io.txQCtrl
  readAtomicRespVerifierAndFatalNakNotifier.io.cachedWorkReqAndRespWithAeth << readRespLenCheck.io.cachedWorkReqAndRespWithAethOut
//  readAtomicRespVerifierAndFatalNakNotifier.io.readAtomicResp << respAckExtractor.io.readAtomicResp
//  io.workReqQuery << readAtomicRespVerifierAndFatalNakNotifier.io.workReqQuery
  io.addrCacheRead << readAtomicRespVerifierAndFatalNakNotifier.io.addrCacheRead

  io.errNotifier :=
    coalesceAndNormalAndRetryNakHandler.io.errNotifier ||
      readRespLenCheck.io.errNotifier ||
      readAtomicRespVerifierAndFatalNakNotifier.io.errNotifier

  val readAtomicRespDmaReqInitiator =
    new ReadAtomicRespDmaReqInitiator(busWidth)
  readAtomicRespDmaReqInitiator.io.qpAttr := io.qpAttr
  readAtomicRespDmaReqInitiator.io.txQCtrl := io.txQCtrl
  readAtomicRespDmaReqInitiator.io.readAtomicRespWithDmaInfoBus << readAtomicRespVerifierAndFatalNakNotifier.io.readAtomicRespWithDmaInfoBus
  io.readRespDmaWrite.req << readAtomicRespDmaReqInitiator.io.readRespDmaWriteReq.req
  io.atomicRespDmaWrite.req << readAtomicRespDmaReqInitiator.io.atomicRespDmaWriteReq.req

  val workCompGen = new WorkCompGen
  workCompGen.io.qpAttr := io.qpAttr
  workCompGen.io.txQCtrl := io.txQCtrl
  workCompGen.io.cachedWorkReqAndAck << readAtomicRespVerifierAndFatalNakNotifier.io.cachedWorkReqAndAck
  workCompGen.io.readRespDmaWriteResp.resp << io.readRespDmaWrite.resp
  workCompGen.io.atomicRespDmaWriteResp.resp << io.atomicRespDmaWrite.resp
  io.workComp << workCompGen.io.workCompPush
}

// Discard all invalid responses:
// - NAK with reserved code;
// - Target QP not exists; dropped by head verifier
// - Ghost ACK;
class RespVerifier(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(Stream(Fragment(ResponseWithAeth(busWidth))))
  }

  val inputRespValid = io.rx.pktFrag.valid
  val inputRespPktFrag = io.rx.pktFrag.payload
  val isLastFrag = io.rx.pktFrag.last
  when(inputRespValid) {
    assert(
      assertion = OpCode.isRespPkt(inputRespPktFrag.bth.opcode),
      message =
        L"${REPORT_TIME} time: RespVerifier received non-response packet with opcode=${inputRespPktFrag.bth.opcode}, PSN=${inputRespPktFrag.bth.psn}".toSeq,
      severity = FAILURE
    )
  }

  val workCompStatus = WorkCompStatus()
  val checkPadCntAndReadRespOpCodeSeq = new Area {
    // Packet padding count check
    val isPadCntCheckPass = respPadCountCheck(
      inputRespPktFrag.bth.opcode,
      inputRespPktFrag.bth.padCnt,
      inputRespPktFrag.mty,
      isLastFrag,
      busWidth
    )

    val isReadResp =
      OpCode.isReadRespPkt(inputRespPktFrag.bth.opcode)

    // The previous read response packet opcode, used to check the read response opcode sequence
    val preReadRespPktOpCodeReg = RegInit(
      B(OpCode.RDMA_READ_RESPONSE_ONLY.id, OPCODE_WIDTH bits)
    ) // CSR
    // CSR needs to reset when QP in error state
    when(io.txQCtrl.wrongStateFlush || io.txQCtrl.errorFlush) {
      preReadRespPktOpCodeReg := OpCode.RDMA_READ_RESPONSE_ONLY.id
    }
    when(isReadResp) {
      when(io.rx.pktFrag.lastFire) {
        preReadRespPktOpCodeReg := inputRespPktFrag.bth.opcode
      }
    }
    val isReadRespOpCodeSeqCheckPass = OpCodeSeq.checkReadRespSeq(
      preReadRespPktOpCodeReg,
      inputRespPktFrag.bth.opcode
    )
    val isReadRespOpCodeSeqErr = isReadResp && !isReadRespOpCodeSeqCheckPass
    when(inputRespValid) {
      assert(
        assertion = isReadResp && isReadRespOpCodeSeqCheckPass,
        message =
          L"${REPORT_TIME} time: read response opcode sequence error: previous opcode=${preReadRespPktOpCodeReg}, current opcode=${inputRespPktFrag.bth.opcode}".toSeq,
        severity = FAILURE
      )
    }

    // TODO: make all following WC status error once WC status is error
    when(!isPadCntCheckPass) {
      workCompStatus := WorkCompStatus.LOC_LEN_ERR
    } elsewhen (isReadRespOpCodeSeqErr) {
      workCompStatus := WorkCompStatus.BAD_RESP_ERR
    } otherwise {
      workCompStatus := WorkCompStatus.SUCCESS
    }
  }

  val hasAeth = OpCode.respPktHasAeth(inputRespPktFrag.bth.opcode)
  val aeth = inputRespPktFrag.extractAeth()
  // Just discard ACK with reserved code
  val hasReservedCode = aeth.isReserved()
  when(inputRespValid && hasAeth) {
    assert(
      assertion = !hasReservedCode,
      message =
        L"${REPORT_TIME} time: acknowledge has reserved code or value, PSN=${inputRespPktFrag.bth.psn}, aeth.code=${aeth.code}, aeth.value=${aeth.value}".toSeq,
      // TODO: change to ERROR, since ACK with reserved code just discard
      severity = FAILURE
    )
  }

  val respWithExtractedAeth = StreamExtractCompany(
    inputStream = io.rx.pktFrag
      .throwWhen(
        io.txQCtrl.wrongStateFlush || io.txQCtrl.errorFlush || (hasAeth && hasReservedCode)
      ),
    companyExtractFunc = (inputStream: Stream[Fragment[RdmaDataPkt]]) =>
      new Composite(inputStream, "RespVerifier_companyExtractFunc") {
        val hasAeth = OpCode.respPktHasAeth(inputStream.bth.opcode)
        val aeth = inputStream.extractAeth()
        val result = Flow(AETH())
        result.valid := hasAeth
        result.payload := aeth
      }.result
  )

  // If error, discard all incoming response
  // TODO: incoming response stream needs retry flush or not?
  io.tx <-/< respWithExtractedAeth.map { payloadData =>
    val result = cloneOf(io.tx.payloadType)
    result.last := payloadData._1.last
    result.pktFrag := payloadData._1.fragment
    result.aeth := payloadData._2
    result.workCompStatus := workCompStatus
    result
  }
}

// Handle coalesce ACK, normal ACK and retry ACK
class CoalesceAndNormalAndRetryNakHandler(busWidth: BusWidth.Value)
    extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    //    val rx = slave(Stream(Acknowledge()))
    val rx = slave(Stream(Fragment(ResponseWithAeth(busWidth))))
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
    val coalesceAckDone = out(Bool())
    val cachedWorkReqAndRespWithAeth = master(
      Stream(Fragment(CachedWorkReqAndRespWithAeth(busWidth)))
    )
    //    val workCompAndAck = master(Stream(WorkCompAndAck()))
    val retryNotifier = out(SqRetryNotifier())
    val errNotifier = out(SqErrNotifier())
  }

  val inputAckValid = io.rx.valid
  val inputRespPktFrag = io.rx.pktFrag
  val inputCachedWorkReqValid = io.cachedWorkReqPop.valid

  val isNormalAck = io.rx.aeth.isNormalAck()
  val isRetryNak = io.rx.aeth.isRetryNak()
  val isErrAck = io.rx.aeth.isFatalNak()

  val isDupAck = PsnUtil.lt(
    inputRespPktFrag.bth.psn,
    io.cachedWorkReqPop.psnStart,
    io.qpAttr.npsn
  )
  val isGhostAck = inputAckValid && !inputCachedWorkReqValid

  val isReadWorkReq =
    WorkReqOpCode.isReadReq(io.cachedWorkReqPop.workReq.opcode)
  val isAtomicWorkReq =
    WorkReqOpCode.isAtomicReq(io.cachedWorkReqPop.workReq.opcode)

  val cachedWorkReqPsnEnd =
    io.cachedWorkReqPop.psnStart + (io.cachedWorkReqPop.pktNum - 1)
  val isTargetWorkReq = PsnUtil.gte(
    inputRespPktFrag.bth.psn,
    io.cachedWorkReqPop.psnStart,
    io.qpAttr.npsn
  ) &&
    PsnUtil.lte(inputRespPktFrag.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val isWholeWorkReqAck =
    PsnUtil.gte(inputRespPktFrag.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val isPartialTargetWorkReqAck = isTargetWorkReq && !isWholeWorkReqAck
  val isWholeTargetWorkReqAck = isTargetWorkReq && isWholeWorkReqAck

  val hasCoalesceAck =
    PsnUtil.gt(inputRespPktFrag.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val hasImplicitRetry = !isTargetWorkReq && (isReadWorkReq || isAtomicWorkReq)
  val hasExplicitRetry = isTargetWorkReq && isRetryNak

  // For the following fire condition, it must make sure inputAckValid or inputCachedWorkReqValid
  val fireBothCachedWorkReqAndAck =
    inputAckValid && inputCachedWorkReqValid && ((isWholeTargetWorkReqAck && isNormalAck) || (isTargetWorkReq && isErrAck))
  val fireCachedWorkReqOnly =
    (inputAckValid && inputCachedWorkReqValid && hasCoalesceAck && !hasImplicitRetry) || io.txQCtrl.wrongStateFlush || io.txQCtrl.errorFlush
  val fireAckOnly =
    isGhostAck || (inputAckValid && inputCachedWorkReqValid && (isDupAck || (isTargetWorkReq && isRetryNak) || (isPartialTargetWorkReqAck && isNormalAck)))
  val zipCachedWorkReqAndAck = StreamZipByCondition(
    leftInputStream = io.cachedWorkReqPop,
    // Flush io.rx
    rightInputStream =
      io.rx.throwWhen(io.txQCtrl.wrongStateFlush || io.txQCtrl.retryFlush),
    // Coalesce ACK pending WR, or errorFlush
    leftFireCond = fireCachedWorkReqOnly,
    // Discard duplicate ACK or ghost ACK if no pending WR
    rightFireCond = fireAckOnly,
    bothFireCond = fireBothCachedWorkReqAndAck
  )
  when(inputAckValid || inputCachedWorkReqValid) {
    assert(
      assertion = CountOne(
        fireBothCachedWorkReqAndAck ## fireCachedWorkReqOnly ## fireAckOnly
      ) <= 1,
      message =
        L"${REPORT_TIME} time: fire CachedWorkReq only, fire ACK only, and fire both should be mutually exclusive, but fireBothCachedWorkReqAndAck=${fireBothCachedWorkReqAndAck}, fireCachedWorkReqOnly=${fireCachedWorkReqOnly}, fireAckOnly=${fireAckOnly}".toSeq,
      severity = FAILURE
    )
  }

  val zipCachedWorkReqValid = zipCachedWorkReqAndAck._1
  val zipRespValid = zipCachedWorkReqAndAck._3
//  when(zipCachedWorkReqAndAck.valid && zipCachedWorkReqValid) {
//    assert(
//      assertion = zipRespValid,
//      message =
//        L"${REPORT_TIME} time: zipRespValid=${zipRespValid} should be true when zipCachedWorkReqAndAck.valid=${zipCachedWorkReqAndAck.valid} and zipCachedWorkReqValid=${zipCachedWorkReqValid}",
//      severity = FAILURE
//    )
//  }
  io.cachedWorkReqAndRespWithAeth <-/< zipCachedWorkReqAndAck
    .takeWhen(zipCachedWorkReqValid)
    .translateWith {
      val result = cloneOf(io.cachedWorkReqAndRespWithAeth.payloadType)
      result.cachedWorkReq := zipCachedWorkReqAndAck._2
      result.respValid := zipRespValid
      result.pktFrag := zipCachedWorkReqAndAck._4.pktFrag
      result.aeth := zipCachedWorkReqAndAck._4.aeth
      result.workCompStatus := zipCachedWorkReqAndAck._4.workCompStatus
      result.last := zipCachedWorkReqAndAck._4.last
      result
    }

  val retryReport = new Area {
    // Handle response timeout retry
    val respTimer = Timeout(respTimeOutOptionToTimeNum(MAX_RESP_TIMEOUT_OPTION))
    val respTimeOutThreshold = io.qpAttr.getRespTimeOutCycleNum()
    val respTimeOutRetry = respTimeOutThreshold =/= INFINITE_RESP_TIMEOUT &&
      respTimer.counter.value > respTimeOutThreshold
    // TODO: should use io.rx.fire or io.rx.valid to clear response timer?
    when(
      io.txQCtrl.wrongStateFlush || inputAckValid || !io.cachedWorkReqPop.valid || io.txQCtrl.retryFlush
    ) {
      respTimer.clear()
    }

    // Retry notification
    io.retryNotifier.pulse := (respTimeOutRetry || hasExplicitRetry || hasImplicitRetry) && io.rx.fire
    io.retryNotifier.psnStart := inputRespPktFrag.bth.psn
    io.retryNotifier.reason := RetryReason.NO_RETRY
    io.retryNotifier.receivedRnrTimeOut := DEFAULT_MIN_RNR_TIME_OUT
    when(hasImplicitRetry) {
      io.retryNotifier.psnStart := io.cachedWorkReqPop.psnStart
      io.retryNotifier.reason := RetryReason.IMPLICIT_ACK
    } elsewhen (respTimeOutRetry) {
      io.retryNotifier.psnStart := io.cachedWorkReqPop.psnStart
      io.retryNotifier.reason := RetryReason.RESP_TIMEOUT
    } elsewhen (io.rx.aeth.isRnrNak()) {
      io.retryNotifier.reason := RetryReason.RNR
      io.retryNotifier.receivedRnrTimeOut := io.rx.aeth.value
    } elsewhen (io.rx.aeth.isSeqNak()) {
      io.retryNotifier.reason := RetryReason.SEQ_ERR
    }
  }

  val fatalErrReport = new Area {
    io.errNotifier.setNoErr()
    when(!io.txQCtrl.wrongStateFlush) {
      when(inputAckValid && isErrAck) {
        io.errNotifier.setFromAeth(io.rx.aeth)
      } elsewhen (io.rx.workCompStatus =/= WorkCompStatus.SUCCESS) {
        io.errNotifier.setLocalErr()
      }
    }
  }

  io.coalesceAckDone := io.txQCtrl.wrongStateFlush && io.rx.fire
}

class ReadRespLenCheck(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val errNotifier = out(SqErrNotifier())
    val cachedWorkReqAndRespWithAethIn = slave(
      Stream(Fragment(CachedWorkReqAndRespWithAeth(busWidth)))
    )
    val cachedWorkReqAndRespWithAethOut = master(
      Stream(Fragment(CachedWorkReqAndRespWithAeth(busWidth)))
    )
  }

  val opcode = io.cachedWorkReqAndRespWithAethIn.pktFrag.bth.opcode
  val isReadRespPkt = OpCode.isReadRespPkt(opcode)
  val readRespLenCheckFlow = io.cachedWorkReqAndRespWithAethIn.toFlowFire
    .takeWhen(isReadRespPkt)
    .translateWith {
      val lenCheckElements = LenCheckElements(busWidth)
      lenCheckElements.opcode := io.cachedWorkReqAndRespWithAethIn.pktFrag.bth.opcode
      lenCheckElements.psn := io.cachedWorkReqAndRespWithAethIn.pktFrag.bth.psn
//      lenCheckElements.psnStart := io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.psnStart
      lenCheckElements.padCnt := io.cachedWorkReqAndRespWithAethIn.pktFrag.bth.padCnt
      lenCheckElements.lenBytes := io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.workReq.lenBytes
      lenCheckElements.mty := io.cachedWorkReqAndRespWithAethIn.pktFrag.mty

      val result = Fragment(cloneOf(lenCheckElements))
      result.fragment := lenCheckElements
      result.last := io.cachedWorkReqAndRespWithAethIn.last
      result
    }

  val pmtuLenBytes = getPmtuPktLenBytes(io.qpAttr.pmtu)

  val totalLenFlow = ReqRespTotalLenCalculator(
    flush = io.txQCtrl.wrongStateFlush,
    pktFireFlow = readRespLenCheckFlow,
    pmtuLenBytes = pmtuLenBytes
  )

  val readRespLenCheckWithFirstPktPsnFlow = FlowExtractCompany(
    inputFlow = readRespLenCheckFlow,
    companyExtractFunc =
      (readRespPktFragFlow: Flow[Fragment[LenCheckElements]]) =>
        new Composite(
          readRespPktFragFlow,
          "ReadRespLenCheck_companyExtractFunc"
        ) {
          val result = Flow(UInt(PSN_WIDTH bits))
          result.valid := readRespPktFragFlow.isFirst && OpCode
            .isFirstOrOnlyReadRespPkt(readRespPktFragFlow.opcode)
          result.payload := readRespPktFragFlow.psn
        }.result
  )
  // Read first response might not start from WR PsnStart, due to retry
  val readRespFirstPktPsn = readRespLenCheckWithFirstPktPsnFlow._2
  val workReqPsnStart = io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.psnStart
  val workReqOrigTotalLenBytes =
    readRespLenCheckWithFirstPktPsnFlow._1.lenBytes
  val psnDiff = PsnUtil.diff(readRespFirstPktPsn, workReqPsnStart)
  val readRespOffset = PsnUtil.psnDiffLenBytes(psnDiff, io.qpAttr.pmtu)
  val readRespExpectedTotalLenBytes =
    workReqOrigTotalLenBytes - readRespOffset

  val isRespTotalLenCheckErr = False
  val totalLenValid = totalLenFlow.valid
  val totalLenBytes = totalLenFlow.totalLenOutput
  val isPktLenCheckErr = totalLenFlow.isPktLenCheckErr

  // TODO: check valid WC status should be with last fire
  val workCompStatus = WorkCompStatus()
  workCompStatus := io.cachedWorkReqAndRespWithAethIn.workCompStatus
  io.errNotifier.setNoErr()
  when(totalLenValid) {
    assert(
      assertion = io.cachedWorkReqAndRespWithAethIn.lastFire,
      message =
        L"${REPORT_TIME} time: totalLenValid=${totalLenValid} should == io.cachedWorkReqAndRespWithAethIn.lastFire=${io.cachedWorkReqAndRespWithAethIn.lastFire}".toSeq,
      severity = FAILURE
    )

//    report(
//      L"${REPORT_TIME} time: readRespExpectedTotalLenBytes=${readRespExpectedTotalLenBytes}, totalLenBytes=${totalLenBytes}, isRespTotalLenCheckErr=${isRespTotalLenCheckErr}, isPktLenCheckErr=${isPktLenCheckErr}"
//    )
    isRespTotalLenCheckErr := readRespExpectedTotalLenBytes =/= totalLenBytes
    when(!io.txQCtrl.wrongStateFlush) {
      when(isRespTotalLenCheckErr || isPktLenCheckErr) {
        io.errNotifier.setLocalErr()
      }
    }
  }

  io.cachedWorkReqAndRespWithAethOut <-/< io.cachedWorkReqAndRespWithAethIn
    .map { payloadData =>
      val result = cloneOf(io.cachedWorkReqAndRespWithAethOut.payloadType)
      result := payloadData
      when(totalLenValid && (isRespTotalLenCheckErr || isPktLenCheckErr)) {
        when(payloadData.workCompStatus === WorkCompStatus.SUCCESS) {
          result.workCompStatus := WorkCompStatus.LOC_LEN_ERR
        }
      }
      result
    }
}

class ReadAtomicRespVerifierAndFatalNakNotifier(busWidth: BusWidth.Value)
    extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val errNotifier = out(SqErrNotifier())
    val cachedWorkReqAndRespWithAeth = slave(
      Stream(Fragment(CachedWorkReqAndRespWithAeth(busWidth)))
    )
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val cachedWorkReqAndAck = master(Stream(Fragment(CachedWorkReqAndAck())))
    val readAtomicRespWithDmaInfoBus = master(
      SqReadAtomicRespWithDmaInfoBus(busWidth)
    )
  }

  when(
    io.cachedWorkReqAndRespWithAeth.valid &&
      io.cachedWorkReqAndRespWithAeth.workCompStatus === WorkCompStatus.SUCCESS
  ) {
    assert(
      assertion = io.cachedWorkReqAndRespWithAeth.aeth.isNormalAck(),
      message =
        L"${REPORT_TIME} time: illegal io.cachedWorkReqAndRespWithAeth, io.cachedWorkReqAndRespWithAeth.aeth.code=${io.cachedWorkReqAndRespWithAeth.aeth.code} should be normal ACK, when io.cachedWorkReqAndRespWithAeth.workCompStatus=${io.cachedWorkReqAndRespWithAeth.workCompStatus}".toSeq,
      severity = FAILURE
    )
  }

  // Build QpAddrCacheAgentReadReq according to RqReqWithRxBufAndDmaInfo
  val buildAddrCacheQuery =
    (pktFragStream: Stream[Fragment[CachedWorkReqAndRespWithAeth]]) =>
      new Composite(
        pktFragStream,
        "ReadAtomicRespVerifierAndFatalNakNotifier_buildAddrCacheQuery"
      ) {
        val cachedWorkReq = pktFragStream.cachedWorkReq

        val addrCacheReadReq = QpAddrCacheAgentReadReq()
        addrCacheReadReq.sqpn := io.qpAttr.sqpn
        addrCacheReadReq.psn := cachedWorkReq.psnStart
        addrCacheReadReq.key := cachedWorkReq.workReq.lkey
        addrCacheReadReq.pdId := io.qpAttr.pdId
        addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = False)
        addrCacheReadReq.accessType.set(AccessPermission.LOCAL_WRITE)
        addrCacheReadReq.va := cachedWorkReq.workReq.laddr
        addrCacheReadReq.dataLenBytes := cachedWorkReq.workReq.lenBytes
      }.addrCacheReadReq

  // Only expect AddrCache query response when the input has no NAK
  val expectAddrCacheResp =
    (_: Stream[Fragment[CachedWorkReqAndRespWithAeth]]) => True

  // Only send out AddrCache query when the input data is the first fragment of
  // send/write/read/atomic request
  val addrCacheQueryCond =
    (pktFragStream: Stream[Fragment[CachedWorkReqAndRespWithAeth]]) =>
      new Composite(
        pktFragStream,
        "ReadAtomicRespVerifierAndFatalNakNotifier_addrCacheQueryCond"
      ) {
        val result = pktFragStream.isFirst && (
          OpCode.isFirstOrOnlyReadRespPkt(pktFragStream.pktFrag.bth.opcode) ||
            OpCode.isAtomicRespPkt(pktFragStream.pktFrag.bth.opcode)
        )
      }.result

  // Only join AddrCache query response when the input data is the last fragment of
  // the only or last send/write/read/atomic request
  val addrCacheQueryRespJoinCond =
    (pktFragStream: Stream[Fragment[CachedWorkReqAndRespWithAeth]]) =>
      new Composite(
        pktFragStream,
        "ReadAtomicRespVerifierAndFatalNakNotifier_addrCacheQueryRespJoinCond"
      ) {
        val isReadLastOrOnlyRespOrAtomicResp = (opcode: Bits) => {
          OpCode.isLastOrOnlyReadRespPkt(opcode) ||
          OpCode.isAtomicRespPkt(opcode)
        }
        val result = pktFragStream.valid && pktFragStream.isLast &&
          isReadLastOrOnlyRespOrAtomicResp(pktFragStream.pktFrag.bth.opcode)
      }.result

  val (joinStream, bufLenErr, keyErr, accessErr, addrCheckErr) =
    AddrCacheQueryAndRespHandler(
      io.cachedWorkReqAndRespWithAeth.throwWhen(io.txQCtrl.wrongStateFlush),
      io.addrCacheRead,
      inputAsRdmaDataPktFrag =
        (cachedWorkReqAdnRespWithAeth: CachedWorkReqAndRespWithAeth) =>
          cachedWorkReqAdnRespWithAeth.pktFrag,
      buildAddrCacheQuery = buildAddrCacheQuery,
      addrCacheQueryCond = addrCacheQueryCond,
      expectAddrCacheResp = expectAddrCacheResp,
      addrCacheQueryRespJoinCond = addrCacheQueryRespJoinCond
    )

  val (outStream4Dma, outStreamWithAck) = StreamConditionalFork2(
    joinStream,
    forkCond = !addrCheckErr
  )

  io.errNotifier.setNoErr()
  when(!io.txQCtrl.wrongStateFlush && addrCheckErr) {
    io.errNotifier.setLocalErr()
//    report(L"${REPORT_TIME} time:  addrCheckErr=${addrCheckErr}, io.txQCtrl.wrongStateFlush=${io.txQCtrl.wrongStateFlush}, io.errNotifier.pulse=${io.errNotifier.pulse}")
  }

  io.cachedWorkReqAndAck <-/< outStreamWithAck.map { payloadData =>
    val result = cloneOf(io.cachedWorkReqAndAck.payloadType)
    result.workCompStatus := payloadData._1.workCompStatus

    val ack = Acknowledge()
    ack.bth := payloadData._1.pktFrag.bth
    ack.aeth := payloadData._1.aeth
    when(payloadData._1.aeth.isNormalAck()) {
      when(bufLenErr) {
        ack.aeth.set(AckType.NAK_INV)
        result.workCompStatus := WorkCompStatus.LOC_LEN_ERR
      } elsewhen (keyErr || accessErr) {
        ack.aeth.set(AckType.NAK_RMT_ACC)
        result.workCompStatus := WorkCompStatus.LOC_PROT_ERR
      }
    }

    result.cachedWorkReq := payloadData._1.cachedWorkReq
    result.ackValid := payloadData._1.respValid
    result.ack := ack
    result.last := payloadData.last
    result
  }

  io.readAtomicRespWithDmaInfoBus.respWithDmaInfo <-/< outStream4Dma.map {
    payloadData =>
      val result =
        cloneOf(io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.payloadType)
      result.pktFrag := payloadData._1.pktFrag
      result.pa := payloadData._3.pa
      result.workReqId := payloadData._1.cachedWorkReq.workReq.id
      result.last := payloadData.last
      result
  }
}

// TODO: check read response length pass otherwise generate WC with status LOC_LEN_ERR
class ReadAtomicRespDmaReqInitiator(busWidth: BusWidth.Value)
    extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val readAtomicRespWithDmaInfoBus = slave(
      SqReadAtomicRespWithDmaInfoBus(busWidth)
    )
    val readRespDmaWriteReq = master(DmaWriteReqBus(busWidth))
    val atomicRespDmaWriteReq = master(DmaWriteReqBus(busWidth))
  }

  val inputValid = io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.valid
  val inputPktFrag = io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag

  val (readRespIdx, atomicRespIdx) = (0, 1)
  val twoStreams = StreamDeMuxByConditions(
    io.readAtomicRespWithDmaInfoBus.respWithDmaInfo
      .throwWhen(io.txQCtrl.wrongStateFlush),
    OpCode.isReadRespPkt(inputPktFrag.bth.opcode),
    OpCode.isAtomicRespPkt(inputPktFrag.bth.opcode)
  )

  val isLast = io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.isLast
  val pa = io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pa
  val workReqId = io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.workReqId

  io.readRespDmaWriteReq.req <-/< twoStreams(readRespIdx)
    .throwWhen(io.txQCtrl.wrongStateFlush)
    .translateWith {
      val result =
        cloneOf(io.readRespDmaWriteReq.req.payloadType) // DmaWriteReq(busWidth)
      result.last := isLast
      result.set(
        initiator = DmaInitiator.SQ_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        pa = pa,
        workReqId = workReqId,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      result
    }

  io.atomicRespDmaWriteReq.req <-/< twoStreams(atomicRespIdx)
    .throwWhen(io.txQCtrl.wrongStateFlush)
    .translateWith {
      val result = cloneOf(io.readRespDmaWriteReq.req.payloadType)
      result.last := isLast
      result.set(
        initiator = DmaInitiator.SQ_ATOMIC_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        pa = pa,
        workReqId = workReqId,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      result
    }
}

class WorkCompGen extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
//    val workCompAndAck = slave(Stream(WorkCompAndAck()))
    val cachedWorkReqAndAck = slave(Stream(Fragment(CachedWorkReqAndAck())))
    val readRespDmaWriteResp = slave(DmaWriteRespBus())
    val atomicRespDmaWriteResp = slave(DmaWriteRespBus())
    val workCompPush = master(Stream(WorkComp()))
  }

  // TODO: support atomic response handling
  io.atomicRespDmaWriteResp.resp.ready := False

  val insertIntoQueue = new Area {
    val isReadWorkReq = WorkReqOpCode.isReadReq(
      io.cachedWorkReqAndAck.cachedWorkReq.workReq.opcode
    )
    val isAtomicWorkReq = WorkReqOpCode.isAtomicReq(
      io.cachedWorkReqAndAck.cachedWorkReq.workReq.opcode
    )
    val isWorkCompNeeded = isReadWorkReq || isAtomicWorkReq ||
      io.cachedWorkReqAndAck.cachedWorkReq.workReq.flags.signaled

    val inputWorkReqValid = io.cachedWorkReqAndAck.valid
    val inputCachedWorkReq = io.cachedWorkReqAndAck.cachedWorkReq
    val inputAckValid = inputWorkReqValid && io.cachedWorkReqAndAck.ackValid
    //  val inputAck = io.cachedWorkReqAndAck.ack

    val cachedWorkReqPsnEnd =
      inputCachedWorkReq.psnStart + (inputCachedWorkReq.pktNum - 1)
    val isWorkReqDone = io.cachedWorkReqAndAck.isLast
    when(isWorkReqDone && inputAckValid) {
      val ackPsn = io.cachedWorkReqAndAck.ack.bth.psn
      assert(
        assertion = ackPsn === cachedWorkReqPsnEnd,
        message =
          L"${REPORT_TIME} time: io.cachedWorkReqAndAck.ack.bth.psn=${ackPsn} should == cachedWorkReqPsnEnd=${cachedWorkReqPsnEnd}, when io.cachedWorkReqAndAck.isLast=${io.cachedWorkReqAndAck.isLast}".toSeq,
        severity = FAILURE
      )
    }
    /*
    val inputWorkReqAndAckQueue = StreamFifoLowLatency(
      io.cachedWorkReqAndAck.payloadType(),
      DMA_WRITE_FIFO_DEPTH
    )
    inputWorkReqAndAckQueue.io.push << io.cachedWorkReqAndAck
      .takeWhen(isWorkReqDone && isWorkCompNeeded)
    // DO NOT flush inputWorkReqAndAckQueue even when error
//    inputWorkReqAndAckQueue.io.flush := io.txQCtrl.errorFlush
    assert(
      assertion = inputWorkReqAndAckQueue.io.push.ready,
      message =
        L"${REPORT_TIME} time: inputWorkReqAndAckQueue is full, inputWorkReqAndAckQueue.io.push.ready=${inputWorkReqAndAckQueue.io.push.ready}, inputWorkReqAndAckQueue.io.occupancy=${inputWorkReqAndAckQueue.io.occupancy}, which is not allowed in WorkCompGen".toSeq,
      severity = FAILURE
    )
    val inputWorkReqAndAckQueuePop = inputWorkReqAndAckQueue.io.pop.combStage()
     */
    val inputWorkReqAndAckQueuePop = FixedLenQueue(
      io.cachedWorkReqAndAck.payloadType(),
      depth = DMA_WRITE_FIFO_DEPTH,
      push = io.cachedWorkReqAndAck.takeWhen(isWorkReqDone && isWorkCompNeeded),
      // DO NOT flush inputWorkReqAndAckQueue even when error
      flush = False, // io.txQCtrl.errorFlush
      queueName = "inputWorkReqAndAckQueue"
    )
  }

  val joinWithDmaResp = new Area {
//    val workReqInQueueValid = insertIntoQueue.inputWorkReqAndAckQueuePop.valid
//    val inputDmaWriteRespValid = io.readRespDmaWriteResp.resp.valid
    val inputHasNak =
      insertIntoQueue.inputWorkReqAndAckQueuePop.ackValid && !insertIntoQueue.inputWorkReqAndAckQueuePop.ack.aeth
        .isNormalAck()

    val isReadWorkReq = WorkReqOpCode.isReadReq(
      insertIntoQueue.inputWorkReqAndAckQueuePop.cachedWorkReq.workReq.opcode
    )
    val isAtomicWorkReq = WorkReqOpCode.isAtomicReq(
      insertIntoQueue.inputWorkReqAndAckQueuePop.cachedWorkReq.workReq.opcode
    )

    val joinDmaRespCond = (isReadWorkReq || isAtomicWorkReq) && !inputHasNak
    val joinStream = StreamConditionalJoin(
      insertIntoQueue.inputWorkReqAndAckQueuePop,
      io.readRespDmaWriteResp.resp,
      joinCond = joinDmaRespCond
    )
    when(joinStream.fire && joinDmaRespCond) {
      assert(
        assertion =
          joinStream._1.cachedWorkReq.workReq.id === joinStream._2.workReqId,
        message =
          L"${REPORT_TIME} time: inputWorkReqAndAckQueuePop.cachedWorkReq.workReq.id=${joinStream._1.cachedWorkReq.workReq.id} should == io.readRespDmaWriteResp.resp.workReqId=${joinStream._2.workReqId}, when WR opcode=${insertIntoQueue.inputWorkReqAndAckQueuePop.cachedWorkReq.workReq.opcode}, joinStream.fire=${joinStream.fire}, joinDmaRespCond=${joinDmaRespCond}, io.readRespDmaWriteResp.resp.fire=${io.readRespDmaWriteResp.resp.fire}".toSeq,
        severity = FAILURE
      )
    }
  }

  val workCompFlushStatus = WorkCompStatus()
  // TODO: what status should the read/atomic requests before error ACK have?
  workCompFlushStatus := WorkCompStatus.WR_FLUSH_ERR
  // TODO: only explicit ACK generate WC
  io.workCompPush <-/< joinWithDmaResp.joinStream.map { payloadData =>
    val isExplicitAck = payloadData._1.ackValid
    val isErrAck = payloadData._1.ack.aeth.isFatalNak()
    val result = cloneOf(io.workCompPush.payloadType)
    when(isExplicitAck && isErrAck) {
      // Handle fatal NAK
      result.setFromWorkReq(
        workReq = payloadData._1.cachedWorkReq.workReq,
        dqpn = io.qpAttr.dqpn,
        status = payloadData._1.ack.aeth.toWorkCompStatus()
      )
    } elsewhen (payloadData._1.workCompStatus =/= WorkCompStatus.SUCCESS) {
      // Handle local detected error
      result.setFromWorkReq(
        workReq = payloadData._1.cachedWorkReq.workReq,
        dqpn = io.qpAttr.dqpn,
        status = payloadData._1.workCompStatus
      )
    } elsewhen (io.txQCtrl.errorFlush) {
      // TODO: verify error flush logic
      // Handle errorFlush
      result.setFromWorkReq(
        workReq = payloadData._1.cachedWorkReq.workReq,
        dqpn = io.qpAttr.dqpn,
        status = workCompFlushStatus
      )
    } otherwise {
      // Handle coalesce and normal ACK
      result.setSuccessFromWorkReq(
        workReq = payloadData._1.cachedWorkReq.workReq,
        dqpn = io.qpAttr.dqpn
      )
    }
    result
  }
}
