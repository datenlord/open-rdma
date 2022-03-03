package rdma

import spinal.core._
import spinal.core.sim._

import RdmaConstants._

object TypeReDef {
  type Addr = BigInt
  type FragIdx = Int
  type FragLast = Boolean
  type FragNum = Int
  type LRKey = Long
  type MTY = BigInt
  type PktIdx = Int
  type PktLen = Long
  type PktNum = Int
  type PSN = Int
  type PsnStart = Int
  type QPN = Int
  type RdmaFragData = BigInt
  type WorkReqId = BigInt
}

object WorkCompSim {
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
//      f"${simTime()} time: workCompOpCode=${workCompOpCode} not match expected matchOpCode=${matchOpCode}, workReqOpCode=${workReqOpCode}"
//    )
    assert(
      workCompOpCode == matchOpCode,
      f"${simTime()} time: workCompOpCode=${workCompOpCode} not match expected matchOpCode=${matchOpCode}, workReqOpCode=${workReqOpCode}"
    )
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
//      f"${simTime()} time: workCompFlags=${workCompFlags} not match expected matchFlag=${matchFlag}, workReqOpCode=${workReqOpCode}"
//    )
    assert(
      workCompFlags == matchFlag,
      f"${simTime()} time: workCompFlags=${workCompFlags} not match expected matchFlag=${matchFlag}, workReqOpCode=${workReqOpCode}"
    )
  }
}

object WorkReqSim {
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

  def isSendReq(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
  ): Boolean = {
    workReqSend.contains(workReqOpCode)
  }

  def isWriteReq(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
  ): Boolean = {
    workReqWrite.contains(workReqOpCode)
  }

  def isReadReq(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
  ): Boolean = {
    workReqRead.contains(workReqOpCode)
  }

  def isAtomicReq(
      workReqOpCode: SpinalEnumElement[WorkReqOpCode.type]
  ): Boolean = {
    workReqAtomic.contains(workReqOpCode)
  }

  def randomReadAtomicOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = WorkReqOpCode.RDMA_READ +: workReqAtomic
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    assert(opCodes.contains(result))
    result
  }

  def randomSendWriteOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = workReqSend ++ workReqWrite
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    assert(opCodes.contains(result))
    result
  }

  def randomSendWriteReadOpCode(): SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes = WorkReqOpCode.RDMA_READ +: (workReqSend ++ workReqWrite)
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    assert(opCodes.contains(result))
    result
  }

  def randomSendWriteReadAtomicOpCode()
      : SpinalEnumElement[WorkReqOpCode.type] = {
    val opCodes =
      WorkReqOpCode.RDMA_READ +: (workReqSend ++ workReqWrite ++ workReqAtomic)
    val randIdx = scala.util.Random.nextInt(opCodes.size)
    val result = opCodes(randIdx)
    assert(opCodes.contains(result))
    result
  }

  def randomDmaLength(): Long = {
    // RDMA max packet length 2GB=2^31
    scala.util.Random.nextLong(1L << (RDMA_MAX_LEN_WIDTH - 1))
  }
}

/*
object WorkReqOpCodeSim extends Enumeration {
  type WorkReqOpCodeSim = Value

  val RDMA_WRITE = Value(0)
  val RDMA_WRITE_WITH_IMM = Value(1)
  val SEND = Value(2)
  val SEND_WITH_IMM = Value(3)
  val RDMA_READ = Value(4)
  val ATOMIC_CMP_AND_SWP = Value(5)
  val ATOMIC_FETCH_AND_ADD = Value(6)
  val LOCAL_INV = Value(7)
  val BIND_MW = Value(8)
  val SEND_WITH_INV = Value(9)
  val TSO = Value(10)
  val DRIVER1 = Value(11)
}

object WorkCompFlagsSim extends Enumeration {
  type WorkCompFlagsSim = Value

  val NO_FLAGS = Value(0) // Not defined in spec.
  val GRH = Value(1)
  val WITH_IMM = Value(2)
  val IP_CSUM_OK = Value(4)
  val WITH_INV = Value(8)
  val TM_SYNC_REQ = Value(16)
  val TM_MATCH = Value(32)
  val TM_DATA_VALID = Value(64)
}

object WorkCompOpCodeSim extends Enumeration {
  type WorkCompOpCodeSim = Value

  val SEND = Value(0)
  val RDMA_WRITE = Value(1)
  val RDMA_READ = Value(2)
  val COMP_SWAP = Value(3)
  val FETCH_ADD = Value(4)
  val BIND_MW = Value(5)
  val LOCAL_INV = Value(6)
  val TSO = Value(7)
  val RECV = Value(128)
  val RECV_RDMA_WITH_IMM = Value(129)
  val TM_ADD = Value(130)
  val TM_DEL = Value(131)
  val TM_SYNC = Value(132)
  val TM_RECV = Value(133)
  val TM_NO_TAG = Value(134)
  val DRIVER1 = Value(135)
}

object WorkCompStatusSim extends Enumeration {
  type WorkCompStatusSim = Value

  val SUCCESS = Value(0)
  val LOC_LEN_ERR = Value(1)
  val LOC_QP_OP_ERR = Value(2)
  val LOC_EEC_OP_ERR = Value(3)
  val LOC_PROT_ERR = Value(4)
  val WR_FLUSH_ERR = Value(5)
  val MW_BIND_ERR = Value(6)
  val BAD_RESP_ERR = Value(7)
  val LOC_ACCESS_ERR = Value(8)
  val REM_INV_REQ_ERR = Value(9)
  val REM_ACCESS_ERR = Value(10)
  val REM_OP_ERR = Value(11)
  val RETRY_EXC_ERR = Value(12)
  val RNR_RETRY_EXC_ERR = Value(13)
  val LOC_RDD_VIOL_ERR = Value(14)
  val REM_INV_RD_REQ_ERR = Value(15)
  val REM_ABORT_ERR = Value(16)
  val INV_EECN_ERR = Value(17)
  val INV_EEC_STATE_ERR = Value(18)
  val FATAL_ERR = Value(19)
  val RESP_TIMEOUT_ERR = Value(20)
  val GENERAL_ERR = Value(21)
  val TM_ERR = Value(22)
  val TM_RNDV_INCOMPLETE = Value(23)
}
object AccessTypeSim extends Enumeration {
  type AccessTypeSim = Value

  val LOCAL_READ = Value(0) // Not defined in spec.
  val LOCAL_WRITE = Value(1)
  val REMOTE_WRITE = Value(2)
  val REMOTE_READ = Value(4)
  val REMOTE_ATOMIC = Value(8)
  val MW_BIND = Value(16)
  val ZERO_BASED = Value(32)
  val ON_DEMAND = Value(64)
  val HUGETLB = Value(128)
  val RELAXED_ORDERING = Value(1048576)
}
 */

object AckTypeSim {
  val retryNakTypes = Seq(AckType.NAK_RNR, AckType.NAK_SEQ)
  val fatalNakType =
    Seq(AckType.NAK_INV, AckType.NAK_RMT_ACC, AckType.NAK_RMT_OP)

  def randomRetryNak(): SpinalEnumElement[AckType.type] = {
    val nakTypes = retryNakTypes
    val randIdx = scala.util.Random.nextInt(nakTypes.size)
    val result = nakTypes(randIdx)
    assert(nakTypes.contains(result))
    result
  }

  def randomFatalNak(): SpinalEnumElement[AckType.type] = {
    val nakTypes = fatalNakType
    val randIdx = scala.util.Random.nextInt(nakTypes.size)
    val result = nakTypes(randIdx)
    assert(nakTypes.contains(result))
    result
  }

  def randomNormalAckOrFatalNak(): SpinalEnumElement[AckType.type] = {
    val ackTypes = AckType.NORMAL +: fatalNakType
    val randIdx = scala.util.Random.nextInt(ackTypes.size)
    val result = ackTypes(randIdx)
    assert(ackTypes.contains(result))
    result
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
  implicit class AethAssignment(val that: AETH) {
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

object BthSim {
  implicit class BthAssignment(val that: BTH) {
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
