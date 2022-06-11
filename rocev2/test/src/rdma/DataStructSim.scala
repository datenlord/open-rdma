package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import ConstantSettings._
import RdmaConstants._
import RdmaTypeReDef._
import StreamSimUtil._
import SimSettings._

import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._
import scala.collection.mutable

object RdmaTypeReDef {
  type PhysicalAddr = BigInt
  type VirtualAddr = BigInt
  type FragIdx = Int
  type FragLast = Boolean
  type FragNum = Int
  type HeaderLen = Int // Length in bytes
  type LRKey = Long
  type MTY = BigInt
  type PadCnt = Int
  type PktIdx = Int
  type PktLen = Long // Length in bytes
  type PktNum = Int
  type ReqPktNum = Int
  type RespPktNum = Int
//  type PktLast = Boolean
  type PSN = Int
  type PsnStart = Int
  type PsnEnd = Int
  type PsnExpected = Int
  type PsnNext = Int
  type QueryPsn = Int
  type QuerySuccess = Boolean
  type DQPN = Int
  type SQPN = Int
  type PktFragData = BigInt
  type WorkReqId = BigInt
  type WorkReqFlags = Int
  type WorkReqValid = Boolean
  type WidthBytes = Int

  type AckReq = Boolean
  type RxBufValid = Boolean
  type HasNak = Boolean
  type HasNakSeq = Boolean
  type DupReq = Boolean
  type KeyValid = Boolean
  type SizeValid = Boolean
  type AccessValid = Boolean
  type AccessPermissionType = Int

  type AethRsvd = Int
  type AethCode = Int
  type AethValue = Int
  type AethMsn = Int

  type AtomicComp = BigInt
  type AtomicSwap = BigInt
  type AtomicOrig = BigInt

  type RnrCnt = Int
  type RetryCnt = Int

  type SimTimeStamp = Long
}

object PsnSim {
  implicit def build(that: PSN) = new PsnSim(that)

  def psnCmp(psnA: PSN, psnB: PSN, curPsn: PSN): Int = {
    require(
      psnA >= 0 && psnB >= 0 && curPsn >= 0,
      s"${simTime()} time: psnA=${psnA}, psnB=${psnB}, curPsn=${curPsn} should all >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN && curPsn < TOTAL_PSN,
      s"${simTime()} time: psnA=${psnA}, psnB=${psnB}, curPsn=${curPsn} should all < TOTAL_PSN=${TOTAL_PSN}"
    )
    val oldestPSN = (curPsn - HALF_MAX_PSN) & PSN_MASK

    if (psnA == psnB) {
      0
    } else if (psnA < psnB) {
      if (oldestPSN <= psnA) {
        -1 // LESSER
      } else if (psnB <= oldestPSN) {
        -1 // LESSER
      } else {
        1 // GREATER
      }
    } else { // psnA > psnB
      if (psnA <= oldestPSN) {
        1 // GREATER
      } else if (oldestPSN <= psnB) {
        1 // GREATER
      } else {
        -1 // LESSER
      }
    }
  }

  /** psnA - psnB, PSN diff is always <= HALF_MAX_PSN
    */
  def psnDiff(psnA: PSN, psnB: PSN): PSN = {
    require(
      psnA >= 0 && psnB >= 0,
      s"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN,
      s"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both < TOTAL_PSN=${TOTAL_PSN}"
    )
    val diff = (psnA + TOTAL_PSN) -% psnB
//    val (min, max) = if (psnA > psnB) {
//      (psnB, psnA)
//    } else {
//      (psnA, psnB)
//    }
//    val diff = max - min
    if (diff > HALF_MAX_PSN) {
      TOTAL_PSN - diff
    } else {
      diff
    }
  }

//  /** psnA + psnB, modulo by TOTAL_PSN
//    */
//  def psnAdd(psnA: Int, psnB: Int): Int = {
//    require(
//      psnA >= 0 && psnB >= 0,
//      s"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both >= 0"
//    )
//    require(
//      psnA < TOTAL_PSN && psnB < TOTAL_PSN,
//      s"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both < TOTAL_PSN=${TOTAL_PSN}"
//    )
//    (psnA + psnB) % TOTAL_PSN
//  }
}

class PsnSim(val psn: PSN) {
//  require(
//    psn >= 0 && psn < TOTAL_PSN,
//    s"${simTime()} time: PSN value PSN=${psn}%X must >= 0 and < TOTAL_PSN=${TOTAL_PSN}%X"
//  )

  def +%(that: PSN): PSN = {
    require(
      that >= 0 && that < TOTAL_PSN,
      s"${simTime()} time: PSN value that=${that}%X must >= 0 and < TOTAL_PSN=${TOTAL_PSN}%X"
    )

    (psn + that) % TOTAL_PSN
  }

  def -%(that: PSN): PSN = {
//    require(
//      that >= 0 && that < TOTAL_PSN,
//      s"${simTime()} time: PSN value that=${that}%X must >= 0 and < TOTAL_PSN=${TOTAL_PSN}%X"
//    )

    (TOTAL_PSN + psn - that) % TOTAL_PSN
  }
}

object WorkCompSim {
  def fromSqWorkReqOpCode(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
  ): SpinalEnumElement[WorkCompOpCode.type] = {
    // TODO: check WR opcode without WC opcode equivalent
    //    val TM_ADD = Value(130)
    //    val TM_DEL = Value(131)
    //    val TM_SYNC = Value(132)
    //    val TM_RECV = Value(133)
    //    val TM_NO_TAG = Value(134)
    workReqOpCode match {
      case WorkReqOpCode.RDMA_WRITE | WorkReqOpCode.RDMA_WRITE_WITH_IMM =>
        WorkCompOpCode.RDMA_WRITE
      case WorkReqOpCode.SEND | WorkReqOpCode.SEND_WITH_IMM |
          WorkReqOpCode.SEND_WITH_INV =>
        WorkCompOpCode.SEND
      case WorkReqOpCode.RDMA_READ =>
        WorkCompOpCode.RDMA_READ
      case WorkReqOpCode.ATOMIC_CMP_AND_SWP =>
        WorkCompOpCode.COMP_SWAP
      case WorkReqOpCode.ATOMIC_FETCH_AND_ADD =>
        WorkCompOpCode.FETCH_ADD
      case WorkReqOpCode.LOCAL_INV =>
        WorkCompOpCode.LOCAL_INV
      case WorkReqOpCode.BIND_MW =>
        WorkCompOpCode.BIND_MW
      case WorkReqOpCode.TSO =>
        WorkCompOpCode.TSO
      case WorkReqOpCode.DRIVER1 =>
        WorkCompOpCode.DRIVER1
      case _ =>
        SpinalExit(
          s"${simTime()} time: no matched WC opcode at SQ side for WR opcode=${workReqOpCode} when in simulation"
        )
    }
  }

  def rqCheckWorkCompStatus(
      ackType: SpinalEnumElement[AckType.type],
      workCompStatus: SpinalEnumElement[WorkCompStatus.type]
  ): Unit = {
    val matchStatus = ackType match {
      case AckType.NORMAL      => WorkCompStatus.SUCCESS
      case AckType.NAK_INV     => WorkCompStatus.REM_INV_REQ_ERR
      case AckType.NAK_RMT_ACC => WorkCompStatus.REM_ACCESS_ERR
      case AckType.NAK_RMT_OP  => WorkCompStatus.REM_OP_ERR
      case _ =>
        SpinalExit(
          s"${simTime()} time: invalid AckType=${ackType} to match WorkCompStatus"
        )
    }

    workCompStatus shouldBe matchStatus withClue
      f"${simTime()} time: workCompStatus=${workCompStatus} not match expected matchStatus=${matchStatus}"
  }

  def rqCheckWorkCompOpCode(
      reqOpCode: OpCode.Value,
      workCompOpCode: SpinalEnumElement[WorkCompOpCode.type]
  ): Unit = {
    val matchOpCode = reqOpCode match {
      case OpCode.SEND_FIRST | OpCode.SEND_MIDDLE | OpCode.SEND_LAST |
          OpCode.SEND_LAST_WITH_IMMEDIATE | OpCode.SEND_LAST_WITH_INVALIDATE |
          OpCode.SEND_ONLY | OpCode.SEND_ONLY_WITH_IMMEDIATE |
          OpCode.SEND_ONLY_WITH_INVALIDATE =>
        WorkCompOpCode.RECV
      case OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE |
          OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE =>
        WorkCompOpCode.RECV_RDMA_WITH_IMM
      case _ =>
        SpinalExit(
          s"${simTime()} time: RQ side WC opcode no match for request opcode=${reqOpCode}"
        )
    }
//    println(
//      f"${simTime()} time: RQ side workCompOpCode=${workCompOpCode} not match expected matchOpCode=${matchOpCode}, workReqOpCode=${workReqOpCode}"
//    )

    workCompOpCode shouldBe matchOpCode withClue
      f"${simTime()} time: workCompOpCode=${workCompOpCode} not match expected matchOpCode=${matchOpCode}, reqOpCode=${reqOpCode}"
  }

  def sqCheckWorkCompOpCode(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type],
      workCompOpCode: SpinalEnumElement[WorkCompOpCode.type]
  ): Unit = {
    val matchOpCode = workReqOpCode match {
      case WorkReqOpCode.SEND | WorkReqOpCode.SEND_WITH_IMM |
          WorkReqOpCode.SEND_WITH_INV =>
        WorkCompOpCode.SEND
      case WorkReqOpCode.RDMA_WRITE | WorkReqOpCode.RDMA_WRITE_WITH_IMM =>
        WorkCompOpCode.RDMA_WRITE
      case WorkReqOpCode.RDMA_READ => WorkCompOpCode.RDMA_READ
      case WorkReqOpCode.ATOMIC_CMP_AND_SWP =>
        WorkCompOpCode.COMP_SWAP
      case WorkReqOpCode.ATOMIC_FETCH_AND_ADD =>
        WorkCompOpCode.FETCH_ADD
      case _ =>
        SpinalExit(
          s"${simTime()} time: SQ check WR opcode=${workReqOpCode} not match WC opcode=${workCompOpCode}"
        )
    }
//    println(
//      f"${simTime()} time: SQ side workCompOpCode=${workCompOpCode} not match expected matchOpCode=${matchOpCode}, workReqOpCode=${workReqOpCode}"
//    )

    workCompOpCode shouldBe matchOpCode withClue
      f"${simTime()} time: workCompOpCode=${workCompOpCode} not match expected matchOpCode=${matchOpCode}, workReqOpCode=${workReqOpCode}"
  }

  def sqCheckWorkCompFlag(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type],
      workCompFlags: SpinalEnumElement[WorkCompFlags.type]
  ): Unit = {
    val matchFlag = workReqOpCode match {
      case WorkReqOpCode.RDMA_WRITE_WITH_IMM | WorkReqOpCode.SEND_WITH_IMM =>
        WorkCompFlags.WITH_IMM
      case WorkReqOpCode.SEND_WITH_INV =>
        WorkCompFlags.WITH_INV
      case WorkReqOpCode.SEND | WorkReqOpCode.RDMA_WRITE |
          WorkReqOpCode.RDMA_READ | WorkReqOpCode.ATOMIC_CMP_AND_SWP |
          WorkReqOpCode.ATOMIC_FETCH_AND_ADD =>
        WorkCompFlags.NO_FLAGS
      case _ =>
        SpinalExit(
          s"${simTime()} time: SQ check WR opcode=${workReqOpCode} not match WC flags=${workCompFlags}"
        )
    }
//    println(
//      f"${simTime()} time: SQ side workCompFlags=${workCompFlags} not match expected matchFlag=${matchFlag}, workReqOpCode=${workReqOpCode}"
//    )

    workCompFlags shouldBe matchFlag withClue
      f"${simTime()} time: workCompFlags=${workCompFlags} not match expected matchFlag=${matchFlag}, workReqOpCode=${workReqOpCode}"
  }
}

object WorkReqSim {
  implicit class WorkReqOpCodeExt(
      val workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
  ) {
    def isSendReq(): Boolean = {
      workReqSend.contains(workReqOpCode)
    }

    def isWriteReq(): Boolean = {
      workReqWrite.contains(workReqOpCode)
    }

    def isReadReq(): Boolean = {
      workReqRead.contains(workReqOpCode)
    }

    def isAtomicReq(): Boolean = {
      workReqAtomic.contains(workReqOpCode)
    }
  }

  val workReqSend = Seq(
    WorkReqOpCode.SEND,
    WorkReqOpCode.SEND_WITH_IMM,
    WorkReqOpCode.SEND_WITH_INV
  )

  val workReqWrite =
    Seq(WorkReqOpCode.RDMA_WRITE, WorkReqOpCode.RDMA_WRITE_WITH_IMM)

  val workReqRead = Seq(WorkReqOpCode.RDMA_READ)

  val workReqAtomic =
    Seq(WorkReqOpCode.ATOMIC_CMP_AND_SWP, WorkReqOpCode.ATOMIC_FETCH_AND_ADD)

//  def isSendReq(
//      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
//  ): Boolean = {
//    workReqSend.contains(workReqOpCode)
//  }
//
//  def isWriteReq(
//      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
//  ): Boolean = {
//    workReqWrite.contains(workReqOpCode)
//  }
//
//  def isReadReq(
//      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
//  ): Boolean = {
//    workReqRead.contains(workReqOpCode)
//  }
//
//  def isAtomicReq(
//      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
//  ): Boolean = {
//    workReqAtomic.contains(workReqOpCode)
//  }

  def randomAtomicOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = workReqAtomic
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: WR AtomicOpCode should contain ${result}"
    )
    result
  }

  def randomReadAtomicOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = WorkReqOpCode.RDMA_READ +: workReqAtomic
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: WR ReadAtomicOpCode should contain ${result}"
    )
    result
  }

  def randomSendWriteOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = workReqSend ++ workReqWrite
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: WR SendWriteOpCode should contain ${result}"
    )
    result
  }

  def randomSendWriteImmOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = workReqSend :+ WorkReqOpCode.RDMA_WRITE_WITH_IMM
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: WR SendWriteImmOpCode should contain ${result}"
    )
    result
  }

  def randomSendWriteReadOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = WorkReqOpCode.RDMA_READ +: (workReqSend ++ workReqWrite)
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: WR SendWriteReadOpCode should contain ${result}"
    )
    result
  }

  def randomSendWriteReadAtomicOpCode()
      : SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes =
      WorkReqOpCode.RDMA_READ +: (workReqSend ++ workReqWrite ++ workReqAtomic)
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: WR SendWriteReadAtomicOpCode should contain ${result}"
    )
    result
  }

  def randomDmaLength(): Long = {
    // RDMA max packet length 2GB=2^31
    scala.util.Random.nextLong(1L << (RDMA_MAX_LEN_WIDTH - 1))
  }

  def assignReqOpCode(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type],
      pktIdx: Int,
      pktNum: Int
  ): OpCode.Value = {
    val opcode = workReqOpCode match {
      case WorkReqOpCode.SEND =>
        if (pktNum == 1) {
          OpCode.SEND_ONLY
        } else if (pktIdx == 0) {
          OpCode.SEND_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.SEND_LAST
        } else {
          OpCode.SEND_MIDDLE
        }
      case WorkReqOpCode.SEND_WITH_IMM =>
        if (pktNum == 1) {
          OpCode.SEND_ONLY_WITH_IMMEDIATE
        } else if (pktIdx == 0) {
          OpCode.SEND_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.SEND_LAST_WITH_IMMEDIATE
        } else {
          OpCode.SEND_MIDDLE
        }
      case WorkReqOpCode.SEND_WITH_INV =>
        if (pktNum == 1) {
          OpCode.SEND_ONLY_WITH_INVALIDATE
        } else if (pktIdx == 0) {
          OpCode.SEND_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.SEND_LAST_WITH_INVALIDATE
        } else {
          OpCode.SEND_MIDDLE
        }
      case WorkReqOpCode.RDMA_WRITE =>
        if (pktNum == 1) {
          OpCode.RDMA_WRITE_ONLY
        } else if (pktIdx == 0) {
          OpCode.RDMA_WRITE_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.RDMA_WRITE_LAST
        } else {
          OpCode.RDMA_WRITE_MIDDLE
        }
      case WorkReqOpCode.RDMA_WRITE_WITH_IMM =>
        if (pktNum == 1) {
          OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE
        } else if (pktIdx == 0) {
          OpCode.RDMA_WRITE_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE
        } else {
          OpCode.RDMA_WRITE_MIDDLE
        }
      case _ => assignReadAtomicReqOpCode(workReqOpCode)
    }
    opcode
  }

  def assignReadAtomicReqOpCode(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
  ): OpCode.Value = {
    val opcode = workReqOpCode match {
      case WorkReqOpCode.RDMA_READ            => OpCode.RDMA_READ_REQUEST
      case WorkReqOpCode.ATOMIC_CMP_AND_SWP   => OpCode.COMPARE_SWAP
      case WorkReqOpCode.ATOMIC_FETCH_AND_ADD => OpCode.FETCH_ADD
      case _ =>
        SpinalExit(
          s"${simTime()} time: invalid WR opcode=${workReqOpCode} to assign"
        )
    }
    opcode
  }

  def assignReadRespOpCode(
      pktIdx: Int,
      pktNum: Int
  ): OpCode.Value = {
    val opcode =
      if (pktNum == 1) {
        OpCode.RDMA_READ_RESPONSE_ONLY
      } else if (pktIdx == 0) {
        OpCode.RDMA_READ_RESPONSE_FIRST
      } else if (pktIdx == pktNum - 1) {
        OpCode.RDMA_READ_RESPONSE_LAST
      } else {
        OpCode.RDMA_READ_RESPONSE_MIDDLE
      }
    opcode
  }

  def getAllHeaderLenBytes(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type],
      pktNum: Int
  ): Int = {

    val bthWidth = widthOf(BTH())
    val immDtWidth = widthOf(ImmDt())
    val iethWidth = widthOf(IETH())
    val rethWidth = widthOf(RETH())
    val atomicEthWidth = widthOf(AtomicEth())
//    val aethWidth = widthOf(AETH())
//    val atomicAckEthWidth = widthOf(AtomicAckEth())

    val allHeaderLen = workReqOpCode match {
      case WorkReqOpCode.SEND          => bthWidth * pktNum
      case WorkReqOpCode.SEND_WITH_IMM => bthWidth * pktNum + immDtWidth
      case WorkReqOpCode.SEND_WITH_INV => bthWidth * pktNum + iethWidth
      case WorkReqOpCode.RDMA_WRITE    => bthWidth * pktNum + rethWidth
      case WorkReqOpCode.RDMA_WRITE_WITH_IMM =>
        bthWidth * pktNum + rethWidth + immDtWidth
      case WorkReqOpCode.RDMA_READ => bthWidth + rethWidth
      case WorkReqOpCode.ATOMIC_CMP_AND_SWP |
          WorkReqOpCode.ATOMIC_FETCH_AND_ADD =>
        bthWidth + atomicEthWidth
      case _ =>
        SpinalExit(
          s"${simTime()} time: invalid WR opcode=${workReqOpCode} to assign"
        )
    }
    allHeaderLen / BYTE_WIDTH
  }

  def assignFlagBits(
      workReqSendFlags: SpinalEnumElement[WorkReqSendFlagEnum.type]*
  ): BigInt = {
    val result = workReqSendFlags
      .map(WorkReqSendFlagEnum.defaultEncoding.getValue(_))
      .reduce(_ | _)
    result
  }
}

object AckTypeSim {
  val retryNakTypes = Seq(AckType.NAK_RNR, AckType.NAK_SEQ)
  val fatalNakType =
    Seq(AckType.NAK_INV, AckType.NAK_RMT_ACC, AckType.NAK_RMT_OP)

  def toWorkCompStatus(
      ackType: SpinalEnumElement[AckType.type]
  ): SpinalEnumElement[WorkCompStatus.type] = {
    ackType match {
      case AckType.NAK_INV     => WorkCompStatus.REM_INV_REQ_ERR
      case AckType.NAK_RMT_ACC => WorkCompStatus.REM_ACCESS_ERR
      case AckType.NAK_RMT_OP  => WorkCompStatus.REM_OP_ERR
      case _                   => WorkCompStatus.SUCCESS
    }
  }

  def randomRetryNak(): SpinalEnumElement[AckType.type] = {
    val nakTypes = retryNakTypes
    val randIdx = scala.util.Random.nextInt(nakTypes.size)
    val result = nakTypes(randIdx)
    require(
      nakTypes.contains(result),
      s"${simTime()} time: retryNakTypes should contains ${result}"
    )
    result
  }

  def randomFatalNak(): SpinalEnumElement[AckType.type] = {
    val nakTypes = fatalNakType
    val randIdx = scala.util.Random.nextInt(nakTypes.size)
    val result = nakTypes(randIdx)
    require(
      nakTypes.contains(result),
      s"${simTime()} time: fatalNakType should contains ${result}"
    )
    result
  }

  def randomNormalAckOrFatalNak(): SpinalEnumElement[AckType.type] = {
    val ackTypes = AckType.NORMAL +: fatalNakType
    val randIdx = scala.util.Random.nextInt(ackTypes.size)
    val result = ackTypes(randIdx)
    require(
      ackTypes.contains(result),
      s"${simTime()} time: ackTypes should contains ${result}"
    )
    result
  }

  def isRnrNak(ackType: SpinalEnumElement[AckType.type]): Boolean = {
    ackType == AckType.NAK_RNR
  }

  def isNakSeq(ackType: SpinalEnumElement[AckType.type]): Boolean = {
    ackType == AckType.NAK_SEQ
  }

  def isRetryNak(ackType: SpinalEnumElement[AckType.type]): Boolean = {
    retryNakTypes.contains(ackType)
  }

  def isFatalNak(ackType: SpinalEnumElement[AckType.type]): Boolean = {
    fatalNakType.contains(ackType)
  }

  def isNormalAck(ackType: SpinalEnumElement[AckType.type]): Boolean = {
    ackType == AckType.NORMAL
  }

  private def showCodeAndValue(code: Int, value: Int): String = {
//    println(
    s"${simTime()} time: dut.io.rx.aeth.code=${code}, dut.io.rx.aeth.value=${value}"
//    )
  }

  def decodeFromCodeAndValue(
      code: AethCode,
      value: AethValue
  ): SpinalEnumElement[AckType.type] = {
    // TODO: change AethCode to SpinalEnum
    code match {
      case 0 /* AethCode.ACK.id */  => AckType.NORMAL
      case 1 /* AethCode.RNR.id */  => AckType.NAK_RNR
      case 2 /* AethCode.RSVD.id */ => SpinalExit(showCodeAndValue(code, value))
      case 3 /* AethCode.NAK.id */ => {
        value match {
          case 0 /* NakCode.SEQ.id */     => AckType.NAK_SEQ
          case 1 /* NakCode.INV.id */     => AckType.NAK_INV
          case 2 /* NakCode.RMT_ACC.id */ => AckType.NAK_RMT_ACC
          case 3 /* NakCode.RMT_OP.id */  => AckType.NAK_RMT_OP
          case 4 /* NakCode.INV_RD.id */ =>
            SpinalExit(showCodeAndValue(code, value))
          case 5 /* NakCode.RSVD.id */ =>
            SpinalExit(showCodeAndValue(code, value))
          case _ => SpinalExit(showCodeAndValue(code, value))
        }
      }
      case _ => SpinalExit(showCodeAndValue(code, value))
    }
  }

  def decodeFromAeth(aeth: AETH): SpinalEnumElement[AckType.type] = {
    val code = aeth.code.toInt
    val value = aeth.value.toInt

    decodeFromCodeAndValue(code, value)
  }
}

object AethSim {
  def extract(
      fragData: PktFragData,
      busWidth: BusWidth.Value
  ): (AethRsvd, AethCode, AethValue, AethMsn) = {
    val bthWidth = widthOf(BTH())
    val aethWidth = widthOf(AETH())
    require(
      busWidth.id >= bthWidth + aethWidth,
      s"${simTime()} time: input busWidth=${busWidth.id} should >= widthOf(BTH())=${bthWidth} + widthOf(AETH())=${aethWidth}"
    )

    val rsvdShiftAmt = busWidth.id - bthWidth - AETH_RSVD_WIDTH
    val rsvdBitMask = setAllBits(AETH_RSVD_WIDTH) << rsvdShiftAmt

    val codeShiftAmt =
      busWidth.id - bthWidth - AETH_RSVD_WIDTH - AETH_CODE_WIDTH
    val codeBitMask = setAllBits(AETH_CODE_WIDTH) << codeShiftAmt

    val valueShiftAmt =
      busWidth.id - bthWidth - AETH_RSVD_WIDTH - AETH_CODE_WIDTH - AETH_VALUE_WIDTH
    val valueBitMask = setAllBits(AETH_VALUE_WIDTH) << valueShiftAmt

    val msnShiftAmt = busWidth.id - bthWidth - aethWidth
    val msnBitMask = setAllBits(MSN_WIDTH) << msnShiftAmt

    val rsvd = (fragData & rsvdBitMask) >> rsvdShiftAmt
    val code = (fragData & codeBitMask) >> codeShiftAmt
    val value = (fragData & valueBitMask) >> valueShiftAmt
    val msn = (fragData & msnBitMask) >> msnShiftAmt

    (rsvd.toInt, code.toInt, value.toInt, msn.toInt)
  }

  implicit class AethExt(val that: AETH) {
    def setAsNormalAck(): that.type = {
      that.code #= AethCode.ACK.id
      that
    }

    def setAsRnrNak(): that.type = {
      that.code #= AethCode.RNR.id
      that
    }

    def setAsSeqNak(): that.type = {
      that.code #= AethCode.NAK.id
      that.value #= NakCode.SEQ.id
      that
    }

    def setAsInvReqNak(): that.type = {
      that.code #= AethCode.NAK.id
      that.value #= NakCode.INV.id
      that
    }

    def setAsRmtAccNak(): that.type = {
      that.code #= AethCode.NAK.id
      that.value #= NakCode.RMT_ACC.id
      that
    }

    def setAsRmtOpNak(): that.type = {
      that.code #= AethCode.NAK.id
      that.value #= NakCode.RMT_OP.id
      that
    }

    def setAsReserved(): that.type = {
      that.code #= AethCode.RSVD.id
      that.value #= NakCode.RSVD.id
      that
    }

    def setAs(ackType: SpinalEnumElement[AckType.type]): that.type = {
      ackType match {
        case AckType.NORMAL      => setAsNormalAck()
        case AckType.NAK_INV     => setAsInvReqNak()
        case AckType.NAK_RNR     => setAsRnrNak()
        case AckType.NAK_RMT_ACC => setAsRmtAccNak()
        case AckType.NAK_RMT_OP  => setAsRmtOpNak()
        case AckType.NAK_SEQ     => setAsSeqNak()
        case _ =>
          SpinalExit(s"${simTime()} time: invalid AckType=${ackType} to set")
      }
    }
  }
}

object AtomicEthSim {
  def extract(
      fragData: PktFragData,
      busWidth: BusWidth.Value
  ): (VirtualAddr, LRKey, AtomicSwap, AtomicComp) = {
    val bthWidth = widthOf(BTH())
    val atomicEthWidth = widthOf(AtomicEth())
    require(
      busWidth.id >= bthWidth + atomicEthWidth,
      s"${simTime()} time: input busWidth=${busWidth.id} should >= widthOf(BTH())=${bthWidth} + widthOf(AtomicEth())=${atomicEthWidth}"
    )

    val addrShiftAmt = busWidth.id - bthWidth - MEM_ADDR_WIDTH
    val addrBitMask = setAllBits(MEM_ADDR_WIDTH) << addrShiftAmt

    val rkeyShiftAmt =
      busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH
    val rkeyBitMask = setAllBits(LRKEY_IMM_DATA_WIDTH) << rkeyShiftAmt

    val swapShiftAmt =
      busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH - LONG_WIDTH
    val swapBitMask = setAllBits(LONG_WIDTH) << swapShiftAmt

    val compShiftAmt =
      busWidth.id - bthWidth - atomicEthWidth
    val compBitMask = setAllBits(LONG_WIDTH) << compShiftAmt

    val addr = (fragData & addrBitMask) >> addrShiftAmt
    val rkey = (fragData & rkeyBitMask) >> rkeyShiftAmt
    val comp = (fragData & compBitMask) >> compShiftAmt
    val swap = (fragData & swapBitMask) >> swapShiftAmt

    (addr, rkey.toLong, swap, comp)
  }
}

object AtomicAckEthSim {
  def extract(
      fragData: PktFragData,
      busWidth: BusWidth.Value
  ): AtomicOrig = {
    val bthWidth = widthOf(BTH())
    val aethWidth = widthOf(AETH())
    val atomicAckEthWidth = widthOf(AtomicAckEth())
    require(
      busWidth.id >= bthWidth + aethWidth + atomicAckEthWidth,
      s"${simTime()} time: input busWidth=${busWidth.id} should >= widthOf(BTH())=${bthWidth} + width(AETH())=${aethWidth} + widthOf(AtomicAckEth())=${atomicAckEthWidth}"
    )

    val origShiftAmt = busWidth.id - bthWidth - aethWidth - LONG_WIDTH
    val origBitMask = setAllBits(LONG_WIDTH) << origShiftAmt

    val orig = (fragData & origBitMask) >> origShiftAmt
    orig
  }
}

// TODO: refactor to set simulation value directly
object RethSim {
  val bthWidth = widthOf(BTH())
  val rethWidth = widthOf(RETH())
  val addrBitMask = setAllBits(MEM_ADDR_WIDTH)
  val rkeyBitMask = setAllBits(LRKEY_IMM_DATA_WIDTH)
  val dlenBitMask = setAllBits(RDMA_MAX_LEN_WIDTH)

  private def setHelper[T: Numeric](
      inputData: PktFragData,
      field: T,
      shiftAmt: Int,
      mask: BigInt
  ): PktFragData = {
    val fieldVal = implicitly[Numeric[T]].toLong(field)
    val maskShifted = addrBitMask << shiftAmt
    val fieldShifted = (fieldVal & mask) << shiftAmt
    (inputData & (~maskShifted)) | fieldShifted
  }

  def setAddr(
      inputData: PktFragData,
      addr: VirtualAddr,
      busWidth: BusWidth.Value
  ): PktFragData = {
    val addrShiftAmt = busWidth.id - bthWidth - MEM_ADDR_WIDTH
    setHelper(inputData, addr, addrShiftAmt, addrBitMask)
  }

  def setRkey(
      inputData: PktFragData,
      rkey: LRKey,
      busWidth: BusWidth.Value
  ): PktFragData = {
    val rkeyShiftAmt =
      busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH
    setHelper(inputData, rkey, rkeyShiftAmt, rkeyBitMask)
  }

  def setDlen(
      inputData: PktFragData,
      dlen: PktLen,
      busWidth: BusWidth.Value
  ): PktFragData = {
    val dlenShiftAmt =
      busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH - RDMA_MAX_LEN_WIDTH
    setHelper(inputData, dlen, dlenShiftAmt, dlenBitMask)
  }

  def set(
      addr: VirtualAddr,
      rkey: LRKey,
      dlen: PktLen,
      busWidth: BusWidth.Value
  ): PktFragData = {
    val addrShiftAmt = busWidth.id - bthWidth - MEM_ADDR_WIDTH
    val rkeyShiftAmt =
      busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH
    val dlenShiftAmt =
      busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH - RDMA_MAX_LEN_WIDTH

    val result: BigInt = ((addr & addrBitMask) << addrShiftAmt) |
      ((rkey & rkeyBitMask) << rkeyShiftAmt) |
      ((dlen & dlenBitMask) << dlenShiftAmt)
    result
  }

  def extract(
      fragData: PktFragData,
      busWidth: BusWidth.Value
  ): (VirtualAddr, LRKey, PktLen) = {
    require(
      busWidth.id >= bthWidth + rethWidth,
      s"${simTime()} time: input busWidth=${busWidth.id} should >= widthOf(BTH())=${bthWidth} + widthOf(RETH())=${rethWidth}"
    )

    val addrShiftAmt = busWidth.id - bthWidth - MEM_ADDR_WIDTH
    val addrBitMask = setAllBits(MEM_ADDR_WIDTH) << addrShiftAmt

    val rkeyShiftAmt =
      busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH
    val rkeyBitMask = setAllBits(LRKEY_IMM_DATA_WIDTH) << rkeyShiftAmt

    val dlenShiftAmt =
      busWidth.id - bthWidth - MEM_ADDR_WIDTH - LRKEY_IMM_DATA_WIDTH - RDMA_MAX_LEN_WIDTH
    val dlenBitMask = setAllBits(RDMA_MAX_LEN_WIDTH) << dlenShiftAmt

    val addr = (fragData & addrBitMask) >> addrShiftAmt
    val rkey = (fragData & rkeyBitMask) >> rkeyShiftAmt
    val dlen = (fragData & dlenBitMask) >> dlenShiftAmt

    (addr, rkey.toLong, dlen.toLong)
  }
}

object BthSim {
  implicit class BthExt(val that: BTH) {
    def setTransportAndOpCode(
        transport: Transports.Value,
        opcode: OpCode.Value
    ): that.type = {
      val opcodeFull = transport.id << OPCODE_WIDTH + opcode.id
      that.opcodeFull #= opcodeFull
//      println(
//        f"${simTime()} time: opcodeFull=${opcodeFull}%X, transport=${transport.id}%X, opcode=${opcode.id}%X"
//      )
      that
    }
  }
}

object OpCodeSim {
  def randomAtomicOpCode(): OpCode.Value = {
    val opCodes = Seq(OpCode.COMPARE_SWAP, OpCode.FETCH_ADD)
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: AtomicOpCode should contain ${result}"
    )
    result
  }

  def randomReadAtomicOpCode(): OpCode.Value = {
    val opCodes =
      Seq(OpCode.RDMA_READ_REQUEST, OpCode.COMPARE_SWAP, OpCode.FETCH_ADD)
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: AtomicOpCode should contain ${result}"
    )
    result
  }

  def randomNonReadRespOpCode(): OpCode.Value = {
    val opCodes =
      Seq(OpCode.ACKNOWLEDGE, OpCode.ATOMIC_ACKNOWLEDGE)
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      s"${simTime()} time: non-read response opcode should contain ${result}"
    )
    result
  }

  implicit class OpCodeExt(val opcode: OpCode.Value) {
    def getPktHeaderLenBytes(): HeaderLen = {
      val bthWidth = widthOf(BTH())
      val immDtWidth = widthOf(ImmDt())
      val iethWidth = widthOf(IETH())
      val rethWidth = widthOf(RETH())
      val atomicEthWidth = widthOf(AtomicEth())
      val aethWidth = widthOf(AETH())
      val atomicAckEthWidth = widthOf(AtomicAckEth())

      val headerLen = opcode match {
        case OpCode.SEND_FIRST | OpCode.SEND_MIDDLE | OpCode.SEND_LAST |
            OpCode.SEND_ONLY =>
          bthWidth
        case OpCode.SEND_LAST_WITH_IMMEDIATE |
            OpCode.SEND_ONLY_WITH_IMMEDIATE =>
          bthWidth + immDtWidth
        case OpCode.SEND_LAST_WITH_INVALIDATE |
            OpCode.SEND_ONLY_WITH_INVALIDATE =>
          bthWidth + iethWidth

        case OpCode.RDMA_WRITE_FIRST | OpCode.RDMA_WRITE_ONLY =>
          bthWidth + rethWidth
        case OpCode.RDMA_WRITE_MIDDLE | OpCode.RDMA_WRITE_LAST => bthWidth
        case OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE => bthWidth + immDtWidth
        case OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE =>
          bthWidth + rethWidth + immDtWidth
        case OpCode.ACKNOWLEDGE => bthWidth + aethWidth

        case OpCode.RDMA_READ_REQUEST => bthWidth + rethWidth
        case OpCode.RDMA_READ_RESPONSE_FIRST | OpCode.RDMA_READ_RESPONSE_LAST |
            OpCode.RDMA_READ_RESPONSE_ONLY =>
          bthWidth + aethWidth
        case OpCode.RDMA_READ_RESPONSE_MIDDLE => bthWidth

        case OpCode.COMPARE_SWAP | OpCode.FETCH_ADD => bthWidth + atomicEthWidth
        case OpCode.ATOMIC_ACKNOWLEDGE =>
          bthWidth + aethWidth + atomicAckEthWidth
        case _ => SpinalExit(s"${simTime()} time: invalid opcode=${opcode}")
      }
      headerLen / BYTE_WIDTH
    }

    def isSendReqPkt(): Boolean = {
      opcode match {
        case OpCode.SEND_FIRST | OpCode.SEND_MIDDLE | OpCode.SEND_LAST |
            OpCode.SEND_LAST_WITH_IMMEDIATE | OpCode.SEND_LAST_WITH_INVALIDATE |
            OpCode.SEND_ONLY | OpCode.SEND_ONLY_WITH_IMMEDIATE |
            OpCode.SEND_ONLY_WITH_INVALIDATE =>
          true
        case _ => false
      }
    }

    def isWriteReqPkt(): Boolean = {
      opcode match {

        case OpCode.RDMA_WRITE_FIRST | OpCode.RDMA_WRITE_ONLY |
            OpCode.RDMA_WRITE_MIDDLE | OpCode.RDMA_WRITE_LAST |
            OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE |
            OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE =>
          true
        case _ => false
      }
    }

    def isWriteWithImmReqPkt(): Boolean = {
      opcode match {
        case OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE |
            OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE =>
          true
        case _ => false
      }
    }

    def isReadReqPkt(): Boolean = {
      opcode == OpCode.RDMA_READ_REQUEST
    }

    def isAtomicReqPkt(): Boolean = {
      opcode match {
        case OpCode.COMPARE_SWAP | OpCode.FETCH_ADD =>
          true
        case _ => false
      }
    }

    def isFirstReqPkt(): Boolean = {
      opcode match {
        case OpCode.SEND_FIRST | OpCode.RDMA_WRITE_FIRST =>
          true
        case _ => false
      }
    }

    def isLastReqPkt(): Boolean = {
      opcode match {
        case OpCode.SEND_LAST | OpCode.SEND_LAST_WITH_IMMEDIATE |
            OpCode.SEND_LAST_WITH_INVALIDATE | OpCode.RDMA_WRITE_LAST |
            OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE =>
          true
        case _ => false
      }
    }

    def isOnlyReqPkt(): Boolean = {
      opcode match {
        case OpCode.SEND_ONLY | OpCode.SEND_ONLY_WITH_IMMEDIATE |
            OpCode.SEND_ONLY_WITH_INVALIDATE | OpCode.RDMA_WRITE_ONLY |
            OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE | OpCode.RDMA_READ_REQUEST |
            OpCode.COMPARE_SWAP | OpCode.FETCH_ADD =>
          true
        case _ => false
      }
    }

    def isAckRespPkt(): Boolean = {
      opcode == OpCode.ACKNOWLEDGE
    }

    def isReadRespPkt(): Boolean = {
      opcode match {
        case OpCode.RDMA_READ_RESPONSE_FIRST |
            OpCode.RDMA_READ_RESPONSE_MIDDLE | OpCode.RDMA_READ_RESPONSE_LAST |
            OpCode.RDMA_READ_RESPONSE_ONLY =>
          true
        case _ => false
      }
    }

    def isFirstOrOnlyReadRespPkt(): Boolean = {
      opcode match {
        case OpCode.RDMA_READ_RESPONSE_FIRST | OpCode.RDMA_READ_RESPONSE_ONLY =>
          true
        case _ => false
      }
    }

    def isLastOrOnlyReadRespPkt(): Boolean = {
      opcode match {
        case OpCode.RDMA_READ_RESPONSE_LAST | OpCode.RDMA_READ_RESPONSE_ONLY =>
          true
        case _ => false
      }
    }

    def isAtomicRespPkt(): Boolean = {
      opcode == OpCode.ATOMIC_ACKNOWLEDGE
    }

    def isFirstOrOnlyReqPkt(): Boolean = {
      isFirstReqPkt() || isOnlyReqPkt()
    }

    def isLastOrOnlyReqPkt(): Boolean = {
      isLastReqPkt() || isOnlyReqPkt()
    }

    def hasReth(): Boolean = {
      opcode match {
        case OpCode.RDMA_WRITE_FIRST | OpCode.RDMA_WRITE_ONLY |
            OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE | OpCode.RDMA_READ_REQUEST =>
          true
        case _ => false
      }
    }

    def needRxBuf(): Boolean = {
      isSendReqPkt() || isWriteWithImmReqPkt()
    }
  }
}

trait QueryRespSim[
    Treq <: Data,
    Tresp <: Data,
    QueryBus <: ReqRespBus[Treq, Tresp],
    ReqData,
    RespData
] {
  // Functions need override
  protected def onReqFire(req: Treq): ReqData

  protected def onRespFire(resp: Tresp): RespData

//  protected def onReqFire(req: Treq, reqQueue: mutable.Queue[ReqData]): Unit
//
//  protected def onRespFire(
//      resp: Tresp,
//      respQueue: mutable.Queue[RespData]
//  ): Unit

  // Need to override if generate multiple responses
  protected def buildMultiResp(
      reqData: ReqData,
      respAlwaysSuccess: Boolean
  ): Seq[RespData]

  protected def buildRespFromReqData(
      resp: Tresp,
      reqData: ReqData,
      respAlwaysSuccess: Boolean
  ): Boolean // Response valid

  // Need to override if generate multiple responses
  protected def buildRespFromRespData(
      resp: Tresp,
      respData: RespData,
      respAlwaysSuccess: Boolean
  ): Boolean // Response valid

  protected def handleCacheReqAndResp(
      queryBus: QueryBus,
      clockDomain: ClockDomain,
      hasProcessDelay: Boolean,
      processDelayCycles: Int,
      hasMultiResp: Boolean,
      respAlwaysSuccess: Boolean
  ): mutable.Queue[RespData] = {
    val reqStream = queryBus.req
    val respStream = queryBus.resp

    val reqQueue = mutable.Queue[ReqData]()
//    val processDelayQueue = mutable.Queue[(ReqData, SimTimeStamp)]()
//    val delayedReqQueue = mutable.Queue[ReqData]()
    val respQueue = mutable.Queue[RespData]()

    // Set initial state for request and response streams
    fork {
      reqStream.ready #= false
      respStream.valid #= false
      clockDomain.waitSampling()
    }

    // Handle requests
    val delayedReqQueue = if (hasProcessDelay) {
      require(
        processDelayCycles > 0,
        s"${simTime()} time: processDelayCycles=${processDelayCycles} should be positive when hasProcessDelay=${hasProcessDelay}"
      )
      val processDelayQueue =
        DelayedQueue[ReqData](clockDomain, processDelayCycles)
//      println(
//        f"${simTime()} time: processDelayCycles=${processDelayCycles} should be positive when hasProcessDelay=${hasProcessDelay}"
//      )

      fork {
        clockDomain.waitSampling()

        while (true) {
          val inputReq = MiscUtils.safeDeQueue(reqQueue, clockDomain)
          processDelayQueue.enqueue(inputReq)
        }
      }
      streamSlaveReadyOnCondition(
        reqStream,
        clockDomain,
        condition = {
          // Must be less than or equal to ensure max delay to be exactly processDelayCycles
          processDelayQueue.length <= processDelayCycles
        }
      )

      processDelayQueue.toMutableQueue()
    } else {
      streamSlaveAlwaysReady(reqStream, clockDomain)
      reqQueue
    }
    onStreamFire(reqStream, clockDomain) {
      val reqData = onReqFire(reqStream.payload)
      reqQueue.enqueue(reqData)
    }

//    println(f"${simTime()} time: hasProcessDelay=${hasProcessDelay}")

    // Handle response
    if (hasMultiResp) {
      val multiRespQueue = mutable.Queue[RespData]()
      fork {
        clockDomain.waitSampling()

        while (true) {
          val inputReq = MiscUtils.safeDeQueue(delayedReqQueue, clockDomain)
          val multiRespData = buildMultiResp(inputReq, respAlwaysSuccess)
          multiRespQueue.appendAll(multiRespData)

//          println(f"${simTime()} time: multiRespQueue.length=${multiRespQueue.length}")
        }
      }

      streamMasterPayloadFromQueueNoRandomDelay(
        respStream,
        clockDomain,
        multiRespQueue,
        payloadAssignFunc = (resp: Tresp, respData: RespData) => {
          buildRespFromRespData(resp, respData, respAlwaysSuccess)
        }
      )
    } else {
      streamMasterPayloadFromQueueNoRandomDelay(
        respStream,
        clockDomain,
        delayedReqQueue,
        payloadAssignFunc = (resp: Tresp, reqData: ReqData) => {
          buildRespFromReqData(resp, reqData, respAlwaysSuccess)
        }
      )
    }
    onStreamFire(respStream, clockDomain) {
      val respData = onRespFire(respStream.payload)
      respQueue.enqueue(respData)
    }

    respQueue
  }
}

trait SingleRespQuerySim[
    Treq <: Data,
    Tresp <: Data,
    QueryBus <: ReqRespBus[Treq, Tresp],
    ReqData,
    RespData
] extends QueryRespSim[
      Treq,
      Tresp,
      QueryBus,
      ReqData,
      RespData
    ] {
  def reqStreamAlwaysFireAndRespSuccess(
      queryBus: QueryBus,
      clockDomain: ClockDomain
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = false,
      processDelayCycles = 0,
      hasMultiResp = false,
      respAlwaysSuccess = true
    )
  }

  def reqStreamAlwaysFireAndRespFailure(
      queryBus: QueryBus,
      clockDomain: ClockDomain
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = false,
      processDelayCycles = 0,
      hasMultiResp = false,
      respAlwaysSuccess = false
    )
  }

  def reqStreamFixedDelayAndRespSuccess(
      queryBus: QueryBus,
      clockDomain: ClockDomain,
      fixedRespDelayCycles: Int
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = true,
      processDelayCycles = fixedRespDelayCycles,
      hasMultiResp = false,
      respAlwaysSuccess = true
    )
  }

  def reqStreamFixedDelayAndRespFailure(
      queryBus: QueryBus,
      clockDomain: ClockDomain,
      fixedRespDelayCycles: Int
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = true,
      processDelayCycles = fixedRespDelayCycles,
      hasMultiResp = false,
      respAlwaysSuccess = false
    )
  }

  def reqStreamFixedDelayAndMultiRespSuccess(
      queryBus: QueryBus,
      clockDomain: ClockDomain,
      fixedRespDelayCycles: Int
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = true,
      processDelayCycles = fixedRespDelayCycles,
      hasMultiResp = true,
      respAlwaysSuccess = true
    )
  }

//  protected def onReqFire(req: Treq, reqQueue: mutable.Queue[ReqData]): Unit
//
//  protected def onRespFire(
//      resp: Tresp,
//      respQueue: mutable.Queue[RespData]
//  ): Unit

//  protected def buildRespFromReqData(
//      resp: Tresp,
//      reqData: ReqData,
//      respAlwaysSuccess: Boolean
//  ): Boolean = {
//    ???
//  }

  // No multiple responses generated for a single request
  override def buildMultiResp(
      reqData: ReqData,
      respAlwaysSuccess: Boolean
  ): Seq[RespData] = {
    ??? // Need to override
  }

  // No multiple responses generated for a single request
  override def buildRespFromRespData(
      resp: Tresp,
      respData: RespData,
      respAlwaysSuccess: Boolean
  ): Boolean = {
    ??? // Response valid
  }
}

trait MultiRespQuerySim[
    Treq <: Data,
    Tresp <: Data,
    QueryBus <: ReqRespBus[Treq, Tresp],
    ReqData,
    RespData
] extends QueryRespSim[
      Treq,
      Tresp,
      QueryBus,
      ReqData,
      RespData
    ] {
  def reqStreamAlwaysFireAndMultiRespSuccess(
      queryBus: QueryBus,
      clockDomain: ClockDomain
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = false,
      processDelayCycles = 0,
      hasMultiResp = true,
      respAlwaysSuccess = true
    )
  }

  def reqStreamAlwaysFireAndMultiRespFailure(
      queryBus: QueryBus,
      clockDomain: ClockDomain
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = false,
      processDelayCycles = 0,
      hasMultiResp = true,
      respAlwaysSuccess = false
    )
  }

  def reqStreamFixedDelayAndMultiRespSuccess(
      queryBus: QueryBus,
      clockDomain: ClockDomain,
      fixedRespDelayCycles: Int
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = true,
      processDelayCycles = fixedRespDelayCycles,
      hasMultiResp = true,
      respAlwaysSuccess = true
    )
  }

  def reqStreamFixedDelayAndMultiRespFailure(
      queryBus: QueryBus,
      clockDomain: ClockDomain,
      fixedRespDelayCycles: Int
  ) = {
    handleCacheReqAndResp(
      queryBus,
      clockDomain,
      hasProcessDelay = true,
      processDelayCycles = fixedRespDelayCycles,
      hasMultiResp = true,
      respAlwaysSuccess = false
    )
  }

  // DO NOT generate a single response for a single request
  override def buildRespFromReqData(
      resp: Tresp,
      reqData: ReqData,
      respAlwaysSuccess: Boolean
  ): Boolean = {
    ???
  }
}

object AddrCacheSim
    extends SingleRespQuerySim[
      QpAddrCacheAgentReadReq,
      QpAddrCacheAgentReadResp,
      QpAddrCacheAgentReadBus,
      (
          PSN,
          LRKey,
          Boolean,
          VirtualAddr,
          PktLen
      ),
      (
          PSN,
          KeyValid,
          SizeValid,
          AccessValid,
          PhysicalAddr
      )
    ] {

  override def onReqFire(req: QpAddrCacheAgentReadReq):
//      reqQueue: mutable.Queue[
  (
      PSN,
      LRKey,
      Boolean,
      VirtualAddr,
      PktLen
  ) = {
//      ]
//  ): Unit = {

//    println(f"${simTime()} time: AddrCacheSim receive request PSN=${req.psn.toInt}%X")

//    reqQueue.enqueue(
    (
      req.psn.toInt,
      req.key.toLong,
      req.remoteOrLocalKey.toBoolean,
      req.va.toBigInt,
      req.dataLenBytes.toLong
    )
    //    )
  }

  override def buildRespFromReqData(
      resp: QpAddrCacheAgentReadResp,
      reqData: (
          PSN,
          LRKey,
          Boolean,
          VirtualAddr,
          PktLen
      ),
      respAlwaysSuccess: Boolean
  ): Boolean = {
//    val (psn, _, _, _, _) = reqQueue.dequeue()
    val psn = reqData._1

    resp.psn #= psn
    if (respAlwaysSuccess) {
      resp.keyValid #= true
      resp.sizeValid #= true
      resp.accessValid #= true
    } else {
      resp.keyValid #= false
      resp.sizeValid #= false
      resp.accessValid #= false
    }

//    println(f"${simTime()} time: AddrCacheSim response to PSN=${psn}%X, respAlwaysSuccess=${respAlwaysSuccess}")
    val respValid = true
    respValid
  }

  override def onRespFire(respData: QpAddrCacheAgentReadResp):
//      respQueue: mutable.Queue[
  (
      PSN,
      KeyValid,
      SizeValid,
      AccessValid,
      PhysicalAddr
  ) = {
//      ]
//  ): Unit = {
//    respQueue.enqueue(
    (
      respData.psn.toInt,
      respData.keyValid.toBoolean,
      respData.sizeValid.toBoolean,
      respData.accessValid.toBoolean,
      respData.pa.toBigInt
    )
    //    )
  }
}

object RqDmaBusSim {
  private def simHelper(
      rqDmaBus: RqDmaBus,
      clockDomain: ClockDomain,
      busWidth: BusWidth.Value,
      hasProcessDelay: Boolean
  ) = {
    val dmaReadBusVec = Seq(rqDmaBus.read, rqDmaBus.atomic.rd)
    val dmaWriteBusVec = Seq(rqDmaBus.sendWrite, rqDmaBus.atomic.wr)

    // dmaWriteRespQueue
    val dmaWriteRespVec = for (dmaWriteBus <- dmaWriteBusVec) yield {
      if (hasProcessDelay) {
        DmaWriteBusSim.reqStreamFixedDelayAndRespSuccess(
          dmaWriteBus,
          clockDomain,
          fixedRespDelayCycles = DMA_WRITE_DELAY_CYCLES
        )
      } else {
        DmaWriteBusSim.reqStreamAlwaysFireAndRespSuccess(
          dmaWriteBus,
          clockDomain
        )
      }
    }
    // dmaReadRespQueue
    val dmaReadRespVec = for (dmaReadBus <- dmaReadBusVec) yield {
      if (hasProcessDelay) {
        DmaReadBusSim(busWidth).reqStreamFixedDelayAndMultiRespSuccess(
          dmaReadBus,
          clockDomain,
          fixedRespDelayCycles = DMA_READ_DELAY_CYCLES
        )
      } else {
        DmaReadBusSim(busWidth).reqStreamAlwaysFireAndMultiRespSuccess(
          dmaReadBus,
          clockDomain
        )
      }
    }

    (dmaWriteRespVec, dmaReadRespVec)
  }

  def reqStreamAlwaysFireAndRespSuccess(
      rqDmaBus: RqDmaBus,
      clockDomain: ClockDomain,
      busWidth: BusWidth.Value
  ) = {
    simHelper(rqDmaBus, clockDomain, busWidth, hasProcessDelay = true)
  }

//  def reqStreamRandomFireAndRespSuccess(
//      rqDmaBus: RqDmaBus,
//      clockDomain: ClockDomain,
//      busWidth: BusWidth.Value
//  ) = {
//    simHelper(rqDmaBus, clockDomain, busWidth, hasProcessDelay = false)
//  }
}

object PdAddrCacheSim
    extends SingleRespQuerySim[
      PdAddrCacheReadReq,
      PdAddrCacheReadResp,
      PdAddrCacheReadBus,
      (
          PSN,
          LRKey,
          Boolean,
          VirtualAddr,
          PktLen
      ),
      (
          PSN,
          KeyValid,
          SizeValid,
          AccessValid,
          PhysicalAddr
      )
    ] {

  override def onReqFire(req: PdAddrCacheReadReq):
//      reqQueue: mutable.Queue[
  (
      PSN,
      LRKey,
      Boolean,
      VirtualAddr,
      PktLen
  ) = {
//      ]
//  ): Unit = {
//    reqQueue.enqueue(
    (
      req.psn.toInt,
      req.key.toLong,
      req.remoteOrLocalKey.toBoolean,
      req.va.toBigInt,
      req.dataLenBytes.toLong
    )
    //    )
  }

  override def buildRespFromReqData(
      resp: PdAddrCacheReadResp,
      reqData: (
          PSN,
          LRKey,
          Boolean,
          VirtualAddr,
          PktLen
      ),
      respAlwaysSuccess: Boolean
  ): Boolean = {
//    val (psn, _, _, _, _) = reqQueue.dequeue()
    val psn = reqData._1
    resp.psn #= psn
    if (respAlwaysSuccess) {
      resp.keyValid #= true
      resp.sizeValid #= true
      resp.accessValid #= true
    } else {
      resp.keyValid #= false
      resp.sizeValid #= false
      resp.accessValid #= false
    }

    val respValid = true
    respValid
  }

  override def onRespFire(resp: PdAddrCacheReadResp):
//      respQueue: mutable.Queue[
  (
      PSN,
      KeyValid,
      SizeValid,
      AccessValid,
      PhysicalAddr
  ) = {
//      ]
//  ): Unit = {
//    respQueue.enqueue(
    (
      resp.psn.toInt,
      resp.keyValid.toBoolean,
      resp.sizeValid.toBoolean,
      resp.accessValid.toBoolean,
      resp.pa.toBigInt
    )
//    )
  }
}

object ReadAtomicRstCacheSim
    extends SingleRespQuerySim[
      ReadAtomicRstCacheReq,
      CamQueryResp[ReadAtomicRstCacheReq, ReadAtomicRstCacheData],
      ReadAtomicRstCacheQueryBus,
      (PSN, OpCode.Value),
      (QueryPsn, QuerySuccess, PsnStart, PhysicalAddr, PktNum, PktLen)
    ] {
  type ReadAtomicRstCacheResp =
    CamQueryResp[ReadAtomicRstCacheReq, ReadAtomicRstCacheData]

  override def onReqFire(req: ReadAtomicRstCacheReq):
//      reqQueue: mutable.Queue[
  (PSN, OpCode.Value) = {
//  ]): Unit = {

//    println(
//      f"${simTime()} time: ReadAtomicRstCacheSim receive request PSN=${req.queryPsn.toInt}%X"
//    )

//    reqQueue.enqueue(
    (
      req.queryPsn.toInt,
      OpCode(req.opcode.toInt)
    )
//    )
  }

  override def buildRespFromReqData(
      resp: ReadAtomicRstCacheResp,
      reqData: (PSN, OpCode.Value),
//      reqQueue: mutable.Queue[(PSN, OpCode.Value)],
      respAlwaysSuccess: Boolean
  ): Boolean = {
//    val (queryPsn, queryOpCode) = reqQueue.dequeue()
    val (queryPsn, queryOpCode) = reqData
    resp.respValue.opcode #= queryOpCode.id
    resp.respValue.psnStart #= queryPsn // TODO: support partial retry
    resp.queryKey.queryPsn #= queryPsn
    resp.found #= respAlwaysSuccess

    val respValid = true
    respValid
  }

  override def onRespFire(resp: ReadAtomicRstCacheResp):
//      respQueue: mutable.Queue[
  (QueryPsn, QuerySuccess, PsnStart, PhysicalAddr, PktNum, PktLen) = {
//      ]
//  ): Unit = {
//    println(
//      f"${simTime()} time: ReadAtomicRstCacheSim response to PSN=${resp.queryKey.queryPsn.toInt}%X"
//    )

//    respQueue.enqueue(
    (
      resp.queryKey.queryPsn.toInt,
      resp.found.toBoolean,
      resp.respValue.psnStart.toInt,
      resp.respValue.pa.toBigInt,
      resp.respValue.pktNum.toInt,
      resp.respValue.dlen.toLong
    )
//    )
  }
}

object DmaWriteBusSim
    extends SingleRespQuerySim[
      Fragment[DmaWriteReq],
      DmaWriteResp,
      DmaWriteBus,
      (
          SpinalEnumElement[DmaInitiator.type],
          PSN,
          WorkReqId,
          PhysicalAddr,
          PktFragData,
          MTY,
          FragLast
      ),
      (
          SpinalEnumElement[DmaInitiator.type],
          PSN,
          WorkReqId,
          PktLen
      )
    ] {
  private val pktFragSizeQueue = mutable.Queue[PktLen]()

  override def onReqFire(req: Fragment[DmaWriteReq]):
//      reqQueue: mutable.Queue[
  (
      SpinalEnumElement[DmaInitiator.type],
      PSN,
      WorkReqId,
      PhysicalAddr,
      PktFragData,
      MTY,
      FragLast
  ) = {
//      ]
//  ): Unit = {

//    println(f"${simTime()} time: DMA receive write request PSN=${reqData.psn.toInt}%X")

    val mty = req.mty.toBigInt
    pktFragSizeQueue.enqueue(mty.bitCount.toLong)

//    reqQueue.enqueue(
    (
      req.initiator.toEnum,
      req.psn.toInt,
      req.workReqId.toBigInt,
      req.pa.toBigInt,
      req.data.toBigInt,
      mty,
      req.last.toBoolean
    )
//    )
  }

  override def buildRespFromReqData(
      resp: DmaWriteResp,
      reqData: (
          SpinalEnumElement[DmaInitiator.type],
          PSN,
          WorkReqId,
          PhysicalAddr,
          PktFragData,
          MTY,
          FragLast
      ),
      respAlwaysSuccess: KeyValid
  ): Boolean = {
    val (
      dmaInitiator,
      psn,
      workReqId,
      _,
      _,
      _,
      fragLast
    ) = reqData
    resp.initiator #= dmaInitiator
    resp.psn #= psn
    resp.workReqId #= workReqId
    if (fragLast) {
      val pktLen = pktFragSizeQueue.sum
      resp.lenBytes #= pktLen
      pktFragSizeQueue.clear()
    } else {
      resp.lenBytes #= 0
    }

    val respValid = fragLast
    respValid
  }

  override def onRespFire(resp: DmaWriteResp):
//      respQueue: mutable.Queue[
  (
      SpinalEnumElement[DmaInitiator.type],
      PSN,
      WorkReqId,
      PktLen
  ) = {
//      ]
//  ): Unit = {
//    respQueue.enqueue(
    (
      resp.initiator.toEnum,
      resp.psn.toInt,
      resp.workReqId.toBigInt,
      resp.lenBytes.toLong
    )
//    )
  }
}

case class DmaReadBusSim(busWidth: BusWidth.Value)
    extends MultiRespQuerySim[
      DmaReadReq,
      Fragment[DmaReadResp],
      DmaReadBus,
      (
          SpinalEnumElement[DmaInitiator.type],
          PsnStart,
          PhysicalAddr,
          PktLen
      ),
      (
          SpinalEnumElement[DmaInitiator.type],
          PsnStart,
          MTY,
          PktLen,
//          PktFragData,
          FragLast
      )
    ] {

  override def onReqFire(req: DmaReadReq):
//      reqQueue: mutable.Queue[
  (
      SpinalEnumElement[DmaInitiator.type],
      PSN,
      PhysicalAddr,
      PktLen
  ) = {
//      ]
//  ): Unit = {
    val dmaInitiator = req.initiator.toEnum
    val psnStart = req.psnStart.toInt

    val readDataLenBytes = req.lenBytes.toLong
    readDataLenBytes should be <= RDMA_MAX_PKT_LEN_BYTES withClue
      f"${simTime()} time: req.lenBytes=${req.lenBytes.toLong}%X"

    //    println(
    //      f"${simTime()} time: DMA read request, psnStart=${psnStart}%X, physicalAddr=${physicalAddr}%X, readDataLenBytes=${readDataLenBytes}%X"
    //    )
    val physicalAddr = req.pa.toBigInt
//    reqQueue.enqueue(
    (
      dmaInitiator,
      psnStart,
      physicalAddr,
      readDataLenBytes
    )
//    )
  }

  override def buildMultiResp(
      reqData: (
          SpinalEnumElement[DmaInitiator.type],
          PSN,
          PhysicalAddr,
          PktLen
      ),
      respAlwaysSuccess: Boolean
  ): Seq[
    (
        SpinalEnumElement[DmaInitiator.type],
        PsnStart,
        MTY,
        PktLen,
        FragLast
    )
  ] = {
    val (
      dmaInitiator,
      psnStart,
      _, // physicalAddr,
      readDataLenBytes
    ) = reqData

    // Prepare DMA read response data
    val dmaRespMtyWidth = MiscUtils.busWidthBytes(busWidth)
    val finalFragValidBytes = (readDataLenBytes % dmaRespMtyWidth).toInt
    val fullMty = setAllBits(dmaRespMtyWidth)
    val (fragNum, lastFragMty) = if (finalFragValidBytes == 0) {
      (
        (readDataLenBytes / dmaRespMtyWidth).toInt,
        fullMty
      )
    } else {
      val leftShiftAmt = busWidth.id - finalFragValidBytes
      val lastFragMty = setAllBits(finalFragValidBytes) << leftShiftAmt
      (
        (readDataLenBytes / dmaRespMtyWidth).toInt + 1,
        lastFragMty
      )
    }
//    println(
//      f"${simTime()} time: generate ${fragNum} fragments for DMA read request PSN=${psnStart}"
//    )
    for (fragIdx <- 0 until fragNum) yield {
      val fragLast = fragIdx == fragNum - 1
      val mty = if (fragLast) {
        lastFragMty
      } else {
        fullMty
      }

      (
        dmaInitiator,
        psnStart,
        mty,
        readDataLenBytes,
        fragLast
      )
    }
  }

  override def buildRespFromRespData(
      resp: Fragment[DmaReadResp],
      respData: (
          SpinalEnumElement[DmaInitiator.type],
          PsnStart,
          MTY,
          PktLen,
          FragLast
      ),
      respAlwaysSuccess: Boolean
  ): Boolean = {

    val (
      dmaInitiator,
      psnStart,
      mty,
      readDataLenBytes,
      fragLast
    ) = respData
    resp.initiator #= dmaInitiator
    resp.psnStart #= psnStart
    resp.data.randomize()
    resp.mty #= mty
    resp.lenBytes #= readDataLenBytes
    resp.last #= fragLast

//    println(
//      f"${simTime()} time: respPktFragDataQueue dequeue psnStart=${psnStart}%X, readDataLenBytes=${readDataLenBytes}%X, last=${fragLast}"
//    )
    val respValid = true
    respValid
  }

  override def onRespFire(resp: Fragment[DmaReadResp]):
//      respQueue: mutable.Queue[
  (
      SpinalEnumElement[DmaInitiator.type],
      PsnStart,
      MTY,
      PktLen,
//            PktFragData,
      FragLast
  ) = {
//      ]
//  ): Unit = {

//    println(
//      f"${simTime()} time: DMA read response, psnStart=${resp.psnStart.toInt}%X, dlen=${resp.lenBytes.toLong}%X, last=${resp.last.toBoolean}"
//    )

//    respQueue.enqueue(
    (
      resp.initiator.toEnum,
      resp.psnStart.toInt,
      resp.mty.toBigInt,
      resp.lenBytes.toLong,
//        resp.data.toBigInt,
      resp.last.toBoolean
    )
//    )
  }
}

object RdmaDataPktSim {
  import OpCodeSim._

  private def pktFragStreamMasterDriverHelper[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      alwaysValid: Boolean,
      isReadRespGen: Boolean
  )(
      outerLoopBody: => (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type]
      )
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      // Outer loop
      while (true) {
        val (
          psnStart,
          _, // payloadFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          payloadLenBytes,
          workReqOpCode
        ) =
          outerLoopBody

        // Inner loop
        for (pktIdx <- 0 until pktNum) {
          val opcode = if (isReadRespGen) {
            WorkReqSim.assignReadRespOpCode(pktIdx, pktNum)
          } else {
            WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx, pktNum)
          }

          val headerLenBytes = opcode.getPktHeaderLenBytes()
          val psn = psnStart + pktIdx
          val pktFragNum = computePktFragNum(
            pmtuLen,
            busWidth,
            opcode,
            payloadLenBytes,
            pktIdx,
            pktNum
          )

          for (fragIdx <- 0 until pktFragNum) {
            val fragLast = fragIdx == pktFragNum - 1
            val mty = computePktFragMty(
              pmtuLen,
              busWidth,
              opcode,
              fragLast,
              pktIdx,
              pktNum,
              payloadLenBytes
            )

//            println(
//              f"${simTime()} time: opcode=${opcode}, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, fragLast=${fragLast}, mty=${mty}%X, PSN=${psn}%X, headerLenBytes=${headerLenBytes}%X, payloadLenBytes=${payloadLenBytes}%X"
//            )
            do {
              if (alwaysValid) {
                stream.valid #= true
              } else {
                stream.valid.randomize()
              }
              stream.payload.randomize()
              sleep(0)
              if (!stream.valid.toBoolean) {
                clockDomain.waitSampling()
              }
            } while (!stream.valid.toBoolean)

            stream.last #= fragLast // Set last must after set payload, since last is part of the payload
            setRdmaDataFrag(
              getRdmaPktDataFunc(stream.fragment),
              psn,
              opcode,
              fragIdx,
//              fragLast,
              pktIdx,
              pktNum,
              payloadLenBytes,
              mty,
              pmtuLen,
              busWidth
            )

            innerLoopFunc(
              psn,
              psnStart,
              fragLast,
              fragIdx,
              pktFragNum,
              pktIdx,
              pktNum,
              payloadLenBytes,
              headerLenBytes,
              opcode
            )

            clockDomain.waitSamplingWhere(
              stream.valid.toBoolean && stream.ready.toBoolean
            )
          }
        }
        // Set stream.valid to false, since outerLoopBody might consume simulation time
        stream.valid #= false
      }
    }

  def pktFragStreamMasterDriver[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt
  )(
      outerLoopBody: => (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type]
      )
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit =
    pktFragStreamMasterDriverHelper(
      stream,
      clockDomain,
      getRdmaPktDataFunc,
      alwaysValid = false,
      isReadRespGen = false
    )(outerLoopBody)(innerLoopFunc)

  def pktFragStreamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt
  )(
      outerLoopBody: => (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          PktLen,
          SpinalEnumElement[WorkReqOpCode.type]
      )
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit =
    pktFragStreamMasterDriverHelper(
      stream,
      clockDomain,
      getRdmaPktDataFunc,
      alwaysValid = true,
      isReadRespGen = false
    )(outerLoopBody)(innerLoopFunc)

  private def rdmaPktFragStreamMasterDriverHelper[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      pendingReqNumExceed: => Boolean,
      readAtomicRstCacheFull: => Boolean,
      alwaysValid: Boolean,
      isReadRespGen: Boolean,
      isSendWriteReqOnly: Boolean,
      isReadAtomicReqOnly: Boolean,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          ReqPktNum,
          RespPktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit = fork {
    stream.valid #= false
    clockDomain.waitSampling()

    val (payloadFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    // Outer loop
    while (true) {
      val _ = payloadFragNumItr.next()
      val totalPktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()

      val workReqOpCode = if (isSendWriteReqOnly) {
        WorkReqSim.randomSendWriteOpCode()
      } else if (isReadAtomicReqOnly) {
        if (totalPktNum == 1) {
          WorkReqSim.randomReadAtomicOpCode()
        } else {
          WorkReqOpCode.RDMA_READ
        }
      } else {
        if (readAtomicRstCacheFull) {
          // If readAtomicRstCacheFull is true, then only generate send/write request
          WorkReqSim.randomSendWriteOpCode()
        } else {
          if (totalPktNum == 1) {
            WorkReqSim.randomSendWriteReadAtomicOpCode()
          } else {
            WorkReqSim.randomSendWriteReadOpCode()
          }
        }
      }

      val isReadReq = workReqOpCode == WorkReqOpCode.RDMA_READ
      val isAtomicReq = Seq(
        WorkReqOpCode.ATOMIC_CMP_AND_SWP,
        WorkReqOpCode.ATOMIC_FETCH_AND_ADD
      ).contains(workReqOpCode)

      if (!isReadRespGen) {
        if (pendingReqNumExceed) {
          clockDomain.waitSamplingWhere(!pendingReqNumExceed)
        }
        if ((isReadReq || isAtomicReq) && readAtomicRstCacheFull) {
          clockDomain.waitSamplingWhere(!readAtomicRstCacheFull)
        }
      }
      val (pktNum4Loop, reqPktNum, respPktNum) = if (isReadRespGen) {
        (totalPktNum, 1, totalPktNum)
      } else if (isReadReq) {
        (1, 1, totalPktNum) // Only 1 packet for read request
      } else {
        (
          totalPktNum,
          totalPktNum,
          1
        ) // Only 1 packet for send/write/atomic response
      }

      // Inner loop
      for (pktIdx <- 0 until pktNum4Loop) {
        val opcode = if (isReadRespGen) {
          WorkReqSim.assignReadRespOpCode(pktIdx, pktNum4Loop)
        } else {
          WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx, pktNum4Loop)
        }

        val headerLenBytes = opcode.getPktHeaderLenBytes()
        val psn = psnStart + pktIdx
        val pktFragNum = if (!isReadRespGen && (isReadReq || isAtomicReq)) {
          1 // Only 1 fragment for read/atomic request
        } else {
          computePktFragNum(
            pmtuLen,
            busWidth,
            opcode,
            payloadLenBytes.toLong,
            pktIdx,
            pktNum4Loop
          )
        }

//        println(
//          f"${simTime()} time: opcode=${opcode}, PSN=${psn}, pktNum4Loop=${pktNum4Loop}%X, totalPktNum=${totalPktNum}, pktFragNum=${pktFragNum}%X, psnStart=${psnStart}%X, payloadLenBytes=${payloadLenBytes}%X"
//        )
        for (fragIdx <- 0 until pktFragNum) {
          val fragLast = fragIdx == pktFragNum - 1
          val mty = if (!isReadRespGen && (isReadReq || isAtomicReq)) {
            computeHeaderMty(headerLenBytes, busWidth)
          } else {
            computePktFragMty(
              pmtuLen,
              busWidth,
              opcode,
              fragLast,
              pktIdx,
              pktNum4Loop,
              payloadLenBytes.toLong
            )
          }

//          println(
//            f"${simTime()} time: opcode=${opcode}, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, fragLast=${fragLast}, mty=${mty}%X, PSN=${psn}%X, headerLenBytes=${headerLenBytes}%X, payloadLenBytes=${payloadLenBytes}%X"
//          )

          do {
            if (alwaysValid) {
              stream.valid #= true
            } else {
              stream.valid.randomize()
            }
            stream.payload.randomize()
            sleep(0)
            if (!stream.valid.toBoolean) {
              clockDomain.waitSampling()
            }
          } while (!stream.valid.toBoolean)

          stream.last #= fragLast // Set last must after set payload, since last is part of the payload
          setRdmaDataFrag(
            getRdmaPktDataFunc(stream.fragment),
            psn,
            opcode,
            fragIdx,
//              fragLast,
            pktIdx,
            pktNum4Loop,
            payloadLenBytes.toLong,
            mty,
            pmtuLen,
            busWidth
          )

          innerLoopFunc(
            psn,
            psnStart,
            fragLast,
            fragIdx,
            pktFragNum,
            pktIdx,
            reqPktNum,
            respPktNum,
            payloadLenBytes.toLong,
            headerLenBytes,
            opcode
          )

          clockDomain.waitSamplingWhere(
            stream.valid.toBoolean && stream.ready.toBoolean
          )

          // Set stream.valid to false, since outerLoopBody might consume simulation time
          stream.valid #= false
        }
      }
    }
  }

  def sendWriteReqPktFragStreamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          ReqPktNum,
          RespPktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit =
    rdmaPktFragStreamMasterDriverHelper(
      stream,
      clockDomain,
      getRdmaPktDataFunc,
      pendingReqNumExceed = false,
      readAtomicRstCacheFull = true,
      alwaysValid = true,
      isReadRespGen = false,
      isSendWriteReqOnly = true,
      isReadAtomicReqOnly = false,
      pmtuLen = pmtuLen,
      busWidth = busWidth,
      maxFragNum = maxFragNum
    )(innerLoopFunc)

  def readAtomicReqStreamMasterDriver[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      readAtomicRstCacheFull: => Boolean,
      maxFragNum: Int,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          ReqPktNum,
          RespPktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit = rdmaPktFragStreamMasterDriverHelper(
    stream,
    clockDomain,
    getRdmaPktDataFunc,
    pendingReqNumExceed = false,
    readAtomicRstCacheFull = readAtomicRstCacheFull,
    alwaysValid = false,
    isReadRespGen = false,
    isSendWriteReqOnly = false,
    isReadAtomicReqOnly = true,
    pmtuLen = pmtuLen,
    busWidth = busWidth,
    maxFragNum = maxFragNum
  )(innerLoopFunc)

  def readAtomicReqStreamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      readAtomicRstCacheFull: => Boolean,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          ReqPktNum,
          RespPktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit = rdmaPktFragStreamMasterDriverHelper(
    stream,
    clockDomain,
    getRdmaPktDataFunc,
    pendingReqNumExceed = false,
    readAtomicRstCacheFull = readAtomicRstCacheFull,
    alwaysValid = true,
    isReadRespGen = false,
    isSendWriteReqOnly = false,
    isReadAtomicReqOnly = true,
    pmtuLen = pmtuLen,
    busWidth = busWidth,
    maxFragNum = maxFragNum
  )(innerLoopFunc)

  def readRespPktFragStreamMasterDriver[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int,
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          ReqPktNum,
          RespPktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit = rdmaPktFragStreamMasterDriverHelper(
    stream,
    clockDomain,
    getRdmaPktDataFunc,
    pendingReqNumExceed = false,
    readAtomicRstCacheFull = false,
    alwaysValid = false,
    isReadRespGen = true,
    isSendWriteReqOnly = false,
    isReadAtomicReqOnly = false,
    pmtuLen = pmtuLen,
    busWidth = busWidth,
    maxFragNum = maxFragNum
  )(innerLoopFunc)

  def readRespPktFragStreamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          ReqPktNum,
          RespPktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit = rdmaPktFragStreamMasterDriverHelper(
    stream,
    clockDomain,
    getRdmaPktDataFunc,
    pendingReqNumExceed = false,
    readAtomicRstCacheFull = false,
    alwaysValid = true,
    isReadRespGen = true,
    isSendWriteReqOnly = false,
    isReadAtomicReqOnly = false,
    pmtuLen = pmtuLen,
    busWidth = busWidth,
    maxFragNum = maxFragNum
  )(innerLoopFunc)

  def rdmaReqPktFragStreamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      pendingReqNumExceed: => Boolean,
      readAtomicRstCacheFull: => Boolean,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          ReqPktNum,
          RespPktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit =
    rdmaPktFragStreamMasterDriverHelper(
      stream,
      clockDomain,
      getRdmaPktDataFunc,
      pendingReqNumExceed,
      readAtomicRstCacheFull,
      alwaysValid = true,
      isReadRespGen = false,
      isSendWriteReqOnly = false,
      isReadAtomicReqOnly = false,
      pmtuLen = pmtuLen,
      busWidth = busWidth,
      maxFragNum = maxFragNum
    )(innerLoopFunc)
  /*
  def readRespPktFragStreamMasterDriver[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int,
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit = {
    val (totalFragNumItr, pktNumItr, psnStartItr, payloadLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    val outerLoopBody = {
      val payloadFragNum = totalFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = payloadLenItr.next()

//      println(
//        f"${simTime()} time: pktNum=${pktNum}%X, totalFragNum=${totalFragNum}%X, psnStart=${psnStart}%X, totalLenBytes=${totalLenBytes}%X"
//      )
      val workReqOpCode = WorkReqOpCode.RDMA_READ
      (
        psnStart,
        payloadFragNum,
        pktNum,
        pmtuLen,
        busWidth,
        payloadLenBytes.toLong,
        workReqOpCode
      )
    }
    pktFragStreamMasterDriverHelper(
      stream,
      clockDomain,
      getRdmaPktDataFunc,
      alwaysValid = false,
      isReadRespGen = true
    )(outerLoopBody)(innerLoopFunc)
  }

  def readRespPktFragStreamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          PktLen,
          HeaderLen, // TODO: remove this field
          OpCode.Value
      ) => Unit
  ): Unit = {
    val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    val outerLoopBody = {
      val payloadFragNum = totalFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val payloadLenBytes = totalLenItr.next()

//      println(
//        f"${simTime()} time: pktNum=${pktNum}%X, totalFragNum=${totalFragNum}%X, psnStart=${psnStart}%X, totalLenBytes=${totalLenBytes}%X"
//      )
      val workReqOpCode = WorkReqOpCode.RDMA_READ
      (
        psnStart,
        payloadFragNum,
        pktNum,
        pmtuLen,
        busWidth,
        payloadLenBytes.toLong,
        workReqOpCode
      )
    }
    pktFragStreamMasterDriverHelper(
      stream,
      clockDomain,
      getRdmaPktDataFunc,
      alwaysValid = true,
      isReadRespGen = true
    )(outerLoopBody)(innerLoopFunc)
  }
   */
  private def buildPktMetaDataHelper(
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      opcode: OpCode.Value,
      payloadLenBytes: PktLen
  ) = {
    val maxPayloadFragNumPerPkt =
      SendWriteReqReadRespInputGen.maxFragNumPerPkt(pmtuLen, busWidth)
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
    val pmtuLenBytes = SendWriteReqReadRespInputGen.pmtuLenBytes(pmtuLen)
    val lastPktPadCnt = if (opcode.isReadReqPkt() || opcode.isAtomicReqPkt()) {
      0
    } else {
      (PAD_COUNT_FULL - (payloadLenBytes.toInt % PAD_COUNT_FULL)) % PAD_COUNT_FULL
    }

    val headerLenBytes = opcode.getPktHeaderLenBytes()

    (
      maxPayloadFragNumPerPkt,
      mtyWidth,
      pmtuLenBytes,
      lastPktPadCnt,
      headerLenBytes
    )
  }

  def computePktFragNum(
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      opcode: OpCode.Value,
      payloadLenBytes: PktLen,
      pktIdx: PktNum,
      pktNum: PktNum
  ): FragNum = {
    require(
      opcode.isSendReqPkt() || opcode.isWriteReqPkt() || opcode.isReadRespPkt(),
      s"${simTime()} time: only send/write requests and read response need to compute packet fragment number, but opcode=${opcode}"
    )

    val (
      maxPayloadFragNumPerPkt,
      mtyWidth,
      pmtuLenBytes,
      lastPktPadCnt,
      headerLenBytes
    ) = buildPktMetaDataHelper(pmtuLen, busWidth, opcode, payloadLenBytes)

    val lastOrOnlyPktTotalLenBytes = {
      val lastOrOnlyPktPayloadLenBytes =
        payloadLenBytes.toInt % pmtuLenBytes
      if (lastOrOnlyPktPayloadLenBytes == 0) {
        // In case last or only packet payload length is exactly pmtuLenBytes
        pmtuLenBytes + headerLenBytes + lastPktPadCnt
      } else {
        lastOrOnlyPktPayloadLenBytes + headerLenBytes + lastPktPadCnt
      }
    }

    val pktFragNum = if (pktIdx == pktNum - 1) { // Last or only packet
      val lastOrOnlyPktFragNum = lastOrOnlyPktTotalLenBytes / mtyWidth
      if (lastOrOnlyPktTotalLenBytes % mtyWidth == 0) {
        // In case last or only packet with header and padCnt length is exactly mtyWidth
        lastOrOnlyPktFragNum
      } else {
        lastOrOnlyPktFragNum + 1
      }
    } else {
      maxPayloadFragNumPerPkt + 1 // First or middle packet has one extra fragment for header
    }
    pktFragNum
  }

  private def setRdmaDataFrag(
      pktFrag: RdmaDataPkt,
      psn: PSN,
      opcode: OpCode.Value,
      fragIdx: FragIdx,
//      fragLast: FragLast,
      pktIdx: PktIdx,
      pktNum: PktNum,
      payloadLenBytes: PktLen,
      mty: BigInt,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value
  ): Unit = {
    pktFrag.bth.psn #= psn
    pktFrag.bth.opcodeFull #= opcode.id

    val (_, mtyWidth, pmtuLenBytes, lastPktPadCnt, headerLenBytes) =
      buildPktMetaDataHelper(pmtuLen, busWidth, opcode, payloadLenBytes)
    val padCnt = if (pktIdx == pktNum - 1) {
      lastPktPadCnt
    } else {
      0
    }

    require(
      pmtuLenBytes % mtyWidth == 0,
      s"${simTime()} time: invalid pmtuLenBytes=${pmtuLenBytes}, should be multiple of mtyWidth=${mtyWidth}"
    )
    require(
      headerLenBytes % PAD_COUNT_FULL == 0,
      s"${simTime()} time: invalid headerLenBytes=${headerLenBytes}, should be multiple of PAD_COUNT_FULL=${PAD_COUNT_FULL}"
    )
    require(
      mtyWidth > PAD_COUNT_FULL,
      s"${simTime()} time: mtyWidth=${mtyWidth} should > PAD_COUNT_FULL=${PAD_COUNT_FULL}"
    )
    if (!opcode.isReadReqPkt() && !opcode.isAtomicReqPkt()) {
      require(
        pktNum * pmtuLenBytes >= payloadLenBytes,
        s"${simTime()} time: pktNum * pmtuLenBytes=${pktNum * pmtuLenBytes} should >= payloadLenBytes=${payloadLenBytes}"
      )
      require(
        (pktNum - 1) * pmtuLenBytes < payloadLenBytes,
        s"${simTime()} time: (pktNum - 1) * pmtuLenBytes=${(pktNum - 1) * pmtuLenBytes} should < payloadLenBytes=${payloadLenBytes}"
      )
    }

    pktFrag.bth.padCnt #= padCnt
    pktFrag.bth.ackreq #= opcode.isLastOrOnlyReqPkt() // && fragLast
    pktFrag.mty #= mty

    if (fragIdx == 0 && pktIdx == 0 && opcode.hasReth()) {
      // Set DmaInfo for write/read requests
      val pktFragData = pktFrag.data.toBigInt
      pktFrag.data #= RethSim.setDlen(
        pktFragData,
        payloadLenBytes,
        busWidth
      )
    }
  }

  private def computeHeaderMty(
      headerLenBytes: HeaderLen,
      busWidth: BusWidth.Value
  ): MTY = {
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
    require(
      headerLenBytes <= mtyWidth,
      f"${simTime()} time: headerLenBytes=${headerLenBytes} should <= mtyWidth=${mtyWidth}"
    )

    val leftShiftAmt = mtyWidth - headerLenBytes
    setAllBits(headerLenBytes) << leftShiftAmt
  }

  private def computePktFragMty(
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      opcode: OpCode.Value,
      fragLast: FragLast,
      pktIdx: PktIdx,
      pktNum: PktNum,
      payloadLenBytes: PktLen
  ): MTY = {
    require(
      opcode.isSendReqPkt() || opcode.isWriteReqPkt() || opcode.isReadRespPkt(),
      s"${simTime()} time: only send/write requests and read response need to compute MTY, but opcode=${opcode}"
    )

    val (_, mtyWidth, pmtuLenBytes, lastPktPadCnt, headerLenBytes) =
      buildPktMetaDataHelper(pmtuLen, busWidth, opcode, payloadLenBytes)

    val mty = if (fragLast) {
      if (pktIdx == pktNum - 1) { // Final fragment of last or only packet
        val finalFragValidBytes =
          ((payloadLenBytes + headerLenBytes + lastPktPadCnt) % mtyWidth).toInt
        if (finalFragValidBytes == 0) {
          setAllBits(mtyWidth)
        } else {
          val leftShiftAmt = mtyWidth - finalFragValidBytes
          setAllBits(finalFragValidBytes) << leftShiftAmt
        }
      } else { // Last fragment of a first or middle packet
        val lastFragValidBytes = (pmtuLenBytes + headerLenBytes) % mtyWidth
        val leftShiftAmt = mtyWidth - lastFragValidBytes
        setAllBits(lastFragValidBytes) << leftShiftAmt
      }
    } else {
      setAllBits(mtyWidth)
    }
    mty
  }
  /*
  def readAtomicReqStreamMasterDriver[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      readAtomicRstCacheFull: => Boolean,
      maxFragNum: Int,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value
  )(
      innerLoopFunc: (
          PSN,
          PktLen,
          OpCode.Value
      ) => Unit
  ): Unit = readAtomicReqStreamMasterDriverHelper(
    stream,
    clockDomain,
    getRdmaPktDataFunc,
    readAtomicRstCacheFull,
    maxFragNum,
    pmtuLen,
    busWidth,
    alwaysValid = false
  )(innerLoopFunc)

  def readAtomicReqStreamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      readAtomicRstCacheFull: => Boolean,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      maxFragNum: Int
  )(
      innerLoopFunc: (
          PSN,
          PktLen,
          OpCode.Value
      ) => Unit
  ): Unit = readAtomicReqStreamMasterDriverHelper(
    stream,
    clockDomain,
    getRdmaPktDataFunc,
    readAtomicRstCacheFull,
    maxFragNum,
    pmtuLen,
    busWidth,
    alwaysValid = true
  )(innerLoopFunc)

  private def readAtomicReqStreamMasterDriverHelper[T <: Data](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getRdmaPktDataFunc: T => RdmaDataPkt,
      readAtomicRstCacheFull: => Boolean,
      maxFragNum: Int,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      alwaysValid: Boolean
  )(
      innerLoopFunc: (
          PSN,
          PktLen,
          OpCode.Value
      ) => Unit
  ): Unit = fork {
    stream.valid #= false
    clockDomain.waitSampling()

    val (totalFragNumItr, pktNumItr, psnStartItr, totalLenItr) =
      SendWriteReqReadRespInputGen.getItr(maxFragNum, pmtuLen, busWidth)

    while (true) {
      val _ = totalFragNumItr.next()
      val pktNum = pktNumItr.next()
      val psnStart = psnStartItr.next()
      val readDataLenBytes = totalLenItr.next()

      val isReadReq = pktNum > 1
//      for (reqIdx <- 0 until reqNum) {
      val opcode = if (isReadReq) {
        OpCode.RDMA_READ_REQUEST
      } else {
        val atomicOpCode = WorkReqSim.assignReadAtomicReqOpCode(
          WorkReqSim.randomAtomicOpCode()
//            workReqOpCode
        )
        atomicOpCode
      }
      val headerLenBytes = opcode.getPktHeaderLenBytes()
      val mty = computeHeaderMty(headerLenBytes, busWidth)

//        val psn = psnStart + reqIdx
      val fragLast = true

//      println(
//        f"${simTime()} time: opcode=${opcode}, pktNum=${pktNum}%X, fragLast=${fragLast}, mty=${mty}%X, PSN=${psnStart}%X, headerLenBytes=${headerLenBytes}%X, readDataLenBytes=${readDataLenBytes}%X, readAtomicRstCacheFull=${readAtomicRstCacheFull}"
//      )

      if (readAtomicRstCacheFull) {
        clockDomain.waitSamplingWhere(!readAtomicRstCacheFull)
      }
      do {
        if (alwaysValid) {
          stream.valid #= true
        } else {
          stream.valid.randomize()
        }
        stream.payload.randomize()
        sleep(0)
        if (!stream.valid.toBoolean) {
          clockDomain.waitSampling()
        }
      } while (!stream.valid.toBoolean)

      stream.last #= fragLast // Set last must after set payload, since last is part of the payload
      val pktFragData = getRdmaPktDataFunc(stream.fragment)
      setRdmaDataFrag(
        pktFragData,
        psnStart,
        opcode,
        fragIdx = 0,
//              fragLast,
        pktIdx = 0,
        pktNum = 1, // Since read/atomic request itself has only 1 packet
        payloadLenBytes = readDataLenBytes.toLong,
        mty,
        pmtuLen,
        busWidth
      )

      innerLoopFunc(
        psnStart,
        readDataLenBytes.toLong,
        opcode
      )
      clockDomain.waitSamplingWhere(
        stream.valid.toBoolean && stream.ready.toBoolean
      )
      stream.valid #= false
//      }
    }
  }
   */
}

object DmaReadRespSim {
  def pktFragStreamMasterDriver[T <: Data, InternalData](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getDmaReadRespPktDataFunc: T => DmaReadResp,
      segmentRespByPmtu: Boolean
  )(
      outerLoopBody: => (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          PktLen
      )
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          PktLen
      ) => Unit
  ): Unit = pktFragStreamMasterDriverHelper(
    stream,
    clockDomain,
    getDmaReadRespPktDataFunc,
    segmentRespByPmtu,
    alwaysValid = false
  )(outerLoopBody)(innerLoopFunc)

  def pktFragStreamMasterDriverAlwaysValid[T <: Data, InternalData](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getDmaReadRespPktDataFunc: T => DmaReadResp,
      segmentRespByPmtu: Boolean
  )(
      outerLoopBody: => (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          PktLen
      )
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          PktLen
      ) => Unit
  ): Unit = pktFragStreamMasterDriverHelper(
    stream,
    clockDomain,
    getDmaReadRespPktDataFunc,
    segmentRespByPmtu,
    alwaysValid = true
  )(outerLoopBody)(innerLoopFunc)

  private def pktFragStreamMasterDriverHelper[T <: Data, InternalData](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain,
      getDmaReadRespPktDataFunc: T => DmaReadResp,
      segmentRespByPmtu: Boolean,
      alwaysValid: Boolean
  )(
      outerLoopBody: => (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          PktLen
      )
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          PktLen
      ) => Unit
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      // Outer loop
      while (true) {
        val (
          psnStart,
          totalFragNum,
          pktNum,
          pmtuLen,
          busWidth,
          payloadLenBytes
        ) =
          outerLoopBody
        val maxFragNumPerPkt =
          SendWriteReqReadRespInputGen.maxFragNumPerPkt(pmtuLen, busWidth)

        // Inner loop
        for (fragIdx <- 0 until totalFragNum) {
          val pktIdx = fragIdx / maxFragNumPerPkt
          val psn = psnStart + pktIdx
          val fragLast = MiscUtils.isFragLast(
            fragIdx,
            maxFragNumPerPkt,
            totalFragNum,
            segmentRespByPmtu
          )
//          println(
//            f"${simTime()} time: pktIdx=${pktIdx}%X, pktNum=${pktNum}%X, fragIdx=${fragIdx}%X, totalFragNum=${totalFragNum}%X, fragLast=${fragLast}, PSN=${psn}%X, maxFragNumPerPkt=${maxFragNumPerPkt}%X"
//          )

//          val fragLast = if (segmentRespByPmtu) {
//            ((fragIdx % maxFragNumPerPkt) == (maxFragNumPerPkt - 1)) || (fragIdx == totalFragNum - 1)
//          } else {
//            fragIdx == totalFragNum - 1
//          }

          do {
            if (alwaysValid) {
              stream.valid #= true
            } else {
              stream.valid.randomize()
            }
            stream.payload.randomize()
            sleep(0)
            if (!stream.valid.toBoolean) {
              clockDomain.waitSampling()
            }
          } while (!stream.valid.toBoolean)

          stream.last #= fragLast // Set last must after set payload, since last is part of the payload
          setReadResp(
            getDmaReadRespPktDataFunc(stream.payload),
            psnStart,
            fragIdx,
            totalFragNum,
            payloadLenBytes,
            busWidth
          )

//          do {
//            stream.valid.randomize()
//            stream.payload.randomize()
//            sleep(0)
//            if (stream.valid.toBoolean) {
          innerLoopFunc(
            psn,
            psnStart,
            fragLast,
            fragIdx,
            totalFragNum,
            pktIdx,
            pktNum,
            payloadLenBytes
          )
          if (fragIdx == totalFragNum - 1) {
            pktIdx shouldBe (pktNum - 1) withClue
              f"${simTime()} time: this fragment with fragIdx=${fragIdx}%X is the last one, pktIdx=${pktIdx}%X should equal pktNum=${pktNum}%X-1"
          }
          clockDomain.waitSamplingWhere(
            stream.valid.toBoolean && stream.ready.toBoolean
          )
//            } else {
//              clockDomain.waitSampling()
//            }
//          } while (!stream.valid.toBoolean)
        }
        // Set stream.valid to false, since outerLoopBody might consume simulation time
        stream.valid #= false
      }
    }

  private def setReadResp(
      dmaReadResp: DmaReadResp,
      psnStart: PsnStart,
      fragIdx: FragIdx,
      totalFragNum: FragNum,
      totalLenBytes: PktLen,
      busWidth: BusWidth.Value
  ) = {
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
    val isLastPktFrag = fragIdx == totalFragNum - 1

    val mty = if (isLastPktFrag) {
      val residue = (totalLenBytes % mtyWidth).toInt
      if (residue == 0) {
        setAllBits(mtyWidth) // Last fragment has full valid data
      } else {
        val leftShiftAmt = mtyWidth - residue
        // Last fragment has partial valid data
        setAllBits(residue) << leftShiftAmt
      }
    } else {
      setAllBits(mtyWidth)
    }
    dmaReadResp.psnStart #= psnStart
    dmaReadResp.mty #= mty
    dmaReadResp.lenBytes #= totalLenBytes
    sleep(0) // To make assignment take effective
  }
}

object RqNakSim {
  def matchNakType(
      rqNak: RqNakNotifier,
      expectNakType: SpinalEnumElement[AckType.type]
  ): Unit = {
    val pulse = expectNakType match {
      case AckType.NAK_RNR     => rqNak.rnr.pulse
      case AckType.NAK_SEQ     => rqNak.seqErr.pulse
      case AckType.NAK_INV     => rqNak.invReq
      case AckType.NAK_RMT_ACC => rqNak.rmtAcc
      case AckType.NAK_RMT_OP  => rqNak.rmtOp
      case _ => SpinalExit(s"${simTime()} time: rqNak=${rqNak} is not NAK")
    }

    pulse.toBoolean shouldBe true withClue
      f"${simTime()} time: rqNak=${rqNak} not match expected NAK=${expectNakType}"
  }
}

object QpCtrlSim {
  object RqSubState extends SpinalEnum {
    val WAITING, NORMAL, NAK_SEQ, RNR_TIMEOUT, RNR = newElement()
  }

  object SqSubState extends SpinalEnum {
    val WAITING, NORMAL, RETRY_FLUSH, RETRY_WORK_REQ, DRAINING, DRAINED,
        COALESCE, ERR_FLUSH = newElement()
  }

//  val rxQCtrl = RxQCtrl()
//  val txQCtrl = TxQCtrl()
//  val qpAttr = QpAttrData()
//  val rqSubState = RqSubState()
//  val sqSubState = SqSubState()

  private def initQpAttrData(qpAttr: QpAttrData, pmtuLen: PMTU.Value): Unit = {
    qpAttr.npsn #= INIT_PSN
    qpAttr.epsn #= INIT_PSN
    qpAttr.rqOutPsn #= INIT_PSN
    qpAttr.sqOutPsn #= INIT_PSN
    qpAttr.pmtu #= pmtuLen.id

    qpAttr.maxDstPendingReadAtomicWorkReqNum #= MAX_PENDING_READ_ATOMIC_REQ_NUM
    qpAttr.maxPendingReadAtomicWorkReqNum #= MAX_PENDING_READ_ATOMIC_REQ_NUM
    qpAttr.maxPendingWorkReqNum #= MAX_PENDING_REQ_NUM
    qpAttr.maxDstPendingWorkReqNum #= MAX_PENDING_REQ_NUM

    qpAttr.rqPreReqOpCode #= OpCode.SEND_ONLY.id
    qpAttr.retryStartPsn #= INIT_PSN
    qpAttr.retryReason #= RetryReason.RESP_TIMEOUT
    qpAttr.maxRetryCnt #= DEFAULT_MAX_RETRY_CNT
    qpAttr.maxRnrRetryCnt #= DEFAULT_MAX_RNR_RETRY_CNT
    qpAttr.minRnrTimeOut #= DEFAULT_MIN_RNR_TIME_OUT
    qpAttr.negotiatedRnrTimeOut #= DEFAULT_RNR_TIME_OUT
    qpAttr.receivedRnrTimeOut #= DEFAULT_RNR_TIME_OUT
    qpAttr.respTimeOut #= DEFAULT_RESP_TIME_OUT

    qpAttr.state #= QpState.RESET
//    rqSubState #= RqSubState.WAITING
//    sqSubState #= SqSubState.WAITING
  }

  private def rqHasFatalNak(rqNotifier: RqNotifier): Boolean = {
    rqNotifier.nak.rmtAcc.toBoolean ||
    rqNotifier.nak.rmtOp.toBoolean ||
    rqNotifier.nak.invReq.toBoolean
  }

//  private def updateQpAttr(thatQpAttr: QpAttrData): Unit = {
//    thatQpAttr.ipv4Peer := qpAttr.ipv4Peer
//    thatQpAttr.pdId := qpAttr.pdId
//    thatQpAttr.epsn := qpAttr.epsn
//    thatQpAttr.npsn := qpAttr.npsn
//    thatQpAttr.rqOutPsn := qpAttr.rqOutPsn
//    thatQpAttr.sqOutPsn := qpAttr.sqOutPsn
//    thatQpAttr.pmtu := qpAttr.pmtu
//    thatQpAttr.maxPendingReadAtomicWorkReqNum := qpAttr.maxPendingReadAtomicWorkReqNum
//    thatQpAttr.maxDstPendingReadAtomicWorkReqNum := qpAttr.maxDstPendingReadAtomicWorkReqNum
//    thatQpAttr.maxPendingWorkReqNum := qpAttr.maxPendingWorkReqNum
//    thatQpAttr.maxDstPendingWorkReqNum := qpAttr.maxDstPendingWorkReqNum
//    thatQpAttr.sqpn := qpAttr.sqpn
//    thatQpAttr.dqpn := qpAttr.dqpn
//
//    thatQpAttr.rqPreReqOpCode := qpAttr.rqPreReqOpCode
//    thatQpAttr.retryStartPsn := qpAttr.retryStartPsn
//    thatQpAttr.retryReason := qpAttr.retryReason
//    thatQpAttr.maxRetryCnt := qpAttr.maxRetryCnt
//    thatQpAttr.maxRnrRetryCnt := qpAttr.maxRnrRetryCnt
//    thatQpAttr.minRnrTimeOut := qpAttr.minRnrTimeOut
//    thatQpAttr.negotiatedRnrTimeOut := qpAttr.negotiatedRnrTimeOut
//    thatQpAttr.receivedRnrTimeOut := qpAttr.receivedRnrTimeOut
//    thatQpAttr.respTimeOut := qpAttr.respTimeOut
//
//    thatQpAttr.state := qpAttr.state
//  }

  def getMaxPendingWorkReqNum(qpAttr: QpAttrData): Int = {
    if (
      qpAttr.maxDstPendingWorkReqNum.toInt < qpAttr.maxPendingWorkReqNum.toInt
    ) {
      qpAttr.maxDstPendingWorkReqNum.toInt
    } else {
      qpAttr.maxPendingWorkReqNum.toInt
    }
  }

  def getMaxPendingReadAtomicWorkReqNum(qpAttr: QpAttrData): Int = {
    if (
      qpAttr.maxDstPendingReadAtomicWorkReqNum.toInt < qpAttr.maxPendingReadAtomicWorkReqNum.toInt
    ) {
      qpAttr.maxDstPendingReadAtomicWorkReqNum.toInt
    } else {
      qpAttr.maxPendingReadAtomicWorkReqNum.toInt
    }
  }

  def assignDefaultQpAttrAndRxCtrl(
      qpAttr: QpAttrData,
      pmtuLen: PMTU.Value,
      rxQCtrl: RxQCtrl
  ): Unit = {
    initQpAttrData(qpAttr, pmtuLen)

    rxQCtrl.stateErrFlush #= false
    rxQCtrl.flush #= false
    rxQCtrl.rnrFlush #= false
    rxQCtrl.rnrTimeOut #= false
    rxQCtrl.nakSeqTrigger #= false
  }

  def assignDefaultQpAttrAndTxCtrl(
      qpAttr: QpAttrData,
      pmtuLen: PMTU.Value,
      txQCtrl: TxQCtrl
  ): Unit = {
    initQpAttrData(qpAttr, pmtuLen)

    txQCtrl.errorFlush #= false
    txQCtrl.retry #= false
    txQCtrl.retryStartPulse #= false
    txQCtrl.retryFlush #= false
    txQCtrl.wrongStateFlush #= false
  }

  def connectRecvQ(
      clockDomain: ClockDomain,
      pmtuLen: PMTU.Value,
      rqPsnInc: RqPsnInc,
      rqNotifier: RqNotifier,
      qpAttr: QpAttrData,
      rxQCtrl: RxQCtrl
  ) = {
    initQpAttrData(qpAttr, pmtuLen)
    qpAttr.state #= QpState.RTR
    var rqSubState = RqSubState.NORMAL

    fork {
      while (true) {
//        updateQpAttr(thatQpAttr)
        clockDomain.waitSampling()
        if (rqPsnInc.epsn.inc.toBoolean) {
          qpAttr.epsn #= qpAttr.epsn.toInt + rqPsnInc.epsn.incVal.toInt
          qpAttr.rqPreReqOpCode #= rqPsnInc.epsn.preReqOpCode.toInt
        }
        if (rqPsnInc.opsn.inc.toBoolean) {
          qpAttr.rqOutPsn #= rqPsnInc.opsn.psnVal.toInt
        }

        if (
          qpAttr.state.toEnum == QpState.RTR || qpAttr.state.toEnum == QpState.RTS
        ) {
          if (rqHasFatalNak(rqNotifier)) {
            qpAttr.state #= QpState.ERR
            rqSubState = RqSubState.WAITING
          } else if (rqNotifier.nak.rnr.pulse.toBoolean) {
            rqSubState = RqSubState.RNR_TIMEOUT
          } else if (rqNotifier.nak.seqErr.pulse.toBoolean) {
            rqSubState = RqSubState.NAK_SEQ
          }
        }
        // TODO: check RQ behavior under ERR

        val isQpStateWrong =
          qpAttr.state.toEnum == QpState.ERR ||
            qpAttr.state.toEnum == QpState.INIT ||
            qpAttr.state.toEnum == QpState.RESET
        // RQ flush
        rxQCtrl.stateErrFlush #= isQpStateWrong
        rxQCtrl.nakSeqTrigger #= rqSubState == RqSubState.NAK_SEQ
        rxQCtrl.rnrFlush #= rqSubState == RqSubState.RNR
        rxQCtrl.rnrTimeOut #= rqSubState == RqSubState.RNR_TIMEOUT
        rxQCtrl.flush #= rxQCtrl.stateErrFlush.toBoolean || rxQCtrl.rnrFlush.toBoolean || rxQCtrl.nakSeqTrigger.toBoolean
      }
    }
  }

  def connectSendQ(
      clockDomain: ClockDomain,
      pmtuLen: PMTU.Value,
      sqPsnInc: SqPsnInc,
      sqNotifier: SqNotifier,
      qpAttr: QpAttrData,
      txQCtrl: TxQCtrl
  ) = {
    initQpAttrData(qpAttr, pmtuLen)
    qpAttr.state #= QpState.RTS
    var sqSubState = SqSubState.NORMAL

    fork {
      while (true) {
//        updateQpAttr(thatQpAttr)
        clockDomain.waitSampling()
        if (qpAttr.state.toEnum == QpState.RTS) {
          if (sqPsnInc.npsn.inc.toBoolean) {
            qpAttr.npsn #= qpAttr.npsn.toInt + sqPsnInc.npsn.incVal.toInt
          }
          if (sqPsnInc.opsn.inc.toBoolean) {
            qpAttr.sqOutPsn #= sqPsnInc.opsn.psnVal.toInt
          }
        }

        if (qpAttr.state.toEnum == QpState.RTS) {
          // TODO: support SQD
//          if () {
//            qpAttr.state #= QpState.SQD
//            sqSubState #= SqSubState.DRAINING
//          }
          if (sqNotifier.err.pulse.toBoolean) {
            qpAttr.state #= QpState.ERR
            sqSubState = SqSubState.COALESCE
          } else if (
            sqSubState == SqSubState.NORMAL && sqNotifier.retry.pulse.toBoolean
          ) {
            sqSubState = SqSubState.RETRY_FLUSH
          } else if (
            sqSubState == SqSubState.RETRY_FLUSH && sqNotifier.retryClear.retryFlushDone.toBoolean
          ) {
            sqSubState = SqSubState.RETRY_WORK_REQ
          } else if (
            sqSubState == SqSubState.RETRY_WORK_REQ && sqNotifier.retryClear.retryWorkReqDone.toBoolean
          ) {
            sqSubState = SqSubState.NORMAL
          }
        } else if (qpAttr.state.toEnum == QpState.ERR) {
          if (
            sqSubState == SqSubState.COALESCE && sqNotifier.coalesceAckDone.toBoolean
          ) {
            sqSubState = SqSubState.ERR_FLUSH
          }
        } else if (qpAttr.state.toEnum == QpState.SQD) {
          if (
            sqSubState == SqSubState.DRAINING && sqNotifier.workReqCacheEmpty.toBoolean
          ) {
            sqSubState = SqSubState.DRAINED
          }
        }

        val isQpStateWrong =
          qpAttr.state.toEnum == QpState.ERR ||
            qpAttr.state.toEnum == QpState.INIT ||
            qpAttr.state.toEnum == QpState.RESET

        val fsmInRetryState = sqSubState == SqSubState.RETRY_FLUSH ||
          sqSubState == SqSubState.RETRY_WORK_REQ
        val retryFlushState = sqSubState == SqSubState.RETRY_FLUSH

        txQCtrl.errorFlush #= sqSubState == SqSubState.ERR_FLUSH
        txQCtrl.retry #= fsmInRetryState
        txQCtrl.retryStartPulse #= sqNotifier.retry.pulse.toBoolean
        txQCtrl.retryFlush #= retryFlushState
        txQCtrl.wrongStateFlush #= isQpStateWrong
      }
    }
  }
}
