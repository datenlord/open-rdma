package rdma

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

import BusWidth.BusWidth
import Constants._

class RetryHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    //val qpAttr = in(QpAttrData())
    val rx = slave(Stream(Fragment(RdmaDataBus(busWidth))))
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
  //retryLogic.io.qpAttr := io.qpAttr
  retryLogic.io.workReqPSN <-/< io.cacheReadResp.translateWith {
    io.cacheReadResp.workReqPSN
  }
  io.dmaReadReq <-/< retryLogic.io.dmaReadReq
  retryLogic.io.dmaReadResp <-/< io.dmaReadResp
  io.tx <-/< retryLogic.io.tx
}
