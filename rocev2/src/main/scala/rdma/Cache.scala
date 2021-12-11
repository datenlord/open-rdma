package rdma

import spinal.core._
import spinal.lib._

class ReqCache extends Component {
  val io = new Bundle {
    val portR = slave(ReqCacheBus())
    val portRW = slave(ReqCacheBus())
    val portW = slave(ReqCacheBus())
  }

  // TODO: implementation
  io.portR.resp <-/< io.portR.req.translateWith {
    CacheResp().setDefaultVal()
  }
  io.portRW.resp <-/< io.portRW.req.translateWith {
    CacheResp().setDefaultVal()
  }
  io.portW.resp <-/< io.portW.req.translateWith {
    CacheResp().setDefaultVal()
  }
}

class AddrCache extends Component {
  val io = new Bundle {
    val portR1 = AddrCacheReadBus()
    val portR2 = AddrCacheReadBus()
  }

  // TODO: implementation
  io.portR1.resp <-/< io.portR1.req.translateWith {
    AddrReadResp().setDefaultVal()
  }
  io.portR2.resp <-/< io.portR2.req.translateWith {
    AddrReadResp().setDefaultVal()
  }

  // TODO: check cache read size within cache
//  bufSizeCheck(
//    targetPhysicalAddrReg,
//    rdmaPkt.reth.dlen,
//    pa,
//    mrSize
//  )
}
