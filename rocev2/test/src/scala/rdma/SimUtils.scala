package rdma

import spinal.core._
import spinal.core.sim._
import spinal.lib._

import scala.collection.mutable

import ConstantSettings._

//object SimUtils {
//  def randomizeSignalAndWaitUntilTrue(signal: Bool, clockDomain: ClockDomain) =
//    new Area {
//      while (!signal.toBoolean) {
//        clockDomain.waitSampling()
//        signal.randomize()
//        sleep(0) // To make signal random assignment take effect
//      }
//    }
//}

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

  def dividedByUp(dividend: Int, divisor: Int): Int = {
    require(
      dividend >= 0 && divisor > 0,
      f"dividend=${dividend} should >= 0, and divisor=${divisor} should > 0"
    )
    val remainder = dividend % divisor
    val quotient = dividend / divisor
    if (remainder > 0) {
      quotient + 1
    } else {
      quotient
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
      assert(cond, f"condition=${cond} not satisfied")
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
