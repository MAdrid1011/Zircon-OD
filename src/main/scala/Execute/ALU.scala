import chisel3._
import chisel3.util._

class ALU extends Module {
  val io = IO(new Bundle {
    val op  = Input(UInt(4.W))
    val a   = Input(UInt(32.W))
    val b   = Input(UInt(32.W))
    val out = Output(UInt(32.W))
  })

  import Control_Signal._

  val shamt = io.b(4, 0)
  val a_sx  = io.a.asSInt
  val b_sx  = io.b.asSInt

  val out = Wire(UInt(32.W))
  out := 0.U
  switch(io.op) {
    is(ALU_ADD)  { out := io.a + io.b }
    is(ALU_SUB)  { out := io.a - io.b }
    is(ALU_SLT)  { out := Mux(a_sx < b_sx, 1.U, 0.U) }
    is(ALU_SLTU) { out := Mux(io.a < io.b, 1.U, 0.U) }
    is(ALU_NOR)  { out := ~(io.a | io.b) }
    is(ALU_AND)  { out := io.a & io.b }
    is(ALU_OR)   { out := io.a | io.b }
    is(ALU_XOR)  { out := io.a ^ io.b }
    is(ALU_SLL)  { out := io.a << shamt }
    is(ALU_SRL)  { out := io.a >> shamt }
    is(ALU_SRA)  { out := (a_sx >> shamt).asUInt }
    is(ALU_NOP)  { out := 0.U }
  }
  io.out := out
}
