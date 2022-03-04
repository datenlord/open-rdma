package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import ConstantSettings._
import RdmaConstants._

class RespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val errNotifier = out(SqErrNotifier())
    val retryNotifier = out(SqRetryNotifier())
    val coalesceAckDone = out(Bool())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
    val workReqQuery = master(WorkReqCacheQueryBus())
    val workComp = master(Stream(WorkComp()))
    // Save read/atomic response data to main memory
    val readRespDmaWrite = master(DmaWriteBus(busWidth))
    val atomicRespDmaWrite = master(DmaWriteBus(busWidth))
  }

  val respAckExtractor = new RespAckExtractor(busWidth)
//  respAckExtractor.io.qpAttr := io.qpAttr
  respAckExtractor.io.txQCtrl := io.txQCtrl
  respAckExtractor.io.rx << io.rx

  val readAtomicRespVerifierAndFatalNakNotifier =
    new ReadAtomicRespVerifierAndFatalNakNotifier(busWidth)
  readAtomicRespVerifierAndFatalNakNotifier.io.qpAttr := io.qpAttr
  readAtomicRespVerifierAndFatalNakNotifier.io.txQCtrl := io.txQCtrl
  readAtomicRespVerifierAndFatalNakNotifier.io.rxAck << respAckExtractor.io.txAck
  readAtomicRespVerifierAndFatalNakNotifier.io.readAtomicResp << respAckExtractor.io.readAtomicResp
  io.workReqQuery << readAtomicRespVerifierAndFatalNakNotifier.io.workReqQuery
  io.addrCacheRead << readAtomicRespVerifierAndFatalNakNotifier.io.addrCacheRead
  io.errNotifier := readAtomicRespVerifierAndFatalNakNotifier.io.errNotifier

  val coalesceAndNormalAndRetryNakHandler =
    new CoalesceAndNormalAndRetryNakHandler
  coalesceAndNormalAndRetryNakHandler.io.qpAttr := io.qpAttr
  coalesceAndNormalAndRetryNakHandler.io.txQCtrl := io.txQCtrl
  coalesceAndNormalAndRetryNakHandler.io.rx << readAtomicRespVerifierAndFatalNakNotifier.io.txAck
  coalesceAndNormalAndRetryNakHandler.io.cachedWorkReqPop << io.cachedWorkReqPop
  io.coalesceAckDone := coalesceAndNormalAndRetryNakHandler.io.coalesceAckDone
  io.retryNotifier := coalesceAndNormalAndRetryNakHandler.io.retryNotifier
//  io.nakNotifier := coalesceAndNormalAndRetryNakHandler.io.nakNotifier

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
  workCompGen.io.workCompAndAck << coalesceAndNormalAndRetryNakHandler.io.workCompAndAck
  workCompGen.io.readRespDmaWriteResp.resp << io.readRespDmaWrite.resp
  workCompGen.io.atomicRespDmaWriteResp.resp << io.atomicRespDmaWrite.resp
  io.workComp << workCompGen.io.workCompPush
}

// Discard all invalid responses:
// - NAK with reserved code;
// - Target QP not exists; dropped by head verifier
// - Ghost ACK;
class RespAckExtractor(busWidth: BusWidth) extends Component {
  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val rx = slave(RdmaDataBus(busWidth))
    val txAck = master(Stream(Acknowledge()))
    val readAtomicResp = master(RdmaDataBus(busWidth))
  }

  val inputRespValid = io.rx.pktFrag.valid
  val inputRespPktFrag = io.rx.pktFrag.payload
  when(inputRespValid) {
    assert(
      assertion = OpCode.isRespPkt(inputRespPktFrag.bth.opcode),
      message =
        L"${REPORT_TIME} time: RespVerifier received non-response packet with opcode=${inputRespPktFrag.bth.opcode}, PSN=${inputRespPktFrag.bth.psn}",
      severity = FAILURE
    )
  }

  val acknowledge = Acknowledge()
  acknowledge.bth := inputRespPktFrag.bth
  acknowledge.aeth.assignFromBits(
    inputRespPktFrag.data(
      (busWidth.id - widthOf(BTH()) - widthOf(AETH())) until
        (busWidth.id - widthOf(BTH()))
    )
  )

  val hasAeth = OpCode.respPktHasAeth(inputRespPktFrag.bth.opcode)
  // Just discard ACK with reserved code
  val hasReservedCode = acknowledge.aeth.isReserved()
  when(inputRespValid && hasAeth) {
    assert(
      assertion = !hasReservedCode,
      message =
        L"${REPORT_TIME} time: acknowledge has reserved code or value, PSN=${acknowledge.bth.psn}, aeth.code=${acknowledge.aeth.code}, aeth.value=${acknowledge.aeth.value}",
      severity =
        FAILURE // TODO: change to ERROR, since ACK with reserved code just discard
    )
  }

  val isReadResp =
    OpCode.isReadRespPkt(io.rx.pktFrag.bth.opcode)
  val isAtomicResp =
    OpCode.isAtomicRespPkt(io.rx.pktFrag.bth.opcode)

  // If error, discard all incoming response
  // TODO: incoming response stream needs retry flush or not?
  val (resp4ReadAtomic, resp4Ack) = StreamFork2(
    io.rx.pktFrag
      .throwWhen(io.txQCtrl.wrongStateFlush || (hasAeth && hasReservedCode))
  )
  io.txAck <-/< resp4Ack.takeWhen(hasAeth).translateWith(acknowledge)
  io.readAtomicResp.pktFrag <-/< resp4ReadAtomic.takeWhen(
    isReadResp || isAtomicResp
  )
}

class ReadAtomicRespVerifierAndFatalNakNotifier(busWidth: BusWidth)
    extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val readAtomicResp = slave(RdmaDataBus(busWidth))
    val errNotifier = out(SqErrNotifier())
    val workReqQuery = master(WorkReqCacheQueryBus())
    val addrCacheRead = master(QpAddrCacheAgentReadBus())
    val rxAck = slave(Stream(Acknowledge()))
    val txAck = master(Stream(Acknowledge()))
    val readAtomicRespWithDmaInfoBus = master(
      SqReadAtomicRespWithDmaInfoBus(busWidth)
    )
  }

  // The previous read response packet opcode, used to check the read response opcode sequence
  val preReadRespPktOpCodeReg = RegInit(
    B(OpCode.RDMA_READ_RESPONSE_ONLY.id, OPCODE_WIDTH bits)
  )
  // CSR needs to reset when QP in error state
  when(io.txQCtrl.wrongStateFlush) {
    preReadRespPktOpCodeReg := OpCode.RDMA_READ_RESPONSE_ONLY.id
  }
  val isReadResp = OpCode.isReadRespPkt(io.readAtomicResp.pktFrag.bth.opcode)
  when(isReadResp) {
    when(io.readAtomicResp.pktFrag.lastFire) {
      preReadRespPktOpCodeReg := io.readAtomicResp.pktFrag.bth.opcode
    }
  }
  val isReadRespOpCodeSeqCheckPass = isReadResp && OpCodeSeq.checkReadRespSeq(
    preReadRespPktOpCodeReg,
    io.readAtomicResp.pktFrag.bth.opcode
  )

  val inputAckValid = io.rxAck.valid
  val inputReadAtomicRespValid = io.readAtomicResp.pktFrag.valid

  val isErrAck = io.rxAck.aeth.isErrAck()
  // SQ into ERR state if fatal error
  io.errNotifier.setNoErr()
  when(!io.txQCtrl.wrongStateFlush) {
    when(inputAckValid && isErrAck) {
      io.errNotifier.setFromAeth(io.rxAck.aeth)
    } elsewhen (inputReadAtomicRespValid && isReadResp && !isReadRespOpCodeSeqCheckPass) {
      io.errNotifier.setLocalErr()
      report(
        message =
          L"read response opcode sequence error: previous opcode=${preReadRespPktOpCodeReg}, current opcode=${io.readAtomicResp.pktFrag.bth.opcode}",
        severity = FAILURE
      )
    }
  }

  // TODO: does it need to flush whole SQ when retry triggered?
  val ackQueue =
    StreamFifoLowLatency(Acknowledge(), depth = MAX_COALESCE_ACK_NUM)
  ackQueue.io.push << io.rxAck
  // TODO: verify that once retry started, discard all responses before send out the retry request
  ackQueue.io.flush := io.txQCtrl.retryFlush || io.txQCtrl.wrongStateFlush
  io.txAck <-/< ackQueue.io.pop

  // Only send out WorkReqCache query when the input data is the first fragment of
  // the only or first read responses or atomic responses
  val workReqCacheQueryCond =
    (pktFragStream: Stream[Fragment[RdmaDataPkt]]) =>
      new Composite(pktFragStream, "workReqCacheQueryCond") {
        val isReadFirstOrOnlyRespOrAtomicResp = (opcode: Bits) => {
          OpCode.isFirstOrOnlyReadRespPkt(opcode) ||
            OpCode.isAtomicRespPkt(opcode)
        }
        val result = pktFragStream.valid && pktFragStream.isFirst &&
          isReadFirstOrOnlyRespOrAtomicResp(pktFragStream.bth.opcode)
      }.result
  // Only join AddrCache query response when the input data is the last fragment of
  // the only or last read responses or atomic responses
  val addrCacheQueryRespJoinCond =
    (pktFragStream: Stream[Fragment[RdmaDataPkt]]) =>
      new Composite(pktFragStream, "addrCacheQueryRespJoinCond") {
        val isReadLastOrOnlyRespOrAtomicResp = (opcode: Bits) => {
          OpCode.isLastOrOnlyReadRespPkt(opcode) ||
            OpCode.isAtomicRespPkt(opcode)
        }
        val result = pktFragStream.valid && pktFragStream.isLast &&
          isReadLastOrOnlyRespOrAtomicResp(pktFragStream.bth.opcode)
      }.result

  val (everyFirstReadAtomicRespPktFragStream, allReadAtomicRespPktFragStream) =
    StreamConditionalFork2(
      io.readAtomicResp.pktFrag.throwWhen(io.txQCtrl.wrongStateFlush),
      forkCond = workReqCacheQueryCond(io.readAtomicResp.pktFrag)
    )
  val rxAllReadAtomicRespPkgFragQueue = allReadAtomicRespPktFragStream
    .queueLowLatency(
      ADDR_CACHE_QUERY_DELAY_CYCLE + WORK_REQ_CACHE_QUERY_DELAY_CYCLE
    )

  val rxEveryFirstReadAtomicRespPktFragQueue =
    everyFirstReadAtomicRespPktFragStream
      .queueLowLatency(ADDR_CACHE_QUERY_DELAY_CYCLE)
  io.workReqQuery.req << rxEveryFirstReadAtomicRespPktFragQueue
    .translateWith {
      val result = cloneOf(io.workReqQuery.req.payloadType)
      result.psn := rxEveryFirstReadAtomicRespPktFragQueue.bth.psn
      result
    }

  val workReqIdReg = RegNextWhen(
    io.workReqQuery.resp.cachedWorkReq.workReq.id,
    cond = io.workReqQuery.resp.fire
  )
  val cachedWorkReq = io.workReqQuery.resp.cachedWorkReq
  io.addrCacheRead.req <-/< io.workReqQuery.resp
    .throwWhen(io.txQCtrl.wrongStateFlush)
    //    .continueWhen(io.rx.pktFrag.fire && (isReadFirstOrOnlyResp || isAtomicResp))
    .translateWith {
      val addrCacheReadReq = QpAddrCacheAgentReadReq()
      addrCacheReadReq.sqpn := io.qpAttr.sqpn
      addrCacheReadReq.psn := cachedWorkReq.psnStart
      addrCacheReadReq.key := cachedWorkReq.workReq.lkey
      addrCacheReadReq.pdId := io.qpAttr.pdId
      addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = False)
      addrCacheReadReq.accessType := AccessType.LOCAL_WRITE
      addrCacheReadReq.va := cachedWorkReq.workReq.laddr
      addrCacheReadReq.dataLenBytes := cachedWorkReq.workReq.lenBytes
      addrCacheReadReq
    }

  val joinStream =
    FragmentStreamConditionalJoinStream(
      rxAllReadAtomicRespPkgFragQueue,
      io.addrCacheRead.resp,
      joinCond = addrCacheQueryRespJoinCond(rxAllReadAtomicRespPkgFragQueue)
    )

  io.readAtomicRespWithDmaInfoBus.respWithDmaInfo <-/< joinStream
    .translateWith {
      val result =
        cloneOf(io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.payloadType)
      result.pktFrag := joinStream._1
      result.addr := joinStream._2.pa
      result.workReqId := workReqIdReg
      result.last := joinStream.isLast
      result
    }
}

//class RespVerifierAndFatalNakHandler(busWidth: BusWidth) extends Component {
//  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
//    val txQCtrl = in(TxQCtrl())
//    val rx = slave(RdmaDataBus(busWidth))
//    val nakNotifier = out(SqErrNotifier())
//    val workReqQuery = master(WorkReqCacheQueryBus())
//    val addrCacheRead = master(QpAddrCacheAgentReadBus())
//    val txRespWithAeth = master(Stream(Acknowledge()))
//    val readAtomicRespWithDmaInfoBus = master(
//      SqReadAtomicRespWithDmaInfoBus(busWidth)
//    )
//  }
//
//  val inputRespValid = io.rx.pktFrag.valid
//  val inputRespPktFrag = io.rx.pktFrag.payload
//  when(inputRespValid) {
//    assert(
//      assertion = OpCode.isRespPkt(inputRespPktFrag.bth.opcode),
//      message =
//        L"${REPORT_TIME} time: RespVerifier received non-response packet with opcode=${inputRespPktFrag.bth.opcode}, PSN=${inputRespPktFrag.bth.psn}",
//      severity = FAILURE
//    )
//  }
//
//  val acknowledge = Acknowledge()
//  acknowledge.bth := inputRespPktFrag.bth
//  acknowledge.aeth.assignFromBits(
//    inputRespPktFrag.data(
//      (busWidth.id - widthOf(BTH()) - widthOf(AETH())) until
//        (busWidth.id - widthOf(BTH()))
//    )
//  )
//
//  val hasAeth = OpCode.respPktHasAeth(inputRespPktFrag.bth.opcode)
//  when(inputRespValid && hasAeth) {
//    assert(
//      assertion = !acknowledge.aeth.isReserved(),
//      message =
//        L"${REPORT_TIME} time: acknowledge has reserved code or value, PSN=${acknowledge.bth.psn}, aeth.code=${acknowledge.aeth.code}, aeth.value=${acknowledge.aeth.value}",
//      severity = FAILURE
//    )
//  }
//
//  // Just discard ACK with reserved code
//  val hasReservedCode = acknowledge.aeth.isReserved()
//  //  val isNormalAck = acknowledge.aeth.isNormalAck()
//  //  val isRetryNak = acknowledge.aeth.isRetryNak()
//  val isErrAck = acknowledge.aeth.isErrAck()
//  // SQ into ERR state if fatal error
//  io.nakNotifier.setNoErr()
//  when(!io.txQCtrl.wrongStateFlush && inputRespValid && isErrAck) {
//    io.nakNotifier.setFromAeth(acknowledge.aeth)
//  }
//
//  val isReadResp =
//    OpCode.isReadRespPkt(io.rx.pktFrag.bth.opcode)
//  val isAtomicResp =
//    OpCode.isAtomicRespPkt(io.rx.pktFrag.bth.opcode)
//
//  // If error, discard all incoming response
//  // TODO: incoming response stream needs retry flush or not?
//  val (resp4ReadAtomic, resp4Ack) = StreamFork2(
//    io.rx.pktFrag.throwWhen(io.txQCtrl.wrongStateFlush || hasReservedCode)
//  )
//
//  // TODO: does it need to flush whole SQ when retry triggered?
//  val ackQueue =
//    StreamFifoLowLatency(Acknowledge(), depth = MAX_COALESCE_ACK_NUM)
//  ackQueue.io.push << resp4Ack
//    .takeWhen(hasAeth)
//    .translateWith(acknowledge)
//  // TODO: verify that once retry started, discard all responses before send out the retry request
//  ackQueue.io.flush := io.txQCtrl.retryFlush || io.txQCtrl.wrongStateFlush
//  io.txRespWithAeth <-/< ackQueue.io.pop
//    .translateWith(acknowledge)
//
//  val readAtomicRespValidateLogic = new Area {
//    // Only send out WorkReqCache query when the input data is the first fragment of
//    // the only or first read responses or atomic responses
//    val workReqCacheQueryCond =
//      (pktFragStream: Stream[Fragment[RdmaDataPkt]]) =>
//        new Composite(pktFragStream, "queryCond") {
//          val isReadFirstOrOnlyRespOrAtomicResp = (opcode: Bits) => {
//            OpCode.isFirstOrOnlyReadRespPkt(opcode) ||
//            OpCode.isAtomicRespPkt(opcode)
//          }
//          val result = pktFragStream.valid && pktFragStream.isFirst &&
//            isReadFirstOrOnlyRespOrAtomicResp(pktFragStream.bth.opcode)
//        }.result
//    // Only join AddrCache query response when the input data is the last fragment of
//    // the only or last read responses or atomic responses
//    val addrCacheQueryRespJoinCond =
//      (pktFragStream: Stream[Fragment[RdmaDataPkt]]) =>
//        new Composite(pktFragStream, "queryCond") {
//          val isReadLastOrOnlyRespOrAtomicResp = (opcode: Bits) => {
//            OpCode.isLastOrOnlyReadRespPkt(opcode) ||
//            OpCode.isAtomicRespPkt(opcode)
//          }
//          val result = pktFragStream.valid && pktFragStream.isLast &&
//            isReadLastOrOnlyRespOrAtomicResp(pktFragStream.bth.opcode)
//        }.result
//
//    val (
//      everyFirstReadAtomicRespPktFragStream,
//      allReadAtomicRespPktFragStream
//    ) =
//      StreamConditionalFork2(
//        resp4ReadAtomic.takeWhen(isReadResp || isAtomicResp),
//        forkCond = workReqCacheQueryCond(io.rx.pktFrag)
//      )
//    val rxAllReadAtomicRespPkgFragQueue = allReadAtomicRespPktFragStream
//      .queueLowLatency(
//        ADDR_CACHE_QUERY_DELAY_CYCLE + WORK_REQ_CACHE_QUERY_DELAY_CYCLE
//      )
//
//    val rxEveryFirstReadAtomicRespPktFragQueue =
//      everyFirstReadAtomicRespPktFragStream
//        .queueLowLatency(ADDR_CACHE_QUERY_DELAY_CYCLE)
//    io.workReqQuery.req << rxEveryFirstReadAtomicRespPktFragQueue
//      .translateWith {
//        val result = cloneOf(io.workReqQuery.req.payloadType)
//        result.psn := rxEveryFirstReadAtomicRespPktFragQueue.bth.psn
//        result
//      }
//
//    // TODO: verify it's correct
//    val workReqIdReg = RegNextWhen(
//      io.workReqQuery.resp.cachedWorkReq.workReq.id,
//      cond = io.workReqQuery.resp.fire
//    )
//    val cachedWorkReq = io.workReqQuery.resp.cachedWorkReq
//    io.addrCacheRead.req <-/< io.workReqQuery.resp
//      .throwWhen(io.txQCtrl.wrongStateFlush)
//      //    .continueWhen(io.rx.pktFrag.fire && (isReadFirstOrOnlyResp || isAtomicResp))
//      .translateWith {
//        val addrCacheReadReq = QpAddrCacheAgentReadReq()
//        addrCacheReadReq.sqpn := io.qpAttr.sqpn
//        addrCacheReadReq.psn := cachedWorkReq.psnStart
//        addrCacheReadReq.key := cachedWorkReq.workReq.lkey
//        addrCacheReadReq.pdId := io.qpAttr.pdId
//        addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = False)
//        addrCacheReadReq.accessType := AccessType.LOCAL_WRITE
//        addrCacheReadReq.va := cachedWorkReq.workReq.laddr
//        addrCacheReadReq.dataLenBytes := cachedWorkReq.workReq.lenBytes
//        addrCacheReadReq
//      }
//
//    val joinStream =
//      FragmentStreamConditionalJoinStream(
//        rxAllReadAtomicRespPkgFragQueue,
//        io.addrCacheRead.resp,
//        joinCond = addrCacheQueryRespJoinCond(rxAllReadAtomicRespPkgFragQueue)
//      )
//
//    io.readAtomicRespWithDmaInfoBus.respWithDmaInfo <-/< joinStream
//      .translateWith {
//        val result =
//          cloneOf(io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.payloadType)
//        result.pktFrag := joinStream._1
//        result.addr := joinStream._2.pa
//        result.workReqId := workReqIdReg
//        result.last := joinStream.isLast
//        result
//      }
//  }
//}

// Handle coalesce ACK, normal ACK and retry ACK
class CoalesceAndNormalAndRetryNakHandler extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val rx = slave(Stream(Acknowledge()))
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
    val coalesceAckDone = out(Bool())
    val workCompAndAck = master(Stream(WorkCompAndAck()))
    val retryNotifier = out(SqRetryNotifier())
//    val nakNotifier = out(SqErrNotifier())
//    val tx = slave(Stream(Acknowledge()))
  }

  val inputAckValid = io.rx.valid
  val inputCachedWorkReqValid = io.cachedWorkReqPop.valid

  val isDupAck =
    PsnUtil.lt(io.rx.bth.psn, io.cachedWorkReqPop.psnStart, io.qpAttr.npsn)
  val isGhostAck = inputAckValid && !inputCachedWorkReqValid

  val isNormalAck = io.rx.aeth.isNormalAck()
  val isRetryNak = io.rx.aeth.isRetryNak()
  val isErrAck = io.rx.aeth.isErrAck()

  val isReadWorkReq =
    WorkReqOpCode.isReadReq(io.cachedWorkReqPop.workReq.opcode)
  val isAtomicWorkReq =
    WorkReqOpCode.isAtomicReq(io.cachedWorkReqPop.workReq.opcode)

  val cachedWorkReqPsnEnd =
    io.cachedWorkReqPop.psnStart + (io.cachedWorkReqPop.pktNum - 1)
  val isTargetWorkReq =
    PsnUtil.gte(io.rx.bth.psn, io.cachedWorkReqPop.psnStart, io.qpAttr.npsn) &&
      PsnUtil.lte(io.rx.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val isWholeWorkReqAck = io.rx.bth.psn === cachedWorkReqPsnEnd
  val isPartialTargetWorkReqAck = isTargetWorkReq && !isWholeWorkReqAck
  val isWholeTargetWorkReqAck = isTargetWorkReq && isWholeWorkReqAck

  val hasCoalesceAck =
    PsnUtil.gt(io.rx.bth.psn, cachedWorkReqPsnEnd, io.qpAttr.npsn)
  val hasImplicitRetry = !isTargetWorkReq && (isReadWorkReq || isAtomicWorkReq)
  val hasExplicitRetry = isTargetWorkReq && isRetryNak

  // For the following fire condition, it must make sure inputAckValid or inputCachedWorkReqValid
  val fireBothCachedWorkReqAndAck =
    (inputAckValid && inputCachedWorkReqValid && ((isWholeTargetWorkReqAck && isNormalAck) || (isTargetWorkReq && isErrAck)))
  val fireCachedWorkReqOnly =
    (inputAckValid && inputCachedWorkReqValid && hasCoalesceAck && !hasImplicitRetry) || io.txQCtrl.errorFlush
  val fireAckOnly =
    isGhostAck || (inputAckValid && inputCachedWorkReqValid && (isDupAck || (isTargetWorkReq && isRetryNak) || (isPartialTargetWorkReqAck && isNormalAck)))
  val zipCachedWorkReqAndAck = StreamZipByCondition(
    leftInputStream = io.cachedWorkReqPop,
    // Only retryFlush io.rx, no need to errorFlush
    rightInputStream = io.rx.throwWhen(io.txQCtrl.retryFlush),
    // Coalesce ACK pending WR, or errorFlush
    leftFireCond = fireCachedWorkReqOnly,
    // Discard duplicated ACK or ghost ACK if no pending WR
    rightFireCond = fireAckOnly,
    bothFireCond = fireBothCachedWorkReqAndAck
  )
  when(inputAckValid || inputCachedWorkReqValid) {
    assert(
      assertion = CountOne(
        fireBothCachedWorkReqAndAck ## fireCachedWorkReqOnly ## fireAckOnly
      ) <= 1,
      message =
        L"${REPORT_TIME} time: fire CachedWorkReq, fire ACK, and fire both should be mutually exclusive, but fireBothCachedWorkReqAndAck=${fireBothCachedWorkReqAndAck}, fireCachedWorkReqOnly=${fireCachedWorkReqOnly}, fireAckOnly=${fireAckOnly}",
      severity = FAILURE
    )
  }

  val workCompFlushStatus = WorkCompStatus() // Bits(WC_STATUS_WIDTH bits)
  // TODO: what status should the read/atomic requests before error ACK have?
  workCompFlushStatus := WorkCompStatus.WR_FLUSH_ERR
  val zipCachedWorkReqValid = zipCachedWorkReqAndAck._1
  io.workCompAndAck <-/< zipCachedWorkReqAndAck
    .takeWhen(zipCachedWorkReqValid)
    .translateWith {
      val result = cloneOf(io.workCompAndAck.payloadType)
      when(isTargetWorkReq && isErrAck) {
        // Handle fatal NAK
        result.workComp.setFromWorkReq(
          workReq = zipCachedWorkReqAndAck._2.workReq,
          dqpn = io.qpAttr.dqpn,
          status = zipCachedWorkReqAndAck._4.aeth.toWorkCompStatus()
        )
      } elsewhen (io.txQCtrl.errorFlush) {
        // Handle errorFlush
        result.workComp.setFromWorkReq(
          workReq = zipCachedWorkReqAndAck._2.workReq,
          dqpn = io.qpAttr.dqpn,
          status = workCompFlushStatus
        )
      } otherwise {
        // Handle coalesce and normal ACK
        result.workComp.setSuccessFromWorkReq(
          workReq = zipCachedWorkReqAndAck._2.workReq,
          dqpn = io.qpAttr.dqpn
        )
      }
      // TODO: verify when errorFlush, is zipCachedWorkReqAndAck still valid?
      result.ackValid := zipCachedWorkReqAndAck._3
      result.ack := zipCachedWorkReqAndAck._4
      result
    }

  // Handle response timeout retry
  val respTimer = Timeout(MAX_RESP_TIMEOUT)
  val respTimeOutThreshold = io.qpAttr.getRespTimeOut()
  val respTimeOutRetry = respTimeOutThreshold =/= INFINITE_RESP_TIMEOUT &&
    respTimer.counter.value > respTimeOutThreshold
  // TODO: should use io.rx.fire or io.rx.valid to clear response timer?
  when(
    io.txQCtrl.wrongStateFlush || inputAckValid || !io.cachedWorkReqPop.valid || io.txQCtrl.retryFlush
  ) {
    respTimer.clear()
  }

  io.coalesceAckDone := io.txQCtrl.wrongStateFlush && io.rx.fire

  // Retry notification
  io.retryNotifier.pulse := (respTimeOutRetry || hasExplicitRetry || hasImplicitRetry) && io.rx.fire
  io.retryNotifier.psnStart := io.rx.bth.psn
  io.retryNotifier.reason := RetryReason.NO_RETRY
  when(hasImplicitRetry) {
    io.retryNotifier.psnStart := io.cachedWorkReqPop.psnStart
    io.retryNotifier.reason := RetryReason.IMPLICIT_ACK
  } elsewhen (respTimeOutRetry) {
    io.retryNotifier.psnStart := io.cachedWorkReqPop.psnStart
    io.retryNotifier.reason := RetryReason.RESP_TIMEOUT
  } elsewhen (io.rx.aeth.isRnrNak()) {
    io.retryNotifier.reason := RetryReason.RNR
  } elsewhen (io.rx.aeth.isSeqNak()) {
    io.retryNotifier.reason := RetryReason.SEQ_ERR
  }
}

// TODO: check read response opcode sequence
// TODO: handle read/atomic WC
class ReadAtomicRespDmaReqInitiator(busWidth: BusWidth) extends Component {
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

  val txSel = UInt(2 bits)
  val (readRespIdx, atomicRespIdx, otherRespIdx) = (0, 1, 2)
  when(OpCode.isReadRespPkt(inputPktFrag.bth.opcode)) {
    txSel := readRespIdx
  } elsewhen (OpCode.isAtomicRespPkt(inputPktFrag.bth.opcode)) {
    txSel := atomicRespIdx
  } otherwise {
    txSel := otherRespIdx
    when(inputValid) {
      report(
        message =
          L"${REPORT_TIME} time: input packet should be read/atomic response, but opcode=${inputPktFrag.bth.opcode}",
        severity = FAILURE
      )
    }
  }
  val threeStreams = StreamDemux(
    io.readAtomicRespWithDmaInfoBus.respWithDmaInfo
      .throwWhen(io.txQCtrl.wrongStateFlush),
    select = txSel,
    portCount = 3
  )
  StreamSink(NoData) << threeStreams(otherRespIdx).translateWith(NoData)

  val isLast = io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.isLast
  val pa = io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.addr
  val workReqId = io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.workReqId

  io.readRespDmaWriteReq.req <-/< threeStreams(readRespIdx)
    .throwWhen(io.txQCtrl.wrongStateFlush)
    .translateWith {
      val result =
        cloneOf(io.readRespDmaWriteReq.req.payloadType) //DmaWriteReq(busWidth)
      result.last := isLast
      result.set(
        initiator = DmaInitiator.SQ_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        addr = pa,
        workReqId = workReqId,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      result
    }

  io.atomicRespDmaWriteReq.req <-/< threeStreams(atomicRespIdx)
    .throwWhen(io.txQCtrl.wrongStateFlush)
    .translateWith {
      val result = cloneOf(io.readRespDmaWriteReq.req.payloadType)
      result.last := isLast
      result.set(
        initiator = DmaInitiator.SQ_ATOMIC_WR,
        sqpn = io.qpAttr.sqpn,
        psn = inputPktFrag.bth.psn,
        addr = pa,
        workReqId = workReqId,
        data = inputPktFrag.data,
        mty = inputPktFrag.mty
      )
      result
    }
}

//class ReadAtomicRespDmaReqInitiator(busWidth: BusWidth) extends Component {
//  val io = new Bundle {
//    val qpAttr = in(QpAttrData())
//    val txQCtrl = in(TxQCtrl())
//    val rx = slave(RdmaDataBus(busWidth))
//    val workReqQuery = master(WorkReqCacheQueryBus())
//    val addrCacheRead = master(QpAddrCacheAgentReadBus())
//    val readRespDmaWriteReq = master(DmaWriteReqBus(busWidth))
//    val atomicRespDmaWriteReq = master(DmaWriteReqBus(busWidth))
//  }
//
//  // Only send out WorkReqCache query when the input data is the first fragment of
//  // the only or first read responses or atomic responses
//  val workReqCacheQueryCond = (pktFragStream: Stream[Fragment[RdmaDataPkt]]) =>
//    new Composite(pktFragStream, "queryCond") {
//      val isReadFirstOrOnlyRespOrAtomicResp = (opcode: Bits) => {
//        OpCode.isFirstOrOnlyReadRespPkt(opcode) ||
//        OpCode.isAtomicRespPkt(opcode)
//      }
//      val result = pktFragStream.valid && pktFragStream.isFirst &&
//        isReadFirstOrOnlyRespOrAtomicResp(pktFragStream.bth.opcode)
//    }.result
//  // Only join AddrCache query response when the input data is the last fragment of
//  // the only or last read responses or atomic responses
//  val addrCacheQueryRespJoinCond =
//    (pktFragStream: Stream[Fragment[RdmaDataPkt]]) =>
//      new Composite(pktFragStream, "queryCond") {
//        val isReadLastOrOnlyRespOrAtomicResp = (opcode: Bits) => {
//          OpCode.isLastOrOnlyReadRespPkt(opcode) ||
//          OpCode.isAtomicRespPkt(opcode)
//        }
//        val result = pktFragStream.valid && pktFragStream.isLast &&
//          isReadLastOrOnlyRespOrAtomicResp(pktFragStream.bth.opcode)
//      }.result
//
//  val (everyFirstInputPktFragStream, allInputPktFragStream) =
//    StreamConditionalFork2(
//      io.rx.pktFrag.throwWhen(io.txQCtrl.wrongStateFlush),
//      forkCond = workReqCacheQueryCond(io.rx.pktFrag)
//    )
//  val rxAllInputPkgFragQueue = allInputPktFragStream
//    .queueLowLatency(
//      ADDR_CACHE_QUERY_DELAY_CYCLE + WORK_REQ_CACHE_QUERY_DELAY_CYCLE
//    )
//  val inputValid = rxAllInputPkgFragQueue.valid
//  val inputPktFrag = rxAllInputPkgFragQueue.payload
//  val isLastFrag = rxAllInputPkgFragQueue.isLast
//
//  val rxEveryFirstInputPktFragQueue = everyFirstInputPktFragStream
//    .queueLowLatency(ADDR_CACHE_QUERY_DELAY_CYCLE)
//  io.workReqQuery.req << rxEveryFirstInputPktFragQueue
//    .translateWith {
//      val result = cloneOf(io.workReqQuery.req.payloadType)
//      result.psn := rxEveryFirstInputPktFragQueue.bth.psn
//      result
//    }
//
//  // TODO: verify it's correct
//  val workReqIdReg = RegNextWhen(
//    io.workReqQuery.resp.cachedWorkReq.workReq.id,
//    cond = io.workReqQuery.resp.fire
//  )
//  val cachedWorkReq = io.workReqQuery.resp.cachedWorkReq
//  io.addrCacheRead.req <-/< io.workReqQuery.resp
//    .throwWhen(io.txQCtrl.wrongStateFlush)
////    .continueWhen(io.rx.pktFrag.fire && (isReadFirstOrOnlyResp || isAtomicResp))
//    .translateWith {
//      val addrCacheReadReq = QpAddrCacheAgentReadReq()
//      addrCacheReadReq.sqpn := io.qpAttr.sqpn
//      addrCacheReadReq.psn := cachedWorkReq.psnStart
//      addrCacheReadReq.key := cachedWorkReq.workReq.lkey
//      addrCacheReadReq.pdId := io.qpAttr.pdId
//      addrCacheReadReq.setKeyTypeRemoteOrLocal(isRemoteKey = False)
//      addrCacheReadReq.accessType := AccessType.LOCAL_WRITE
//      addrCacheReadReq.va := cachedWorkReq.workReq.laddr
//      addrCacheReadReq.dataLenBytes := cachedWorkReq.workReq.lenBytes
//      addrCacheReadReq
//    }
//
//  val joinStream =
//    FragmentStreamConditionalJoinStream(
//      rxAllInputPkgFragQueue,
//      io.addrCacheRead.resp,
//      joinCond = addrCacheQueryRespJoinCond(rxAllInputPkgFragQueue)
//    )
//
//  val txSel = UInt(2 bits)
//  val (readRespIdx, atomicRespIdx, otherRespIdx) = (0, 1, 2)
//  when(OpCode.isReadRespPkt(joinStream._1.bth.opcode)) {
//    txSel := readRespIdx
//  } elsewhen (OpCode.isAtomicRespPkt(joinStream._1.bth.opcode)) {
//    txSel := atomicRespIdx
//  } otherwise {
//    txSel := otherRespIdx
//    when(inputValid) {
//      report(
//        message =
//          L"${REPORT_TIME} time: input packet should be read/atomic response, but opcode=${inputPktFrag.bth.opcode}",
//        severity = FAILURE
//      )
//    }
//  }
//  val threeStreams = StreamDemux(joinStream, select = txSel, portCount = 3)
//  StreamSink(NoData) << threeStreams(otherRespIdx).translateWith(NoData)
//
//  io.readRespDmaWriteReq.req <-/< threeStreams(readRespIdx)
//    .throwWhen(io.txQCtrl.wrongStateFlush)
//    .translateWith {
//      val result =
//        cloneOf(io.readRespDmaWriteReq.req.payloadType) //DmaWriteReq(busWidth)
//      result.last := isLastFrag
//      result.set(
//        initiator = DmaInitiator.SQ_WR,
//        sqpn = io.qpAttr.sqpn,
//        psn = inputPktFrag.bth.psn,
//        addr = joinStream._2.pa,
//        workReqId = workReqIdReg,
//        data = inputPktFrag.data,
//        mty = inputPktFrag.mty
//      )
//      result
//    }
//
//  io.atomicRespDmaWriteReq.req <-/< threeStreams(atomicRespIdx)
//    .throwWhen(io.txQCtrl.wrongStateFlush)
//    .translateWith {
//      val result = cloneOf(io.readRespDmaWriteReq.req.payloadType)
//      result.last := isLastFrag
//      result.set(
//        initiator = DmaInitiator.SQ_ATOMIC_WR,
//        sqpn = io.qpAttr.sqpn,
//        psn = inputPktFrag.bth.psn,
//        addr = joinStream._2.pa,
//        workReqId = workReqIdReg,
//        data = inputPktFrag.data,
//        mty = inputPktFrag.mty
//      )
//      result
//    }
//}

// TODO: verify conditional join between read/atomic WC and DMA write response
class WorkCompGen extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val txQCtrl = in(TxQCtrl())
    val workCompAndAck = slave(Stream(WorkCompAndAck()))
    val workCompPush = master(Stream(WorkComp()))
    val readRespDmaWriteResp = slave(DmaWriteRespBus())
    val atomicRespDmaWriteResp = slave(DmaWriteRespBus())
  }

  val inputWorkCompAndAckQueue =
    io.workCompAndAck.queueLowLatency(DMA_WRITE_DELAY_CYCLE)

  val inputWorkCompValid = inputWorkCompAndAckQueue.valid
  val inputDmaWriteRespValid = io.readRespDmaWriteResp.resp.valid

  val isReadWorkComp =
    WorkCompOpCode.isReadComp(inputWorkCompAndAckQueue.workComp.opcode)
  val isAtomicWorkComp =
    WorkCompOpCode.isAtomicComp(inputWorkCompAndAckQueue.workComp.opcode)

  io.workCompPush.valid := False
  io.workCompPush.payload := inputWorkCompAndAckQueue.payload.workComp
  inputWorkCompAndAckQueue.ready := io.workCompPush.fire
  io.readRespDmaWriteResp.resp.ready := False
  io.atomicRespDmaWriteResp.resp.ready := False

  val dmaWriteRespTimer = Timeout(DMA_WRITE_DELAY_CYCLE)
  when(io.txQCtrl.errorFlush || io.workCompPush.fire || !inputWorkCompValid) {
    dmaWriteRespTimer.clear()
  }
  when(io.txQCtrl.errorFlush) {
    io.workCompPush.valid := inputWorkCompValid
    io.readRespDmaWriteResp.resp.ready := io.readRespDmaWriteResp.resp.valid
  } elsewhen (isReadWorkComp) {
    // Read WC need to join with DMA write response
    io.workCompPush.valid := inputWorkCompValid && io.readRespDmaWriteResp.resp.psn === inputWorkCompAndAckQueue.ack.bth.psn
    io.readRespDmaWriteResp.resp.ready := io.workCompPush.fire

    when(
      PsnUtil.lt(
        io.readRespDmaWriteResp.resp.psn,
        inputWorkCompAndAckQueue.ack.bth.psn,
        io.qpAttr.npsn
      )
    ) {
      io.readRespDmaWriteResp.resp.ready := inputWorkCompValid
    }
    when(inputWorkCompValid) {
      assert(
        assertion = !dmaWriteRespTimer.state,
        message =
          L"${REPORT_TIME} time: DMA write for read response timeout, response PSN=${io.workCompAndAck.ack.bth.psn}",
        severity = FAILURE
      )
    }
  } elsewhen (isAtomicWorkComp) {
    // Atomic WC need to join with DMA write response
    io.workCompPush.valid := inputWorkCompValid && io.readRespDmaWriteResp.resp.psn === inputWorkCompAndAckQueue.ack.bth.psn
    io.atomicRespDmaWriteResp.resp.ready := io.workCompPush.fire

    when(inputWorkCompValid) {
      assert(
        assertion = !dmaWriteRespTimer.state,
        message =
          L"${REPORT_TIME} time: DMA write for atomic response timeout, response PSN=${io.workCompAndAck.ack.bth.psn}",
        severity = FAILURE
      )
    }
  } otherwise {
    io.workCompPush.valid := inputWorkCompValid
  }
}
