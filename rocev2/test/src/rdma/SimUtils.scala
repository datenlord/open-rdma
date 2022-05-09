package rdma

import spinal.core.{assert => _, _}
import spinal.core.sim._
import spinal.lib._

import ConstantSettings._
import RdmaConstants._
import RdmaTypeReDef._

import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._
import scala.collection.mutable
import scala.util.Random

case class PayloadFragNumItr(payloadFragNumItr: Iterator[FragNum]) {
  def next(): FragNum = {
    payloadFragNumItr.next()
  }
}

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

case class PktNumItr(pktNumItr: Iterator[PktNum]) {
  def next(): Int = {
    val pktNum = pktNumItr.next()

    withClue(
      f"${simTime()} time: pktNum=${pktNum} should < HALF_MAX_PSN=${HALF_MAX_PSN}"
    )(pktNum should be < HALF_MAX_PSN)

    pktNum
  }
}

case class PayloadLenItr(totalLenItr: Iterator[Int]) {
  val maxReqRespLen = 1L << (RDMA_MAX_LEN_WIDTH - 1) // 2GB

  def next(): Int = {
    val totalLen = totalLenItr.next()

    assert(
      totalLen < maxReqRespLen,
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
      dmaRespIdxGen.map(_ => 1 + Random.nextInt((avgReqRespLen - 1).toInt))
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

  private def genPayloadLen(busWidth: BusWidth.Value, maxFragNum: FragNum) = {
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
      (Random.nextInt(maxFragNum - 1) + 1)
    require(
      reqRespLenUnderMaxFragNUm >= mtyWidth,
      f"${simTime()} time: reqRespLenUnderMaxFragNUm=${reqRespLenUnderMaxFragNUm} should >= mtyWidth=${mtyWidth}"
    )
    val dmaRespIdxGen = NaturalNumber.from(0)

    // The total request/response length is from 1 byte to 2G=2^31 bytes
    val totalLenGen =
      dmaRespIdxGen.map(_ => 1 + Random.nextInt(reqRespLenUnderMaxFragNUm - 1))
    totalLenGen
  }

  private def genPayloadLen(
      busWidth: BusWidth.Value,
      maxFragNum: FragNum,
      randSeed: Int
  ) = {
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

    val randomGen = new Random(randSeed)
    val reqRespLenUnderMaxFragNUm = mtyWidth *
      (randomGen.nextInt(maxFragNum - 1) + 1)
    require(
      reqRespLenUnderMaxFragNUm >= mtyWidth,
      f"${simTime()} time: reqRespLenUnderMaxFragNUm=${reqRespLenUnderMaxFragNUm} should >= mtyWidth=${mtyWidth}"
    )
    val dmaRespIdxGen = NaturalNumber.from(0)

    // The total request/response length is from 1 byte to 2G=2^31 bytes
    val totalLenGen =
      dmaRespIdxGen.map(_ =>
        1 + randomGen.nextInt(reqRespLenUnderMaxFragNUm - 1)
      )
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

    (
      PayloadFragNumItr(payloadFragNumItr),
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

  def getItr(
      maxFragNum: Int,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      randSeed: Int
  ) = {
    val payloadLenGen = genPayloadLen(busWidth, maxFragNum, randSeed)
    genOtherItr(payloadLenGen, pmtuLen, busWidth)
  }
}

object StreamSimUtil {
  private def streamMasterDriverHelper[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain,
      alwaysValid: Boolean
  )(assignments: => Unit): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
        if (alwaysValid) {
          stream.valid #= true
        } else {
          stream.valid.randomize()
        }
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

  def streamMasterDriver[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  )(assignments: => Unit): Unit =
    streamMasterDriverHelper(stream, clockDomain, alwaysValid = false)(
      assignments
    )

  def streamMasterDriverAlwaysValid[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  )(assignments: => Unit): Unit =
    streamMasterDriverHelper(stream, clockDomain, alwaysValid = true)(
      assignments
    )

  def streamSlaveRandomizer[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  ): Unit = fork {
    while (true) {
      stream.ready.randomize()
      clockDomain.waitSampling()
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

        respStream.valid #= true
        respBody
        clockDomain.waitSamplingWhere(
          respStream.valid.toBoolean && respStream.ready.toBoolean
        )
        respStream.valid #= false
      } else {
        respStream.valid #= false
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
//        reqStream.ready #= true
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
        respStream.valid #= false
        clockDomain.waitSampling()
      }
    }
  }
  /*
  def onReceiveStreamReqAndThenResponseOneShot[T1 <: Data, T2 <: Data](
      reqStream: Stream[T1],
      respStream: Stream[T2],
      clockDomain: ClockDomain
  )(reqFireBody: => Unit)(respBody: => Unit): Unit = fork {
    reqStream.ready #= false
    respStream.valid #= false
    clockDomain.waitSampling()

    clockDomain.waitSamplingWhere(reqStream.valid.toBoolean)
    reqStream.ready #= true
    reqFireBody
    clockDomain.waitSampling()
    reqStream.ready #= false

    respStream.valid #= true
    respBody
    clockDomain.waitSamplingWhere(
      respStream.valid.toBoolean && respStream.ready.toBoolean
    )
    respStream.valid #= false
    clockDomain.waitSampling()
  }
   */
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
  }

  def streamMasterPayloadFromQueueRandomInterval[T <: Data, PayloadData](
      stream: Stream[T],
      clockDomain: ClockDomain,
      payloadQueue: mutable.Queue[PayloadData],
      maxIntervalCycles: Int,
      payloadAssignFunc: (T, PayloadData) => Unit
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
        val payloadData = MiscUtils.safeDeQueue(payloadQueue, clockDomain)
        val randomWaitingCycles =
          scala.util.Random.nextInt(maxIntervalCycles - 1) + 1
        clockDomain.waitSampling(randomWaitingCycles)
        stream.valid #= true
        payloadAssignFunc(stream.payload, payloadData)
//        do {
//          clockDomain.waitSampling()
//          stream.valid.randomize()
//          sleep(0)
//        } while (!stream.valid.toBoolean)

        clockDomain.waitSamplingWhere(
          stream.valid.toBoolean && stream.ready.toBoolean
        )
        stream.valid #= false
      }
    }

  def streamMasterPayloadFromQueueFixedInterval[T <: Data, PayloadData](
      stream: Stream[T],
      clockDomain: ClockDomain,
      payloadQueue: mutable.Queue[PayloadData],
      maxIntervalCycles: Int,
      payloadAssignFunc: (T, PayloadData) => Unit
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
        val payloadData = MiscUtils.safeDeQueue(payloadQueue, clockDomain)
        clockDomain.waitSampling(maxIntervalCycles)
        stream.valid #= true
        payloadAssignFunc(stream.payload, payloadData)

        clockDomain.waitSamplingWhere(
          stream.valid.toBoolean && stream.ready.toBoolean
        )
        stream.valid #= false
      }
    }

  def streamMasterPayloadFromQueue[T <: Data, PayloadData](
      stream: Stream[T],
      clockDomain: ClockDomain,
      payloadQueue: mutable.Queue[PayloadData],
//    semaphore: Semaphore,
      payloadAssignFunc: (T, PayloadData) => Unit
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
//        semaphore.acquire()
        val payloadData = MiscUtils.safeDeQueue(payloadQueue, clockDomain)
        do {
          clockDomain.waitSampling()
          stream.valid.randomize()
          stream.payload.randomize()
          sleep(0)
        } while (!stream.valid.toBoolean)
        payloadAssignFunc(stream.payload, payloadData)

        clockDomain.waitSamplingWhere(
          stream.valid.toBoolean && stream.ready.toBoolean
        )
        stream.valid #= false
      }
    }

  def streamMasterPayloadFromQueueAlwaysValid[T <: Data, PayloadData](
      stream: Stream[T],
      clockDomain: ClockDomain,
      payloadQueue: mutable.Queue[PayloadData],
//     semaphore: Semaphore,
      payloadAssignFunc: (T, PayloadData) => Unit
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
//        semaphore.acquire()
        val payloadData = MiscUtils.safeDeQueue(payloadQueue, clockDomain)
        stream.valid #= true
        stream.payload.randomize()
        payloadAssignFunc(stream.payload, payloadData)

        clockDomain.waitSamplingWhere(
          stream.valid.toBoolean && stream.ready.toBoolean
        )
        stream.valid #= false
      }
    }

//  private def streamMasterSyncDriverHelper[T <: Data](
//                                                   stream: Stream[T],
//                                                   clockDomain: ClockDomain,
//                                                   semaphore: Semaphore,
//                                                   alwaysValid: Boolean
//                                                 )(assignments: => Unit): Unit =
//    fork {
//      stream.valid #= false
//      clockDomain.waitSampling()
//
//      while (true) {
//        semaphore.acquire()
//
//        if (alwaysValid) {
//          stream.valid #= true
//        } else {
//          stream.valid.randomize()
//        }
//        stream.payload.randomize()
//        sleep(0)
//
//        if (stream.valid.toBoolean) {
//          assignments
//          clockDomain.waitSamplingWhere(
//            stream.valid.toBoolean && stream.ready.toBoolean
//          )
//        } else {
//          clockDomain.waitSampling()
//        }
//      }
//    }
//
//  def streamMasterSyncDriver[T <: Data](
//                                     stream: Stream[T],
//                                     clockDomain: ClockDomain,
//                                     semaphore: Semaphore,
//                                   )(assignments: => Unit): Unit =
//    streamMasterSyncDriverHelper(stream, clockDomain, semaphore, alwaysValid = false)(
//      assignments
//    )
//
//  def streamMasterSyncDriverAlwaysValid[T <: Data](
//                                                stream: Stream[T],
//                                                clockDomain: ClockDomain,
//                                                semaphore: Semaphore,
//                                              )(assignments: => Unit): Unit =
//    streamMasterSyncDriverHelper(stream, clockDomain, semaphore, alwaysValid = true)(
//      assignments
//    )

  // TODO: remove this
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
                withClue(
                  f"${simTime()} time: this fragment with fragIdx=${fragIdx}%X is the last one, pktIdx=${pktIdx}%X should equal pktNum=${pktNum}%X-1"
                )(pktIdx shouldBe (pktNum - 1))
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

          withClue(
            f"${simTime()} time: inputData=${inputData} not match outputData=${outputData} @ outputIdx=${outputIdx}"
          )(inputData shouldBe outputData)

          matchQueue.enqueue(inputData)
//          println(f"${simTime()} time: matchQueue.size=${matchQueue.size}")
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
//      if (!cond) {
//        println(f"${simTime()} time: always condition=${cond} not satisfied")
//      }
      cond shouldBe true withClue f"${simTime()} time: always condition=${cond} not satisfied"
    }
  }

  def checkConditionAlwaysHold(
      clockDomain: ClockDomain
  )(cond: => Boolean, clue: => Any) = fork {
    while (true) {
      clockDomain.waitSampling()
      cond shouldBe true withClue clue
    }
  }

  def checkSignalWhen(
      clockDomain: ClockDomain,
      when: => Boolean,
      signal: => Boolean,
      clue: Any
  ) = fork {
    while (true) {
      clockDomain.waitSampling()
      if (when) {
        signal shouldBe true withClue (clue)
        // f"${simTime()} time: condition=${cond} not satisfied when=${when}"
      }
    }
  }

  def checkConditionForSomePeriod(clockDomain: ClockDomain, cycles: Int)(
      cond: => Boolean
  ) = fork {
    require(cycles > 0, s"cycles=${cycles} should > 0")

    for (cycleIdx <- 0 until cycles) {
      clockDomain.waitSampling()

      withClue(
        f"${simTime()} time: condition=${cond} not satisfied @ cycleIdx=${cycleIdx}"
      )(cond shouldBe true)
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
        withClue(
          f"${simTime()} time: last fragment data=${lastFragDataOutValidBits}%X not match last fragment input data=${lastFragDataInValidBits}%X with minimum last fragment out MTY=(${lastFragMtyMinimumByteNum}*BYTE_WIDTH)"
        )(
          (lastFragDataOutValidBits & lastFragMtyMinimumBits) shouldBe (lastFragDataInValidBits & lastFragMtyMinimumBits)
        )
//          println(
//            f"${simTime()} time: expected output PSN=${nextPsn} not match output PSN=${psnOut}"
//          )
        withClue(
          f"${simTime()} time: expected output PSN=${nextPsn} not match output PSN=${psnOut}"
        )(psnOut shouldBe nextPsn)
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
    minHeaderWidthBytes + Random.between(
      0,
      headerMtyValidWidthRange / headerMtyMultiplier
    ) * headerMtyMultiplier // between(minInclusive: Int, maxExclusive: Int)
  }
}

object NaturalNumber {
  // Generate natural numbers from input N
  def from(n: Int): LazyList[Int] = n #:: from(n + 1)
}
