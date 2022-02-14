package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable

import ConstantSettings._

object SendWriteReqReadRespInput {
  def getItr(pmtuBytes: Int, busWidthBytes: Int) = {
    val dmaRespIdxGen = NaturalNumber.from(0)
    val fragNumGen = dmaRespIdxGen.map(_ * 2 + 1)
    val totalLenGen =
      fragNumGen.map(_ * busWidthBytes.toLong) // Assume all bits are valid
    val pktNumGen = totalLenGen.map(MiscUtils.computePktNum(_, pmtuBytes))
    val psnGen = pktNumGen.scan(0)(_ + _)
    val fragNumItr = fragNumGen.iterator
    val pktNumItr = pktNumGen.iterator
    val psnItr = psnGen.iterator
    val totalLenItr = totalLenGen.iterator
    //    for (idx <- 0 until 3) {
    //      println(f"idx=$idx, fragNum=${fragNumItr.next()}, pktNum=${pktNumItr
    //        .next()}, psnStart=${psnItr.next()}, totalLenBytes=${totalLenItr.next()}")
    //    }
    (fragNumItr, pktNumItr, psnItr, totalLenItr)
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

  def fragmentStreamMasterDriverAlwaysValid[T <: Data, D](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain
  )(outerLoopBody: => (Int, D))(innerLoopFunc: (Int, (Int, D)) => Unit): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
        val (innerLoopNum, rslt) = outerLoopBody
        for (innerLoopIdx <- 0 until innerLoopNum) {
          stream.valid #= true
          stream.payload.randomize()
          sleep(0)

          innerLoopFunc(innerLoopIdx, (innerLoopNum, rslt))
          clockDomain.waitSamplingWhere(
            stream.valid.toBoolean && stream.ready.toBoolean
          )
        }
      }
    }

  def fragmentStreamMasterDriver[T <: Data, D](
      stream: Stream[Fragment[T]],
      clockDomain: ClockDomain
  )(outerLoopBody: => (Int, D))(innerLoopFunc: (Int, (Int, D)) => Unit): Unit =
    fork {
      stream.valid #= false
      clockDomain.waitSampling()

      while (true) {
        val (innerLoopNum, rslt) = outerLoopBody
        for (innerLoopIdx <- 0 until innerLoopNum) {
          stream.valid.randomize()
          stream.payload.randomize()
          sleep(0)
          // Loop until valid
          while (!stream.valid.toBoolean) {
            clockDomain.waitSampling()
            stream.valid.randomize()
            sleep(0)
          }

          innerLoopFunc(innerLoopIdx, (innerLoopNum, rslt))
          clockDomain.waitSamplingWhere(
            stream.valid.toBoolean && stream.ready.toBoolean
          )
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
      f"psnA=${psnA}, psnB=${psnB}, curPsn=${curPsn} should all >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN && curPsn < TOTAL_PSN,
      f"psnA=${psnA}, psnB=${psnB}, curPsn=${curPsn} should all < TOTAL_PSN=${TOTAL_PSN}"
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
  def psnDiff(psnA: Int, psnB: Int): Int = {
    require(
      psnA >= 0 && psnB >= 0,
      f"psnA=${psnA}, psnB=${psnB} should both >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN,
      f"psnA=${psnA}, psnB=${psnB} should both < TOTAL_PSN=${TOTAL_PSN}"
    )

    val (min, max) = if (psnA > psnB) {
      (psnB, psnA)
    } else {
      (psnA, psnB)
    }
    val diff = max - min
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
      f"psnA=${psnA}, psnB=${psnB} should both >= 0"
    )
    require(
      psnA < TOTAL_PSN && psnB < TOTAL_PSN,
      f"psnA=${psnA}, psnB=${psnB} should both < TOTAL_PSN=${TOTAL_PSN}"
    )
    (psnA + psnB) % TOTAL_PSN
  }
  def computePktNum(pktLenBytes: Long, pmtuBytes: Int): Int = {
    require(
      pktLenBytes >= 0 && pmtuBytes > 0,
      f"pktLenBytes=${pktLenBytes} should >= 0, and pmtuBytes=${pmtuBytes} should > 0"
    )
    val remainder = pktLenBytes % pmtuBytes
    val quotient = pktLenBytes / pmtuBytes
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
            f"inputData=${inputData} not match outputData=${outputData} @ outputIdx=${outputIdx}"
          )
          matchQueue.enqueue(inputData)
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
        clockDomain.waitFallingEdge()
        if (inputQueue.nonEmpty && outputQueue.nonEmpty) {
          val outputIdx = outputIdxItr.next()

          val inputData = inputQueue.dequeue()
          val outputData = outputQueue.dequeue()
          println(
            f"inputData=${inputData} not match outputData=${outputData} @ outputIdx=${outputIdx}"
          )
          matchQueue.enqueue(inputData)
        }
      }
    }

    waitUntil(matchQueue.size > showCnt)
  }

  def checkConditionAlways(clockDomain: ClockDomain)(cond: => Boolean) = fork {
    while (true) {
      clockDomain.waitSampling()
      assert(cond, f"always condition=${cond} not satisfied")
    }
  }

  def checkConditionForSomePeriod(clockDomain: ClockDomain, cycles: Int)(
      cond: => Boolean
  ) = fork {
    require(cycles > 0, s"cycles=${cycles} should > 0")

    for (cycleIdx <- 0 until cycles) {
      clockDomain.waitSampling()
      assert(cond, f"condition=${cond} not satisfied @ cycleIdx=${cycleIdx}")
    }
  }

  def checkSendWriteReqReadResp(
      clockDomain: ClockDomain,
      inputDataQueue: mutable.Queue[(BigInt, BigInt, Int, Int, Long, Boolean)],
      outputDataQueue: mutable.Queue[(BigInt, BigInt, Int, Boolean)],
      busWidth: BusWidth.BusWidth
  ) = {
    val mtyWidth = busWidth.id / BYTE_WIDTH
    val matchPsnQueue = mutable.Queue[Boolean]()

    fork {
      var nextPsn = 0
      while (true) {
        var (dataIn, mtyIn, pktNum, psnStart, totalLenBytes, isLastIn) =
          (BigInt(0), BigInt(0), 0, 0, 0L, false)
        do {
          val inputData =
            MiscUtils.safeDeQueue(inputDataQueue, clockDomain)
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
          val outputData =
            MiscUtils.safeDeQueue(outputDataQueue, clockDomain)
          dataOut = outputData._1
          mtyOut = outputData._2
          psnOut = outputData._3
          isLastOut = outputData._4

//            println(
//              f"pktNum=${pktNum}, psnStart=${psnStart}, totalLenBytes=${totalLenBytes}, isLastIn=${isLastIn}, psnOut=${psnOut}, isLastOut=${isLastOut}"
//            )
          matchPsnQueue.enqueue(isLastOut)
        } while (!isLastOut)

        val lastFragMtyInValidBytesNum = MiscUtils.countOnes(mtyIn, mtyWidth)
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
//            f"last fragment data=${lastFragDataOutValidBits}%X not match last fragment input data=${lastFragDataInValidBits}%X with minimum last fragment MTY=(${lastFragMtyMinimumByteNum}*BYTE_WIDTH)"
//          )
        assert(
          (lastFragDataOutValidBits & lastFragMtyMinimumBits)
            .toString(16) == (lastFragDataInValidBits & lastFragMtyMinimumBits)
            .toString(16),
          f"last fragment data=${lastFragDataOutValidBits}%X not match last fragment input data=${lastFragDataInValidBits}%X with minimum last fragment out MTY=(${lastFragMtyMinimumByteNum}*BYTE_WIDTH)"
        )

//          println(
//            f"expected output PSN=${nextPsn} not match output PSN=${psnOut}"
//          )
        assert(
          psnOut == nextPsn,
          f"expected output PSN=${nextPsn} not match output PSN=${psnOut}"
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
