package rdma

import spinal.core._
import spinal.lib._
// import ConstantSettings._
import RdmaConstants._

case class QueryCacheResp[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv]
) extends Bundle {
  val key = keyType()
  val value = valueType()
  val found = Bool()
}

case class QueryCacheBus[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv]
) extends Bundle
    with IMasterSlave {
  val req = Stream(keyType())
  val resp = Stream(QueryCacheResp(keyType, valueType))

//  def >>(that: QueryCacheBus[Tk, Tv]): Unit = {
//    this.req >> that.req
//    this.resp << that.resp
//  }
//
//  def <<(that: QueryCacheBus[Tk, Tv]): Unit = that >> this

  override def asMaster(): Unit = {
    master(req)
    slave(resp)
  }
}

class QueryCache[Tk <: Data, Tv <: Data](
    keyType: HardType[Tk],
    valueType: HardType[Tv],
    queryFunc: (Tk, Tv) => Bool,
    initialValue: Tv,
    depth: Int,
    portCount: Int
) extends Component {
  val io = new Bundle {
    val push = slave(Stream(valueType))
    val pop = master(Stream(valueType))
    val flush = in(Bool())
    val occupancy = out(UInt(log2Up(depth + 1) bits))
    val availability = out(UInt(log2Up(depth + 1) bits))
    val queryBusVec = Vec(slave(QueryCacheBus(keyType, valueType)), portCount)
//    val queryReq = Vec(slave(Stream(keyType)), portCount)
//    val queryResp =
//      Vec(master(Stream(QueryCacheResp(keyType, valueType))), portCount)
  }

  // Copied code from StreamFifo
  val fifo = new Area {
//    val bypass = (depth == 0) generate new Area {
//      io.push >> io.pop
//      io.occupancy := 0
//      io.availability := 0
//    }
//    val oneStage = (depth == 1) generate new Area {
//      io.push.m2sPipe(flush = io.flush) >> io.pop
//      io.occupancy := U(io.pop.valid)
//      io.availability := U(!io.pop.valid)
//    }
//    val logic = (depth > 1) generate new Area {
    val logic = new Area {
      require(depth > 1, s"depth=${depth} is not larger than 1")
      val ram = Mem(valueType, depth) init (List.fill(depth)(initialValue))
      val pushPtr = Counter(depth)
      val popPtr = Counter(depth)
      val ptrMatch = pushPtr === popPtr
      val risingOccupancy = RegInit(False)
      val pushing = io.push.fire
      val popping = io.pop.fire
      val empty = ptrMatch & !risingOccupancy
      val full = ptrMatch & risingOccupancy

      io.push.ready := !full
      io.pop.valid := !empty & !(RegNext(
        popPtr.valueNext === pushPtr,
        False
      ) & !full) //mem write to read propagation
      io.pop.payload := ram.readSync(popPtr.valueNext)

      when(pushing =/= popping) {
        risingOccupancy := pushing
      }
      when(pushing) {
        ram(pushPtr.value) := io.push.payload
        pushPtr.increment()
      }
      when(popping) {
        popPtr.increment()
      }

      val ptrDif = pushPtr - popPtr
      if (isPow2(depth)) {
        io.occupancy := ((risingOccupancy && ptrMatch) ## ptrDif).asUInt
        io.availability := ((!risingOccupancy && ptrMatch) ## (popPtr - pushPtr)).asUInt
      } else {
        when(ptrMatch) {
          io.occupancy := Mux(risingOccupancy, U(depth), U(0))
          io.availability := Mux(risingOccupancy, U(0), U(depth))
        } otherwise {
          io.occupancy := Mux(pushPtr > popPtr, ptrDif, U(depth) + ptrDif)
          io.availability := Mux(
            pushPtr > popPtr,
            U(depth) + (popPtr - pushPtr),
            (popPtr - pushPtr)
          )
        }
      }

      when(io.flush) {
        pushPtr.clear()
        popPtr.clear()
        risingOccupancy := False
      }
    }
  }

  def search(queryBus: QueryCacheBus[Tk, Tv]) = new Area {
    val queryKey = queryBus.req.payload
    val idxOH = Vec((0 until depth).map(idx => {
      val v =
        fifo.logic.ram.readAsync(
          address = U(idx, log2Up(depth) bits),
          // The read will get the new value (provided by the write)
          readUnderWrite = writeFirst
        )
      queryFunc(queryKey, v)
    }))
    val found = idxOH.asBits.andR
    val idxBinary = OHToUInt(idxOH)
    val ramIdxStream = queryBus.req.translateWith(idxBinary)
    // TODO: check the timing of PSN search in FIFO
    // ramIdxStream cannot be staged, to prevent FIFO write change to void the idxOH
    val queryResultStream =
      fifo.logic.ram
        .streamReadSync(ramIdxStream, linkedData = TupleBundle(queryKey, found))
    queryBus.resp <-/< queryResultStream.translateWith {
      val rslt = QueryCacheResp(keyType, valueType)
      rslt.value := queryResultStream.payload.value
      rslt.key := queryResultStream.payload.linked._1
      rslt.found := queryResultStream.payload.linked._2
      rslt
    }
  }

  for (busIdx <- 0 until portCount) {
    search(io.queryBusVec(busIdx))
  }
}

// pp. 287 spec 1.4
// If the responder QP supports multiple outstanding ATOMIC Operations
// and RDMA READ Operations, the information on each valid request is
// saved in FIFO order. The FIFO depth is the same as the maximum number
// of outstanding ATOMIC Operations and RDMA READ requests negotiated
// on a per QP basis at connection setup.
class ReadAtomicResultCache(depth: Int) extends Component {
  val io = new Bundle {
    val push = slave(Stream(ReadAtomicResultCacheData()))
    val pop = master(Stream(ReadAtomicResultCacheData()))
    val occupancy = out(UInt(log2Up(depth + 1) bits))
    val flush = in(Bool())
    val queryPort4DmaRead = slave(ReadAtomicResultCacheQueryBus())
    val queryPort4DupReq = slave(ReadAtomicResultCacheQueryBus())
  }

  val cache = new QueryCache(
    ReadAtomicResultCacheQueryReq(),
    ReadAtomicResultCacheData(),
    queryFunc =
      (k: ReadAtomicResultCacheQueryReq, v: ReadAtomicResultCacheData) =>
        v.psn === k.psn,
    initialValue = ReadAtomicResultCacheData().setDefaultVal(),
    depth = depth,
    portCount = 2
  )
  cache.io.push << io.push
  io.pop << cache.io.pop
  io.occupancy := cache.io.occupancy
  cache.io.flush := io.flush
  cache.io.queryBusVec(0).req << io.queryPort4DmaRead.req
  io.queryPort4DmaRead.resp << cache.io.queryBusVec(0).resp.translateWith {
    val rslt = ReadAtomicResultCacheQueryResp()
    rslt.cachedData := cache.io.queryBusVec(0).resp.value
    rslt.queryPsn := cache.io.queryBusVec(0).resp.key.psn
    rslt.found := cache.io.queryBusVec(0).resp.found
    rslt
  }
  cache.io.queryBusVec(1).req << io.queryPort4DupReq.req
  io.queryPort4DupReq.resp << cache.io.queryBusVec(1).resp.translateWith {
    val rslt = ReadAtomicResultCacheQueryResp()
    rslt.cachedData := cache.io.queryBusVec(1).resp.value
    rslt.queryPsn := cache.io.queryBusVec(1).resp.key.psn
    rslt.found := cache.io.queryBusVec(1).resp.found
    rslt
  }
//  val fifo = StreamFifo(ReadAtomicResultCacheData(), depth)
//  fifo.io.flush := io.flush
//  fifo.io.push << io.push
//  io.pop << fifo.io.pop
//
//  def query(queryBus: ReadAtomicResultCacheQueryBus) = new Area {
//    val queryPsn = queryBus.req.payload
//    val idxOH = Vec(
//      (0 until depth)
//        .map(idx => fifo.logic.ram(idx).psn === queryPsn)
//    )
//    val found = idxOH.asBits.andR
//    val idxBinary = OHToUInt(idxOH)
//    val ramIdxStream = queryBus.req.translateWith(idxBinary)
//    // TODO: check the timing of PSN search in FIFO
//    // ramIdxStream cannot be staged, to prevent FIFO write change to void the idxOH
//    val queryResultStream =
//      fifo.logic.ram
//        .streamReadSync(ramIdxStream, linkedData = TupleBundle(queryPsn, found))
//    queryBus.resp <-/< queryResultStream.translateWith {
//      val rslt = ReadAtomicResultCacheQueryResp()
//      rslt.cachedData := queryResultStream.payload.value
//      rslt.queryPsn := queryResultStream.payload.linked._1
//      rslt.found := queryResultStream.payload.linked._2
//      rslt
//    }
//  }
//
//  query(io.queryPort4Atomic)
//  query(io.queryPort4Read)
}

//class ReqCache extends Component {
//  val io = new Bundle {
//    val portR = slave(PktCacheBus())
//    val portRW = slave(PktCacheBus())
//    val portW = slave(PktCacheBus())
//  }
//
//  // TODO: implementation
//  io.portR.resp <-/< io.portR.req.translateWith {
//    PktCacheResp().setDefaultVal()
//  }
//  io.portRW.resp <-/< io.portRW.req.translateWith {
//    PktCacheResp().setDefaultVal()
//  }
//  io.portW.resp <-/< io.portW.req.translateWith {
//    PktCacheResp().setDefaultVal()
//  }
//}

class WorkReqCache(depth: Int) extends Component {
  val io = new Bundle {
    val push = slave(Stream(CachedWorkReq()))
    val pop = master(Stream(CachedWorkReq()))
    val flush = in(Bool())
    val queryBus = slave(WorkReqCacheQueryBus())
  }

  val cache = new QueryCache(
    UInt(PSN_WIDTH bits),
    CachedWorkReq(),
    queryFunc = (k: UInt, v: CachedWorkReq) =>
      v.psnStart <= k && k < (v.psnStart + v.pktNum),
    initialValue = CachedWorkReq().setDefaultVal(),
    depth = depth,
    portCount = 1
  )
  cache.io.push << io.push
  io.pop << cache.io.pop
  cache.io.flush := io.flush
  cache.io.queryBusVec(0).req << io.queryBus.req
  io.queryBus.resp << cache.io.queryBusVec(0).resp.translateWith {
    val rslt = WorkReqCacheQueryResp()
    rslt.wrCached := cache.io.queryBusVec(0).resp.value
    rslt.queryPsn := cache.io.queryBusVec(0).resp.key
    rslt.found := cache.io.queryBusVec(0).resp.found
    rslt
  }
//  val fifo = StreamFifo(CachedWorkReq(), depth)
//  fifo.io.flush := io.flush
//  fifo.io.push << io.push
//  io.pop << fifo.io.pop
//
//  val queryPsn = io.queryBus.req.payload
//  val idxOH = Vec(
//    (0 until depth)
//      .map(idx => {
//        fifo.logic.ram(idx).psnStart <= queryPsn &&
//        queryPsn < (fifo.logic.ram(idx).psnStart + fifo.logic.ram(idx).pktNum)
//      })
//  )
//  val found = idxOH.asBits.andR
//  val idxBinary = OHToUInt(idxOH)
//  val ramIdxStream = io.queryBus.req.translateWith(idxBinary)
//  // TODO: check the timing of PSN search in FIFO
//  // ramIdxStream cannot be staged, to prevent FIFO write change to void the idxOH
//  val queryResultStream =
//    fifo.logic.ram
//      .streamReadSync(ramIdxStream, linkedData = TupleBundle(queryPsn, found))
//  io.queryBus.resp <-/< queryResultStream.translateWith {
//    val rslt = WorkReqCacheQueryResp()
//    rslt.wrCached := queryResultStream.payload.value
//    rslt.queryPsn := queryResultStream.payload.linked._1
//    rslt.found := queryResultStream.payload.linked._2
//    rslt
//  }
}

// When allocate MR, it needs to update AddrCache with PD, MR key, physical/virtual address, size;
// TODO: change AddrCache to be not per QP structure, better be per PD structure
// TODO: handle zero DMA length key check
class AddrCache extends Component {
  val io = new Bundle {
    val sqCacheRead = slave(SqOrRetryAddrCacheReadBus())
    val rqCacheRead = slave(RqAddrCacheReadBus())
    val respCacheRead = slave(AddrCacheReadBus())
    val retryCacheRead = slave(SqOrRetryAddrCacheReadBus())
    // TODO: AddrCache content input
  }

  // TODO: implementation
  io.rqCacheRead.bus.resp <-/< io.rqCacheRead.bus.req
    .translateWith {
      AddrCacheReadResp().setDefaultVal()
    }
//  io.rqCacheRead.sendWrite.resp <-/< io.rqCacheRead.sendWrite.req
//    .translateWith {
//      AddrCacheReadResp().setDefaultVal()
//    }
//  io.rqCacheRead.write.resp <-/< io.rqCacheRead.write.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }
//  io.rqCacheRead.read.resp <-/< io.rqCacheRead.read.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }
//  io.rqCacheRead.atomic.resp <-/< io.rqCacheRead.atomic.req.translateWith {
//    AddrCacheReadResp().setDefaultVal()
//  }

  io.sqCacheRead.send.resp <-/< io.sqCacheRead.send.req.translateWith {
    AddrCacheReadResp().setDefaultVal()
  }
  io.sqCacheRead.write.resp <-/< io.sqCacheRead.write.req.translateWith {
    AddrCacheReadResp().setDefaultVal()
  }

  io.respCacheRead.resp <-/< io.respCacheRead.req.translateWith {
    AddrCacheReadResp().setDefaultVal()
  }

  io.retryCacheRead.send.resp <-/< io.retryCacheRead.send.req.translateWith {
    AddrCacheReadResp().setDefaultVal()
  }
  io.retryCacheRead.write.resp <-/< io.retryCacheRead.write.req.translateWith {
    AddrCacheReadResp().setDefaultVal()
  }

  // TODO: check MR size and permission
  //
  // pp. 264, spec 1.4
  // A responder that supports RDMA and / or ATOMIC Operations shall verify
  // the R_Key, the associated access rights, and the specified virtual address.
  // The responder must also perform bounds checking (i.e. verify that
  // the length of the data being referenced does not cross the associated
  // memory start and end addresses). Any violation must result in the packet
  // being discarded and for reliable services, the generation of a NAK.
}
