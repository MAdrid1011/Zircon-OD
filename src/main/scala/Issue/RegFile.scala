import chisel3._
import chisel3.util._

/** Architectural register file.  4 read ports, 2 write ports.
  *
  *   r0 is hard-wired to zero.
  *   Write-port 1 wins over write-port 0 when they target the same reg
  *   (write-port 1 carries the program-order-later lane, i.e. P1).
  *
  *   `snap` exposes all 32 registers combinationally with write-forwarding
  *   applied, i.e. it reflects the state that *would* be seen right after
  *   this cycle's writes.  This is exactly what the LA32RSim-2026
  *   `io_cmt_rf_*` interface requires:  the snapshot in the current cycle
  *   must match the reference model after it steps by the number of valid
  *   commits this cycle.
  */
class RegFile extends Module {
  val io = IO(new Bundle {
    val raddr = Input(Vec(4, UInt(5.W)))
    val rdata = Output(Vec(4, UInt(32.W)))
    val waddr = Input(Vec(2, UInt(5.W)))
    val wdata = Input(Vec(2, UInt(32.W)))
    val wen   = Input(Vec(2, Bool()))
    val snap  = Output(Vec(32, UInt(32.W)))
  })

  val regs = RegInit(VecInit(Seq.fill(32)(0.U(32.W))))

  // Write: later-lane wins, r0 ignored
  for (i <- 0 until 2) {
    when(io.wen(i) && io.waddr(i) =/= 0.U) {
      regs(io.waddr(i)) := io.wdata(i)
    }
  }
  // If both ports write to the same non-zero reg, port 1 overrides port 0
  when(io.wen(0) && io.wen(1) && io.waddr(0) === io.waddr(1) && io.waddr(0) =/= 0.U) {
    regs(io.waddr(1)) := io.wdata(1)
  }

  // Read with write-forwarding (so reads in the same cycle as the writes
  // already see the new value -- needed for the RF snapshot).
  def readFwd(idx: UInt): UInt = {
    val base = Mux(idx === 0.U, 0.U(32.W), regs(idx))
    val w1_hit = io.wen(1) && io.waddr(1) === idx && idx =/= 0.U
    val w0_hit = io.wen(0) && io.waddr(0) === idx && idx =/= 0.U
    Mux(w1_hit, io.wdata(1),
      Mux(w0_hit, io.wdata(0), base))
  }

  for (i <- 0 until 4) {
    io.rdata(i) := readFwd(io.raddr(i))
  }

  for (i <- 0 until 32) {
    io.snap(i) := readFwd(i.U(5.W))
  }
}
