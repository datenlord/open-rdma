package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
// import RdmaConstants._
// import ConstantSettings._

// Discard all invalid responses:
// - NAK with reserved code;
// - Target QP not exists;
// - Ghost ACK;
class RespVerifer(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rx = slave(RdmaDataBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
  }

  val validResp = False
  io.tx.pktFrag <-/< io.rx.pktFrag.throwWhen(!validResp)
}

class RespHandler(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val rx = slave(RdmaDataBus(busWidth))
    val qpStateChange = master(Stream(QpStateChange()))
    val workReqPopped = slave(Stream(CachedWorkReq()))
    val workComp = master(Stream(WorkComp()))
    // val reqCacheBus = master(PktCacheBus())
    val addrCacheRead = master(AddrCacheReadBus())
    // Save read/atomic response data to main memory
    val dmaWrite = master(DmaWriteReqBus(busWidth))
  }

  val respVerifier = new RespVerifer(busWidth)
  respVerifier.io.rx << io.rx
  val (validRespRx1, validRespRx2) = StreamFork2(respVerifier.io.tx.pktFrag)

  io.qpStateChange.setDefaultVal()
  io.qpStateChange.valid := False

  val bothValid = io.workReqPopped.valid && validRespRx2.valid
  val wrPsnEnd = io.workReqPopped.psnStart + io.workReqPopped.pktNum
  when(validRespRx2.bth.psn > wrPsnEnd) {
    io.workReqPopped.ready := io.workReqPopped.valid
    validRespRx2.ready := False
  } elsewhen (validRespRx2.bth.psn === wrPsnEnd) {
    io.workReqPopped.ready := bothValid
    validRespRx2.ready := bothValid
  } otherwise {
    io.workReqPopped.ready := False
    validRespRx2.ready := validRespRx2.valid
  }

//  StreamSink(NoData) << StreamMerge(
//    io.workReqPopped,
//    validRespRx2,
//    mergeFunc = (fire: Bool) => {
//      val rslt = False
//      val wrPsnEnd = io.workReqPopped.psnStart + io.workReqPopped.pktNum
//      when(validRespRx2.bth.psn > wrPsnEnd) {
//        io.workReqPopped.ready := io.workReqPopped.valid
//        validRespRx2.ready := False
//      } elsewhen (validRespRx2.bth.psn === wrPsnEnd) {
//        io.workReqPopped.ready := fire
//        validRespRx2.ready := fire
//        rslt := True
//      } otherwise {
//        io.workReqPopped.ready := False
//        validRespRx2.ready := validRespRx2.valid
//      }
//      rslt
//    }
//  ).translateWith(NoData)

  // TODO: implementation
  io.workComp <-/< validRespRx1.translateWith(WorkComp().setDefaultVal())

  io.dmaWrite.req <-/< StreamSource().translateWith {
    val dmaWriteReq = Fragment(DmaWriteReq(busWidth))
    dmaWriteReq.setDefaultVal()
    dmaWriteReq.last := False
    dmaWriteReq
  }

  io.addrCacheRead.req <-/< io.addrCacheRead.resp
    .translateWith(AddrCacheReadReq().setDefaultVal())
}
