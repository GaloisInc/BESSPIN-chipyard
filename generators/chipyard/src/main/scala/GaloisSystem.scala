
package chipyard

import chisel3._
import chipsalliance.rocketchip.config.{Config, Parameters}
import chisel3.util.Decoupled
import freechips.rocketchip.devices.tilelink.BootROMParams
import freechips.rocketchip.diplomacy.{LazyModule, LazyModuleImp}
import freechips.rocketchip.subsystem.{CanHaveMasterAXI4MMIOPort, CanHaveMasterAXI4MMIOPortModuleImp, CanHaveMasterAXI4MemPort,
  CanHaveMasterAXI4MemPortModuleImp, CanHaveSlaveAXI4Port, CanHaveSlaveAXI4PortModuleImp, HasAsyncExtInterrupts,
  HasExtInterruptsModuleImp, HasHierarchicalBusTopology, HasRTCModuleImp, HasResetVectorWire}
import freechips.rocketchip.util.{DontTouch}

// Simple top-level harness to satisfy build system
class GaloisTop(implicit p: Parameters) extends Module with DontTouch {
  val system = Module(LazyModule(new GaloisSystem).module)
  val io =  IO(new Bundle {})
  system.tieOffInterrupts()
  system.mem_axi4.get.getElements.foreach(_ := DontCare)
  system.mmio_axi4.getElements.foreach(_ := DontCare)
  system.debug.get.systemjtag.get.getElements.foreach(_ := DontCare)
  system.traceout.ready := DontCare
}

// GaloisSystem overrides certain components that are not used (BootROM) and unifies naming with GFE
class GaloisSystem(implicit p: Parameters) extends Subsystem
  with HasHierarchicalBusTopology
  with HasAsyncExtInterrupts
  with CanHaveMasterAXI4MemPort
  with CanHaveMasterAXI4MMIOPort
  with CanHaveSlaveAXI4Port
{
  override lazy val module = new GaloisSystemModule(this)
}

class GaloisSystemModule[+L <: GaloisSystem](_outer: L) extends SubsystemModuleImp(_outer)
  with HasRTCModuleImp
  with HasExtInterruptsModuleImp
  with CanHaveMasterAXI4MemPortModuleImp
  with CanHaveMasterAXI4MMIOPortModuleImp
  with CanHaveSlaveAXI4PortModuleImp
  with HasGFEBootROMModuleImp
  with DontTouch {
  val traceout = IO(Decoupled(new TraceVector))
  traceout.valid := true.B
}

class TraceVector extends Bundle {
  val vec   = Vec(72, UInt(8.W))
  val count = UInt(7.W)
}

trait HasGFEBootROMModuleImp extends LazyModuleImp
  with HasResetVectorWire {
  private val params = p(BootROMParams)
  global_reset_vector := params.hang.U
}

class P2FPGAConfig extends Config(
  //new WithXilinxJtag ++ // NOTE: Currently incompatible with Xilinx JTAG for VCU118
    new P2Config
)

class P2Config extends Config(
  new chipyard.config.WithGFEBootROM() ++
  new ssith.WithSSITHMemPort() ++
  new ssith.WithSSITHMMIOPort() ++
  new chipyard.config.WithGFEClint ++
  new freechips.rocketchip.subsystem.WithJtagDTM ++ // NOTE: Currently incompatible with Xilinx JTAG for VCU118
  new freechips.rocketchip.subsystem.WithNoSlavePort ++
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++
  new freechips.rocketchip.subsystem.WithNExtTopInterrupts(16) ++
  new freechips.rocketchip.subsystem.WithL1ICacheSets(32) ++
  new freechips.rocketchip.subsystem.WithL1DCacheSets(32) ++
  new freechips.rocketchip.subsystem.WithNBigCores(1) ++
  new freechips.rocketchip.subsystem.WithEdgeDataBits(64) ++
  new freechips.rocketchip.subsystem.WithDTS("galois,rocketchip-p2", Nil) ++
  new freechips.rocketchip.subsystem.WithTimebase(BigInt(100000000)) ++ // 100 MHz - Sets RTC tick to match global clock rate
  new freechips.rocketchip.system.BaseConfig
)