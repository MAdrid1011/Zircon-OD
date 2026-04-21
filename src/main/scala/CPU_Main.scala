import chisel3._
import circt.stage.ChiselStage

object CPU_Main extends App {
  var firtool_options = Array(
    "-disable-all-randomization",
    "-strip-debug-info",
    "-strip-fir-debug-info",
    "-O=release",
    "--ignore-read-enable-mem",
    "--lower-memories",
    "--lowering-options=disallowLocalVariables, explicitBitcast, disallowMuxInlining, disallowExpressionInliningInPorts, verifLabels",
    "-o=verilog/",
    "-split-verilog",
  )
  if (System.getProperties().getProperty("mode") != "sim") {
    firtool_options = firtool_options ++ Array("--vb-to-bv")
  }
  ChiselStage.emitSystemVerilogFile(
    new CPU,
    Array("-td", "build/"),
    firtoolOpts = firtool_options,
  )
}
