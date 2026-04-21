import chisel3._
import chisel3.util._

/** Single-cycle 32×32 multiplier.  `op` selects which half of the 64-bit
  * product to produce:
  *
  *   MD_MUL    -> low  32 bits (MUL.W)
  *   MD_MULH   -> high 32 bits of signed × signed
  *   MD_MULHU  -> high 32 bits of unsigned × unsigned
  *
  * For MUL.W the signedness of the operands doesn't matter because the
  * low 32 bits are the same.
  */
class MUL extends Module {
  val io = IO(new Bundle {
    val op  = Input(UInt(4.W))
    val a   = Input(UInt(32.W))
    val b   = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  import Control_Signal._

  val a_s = io.a.asSInt
  val b_s = io.b.asSInt

  val prod_ss = (a_s * b_s).asUInt          // 64-bit signed×signed
  val prod_uu = (io.a * io.b)               // 64-bit unsigned×unsigned
  val prod_lo = prod_uu(31, 0)

  val out = Wire(UInt(32.W))
  out := 0.U
  switch(io.op) {
    is(MD_MUL)   { out := prod_lo }
    is(MD_MULH)  { out := prod_ss(63, 32) }
    is(MD_MULHU) { out := prod_uu(63, 32) }
  }
  io.out := out
}
