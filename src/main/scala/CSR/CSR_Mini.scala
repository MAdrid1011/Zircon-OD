import chisel3._
import chisel3.util._

/** Minimal CSR file to keep `_start` difftest-clean.  Only three CSRs are
  * actually stored (CRMD, DMW0, DMW1); every other CSR reads as zero and
  * silently discards writes.
  *
  *   op == CSR_RD   : rdata = csr(addr)
  *   op == CSR_WR   : rdata = csr(addr); csr(addr) = wdata
  *   op == CSR_XCHG : old = csr(addr); rdata = old;
  *                    csr(addr) = (old & ~mask) | (new & mask)
  *                    where mask = rj_val (register from inst[9:5])
  *                          new  = wdata  (register from inst[4:0])
  */
class CSR_Mini extends Module {
  val io = IO(new Bundle {
    val valid    = Input(Bool())
    val op       = Input(UInt(2.W))
    val csr_addr = Input(UInt(14.W))
    val rj_val   = Input(UInt(32.W))   // XCHG mask
    val wdata    = Input(UInt(32.W))   // WR / XCHG new
    val rdata    = Output(UInt(32.W))
  })

  import Control_Signal._

  // Reference-model initial values (see ref/include/CSR.h):
  //   CRMD = 0x8  (DA=1, PG=0, PLV=0)  -- direct-address mode
  //   DMW0 = 0
  //   DMW1 = 0
  val crmd = RegInit(0x8.U(32.W))
  val dmw0 = RegInit(0.U(32.W))
  val dmw1 = RegInit(0.U(32.W))

  val is_crmd = io.csr_addr === CSR.CSR_CRMD
  val is_dmw0 = io.csr_addr === CSR.CSR_DMW0
  val is_dmw1 = io.csr_addr === CSR.CSR_DMW1

  val old = Wire(UInt(32.W))
  old := 0.U
  when(is_crmd) { old := crmd }
  when(is_dmw0) { old := dmw0 }
  when(is_dmw1) { old := dmw1 }
  io.rdata := old

  // CRMD only keeps the low 9 bits in the reference model
  val crmd_mask = "h1FF".U(32.W)

  when(io.valid) {
    switch(io.op) {
      is(CSR_WR) {
        when(is_crmd) { crmd := io.wdata & crmd_mask }
        when(is_dmw0) { dmw0 := io.wdata }
        when(is_dmw1) { dmw1 := io.wdata }
      }
      is(CSR_XCHG) {
        val mask = io.rj_val
        val nxt  = (old & ~mask) | (io.wdata & mask)
        when(is_crmd) { crmd := nxt & crmd_mask }
        when(is_dmw0) { dmw0 := nxt }
        when(is_dmw1) { dmw1 := nxt }
      }
    }
  }
}
