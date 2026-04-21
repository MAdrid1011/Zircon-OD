import chisel3._
import chisel3.util._

/** Top-level LA32R in-order dual-issue processor.
  *
  * Pipeline (register boundaries marked with *):
  *
  *   PF   PC register
  *   IF   simple-bus fetch (2-cycle burst per pair)
  *   *    IF_ID_Reg (2 lanes)
  *   ID   DualDecode
  *   *    Issue buffer (2-slot compacting queue)
  *   IS   RegRead + Bypass + Hazard + Dual-issue arbitration
  *   *    IS_EX_Reg (2 lanes)
  *   EX   2× ALU, BRU, MUL, AGU, DIV launch
  *   *    EX_MEM_Reg (2 lanes)
  *   MEM  LSU access, CSR access
  *   *    MEM_WB_Reg (2 lanes)
  *   WB   register writeback + commit ports
  *
  * Only P0 can execute BR / LS / MUL / DIV / CSR.  P1 is always ALU.
  */
class CPU extends Module {
  val io = IO(new Bundle {
    // Harvard split bus (zero-latency, 64-bit, variable size)
    val imem = new SimpleBusIO   // instruction port (read-only)
    val dmem = new SimpleBusIO   // data port        (read-write)

    // Commit ports (flattened to io_cmt_0_*, io_cmt_1_* in Verilog)
    val cmt_0 = new CommitPort
    val cmt_1 = new CommitPort

    // All 32 architectural registers (flattened to io_cmt_rf_0..31)
    val cmt_rf_0  = Output(UInt(32.W))
    val cmt_rf_1  = Output(UInt(32.W))
    val cmt_rf_2  = Output(UInt(32.W))
    val cmt_rf_3  = Output(UInt(32.W))
    val cmt_rf_4  = Output(UInt(32.W))
    val cmt_rf_5  = Output(UInt(32.W))
    val cmt_rf_6  = Output(UInt(32.W))
    val cmt_rf_7  = Output(UInt(32.W))
    val cmt_rf_8  = Output(UInt(32.W))
    val cmt_rf_9  = Output(UInt(32.W))
    val cmt_rf_10 = Output(UInt(32.W))
    val cmt_rf_11 = Output(UInt(32.W))
    val cmt_rf_12 = Output(UInt(32.W))
    val cmt_rf_13 = Output(UInt(32.W))
    val cmt_rf_14 = Output(UInt(32.W))
    val cmt_rf_15 = Output(UInt(32.W))
    val cmt_rf_16 = Output(UInt(32.W))
    val cmt_rf_17 = Output(UInt(32.W))
    val cmt_rf_18 = Output(UInt(32.W))
    val cmt_rf_19 = Output(UInt(32.W))
    val cmt_rf_20 = Output(UInt(32.W))
    val cmt_rf_21 = Output(UInt(32.W))
    val cmt_rf_22 = Output(UInt(32.W))
    val cmt_rf_23 = Output(UInt(32.W))
    val cmt_rf_24 = Output(UInt(32.W))
    val cmt_rf_25 = Output(UInt(32.W))
    val cmt_rf_26 = Output(UInt(32.W))
    val cmt_rf_27 = Output(UInt(32.W))
    val cmt_rf_28 = Output(UInt(32.W))
    val cmt_rf_29 = Output(UInt(32.W))
    val cmt_rf_30 = Output(UInt(32.W))
    val cmt_rf_31 = Output(UInt(32.W))
  })

  import Control_Signal._

  // ═════════════════════════════════════════════════════════════════════
  //                           MODULE INSTANCES
  // ═════════════════════════════════════════════════════════════════════
  val pc_mod   = Module(new PC)
  val if_mod   = Module(new IFStage)
  val dec0     = Module(new Decode)
  val dec1     = Module(new Decode)
  val alu0     = Module(new ALU)
  val alu1     = Module(new ALU)
  val bru      = Module(new BRU)
  val mul      = Module(new MUL)
  val div      = Module(new DIV)
  val lsu      = Module(new LSU)
  val csr_mini = Module(new CSR_Mini)
  val regfile  = Module(new RegFile)

  // ═════════════════════════════════════════════════════════════════════
  //                         PIPELINE REGISTERS
  // ═════════════════════════════════════════════════════════════════════
  val if_id_0 = RegInit(0.U.asTypeOf(new IF_ID_Entry))
  val if_id_1 = RegInit(0.U.asTypeOf(new IF_ID_Entry))

  // 2-slot compacting issue buffer (replaces a plain ID_IS_Reg)
  val buf_0 = RegInit(0.U.asTypeOf(new ID_IS_Entry))
  val buf_1 = RegInit(0.U.asTypeOf(new ID_IS_Entry))

  val is_ex_0 = RegInit(0.U.asTypeOf(new IS_EX_Entry))
  val is_ex_1 = RegInit(0.U.asTypeOf(new IS_EX_Entry))

  val ex_mem_0 = RegInit(0.U.asTypeOf(new EX_MEM_Entry))
  val ex_mem_1 = RegInit(0.U.asTypeOf(new EX_MEM_Entry))

  val mem_wb_0 = RegInit(0.U.asTypeOf(new MEM_WB_Entry))
  val mem_wb_1 = RegInit(0.U.asTypeOf(new MEM_WB_Entry))

  // Forward declarations
  val flush           = Wire(Bool())
  val redirect_pc     = Wire(UInt(32.W))
  val div_busy        = Wire(Bool())

  // ═════════════════════════════════════════════════════════════════════
  //                               ID  (decode both IF_ID_Reg lanes)
  // ═════════════════════════════════════════════════════════════════════
  dec0.io.inst  := if_id_0.inst
  dec0.io.pc    := if_id_0.pc
  dec0.io.valid := if_id_0.valid
  dec1.io.inst  := if_id_1.inst
  dec1.io.pc    := if_id_1.pc
  dec1.io.valid := if_id_1.valid

  val dec_ent_0 = Wire(new ID_IS_Entry)
  val dec_ent_1 = Wire(new ID_IS_Entry)
  dec_ent_0.valid := if_id_0.valid
  dec_ent_0.ctrl  := dec0.io.out
  dec_ent_1.valid := if_id_1.valid
  dec_ent_1.ctrl  := dec1.io.out

  // ═════════════════════════════════════════════════════════════════════
  //                     IS — RegRead + Bypass + Hazard
  // ═════════════════════════════════════════════════════════════════════
  val p0 = buf_0.ctrl
  val p1 = buf_1.ctrl
  val p0_valid = buf_0.valid
  val p1_valid = buf_1.valid

  regfile.io.raddr(0) := p0.rj
  regfile.io.raddr(1) := p0.rk
  regfile.io.raddr(2) := p1.rj
  regfile.io.raddr(3) := p1.rk

  val rf_p0_rj = regfile.io.rdata(0)
  val rf_p0_rk = regfile.io.rdata(1)
  val rf_p1_rj = regfile.io.rdata(2)
  val rf_p1_rk = regfile.io.rdata(3)

  val ex0_is_load = is_ex_0.valid && is_ex_0.ctrl.is_load
  val ex1_is_load = is_ex_1.valid && is_ex_1.ctrl.is_load

  // EX-stage bypass values (forward-declared, filled in EX section).
  val ex0_result = Wire(UInt(32.W))
  val ex1_result = Wire(UInt(32.W))
  ex0_result := 0.U
  ex1_result := 0.U

  def bypassLookup(ra: UInt, rf_val: UInt): (UInt, Bool) = {
    val ex0_hit  = is_ex_0.valid && is_ex_0.ctrl.rd_valid && (is_ex_0.ctrl.rd === ra) && (ra =/= 0.U)
    val ex1_hit  = is_ex_1.valid && is_ex_1.ctrl.rd_valid && (is_ex_1.ctrl.rd === ra) && (ra =/= 0.U)
    val mem0_hit = ex_mem_0.valid && ex_mem_0.rd_valid && (ex_mem_0.rd === ra) && (ra =/= 0.U)
    val mem1_hit = ex_mem_1.valid && ex_mem_1.rd_valid && (ex_mem_1.rd === ra) && (ra =/= 0.U)
    val wb0_hit  = mem_wb_0.valid && mem_wb_0.rd_valid && (mem_wb_0.rd === ra) && (ra =/= 0.U)
    val wb1_hit  = mem_wb_1.valid && mem_wb_1.rd_valid && (mem_wb_1.rd === ra) && (ra =/= 0.U)

    // Priority (freshest first): EX1 > EX0 > MEM1 > MEM0 > WB1 > WB0 > rf
    val mem0_data = Mux(ex_mem_0.is_load, lsu.io.rdata, ex_mem_0.alu_out)
    val value = PriorityMux(Seq(
      ex1_hit  -> ex1_result,
      ex0_hit  -> ex0_result,
      mem1_hit -> ex_mem_1.alu_out,
      mem0_hit -> mem0_data,
      wb1_hit  -> mem_wb_1.wb_data,
      wb0_hit  -> mem_wb_0.wb_data,
      true.B   -> rf_val,
    ))

    // Load-use hazard: highest-priority EX hit is a load not yet ready.
    val load_use =
      (ex1_hit && ex1_is_load) ||
      (ex0_hit && !ex1_hit && ex0_is_load)
    (value, !load_use)
  }

  val (p0_rj_val, p0_rj_rdy) = bypassLookup(p0.rj, rf_p0_rj)
  val (p0_rk_val, p0_rk_rdy) = bypassLookup(p0.rk, rf_p0_rk)
  val (p1_rj_val, p1_rj_rdy) = bypassLookup(p1.rj, rf_p1_rj)
  val (p1_rk_val, p1_rk_rdy) = bypassLookup(p1.rk, rf_p1_rk)

  // ── Dual-issue arbitration ────────────────────────────────────────────
  val p1_not_alu   = p1.fu_id =/= FU_ALU
  val p0_is_br     = p0_valid && (p0.fu_id === FU_BR)
  val p0_is_non_alu = p0_valid && (p0.fu_id =/= FU_ALU)
  val p0_is_csr    = p0_valid && (p0.fu_id === FU_CSR)
  val p1_is_csr    = p1_valid && (p1.fu_id === FU_CSR)
  val p1_raw_on_p0 =
    p0_valid && p0.rd_valid && (p0.rd =/= 0.U) && (
      (p1.rj_valid && p1.rj === p0.rd) ||
      (p1.rk_valid && p1.rk === p0.rd))

  val dual_issue_ok =
    p0_valid && p1_valid &&
      !p1_not_alu &&
      !p0_is_non_alu &&
      !p0_is_br &&
      !p0_is_csr && !p1_is_csr &&
      !p1_raw_on_p0

  // Load-use stall checks: if the would-be-issued operands are not ready
  // because of a pending load, stall IS.
  val p0_lu_stall = p0_valid && (
    (p0.rj_valid && !p0_rj_rdy) ||
    (p0.rk_valid && !p0_rk_rdy))
  val p1_lu_stall_if_issued = p1_valid && (
    (p1.rj_valid && !p1_rj_rdy) ||
    (p1.rk_valid && !p1_rk_rdy))

  val div_is_in_ex = is_ex_0.valid && is_ex_0.ctrl.is_div

  // fire_p0 / fire_p1 decide what leaves IS this cycle:
  val fire_p0 = p0_valid && !p0_lu_stall && !div_busy && !flush
  val fire_p1 = fire_p0 && dual_issue_ok && !p1_lu_stall_if_issued

  // ── Compute src1 / src2 ──────────────────────────────────────────────
  def srcSel(ctrl: DecodeOut, rj_val: UInt, rk_val: UInt): (UInt, UInt) = {
    val src1 = Mux(ctrl.alu_rs1_sel === RS1_PC, ctrl.pc, rj_val)
    val src2 = MuxLookup(ctrl.alu_rs2_sel, rk_val)(Seq(
      RS2_REG  -> rk_val,
      RS2_IMM  -> ctrl.imm,
      RS2_FOUR -> 4.U(32.W),
    ))
    (src1, src2)
  }
  val (p0_src1, p0_src2) = srcSel(p0, p0_rj_val, p0_rk_val)
  val (p1_src1, p1_src2) = srcSel(p1, p1_rj_val, p1_rk_val)

  // ═════════════════════════════════════════════════════════════════════
  //                    ISSUE BUFFER REFILL (ID → buf)
  // ═════════════════════════════════════════════════════════════════════
  //
  // Residual (unfired) entries, in program order: buf_0_res (older), buf_1_res
  val res_0_valid = p0_valid && !fire_p0
  val res_1_valid = p1_valid && !fire_p1

  // The residual entries need to stay in the same program order.  Possible
  // post-fire combinations:
  //   (res_0_valid, res_1_valid) = (F,F): both fired         -> 2 slots free
  //   (F,T): only P1 left (impossible because fire requires order) ← see note
  //   (T,F): only P0 left (P1 fired? can't happen either)
  //   (T,T): neither fired                                    -> 0 slots free
  //
  // Because fire_p1 is gated on fire_p0, and the issue is in-order, we
  // can only get (F,F) or (T,*).  If fire_p0=false then res_0_valid=true
  // AND res_1_valid=p1_valid (P1 can't fire without P0).

  val free_slots = (!res_0_valid).asUInt +& (!res_1_valid).asUInt

  // Refill rule: only consume IF_ID_Reg lanes that fit into free_slots.
  // If both lanes are valid but free_slots==1, we can only take 1 → lose the
  // other.  Instead, keep IF_ID_Reg held for that case.
  val if_id_cnt = if_id_0.valid.asUInt +& if_id_1.valid.asUInt
  val can_refill_all = if_id_cnt <= free_slots

  val if_id_consume = can_refill_all && !flush

  // Compose next buf_0, buf_1:
  //   [buf_0, buf_1] next = first 2 of:
  //     if res_0_valid: buf_0
  //     if res_1_valid: buf_1
  //     if if_id_consume && dec_ent_0.valid: dec_ent_0
  //     if if_id_consume && dec_ent_1.valid: dec_ent_1
  val sources = Seq(
    (res_0_valid,                               buf_0),
    (res_1_valid,                               buf_1),
    (if_id_consume && dec_ent_0.valid,          dec_ent_0),
    (if_id_consume && dec_ent_1.valid,          dec_ent_1),
  )

  // Pick the 1st / 2nd valid source from the priority list (in order).
  val vs = sources.map(_._1)
  val es = sources.map(_._2)

  val buf_0_next = Wire(new ID_IS_Entry)
  val buf_1_next = Wire(new ID_IS_Entry)
  buf_0_next := 0.U.asTypeOf(new ID_IS_Entry)
  buf_1_next := 0.U.asTypeOf(new ID_IS_Entry)

  // Build the FIRST-valid using cumulative "before i" flag
  {
    var before: Bool = false.B
    for (i <- sources.indices) {
      when(!before && vs(i)) { buf_0_next := es(i) }
      before = before || vs(i)
    }
  }
  // Build the SECOND-valid using cumulative "seen >=1 before i" flag
  {
    var seen0: Bool = false.B
    var seen1: Bool = false.B
    for (i <- sources.indices) {
      when(seen0 && !seen1 && vs(i)) { buf_1_next := es(i) }
      seen1 = seen1 || (seen0 && vs(i))
      seen0 = seen0 || vs(i)
    }
  }

  // ─────────────  Commit the buffer update ─────────────
  when(flush) {
    buf_0.valid := false.B
    buf_1.valid := false.B
  }.otherwise {
    buf_0 := buf_0_next
    buf_1 := buf_1_next
  }

  // ─────────────  IF_ID_Reg update ─────────────
  when(flush) {
    if_id_0.valid := false.B
    if_id_1.valid := false.B
  }.elsewhen(if_id_consume) {
    if_id_0.valid := if_mod.io.out_valid_0
    if_id_0.pc    := if_mod.io.out_pc_0
    if_id_0.inst  := if_mod.io.out_inst_0
    if_id_1.valid := if_mod.io.out_valid_1
    if_id_1.pc    := if_mod.io.out_pc_1
    if_id_1.inst  := if_mod.io.out_inst_1
  }

  // Frontend backpressure: IF must not advance unless we're consuming its pair.
  val stall_front = !if_id_consume
  pc_mod.io.stall          := if_mod.io.pc_hold || stall_front
  pc_mod.io.redirect_valid := flush
  pc_mod.io.redirect_pc    := redirect_pc

  if_mod.io.pc_in            := pc_mod.io.pc
  if_mod.io.flush            := flush
  if_mod.io.downstream_stall := stall_front

  // ═════════════════════════════════════════════════════════════════════
  //                        IS → IS_EX_Reg
  // ═════════════════════════════════════════════════════════════════════
  when(flush) {
    is_ex_0.valid := false.B
    is_ex_1.valid := false.B
  }.elsewhen(!div_busy) {
    is_ex_0.valid  := fire_p0
    is_ex_0.ctrl   := p0
    is_ex_0.rj_val := p0_rj_val
    is_ex_0.rk_val := p0_rk_val
    is_ex_0.src1   := p0_src1
    is_ex_0.src2   := p0_src2
    is_ex_0.st_val := p0_rk_val

    is_ex_1.valid  := fire_p1
    is_ex_1.ctrl   := p1
    is_ex_1.rj_val := p1_rj_val
    is_ex_1.rk_val := p1_rk_val
    is_ex_1.src1   := p1_src1
    is_ex_1.src2   := p1_src2
    is_ex_1.st_val := p1_rk_val
  }
  // else div_busy: keep IS_EX_Reg contents so the DIV keeps being fed

  // ═════════════════════════════════════════════════════════════════════
  //                               EX
  // ═════════════════════════════════════════════════════════════════════
  alu0.io.op := is_ex_0.ctrl.alu_op
  alu0.io.a  := is_ex_0.src1
  alu0.io.b  := is_ex_0.src2

  alu1.io.op := is_ex_1.ctrl.alu_op
  alu1.io.a  := is_ex_1.src1
  alu1.io.b  := is_ex_1.src2

  bru.io.valid   := is_ex_0.valid && is_ex_0.ctrl.is_br
  bru.io.br_type := is_ex_0.ctrl.br_type
  bru.io.rj_val  := is_ex_0.rj_val
  bru.io.rk_val  := is_ex_0.rk_val
  bru.io.pc      := is_ex_0.ctrl.pc
  bru.io.imm     := is_ex_0.ctrl.imm

  mul.io.op := is_ex_0.ctrl.alu_op
  mul.io.a  := is_ex_0.rj_val
  mul.io.b  := is_ex_0.rk_val

  div.io.start := is_ex_0.valid && is_ex_0.ctrl.is_div && !div.io.busy && !div.io.done
  div.io.op    := is_ex_0.ctrl.alu_op
  div.io.a     := is_ex_0.rj_val
  div.io.b     := is_ex_0.rk_val

  // DIV keeps the pipe stalled until the DONE cycle.
  div_busy := (is_ex_0.valid && is_ex_0.ctrl.is_div && !div.io.done)

  // P0 result selection at EX output.
  val p0_link_pc  = is_ex_0.ctrl.pc + 4.U
  val p0_use_link = is_ex_0.ctrl.is_br &&
    (is_ex_0.ctrl.br_type === BR_BL || is_ex_0.ctrl.br_type === BR_JIRL)

  val p0_alu_out = Wire(UInt(32.W))
  when(is_ex_0.ctrl.is_csr) {
    p0_alu_out := 0.U   // CSR rdata produced in MEM; alu_out here is don't-care
  }.elsewhen(is_ex_0.ctrl.is_md && !is_ex_0.ctrl.alu_op(2)) {
    p0_alu_out := mul.io.out
  }.elsewhen(is_ex_0.ctrl.is_md && is_ex_0.ctrl.alu_op(2)) {
    p0_alu_out := div.io.out
  }.elsewhen(p0_use_link) {
    p0_alu_out := p0_link_pc
  }.otherwise {
    p0_alu_out := alu0.io.out      // ALU op or LS address
  }

  ex0_result := p0_alu_out
  ex1_result := alu1.io.out

  flush       := bru.io.mispred
  redirect_pc := bru.io.target

  // ─────────────  EX → EX_MEM_Reg ─────────────
  val ex_advance  = !div_busy || div.io.done
  val ex_flush_p1 = bru.io.mispred

  when(!ex_advance) {
    ex_mem_0.valid := false.B
    ex_mem_1.valid := false.B
  }.otherwise {
    ex_mem_0.valid    := is_ex_0.valid
    ex_mem_0.pc       := is_ex_0.ctrl.pc
    ex_mem_0.inst     := is_ex_0.ctrl.inst
    ex_mem_0.rd       := is_ex_0.ctrl.rd
    ex_mem_0.rd_valid := is_ex_0.ctrl.rd_valid
    ex_mem_0.alu_out  := p0_alu_out
    ex_mem_0.st_val   := is_ex_0.st_val
    ex_mem_0.mem_type := is_ex_0.ctrl.mem_type
    ex_mem_0.is_load  := is_ex_0.ctrl.is_load
    ex_mem_0.is_store := is_ex_0.ctrl.is_store
    ex_mem_0.is_mul   := is_ex_0.ctrl.is_mul
    ex_mem_0.is_csr   := is_ex_0.ctrl.is_csr
    ex_mem_0.link_pc  := p0_link_pc
    ex_mem_0.use_link := p0_use_link
    ex_mem_0.csr_op   := is_ex_0.ctrl.csr_op
    ex_mem_0.csr_addr := is_ex_0.ctrl.csr_addr
    ex_mem_0.csr_mask := is_ex_0.rj_val
    ex_mem_0.csr_new  := is_ex_0.rk_val

    ex_mem_1.valid    := is_ex_1.valid && !ex_flush_p1
    ex_mem_1.pc       := is_ex_1.ctrl.pc
    ex_mem_1.inst     := is_ex_1.ctrl.inst
    ex_mem_1.rd       := is_ex_1.ctrl.rd
    ex_mem_1.rd_valid := is_ex_1.ctrl.rd_valid
    ex_mem_1.alu_out  := alu1.io.out
    ex_mem_1.st_val   := 0.U
    ex_mem_1.mem_type := 0.U
    ex_mem_1.is_load  := false.B
    ex_mem_1.is_store := false.B
    ex_mem_1.is_mul   := false.B
    ex_mem_1.is_csr   := false.B
    ex_mem_1.link_pc  := 0.U
    ex_mem_1.use_link := false.B
    ex_mem_1.csr_op   := 0.U
    ex_mem_1.csr_addr := 0.U
    ex_mem_1.csr_mask := 0.U
    ex_mem_1.csr_new  := 0.U
  }

  // ═════════════════════════════════════════════════════════════════════
  //                               MEM
  // ═════════════════════════════════════════════════════════════════════
  lsu.io.valid    := ex_mem_0.valid && (ex_mem_0.is_load || ex_mem_0.is_store)
  lsu.io.mem_type := ex_mem_0.mem_type
  lsu.io.addr     := ex_mem_0.alu_out
  lsu.io.st_val   := ex_mem_0.st_val

  csr_mini.io.valid    := ex_mem_0.valid && ex_mem_0.is_csr
  csr_mini.io.op       := ex_mem_0.csr_op
  csr_mini.io.csr_addr := ex_mem_0.csr_addr
  csr_mini.io.rj_val   := ex_mem_0.csr_mask
  csr_mini.io.wdata    := ex_mem_0.csr_new

  val p0_wb_data = Wire(UInt(32.W))
  when(ex_mem_0.is_load) {
    p0_wb_data := lsu.io.rdata
  }.elsewhen(ex_mem_0.is_csr) {
    p0_wb_data := csr_mini.io.rdata
  }.elsewhen(ex_mem_0.use_link) {
    p0_wb_data := ex_mem_0.link_pc
  }.otherwise {
    p0_wb_data := ex_mem_0.alu_out
  }
  val p1_wb_data = ex_mem_1.alu_out

  // ─────────────  MEM → MEM_WB_Reg  ─────────────
  mem_wb_0.valid    := ex_mem_0.valid
  mem_wb_0.pc       := ex_mem_0.pc
  mem_wb_0.inst     := ex_mem_0.inst
  mem_wb_0.rd       := ex_mem_0.rd
  mem_wb_0.rd_valid := ex_mem_0.rd_valid
  mem_wb_0.wb_data  := p0_wb_data

  mem_wb_1.valid    := ex_mem_1.valid
  mem_wb_1.pc       := ex_mem_1.pc
  mem_wb_1.inst     := ex_mem_1.inst
  mem_wb_1.rd       := ex_mem_1.rd
  mem_wb_1.rd_valid := ex_mem_1.rd_valid
  mem_wb_1.wb_data  := p1_wb_data

  // ═════════════════════════════════════════════════════════════════════
  //                               WB + COMMIT
  // ═════════════════════════════════════════════════════════════════════
  regfile.io.waddr(0) := mem_wb_0.rd
  regfile.io.wdata(0) := mem_wb_0.wb_data
  regfile.io.wen(0)   := mem_wb_0.valid && mem_wb_0.rd_valid

  regfile.io.waddr(1) := mem_wb_1.rd
  regfile.io.wdata(1) := mem_wb_1.wb_data
  regfile.io.wen(1)   := mem_wb_1.valid && mem_wb_1.rd_valid

  io.cmt_0.valid          := mem_wb_0.valid
  io.cmt_0.pc             := mem_wb_0.pc
  io.cmt_0.inst           := mem_wb_0.inst
  io.cmt_0.rd_valid       := mem_wb_0.rd_valid
  io.cmt_0.rd             := mem_wb_0.rd
  io.cmt_0.exception      := false.B
  io.cmt_0.exception_code := 0.U

  io.cmt_1.valid          := mem_wb_1.valid
  io.cmt_1.pc             := mem_wb_1.pc
  io.cmt_1.inst           := mem_wb_1.inst
  io.cmt_1.rd_valid       := mem_wb_1.rd_valid
  io.cmt_1.rd             := mem_wb_1.rd
  io.cmt_1.exception      := false.B
  io.cmt_1.exception_code := 0.U

  io.cmt_rf_0  := regfile.io.snap(0)
  io.cmt_rf_1  := regfile.io.snap(1)
  io.cmt_rf_2  := regfile.io.snap(2)
  io.cmt_rf_3  := regfile.io.snap(3)
  io.cmt_rf_4  := regfile.io.snap(4)
  io.cmt_rf_5  := regfile.io.snap(5)
  io.cmt_rf_6  := regfile.io.snap(6)
  io.cmt_rf_7  := regfile.io.snap(7)
  io.cmt_rf_8  := regfile.io.snap(8)
  io.cmt_rf_9  := regfile.io.snap(9)
  io.cmt_rf_10 := regfile.io.snap(10)
  io.cmt_rf_11 := regfile.io.snap(11)
  io.cmt_rf_12 := regfile.io.snap(12)
  io.cmt_rf_13 := regfile.io.snap(13)
  io.cmt_rf_14 := regfile.io.snap(14)
  io.cmt_rf_15 := regfile.io.snap(15)
  io.cmt_rf_16 := regfile.io.snap(16)
  io.cmt_rf_17 := regfile.io.snap(17)
  io.cmt_rf_18 := regfile.io.snap(18)
  io.cmt_rf_19 := regfile.io.snap(19)
  io.cmt_rf_20 := regfile.io.snap(20)
  io.cmt_rf_21 := regfile.io.snap(21)
  io.cmt_rf_22 := regfile.io.snap(22)
  io.cmt_rf_23 := regfile.io.snap(23)
  io.cmt_rf_24 := regfile.io.snap(24)
  io.cmt_rf_25 := regfile.io.snap(25)
  io.cmt_rf_26 := regfile.io.snap(26)
  io.cmt_rf_27 := regfile.io.snap(27)
  io.cmt_rf_28 := regfile.io.snap(28)
  io.cmt_rf_29 := regfile.io.snap(29)
  io.cmt_rf_30 := regfile.io.snap(30)
  io.cmt_rf_31 := regfile.io.snap(31)

  // ═════════════════════════════════════════════════════════════════════
  //               HARVARD BUS — no arbitration, direct wiring
  // ═════════════════════════════════════════════════════════════════════
  io.imem.valid := if_mod.io.imem.valid
  io.imem.addr  := if_mod.io.imem.addr
  io.imem.size  := if_mod.io.imem.size
  io.imem.wdata := 0.U
  io.imem.wstrb := 0.U
  if_mod.io.imem.rdata := io.imem.rdata
  if_mod.io.imem.ready := io.imem.ready

  io.dmem.valid := lsu.io.dmem.valid
  io.dmem.addr  := lsu.io.dmem.addr
  io.dmem.size  := lsu.io.dmem.size
  io.dmem.wdata := lsu.io.dmem.wdata
  io.dmem.wstrb := lsu.io.dmem.wstrb
  lsu.io.dmem.rdata := io.dmem.rdata
  lsu.io.dmem.ready := io.dmem.ready
}
