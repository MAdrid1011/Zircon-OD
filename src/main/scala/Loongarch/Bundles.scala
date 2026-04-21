import chisel3._
import chisel3.util._

/** Harvard-split, variable-width single-cycle memory bus expected by
  * `CONFIG_MEM_HARVARD=y` in LA32RSim-2026.
  *
  *   valid=1 wstrb=0     : read, rdata valid same cycle
  *   valid=1 wstrb!=0    : write, happens on the falling edge
  *   ready is always 1 on the sim side (zero-latency)
  *   size ∈ {0,1,2,3} -> 1 / 2 / 4 / 8 bytes
  *   rdata / wdata are 64-bit; wstrb is 8-bit byte enable.
  *
  * Both the instruction port (`io_imem_*`, read-only — wstrb tied to 0)
  * and the data port (`io_dmem_*`) share this bundle.
  */
class SimpleBusIO extends Bundle {
  val valid = Output(Bool())
  val ready = Input(Bool())
  val addr  = Output(UInt(32.W))
  val size  = Output(UInt(2.W))     // log2 bytes, 0..3
  val wdata = Output(UInt(64.W))
  val wstrb = Output(UInt(8.W))     // 0 = read
  val rdata = Input(UInt(64.W))
}

/** Per-lane commit port. Mirrors the Verilog signals
  * io_cmt_{i}_valid / pc / inst / rd_valid / rd / exception / exception_code.
  */
class CommitPort extends Bundle {
  val valid          = Output(Bool())
  val pc             = Output(UInt(32.W))
  val inst           = Output(UInt(32.W))
  val rd_valid       = Output(Bool())
  val rd             = Output(UInt(5.W))
  val exception      = Output(Bool())
  val exception_code = Output(UInt(6.W))
}

/** Decoded control bundle produced by `Decode`. */
class DecodeOut extends Bundle {
  val inst        = UInt(32.W)
  val pc          = UInt(32.W)
  val valid       = Bool()

  val rj          = UInt(5.W)
  val rk          = UInt(5.W)
  val rd          = UInt(5.W)
  val rj_valid    = Bool()
  val rk_valid    = Bool()
  val rd_valid    = Bool()

  val imm         = UInt(32.W)
  val alu_op      = UInt(4.W)
  val alu_rs1_sel = UInt(1.W)
  val alu_rs2_sel = UInt(2.W)
  val br_type     = UInt(4.W)
  val mem_type    = UInt(5.W)
  val fu_id       = UInt(3.W)

  val csr_op      = UInt(2.W)
  val csr_addr    = UInt(14.W)

  // Convenience predicates
  def is_alu: Bool = fu_id === Control_Signal.FU_ALU
  def is_br:  Bool = fu_id === Control_Signal.FU_BR
  def is_ls:  Bool = fu_id === Control_Signal.FU_LS
  def is_md:  Bool = fu_id === Control_Signal.FU_MD
  def is_csr: Bool = fu_id === Control_Signal.FU_CSR

  def is_load:  Bool = mem_type(3) && !mem_type(4)
  def is_store: Bool = mem_type(4)

  def is_mul:   Bool = is_md && !alu_op(2)
  def is_div:   Bool = is_md &&  alu_op(2)
}

class IF_ID_Entry extends Bundle {
  val valid = Bool()
  val pc    = UInt(32.W)
  val inst  = UInt(32.W)
}

class ID_IS_Entry extends Bundle {
  val valid = Bool()
  val ctrl  = new DecodeOut
}

class IS_EX_Entry extends Bundle {
  val valid    = Bool()
  val ctrl     = new DecodeOut
  val rj_val   = UInt(32.W)
  val rk_val   = UInt(32.W)
  val src1     = UInt(32.W)  // already resolved with rs1_sel (reg or pc)
  val src2     = UInt(32.W)  // already resolved with rs2_sel (reg/imm/4)
  val st_val   = UInt(32.W)  // store data (= rd register value)
}

class EX_MEM_Entry extends Bundle {
  val valid    = Bool()
  val pc       = UInt(32.W)
  val inst     = UInt(32.W)
  val rd       = UInt(5.W)
  val rd_valid = Bool()
  val alu_out  = UInt(32.W)
  val st_val   = UInt(32.W)
  val mem_type = UInt(5.W)
  val is_load  = Bool()
  val is_store = Bool()
  val is_mul   = Bool()
  val is_csr   = Bool()
  val link_pc  = UInt(32.W)   // pc+4 for BL / JIRL
  val use_link = Bool()       // if true, wb_data := link_pc
  // CSR fields forwarded for CSR_Mini access in MEM stage
  val csr_op   = UInt(2.W)
  val csr_addr = UInt(14.W)
  val csr_mask = UInt(32.W)   // reg(rj) for CSRXCHG
  val csr_new  = UInt(32.W)   // reg(rd) for CSRWR / CSRXCHG
}

class MEM_WB_Entry extends Bundle {
  val valid    = Bool()
  val pc       = UInt(32.W)
  val inst     = UInt(32.W)
  val rd       = UInt(5.W)
  val rd_valid = Bool()
  val wb_data  = UInt(32.W)
}

/** Lightweight bypass tag used by Hazard/Bypass. */
class BypassTag extends Bundle {
  val valid      = Bool()    // stage has a producer in this cycle
  val rd         = UInt(5.W)
  val rd_valid   = Bool()    // producer really writes rd
  val data       = UInt(32.W)
  val data_valid = Bool()    // false for a load in EX (not yet ready)
}
