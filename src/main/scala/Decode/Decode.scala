import chisel3._
import chisel3.util._

/** Single-instruction decoder producing a DecodeOut bundle.
  * Compatible control encoding with Zircon's Decode, restricted to the
  * non-privileged LA32R subset Zircon-OD actually implements.
  */
object Decode_Map {
  import Instructions._
  import Control_Signal._

  // Fields:   0 rj_valid | 1 rk_valid | 2 rd_valid | 3 alu_op
  //           4 rs1_sel  | 5 rs2_sel  | 6 br_type  | 7 mem_type
  //           8 fu_id    | 9 rk_sel   | 10 rd_sel  | 11 imm_type
  //           12 csr_op
  val default: List[UInt] =
    List(N, N, N, ALU_NOP, RS1_REG, RS2_IMM, NO_BR, NO_MEM,
         FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE).map(x => x.asUInt)

  val table: Array[(BitPat, List[UInt])] = Array(
    // reg-reg arith
    ADDW   -> List(Y, Y, Y, ALU_ADD,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    SUBW   -> List(Y, Y, Y, ALU_SUB,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    SLT    -> List(Y, Y, Y, ALU_SLT,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    SLTU   -> List(Y, Y, Y, ALU_SLTU,  RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    NOR    -> List(Y, Y, Y, ALU_NOR,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    AND    -> List(Y, Y, Y, ALU_AND,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    OR     -> List(Y, Y, Y, ALU_OR,    RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    XOR    -> List(Y, Y, Y, ALU_XOR,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    SLLW   -> List(Y, Y, Y, ALU_SLL,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    SRLW   -> List(Y, Y, Y, ALU_SRL,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    SRAW   -> List(Y, Y, Y, ALU_SRA,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    // mul / div
    MULW   -> List(Y, Y, Y, MD_MUL,    RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_MD,  RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    MULHW  -> List(Y, Y, Y, MD_MULH,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_MD,  RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    MULHWU -> List(Y, Y, Y, MD_MULHU,  RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_MD,  RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    DIVW   -> List(Y, Y, Y, MD_DIV,    RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_MD,  RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    MODW   -> List(Y, Y, Y, MD_MOD,    RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_MD,  RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    DIVWU  -> List(Y, Y, Y, MD_DIVU,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_MD,  RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    MODWU  -> List(Y, Y, Y, MD_MODU,   RS1_REG, RS2_REG, NO_BR, NO_MEM, FU_MD,  RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
    // reg-imm
    SLLIW  -> List(Y, N, Y, ALU_SLL,   RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_05U, CSR_NONE),
    SRLIW  -> List(Y, N, Y, ALU_SRL,   RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_05U, CSR_NONE),
    SRAIW  -> List(Y, N, Y, ALU_SRA,   RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_05U, CSR_NONE),
    SLTI   -> List(Y, N, Y, ALU_SLT,   RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_12S, CSR_NONE),
    SLTUI  -> List(Y, N, Y, ALU_SLTU,  RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_12S, CSR_NONE),
    ADDIW  -> List(Y, N, Y, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_12S, CSR_NONE),
    ANDI   -> List(Y, N, Y, ALU_AND,   RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_12U, CSR_NONE),
    ORI    -> List(Y, N, Y, ALU_OR,    RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_12U, CSR_NONE),
    XORI   -> List(Y, N, Y, ALU_XOR,   RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_12U, CSR_NONE),
    // upper-imm
    LU12IW    -> List(N, N, Y, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_20S, CSR_NONE),
    PCADDU12I -> List(N, N, Y, ALU_ADD,   RS1_PC,  RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_20S, CSR_NONE),
    // load
    LDB  -> List(Y, N, Y, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, MEM_LDB,  FU_LS, RK_RK, RDSEL_RD, IMM_12S, CSR_NONE),
    LDH  -> List(Y, N, Y, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, MEM_LDH,  FU_LS, RK_RK, RDSEL_RD, IMM_12S, CSR_NONE),
    LDW  -> List(Y, N, Y, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, MEM_LDW,  FU_LS, RK_RK, RDSEL_RD, IMM_12S, CSR_NONE),
    LDBU -> List(Y, N, Y, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, MEM_LDBU, FU_LS, RK_RK, RDSEL_RD, IMM_12S, CSR_NONE),
    LDHU -> List(Y, N, Y, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, MEM_LDHU, FU_LS, RK_RK, RDSEL_RD, IMM_12S, CSR_NONE),
    // store: rk field re-used as the store-data source register => RK_RD
    STB  -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, MEM_STB,  FU_LS, RK_RD, RDSEL_RD, IMM_12S, CSR_NONE),
    STH  -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, MEM_STH,  FU_LS, RK_RD, RDSEL_RD, IMM_12S, CSR_NONE),
    STW  -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM, NO_BR, MEM_STW,  FU_LS, RK_RD, RDSEL_RD, IMM_12S, CSR_NONE),
    // branches
    JIRL -> List(Y, N, Y, ALU_ADD,   RS1_PC,  RS2_FOUR, BR_JIRL, NO_MEM, FU_BR, RK_RK, RDSEL_RD, IMM_16S, CSR_NONE),
    B    -> List(N, N, N, ALU_ADD,   RS1_REG, RS2_IMM,  BR_B,    NO_MEM, FU_BR, RK_RK, RDSEL_RD, IMM_26S, CSR_NONE),
    BL   -> List(N, N, Y, ALU_ADD,   RS1_PC,  RS2_FOUR, BR_BL,   NO_MEM, FU_BR, RK_RK, RDSEL_R1, IMM_26S, CSR_NONE),
    BEQ  -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM,  BR_BEQ,  NO_MEM, FU_BR, RK_RD, RDSEL_RD, IMM_16S, CSR_NONE),
    BNE  -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM,  BR_BNE,  NO_MEM, FU_BR, RK_RD, RDSEL_RD, IMM_16S, CSR_NONE),
    BLT  -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM,  BR_BLT,  NO_MEM, FU_BR, RK_RD, RDSEL_RD, IMM_16S, CSR_NONE),
    BGE  -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM,  BR_BGE,  NO_MEM, FU_BR, RK_RD, RDSEL_RD, IMM_16S, CSR_NONE),
    BLTU -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM,  BR_BLTU, NO_MEM, FU_BR, RK_RD, RDSEL_RD, IMM_16S, CSR_NONE),
    BGEU -> List(Y, Y, N, ALU_ADD,   RS1_REG, RS2_IMM,  BR_BGEU, NO_MEM, FU_BR, RK_RD, RDSEL_RD, IMM_16S, CSR_NONE),
    // CSR (minimal). Decoded as CSRXCHG generically; rj==0/1 is reclassified
    // as CSRRD / CSRWR below (the BitPat patterns overlap so we can't rely
    // on them alone).  rk <- inst[4:0] (via RK_RD) routes reg(rd) into rk_val
    // as the "new value" source.  For XCHG we also read reg(rj) as the mask.
    CSRXCHG -> List(Y, Y, Y, ALU_NOP, RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_CSR, RK_RD, RDSEL_RD, IMM_CSR, CSR_XCHG),
    // BREAK kept as literal NOP (magic-word handled in sim)
    BREAK -> List(N, N, N, ALU_NOP,  RS1_REG, RS2_IMM, NO_BR, NO_MEM, FU_ALU, RK_RK, RDSEL_RD, IMM_00U, CSR_NONE),
  )

  def lookup(inst: UInt): List[UInt] =
    ListLookup(inst, default, table)
}

class Decode extends Module {
  val io = IO(new Bundle {
    val inst  = Input(UInt(32.W))
    val pc    = Input(UInt(32.W))
    val valid = Input(Bool())
    val out   = Output(new DecodeOut)
  })

  val ctrl = Decode_Map.lookup(io.inst)

  val rj_valid    = ctrl(0).asBool
  val rk_valid    = ctrl(1).asBool
  val rd_valid_in = ctrl(2).asBool
  val alu_op      = ctrl(3)(3, 0)
  val rs1_sel     = ctrl(4)(0)
  val rs2_sel     = ctrl(5)(1, 0)
  val br_type     = ctrl(6)(3, 0)
  val mem_type    = ctrl(7)(4, 0)
  val fu_id       = ctrl(8)(2, 0)
  val rk_sel      = ctrl(9)(0)
  val rd_sel      = ctrl(10)(1, 0)
  val imm_type    = ctrl(11)(3, 0)
  val csr_op      = ctrl(12)(1, 0)

  import Control_Signal._

  // Register extraction ------------------------------------------------------
  val rj_idx = io.inst(9, 5)
  val rk_raw = Mux(rk_sel === RK_RK, io.inst(14, 10), io.inst(4, 0))
  val rd_raw = MuxLookup(rd_sel, io.inst(4, 0))(Seq(
    RDSEL_RD -> io.inst(4, 0),
    RDSEL_R1 -> 1.U(5.W),
    RDSEL_RJ -> io.inst(9, 5),
  ))

  io.out.inst     := io.inst
  io.out.pc       := io.pc
  io.out.valid    := io.valid

  io.out.rj       := Mux(rj_valid, rj_idx, 0.U)
  io.out.rk       := Mux(rk_valid, rk_raw, 0.U)
  io.out.rd       := rd_raw

  io.out.rj_valid := rj_valid
  io.out.rk_valid := rk_valid
  io.out.rd_valid := rd_valid_in && (rd_raw =/= 0.U)

  io.out.alu_op      := alu_op
  io.out.alu_rs1_sel := rs1_sel
  io.out.alu_rs2_sel := rs2_sel
  io.out.br_type     := br_type
  io.out.mem_type    := mem_type
  io.out.fu_id       := fu_id
  // Reclassify CSRXCHG as CSRRD (rj==0) or CSRWR (rj==1) based on inst[9:5].
  val csr_rj = io.inst(9, 5)
  val csr_op_fixed = Mux(csr_op === CSR_XCHG,
    MuxLookup(csr_rj, CSR_XCHG)(Seq(
      0.U(5.W) -> CSR_RD,
      1.U(5.W) -> CSR_WR,
    )),
    csr_op)
  io.out.csr_op   := csr_op_fixed
  io.out.csr_addr := io.inst(23, 10)

  // For the pseudo CSRRD case we don't actually need reg(rj) as mask, and
  // for CSRWR we don't need reg(rj) either.  Override the register-valid
  // flags accordingly so the hazard logic doesn't observe a fake producer.
  when(csr_op === CSR_XCHG && (csr_rj === 0.U || csr_rj === 1.U)) {
    io.out.rj_valid := false.B
    io.out.rj       := 0.U
  }
  when(csr_op === CSR_XCHG && csr_rj === 0.U) {
    // CSRRD: no "new value" source either
    io.out.rk_valid := false.B
    io.out.rk       := 0.U
  }

  // Immediate generation -----------------------------------------------------
  val imm = Wire(UInt(32.W))
  imm := 0.U
  switch(imm_type) {
    is(IMM_00U) { imm := 0.U }
    is(IMM_05U) { imm := Cat(0.U(27.W), io.inst(14, 10)) }
    is(IMM_12U) { imm := Cat(0.U(20.W), io.inst(21, 10)) }
    is(IMM_12S) { imm := Cat(Fill(20, io.inst(21)), io.inst(21, 10)) }
    is(IMM_14S) { imm := Cat(Fill(16, io.inst(23)), io.inst(23, 10), 0.U(2.W)) }
    is(IMM_16S) { imm := Cat(Fill(14, io.inst(25)), io.inst(25, 10), 0.U(2.W)) }
    is(IMM_20S) { imm := Cat(io.inst(24, 5), 0.U(12.W)) }
    is(IMM_26S) { imm := Cat(Fill(4,  io.inst(9)), io.inst(9, 0), io.inst(25, 10), 0.U(2.W)) }
    is(IMM_CSR) { imm := Cat(0.U(18.W), io.inst(23, 10)) }
  }
  io.out.imm := imm
}
