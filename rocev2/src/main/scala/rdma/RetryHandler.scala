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
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val workReqQuery = master(WorkReqCacheQueryBus())
    val addrCacheRead = master(SqOrRetryAddrCacheReadBus())
    val dma = master(SqDmaBus(busWidth))
  }

  io.workReqQuery.req <-/< io.rx.pktFrag.translateWith {
    io.rx.pktFrag.bth.psn
  }

  // TODO: should throw not found result?
  val throwCond = io.workReqQuery.resp.valid && !io.workReqQuery.resp.found
  val retryLogic = new SqLogic(busWidth, retry = true)
  retryLogic.io.workReqCached << io.workReqQuery.resp
    .throwWhen(throwCond)
    .translateWith(io.workReqQuery.resp.wrCached)

  assert(
    assertion = throwCond,
    message =
      L"not found WorkReq with PSN=${io.workReqQuery.resp.queryPsn} in WorkReqQuery",
    severity = FAILURE
  )

  io.addrCacheRead << retryLogic.io.addrCacheRead
  io.dma << retryLogic.io.dma
  io.tx << retryLogic.io.tx
}
