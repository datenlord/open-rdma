package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth

class RetryHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(Fragment(RdmaDataBus(busWidth))))
    val cacheReadReq = master(Stream(CacheReq()))
    val cacheReadResp = slave(Stream(CacheData()))
    val dmaReadReq = master(Stream(DmaReadReq()))
    val dmaReadResp = slave(Stream(Fragment(DmaReadResp())))
  }

  io.cacheReadReq <-/< io.rx.translateWith {
    CacheReq().setDefaultVal()
  }

  val retryLogic = new SqLogic(busWidth, retry = true)
  retryLogic.io.workReqPSN <-/< io.cacheReadResp.translateWith {
    io.cacheReadResp.workReqPSN
  }
  io.dmaReadReq <-/< retryLogic.io.dmaReadReq
  retryLogic.io.dmaReadResp <-/< io.dmaReadResp
  io.tx <-/< retryLogic.io.tx
}
