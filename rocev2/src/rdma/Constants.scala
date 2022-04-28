package rdma

import spinal.core._

/** ibv_device_attr fields definition:
  * https://www.mellanox.com/related-docs/prod_software/RDMA_Aware_Programming_user_manual.pdf
  *
  * fw_ver                    Firmware version
  * node_guid                 Node global unique identifier (GUID)
  * sys_image_guid            System image GUID
  * max_mr_size               Largest contiguous block that can be registered
  * page_size_cap             Supported page sizes
  * vendor_id                 Vendor ID, per IEEE
  * vendor_part_id            Vendor supplied part ID
  * hw_ver                    Hardware version
  * max_qp                    Maximum number of Queue Pairs (QP)
  * max_qp_wr                 Maximum outstanding work requests (WR) on any queue
  * device_cap_flags
  *         IBV_DEVICE_RESIZE_MAX_WR
  *         IBV_DEVICE_BAD_PKEY_CNTR
  *         IBV_DEVICE_BAD_QKEY_CNTR
  *         IBV_DEVICE_RAW_MULTI
  *         IBV_DEVICE_AUTO_PATH_MIG
  *         IBV_DEVICE_CHANGE_PHY_PORT
  *         IBV_DEVICE_UD_AV_PORT_ENFORCE
  *         IBV_DEVICE_CURR_QP_STATE_MOD
  *         IBV_DEVICE_SHUTDOWN_PORT
  *         IBV_DEVICE_INIT_TYPE
  *         IBV_DEVICE_PORT_ACTIVE_EVENT
  *         IBV_DEVICE_SYS_IMAGE_GUID
  *         IBV_DEVICE_RC_RNR_NAK_GEN
  *         IBV_DEVICE_SRQ_RESIZE
  *         IBV_DEVICE_N_NOTIFY_CQ
  *         IBV_DEVICE_XRC
  * max_sge                    Maximum scatter/gather entries (SGE) per WR for non-RD QPs
  * max_sge_rd                 Maximum SGEs per WR for RD QPs
  * max_cq                     Maximum supported completion queues (CQ)
  * max_cqe                    Maximum completion queue entries (CQE) per CQ
  * max_mr                     Maximum supported memory regions (MR)
  * max_pd                     Maximum supported protection domains (PD)
  * max_qp_rd_atom             Maximum outstanding RDMA read and atomic operations per QP
  * max_ee_rd_atom             Maximum outstanding RDMA read and atomic operations per End to End (EE) context (RD connections)
  * max_res_rd_atom            Maximum resources used for incoming RDMA read and atomic operations
  * max_qp_init_rd_atom        Maximum RDMA read and atomic operations that may be initiated per QP
  * max_ee_init_atom           Maximum RDMA read and atomic operations that may be initiated per EE
  * atomic_cap
  *         IBV_ATOMIC_NONE    - no atomic guarantees
  *         IBV_ATOMIC_HCA     - atomic guarantees within this device
  *         IBV_ATOMIC_GLOB    - global atomic guarantees
  * max_ee                     Maximum supported EE contexts
  * max_rdd                    Maximum supported RD domains
  * max_mw                     Maximum supported memory windows (MW)
  * max_raw_ipv6_qp            Maximum supported raw IPv6 datagram QPs
  * max_raw_ethy_qp            Maximum supported ethertype datagram QPs
  * max_mcast_grp              Maximum supported multicast groups
  * max_mcast_qp_attach        Maximum QPs per multicast group that can be attached
  * max_total_mcast_qp_attach  Maximum total QPs that can be attached to multicast groups
  * max_ah                     Maximum supported address handles (AH)
  * max_fmr                    Maximum supported fast memory regions (FMR)
  * max_map_per_fmr            Maximum number of remaps per FMR before an unmap operation is required
  * max_srq                    Maximum supported shared receive queues (SRCQ)
  * max_srq_wr                 Maximum work requests (WR) per SRQ
  * max_srq_sge                Maximum SGEs per SRQ
  * max_pkeys                  Maximum number of partitions
  * local_ca_ack_delay         Local CA ack delay
  * phys_port_cnt              Number of physical ports
  */
object ConstantSettings {
  // Device changeable settings
  val PENDING_REQ_NUM = 32
  val MAX_PENDING_READ_ATOMIC_REQ_NUM = 16
  val MIN_RNR_TIMEOUT = 1 // 0.01ms = 10us

  val MAX_WR_NUM_WIDTH = 8 // RDMA max in-flight packets 2^23
  val MAX_SGL_LEN_WIDTH = 8
  val MAX_SG_LEN_WIDTH = 12 // Max SG size is 4K bytes, a page size

  require(PENDING_REQ_NUM > MAX_PENDING_READ_ATOMIC_REQ_NUM)
  require((2 << MAX_WR_NUM_WIDTH) >= PENDING_REQ_NUM)

//  val WORK_REQ_CACHE_QUERY_DELAY_CYCLE = 4
  val COALESCE_HANDLE_CYCLE = 4
  val ADDR_CACHE_QUERY_DELAY_CYCLE = 8
  val READ_ATOMIC_RESULT_CACHE_QUERY_DELAY_CYCLE = 2
  val DMA_WRITE_DELAY_CYCLE = 128
  val DMA_READ_DELAY_CYCLE = 128

  val MAX_COALESCE_ACK_NUM = 8

  val MAX_PD = 8
  val MAX_MR_PER_PD = 8
  // require(MAX_PD <= (1 << PD_ID_WIDTH))

  // Test related settings
  val MATCH_CNT = 1000

  // Non-changeable settings
  val BYTE_WIDTH = 8

  val IPV4_WIDTH = 32
  val IPV6_WIDTH = 128

  val ACK_TYPE_WIDTH = 3

//  val WR_OPCODE_WIDTH = log2Up(11) // 11 is the max WR opcode
  val WR_FLAG_WIDTH = log2Up(16) + 1 // 16 is the max WR flag
//  val WC_OPCODE_WIDTH = log2Up(135) // 135 is the max WC opcode
//  val WC_FLAG_WIDTH = log2Up(64) + 1 // 64 is the max WC flag
//  val WC_STATUS_WIDTH = log2Up(23) // 23 is the max WC status

//  val ETH_WIDTH = 28 * 8 // AtomicEth is the largest ETH

  val PMTU_WIDTH = 4 // log2Up(log2Up(4096)) = log2Up(12) = 4
  // val MAX_PKT_NUM_WIDTH = 24 // packet max length = 2^31, min PMUT size = 256 = 2^8, max packet number = 2^(31 - 8)

  val QP_STATE_WIDTH = 3

  val QP_ATTR_MASK_WIDTH = 32

  val ACCESS_PERMISSION_WIDTH = 21
  require(
    widthOf(AccessPermission()) == ACCESS_PERMISSION_WIDTH,
    f"widthOf(AccessPermission())=${widthOf(AccessPermission())} should == ACCESS_PERMISSION_WIDTH=${ACCESS_PERMISSION_WIDTH}"
  )

  //  val PKT_SEQ_TYPE_WIDTH = 3

  val MAX_HEADER_LEN_WIDTH = log2Up(widthOf(BTH()) + widthOf(AtomicEth()))

  val INVALID_SG_NEXT_ADDR = 0x0

//  val RETRY_REASON_WIDTH = 2

//  val DMA_INITIATOR_WIDTH = 4
}

object DmaInitiator extends SpinalEnum(binarySequential) {
  val RQ_RD, RQ_WR, RQ_DUP, RQ_ATOMIC_RD, RQ_ATOMIC_WR, SQ_RD, SQ_WR,
      SQ_ATOMIC_WR = newElement() // SQ_DUP
}
//object DmaInitiator extends Enumeration {
//  type DmaInitiator = Value
//
//  val RQ_RD = Value(0)
//  val RQ_WR = Value(1)
//  val RQ_DUP = Value(2)
//  val RQ_ATOMIC_RD = Value(3)
//  val RQ_ATOMIC_WR = Value(4)
//  val SQ_RD = Value(5)
//  val SQ_WR = Value(6)
//  val SQ_ATOMIC_WR = Value(7)
//  val SQ_DUP = Value(8)
//}

object RetryReason extends SpinalEnum(binarySequential) {
  val NO_RETRY, IMPLICIT_ACK, RESP_TIMEOUT, RNR, SEQ_ERR = newElement()
}

object AddrQueryInitiator extends SpinalEnum(binarySequential) {
  val RQ, SQ_REQ, SQ_RESP = newElement()
}

object CRUD extends SpinalEnum(binarySequential) {
  val DELETE, CREATE, READ, UPDATE = newElement()
}

object PsnCompResult extends SpinalEnum(binarySequential) {
  val GREATER, EQUAL, LESSER = newElement()
}
//object PsnCompResult extends Enumeration {
//  type PsnCompResult = Value
//
//  val GREATER = Value(1)
//  val EQUAL = Value(0)
//  val LESSER = Value(2)
//}

object AckType extends SpinalEnum(binarySequential) {
  val NORMAL, NAK_SEQ, NAK_INV, NAK_RMT_ACC, NAK_RMT_OP, NAK_RNR = newElement()
}
//object AckType extends Enumeration {
//  type AckType = Value
//
//  val NORMAL = Value(0)
//  val NAK_SEQ = Value(1)
//  val NAK_INV = Value(2)
//  val NAK_RMT_ACC = Value(3)
//  val NAK_RMT_OP = Value(4)
//  val NAK_RNR = Value(5)
//}

object SqErrType extends SpinalEnum(binarySequential) {
  val NO_ERR, INV_REQ, RMT_ACC, RMT_OP, LOC_ERR, RETRY_EXC, RNR_EXC =
    newElement()
}

object BusWidth extends Enumeration {
  type BusWidth = Value

  // Internal bus width, must be larger than BTH width=48B
  val W32 = Value(32) // for test only
  val W512 = Value(512)
  val W1024 = Value(1024)
}

//----------RDMA related constants----------//
object RdmaConstants {
  val TRANSPORT_WIDTH = 3
  val OPCODE_WIDTH = 5
  val PAD_COUNT_WIDTH = 2
  val VERSION_WIDTH = 4
  val PKEY_WIDTH = 16
  val QPN_WIDTH = 24
  val PSN_WIDTH = 24
  val LRKEY_IMM_DATA_WIDTH = 32

  val LONG_WIDTH = 64
  val MEM_ADDR_WIDTH = 64
  val PAD_COUNT_FULL = 4

  val WR_ID_WIDTH = 64
  val PD_ID_WIDTH = 32
  val RDMA_MAX_LEN_WIDTH = 32 // RDMA max request/response length 2GB=2^31
  // RDMA_MAX_LEN_WIDTH - log2Up(256) = RDMA_MAX_LEN_WIDTH - PMTU.U256.id = 24
  // PMTU.U256.id = 8 is the bit width of 256.
  // This is the max number of packets of a request or a response.
  val MAX_PKT_NUM_WIDTH = RDMA_MAX_LEN_WIDTH - PMTU.U256.id

  val RESP_TIMEOUT_WIDTH = 5
  val RETRY_COUNT_WIDTH = 3
  val MSN_WIDTH = 24
  val AETH_RSVD_WIDTH = 1
  val AETH_CODE_WIDTH = 2
  val AETH_VALUE_WIDTH = 5
  val RNR_TIMEOUT_WIDTH = AETH_VALUE_WIDTH
  val CREDIT_COUNT_WIDTH = AETH_VALUE_WIDTH

  val ATOMIC_DATA_LEN = 8
  val PMTU_FRAG_NUM_WIDTH = 13 // PMTU max 4096

  val MAX_RESP_TIMEOUT = 8800 sec

  val INFINITE_RESP_TIMEOUT = 0

  val TOTAL_PSN = 1 << PSN_WIDTH
  val HALF_MAX_PSN = 1 << (PSN_WIDTH - 1)
  val PSN_MASK = (1 << PSN_WIDTH) - 1
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

object PMTU extends Enumeration {
  type PMTU = Value

  // The value means right shift amount of bits
  val U256 = Value(log2Up(256)) // 8
  val U512 = Value(log2Up(512)) // 9
  val U1024 = Value(log2Up(1024)) // 10
  val U2048 = Value(log2Up(2048)) // 11
  val U4096 = Value(log2Up(4096)) // 12
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

  def allowRecv(opcode: Bits, qps: Bits) =
    new Composite(qps) {
      val isReqAllowState = Bool()
      switch(qps) {
        is(RTR.id, RTS.id, SQD.id, SQE.id) {
          isReqAllowState := True
        }
        default {
          isReqAllowState := False
        }
      }

      val isRespAllowState = Bool()
      switch(qps) {
        is(RTR.id, SQD.id, SQE.id) {
          isRespAllowState := True
        }
        default {
          isRespAllowState := False
        }
      }

      val result =
        (OpCode.isReqPkt(opcode) && isReqAllowState) || (OpCode.isRespPkt(
          opcode
        ) && isRespAllowState)
    }.result
}

object Transports extends Enumeration {
  type Transports = Value

  val RC = Value(0x0) // 0x00
  val UC = Value(0x1) // 0x20
  val RD = Value(0x2) // 0x40
  val UD = Value(0x3) // 0x60
  val CNP = Value(0x4) // 0x80
  val XRC = Value(0x5) // 0xa0

  // Currently only RC and CNP are supported
  def isSupportedType(transport: Bits) =
    new Composite(transport) {
      val result = Bool()
      switch(transport) {
        is(RC.id, CNP.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result
}

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

  def isValidCode(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isValidCode") {
      val result =
        opcode.asUInt <= SEND_ONLY_WITH_INVALIDATE.id // && opcode <= 0x1F
    }.result

  def isFirstReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isFirstReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(SEND_FIRST.id, RDMA_WRITE_FIRST.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isMidReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isMidReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(SEND_MIDDLE.id, RDMA_WRITE_MIDDLE.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isLastReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isLastReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_LAST.id,
          SEND_LAST_WITH_IMMEDIATE.id,
          SEND_LAST_WITH_INVALIDATE.id,
          RDMA_WRITE_LAST.id,
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_ONLY.id,
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_ONLY_WITH_INVALIDATE.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id,
          RDMA_READ_REQUEST.id,
          COMPARE_SWAP.id,
          FETCH_ADD.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isLastOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isLastOrOnlyReqPkt") {
      val result = isLastReqPkt(opcode) || isOnlyReqPkt(opcode)
    }.result

  def isFirstOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isFirstOrOnlyReqPkt") {
      val result = isFirstReqPkt(opcode) || isOnlyReqPkt(opcode)
    }.result

  def isFirstOrMidReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isFirstOrMidReqPkt") {
      val result = isFirstReqPkt(opcode) || isMidReqPkt(opcode)
    }.result

  def isSendFirstReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isSendFirstReqPkt") {
      val result = opcode === SEND_FIRST.id
    }.result

  def isSendOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isSendOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_ONLY.id,
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_ONLY_WITH_INVALIDATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isSendLastReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isSendLastReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_LAST.id,
          SEND_LAST_WITH_IMMEDIATE.id,
          SEND_LAST_WITH_INVALIDATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isSendFirstOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isSendFirstOrOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_FIRST.id,
          SEND_ONLY.id,
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_ONLY_WITH_INVALIDATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isSendLastOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isSendLastOrOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_LAST.id,
          SEND_LAST_WITH_IMMEDIATE.id,
          SEND_LAST_WITH_INVALIDATE.id,
          SEND_ONLY.id,
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_ONLY_WITH_INVALIDATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isSendReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isSendReqPkt") {
      val result = Bool()
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
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isSendWriteFirstOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isSendWriteFirstOrOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_FIRST.id,
          SEND_ONLY.id,
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_ONLY_WITH_INVALIDATE.id,
          RDMA_WRITE_FIRST.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isSendWriteLastOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isSendWriteLastOrOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_ONLY.id,
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_ONLY_WITH_INVALIDATE.id,
          SEND_LAST.id,
          SEND_LAST_WITH_IMMEDIATE.id,
          SEND_LAST_WITH_INVALIDATE.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id,
          RDMA_WRITE_LAST.id,
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isFirstReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isFirstReadRespPkt") {
      val result = opcode === RDMA_READ_RESPONSE_FIRST.id
    }.result

  def isFirstOrOnlyReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isFirstOrOnlyReadRespPkt") {
      val result = Bool()
      switch(opcode) {
        is(RDMA_READ_RESPONSE_FIRST.id, RDMA_READ_RESPONSE_ONLY.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isFirstOrMidReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isFirstOrMidReadRespPkt") {
      val result = Bool()
      switch(opcode) {
        is(RDMA_READ_RESPONSE_FIRST.id, RDMA_READ_RESPONSE_MIDDLE.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isLastOrOnlyReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isLastOrOnlyReadRespPkt") {
      val result = Bool()
      switch(opcode) {
        is(RDMA_READ_RESPONSE_ONLY.id, RDMA_READ_RESPONSE_LAST.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isMidReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isMidReadRespPkt") {
      val result = opcode === RDMA_READ_RESPONSE_MIDDLE.id
    }.result

  def isLastReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isLastReadRespPkt") {
      val result = opcode === RDMA_READ_RESPONSE_LAST.id
    }.result

  def isOnlyReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isOnlyReadRespPkt") {
      val result = opcode === RDMA_READ_RESPONSE_ONLY.id
    }.result

  // CNP not considered
  def isOnlyRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isOnlyRespPkt") {
      val result = Bool()
      switch(opcode) {
        is(ACKNOWLEDGE.id, ATOMIC_ACKNOWLEDGE.id, RDMA_READ_RESPONSE_ONLY.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isLastOrOnlyRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isLastOrOnlyRespPkt") {
      val result = isOnlyRespPkt(opcode) || isLastReadRespPkt(opcode)
    }.result

  def isWriteFirstReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isWriteFirstReqPkt") {
      val result = opcode === RDMA_WRITE_FIRST.id
    }.result

  def isWriteFirstOrMidReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isWriteFirstOrMidReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(RDMA_WRITE_FIRST.id, RDMA_WRITE_MIDDLE.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isWriteFirstOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isWriteFirstOrOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          RDMA_WRITE_FIRST.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isWriteOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isWriteOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(RDMA_WRITE_ONLY.id, RDMA_WRITE_ONLY_WITH_IMMEDIATE.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isWriteLastReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isWriteLastReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(RDMA_WRITE_LAST.id, RDMA_WRITE_LAST_WITH_IMMEDIATE.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isWriteLastOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isWriteLastOrOnlyReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          RDMA_WRITE_LAST.id,
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isWriteReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isWriteReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          RDMA_WRITE_FIRST.id,
          RDMA_WRITE_MIDDLE.id,
          RDMA_WRITE_LAST.id,
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isWriteWithImmReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isWriteWithImmReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def hasImmDt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_hasImmDt") {
      val result = Bool()
      switch(opcode) {
        is(
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_LAST_WITH_IMMEDIATE.id,
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def hasIeth(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_hasIeth") {
      val result = Bool()
      switch(opcode) {
        is(SEND_LAST_WITH_INVALIDATE.id, SEND_ONLY_WITH_INVALIDATE.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def hasRethOrAtomicEth(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_hasRethOrAtomicEth") {
      val result = Bool()
      switch(opcode) {
        is(
          RDMA_READ_REQUEST.id,
          COMPARE_SWAP.id,
          FETCH_ADD.id,
          RDMA_WRITE_FIRST.id,
          RDMA_WRITE_ONLY.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isAtomicReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isAtomicReqPkt") {
      val result = Bool()
      switch(opcode) {
        is(COMPARE_SWAP.id, FETCH_ADD.id) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isReadReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isReadReqPkt") {
      val result = (opcode === RDMA_READ_REQUEST.id)
    }.result

  def isReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isReadRespPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          RDMA_READ_RESPONSE_FIRST.id,
          RDMA_READ_RESPONSE_MIDDLE.id,
          RDMA_READ_RESPONSE_LAST.id,
          RDMA_READ_RESPONSE_ONLY.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isAtomicRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isAtomicRespPkt") {
      val result = (opcode === ATOMIC_ACKNOWLEDGE.id)
    }.result

  def isNonReadAtomicRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isNonReadAtomicRespPkt") {
      val result = (opcode === ACKNOWLEDGE.id)
    }.result

  def isReqPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isReqPkt") {
      val result = isSendReqPkt(opcode) || isWriteReqPkt(opcode) ||
        isReadReqPkt(opcode) || isAtomicReqPkt(opcode)
    }.result

  // CNP is not considered
  def isRespPkt(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isRespPkt") {
      val result = Bool()
      switch(opcode) {
        is(
          ACKNOWLEDGE.id,
          ATOMIC_ACKNOWLEDGE.id,
          RDMA_READ_RESPONSE_FIRST.id,
          RDMA_READ_RESPONSE_MIDDLE.id,
          RDMA_READ_RESPONSE_LAST.id,
          RDMA_READ_RESPONSE_ONLY.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def respPktHasAeth(opcode: Bits): Bool =
    new Composite(opcode, "OpCode_respPktHasAeth") {
      val result = Bool()
      switch(opcode) {
        is(
          ACKNOWLEDGE.id,
          ATOMIC_ACKNOWLEDGE.id,
          RDMA_READ_RESPONSE_FIRST.id,
          RDMA_READ_RESPONSE_LAST.id,
          RDMA_READ_RESPONSE_ONLY.id
        ) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isCnpPkt(transport: Bits, opcode: Bits): Bool =
    new Composite(opcode, "OpCode_isCnpPkt") {
      val result = (transport ## opcode) === CNP.id
    }.result
}

// Table 46, pp. 348, spec 1.4
object AethCode extends Enumeration {
  type AethCode = Value

  val ACK = Value(0)
  val RNR = Value(1)
  val RSVD = Value(2)
  val NAK = Value(3)
}

// Table 47, pp. 347, spec 1.4
object NakCode extends Enumeration {
  type NakCode = Value

  val SEQ = Value(0)
  val INV = Value(1)
  val RMT_ACC = Value(2)
  val RMT_OP = Value(3)
  val INV_RD = Value(4)
  val RSVD = Value(5) // 5 - 31 reserved

  def isReserved(nakCode: Bits): Bool =
    new Composite(nakCode) {
      val result = nakCode.asUInt >= RSVD.id
    }.result
}

object WorkReqSendFlagEnum extends SpinalEnum {
  val FENCE, SIGNALED, SOLICITED, INLINE, IP_CSUM = newElement()

  defaultEncoding = SpinalEnumEncoding("opt")(
    FENCE -> 1,
    SIGNALED -> 2,
    SOLICITED -> 4,
    INLINE -> 8,
    IP_CSUM -> 16
  )
}
//object WorkReqSendFlags extends Enumeration {
//  type WorkReqSendFlags = Value
//
//  val FENCE = Value(1)
//  val SIGNALED = Value(2)
//  val SOLICITED = Value(4)
//  val INLINE = Value(8)
//  val IP_CSUM = Value(16)
//}

object WorkReqOpCode extends SpinalEnum {
  val RDMA_WRITE, RDMA_WRITE_WITH_IMM, SEND, SEND_WITH_IMM, RDMA_READ,
      ATOMIC_CMP_AND_SWP, ATOMIC_FETCH_AND_ADD, LOCAL_INV, BIND_MW,
      SEND_WITH_INV, TSO, DRIVER1 = newElement()

  defaultEncoding = SpinalEnumEncoding("opt")(
    RDMA_WRITE -> 0,
    RDMA_WRITE_WITH_IMM -> 1,
    SEND -> 2,
    SEND_WITH_IMM -> 3,
    RDMA_READ -> 4,
    ATOMIC_CMP_AND_SWP -> 5,
    ATOMIC_FETCH_AND_ADD -> 6,
    LOCAL_INV -> 7,
    BIND_MW -> 8,
    SEND_WITH_INV -> 9,
    TSO -> 10,
    DRIVER1 -> 11
  )
//object WorkReqOpCode extends Enumeration {
//  type WorkReqOpCode = Value
//
//  val RDMA_WRITE = Value(0)
//  val RDMA_WRITE_WITH_IMM = Value(1)
//  val SEND = Value(2)
//  val SEND_WITH_IMM = Value(3)
//  val RDMA_READ = Value(4)
//  val ATOMIC_CMP_AND_SWP = Value(5)
//  val ATOMIC_FETCH_AND_ADD = Value(6)
//  val LOCAL_INV = Value(7)
//  val BIND_MW = Value(8)
//  val SEND_WITH_INV = Value(9)
//  val TSO = Value(10)
//  val DRIVER1 = Value(11)

  def hasImmDt(opcode: SpinalEnumCraft[WorkReqOpCode.type]): Bool =
    new Composite(opcode) {
      val result = Bool()
      switch(opcode) {
        is(SEND_WITH_IMM, RDMA_WRITE_WITH_IMM) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def hasIeth(opcode: SpinalEnumCraft[WorkReqOpCode.type]): Bool = {
    opcode === SEND_WITH_INV
  }

  def isSendReq(opcode: SpinalEnumCraft[WorkReqOpCode.type]): Bool =
    new Composite(opcode) {
      val result = Bool()
      switch(opcode) {
        is(SEND, SEND_WITH_IMM, SEND_WITH_INV) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isWriteReq(opcode: SpinalEnumCraft[WorkReqOpCode.type]): Bool =
    new Composite(opcode) {
      val result = Bool()
      switch(opcode) {
        is(RDMA_WRITE, RDMA_WRITE_WITH_IMM) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isAtomicReq(opcode: SpinalEnumCraft[WorkReqOpCode.type]): Bool =
    new Composite(opcode) {
      val result = Bool()
      switch(opcode) {
        is(ATOMIC_CMP_AND_SWP, ATOMIC_FETCH_AND_ADD) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isReadReq(opcode: SpinalEnumCraft[WorkReqOpCode.type]): Bool =
    new Composite(opcode) {
      val result = (opcode === RDMA_READ)
    }.result
}

object WorkCompFlags extends SpinalEnum {
  val NO_FLAGS, GRH, WITH_IMM, IP_CSUM_OK, WITH_INV, TM_SYNC_REQ, TM_MATCH,
      TM_DATA_VALID = newElement()

  defaultEncoding = SpinalEnumEncoding("opt")(
    NO_FLAGS -> 0,
    GRH -> 1,
    WITH_IMM -> 2,
    IP_CSUM_OK -> 4,
    WITH_INV -> 8,
    TM_SYNC_REQ -> 16,
    TM_MATCH -> 32,
    TM_DATA_VALID -> 64
  )
}
//object WorkCompFlags extends Enumeration {
//  type WorkCompFlags = Value
//
//  val NO_FLAGS = Value(0) // Not defined in spec.
//  val GRH = Value(1)
//  val WITH_IMM = Value(2)
//  val IP_CSUM_OK = Value(4)
//  val WITH_INV = Value(8)
//  val TM_SYNC_REQ = Value(16)
//  val TM_MATCH = Value(32)
//  val TM_DATA_VALID = Value(64)
//}

object WorkCompOpCode extends SpinalEnum {
  val SEND, RDMA_WRITE, RDMA_READ, COMP_SWAP, FETCH_ADD, BIND_MW, LOCAL_INV,
      TSO, RECV, RECV_RDMA_WITH_IMM, TM_ADD, TM_DEL, TM_SYNC, TM_RECV,
      TM_NO_TAG, DRIVER1 = newElement()

  defaultEncoding = SpinalEnumEncoding("opt")(
    SEND -> 0,
    RDMA_WRITE -> 1,
    RDMA_READ -> 2,
    COMP_SWAP -> 3,
    FETCH_ADD -> 4,
    BIND_MW -> 5,
    LOCAL_INV -> 6,
    TSO -> 7,
    RECV -> 128,
    RECV_RDMA_WITH_IMM -> 129,
    TM_ADD -> 130,
    TM_DEL -> 131,
    TM_SYNC -> 132,
    TM_RECV -> 133,
    TM_NO_TAG -> 134,
    DRIVER1 -> 135
  )

//object WorkCompOpCode extends Enumeration {
//  type WorkCompOpCode = Value
//
//  val SEND = Value(0)
//  val RDMA_WRITE = Value(1)
//  val RDMA_READ = Value(2)
//  val COMP_SWAP = Value(3)
//  val FETCH_ADD = Value(4)
//  val BIND_MW = Value(5)
//  val LOCAL_INV = Value(6)
//  val TSO = Value(7)
//  val RECV = Value(128)
//  val RECV_RDMA_WITH_IMM = Value(129)
//  val TM_ADD = Value(130)
//  val TM_DEL = Value(131)
//  val TM_SYNC = Value(132)
//  val TM_RECV = Value(133)
//  val TM_NO_TAG = Value(134)
//  val DRIVER1 = Value(135)

  def isSendComp(opcode: SpinalEnumCraft[WorkCompOpCode.type]): Bool =
    new Composite(opcode) {
      val result = opcode === SEND
    }.result

  def isWriteComp(opcode: SpinalEnumCraft[WorkCompOpCode.type]): Bool =
    new Composite(opcode) {
      val result = opcode === RDMA_WRITE
    }.result

  def isAtomicComp(opcode: SpinalEnumCraft[WorkCompOpCode.type]): Bool =
    new Composite(opcode) {
      val result = Bool()
      switch(opcode) {
        is(COMP_SWAP, FETCH_ADD) {
          result := True
        }
        default {
          result := False
        }
      }
    }.result

  def isReadComp(opcode: SpinalEnumCraft[WorkCompOpCode.type]): Bool =
    new Composite(opcode) {
      val result = (opcode === RDMA_READ)
    }.result
}

object WorkCompStatus extends SpinalEnum(binarySequential) {
  val SUCCESS, LOC_LEN_ERR, LOC_QP_OP_ERR, LOC_EEC_OP_ERR, LOC_PROT_ERR,
      WR_FLUSH_ERR, MW_BIND_ERR, BAD_RESP_ERR, LOC_ACCESS_ERR, REM_INV_REQ_ERR,
      REM_ACCESS_ERR, REM_OP_ERR, RETRY_EXC_ERR, RNR_RETRY_EXC_ERR,
      LOC_RDD_VIOL_ERR, REM_INV_RD_REQ_ERR, REM_ABORT_ERR, INV_EECN_ERR,
      INV_EEC_STATE_ERR, FATAL_ERR, RESP_TIMEOUT_ERR, GENERAL_ERR, TM_ERR,
      TM_RNDV_INCOMPLETE = newElement()

  defaultEncoding = SpinalEnumEncoding("opt")(
    SUCCESS -> 0,
    LOC_LEN_ERR -> 1,
    LOC_QP_OP_ERR -> 2,
    LOC_EEC_OP_ERR -> 3,
    LOC_PROT_ERR -> 4,
    WR_FLUSH_ERR -> 5,
    MW_BIND_ERR -> 6,
    BAD_RESP_ERR -> 7,
    LOC_ACCESS_ERR -> 8,
    REM_INV_REQ_ERR -> 9,
    REM_ACCESS_ERR -> 10,
    REM_OP_ERR -> 11,
    RETRY_EXC_ERR -> 12,
    RNR_RETRY_EXC_ERR -> 13,
    LOC_RDD_VIOL_ERR -> 14,
    REM_INV_RD_REQ_ERR -> 15,
    REM_ABORT_ERR -> 16,
    INV_EECN_ERR -> 17,
    INV_EEC_STATE_ERR -> 18,
    FATAL_ERR -> 19,
    RESP_TIMEOUT_ERR -> 20,
    GENERAL_ERR -> 21,
    TM_ERR -> 22,
    TM_RNDV_INCOMPLETE -> 23
  )
}
//object WorkCompStatus extends Enumeration {
//  type WorkCompStatus = Value
//
//  val SUCCESS = Value(0)
//  val LOC_LEN_ERR = Value(1)
//  val LOC_QP_OP_ERR = Value(2)
//  val LOC_EEC_OP_ERR = Value(3)
//  val LOC_PROT_ERR = Value(4)
//  val WR_FLUSH_ERR = Value(5)
//  val MW_BIND_ERR = Value(6)
//  val BAD_RESP_ERR = Value(7)
//  val LOC_ACCESS_ERR = Value(8)
//  val REM_INV_REQ_ERR = Value(9)
//  val REM_ACCESS_ERR = Value(10)
//  val REM_OP_ERR = Value(11)
//  val RETRY_EXC_ERR = Value(12)
//  val RNR_RETRY_EXC_ERR = Value(13)
//  val LOC_RDD_VIOL_ERR = Value(14)
//  val REM_INV_RD_REQ_ERR = Value(15)
//  val REM_ABORT_ERR = Value(16)
//  val INV_EECN_ERR = Value(17)
//  val INV_EEC_STATE_ERR = Value(18)
//  val FATAL_ERR = Value(19)
//  val RESP_TIMEOUT_ERR = Value(20)
//  val GENERAL_ERR = Value(21)
//  val TM_ERR = Value(22)
//  val TM_RNDV_INCOMPLETE = Value(23)
//}

object AccessPermission extends SpinalEnum {
  val LOCAL_READ, LOCAL_WRITE, REMOTE_WRITE, REMOTE_READ, REMOTE_ATOMIC,
      MW_BIND, ZERO_BASED, ON_DEMAND, HUGETLB, RELAXED_ORDERING = newElement()

  defaultEncoding = SpinalEnumEncoding("opt")(
    LOCAL_READ -> 0, // Not defined in spec
    LOCAL_WRITE -> 1,
    REMOTE_WRITE -> 2,
    REMOTE_READ -> 4,
    REMOTE_ATOMIC -> 8,
    MW_BIND -> 16,
    ZERO_BASED -> 32,
    ON_DEMAND -> 64,
    HUGETLB -> 128,
    RELAXED_ORDERING -> 1048576
  )
}
//object AccessType extends Enumeration {
//  type AccessType = Value
//
//  val LOCAL_READ = Value(0) // Not defined in spec.
//  val LOCAL_WRITE = Value(1)
//  val REMOTE_WRITE = Value(2)
//  val REMOTE_READ = Value(4)
//  val REMOTE_ATOMIC = Value(8)
//  val MW_BIND = Value(16)
//  val ZERO_BASED = Value(32)
//  val ON_DEMAND = Value(64)
//  val HUGETLB = Value(128)
//  val RELAXED_ORDERING = Value(1048576)
//}
