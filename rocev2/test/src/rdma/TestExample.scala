package rdma

import org.scalatest.funsuite.AnyFunSuite

class SetSuite extends AnyFunSuite {
  test("An empty Set should have size 0") {
    assert(Set.empty.size == 0)

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
