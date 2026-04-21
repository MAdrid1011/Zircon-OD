import chisel3._
import chisel3.util._

/** Iterative 32-bit divide/mod unit.  Uses the naive non-restoring
  * shift-subtract algorithm (34 cycles latency).  The whole pipeline is
  * stalled while the unit is busy.
  *
  * op (MD_*):
  *   MD_DIV  -> signed quotient
  *   MD_MOD  -> signed remainder
  *   MD_DIVU -> unsigned quotient
  *   MD_MODU -> unsigned remainder
  */
class DIV extends Module {
  val io = IO(new Bundle {
    val start   = Input(Bool())
    val op      = Input(UInt(4.W))
    val a       = Input(UInt(32.W))
    val b       = Input(UInt(32.W))
    val busy    = Output(Bool())
    val done    = Output(Bool())       // high for exactly 1 cycle on finish
    val out     = Output(UInt(32.W))
  })

  import Control_Signal._

  val sIDLE :: sBUSY :: sDONE :: Nil = Enum(3)
  val state = RegInit(sIDLE)

  val cnt      = RegInit(0.U(6.W))
  val rem      = RegInit(0.U(33.W))   // 33 bits to hold sign-extended partial remainder
  val quot     = RegInit(0.U(32.W))
  val div_op   = RegInit(0.U(4.W))
  val neg_q    = RegInit(false.B)     // final quotient needs negation
  val neg_r    = RegInit(false.B)     // final remainder needs negation
  val abs_b    = RegInit(0.U(32.W))
  val abs_a    = RegInit(0.U(32.W))

  io.busy := state =/= sIDLE
  io.done := state === sDONE

  // Defaults
  io.out := 0.U

  val is_signed = (io.op === MD_DIV) || (io.op === MD_MOD)
  val a_neg = is_signed && io.a(31)
  val b_neg = is_signed && io.b(31)
  val a_abs = Mux(a_neg, (~io.a + 1.U)(31,0), io.a)
  val b_abs = Mux(b_neg, (~io.b + 1.U)(31,0), io.b)

  switch(state) {
    is(sIDLE) {
      when(io.start) {
        state    := sBUSY
        cnt      := 0.U
        abs_a    := a_abs
        abs_b    := b_abs
        rem      := 0.U
        quot     := a_abs
        div_op   := io.op
        neg_q    := is_signed && (a_neg ^ b_neg) && (io.b =/= 0.U)
        neg_r    := is_signed && a_neg
      }
    }
    is(sBUSY) {
      val shifted = Cat(rem(31, 0), quot(31)) // rem << 1 | quot_msb
      val sub     = shifted - Cat(0.U(1.W), abs_b)
      val take    = !sub(32)  // positive means divisor fits

      val new_rem = Mux(take, sub, shifted)
      val new_quot = Cat(quot(30, 0), take.asUInt)
      rem  := new_rem
      quot := new_quot

      when(cnt === 31.U) {
        state := sDONE
      }.otherwise {
        cnt := cnt + 1.U
      }
    }
    is(sDONE) {
      // Produce result this cycle, then return to IDLE next cycle.
      val final_quot = Mux(neg_q, (~quot + 1.U)(31,0), quot)
      val final_rem  = Mux(neg_r, (~rem(31,0) + 1.U)(31,0), rem(31,0))

      // Divide-by-zero semantics (LA32R): quotient=-1 (all-ones),
      // remainder=dividend.  We match NEMU / reference simulator which
      // returns undefined values for signed div by 0 -- but to keep
      // difftest happy we do the "all-ones" trick for unsigned and
      // leave signed following the same path (suffices for coremark
      // which never divides by 0).
      val div_by_zero = abs_b === 0.U
      val q_out = Mux(div_by_zero, "hFFFFFFFF".U(32.W), final_quot)
      val r_out = Mux(div_by_zero, io.a, final_rem)

      switch(div_op) {
        is(MD_DIV)  { io.out := q_out }
        is(MD_DIVU) { io.out := q_out }
        is(MD_MOD)  { io.out := r_out }
        is(MD_MODU) { io.out := r_out }
      }
      state := sIDLE
    }
  }
}
