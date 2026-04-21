import chisel3._
import chisel3.util._

/** Instruction Fetch for a dual-issue pair on the Harvard 64-bit bus.
  *
  * One bus read returns 8 bytes combinationally (`size = 3` → 8 B).
  *
  *   pair pc_base = pc_in & ~7
  *   imem.addr    = pc_base
  *   rdata[31: 0] = inst at pc_base + 0
  *   rdata[63:32] = inst at pc_base + 4
  *
  * If the redirected PC lands on the high word (pc[2] == 1), only
  * `valid_1` is asserted and `valid_0 = 0` (we skip the low slot).
  *
  * Throughput: one pair / cycle → up to 2 IPC in steady state.
  * No IF/LSU arbitration is needed (Harvard).
  */
class IFStage extends Module {
  val io = IO(new Bundle {
    val pc_in            = Input(UInt(32.W))
    val imem             = new SimpleBusIO

    val flush            = Input(Bool())       // branch misprediction flush
    val downstream_stall = Input(Bool())       // ID/IS cannot accept

    val out_valid_0   = Output(Bool())
    val out_valid_1   = Output(Bool())
    val out_pc_0      = Output(UInt(32.W))
    val out_pc_1      = Output(UInt(32.W))
    val out_inst_0    = Output(UInt(32.W))
    val out_inst_1    = Output(UInt(32.W))
    val out_has_pair  = Output(Bool())
    val pc_hold       = Output(Bool())
  })

  val pc_base       = Cat(io.pc_in(31, 3), 0.U(3.W))
  val pc_misaligned = io.pc_in(2)

  val want_bus = !io.flush
  io.imem.valid := want_bus
  io.imem.addr  := pc_base
  io.imem.size  := 3.U           // 8 bytes
  io.imem.wdata := 0.U
  io.imem.wstrb := 0.U

  val bus_granted = want_bus && io.imem.ready

  val inst_lo = io.imem.rdata(31,  0)
  val inst_hi = io.imem.rdata(63, 32)

  io.out_inst_0   := inst_lo
  io.out_inst_1   := inst_hi
  io.out_pc_0     := pc_base
  io.out_pc_1     := pc_base + 4.U
  io.out_valid_0  := bus_granted && !io.flush && !pc_misaligned
  io.out_valid_1  := bus_granted && !io.flush
  io.out_has_pair := bus_granted && !io.flush

  // PC advances whenever downstream accepts the pair we produce this cycle.
  val pc_can_advance = bus_granted && !io.downstream_stall && !io.flush
  io.pc_hold := !pc_can_advance
}
