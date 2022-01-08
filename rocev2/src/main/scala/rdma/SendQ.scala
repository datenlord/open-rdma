package rdma

import spinal.core._
import spinal.lib._

import BusWidth.BusWidth
// import RdmaConstants._

class ReqBuilder(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val workReqCached = slave(Stream(CachedWorkReq()))
    val addrCacheRead = master(AddrCacheReadBus())
    val dmaRead = master(DmaReadBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    // val psnInc = out(PsnIncNotifier())
  }

//  val npsnReg = Reg(UInt(PSN_WIDTH bits)) init (io.workReqCached.psnStart)
//  io.psnInc.npsnInc := io.tx.pktFrag.fire && io.tx.pktFrag.last
//  // TODO: change nPSN increment accordingly
//  io.psnInc.npsnIncVal := 1

  io.dmaRead.req <-/< io.dmaRead.resp.translateWith {
    DmaReadReq().setDefaultVal()
  }

  // TODO: implement SQ logic
  io.tx.pktFrag <-/< io.workReqCached.translateWith {
    val rslt = Fragment(RdmaDataPacket(busWidth))
    rslt.setDefaultVal()
    // TODO: WR opcode to RC opcode
    // frag.bth.opcode := io.workReqCached.workReq.opcode.resize(OPCODE_WIDTH)
    rslt.last := False
    rslt
  }
}

class SendReqBuilder(busWidth: BusWidth) extends ReqBuilder(busWidth) {
  io.addrCacheRead.req <-/< io.addrCacheRead.resp
    .translateWith(AddrCacheReadReq().setDefaultVal())
}

class WriteReqBuilder(busWidth: BusWidth) extends ReqBuilder(busWidth) {
  io.addrCacheRead.req <-/< io.addrCacheRead.resp
    .translateWith(AddrCacheReadReq().setDefaultVal())
}

class ReadReqBuilder(busWidth: BusWidth) extends ReqBuilder(busWidth) {}

class AtomicReqBuilder(busWidth: BusWidth) extends ReqBuilder(busWidth) {}

// Send a request
class SqLogic(busWidth: BusWidth, retry: Boolean = false) extends Component {
  val io = new Bundle {
    val workReqCached = slave(Stream(CachedWorkReq()))
    val addrCacheRead = master(AddrCacheReadBus())
    val dma = master(SqDmaBus(busWidth))
    val tx = master(RdmaDataBus(busWidth))
    val nakNotifier = out(SqNakNotifier())
  }

  val sendReqBuilder = new SendReqBuilder(busWidth)
  val writeReqBuilder = new WriteReqBuilder(busWidth)
  val readReqBuilder = new ReadReqBuilder(busWidth)
  val atomicReqBuilder = new AtomicReqBuilder(busWidth)

  val reqBuilders =
    List(sendReqBuilder, writeReqBuilder, readReqBuilder, atomicReqBuilder)
  val reqTypeFuncs = List(
    WorkReqOpCode.isSendReq(_),
    WorkReqOpCode.isWriteReq(_),
    WorkReqOpCode.isReadReq(_),
    WorkReqOpCode.isAtomicReq(_)
  )

  // TODO: support fence
  val allBuilderReady = RegNext(
    reqBuilders.map(_.io.workReqCached.ready).reduceBalancedTree(_ || _)
  )
  // TODO: do retry requests need to keep order?
  val continueCond = if (retry) True else allBuilderReady

  val reqBuilderSel =
    reqTypeFuncs.map(typeFunc => typeFunc(io.workReqCached.workReq.opcode))
  val reqBuilderIdx = OHToUInt(reqBuilderSel)
  Vec(reqBuilders.map(_.io.workReqCached)) <> StreamDemux(
    io.workReqCached.continueWhen(continueCond),
    reqBuilderIdx,
    reqBuilders.size
  )

  // TODO: merge send and write AddrCacheReadBus
  io.addrCacheRead << sendReqBuilder.io.addrCacheRead
  writeReqBuilder.io.addrCacheRead.resp <-/< writeReqBuilder.io.addrCacheRead.req
    .translateWith(AddrCacheReadResp().setDefaultVal())
//  io.addrCacheRead.send << sendReqBuilder.io.addrCacheRead
//  io.addrCacheRead.write << writeReqBuilder.io.addrCacheRead

  val txVec = Vec(reqBuilders.map(_.io.tx.pktFrag))
  val txSel = StreamArbiterFactory.roundRobin.fragmentLock.on(txVec)
  io.tx.pktFrag <-/< txSel

  io.nakNotifier.setDefaultVal()
  io.dma.sendRd << sendReqBuilder.io.dmaRead
  io.dma.writeRd << writeReqBuilder.io.dmaRead
}

// TODO: if retrying, SQ should wait until retry go-back-to-N finished?
class SendQ(busWidth: BusWidth) extends Component {
  val io = new Bundle {
    val qpAttr = in(QpAttrData())
    val psnInc = out(SqPsnInc())
    val nakNotifier = out(SqNakNotifier())
    // val qpAttrUpdate = in(QpAttrUpdateNotifier())
    // val npsn = out(UInt(PSN_WIDTH bits))
    val workReq = slave(Stream(WorkReq()))
    val addrCacheRead = master(AddrCacheReadBus())
    val workReqCachePush = master(Stream(CachedWorkReq()))
    // val reqCacheBus = master(PktCacheBus())
    val tx = master(RdmaDataBus(busWidth))
    val dma = master(SqDmaBus(busWidth))
  }
  val npsn = io.qpAttr.npsn
  val pktNum = computePktNum(io.workReq.len, io.qpAttr.pmtu)

  val twoStreams = StreamSequentialFork(
    io.workReq.translateWith {
      val rslt = CachedWorkReq()
      rslt.workReq := io.workReq.payload
      rslt.psnStart := npsn
      rslt.pktNum := pktNum
      rslt
    },
    portCount = 2
  )
  val workReqCachePush = twoStreams(0)
  val sqLogicInput = twoStreams(1)

  // TODO: verify timing, pipelining does not work here, since it requires
  // TODO: to push WorkReq into cache first, then start to process it.
  // io.workReqCachePush <-/< workReqCachePush // Not work
  io.workReqCachePush << workReqCachePush

  // Increment nPSN only when io.workReq fired
  io.psnInc.npsn.inc := workReqCachePush.fire
  io.psnInc.npsn.incVal := pktNum

  val sqLogic = new SqLogic(busWidth)
  io.nakNotifier := sqLogic.io.nakNotifier
  io.addrCacheRead << sqLogic.io.addrCacheRead
  io.dma << sqLogic.io.dma
  // io.psnInc := sqLogic.io.psnInc
  sqLogic.io.workReqCached <-/< sqLogicInput
  io.tx << sqLogic.io.tx
}
