package rdma

import spinal.core._
import RdmaConstants._

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

  val WORK_REQ_CACHE_QUERY_DELAY_CYCLE = 4
  val ADDR_CACHE_QUERY_DELAY_CYCLE = 8
  val READ_ATOMIC_RESULT_CACHE_QUERY_DELAY_CYCLE = 2
  val DMA_WRITE_DELAY_CYCLE = 128
  val DMA_READ_DELAY_CYCLE = 128

  val MAX_COALESCE_ACK_NUM = 8

  val MAX_PD = 32
  val MAX_MR_PER_PD = 32
  // require(MAX_PD <= (1 << PD_ID_WIDTH))

  // Non-changeable settings
  val BYTE_WIDTH = 8

  val IPV4_WIDTH = 32
  val IPV6_WIDTH = 128

  val TOTAL_PSN = (1 << PSN_WIDTH)
  val HALF_MAX_PSN = 1 << (PSN_WIDTH - 1)
  val PSN_MASK = (1 << PSN_WIDTH) - 1
  val PSN_COMP_RESULT_WIDTH = 2

  val ACK_TYPE_WIDTH = 3

  val WR_OPCODE_WIDTH = log2Up(11) // 11 is the max WR opcode
  val WR_FLAG_WIDTH = log2Up(16) + 1 // 16 is the max WR flag
  val WC_OPCODE_WIDTH = log2Up(135) // 135 is the max WC opcode
  val WC_FLAG_WIDTH = log2Up(64) + 1 // 64 is the max WC flag
  val WC_STATUS_WIDTH = log2Up(23) // 23 is the max WC status

//  val ETH_WIDTH = 28 * 8 // AtomicEth is the largest ETH

  val PMTU_WIDTH = 4 // log2Up(log2Up(4096)) = log2Up(12) = 4
  // val MAX_PKT_NUM_WIDTH = 24 // packet max length = 2^31, min PMUT size = 256 = 2^8, max packet number = 2^(31 - 8)

  val QP_STATE_WIDTH = 3

  val QP_ATTR_MASK_WIDTH = 32

  val ACCESS_TYPE_WIDTH = 20

  val PKT_SEQ_TYPE_WIDTH = 3

  val MAX_HEADER_LEN_WIDTH = log2Up(widthOf(BTH()) + widthOf(AtomicEth()))

  val INVALID_SG_NEXT_ADDR = 0x0

  val RETRY_REASON_WIDTH = 2

  val DMA_INITIATOR_WIDTH = 4
}

object DmaInitiator extends Enumeration {
  type DmaInitiator = Value

  val RQ_RD = Value(0)
  val RQ_WR = Value(1)
  val RQ_DUP = Value(2)
  val RQ_ATOMIC_RD = Value(3)
  val RQ_ATOMIC_WR = Value(4)
  val SQ_RD = Value(5)
  val SQ_WR = Value(6)
  val SQ_ATOMIC_WR = Value(7)
  val SQ_DUP = Value(8)
}

object RetryReason extends SpinalEnum(binarySequential) {
  val RESP_TIMEOUT, RETRY_ACK, IMPLICIT_ACK = newElement()
}

object AddrQueryInitiator extends SpinalEnum(binarySequential) {
  val RQ, SQ_REQ, SQ_RESP = newElement()
}

object CRUD extends SpinalEnum(binarySequential) {
  val DELETE, CREATE, READ, UPDATE = newElement()
}

object PsnCompResult extends Enumeration {
  type PsnCompResult = Value

  val GREATER = Value(1)
  val EQUAL = Value(0)
  val LESSER = Value(2)
}

object AckType extends Enumeration {
  type AckType = Value

  val NORMAL = Value(0)
  val NAK_SEQ = Value(1)
  val NAK_INV = Value(2)
  val NAK_RMT_ACC = Value(3)
  val NAK_RMT_OP = Value(4)
  val NAK_RNR = Value(5)
}

object BusWidth extends Enumeration {
  type BusWidth = Value

  // Internal bus width, must be larger than BTH width=48B
  val W512 = Value(512)
  val W1024 = Value(1024)
}

//----------RDMA related constants----------//
object RdmaConstants {
  val TRANSPORT_WIDTH = 3
  val OPCODE_WIDTH = 5
  val PADCOUNT_WIDTH = 2
  val VERSION_WIDTH = 4
  val PKEY_WIDTH = 16
  val QPN_WIDTH = 24
  val PSN_WIDTH = 24
  val LRKEY_IMM_DATA_WIDTH = 32

  val LONG_WIDTH = 64
  val MEM_ADDR_WIDTH = 64
  val PADCOUNT_FULL = 4

  val WR_ID_WIDTH = 64
  val PD_ID_WIDTH = 32
  val RDMA_MAX_LEN_WIDTH = 32 // RDMA max packet length 2GB=2^31

  val RESP_TIMEOUT_WIDTH = 5
  val RETRY_COUNT_WIDTH = 3
  val MSN_WIDTH = 24
  val AETH_VALUE_WIDTH = 5
  val RNR_TIMEOUT_WIDTH = AETH_VALUE_WIDTH
  val CREDIT_COUNT_WIDTH = AETH_VALUE_WIDTH

  val RETRY_CNT_WIDTH = 3

  val ATOMIC_DATA_LEN = 8
  val PMTU_FRAG_NUM_WIDTH = 13 // PMTU max 4096

  val MAX_RESP_TIMEOUT = 8800 sec
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

  // Currently only RC and CNP are supported
  def isSupportedType(transport: Bits) =
    new Composite(transport) {
      val rslt = Bool()
      switch(transport) {
        is(RC.id, CNP.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt
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
    new Composite(opcode) {
      val rslt =
        opcode.asUInt <= SEND_ONLY_WITH_INVALIDATE.id // && opcode <= 0x1F
    }.rslt

  def isFirstReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(SEND_FIRST.id, RDMA_WRITE_FIRST.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isMidReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(SEND_MIDDLE.id, RDMA_WRITE_MIDDLE.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isLastReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
          SEND_LAST.id,
          SEND_LAST_WITH_IMMEDIATE.id,
          SEND_LAST_WITH_INVALIDATE.id,
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

  def isOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
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
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isLastOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = isLastReqPkt(opcode) || isOnlyReqPkt(opcode)
    }.rslt

  def isFirstOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = isFirstReqPkt(opcode) || isOnlyReqPkt(opcode)
    }.rslt

  def isFirstOrMidReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = isFirstReqPkt(opcode) || isMidReqPkt(opcode)
    }.rslt

  def isSendFirstReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = opcode === SEND_FIRST.id
    }.rslt

  def isSendOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
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

  def isSendLastReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
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

  def isSendFirstOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
          SEND_FIRST.id,
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

  def isSendLastOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
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

  def isSendReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
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

  def isSendWriteFirstOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
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
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isSendWriteLastOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
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
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isFirstReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = opcode === RDMA_READ_RESPONSE_FIRST.id
    }.rslt

  def isFirstOrOnlyReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(RDMA_READ_RESPONSE_FIRST.id, RDMA_READ_RESPONSE_ONLY.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isFirstOrMidReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(RDMA_READ_RESPONSE_FIRST.id, RDMA_READ_RESPONSE_MIDDLE.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isLastOrOnlyReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(RDMA_READ_RESPONSE_ONLY.id, RDMA_READ_RESPONSE_LAST.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isMidReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = opcode === RDMA_READ_RESPONSE_MIDDLE.id
    }.rslt

  def isLastReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = opcode === RDMA_READ_RESPONSE_LAST.id
    }.rslt

  def isOnlyReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = opcode === RDMA_READ_RESPONSE_ONLY.id
    }.rslt

  // CNP not considered
  def isOnlyRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(ACKNOWLEDGE.id, ATOMIC_ACKNOWLEDGE.id, RDMA_READ_RESPONSE_ONLY.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isLastOrOnlyRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = isOnlyRespPkt(opcode) || isLastReadRespPkt(opcode)
    }.rslt

  def isWriteFirstReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = opcode === RDMA_WRITE_FIRST.id
    }.rslt

  def isWriteFirstOrMidReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(RDMA_WRITE_FIRST.id, RDMA_WRITE_MIDDLE.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isWriteFirstOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
          RDMA_WRITE_FIRST.id,
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

  def isWriteOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(RDMA_WRITE_ONLY.id, RDMA_WRITE_ONLY_WITH_IMMEDIATE.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isWriteLastReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(RDMA_WRITE_LAST.id, RDMA_WRITE_LAST_WITH_IMMEDIATE.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isWriteLastOrOnlyReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
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

  def isWriteReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
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

  def isWriteWithImmReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def hasImmDt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
          SEND_ONLY_WITH_IMMEDIATE.id,
          SEND_LAST_WITH_IMMEDIATE.id,
          RDMA_WRITE_LAST_WITH_IMMEDIATE.id,
          RDMA_WRITE_ONLY_WITH_IMMEDIATE.id
        ) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def hasIeth(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(SEND_LAST_WITH_INVALIDATE.id, SEND_ONLY_WITH_INVALIDATE.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isAtomicReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
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

  def isReadReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = (opcode === RDMA_READ_REQUEST.id)
    }.rslt

  def isReadRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
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

  def isAtomicRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = (opcode === ATOMIC_ACKNOWLEDGE.id)
    }.rslt

  def isNonReadAtomicRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = (opcode === ACKNOWLEDGE.id)
    }.rslt

  def isReqPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt =
        isSendReqPkt(opcode) || isWriteReqPkt(opcode) || isReadReqPkt(
          opcode
        ) || isAtomicReqPkt(
          opcode
        )
    }.rslt

  // CNP is not considered
  def isRespPkt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
          ACKNOWLEDGE.id,
          ATOMIC_ACKNOWLEDGE.id,
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

  def respPktHasAeth(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(
          ACKNOWLEDGE.id,
          ATOMIC_ACKNOWLEDGE.id,
          RDMA_READ_RESPONSE_FIRST.id,
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

  def isCnpPkt(transport: Bits, opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = (transport ## opcode) === CNP.id
    }.rslt
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
      val rslt = nakCode.asUInt >= RSVD.id
    }.rslt
}

object WorkReqSendFlags extends Enumeration {
  type WorkReqSendFlags = Value

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

  def hasImmDt(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(SEND_WITH_IMM.id, RDMA_WRITE_WITH_IMM.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def hasIeth(opcode: Bits): Bool = {
    opcode === SEND_WITH_INV.id
  }

  def isSendReq(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(SEND.id, SEND_WITH_IMM.id, SEND_WITH_INV.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isWriteReq(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(RDMA_WRITE.id, RDMA_WRITE_WITH_IMM.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isAtomicReq(opcode: Bits): Bool =
    new Composite(opcode) {
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

  def isReadReq(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = (opcode === RDMA_READ.id)
    }.rslt
}

object WorkCompFlags extends Enumeration {
  type WorkCompFlags = Value

  val NO_FLAGS = Value(0) // Not defined in spec.
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

  def isSendComp(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = opcode === SEND.id
    }.rslt

  def isWriteComp(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = opcode === RDMA_WRITE.id
    }.rslt

  def isAtomicComp(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = Bool()
      switch(opcode) {
        is(COMP_SWAP.id, FETCH_ADD.id) {
          rslt := True
        }
        default {
          rslt := False
        }
      }
    }.rslt

  def isReadComp(opcode: Bits): Bool =
    new Composite(opcode) {
      val rslt = (opcode === RDMA_READ.id)
    }.rslt
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
