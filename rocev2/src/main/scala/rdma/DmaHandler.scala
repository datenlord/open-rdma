package rdma

import spinal.core._
import spinal.lib._
import spinal.lib.fsm._

import BusWidth.BusWidth
import RdmaConstants._

class DmaHandler() extends Component {
  val io = new Bundle {
    val dmaReadReq = slave(Stream(DmaReadReq()))
    val dmaReadResp = master(Stream(Fragment(DmaReadResp())))
    val dmaWriteReq = slave(Stream(Fragment(DmaWriteReq())))
    val dmaWriteResp = master(Stream(DmaWriteResp()))
  }

  io.dmaReadResp <-/< io.dmaReadReq.translateWith {
    val dmaReadData = Fragment(DmaReadResp())
    dmaReadData.fragment.setDefaultVal()
    dmaReadData.last := False
    dmaReadData
  }

  io.dmaWriteResp <-/< io.dmaWriteReq.translateWith {
    DmaWriteResp().setDefaultVal()
  }
}
