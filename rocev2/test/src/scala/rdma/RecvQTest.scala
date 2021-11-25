package rdma

import org.scalatest.funsuite.AnyFunSuite
import spinal.core
import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable
import scala.util.Random

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
        println(s"psn is set to $psn")
    }
  }

  class DisorderPsnGenerator(bufferDepth:Int){
    val maxDistanceBetweenTwoConsecutivePSN = bufferDepth-1
    var PSN: Int = -1
    def setPsn(psn:Int): Unit ={
      PSN = psn
    }
    def genNext(n:Int): List[Int] ={
      assert(PSN!=(-1))
      var psnList = (PSN until PSN+n).toList
      var moved = List.fill(n)(false)
      var distance = maxDistanceBetweenTwoConsecutivePSN
      for(i <- psnList.indices){
        if(!moved(i)){
          val r = Random.nextInt(distance)
          val j = i + r
          if(j<psnList.length){
            val t = psnList(j)
            psnList = psnList.updated(j, psnList(i))
            psnList = psnList.updated(i, t)
            distance = distance + 1 - r
            if(distance>maxDistanceBetweenTwoConsecutivePSN){
              distance = maxDistanceBetweenTwoConsecutivePSN
            }
            moved = moved.updated(j, true)
          }
        }
      }
      println(s"generate psn from $PSN until ${PSN+n}")
      psnList
    }
  }

  def waitTargetPsn(dut:SeqOut, psn:Int): Unit ={
    var timeOut = 1000
    while(
      !(dut.io.tx.bth.psn.toInt == psn && dut.io.tx.valid.toBoolean && dut.io.tx.ready.toBoolean)
    ){
      timeOut -= 1
      dut.clockDomain.waitRisingEdge()
      if(timeOut==0){
        simFailure(Seq(
          s"sim time out when waiting psn = ${psn}, opsnReg = ${dut.opsnReg.toInt}",
          s"rxOtherResp.psn = ${dut.io.rxOtherResp.bth.psn.toInt}, valid ${dut.io.rxOtherResp.valid.toBoolean}, ready ${dut.io.rxOtherResp.ready.toBoolean}",
          s"tx.psn          = ${dut.io.tx.bth.psn.toInt}, valid ${dut.io.tx.valid.toBoolean}, ready ${dut.io.tx.ready.toBoolean}",
          ).reduce((a,b)=>a++"\n"++b)
        )
      }
    }
  }

  def simRxOtherRespSimple(dut:SeqOut, bufferDepth:Int): Unit ={
    SimTimeout(10000)
    dut.clockDomain.forkStimulus(2)
    dut.io.tx.ready #= true
    val rxOtherDriver = new RxDriver(dut.io.rxOtherResp, dut.clockDomain, 1)
    val rxReadDriver = new RxDriver(dut.io.rxReadResp, dut.clockDomain, 8)
    val psnSetter = new PsnSetter(dut.io.qpAttr, dut.io.qpAttrUpdate, dut.clockDomain)
    // test 1 simple test
    psnSetter.set(200)
    rxOtherDriver.push((200 until 222).toList)
    rxOtherDriver.push((222 until 250).reverse.toList)
    rxOtherDriver.push((250 to 300).toList)
    waitTargetPsn(dut, 300)

    psnSetter.set(20210)
    rxOtherDriver.push((20210 until 20220).toList)
    rxOtherDriver.push((20220 until 20230).reverse.toList)
    waitTargetPsn(dut, 20229)

    // test 2 with random tx.ready
    rxOtherDriver.push((20230 to 20520).toList)
    var test3End = false
    fork{
      while(test3End){
        dut.io.tx.ready #= !dut.io.tx.ready.toBoolean
        dut.clockDomain.waitRisingEdge(Random.nextInt(8))
      }
    }
    waitTargetPsn(dut, 20520)
    test3End = true

    // test 4 write until full
    dut.io.tx.ready #= false
    psnSetter.set(1000)
    val fullPsn = 1000 + bufferDepth + 1
    rxOtherDriver.push((1000 to fullPsn).toList)
    // the 'ready' fall means full
    dut.clockDomain.waitSamplingWhere(!dut.io.rxOtherResp.ready.toBoolean)
    dut.io.tx.ready #= true
    waitTargetPsn(dut, fullPsn)
  }

  def simRxOtherRespRandom(dut: SeqOut, bufferDepth:Int): Unit ={
    dut.clockDomain.forkStimulus(2)
    fork{
      while(true){
        dut.io.tx.ready #= !dut.io.tx.ready.toBoolean
        dut.clockDomain.waitRisingEdge(Random.nextInt(bufferDepth))
      }
    }
    val rxOtherDriver = new RxDriver(dut.io.rxOtherResp, dut.clockDomain, 1)
    val rxReadDriver = new RxDriver(dut.io.rxReadResp, dut.clockDomain, 8)
    val psnSetter = new PsnSetter(dut.io.qpAttr, dut.io.qpAttrUpdate, dut.clockDomain)
    val disorderPsn = new DisorderPsnGenerator(bufferDepth)

    for(_ <- 0 to 100){
      // the psn width is 24, so...
      val psn = Random.nextInt(0xFFFFFF)
      var packetNum = Random.nextInt(0xFFF)
      if(psn+packetNum>0xFFFFFF){
        packetNum = 0xFFFFFF - psn
      }
      psnSetter.set(psn)
      disorderPsn.setPsn(psn)
      rxOtherDriver.push(disorderPsn.genNext(packetNum))
      for(targetPsn <- psn until psn+packetNum){
        waitTargetPsn(dut, targetPsn)
      }
    }
  }
  test("rxOtherResp simple test"){
    val bufferDepth = 64
    SimConfig
      .withWave
      .compile(new SeqOut(BusWidth.W128, bufferDepth, false))
      .doSim(simRxOtherRespSimple(_, bufferDepth))
  }

  test("rxOtherResp random test"){
    for(bufferDepth <- List(32, 64, 128, 256, 512)){
      SimConfig
        .compile(new SeqOut(BusWidth.W128, bufferDepth, false))
        .doSim{simRxOtherRespRandom(_, bufferDepth)}
    }
  }
}
