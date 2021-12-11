package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth

class RetryHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val reqCacheBus = master(ReqCacheBus())
    val dmaRead = master(DmaReadBus(busWidth))
  }

  io.reqCacheBus.req <-/< io.rx.translateWith {
    CacheReq().setDefaultVal()
  }

  val retryLogic = new SqLogic(busWidth, retry = true)
  retryLogic.io.workReqCached <-/< io.reqCacheBus.resp.translateWith {
    io.reqCacheBus.resp.workReqCached
  }
  io.dmaRead.req <-/< retryLogic.io.dmaRead.req
  retryLogic.io.dmaRead.resp <-/< io.dmaRead.resp
  io.tx <-/< retryLogic.io.tx
}
