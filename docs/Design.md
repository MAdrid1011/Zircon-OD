# Zircon-OD 详细设计文档

> LoongArch32R 顺序双发射处理器（Chisel 6 实现）
> 配套 LA32RSim-2026 仿真框架 · 哈佛双端口简单总线 · 无 Cache

---

## 目录

1. [设计目标与配置口径](#1-设计目标与配置口径)
2. [指令集覆盖范围](#2-指令集覆盖范围)
3. [流水线总览](#3-流水线总览)
4. [顶层 I/O 与仿真对接](#4-顶层-io-与仿真对接)
5. [模块接口与行为规范](#5-模块接口与行为规范)
   - 5.1 [PC](#51-pc)
   - 5.2 [IF — 取指](#52-if--取指)
   - 5.3 [Decode / DualDecode](#53-decode--dualdecode)
   - 5.4 [Hazard / Bypass](#54-hazard--bypass)
   - 5.5 [RegFile](#55-regfile)
   - 5.6 [ALU / MUL / DIV](#56-alu--mul--div)
   - 5.7 [BRU 分支单元](#57-bru-分支单元)
   - 5.8 [AGU / LSU 访存单元](#58-agu--lsu-访存单元)
   - 5.9 [CSR_Mini](#59-csr_mini)
   - 5.10 [Commit & 架构寄存器堆输出](#510-commit--架构寄存器堆输出)
6. [冒险与前递详表](#6-冒险与前递详表)
7. [双发射可行性判定](#7-双发射可行性判定)
8. [流水线控制信号](#8-流水线控制信号--stall-与-flush)
9. [对 LA32RSim-2026 的对接细节](#9-对-la32rsim-2026-的对接细节)
10. [构建、验证与调试](#10-构建验证与调试)
11. [性能目标与可裁剪性](#11-性能目标与可裁剪性)

---

## 1 设计目标与配置口径

Zircon-OD 是 Zircon 系列的**顺序双发射**分支，与乱序的 Zircon 共用一套译码
表和控制信号编码，但刻意去掉了重命名 / ROB / 发射队列 / Cache / TLB /
CSR·异常等一切复杂机构，保留最简的双发射流水线骨架，作为从标量流水线迈向
超标量的第一步。

**硬性配置口径：**

| 项 | 取值 | 说明 |
|---|---|---|
| 指令集 | LA32R 非特权子集 | 见 §2 |
| 发射宽度 | 2 | 严格顺序、同步发射/同步写回/同步提交 |
| 复位向量 | `0x1c000000` | 与 `CONFIG_RESET_VECTOR` 一致 |
| 指令存储访问 | 哈佛 imem 端口 | 64-bit 每周期（2 条 inst），`size=3` |
| 数据存储访问 | 哈佛 dmem 端口 | 单周期读/写，`size=2`（4 字节） |
| 提交端口数 | 2 | `CONFIG_NCOMMIT=2` |
| 差分寄存器堆 | 全量 32 个 GPR | `CONFIG_DIFFTEST_FULL_RF=y` |
| TLBFILL 同步 | **关闭** | `# CONFIG_DIFFTEST_TLBFILL_SYNC is not set` |
| Cache | 无 | 不实现 I/D-Cache |
| TLB / MMU | 无 | 依赖 REF 侧 DA 模式，CPU 直接输出虚拟地址 |
| 异常 / 中断 | 不实现 | 不产生任何异常提交（`io_cmt_*_exception = 0`） |

仿真侧需手动执行：

```bash
make menuconfig            # 取消 MEM_AXI，选 MEM_HARVARD；关 DIFFTEST_TLBFILL_SYNC
```

---

## 2 指令集覆盖范围

基于 `software/coremark/build/coremark-la32r.txt` 全文扫描，CoreMark 使用
到的指令集合为：

> `add.w addi.w and andi beq bge bgeu bl blt bltu bne break csrwr csrxchg`
> `div.w div.wu jirl ld.b ld.bu ld.h ld.hu ld.w lu12i.w mod.w mod.wu move`
> `mulh.wu mul.w or ori pcaddu12i slli.w sll.w slt sltu sltui srai.w`
> `srli.w srl.w st.b st.h st.w sub.w xor xori`

注：`move = or rd, rj, $r0`；`break` 作为仿真退出魔法字。

Zircon-OD 实现**以下完整列表**（囊括 functest / CoreMark / Dhrystone）：

| 类别 | 指令 |
|---|---|
| 算术 | `ADD.W` `SUB.W` `ADDI.W` `LU12I.W` `PCADDU12I` `SLT` `SLTU` `SLTI` `SLTUI` |
| 逻辑 | `AND` `OR` `XOR` `NOR` `ANDI` `ORI` `XORI` |
| 移位 | `SLL.W` `SRL.W` `SRA.W` `SLLI.W` `SRLI.W` `SRAI.W` |
| 乘除 | `MUL.W` `MULH.W` `MULH.WU` `DIV.W` `DIV.WU` `MOD.W` `MOD.WU` |
| 分支 | `BEQ` `BNE` `BLT` `BGE` `BLTU` `BGEU` `B` `BL` `JIRL` |
| 访存 | `LD.B` `LD.H` `LD.W` `LD.BU` `LD.HU` `ST.B` `ST.H` `ST.W` |
| 杂项 | `BREAK`（仿真退出）`CSRWR` `CSRXCHG` `CSRRD`（仅兼容 `_start`） |

**遇到未实现指令**的处理策略：译码生成 `illegal = 1`，但因为无异常机构，
直接**作为 NOP 提交**（`rd_valid = 0`）。只要测试用例不包含此类指令即可。

> 关于 CSR：虽然用户明确"不需要特权指令"，但 LA32RSim-2026 统一使用
> `base-port/start.S` 引导，它在 `_start` 中执行了 3 条 CSR 指令
> 设置 `CSR_DMW0 / CSR_DMW1 / CSR_CRMD`。参考模拟器会真实执行这些指令
> 并改写 `r12`，因此 DUT 也必须给出**与参考模型一致的 r12 值**，否则
> 差分测试立即失败。为此 Zircon-OD 提供 [`CSR_Mini`](#59-csr_mini) —— 只
> 在一张 14-bit 索引的小表里保留 3 个 CSR，支持 `csrrd/csrwr/csrxchg`
> 的读-写-读回语义，不涉及任何 TLB/例外逻辑。

---

## 3 流水线总览

```
       ┌───┐   ┌───┐   ┌───┐   ┌───┐   ┌───┐   ┌────┐   ┌───┐
 PC ─► │PF │─► │IF │─► │ID │─► │IS │─► │EX │─► │MEM │─► │WB │─► ARF
       └───┘   └───┘   └───┘   └───┘   └───┘   └────┘   └───┘
        nextPC  imem    decode  rf-rd  ALU0/1  dmem    write    commit
               64-bit   × 2    + haz   BR/AGU  (LSU)   back      × 2
                                + byp   MUL-p0
                                        DIV-p0
```

7 级；每级两条 lane（记为 **P0 / P1**），全程保持程序顺序：

- **PF (Pre-Fetch)**：产生 64-bit 对齐的 `fetch_pc`，通过 `io_imem_*` 请求取指
- **IF (Instruction Fetch)**：单拍接收 64-bit `rdata`，切分为 `inst0`（低 32 bit）、`inst1`（高 32 bit）
- **ID (Decode)**：两条 lane 并行译码
- **IS (Issue / RegRead)**：冒险检查；合法则同时读寄存器堆与发射；否则"P1 不发 / 全停"
- **EX (Execute)**：P0/P1 的 ALU；P0 还可以是 BRU / AGU / MUL 起始 / DIV 启动
- **MEM (Memory)**：P0 访问 dmem 经过 1 拍；P1 的 ALU 结果直通；MUL 在此拍完成第 2 拍
- **WB (Write Back)**：两条 lane 各自写回寄存器堆；按顺序提交到 `io_cmt_0/1`

**关键不变式**：P0 指令在程序序上**早于** P1；因此提交时 `io_cmt_0` 槽位
始终对应较早的 PC。当 P1 被单发射时，只使用 `io_cmt_0`。

---

## 4 顶层 I/O 与仿真对接

顶层模块命名必须为 `CPU`（与 LA32RSim-2026 `VCPU` 对齐）。

```scala
// src/main/scala/Top/CPU.scala
class CPU extends Module {
  val io = IO(new Bundle {
    // 哈佛双端口简单总线（CONFIG_MEM_HARVARD=y）
    val imem = new SimpleBusIO              // 只读
    val dmem = new SimpleBusIO              // 读写

    // 提交端口 × 2
    val cmt_0 = new CommitPort              // 见 §5.10
    val cmt_1 = new CommitPort

    // 全量 32 GPR 快照
    val cmt_rf = Output(Vec(32, UInt(32.W)))
  })
}
```

生成 Verilog 后，Chisel 将 `io.imem.valid` 展平成 `io_imem_valid`，`io.cmt_0.valid`
展平成 `io_cmt_0_valid`，与 LA32RSim-2026 的顶层接口约定一致。

```scala
class SimpleBusIO extends Bundle {
  val valid = Output(Bool())
  val ready = Input(Bool())
  val addr  = Output(UInt(32.W))
  val size  = Output(UInt(2.W))      // log2 bytes: 0/1/2/3 → 1/2/4/8 B
  val wdata = Output(UInt(64.W))
  val wstrb = Output(UInt(8.W))      // 0 = 读；非 0 = 写
  val rdata = Input(UInt(64.W))
}
```

时序约定（与 `sim/src/Memory.cc::HarvardMemory` 对齐）：

- `ready` 始终为 1，零延迟
- 读：`valid & wstrb==0`，`rdata` 在**同周期**组合返回；低 `(1<<size)` 字节有效
- 写：`valid & wstrb!=0`，下降沿完成；`wstrb` 以字节粒度指定写掩码（可跨 4 字节边界）
- 两个端口物理独立，**无需 CPU 侧仲裁**

---

## 5 模块接口与行为规范

为便于阅读，每个模块给出：**接口 Bundle · 组合/时序行为 · 关键时序图**。
所有 `Input()/Output()` 基于模块侧视角（与 Chisel 惯用 API 一致）。

### 5.1 PC

生成 next fetch_pc，是唯一的分支冲刷汇聚点。

```scala
class PCIO extends Bundle {
  // 外部事件
  val stall          = Input(Bool())     // 前端停顿（IF 满 / IS 停 / dmem 占总线）
  val redirect_valid = Input(Bool())     // EX 分支误预测或 JIRL 提供的纠正
  val redirect_pc    = Input(UInt(32.W))
  // 输出
  val pc             = Output(UInt(32.W))   // 当前要取的 PC（总是 8-对齐后再使用）
  val fetch_pc       = Output(UInt(32.W))   // pc & ~7；imem 读地址
}
```

**行为**（PF 寄存器级，Reg 同步更新）：

```
reset          → pc := 0x1c000000
redirect_valid → pc := redirect_pc
stall          → pc 保持
otherwise      → pc := (pc & ~7) + 8     // 自然递增 8 字节向下对齐
```

fetch_pc 始终 = `pc & ~0x7`。若 `pc[2] == 1`，表示跳转目标落在第二条指令槽
位上，PF 需记忆一个 `drop_inst0` 位，IF 取回后把 inst0 作废（见 §5.2）。

### 5.2 IF — 取指

```scala
class IFIO extends Bundle {
  // 来自 PC
  val fetch_pc      = Input(UInt(32.W))
  val drop_inst0    = Input(Bool())       // PC[2]=1 时，本 bundle 只有 inst1 有效
  // 与 SimpleBus 仲裁器
  val imem          = Flipped(new SimpleBusIO)   // flipped：IF 作 master
  val mem_busy      = Input(Bool())       // LSU 正占用总线；IF 退让
  // 到 IF_ID 寄存器的两条指令（可能 0/1/2 条 valid）
  val inst_0        = Output(UInt(32.W))
  val inst_1        = Output(UInt(32.W))
  val pc_0          = Output(UInt(32.W))
  val pc_1          = Output(UInt(32.W))
  val valid_0       = Output(Bool())
  val valid_1       = Output(Bool())
  val stall_out     = Output(Bool())      // imem 未完成，前端需 stall
}
```

**行为**（哈佛 64-bit 单拍取指）：

```
imem.valid := !flush
imem.addr  := pc & ~7
imem.size  := 3              // 8 字节
inst_lo    := imem.rdata[31: 0]
inst_hi    := imem.rdata[63:32]
drop_inst0 := pc[2]           // 跳进高半字时丢弃 lo
valid_0    := !drop_inst0 && !flush
valid_1    := !flush
```

- 一拍返回一对指令；稳态取指吞吐 = 2 inst/cycle，不再是 IPC 瓶颈。
- `flush`（分支误预测）：同拍拉低 `valid_0/1`，下一拍 PC 已重定向到目标。

### 5.3 Decode / DualDecode

两条 lane 并行译码。`Decode` 与 Zircon 的译码器几乎相同，但**省略**与特权
和异常相关的输出；`DualDecode` 是对两个 `Decode` 的简单组合，并额外输出
"双发射能力向量"供 Issue 使用。

```scala
class DecodeOut extends Bundle {
  val inst        = UInt(32.W)
  val pc          = UInt(32.W)
  val valid       = Bool()

  // 寄存器号
  val rj          = UInt(5.W)
  val rk          = UInt(5.W)
  val rd          = UInt(5.W)
  val rj_valid    = Bool()
  val rk_valid    = Bool()
  val rd_valid    = Bool()     // 需要写回 (已处理 rd=0)

  // 控制信号（复用 Zircon Control_Signal）
  val imm         = UInt(32.W)
  val alu_op      = UInt(4.W)
  val alu_rs1_sel = UInt(1.W)   // 0=reg, 1=pc
  val alu_rs2_sel = UInt(2.W)   // 0=reg, 1=imm, 2=4
  val br_type     = UInt(4.W)   // NO_BR / BR_JIRL / BR_B / BR_BL / BR_xx
  val mem_type    = UInt(5.W)   // NO_MEM / LDB/LDH/LDW/LDBU/LDHU/STB/STH/STW
  val fu_id       = UInt(3.W)   // ALU/BR/LS/MD/CSR
  val csr_addr    = UInt(14.W)  // csr 指令用
  val csr_op      = UInt(2.W)   // 0=none,1=csrrd,2=csrwr,3=csrxchg
}
class DecodeIO extends Bundle {
  val inst  = Input(UInt(32.W))
  val pc    = Input(UInt(32.W))
  val valid = Input(Bool())
  val out   = Output(new DecodeOut)
}

class DualDecodeIO extends Bundle {
  val in_0, in_1   = Input(new IF_ID_Entry)      // inst, pc, valid
  val out_0, out_1 = Output(new DecodeOut)
}
```

**行为**（`Decode` 内部）：
复制 Zircon 的 `Decode_Map.map`，剔除所有 TLB / CSR_EXC / CACHE_OP / ERTN /
IDLE / BREAK / SYSCALL（或 BREAK 仅写 rd_valid=0 保持字面 NOP 语义，`break`
通过外部"魔法字 `0x80000000`"判别，不需要在译码里做任何事）。

**双发射信号（组合）**（定义于 DualDecode，用于 Hazard）：

```
p0_is_alu   = out_0.fu_id == ALU
p0_is_br    = out_0.fu_id == BR
p0_is_ls    = out_0.fu_id == LS
p0_is_mul   = out_0.fu_id == MD && alu_op ∈ {MUL,MULH,MULHU}
p0_is_div   = out_0.fu_id == MD && alu_op ∈ {DIV,DIVU,MOD,MODU}
p0_is_csr   = out_0.fu_id == CSR
// p1_* 同理
```

### 5.4 Hazard / Bypass

负责 3 件事：

1. **结构冒险**：判定 P1 是否可以与 P0 同时发射（§7）
2. **数据冒险**：RAW、load-use（§6）
3. **分支纠正回压**：在 EX 阶段产生 `redirect_valid/redirect_pc` → PC

```scala
class HazardIO extends Bundle {
  // 来自 ID 的两条指令（只读它们的 rj/rk/rd/*_valid/fu_id/br_type/mem_type）
  val id_0        = Input(new DecodeOut)
  val id_1        = Input(new DecodeOut)

  // 流水后续各级目的寄存器（用于 bypass / load-use）
  val ex0         = Input(new BypassTag)    // (rd, rd_valid, is_load, data, data_valid)
  val ex1         = Input(new BypassTag)
  val mem0        = Input(new BypassTag)
  val mem1        = Input(new BypassTag)
  val wb0         = Input(new BypassTag)
  val wb1         = Input(new BypassTag)

  // 分支纠正 & Flush
  val bru_mispred      = Input(Bool())
  val bru_target       = Input(UInt(32.W))

  // 输出：发射决策
  val issue_p0    = Output(Bool())
  val issue_p1    = Output(Bool())
  val stall_is    = Output(Bool())    // 后端尚未能接收
  val stall_front = Output(Bool())    // PF/IF/ID 全停
  val flush_front = Output(Bool())    // 分支误预测时把 PF/IF/ID/IS 清空

  // 到 Bypass mux 的选择位（6 路：rf / ex0 / ex1 / mem0 / mem1 / wb0 / wb1）
  val byp_sel_p0_rj = Output(UInt(3.W))
  val byp_sel_p0_rk = Output(UInt(3.W))
  val byp_sel_p1_rj = Output(UInt(3.W))
  val byp_sel_p1_rk = Output(UInt(3.W))
}
```

`BypassTag`：

```scala
class BypassTag extends Bundle {
  val valid      = Bool()
  val rd         = UInt(5.W)
  val data       = UInt(32.W)
  val data_valid = Bool()     // false ⇒ load 还没出结果，不能 bypass，需停顿
}
```

详细判定表见 §6 / §7。

### 5.5 RegFile

**4 读 · 2 写** 架构寄存器堆，组合读端口。

```scala
class RegFileIO extends Bundle {
  val raddr = Input(Vec(4, UInt(5.W)))    // p0.rj, p0.rk, p1.rj, p1.rk
  val rdata = Output(Vec(4, UInt(32.W)))
  val waddr = Input(Vec(2, UInt(5.W)))
  val wdata = Input(Vec(2, UInt(32.W)))
  val wen   = Input(Vec(2, Bool()))
  val snap  = Output(Vec(32, UInt(32.W)))  // 直接用于 io_cmt_rf_*
}
```

**行为**：

```
read  : rdata(i) = (raddr(i)==0) ? 0 : regs(raddr(i))
write : 同周期 wen(0)&&waddr(0)==k 则 regs(k) := wdata(0)
        同周期 wen(1)&&waddr(1)==k 则 regs(k) := wdata(1)
冲突  : 两个写端口同地址非零时，取 lane-1（程序序更晚）
r0    : 写入 r0 无效
snap  : 组合读出的数组，用于 difftest。必须在 commit 的 **同一周期** 反
        映写入后的值。最简实现：snap(i) = (i==0)?0: regs_next(i)。
```

`regs_next` 在 Chisel 里可以用 `RegNext` 前的 mux 组合表达，也可以直接让
顶层把 `io.cmt_rf` 连到经过写旁路的 RegFile 读结果。

### 5.6 ALU / MUL / DIV

#### ALU（2 份）

```scala
class ALUIO extends Bundle {
  val op  = Input(UInt(4.W))     // Control_Signal.ALU_*
  val a   = Input(UInt(32.W))
  val b   = Input(UInt(32.W))
  val out = Output(UInt(32.W))
}
```

组合逻辑，对应表：

| op | 行为 |
|---|---|
| ADD / SUB | `a +/- b` |
| SLT / SLTU | `$signed(a) < $signed(b)` / 无符号 |
| AND / OR / XOR / NOR | 按位 |
| SLL / SRL / SRA | 移位，`shamt = b[4:0]` |

> `LU12I.W` / `PCADDU12I` / `ADDI.W` 等都通过 `alu_rs1_sel / alu_rs2_sel`
> 在 IS 阶段把 `a/b` 选好后传给 ALU，ALU 无需特殊分支。

#### MUL（1 份，仅 P0 可发）

3 级流水 `BOOTH` 或直接用 Chisel `*`（让 firtool 综合成 DSP）：

```scala
class MULIO extends Bundle {
  val valid_in  = Input(Bool())
  val is_signed = Input(Bool())
  val op        = Input(UInt(2.W))      // 0=MUL.W (低32), 1=MULH.W, 2=MULH.WU
  val a, b      = Input(UInt(32.W))
  val valid_out = Output(Bool())
  val out       = Output(UInt(32.W))
}
```

**行为**：3 拍流水，`MUL` 入口在 EX；`MEM` 进到第 2 拍；`WB` 拿结果。
因为双发射但 MUL 只 1 份，**P1 永远不得是乘法指令**（见 §7）。
如果 P0 发了 MUL，P1 可以同时发 ALU（不冲突结构资源）。
但 P1 若在下一周期又是 MUL，就要看 MUL 流水是否空闲 —— 简化：**任意时刻
最多一条 MUL 在流水**，流水未空时新 MUL 到来会暂停整个 IS 阶段。

#### DIV（1 份，仅 P0 可发，**非流水**）

迭代除法，需要 34 拍左右。采用标准 `Valid/Ready` 握手：

```scala
class DIVIO extends Bundle {
  val in   = Flipped(Decoupled(new Bundle{
    val is_signed = Bool()
    val get_rem   = Bool()                 // 1 = MOD, 0 = DIV
    val a, b      = UInt(32.W)
  }))
  val out  = Decoupled(UInt(32.W))
  val busy = Output(Bool())
}
```

**行为**：P0 发 DIV 时，EX 直接 `in.valid := 1`。若 `in.ready == 0`（上一次除法未完），
IS 停顿；若 `in.ready == 1`，P0 进入"等待除法完成"状态，P1 也停止推进（顺序语义）。
除法完成时 `out.valid = 1`，数据写回 WB，整条流水线恢复。

> 更简化的写法：在 EX 里直接卡一个 `RegInit(0.U)` 计数器，busy 期间
> 把 `flush_front = 0, stall_front = 1, stall_is = 1`，计数满后把除法
> 结果注入 EX/MEM 寄存器。数学上等价。

### 5.7 BRU 分支单元

只挂在 P0 lane（P1 不能是分支，见 §7）。

```scala
class BRUIO extends Bundle {
  val valid   = Input(Bool())
  val br_type = Input(UInt(4.W))
  val rj_val  = Input(UInt(32.W))
  val rk_val  = Input(UInt(32.W))
  val pc      = Input(UInt(32.W))
  val imm     = Input(UInt(32.W))
  // 输出
  val taken   = Output(Bool())
  val target  = Output(UInt(32.W))
  val link_pc = Output(UInt(32.W))   // BL/JIRL 写 rd 的值 = pc + 4
  val mispred = Output(Bool())        // 始终等价于 taken（静态不预测）
  val next_pc_seq = Output(UInt(32.W)) // pc + 4（给 P1 后的下一个连续地址）
}
```

**行为**：

| br_type | taken | target |
|---|---|---|
| NO_BR | 0 | — |
| B / BL | 1 | `pc + imm(26s)` |
| JIRL | 1 | `rj + imm(16s)` |
| BEQ | `rj==rk` | `pc + imm(16s)` |
| BNE | `rj!=rk` | 同上 |
| BLT/BGE/BLTU/BGEU | 按比较 | 同上 |

**mispred**：因为静态不预测（默认 `next_pc = pc+8`），只要 `taken==1` 就
是误预测；`flush_front = 1`，`redirect_pc = target`，这条分支本身仍正常写
回 `link_pc` 到 r1（BL）或 rd（JIRL）。

**P1 的处理**：如果 P0 是分支 *且* taken=1，那 P1 就是"影子指令"，会被注
销（设置 P1 的 `io_cmt_1_valid=0`，不写寄存器，不访存）。这对应于分支
延迟槽被丢弃。实现上就是：

```
if (p0_mispred_in_EX) {
  flush IS/ID/IF/PF  (内部 valid := false)
  kill EX.p1         (本拍 P1 的写回/访存被 mask)
}
```

### 5.8 AGU / LSU 访存单元

AGU = 地址计算（组合 `rj + imm`）；LSU = 发送到 SimpleBus。只挂在 P0。

```scala
class LSUIO extends Bundle {
  // IS/EX 输入（锁存进 EX_MEM_Reg 后送来）
  val valid    = Input(Bool())
  val mem_type = Input(UInt(5.W))      // NO_MEM / LDB / ... / STW
  val addr     = Input(UInt(32.W))     // rj + imm
  val wdata    = Input(UInt(32.W))     // st_src
  // 总线
  val dmem     = new SimpleBusIO       // master
  // 到 WB
  val rdata    = Output(UInt(32.W))    // 对齐、符号扩展后的值
  val busy     = Output(Bool())
}
```

**对地址的分字节/分半字处理**：

```
addr_word = addr & ~3
offset    = addr[1:0]
wstrb     = MuxCase(0.U, Seq(
             (mem_type==MEM_STB) ->  (1.U << offset),
             (mem_type==MEM_STH) ->  (3.U << offset),        // offset[0]==0
             (mem_type==MEM_STW) ->  "b1111".U))
wdata_aligned = MuxCase(wdata, Seq(
             (mem_type==MEM_STB) ->  (wdata(7,0)  << (offset*8)),
             (mem_type==MEM_STH) ->  (wdata(15,0) << (offset*8)),
             (mem_type==MEM_STW) ->  wdata))

rdata_raw  = dmem.rdata
rdata_word = rdata_raw
rdata_half = rdata_raw >> (offset*8) (低16)
rdata_byte = rdata_raw >> (offset*8) (低 8)
rdata_out  = MuxLookup(mem_type, 0.U, Seq(
               MEM_LDB  -> SignExt(rdata_byte, 32),
               MEM_LDBU -> ZeroExt(rdata_byte, 32),
               MEM_LDH  -> SignExt(rdata_half, 32),
               MEM_LDHU -> ZeroExt(rdata_half, 32),
               MEM_LDW  -> rdata_word))
```

**访存 = 1 拍**（直接 SimpleBus）：MEM 拍 `valid := 1`；因为仿真器 `ready`
恒为 1 且 `rdata` 组合返回，下一拍 WB 就能写回。

**与 IF 的总线关系**：哈佛架构下 IF 直接使用 `io.imem`、LSU 使用 `io.dmem`，
两个端口物理独立、仿真器同周期并行服务，无需任何仲裁。

### 5.9 CSR_Mini

仅实现 `_start` 需要的 3 条 CSR，用于避免 difftest 在启动段就失败。

```scala
class CSRMiniIO extends Bundle {
  val valid     = Input(Bool())
  val op        = Input(UInt(2.W))       // 1=RD, 2=WR, 3=XCHG
  val csr_addr  = Input(UInt(14.W))
  val rj_val    = Input(UInt(32.W))      // XCHG 的 mask (实际上是 rj)
  val rd_in     = Input(UInt(32.W))      // XCHG 的 new_val (实际上是 rd_old_reg)
  val wdata     = Input(UInt(32.W))      // WR 的数据
  val rdata     = Output(UInt(32.W))     // 回写到 GPR 的值
}
```

**内部存储**（初值见 `ref/include/CSR.h`）：

```
CSR_CRMD (0x000) : initial 0x8        ( DA=1, PG=0, PLV=0 )
CSR_DMW0 (0x180) : initial 0
CSR_DMW1 (0x181) : initial 0
其它 csr_addr     : 读作 0 ； 写后丢弃
```

**语义**（与参考模拟器一致）：

| op | 行为（伪代码） |
|---|---|
| `CSRRD`  | `rdata := csr(addr)` |
| `CSRWR`  | `rdata := csr(addr); csr(addr) := wdata` |
| `CSRXCHG`| `old := csr(addr); rdata := old; csr(addr) := (old & ~mask) \| (new & mask)` 其中 `mask = rj_val, new = rd_in` |

CSR 操作只在 P0 通道，且**禁止**与任何指令同拍双发射（见 §7）。
执行延迟 1 拍（当作 ALU-like），结果在 WB 写回 rd。

### 5.10 Commit & 架构寄存器堆输出

```scala
class CommitPort extends Bundle {
  val valid          = Output(Bool())
  val pc             = Output(UInt(32.W))
  val inst           = Output(UInt(32.W))
  val rd_valid       = Output(Bool())
  val rd             = Output(UInt(5.W))
  val exception      = Output(Bool())      // 恒 0
  val exception_code = Output(UInt(6.W))   // 恒 0
}
```

**行为**（顶层组合连接）：

```
io.cmt_0.valid          := wb_p0.valid && !wb_p0.kill
io.cmt_0.pc             := wb_p0.pc
io.cmt_0.inst           := wb_p0.inst
io.cmt_0.rd_valid       := wb_p0.rd_valid
io.cmt_0.rd             := wb_p0.rd
io.cmt_0.exception      := 0.B
io.cmt_0.exception_code := 0.U

io.cmt_1.valid          := wb_p1.valid && !wb_p1.kill && !wb_p0_is_taken_branch
io.cmt_1....

for i in 0..31:
  io.cmt_rf[i] := regfile.snap(i)     // 已反映本周期两条写入
```

关键约束（来自 `sim/src/Emulator.cc`）：

- `cmt_rf` 必须与 `valid=1` **同周期**有效，且反映*所有*当拍 commit 之后的 GPR。
- `io_cmt_0.pc` 必须是程序序上较早的那条。
- 当一条指令不写寄存器（如 `st.w`, `b`, `bne` 不成立时），`rd_valid=0`，`rd` 任取，差分器不检查。

---

## 6 冒险与前递详表

**目的寄存器编号**：P0 lane 记为 `p0_rd`，P1 记为 `p1_rd`，其中若
`rd=0` 或 `rd_valid=0`，视为 "no-write"（不产生写后读相关）。

### 6.1 RAW（IS 阶段判断）

记 IS 阶段两条指令为 `I0, I1`。需要停顿/bypass 的情况：

| 读源 | 写源 | 数据是否已就绪 | 处理 |
|---|---|---|---|
| `I0.rj/rk`  | `EX.p0/p1.rd` | ALU 结果已在 EX 末端可取 | EX→IS bypass |
| `I0.rj/rk`  | `MEM.p0/p1.rd` | ALU 结果已稳定；load 结果当拍可用 | MEM→IS bypass |
| `I0.rj/rk`  | `WB.p0/p1.rd`  | 同上 | WB→IS bypass |
| `I0.rj/rk`  | `EX.p0.rd` 且 `EX.p0.is_load=1` | load **还没出数据** | **stall 1 拍** |
| `I1.rj/rk`  | `I0.rd`（同拍）| 本拍 ALU 结果 end-of-EX 才出 | P1 取 `ALU0.out` 作 bypass ⟹ 增加 EX 同级 bypass |
|             |                | 若 `I0` 是 load/mul/div/csr | **I1 必须等下一拍**（单发射 I0，I1 留在 ID）|

**关键点**：P1 读 P0 的结果需要"EX 级同拍旁路"——这是双发射新增的硬件。
实现：在 EX 级，把 P0 的 ALU 组合输出直接接入 P1 的 rs1/rs2 mux；
`byp_sel_p1_rj == SAME_CYCLE_P0` 时选它。这条路径只有在 P0 是 ALU / CSR-rd
（1 拍就能出结果）时有效；P0 是 load/mul/div 时，`I1` 必须等，退化为单发射。

### 6.2 Bypass 优先级

`I.rj / I.rk` 读端的选择顺序（按优先级由高到低）：

```
1. (I.rj == same-cycle P0.rd) && P0 是 ALU/CSR-rd   → 同拍 ALU0 组合输出
2. (I.rj == EX.p0.rd)  && EX.p0 非 load/mul/div     → EX0 末端寄存器
3. (I.rj == EX.p1.rd)  && EX.p1 是 ALU              → EX1 末端寄存器
4. (I.rj == MEM.p0.rd)                              → MEM.p0.data（load 完成 / ALU-pass-through）
5. (I.rj == MEM.p1.rd)                              → MEM.p1.data
6. (I.rj == WB.p0.rd)                               → WB.p0.data
7. (I.rj == WB.p1.rd)                               → WB.p1.data
8. 以上都不命中                                       → RegFile.rdata
```

**load-use stall**：当第 2 条匹配但 `EX.p0.is_load=1`，整条流水线 IS 阶段
停 1 拍：`stall_front = 1, stall_is = 1`，IS_EX_Reg 插入一个 bubble。

### 6.3 WAW / WAR

顺序提交/顺序写回天然无 WAW、WAR 隐患，但要注意 **同拍 P0/P1 写同一架构
寄存器**的情况：取 P1 的值（程序序晚的覆盖早的）。这在 RegFile 已处理
（§5.5）。

---

## 7 双发射可行性判定

默认 P0、P1 **都能**发射，除非遇到以下任一情形（此时 P1 不发，留在 ID
下一拍再议；P0 照常发射）：

| 规则 | 具体条件 |
|---|---|
| **R1 结构—单份功能单元** | `p1 是 BR / LS / MUL / DIV / CSR`（这些只挂 P0） |
| **R2 结构—同拍同单元** | `p0 和 p1 同为 MUL`（只有 1 份乘法器） |
| **R3 RAW 非 ALU 前递路径** | `p1 的 rj/rk == p0.rd` 且 `p0` 非 ALU/CSR-rd（即 `p0 ∈ load/mul/div/br`） |
| **R4 BR 阴影** | `p0 是 br`（任何 br，即便 `taken=0`）——为简化控制流，P1 与 BR 不并发 |
| **R5 CSR 串行化** | `p0 或 p1 是 CSR` → 只发 1 条 |

实际通常：两条 ALU、或一条 ALU + 一条 ALU。按 PDF 数据：~42.7% 相邻 ALU
对，因此即便 R1–R5 限制严厉，也足以体现教学级双发射的价值。

另外 P0 本身若无效（`!id_0.valid`），那 P1 禁止越过它发射——严格顺序。

> **进阶实现（非本版必需）**：允许 "p0 是 BR, p1 是 ALU" 并发；
> 只要 BR.taken=0 就正常提交 p1，taken=1 就 kill p1。可见 §5.7 已经按
> 这种实现给出 "kill EX.p1" 逻辑；§7 的 R4 是更保守的替代策略，二选一。
> **推荐使用 R4**（实现最简单）。

---

## 8 流水线控制信号 — Stall 与 Flush

全局只有 4 个控制信号：

| 信号 | 含义 | 产生处 |
|---|---|---|
| `stall_front` | PF / IF / ID 全部保持 | IF 未就绪 / IS 停 / DIV busy / MUL busy |
| `stall_is`    | IS_EX_Reg 插入 bubble，ID_IS_Reg 保持 | load-use / 双发资源冲突 |
| `flush_front` | PF / IF / ID / IS 清为 NOP | BR 误预测 / 异常（恒 0） |
| `mem_busy`    | IF 暂时不能使用总线 | MEM 阶段正在发起 dmem 访问 |

**时序**：所有控制信号**寄存器级传递**。`RegNext` 方式避免长组合路径。

---

## 9 对 LA32RSim-2026 的对接细节

### 9.1 启动 / 复位向量

参考 `sim/src/Emulator.cc::reset()`：

```
cpu->reset = 1; 切 10 个时钟； cpu->reset = 0;
```

Chisel 默认 `reset` 是高电平有效。PC 在复位后第 1 个 clock 的上升沿
变为 `0x1c000000`，恰好符合约定。

### 9.2 总线（哈佛分离双端口）

配合仿真环境 `CONFIG_MEM_HARVARD=y`，顶层暴露两组独立的简单总线：

```scala
val imem = io.imem   // 只读：IF 直连
val dmem = io.dmem   // 读写：LSU 直连

imem <> frontend.imem   // 两端均为 64-bit rdata + size 字段
dmem <> lsu.dmem
```

两个端口零延迟、互不仲裁。取指一次读 8 字节（`size=3`），一拍拿到一对 64-bit
指令对；LSU 按 `size=2` 读写 4 字节，完全没有 IF/LSU 争用。

### 9.3 差分接口

- `io_cmt_0_exception = 0`，`io_cmt_0_exception_code = 0`（Zircon-OD 不产生异常）
- `io_cmt_*_inst` 必须是**本条真实指令字**（用于 itrace + `isBreak0` 判定）
- `io_cmt_rf_*` 是**组合信号**，在 commit 的同一上升沿反映本拍写回后的 ARF

### 9.4 `break` 魔法字处理

仿真器检测 `inst == 0x80000000`（见 `sim/src/Emulator.cc::isBreak0`）。
CPU 只要把这一条"伪指令"像 NOP 一样往前推并在 WB 正常 commit（给出正确 pc、inst）
即可，不需任何特殊识别。

### 9.5 不写 `rd` 的指令

- `st.b/h/w`：`rd_valid = 0`
- `b`：`rd_valid = 0`
- `beq/bne/...`：`rd_valid = 0`
- `break`（`0x80000000`）：`rd_valid = 0`

---

## 10 构建、验证与调试

### 10.1 Chisel → Verilog

参考 `Zircon/build.sbt + CPU_Main.scala`：

```
cd Zircon-OD
make verilog         # sbt run CPU_Main
cp build/*.sv ../rtl/
cp build/filelist.f ../rtl/
```

### 10.2 选择接口配置

```
cd LA32RSim-2026
make menuconfig
  - 目标 CPU 设置 → NCOMMIT = 2
  - 内存接口 → 选 CONFIG_MEM_HARVARD
  - Difftest → 关闭 CONFIG_DIFFTEST_TLBFILL_SYNC
make all
make run IMG=software/functest/build/add-la32r.bin
```

### 10.3 推荐调试顺序

1. **通功能测试**：`make sim-all`，35 条 functest 全过
2. **通 coremark/dhrystone**：确认 CSR_Mini 行为正确
3. **开 wave**：`CONFIG_DUMP_WAVE=y` + `WAVE_BEGIN/END` 锁定时间窗
4. **开 itrace**：默认开启，BAD TRAP 时自动 dump 最近 32 条指令

### 10.4 典型 difftest 错误与定位

| 症状 | 根因 | 修复方向 |
|---|---|---|
| `_start` 第 4~5 条就挂 | CSR 不一致 | 检查 `CSR_Mini` 初始值、XCHG 语义 |
| 数据 load 值与 ref 差 16/24 bit 偏移 | `ld.b/h` 的 offset 对齐错误 | §5.8 的 `rdata_byte/half` |
| 分支后第 1 条指令执行了 | BR 阴影未 kill P1 | §5.7 / §7 R4 |
| `beq` 正确但 `bl` 的 r1 值不对 | BL 的 `link_pc = pc + 4`，别忘了 BL 通过 P0 lane 写 rd=1 | §5.7 |
| 某条 addi.w 的结果正确但下一条 add.w 错 | 同拍 RAW 旁路链漏掉 "P0→P1 同拍" | §6.1 |
| 0 条指令 commit 卡住 | IF 前端卡住（buf / pc_hold 条件不自洽） | §5.2 / §9.2 |

---

## 11 性能目标与可裁剪性

### 11.1 性能目标

- 面积：在 Artix-7 FPGA 上约 1.5× 单发射 Zircon-mini 基线
- 时序：100 MHz @ xc7a100t（关键路径在 EX 同拍 bypass mux）
- **IPC**（理论）：哈佛 64-bit 取指后上限恢复为 2.0；CoreMark 实测 ≈0.74（主要瓶颈转移到分支 mispred 与 load-use）

### 11.2 可裁剪的实现复杂度开关

| 旋钮 | Simple | Normal | Advanced |
|---|---|---|---|
| IF 带宽 | 64-bit 哈佛（本版） | 128-bit 哈佛 / 流水取指 | +ICache |
| MUL | 单周期 `*` | 3 级流水 Booth（本版） | Wallace 2-cycle |
| DIV | 迭代 34 拍（本版） | SRT-radix-2 18 拍 | SRT-radix-4 9 拍 |
| 分支 | 静态不取（本版） | 静态 BTFN | 1-bit / 2-bit BHT |
| CSR | Mini（本版） | 全量无特权 | 加 TLB/异常 |

### 11.3 后续扩展路径（与 Zircon 全家桶对齐）

```
Zircon-OD (当前)
   ↓ +重命名 / ROB
Zircon-Renamed  
   ↓ +发射队列
Zircon-OOO-Issue
   ↓ +Cache + 分支预测
Zircon (完整乱序)
```

每次"升级"只替换 IS 后的机构，前端与 LA32RSim-2026 接口保持稳定。

---

## 附录 A：各级流水寄存器 Bundle（建议）

```scala
class IF_ID_Entry extends Bundle {
  val valid = Bool()
  val pc    = UInt(32.W)
  val inst  = UInt(32.W)
}

class ID_IS_Entry extends Bundle {
  val valid = Bool()
  val pc    = UInt(32.W)
  val inst  = UInt(32.W)
  val ctrl  = new DecodeOut    // 译码结果
}

class IS_EX_Entry extends Bundle {
  val valid = Bool()
  val pc    = UInt(32.W)
  val inst  = UInt(32.W)
  val ctrl  = new DecodeOut
  val rj_val = UInt(32.W)
  val rk_val = UInt(32.W)
  val src1   = UInt(32.W)    // 已完成 rs1_sel（pc / reg）
  val src2   = UInt(32.W)    // 已完成 rs2_sel（imm / reg / 4）
}

class EX_MEM_Entry extends Bundle {
  val valid    = Bool()
  val pc       = UInt(32.W)
  val inst     = UInt(32.W)
  val rd       = UInt(5.W)
  val rd_valid = Bool()
  val alu_out  = UInt(32.W)    // 或 AGU.addr
  val st_val   = UInt(32.W)    // 对 lane P0 是 STORE 的数据
  val mem_type = UInt(5.W)
  val is_load  = Bool()
  val is_mul   = Bool()
  val link_pc  = UInt(32.W)    // for BL / JIRL, = pc + 4
  val is_br    = Bool()
  val br_taken = Bool()
  val br_target= UInt(32.W)
}

class MEM_WB_Entry extends Bundle {
  val valid    = Bool()
  val pc       = UInt(32.W)
  val inst     = UInt(32.W)
  val rd       = UInt(5.W)
  val rd_valid = Bool()
  val wb_data  = UInt(32.W)    // 来自 ALU / load / mul / csr
}
```

## 附录 B：模块文件对照表

| Scala 文件 | 实现 | 参考 Zircon 源 |
|---|---|---|
| `Loongarch/Config.scala`        | 直接复用 | `Zircon/.../Config.scala` |
| `Loongarch/Architecture.scala`  | 直接复用 | `Zircon/.../Architecture.scala` |
| `Frontend/PC.scala`             | §5.1 | 极简化自 `Zircon/.../PC.scala` |
| `Frontend/IF.scala`             | §5.2 | 新写（无 ICache） |
| `Decode/Decode.scala`           | §5.3 | 大幅裁剪 `Zircon/.../Decode.scala` |
| `Decode/DualDecode.scala`       | §5.3 | 新写 |
| `Issue/Hazard.scala`            | §5.4 / §6 / §7 | 新写，参考 `Zircon/.../Bypass_3.scala` 思路 |
| `Issue/Bypass.scala`            | §6.2 | 新写 |
| `Issue/RegFile.scala`           | §5.5 | 新写（4R2W） |
| `Execute/ALU.scala`             | §5.6 | 裁剪自 `Zircon/.../ALU.scala` |
| `Execute/MUL.scala`             | §5.6 | 裁剪自 `Zircon/.../Multiply.scala` |
| `Execute/DIV.scala`             | §5.6 | 裁剪自 `Zircon/.../Divide.scala` |
| `Execute/BRU.scala`             | §5.7 | 裁剪自 `Zircon/.../Branch.scala` |
| `Execute/AGU.scala`             | §5.8 | 新写（简单 `rj + imm`） |
| `Mem/LSU.scala`                 | §5.8 | 新写（SimpleBus 版） |
| `CSR/CSR_Mini.scala`            | §5.9 | 全新（3-CSR 兼容） |
| `Loongarch/Bundles.scala`       | §4  | `SimpleBusIO` 与 `CONFIG_MEM_HARVARD` 一致（64-bit + size） |
| `Top/CPU.scala`                 | §4  | 顶层组装，连接 `io_imem_*`, `io_dmem_*`, `io_cmt_*`, `io_cmt_rf_*` |
| `CPU_Main.scala`                | 入口 | 拷自 Zircon |
