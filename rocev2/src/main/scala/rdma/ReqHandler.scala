package rdma

import spinal.core._
import spinal.lib._
import BusWidth.BusWidth
import RdmaConstants._
import ConstantSettings._

class ReqPreValidator(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpAttrUpdate = in(QpAttrUpdateNotifier())
    val rnrNotify = in(NakNotifier())
    val rnrClear = out(Bool())
    val recvQFlush = in(QpFlushNotifier())
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
    val txDupReq = master(Stream(RdmaDataBus(busWidth)))
    val txErrResp = master(Stream(RdmaNonReadRespBus()))
  }

  val expectRnrRetryReg = RegInit(False)
  val ePsnPreOpCodeUpdate = io.recvQFlush.stateErrFlush
  val epsnReg = Reg(UInt(PSN_WIDTH bits))
  val preOpCodeReg = Reg(Bits(OPCODE_WIDTH bits)) init (OpCode.SEND_FIRST.id)
  when(io.qpAttrUpdate.pulseRqPsnReset) {
    epsnReg := io.qpAttr.epsn
    // When RQ ePSN reset, assume previous opcode is SEND_ONLY to avoid OpCode sequence check failure
    preOpCodeReg := OpCode.SEND_ONLY.id
  } elsewhen (io.rnrNotify.rnrPulse) {
    expectRnrRetryReg := True
    epsnReg := io.rnrNotify.rnrNakPsn
    // When RQ reset ePSN to RNR PSN, assume previous opcode is SEND_ONLY to avoid OpCode sequence check failure
    preOpCodeReg := OpCode.SEND_ONLY.id
  } elsewhen (ePsnPreOpCodeUpdate) {
    // Only update ePSN and previous opcode when ePSN matches and input is valid
    epsnReg := epsnReg + 1
    preOpCodeReg := io.rx.bth.opcode
  }

  val checkStageContinue = True
  // This stage cannot be flushed, since it might need to process duplicated requests
  val checkStageFlush = False
  val checkStageOut =
    StreamPipeStage(
      continue = checkStageContinue,
      flush = checkStageFlush,
      input = io.rx
    )((inputPayload, inputValid) => {
      // PSN sequence check
      val psnCheckPass = Bool()
      val dupReq = Bool()
      val cmpRslt = psnComp(inputPayload.bth.psn, epsnReg, curPsn = epsnReg)
      switch(cmpRslt) {
        is(PsnCompResult.GREATER.id) {
          psnCheckPass := False
          dupReq := False
        }
        is(PsnCompResult.LESSER.id) {
          psnCheckPass := inputValid
          dupReq := inputValid
        }
        default { // PsnCompResult.EQUAL
          psnCheckPass := inputValid
          dupReq := False
        }
      }

      // OpCode sequence check
      val opSeqCheckRslt =
        OpCodeSeq.checkReqSeq(preOpCodeReg, inputPayload.bth.opcode)

      // Packet length check
      val pktLenCheckRslt = pktLengthCheck(inputPayload, busWidth)

      val rslt = TupleBundle(
        inputPayload,
        epsnReg,
        psnCheckPass,
        dupReq,
        pktLenCheckRslt,
        opSeqCheckRslt
      )
      rslt
    })

  val dataBuildStageContinue = True
  val dataBuildStageFlush = io.recvQFlush.stateErrFlush
  val dataBuildStageOut = StreamPipeStage(
    continue = dataBuildStageContinue,
    flush = dataBuildStageFlush,
    input = checkStageOut
  )((inputPayload, inputValid) => {
    val (rdmaPkt, epsn, psnCheckPass, dupReq, pktLenCheckRslt, opSeqCheckRslt) =
      asTuple(inputPayload)

    val commCheckPass = Bool()
    val rdmaAck = RdmaNonReadRespBus().setDefaultVal()
    when(!psnCheckPass) {
      rdmaAck.setAck(AckType.NAK_SEQ.id, epsn, rdmaPkt.bth.dqpn)
      commCheckPass := False
    } elsewhen (dupReq) {
      rdmaAck.setAck(AckType.NORMAL.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      commCheckPass := inputValid
    } elsewhen (!opSeqCheckRslt || !pktLenCheckRslt) {
      rdmaAck.setAck(AckType.NAK_INV.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      commCheckPass := False
    } otherwise {
      rdmaAck.setAck(AckType.NORMAL.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      commCheckPass := inputValid
    }

    val rslt = TupleBundle(rdmaPkt, rdmaAck, commCheckPass, dupReq)
    rslt
  })

  val finalStageContinue = True
  val finalStageFlush = io.recvQFlush.stateErrFlush
  val finalStage =
    StreamPipeStageSink(
      continue = finalStageContinue,
      flush = finalStageFlush,
      input = {
        val (rdmaPkt, rdmaAck, commCheckPass, dupReq) =
          asTuple(dataBuildStageOut)
        val outputPortCount = 3
        val selIdx = UInt(log2Up(outputPortCount) bits)
        when(commCheckPass) {
          when(!dupReq) {
            selIdx := 0
          } otherwise {
            selIdx := 1
          }
        } otherwise {
          selIdx := 2
        }
        val outputStream =
          StreamDemux(
            dataBuildStageOut,
            select = selIdx,
            portCount = outputPortCount
          )

        // If RNR PSN matched, RNR flush will be cleared in the next cycle,
        // therefore outputStream needs to be staged for on cycle
        io.rnrClear := io.recvQFlush.rnrFlush && commCheckPass && dataBuildStageOut.valid
        val txNormal = outputStream(0).stage()
        val txDupReq = outputStream(1).stage()
        val txErrResp = outputStream(2).stage()

        io.txDupReq <-/< txDupReq.translateWith(rdmaPkt)
        io.txErrResp <-/< txErrResp
          .throwWhen(io.recvQFlush.rnrFlush)
          .translateWith(rdmaAck)
        // If RNR sent, flush until RNR cleared
        txNormal.throwWhen(io.recvQFlush.rnrFlush)
      }
    )((inputPayload, _) => {
      val (rdmaPkt, _, _, _) =
        asTuple(inputPayload)
      rdmaPkt
    })(sink = io.tx)
}

class SendWriteReqHandler(busWidth: BusWidth) extends ReqHandler {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpFlushNotify = in(QpFlushNotifier())
    val nakNotify = out(NakNotifier())
    val recvWorkReq = slave(Stream(RecvWorkReq()))
    val workComp = master(Stream(WorkComp()))
    val addrCacheBus = master(AddrCacheReadBus())
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaNonReadRespBus()))
    val txErrResp = master(Stream(RdmaNonReadRespBus()))
    val dmaWriteReq = master(DmaWriteReqBus(busWidth))
  }

  io.nakNotify.rnrPulse := False
  io.nakNotify.rnrNakPsn := 0
  io.nakNotify.nakPulse := False

  val recvWorkReqIdReg = Reg(Bits(WR_ID_WIDTH bits))
  val writeTotalSizeReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))
  val dmaLenReg = Reg(UInt(RDMA_MAX_LEN_WIDTH bits))

  val prepareStageContinue = True
  val prepareStageFlush = io.qpFlushNotify.flush
  val prepareStageOut =
    StreamPipeStage(
      continue = prepareStageContinue,
      flush = prepareStageFlush,
      input = io.rx
    )((inputPayload, inputValid) => {
      val isFirstOrOnly =
        inputValid && OpCode.isSendWriteFirstOrOnlyReqPkt(
          inputPayload.bth.opcode
        )
      val isLastOrOnly = // True for send, False for write
        inputValid && OpCode.isSendWriteLastOrOnlyReqPkt(
          inputPayload.bth.opcode
        )
      val isSendOrWrite = inputValid && OpCode.isSendReqPkt(
        inputPayload.bth.opcode
      )
      val hasImmDt = inputValid && OpCode.hasImmDt(inputPayload.bth.opcode)
      val pktLenBytes = CountOne(inputPayload.mty)

      assert(
        assertion = OpCode.isSendReqPkt(inputPayload.bth.opcode) || OpCode
          .isWriteReqPkt(inputPayload.bth.opcode),
        message =
          L"opcode should be send or write request, but has ${inputPayload.bth.opcode}",
        severity = ERROR
      )

      val rslt =
        TupleBundle(
          inputPayload,
          pktLenBytes,
          isSendOrWrite,
          isFirstOrOnly,
          isLastOrOnly,
          hasImmDt
        )
      rslt
    })

  val addrQueryStageContinue = True
  val addrQueryStageFlush = io.qpFlushNotify.flush
  val addrQueryStageOut =
    StreamPipeStage(
      continue = addrQueryStageContinue,
      flush = addrQueryStageFlush,
      input = prepareStageOut
    )((inputPayload, inputValid) => {
      val (
        rdmaPkt,
        pktLenBytes,
        isSendOrWrite,
        isFirstOrOnly,
        isLastOrOnly,
        hasImmDt
      ) =
        asTuple(inputPayload)

      val accessKey = Bits(LRKEY_IMM_DATA_WIDTH bits)
      val accessType = Bits(ACCESS_TYPE_WIDTH bits)
      val pd = io.qpAttr.pd
      val localOrRemoteKey =
        isSendOrWrite // Local key for send, remote key for write
      val va = UInt(MEM_ADDR_WIDTH bits)
      val addrCacheReqValid = inputValid && isFirstOrOnly
      when(isSendOrWrite) {
        // Send out buffer access permission check for send
        accessKey := io.recvWorkReq.lkey
        accessType := AccessType.LOCAL_WRITE.id
        va := io.recvWorkReq.addr
      } otherwise {
        // Send out access permission check for write
        accessKey := rdmaPkt.reth.rkey
        accessType := AccessType.REMOTE_WRITE.id
        va := rdmaPkt.reth.va
      }
      io.addrCacheBus.sendAddrCacheReq(
        addrCacheReqValid,
        accessKey,
        accessType,
        pd,
        localOrRemoteKey,
        va,
        pktLenBytes
      )

      val isZeroLenPkt = pktLenBytes === 0
      val rslt =
        TupleBundle(
          rdmaPkt,
          isSendOrWrite,
          isFirstOrOnly,
          isLastOrOnly,
          hasImmDt,
          pktLenBytes,
          isZeroLenPkt
        )
      rslt
    })

  val validationStageOutContinue = True
  val validationStageOutFlush = io.qpFlushNotify.flush
  val validationStageOut = StreamPipeStage(
    continue = validationStageOutContinue,
    flush = validationStageOutFlush,
    input = {
      val (_, _, isFirstOrOnly, _, _, _, _) = asTuple(addrQueryStageOut)
      // If first or only send and write request packet, join with key validation result
      io.addrCacheBus.joinWithAddrCacheRespStream(
        addrQueryStageOut,
        joinCond = isFirstOrOnly
      )
    }
  )((inputPayload, inputValid) => {
    val (
      rdmaPkt,
      isSendOrWrite,
      isFirstOrOnly,
      isLastOrOnly,
      hasImmDt,
      pktLenBytes,
      isZeroLenPkt
    ) =
      asTuple(inputPayload._1)
    val keyValid =
      inputPayload._2.keyValid || isZeroLenPkt // Zero length packets ignore key validation
    val sizeValid = inputPayload._2.sizeValid || isZeroLenPkt
    val pa = inputPayload._2.pa

    val targetPhysicalAddr = pa
    val sendRnrErr =
      inputValid && isSendOrWrite && isFirstOrOnly && !io.recvWorkReq.valid
    val writeImmRnrErr =
      inputValid && !isSendOrWrite && hasImmDt && !io.recvWorkReq.valid

    // Check receive buffer space for send
    when(inputValid && isSendOrWrite) {
      when(isFirstOrOnly && io.recvWorkReq.valid) {
        io.recvWorkReq.ready := True
        recvWorkReqIdReg := io.recvWorkReq.id
      }
    }

    // Check receive buffer space for write
    when(inputValid && !isSendOrWrite) {
      when(isFirstOrOnly) {
        writeTotalSizeReg := pktLenBytes
        dmaLenReg := rdmaPkt.reth.dlen
      } otherwise {
        writeTotalSizeReg := writeTotalSizeReg + pktLenBytes
      }
    }
    // Check receive buffer available for write imm
    when(inputValid && !isSendOrWrite && hasImmDt) {
      when(io.recvWorkReq.valid) {
        recvWorkReqIdReg := io.recvWorkReq.id
        io.recvWorkReq.ready := True
      }
    }

    val rslt = TupleBundle(
      rdmaPkt,
      isSendOrWrite,
      isFirstOrOnly,
      isLastOrOnly,
      hasImmDt,
      pktLenBytes,
      isZeroLenPkt,
      keyValid,
      sizeValid,
      sendRnrErr,
      writeImmRnrErr,
      targetPhysicalAddr
    )
    rslt
  })

  val finishCheckStageContinue = True
  val finishCheckStageFlush = io.qpFlushNotify.flush
  val finishCheckStageOut = StreamPipeStage(
    continue = finishCheckStageContinue,
    flush = finishCheckStageFlush,
    input = validationStageOut
  )((inputPayload, inputValid) => {
    val (
      rdmaPkt,
      isSendOrWrite,
      isFirstOrOnly,
      isLastOrOnly,
      hasImmDt,
      pktLenBytes,
      isZeroLenPkt,
      keyValid,
      sizeValid,
      sendRnrErr,
      writeImmRnrErr,
      targetPhysicalAddr
    ) = asTuple(inputPayload)

    val writeTotalSize = writeTotalSizeReg
    val dmaLen = dmaLenReg
    val writeTotalSizeErr =
      inputValid && !isSendOrWrite && isLastOrOnly && (writeTotalSize =/= dmaLen)
    val rnrErr = inputValid && isFirstOrOnly && (sendRnrErr || writeImmRnrErr)
    val bufLenErr = inputValid && !sizeValid
    val keyErr = inputValid && isFirstOrOnly && !keyValid
    val checkPass =
      inputValid && !keyErr && !rnrErr && !bufLenErr && !writeTotalSizeErr

    val rdmaAck = RdmaNonReadRespBus().setDefaultVal()
    when(inputValid) {
      when(rnrErr) {
        rdmaAck
          .setAck(AckType.NAK_RNR.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      } elsewhen (bufLenErr || writeTotalSizeErr) {
        rdmaAck
          .setAck(AckType.NAK_INV.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      } elsewhen (isFirstOrOnly && !keyValid) {
        rdmaAck
          .setAck(AckType.NAK_RMT_ACC.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      } otherwise {
        rdmaAck
          .setAck(AckType.NORMAL.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      }
    }

    val recvWorkReqId = recvWorkReqIdReg
    val rslt = TupleBundle(
      rdmaPkt,
      rdmaAck,
      isSendOrWrite,
      isFirstOrOnly,
      isLastOrOnly,
      hasImmDt,
      pktLenBytes,
      isZeroLenPkt,
      targetPhysicalAddr,
      recvWorkReqId,
      keyErr,
      rnrErr,
      bufLenErr,
      writeTotalSizeErr,
      checkPass
    )
    rslt
  })

  val faultZoneStageOutContinue = True
  val faultZoneStageOutFlush = io.qpFlushNotify.flush
  val faultZoneOutputStage =
    StreamPipeStageSink(
      continue = faultZoneStageOutContinue,
      flush = faultZoneStageOutFlush,
      input = {
        val (_, _, _, _, _, _, _, _, _, _, _, _, _, _, checkPass) =
          asTuple(finishCheckStageOut)
        val twoStream =
          StreamDemux(
            finishCheckStageOut,
            select = checkPass.asUInt,
            portCount = 2
          )

        val txErrResp = twoStream(0)
        val txNormalResp = twoStream(1)

        val (txErrResp1, txErrResp2) = StreamFork2(txErrResp)
        val (
          _,
          rdmaAck,
          _,
          _,
          _,
          _,
          _,
          _,
          _,
          _,
          keyErr,
          rnrErr,
          bufLenErr,
          writeTotalSizeErr,
          _
        ) = asTuple(txErrResp1)
        io.txErrResp <-/< txErrResp1.translateWith(rdmaAck)
        when(txErrResp1.fire) {
          // Send NAK pulse, flush RQ in the next cycle
          io.nakNotify.rnrPulse := rnrErr
          io.nakNotify.rnrNakPsn := rdmaAck.bth.psn
          io.nakNotify.nakPulse := keyErr || bufLenErr || writeTotalSizeErr
        }

        io.workComp <-/< txErrResp2
          .translateWith {
            // TODO: build error WC
            WorkComp().setDefaultVal()
          }

        val (txNormalResp1, txNormalResp2) = StreamFork2(txNormalResp)
        io.tx <-/< txNormalResp1.translateWith(rdmaAck)
        txNormalResp2
      }
    )((inputPayload, inputValid) => {
      val (
        rdmaPkt,
        _,
        _,
        _,
        isLastOrOnly,
        hasImmDt,
        _,
        _,
        targetPhysicalAddr,
        recvWorkReqId,
        _,
        _,
        _,
        _,
        _
      ) =
        asTuple(inputPayload)

      val rslt = Fragment(DmaWriteReq(busWidth: BusWidth))
      rslt.qpn := io.qpAttr.dqpn
      rslt.psn := rdmaPkt.bth.psn
      rslt.wrId := recvWorkReqId
      rslt.wrIdValid := inputValid && (hasImmDt || isLastOrOnly)
      rslt.addr := targetPhysicalAddr
      rslt.mty := rdmaPkt.mty
      rslt.data := rdmaPkt.data
      rslt.last := isLastOrOnly
      rslt
    })(sink = io.dmaWriteReq.req)
}

class ReadAtomicReqHandler(busWidth: BusWidth) extends ReqHandler {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val qpFlushNotify = in(QpFlushNotifier())
    val nakNotify = out(NakNotifier())
    val addrCacheBus = master(AddrCacheReadBus())
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
    val txErrResp = master(Stream(RdmaNonReadRespBus()))
    val dmaRead = master(DmaReadBus(busWidth))
    val dmaWriteReq = master(DmaWriteReqBus(busWidth))
  }

  io.nakNotify.rnrPulse := False
  io.nakNotify.rnrNakPsn := 0
  io.nakNotify.nakPulse := False

  val targetPhysicalAddrReg = Reg(UInt(MEM_ADDR_WIDTH bits))

  val prepareStageContinue = True
  val prepareStageFlush = io.qpFlushNotify.stateErrFlush
  val prepareStageOut =
    StreamPipeStage(
      continue = prepareStageContinue,
      flush = prepareStageFlush,
      input = io.rx
    )((inputPayload, inputValid) => {
      val isReadOrAtomic = inputValid && OpCode.isSendReqPkt(
        inputPayload.bth.opcode
      )

      assert(
        assertion = OpCode.isReadReqPkt(inputPayload.bth.opcode) || OpCode
          .isAtomicReqPkt(inputPayload.bth.opcode),
        message =
          L"opcode should be read or atomic request, but has ${inputPayload.bth.opcode}",
        severity = ERROR
      )

      val rslt = TupleBundle(inputPayload, isReadOrAtomic)
      rslt
    })

  val addrQueryStageContinue = True
  val addrQueryStageFlush = io.qpFlushNotify.stateErrFlush
  val addrQueryStageOut =
    StreamPipeStage(
      continue = addrQueryStageContinue,
      flush = addrQueryStageFlush,
      input = prepareStageOut
    )((inputPayload, inputValid) => {
      val (rdmaPkt, isReadOrAtomic) = asTuple(inputPayload)

      val accessKey = Bits(LRKEY_IMM_DATA_WIDTH bits)
      val accessType = Bits(ACCESS_TYPE_WIDTH bits)
      val pd = io.qpAttr.pd
      val localOrRemoteKey = False // Local key for True, remote key for False
      val va = UInt(MEM_ADDR_WIDTH bits)
      val dataSize = UInt(RDMA_MAX_LEN_WIDTH bits)
      val addrCacheReqValid = inputValid
      when(isReadOrAtomic) {
        // Send out buffer access permission check for read
        accessKey := rdmaPkt.reth.rkey
        accessType := AccessType.REMOTE_READ.id
        va := rdmaPkt.reth.va
        dataSize := rdmaPkt.reth.dlen
      } otherwise {
        // Send out access permission check for atomic
        accessKey := rdmaPkt.atomicETH.rkey
        accessType := AccessType.REMOTE_ATOMIC.id
        va := rdmaPkt.atomicETH.va
        dataSize := 4
      }
      io.addrCacheBus.sendAddrCacheReq(
        addrCacheReqValid,
        accessKey,
        accessType,
        pd,
        localOrRemoteKey,
        va,
        dataSize
      )

      val isZeroLenPkt = inputValid && isReadOrAtomic && rdmaPkt.reth.dlen === 0
      val rslt = TupleBundle(rdmaPkt, isReadOrAtomic, isZeroLenPkt)
      rslt
    })

  val validationStageOutContinue = True
  val validationStageOutFlush = io.qpFlushNotify.stateErrFlush
  val validationStageOut = StreamPipeStage(
    continue = validationStageOutContinue,
    flush = validationStageOutFlush,
    // Join with key validation result
    input = io.addrCacheBus.joinWithAddrCacheRespStream(addrQueryStageOut)
  )((inputPayload, inputValid) => {
    val (rdmaPkt, isReadOrAtomic, isZeroLenPkt) = asTuple(inputPayload._1)
    val keyValid =
      inputValid && (inputPayload._2.keyValid || isZeroLenPkt) // Zero length packets ignore key validation
    val sizeValid = inputValid && (inputPayload._2.sizeValid || isZeroLenPkt)
    val pa = inputPayload._2.pa

    val targetPhysicalAddr = pa
    val rslt = TupleBundle(
      rdmaPkt,
      isReadOrAtomic,
      isZeroLenPkt,
      keyValid,
      sizeValid,
      targetPhysicalAddr
    )
    rslt
  })

  val finishCheckStageContinue = True
  val finishCheckStageFlush = io.qpFlushNotify.stateErrFlush
  val finishCheckStageOut = StreamPipeStage(
    continue = finishCheckStageContinue,
    flush = finishCheckStageFlush,
    input = validationStageOut
  )((inputPayload, inputValid) => {
    val (
      rdmaPkt,
      isReadOrAtomic,
      isZeroLenPkt,
      keyValid,
      sizeValid,
      targetPhysicalAddr
    ) =
      asTuple(inputPayload)

    val bufLenErr = inputValid && !sizeValid
    val keyErr = inputValid && !keyValid
    val checkPass = inputValid && !keyErr && !bufLenErr

    val rdmaNak = RdmaNonReadRespBus().setDefaultVal()
    when(inputValid) {
      when(bufLenErr) {
        rdmaNak
          .setAck(AckType.NAK_INV.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      } elsewhen (keyErr) {
        rdmaNak
          .setAck(AckType.NAK_RMT_ACC.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      } otherwise { // Just to avoid latch
        rdmaNak
          .setAck(AckType.NORMAL.id, rdmaPkt.bth.psn, rdmaPkt.bth.dqpn)
      }
    }

    val rslt = TupleBundle(
      rdmaPkt,
      rdmaNak,
      isReadOrAtomic,
      isZeroLenPkt,
      targetPhysicalAddr,
      keyErr,
      bufLenErr,
      checkPass
    )
    rslt
  })

  val faultZoneStageOutContinue = True
  val faultZoneStageOutFlush = io.qpFlushNotify.stateErrFlush
  val faultZoneOutputStage =
    StreamPipeStageSink(
      continue = faultZoneStageOutContinue,
      flush = faultZoneStageOutFlush,
      input = {
        val (_, rdmaNak, _, _, _, keyErr, bufLenErr, checkPass) =
          asTuple(finishCheckStageOut)
        val twoStream =
          StreamDemux(
            finishCheckStageOut,
            select = checkPass.asUInt,
            portCount = 2
          )

        val txErrResp = twoStream(0)
        val txNormal = twoStream(1)

        io.txErrResp <-/< txErrResp.translateWith(rdmaNak)
        when(txErrResp.fire) {
          // Send NAK pulse, flush RQ in the next cycle
          io.nakNotify.rnrPulse := False
          io.nakNotify.rnrNakPsn := rdmaNak.bth.psn
          io.nakNotify.nakPulse := keyErr || bufLenErr
        }

        txNormal
      }
    )((inputPayload, _) => {
      val (rdmaPkt, _, _, _, targetPhysicalAddr, _, _, _) =
        asTuple(inputPayload)

      val rslt = DmaReadReq()
      rslt.qpn := io.qpAttr.dqpn
      rslt.psn := rdmaPkt.bth.psn
      rslt.addr := targetPhysicalAddr
      rslt.len := rdmaPkt.reth.dlen
      rslt
    })(sink = io.dmaRead.req)

//  val newRespReg = RegInit(True)
//  val readRespStageContinue = True
//  val readRespStageFlush = False
//  val readRespStageOut = StreamPipeStageSink(
//    continue = readRespStageContinue,
//    flush = readRespStageFlush,
//    input = io.dmaRead.resp
//  )((inputPayload, inputValid) => {})(sink = io.tx)
}
