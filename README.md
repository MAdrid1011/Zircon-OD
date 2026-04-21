# Zircon-OD

> LoongArch32R 顺序双发射处理器 · Chisel 6 · 零依赖教学实现

**Zircon-OD**（*Order / Dual-issue*）是 **Zircon** 处理器家族中的**顺序双发射**
分支。它与乱序版的 Zircon 共用同一套译码表与控制信号编码，但刻意去掉了
寄存器重命名、ROB、发射队列、Cache、TLB、MMU、CSR/异常等复杂机构，保留最简洁
的双发射流水线骨架 —— 作为从标量流水线迈向超标量的第一步，结构清晰、模块
边界明确，适合作为教学与迭代开发的起点。

---

## 设计亮点

- **LoongArch32R 非特权子集**：覆盖 functest / CoreMark / Dhrystone 全部用到的指令。
- **7 级顺序双发射流水线**：`PF → IF → ID → IS → EX → MEM → WB`，两条 lane（P0 / P1）始终保持程序顺序。
- **双 ALU + 共享专用单元**：2 × ALU · 1 × BRU · 1 × AGU/LSU · 1 × MUL（单拍组合） · 1 × DIV（多拍阻塞） · 1 × CSR_Mini。
- **双端口哈佛总线**：指令端口一拍取 64-bit（同时拿到两条指令），数据端口独立读写，**无需内部仲裁**。
- **多级旁路 + 严格顺序提交**：EX→EX、MEM→EX、WB→EX 三级 bypass；`load-use` 暂停一拍；2-slot compacting issue buffer。
- **极小 CSR 子集**（`CSR_Mini`）：仅实现 `CRMD / DMW0 / DMW1`，用于通过 LA32R 启动段 `_start`；其它 CSR 一律忽略。
- **静态 not-taken 分支策略**：分支在 EX 解析并冲刷前端（4-cycle penalty）。

## 配套仿真环境

Zircon-OD 的差分测试与运行依赖 LA32RSim-2026 仿真框架，只需把生成的 RTL
放入它的 `rtl/` 目录即可完成对接。关键配置：

```make
CONFIG_NCOMMIT=2
CONFIG_RESET_VECTOR=0x1c000000
CONFIG_MEM_HARVARD=y                     # 哈佛双端口简单总线
CONFIG_DIFFTEST=y
CONFIG_DIFFTEST_FULL_RF=y
# CONFIG_DIFFTEST_TLBFILL_SYNC is not set
```

顶层模块名固定为 `CPU`，它向外暴露：

- `io_imem_*`（只读，`size` + 64-bit `rdata`）
- `io_dmem_*`（读写，8-bit `wstrb` + 64-bit `rdata/wdata`）
- `io_cmt_0/1_*`（2 路提交端口）
- `io_cmt_rf_0 .. io_cmt_rf_31`（组合输出的架构寄存器堆快照）

接口细节见 [`docs/Design.md §4`](docs/Design.md#4-顶层-io-与仿真对接)。

## Quick Start

```bash
# 1) 生成 SystemVerilog 到 ./verilog/
make verilog

# 2) 生成并部署到配套仿真器的 rtl/ 目录
#    （默认路径 ../rtl/，对应同级的 LA32RSim-2026 目录）
make deploy
```

更细的流程（如何把 Verilog 接入仿真器、跑 functest / CoreMark）请参考配套
仿真框架的 README 与本仓库 `docs/Design.md §10`。

## 基准测试结果

在 LA32RSim-2026 + 差分测试下：

| 测试 | 结果 | 说明 |
|---|---|---|
| functest (35 条小用例) | 35 / 35 PASS | 包括分支、移位、访存、递归、字符串、大数乘除等 |
| CoreMark                | GOOD_TRAP, IPC ≈ **0.744** | 主要瓶颈：分支误预测（静态策略） + load-use 气泡 |

理论 IPC 上限为 2.0。进一步提升需要引入简易分支预测、流水冲刷恢复优化
或指令级预取，可在当前结构上渐进式增量实现。

## 目录结构

```
Zircon-OD/
├── README.md                ← 本文件
├── build.sbt                ← Chisel 6 构建脚本
├── Makefile                 ← verilog / deploy 入口
├── docs/
│   └── Design.md            ← 详细设计文档（流水线、模块接口、冒险与前递）
└── src/main/scala/
    ├── CPU_Main.scala       ← ChiselStage 顶层入口
    ├── Top/CPU.scala        ← 顶层接线（流水线控制、旁路、双发射仲裁）
    ├── Loongarch/
    │   ├── Config.scala     ← 控制信号编码、FU/MEM/BR 常量、RESET_VEC
    │   ├── Architecture.scala  ← 指令 BitPat 与 Decode_Map
    │   └── Bundles.scala    ← 流水级寄存器 / SimpleBusIO / DecodeOut
    ├── Frontend/
    │   ├── PC.scala         ← 8 字节步进，分支冲刷汇聚点
    │   └── IF.scala         ← 单拍 64-bit 取指
    ├── Decode/
    │   └── Decode.scala     ← 单条译码
    ├── Issue/
    │   └── RegFile.scala    ← 4R2W，r0 硬零，write-forwarding
    ├── Execute/
    │   ├── ALU.scala        ← 2 条 lane 共享
    │   ├── BRU.scala        ← 分支解析，产生 flush / target
    │   ├── MUL.scala        ← 单拍 32×32
    │   └── DIV.scala        ← 多拍非还原除法，阻塞 P0
    ├── Mem/
    │   └── LSU.scala        ← 字节 / 半字 / 字对齐 + 符号扩展
    └── CSR/
        └── CSR_Mini.scala   ← 3 条 CSR 最小兼容
```

## 与 Zircon 的关系

Zircon 本体是**乱序 4 发射**的 LA32R 实现，结构更完整（ROB / 重命名 / 发射队列 /
Cache / TLB / CSR / 异常）。Zircon-OD 复用了 Zircon 的指令解码表和控制信号
常量，但只保留顺序双发射所需的最小机构，因此模块数、代码量与学习曲线都显著
降低。二者可作为同一家族的"简化版 / 完整版"对照实现。

## License

MIT
