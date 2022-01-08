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
    val cachedWorkReqPop = slave(Stream(CachedWorkReq()))
    val workComp = master(Stream(WorkComp()))
    val txRetryResp = master(Stream(Acknowlege()))
    val addrCacheRead = master(AddrCacheReadBus())
    // Save read/atomic response data to main memory
    val dmaWrite = master(DmaWriteReqBus(busWidth))
  }

  val inputPktFrag = io.rx.pktFrag

  val respVerifier = new RespVerifer(busWidth)
  respVerifier.io.rx << io.rx

  // TODO: use StreamDemux instead of StreamFork
  val (
    validResp2WorkComp,
    validRespRx2PopCachedWorkReq,
    validRespRx2RetryLogic
  ) = StreamFork3(respVerifier.io.tx.pktFrag)

  // TODO: implement retry response detection logic
  io.txRetryResp <-/< validRespRx2RetryLogic.translateWith(
    Acknowlege()
      .setAck(AckType.NAK_SEQ, inputPktFrag.bth.psn, io.qpAttr.sqpn)
  )

  io.workComp <-/< validResp2WorkComp.translateWith(WorkComp().setDefaultVal())

  io.qpStateChange.setDefaultVal()
  io.qpStateChange.valid := False

  // TODO: verify the way to pop WR by responses
  val bothValid =
    io.cachedWorkReqPop.valid && validRespRx2PopCachedWorkReq.valid
  val wrPsnEnd = io.cachedWorkReqPop.psnStart + io.cachedWorkReqPop.pktNum
  when(validRespRx2PopCachedWorkReq.bth.psn > wrPsnEnd) {
    io.cachedWorkReqPop.ready := io.cachedWorkReqPop.valid
    validRespRx2PopCachedWorkReq.ready := False
  } elsewhen (validRespRx2PopCachedWorkReq.bth.psn === wrPsnEnd) {
    io.cachedWorkReqPop.ready := bothValid
    validRespRx2PopCachedWorkReq.ready := bothValid
  } otherwise {
    io.cachedWorkReqPop.ready := False
    validRespRx2PopCachedWorkReq.ready := validRespRx2PopCachedWorkReq.valid
  }

  // TODO: pop SqPktCache by responses

  io.dmaWrite.req <-/< StreamSource().translateWith {
    val dmaWriteReq = Fragment(DmaWriteReq(busWidth))
    dmaWriteReq.setDefaultVal()
    dmaWriteReq.last := False
    dmaWriteReq
  }

  io.addrCacheRead.req <-/< io.addrCacheRead.resp
    .translateWith(AddrCacheReadReq().setDefaultVal())

  // TODO: generate WC
}
