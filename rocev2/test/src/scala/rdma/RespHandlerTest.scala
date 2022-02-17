package rdma

import spinal.core._
import spinal.core.sim._
import ConstantSettings._
import StreamSimUtil._
import AethSim._
import BthSim._
import TypeReDef._

import scala.collection.mutable
import org.scalatest.funsuite.AnyFunSuite

class CoalesceAndNormalAndRetryNakHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(
      new SpinalConfig(defaultClockDomainFrequency = FixedFrequency(200 MHz))
    )
    .compile(new CoalesceAndNormalAndRetryNakHandler)
  /*
  test("CoalesceAndNormalAndRetryNakHandler coalesce ACK test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.qpAttr.dqpn #= 11
      dut.io.qpAttr.respTimeOut #= 0 // Disable response timeout retry

      // TODO: change flush signal accordingly
      dut.io.sendQCtrl.wrongStateFlush #= false
      dut.io.sendQCtrl.errorFlush #= false
      dut.io.sendQCtrl.retryFlush #= false

      val pmtuLen = PMTU.U1024
//      val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)
      val (_, pktNumItr, psnItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)

      val cachedWorkReqQueue = mutable.Queue[(Int, Int, Long)]()
      val explicitAckQueue = mutable.Queue[(Int, Int)]()
      val inputCachedWorkReqQueue =
        mutable.Queue[
          (Int, Int, BigInt, Long, SpinalEnumElement[WorkReqOpCode.type])
        ]()
      val inputAckQueue = mutable.Queue[Int]()
      val outputWorkCompAndAckQueue =
        mutable.Queue[
          (Int,
           SpinalEnumElement[WorkCompFlags.type],
           BigInt,
           SpinalEnumElement[WorkCompOpCode.type],
           BigInt,
           SpinalEnumElement[WorkCompStatus.type])
        ]()
      val matchQueue = mutable.Queue[Int]()

      val pendingReqNum = PENDING_REQ_NUM
      val matchCnt = MATCH_CNT
      for (idx <- 0 until pendingReqNum) {
        val pktNum = pktNumItr.next()
        val psnStart = psnItr.next()
        val lenBytes = totalLenItr.next()
        cachedWorkReqQueue.enqueue((pktNum, psnStart, lenBytes))
//        println(
//          f"${simTime()} time: cachedWorkReqQueue enqueue: pktNum=${pktNum}%X, psnStart=${psnStart}%X, lenBytes=${lenBytes}%X"
//        )
        if ((idx % pendingReqNum) == (pendingReqNum - 1)) {
          explicitAckQueue.enqueue((pktNum, psnStart))
//          println(
//            f"${simTime()} time: explicitAckQueue enqueue: pktNum=${pktNum}%X, psnStart=${psnStart}%X"
//          )
        }
      }
      fork {
        while (true) {
//          waitUntil(cachedWorkReqQueue.size < (PENDING_REQ_NUM / 2))
          waitUntil(explicitAckQueue.isEmpty)

          for (idx <- 0 until pendingReqNum) {
            val pktNum = pktNumItr.next()
            val psnStart = psnItr.next()
            val lenBytes = totalLenItr.next()
            cachedWorkReqQueue.enqueue((pktNum, psnStart, lenBytes))
            if ((idx % pendingReqNum) == (pendingReqNum - 1)) {
              explicitAckQueue.enqueue((pktNum, psnStart))
//              println(
//                f"${simTime()} time: explicitAckQueue enqueue: pktNum=${pktNum}=${pktNum}%X, psnStart=${psnStart}=${psnStart}%X"
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
        val (pktNum, psnStart) = explicitAckQueue.dequeue()
        val ackPsn = (psnStart + pktNum - 1) % TOTAL_PSN
        // NOTE: if PSN comparison is involved, it must update nPSN too
        // ACK PSN always larger than current CacheWorkReq PSN
        dut.io.qpAttr.npsn #= ackPsn
        dut.io.rx.bth.psn #= ackPsn
        dut.io.rx.bth.setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.rx.aeth.setAsNormalAck()
//        println(
//          f"${simTime()} time: ACK psn=${ackPsn}=${ackPsn}%X, psnStart=${psnStart}=${psnStart}%X, pktNum=${pktNum}=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx, dut.clockDomain) {
        inputAckQueue.enqueue(dut.io.rx.bth.psn.toInt)
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

//      dut.clockDomain.waitSampling(10000)
      fork {
        while (true) {
          val ackPsnIn = MiscUtils.safeDeQueue(inputAckQueue, dut.clockDomain)
//          println(f"${simTime()} time: ACK psnIn=${ackPsnIn}=${ackPsnIn}%X")

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
            val workReqEndPsn = (psnStartInIn + pktNumIn - 1) % TOTAL_PSN
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
            assert(
              workCompStatusOut == WorkCompStatus.SUCCESS,
              f"${simTime()} time: workCompStatusOut=${workCompStatusOut} should be WorkCompStatus.SUCCESS"
            )

//            needCoalesceAck = MiscUtils.psnCmp(
//              workReqEndPsn,
//              ackPsnIn,
//              ackPsnIn
//            ) < 0
            needCoalesceAck = workReqEndPsn != ackPsnIn
          } while (needCoalesceAck)
          matchQueue.enqueue(ackPsnIn)
        }
      }
      waitUntil(matchQueue.size > matchCnt)
    }
  }
   */
  test("CoalesceAndNormalAndRetryNakHandler duplicate and ghost ACK test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.qpAttr.dqpn #= 11
      dut.io.qpAttr.respTimeOut #= 0 // Disable response timeout retry

      // TODO: change flush signal accordingly
      dut.io.sendQCtrl.wrongStateFlush #= false
      dut.io.sendQCtrl.errorFlush #= false
      dut.io.sendQCtrl.retryFlush #= false

      val pmtuLen = PMTU.U256
      val (_, pktNumItr, psnItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)

      val dupOrGhostAckQueue = mutable.Queue[(Int, Int)]()
      val inputAckQueue = mutable.Queue[Int]()

      val pendingReqNum = PENDING_REQ_NUM
      val matchCnt = MATCH_CNT

      val pktNum4CachedWorkReq = pktNumItr.next()
      val psnStart4CachedWorkReq = psnItr.next()
      val lenBytes4CachedWorkReq = totalLenItr.next()
      for (_ <- 0 until pendingReqNum) {
        val pktNum = pktNumItr.next()
        val psnStart = psnItr.next()
        val _ = totalLenItr.next()
        dupOrGhostAckQueue.enqueue((pktNum, psnStart))
      }
      fork {
        while (true) {
          waitUntil(dupOrGhostAckQueue.isEmpty)

          for (_ <- 0 until pendingReqNum) {
            val pktNum = pktNumItr.next()
            val psnStart = psnItr.next()
            val _ = totalLenItr.next()
            dupOrGhostAckQueue.enqueue((pktNum, psnStart))
          }
        }
      }

      streamMasterDriver(dut.io.cachedWorkReqPop, dut.clockDomain) {
        val randOpCode = WorkReqSim.randomSendWriteOpCode()
        dut.io.cachedWorkReqPop.workReq.opcode #= randOpCode
        // NOTE: if PSN comparison is involved, it must update nPSN too
        // CacheWorkReq PSN always larger than ACK PSN
        dut.io.qpAttr.npsn #= pktNum4CachedWorkReq
        dut.io.cachedWorkReqPop.pktNum #= pktNum4CachedWorkReq
        dut.io.cachedWorkReqPop.psnStart #= psnStart4CachedWorkReq
        dut.io.cachedWorkReqPop.workReq.lenBytes #= lenBytes4CachedWorkReq
      }
      // io.cachedWorkReqPop never fire
      MiscUtils.checkConditionAlways(dut.clockDomain)(
//        !dut.io.cachedWorkReqPop.ready.toBoolean
        !(dut.io.cachedWorkReqPop.valid.toBoolean && dut.io.cachedWorkReqPop.ready.toBoolean)
      )

      streamMasterDriver(dut.io.rx, dut.clockDomain) {
        val (pktNum, psnStart) = dupOrGhostAckQueue.dequeue()
        val ackPsn = (psnStart + pktNum - 1) % TOTAL_PSN
        dut.io.rx.bth.psn #= ackPsn
        dut.io.rx.bth.setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.rx.aeth.setAsNormalAck()
//        println(
//          f"${simTime()} time: ACK psn=${ackPsn}=${ackPsn}%X, psnStart=${psnStart}=${psnStart}%X, pktNum=${pktNum}=${pktNum}%X"
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
      dut.io.sendQCtrl.wrongStateFlush #= false
      dut.io.sendQCtrl.errorFlush #= false
      dut.io.sendQCtrl.retryFlush #= false

      val pmtuLen = PMTU.U1024
      val (_, pktNumItr, psnItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(pmtuLen, busWidth)

      val cachedWorkReqQueue = mutable.Queue[(Int, Int, Int)]()
      val explicitAckQueue =
        mutable.Queue[(Int, Int, SpinalEnumElement[AckType.type])]()
      val inputCachedWorkReqQueue =
        mutable.Queue[
          (Int, Int, BigInt, Long, SpinalEnumElement[WorkReqOpCode.type])
        ]()
      val inputAckQueue =
        mutable.Queue[(Int, SpinalEnumElement[AckType.type])]()
      val outputWorkCompAndAckQueue =
        mutable.Queue[
          (
              Int,
              SpinalEnumElement[WorkCompFlags.type],
              BigInt,
              SpinalEnumElement[WorkCompOpCode.type],
              BigInt,
              SpinalEnumElement[WorkCompStatus.type]
          )
        ]()
      val matchQueue = mutable.Queue[Int]()

      val pendingReqNum = PENDING_REQ_NUM
      val matchCnt = MATCH_CNT
      for (idx <- 0 until pendingReqNum) {
        val pktNum = pktNumItr.next()
        val psnStart = psnItr.next()
        val lenBytes = totalLenItr.next()
        cachedWorkReqQueue.enqueue((pktNum, psnStart, lenBytes))
//        println(
//          f"${simTime()} time: cachedWorkReqQueue enqueue: pktNum=${pktNum}%X, psnStart=${psnStart}%X, lenBytes=${lenBytes}%X"
//        )
        if ((idx % pendingReqNum) == (pendingReqNum - 1)) {
          val retryNakType = AckTypeSim.randomRetryNak()
          val normalAckOrFatalNakeType = AckTypeSim.randomNormalAckOrFatalNak()
          explicitAckQueue.enqueue((pktNum, psnStart, retryNakType))
          explicitAckQueue.enqueue((pktNum, psnStart, normalAckOrFatalNakeType))
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
            val psnStart = psnItr.next()
            val lenBytes = totalLenItr.next()
            cachedWorkReqQueue.enqueue((pktNum, psnStart, lenBytes))
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
        val ackPsn = (psnStart + pktNum - 1) % TOTAL_PSN
        // NOTE: if PSN comparison is involved, it must update nPSN too
        // ACK PSN always larger than current CacheWorkReq PSN
        dut.io.qpAttr.npsn #= ackPsn
        dut.io.rx.bth.psn #= ackPsn
        dut.io.rx.bth.setTransportAndOpCode(Transports.RC, OpCode.ACKNOWLEDGE)
        dut.io.rx.aeth.setAs(ackType)
//        println(
//          f"${simTime()} time: ACK ackType=${ackType} psn=${ackPsn}=${ackPsn}%X, psnStart=${psnStart}=${psnStart}%X, pktNum=${pktNum}=${pktNum}%X"
//        )
      }
      onStreamFire(dut.io.rx, dut.clockDomain) {
        val ackType = AckTypeSim.decodeFromAeth(dut.io.rx.aeth)
        if (AckTypeSim.isRetryNak(ackType)) {
          assert(
            dut.io.retryNotifier.pulse.toBoolean,
            f"${simTime()} time: dut.io.retryNotifier.pulse=${dut.io.retryNotifier.pulse.toBoolean} should be true when ackType=${ackType}"
          )
        } else if (AckTypeSim.isFatalNak(ackType)) {
          assert(
            dut.io.nakNotifier.pulse.toBoolean,
            f"${simTime()} time: dut.io.nakNotifier.pulse=${dut.io.nakNotifier.pulse.toBoolean} should be true when ackType=${ackType}"
          )
          assert(
            dut.io.nakNotifier.errType.toEnum != SqErrType.NO_ERR,
            f"${simTime()} time: dut.io.nakNotifier.errType=${dut.io.nakNotifier.errType.toEnum} should not be ${SqErrType.NO_ERR} when ackType=${ackType}"
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
            val workReqEndPsn = (psnStartInIn + pktNumIn - 1) % TOTAL_PSN
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
//            needCoalesceAck = MiscUtils.psnCmp(
//              workReqEndPsn,
//              ackPsnIn,
//              ackPsnIn
//            ) < 0
            needCoalesceAck = workReqEndPsn != ackPsnIn
          } while (needCoalesceAck)
          matchQueue.enqueue(ackPsnIn)
        }
      }
      waitUntil(matchQueue.size > matchCnt)
    }
  }
}

class ReadAtomicRespDmaReqInitiatorTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .withConfig(
      new SpinalConfig(defaultClockDomainFrequency = FixedFrequency(200 MHz))
    )
    .compile(new ReadAtomicRespDmaReqInitiator(busWidth))

  test("ReadAtomicRespDmaReqInitiator normal behavior test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      dut.io.qpAttr.dqpn #= 11
      dut.io.qpAttr.respTimeOut #= 0 // Disable response timeout retry

      // TODO: change flush signal accordingly
      dut.io.sendQCtrl.wrongStateFlush #= false
      dut.io.sendQCtrl.errorFlush #= false
      dut.io.sendQCtrl.retryFlush #= false

      val pmtuLen = PMTU.U256

      // Input to DUT
      val maxFragNum = 17
      val (totalFragNumItr, pktNumItr, psnItr, totalLenItr) =
        SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

      val readRespMetaDataQueue = mutable.Queue[(PSN, FragNum)]()
      val rxReadRespQueue = mutable.Queue[(PSN, RdmaFragData, FragLast)]()
      val readRespDmaWriteReqQueue =
        mutable.Queue[(PSN, Addr, WorkReqId, RdmaFragData, FragLast)]()
      val workReqQueryReqQueue = mutable.Queue[PSN]()
      val workReqQueryRespQueue = mutable.Queue[(PSN, Addr, WorkReqId)]()
      val addrCacheReadReqQueue = mutable.Queue[(PSN, Addr)]()
      val addrCacheReadRespQueue = mutable.Queue[(PSN, Addr)]()
      val matchQueue = mutable.Queue[PSN]()

      pktFragStreamMasterDriver(dut.io.rx.pktFrag, dut.clockDomain) {
        val totalFragNum = totalFragNumItr.next()
        val pktNum = pktNumItr.next()
        val psnStart = psnItr.next()
        val totalLenBytes = totalLenItr.next()
        val outerLoopRslt = (pktNum, totalLenBytes)

//        println(
//          f"${simTime()} time: pktNum=${pktNum}, totalFragNum=${totalFragNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}"
//        )

        readRespMetaDataQueue.enqueue((psnStart, totalFragNum))
        (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, outerLoopRslt)
      } { (psn, isLast, pktIdx, pktNum, _) =>
//        val (pktNum, totalLenBytes) = outerLoopRslt

        // Only RC is supported
//        dut.io.rx.pktFrag.bth.transport #= Transports.RC.id
        if (pktNum == 1) {
          dut.io.rx.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_ONLY.id
        } else if (pktIdx == 0) {
          dut.io.rx.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_FIRST.id
        } else if (pktIdx == pktNum - 1) {
          dut.io.rx.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_LAST.id
        } else {
          dut.io.rx.pktFrag.bth.opcodeFull #= OpCode.RDMA_READ_RESPONSE_MIDDLE.id
        }
        dut.io.rx.pktFrag.bth.psn #= psn
        dut.io.rx.pktFrag.last #= isLast
//        println(
//          f"${simTime()} time: fragIdx=${fragIdx}, fragNum=${fragNum}, isLastInputFrag=${isLastInputFrag}, isLastFragPerPkt=${isLastFragPerPkt}, isLast=${isLast}, totalLenBytes=${totalLenBytes}, pktNum=${pktNum}, mtyWidth=${mtyWidth}, residue=${totalLenBytes % mtyWidth}, mty=${mty}%X"
//        )
      }
      onStreamFire(dut.io.rx.pktFrag, dut.clockDomain) {
//        println(f"${simTime()} time: dut.io.rx.pktFrag.bth.psn=${dut.io.rx.pktFrag.bth.psn.toInt}")
        rxReadRespQueue.enqueue(
          (
            dut.io.rx.pktFrag.bth.psn.toInt,
            dut.io.rx.pktFrag.data.toBigInt,
            dut.io.rx.pktFrag.last.toBoolean
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

//        println(f"${simTime()} time: dut.io.workReqQuery.resp psn=${psn}%X")
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
//          f"${simTime()} time: dut.io.addrCacheRead.req received psn=${dut.io.addrCacheRead.req.psn.toInt}%X"
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
//          f"${simTime()} time: dut.io.addrCacheRead.resp psn=${dut.io.addrCacheRead.resp.psn.toInt}%X"
//        )
      }

      streamSlaveRandomizer(dut.io.readRespDmaWriteReq.req, dut.clockDomain)
      onStreamFire(dut.io.readRespDmaWriteReq.req, dut.clockDomain) {
        readRespDmaWriteReqQueue.enqueue(
          (
            dut.io.readRespDmaWriteReq.req.psn.toInt,
            dut.io.readRespDmaWriteReq.req.addr.toBigInt,
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
            ) = MiscUtils.safeDeQueue(readRespDmaWriteReqQueue, dut.clockDomain)

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
