import chisel3._
import chisel3.util._

/** PC register. Naturally advances in 8-byte strides (rounded down to the
  * 8-byte boundary). On branch misprediction in EX, jump to `redirect_pc`.
  *
  *   reset              -> pc := RESET_VEC
  *   redirect_valid      -> pc := redirect_pc   (wins over stall)
  *   stall               -> pc keeps
  *   otherwise           -> pc := (pc & ~7) + 8
  */
class PC extends Module {
  val io = IO(new Bundle {
    val stall          = Input(Bool())
    val redirect_valid = Input(Bool())
    val redirect_pc    = Input(UInt(32.W))
    val pc             = Output(UInt(32.W))
  })

  val pc = RegInit(CPU_Config.RESET_VEC)

  when(io.redirect_valid) {
    pc := io.redirect_pc
  }.elsewhen(!io.stall) {
    pc := Cat(pc(31, 3), 0.U(3.W)) + 8.U
  }

  io.pc := pc
}
