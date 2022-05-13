package rdma

import spinal.core._
import spinal.lib._

class DmaHandler(busWidth: BusWidth.Value) extends Component {
  val io = new Bundle {
    val dma = slave(DmaBus(busWidth))
  }

  // TODO: connect to XDMA
  io.dma.rd.resp <-/< io.dma.rd.req.translateWith {
    val dmaReadData = Fragment(DmaReadResp(busWidth))
    dmaReadData.setDefaultVal()
    dmaReadData.last := False
    dmaReadData
  }

  io.dma.wr.resp <-/< io.dma.wr.req.translateWith {
    DmaWriteResp().setDefaultVal()
  }
}

// TODO: how to find out the order between receive WR and send WR?
//
// pp. 292 spec 1.4
// Due to the ordering rule guarantees of requests and responses
// for reliable services, the requester is allowed to write CQ
// completion events upon response receipt.
//
// pp. 292 spec 1.4
// The completion at the receiver is in the order sent (applies only to
// SENDs and RDMA WRITE with Immediate) and does not imply previous
// RDMA READs are complete unless fenced by the requester.
class WorkCompOut extends Component {
  val io = new Bundle {
//    val dmaWrite = slave(DmaWriteRespBus())
    val rqSendWriteWorkComp = slave(Stream(WorkComp()))
    val sqWorkComp = slave(Stream(WorkComp()))
    val sqWorkCompErr = slave(Stream(WorkComp()))
    val workCompPush = master(Stream(WorkComp()))
  }

  // TODO: flush WorkReqCache when error
  //      status := WorkCompStatus.WR_FLUSH_ERR.id
  // TODO: output WC in PSN order
  io.workCompPush <-/< io.sqWorkComp
  StreamSink(io.rqSendWriteWorkComp.payloadType) << io.rqSendWriteWorkComp
  StreamSink(io.sqWorkCompErr.payloadType) << io.sqWorkCompErr
//  StreamSink(io.dmaWrite.resp.payloadType) << io.dmaWrite.resp
}
