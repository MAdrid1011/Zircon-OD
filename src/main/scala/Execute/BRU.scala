import chisel3._
import chisel3.util._

/** Branch resolution unit.  Always assumes "not taken" upstream (no
  * predictor): so `taken == mispred`.  The target is computed from either
  * (pc + imm) or (rj + imm) depending on the branch type.
  */
class BRU extends Module {
  val io = IO(new Bundle {
    val valid   = Input(Bool())
    val br_type = Input(UInt(4.W))
    val rj_val  = Input(UInt(32.W))
    val rk_val  = Input(UInt(32.W))
    val pc      = Input(UInt(32.W))
    val imm     = Input(UInt(32.W))

    val taken   = Output(Bool())
    val target  = Output(UInt(32.W))
    val mispred = Output(Bool())
  })

  import Control_Signal._

  val eq  = io.rj_val === io.rk_val
  val ne  = !eq
  val lt  = io.rj_val.asSInt < io.rk_val.asSInt
  val ge  = !lt
  val ltu = io.rj_val < io.rk_val
  val geu = !ltu

  val pc_target  = io.pc + io.imm
  val reg_target = io.rj_val + io.imm

  val taken = Wire(Bool())
  taken := false.B
  val target = Wire(UInt(32.W))
  target := pc_target

  switch(io.br_type) {
    is(BR_B)    { taken := true.B; target := pc_target }
    is(BR_BL)   { taken := true.B; target := pc_target }
    is(BR_JIRL) { taken := true.B; target := reg_target }
    is(BR_BEQ)  { taken := eq;     target := pc_target }
    is(BR_BNE)  { taken := ne;     target := pc_target }
    is(BR_BLT)  { taken := lt;     target := pc_target }
    is(BR_BGE)  { taken := ge;     target := pc_target }
    is(BR_BLTU) { taken := ltu;    target := pc_target }
    is(BR_BGEU) { taken := geu;    target := pc_target }
  }

  io.taken   := io.valid && taken
  io.target  := target
  io.mispred := io.valid && taken   // static not-taken predictor
}
