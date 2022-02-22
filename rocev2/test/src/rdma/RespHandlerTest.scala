package rdma

import spinal.core._
import spinal.core.sim._
import ConstantSettings._
import RdmaConstants._
import StreamSimUtil._
import AethSim._
import BthSim._
import PsnSim._
import RdmaTypeReDef._

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

class CoalesceAndNormalAndRetryNakHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(
      new SpinalConfig(defaultClockDomainFrequency = FixedFrequency(200 MHz))
    )
    .compile(new CoalesceAndNormalAndRetryNakHandler)

// TODO: test("CoalesceAndNormalAndRetryNakHandler error flush test")
// TODO: test("CoalesceAndNormalAndRetryNakHandler implicit retry test")
// TODO: test("CoalesceAndNormalAndRetryNakHandler response timeout test")

  test("CoalesceAndNormalAndRetryNakHandler duplicate and ghost ACK test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.qpAttr.dqpn #= 11
      dut.io.qpAttr.respTimeOut #= INFINITE_RESP_TIMEOUT // Disable response timeout retry

      // TODO: change flush signal accordingly
      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.errorFlush #= false
      dut.io.txQCtrl.retryFlush #= false

      val pmtuLen = PMTU.U256
      val (_, pktNumItr, psnItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)

      val dupOrGhostAckQueue = mutable.Queue[(PktNum, PsnStart, PktLen)]()
      val inputAckQueue = mutable.Queue[PSN]()

      val pendingReqNum = PENDING_REQ_NUM
      val matchCnt = MATCH_CNT

      for (_ <- 0 until pendingReqNum) {
        val pktNum = pktNumItr.next()
        val psnStart = psnItr.next()
        val totalLenBytes = totalLenItr.next()
        dupOrGhostAckQueue.enqueue((pktNum, psnStart, totalLenBytes.toLong))
      }
      fork {
        while (true) {
          waitUntil(dupOrGhostAckQueue.isEmpty)

          for (_ <- 0 until pendingReqNum) {
            val pktNum = pktNumItr.next()
            val psnStart = psnItr.next()
            val totalLenBytes = totalLenItr.next()
            dupOrGhostAckQueue.enqueue((pktNum, psnStart, totalLenBytes.toLong))
          }
        }
      }

      // io.cachedWorkReqPop never fire
      MiscUtils.checkConditionAlways(dut.clockDomain)(
//        !dut.io.cachedWorkReqPop.ready.toBoolean
        !(dut.io.cachedWorkReqPop.valid.toBoolean && dut.io.cachedWorkReqPop.ready.toBoolean)
      )

      streamMasterDriver(dut.io.rx, dut.clockDomain) {
        val (pktNum, psnStart, totalLenBytes) = dupOrGhostAckQueue.dequeue()

        // Set input to dut.io.cachedWorkReqPop
        dut.io.cachedWorkReqPop.randomize()
        val randOpCode = WorkReqSim.randomSendWriteOpCode()
        dut.io.cachedWorkReqPop.workReq.opcode #= randOpCode
        // NOTE: if PSN comparison is involved, it must update nPSN too
        dut.io.qpAttr.npsn #= psnStart +% pktNum
        dut.io.cachedWorkReqPop.pktNum #= pktNum
        dut.io.cachedWorkReqPop.psnStart #= psnStart
        dut.io.cachedWorkReqPop.workReq.lenBytes #= totalLenBytes

        // Set input to dut.io.rx
        val ackPsn = psnStart -% 1
        dut.io.rx.bth.psn #= ackPsn
        dut.io.rx.bth.setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.rx.aeth.setAsNormalAck()
//        println(
//          f"${simTime()} time: ACK PSN=${ackPsn}=${ackPsn}%X, psnStart=${psnStart}=${psnStart}%X, pktNum=${pktNum}=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx, dut.clockDomain) {
        inputAckQueue.enqueue(dut.io.rx.bth.psn.toInt)
      }

      streamSlaveRandomizer(dut.io.workCompAndAck, dut.clockDomain)
      // io.workCompAndAck never valid
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.workCompAndAck.valid.toBoolean
      )

      waitUntil(inputAckQueue.size > matchCnt)
    }
  }

  test(
    "CoalesceAndNormalAndRetryNakHandler normal ACK, explicit retry NAK, fatal NAK test"
  ) {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.qpAttr.dqpn #= 11
      dut.io.qpAttr.respTimeOut #= 0 // Disable response timeout retry

      // TODO: change flush signal accordingly
      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.errorFlush #= false
      dut.io.txQCtrl.retryFlush #= false

      val pmtuLen = PMTU.U1024
      val (_, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)

      val cachedWorkReqQueue = mutable.Queue[(PktNum, PsnStart, PktLen)]()
      val explicitAckQueue =
        mutable.Queue[(PktNum, PsnStart, SpinalEnumElement[AckType.type])]()
      val inputCachedWorkReqQueue = mutable.Queue[
        (
            PktNum,
            PsnStart,
            WorkReqId,
            PktLen,
            SpinalEnumElement[WorkReqOpCode.type]
        )
      ]()
      val inputAckQueue =
        mutable.Queue[(PSN, SpinalEnumElement[AckType.type])]()
      val outputWorkCompAndAckQueue =
        mutable.Queue[
          (
              PSN,
              SpinalEnumElement[WorkCompFlags.type],
              PktLen,
              SpinalEnumElement[WorkCompOpCode.type],
              WorkReqId,
              SpinalEnumElement[WorkCompStatus.type]
          )
        ]()
      val matchQueue = mutable.Queue[Int]()

      val pendingReqNum = PENDING_REQ_NUM
      val matchCnt = MATCH_CNT
      for (idx <- 0 until pendingReqNum) {
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val lenBytes = totalLenItr.next()
        // NOTE: if PSN comparison is involved, it must update nPSN too
        dut.io.qpAttr.npsn #= psnStart +% pktNum
        cachedWorkReqQueue.enqueue((pktNum, psnStart, lenBytes.toLong))
//        println(
//          f"${simTime()} time: cachedWorkReqQueue enqueue: pktNum=${pktNum}%X, psnStart=${psnStart}%X, lenBytes=${lenBytes}%X"
//        )
        if ((idx % pendingReqNum) == (pendingReqNum - 1)) {
          val retryNakType = AckTypeSim.randomRetryNak()
          val normalAckOrFatalNakType = AckTypeSim.randomNormalAckOrFatalNak()
          explicitAckQueue.enqueue((pktNum, psnStart, retryNakType))
          explicitAckQueue.enqueue((pktNum, psnStart, normalAckOrFatalNakType))
//          println(
//            f"${simTime()} time: explicitAckQueue enqueue: pktNum=${pktNum}=${pktNum}%X, psnStart=${psnStart}=${psnStart}%X, retryNakType=${retryNakType}, normalAckOrFatalNakeType=${normalAckOrFatalNakeType}"
//          )
        }
      }
      fork {
        while (true) {
          waitUntil(explicitAckQueue.isEmpty)

          for (idx <- 0 until pendingReqNum) {
            val pktNum = pktNumItr.next()
            val psnStart = psnStartItr.next()
            val lenBytes = totalLenItr.next()
            // NOTE: if PSN comparison is involved, it must update nPSN too
            dut.io.qpAttr.npsn #= psnStart +% pktNum
            cachedWorkReqQueue.enqueue((pktNum, psnStart, lenBytes.toLong))
            if ((idx % pendingReqNum) == (pendingReqNum - 1)) {
              explicitAckQueue.enqueue((pktNum, psnStart, AckType.NAK_RNR))
              explicitAckQueue.enqueue((pktNum, psnStart, AckType.NORMAL))
//              println(
//                f"${simTime()} time: explicitAckQueue enqueue: pktNum=${pktNum}=${pktNum}%X, psnStart=${psnStart}=${psnStart}%X, ackType=${AckType.NAK_RNR}"
//              )
            }
          }
        }
      }

      // NOTE: io.cachedWorkReqPop.valid should be always true,
      // otherwise ACKs will be treated as ghost ones
      streamMasterDriverAlwaysValid(dut.io.cachedWorkReqPop, dut.clockDomain) {
        val (pktNum, psnStart, lenBytes) = cachedWorkReqQueue.dequeue()
        val randOpCode = WorkReqSim.randomSendWriteOpCode()
        dut.io.cachedWorkReqPop.workReq.opcode #= randOpCode
        dut.io.cachedWorkReqPop.pktNum #= pktNum
        dut.io.cachedWorkReqPop.psnStart #= psnStart
        dut.io.cachedWorkReqPop.workReq.lenBytes #= lenBytes
//        println(
//          f"${simTime()} time: WR: opcode=${randOpCode}, pktNum=${pktNum}=${pktNum}%X, psnStart=${psnStart}=${psnStart}%X, lenBytes=${lenBytes}=${lenBytes}%X"
//        )
      }
      onStreamFire(dut.io.cachedWorkReqPop, dut.clockDomain) {
        inputCachedWorkReqQueue.enqueue(
          (
            dut.io.cachedWorkReqPop.pktNum.toInt,
            dut.io.cachedWorkReqPop.psnStart.toInt,
            dut.io.cachedWorkReqPop.workReq.id.toBigInt,
            dut.io.cachedWorkReqPop.workReq.lenBytes.toLong,
            dut.io.cachedWorkReqPop.workReq.opcode.toEnum
          )
        )
      }

      streamMasterDriver(dut.io.rx, dut.clockDomain) {
        val (pktNum, psnStart, ackType) = explicitAckQueue.dequeue()
        val ackPsn = psnStart +% pktNum -% 1
//        dut.io.qpAttr.npsn #= ackPsn
        dut.io.rx.bth.psn #= ackPsn
        dut.io.rx.bth.setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.rx.aeth.setAs(ackType)
//        println(
//          f"${simTime()} time: ACK ackType=${ackType} PSN=${ackPsn}=${ackPsn}%X, psnStart=${psnStart}=${psnStart}%X, pktNum=${pktNum}=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx, dut.clockDomain) {
        val ackType = AckTypeSim.decodeFromAeth(dut.io.rx.aeth)
        if (AckTypeSim.isRetryNak(ackType)) {
          assert(
            dut.io.retryNotifier.pulse.toBoolean,
            f"${simTime()} time: dut.io.retryNotifier.pulse=${dut.io.retryNotifier.pulse.toBoolean} should be true when ackType=${ackType}"
          )
        }
        inputAckQueue.enqueue((dut.io.rx.bth.psn.toInt, ackType))
      }

      streamSlaveRandomizer(dut.io.workCompAndAck, dut.clockDomain)
      onStreamFire(dut.io.workCompAndAck, dut.clockDomain) {
        outputWorkCompAndAckQueue.enqueue(
          (
            dut.io.workCompAndAck.ack.bth.psn.toInt,
            dut.io.workCompAndAck.workComp.flags.toEnum,
            dut.io.workCompAndAck.workComp.lenBytes.toLong,
            dut.io.workCompAndAck.workComp.opcode.toEnum,
            dut.io.workCompAndAck.workComp.id.toBigInt,
            dut.io.workCompAndAck.workComp.status.toEnum
          )
        )
      }

      fork {
        while (true) {
          val (retryNakPsnIn, retryNakTypeIn) =
            MiscUtils.safeDeQueue(inputAckQueue, dut.clockDomain)
          val (ackPsnIn, ackTypeIn) =
            MiscUtils.safeDeQueue(inputAckQueue, dut.clockDomain)
          assert(
            retryNakPsnIn == ackPsnIn,
            f"${simTime()} time: retryNakPsnIn=${retryNakPsnIn} == ackPsnIn=${ackPsnIn}"
          )
          assert(
            AckTypeSim.isRetryNak(retryNakTypeIn),
            f"${simTime()} time: retryNakTypeIn=${retryNakTypeIn} should be retry NAK"
          )
          assert(
            AckTypeSim.isFatalNak(ackTypeIn) || AckTypeSim.isNormalAck(
              ackTypeIn
            ),
            f"${simTime()} time: ackTypeIn=${ackTypeIn} should be normal ACK or fatal NAK"
          )
//          println(
//            f"${simTime()} time: ACK psnIn=${ackPsnIn}=${ackPsnIn}%X, ackTypeIn=${ackTypeIn}"
//          )

          var needCoalesceAck = false
          do {
            val (
              pktNumIn,
              psnStartInIn,
              workReqIdIn,
              lenBytesIn,
              workReqOpCodeIn
            ) = MiscUtils.safeDeQueue(inputCachedWorkReqQueue, dut.clockDomain)
            val (
              ackPsnOut,
              workCompFlagOut,
              lenBytesOut,
              workCompOpCodeOut,
              workCompIdOut,
              workCompStatusOut
            ) =
              MiscUtils.safeDeQueue(outputWorkCompAndAckQueue, dut.clockDomain)
            val workReqEndPsn = psnStartInIn +% pktNumIn -% 1
//            println(
//              f"${simTime()} time: WR: workReqOpCodeIn=${workReqOpCodeIn}, psnStartInIn=${psnStartInIn}=${psnStartInIn}%X, workReqEndPsn=${workReqEndPsn}=${workReqEndPsn}%X, ackPsnOut=${ackPsnOut}=${ackPsnOut}%X, pktNumIn=${pktNumIn}=${pktNumIn}%X"
//            )

            WorkCompSim.sqCheckWorkCompOpCode(
              workReqOpCodeIn,
              workCompOpCodeOut
            )
            WorkCompSim.sqCheckWorkCompFlag(workReqOpCodeIn, workCompFlagOut)

            if (ackPsnOut != ackPsnIn) {
              println(
                f"${simTime()} time: ackPsnIn=${ackPsnIn}%X should == ackPsnOut=${ackPsnOut}%X"
              )
              println(
                f"${simTime()} time: WR: workReqOpCodeIn=${workReqOpCodeIn}, psnStartInIn=${psnStartInIn}=${psnStartInIn}%X, workReqEndPsn=${workReqEndPsn}=${workReqEndPsn}%X, ackPsnOut=${ackPsnOut}=${ackPsnOut}%X, pktNumIn=${pktNumIn}=${pktNumIn}%X"
              )
            }

            assert(
              ackPsnIn == ackPsnOut,
              f"${simTime()} time: ackPsnIn=${ackPsnIn}%X should == ackPsnOut=${ackPsnOut}%X"
            )

            assert(
              workCompIdOut == workReqIdIn,
              f"${simTime()} time: workCompIdOut=${workCompIdOut}%X should == workReqIdIn=${workReqIdIn}%X"
            )
            assert(
              lenBytesOut == lenBytesIn,
              f"${simTime()} time: lenBytesOut=${lenBytesOut}%X should == lenBytesIn=${lenBytesIn}%X"
            )

            if (workReqEndPsn != ackPsnIn) { // Coalesce ACK case
              assert(
                workCompStatusOut == WorkCompStatus.SUCCESS,
                f"${simTime()} time: ackTypeIn=${ackTypeIn} workCompStatusOut=${workCompStatusOut} should be WorkCompStatus.SUCCESS, ackPsnIn=${ackPsnIn}%X"
              )
            } else { // Explicit ACK case
              if (AckTypeSim.isFatalNak(ackTypeIn)) {
                assert(
                  workCompStatusOut != WorkCompStatus.SUCCESS,
                  f"${simTime()} time: ackTypeIn=${ackTypeIn} workCompStatusOut=${workCompStatusOut} should not be WorkCompStatus.SUCCESS, ackPsnIn=${ackPsnIn}%X"
                )
              } else {
                assert(
                  workCompStatusOut == WorkCompStatus.SUCCESS,
                  f"${simTime()} time: ackTypeIn=${ackTypeIn} workCompStatusOut=${workCompStatusOut} should be WorkCompStatus.SUCCESS, ackPsnIn=${ackPsnIn}%X"
                )
              }
            }

            needCoalesceAck = workReqEndPsn != ackPsnIn
          } while (needCoalesceAck)

          matchQueue.enqueue(ackPsnIn)
        }
      }
      waitUntil(matchQueue.size > matchCnt)
    }
  }
}

class ReadAtomicRespVerifierAndFatalNakNotifierTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadAtomicRespVerifierAndFatalNakNotifier(busWidth))

// TODO: test("ReadAtomicRespVerifierAndFatalNakNotifier error flush test")

  test("ReadAtomicRespVerifierAndFatalNakNotifier fatal NAK test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.errorFlush #= false
      dut.io.txQCtrl.retryFlush #= false

      val matchQueue = mutable.Queue[PSN]()

      dut.io.readAtomicResp.pktFrag.valid #= false
      streamMasterDriver(dut.io.rxAck, dut.clockDomain) {
        val normalAckOrFatalNakType = AckTypeSim.randomFatalNak()
        dut.io.rxAck.bth
          .setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.rxAck.aeth.setAs(normalAckOrFatalNakType)
      }
      onStreamFire(dut.io.rxAck, dut.clockDomain) {
        val ackType = AckTypeSim.decodeFromAeth(dut.io.rxAck.aeth)
        if (AckTypeSim.isFatalNak(ackType)) {
          assert(
            dut.io.errNotifier.pulse.toBoolean,
            f"${simTime()} time: dut.io.errNotifier.pulse=${dut.io.errNotifier.pulse.toBoolean} should be true when ackType=${ackType}"
          )
          assert(
            dut.io.errNotifier.errType.toEnum != SqErrType.NO_ERR,
            f"${simTime()} time: dut.io.errNotifier.errType=${dut.io.errNotifier.errType.toEnum} should not be ${SqErrType.NO_ERR} when ackType=${ackType}"
          )
          matchQueue.enqueue(dut.io.rxAck.bth.psn.toInt)
        }
      }
      streamSlaveRandomizer(dut.io.txAck, dut.clockDomain)

      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }

  test("ReadAtomicRespVerifierAndFatalNakNotifier read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.errorFlush #= false
      dut.io.txQCtrl.retryFlush #= false

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val readRespMetaDataQueue = mutable.Queue[(PsnStart, FragNum)]()
      val rxReadRespQueue = mutable.Queue[(PSN, PktFragData, FragLast)]()
      val txReadRespQueue =
        mutable.Queue[(PSN, PhysicalAddr, WorkReqId, PktFragData, FragLast)]()
      val workReqQueryReqQueue = mutable.Queue[PSN]()
      val workReqQueryRespQueue = mutable.Queue[(PSN, VirtualAddr, WorkReqId)]()
      val addrCacheReadReqQueue = mutable.Queue[(PSN, VirtualAddr)]()
      val addrCacheReadRespQueue = mutable.Queue[(PSN, PhysicalAddr)]()
      val matchQueue = mutable.Queue[PSN]()

      dut.io.rxAck.valid #= false
      pktFragStreamMasterDriver(
        dut.io.readAtomicResp.pktFrag,
        dut.clockDomain
      ) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val totalLenBytes = totalLenItr.next()
//        println(
//          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        readRespMetaDataQueue.enqueue((psnStart, totalFragNum))
        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, totalLenBytes)
      } { (psn, _, fragLast, _, _, pktIdx, pktNum, _) =>
        // Only RC is supported
//        dut.io.rx.pktFrag.bth.transport #= Transports.RC.id
        if (pktNum == 1) {
          dut.io.readAtomicResp.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_ONLY.id
        } else if (pktIdx == 0) {
          dut.io.readAtomicResp.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_FIRST.id
        } else if (pktIdx == pktNum - 1) {
          dut.io.readAtomicResp.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_LAST.id
        } else {
          dut.io.readAtomicResp.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_MIDDLE.id
        }
        dut.io.readAtomicResp.pktFrag.bth.psn #= psn
        dut.io.readAtomicResp.pktFrag.last #= fragLast
//        println(
//          f"${simTime()} time: fragIdx=${fragIdx}%X, fragNum=${fragNum}%X, fragLast=${fragLast}, isLastFragPerPkt=${pktIdx == pktNum - 1}, totalLenBytes=${totalLenBytes}%X, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.readAtomicResp.pktFrag, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        rxReadRespQueue.enqueue(
          (
            dut.io.readAtomicResp.pktFrag.bth.psn.toInt,
            dut.io.readAtomicResp.pktFrag.data.toBigInt,
            dut.io.readAtomicResp.pktFrag.last.toBoolean
          )
        )
      }

      onReceiveStreamReqAndThenResponseRandom(
        reqStream = dut.io.workReqQuery.req,
        respStream = dut.io.workReqQuery.resp,
        dut.clockDomain
      ) {
        val queryPsn = dut.io.workReqQuery.req.psn.toInt
        workReqQueryReqQueue.enqueue(queryPsn)

//        println(
//          f"${simTime()} time: dut.io.workReqQuery.req received queryPsn=${queryPsn}%X"
//        )
      } {
        val psn = workReqQueryReqQueue.dequeue()
        dut.io.workReqQuery.resp.query.psn #= psn
        dut.io.workReqQuery.resp.cachedWorkReq.psnStart #= psn
        dut.io.workReqQuery.resp.found #= true

//        println(f"${simTime()} time: dut.io.workReqQuery.resp PSN=${psn}%X")
      }
      onStreamFire(dut.io.workReqQuery.resp, dut.clockDomain) {
        workReqQueryRespQueue.enqueue(
          (
            dut.io.workReqQuery.resp.query.psn.toInt,
            dut.io.workReqQuery.resp.cachedWorkReq.workReq.laddr.toBigInt,
            dut.io.workReqQuery.resp.cachedWorkReq.workReq.id.toBigInt
          )
        )
      }

      onReceiveStreamReqAndThenResponseRandom(
        reqStream = dut.io.addrCacheRead.req,
        respStream = dut.io.addrCacheRead.resp,
        dut.clockDomain
      ) {
        addrCacheReadReqQueue.enqueue(
          (
            dut.io.addrCacheRead.req.psn.toInt,
            dut.io.addrCacheRead.req.va.toBigInt
          )
        )
//        println(
//          f"${simTime()} time: dut.io.addrCacheRead.req received PSN=${dut.io.addrCacheRead.req.psn.toInt}%X"
//        )
      } {
        val (psn, _) = addrCacheReadReqQueue.dequeue()
        dut.io.addrCacheRead.resp.psn #= psn
      }
      onStreamFire(dut.io.addrCacheRead.resp, dut.clockDomain) {
        addrCacheReadRespQueue.enqueue(
          (
            dut.io.addrCacheRead.resp.psn.toInt,
            dut.io.addrCacheRead.resp.pa.toBigInt
          )
        )
//        println(
//          f"${simTime()} time: dut.io.addrCacheRead.resp PSN=${dut.io.addrCacheRead.resp.psn.toInt}%X"
//        )
      }

      streamSlaveRandomizer(
        dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo,
        dut.clockDomain
      )
      onStreamFire(
        dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo,
        dut.clockDomain
      ) {
        txReadRespQueue.enqueue(
          (
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.bth.psn.toInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pa.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.workReqId.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.data.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.last.toBoolean
          )
        )
      }

      val maxFragNumPerPkt =
        SendWriteReqReadRespInputGen.maxFragNumPerPkt(pmtuLen, busWidth)
      fork {
        while (true) {
          val (psnStartInReadRespMeta, totalFragNum) =
            MiscUtils.safeDeQueue(readRespMetaDataQueue, dut.clockDomain)
          val (psnStartInWorkReqQueryResp, _, workReqIdInWorkReqQueryResp) =
            MiscUtils.safeDeQueue(workReqQueryRespQueue, dut.clockDomain)

          val (psnStartInAddrCacheResp, phyAddrInAddrCacheResp) =
            MiscUtils.safeDeQueue(addrCacheReadRespQueue, dut.clockDomain)
//          println(
//            f"${simTime()} time: Read response psnStartInReadRespMeta=${psnStartInReadRespMeta}=${psnStartInReadRespMeta}%X"
//          )
          assert(
            psnStartInReadRespMeta == psnStartInWorkReqQueryResp && psnStartInWorkReqQueryResp == psnStartInAddrCacheResp,
            f"${simTime()} time: psnStartInReadRespMeta=${psnStartInReadRespMeta}%X should == psnStartInWorkReqQueryResp=${psnStartInWorkReqQueryResp}%X and psnStartInWorkReqQueryResp=${psnStartInWorkReqQueryResp}%X should == psnStartInAddrCacheResp=${psnStartInAddrCacheResp}%X"
          )

          for (fragIdx <- 0 until totalFragNum) {
            val psnIn = psnStartInReadRespMeta + (fragIdx / maxFragNumPerPkt)
            val (psnInReadResp, dataInReadResp, isLastInReadResp) =
              MiscUtils.safeDeQueue(rxReadRespQueue, dut.clockDomain)
            val (
              psnInDmaWriteReq,
              phyAddrOutDmaWriteReq,
              workReqIdOutDmaWriteReq,
              dataOutDmaWriteReq,
              isLastOutDmaWriteReq
            ) = MiscUtils.safeDeQueue(txReadRespQueue, dut.clockDomain)

            assert(
              psnIn == psnInReadResp && psnInReadResp == psnInDmaWriteReq,
              f"${simTime()} time: psnIn=${psnIn}%X should == psnInReadResp=${psnInReadResp}%X and psnInReadResp=${psnInReadResp}%X should == psnInDmaWriteReq=${psnInDmaWriteReq}%X"
            )
            assert(
              phyAddrInAddrCacheResp == phyAddrOutDmaWriteReq,
              f"${simTime()} time: phyAddrInAddrCacheResp=${phyAddrInAddrCacheResp}%X == phyAddrOutDmaWriteReq=${phyAddrOutDmaWriteReq}%X"
            )
            assert(
              workReqIdInWorkReqQueryResp == workReqIdOutDmaWriteReq,
              f"${simTime()} time: workReqIdInWorkReqQueryResp=${workReqIdInWorkReqQueryResp}%X == workReqIdOutDmaWriteReq=${workReqIdOutDmaWriteReq}%X"
            )
            assert(
              dataInReadResp == dataOutDmaWriteReq,
              f"${simTime()} time: dataInReadResp=${dataInReadResp}%X should == dataOutDmaWriteReq=${dataOutDmaWriteReq}%X"
            )
            assert(
              isLastInReadResp == isLastOutDmaWriteReq,
              f"${simTime()} time: isLastInReadResp=${isLastInReadResp} should == isLastOutDmaWriteReq=${isLastOutDmaWriteReq}"
            )
          }
          matchQueue.enqueue(psnStartInReadRespMeta)
//          println(f"${simTime()} time: ${matchQueue.size} matches")
        }
      }
      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
}

class ReadAtomicRespDmaReqInitiatorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadAtomicRespDmaReqInitiator(busWidth))

  test("ReadAtomicRespDmaReqInitiator normal behavior test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      // TODO: change flush signal accordingly
      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.errorFlush #= false
      dut.io.txQCtrl.retryFlush #= false

      // Input to DUT
      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val rxReadRespQueue =
        mutable.Queue[(PSN, PhysicalAddr, WorkReqId, PktFragData, FragLast)]()
      val readRespDmaWriteReqQueue =
        mutable.Queue[(PSN, PhysicalAddr, WorkReqId, PktFragData, FragLast)]()

      pktFragStreamMasterDriver(
        dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo,
        dut.clockDomain
      ) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val totalLenBytes = totalLenItr.next()
//        println(
//          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )
        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, totalLenBytes)
      } { (psn, _, fragLast, _, _, pktIdx, pktNum, _) =>
        // Only RC is supported
//        dut.io.rx.pktFrag.bth.transport #= Transports.RC.id
        if (pktNum == 1) {
          dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_ONLY.id
        } else if (pktIdx == 0) {
          dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_FIRST.id
        } else if (pktIdx == pktNum - 1) {
          dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_LAST.id
        } else {
          dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_MIDDLE.id
        }
        dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.bth.psn #= psn
        dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.last #= fragLast
//        println(
//          f"${simTime()} time: fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, fragLast=${fragLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )
      }
      onStreamFire(
        dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo,
        dut.clockDomain
      ) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        rxReadRespQueue.enqueue(
          (
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.bth.psn.toInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pa.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.workReqId.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.data.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.readRespDmaWriteReq.req, dut.clockDomain)
      onStreamFire(dut.io.readRespDmaWriteReq.req, dut.clockDomain) {
        readRespDmaWriteReqQueue.enqueue(
          (
            dut.io.readRespDmaWriteReq.req.psn.toInt,
            dut.io.readRespDmaWriteReq.req.pa.toBigInt,
            dut.io.readRespDmaWriteReq.req.workReqId.toBigInt,
            dut.io.readRespDmaWriteReq.req.data.toBigInt,
            dut.io.readRespDmaWriteReq.req.last.toBoolean
          )
        )
      }

      streamSlaveRandomizer(dut.io.atomicRespDmaWriteReq.req, dut.clockDomain)
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        dut.io.atomicRespDmaWriteReq.req.valid.toBoolean == false
      )

      streamSlaveRandomizer(dut.io.atomicRespDmaWriteReq.req, dut.clockDomain)
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        dut.io.atomicRespDmaWriteReq.req.valid.toBoolean == false
      )

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        rxReadRespQueue,
        readRespDmaWriteReqQueue,
        MATCH_CNT
      )
    }
  }
}