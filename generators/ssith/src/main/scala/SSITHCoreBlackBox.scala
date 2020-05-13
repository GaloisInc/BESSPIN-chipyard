package ssith

import java.io.File

import chisel3._
import chisel3.experimental.{ChiselAnnotation, ExtModule, RunFirrtlTransform}
import firrtl.transforms.{BlackBoxResourceAnno, BlackBoxSourceHelper}
import freechips.rocketchip.amba.axi4.AXI4BundleParameters
import ssith.SSITHCoreType.SSITHCoreType

import scala.sys.process._

class SSITHCoreBlackbox( coreType: SSITHCoreType,
                         axiAddrWidth: Int,
                          axiDataWidth: Int,
                          axiUserWidth: Int,
                          axiIdWidth: Int)
  extends ExtModule()
{
  override def desiredName: String = {
    if (coreType.toString.contains("p2"))
      "mkP2_Core"
    else if (coreType.toString.contains("p1"))
      "mkP1_Core"
    else
      "mkP3_Core"
  }
  val CLK = IO(Input(Clock()))
  val RST_N = IO(Input(Bool()))
  val master0 = IO(new SSITHAXI4Bundle(AXI4BundleParameters(axiAddrWidth, axiDataWidth, axiIdWidth, axiUserWidth, false)))
  val master1 = IO(new SSITHAXI4Bundle(AXI4BundleParameters(axiAddrWidth, axiDataWidth, axiIdWidth, axiUserWidth, false)))
  val cpu_external_interrupt_req = IO(Input(UInt(16.W)))
  val tv_verifier_info_tx_tvalid = IO(Output(Bool()))
  val tv_verifier_info_tx_tdata = IO(Output(UInt(608.W)))
  val tv_verifier_info_tx_tstrb = IO(Output(UInt(76.W)))
  val tv_verifier_info_tx_tkeep = IO(Output(UInt(76.W)))
  val tv_verifier_info_tx_tlast = IO(Output(Bool()))
  val tv_verifier_info_tx_tready = IO(Input(Bool()))
  val jtag_tdi = IO(Input(Bool()))
  val jtag_tms = IO(Input(Bool()))
  val jtag_tclk = IO(Input(Bool()))
  val jtag_tdo = IO(Output(Bool()))
  val CLK_jtag_tclk_out = IO(Output(Bool()))
  val CLK_GATE_jtag_tclk_out = IO(Output(Bool()))

  // pre-process the verilog to remove "includes" and combine into one file
  val verilogDir = new File(s"generators/ssith/src/main/resources/vsrc")

  val verilogDirPath = if (verilogDir.exists()) {
    verilogDir.getAbsolutePath()
  } else {
    // We are running in FireSim, need to use expanded path
    s"target-rtl/chipyard/generators/ssith/src/main/resources/vsrc"
  }
  val make = s"make -C ${verilogDirPath} ${coreType}"
  val proc = make
  require (proc.! == 0, "Failed to run preprocessing step")

  // add wrapper/blackbox after it is pre-processed
  val anno = new ChiselAnnotation with RunFirrtlTransform {
    def toFirrtl = BlackBoxResourceAnno(toTarget, s"/vsrc/${coreType}.v")
    def transformClass = classOf[BlackBoxSourceHelper]
  }
  chisel3.experimental.annotate(anno)
}