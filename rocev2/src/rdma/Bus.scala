package rdma

import spinal.core._
import spinal.lib._
import RdmaConstants._
import ConstantSettings._
import StreamVec._

import scala.language.postfixOps

sealed abstract class ReqRespBus[Req <: Data, Resp <: Data](
    reqType: HardType[Req],
    respType: HardType[Resp]
) extends Bundle
    with IMasterSlave {
  val req = Stream(reqType())
  val resp = Stream(respType())

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class DevMetaData() extends Bundle {
  val maxPendingReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val minRnrTimeOut = Bits(RNR_TIMEOUT_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    maxPendingReqNum := MAX_PENDING_REQ_NUM
    maxPendingReadAtomicReqNum := MAX_PENDING_READ_ATOMIC_REQ_NUM
    minRnrTimeOut := MIN_RNR_TIMEOUT
    this
  }
}

case class SqRetryNotifier() extends Bundle {
  val pulse = Bool()
  val psnStart = UInt(PSN_WIDTH bits)
  val reason = RetryReason()
  val receivedRnrTimeOut = Bits(RNR_TIMEOUT_WIDTH bits)

  def needRetry(): Bool = {
    when(pulse) {
      assert(
        assertion = reason =/= RetryReason.NO_RETRY,
        message =
          L"${REPORT_TIME} time: SqRetryNotifier.pulse=${pulse}, but retry reason=${reason} shows no retry".toSeq,
        severity = FAILURE
      )
    }
    pulse
  }

  // When merge, this has higher priority than that
  def merge(that: SqRetryNotifier, curPsn: UInt): SqRetryNotifier = {
    val result = SqRetryNotifier()
    result.pulse := this.pulse || that.pulse
    when(this.pulse && !that.pulse) {
      result.psnStart := this.psnStart
      result.reason := this.reason
    } elsewhen (!this.pulse && that.pulse) {
      result.psnStart := that.psnStart
      result.reason := that.reason
    } elsewhen (!this.pulse && !that.pulse) {
      result := this
    } otherwise { // this.pulse && that.pulse
      when(PsnUtil.lte(this.psnStart, that.psnStart, curPsn)) {
        result.psnStart := this.psnStart
        result.reason := this.reason
      } otherwise {
        result.psnStart := that.psnStart
        result.reason := that.reason
      }

      assert(
        assertion = this.psnStart =/= that.psnStart,
        message =
          L"${REPORT_TIME} time: impossible to have two SqRetryNotifier with the same PSN=${this.psnStart}".toSeq,
        severity = FAILURE
      )
    }
    result
  }
}

case class SqRetryClear() extends Bundle {
  val retryFlushDone = Bool()
  val retryWorkReqDone = Bool()
}

case class RetryNakClear() extends Bundle {
  val pulse = Bool()
}

case class RetryNakSent() extends Bundle {
  val pulse = Bool()
}

case class RqRetryNakNotifier() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
  val preOpCode = Bits(OPCODE_WIDTH bits)
  val pulse = Bool()

  def setNoErr(): this.type = {
    psn := 0
    preOpCode := OpCode.SEND_ONLY.id
    pulse := False
    this
  }
}

case class RqFatalNakNotifier() extends Bundle {
  val invReq = Bool()
  val rmtAcc = Bool()
  val rmtOp = Bool()

  def setNoErr(): this.type = {
    invReq := False
    rmtAcc := False
    rmtOp := False
    this
  }

  def setFromAeth(aeth: AETH): this.type = {
    when(aeth.isNormalAck()) {
      setNoErr()
    } elsewhen (aeth.isInvReqNak()) {
      setInvReq()
    } elsewhen (aeth.isRmtAccNak()) {
      setRmtAcc()
    } elsewhen (aeth.isRmtOpNak()) {
      setRmtOp()
    } otherwise {
      report(
        message =
          L"${REPORT_TIME} time: illegal AETH to set RqFatalNakNotifier, aeth.code=${aeth.code}, aeth.value=${aeth.value}".toSeq,
        severity = FAILURE
      )
      setNoErr() // To avoid latch
    }
    this
  }

  private def setInvReq(): this.type = {
    invReq := True
    this
  }

  private def setRmtAcc(): this.type = {
    rmtAcc := True
    this
  }

  private def setRmtOp(): this.type = {
    rmtOp := True
    this
  }
}

case class SqErrNotifier() extends Bundle {
  val pulse = Bool()
  val errType = SqErrType()
//  val invReq = Bool()
//  val rmtAcc = Bool()
//  val rmtOp = Bool()
//  val localErr = Bool()
//  val retryExc = Bool()
//  val rnrExc = Bool()

  def setFromAeth(aeth: AETH): this.type = {
    when(aeth.isNormalAck()) {
      setNoErr()
    } elsewhen (aeth.isInvReqNak()) {
      setInvReq()
    } elsewhen (aeth.isRmtAccNak()) {
      setRmtAcc()
    } elsewhen (aeth.isRmtOpNak()) {
      setRmtOp()
    } otherwise {
      report(
        message =
          L"${REPORT_TIME} time: illegal AETH to set SqErrNotifier, aeth.code=${aeth.code}, aeth.value=${aeth.value}".toSeq,
        severity = FAILURE
      )
      setNoErr() // To avoid latch
    }
    this
  }

  private def setInvReq(): this.type = {
    errType := SqErrType.RMT_ACC
    pulse := True
    this
  }

  private def setRmtAcc(): this.type = {
    errType := SqErrType.RMT_ACC
    pulse := True
    this
  }

  private def setRmtOp(): this.type = {
    errType := SqErrType.RMT_OP
    pulse := True
    this
  }

  def setLocalErr(): this.type = {
    errType := SqErrType.LOC_ERR
    pulse := True
    this
  }

  def setRetryExceed(): this.type = {
    errType := SqErrType.RETRY_EXC
    pulse := True
    this
  }

  def setRnrExceed(): this.type = {
    errType := SqErrType.RNR_EXC
    pulse := True
    this
  }

  def setNoErr(): this.type = {
    errType := SqErrType.NO_ERR
    pulse := False
    this
  }

  def hasFatalErr(): Bool = {
    when(pulse) {
      assert(
        assertion = errType =/= SqErrType.NO_ERR,
        message =
          L"${REPORT_TIME} time: SqErrNotifier.pulse=${pulse}, but errType=${errType} shows no error".toSeq,
        severity = FAILURE
      )
    }
    pulse
  }

  /** Merge two SqErrNotifier, left one has higher priority
    */
  def ||(that: SqErrNotifier): SqErrNotifier = {
    assert(
      assertion = !(this.hasFatalErr() && that.hasFatalErr()),
      message =
        L"${REPORT_TIME} time: cannot merge two SqErrNotifier both have fatal error, this.pulse=${this.pulse}, this.errType=${this.errType}, that.pulse=${that.pulse}, that.errType=${that.errType}".toSeq,
      severity = FAILURE
    )
    val result = SqErrNotifier()
    result.pulse := this.pulse || that.pulse
    result.errType := (this.errType =/= SqErrType.NO_ERR) ? this.errType | that.errType
    result
  }
}

case class RqNakNotifier() extends Bundle {
  val rnr = RqRetryNakNotifier()
  val seqErr = RqRetryNakNotifier()
  val fatal = RqFatalNakNotifier()
//  val invReq = Bool()
//  val rmtAcc = Bool()
//  val rmtOp = Bool()
//  val localErr = Bool()

  def setFromAeth(
      aeth: AETH,
      pulse: Bool,
      preOpCode: Bits,
      psn: UInt
  ): this.type = {
    when(aeth.isNormalAck()) {
      setNoErr()
    } elsewhen (aeth.isRnrNak()) {
      setRnrNak(pulse, preOpCode, psn)
    } elsewhen (aeth.isSeqNak()) {
      setSeqErr(pulse, preOpCode, psn)
    } elsewhen (aeth.isInvReqNak() || aeth.isRmtAccNak() || aeth.isRmtOpNak()) {
      fatal.setFromAeth(aeth)
    } elsewhen (pulse) {
      report(
        message =
          L"${REPORT_TIME} time: illegal AETH to set NakNotifier, aeth.code=${aeth.code}, aeth.value=${aeth.value}".toSeq,
        severity = FAILURE
      )
      setNoErr() // To avoid latch
    }

    this
  }

  private def setRnrNak(pulse: Bool, preOpCode: Bits, psn: UInt): this.type = {
    rnr.pulse := pulse
    rnr.psn := psn
    rnr.preOpCode := preOpCode
    this
  }

  private def setSeqErr(pulse: Bool, preOpCode: Bits, psn: UInt): this.type = {
    seqErr.pulse := pulse
    seqErr.psn := psn
    seqErr.preOpCode := preOpCode
    this
  }

//  private def setLocalErr(): this.type = {
//    localErr := True
//    this
//  }

  def setNoErr(): this.type = {
    rnr.setNoErr()
    seqErr.setNoErr()
    fatal.setNoErr()
//    invReq := False
//    rmtAcc := False
//    rmtOp := False
//    localErr := False
    this
  }

  def hasFatalNak(): Bool =
    fatal.invReq || fatal.rmtAcc || fatal.rmtOp // || localErr

//  def ||(that: NakNotifier): NakNotifier = {
//    val result = NakNotifier()
//    result.seqErr := this.seqErr || that.seqErr
//    result.invReq := this.invReq || that.invReq
//    result.rmtAcc := this.rmtAcc || that.rmtAcc
//    result.rmtOp := this.rmtOp || that.rmtOp
//    result.localErr := this.localErr || that.localErr
//    result
//  }
}

case class RqNotifier() extends Bundle {
  val nak = RqNakNotifier()
  val clearRetryNakFlush = RetryNakClear()
  val retryNakHasSent = RetryNakSent()

  def hasFatalNak(): Bool = nak.hasFatalNak()
}

case class SqNotifier() extends Bundle {
  val err = SqErrNotifier()
  val retry = SqRetryNotifier()
  val retryClear = SqRetryClear()
//  val workReqHasFence = Bool()
  val workReqCacheEmpty = Bool()
  val coalesceAckDone = Bool()

  def hasFatalErr(): Bool = err.hasFatalErr()
}

case class RxQCtrl() extends Bundle {
  val stateErrFlush = Bool()
//  val rnrTriggered = Bool()
//  val rnrTimeOut = Bool()
  val rnrFlush = Bool()
//  val nakSeqTriggered = Bool()
  val nakSeqFlush = Bool()
  val isRetryNakNotCleared = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    stateErrFlush := False
    rnrFlush := False
//    rnrTimeOut := True
//    nakSeqTriggered := False
    nakSeqFlush := False
    isRetryNakNotCleared := False
    this
  }
}

case class TxQCtrl() extends Bundle {
  val errorFlush = Bool()
  val retry = Bool()
  val retryFlush = Bool()
  val retryStartPulse = Bool()
//  val fencePulse = Bool()
//  val fence = Bool()
  val wrongStateFlush = Bool()
//  val fenceOrRetry = Bool()
//  val psnBeforeFence = UInt(PSN_WIDTH bits)
}

case class EPsnInc() extends Bundle {
  val inc = Bool()
  val incVal = UInt(PSN_WIDTH bits)
  val preReqOpCode = Bits(OPCODE_WIDTH bits)
}

case class NPsnInc() extends Bundle {
  val inc = Bool()
  val incVal = UInt(PSN_WIDTH bits)
}

// Both SQ and RQ have oPSN
case class OPsnInc() extends Bundle {
  val inc = Bool()
  val psnVal = UInt(PSN_WIDTH bits)
}

case class RqPsnInc() extends Bundle {
  val epsn = EPsnInc()
  val opsn = OPsnInc()
}

case class SqPsnInc() extends Bundle {
  val npsn = NPsnInc()
  val opsn = OPsnInc()
}

case class PsnIncNotifier() extends Bundle {
  val rq = RqPsnInc()
  val sq = SqPsnInc()
}

case class QpAttrMask() extends Bundle {
  val maskBits = Bits(widthOf(QpAttrMaskEnum()) bits)

  def set(masks: SpinalEnumCraft[QpAttrMaskEnum.type]*): Unit = {
    maskBits := masks.map(_.asBits).reduceBalancedTree(_ | _)
  }

  def include(mask: SpinalEnumCraft[QpAttrMaskEnum.type]): Bool = {
    (maskBits & mask.asBits).orR
  }

  def init(): Unit = {
    maskBits := QpAttrMaskEnum.QP_CREATE.asBits // QP_CREATE -> 0
  }
}

case class QpAttrData() extends Bundle {
  val ipv4Peer = Bits(IPV4_WIDTH bits) // IPv4 only

  val pdId = Bits(PD_ID_WIDTH bits)
  val epsn = UInt(PSN_WIDTH bits)
  val npsn = UInt(PSN_WIDTH bits)
  val rqOutPsn = UInt(PSN_WIDTH bits)
  val sqOutPsn = UInt(PSN_WIDTH bits)
  val pmtu = UInt(PMTU_WIDTH bits)
  val maxPendingReadAtomicWorkReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxDstPendingReadAtomicWorkReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxPendingWorkReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxDstPendingWorkReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val dqpn = UInt(QPN_WIDTH bits)

  // The previous received request opcode of RQ
  val rqPreReqOpCode = Bits(OPCODE_WIDTH bits)

  val retryStartPsn = UInt(PSN_WIDTH bits)
  val retryReason = RetryReason()

  val maxRetryCnt = UInt(RETRY_COUNT_WIDTH bits)
  val maxRnrRetryCnt = UInt(RETRY_COUNT_WIDTH bits)
  val minRnrTimeOut = Bits(RNR_TIMEOUT_WIDTH bits)
  val negotiatedRnrTimeOut = Bits(RNR_TIMEOUT_WIDTH bits)
  val receivedRnrTimeOut = Bits(RNR_TIMEOUT_WIDTH bits)
  // respTimeOut need to be converted to actual cycle number,
  // by calling getRespTimeOut()
  val respTimeOut = Bits(RESP_TIMEOUT_WIDTH bits)

//  val fence = Bool()
//  val psnBeforeFence = UInt(PSN_WIDTH bits)

  val state = QpState() // Bits(QP_STATE_WIDTH bits)

//  val modifyMask = QpAttrMask() // Bits(QP_ATTR_MASK_WIDTH bits)

  def isValid = state =/= QpState.RESET
  def isReset = state === QpState.RESET

  def init(): this.type = {
    ipv4Peer := 0
    pdId := 0
    epsn := 0
    npsn := 0
    rqOutPsn := setAllBits(PSN_WIDTH)
    sqOutPsn := setAllBits(PSN_WIDTH)
    pmtu := PMTU.U512.id
    maxPendingReadAtomicWorkReqNum := MAX_PENDING_READ_ATOMIC_REQ_NUM
    maxDstPendingReadAtomicWorkReqNum := MAX_PENDING_READ_ATOMIC_REQ_NUM
    maxPendingWorkReqNum := MAX_PENDING_REQ_NUM
    maxDstPendingWorkReqNum := MAX_PENDING_REQ_NUM
    sqpn := 0
    dqpn := 0

    rqPreReqOpCode := OpCode.SEND_ONLY.id
    retryStartPsn := 0
    retryReason := RetryReason.RESP_TIMEOUT
    maxRetryCnt := DEFAULT_MAX_RETRY_CNT
    maxRnrRetryCnt := DEFAULT_MAX_RNR_RETRY_CNT
    minRnrTimeOut := DEFAULT_MIN_RNR_TIME_OUT
    negotiatedRnrTimeOut := DEFAULT_RNR_TIME_OUT
    receivedRnrTimeOut := DEFAULT_RNR_TIME_OUT
    respTimeOut := DEFAULT_RESP_TIME_OUT
//    fence := False
//    psnBeforeFence := 0

    state := QpState.RESET

//    modifyMask.init()
    this
  }

  def getMaxPendingReadAtomicWorkReqNum(): UInt = {
    (maxDstPendingReadAtomicWorkReqNum < maxPendingReadAtomicWorkReqNum) ?
      maxDstPendingReadAtomicWorkReqNum | maxPendingReadAtomicWorkReqNum
  }

  def getMaxPendingWorkReqNum(): UInt = {
    (maxDstPendingWorkReqNum < maxPendingWorkReqNum) ?
      maxDstPendingWorkReqNum | maxPendingWorkReqNum
  }

  def getRnrTimeOutCycleNum(): UInt =
    new Composite(this) {
      val result = UInt()
      switch(receivedRnrTimeOut) {
//        is(0) {
//          result := timeNumToCycleNum(655360 us)
//        }
//        is(1) {
//          result := timeNumToCycleNum(10 us)
//        }
//        is(2) {
//          result := timeNumToCycleNum(20 us)
//        }
//        is(3) {
//          result := timeNumToCycleNum(30 us)
//        }
//        is(4) {
//          result := timeNumToCycleNum(40 us)
//        }
//        is(5) {
//          result := timeNumToCycleNum(60 us)
//        }
//        is(6) {
//          result := timeNumToCycleNum(80 us)
//        }
//        is(7) {
//          result := timeNumToCycleNum(120 us)
//        }
//        is(8) {
//          result := timeNumToCycleNum(160 us)
//        }
//        is(9) {
//          result := timeNumToCycleNum(240 us)
//        }
//        is(10) {
//          result := timeNumToCycleNum(320 us)
//        }
//        is(11) {
//          result := timeNumToCycleNum(480 us)
//        }
//        is(12) {
//          result := timeNumToCycleNum(640 us)
//        }
//        is(13) {
//          result := timeNumToCycleNum(960 us)
//        }
//        is(14) {
//          result := timeNumToCycleNum(1280 us)
//        }
//        is(15) {
//          result := timeNumToCycleNum(1920 us)
//        }
//        is(16) {
//          result := timeNumToCycleNum(2560 us)
//        }
//        is(17) {
//          result := timeNumToCycleNum(3840 us)
//        }
//        is(18) {
//          result := timeNumToCycleNum(5120 us)
//        }
//        is(19) {
//          result := timeNumToCycleNum(7680 us)
//        }
//        is(20) {
//          result := timeNumToCycleNum(10240 us)
//        }
//        is(21) {
//          result := timeNumToCycleNum(15360 us)
//        }
//        is(22) {
//          result := timeNumToCycleNum(20480 us)
//        }
//        is(23) {
//          result := timeNumToCycleNum(30720 us)
//        }
//        is(24) {
//          result := timeNumToCycleNum(40960 us)
//        }
//        is(25) {
//          result := timeNumToCycleNum(61440 us)
//        }
//        is(26) {
//          result := timeNumToCycleNum(81920 us)
//        }
//        is(27) {
//          result := timeNumToCycleNum(122880 us)
//        }
//        is(28) {
//          result := timeNumToCycleNum(163840 us)
//        }
//        is(29) {
//          result := timeNumToCycleNum(245760 us)
//        }
//        is(30) {
//          result := timeNumToCycleNum(327680 us)
//        }
//        is(31) {
//          result := timeNumToCycleNum(491520 us)
//        }
        for (rnrTimeOutOption <- 0 until (1 << RNR_TIMEOUT_WIDTH)) {
          is(rnrTimeOutOption) {
            result := timeNumToCycleNum(
              rnrTimeOutOptionToTimeNum(rnrTimeOutOption)
            )
          }
        }
//        default {
//          report(
//            message =
//              L"${REPORT_TIME} time: invalid rnrTimeOut=${rnrTimeOut}, should between 0 and 31",
//            severity = FAILURE
//          )
//          result := 0
//        }
      }
    }.result

  def getRespTimeOutCycleNum(): UInt =
    new Composite(this, "QpAttrData_getRespTimeOut") {
      val maxCycleNum = timeNumToCycleNum(
        respTimeOutOptionToTimeNum(MAX_RESP_TIMEOUT_OPTION)
      )
      val result = UInt(log2Up(maxCycleNum) bits)
      switch(respTimeOut) {
        is(INFINITE_RESP_TIMEOUT) {
          // Infinite
          result := INFINITE_RESP_TIMEOUT
        }
        for (timeOutOption <- 1 until (1 << RESP_TIMEOUT_WIDTH)) {
          is(timeOutOption) {
            result := timeNumToCycleNum(
//              BigDecimal(BigInt(8192) << (timeOutOption - 1)) ns
              respTimeOutOptionToTimeNum(timeOutOption)
            )
          }
        }
//        default {
//          report(
//            message =
//              L"${REPORT_TIME} time: invalid respTimeOut=${respTimeOut}, should between 0 and 31",
//            severity = FAILURE
//          )
//          result := 0
//        }
      }
    }.result
}

case class DmaReadReq() extends Bundle {
  // opcodeStart can only be read response, send/write/atomic request
  val initiator = DmaInitiator()
  val sqpn = UInt(QPN_WIDTH bits)
  val psnStart = UInt(PSN_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
//  private val hasMultiPkts = Bool()
//  private val hasImmDt = Bool()
//  private val immDt = Bits(LRKEY_IMM_DATA_WIDTH bits)
//  private val hasIeth = Bool()
//  private val ieth = Bits(LRKEY_IMM_DATA_WIDTH bits)

  def set(
      initiator: SpinalEnumCraft[DmaInitiator.type],
      sqpn: UInt,
      psnStart: UInt,
      pa: UInt,
      lenBytes: UInt
  ): this.type = {
    this.initiator := initiator
    this.psnStart := psnStart
    this.sqpn := sqpn
    this.pa := pa
    this.lenBytes := lenBytes
    this
  }
  /*
  def getSendReqOpCodeStart(fromFirstReq: Bool,
                            hasMultiPkts: Bool,
                            hasImmDt: Bool,
                            hasIeth: Bool): Bits =
    new Composite(fromFirstReq) {
      val result = Bits(OPCODE_WIDTH bits)
      when(fromFirstReq) {
        when(hasMultiPkts) {
          result := OpCode.SEND_FIRST.id
        } otherwise {
          result := OpCode.SEND_ONLY.id
          when(hasImmDt) {
            result := OpCode.SEND_ONLY_WITH_IMMEDIATE.id
          } elsewhen (hasIeth) {
            result := OpCode.SEND_ONLY_WITH_INVALIDATE.id
          }
        }
      } otherwise {
        when(hasMultiPkts) {
          result := OpCode.SEND_MIDDLE.id
        } otherwise {
          result := OpCode.SEND_LAST.id
          when(hasImmDt) {
            result := OpCode.SEND_LAST_WITH_IMMEDIATE.id
          } elsewhen (hasIeth) {
            result := OpCode.SEND_LAST_WITH_INVALIDATE.id
          }
        }
      }
    }.result

  def setBySendReq(sqpn: UInt,
                   psn: UInt,
                   addr: UInt,
                   lenBytes: UInt,
                   pmtu: Bits,
                   hasImmDt: Bool,
                   immDt: Bits,
                   hasIeth: Bool,
                   ieth: Bits,
                   fromFirstReq: Bool): this.type = {
    assert(
      assertion = !(hasImmDt && hasIeth),
      message =
        L"${REPORT_TIME} time: hasImmDt=${hasImmDt} and hasIeth=${hasIeth} cannot be both true",
      severity = FAILURE
    )

    hasMultiPkts := lenBytes > pmtuPktLenBytes(pmtu)
    dmaRespOpCodeStart := getSendReqOpCodeStart(
      fromFirstReq,
      hasMultiPkts,
      hasImmDt,
      hasIeth
    )
    psnStart := psn
    this.sqpn := sqpn
    this.addr := addr
    this.lenBytes := lenBytes
    this.hasImmDt := hasImmDt
    this.immDt := immDt
    this.hasIeth := hasIeth
    this.ieth := ieth
    this
  }

  def getWriteReqOpCodeStart(fromFirstReq: Bool,
                             hasMultiPkts: Bool,
                             hasImmDt: Bool): Bits =
    new Composite(fromFirstReq) {
      val result = Bits(OPCODE_WIDTH bits)
      when(fromFirstReq) {
        when(hasMultiPkts) {
          result := OpCode.RDMA_WRITE_FIRST.id
        } otherwise {
          result := OpCode.RDMA_WRITE_ONLY.id
          when(hasImmDt) {
            result := OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
          }
        }
      } otherwise {
        when(hasMultiPkts) {
          result := OpCode.RDMA_WRITE_MIDDLE.id
        } otherwise {
          result := OpCode.RDMA_WRITE_LAST.id
          when(hasImmDt) {
            result := OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE.id
          }
        }
      }
    }.result

  def setByWriteReq(sqpn: UInt,
                    psn: UInt,
                    addr: UInt,
                    lenBytes: UInt,
                    pmtu: Bits,
                    hasImmDt: Bool,
                    immDt: Bits,
                    fromFirstReq: Bool): this.type = {
    hasMultiPkts := lenBytes > pmtuPktLenBytes(pmtu)
    dmaRespOpCodeStart := getWriteReqOpCodeStart(
      fromFirstReq,
      hasImmDt,
      hasImmDt
    )
    psnStart := psn
    this.sqpn := sqpn
    this.addr := addr
    this.lenBytes := lenBytes
    this.hasImmDt := hasImmDt
    this.immDt := immDt
    hasIeth := False
    ieth := 0
    this
  }

  def getReadRespOpCodeStart(fromFirstResp: Bool, hasMultiPkts: Bool): Bits =
    new Composite(fromFirstResp) {
      val result = Bits(OPCODE_WIDTH bits)
      when(fromFirstResp) {
        when(hasMultiPkts) {
          result := OpCode.RDMA_READ_RESPONSE_FIRST.id
        } otherwise {
          result := OpCode.RDMA_READ_RESPONSE_ONLY.id
        }
      } otherwise {
        when(hasMultiPkts) {
          result := OpCode.RDMA_READ_RESPONSE_MIDDLE.id
        } otherwise {
          result := OpCode.RDMA_READ_RESPONSE_LAST.id
        }
      }
    }.result

  def setByReadReq(sqpn: UInt,
                   psn: UInt,
                   addr: UInt,
                   lenBytes: UInt,
                   pmtu: Bits,
                   fromFirstResp: Bool): this.type = {
    hasMultiPkts := lenBytes > pmtuPktLenBytes(pmtu)
    dmaRespOpCodeStart := getReadRespOpCodeStart(fromFirstResp, hasMultiPkts)
    psnStart := psn
    this.sqpn := sqpn
    this.addr := addr
    this.lenBytes := lenBytes
    hasImmDt := False
    immDt := 0
    hasIeth := False
    ieth := 0
    this
  }
   */
  // TODO: remove this
  def setDefaultVal(): this.type = {
    initiator := DmaInitiator.RQ_RD
    sqpn := 0
    psnStart := 0
    pa := 0
    lenBytes := 0
//    hasMultiPkts := False
//    hasImmDt := False
//    immDt := 0
//    hasIeth := False
//    ieth := 0
    this
  }
}

case class DmaReadResp(busWidth: BusWidth.Value) extends Bundle {
  val initiator = DmaInitiator()
  val sqpn = UInt(QPN_WIDTH bits)
  val psnStart = UInt(PSN_WIDTH bits)
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
//  val hasMultiPkts = Bool()
//  val hasImmDt = Bool()
//  val immDt = Bits(LRKEY_IMM_DATA_WIDTH bits)
//  val hasIeth = Bool()
//  val ieth = Bits(LRKEY_IMM_DATA_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    initiator := DmaInitiator.RQ_RD
    sqpn := 0
    psnStart := 0
    data := 0
    mty := 0
    lenBytes := 0
//    hasMultiPkts := False
//    hasImmDt := False
//    immDt := 0
//    hasIeth := False
//    ieth := 0
    this
  }
}

case class DmaReadReqBus() extends Bundle with IMasterSlave {
  val req = Stream(DmaReadReq())

  def >>(that: DmaReadReqBus): Unit = {
    this.req >> that.req
  }

  def <<(that: DmaReadReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class DmaReadRespBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val resp = Stream(Fragment(DmaReadResp(busWidth)))

  def >>(that: DmaReadRespBus): Unit = {
    this.resp >> that.resp
  }

  def <<(that: DmaReadRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class DmaReadBus(busWidth: BusWidth.Value)
    extends ReqRespBus(DmaReadReq(), Fragment(DmaReadResp(busWidth))) {
//    extends Bundle
//    with IMasterSlave {
//  val req = Stream(DmaReadReq())
//  val resp = Stream(Fragment(DmaReadResp(busWidth)))

  def arbitReq(dmaRdReqVec: Vec[Stream[DmaReadReq]]) = new Area {
    val dmaRdReqSel =
      StreamArbiterFactory().roundRobin.transactionLock.on(dmaRdReqVec)
    req <-/< dmaRdReqSel
  }

  def deMuxRespByInitiator(
      rqRead: Stream[Fragment[DmaReadResp]],
//      rqDup: Stream[Fragment[DmaReadResp]],
      rqAtomicRead: Stream[Fragment[DmaReadResp]],
      sqRead: Stream[Fragment[DmaReadResp]]
//      sqDup: Stream[Fragment[DmaReadResp]]
  ) = new Area {
    val readRespDeMuxOH = Vec(
      resp.initiator === DmaInitiator.RQ_RD || resp.initiator === DmaInitiator.RQ_DUP,
      resp.initiator === DmaInitiator.RQ_ATOMIC_RD,
      resp.initiator === DmaInitiator.SQ_RD
    )
    Vec(
      rqRead,
//      rqDup,
      rqAtomicRead,
      sqRead
//      sqDup,
//      StreamSink(rqRead.payloadType)
    ) <-/< StreamOneHotDeMux(resp, readRespDeMuxOH.asBits)
  }

  def arbitReqAndDemuxRespByQpn(
      dmaRdReqVec: Vec[Stream[DmaReadReq]],
      dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]],
      qpAttrVec: Vec[QpAttrData]
  ) = new Area {
    val dmaRdReqSel =
      StreamArbiterFactory().roundRobin.transactionLock.on(dmaRdReqVec)
    req <-/< dmaRdReqSel

    val dmaRdRespOH = qpAttrVec.map(_.sqpn === resp.sqpn)
    val foundRespTargetQp = dmaRdRespOH.orR
    when(resp.valid) {
      assert(
        assertion = foundRespTargetQp,
        message =
          L"${REPORT_TIME} time: failed to find DMA read response target QP with QPN=${resp.sqpn}".toSeq,
        severity = FAILURE
      )
    }
    dmaRdRespVec <-/< StreamOneHotDeMux(resp, dmaRdRespOH.asBits())
  }

  def >>(that: DmaReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: DmaReadBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class DmaWriteReq(busWidth: BusWidth.Value) extends Bundle {
  val initiator = DmaInitiator()
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val workReqId = Bits(WR_ID_WIDTH bits)
  // val workReqIdValid = Bool()
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)

  def set(
      initiator: SpinalEnumCraft[DmaInitiator.type],
      sqpn: UInt,
      psn: UInt,
      workReqId: Bits,
      pa: UInt,
      data: Bits,
      mty: Bits
  ): this.type = {
    this.initiator := initiator
    this.sqpn := sqpn
    this.psn := psn
    this.workReqId := workReqId
    this.pa := pa
    this.data := data
    this.mty := mty
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    initiator := DmaInitiator.RQ_RD
    sqpn := 0
    psn := 0
    workReqId := 0
    // workReqIdValid := False
    pa := 0
    mty := 0
    data := 0
    this
  }
}

case class DmaWriteResp() extends Bundle {
  val initiator = DmaInitiator()
  val sqpn = UInt(QPN_WIDTH bits)
  // TODO: PSN should be PsnStart?
  val psn = UInt(PSN_WIDTH bits)
  val workReqId = Bits(WR_ID_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    initiator := DmaInitiator.RQ_RD
    sqpn := 0
    psn := 0
    workReqId := 0
    lenBytes := 0
    this
  }
}

case class DmaWriteReqBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val req = Stream(Fragment(DmaWriteReq(busWidth)))

  def >>(that: DmaWriteReqBus): Unit = {
    this.req >> that.req
  }

  def <<(that: DmaWriteReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class DmaWriteRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(DmaWriteResp())

  def >>(that: DmaWriteRespBus): Unit = {
    this.resp >> that.resp
  }

  def <<(that: DmaWriteRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class DmaWriteBus(busWidth: BusWidth.Value)
    extends ReqRespBus(Fragment(DmaWriteReq(busWidth)), DmaWriteResp()) {
//    extends Bundle
//    with IMasterSlave {
//  val req = Stream(Fragment(DmaWriteReq(busWidth)))
//  val resp = Stream(DmaWriteResp())

  def arbitReqAndDemuxRespByQpn(
      dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]],
      dmaWrRespVec: Vec[Stream[DmaWriteResp]],
      qpAttrVec: Vec[QpAttrData]
  ) = new Area {
    arbitReq(dmaWrReqVec)

    val dmaWrRespOH = qpAttrVec.map(_.sqpn === resp.sqpn)
    val foundRespTargetQp = dmaWrRespOH.orR
    when(resp.valid) {
      assert(
        assertion = foundRespTargetQp,
        message =
          L"${REPORT_TIME} time: failed to find DMA write response target QP with QPN=${resp.sqpn}".toSeq,
        severity = FAILURE
      )
    }
    dmaWrRespVec <-/< StreamOneHotDeMux(resp, dmaWrRespOH.asBits())
  }

  def arbitReq(dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]]) =
    new Area {
      val dmaWrReqSel =
        StreamArbiterFactory().roundRobin.fragmentLock.on(dmaWrReqVec)
      req <-/< dmaWrReqSel
    }

  def deMuxRespByInitiator(
      rqWrite: Stream[DmaWriteResp],
      rqAtomicWr: Stream[DmaWriteResp],
      sqWrite: Stream[DmaWriteResp],
      sqAtomicWr: Stream[DmaWriteResp]
  ) = new Area {

    val txSel = UInt(3 bits)
    val (rqWriteIdx, rqAtomicWrIdx, sqWriteIdx, sqAtomicWrIdx, otherIdx) =
      (0, 1, 2, 3, 4)
    switch(resp.initiator) {
      is(DmaInitiator.RQ_WR) {
        txSel := rqWriteIdx
      }
      is(DmaInitiator.RQ_ATOMIC_WR) {
        txSel := rqAtomicWrIdx
      }
      is(DmaInitiator.SQ_WR) {
        txSel := sqWriteIdx
      }
      is(DmaInitiator.SQ_ATOMIC_WR) {
        txSel := sqAtomicWrIdx
      }
      default {
        report(
          message =
            L"${REPORT_TIME} time: invalid DMA initiator=${resp.initiator}, should be RQ_WR, RQ_ATOMIC_WR, RQ_WR, SQ_ATOMIC_WR".toSeq,
          severity = FAILURE
        )
        txSel := otherIdx
      }
    }
    Vec(
      rqWrite,
      rqAtomicWr,
      sqWrite,
      sqAtomicWr,
      StreamSink(rqWrite.payloadType)
    ) <-/< StreamDemux(resp, select = txSel, portCount = 5)
  }

  def >>(that: DmaWriteBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: DmaWriteBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class DmaBus(busWidth: BusWidth.Value) extends Bundle with IMasterSlave {
  val rd = DmaReadBus(busWidth)
  val wr = DmaWriteBus(busWidth)

  def >>(that: DmaBus): Unit = {
    this.rd >> that.rd
    this.wr >> that.wr
  }

  def <<(that: DmaBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(rd, wr)
  }
}

case class SqDmaBus(busWidth: BusWidth.Value) extends Bundle with IMasterSlave {
  val reqOut = DmaReadBus(busWidth)
//  val reqSender = DmaReadBus(busWidth)
//  val retry = DmaReadBus(busWidth)
  val readResp = DmaWriteBus(busWidth)
  val atomic = DmaWriteBus(busWidth)

  def dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]] = {
    Vec(readResp.req, atomic.req)
  }

  def dmaWrRespVec: Vec[Stream[DmaWriteResp]] = {
    Vec(readResp.resp, atomic.resp)
  }

  def dmaRdReqVec: Vec[Stream[DmaReadReq]] = {
    Vec(reqOut.req)
  }

  def dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]] = {
    Vec(reqOut.resp)
  }

  override def asMaster(): Unit = {
    master(reqOut, readResp, atomic)
  }
}

case class RqDmaBus(busWidth: BusWidth.Value) extends Bundle with IMasterSlave {
  val sendWrite = DmaWriteBus(busWidth)
//  val dupRead = DmaReadBus(busWidth)
  val read = DmaReadBus(busWidth)
  val atomic = DmaBus(busWidth)

  def dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]] = {
    Vec(sendWrite.req, atomic.wr.req)
  }

  def dmaWrRespVec: Vec[Stream[DmaWriteResp]] = {
    Vec(sendWrite.resp, atomic.wr.resp)
  }

  def dmaRdReqVec: Vec[Stream[DmaReadReq]] = {
//    Vec(read.req, dupRead.req, atomic.rd.req)
    Vec(read.req, atomic.rd.req)
  }

  def dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]] = {
//    Vec(read.resp, dupRead.resp, atomic.rd.resp)
    Vec(read.resp, atomic.rd.resp)
  }

  override def asMaster(): Unit = {
//    master(sendWrite, read, dupRead, atomic)
    master(sendWrite, read, atomic)
  }
}

case class LenCheckElements(busWidth: BusWidth.Value) extends Bundle {
  val opcode = Bits(OPCODE_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
//  val psnStart = UInt(PSN_WIDTH bits)
  val padCnt = UInt(PAD_COUNT_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  val mty = Bits(busWidth.id / BYTE_WIDTH bits)
}

case class LenCheckResult() extends Bundle {
  val totalLenOutput = UInt(RDMA_MAX_LEN_WIDTH bits)
  val isPktLenCheckErr = Bool()
}

case class ScatterGather() extends Bundle {
  val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  // next is physical address to next ScatterGather in main memory
  val next = UInt(MEM_ADDR_WIDTH bits)

  def hasNext: Bool = {
    next === INVALID_SG_NEXT_ADDR
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    va := 0
    pa := 0
    lkey := 0
    lenBytes := 0
    next := 0
    this
  }
}

case class ScatterGatherList() extends Bundle {
  val first = ScatterGather()
  val sgNum = UInt(MAX_SG_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    first.setDefaultVal()
    sgNum := 0
    this
  }
}

case class WorkReqSendFlags() extends Bundle {
  val flagBits = Bits(widthOf(WorkReqSendFlagEnum()) bits)

  def set(flags: SpinalEnumCraft[WorkReqSendFlagEnum.type]*): Unit = {
    flagBits := flags.map(_.asBits).reduceBalancedTree(_ | _)
  }

  def init(): Unit = {
    flagBits := 0 // No flags
  }

  def fence: Bool = (flagBits & WorkReqSendFlagEnum.FENCE.asBits).orR
  def signaled: Bool = (flagBits & WorkReqSendFlagEnum.SIGNALED.asBits).orR
  def solicited: Bool = (flagBits & WorkReqSendFlagEnum.SOLICITED.asBits).orR
  def inline: Bool = (flagBits & WorkReqSendFlagEnum.INLINE.asBits).orR
  def ipChkSum: Bool = (flagBits & WorkReqSendFlagEnum.IP_CSUM.asBits).orR
}

case class WorkReq() extends Bundle {
  val id = Bits(WR_ID_WIDTH bits)
  val opcode = WorkReqOpCode()
  val raddr = UInt(MEM_ADDR_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
//  val solicited = Bool()
  val sqpn = UInt(QPN_WIDTH bits)
  val ackreq = Bool()
  val flags = WorkReqSendFlags() // Bits(WR_FLAG_WIDTH bits)
//  val fence = Bool()
  val swap = Bits(LONG_WIDTH bits)
  val comp = Bits(LONG_WIDTH bits)
  val immDtOrRmtKeyToInv = Bits(LRKEY_IMM_DATA_WIDTH bits)

  // TODO: assume single SG, if SGL, pa, len and lkey should come from SGL
  val laddr = UInt(MEM_ADDR_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)

//  def fence = (flags & WorkReqSendFlags.FENCE.asBits).orR
//  def signaled = (flags & WorkReqSendFlags.SIGNALED.asBits).orR
//  def solicited = (flags & WorkReqSendFlags.SOLICITED.asBits).orR
//  def inline = (flags & WorkReqSendFlags.INLINE.asBits).orR
//  def ipChkSum = (flags & WorkReqSendFlags.IP_CSUM.asBits).orR

  // TODO: remove this
  def setDefaultVal(): this.type = {
    id := 0
    opcode := WorkReqOpCode.RDMA_WRITE // Default WR opcode
    raddr := 0
    rkey := 0
//    solicited := False
    sqpn := 0
    ackreq := False
    flags.init()
//    fence := False
    swap := 0
    comp := 0
    immDtOrRmtKeyToInv := 0

    laddr := 0
    lenBytes := 0
    lkey := 0
    this
  }
}

case class RxWorkReq() extends Bundle {
  val sqpn = UInt(QPN_WIDTH bits)
  val id = Bits(WR_ID_WIDTH bits)
  val laddr = UInt(MEM_ADDR_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  // TODO: assume single SG
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    sqpn := 0
    id := 0
    laddr := 0
    lkey := 0
    lenBytes := 0
    this
  }
}

case class WorkReqAndMetaData() extends Bundle {
  val workReq = WorkReq()
  val psnStart = UInt(PSN_WIDTH bits)
  val pktNum = UInt(PSN_WIDTH bits)
}

case class CachedWorkReq() extends Bundle {
  val workReq = WorkReq()
  val psnStart = UInt(PSN_WIDTH bits)
  val pktNum = UInt(PSN_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
//  val rnrCnt = UInt(RETRY_COUNT_WIDTH bits)
//  val retryCnt = UInt(RETRY_COUNT_WIDTH bits)

  // Used for cache to set initial CachedWorkReq value
  def setInitVal(): this.type = {
    workReq.setDefaultVal()
    psnStart := 0
    pktNum := 0
    pa := 0
//    rnrCnt := 0
//    retryCnt := 0
    this
  }

  def psnWithIn(psn: UInt, curPsn: UInt): Bool =
    new Composite(this) {
//      val psnEnd = psnStart + pktNum
      val result = PsnUtil.withInRange(psn, psnStart, pktNum, curPsn)
    }.result
}

case class WorkReqCacheQueryReq() extends Bundle {
//  val workReqOpCode = WorkReqOpCode()
  val queryPsn = UInt(PSN_WIDTH bits)
  val npsn = UInt(PSN_WIDTH bits)
}

//case class WorkReqCacheQueryReqBus() extends Bundle with IMasterSlave {
//  val req = Stream(WorkReqCacheQueryReq())
//
//  override def asMaster(): Unit = {
//    master(req)
//  }
//}
//
//case class WorkReqCacheResp() extends Bundle {
//  val cachedWorkReq = CachedWorkReq()
//  val query = WorkReqCacheQueryReq()
//  val found = Bool()
//}
//
//case class WorkReqCacheRespBus() extends Bundle with IMasterSlave {
//  val resp = Stream(WorkReqCacheResp())
//
//  override def asMaster(): Unit = {
//    master(resp)
//  }
//}

case class WorkReqCacheQueryBus() extends Bundle with IMasterSlave {
  val req = Stream(WorkReqCacheQueryReq())
//  val resp = Stream(WorkReqCacheResp())
  val resp = Stream(CamQueryResp(WorkReqCacheQueryReq(), CachedWorkReq()))

  def >>(that: WorkReqCacheQueryBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: WorkReqCacheQueryBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class ReadAtomicRstCacheData() extends Bundle {
  val psnStart = UInt(PSN_WIDTH bits)
  val pktNum = UInt(PSN_WIDTH bits)
  val opcode = Bits(OPCODE_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val va = UInt(MEM_ADDR_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val dlen = UInt(RDMA_MAX_LEN_WIDTH bits)
  val swap = Bits(LONG_WIDTH bits)
  val comp = Bits(LONG_WIDTH bits)
  val atomicRst = Bits(LONG_WIDTH bits)
  val dupReq = Bool()

  def setInitVal(): this.type = {
    psnStart := 0
    pktNum := 0
    opcode := 0
    pa := 0
    va := 0
    rkey := 0
    dlen := 0
    swap := 0
    comp := 0
    atomicRst := 0
    dupReq := False
    this
  }
}

case class ReadAtomicRstCacheReq() extends Bundle {
  val queryPsn = UInt(PSN_WIDTH bits)
  val opcode = Bits(OPCODE_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val epsn = UInt(PSN_WIDTH bits)
}

//case class ReadAtomicRstCacheResp() extends Bundle {
//  val rstCacheData = ReadAtomicRstCacheData()
//  val query = ReadAtomicRstCacheReq()
//  val found = Bool()
//}

//case class ReadAtomicRstCacheReqBus() extends Bundle with IMasterSlave {
//  val req = Stream(ReadAtomicRstCacheReq())
//
//  def >>(that: ReadAtomicRstCacheReqBus): Unit = {
//    this.req >> that.req
//  }
//
//  def <<(that: ReadAtomicRstCacheReqBus): Unit = that >> this
//
//  override def asMaster(): Unit = {
//    master(req)
//  }
//}

//case class ReadAtomicRstCacheRespBus() extends Bundle with IMasterSlave {
//  val resp = Stream(ReadAtomicRstCacheResp())
//
//  def >>(that: ReadAtomicRstCacheRespBus): Unit = {
//    this.resp >> that.resp
//  }
//
//  def <<(that: ReadAtomicRstCacheRespBus): Unit = that >> this
//
//  override def asMaster(): Unit = {
//    master(resp)
//  }
//}

case class ReadAtomicRstCacheQueryBus()
    extends ReqRespBus(
      ReadAtomicRstCacheReq(),
      CamQueryResp(ReadAtomicRstCacheReq(), ReadAtomicRstCacheData())
    ) {
//case class ReadAtomicRstCacheQueryBus() extends Bundle with IMasterSlave {
//  val req = Stream(ReadAtomicRstCacheReq())
////  val resp = Stream(ReadAtomicRstCacheResp())
//  val resp = Stream(
//    CamQueryResp(ReadAtomicRstCacheReq(), ReadAtomicRstCacheData())
//  )

  def >>(that: ReadAtomicRstCacheQueryBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: ReadAtomicRstCacheQueryBus): Unit = that >> this

//  override def asMaster(): Unit = {
//    master(req)
//    slave(resp)
//  }
}

case class CombineHeaderAndDmaRespInternalRst(busWidth: BusWidth.Value)
    extends Bundle {
  val pktNum = UInt(PSN_WIDTH bits)
  val bth = BTH()
  val headerBits = Bits(busWidth.id bits)
  val headerMtyBits = Bits((busWidth.id / BYTE_WIDTH) bits)

  def set(
      pktNum: UInt,
      bth: BTH,
      headerBits: Bits,
      headerMtyBits: Bits
  ): this.type = {
    this.pktNum := pktNum
    this.bth := bth
    this.headerBits := headerBits
    this.headerMtyBits := headerMtyBits
    this
  }

  def get(): (UInt, BTH, Bits, Bits) = (pktNum, bth, headerBits, headerMtyBits)
}

case class ReqAndDmaReadResp[T <: Data](
    reqType: HardType[T],
    busWidth: BusWidth.Value
) extends Bundle {
  val dmaReadResp = DmaReadResp(busWidth)
  val req = reqType()
}

/** for RQ */
//case class ReadAtomicRstCacheRespAndDmaReadResp(busWidth: BusWidth.Value)
//    extends Bundle {
//  val dmaReadResp = DmaReadResp(busWidth)
//  val resultCacheResp = ReadAtomicRstCacheResp()
//}

//object ABC {
//  type ReadAtomicRstCacheDataAndDmaReadResp =
//    ReqAndDmaReadResp[ReadAtomicRstCacheData]
//}
case class ReadAtomicRstCacheDataAndDmaReadResp(busWidth: BusWidth.Value)
    extends Bundle {
  val dmaReadResp = DmaReadResp(busWidth)
  val rstCacheData = ReadAtomicRstCacheData()
}

/** for SQ */
case class CachedWorkReqAndDmaReadResp(busWidth: BusWidth.Value)
    extends Bundle {
  val dmaReadResp = DmaReadResp(busWidth)
  val cachedWorkReq = CachedWorkReq()
//  val workReqCacheResp = WorkReqCacheResp()
}

case class ResponseWithAeth(busWidth: BusWidth.Value) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val aeth = AETH()
  val workCompStatus = WorkCompStatus()
}

case class CachedWorkReqAndRespWithAeth(busWidth: BusWidth.Value)
    extends Bundle {
  val cachedWorkReq = CachedWorkReq()
  val respValid = Bool() // False: implicit ACK, True: explicit ACK
  val pktFrag = RdmaDataPkt(busWidth)
  val aeth = AETH()
  val workCompStatus = WorkCompStatus()
}

case class CachedWorkReqAndAck() extends Bundle {
  val cachedWorkReq = CachedWorkReq()
  val ackValid = Bool() // False: implicit ACK, True: explicit ACK
  val ack = Acknowledge()
  val workCompStatus = WorkCompStatus()
}

case class WorkCompAndAck() extends Bundle {
  val workComp = WorkComp()
  val ackValid = Bool()
  val ack = Acknowledge()
}

case class WorkComp() extends Bundle {
  val id = Bits(WR_ID_WIDTH bits)
  val opcode = WorkCompOpCode() // Bits(WC_OPCODE_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val dqpn = UInt(QPN_WIDTH bits)
  val flags = WorkCompFlags() // Bits(WC_FLAG_WIDTH bits)
  val status = WorkCompStatus() // Bits(WC_STATUS_WIDTH bits)
  val immDtOrRmtKeyToInv = Bits(LRKEY_IMM_DATA_WIDTH bits)

  def setSuccessFromRxWorkReq(
      recvWorkReq: RxWorkReq,
      reqOpCode: Bits,
      dqpn: UInt,
      reqTotalLenBytes: UInt,
      pktFragData: Bits
  ): this.type = {
//    val status = Bits(WC_STATUS_WIDTH bits)
    val status = WorkCompStatus.SUCCESS
    setFromRxWorkReq(
      recvWorkReq,
      reqOpCode,
      dqpn,
      status,
      reqTotalLenBytes,
      pktFragData
    )
  }

  def setFromRxWorkReq(
      recvWorkReq: RxWorkReq,
      reqOpCode: Bits,
      dqpn: UInt,
      status: SpinalEnumCraft[WorkCompStatus.type],
      reqTotalLenBytes: UInt,
      pktFragData: Bits
  ): this.type = {
    id := recvWorkReq.id
    setOpCodeFromRqReqOpCode(reqOpCode)
    sqpn := recvWorkReq.sqpn
    this.dqpn := dqpn
    lenBytes := reqTotalLenBytes

    require(
      widthOf(IETH()) == widthOf(ImmDt()),
      s"widthOf(IETH())=${widthOf(IETH())} should equal widthOf(ImmDt())=${widthOf(ImmDt())}"
    )
    require(
      widthOf(pktFragData) >= widthOf(BTH()) + widthOf(ImmDt()),
      s"widthOf(pktFragData)=${widthOf(pktFragData)} should >= widthOf(BTH())=${widthOf(
        BTH()
      )} + widthOf(ImmDt())=${widthOf(ImmDt())}"
    )
    // TODO: verify inputPktFrag.data is big endian
    val immDtOrRmtKeyToInvBits = pktFragData(
      (widthOf(pktFragData) - widthOf(BTH()) - widthOf(ImmDt())) until
        (widthOf(pktFragData) - widthOf(BTH()))
    )

    when(OpCode.hasImmDt(reqOpCode)) {
      flags := WorkCompFlags.WITH_IMM
      immDtOrRmtKeyToInv := immDtOrRmtKeyToInvBits
    } elsewhen (OpCode.hasIeth(reqOpCode)) {
      flags := WorkCompFlags.WITH_INV
      immDtOrRmtKeyToInv := immDtOrRmtKeyToInvBits
    } otherwise {
      flags := WorkCompFlags.NO_FLAGS
      immDtOrRmtKeyToInv := 0
    }
    this.status := status
    this
  }

  def setSuccessFromWorkReq(workReq: WorkReq, dqpn: UInt): this.type = {
//    val status = Bits(WC_STATUS_WIDTH bits)
    val status = WorkCompStatus.SUCCESS
    setFromWorkReq(workReq, dqpn, status)
  }

  def setFromWorkReq(
      workReq: WorkReq,
      dqpn: UInt,
      status: SpinalEnumCraft[WorkCompStatus.type]
  ): this.type = {
    id := workReq.id
    setOpCodeFromSqWorkReqOpCode(workReq.opcode)
    lenBytes := workReq.lenBytes
    sqpn := workReq.sqpn
    this.dqpn := dqpn
    when(WorkReqOpCode.hasImmDt(workReq.opcode)) {
      flags := WorkCompFlags.WITH_IMM
    } elsewhen (WorkReqOpCode.hasIeth(workReq.opcode)) {
      flags := WorkCompFlags.WITH_INV
    } otherwise {
      flags := WorkCompFlags.NO_FLAGS
    }
    this.status := status
    immDtOrRmtKeyToInv := workReq.immDtOrRmtKeyToInv
    this
  }

  def setOpCodeFromRqReqOpCode(reqOpCode: Bits): this.type = {
    when(OpCode.isSendReqPkt(reqOpCode)) {
      opcode := WorkCompOpCode.RECV
    } elsewhen (OpCode.isWriteImmReqPkt(reqOpCode)) {
      opcode := WorkCompOpCode.RECV_RDMA_WITH_IMM
    } otherwise {
      report(
        message =
          L"${REPORT_TIME} time: unmatched WC opcode at RQ side for request opcode=${reqOpCode}".toSeq,
        severity = FAILURE
      )
      opcode.assignDontCare()
    }
    this
  }

  def setOpCodeFromSqWorkReqOpCode(
      workReqOpCode: SpinalEnumCraft[WorkReqOpCode.type]
  ): this.type = {
    // TODO: check WR opcode without WC opcode equivalent
//    val TM_ADD = Value(130)
//    val TM_DEL = Value(131)
//    val TM_SYNC = Value(132)
//    val TM_RECV = Value(133)
//    val TM_NO_TAG = Value(134)
    switch(workReqOpCode) {
      is(WorkReqOpCode.RDMA_WRITE, WorkReqOpCode.RDMA_WRITE_WITH_IMM) {
        opcode := WorkCompOpCode.RDMA_WRITE
      }
      is(
        WorkReqOpCode.SEND,
        WorkReqOpCode.SEND_WITH_IMM,
        WorkReqOpCode.SEND_WITH_INV
      ) {
        opcode := WorkCompOpCode.SEND
      }
      is(WorkReqOpCode.RDMA_READ) {
        opcode := WorkCompOpCode.RDMA_READ
      }
      is(WorkReqOpCode.ATOMIC_CMP_AND_SWP) {
        opcode := WorkCompOpCode.COMP_SWAP
      }
      is(WorkReqOpCode.ATOMIC_FETCH_AND_ADD) {
        opcode := WorkCompOpCode.FETCH_ADD
      }
      is(WorkReqOpCode.LOCAL_INV) {
        opcode := WorkCompOpCode.LOCAL_INV
      }
      is(WorkReqOpCode.BIND_MW) {
        opcode := WorkCompOpCode.BIND_MW
      }
      is(WorkReqOpCode.TSO) {
        opcode := WorkCompOpCode.TSO
      }
      is(WorkReqOpCode.DRIVER1) {
        opcode := WorkCompOpCode.DRIVER1
      }
      // UNREACHABLE DEFAULT STATEMENT
//      default {
//        report(
//          message =
//            L"${REPORT_TIME} time: no matched WC opcode at SQ side for WR opcode=${workReqOpCode}",
//          severity = FAILURE
//        )
//        opcode := WorkCompOpCode.SEND // Default WC opcode
//      }
    }
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    id := 0
    opcode := WorkCompOpCode.SEND // Default WC opcode
    lenBytes := 0
    sqpn := 0
    dqpn := 0
    flags := WorkCompFlags.NO_FLAGS
    status := WorkCompStatus.FATAL_ERR
    immDtOrRmtKeyToInv := 0
    this
  }
}

case class QpCreateOrModifyReq() extends Bundle {
  val qpAttr = QpAttrData()
  val modifyMask = QpAttrMask() // Bits(QP_ATTR_MASK_WIDTH bits)

  def changeToState(targetState: SpinalEnumCraft[QpState.type]): Bool = {
    modifyMask.include(QpAttrMaskEnum.QP_STATE) &&
    qpAttr.state === targetState
  }
}

case class QpCreateOrModifyResp() extends Bundle {
  val successOrFailure = Bool()
}

case class QpCreateOrModifyBus() extends Bundle with IMasterSlave {
  val req = Stream(QpCreateOrModifyReq())
  val resp = Stream(QpCreateOrModifyResp())

  def >>(that: QpCreateOrModifyBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: QpCreateOrModifyBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class AccessType() extends Bundle {
  val accessBits = Bits(ACCESS_PERMISSION_WIDTH bits)

  def init(): this.type = {
    accessBits := AccessPermission.LOCAL_READ.asBits
    this
  }

  def set(permissions: SpinalEnumCraft[AccessPermission.type]*): Unit = {
    accessBits := permissions.map(_.asBits).reduceBalancedTree(_ | _)
  }

  // Check whether this AccessType contains all that AccessType permissions
  def permit(that: AccessType): Bool = {
    (this.accessBits | that.accessBits) === this.accessBits
  }
}

case class PdAddrCacheReadReq() extends Bundle {
  val initiator = AddrQueryInitiator()
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val key = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val pdId = Bits(PD_ID_WIDTH bits)
  val remoteOrLocalKey = Bool() // True: remote, False: local
  val accessType = AccessType()
  val va = UInt(MEM_ADDR_WIDTH bits)
  val dataLenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)

  def setKeyTypeRemoteOrLocal(isRemoteKey: Bool): this.type = {
    remoteOrLocalKey := isRemoteKey
    this
  }
}

case class PdAddrCacheReadResp() extends Bundle {
  val initiator = AddrQueryInitiator()
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val keyValid = Bool()
  val sizeValid = Bool()
  val accessValid = Bool()
  val pa = UInt(MEM_ADDR_WIDTH bits)
}

case class PdAddrCacheReadBus()
    extends ReqRespBus(
      PdAddrCacheReadReq(),
      PdAddrCacheReadResp()
    ) {
  def >>(that: PdAddrCacheReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: PdAddrCacheReadBus): Unit = that >> this
}
//case class PdAddrCacheReadBus() extends Bundle with IMasterSlave {
//  val req = Stream(PdAddrCacheReadReq())
//  val resp = Stream(PdAddrCacheReadResp())
//
//  def >>(that: PdAddrCacheReadBus): Unit = {
//    this.req >> that.req
//    this.resp << that.resp
//  }
//
//  def <<(that: PdAddrCacheReadBus): Unit = that >> this
//
//  override def asMaster(): Unit = {
//    master(req)
//    slave(resp)
//  }
//}

case class PdCreateOrDeleteReq() extends Bundle {
  val createOrDelete = CRUD()
  val pdId = Bits(PD_ID_WIDTH bits)
}

case class PdCreateOrDeleteResp() extends Bundle {
  val successOrFailure = Bool()
  val pdId = Bits(PD_ID_WIDTH bits)
}

case class PdCreateOrDeleteBus() extends Bundle with IMasterSlave {
  val req = Stream(PdCreateOrDeleteReq())
  val resp = Stream(PdCreateOrDeleteResp())

  def >>(that: PdCreateOrDeleteBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: PdCreateOrDeleteBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class PdAddrDataCreateOrDeleteReq() extends Bundle {
  val createOrDelete = CRUD()
  val pdId = Bits(PD_ID_WIDTH bits)
  val addrData = AddrData()
}

case class PdAddrDataCreateOrDeleteResp() extends Bundle {
  val isSuccess = Bool()
  val createOrDelete = CRUD()
  val addrData = AddrData()
}

case class PdAddrDataCreateOrDeleteBus() extends Bundle with IMasterSlave {
  val req = Stream(PdAddrDataCreateOrDeleteReq())
  val resp = Stream(PdAddrDataCreateOrDeleteResp())

  def >>(that: PdAddrDataCreateOrDeleteBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: PdAddrDataCreateOrDeleteBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class AddrCacheDataCreateOrDeleteReq() extends Bundle {
  val createOrDelete = CRUD()
  val addrData = AddrData()
}

case class AddrCacheDataCreateOrDeleteResp() extends Bundle {
  val successOrFailure = Bool()
}

case class AddrCacheDataCreateOrDeleteBus() extends Bundle with IMasterSlave {
  val req = Stream(AddrCacheDataCreateOrDeleteReq())
  val resp = Stream(AddrCacheDataCreateOrDeleteBus())

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

//case class PdAddrCacheQueryReq() extends Bundle {
//  val remoteOrLocalKey = Bool() // True: remote, False: local
//  val key = Bits(LRKEY_IMM_DATA_WIDTH bits)
//}

case class AddrData() extends Bundle {
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val accessType = AccessType()
  val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val dataLenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)

  def init(): this.type = {
    this.lkey := 0
    this.rkey := 0
    this.accessType.init() // Default AccessType
    this.va := 0
    this.pa := 0
    this.dataLenBytes := 0
    this
  }
}

case class QpAddrCacheAgentReadReq() extends Bundle {
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val key = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val pdId = Bits(PD_ID_WIDTH bits)
  // TODO: consider remove remoteOrLocalKey
  val remoteOrLocalKey = Bool() // True: remote, False: local
  val accessType = AccessType()
  val va = UInt(MEM_ADDR_WIDTH bits)
  val dataLenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)

  def setKeyTypeRemoteOrLocal(isRemoteKey: Bool): this.type = {
    remoteOrLocalKey := isRemoteKey
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    sqpn := 0
    psn := 0
    key := 0
    pdId := 0
    remoteOrLocalKey := True
    accessType.init() // Default AccessType
    va := 0
    dataLenBytes := 0
    this
  }
}

case class QpAddrCacheAgentReadResp() extends Bundle {
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
//  val found = Bool()
  val keyValid = Bool()
  val sizeValid = Bool()
  val accessValid = Bool()
  // val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  // val len = UInt(RDMA_MAX_LEN_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    sqpn := 0
    psn := 0
//    found := False
    keyValid := False
    sizeValid := False
    accessValid := False
    // va := 0
    pa := 0
    this
  }
}

case class QpAddrCacheAgentReadReqBus() extends Bundle with IMasterSlave {
  val req = Stream(QpAddrCacheAgentReadReq())

//  def >>(that: QpAddrCacheAgentReadReqBus): Unit = {
//    this.req >> that.req
//  }
//
//  def <<(that: QpAddrCacheAgentReadReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class QpAddrCacheAgentReadRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(QpAddrCacheAgentReadResp())

//  def >>(that: QpAddrCacheAgentReadRespBus): Unit = {
//    this.resp >> that.resp
//  }
//
//  def <<(that: QpAddrCacheAgentReadRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class QpAddrCacheAgentReadBus()
    extends ReqRespBus(
      QpAddrCacheAgentReadReq(),
      QpAddrCacheAgentReadResp()
    ) {
//case class QpAddrCacheAgentReadBus() extends Bundle with IMasterSlave {
//  val req = Stream(QpAddrCacheAgentReadReq())
//  val resp = Stream(QpAddrCacheAgentReadResp())

  def >>(that: QpAddrCacheAgentReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: QpAddrCacheAgentReadBus): Unit = that >> this

//  override def asMaster(): Unit = {
//    master(req)
//    slave(resp)
//  }
}

//case class SqOrRetryQpAddrCacheAgentReadBus() extends Bundle with IMasterSlave {
//  val send = QpAddrCacheAgentReadBus()
//  val write = QpAddrCacheAgentReadBus()
//
//  def >>(that: SqOrRetryQpAddrCacheAgentReadBus): Unit = {
//    this.send >> that.send
//    this.write >> that.write
//  }
//
//  def <<(that: SqOrRetryQpAddrCacheAgentReadBus): Unit = that >> this
//
//  def asMaster(): Unit = {
//    master(send, write)
//  }
//}

trait PsnRange extends Bundle {
  val start = UInt(PSN_WIDTH bits)
  // end PSN is included in the range
  val end = UInt(PSN_WIDTH bits)
}

case class RespPsnRange() extends PsnRange {
  val opcode = Bits(OPCODE_WIDTH bits)
}

case class ReqPsnRange() extends PsnRange {
  val workReqOpCode = WorkReqOpCode()
  val isRetryWorkReq = Bool()
}

case class UdpMetaData() extends Bundle {
  val ip = Bits(IPV4_WIDTH bits) // IPv4 only
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
}

case class UdpData(busWidth: BusWidth.Value) extends Bundle {
  val udp = UdpMetaData()
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
  val sop = Bool()
}

case class UdpDataBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val pktFrag = Stream(Fragment(UdpData(busWidth)))

  def >>(that: UdpDataBus): Unit = {
    this.pktFrag >> that.pktFrag
  }

  def <<(that: UdpDataBus): Unit = that >> this

  override def asMaster(): Unit = master(pktFrag)
}

//----------Combined packets----------//
case class RdmaDataBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val pktFrag = Stream(Fragment(RdmaDataPkt(busWidth)))

  def >>(that: RdmaDataBus): Unit = {
    this.pktFrag >> that.pktFrag
  }

  def <<(that: RdmaDataBus): Unit = that >> this

  override def asMaster(): Unit = master(pktFrag)

  // TODO: remove this
  def setDefaultVal() = {
    val result = Fragment(RdmaDataPkt(busWidth))
    result.fragment.setDefaultVal()
    result.last := False
    result
  }

}

case class SqReadAtomicRespWithDmaInfo(busWidth: BusWidth.Value)
    extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val workReqId = Bits(WR_ID_WIDTH bits)
}

case class SqReadAtomicRespWithDmaInfoBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val respWithDmaInfo = Stream(Fragment(SqReadAtomicRespWithDmaInfo(busWidth)))

  def >>(that: SqReadAtomicRespWithDmaInfoBus): Unit = {
    this.respWithDmaInfo >> that.respWithDmaInfo
  }

  def <<(that: SqReadAtomicRespWithDmaInfoBus): Unit = that >> this

  override def asMaster(): Unit = master(respWithDmaInfo)
}

case class RqReqCheckRst() extends Bundle {
  val isCheckPass = Bool()
//  val isDupReq = Bool()
  val isOpSeqCheckPass = Bool()
  val isPsnExpected = Bool()
  val isPadCntCheckPass = Bool()
  val isReadAtomicRstCacheFull = Bool()
//  val isSeqErr = Bool()
  val isSupportedOpCode = Bool()
  val epsn = UInt(PSN_WIDTH bits)
  val preOpCode = Bits(OPCODE_WIDTH bits)
}

case class RqReqWithRxBuf(busWidth: BusWidth.Value) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
//  val preOpCode = Bits(OPCODE_WIDTH bits)
//  val curExpectedPsn = UInt(PSN_WIDTH bits)
  val hasNak = Bool()
  val ackAeth = AETH()
  // RxWorkReq is only valid at the first or only fragment for send,
  // or valid at the last or only fragment for write imm
  val rxBufValid = Bool()
  val rxBuf = RxWorkReq()
}

case class RqReqWithRxBufBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val reqWithRxBuf = Stream(Fragment(RqReqWithRxBuf(busWidth)))

  def >>(that: RqReqWithRxBufBus): Unit = {
    this.reqWithRxBuf >> that.reqWithRxBuf
  }

  def <<(that: RqReqWithRxBufBus): Unit = that >> this

  override def asMaster(): Unit = master(reqWithRxBuf)
}

case class RqReqCheckInternalOutput(busWidth: BusWidth.Value) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val checkRst = RqReqCheckRst()
}

case class RqReqCheckStageOutput(busWidth: BusWidth.Value) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val preOpCode = Bits(OPCODE_WIDTH bits)
  val hasNak = Bool()
//  val isDupReq = Bool()
  val ackAeth = AETH()
}

case class RqReqCommCheckRstBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val checkRst = Stream(Fragment(RqReqCheckStageOutput(busWidth)))

  def >>(that: RqReqCommCheckRstBus): Unit = {
    this.checkRst >> that.checkRst
  }

  def <<(that: RqReqCommCheckRstBus): Unit = that >> this

  override def asMaster(): Unit = master(checkRst)
}

case class VirtualAddrInfo() extends Bundle {
  val va = UInt(MEM_ADDR_WIDTH bits)
  //  val pa = UInt(MEM_ADDR_WIDTH bits)
  val lrkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val dlen = UInt(RDMA_MAX_LEN_WIDTH bits)

  def init(): this.type = {
    va := 0
    //    pa := 0
    lrkey := 0
    dlen := 0
    this
  }
}

case class RqReqWithRxBufAndVirtualAddrInfo(busWidth: BusWidth.Value)
    extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
//  val preOpCode = Bits(OPCODE_WIDTH bits)
  val hasNak = Bool()
  val ackAeth = AETH()
  // RxWorkReq is only valid at the first or only fragment for send,
  // or valid at the last or only fragment for write imm
  val rxBufValid = Bool()
  val rxBuf = RxWorkReq()
  // VirtualAddrInfo is always valid for send/write/read/atomic requests
  val virtualAddrInfo = VirtualAddrInfo()
}

case class RqReqWithRxBufAndVirtualAddrInfoBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val reqWithRxBufAndVirtualAddrInfo = Stream(
    Fragment(RqReqWithRxBufAndVirtualAddrInfo(busWidth))
  )

  def >>(that: RqReqWithRxBufAndVirtualAddrInfoBus): Unit = {
    this.reqWithRxBufAndVirtualAddrInfo >> that.reqWithRxBufAndVirtualAddrInfo
  }

  def <<(that: RqReqWithRxBufAndVirtualAddrInfoBus): Unit = that >> this

  override def asMaster(): Unit = master(reqWithRxBufAndVirtualAddrInfo)
}

case class DmaInfo() extends Bundle {
  //  val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  //  val lrkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val dlen = UInt(RDMA_MAX_LEN_WIDTH bits)

  def init(): this.type = {
    //    va := 0
    pa := 0
    //    lrkey := 0
    dlen := 0
    this
  }
}

case class RqReqWithRxBufAndDmaInfo(busWidth: BusWidth.Value) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
//  val preOpCode = Bits(OPCODE_WIDTH bits)
  val hasNak = Bool()
  val ackAeth = AETH()
  // RxWorkReq is only valid at the first or only fragment for send,
  // or valid at the last or only fragment for write imm
  val rxBufValid = Bool()
  val rxBuf = RxWorkReq()
  // DmaInfo is always valid for send/write/read/atomic requests
  val dmaInfo = DmaInfo()
}

case class RqReqWithRxBufAndDmaInfoBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val reqWithRxBufAndDmaInfo = Stream(
    Fragment(RqReqWithRxBufAndDmaInfo(busWidth))
  )

  def >>(that: RqReqWithRxBufAndDmaInfoBus): Unit = {
    this.reqWithRxBufAndDmaInfo >> that.reqWithRxBufAndDmaInfo
  }

  def <<(that: RqReqWithRxBufAndDmaInfoBus): Unit = that >> this

  override def asMaster(): Unit = master(reqWithRxBufAndDmaInfo)
}

case class RqReqWithRxBufAndDmaInfoWithLenCheck(busWidth: BusWidth.Value)
    extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
//  val preOpCode = Bits(OPCODE_WIDTH bits)
  val hasNak = Bool()
  val ackAeth = AETH()
  // reqTotalLenValid is only for the last fragment of send/write request packet
  val reqTotalLenValid = Bool()
  val reqTotalLenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  // RxWorkReq is only valid at the first or only fragment for send,
  // or valid at the last or only fragment for write imm
  val rxBufValid = Bool()
  val rxBuf = RxWorkReq()
  // DmaInfo is always valid for send/write/read/atomic requests
  val dmaInfo = DmaInfo()

  def isEmptyReq(): Bool =
    new Composite(this, "RqReqWithRxBufAndDmaInfoWithLenCheck_isEmptyReq") {
      val result =
        // Check empty write request
        dmaInfo.dlen === 0 ||
          // Check empty send request
          (reqTotalLenValid && reqTotalLenBytes === 0)
    }.result
}

case class RqReqWithRxBufAndDmaInfoWithLenCheckBus(busWidth: BusWidth.Value)
    extends Bundle
    with IMasterSlave {
  val reqWithRxBufAndDmaInfoWithLenCheck = Stream(
    Fragment(RqReqWithRxBufAndDmaInfoWithLenCheck(busWidth))
  )

  def >>(that: RqReqWithRxBufAndDmaInfoWithLenCheckBus): Unit = {
    this.reqWithRxBufAndDmaInfoWithLenCheck >> that.reqWithRxBufAndDmaInfoWithLenCheck
  }

  def <<(that: RqReqWithRxBufAndDmaInfoWithLenCheckBus): Unit = that >> this

  override def asMaster(): Unit = master(reqWithRxBufAndDmaInfoWithLenCheck)
}

case class RqDupReadReqAndRstCacheData(busWidth: BusWidth.Value)
    extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val rstCacheData = ReadAtomicRstCacheData()
}

case class RqDmaReadReqAndRstCacheData() extends Bundle {
  val dmaReadReq = DmaReadReq()
  val rstCacheData = ReadAtomicRstCacheData()

  def isEmptyReq(): Bool =
    new Composite(this, "RqDmaReadReqAndRstCacheData_isEmptyReq") {
      val result = dmaReadReq.lenBytes === 0
    }.result
}

sealed abstract class RdmaBasePacket extends Bundle {
  val bthWidth = widthOf(BTH())
  // this: Bundle => // RdmaDataPkt must be of Bundle class
  val bth = BTH()
  // val eth = Bits(ETH_WIDTH bits)
}

case class DataAndMty(busWidth: BusWidth.Value) extends Bundle {
  require(isPow2(busWidth.id), s"width=${busWidth.id} should be power of 2")
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
}

case class HeaderDataAndMty[T <: Data](
    headerType: HardType[T],
    busWidth: BusWidth.Value
) extends Bundle {
  //  type DataAndMty = HeaderDataAndMty[NoData]

  val header = headerType()
  val data = Bits(busWidth.id bits)
  // UInt() avoid sparse
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
}

object RdmaDataPkt {
  def apply(busWidth: BusWidth.Value) = new RdmaDataPkt(busWidth)
}

sealed class RdmaDataPkt(busWidth: BusWidth.Value) extends RdmaBasePacket {
  // data include BTH
  val data = Bits(busWidth.id bits)
  // mty does not include BTH
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    data := 0
    mty := 0
    this
  }

  def mtuWidth(pmtuEnum: Bits): Bits = {
    val pmtuBytes = Bits(log2Up(busWidth.id / BYTE_WIDTH) bits)
    switch(pmtuEnum) {
      is(PMTU.U256.id) { pmtuBytes := 256 / BYTE_WIDTH } // 32B
      is(PMTU.U512.id) { pmtuBytes := 512 / BYTE_WIDTH } // 64B
      is(PMTU.U1024.id) { pmtuBytes := 1024 / BYTE_WIDTH } // 128B
      is(PMTU.U2048.id) { pmtuBytes := 2048 / BYTE_WIDTH } // 256B
      is(PMTU.U4096.id) { pmtuBytes := 4096 / BYTE_WIDTH } // 512B
    }
    pmtuBytes
  }

  def extractReth(): RETH = new Composite(this, "extractReth") {
    val rethWidth = widthOf(RETH())
    require(
      busWidth.id >= bthWidth + rethWidth,
      L"${REPORT_TIME} time: busWidth=${busWidth.id} should >= bthWidth=${bthWidth} + rethWidth=${rethWidth}"
    )
    val result = RETH()
    result.va.assignFromBits(
      data(busWidth.id - bthWidth - MEM_ADDR_WIDTH until busWidth.id - bthWidth)
    )
    result.rkey.assignFromBits(
      data(
        busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH until busWidth.id - bthWidth - MEM_ADDR_WIDTH
      )
    )
    result.dlen.assignFromBits(
      data(
        busWidth.id - bthWidth - rethWidth until busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH
      )
    )
  }.result

  def extractAtomicEth(): AtomicEth = new Composite(this, "extractAtomicEth") {
    val atomicEthWidth = widthOf(AtomicEth())
    require(
      busWidth.id >= bthWidth + atomicEthWidth,
      L"${REPORT_TIME} time: busWidth=${busWidth.id} should >= bthWidth=${bthWidth} + atomicEthWidth=${atomicEthWidth}"
    )
    val result = AtomicEth()
    result.va.assignFromBits(
      data(busWidth.id - bthWidth - MEM_ADDR_WIDTH until busWidth.id - bthWidth)
    )
    result.rkey.assignFromBits(
      data(
        busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH until busWidth.id - bthWidth - MEM_ADDR_WIDTH
      )
    )
    result.swap.assignFromBits(
      data(
        busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH - LONG_WIDTH until busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH
      )
    )
    result.comp.assignFromBits(
      data(
        busWidth.id - bthWidth - atomicEthWidth until busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH - LONG_WIDTH
      )
    )
  }.result

  def extractAeth(): AETH = new Composite(this, "extractAeth") {
    val aethWidth = widthOf(AETH())
    require(
      busWidth.id >= bthWidth + aethWidth,
      L"${REPORT_TIME} time: busWidth=${busWidth.id} should >= bthWidth=${bthWidth} + aethWidth=${aethWidth}"
    )
    val result = AETH()
    result.rsvd.assignFromBits(
      data(
        busWidth.id - bthWidth - AETH_RSVD_WIDTH until busWidth.id - bthWidth
      )
    )
    result.code.assignFromBits(
      data(
        busWidth.id - bthWidth - AETH_CODE_WIDTH - AETH_RSVD_WIDTH until busWidth.id - bthWidth - AETH_RSVD_WIDTH
      )
    )
    result.value.assignFromBits(
      data(
        busWidth.id - bthWidth - AETH_VALUE_WIDTH - AETH_CODE_WIDTH - AETH_RSVD_WIDTH until busWidth.id - bthWidth - AETH_CODE_WIDTH - AETH_RSVD_WIDTH
      )
    )
    result.msn.assignFromBits(
      data(
        busWidth.id - bthWidth - aethWidth until busWidth.id - bthWidth - AETH_VALUE_WIDTH - AETH_CODE_WIDTH - AETH_RSVD_WIDTH
      )
    )
  }.result
}

trait ImmDtHeader extends RdmaBasePacket {
  // val immDtValid = Bool()
  val immdt = ImmDt()
}

trait RdmaReq extends RdmaBasePacket {
  val reth = RETH()
}

trait Response extends RdmaBasePacket {
  val aeth = AETH()
}

trait IethHeader extends RdmaBasePacket {
  // val iethValid = Bool()
  val ieth = IETH()
}

case class SendReq(busWidth: BusWidth.Value)
    extends RdmaDataPkt(busWidth)
    with ImmDtHeader
    with IethHeader {}

case class WriteReq(busWidth: BusWidth.Value)
    extends RdmaDataPkt(busWidth)
    with RdmaReq
    with ImmDtHeader {}

case class ReadReq() extends RdmaReq {
  def asRdmaDataPktFrag(busWidth: BusWidth.Value): Fragment[RdmaDataPkt] =
    new Composite(this) {
      val reqWidth = widthOf(bth) + widthOf(reth)
      require(
        busWidth.id >= reqWidth,
        s"busWidth=${busWidth.id} must >= ReadReq width=${reqWidth}"
      )
      val busWidthBytes = busWidth.id / BYTE_WIDTH
      val reqWidthBytes = reqWidth / BYTE_WIDTH

      val result = Fragment(RdmaDataPkt(busWidth))
      result.last := True
      result.bth := bth
      result.data := (bth.asBigEndianBits() ## reth.asBigEndianBits())
        .resizeLeft(busWidth.id)
      result.mty := (setAllBits(reqWidthBytes) <<
        (busWidthBytes - reqWidthBytes))
    }.result

//  def set(thatBth: BTH, rethBits: Bits): this.type = {
//    bth := thatBth
//    // TODO: verify rethBits is big endian
//    reth.assignFromBits(rethBits)
//    this
//  }

  def set(
      dqpn: UInt,
      psn: UInt,
      va: UInt,
      rkey: Bits,
      dlen: UInt
  ): this.type = {
    val opcode = Bits(OPCODE_WIDTH bits)
    opcode := OpCode.RDMA_READ_REQUEST.id
    bth.set(opcode, dqpn, psn)
    reth.va := va
    reth.rkey := rkey
    reth.dlen := dlen
    this
  }
}

case class ReadOnlyFirstLastResp(busWidth: BusWidth.Value)
    extends RdmaDataPkt(busWidth)
    with Response {
//  when(OpCode.isMidReadRespPkt(bth.opcode)) {
//    assert(
//      assertion = !aethValid,
//      message =
//        L"${REPORT_TIME} time: read response middle packet should have no AETH, but opcode=${bth.opcode}, aethValid=${aethValid}",
//      severity = FAILURE
//    )
//  }
}

case class ReadMidResp(busWidth: BusWidth.Value)
    extends RdmaDataPkt(busWidth) {}

case class Acknowledge() extends Response {
  def asRdmaDataPktFrag(busWidth: BusWidth.Value): Fragment[RdmaDataPkt] =
    new Composite(this) {
      val ackWidth = widthOf(bth) + widthOf(aeth)
      require(
        busWidth.id >= ackWidth,
        s"busWidth=${busWidth.id} must >= ACK width=${ackWidth}"
      )
      val busWidthBytes = busWidth.id / BYTE_WIDTH
      val ackWidthBytes = ackWidth / BYTE_WIDTH

      val result = Fragment(RdmaDataPkt(busWidth))
      result.last := True
      result.bth := bth
      result.data := (bth.asBigEndianBits() ## aeth.asBigEndianBits())
        .resizeLeft(busWidth.id)

      result.mty := (setAllBits(ackWidthBytes) <<
        (busWidthBytes - ackWidthBytes))
    }.result

  def setAck(aeth: AETH, psn: UInt, dqpn: UInt): this.type = {
    bth.set(opcode = OpCode.ACKNOWLEDGE.id, dqpn = dqpn, psn = psn)
    this.aeth := aeth
    this
  }

  def setAck(
      ackType: SpinalEnumCraft[AckType.type],
      psn: UInt,
      dqpn: UInt
  ): this.type = {
//    val ackTypeBits = Bits(ACK_TYPE_WIDTH bits)
//    ackTypeBits := ackType.id

    val rnrTimeOut = Bits(RNR_TIMEOUT_WIDTH bits)
    rnrTimeOut := MIN_RNR_TIMEOUT

    setAckHelper(
      ackType,
      psn,
      dqpn,
      msn = 0,
      creditCnt = 0,
      rnrTimeOut = rnrTimeOut
    )
  }

  def setAck(
      ackType: SpinalEnumCraft[AckType.type],
      psn: UInt,
      dqpn: UInt,
      rnrTimeOut: Bits
  ): this.type = {
//    val ackTypeBits = Bits(ACK_TYPE_WIDTH bits)
//    ackTypeBits := ackType.id
    setAckHelper(ackType, psn, dqpn, msn = 0, creditCnt = 0, rnrTimeOut)
  }

  private def setAckHelper(
      ackType: SpinalEnumCraft[AckType.type],
      psn: UInt,
      dqpn: UInt,
      msn: Int,
      creditCnt: Int,
      rnrTimeOut: Bits
  ): this.type = {
    bth.set(opcode = OpCode.ACKNOWLEDGE.id, dqpn = dqpn, psn = psn)
    aeth.set(ackType, msn, creditCnt, rnrTimeOut)
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    aeth.setDefaultVal()
    this
  }
}

case class AtomicReq() extends RdmaBasePacket {
  val atomicEth = AtomicEth()

  def asRdmaDataPktFrag(busWidth: BusWidth.Value): Fragment[RdmaDataPkt] =
    new Composite(this) {
      val reqWidth = widthOf(bth) + widthOf(atomicEth)
      require(
        busWidth.id >= reqWidth,
        s"busWidth=${busWidth.id} must >= AtomicReq width=${reqWidth}"
      )
      val busWidthBytes = busWidth.id / BYTE_WIDTH
      val reqWidthBytes = reqWidth / BYTE_WIDTH

      val result = Fragment(RdmaDataPkt(busWidth))
      result.last := True
      result.bth := bth
      result.data := (bth.asBigEndianBits() ## atomicEth.asBigEndianBits())
        .resizeLeft(busWidth.id)

      result.mty := (setAllBits(reqWidthBytes) <<
        (busWidthBytes - reqWidthBytes))
    }.result

  def set(
      isCompSwapOrFetchAdd: Bool,
      dqpn: UInt,
      psn: UInt,
      va: UInt,
      rkey: Bits,
      comp: Bits,
      swap: Bits
  ): this.type = {
    val opcode = Bits(OPCODE_WIDTH bits)
    when(isCompSwapOrFetchAdd) {
      opcode := OpCode.COMPARE_SWAP.id
    } otherwise {
      opcode := OpCode.FETCH_ADD.id
    }

    bth.set(opcode, dqpn, psn)
    atomicEth.va := va
    atomicEth.rkey := rkey
    atomicEth.comp := comp
    atomicEth.swap := swap
    this
  }

  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    atomicEth.setDefaultVal()
    this
  }
}

case class AtomicResp() extends Response {
  val atomicAckEth = AtomicAckEth()

  def asRdmaDataPktFrag(busWidth: BusWidth.Value): Fragment[RdmaDataPkt] =
    new Composite(this) {
      val ackWidth = widthOf(bth) + widthOf(atomicAckEth)
      require(
        busWidth.id >= ackWidth,
        s"busWidth=${busWidth.id} must >= Atomic ACK width=${ackWidth}"
      )
      val busWidthBytes = busWidth.id / BYTE_WIDTH
      val ackWidthBytes = ackWidth / BYTE_WIDTH

      val result = Fragment(RdmaDataPkt(busWidth))
      result.last := True
      result.bth := bth
      result.data := (bth.asBigEndianBits() ## aeth
        .asBigEndianBits() ## atomicAckEth.asBigEndianBits())
        .resizeLeft(busWidth.id)
      result.mty := (setAllBits(ackWidthBytes) <<
        (busWidthBytes - ackWidthBytes))
    }.result

  def set(dqpn: UInt, psn: UInt, orig: Bits): this.type = {
    val opcode = Bits(OPCODE_WIDTH bits)
    opcode := OpCode.ATOMIC_ACKNOWLEDGE.id

    bth.set(opcode, dqpn, psn)
    // TODO: verify the AckType when atomic change failed
    aeth.set(AckType.NORMAL)
    atomicAckEth.orig := orig
    this
  }
  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    aeth.setDefaultVal()
    atomicAckEth.setDefaultVal()
    this
  }
}

case class CNP() extends RdmaBasePacket {
  val padding = CNPPadding()
}
