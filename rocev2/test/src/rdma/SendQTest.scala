package rdma

import spinal.core._
import spinal.core.sim._

import org.scalatest.AppendedClues._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import scala.collection.mutable

import ConstantSettings._
import OpCodeSim._
import PsnSim._
import RdmaTypeReDef._
import StreamSimUtil._
import WorkReqSim._

class WorkReqValidatorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile {
      val dut = new WorkReqValidator
      dut.workReqValidator.errorStream.valid.simPublic()
      dut.workReqValidator.errorStream.ready.simPublic()
      dut.workReqValidator.addrCacheReadResp.valid.simPublic()
      dut.workReqValidator.addrCacheReadResp.ready.simPublic()
      dut
    }

  test("WorkReqValidator input normal case") {
    testFunc(normalOrErrorCase = true, addrCacheQueryErrOrFlushErr = true)
  }

  test("WorkReqValidator AddrCache query response error case") {
    testFunc(normalOrErrorCase = false, addrCacheQueryErrOrFlushErr = true)
  }

  test("WorkReqValidator error state flush case") {
    testFunc(normalOrErrorCase = false, addrCacheQueryErrOrFlushErr = false)
  }

  def testFunc(
      normalOrErrorCase: Boolean,
      addrCacheQueryErrOrFlushErr: Boolean
  ) = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.qpAttr.pmtu #= pmtuLen.id
    dut.io.txQCtrl.fenceOrRetry #= false
    dut.io.txQCtrl.wrongStateFlush #= !normalOrErrorCase && !addrCacheQueryErrOrFlushErr

    val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
      SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)
    val input4WorkReqCacheQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val outputWorkReqCacheQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val inputWorkCompErrQueue = mutable.Queue[
      (
          SpinalEnumElement[WorkCompOpCode.type],
          SpinalEnumElement[WorkCompStatus.type],
          PktLen,
          WorkReqId
      )
    ]()
    val outputWorkCompErrQueue = mutable.Queue[
      (
          SpinalEnumElement[WorkCompOpCode.type],
          SpinalEnumElement[WorkCompStatus.type],
          PktLen,
          WorkReqId
      )
    ]()
    val input4SqOutPsnRangeQueue =
      mutable.Queue[(SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)]()
    val outputSqOutPsnRangeQueue =
      mutable.Queue[(SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)]()
    val inputPktNumQueue = mutable.Queue[PktNum]()
    val outputPktNumQueue = mutable.Queue[PktNum]()

    streamMasterDriverAlwaysValid(dut.io.workReq, dut.clockDomain) {
      val _ = totalFragNumItr.next()
      val _ = pktNumItr.next()
      val pktLen = totalLenItr.next()
      val psnStart = psnStartItr.next()
      dut.io.qpAttr.npsn #= psnStart
      val pktNum = MiscUtils.computePktNum(pktLen.toLong, pmtuLen)
      val psnEnd = psnStart +% (pktNum - 1)

      val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
      dut.io.workReq.opcode #= workReqOpCode
      dut.io.workReq.lenBytes #= pktLen
      val noFlags = 0
      dut.io.workReq.flags #= noFlags

      input4SqOutPsnRangeQueue.enqueue(
        (
          workReqOpCode,
          psnStart,
          psnEnd
        )
      )
      input4WorkReqCacheQueue.enqueue(
        (
          workReqOpCode,
          psnStart,
          pktLen.toLong,
          dut.io.workReq.id.toBigInt
        )
      )
      inputPktNumQueue.enqueue(pktNum)
    }
    onStreamFire(dut.io.workReq, dut.clockDomain) {
      if (!normalOrErrorCase) { // Error case
        val workCompStatus = if (addrCacheQueryErrOrFlushErr) {
          // AddrCache query response error
          WorkCompStatus.LOC_LEN_ERR
        } else { // Flush error
          WorkCompStatus.WR_FLUSH_ERR
        }
        inputWorkCompErrQueue.enqueue(
          (
            WorkCompSim.setOpCodeFromSqWorkReqOpCode(
              dut.io.workReq.opcode.toEnum
            ),
            workCompStatus,
            dut.io.workReq.lenBytes.toLong,
            dut.io.workReq.id.toBigInt
          )
        )
      }
    }

    if (normalOrErrorCase) {
      AddrCacheSim.alwaysStreamFireAndRespSuccess(
        dut.io.addrCacheRead,
        dut.clockDomain
      )

      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.workCompErr.valid.toBoolean
      )
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.errNotifier.pulse.toBoolean
      )

      streamSlaveAlwaysReady(dut.io.workReqToCache, dut.clockDomain)
      onStreamFire(dut.io.workReqToCache, dut.clockDomain) {
        outputWorkReqCacheQueue.enqueue(
          (
            dut.io.workReqToCache.workReq.opcode.toEnum,
            dut.io.workReqToCache.psnStart.toInt,
            dut.io.workReqToCache.workReq.lenBytes.toLong,
            dut.io.workReqToCache.workReq.id.toBigInt
          )
        )
      }
      streamSlaveAlwaysReady(dut.io.sqOutPsnRangeFifoPush, dut.clockDomain)
      onStreamFire(dut.io.sqOutPsnRangeFifoPush, dut.clockDomain) {
        outputSqOutPsnRangeQueue.enqueue(
          (
            dut.io.sqOutPsnRangeFifoPush.workReqOpCode.toEnum,
            dut.io.sqOutPsnRangeFifoPush.start.toInt,
            dut.io.sqOutPsnRangeFifoPush.end.toInt
          )
        )
      }
      fork {
        while (true) {
          dut.clockDomain.waitSampling()
          if (dut.io.npsnInc.inc.toBoolean) {
            outputPktNumQueue.enqueue(dut.io.npsnInc.incVal.toInt)
          }
        }
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        input4SqOutPsnRangeQueue,
        outputSqOutPsnRangeQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        input4WorkReqCacheQueue,
        outputWorkReqCacheQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputPktNumQueue,
        outputPktNumQueue,
        MATCH_CNT
      )
    } else {
      val _ = if (addrCacheQueryErrOrFlushErr) { // addrCacheRespQueue
        // AddrCache query response error
        AddrCacheSim.alwaysStreamFireAndRespFailure(
          dut.io.addrCacheRead,
          dut.clockDomain
        )
      } else {
        // Error flush
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.workReqValidator.addrCacheReadResp.ready.toBoolean
        )
      }

      dut.io.errNotifier.pulse.toBoolean shouldBe
        (dut.workReqValidator.errorStream.valid.toBoolean && dut.workReqValidator.errorStream.ready.toBoolean) withClue (
          f"${simTime()} time: dut.io.errNotifier.pulse=${dut.io.errNotifier.pulse.toBoolean} should == (dut.workReqValidator.errorStream.valid=${dut.workReqValidator.errorStream.valid.toBoolean} && dut.workReqValidator.errorStream.ready=${dut.workReqValidator.errorStream.ready.toBoolean})"
        )

      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.workReqToCache.valid.toBoolean
      )
      if (!addrCacheQueryErrOrFlushErr) { // Error flush
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.addrCacheRead.req.valid.toBoolean
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.sqOutPsnRangeFifoPush.valid.toBoolean
        )
      }

      streamSlaveAlwaysReady(dut.io.workCompErr, dut.clockDomain)
      onStreamFire(dut.io.workCompErr, dut.clockDomain) {
        outputWorkCompErrQueue.enqueue(
          (
            dut.io.workCompErr.opcode.toEnum,
            dut.io.workCompErr.status.toEnum,
            dut.io.workCompErr.lenBytes.toLong,
            dut.io.workCompErr.id.toBigInt
          )
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputWorkCompErrQueue,
        outputWorkCompErrQueue,
        MATCH_CNT
      )
    }
  }
}

class WorkReqCachePushAndReadAtomicHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new WorkReqCachePushAndReadAtomicHandler)

  def testFunc() = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.txQCtrl.wrongStateFlush #= false
    dut.io.txQCtrl.fenceOrRetry #= false

    val inputWorkReqQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val inputWorkReq4CachePushQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val inputWorkReq4DmaReadQueue =
      mutable.Queue[(PsnStart, PktLen, PhysicalAddr)]()
    val inputWorkReq4ReadReqQueue =
      mutable.Queue[(OpCode.Value, PSN, PktLen, VirtualAddr, LRKey)]()
    val inputWorkReq4AtomicReqQueue = mutable
      .Queue[(OpCode.Value, PSN, VirtualAddr, LRKey, AtomicComp, AtomicSwap)]()
    val outputWorkReqCachePushQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val outputCachedWorkReqOutQueue = mutable.Queue[
      (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PktLen, WorkReqId)
    ]()
    val outputDmaReadQueue = mutable.Queue[(PsnStart, PktLen, PhysicalAddr)]()
    val outputReadReqQueue =
      mutable.Queue[(OpCode.Value, PSN, PktLen, VirtualAddr, LRKey)]()
    val outputAtomicReqQueue = mutable
      .Queue[(OpCode.Value, PSN, VirtualAddr, LRKey, AtomicComp, AtomicSwap)]()

    streamMasterDriverAlwaysValid(dut.io.workReqToCache, dut.clockDomain) {
      val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
      dut.io.workReqToCache.workReq.opcode #= workReqOpCode
      val noFlags = 0
      dut.io.workReqToCache.workReq.flags #= noFlags
    }
    onStreamFire(dut.io.workReqToCache, dut.clockDomain) {
      val workReqOpCode = dut.io.workReqToCache.workReq.opcode.toEnum
      val psnStart = dut.io.workReqToCache.psnStart.toInt
      val workReqLen = dut.io.workReqToCache.workReq.lenBytes.toLong
      val workReqId = dut.io.workReqToCache.workReq.id.toBigInt
      inputWorkReqQueue.enqueue(
        (
          workReqOpCode,
          psnStart,
          workReqLen,
          workReqId
        )
      )
      inputWorkReq4CachePushQueue.enqueue(
        (
          workReqOpCode,
          psnStart,
          workReqLen,
          workReqId
        )
      )

      if (workReqOpCode.isReadReq()) {
        inputWorkReq4ReadReqQueue.enqueue(
          (
            OpCode.RDMA_READ_REQUEST,
            psnStart,
            workReqLen,
            dut.io.workReqToCache.workReq.raddr.toBigInt,
            dut.io.workReqToCache.workReq.rkey.toLong
          )
        )
      } else if (workReqOpCode.isAtomicReq()) {
        val atomicReqOpCode =
          WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx = 0, pktNum = 1)
        inputWorkReq4AtomicReqQueue.enqueue(
          (
            atomicReqOpCode,
            psnStart,
            dut.io.workReqToCache.workReq.raddr.toBigInt,
            dut.io.workReqToCache.workReq.rkey.toLong,
            dut.io.workReqToCache.workReq.comp.toBigInt,
            dut.io.workReqToCache.workReq.swap.toBigInt
          )
        )
      } else {
        inputWorkReq4DmaReadQueue.enqueue(
          (
            psnStart,
            workReqLen,
            dut.io.workReqToCache.pa.toBigInt
          )
        )
      }
    }

    streamSlaveAlwaysReady(dut.io.workReqCachePush, dut.clockDomain)
    onStreamFire(dut.io.workReqCachePush, dut.clockDomain) {
      outputWorkReqCachePushQueue.enqueue(
        (
          dut.io.workReqCachePush.workReq.opcode.toEnum,
          dut.io.workReqCachePush.psnStart.toInt,
          dut.io.workReqCachePush.workReq.lenBytes.toLong,
          dut.io.workReqCachePush.workReq.id.toBigInt
        )
      )
    }
    streamSlaveAlwaysReady(dut.io.cachedWorkReqOut, dut.clockDomain)
    onStreamFire(dut.io.cachedWorkReqOut, dut.clockDomain) {
      outputCachedWorkReqOutQueue.enqueue(
        (
          dut.io.cachedWorkReqOut.workReq.opcode.toEnum,
          dut.io.cachedWorkReqOut.psnStart.toInt,
          dut.io.cachedWorkReqOut.workReq.lenBytes.toLong,
          dut.io.cachedWorkReqOut.workReq.id.toBigInt
        )
      )
    }
    streamSlaveAlwaysReady(dut.io.dmaRead.req, dut.clockDomain)
    onStreamFire(dut.io.dmaRead.req, dut.clockDomain) {
      outputDmaReadQueue.enqueue(
        (
          dut.io.dmaRead.req.psnStart.toInt,
          dut.io.dmaRead.req.lenBytes.toLong,
          dut.io.dmaRead.req.pa.toBigInt
        )
      )
    }
    streamSlaveAlwaysReady(dut.io.txReadReq, dut.clockDomain)
    onStreamFire(dut.io.txReadReq, dut.clockDomain) {
      outputReadReqQueue.enqueue(
        (
          OpCode(dut.io.txReadReq.bth.opcodeFull.toInt),
          dut.io.txReadReq.bth.psn.toInt,
          dut.io.txReadReq.reth.dlen.toLong,
          dut.io.txReadReq.reth.va.toBigInt,
          dut.io.txReadReq.reth.rkey.toLong
        )
      )
    }
    streamSlaveAlwaysReady(dut.io.txAtomicReq, dut.clockDomain)
    onStreamFire(dut.io.txAtomicReq, dut.clockDomain) {
      outputAtomicReqQueue.enqueue(
        (
          OpCode(dut.io.txAtomicReq.bth.opcodeFull.toInt),
          dut.io.txAtomicReq.bth.psn.toInt,
          dut.io.txAtomicReq.atomicEth.va.toBigInt,
          dut.io.txAtomicReq.atomicEth.rkey.toLong,
          dut.io.txAtomicReq.atomicEth.comp.toBigInt,
          dut.io.txAtomicReq.atomicEth.swap.toBigInt
        )
      )
    }

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputWorkReq4CachePushQueue,
      outputWorkReqCachePushQueue,
      MATCH_CNT
    )
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputWorkReqQueue,
      outputCachedWorkReqOutQueue,
      MATCH_CNT
    )
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputWorkReq4DmaReadQueue,
      outputDmaReadQueue,
      MATCH_CNT
    )
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputWorkReq4ReadReqQueue,
      outputReadReqQueue,
      MATCH_CNT
    )
    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputWorkReq4AtomicReqQueue,
      outputAtomicReqQueue,
      MATCH_CNT
    )
  }

  test("WorkReqCachePushAndReadAtomicHandler input normal case") {
    testFunc()
  }
}

class SqOutTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U512
  val maxFragNum = 37

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile(new SqOut(busWidth))

  def insertToOutPsnRangeQueue(
      payloadFragNumItr: PayloadFragNumItr,
      pktNumItr: PktNumItr,
      psnStartItr: PsnStartItr,
      payloadLenItr: PayloadLenItr,
      inputSendReqMetaDataQueue: mutable.Queue[(PSN, OpCode.Value, FragLast)],
      inputWriteReqMetaDataQueue: mutable.Queue[(PSN, OpCode.Value, FragLast)],
      inputReadReqMetaDataQueue: mutable.Queue[(PSN, OpCode.Value)],
      inputAtomicReqMetaDataQueue: mutable.Queue[(PSN, OpCode.Value)],
      outPsnRangeQueue: mutable.Queue[
        (SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)
      ],
      inputOutPsnQueue: mutable.Queue[PSN]
  ) =
    for (_ <- 0 until PENDING_REQ_NUM) {
      val _ = payloadFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()
      val workReqOpCode = WorkReqSim.randomSendWriteReadAtomicOpCode()
      val psnEnd = psnStart +% (pktNum - 1)

      val isReadReq = workReqOpCode.isReadReq()
      val isAtomicReq = workReqOpCode.isAtomicReq()
      val isSendReq = workReqOpCode.isSendReq()
      val isWriteReq = workReqOpCode.isWriteReq()

      assert(
        isSendReq || isWriteReq || isReadReq || isAtomicReq,
        f"${simTime()} time: invalid WR opcode=${workReqOpCode}, must be send/write/read/atomic"
      )

      outPsnRangeQueue.enqueue((workReqOpCode, psnStart, psnEnd))

      if (isSendReq || isWriteReq) {
        for (pktIdx <- 0 until pktNum) {
          val psn = psnStart +% pktIdx
          inputOutPsnQueue.enqueue(psn)
          val sendWriteReqOpCode =
            WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx, pktNum)
          val pktFragNum = RdmaDataPktSim.computePktFragNum(
            pmtuLen,
            busWidth,
            sendWriteReqOpCode,
            payloadLenBytes.toLong,
            pktIdx,
            pktNum
          )
//          println(
//            f"${simTime()} time: psn=${psn}%X, opcode=${sendWriteReqOpCode}, workReqOpCode=${workReqOpCode}, psnStart=${psnStart}%X, psnEnd=${psnEnd}%X, pktNum=${pktNum}, isLastFrag=${isLastFrag}"
//          )
          for (fragIdx <- 0 until pktFragNum) {
            val isLastFrag = fragIdx == pktFragNum - 1
            if (isSendReq) {
              inputSendReqMetaDataQueue.enqueue(
                (
                  psn,
                  sendWriteReqOpCode,
                  isLastFrag
                )
              )
            } else {
              inputWriteReqMetaDataQueue.enqueue(
                (
                  psn,
                  sendWriteReqOpCode,
                  isLastFrag
                )
              )
            }
          }
        }
      } else if (isAtomicReq) {
        inputOutPsnQueue.enqueue(psnEnd)
        val atomicReqOpCode =
          WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx = 0, pktNum = 1)
        inputAtomicReqMetaDataQueue.enqueue(
          (
            psnEnd,
            atomicReqOpCode
          )
        )
      } else {
        inputOutPsnQueue.enqueue(psnEnd)
        inputReadReqMetaDataQueue.enqueue(
          (
            psnEnd,
            OpCode.RDMA_READ_REQUEST
          )
        )
      }
    }

  def testFunc(normalOrDupReq: Boolean): Unit =
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // Input to DUT
      val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val inputSendReqMetaDataQueue =
        mutable.Queue[(PSN, OpCode.Value, FragLast)]()
      val inputWriteReqMetaDataQueue =
        mutable.Queue[(PSN, OpCode.Value, FragLast)]()
      val inputReadReqMetaDataQueue =
        mutable.Queue[(PSN, OpCode.Value)]()
      val inputAtomicReqMetaDataQueue = mutable.Queue[(PSN, OpCode.Value)]()
      val outPsnRangeQueue = mutable
        .Queue[(SpinalEnumElement[WorkReqOpCode.type], PsnStart, PsnEnd)]()

      val inputOutPsnQueue = mutable.Queue[PSN]()
      val outputOutPsnQueue = mutable.Queue[PSN]()

      val inputSendReqQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val inputWriteReqQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val inputReadReqQueue =
        mutable.Queue[(PSN, OpCode.Value, VirtualAddr, LRKey, PktLen)]()
      val inputAtomicReqQueue =
        mutable.Queue[
          (PSN, OpCode.Value, VirtualAddr, LRKey, AtomicSwap, AtomicComp)
        ]()

      val outputSendReqQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputWriteReqQueue = mutable
        .Queue[(PSN, OpCode.Value, PktFragData, FragLast)]()
      val outputReadReqQueue =
        mutable.Queue[(PSN, OpCode.Value, VirtualAddr, LRKey, PktLen)]()
      val outputAtomicReqQueue =
        mutable.Queue[
          (PSN, OpCode.Value, VirtualAddr, LRKey, AtomicSwap, AtomicComp)
        ]()

      dut.io.qpAttr.pmtu #= pmtuLen.id
      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.retry #= !normalOrDupReq

      // Disable normal requests or duplicate requests
      if (normalOrDupReq) { // Normal requests
        dut.io.rxSendReqRetry.pktFrag.valid #= false
        dut.io.rxWriteReqRetry.pktFrag.valid #= false
        dut.io.rxReadReqRetry.valid #= false
        dut.io.rxAtomicReqRetry.valid #= false
      } else { // Duplicate requests
        dut.io.outPsnRangeFifoPush.valid #= false

        dut.io.rxSendReq.pktFrag.valid #= false
        dut.io.rxWriteReq.pktFrag.valid #= false
        dut.io.rxReadReq.valid #= false
        dut.io.rxAtomicReq.valid #= false
      }

      insertToOutPsnRangeQueue(
        payloadFragNumItr,
        pktNumItr,
        psnStartItr,
        payloadLenItr,
        inputSendReqMetaDataQueue,
        inputWriteReqMetaDataQueue,
        inputReadReqMetaDataQueue,
        inputAtomicReqMetaDataQueue,
        outPsnRangeQueue,
        inputOutPsnQueue
      )
      if (normalOrDupReq) {
        // io.outPsnRangeFifoPush must always be valid when output normal responses
        streamMasterDriverAlwaysValid(
          dut.io.outPsnRangeFifoPush,
          dut.clockDomain
        ) {
          val (workReqOpCode, psnStart, psnEnd) = outPsnRangeQueue.dequeue()
          dut.io.outPsnRangeFifoPush.workReqOpCode #= workReqOpCode
          dut.io.outPsnRangeFifoPush.start #= psnStart
          dut.io.outPsnRangeFifoPush.end #= psnEnd
          dut.io.qpAttr.npsn #= psnEnd
        }
      }
      fork {
        while (true) {
          waitUntil(
            inputSendReqMetaDataQueue.isEmpty || inputWriteReqMetaDataQueue.isEmpty ||
              inputReadReqMetaDataQueue.isEmpty || inputAtomicReqMetaDataQueue.isEmpty ||
              outPsnRangeQueue.isEmpty || inputOutPsnQueue.isEmpty
          )

          insertToOutPsnRangeQueue(
            payloadFragNumItr,
            pktNumItr,
            psnStartItr,
            payloadLenItr,
            inputSendReqMetaDataQueue,
            inputWriteReqMetaDataQueue,
            inputReadReqMetaDataQueue,
            inputAtomicReqMetaDataQueue,
            outPsnRangeQueue,
            inputOutPsnQueue
          )
        }
      }

      // Either send or write requests
      val sendReqIn = if (normalOrDupReq) {
        dut.io.rxSendReq
      } else {
        dut.io.rxSendReqRetry
      }
      val writeReqIn = if (normalOrDupReq) {
        dut.io.rxWriteReq
      } else {
        dut.io.rxWriteReqRetry
      }
      val sendWriteReqIn = Seq(
        (sendReqIn, inputSendReqMetaDataQueue, inputSendReqQueue),
        (writeReqIn, inputWriteReqMetaDataQueue, inputWriteReqQueue)
      )
      for ((reqIn, inputMetaDataQueue, inputReqQueue) <- sendWriteReqIn) {
        streamMasterDriver(
          reqIn.pktFrag,
          dut.clockDomain
        ) {
          val (psnEnd, opcode, isLastFrag) = inputMetaDataQueue.dequeue()
          reqIn.pktFrag.bth.psn #= psnEnd
          reqIn.pktFrag.bth.opcodeFull #= opcode.id
          reqIn.pktFrag.last #= isLastFrag
        }
        onStreamFire(reqIn.pktFrag, dut.clockDomain) {
          val psn = reqIn.pktFrag.bth.psn.toInt
          val opcode = OpCode(reqIn.pktFrag.bth.opcodeFull.toInt)
          inputReqQueue.enqueue(
            (
              psn,
              opcode,
              reqIn.pktFrag.data.toBigInt,
              reqIn.pktFrag.last.toBoolean
            )
          )
//        println(
//          f"${simTime()} time: sendWriteReqOrErrRespIn has opcode=${opcode}, PSN=${psn}, when normalOrDupReq=${normalOrDupReq} and hasErrResp=${hasErrResp}"
//        )
        }
      }
      val readReqIn = if (normalOrDupReq) {
        dut.io.rxReadReq
      } else {
        dut.io.rxReadReqRetry
      }
      streamMasterDriver(readReqIn, dut.clockDomain) {
        val (psnEnd, opcode) = inputReadReqMetaDataQueue.dequeue()
        readReqIn.bth.psn #= psnEnd
        readReqIn.bth.opcodeFull #= opcode.id
      }
      onStreamFire(readReqIn, dut.clockDomain) {
        val psn = readReqIn.bth.psn.toInt
        val opcode = OpCode(readReqIn.bth.opcodeFull.toInt)
        inputReadReqQueue.enqueue(
          (
            psn,
            opcode,
            readReqIn.reth.va.toBigInt,
            readReqIn.reth.rkey.toLong,
            readReqIn.reth.dlen.toLong
          )
        )
//        println(
//          f"${simTime()} time: readRespIn has opcode=${opcode}, PSN=${psn}, when normalOrDupReq=${normalOrDupReq}"
//        )
      }

      val atomicReqIn = if (normalOrDupReq) {
        dut.io.rxAtomicReq
      } else {
        dut.io.rxAtomicReqRetry
      }
      streamMasterDriver(atomicReqIn, dut.clockDomain) {
        val (psnEnd, opcode) = inputAtomicReqMetaDataQueue.dequeue()
        atomicReqIn.bth.psn #= psnEnd
        atomicReqIn.bth.opcodeFull #= opcode.id
      }
      onStreamFire(atomicReqIn, dut.clockDomain) {
        val psn = atomicReqIn.bth.psn.toInt
        val opcode = OpCode(atomicReqIn.bth.opcodeFull.toInt)
        inputAtomicReqQueue.enqueue(
          (
            psn,
            opcode,
            atomicReqIn.atomicEth.va.toBigInt,
            atomicReqIn.atomicEth.rkey.toLong,
            atomicReqIn.atomicEth.swap.toBigInt,
            atomicReqIn.atomicEth.comp.toBigInt
          )
        )
//        println(
//          f"${simTime()} time: atomicRespIn has opcode=${opcode}, PSN=${psn}, rsvd=${atomicRespIn.aeth.rsvd.toInt}%X, code=${atomicRespIn.aeth.code.toInt}%X, value=${atomicRespIn.aeth.value.toInt}%X, msn=${atomicRespIn.aeth.msn.toInt}%X, orig=${atomicRespIn.atomicAckEth.orig.toBigInt}%X, when normalOrDupReq=${normalOrDupReq}"
//        )
      }

      var prePsn = -1
      streamSlaveRandomizer(dut.io.tx.pktFrag, dut.clockDomain)
      onStreamFire(dut.io.tx.pktFrag, dut.clockDomain) {
        val psn = dut.io.tx.pktFrag.bth.psn.toInt
        val opcode = OpCode(dut.io.tx.pktFrag.bth.opcodeFull.toInt)
        val pktFragData = dut.io.tx.pktFrag.data.toBigInt
        val isLastFrag = dut.io.tx.pktFrag.last.toBoolean

        if (normalOrDupReq && prePsn >= 0) { // Duplicate request might not in PSN order
          val curPsn = dut.io.qpAttr.epsn.toInt
          assert(
            PsnSim.psnCmp(prePsn, psn, curPsn) <= 0,
            f"${simTime()} time: prePsn=${prePsn} should < PSN=${psn} in PSN order, curPsn=dut.io.qpAttr.epsn=${curPsn}"
          )
        }
        prePsn = psn

        if (opcode.isReadReqPkt()) {
          val (readAddr, rmtKey, pktLen) =
            RethSim.extract(pktFragData, busWidth)
          outputReadReqQueue.enqueue((psn, opcode, readAddr, rmtKey, pktLen))
        } else if (opcode.isAtomicReqPkt()) {
          val (atomicAddr, rmtKey, atomicSwap, atomicComp) =
            AtomicEthSim.extract(pktFragData, busWidth)
          outputAtomicReqQueue.enqueue(
            (psn, opcode, atomicAddr, rmtKey, atomicSwap, atomicComp)
          )
        } else if (opcode.isSendReqPkt()) {
          outputSendReqQueue.enqueue((psn, opcode, pktFragData, isLastFrag))
        } else if (opcode.isWriteReqPkt()) {
          outputWriteReqQueue.enqueue((psn, opcode, pktFragData, isLastFrag))
        }
//        println(
//          f"${simTime()} time: dut.io.tx has opcode=${opcode}, PSN=${psn}, isLastFrag=${isLastFrag}, pktFragData=${pktFragData}%X"
//        )
      }

      if (normalOrDupReq) {
        fork {
          while (true) {
//            println(f"${simTime()} time: inputSendWriteReqOrErrRespQueue.size=${inputSendWriteReqOrErrRespQueue.size}")
//            println(f"${simTime()} time: inputAtomicReqQueue.size=${inputAtomicReqQueue.size}")
//            println(f"${simTime()} time: inputReadReqQueue.size=${inputReadReqQueue.size}")
            dut.clockDomain.waitSampling()
            if (dut.io.opsnInc.inc.toBoolean) {
              outputOutPsnQueue.enqueue(dut.io.opsnInc.psnVal.toInt)
            }
          }
        }
        MiscUtils.checkInputOutputQueues(
          dut.clockDomain,
          inputOutPsnQueue,
          outputOutPsnQueue,
          MATCH_CNT
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          dut.io.outPsnRangeFifoPush.valid.toBoolean
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputReadReqQueue,
        outputReadReqQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputAtomicReqQueue,
        outputAtomicReqQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputSendReqQueue,
        outputSendReqQueue,
        MATCH_CNT
      )
      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputWriteReqQueue,
        outputWriteReqQueue,
        MATCH_CNT
      )
    }

  test("SqOut normal request only case") {
    testFunc(normalOrDupReq = true)
  }

  test("SqOut duplicate request case") {
    testFunc(normalOrDupReq = false)
  }
}
