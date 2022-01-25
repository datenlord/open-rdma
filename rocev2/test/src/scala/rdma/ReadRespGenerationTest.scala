package rdma

import spinal.core.sim._
import org.scalatest.funsuite.AnyFunSuite
//import ConstantSettings._

class RqReadDmaRespHandlerTest extends AnyFunSuite {
  val busWidth = BusWidth.W512

  val simCfg = SimConfig.allOptimisation.withWave
    .compile(new RqReadDmaRespHandler(busWidth))

  test("normal DMA read response test") {
    simCfg.doSim { dut =>
      dut.clockDomain.forkStimulus(10)

      simSuccess()
    }
  }
}
