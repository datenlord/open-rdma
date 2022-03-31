package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import ConstantSettings._
import OpCodeSim._
import RdmaConstants._
import RdmaTypeReDef._
import StreamSimUtil._

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
//  type PktLast = Boolean
  type PSN = Int
  type PsnStart = Int
  type PsnEnd = Int
  type QueryPsn = Int
  type QuerySuccess = Boolean
  type QPN = Int
  type PktFragData = BigInt
  type WorkReqId = BigInt
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

  type AethRsvd = Int
  type AethCode = Int
  type AethValue = Int
  type AethMsn = Int

  type AtomicComp = BigInt
  type AtomicSwap = BigInt
  type AtomicOrig = BigInt
}

object PsnSim {
  implicit def build(that: PSN) = new PsnSim(that)

  def psnCmp(psnA: PSN, psnB: PSN, curPsn: PSN): Int = {
    require(
      psnA >= 0 && psnB >= 0 && curPsn >= 0,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB}, curPsn=${curPsn} should all >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN && curPsn < TOTAL_PSN,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB}, curPsn=${curPsn} should all < TOTAL_PSN=${TOTAL_PSN}"
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
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both < TOTAL_PSN=${TOTAL_PSN}"
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

  /** psnA + psnB, modulo by TOTAL_PSN
    */
  def psnAdd(psnA: Int, psnB: Int): Int = {
    require(
      psnA >= 0 && psnB >= 0,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both < TOTAL_PSN=${TOTAL_PSN}"
    )
    (psnA + psnB) % TOTAL_PSN
  }
}

class PsnSim(val psn: PSN) {
//  require(
//    psn >= 0 && psn < TOTAL_PSN,
//    f"${simTime()} time: PSN value PSN=${psn}%X must >= 0 and < TOTAL_PSN=${TOTAL_PSN}%X"
//  )

  def +%(that: PSN): PSN = {
    require(
      that >= 0 && that < TOTAL_PSN,
      f"${simTime()} time: PSN value that=${that}%X must >= 0 and < TOTAL_PSN=${TOTAL_PSN}%X"
    )

    (psn + that) % TOTAL_PSN
  }

  def -%(that: PSN): PSN = {
//    require(
//      that >= 0 && that < TOTAL_PSN,
//      f"${simTime()} time: PSN value that=${that}%X must >= 0 and < TOTAL_PSN=${TOTAL_PSN}%X"
//    )

    (TOTAL_PSN + psn - that) % TOTAL_PSN
  }
}

object WorkCompSim {
  def setOpCodeFromSqWorkReqOpCode(
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
      case _ => {
        println(
          L"${simTime()} time: no matched WC opcode at SQ side for WR opcode=${workReqOpCode} when in simulation"
        )
        ???
      }
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
      case _ => {
        println(
          f"${simTime()} time: invalid AckType=${ackType} to match WorkCompStatus"
        )
        ???
      }
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
      case _ => {
        println(
          f"${simTime()} time: RQ side WC opcode no match for request opcode=${reqOpCode}"
        )
        ??? // Just break on no match
      }
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
      case _ => ??? // Just break on no match
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
      case _ => ??? // Just break on no match
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

  def randomReadAtomicOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = WorkReqOpCode.RDMA_READ +: workReqAtomic
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      f"${simTime()} time: WR ReadAtomicOpCode should contain ${result}"
    )
    result
  }

  def randomSendWriteOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = workReqSend ++ workReqWrite
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      f"${simTime()} time: WR SendWriteOpCode should contain ${result}"
    )
    result
  }

  def randomSendWriteImmOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = workReqSend :+ WorkReqOpCode.RDMA_WRITE_WITH_IMM
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      f"${simTime()} time: WR SendWriteImmOpCode should contain ${result}"
    )
    result
  }

  def randomSendWriteReadOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = WorkReqOpCode.RDMA_READ +: (workReqSend ++ workReqWrite)
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    require(
      opCodes.contains(result),
      f"${simTime()} time: WR SendWriteReadOpCode should contain ${result}"
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
      f"${simTime()} time: WR SendWriteReadAtomicOpCode should contain ${result}"
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
      case WorkReqOpCode.SEND => {
        if (pktNum == 1) {
          OpCode.SEND_ONLY
        } else if (pktIdx == 0) {
          OpCode.SEND_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.SEND_LAST
        } else {
          OpCode.SEND_MIDDLE
        }
      }
      case WorkReqOpCode.SEND_WITH_IMM => {
        if (pktNum == 1) {
          OpCode.SEND_ONLY_WITH_IMMEDIATE
        } else if (pktIdx == 0) {
          OpCode.SEND_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.SEND_LAST_WITH_IMMEDIATE
        } else {
          OpCode.SEND_MIDDLE
        }
      }
      case WorkReqOpCode.SEND_WITH_INV => {
        if (pktNum == 1) {
          OpCode.SEND_ONLY_WITH_INVALIDATE
        } else if (pktIdx == 0) {
          OpCode.SEND_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.SEND_LAST_WITH_INVALIDATE
        } else {
          OpCode.SEND_MIDDLE
        }
      }
      case WorkReqOpCode.RDMA_WRITE => {
        if (pktNum == 1) {
          OpCode.RDMA_WRITE_ONLY
        } else if (pktIdx == 0) {
          OpCode.RDMA_WRITE_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.RDMA_WRITE_LAST
        } else {
          OpCode.RDMA_WRITE_MIDDLE
        }
      }
      case WorkReqOpCode.RDMA_WRITE_WITH_IMM => {
        if (pktNum == 1) {
          OpCode.RDMA_WRITE_ONLY_WITH_IMMEDIATE
        } else if (pktIdx == 0) {
          OpCode.RDMA_WRITE_FIRST
        } else if (pktIdx == pktNum - 1) {
          OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE
        } else {
          OpCode.RDMA_WRITE_MIDDLE
        }
      }
      case WorkReqOpCode.RDMA_READ            => OpCode.RDMA_READ_REQUEST
      case WorkReqOpCode.ATOMIC_CMP_AND_SWP   => OpCode.COMPARE_SWAP
      case WorkReqOpCode.ATOMIC_FETCH_AND_ADD => OpCode.FETCH_ADD
      case _ => {
        println(f"invalid WR opcode=${workReqOpCode} to assign")
        ???
      }
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
      case _ => {
        println(f"invalid WR opcode=${workReqOpCode} to assign")
        ???
      }
    }
    allHeaderLen / BYTE_WIDTH
  }
}

object AckTypeSim {
  val retryNakTypes = Seq(AckType.NAK_RNR, AckType.NAK_SEQ)
  val fatalNakType =
    Seq(AckType.NAK_INV, AckType.NAK_RMT_ACC, AckType.NAK_RMT_OP)

  def randomRetryNak(): SpinalEnumElement[AckType.type] = {
    val nakTypes = retryNakTypes
    val randIdx = scala.util.Random.nextInt(nakTypes.size)
    val result = nakTypes(randIdx)
    require(
      nakTypes.contains(result),
      f"${simTime()} time: retryNakTypes should contains ${result}"
    )
    result
  }

  def randomFatalNak(): SpinalEnumElement[AckType.type] = {
    val nakTypes = fatalNakType
    val randIdx = scala.util.Random.nextInt(nakTypes.size)
    val result = nakTypes(randIdx)
    require(
      nakTypes.contains(result),
      f"${simTime()} time: fatalNakType should contains ${result}"
    )
    result
  }

  def randomNormalAckOrFatalNak(): SpinalEnumElement[AckType.type] = {
    val ackTypes = AckType.NORMAL +: fatalNakType
    val randIdx = scala.util.Random.nextInt(ackTypes.size)
    val result = ackTypes(randIdx)
    require(
      ackTypes.contains(result),
      f"${simTime()} time: ackTypes should contains ${result}"
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

  def decodeFromAeth(aeth: AETH): SpinalEnumElement[AckType.type] = {
    val showCodeAndValue = (code: Int, value: Int) => {
      println(
        f"${simTime()} time: dut.io.rx.aeth.code=${code}, dut.io.rx.aeth.value=${value}"
      )
    }

    val code = aeth.code.toInt
    val value = aeth.value.toInt

    // TODO: change AethCode to SpinalEnum
    code match {
      case 0 /* AethCode.ACK.id */ => AckType.NORMAL
      case 1 /* AethCode.RNR.id */ => AckType.NAK_RNR
      case 2 /* AethCode.RSVD.id */ => {
        showCodeAndValue(code, value)
        ???
      }
      case 3 /* AethCode.NAK.id */ => {
        value match {
          case 0 /* NakCode.SEQ.id */     => AckType.NAK_SEQ
          case 1 /* NakCode.INV.id */     => AckType.NAK_INV
          case 2 /* NakCode.RMT_ACC.id */ => AckType.NAK_RMT_ACC
          case 3 /* NakCode.RMT_OP.id */  => AckType.NAK_RMT_OP
          case 4 /* NakCode.INV_RD.id */ => {
            showCodeAndValue(code, value)
            ???
          }
          case 5 /* NakCode.RSVD.id */ => {
            showCodeAndValue(code, value)
            ???
          }
          case _ => {
            showCodeAndValue(code, value)
            ???
          }
        }
      }
      case _ => {
        showCodeAndValue(code, value)
        ???
      }
    }
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
      f"${simTime()} time: input busWidth=${busWidth.id} should >= widthOf(BTH())=${bthWidth} + widthOf(AETH())=${aethWidth}"
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
        case _                   => ???
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
      f"${simTime()} time: input busWidth=${busWidth.id} should >= widthOf(BTH())=${bthWidth} + widthOf(AtomicEth())=${atomicEthWidth}"
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
      f"${simTime()} time: input busWidth=${busWidth.id} should >= widthOf(BTH())=${bthWidth} + width(AETH())=${aethWidth} + widthOf(AtomicAckEth())=${atomicAckEthWidth}"
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
      f"${simTime()} time: input busWidth=${busWidth.id} should >= widthOf(BTH())=${bthWidth} + widthOf(RETH())=${rethWidth}"
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
      f"${simTime()} time: AtomicOpCode should contain ${result}"
    )
    result
  }

  implicit class OpCodeExt(val opcode: OpCode.Value) {
    def getPktHeaderLenBytes(): Int = {
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
        case OpCode.RDMA_WRITE_LAST_WITH_IMMEDIATE             => bthWidth + immDtWidth
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
        case _ => {
          println(f"${simTime()} time: invalid opcode=${opcode}")
          ???
        }
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

object ReadAtomicRstCacheSim {
  def alwaysStreamFireAndRespSuccess(
      readAtomicRstCacheQuery: ReadAtomicRstCacheQueryBus,
      queryOpCode: OpCode.Value,
      clockDomain: ClockDomain
  ) = {
    simHelper(
      readAtomicRstCacheQuery,
      queryOpCode,
      clockDomain,
      alwaysValid = true,
      alwaysSuccess = true
    )
  }

  def alwaysStreamFireAndRespFailure(
      readAtomicRstCacheQuery: ReadAtomicRstCacheQueryBus,
      queryOpCode: OpCode.Value,
      clockDomain: ClockDomain
  ) = {
    simHelper(
      readAtomicRstCacheQuery,
      queryOpCode,
      clockDomain,
      alwaysValid = true,
      alwaysSuccess = false
    )
  }

  def randomStreamFireAndRespSuccess(
      readAtomicRstCacheQuery: ReadAtomicRstCacheQueryBus,
      queryOpCode: OpCode.Value,
      clockDomain: ClockDomain
  ) = {
    simHelper(
      readAtomicRstCacheQuery,
      queryOpCode,
      clockDomain,
      alwaysValid = false,
      alwaysSuccess = true
    )
  }

  def randomStreamFireAndRespFailure(
      readAtomicRstCacheQuery: ReadAtomicRstCacheQueryBus,
      queryOpCode: OpCode.Value,
      clockDomain: ClockDomain
  ) = {
    simHelper(
      readAtomicRstCacheQuery,
      queryOpCode,
      clockDomain,
      alwaysValid = false,
      alwaysSuccess = false
    )
  }

  def simHelper(
      readAtomicRstCacheQuery: ReadAtomicRstCacheQueryBus,
      queryOpCode: OpCode.Value,
      clockDomain: ClockDomain,
      alwaysValid: Boolean,
      alwaysSuccess: Boolean
  ) = {

    val onReqFire =
      (reqData: ReadAtomicRstCacheReq, reqQueue: mutable.Queue[PSN]) => {
        reqQueue.enqueue(reqData.psn.toInt)
        ()
      }

    val buildResp =
      (respData: ReadAtomicRstCacheResp, reqQueue: mutable.Queue[PSN]) => {
        val queryPsn = reqQueue.dequeue()
        respData.rstCacheData.opcode #= queryOpCode.id
        respData.rstCacheData.psnStart #= queryPsn // TODO: not support partial retry
        respData.query.psn #= queryPsn
        respData.found #= alwaysSuccess
      }

    val onRespFire = (
        respData: ReadAtomicRstCacheResp,
        respQueue: mutable.Queue[
          (QueryPsn, QuerySuccess, PsnStart, PhysicalAddr, PktNum, PktLen)
        ]
    ) => {
      respQueue.enqueue(
        (
          respData.query.psn.toInt,
          respData.found.toBoolean,
          respData.rstCacheData.psnStart.toInt,
          respData.rstCacheData.pa.toBigInt,
          respData.rstCacheData.pktNum.toInt,
          respData.rstCacheData.dlen.toLong
        )
      )
      ()
    }

    queryCacheHelper(
      reqStream = readAtomicRstCacheQuery.req,
      respStream = readAtomicRstCacheQuery.resp,
      onReqFire = onReqFire,
      buildResp = buildResp,
      onRespFire = onRespFire,
      clockDomain = clockDomain,
      alwaysValid = alwaysValid
    )
  }
}

object AddrCacheSim {
  def alwaysStreamFireAndRespSuccess(
      addrCacheRead: QpAddrCacheAgentReadBus,
      clockDomain: ClockDomain
  ) = {
    simHelper(
      addrCacheRead,
      clockDomain,
      alwaysValid = true,
      alwaysSuccess = true
    )
  }

  def alwaysStreamFireAndRespFailure(
      addrCacheRead: QpAddrCacheAgentReadBus,
      clockDomain: ClockDomain
  ) = {
    simHelper(
      addrCacheRead,
      clockDomain,
      alwaysValid = true,
      alwaysSuccess = false
    )
  }

  def randomStreamFireAndRespSuccess(
      addrCacheRead: QpAddrCacheAgentReadBus,
      clockDomain: ClockDomain
  ) = {
    simHelper(
      addrCacheRead,
      clockDomain,
      alwaysValid = false,
      alwaysSuccess = true
    )
  }

  def randomStreamFireAndRespFailure(
      addrCacheRead: QpAddrCacheAgentReadBus,
      clockDomain: ClockDomain
  ) = {
    simHelper(
      addrCacheRead,
      clockDomain,
      alwaysValid = false,
      alwaysSuccess = false
    )
  }
  /*
  def simHelper(
      addrCacheRead: QpAddrCacheAgentReadBus,
      clockDomain: ClockDomain,
      alwaysValid: Boolean,
      alwaysSuccess: Boolean
  ) = {
    val addrCacheReadReqQueue = mutable.Queue[(PSN, VirtualAddr)]()
    val addrCacheReadRespQueue =
      mutable.Queue[(PSN, KeyValid, SizeValid, AccessValid, PhysicalAddr)]()

    val onReq = () => {
      addrCacheReadReqQueue.enqueue(
        (addrCacheRead.req.psn.toInt, addrCacheRead.req.va.toBigInt)
      )
//        println(
//          f"${simTime()} time: dut.io.addrCacheRead.req received PSN=${dut.io.addrCacheRead.req.psn.toInt}%X"
//        )
    }

    val onResp = () => {
      val (psn, _) = addrCacheReadReqQueue.dequeue()
      addrCacheRead.resp.psn #= psn
      if (alwaysSuccess) {
        addrCacheRead.resp.keyValid #= true
        addrCacheRead.resp.sizeValid #= true
        addrCacheRead.resp.accessValid #= true
      } else {
        addrCacheRead.resp.keyValid #= false
        addrCacheRead.resp.sizeValid #= false
        addrCacheRead.resp.accessValid #= false
      }
    }

    if (alwaysValid) {
      onReceiveStreamReqAndThenResponseAlways(
        reqStream = addrCacheRead.req,
        respStream = addrCacheRead.resp,
        clockDomain
      ) {
        onReq()
      } {
        onResp()
      }
    } else {
      onReceiveStreamReqAndThenResponseRandom(
        reqStream = addrCacheRead.req,
        respStream = addrCacheRead.resp,
        clockDomain
      ) {
        onReq()
      } {
        onResp()
      }
    }

    onStreamFire(addrCacheRead.resp, clockDomain) {
      addrCacheReadRespQueue.enqueue(
        (
          addrCacheRead.resp.psn.toInt,
          addrCacheRead.resp.keyValid.toBoolean,
          addrCacheRead.resp.sizeValid.toBoolean,
          addrCacheRead.resp.accessValid.toBoolean,
          addrCacheRead.resp.pa.toBigInt
        )
      )
//        println(
//          f"${simTime()} time: dut.io.addrCacheRead.resp PSN=${dut.io.addrCacheRead.resp.psn.toInt}%X"
//        )
    }

    addrCacheReadRespQueue
  }
   */
  def simHelper(
      addrCacheRead: QpAddrCacheAgentReadBus,
      clockDomain: ClockDomain,
      alwaysValid: Boolean,
      alwaysSuccess: Boolean
  ) = {
    val onReqFire =
      (
          reqData: QpAddrCacheAgentReadReq,
          reqQueue: mutable.Queue[(PSN, VirtualAddr)]
      ) => {
        reqQueue.enqueue((reqData.psn.toInt, reqData.va.toBigInt))
        ()
      }

    val buildResp =
      (
          respData: QpAddrCacheAgentReadResp,
          reqQueue: mutable.Queue[(PSN, VirtualAddr)]
      ) => {
        val (psn, _) = reqQueue.dequeue()
        respData.psn #= psn
        if (alwaysSuccess) {
          respData.keyValid #= true
          respData.sizeValid #= true
          respData.accessValid #= true
        } else {
          respData.keyValid #= false
          respData.sizeValid #= false
          respData.accessValid #= false
        }
      }

    val onRespFire = (
        respData: QpAddrCacheAgentReadResp,
        respQueue: mutable.Queue[
          (PSN, KeyValid, SizeValid, AccessValid, PhysicalAddr)
        ]
    ) => {
      respQueue.enqueue(
        (
          respData.psn.toInt,
          respData.keyValid.toBoolean,
          respData.sizeValid.toBoolean,
          respData.accessValid.toBoolean,
          respData.pa.toBigInt
        )
      )
      ()
    }

    queryCacheHelper(
      reqStream = addrCacheRead.req,
      respStream = addrCacheRead.resp,
      onReqFire = onReqFire,
      buildResp = buildResp,
      onRespFire = onRespFire,
      clockDomain = clockDomain,
      alwaysValid = alwaysValid
    )
  }
}

object RdmaDataPktSim {
  private def pktFragStreamMasterDriverHelper[T <: Data](
      stream: Stream[Fragment[T]],
      getRdmaPktData: T => RdmaDataPkt,
      clockDomain: ClockDomain,
      alwaysValid: Boolean
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
          val opcode = WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx, pktNum)
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
            val mty = computeMty(
              pmtuLen,
              busWidth,
              opcode,
              fragLast,
              pktIdx,
              pktNum,
              payloadLenBytes
            )

//            println(
//              f"${simTime()} time: opcode=${opcode}, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, fragLast=${fragLast}, mty=${mty}%X, PSN=${psn}%X, headerLenBytes=${headerLenBytes}%X, payloadLenBytes=${payloadLenBytes}%X, lastOrOnlyPktTotalLenBytes=${lastOrOnlyPktTotalLenBytes}%X, padCnt=${padCnt}%X"
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
              getRdmaPktData(stream.fragment),
              psn,
              opcode,
              fragIdx,
              fragLast,
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
//            if (pktIdx == pktNum - 1 && fragIdx == pktFragNum - 1) {
//              require(
//                pktIdx == pktNum - 1,
//                f"${simTime()} time: this fragment with fragIdx=${fragIdx}%X is the last one, pktIdx=${pktIdx}%X should equal pktNum=${pktNum}%X-1"
//              )
//            }
            clockDomain.waitSamplingWhere(
              stream.valid.toBoolean && stream.ready.toBoolean
            )
          }
        }
      }
    }

  def pktFragStreamMasterDriver[T <: Data](
      stream: Stream[Fragment[T]],
      getRdmaPktData: T => RdmaDataPkt,
      clockDomain: ClockDomain
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
      getRdmaPktData,
      clockDomain,
      alwaysValid = false
    )(outerLoopBody)(innerLoopFunc)

  def pktFragStreamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[Fragment[T]],
      getRdmaPktData: T => RdmaDataPkt,
      clockDomain: ClockDomain
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
      getRdmaPktData,
      clockDomain,
      alwaysValid = true
    )(outerLoopBody)(innerLoopFunc)

//    fork {
//      stream.valid #= false
////      sleep(0)
//      clockDomain.waitSampling()
//
//      // Outer loop
//      while (true) {
//        val (
//          psnStart,
//          _, // payloadFragNum,
//          pktNum,
//          pmtuLen,
//          busWidth,
//          payloadLenBytes,
//          workReqOpCode
//        ) =
//          outerLoopBody
//
//        // Inner loop
//        for (pktIdx <- 0 until pktNum) {
//          val opcode = WorkReqSim.assignReqOpCode(workReqOpCode, pktIdx, pktNum)
//          val headerLenBytes = opcode.getPktHeaderLenBytes()
//          val psn = psnStart + pktIdx
//          val pktFragNum = computePktFragNum(
//            pmtuLen,
//            busWidth,
//            opcode,
//            payloadLenBytes,
//            pktIdx,
//            pktNum
//          )
//
//          for (fragIdx <- 0 until pktFragNum) {
//            val fragLast = fragIdx == pktFragNum - 1
//            val mty = computeMty(
//              pmtuLen,
//              busWidth,
//              opcode,
//              fragLast,
//              pktIdx,
//              pktNum,
//              payloadLenBytes
//            )
//
////            println(
////              f"${simTime()} time: opcode=${opcode}, pktIdx=${pktIdx}%X, pktNum=${pktNum}%X, fragIdx=${fragIdx}%X, pktFragNum=${pktFragNum}%X, fragLast=${fragLast}, mty=${mty}%X, PSN=${psn}%X, headerLenBytes=${headerLenBytes}%X, payloadLenBytes=${payloadLenBytes}%X, lastOrOnlyPktTotalLenBytes=${lastOrOnlyPktTotalLenBytes}%X, padCnt=${padCnt}%X"
////            )
//            do {
//              stream.valid.randomize()
////              stream.valid #= true
//              stream.payload.randomize()
//              sleep(0)
//              if (!stream.valid.toBoolean) {
//                clockDomain.waitSampling()
//              }
//            } while(!stream.valid.toBoolean)
//
//            stream.last #= fragLast // Set last must after set payload, since last is part of the payload
//            setRdmaDataFrag(
//              getRdmaPktData(stream.fragment),
//              psn,
//              opcode,
//              fragIdx,
//              fragLast,
//              pktIdx,
//              pktNum,
//              payloadLenBytes,
//              mty,
//              pmtuLen,
//              busWidth
//            )
//
//            innerLoopFunc(
//              psn,
//              psnStart,
//              fragLast,
//              fragIdx,
//              pktFragNum,
//              pktIdx,
//              pktNum,
//              payloadLenBytes,
//              headerLenBytes,
//              opcode
//            )
////            if (pktIdx == pktNum - 1 && fragIdx == pktFragNum - 1) {
////              require(
////                pktIdx == pktNum - 1,
////                f"${simTime()} time: this fragment with fragIdx=${fragIdx}%X is the last one, pktIdx=${pktIdx}%X should equal pktNum=${pktNum}%X-1"
////              )
////            }
//            clockDomain.waitSamplingWhere(
//              stream.valid.toBoolean && stream.ready.toBoolean
//            )
//          }
//        }
//      }
//    }

  def buildPktMetaDataHelper(
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      opcode: OpCode.Value,
      payloadLenBytes: PktLen
  ) = {
    val maxPayloadFragNumPerPkt =
      SendWriteReqReadRespInputGen.maxFragNumPerPkt(pmtuLen, busWidth)
    val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)
    val pmtuLenBytes = SendWriteReqReadRespInputGen.pmtuLenBytes(pmtuLen)
    val lastPktPadCnt =
      (PAD_COUNT_FULL - (payloadLenBytes.toInt % PAD_COUNT_FULL)) % PAD_COUNT_FULL
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

  def setRdmaDataFrag(
      pktFrag: RdmaDataPkt,
      psn: PSN,
      opcode: OpCode.Value,
      fragIdx: FragIdx,
      fragLast: FragLast,
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
    val padCnt = if (pktIdx == pktNum - 1) lastPktPadCnt else 0

    require(
      pmtuLenBytes % mtyWidth == 0,
      f"${simTime()} time: invalid pmtuLenBytes=${pmtuLenBytes}, should be multiple of mtyWidth=${mtyWidth}"
    )
    require(
      headerLenBytes % PAD_COUNT_FULL == 0,
      f"${simTime()} time: invalid headerLenBytes=${headerLenBytes}, should be multiple of PAD_COUNT_FULL=${PAD_COUNT_FULL}"
    )
    require(
      mtyWidth > PAD_COUNT_FULL,
      f"${simTime()} time: mtyWidth=${mtyWidth} should > PAD_COUNT_FULL=${PAD_COUNT_FULL}"
    )
    require(
      pktNum * pmtuLenBytes >= payloadLenBytes,
      f"${simTime()} time: pktNum * pmtuLenBytes=${pktNum * pmtuLenBytes} should >= payloadLenBytes=${payloadLenBytes}"
    )
    require(
      (pktNum - 1) * pmtuLenBytes < payloadLenBytes,
      f"${simTime()} time: (pktNum - 1) * pmtuLenBytes=${(pktNum - 1) * pmtuLenBytes} should < payloadLenBytes=${payloadLenBytes}"
    )

    pktFrag.bth.padCnt #= padCnt
    pktFrag.bth.ackreq #= opcode.isLastOrOnlyReqPkt() && fragLast
    pktFrag.mty #= mty

    if (fragIdx == 0 && pktIdx == 0 && opcode.hasReth()) {
      // Set DmaInfo for write/read requests
      val pktFragData = pktFrag.data.toBigInt
      pktFrag.data #= RethSim.setDlen(
        pktFragData,
        payloadLenBytes.toLong,
        busWidth
      )
    }
  }

  def computeMty(
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      opcode: OpCode.Value,
      fragLast: FragLast,
      pktIdx: PktIdx,
      pktNum: PktNum,
//      mtyWidth: WidthBytes,
      payloadLenBytes: PktLen
//      headerLenBytes: PktLen,
//      lastPktPadCnt: PadCnt,
//      pmtuLenBytes: PktLen
  ): BigInt = {
    val (_, mtyWidth, pmtuLenBytes, lastPktPadCnt, headerLenBytes) =
      buildPktMetaDataHelper(pmtuLen, busWidth, opcode, payloadLenBytes)

    val mty = if (fragLast) {
      if (pktIdx == pktNum - 1) { // Final fragment of last or only packet
        val finalFragValidBytes =
          (payloadLenBytes + headerLenBytes + lastPktPadCnt) % mtyWidth
        if (finalFragValidBytes == 0) {
          setAllBits(mtyWidth)
        } else {
          val leftShiftAmt = mtyWidth - finalFragValidBytes
          setAllBits(finalFragValidBytes.toInt) << leftShiftAmt.toInt
        }
      } else { // Last fragment of a first or middle packet
        val lastFragValidBytes = (pmtuLenBytes + headerLenBytes) % mtyWidth
        val leftShiftAmt = mtyWidth - lastFragValidBytes
        setAllBits(lastFragValidBytes.toInt) << leftShiftAmt.toInt
      }
    } else {
      setAllBits(mtyWidth)
    }
    mty
  }

  // TODO: remove this
  def setMtyAndPadCnt(
      pktFrag: RdmaDataPkt,
      fragIdx: FragIdx,
      fragNum: FragNum,
      totalLenBytes: PktLen,
      busWidth: BusWidth.Value
  ): Unit = {
    val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)
    if (fragIdx == fragNum - 1) {
      val finalFragValidBytes = totalLenBytes % mtyWidth
      val leftShiftAmt = mtyWidth - finalFragValidBytes
      pktFrag.mty #= (setAllBits(
        finalFragValidBytes.toInt
      ) << leftShiftAmt.toInt)
      pktFrag.bth.padCnt #= (PAD_COUNT_FULL - (totalLenBytes % PAD_COUNT_FULL)) % PAD_COUNT_FULL
    } else {
      pktFrag.mty #= setAllBits(mtyWidth)
      pktFrag.bth.padCnt #= 0
    }
  }
}

object DmaReadRespSim {
  def setMtyAndLen(
      dmaReadResp: DmaReadResp,
      fragIdx: FragIdx,
      totalFragNum: FragNum,
      totalLenBytes: PktLen,
      busWidth: BusWidth.Value
  ) = {
    val mtyWidth = SendWriteReqReadRespInputGen.busWidthBytes(busWidth)
    val isLastPktFrag = fragIdx == totalFragNum - 1

    val mty = if (isLastPktFrag) {
      val residue = (totalLenBytes % mtyWidth).toInt
      if (residue == 0) {
        setAllBits(mtyWidth) // Last fragment has full valid data
      } else {
        val leftShiftAmt = mtyWidth - residue
        setAllBits(
          residue
        ) << leftShiftAmt // Last fragment has partial valid data
      }
    } else {
      setAllBits(mtyWidth)
    }
    dmaReadResp.mty #= mty
    dmaReadResp.lenBytes #= totalLenBytes
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
      case _ => {
        println(f"${simTime()} time: rqNak=${rqNak} is not NAK")
        ???
      }
    }

    pulse.toBoolean shouldBe true withClue
      f"${simTime()} time: rqNak=${rqNak} not match expected NAK=${expectNakType}"
  }
}
