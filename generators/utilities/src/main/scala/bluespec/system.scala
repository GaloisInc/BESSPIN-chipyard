package bluespec.system

import chisel3._

import freechips.rocketchip.config.{Parameters}
import freechips.rocketchip.subsystem._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.util.{DontTouch}

import bluespec.trace._
import bluespec.ports._

/**
 * Base top with periphery devices and ports, and a BOOM + Rocket subsystem
 */
class GFESystem(implicit p: Parameters) extends GFESubsystem
  with HasHierarchicalBusTopology
  with HasAsyncExtInterrupts
  with CanHaveMasterAXI4MemPort
  with CanHaveMasterAXI4MMIOPort
  with CanHaveSlaveAXI4Port
  with CanHaveTracePort
  with HasPeripheryBootROM
{
  override lazy val module = new GFESystemModule(this)
}

/**
 * Base top module implementation with periphery devices and ports, and a BOOM + Rocket subsystem
 */
class GFESystemModule[+L <: GFESystem](_outer: L) extends GFESubsystemModuleImp(_outer)
  with HasRTCModuleImp
  with HasExtInterruptsModuleImp
  with CanHaveMasterAXI4MemPortModuleImp
  with CanHaveMasterAXI4MMIOPortModuleImp
  with CanHaveSlaveAXI4PortModuleImp
  with CanHaveTracePortModuleImp
  with HasPeripheryBootROMModuleImp
  with DontTouch
