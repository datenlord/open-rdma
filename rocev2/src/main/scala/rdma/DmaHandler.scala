package rdma

import spinal.core._
import spinal.lib._
import BusWidth.BusWidth

class DmaHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val dma = slave(DmaBus(busWidth))
  }

  // TODO: connect to XDMA

  io.dma.rd.resp <-/< io.dma.rd.req.translateWith {
    val dmaReadData = Fragment(DmaReadResp(busWidth))
    dmaReadData.fragment.setDefaultVal()
    dmaReadData.last := False
    dmaReadData
  }

  io.dma.wr.resp <-/< io.dma.wr.req.translateWith {
    DmaWriteResp().setDefaultVal()
  }
}

class WorkCompGen extends Component {
  val io = new Bundle {
    val dmaWriteResp = slave(Stream(DmaWriteResp()))
    val workComp = master(Stream(WorkComp()))
  }

  // TODO: output WC in PSN order
  io.workComp <-/< io.dmaWriteResp.translateWith(WorkComp().setDefaultVal())
}
