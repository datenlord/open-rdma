package rdma

import org.scalatest.funsuite.AnyFunSuite
import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable

class CamBufferTest extends AnyFunSuite{
  test("ordered input"){
    SimConfig
      .withWave
      .compile(new CamBuffer(Bits(16 bits), 64, true))
      .doSim{dut =>
        dut.clockDomain.forkStimulus(2)
        dut.io.readData.ready #= false
        dut.clockDomain.waitSamplingWhere(dut.io.writePsn.ready.toBoolean)
        for(i <- 0 until 64){
          dut.io.writePsn.valid #= true
          dut.io.writePsn.payload #= i
          dut.clockDomain.waitRisingEdge()
        }
        //assert(!dut.io.writePsn.ready.toBoolean)
      }
  }
}

class SeqOutTest extends AnyFunSuite{

  class RxDriver(rx: Stream[Fragment[RdmaDataBus]], clockDomain: ClockDomain, fragments: Int){
    val psnQueue = new mutable.Queue[Int]
    rx.valid #= false

    def push(psnList: List[Int]): Unit ={
      psnQueue ++= psnList
    }

    fork{
      while(true){
        if(psnQueue.nonEmpty){
          rx.bth.psn #= psnQueue.dequeue()
          rx.valid #= true
          for(i <- 1 to fragments){
            if(i==fragments){
              rx.last #= true
            }
            clockDomain.waitSamplingWhere(rx.ready.toBoolean)
            rx.last #= false
          }
        }else{
          rx.valid #= false
          clockDomain.waitRisingEdge()
        }
      }
    }
  }

  class PsnSetter(qpAttr: QpAttrData, qpAttrUpdate: Stream[Bits], clockDomain: ClockDomain){
    qpAttrUpdate.valid #= false
    qpAttrUpdate.payload #= QpAttrMask.QP_RQ_PSN.id

    def set(psn: Int): Unit ={
        qpAttrUpdate.valid #= true
        qpAttr.epsn #= psn
        clockDomain.waitSamplingWhere(qpAttrUpdate.ready.toBoolean)
        qpAttrUpdate.valid #= false
    }
  }

  test("set PSN"){
    SimConfig
      .withWave
      .compile{
        val dut = new SeqOut(BusWidth.W128, 64, false)
        dut.opsnReg.simPublic()
        dut
      }
      .doSim{dut=>
        SimTimeout(3000)
        dut.clockDomain.forkStimulus(2)
        dut.io.tx.ready #= true
        val rxOtherDriver = new RxDriver(dut.io.rxOtherResp, dut.clockDomain, 1)
        val rxReadDriver = new RxDriver(dut.io.rxReadResp, dut.clockDomain, 8)
        val psnSetter = new PsnSetter(dut.io.qpAttr, dut.io.qpAttrUpdate, dut.clockDomain)
        psnSetter.set(200)
        rxOtherDriver.push((200 until 222).toList)
        //rxReadDriver.push((222 until 233).toList)
        rxOtherDriver.push((222 until 250).reverse.toList)
        rxOtherDriver.push((250 until 300).toList)
        dut.clockDomain.waitRisingEdge(1000)
      }
  }
}
