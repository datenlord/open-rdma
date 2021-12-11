package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
import RdmaConstants._
// import ConstantSettings._

// Discard all invalid responses:
// - NAK with reserved code;
// - Target QP not exists;
// - Ghost ACK;
class RespVerifer(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val npsn = in(UInt(PSN_WIDTH bits))
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val tx = master(Stream(RdmaDataBus(busWidth)))
  }

  val validResp = False
  io.tx <-/< io.rx.throwWhen(!validResp)
}

class RespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val npsn = in(UInt(PSN_WIDTH bits))
    val rx = slave(Stream(RdmaDataBus(busWidth)))
    val qpStateChange = master(Stream(QpStateChange()))
    val reqCacheBus = master(ReqCacheBus())
    // Save read/atomic response data to main memory
    val dmaWrite = master(DmaWriteReqBus(busWidth))
  }

  val respVerifier = new RespVerifer(busWidth)
  respVerifier.io.rx << io.rx
  val validRespRx = respVerifier.io.tx

  io.qpStateChange.setDefaultVal()
  io.qpStateChange.valid := False

  io.reqCacheBus.req <-/< validRespRx.translateWith {
    CacheReq().setDefaultVal()
  }
  StreamSink(io.reqCacheBus.resp.payloadType) << io.reqCacheBus.resp

  io.dmaWrite.req <-/< StreamSource().translateWith {
    val dmaWriteReq = Fragment(DmaWriteReq(busWidth))
    dmaWriteReq.fragment.setDefaultVal()
    dmaWriteReq.last := False
    dmaWriteReq
  }
}
