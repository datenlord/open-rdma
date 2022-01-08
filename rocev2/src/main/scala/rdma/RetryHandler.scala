package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth

// pp.282 spec 1.4
// The retried request may only reread those portions that were not
// successfully responded to the first time.
//
// Any retried request must correspond exactly to a subset of the
// original RDMA READ request in such a manner that all potential
// duplicate response packets must have identical payload data and PSNs
//
// INCONSISTENT: retried requests are not in PSN order
class RetryHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val rx = slave(Stream(Acknowlege()))
    val tx = master(RdmaDataBus(busWidth))
    val workReqQuery = master(WorkReqCacheQueryBus())
    val addrCacheRead = master(AddrCacheReadBus())
    val dma = master(SqDmaBus(busWidth))
  }

  val inputValid = io.rx.valid
  val inputRetryAck = io.rx.payload

  when(inputValid) {
    assert(
      assertion = inputRetryAck.aeth.isRetryAck(),
      message =
        L"input Ack is not retry, AETH code=${inputRetryAck.aeth.code}, value=${inputRetryAck.aeth.value}",
      severity = FAILURE
    )
  }

  io.workReqQuery.req <-/< io.rx.translateWith {
    val rslt = WorkReqCacheQueryReq()
    rslt.psn := inputRetryAck.bth.psn
    rslt
  }

  // TODO: should throw not found result?
  val throwCond = io.workReqQuery.resp.valid && !io.workReqQuery.resp.found
  val retryLogic = new SqLogic(busWidth, retry = true)
  retryLogic.io.workReqCached << io.workReqQuery.resp
    .throwWhen(throwCond)
    .translateWith(io.workReqQuery.resp.cachedWorkReq)

  assert(
    assertion = throwCond,
    message =
      L"not found WorkReq with PSN=${io.workReqQuery.resp.query.psn} in WorkReqQuery",
    severity = FAILURE
  )

  io.addrCacheRead << retryLogic.io.addrCacheRead
  io.dma << retryLogic.io.dma
  io.tx << retryLogic.io.tx
}

// TODO: split into two modules
class SendWriteReadAtomicRetryHandler extends Bundle {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val sendQCtrl = in(SendQCtrl())
    val rx = slave(Stream(Acknowlege()))
    val workReqCacheQuery = master(WorkReqCacheQueryBus())
    val sqPktCacheQuery = slave(SqPktCacheQueryBus())
    val dmaReadReq = master(DmaReadReqBus())
    val txReadReqRetry = master(Stream(ReadReq()))
    val txAtomicReqRetry = master(Stream(AtomicReq()))
  }

  val inputRetryAck = io.rx.payload

  val (retryAck4WorkReqCacheQuery, retryAck4SqPktCacheQuery) = StreamFork2(
    io.rx.throwWhen(io.sendQCtrl.flush)
  )

  io.workReqCacheQuery.req <-/< retryAck4WorkReqCacheQuery.translateWith {
    val rslt = WorkReqCacheQueryReq()
    rslt.psn := inputRetryAck.bth.psn
    rslt
  }

  io.sqPktCacheQuery.req <-/< retryAck4SqPktCacheQuery.translateWith {
    val rslt = SqPktCacheQueryReq()
    rslt.psn := inputRetryAck.bth.psn
    rslt
  }

  val cachedWorkReqValid = io.workReqCacheQuery.resp.valid
  val cachedWorkReqFound = io.workReqCacheQuery.resp.found
  val cachedWorkReq = io.workReqCacheQuery.resp.cachedWorkReq
  when(cachedWorkReqValid) {
    assert(
      assertion = cachedWorkReqFound,
      message =
        L"not found SQ packet for PSN=${io.workReqCacheQuery.resp.query.psn} to retry",
      severity = FAILURE
    )

    assert(
      assertion =
        io.workReqCacheQuery.resp.query.psn === cachedWorkReq.psnStart,
      message =
        L"RQ need to handle the retried read request with middle PSN not the original one",
      severity = FAILURE
    )
  }

  val isReadReq = WorkReqOpCode.isReadReq(cachedWorkReq.workReq.opcode)
  val isAtomicReq = WorkReqOpCode.isAtomicReq(cachedWorkReq.workReq.opcode)
  val txSel = UInt(2 bits)
  when(isReadReq) {
    txSel := 0
  } elsewhen (isAtomicReq) {
    txSel := 1
  } otherwise {
    txSel := 2
  }
  val threeStreams = StreamDemux(
    io.workReqCacheQuery.resp.throwWhen(io.sendQCtrl.flush),
    select = txSel,
    portCount = 3
  )
  io.txReadReqRetry <-/< threeStreams(0).translateWith {
    val rslt = ReadReq().set(
      dqpn = io.qpAttr.dqpn,
      psn = io.workReqCacheQuery.resp.query.psn,
      va = cachedWorkReq.workReq.raddr,
      rkey = cachedWorkReq.workReq.rkey,
      dlen = cachedWorkReq.workReq.len
    )
    rslt
  }
  io.txAtomicReqRetry <-/< threeStreams(1).translateWith {
    val isCompSwap =
      cachedWorkReq.workReq.opcode === WorkReqOpCode.ATOMIC_CMP_AND_SWP.id
    val rslt = AtomicReq().set(
      isCompSwap,
      io.qpAttr.dqpn,
      io.workReqCacheQuery.resp.query.psn,
      cachedWorkReq.workReq.raddr,
      cachedWorkReq.workReq.rkey,
      cachedWorkReq.workReq.comp,
      cachedWorkReq.workReq.swap
    )
    rslt
  }
  StreamSink(NoData) << threeStreams(2).translateWith(NoData)

  //  val workReqCacheAndSqPktCacheQueryResp = StreamJoin(
//    io.workReqCacheQuery.resp.throwWhen(io.sendQCtrl.flush),
//    io.sqPktCacheQuery.resp.throwWhen(io.sendQCtrl.flush)
//  )

  val cachedSqPktValid = io.sqPktCacheQuery.resp.valid
  val cachedSqPktFound = io.sqPktCacheQuery.resp.found
  val cachedSqPkt = io.sqPktCacheQuery.resp.cachedPkt
  when(cachedSqPktValid) {
    assert(
      assertion = cachedSqPktFound,
      message =
        L"not found SQ packet for PSN=${io.sqPktCacheQuery.resp.query.psn} to retry",
      severity = FAILURE
    )
  }
  io.dmaReadReq.req <-/< io.sqPktCacheQuery.resp
    .throwWhen(io.sendQCtrl.flush)
    .translateWith {
      val rslt = DmaReadReq()
      rslt.opcode := cachedSqPkt.opcode
      rslt.psn := cachedSqPkt.psn
      rslt.sqpn := io.qpAttr.sqpn
      rslt.addr := cachedSqPkt.pa
      rslt.len := cachedSqPkt.len
      rslt
    }
}
