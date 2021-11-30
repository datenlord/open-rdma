package rdma

import spinal.core._
import spinal.lib._

object Constants {
  val PENDING_REQ_NUM = 32
  val PENDING_READ_ATOMIC_REQ_NUM = 16

  val TRANSPORT_WIDTH = 3
  val OPCODE_WIDTH = 8
  val PADCOUNT_WIDTH = 2
  val VERSION_WIDTH = 4
  val PKEY_WIDTH = 16
  val QPN_WIDTH = 24
  val PSN_WIDTH = 24
  val REQ_LEN_WIDTH = 31 // RDMA max packet length 2GB
  val IPV4_WIDTH = 32

  val WR_OPCODE_WIDTH = 4
  val RETRY_CNT_WIDTH = 3

  val PMTU_WIDTH = 3
  val QP_STATE_WIDTH = 3

  val MEM_ADDR_WIDTH = 64
  val DMA_LEN_WIDTH = 10
  val DMA_BUS_WIDTH = 64

  val INT_WIDTH = 32
  val LONG_WIDTH = 64

  val MAX_WR_NUM_WIDTH = 8 // RDMA max in-flight packets 2^23

  val QP_ATTR_MASK_WIDTH = 32
}

object QpAttrMask extends Enumeration {
  type QpAttrMask = Value

  val QP_CREATE = Value(0)
  val QP_STATE = Value(1) // Use the value of attr->qp_state
  val QP_CUR_STATE = Value(2) // Use the value of attr->cur_qp_state
  // Use the value of attr->en_sqd_async_notify
  val QP_EN_SQD_ASYNC_NOTIFY = Value(4)
  val QP_ACCESS_FLAGS = Value(8) // Use the value of attr->qp_access_flags
  val QP_PKEY_INDEX = Value(16) // Use the value of attr->pkey_index
  val QP_PORT = Value(32) // Use the value of attr->port_num
  val QP_QKEY = Value(64) // Use the value of attr->qkey
  val QP_AV = Value(128) // Use the value of attr->ah_attr
  val QP_PATH_MTU = Value(256) // Use the value of attr->path_mtu
  val QP_TIMEOUT = Value(512) // Use the value of attr->timeout
  val QP_RETRY_CNT = Value(1024) // Use the value of attr->retry_cnt
  val QP_RNR_RETRY = Value(2048) // Use the value of attr->rnr_retry
  val QP_RQ_PSN = Value(4096) // Use the value of attr->rq_psn
  val QP_MAX_QP_RD_ATOMIC = Value(8192) // Use the value of attr->max_rd_atomic
  // Use the value of attr->alt_ah_attr, attr->alt_pkey_index, attr->alt_port_num, attr->alt_timeout
  val QP_ALT_PATH = Value(16384)
  val QP_MIN_RNR_TIMER = Value(32768) // Use the value of attr->min_rnr_timer
  val QP_SQ_PSN = Value(65536) // Use the value of attr->sq_psn
  // Use the value of attr->max_dest_rd_atomic
  val QP_MAX_DEST_RD_ATOMIC = Value(131072)
  val QP_PATH_MIG_STATE = Value(262144) // Use the value of attr->path_mig_state
  val QP_CAP = Value(524288) // Use the value of attr->cap
  val QP_DEST_QPN = Value(1048576) // Use the value of attr->dest_qp_num
  val QP_RATE_LIMIT = Value(33554432) // 33554432 = 2^25
}

object BusWidth extends Enumeration {
  type BusWidth = Value

  // Internal bus width, must be larger than BTH width=48B
  val W512 = Value(512)
}

object PMTU extends Enumeration {
  type PMTU = Value

  val U256 = Value(0)
  val U512 = Value(1)
  val U1024 = Value(2)
  val U2048 = Value(3)
  val U4096 = Value(4)
}

object QpState extends Enumeration {
  type QpState = Value

  val RESET = Value(0)
  val INIT = Value(1)
  val ERR = Value(2)
  val RTR = Value(3)
  val RTS = Value(4)
  val SQD = Value(5)
  val SQE = Value(6)

  def allowRecv(opcode: Bits, qps: Bits) = new Composite(qps) {
    val isReqAllowState = Bool()
    switch(qps) {
      is(
        RTR.id,
        RTS.id,
        SQD.id,
        SQE.id
      ) {
        isReqAllowState := True
      }
      default {
        isReqAllowState := False
      }
    }

    val isRespAllowState = Bool()
    switch(qps) {
      is(
        RTR.id,
        SQD.id,
        SQE.id
      ) {
        isRespAllowState := True
      }
      default {
        isRespAllowState := False
      }
    }

    val rslt =
      (OpCode.isReqPkt(opcode) && isReqAllowState) || (OpCode.isRespPkt(
        opcode
      ) && isRespAllowState)
  }.rslt
}

object Transports extends Enumeration {
  type Transports = Value

  val RC = Value(0x0) // 0x00
  val UC = Value(0x1) // 0x20
  val RD = Value(0x2) // 0x40
  val UD = Value(0x3) // 0x60
  val CNP = Value(0x4) // 0x80
  val XRC = Value(0x5) // 0xa0

  // Currently only RC is supported
  def isSupportedType(transport: Bits) = new Composite(transport) {
    val rslt = Bool()
    switch(transport) {
      is(
        RC.id,
        CNP.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt
}

import Constants._
object OpCode extends Enumeration {
  type OpCode = Value

  val SEND_FIRST = Value(0x00)
  val SEND_MIDDLE = Value(0x01)
  val SEND_LAST = Value(0x02)
  val SEND_LAST_WITH_IMMEDIATE = Value(0x03)
  val SEND_ONLY = Value(0x04)
  val SEND_ONLY_WITH_IMMEDIATE = Value(0x05)
  val RDMA_WRITE_FIRST = Value(0x06)
  val RDMA_WRITE_MIDDLE = Value(0x07)
  val RDMA_WRITE_LAST = Value(0x08)
  val RDMA_WRITE_LAST_WITH_IMMEDIATE = Value(0x09)
  val RDMA_WRITE_ONLY = Value(0x0a)
  val RDMA_WRITE_ONLY_WITH_IMMEDIATE = Value(0x0b)
  val RDMA_READ_REQUEST = Value(0x0c)
  val RDMA_READ_RESPONSE_FIRST = Value(0x0d)
  val RDMA_READ_RESPONSE_MIDDLE = Value(0x0e)
  val RDMA_READ_RESPONSE_LAST = Value(0x0f)
  val RDMA_READ_RESPONSE_ONLY = Value(0x10)
  val ACKNOWLEDGE = Value(0x11)
  val ATOMIC_ACKNOWLEDGE = Value(0x12)
  val COMPARE_SWAP = Value(0x13)
  val FETCH_ADD = Value(0x14)
  val RESYNC = Value(0x15)
  val SEND_LAST_WITH_INVALIDATE = Value(0x16)
  val SEND_ONLY_WITH_INVALIDATE = Value(0x17)

  val CNP = Value(0x81) // TODO: check where to set CNP opcode

  def isValidCode(opcode: Bits) = new Composite(opcode) {
    val rslt =
      opcode.asUInt <= SEND_ONLY_WITH_INVALIDATE.id // && opcode <= 0x1F
  }.rslt

  def isLastOrOnlyReqPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        SEND_LAST.id,
        SEND_LAST_WITH_IMMEDIATE.id,
        SEND_LAST_WITH_INVALIDATE.id,
        SEND_ONLY.id,
        SEND_ONLY_WITH_IMMEDIATE.id,
        SEND_ONLY_WITH_INVALIDATE.id,
        RDMA_WRITE_LAST.id,
        RDMA_WRITE_ONLY.id,
        RDMA_WRITE_ONLY_WITH_IMMEDIATE.id,
        RDMA_READ_REQUEST.id,
        COMPARE_SWAP.id,
        FETCH_ADD.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isLastOrOnlyRespPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        ACKNOWLEDGE.id,
        ATOMIC_ACKNOWLEDGE.id,
        CNP.id,
        RDMA_READ_RESPONSE_LAST.id,
        RDMA_READ_RESPONSE_ONLY.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isFirstReqPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        SEND_FIRST.id,
        RDMA_WRITE_FIRST.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isSendOnlyPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        SEND_ONLY.id,
        SEND_ONLY_WITH_IMMEDIATE.id,
        SEND_ONLY_WITH_INVALIDATE.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isSendLastPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        SEND_LAST.id,
        SEND_LAST_WITH_IMMEDIATE.id,
        SEND_LAST_WITH_INVALIDATE.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isReqPkt(opcode: Bits) = new Composite(opcode) {
    val rslt =
      isSendReqPkt(opcode) || isWriteReqPkt(opcode) || isReadReqPkt(
        opcode
      ) || isAtomicReqPkt(opcode)
  }.rslt

  def isRespPkt(opcode: Bits) = new Composite(opcode) {
    val rslt =
      isSendOrWriteRespPkt(opcode) || isReadRespPkt(opcode) || isAtomicRespPkt(
        opcode
      ) || isCnpPkt(opcode)
  }.rslt

  def isSendReqPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        SEND_FIRST.id,
        SEND_MIDDLE.id,
        SEND_LAST.id,
        SEND_LAST_WITH_IMMEDIATE.id,
        SEND_LAST_WITH_INVALIDATE.id,
        SEND_ONLY.id,
        SEND_ONLY_WITH_IMMEDIATE.id,
        SEND_ONLY_WITH_INVALIDATE.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isWriteReqOnlyPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        RDMA_WRITE_ONLY.id,
        RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isWriteReqLastPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        RDMA_WRITE_LAST.id,
        RDMA_WRITE_LAST_WITH_IMMEDIATE.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isWriteReqPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        RDMA_WRITE_FIRST.id,
        RDMA_WRITE_MIDDLE.id,
        RDMA_WRITE_LAST.id,
        RDMA_WRITE_LAST_WITH_IMMEDIATE.id,
        RDMA_WRITE_ONLY.id,
        RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isAtomicReqPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(COMPARE_SWAP.id, FETCH_ADD.id) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isReadReqPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = (opcode === RDMA_READ_REQUEST.id)
  }.rslt

  def isReadRespPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        RDMA_READ_RESPONSE_FIRST.id,
        RDMA_READ_RESPONSE_MIDDLE.id,
        RDMA_READ_RESPONSE_LAST.id,
        RDMA_READ_RESPONSE_ONLY.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isAtomicRespPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = (opcode === ATOMIC_ACKNOWLEDGE.id)
  }.rslt

  def isSendOrWriteRespPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = (opcode === ACKNOWLEDGE.id)
  }.rslt

  def isFirstReadRespPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = opcode === RDMA_READ_RESPONSE_FIRST.id
  }.rslt

  def isCnpPkt(opcode: Bits) = new Composite(opcode) {
    val rslt = opcode === CNP.id
  }.rslt
}

object AethCode extends Enumeration {
  type AethCode = Value

  val ACK = Value(0)
  val RNR = Value(1)
  val RSVD = Value(2)
  val NAK = Value(3)
}

object SendFlags extends Enumeration {
  type SendFlags = Value

  val FENCE = Value(1)
  val SIGNALED = Value(2)
  val SOLICITED = Value(4)
  val INLINE = Value(8)
  val IP_CSUM = Value(16)
}

object WorkReqOpCode extends Enumeration {
  type WorkReqOpCode = Value

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

  def isSendReq(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        SEND.id,
        SEND_WITH_IMM.id,
        SEND_WITH_INV.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isWriteReq(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(
        RDMA_WRITE.id,
        RDMA_WRITE_WITH_IMM.id
      ) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isAtomicReq(opcode: Bits) = new Composite(opcode) {
    val rslt = Bool()
    switch(opcode) {
      is(ATOMIC_CMP_AND_SWP.id, ATOMIC_FETCH_AND_ADD.id) {
        rslt := True
      }
      default {
        rslt := False
      }
    }
  }.rslt

  def isReadReq(opcode: Bits) = new Composite(opcode) {
    val rslt = (opcode === RDMA_READ.id)
  }.rslt
}

object WorkCompFlags extends Enumeration {
  type WorkCompFlags = Value

  val GRH = Value(1)
  val WITH_IMM = Value(2)
  val IP_CSUM_OK = Value(4)
  val WITH_INV = Value(8)
  val TM_SYNC_REQ = Value(16)
  val TM_MATCH = Value(32)
  val TM_DATA_VALID = Value(64)
}

object WorkCompOpCode extends Enumeration {
  type WorkCompOpCode = Value

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

object WorkCompStatus extends Enumeration {
  type WorkCompStatus = Value

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

object AccessType extends Enumeration {
  type AccessType = Value

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
