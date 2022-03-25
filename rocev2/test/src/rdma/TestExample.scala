package rdma

import org.scalatest.funsuite.AnyFunSuite

class SetSuite extends AnyFunSuite {
  test("An empty Set should have size 0") {
//    assert(Set.empty.size == 0)

    @scala.annotation.tailrec
    def loop(n: Int, f: Int => Boolean): Unit = {
      if (n > 0) {
        val result = f(n)
        val nNext = if (result) n - 1 else n
        loop(nNext, f)
      }
    }

    loop(
      10,
      n => {
        println(s"n=$n")
        scala.util.Random.nextBoolean()
      }
    )
  }

  test("scan right") {
    def scanRight[A](l: List[A], z: A)(op: (A, A) => A): List[A] = {
      def go(l: List[A], z: A)(op: (A, A) => A): (List[A], A) = l match {
        case Nil => (Nil, z)
        case h :: t => {
          val (ll, zz) = go(t, z)(op)
          val zzz = op(h, zz)
          (zzz :: ll, zzz)
        }
      }
      val (ll, _) = go(l, z)(op)
      ll
    }

    println(scanRight(List(1, 2, 3), 0)((i, j) => i + j))
  }

  test("State Monad") {
    def unit[S, A](a: A): State[S, A] = State { s => (a, s) }
    def setState[S](s: S): State[S, Unit] = State[S, Unit] { _ => ((), s) }

    case class State[S, +A](run: S => (A, S)) {
      def flatMap[B](f: A => State[S, B]): State[S, B] =
        State[S, B] { s =>
          {
            val (a, s1) = run(s)
            f(a).run(s1)
          }
        }
      def map[B](f: A => B): State[S, B] =
        State[S, B] { s =>
          {
            val (a, s1) = run(s)
            (f(a), s1)
          }
        }
      def map_1[B](f: A => B): State[S, B] = flatMap(a => unit(f(a)))

      def map2[B, C](sb: State[S, B])(f: (A, B) => C): State[S, C] =
        State[S, C] { s =>
          {
            val (a, s1) = run(s)
            val (b, s2) = sb.run(s1)
            (f(a, b), s2)
          }
        }
      def map2_1[B, C](sb: State[S, B])(f: (A, B) => C): State[S, C] =
        flatMap(a => sb.map(b => f(a, b)))

      def map3[B, C, D](sb: State[S, B], sc: State[S, C])(
          f: (A, B, C) => D
      ): State[S, D] =
        for {
          a <- this
          b <- sb
          c <- sc
        } yield f(a, b, c)
      def map3_1[B, C, D](sb: State[S, B], sc: State[S, C])(
          f: (A, B, C) => D
      ): State[S, D] =
        flatMap(a => sb.flatMap(b => sc.map(c => f(a, b, c))))
    }

    type Stack = List[Int]
    def pop(): State[Stack, Int] = State[Stack, Int] { s =>
      s match {
        case h :: t => (h, t)
        case Nil => {
          println("cannot pop from empty list")
          ???
        }
      }
    }
    def push(i: Int): State[Stack, Unit] = State[Stack, Unit] { s =>
      ((), i :: s)
    }
    def setStack(s: Stack): State[Stack, Unit] = setState(s)

    def runStack = for {
      _ <- push(3)
      a <- pop()
      _ <- setStack(List(2))
      b <- pop()
    } yield a + b
    val result = runStack.run(List())
    println(result)

    type CandyNum = Int
    type CoinNum = Int
    sealed trait Input
    case class Coin() extends Input
    case class Turn() extends Input
    case class Machine(locked: Boolean, candies: CandyNum, coins: CoinNum)

    def transit(input: Input, machine: Machine): Machine =
      (input, machine) match {
        case (_, Machine(_, 0, _))         => machine
        case (Turn(), Machine(true, _, _)) => machine
        case (Coin(), Machine(false, _, coins)) =>
          machine.copy(locked = false, coins = coins + 1)
        case (Coin(), Machine(true, _, coins)) =>
          machine.copy(locked = false, coins = coins + 1)
        case (Turn(), Machine(false, candies, coins)) =>
          Machine(candies > 0 && coins > 0, candies - 1, coins - 1)
      }

    def sequence(
        actions: List[State[Machine, Input]]
    ): State[Machine, List[Input]] =
      actions.foldLeft(unit[Machine, List[Input]](Nil))((acc, action) =>
        for {
          act <- action
          ll <- acc
        } yield act :: ll
      )

//    def setMachine(machine: Machine) = setState(machine)

    val inputs = List(Coin(), Turn(), Coin(), Turn())
    val states = inputs.map(input =>
      State[Machine, Input](machine => (input, transit(input, machine)))
    )
    val finalMachine = sequence(states)
    println(finalMachine.run(Machine(locked = true, candies = 3, coins = 0)))
  }
  /*
  test("Monoid") {
    import scala.collection.immutable.Map
    trait Monoid[A] {
      def op(a1: A, a2: A): A
      val zero: A
    }

    def foldMap[A, B](l: List[A])(md: Monoid[B])(f: A => B): B = l match {
      case Nil    => md.zero
      case h :: t => md.op(f(h), foldMap(t)(md)(f))
    }

    def endoComposeMonoid[A] = new Monoid[A => A] {
      def op(f: A => A, g: A => A) = f compose g
      val zero = (a: A) => a
    }

    def endoAndThenMonoid[A] = new Monoid[A => A] {
      def op(f: A => A, g: A => A) = f andThen g
      val zero = (a: A) => a
    }

    def dual[A](m: Monoid[A]) = new Monoid[A] {
      def op(x: A, y: A) = m.op(y, x)
      val zero = m.zero
    }

    def mapMergeMonoid[K, V](mv: Monoid[V]): Monoid[Map[K, V]] =
      new Monoid[Map[K, V]] {
        val zero = Map()
        def op(ma: Map[K, V], mb: Map[K, V]) = {
          ma ++ mb
        }
      }

    val intAdditionMonoid = new Monoid[Int] {
      val zero = 0
      def op(a: Int, b: Int) = a + b
    }

    val nestedMapMonoid = mapMergeMonoid[String, Map[String, Int]](
      mapMergeMonoid[String, Int](intAdditionMonoid)
    )
    val m1 = Map("o1" -> Map("i1" -> 1, "i2" -> 2))
    val m2 = Map("o1" -> Map("i2" -> 3))
    val m3 = nestedMapMonoid.op(m1, m2)
    println(m3)

    def frequencyMap[A](as: IndexedSeq[A]): Map[A, Int] = {
      val mergeMapMonoid = mapMergeMonoid[A, Int](intAdditionMonoid)
      as.foldLeft(Map[A, Int]())((m, a) => mergeMapMonoid.op(m, Map(a -> 1)))
    }
    val freqMap = frequencyMap(Vector("a rose", "is a", "rose is", "a rose"))
    println(freqMap)
  }
   */
  test("Invoking head on an empty Set should produce NoSuchElementException") {
    assertThrows[NoSuchElementException] {
      Set.empty.head
    }
  }
}
