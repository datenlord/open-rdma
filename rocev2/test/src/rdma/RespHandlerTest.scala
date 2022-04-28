package rdma

import spinal.core._
import spinal.core.sim._
import ConstantSettings._
import RdmaConstants._
import StreamSimUtil._
import AethSim._
import BthSim._
import OpCodeSim._
import PsnSim._
import RdmaTypeReDef._

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._
import scala.collection.mutable

class CoalesceAndNormalAndRetryNakHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(
      SpinalConfig(defaultClockDomainFrequency = FixedFrequency(200 MHz))
    )
    .compile(new CoalesceAndNormalAndRetryNakHandler(busWidth))

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
        dut.io.rx.pktFrag.bth.psn #= ackPsn
        dut.io.rx.pktFrag.bth
          .setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.rx.aeth.setAsNormalAck()
//        println(
//          f"${simTime()} time: ACK PSN=${ackPsn}=${ackPsn}%X, psnStart=${psnStart}=${psnStart}%X, pktNum=${pktNum}=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx, dut.clockDomain) {
        inputAckQueue.enqueue(dut.io.rx.pktFrag.bth.psn.toInt)
      }

      streamSlaveRandomizer(
        dut.io.cachedWorkReqAndRespWithAeth,
        dut.clockDomain
      )
      // io.cachedWorkReqAndRespWithAeth never valid
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.cachedWorkReqAndRespWithAeth.valid.toBoolean
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
      val outputWorkReqAndAckQueue =
        mutable.Queue[
          (
              PSN,
//              WorkReqFlags, // SpinalEnumElement[WorkCompFlags.type],
              PktLen,
              SpinalEnumElement[WorkReqOpCode.type],
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
              val retryNakType = AckTypeSim.randomRetryNak()
              val normalAckOrFatalNakType =
                AckTypeSim.randomNormalAckOrFatalNak()
              explicitAckQueue.enqueue((pktNum, psnStart, retryNakType))
              explicitAckQueue.enqueue(
                (pktNum, psnStart, normalAckOrFatalNakType)
              )
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
        dut.io.rx.pktFrag.bth.psn #= ackPsn
        dut.io.rx.pktFrag.bth
          .setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.rx.aeth.setAs(ackType)
        dut.io.rx.workCompStatus #= AckTypeSim.toWorkCompStatus(ackType)
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
        inputAckQueue.enqueue((dut.io.rx.pktFrag.bth.psn.toInt, ackType))
      }

      streamSlaveRandomizer(
        dut.io.cachedWorkReqAndRespWithAeth,
        dut.clockDomain
      )
      onStreamFire(dut.io.cachedWorkReqAndRespWithAeth, dut.clockDomain) {
        outputWorkReqAndAckQueue.enqueue(
          (
            dut.io.cachedWorkReqAndRespWithAeth.pktFrag.bth.psn.toInt,
//            dut.io.cachedWorkReqAndRespWithAeth.cachedWorkReq.workReq.flags.flagBits.toInt,
            dut.io.cachedWorkReqAndRespWithAeth.cachedWorkReq.workReq.lenBytes.toLong,
            dut.io.cachedWorkReqAndRespWithAeth.cachedWorkReq.workReq.opcode.toEnum,
            dut.io.cachedWorkReqAndRespWithAeth.cachedWorkReq.workReq.id.toBigInt,
            dut.io.cachedWorkReqAndRespWithAeth.workCompStatus.toEnum
          )
        )
      }

      fork {
        while (true) {
          val (retryNakPsnIn, retryNakTypeIn) =
            MiscUtils.safeDeQueue(inputAckQueue, dut.clockDomain)
          val (ackPsnIn, ackTypeIn) =
            MiscUtils.safeDeQueue(inputAckQueue, dut.clockDomain)

          retryNakPsnIn shouldBe ackPsnIn withClue
            f"${simTime()} time: retryNakPsnIn=${retryNakPsnIn} == ackPsnIn=${ackPsnIn}"

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
//              workReqFlagOut,
              lenBytesOut,
              workReqOpCodeOut,
              workReqIdOut,
              workCompStatusOut
            ) =
              MiscUtils.safeDeQueue(outputWorkReqAndAckQueue, dut.clockDomain)
            val workReqEndPsn = psnStartInIn +% pktNumIn -% 1
//            println(
//              f"${simTime()} time: WR: workReqOpCodeIn=${workReqOpCodeIn}, psnStartInIn=${psnStartInIn}=${psnStartInIn}%X, workReqEndPsn=${workReqEndPsn}=${workReqEndPsn}%X, ackPsnOut=${ackPsnOut}=${ackPsnOut}%X, pktNumIn=${pktNumIn}=${pktNumIn}%X"
//            )

            workReqOpCodeOut shouldBe workReqOpCodeIn withClue
              f"${simTime()} time: workReqOpCodeIn=${workReqOpCodeIn} should equal workReqOpCodeOut=${workReqOpCodeOut}"
//            WorkCompSim.sqCheckWorkCompOpCode(
//              workReqOpCodeIn,
//              workReqOpCodeOut
//            )
//            WorkCompSim.sqCheckWorkCompFlag(workReqOpCodeIn, workCompFlagOut)

            if (ackPsnOut != ackPsnIn) {
              println(
                f"${simTime()} time: ackPsnIn=${ackPsnIn}%X should equal ackPsnOut=${ackPsnOut}%X"
              )
              println(
                f"${simTime()} time: WR: workReqOpCodeIn=${workReqOpCodeIn}, psnStartInIn=${psnStartInIn}=${psnStartInIn}%X, workReqEndPsn=${workReqEndPsn}=${workReqEndPsn}%X, ackPsnOut=${ackPsnOut}=${ackPsnOut}%X, pktNumIn=${pktNumIn}=${pktNumIn}%X"
              )
            }

            ackPsnOut shouldBe ackPsnIn withClue
              f"${simTime()} time: ackPsnIn=${ackPsnIn}%X should equal ackPsnOut=${ackPsnOut}%X"

            workReqIdOut shouldBe workReqIdIn withClue
              f"${simTime()} time: workReqIdOut=${workReqIdOut}%X should equal workReqIdIn=${workReqIdIn}%X"

            lenBytesOut shouldBe lenBytesIn withClue
              f"${simTime()} time: lenBytesOut=${lenBytesOut}%X should equal lenBytesIn=${lenBytesIn}%X"

            if (workReqEndPsn != ackPsnIn) { // Coalesce ACK case

              workCompStatusOut shouldBe WorkCompStatus.SUCCESS withClue
                f"${simTime()} time: ackTypeIn=${ackTypeIn} workCompStatusOut=${workCompStatusOut} should be WorkCompStatus.SUCCESS, ackPsnIn=${ackPsnIn}%X"
            } else { // Explicit ACK case
              if (AckTypeSim.isFatalNak(ackTypeIn)) {
                workCompStatusOut shouldNot be(WorkCompStatus.SUCCESS) withClue
                  f"${simTime()} time: ackTypeIn=${ackTypeIn} workCompStatusOut=${workCompStatusOut} should not be WorkCompStatus.SUCCESS, ackPsnIn=${ackPsnIn}%X"
              } else {
                workCompStatusOut shouldBe WorkCompStatus.SUCCESS withClue
                  f"${simTime()} time: ackTypeIn=${ackTypeIn} workCompStatusOut=${workCompStatusOut} should be WorkCompStatus.SUCCESS, ackPsnIn=${ackPsnIn}%X"
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

class ReadRespLenCheckTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 137

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(SpinalConfig(anonymSignalPrefix = "tmp"))
    .compile {
      val dut = new ReadRespLenCheck(busWidth)
      dut.totalLenValid.simPublic()
      dut.totalLenBytes.simPublic()
      dut
    }

  test("ReadRespLenCheck read response check normal case") {
    testReadRespLenCheckFunc(lenCheckPass = true)
  }

  test("ReadRespLenCheck read response check failure case") {
    testReadRespLenCheckFunc(lenCheckPass = false)
  }

  test("ReadRespLenCheck non-read response case") {
    testNonReadRespFunc()
  }

  def testNonReadRespFunc(): Unit = simCfg.doSim { dut =>
    dut.clockDomain.forkStimulus(10)

    dut.io.txQCtrl.wrongStateFlush #= false
    dut.io.qpAttr.pmtu #= pmtuLen.id

    val inputQueue = mutable
      .Queue[(WorkReqId, PktLen, SpinalEnumElement[WorkCompStatus.type])]()
    val outputQueue = mutable
      .Queue[(WorkReqId, PktLen, SpinalEnumElement[WorkCompStatus.type])]()

    streamMasterDriver(dut.io.cachedWorkReqAndRespWithAethIn, dut.clockDomain) {
      val opcode = OpCodeSim.randomNonReadRespOpCode()
      dut.io.cachedWorkReqAndRespWithAethIn.pktFrag.bth.opcodeFull #= opcode.id
      dut.io.cachedWorkReqAndRespWithAethIn.last #= true
    }
    onStreamFire(dut.io.cachedWorkReqAndRespWithAethIn, dut.clockDomain) {
      inputQueue.enqueue(
        (
          dut.io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.workReq.id.toBigInt,
          dut.io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.workReq.lenBytes.toLong,
          dut.io.cachedWorkReqAndRespWithAethIn.workCompStatus.toEnum
        )
      )
    }

    streamSlaveRandomizer(
      dut.io.cachedWorkReqAndRespWithAethOut,
      dut.clockDomain
    )
    onStreamFire(dut.io.cachedWorkReqAndRespWithAethOut, dut.clockDomain) {
      outputQueue.enqueue(
        (
          dut.io.cachedWorkReqAndRespWithAethOut.cachedWorkReq.workReq.id.toBigInt,
          dut.io.cachedWorkReqAndRespWithAethOut.cachedWorkReq.workReq.lenBytes.toLong,
          dut.io.cachedWorkReqAndRespWithAethOut.workCompStatus.toEnum
        )
      )
    }

    MiscUtils.checkConditionAlways(dut.clockDomain)(
      !dut.totalLenValid.toBoolean
    )

    MiscUtils.checkInputOutputQueues(
      dut.clockDomain,
      inputQueue,
      outputQueue,
      MATCH_CNT
    )
  }

  def makeErrLen(pktLen: PktLen): PktLen = pktLen + 1
  def testReadRespLenCheckFunc(lenCheckPass: Boolean): Unit = simCfg.doSim {
    dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.qpAttr.pmtu #= pmtuLen.id

      val inputQueue = mutable
        .Queue[(WorkReqId, PktLen, SpinalEnumElement[WorkCompStatus.type])]()
      val outputQueue = mutable
        .Queue[(WorkReqId, PktLen, SpinalEnumElement[WorkCompStatus.type])]()

      val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      RdmaDataPktSim.readRespPktFragStreamMasterDriver(
        dut.io.cachedWorkReqAndRespWithAethIn,
        getRdmaPktDataFunc =
          (cachedWorkReqAndRespWithAethIn: CachedWorkReqAndRespWithAeth) =>
            cachedWorkReqAndRespWithAethIn.pktFrag,
        dut.clockDomain
      ) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnStartItr.next()
        val totalLenBytes = totalLenItr.next()

        println(
          f"${simTime()} time: pktNum=${pktNum}%X, totalFragNum=${totalFragNum}%X, psnStart=${psnStart}%X, totalLenBytes=${totalLenBytes}%X"
        )
        (
          psnStart,
          totalFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          totalLenBytes.toLong
        )
      } {
        (
            _, // psn,
            psnStart,
            _, // fragLast,
            _, // fragIdx,
            _, // pktFragNum,
            _, // pktIdx,
            _, // pktNum,
            payloadLenBytes,
            _, // headerLenBytes,
            _ // opcode
        ) =>
          dut.io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.psnStart #= psnStart
          dut.io.cachedWorkReqAndRespWithAethIn.workCompStatus #= WorkCompStatus.SUCCESS
          if (lenCheckPass) {
            dut.io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.workReq.lenBytes #= payloadLenBytes
          } else {
            dut.io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.workReq.lenBytes #=
              makeErrLen(payloadLenBytes)
          }
//        println(
//          f"${simTime()} time: fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, fragLast=${fragLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )
      }
      onStreamFire(dut.io.cachedWorkReqAndRespWithAethIn, dut.clockDomain) {
        val expectedWorkCompStatus = if (lenCheckPass) {
          WorkCompStatus.SUCCESS
        } else {
          WorkCompStatus.LOC_LEN_ERR
        }
        val isLastFrag = dut.io.cachedWorkReqAndRespWithAethIn.last.toBoolean
        val readRespOpCode = OpCode(
          dut.io.cachedWorkReqAndRespWithAethIn.pktFrag.bth.opcodeFull.toInt
        )
        val isLastOrOnlyReadResp = readRespOpCode.isLastOrOnlyReadRespPkt()
        if (isLastOrOnlyReadResp && isLastFrag) {
          inputQueue.enqueue(
            (
              dut.io.cachedWorkReqAndRespWithAethIn.cachedWorkReq.workReq.id.toBigInt,
              if (lenCheckPass) dut.totalLenBytes.toLong
              else makeErrLen(dut.totalLenBytes.toLong),
              expectedWorkCompStatus
            )
          )
          dut.totalLenValid.toBoolean shouldBe true withClue
            f"${simTime()} time: dut.totalLenValid=${dut.totalLenValid.toBoolean} should be true when readRespOpCode=${readRespOpCode}, isLastOrOnlyReadResp=${isLastOrOnlyReadResp}, dut.io.cachedWorkReqAndRespWithAethIn.last=${isLastFrag}"
        }
      }

      streamSlaveRandomizer(
        dut.io.cachedWorkReqAndRespWithAethOut,
        dut.clockDomain
      )
      onStreamFire(dut.io.cachedWorkReqAndRespWithAethOut, dut.clockDomain) {
        val isLastFrag = dut.io.cachedWorkReqAndRespWithAethOut.last.toBoolean
        val readRespOpCode = OpCode(
          dut.io.cachedWorkReqAndRespWithAethOut.pktFrag.bth.opcodeFull.toInt
        )
        val isLastOrOnlyReadResp = readRespOpCode.isLastOrOnlyReadRespPkt()
        if (isLastOrOnlyReadResp && isLastFrag) {
          outputQueue.enqueue(
            (
              dut.io.cachedWorkReqAndRespWithAethOut.cachedWorkReq.workReq.id.toBigInt,
              dut.io.cachedWorkReqAndRespWithAethOut.cachedWorkReq.workReq.lenBytes.toLong,
              dut.io.cachedWorkReqAndRespWithAethOut.workCompStatus.toEnum
            )
          )
        }
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputQueue,
        outputQueue,
        MATCH_CNT
      )
  }
}

class ReadAtomicRespVerifierAndFatalNakNotifierTest extends AnyFunSuite {
  val busWidth = BusWidth.W512
  val pmtuLen = PMTU.U256
  val maxFragNum = 37

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new ReadAtomicRespVerifierAndFatalNakNotifier(busWidth))

  test("ReadAtomicRespVerifierAndFatalNakNotifier normal read response case") {
    testReadRespFunc(addrCacheQuerySuccess = true)
  }

  test(
    "ReadAtomicRespVerifierAndFatalNakNotifier read response AddrCache query failure case"
  ) {
    testReadRespFunc(addrCacheQuerySuccess = false)
  }

  def testReadRespFunc(addrCacheQuerySuccess: Boolean): Unit =
    simCfg.doSim(1905820794) { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.txQCtrl.wrongStateFlush #= false

      val readRespPktFragQueue = mutable.Queue[
        (
            PSN,
            VirtualAddr,
            WorkReqId,
            PsnStart,
            OpCode.Value,
            PktFragData,
            FragLast
        )
      ]()
      val inputWorkReqAndAckQueue =
        mutable.Queue[
          (PSN, VirtualAddr, WorkReqId, SpinalEnumElement[WorkCompStatus.type])
        ]()
      val outputWorkReqAndAckQueue = mutable.Queue[
        (PSN, VirtualAddr, WorkReqId, SpinalEnumElement[WorkCompStatus.type])
      ]()

      val inputReadResp4DmaQueue =
        mutable.Queue[(PSN, PhysicalAddr, WorkReqId, PktFragData, FragLast)]()
      val outputReadResp4DmaQueue =
        mutable.Queue[(PSN, PhysicalAddr, WorkReqId, PktFragData, FragLast)]()

      RdmaDataPktSim.readRespPktFragStreamMasterDriverAlwaysValid2(
        dut.clockDomain,
        dut.io.cachedWorkReqAndRespWithAeth,
        getRdmaPktDataFunc =
          (cachedWorkReqAndRespWithAeth: CachedWorkReqAndRespWithAeth) =>
            cachedWorkReqAndRespWithAeth.pktFrag,
        pmtuLen = pmtuLen,
        busWidth = busWidth,
        maxFragNum = maxFragNum
      ) {
        (
            _, // psn,
            psnStart,
            _, // fragLast,
            _, // fragIdx,
            _, // pktFragNum,
            _, // pktIdx,
            _, // pktNum,
            _, // payloadLenBytes,
            _, // headerLenBytes,
            _ // opcode
        ) =>
          dut.io.cachedWorkReqAndRespWithAeth.cachedWorkReq.psnStart #= psnStart
          dut.io.cachedWorkReqAndRespWithAeth.workCompStatus #= WorkCompStatus.SUCCESS
          dut.io.cachedWorkReqAndRespWithAeth.aeth.setAsNormalAck()
      }
      onStreamFire(dut.io.cachedWorkReqAndRespWithAeth, dut.clockDomain) {
        val isLastFrag = dut.io.cachedWorkReqAndRespWithAeth.last.toBoolean
        val opcode = OpCode(
          dut.io.cachedWorkReqAndRespWithAeth.pktFrag.bth.opcodeFull.toInt
        )
        readRespPktFragQueue.enqueue(
          (
            dut.io.cachedWorkReqAndRespWithAeth.pktFrag.bth.psn.toInt,
            dut.io.cachedWorkReqAndRespWithAeth.cachedWorkReq.workReq.laddr.toBigInt,
            dut.io.cachedWorkReqAndRespWithAeth.cachedWorkReq.workReq.id.toBigInt,
            dut.io.cachedWorkReqAndRespWithAeth.cachedWorkReq.psnStart.toInt,
            opcode,
            dut.io.cachedWorkReqAndRespWithAeth.pktFrag.data.toBigInt,
            isLastFrag
          )
        )
      }

      val addrCacheRespQueue = if (addrCacheQuerySuccess) {
        AddrCacheSim.alwaysStreamFireAndRespSuccess(
          dut.io.addrCacheRead,
          dut.clockDomain
        )
      } else {
        AddrCacheSim.alwaysStreamFireAndRespFailure(
          dut.io.addrCacheRead,
          dut.clockDomain
        )
      }
      fork {
        var physicalAddr = BigInt(0)
        var isFirstFrag = true
        while (true) {
          val (
            psn,
            laddr,
            workReqId,
            psnStart,
            opcode,
            pktFragData,
            isLastFrag
          ) = MiscUtils.safeDeQueue(readRespPktFragQueue, dut.clockDomain)

          if (opcode.isFirstOrOnlyReadRespPkt() && isFirstFrag) {
            val (psnQuery, _, _, _, pa) =
              MiscUtils.safeDeQueue(addrCacheRespQueue, dut.clockDomain)
            physicalAddr = pa
            psnStart shouldBe psnQuery withClue f"${simTime()} time: psnStart=${psnStart} should == psnQuery=${psnQuery}"
          }

          if (isLastFrag) {
            isFirstFrag = true
          } else {
            isFirstFrag = false
          }

          val expectedWorkCompStatus = if (addrCacheQuerySuccess) {
            WorkCompStatus.SUCCESS
          } else {
            WorkCompStatus.LOC_LEN_ERR
          }

          inputWorkReqAndAckQueue.enqueue(
            (
              psnStart,
              laddr,
              workReqId,
              expectedWorkCompStatus
            )
          )
//          println(f"${simTime()} time: inputWorkReqAndAckQueue.size=${inputWorkReqAndAckQueue.size}")

          if (addrCacheQuerySuccess) {
            inputReadResp4DmaQueue.enqueue(
              (
                psn,
                physicalAddr,
                workReqId,
                pktFragData,
                isLastFrag
              )
            )
//            println(f"${simTime()} time: inputReadResp4DmaQueue.size=${inputReadResp4DmaQueue.size}")
          }
        }
      }

      streamSlaveAlwaysReady(dut.io.cachedWorkReqAndAck, dut.clockDomain)
      onStreamFire(dut.io.cachedWorkReqAndAck, dut.clockDomain) {
        outputWorkReqAndAckQueue.enqueue(
          (
            dut.io.cachedWorkReqAndAck.cachedWorkReq.psnStart.toInt,
            dut.io.cachedWorkReqAndAck.cachedWorkReq.workReq.laddr.toBigInt,
            dut.io.cachedWorkReqAndAck.cachedWorkReq.workReq.id.toBigInt,
            dut.io.cachedWorkReqAndAck.workCompStatus.toEnum
          )
        )
//        println(f"${simTime()} time: outputWorkReqAndAckQueue.size=${outputWorkReqAndAckQueue.size}")
      }

      streamSlaveAlwaysReady(
        dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo,
        dut.clockDomain
      )
      onStreamFire(
        dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo,
        dut.clockDomain
      ) {
        outputReadResp4DmaQueue.enqueue(
          (
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.bth.psn.toInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pa.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.workReqId.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.pktFrag.data.toBigInt,
            dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.last.toBoolean
          )
        )
//        println(f"${simTime()} time: outputReadResp4DmaQueue.size=${outputReadResp4DmaQueue.size}")
      }

      if (addrCacheQuerySuccess) {
        MiscUtils.checkInputOutputQueues(
          dut.clockDomain,
          inputReadResp4DmaQueue,
          outputReadResp4DmaQueue,
          MATCH_CNT
        )
        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.errNotifier.pulse.toBoolean
        )
      } else {
        dut.io.errNotifier.pulse.toBoolean shouldBe dut.io.addrCacheRead.resp.valid.toBoolean withClue
          f"${simTime()} time, dut.io.errNotifier.pulse=${dut.io.errNotifier.pulse.toBoolean} should == dut.io.addrCacheRead.resp.valid=${dut.io.addrCacheRead.resp.valid.toBoolean}, when ${addrCacheQuerySuccess}"

        MiscUtils.checkConditionAlways(dut.clockDomain)(
          !dut.io.readAtomicRespWithDmaInfoBus.respWithDmaInfo.valid.toBoolean
        )
      }

      MiscUtils.checkInputOutputQueues(
        dut.clockDomain,
        inputWorkReqAndAckQueue,
        outputWorkReqAndAckQueue,
        MATCH_CNT
      )
    }

  // TODO: test("ReadAtomicRespVerifierAndFatalNakNotifier error flush test")
  /*
  test("ReadAtomicRespVerifierAndFatalNakNotifier fatal NAK test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.txQCtrl.wrongStateFlush #= false
      dut.io.txQCtrl.errorFlush #= false
      dut.io.txQCtrl.retryFlush #= false

      val matchQueue = mutable.Queue[PSN]()

      dut.io.addrCacheRead.req.ready #= false
      dut.io.addrCacheRead.resp.valid #= false

      streamMasterDriver(dut.io.cachedWorkReqAndRespWithAeth, dut.clockDomain) {
        val normalAckOrFatalNakType = AckTypeSim.randomFatalNak()
        dut.io.cachedWorkReqAndRespWithAeth.respWithAeth.pktFrag.bth
          .setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.cachedWorkReqAndRespWithAeth.respWithAeth.aeth.setAs(normalAckOrFatalNakType)
      }
      onStreamFire(dut.io.cachedWorkReqAndRespWithAeth, dut.clockDomain) {
        val ackType = AckTypeSim.decodeFromAeth(dut.io.cachedWorkReqAndRespWithAeth.respWithAeth.aeth)
        if (AckTypeSim.isFatalNak(ackType)) {
          assert(
            dut.io.errNotifier.pulse.toBoolean,
            f"${simTime()} time: dut.io.errNotifier.pulse=${dut.io.errNotifier.pulse.toBoolean} should be true when ackType=${ackType}"
          )

          dut.io.errNotifier.errType.toEnum shouldNot be(SqErrType.NO_ERR) withClue
            f"${simTime()} time: dut.io.errNotifier.errType=${dut.io.errNotifier.errType.toEnum} should not be ${SqErrType.NO_ERR} when ackType=${ackType}"

          matchQueue.enqueue(dut.io.cachedWorkReqAndRespWithAeth.respWithAeth.pktFrag.bth.psn.toInt)
        }
      }
      streamSlaveRandomizer(dut.io.cachedWorkReqAndAck, dut.clockDomain)

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
        val queryPsn = dut.io.workReqQuery.req.queryPsn.toInt
        workReqQueryReqQueue.enqueue(queryPsn)

//        println(
//          f"${simTime()} time: dut.io.workReqQuery.req received queryPsn=${queryPsn}%X"
//        )
      } {
        val psn = workReqQueryReqQueue.dequeue()
        dut.io.workReqQuery.resp.queryKey.queryPsn #= psn
        dut.io.workReqQuery.resp.respValue.psnStart #= psn
        dut.io.workReqQuery.resp.found #= true

//        println(f"${simTime()} time: dut.io.workReqQuery.resp PSN=${psn}%X")
      }
      onStreamFire(dut.io.workReqQuery.resp, dut.clockDomain) {
        workReqQueryRespQueue.enqueue(
          (
            dut.io.workReqQuery.resp.queryKey.queryPsn.toInt,
            dut.io.workReqQuery.resp.respValue.workReq.laddr.toBigInt,
            dut.io.workReqQuery.resp.respValue.workReq.id.toBigInt
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

          psnStartInReadRespMeta shouldBe psnStartInWorkReqQueryResp withClue
            f"${simTime()} time: psnStartInReadRespMeta=${psnStartInReadRespMeta}%X should equal psnStartInWorkReqQueryResp=${psnStartInWorkReqQueryResp}%X"

          psnStartInWorkReqQueryResp shouldBe psnStartInAddrCacheResp withClue
            f"${simTime()} time: psnStartInWorkReqQueryResp=${psnStartInWorkReqQueryResp}%X should equal psnStartInAddrCacheResp=${psnStartInAddrCacheResp}%X"

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

            psnIn shouldBe psnInReadResp withClue
              f"${simTime()} time: psnIn=${psnIn}%X should equal psnInReadResp=${psnInReadResp}%X"

            psnInReadResp shouldBe psnInDmaWriteReq withClue
              f"${simTime()} time: psnInReadResp=${psnInReadResp}%X should equal psnInDmaWriteReq=${psnInDmaWriteReq}%X"

            phyAddrOutDmaWriteReq shouldBe phyAddrInAddrCacheResp withClue
              f"${simTime()} time: phyAddrInAddrCacheResp=${phyAddrInAddrCacheResp}%X == phyAddrOutDmaWriteReq=${phyAddrOutDmaWriteReq}%X"

            workReqIdOutDmaWriteReq shouldBe workReqIdInWorkReqQueryResp withClue
              f"${simTime()} time: workReqIdInWorkReqQueryResp=${workReqIdInWorkReqQueryResp}%X == workReqIdOutDmaWriteReq=${workReqIdOutDmaWriteReq}%X"

            dataOutDmaWriteReq shouldBe dataInReadResp withClue
              f"${simTime()} time: dataInReadResp=${dataInReadResp}%X should equal dataOutDmaWriteReq=${dataOutDmaWriteReq}%X"

            isLastOutDmaWriteReq shouldBe isLastInReadResp withClue
              f"${simTime()} time: isLastInReadResp=${isLastInReadResp} should equal isLastOutDmaWriteReq=${isLastOutDmaWriteReq}"
          }
          matchQueue.enqueue(psnStartInReadRespMeta)
//          println(f"${simTime()} time: ${matchQueue.size} matches")
        }
      }
      waitUntil(matchQueue.size > MATCH_CNT)
    }
  }
   */
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
        !dut.io.atomicRespDmaWriteReq.req.valid.toBoolean
      )

      streamSlaveRandomizer(dut.io.atomicRespDmaWriteReq.req, dut.clockDomain)
      MiscUtils.checkConditionAlways(dut.clockDomain)(
        !dut.io.atomicRespDmaWriteReq.req.valid.toBoolean
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
