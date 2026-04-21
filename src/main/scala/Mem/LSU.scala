import chisel3._
import chisel3.util._

/** Load / Store unit.  Single-cycle access against the simple bus:
  *
  *   load  -> valid=1, wstrb=0, rdata returned combinationally
  *   store -> valid=1, wstrb!=0, completes on the falling edge
  *
  * Sub-word alignment:
  *   addr[1:0] is the byte offset within a word.  stores use a shifted
  *   data pattern + byte-enable mask.  loads shift and zero/sign-extend
  *   the returned 32-bit word.
  */
class LSU extends Module {
  val io = IO(new Bundle {
    val valid    = Input(Bool())
    val mem_type = Input(UInt(5.W))
    val addr     = Input(UInt(32.W))
    val st_val   = Input(UInt(32.W))
    val dmem     = new SimpleBusIO
    val rdata    = Output(UInt(32.W))
  })

  import Control_Signal._

  val word_addr = Cat(io.addr(31, 2), 0.U(2.W))
  val offset    = io.addr(1, 0)

  val is_load  = io.valid && io.mem_type(3) && !io.mem_type(4)  // LDB/LDH/LDW/LDBU/LDHU
  val is_store = io.valid && io.mem_type(4)                      // STB/STH/STW

  // ── Store alignment ───────────────────────────────────────────────────
  val wstrb = Wire(UInt(4.W))
  wstrb := 0.U
  switch(io.mem_type) {
    is(MEM_STB) { wstrb := 1.U << offset }
    is(MEM_STH) { wstrb := "b0011".U << Cat(offset(1), 0.U(1.W)) }
    is(MEM_STW) { wstrb := "b1111".U }
  }

  val wdata_aligned = Wire(UInt(32.W))
  wdata_aligned := 0.U
  switch(io.mem_type) {
    is(MEM_STB) { wdata_aligned := io.st_val(7, 0)  << (offset << 3) }
    is(MEM_STH) { wdata_aligned := io.st_val(15, 0) << (Cat(offset(1), 0.U(1.W)) << 3) }
    is(MEM_STW) { wdata_aligned := io.st_val }
  }

  // ── Drive bus (64-bit Harvard data port, size=2 → 4 bytes) ───────────
  io.dmem.valid := is_load || is_store
  io.dmem.addr  := word_addr
  io.dmem.size  := 2.U                                   // 1<<2 = 4 bytes
  io.dmem.wdata := Cat(0.U(32.W), wdata_aligned)         // upper half unused
  io.dmem.wstrb := Mux(is_store, Cat(0.U(4.W), wstrb), 0.U(8.W))

  // ── Load alignment / extension (only lower 32 bits of rdata used) ─────
  val rdata_raw  = io.dmem.rdata(31, 0)
  val byte_lane  = (rdata_raw >> (offset << 3))(7, 0)
  val half_lane  = (rdata_raw >> (Cat(offset(1), 0.U(1.W)) << 3))(15, 0)

  val extended = Wire(UInt(32.W))
  extended := rdata_raw
  switch(io.mem_type) {
    is(MEM_LDB)  { extended := Cat(Fill(24, byte_lane(7)),  byte_lane) }
    is(MEM_LDBU) { extended := Cat(0.U(24.W), byte_lane) }
    is(MEM_LDH)  { extended := Cat(Fill(16, half_lane(15)), half_lane) }
    is(MEM_LDHU) { extended := Cat(0.U(16.W), half_lane) }
    is(MEM_LDW)  { extended := rdata_raw }
  }
  io.rdata := extended
}
