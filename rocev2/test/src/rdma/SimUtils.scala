package rdma

import spinal.core.{assert => _, _}
import spinal.core.sim._
import spinal.lib._

import org.scalatest.matchers.should.Matchers._
import org.scalatest.AppendedClues._
import scala.collection.mutable
import scala.util.Random

import ConstantSettings._
import RdmaConstants._
import RdmaTypeReDef._

// Test related settings
object SimSettings {
  val SIM_CYCLE_TIME = 10L
  val MATCH_CNT = 1000
  val INIT_PSN = 0
}

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

    assert(
      pktNum < HALF_MAX_PSN,
      f"${simTime()} time: pktNum=${pktNum} should < HALF_MAX_PSN=${HALF_MAX_PSN}"
    )

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

  def maxFragNumPerPkt(pmtuLen: PMTU.Value, busWidth: BusWidth.Value): Int = {
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
    val maxFragNum = (1 << pmtuLen.id) / mtyWidth
    maxFragNum
  }

  def maxPayloadFragNumPerReqOrResp(busWidth: BusWidth.Value): Int = {
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
    val maxFragNum = (1L << RDMA_MAX_LEN_WIDTH) / mtyWidth
    maxFragNum.toInt
  }

  private def genPayloadLen() = {
    val avgReqRespLen = maxReqRespLen / MAX_PENDING_REQ_NUM
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
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
    require(
      mtyWidth > 0,
      s"${simTime()} time: mtyWidth=${mtyWidth} should be positive"
    )

    val fragNumLimit = maxReqRespLen / mtyWidth
    require(
      maxFragNum <= fragNumLimit,
      s"${simTime()} time: input maxFragNum=${maxFragNum} is too large, should <= fragNumLimit=${fragNumLimit}"
    )

    val reqRespLenUnderMaxFragNUm = mtyWidth *
      (Random.nextInt(maxFragNum - 1) + 1)
    require(
      reqRespLenUnderMaxFragNUm >= mtyWidth,
      s"${simTime()} time: reqRespLenUnderMaxFragNUm=${reqRespLenUnderMaxFragNUm} should >= mtyWidth=${mtyWidth}"
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
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
    require(
      mtyWidth > 0,
      s"${simTime()} time: mtyWidth=${mtyWidth} should be positive"
    )

    val fragNumLimit = maxReqRespLen / mtyWidth
    require(
      maxFragNum <= fragNumLimit,
      s"${simTime()} time: input maxFragNum=${maxFragNum} is too large, should <= fragNumLimit=${fragNumLimit}"
    )

    val randomGen = new Random(randSeed)
    val reqRespLenUnderMaxFragNUm = mtyWidth *
      (randomGen.nextInt(maxFragNum - 1) + 1)
    require(
      reqRespLenUnderMaxFragNUm >= mtyWidth,
      s"${simTime()} time: reqRespLenUnderMaxFragNUm=${reqRespLenUnderMaxFragNUm} should >= mtyWidth=${mtyWidth}"
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
      busWidth: BusWidth.Value,
      psnStart: PSN
  ) = {
    val payloadFragNumGen =
      payloadLenGen.map(payloadLen =>
        MiscUtils.computeFragNum(payloadLen.toLong, busWidth)
      )
    val pktNumGen = payloadLenGen.map(payloadLen =>
      MiscUtils.computePktNum(payloadLen.toLong, pmtuLen)
    )
    // psnStartGen uses Long to avoid overflow, since Scala has not unsigned number
    val psnStartGen =
      pktNumGen.map(_.toLong).scan(psnStart.toLong)(_ + _)
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
    genOtherItr(payloadLenGen, pmtuLen, busWidth, SimSettings.INIT_PSN)
  }

  def getItr(maxFragNum: Int, pmtuLen: PMTU.Value, busWidth: BusWidth.Value) = {
    val payloadLenGen = genPayloadLen(busWidth, maxFragNum)
    genOtherItr(payloadLenGen, pmtuLen, busWidth, SimSettings.INIT_PSN)
  }

  def getItr(
      psnStart: PSN,
      maxFragNum: Int,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value
  ) = {
    val payloadLenGen = genPayloadLen(busWidth, maxFragNum)
    genOtherItr(payloadLenGen, pmtuLen, busWidth, psnStart)
  }

  def getItr(pmtuLen: PMTU.Value, busWidth: BusWidth.Value, randSeed: Int) = {
    val payloadLenGen = genPayloadLen(randSeed)
    genOtherItr(payloadLenGen, pmtuLen, busWidth, SimSettings.INIT_PSN)
  }

  def getItr(
      maxFragNum: Int,
      pmtuLen: PMTU.Value,
      busWidth: BusWidth.Value,
      randSeed: Int
  ) = {
    val payloadLenGen = genPayloadLen(busWidth, maxFragNum, randSeed)
    genOtherItr(payloadLenGen, pmtuLen, busWidth, SimSettings.INIT_PSN)
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

  def streamMasterDriverOneShot[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain
  )(assignments: => Unit): Unit = {
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

  // TODO: refactor streamMasterPayloadFromQueue related functions
  def streamMasterPayloadFromQueue[T <: Data, PayloadData](
      stream: Stream[T],
      clockDomain: ClockDomain,
      payloadQueue: mutable.Queue[PayloadData],
      payloadAssignFunc: (T, PayloadData) => Boolean
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
        stream.payload.randomize()
        sleep(0)
        val payloadData = MiscUtils.safeDeQueue(payloadQueue, clockDomain)
        val payloadValid = payloadAssignFunc(stream.payload, payloadData)
        if (payloadValid) {
          do {
            clockDomain.waitSampling()
            stream.valid.randomize()
            sleep(0)
          } while (!stream.valid.toBoolean)

          clockDomain.waitSamplingWhere(
            stream.valid.toBoolean && stream.ready.toBoolean
          )
          stream.valid #= false
        } else {
          clockDomain.waitSampling()
        }
      }
    }

  def streamMasterPayloadFromQueueNoRandomDelay[T <: Data, PayloadData](
      stream: Stream[T],
      clockDomain: ClockDomain,
      payloadQueue: mutable.Queue[PayloadData],
      payloadAssignFunc: (T, PayloadData) => Boolean
  ): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
        stream.payload.randomize()
        sleep(0)

//        println(
//          f"${simTime()} time: payloadQueue.isEmpty=${payloadQueue.isEmpty}"
//        )
        val payloadData = MiscUtils.safeDeQueue(payloadQueue, clockDomain)
//        println(
//          f"${simTime()} time: after payloadQueue safe pop, payloadQueue.isEmpty=${payloadQueue.isEmpty}"
//        )

        val payloadValid = payloadAssignFunc(stream.payload, payloadData)
//        payloadValid shouldBe true withClue
//          f"${simTime()} time: payloadValid=${payloadValid} should be true always in streamMasterPayloadFromQueueNoRandomDelay"

        if (payloadValid) {
          stream.valid #= true
//          println(f"${simTime()} time: stream.valid=${stream.valid.toBoolean}")

          clockDomain.waitSamplingWhere(
            stream.valid.toBoolean && stream.ready.toBoolean
          )
          stream.valid #= false
        } else {
          clockDomain.waitSampling()
        }
      }
    }

  def streamSlaveReadyOnCondition[T <: Data](
      stream: Stream[T],
      clockDomain: ClockDomain,
      condition: => Boolean
  ): Unit = fork {
    while (true) {
      stream.ready #= condition
      if (condition) {
        clockDomain.waitSamplingWhere(
          stream.valid.toBoolean && stream.ready.toBoolean
        )
      } else {
        clockDomain.waitSampling()
      }
    }
  }
}

object MiscUtils {
  def busWidthBytes(busWidth: BusWidth.Value): Int = busWidth.id / BYTE_WIDTH

  def safeDeQueue[T](queue: mutable.Queue[T], clockDomain: ClockDomain): T = {
    while (queue.isEmpty) {
      clockDomain.waitFallingEdge()
//      clockDomain.waitSampling()
    }
    queue.dequeue()
  }

  def computeFragNum(pktLenBytes: Long, busWidth: BusWidth.Value): Int = {
    require(
      pktLenBytes >= 0,
      s"${simTime()} time: pktLenBytes=${pktLenBytes} should >= 0"
    )
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
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
      s"${simTime()} time: pktLenBytes=${pktLenBytes} should >= 0"
    )
    val remainder = pktLenBytes & setAllBits(pmtu.id)
    val quotient = pktLenBytes >> pmtu.id
    if (remainder > 0) {
      (quotient + 1).toInt
    } else {
      quotient.toInt
    }
  }

  def getPmtuPktLenBytes(pmtu: PMTU.Value): Int = {
    pmtu match {
      case PMTU.U256  => 256
      case PMTU.U512  => 512
      case PMTU.U1024 => 1024
      case PMTU.U2048 => 2048
      case PMTU.U4096 => 4096
      case _          => SpinalExit(s"${simTime()} time: invalid PMTU=${pmtu}")
    }
  }

  def isFragLast(
      fragIdx: FragNum,
      maxFragNumPerPkt: FragNum,
      totalFragNum: FragNum,
      segmentRespByPmtu: Boolean
  ): Boolean = {
    val fragLast = if (segmentRespByPmtu) {
      ((fragIdx % maxFragNumPerPkt) == (maxFragNumPerPkt - 1)) || (fragIdx == totalFragNum - 1)
    } else {
      fragIdx == totalFragNum - 1
    }
    fragLast
  }

  private def checkExpectedOutputMatchHelper[T](
      clockDomain: ClockDomain,
      expectedQueue: mutable.Queue[T],
      outputQueue: mutable.Queue[T],
      crashOnMismatch: Boolean
  ): mutable.Queue[T] = {
    val outputIdxItr = NaturalNumber.from(0).iterator
    val matchQueue = mutable.Queue[T]()

    fork {
      while (true) {
        val outputIdx = outputIdxItr.next()

        // NOTE: must check output before expected data so as to make sure output generated
        val outputData = MiscUtils.safeDeQueue(outputQueue, clockDomain)
        val expectedData = MiscUtils.safeDeQueue(expectedQueue, clockDomain)

        if (crashOnMismatch) {
          expectedData shouldBe outputData withClue
            f"${simTime()} time: expectedData=${expectedData} not match outputData=${outputData} @ outputIdx=${outputIdx}"
        } else {
          println(
            f"${simTime()} time: expectedData=${expectedData}, outputData=${outputData} @ outputIdx=${outputIdx}"
          )
        }
        matchQueue.enqueue(expectedData)
//        println(f"${simTime()} time: matchQueue.size=${matchQueue.size}")
      }
    }

    matchQueue
  }

  def checkExpectedOutputMatch[T](
      clockDomain: ClockDomain,
      expectedQueue: mutable.Queue[T],
      outputQueue: mutable.Queue[T],
      matchNum: Int
  ): Unit = {
    require(
      matchNum > 0,
      s"${simTime()} time: the number of matches matchNum=${matchNum} should > 0"
    )

    val matchQueue = checkExpectedOutputMatchHelper(
      clockDomain,
      expectedQueue,
      outputQueue,
      crashOnMismatch = true
    )
    waitUntil(matchQueue.size > matchNum)
  }

  def checkExpectedOutputMatchAlways[T](
      clockDomain: ClockDomain,
      expectedQueue: mutable.Queue[T],
      outputQueue: mutable.Queue[T]
  ) =
    checkExpectedOutputMatchHelper(
      clockDomain,
      expectedQueue,
      outputQueue,
      crashOnMismatch = true
    )

  def showExpectedOutputMatch[T](
      clockDomain: ClockDomain,
      expectedQueue: mutable.Queue[T],
      outputQueue: mutable.Queue[T],
      showCnt: Int
  ): Unit = {
    require(
      showCnt > 0,
      s"${simTime()} time: the number of show count=${showCnt} should > 0"
    )

    val matchQueue = checkExpectedOutputMatchHelper(
      clockDomain,
      expectedQueue,
      outputQueue,
      crashOnMismatch = false
    )
    waitUntil(matchQueue.size > showCnt)
  }

  def showExpectedOutputMatchAlways[T](
      clockDomain: ClockDomain,
      expectedQueue: mutable.Queue[T],
      outputQueue: mutable.Queue[T]
  ) =
    checkExpectedOutputMatchHelper(
      clockDomain,
      expectedQueue,
      outputQueue,
      crashOnMismatch = false
    )

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

  def checkCondChangeOnceAndHoldAfterwards(
      clockDomain: ClockDomain,
      cond: => Boolean,
      clue: => Any
  ) = fork {
    // Once condition is true, check it to be true always
    clockDomain.waitSamplingWhere(cond)
//    waitUntil(cond)
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

//  def checkConditionForSomePeriod(clockDomain: ClockDomain, cycles: Int)(
//      cond: => Boolean
//  ) = fork {
//    require(cycles > 0, s"${simTime()} time: cycles=${cycles} should > 0")
//
//    for (cycleIdx <- 0 until cycles) {
//      clockDomain.waitSampling()
//
//      cond shouldBe true withClue
//        f"${simTime()} time: condition=${cond} not satisfied @ cycleIdx=${cycleIdx}"
//    }
//  }

  def checkSendWriteReqReadResp(
      clockDomain: ClockDomain,
      inputDataQueue: mutable.Queue[(BigInt, BigInt, Int, Int, Long, Boolean)],
      outputDataQueue: mutable.Queue[(BigInt, BigInt, Int, Boolean)],
      busWidth: BusWidth.Value
  ) = fork {
    val mtyWidth = MiscUtils.busWidthBytes(busWidth)
    val matchPsnQueue = mutable.Queue[Boolean]()
//      clockDomain.waitSampling()

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
      (lastFragDataOutValidBits & lastFragMtyMinimumBits) shouldBe
        (lastFragDataInValidBits & lastFragMtyMinimumBits) withClue
        f"${simTime()} time: last fragment data=${lastFragDataOutValidBits}%X not match last fragment input data=${lastFragDataInValidBits}%X with minimum last fragment out MTY=(${lastFragMtyMinimumByteNum}*BYTE_WIDTH)"
//      )(
//        (lastFragDataOutValidBits & lastFragMtyMinimumBits) shouldBe (lastFragDataInValidBits & lastFragMtyMinimumBits)
//      )
//          println(
//            f"${simTime()} time: expected output PSN=${nextPsn} not match output PSN=${psnOut}"
//          )
      psnOut shouldBe nextPsn withClue
        f"${simTime()} time: expected output PSN=${nextPsn} not match output PSN=${psnOut}"
      nextPsn += 1
    }

    waitUntil(matchPsnQueue.size > SimSettings.MATCH_CNT)
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

case class DelayedQueue[T](
    clockDomain: ClockDomain,
    delayCycles: Int
) {
  require(
    delayCycles > 0,
    s"${simTime()} time: delayCycles=${delayCycles} should > 0"
  )

  val queue = mutable.Queue[(T, SimTimeStamp)]()

  def length: Int = queue.length
  def isEmpty: Boolean = queue.isEmpty

  def enqueue(elem: T): this.type = {
    val curTimeStamp = simTime()
    queue.enqueue((elem, curTimeStamp))

//    println(f"${simTime()} time: enqueue, curTimeStamp=${curTimeStamp}")
    this
  }

  def toMutableQueue(): mutable.Queue[T] = {
    val mutableQueue = mutable.Queue[T]()
    fork {
      while (true) {
        val (elem, insertTimeStamp) = MiscUtils.safeDeQueue(queue, clockDomain)

        while (
          simTime() < SimSettings.SIM_CYCLE_TIME * delayCycles + insertTimeStamp
        ) {
//        println(f"${simTime()} time: delayed dequeue, insertTimeStamp=${insertTimeStamp}, delayCycles=${delayCycles}")
          clockDomain.waitSampling()
        }
//    println(f"${simTime()} time: success dequeue, insertTimeStamp=${insertTimeStamp}, delayCycles=${delayCycles}")

        mutableQueue.enqueue(elem)
      }
    }
    mutableQueue
  }
}
