package rdma

import spinal.core._

object RecvQTest {
  def main(argc: Array[String]): Unit ={
    SpinalVerilog(new SeqOut(BusWidth.W512, 64))
  }
}
