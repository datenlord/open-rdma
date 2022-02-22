package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import ConstantSettings._
import RdmaConstants._
import RdmaTypeReDef._
//import PsnSim._

import scala.collection.mutable
import scala.util.Random

case class PsnStartItr(psnStartItr: Iterator[Long]) {
  def next(): Int = {
    val nextPsnStart = psnStartItr.next() % TOTAL_PSN
    assert(
      nextPsnStart >= 0,
      f"${simTime()} time: nextPsnStart=${nextPsnStart} overflowed, it should be positive"
    )
    nextPsnStart.toInt
  }
}

case class PktNumItr(pktNumItr: Iterator[Int]) {
  def next(): Int = {
    val pktNum = pktNumItr.next()
    assert(
      pktNum < HALF_MAX_PSN,
      f"${simTime()} time: pktNum=${pktNum} should < HALF_MAX_PSN=${HALF_MAX_PSN}"
    )
    pktNum
  }
}

case class PayloadLenItr(totalLenItr: Iterator[Int]) {
  def next(): Int = {
    val totalLen = totalLenItr.next()
    assert(
      totalLen < (1L << (RDMA_MAX_LEN_WIDTH - 1)), // 2GB
      f"${simTime()} time: totalLen=${totalLen} should < 2G"
    )
    totalLen
  }
}

object SendWriteReqReadRespInputGen {
  val maxReqRespLen = 1L << (RDMA_MAX_LEN_WIDTH - 1) // 2GB

  def busWidthBytes(busWidth: BusWidth.Value): Int = busWidth.id / BYTE_WIDTH

  def pmtuLenBytes(pmtu: PMTU.Value): Int = {
    pmtu match {
      case PMTU.U256  => 256
      case PMTU.U512  => 512
      case PMTU.U1024 => 1024
      case PMTU.U2048 => 2048
      case PMTU.U4096 => 4096
      case _ => {
        println(f"${simTime()} time: invalid PMTU=${pmtu}")
        ???
      }
    }
  }

  def maxFragNumPerPkt(pmtuLen: PMTU.Value, busWidth: BusWidth.Value): Int = {
    val mtyWidth = busWidthBytes(busWidth)
    val maxFragNum = (1 << pmtuLen.id) / mtyWidth
    maxFragNum
  }

  def maxPayloadFragNumPerReqOrResp(busWidth: BusWidth.Value): Int = {
    val mtyWidth = busWidthBytes(busWidth)
    val maxFragNum = (1L << RDMA_MAX_LEN_WIDTH) / mtyWidth
    maxFragNum.toInt
  }

  private def genPayloadLen() = {
    val avgReqRespLen = maxReqRespLen / PENDING_REQ_NUM
    val dmaRespIdxGen = NaturalNumber.from(0)

    // The total request/response length is from 1 byte to 2G=2^31 bytes
    val totalLenGen =
      dmaRespIdxGen.map(_ =>
        1 + scala.util.Random.nextInt((avgReqRespLen - 1).toInt)
      )
    totalLenGen
  }

  private def genPayloadLen(busWidth: BusWidth.Value, maxFragNum: Int) = {
    val mtyWidth = busWidthBytes(busWidth)
    require(
      mtyWidth > 0,
      f"${simTime()} time: mtyWidth=${mtyWidth} should be positive"
    )

    val fragNumLimit = maxReqRespLen / mtyWidth
    require(
      maxFragNum <= fragNumLimit,
      f"input maxFragNum=${maxFragNum} is too large, should <= fragNumLimit=${fragNumLimit}"
    )

    val reqRespLenUnderMaxFragNUm = mtyWidth *
      (scala.util.Random.nextInt(maxFragNum - 1) + 1)
    require(
      reqRespLenUnderMaxFragNUm >= mtyWidth,
      f"${simTime()} time: reqRespLenUnderMaxFragNUm=${reqRespLenUnderMaxFragNUm} should >= mtyWidth=${mtyWidth}"
    )
    val dmaRespIdxGen = NaturalNumber.from(0)

    // The total request/response length is from 1 byte to 2G=2^31 bytes
    val totalLenGen =
      dmaRespIdxGen.map(_ =>
        1 + scala.util.Random.nextInt(reqRespLenUnderMaxFragNUm - 1)
      )
    totalLenGen
  }

  private def genPayloadLen(randSeed: Int) = {
    val dmaRespIdxGen = NaturalNumber.from(0)

    // The total request/response length is from 1 byte to 2G=2^31 bytes
    val randomGen = new Random(randSeed)
    val totalLenGen =
      dmaRespIdxGen.map(_ => 1 + randomGen.nextInt((maxReqRespLen - 1).toInt))
    totalLenGen
  }

  private def genOtherItr(
      payloadLenGen: LazyList[Int],
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value
  ) = {
    val payloadFragNumGen =
      payloadLenGen.map(payloadLen =>
        MiscUtils.computeFragNum(payloadLen.toLong, busWidth)
      )
    val pktNumGen = payloadLenGen.map(payloadLen =>
      MiscUtils.computePktNum(payloadLen.toLong, pmtuLen)
    )
    // psnStartGen uses Long to avoid overflow, since Scala has not unsigned number
    val psnStartGen = pktNumGen.map(_.toLong).scan(0L)(_ + _)
    val payloadFragNumItr = payloadFragNumGen.iterator
    val pktNumItr = pktNumGen.iterator
    val psnStartItr = psnStartGen.iterator
    val payloadLenItr = payloadLenGen.iterator

//    for (idx <- 0 until 10) {
//      println(f"${simTime()} time: idx=$idx, fragNum=${fragNumItr.next()}, pktNum=${pktNumItr
//        .next()}, psnStart=${psnItr.next()}, totalLenBytes=${totalLenItr.next()}")
//    }
    (
      payloadFragNumItr,
      PktNumItr(pktNumItr),
      PsnStartItr(psnStartItr),
      PayloadLenItr(payloadLenItr)
    )
  }

  def getItr(pmtuLen: PMTU.Value, busWidth: BusWidth.Value) = {
    val payloadLenGen = genPayloadLen()
    genOtherItr(payloadLenGen, pmtuLen, busWidth)
  }

  def getItr(maxFragNum: Int, pmtuLen: PMTU.Value, busWidth: BusWidth.Value) = {
    val payloadLenGen = genPayloadLen(busWidth, maxFragNum)
    genOtherItr(payloadLenGen, pmtuLen, busWidth)
  }

  def getItr(pmtuLen: PMTU.Value, busWidth: BusWidth.Value, randSeed: Int) = {
    val payloadLenGen = genPayloadLen(randSeed)
    genOtherItr(payloadLenGen, pmtuLen, busWidth)
  }
}

object StreamSimUtil {
  def streamMasterDriver[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  )(assignments: => Unit): Unit = fork {
    stream.valid #= false
    clockDomain.waitSampling()

    while (true) {
      stream.valid.randomize()
      stream.payload.randomize()
      sleep(0)
      if (stream.valid.toBoolean) {
        assignments
        clockDomain.waitSamplingWhere(
          stream.valid.toBoolean && stream.ready.toBoolean
        )
      } else {
        clockDomain.waitSampling()
      }
    }
  }

  def streamSlaveRandomizer[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  ): Unit = fork {
    while (true) {
      stream.ready.randomize()
      clockDomain.waitSampling()
    }
  }

  def onStreamFire[T <: Data](stream: Stream[T], clockDomain: ClockDomain)(
      body: => Unit
  ): Unit = fork {
    while (true) {
      clockDomain.waitSampling()
      if (stream.valid.toBoolean && stream.ready.toBoolean) {
        body
      }
    }
  }

  def onStreamFireFallingEdge[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  )(body: => Unit): Unit = fork {
    while (true) {
      clockDomain.waitFallingEdge()
      if (stream.valid.toBoolean && stream.ready.toBoolean) {
        body
      }
    }
  }

  def streamMasterDriverOneShot[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  )(assignments: => Unit): Unit = fork {
    stream.valid #= false
    clockDomain.waitSampling()

    stream.valid #= true
    stream.payload.randomize()
    sleep(0)
    assignments
    clockDomain.waitSamplingWhere(
      stream.valid.toBoolean && stream.ready.toBoolean
    )
    stream.valid #= false
    clockDomain.waitSampling()
  }

  def streamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  )(assignments: => Unit): Unit = fork {
    stream.valid #= false
    clockDomain.waitSampling()

    while (true) {
      stream.valid #= true
      stream.payload.randomize()
      sleep(0)
      if (stream.valid.toBoolean) {
        assignments
        clockDomain.waitSamplingWhere(
          stream.valid.toBoolean && stream.ready.toBoolean
        )
      } else {
        clockDomain.waitSampling()
      }
    }
  }

  def streamSlaveAlwaysReady[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  ): Unit = fork {
    stream.ready #= true
    while (true) {
      clockDomain.waitSampling()
    }
  }

  def onReceiveStreamReqAndThenResponseAlways[T1 <: Data, T2 <: Data](
      reqStream: Stream[T1],
      respStream: Stream[T2],
      clockDomain: ClockDomain
  )(reqFireBody: => Unit)(respBody: => Unit): Unit = fork {
    reqStream.ready #= false
    respStream.valid #= false
    clockDomain.waitSampling()

    while (true) {
      if (reqStream.valid.toBoolean) {
        reqStream.ready #= true
        reqFireBody
        clockDomain.waitSampling()
        reqStream.ready #= false
        //        clockDomain.waitSampling()

        respStream.valid #= true
        respBody
        clockDomain.waitSamplingWhere(
          respStream.valid.toBoolean && respStream.ready.toBoolean
        )
        respStream.valid #= false
      } else {
        clockDomain.waitSampling()
      }
    }
  }

  def onReceiveStreamReqAndThenResponseRandom[T1 <: Data, T2 <: Data](
      reqStream: Stream[T1],
      respStream: Stream[T2],
      clockDomain: ClockDomain
  )(reqFireBody: => Unit)(respBody: => Unit): Unit = fork {
    reqStream.ready #= false
    respStream.valid #= false
    clockDomain.waitSampling()

    val fixedLatencyBetweenReqAndResp = 2
    while (true) {
      if (reqStream.valid.toBoolean) {
        reqStream.ready #= true
        do {
          reqStream.ready.randomize()
          clockDomain.waitSampling()
        } while (!reqStream.ready.toBoolean)
        reqFireBody
        reqStream.ready #= false

        clockDomain.waitSampling(fixedLatencyBetweenReqAndResp)

        respStream.valid #= true
        respBody
        clockDomain.waitSamplingWhere(
          respStream.valid.toBoolean && respStream.ready.toBoolean
        )
        respStream.valid #= false
      } else {
        clockDomain.waitSampling()
      }
    }
  }

  def onReceiveStreamReqAndThenResponseOneShot[T1 <: Data, T2 <: Data](
      reqStream: Stream[T1],
      respStream: Stream[T2],
      clockDomain: ClockDomain
  )(reqFireBody: => Unit)(respBody: => Unit): Unit = fork {
    reqStream.ready #= false
    respStream.valid #= false
    clockDomain.waitSampling()

//    while (!reqStream.valid.toBoolean) {
    clockDomain.waitSamplingWhere(reqStream.valid.toBoolean)
//    }
    reqStream.ready #= true
    reqFireBody
    clockDomain.waitSampling()
    reqStream.ready #= false
    //    clockDomain.waitSampling()

    respStream.valid #= true
    respBody
    clockDomain.waitSamplingWhere(
      respStream.valid.toBoolean && respStream.ready.toBoolean
    )
    respStream.valid #= false
    clockDomain.waitSampling()
  }

  def queryCacheHelper[Treq <: Data, Tresp <: Data, ReqData, RespData](
      reqStream: Stream[Treq],
      respStream: Stream[Tresp],
      onReqFire: (Treq, mutable.Queue[ReqData]) => Unit,
      buildResp: (Tresp, mutable.Queue[ReqData]) => Unit,
      onRespFire: (Tresp, mutable.Queue[RespData]) => Unit,
      clockDomain: ClockDomain,
      alwaysValid: Boolean
  ): mutable.Queue[RespData] = {
    val reqQueue = mutable.Queue[ReqData]()
    val respQueue = mutable.Queue[RespData]()

    if (alwaysValid) {
      onReceiveStreamReqAndThenResponseAlways(
        reqStream = reqStream,
        respStream = respStream,
        clockDomain
      ) {
        onReqFire(reqStream.payload, reqQueue)
      } {
        buildResp(respStream.payload, reqQueue)
      }
    } else {
      onReceiveStreamReqAndThenResponseRandom(
        reqStream = reqStream,
        respStream = respStream,
        clockDomain
      ) {
        onReqFire(reqStream.payload, reqQueue)
      } {
        buildResp(respStream.payload, reqQueue)
      }
    }

    onStreamFire(respStream, clockDomain) {
      onRespFire(respStream.payload, respQueue)
    }

    respQueue
    /*
    val addrCacheReadReqQueue = mutable.Queue[(PSN, VirtualAddr)]()
    val addrCacheReadRespQueue =
      mutable.Queue[(PSN, KeyValid, SizeValid, AccessValid, PhysicalAddr)]()

    val onReq = () => {
      addrCacheReadReqQueue.enqueue(
        (addrCacheRead.req.psn.toInt, addrCacheRead.req.va.toBigInt)
      )
//    println(
//      f"${simTime()} time: dut.io.addrCacheRead.req received PSN=${dut.io.addrCacheRead.req.psn.toInt}%X"
//    )
    }

    val onResp = () => {
      val (psn, _) = addrCacheReadReqQueue.dequeue()
      addrCacheRead.resp.psn #= psn
      if (alwaysSuccess) {
        addrCacheRead.resp.keyValid #= true
        addrCacheRead.resp.sizeValid #= true
        addrCacheRead.resp.accessValid #= true
      } else {
        addrCacheRead.resp.keyValid #= false
        addrCacheRead.resp.sizeValid #= false
        addrCacheRead.resp.accessValid #= false
      }
    }

    if (alwaysValid) {
      onReceiveStreamReqAndThenResponseAlways(
        reqStream = addrCacheRead.req,
        respStream = addrCacheRead.resp,
        clockDomain
      ) {
        onReq()
      } {
        onResp()
      }
    } else {
      onReceiveStreamReqAndThenResponseRandom(
        reqStream = addrCacheRead.req,
        respStream = addrCacheRead.resp,
        clockDomain
      ) {
        onReq()
      } {
        onResp()
      }
    }

    onStreamFire(addrCacheRead.resp, clockDomain) {
      addrCacheReadRespQueue.enqueue(
        (
          addrCacheRead.resp.psn.toInt,
          addrCacheRead.resp.keyValid.toBoolean,
          addrCacheRead.resp.sizeValid.toBoolean,
          addrCacheRead.resp.accessValid.toBoolean,
          addrCacheRead.resp.pa.toBigInt
        )
      )
      //        println(
      //          f"${simTime()} time: dut.io.addrCacheRead.resp PSN=${dut.io.addrCacheRead.resp.psn.toInt}%X"
      //        )
    }

    addrCacheReadRespQueue
     */
  }

  def pktFragStreamMasterDriverAlwaysValid[T <: Data, InternalData](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain
  )(
      outerLoopBody: => (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          InternalData
      )
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          InternalData
      ) => Unit
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      // Outer loop
      while (true) {
        val (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, internalData) =
          outerLoopBody
        val maxFragNumPerPkt =
          SendWriteReqReadRespInputGen.maxFragNumPerPkt(pmtuLen, busWidth)

        // Inner loop
        for (fragIdx <- 0 until totalFragNum) {
          val pktIdx = fragIdx / maxFragNumPerPkt
          val psn = psnStart + pktIdx
          val fragLast =
            ((fragIdx % maxFragNumPerPkt) == (maxFragNumPerPkt - 1)) || (fragIdx == totalFragNum - 1)
//          println(
//            f"${simTime()} time: pktIdx=${pktIdx}%X, pktNum=${pktNum}%X, fragIdx=${fragIdx}%X, totalFragNum=${totalFragNum}%X, fragLast=${fragLast}, PSN=${psn}%X, maxFragNumPerPkt=${maxFragNumPerPkt}%X"
//          )
          stream.valid #= true
          stream.payload.randomize()
          sleep(0)

          innerLoopFunc(
            psn,
            psnStart,
            fragLast,
            fragIdx,
            totalFragNum,
            pktIdx,
            pktNum,
            internalData
          )
          if (fragIdx == totalFragNum - 1) {
            assert(
              pktIdx == pktNum - 1,
              f"${simTime()} time: this fragment with fragIdx=${fragIdx}%X is the last one, pktIdx=${pktIdx}%X should == pktNum=${pktNum}%X-1"
            )
          }
          clockDomain.waitSamplingWhere(
            stream.valid.toBoolean && stream.ready.toBoolean
          )
        }
      }
    }

  def pktFragStreamMasterDriver[T <: Data, InternalData](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain
  )(
      outerLoopBody: => (
          PsnStart,
          FragNum,
          PktNum,
          PMTU.Value,
          BusWidth.Value,
          InternalData
      )
  )(
      innerLoopFunc: (
          PSN,
          PsnStart,
          FragLast,
          FragIdx,
          FragNum,
          PktIdx,
          PktNum,
          InternalData
      ) => Unit
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      // Outer loop
      while (true) {
        val (psnStart, totalFragNum, pktNum, pmtuLen, busWidth, internalData) =
          outerLoopBody
        val maxFragNumPerPkt =
          SendWriteReqReadRespInputGen.maxFragNumPerPkt(pmtuLen, busWidth)

        // Inner loop
        for (fragIdx <- 0 until totalFragNum) {
          val pktIdx = fragIdx / maxFragNumPerPkt
          val psn = psnStart + pktIdx
          val fragLast =
            ((fragIdx % maxFragNumPerPkt) == (maxFragNumPerPkt - 1)) || (fragIdx == totalFragNum - 1)
//          println(
//            f"${simTime()} time: pktIdx=${pktIdx}%X, pktNum=${pktNum}%X, fragIdx=${fragIdx}%X, totalFragNum=${totalFragNum}%X, fragLast=${fragLast}, PSN=${psn}%X, maxFragNumPerPkt=${maxFragNumPerPkt}%X"
//          )

          do {
            stream.valid.randomize()
            stream.payload.randomize()
            sleep(0)
            if (stream.valid.toBoolean) {
              innerLoopFunc(
                psn,
                psnStart,
                fragLast,
                fragIdx,
                totalFragNum,
                pktIdx,
                pktNum,
                internalData
              )
              if (fragIdx == totalFragNum - 1) {
                assert(
                  pktIdx == pktNum - 1,
                  f"${simTime()} time: this fragment with fragIdx=${fragIdx}%X is the last one, pktIdx=${pktIdx}%X should == pktNum=${pktNum}%X-1"
                )
              }
              clockDomain.waitSamplingWhere(
                stream.valid.toBoolean && stream.ready.toBoolean
              )
            } else {
              clockDomain.waitSampling()
            }
          } while (!stream.valid.toBoolean)
        }
      }
    }
}

object MiscUtils {
  def safeDeQueue[T](queue: mutable.Queue[T], clockDomain: ClockDomain): T = {
    while (queue.isEmpty) {
      clockDomain.waitFallingEdge()
    }
    queue.dequeue()
  }

  def psnCmp(psnA: Int, psnB: Int, curPsn: Int): Int = {
    require(
      psnA >= 0 && psnB >= 0 && curPsn >= 0,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB}, curPsn=${curPsn} should all >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN && curPsn < TOTAL_PSN,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB}, curPsn=${curPsn} should all < TOTAL_PSN=${TOTAL_PSN}"
    )
    val oldestPSN = (curPsn - HALF_MAX_PSN) & PSN_MASK

    if (psnA == psnB) {
      0
    } else if (psnA < psnB) {
      if (oldestPSN <= psnA) {
        -1 // LESSER
      } else if (psnB <= oldestPSN) {
        -1 // LESSER
      } else {
        1 // GREATER
      }
    } else { // psnA > psnB
      if (psnA <= oldestPSN) {
        1 // GREATER
      } else if (oldestPSN <= psnB) {
        1 // GREATER
      } else {
        -1 // LESSER
      }
    }
  }

  /** psnA - psnB, always <= HALF_MAX_PSN
    */
  def psnDiff(psnA: PSN, psnB: PSN): PSN = {
    require(
      psnA >= 0 && psnB >= 0,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both < TOTAL_PSN=${TOTAL_PSN}"
    )
    val diff = ((psnA + TOTAL_PSN) - psnB) % TOTAL_PSN
//    val (min, max) = if (psnA > psnB) {
//      (psnB, psnA)
//    } else {
//      (psnA, psnB)
//    }
//    val diff = max - min
    if (diff > HALF_MAX_PSN) {
      TOTAL_PSN - diff
    } else {
      diff
    }
  }

  /** psnA + psnB, modulo by TOTAL_PSN
    */
  def psnAdd(psnA: Int, psnB: Int): Int = {
    require(
      psnA >= 0 && psnB >= 0,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN,
      f"${simTime()} time: psnA=${psnA}, psnB=${psnB} should both < TOTAL_PSN=${TOTAL_PSN}"
    )
    (psnA + psnB) % TOTAL_PSN
  }

  def computeFragNum(pktLenBytes: Long, busWidth: BusWidth.Value): Int = {
    require(
      pktLenBytes >= 0,
      f"${simTime()} time: pktLenBytes=${pktLenBytes} should >= 0"
    )
    val mtyWidth = busWidth.id / BYTE_WIDTH
    val remainder = pktLenBytes % mtyWidth
    val quotient = pktLenBytes / mtyWidth
    if (remainder > 0) {
      (quotient + 1).toInt
    } else {
      quotient.toInt
    }
  }

  def computePktNum(pktLenBytes: Long, pmtu: PMTU.Value): Int = {
    require(
      pktLenBytes >= 0,
      f"${simTime()} time: pktLenBytes=${pktLenBytes} should >= 0"
    )
    val remainder = pktLenBytes & setAllBits(pmtu.id)
    val quotient = pktLenBytes >> pmtu.id
    if (remainder > 0) {
      (quotient + 1).toInt
    } else {
      quotient.toInt
    }
  }

  def checkInputOutputQueues[T](
      clockDomain: ClockDomain,
      inputQueue: mutable.Queue[T],
      outputQueue: mutable.Queue[T],
      matchNum: Int
  ): Unit = {
    require(
      matchNum > 0,
      s"the number of matches matchNum=${matchNum} should > 0"
    )

    val outputIdxItr = NaturalNumber.from(0).iterator
    val matchQueue = mutable.Queue[T]()

    fork {
      while (true) {
        clockDomain.waitFallingEdge()
        if (inputQueue.nonEmpty && outputQueue.nonEmpty) {
          val outputIdx = outputIdxItr.next()

          val inputData = inputQueue.dequeue()
          val outputData = outputQueue.dequeue()
          assert(
            inputData == outputData,
            f"${simTime()} time: inputData=${inputData} not match outputData=${outputData} @ outputIdx=${outputIdx}"
          )
          matchQueue.enqueue(inputData)
//          println(f"matchQueue.size=${matchQueue.size}")
        }
      }
    }

    waitUntil(matchQueue.size > matchNum)
  }

  def showInputOutputQueues[T](
      clockDomain: ClockDomain,
      inputQueue: mutable.Queue[T],
      outputQueue: mutable.Queue[T],
      showCnt: Int
  ): Unit = {
    require(showCnt > 0, s"the number of show count=${showCnt} should > 0")

    val outputIdxItr = NaturalNumber.from(0).iterator
    val matchQueue = mutable.Queue[T]()

    fork {
      while (true) {
        val outputIdx = outputIdxItr.next()

        val inputData = safeDeQueue(inputQueue, clockDomain)
        val outputData = safeDeQueue(outputQueue, clockDomain)
        println(
          f"${simTime()} time: inputData=${inputData} not match outputData=${outputData} @ outputIdx=${outputIdx}"
        )
        matchQueue.enqueue(inputData)

      }
    }

    waitUntil(matchQueue.size > showCnt)
  }

  def checkConditionAlways(clockDomain: ClockDomain)(cond: => Boolean) = fork {
    while (true) {
      clockDomain.waitSampling()
      if (!cond) {
        println(f"${simTime()} time: always condition=${cond} not satisfied")
      }
      assert(cond, f"${simTime()} time: always condition=${cond} not satisfied")
    }
  }

  def checkConditionForSomePeriod(clockDomain: ClockDomain, cycles: Int)(
      cond: => Boolean
  ) = fork {
    require(cycles > 0, s"cycles=${cycles} should > 0")

    for (cycleIdx <- 0 until cycles) {
      clockDomain.waitSampling()
      assert(
        cond,
        f"${simTime()} time: condition=${cond} not satisfied @ cycleIdx=${cycleIdx}"
      )
    }
  }

  def checkSendWriteReqReadResp(
      clockDomain: ClockDomain,
      inputDataQueue: mutable.Queue[(BigInt, BigInt, Int, Int, Long, Boolean)],
      outputDataQueue: mutable.Queue[(BigInt, BigInt, Int, Boolean)],
      busWidth: BusWidth.Value
  ) = {
    val mtyWidth = busWidth.id / BYTE_WIDTH
    val matchPsnQueue = mutable.Queue[Boolean]()

    fork {
      var nextPsn = 0
      while (true) {
        var (dataIn, mtyIn, pktNum, psnStart, totalLenBytes, isLastIn) =
          (BigInt(0), BigInt(0), 0, 0, 0L, false)
        do {
          val inputData = safeDeQueue(inputDataQueue, clockDomain)
          dataIn = inputData._1
          mtyIn = inputData._2
          pktNum = inputData._3
          psnStart = inputData._4
          totalLenBytes = inputData._5
          isLastIn = inputData._6
        } while (!isLastIn)

        var (dataOut, mtyOut, psnOut, isLastOut) =
          (BigInt(0), BigInt(0), 0, false)
        do {
          val outputData = safeDeQueue(outputDataQueue, clockDomain)
          dataOut = outputData._1
          mtyOut = outputData._2
          psnOut = outputData._3
          isLastOut = outputData._4

//            println(
//              f"${simTime()} time: pktNum=${pktNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}, isLastIn=${isLastIn}, psnOut=${psnOut}, isLastOut=${isLastOut}"
//            )
          matchPsnQueue.enqueue(isLastOut)
        } while (!isLastOut)

        val lastFragMtyInValidBytesNum = countOnes(mtyIn, mtyWidth)
        val lastFragMtyOutValidBytesNum =
          MiscUtils.countOnes(mtyOut, mtyWidth)
        val lastFragMtyMinimumByteNum =
          lastFragMtyInValidBytesNum.min(lastFragMtyOutValidBytesNum)
        val lastFragMtyMinimumBitNum = lastFragMtyMinimumByteNum * BYTE_WIDTH
        val dataInLastFragRightShiftBitAmt =
          busWidth.id - (lastFragMtyInValidBytesNum * BYTE_WIDTH)
        val dataOutLastFragRightShiftBitAmt =
          busWidth.id - (lastFragMtyOutValidBytesNum * BYTE_WIDTH)

        val lastFragDataInValidBits = dataIn >> dataInLastFragRightShiftBitAmt
        val lastFragDataOutValidBits =
          dataOut >> dataOutLastFragRightShiftBitAmt
        val lastFragMtyMinimumBits = setAllBits(lastFragMtyMinimumBitNum)

//          println(
//            f"${simTime()} time: last fragment data=${lastFragDataOutValidBits}%X not match last fragment input data=${lastFragDataInValidBits}%X with minimum last fragment MTY=(${lastFragMtyMinimumByteNum}*BYTE_WIDTH)"
//          )
        assert(
          (lastFragDataOutValidBits & lastFragMtyMinimumBits)
            .toString(16) == (lastFragDataInValidBits & lastFragMtyMinimumBits)
            .toString(16),
          f"${simTime()} time: last fragment data=${lastFragDataOutValidBits}%X not match last fragment input data=${lastFragDataInValidBits}%X with minimum last fragment out MTY=(${lastFragMtyMinimumByteNum}*BYTE_WIDTH)"
        )

//          println(
//            f"${simTime()} time: expected output PSN=${nextPsn} not match output PSN=${psnOut}"
//          )
        assert(
          psnOut == nextPsn,
          f"${simTime()} time: expected output PSN=${nextPsn} not match output PSN=${psnOut}"
        )
        nextPsn += 1
      }
    }

    waitUntil(matchPsnQueue.size > MATCH_CNT)
  }

  def countOnes(num: BigInt, width: Int): Int = {
    val bits = for (shiftAmt <- 0 until width) yield {
      (num >> shiftAmt & 1).toInt
    }
    bits.sum
  }

  def truncate(num: BigInt, width: Int): BigInt = {
    num & setAllBits(width)
  }

  def minHeaderWidthBytes: Int = (widthOf(BTH()) / BYTE_WIDTH)
  def maxHeaderWidthBytes: Int =
    (widthOf(BTH()) + widthOf(AtomicEth())) / BYTE_WIDTH

  def randomHeaderByteWidth(): Int = {
    val headerMtyValidWidthRange = maxHeaderWidthBytes - minHeaderWidthBytes
    val headerMtyMultiplier = 4 // Header width is multiple of 4 bytes
    minHeaderWidthBytes + scala.util.Random
      .between(
        0,
        headerMtyValidWidthRange / headerMtyMultiplier
      ) * headerMtyMultiplier // between(minInclusive: Int, maxExclusive: Int)
  }
}

object NaturalNumber {
  // Generate natural numbers from input N
  def from(n: Int): LazyList[Int] = n #:: from(n + 1)
}