package rdma

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

import BusWidth.BusWidth
import Constants._

class ReqCache(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val portW = new Bundle {
      val writeReq = slave(Stream(CacheData()))
    }
    val portRW = new Bundle {
      val rwReq = slave(Stream(CacheReq()))
      val readResp = master(Stream(CacheData()))
    }
    val portR = new Bundle {
      val readReq = slave(Stream(CacheReq()))
      val readResp = master(Stream(CacheData()))
    }
  }
  // TODO: remove this
  io.portW.writeReq.ready := False
  io.portRW.readResp <-/< io.portRW.rwReq.translateWith {
    CacheData().setDefaultVal()
  }

  io.portR.readResp <-/< io.portR.readReq.translateWith {
    CacheData().setDefaultVal()
  }
}
