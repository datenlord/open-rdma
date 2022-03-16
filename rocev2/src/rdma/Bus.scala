package rdma

import spinal.core._
import spinal.lib._
import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._
import StreamVec._

case class DevMetaData() extends Bundle {
  val maxPendingReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val minRnrTimeOut = UInt(RNR_TIMEOUT_WIDTH bits)

  // TODO: remove this
  def setDefaultVal(): this.type = {
    maxPendingReqNum := PENDING_REQ_NUM
    maxPendingReadAtomicReqNum := MAX_PENDING_READ_ATOMIC_REQ_NUM
    minRnrTimeOut := MIN_RNR_TIMEOUT
    this
  }
}

case class SqRetryNotifier() extends Bundle {
  val pulse = Bool()
  val psnStart = UInt(PSN_WIDTH bits)
  val reason = RetryReason()

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
          L"${REPORT_TIME} time: impossible to have two SqRetryNotifier with the same PSN=${this.psnStart}",
        severity = FAILURE
      )
    }
    result
  }
}

case class RnrNakSeqClear() extends Bundle {
  val pulse = Bool()
}

case class RetryNak() extends Bundle {
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
          L"${REPORT_TIME} time: illegal AETH to set SqErrNotifier, aeth.code=${aeth.code}, aeth.value=${aeth.value}",
        severity = FAILURE
      )
      setLocalErr()
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

  def setRetryExc(): this.type = {
    errType := SqErrType.RETRY_EXC
    pulse := True
    this
  }

  def setRnrExc(): this.type = {
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
          L"${REPORT_TIME} time: SqErrNotifier.pulse=${pulse}, but errType=${errType} shows no error",
        severity = FAILURE
      )
    }
    pulse
  }

  def ||(that: SqErrNotifier): SqErrNotifier = {
    assert(
      assertion = !(this.hasFatalErr() && that.hasFatalErr()),
      message =
        L"${REPORT_TIME} time: cannot merge two SqErrNotifier both have fatal error, this.pulse=${this.pulse}, this.errType=${this.errType}, that.pulse=${that.pulse}, that.errType=${that.errType}",
      severity = FAILURE
    )
    val result = SqErrNotifier()
    result.pulse := this.pulse || that.pulse
    result.errType := (this.errType =/= SqErrType.NO_ERR) ? this.errType | that.errType
    result
  }
}

case class RqNakNotifier() extends Bundle {
  val rnr = RetryNak()
  val seqErr = RetryNak()
  val invReq = Bool()
  val rmtAcc = Bool()
  val rmtOp = Bool()
//  val localErr = Bool()

  def setFromAeth(
      aeth: AETH,
      pulse: Bool,
      preOpCode: Bits,
      psn: UInt
  ): this.type = {
    setNoErr()
    when(aeth.isRnrNak()) {
      setRnrNak(pulse, preOpCode, psn)
    } elsewhen (aeth.isSeqNak()) {
      setSeqErr(pulse, preOpCode, psn)
    } elsewhen (aeth.isInvReqNak() && pulse) {
      setInvReq()
    } elsewhen (aeth.isRmtAccNak() && pulse) {
      setRmtAcc()
    } elsewhen (aeth.isRmtOpNak() && pulse) {
      setRmtOp()
    } otherwise {
      report(
        message =
          L"${REPORT_TIME} time: illegal AETH to set NakNotifier, aeth.code=${aeth.code}, aeth.value=${aeth.value}",
        severity = WARNING
      )
      this.assignDontCare() // setInvReq()
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

//  private def setLocalErr(): this.type = {
//    localErr := True
//    this
//  }

  def setNoErr(): this.type = {
    rnr.setNoErr()
    seqErr.setNoErr()
    invReq := False
    rmtAcc := False
    rmtOp := False
//    localErr := False
    this
  }

  def hasFatalNak(): Bool = invReq || rmtAcc || rmtOp // || localErr

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
  val clearRnrOrNakSeq = RnrNakSeqClear()

  def hasFatalNak(): Bool = nak.hasFatalNak()
}

case class SqNotifier() extends Bundle {
  val err = SqErrNotifier()
  val retry = SqRetryNotifier()
  val workReqHasFence = Bool()
  val workReqCacheEmpty = Bool()
  val coalesceAckDone = Bool()

  def hasFatalErr(): Bool = err.hasFatalErr()
}

case class RxQCtrl() extends Bundle {
  val stateErrFlush = Bool()
  val rnrFlush = Bool()
  val rnrTimeOut = Bool()
  val nakSeqTrigger = Bool()
  val flush = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    stateErrFlush := False
    rnrFlush := False
    rnrTimeOut := True
    nakSeqTrigger := False
    flush := False
    this
  }
}

case class TxQCtrl() extends Bundle {
  val errorFlush = Bool()
  val retryFlush = Bool()
  val retry = Bool()
  val fencePulse = Bool()
  val fence = Bool()
  val wrongStateFlush = Bool()
  val fenceOrRetry = Bool()
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

case class QpAttrData() extends Bundle {
  val ipv4Peer = Bits(IPV4_WIDTH bits) // IPv4 only

  val pdId = Bits(PD_ID_WIDTH bits)
  val epsn = UInt(PSN_WIDTH bits)
  val npsn = UInt(PSN_WIDTH bits)
  val rqOutPsn = UInt(PSN_WIDTH bits)
  val sqOutPsn = UInt(PSN_WIDTH bits)
  val pmtu = Bits(PMTU_WIDTH bits)
  val maxPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val maxDstPendingReadAtomicReqNum = UInt(MAX_WR_NUM_WIDTH bits)
  val sqpn = UInt(QPN_WIDTH bits)
  val dqpn = UInt(QPN_WIDTH bits)

  // The previous received request opcode of RQ
  val rqPreReqOpCode = Bits(OPCODE_WIDTH bits)

  val retryStartPsn = UInt(PSN_WIDTH bits)
  val retryReason = RetryReason()
  val maxRetryCnt = UInt(RETRY_COUNT_WIDTH bits)
  val rnrTimeOut = Bits(RNR_TIMEOUT_WIDTH bits)
  // respTimeOut need to be converted to actual cycle number,
  // by calling getRespTimeOut()
  val respTimeOut = Bits(RESP_TIMEOUT_WIDTH bits)

//  val fence = Bool()
//  val psnBeforeFence = UInt(PSN_WIDTH bits)

  val state = Bits(QP_STATE_WIDTH bits)

  val modifyMask = Bits(QP_ATTR_MASK_WIDTH bits)

  def isValid = state =/= QpState.RESET.id
  def isReset = state === QpState.RESET.id

  def initOrReset(): this.type = {
    ipv4Peer := 0
    pdId := 0
    epsn := 0
    npsn := 0
    rqOutPsn := 0
    sqOutPsn := 0
    pmtu := PMTU.U1024.id
    maxPendingReadAtomicReqNum := 0
    maxDstPendingReadAtomicReqNum := 0
    sqpn := 0
    dqpn := 0

//    nakSeqTrigger := False
//    rnrTrigger := False

    rqPreReqOpCode := OpCode.SEND_ONLY.id
    rnrTimeOut := 1 // 1 means 0.01ms
    respTimeOut := 17 // 17 means 536.8709ms
    retryStartPsn := 0
    retryReason := RetryReason.RESP_TIMEOUT
    maxRetryCnt := 3

//    fence := False
//    psnBeforeFence := 0

    state := QpState.RESET.id

    modifyMask := 0
    this
  }

// RNR timeout settings:
//  0 - 655.36 milliseconds delay
//  1 - 0.01 milliseconds delay
//  2 - 0.02 milliseconds delay
//  3 - 0.03 milliseconds delay
//  4 - 0.04 milliseconds delay
//  5 - 0.06 milliseconds delay
//  6 - 0.08 milliseconds delay
//  7 - 0.12 milliseconds delay
//  8 - 0.16 milliseconds delay
//  9 - 0.24 milliseconds delay
//  10 - 0.32 milliseconds delay
//  11 - 0.48 milliseconds delay
//  12 - 0.64 milliseconds delay
//  13 - 0.96 milliseconds delay
//  14 - 1.28 milliseconds delay
//  15 - 1.92 milliseconds delay
//  16 - 2.56 milliseconds delay
//  17 - 3.84 milliseconds delay
//  18 - 5.12 milliseconds delay
//  19 - 7.68 milliseconds delay
//  20 - 10.24 milliseconds delay
//  21 - 15.36 milliseconds delay
//  22 - 20.48 milliseconds delay
//  23 - 30.72 milliseconds delay
//  24 - 40.96 milliseconds delay
//  25 - 61.44 milliseconds delay
//  26 - 81.92 milliseconds delay
//  27 - 122.88 milliseconds delay
//  28 - 163.84 milliseconds delay
//  29 - 245.76 milliseconds delay
//  30 - 327.68 milliseconds delay
//  31 - 491.52 milliseconds delay
  def getRnrTimeOut(): UInt =
    new Composite(this) {
      val result = UInt()
      switch(rnrTimeOut) {
        is(0) {
          result := timeNumToCycleNum(655360 us)
        }
        is(1) {
          result := timeNumToCycleNum(10 us)
        }
        is(2) {
          result := timeNumToCycleNum(20 us)
        }
        is(3) {
          result := timeNumToCycleNum(30 us)
        }
        is(4) {
          result := timeNumToCycleNum(40 us)
        }
        is(5) {
          result := timeNumToCycleNum(60 us)
        }
        is(6) {
          result := timeNumToCycleNum(80 us)
        }
        is(7) {
          result := timeNumToCycleNum(120 us)
        }
        is(8) {
          result := timeNumToCycleNum(160 us)
        }
        is(9) {
          result := timeNumToCycleNum(240 us)
        }
        is(10) {
          result := timeNumToCycleNum(320 us)
        }
        is(11) {
          result := timeNumToCycleNum(480 us)
        }
        is(12) {
          result := timeNumToCycleNum(640 us)
        }
        is(13) {
          result := timeNumToCycleNum(960 us)
        }
        is(14) {
          result := timeNumToCycleNum(1280 us)
        }
        is(15) {
          result := timeNumToCycleNum(1920 us)
        }
        is(16) {
          result := timeNumToCycleNum(2560 us)
        }
        is(17) {
          result := timeNumToCycleNum(3840 us)
        }
        is(18) {
          result := timeNumToCycleNum(5120 us)
        }
        is(19) {
          result := timeNumToCycleNum(7680 us)
        }
        is(20) {
          result := timeNumToCycleNum(10240 us)
        }
        is(21) {
          result := timeNumToCycleNum(15360 us)
        }
        is(22) {
          result := timeNumToCycleNum(20480 us)
        }
        is(23) {
          result := timeNumToCycleNum(30720 us)
        }
        is(24) {
          result := timeNumToCycleNum(40960 us)
        }
        is(25) {
          result := timeNumToCycleNum(61440 us)
        }
        is(26) {
          result := timeNumToCycleNum(81920 us)
        }
        is(27) {
          result := timeNumToCycleNum(122880 us)
        }
        is(28) {
          result := timeNumToCycleNum(163840 us)
        }
        is(29) {
          result := timeNumToCycleNum(245760 us)
        }
        is(30) {
          result := timeNumToCycleNum(327680 us)
        }
        is(31) {
          result := timeNumToCycleNum(491520 us)
        }
        default {
          report(
            message =
              L"${REPORT_TIME} time: invalid rnrTimeOut=${rnrTimeOut}, should between 0 and 31",
            severity = FAILURE
          )
          result := 0
        }
      }
    }.result

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
  def getRespTimeOut(): UInt =
    new Composite(this) {
      val maxCycleNum = timeNumToCycleNum(MAX_RESP_TIMEOUT)
      val result = UInt(log2Up(maxCycleNum) bits)
      switch(respTimeOut) {
        is(INFINITE_RESP_TIMEOUT) {
          // Infinite
          result := INFINITE_RESP_TIMEOUT
        }
        for (timeOut <- 1 until (1 << RESP_TIMEOUT_WIDTH)) {
          is(timeOut) {
            result := timeNumToCycleNum(
              BigDecimal(BigInt(8192) << (timeOut - 1)) ns
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

case class QpStateChange() extends Bundle {
  val changeToState = Bits(QP_STATE_WIDTH bits)
  val changePulse = Bool()

  // TODO: remove this
  def setDefaultVal(): this.type = {
    changeToState := QpState.ERR.id
    changePulse := False
    this
  }
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

case class DmaReadResp(busWidth: BusWidth) extends Bundle {
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

case class DmaReadRespBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val resp = Stream(Fragment(DmaReadResp(busWidth)))

  def >>(that: DmaReadRespBus): Unit = {
    this.resp >> that.resp
  }

  def <<(that: DmaReadRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class DmaReadBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val req = Stream(DmaReadReq())
  val resp = Stream(Fragment(DmaReadResp(busWidth)))

  def arbitReq(dmaRdReqVec: Vec[Stream[DmaReadReq]]) = new Area {
    val dmaRdReqSel =
      StreamArbiterFactory.roundRobin.transactionLock.on(dmaRdReqVec)
    req <-/< dmaRdReqSel
  }

  def deMuxRespByInitiator(
      rqRead: Stream[Fragment[DmaReadResp]],
//      rqDup: Stream[Fragment[DmaReadResp]],
      rqAtomicRead: Stream[Fragment[DmaReadResp]],
      sqRead: Stream[Fragment[DmaReadResp]],
      sqDup: Stream[Fragment[DmaReadResp]]
  ) = new Area {
    val txSel = UInt(3 bits)
//    val (rqReadIdx, rqDupIdx, rqAtomicReadIdx, sqReadIdx, sqDupIdx, otherIdx) =
//      (0, 1, 2, 3, 4, 5)
    val (rqReadIdx, rqAtomicReadIdx, sqReadIdx, sqDupIdx, otherIdx) =
      (0, 1, 2, 3, 4)

    switch(resp.initiator) {
//      is(DmaInitiator.RQ_RD) {
//        txSel := rqReadIdx
//      }
//      is(DmaInitiator.RQ_DUP) {
//        txSel := rqDupIdx
//      }
      is(DmaInitiator.RQ_RD, DmaInitiator.RQ_DUP) {
        txSel := rqReadIdx
      }
      is(DmaInitiator.RQ_ATOMIC_RD) {
        txSel := rqAtomicReadIdx
      }
      is(DmaInitiator.SQ_RD) {
        txSel := sqReadIdx
      }
      is(DmaInitiator.SQ_DUP) {
        txSel := sqDupIdx
      }
      default {
        report(
          message =
            L"${REPORT_TIME} time: invalid DMA initiator=${resp.initiator}, should be RQ_RD, RQ_DUP, RQ_ATOMIC_RD, SQ_RD, SQ_DUP",
          severity = FAILURE
        )
        txSel := otherIdx
      }
    }
    Vec(
      rqRead,
//      rqDup,
      rqAtomicRead,
      sqRead,
      sqDup,
      StreamSink(rqRead.payloadType)
    ) <-/< StreamDemux(resp, select = txSel, portCount = 5) // portCount = 6
  }

  def arbitReqAndDemuxRespByQpn(
      dmaRdReqVec: Vec[Stream[DmaReadReq]],
      dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]],
      qpAttrVec: Vec[QpAttrData]
  ) = new Area {
    val dmaRdReqSel =
      StreamArbiterFactory.roundRobin.transactionLock.on(dmaRdReqVec)
    req <-/< dmaRdReqSel

    val dmaRdRespOH = qpAttrVec.map(_.sqpn === resp.sqpn)
    val foundRespTargetQp = dmaRdRespOH.orR
    when(resp.valid) {
      assert(
        assertion = foundRespTargetQp,
        message =
          L"${REPORT_TIME} time: failed to find DMA read response target QP with QPN=${resp.sqpn}",
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

case class DmaWriteReq(busWidth: BusWidth) extends Bundle {
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

case class DmaWriteReqBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
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

case class DmaWriteBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val req = Stream(Fragment(DmaWriteReq(busWidth)))
  val resp = Stream(DmaWriteResp())

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
          L"${REPORT_TIME} time: failed to find DMA write response target QP with QPN=${resp.sqpn}",
        severity = FAILURE
      )
    }
    dmaWrRespVec <-/< StreamOneHotDeMux(resp, dmaWrRespOH.asBits())
  }

  def arbitReq(dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]]) =
    new Area {
      val dmaWrReqSel =
        StreamArbiterFactory.roundRobin.fragmentLock.on(dmaWrReqVec)
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
            L"${REPORT_TIME} time: invalid DMA initiator=${resp.initiator}, should be RQ_WR, RQ_ATOMIC_WR, RQ_WR, SQ_ATOMIC_WR",
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

case class DmaBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
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

case class SqDmaBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val reqSender = DmaReadBus(busWidth)
  val retry = DmaReadBus(busWidth)
  val readResp = DmaWriteBus(busWidth)
  val atomic = DmaWriteBus(busWidth)

  def dmaWrReqVec: Vec[Stream[Fragment[DmaWriteReq]]] = {
    Vec(readResp.req, atomic.req)
  }

  def dmaWrRespVec: Vec[Stream[DmaWriteResp]] = {
    Vec(readResp.resp, atomic.resp)
  }

  def dmaRdReqVec: Vec[Stream[DmaReadReq]] = {
    Vec(reqSender.req, retry.req)
  }

  def dmaRdRespVec: Vec[Stream[Fragment[DmaReadResp]]] = {
    Vec(reqSender.resp, retry.resp)
  }

//  def >>(that: SqDmaBus): Unit = {
//    this.sq >> that.sq
//    this.retry >> that.retry
//  }
//
//  def <<(that: SqDmaBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(reqSender, retry, readResp, atomic)
  }
}

case class RqDmaBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
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

case class WorkReq() extends Bundle {
  val id = Bits(WR_ID_WIDTH bits)
  val opcode = WorkReqOpCode() // Bits(WR_OPCODE_WIDTH bits)
  val raddr = UInt(MEM_ADDR_WIDTH bits)
  val rkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
//  val solicited = Bool()
  val sqpn = UInt(QPN_WIDTH bits)
  val ackreq = Bool()
  val flags = Bits(WR_FLAG_WIDTH bits)
//  val fence = Bool()
  val swap = Bits(LONG_WIDTH bits)
  val comp = Bits(LONG_WIDTH bits)
  val immDtOrRmtKeyToInv = Bits(LRKEY_IMM_DATA_WIDTH bits)

  // TODO: assume single SG, if SGL, pa, len and lkey should come from SGL
  val laddr = UInt(MEM_ADDR_WIDTH bits)
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  val lkey = Bits(LRKEY_IMM_DATA_WIDTH bits)

  def fence = (flags & WorkReqSendFlags.FENCE.asBits).orR
  def signaled = (flags & WorkReqSendFlags.SIGNALED.asBits).orR
  def solicited = (flags & WorkReqSendFlags.SOLICITED.asBits).orR
  def inline = (flags & WorkReqSendFlags.INLINE.asBits).orR
  def ipChkSum = (flags & WorkReqSendFlags.IP_CSUM.asBits).orR

  // TODO: remove this
  def setDefaultVal(): this.type = {
    id := 0
    opcode := WorkReqOpCode.RDMA_WRITE // Default WR opcode
    raddr := 0
    rkey := 0
//    solicited := False
    sqpn := 0
    ackreq := False
    flags := 0
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

case class CachedWorkReq() extends Bundle {
  val workReq = WorkReq()
  val psnStart = UInt(PSN_WIDTH bits)
  val pktNum = UInt(PSN_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val rnrCnt = UInt(RETRY_COUNT_WIDTH bits)
  val retryCnt = UInt(RETRY_COUNT_WIDTH bits)

  // Used for cache to set initial CachedWorkReq value
  def setInitVal(): this.type = {
    workReq.setDefaultVal()
    psnStart := 0
    pktNum := 0
    pa := 0
    rnrCnt := 0
    retryCnt := 0
    this
  }

  def psnWithIn(psn: UInt, curPsn: UInt): Bool =
    new Composite(this) {
//      val psnEnd = psnStart + pktNum
      val result = PsnUtil.withInRange(psn, psnStart, pktNum, curPsn)
    }.result

  def incRnrOrRetryCnt(retryReason: SpinalEnumCraft[RetryReason.type]) =
    new Composite(this) {
      switch(retryReason) {
        is(RetryReason.RNR) {
          rnrCnt := rnrCnt + 1
        }
        is(
          RetryReason.IMPLICIT_ACK,
          RetryReason.SEQ_ERR,
          RetryReason.RESP_TIMEOUT
        ) {
          retryCnt := retryCnt + 1
        }
        default {
          report(
            message =
              L"${REPORT_TIME} time: input retryReason=${retryReason} should be valid reason",
            severity = FAILURE
          )
        }
      }
    }
}

case class WorkReqCacheQueryReq() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
}

case class WorkReqCacheQueryReqBus() extends Bundle with IMasterSlave {
  val req = Stream(WorkReqCacheQueryReq())

  override def asMaster(): Unit = {
    master(req)
  }
}

case class WorkReqCacheResp() extends Bundle {
  val cachedWorkReq = CachedWorkReq()
  val query = WorkReqCacheQueryReq()
  val found = Bool()
}

case class WorkReqCacheRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(WorkReqCacheResp())

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class WorkReqCacheQueryBus() extends Bundle with IMasterSlave {
  val req = Stream(WorkReqCacheQueryReq())
  val resp = Stream(WorkReqCacheResp())

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
  val duplicate = Bool()

  // TODO: remote this
  def setDefaultVal(): this.type = {
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
    duplicate := False
    this
  }
}

case class ReadAtomicRstCacheReq() extends Bundle {
  val psn = UInt(PSN_WIDTH bits)
}

case class ReadAtomicRstCacheResp() extends Bundle {
  val cachedData = ReadAtomicRstCacheData()
  val query = ReadAtomicRstCacheReq()
  val found = Bool()
}

case class ReadAtomicRstCacheReqBus() extends Bundle with IMasterSlave {
  val req = Stream(ReadAtomicRstCacheReq())

//  def >>(that: ReadAtomicRstCacheReqBus): Unit = {
//    this.req >> that.req
//  }
//
//  def <<(that: ReadAtomicRstCacheReqBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
  }
}

case class ReadAtomicRstCacheRespBus() extends Bundle with IMasterSlave {
  val resp = Stream(ReadAtomicRstCacheResp())

//  def >>(that: ReadAtomicRstCacheRespBus): Unit = {
//    this.resp >> that.resp
//  }
//
//  def <<(that: ReadAtomicRstCacheRespBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(resp)
  }
}

case class ReadAtomicRstCacheQueryBus() extends Bundle with IMasterSlave {
  val req = Stream(ReadAtomicRstCacheReq())
  val resp = Stream(ReadAtomicRstCacheResp())

  def >>(that: ReadAtomicRstCacheQueryBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: ReadAtomicRstCacheQueryBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

case class CombineHeaderAndDmaRespInternalRst(busWidth: BusWidth)
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
    busWidth: BusWidth
) extends Bundle {
  val dmaReadResp = DmaReadResp(busWidth)
  val req = reqType()
}

/** for RQ */
//case class ReadAtomicRstCacheRespAndDmaReadResp(busWidth: BusWidth)
//    extends Bundle {
//  val dmaReadResp = DmaReadResp(busWidth)
//  val resultCacheResp = ReadAtomicRstCacheResp()
//}

//object ABC {
//  type ReadAtomicRstCacheDataAndDmaReadResp =
//    ReqAndDmaReadResp[ReadAtomicRstCacheData]
//}
case class ReadAtomicRstCacheDataAndDmaReadResp(busWidth: BusWidth)
    extends Bundle {
  val dmaReadResp = DmaReadResp(busWidth)
  val resultCacheData = ReadAtomicRstCacheData()
}

/** for SQ */
case class CachedWorkReqAndDmaReadResp(busWidth: BusWidth) extends Bundle {
  val dmaReadResp = DmaReadResp(busWidth)
  val cachedWorkReq = CachedWorkReq()
//  val workReqCacheResp = WorkReqCacheResp()
}

case class WorkCompAndAck() extends Bundle {
//  val workCompValid = Bool()
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
      s"widthOf(IETH())=${widthOf(IETH())} should == widthOf(ImmDt())=${widthOf(ImmDt())}"
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
    } elsewhen (OpCode.isWriteWithImmReqPkt(reqOpCode)) {
      opcode := WorkCompOpCode.RECV_RDMA_WITH_IMM
    } otherwise {
//      report(
//        message =
//          L"${REPORT_TIME} time: unmatched WC opcode at RQ side for request opcode=${reqOpCode}",
//        severity = WARNING
//      )
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
    immDtOrRmtKeyToInv := 0
    this
  }
}

case class QpCreateOrModifyReq() extends Bundle {
  val qpAttr = QpAttrData()
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

case class PdAddrCacheReadReq() extends Bundle {
  val initiator = AddrQueryInitiator()
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val key = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val pdId = Bits(PD_ID_WIDTH bits)
  val remoteOrLocalKey = Bool() // True: remote, False: local
  val accessType = AccessType() // Bits(ACCESS_TYPE_WIDTH bits)
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

case class PdAddrCacheReadBus() extends Bundle with IMasterSlave {
  val req = Stream(PdAddrCacheReadReq())
  val resp = Stream(PdAddrCacheReadResp())

  def >>(that: PdAddrCacheReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: PdAddrCacheReadBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

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
  val successOrFailure = Bool()
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
  val accessType = AccessType() // Bits(ACCESS_TYPE_WIDTH bits)
  val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val dataLenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)

  def init(): this.type = {
    lkey := 0
    rkey := 0
    accessType := AccessType.LOCAL_READ // Default AccessType
    va := 0
    pa := 0
    dataLenBytes := 0
    this
  }
}

case class QpAddrCacheAgentReadReq() extends Bundle {
  val sqpn = UInt(QPN_WIDTH bits)
  val psn = UInt(PSN_WIDTH bits)
  val key = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val pdId = Bits(PD_ID_WIDTH bits)
  // TODO: consider remove remoteOrLocalKey
  private val remoteOrLocalKey = Bool() // True: remote, False: local
  val accessType = AccessType() // Bits(ACCESS_TYPE_WIDTH bits)
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
    accessType := AccessType.LOCAL_READ // Default AccessType
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

case class QpAddrCacheAgentReadBus() extends Bundle with IMasterSlave {
  val req = Stream(QpAddrCacheAgentReadReq())
  val resp = Stream(QpAddrCacheAgentReadResp())

  def >>(that: QpAddrCacheAgentReadBus): Unit = {
    this.req >> that.req
    this.resp << that.resp
  }

  def <<(that: QpAddrCacheAgentReadBus): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }

//  def sendQpAddrCacheAgentReq(reqValid: Bool,
//                       accessKey: Bits,
//                       accessType: Bits,
//                       pd: Bits,
//                       remoteOrLocalKey: Bool,
//                       va: UInt,
//                       dataLenBytes: UInt) = new Area {
//    req <-/< StreamSource()
//      .throwWhen(!reqValid)
//      .translateWith {
//        val addrCacheReadReq = QpAddrCacheAgentReadReq()
//        addrCacheReadReq.key := accessKey
//        addrCacheReadReq.pd := pd
//        addrCacheReadReq.remoteOrLocalKey := remoteOrLocalKey
//        addrCacheReadReq.accessType := accessType
//        addrCacheReadReq.va := va
//        addrCacheReadReq.dataLenBytes := dataLenBytes
//        addrCacheReadReq
//      }
//  }

//  def joinWithQpAddrCacheAgentRespStream[T <: Data](streamIn: Stream[T],
//                                             joinCond: Bool) =
//    new Composite(resp) {
//      val invalidStream =
//        StreamSource().translateWith(QpAddrCacheAgentReadResp().setDefaultVal())
//      val addrCacheRespStream =
//        StreamMux(select = joinCond.asUInt, Vec(invalidStream, resp))
//      val joinedStream = StreamJoin(streamIn, addrCacheRespStream)
//        .pipelined(m2s = true, s2m = true)
//    }.joinedStream
}

//case class RqQpAddrCacheAgentReadBus() extends Bundle with IMasterSlave {
//  val bus = QpAddrCacheAgentReadBus()
//
//  def >>(that: RqQpAddrCacheAgentReadBus): Unit = {
//    this.bus >> that.bus
//  }
////  val sendWrite = QpAddrCacheAgentReadBus()
////  val read = QpAddrCacheAgentReadBus()
////  val atomic = QpAddrCacheAgentReadBus()
////
////  def >>(that: RqQpAddrCacheAgentReadBus): Unit = {
////    this.sendWrite >> that.sendWrite
////    this.read >> that.read
////    this.atomic >> that.atomic
////  }
//
//  def <<(that: RqQpAddrCacheAgentReadBus): Unit = that >> this
//
//  def asMaster(): Unit = {
//    master(bus)
//    // master(sendWrite, read, atomic)
//  }
//}

case class SqOrRetryQpAddrCacheAgentReadBus() extends Bundle with IMasterSlave {
  val send = QpAddrCacheAgentReadBus()
  val write = QpAddrCacheAgentReadBus()

  def >>(that: SqOrRetryQpAddrCacheAgentReadBus): Unit = {
    this.send >> that.send
    this.write >> that.write
  }

  def <<(that: SqOrRetryQpAddrCacheAgentReadBus): Unit = that >> this

  def asMaster(): Unit = {
    master(send, write)
  }
}

case class RespPsnRange() extends Bundle {
  val opcode = Bits(OPCODE_WIDTH bits)
  val start = UInt(PSN_WIDTH bits)
  // end PSN is included in the range
  val end = UInt(PSN_WIDTH bits)
}

case class ReqPsnRange() extends Bundle {
  val opcode = WorkReqOpCode() // Bits(WR_OPCODE_WIDTH bits)
  val start = UInt(PSN_WIDTH bits)
  // end PSN is included in the range
  val end = UInt(PSN_WIDTH bits)
}

case class UdpMetaData() extends Bundle {
  val ip = Bits(IPV4_WIDTH bits) // IPv4 only
  val lenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
}

case class UdpData(busWidth: BusWidth) extends Bundle {
  val udp = UdpMetaData()
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
  val sop = Bool()
}

case class UdpDataBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
  val pktFrag = Stream(Fragment(UdpData(busWidth)))

  def >>(that: UdpDataBus): Unit = {
    this.pktFrag >> that.pktFrag
  }

  def <<(that: UdpDataBus): Unit = that >> this

  override def asMaster(): Unit = master(pktFrag)
}

//----------Combined packets----------//
// TODO: defined as IMasterSlave
case class RdmaDataBus(busWidth: BusWidth) extends Bundle with IMasterSlave {
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

case class SqReadAtomicRespWithDmaInfo(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val workReqId = Bits(WR_ID_WIDTH bits)
}

case class SqReadAtomicRespWithDmaInfoBus(busWidth: BusWidth)
    extends Bundle
    with IMasterSlave {
  val respWithDmaInfo = Stream(Fragment(SqReadAtomicRespWithDmaInfo(busWidth)))

  def >>(that: SqReadAtomicRespWithDmaInfoBus): Unit = {
    this.respWithDmaInfo >> that.respWithDmaInfo
  }

  def <<(that: SqReadAtomicRespWithDmaInfoBus): Unit = that >> this

  override def asMaster(): Unit = master(respWithDmaInfo)
}

case class DmaInfo() extends Bundle {
  val va = UInt(MEM_ADDR_WIDTH bits)
  val pa = UInt(MEM_ADDR_WIDTH bits)
  val lrkey = Bits(LRKEY_IMM_DATA_WIDTH bits)
  val dlen = UInt(RDMA_MAX_LEN_WIDTH bits)

  def init(): this.type = {
    va := 0
    pa := 0
    lrkey := 0
    dlen := 0
    this
  }
}

case class RqReqCheckRst() extends Bundle {
  val isPsnCheckPass = Bool()
  val isDupReq = Bool()
  val isOpSeqCheckPass = Bool()
  val isSupportedOpCode = Bool()
  val isPadCntCheckPass = Bool()
  val isReadAtomicRstCacheFull = Bool()
  val epsn = UInt(PSN_WIDTH bits)
}

case class RqReqWithRxBuf(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val preOpCode = Bits(OPCODE_WIDTH bits)
  val hasNak = Bool()
  val nakAeth = AETH()
  // RxWorkReq is only valid at the first or only fragment for send,
  // or valid at the last or only fragment for write imm
  val rxBufValid = Bool()
  val rxBuf = RxWorkReq()
}

case class RqReqWithRxBufBus(busWidth: BusWidth)
    extends Bundle
    with IMasterSlave {
  val reqWithRxBuf = Stream(Fragment(RqReqWithRxBuf(busWidth)))

  def >>(that: RqReqWithRxBufBus): Unit = {
    this.reqWithRxBuf >> that.reqWithRxBuf
  }

  def <<(that: RqReqWithRxBufBus): Unit = that >> this

  override def asMaster(): Unit = master(reqWithRxBuf)
}

case class RqReqCheckInternalOutput(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val checkRst = RqReqCheckRst()
}

case class RqReqCheckStageOutput(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val preOpCode = Bits(OPCODE_WIDTH bits)
  val hasNak = Bool()
  val nakAeth = AETH()
}

case class RqReqCommCheckRstBus(busWidth: BusWidth)
    extends Bundle
    with IMasterSlave {
  val checkRst = Stream(Fragment(RqReqCheckStageOutput(busWidth)))

  def >>(that: RqReqCommCheckRstBus): Unit = {
    this.checkRst >> that.checkRst
  }

  def <<(that: RqReqCommCheckRstBus): Unit = that >> this

  override def asMaster(): Unit = master(checkRst)
}

case class RqReqWithRxBufAndDmaInfo(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val preOpCode = Bits(OPCODE_WIDTH bits)
  val hasNak = Bool()
  val nakAeth = AETH()
  // reqTotalLenValid is only for the last fragment of send/write request packet
  val reqTotalLenValid = Bool()
  val reqTotalLenBytes = UInt(RDMA_MAX_LEN_WIDTH bits)
  // RxWorkReq is only valid at the first or only fragment for send,
  // or valid at the last or only fragment for write imm
  val rxBufValid = Bool()
  val rxBuf = RxWorkReq()
  // DmaInfo is only valid at the first or only fragment
  // TODO: remove this, DMA info should be always valid
//  val dmaHeaderValid = Bool()
  val dmaInfo = DmaInfo()
}

case class RqReqWithRxBufAndDmaInfoBus(busWidth: BusWidth)
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

case class RqDupReadReqAndRstCacheData(busWidth: BusWidth) extends Bundle {
  val pktFrag = RdmaDataPkt(busWidth)
  val cachedData = ReadAtomicRstCacheData()
}

case class RqDmaReadReqAndRstCacheData() extends Bundle {
  val dmaReadReq = DmaReadReq()
  val cachedData = ReadAtomicRstCacheData()
}

sealed abstract class RdmaBasePacket extends Bundle {
  // this: Bundle => // RdmaDataPkt must be of Bundle class
  val bth = BTH()
  // val eth = Bits(ETH_WIDTH bits)
}

case class DataAndMty(busWidth: BusWidth) extends Bundle {
  require(isPow2(busWidth.id), s"width=${busWidth.id} should be power of 2")
  val data = Bits(busWidth.id bits)
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
}

case class HeaderDataAndMty[T <: Data](
    headerType: HardType[T],
    busWidth: BusWidth
) extends Bundle {
  //  type DataAndMty = HeaderDataAndMty[NoData]

  val header = headerType()
  val data = Bits(busWidth.id bits)
  // UInt() avoid sparse
  val mty = Bits((busWidth.id / BYTE_WIDTH) bits)
}

object RdmaDataPkt {
  def apply(busWidth: BusWidth) = new RdmaDataPkt(busWidth)
}

sealed class RdmaDataPkt(busWidth: BusWidth) extends RdmaBasePacket {
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

case class SendReq(busWidth: BusWidth)
    extends RdmaDataPkt(busWidth)
    with ImmDtHeader
    with IethHeader {}

case class WriteReq(busWidth: BusWidth)
    extends RdmaDataPkt(busWidth)
    with RdmaReq
    with ImmDtHeader {}

case class ReadReq() extends RdmaReq {
  def toRdmaDataPktFrag(busWidth: BusWidth): Fragment[RdmaDataPkt] =
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
      result.data := (bth ## reth).resize(busWidth.id)
      // TODO: verify endian
      result.mty := (setAllBits(
        reqWidthBytes
      ) << (busWidthBytes - reqWidthBytes))
    }.result

  def set(thatBth: BTH, rethBits: Bits): this.type = {
    bth := thatBth
    // TODO: verify rethBits is big endian
    reth.assignFromBits(rethBits)
    this
  }

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

case class ReadOnlyFirstLastResp(busWidth: BusWidth)
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

case class ReadMidResp(busWidth: BusWidth) extends RdmaDataPkt(busWidth) {}

case class Acknowledge() extends Response {
  def toRdmaDataPktFrag(busWidth: BusWidth): Fragment[RdmaDataPkt] =
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
      result.data := (bth ## aeth).resize(busWidth.id)
      // TODO: verify endian
      result.mty := (setAllBits(
        ackWidthBytes
      ) << (busWidthBytes - ackWidthBytes))
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

  def toRdmaDataPktFrag(busWidth: BusWidth): Fragment[RdmaDataPkt] =
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
      result.data := (bth ## atomicEth).resize(busWidth.id)
      // TODO: verify endian
      result.mty := (setAllBits(
        reqWidthBytes
      ) << (busWidthBytes - reqWidthBytes))
    }.result

  def set(
      isCompSwap: Bool,
      dqpn: UInt,
      psn: UInt,
      va: UInt,
      rkey: Bits,
      comp: Bits,
      swap: Bits
  ): this.type = {
    val opcode = Bits(OPCODE_WIDTH bits)
    when(isCompSwap) {
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
  val atomicAckETH = AtomicAckETH()

  def toRdmaDataPktFrag(busWidth: BusWidth): Fragment[RdmaDataPkt] =
    new Composite(this) {
      val ackWidth = widthOf(bth) + widthOf(atomicAckETH)
      require(
        busWidth.id >= ackWidth,
        s"busWidth=${busWidth.id} must >= Atomic ACK width=${ackWidth}"
      )
      val busWidthBytes = busWidth.id / BYTE_WIDTH
      val ackWidthBytes = ackWidth / BYTE_WIDTH

      val result = Fragment(RdmaDataPkt(busWidth))
      result.last := True
      result.bth := bth
      result.data := (bth ## atomicAckETH).resize(busWidth.id)
      // TODO: verify endian
      result.mty := (setAllBits(
        ackWidthBytes
      ) << (busWidthBytes - ackWidthBytes))
    }.result

  def set(dqpn: UInt, psn: UInt, orig: Bits): this.type = {
    val opcode = Bits(OPCODE_WIDTH bits)
    opcode := OpCode.ATOMIC_ACKNOWLEDGE.id

    bth.set(opcode, dqpn, psn)
    // TODO: verify the AckType when atomic change failed
    aeth.set(AckType.NORMAL)
    atomicAckETH.orig := orig
    this
  }
  // TODO: remove this
  def setDefaultVal(): this.type = {
    bth.setDefaultVal()
    aeth.setDefaultVal()
    atomicAckETH.setDefaultVal()
    this
  }
}

case class CNP() extends RdmaBasePacket {
  val padding = CNPPadding()
}
