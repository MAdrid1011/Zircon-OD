import chisel3._
import chisel3.util._

object CPU_Config {
  val RESET_VEC = 0x1c000000L.U(32.W)
}

/** Control-signal encoding, kept compatible with Zircon.
  * Only a minimal subset is used by Zircon-OD.
  */
object Control_Signal {
  val Y = true.B
  val N = false.B

  // ── alu_op ────────────────────────────────────────────────────────────────
  // Logical / arithmetic (4-bit). Values chosen to share the decode table
  // with the (unused here) MD encodings.
  val ALU_ADD   = 0.U(4.W)
  val ALU_SUB   = 1.U(4.W)
  val ALU_SLT   = 2.U(4.W)
  val ALU_SLTU  = 3.U(4.W)
  val ALU_NOR   = 4.U(4.W)
  val ALU_AND   = 5.U(4.W)
  val ALU_OR    = 6.U(4.W)
  val ALU_XOR   = 7.U(4.W)
  val ALU_SLL   = 8.U(4.W)
  val ALU_SRL   = 9.U(4.W)
  val ALU_SRA   = 10.U(4.W)
  val ALU_NOP   = 15.U(4.W) // unused / passthrough

  // md_op (re-uses the alu_op field when fu_id == MD)
  val MD_MUL   = 0.U(4.W)
  val MD_MULH  = 1.U(4.W)
  val MD_MULHU = 2.U(4.W)
  val MD_DIV   = 4.U(4.W)
  val MD_MOD   = 5.U(4.W)
  val MD_DIVU  = 6.U(4.W)
  val MD_MODU  = 7.U(4.W)

  // ── alu_rs1_sel ──────────────────────────────────────────────────────────
  val RS1_REG = 0.U(1.W)
  val RS1_PC  = 1.U(1.W)

  // ── alu_rs2_sel ──────────────────────────────────────────────────────────
  val RS2_REG  = 0.U(2.W)
  val RS2_IMM  = 1.U(2.W)
  val RS2_FOUR = 2.U(2.W)

  // ── br_type ──────────────────────────────────────────────────────────────
  val NO_BR   = 0.U(4.W)
  val BR_JIRL = 3.U(4.W)
  val BR_B    = 4.U(4.W)
  val BR_BL   = 5.U(4.W)
  val BR_BEQ  = 6.U(4.W)
  val BR_BNE  = 7.U(4.W)
  val BR_BLT  = 8.U(4.W)
  val BR_BGE  = 9.U(4.W)
  val BR_BLTU = 10.U(4.W)
  val BR_BGEU = 11.U(4.W)

  // ── mem_type ─────────────────────────────────────────────────────────────
  val NO_MEM   = 0.U(5.W)
  val MEM_LDB  = 8.U(5.W)
  val MEM_LDH  = 9.U(5.W)
  val MEM_LDW  = 10.U(5.W)
  val MEM_STB  = 16.U(5.W)
  val MEM_STH  = 17.U(5.W)
  val MEM_STW  = 18.U(5.W)
  val MEM_LDBU = 12.U(5.W)
  val MEM_LDHU = 13.U(5.W)

  // ── fu_id ────────────────────────────────────────────────────────────────
  val FU_ALU = 0.U(3.W)
  val FU_BR  = 1.U(3.W)
  val FU_LS  = 2.U(3.W)
  val FU_MD  = 3.U(3.W)
  val FU_CSR = 4.U(3.W)
  val FU_NOP = 7.U(3.W)

  // ── csr_op ───────────────────────────────────────────────────────────────
  val CSR_NONE = 0.U(2.W)
  val CSR_RD   = 1.U(2.W)
  val CSR_WR   = 2.U(2.W)
  val CSR_XCHG = 3.U(2.W)

  // ── rk_sel / rd_sel ──────────────────────────────────────────────────────
  val RK_RD = 0.U(1.W) // rk field = inst[4:0]   (for store / branch compare)
  val RK_RK = 1.U(1.W) // rk field = inst[14:10] (for reg-reg arith)

  val RDSEL_RD = 0.U(2.W) // rd comes from inst[4:0]
  val RDSEL_R1 = 1.U(2.W) // rd = $r1 (BL)
  val RDSEL_RJ = 2.U(2.W) // rd = inst[9:5] (rdcntid)

  // ── imm_type ─────────────────────────────────────────────────────────────
  val IMM_00U = 0.U(4.W)
  val IMM_05U = 1.U(4.W)
  val IMM_12U = 2.U(4.W)
  val IMM_12S = 3.U(4.W)
  val IMM_16S = 4.U(4.W)
  val IMM_20S = 5.U(4.W)
  val IMM_26S = 6.U(4.W)
  val IMM_CSR = 7.U(4.W)
  val IMM_14S = 11.U(4.W)
}
