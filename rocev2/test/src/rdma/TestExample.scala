package rdma

import org.scalatest.funsuite.AnyFunSuite

class SetSuite extends AnyFunSuite {
  test("An empty Set should have size 0") {
    assert(Set.empty.size == 0)
    /*
    def scanRight[A](l: List[A], z: A)(op: (A, A) => A): List[A] = {
      def srh[A](l: List[A], z: A)(op: (A, A) => A): (List[A], A) = l match {
        case Nil => (Nil, z)
        case h :: t => {
          val (ll, zz) = srh(t, z)(op)
          (op(h, zz) :: ll, zz)
        }
      }
      val (ll, _) = srh(l, z)(op)
      ll
    }
     */
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

  test("Invoking head on an empty Set should produce NoSuchElementException") {
    assertThrows[NoSuchElementException] {
      Set.empty.head
    }
  }
}
