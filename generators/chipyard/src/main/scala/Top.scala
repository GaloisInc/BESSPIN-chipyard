package chipyard

import chisel3._

import freechips.rocketchip.subsystem._
import freechips.rocketchip.system._
import freechips.rocketchip.config.Parameters
import freechips.rocketchip.devices.tilelink._

// ------------------------------------
// BOOM and/or Rocket Top Level Systems
// ------------------------------------

// DOC include start: Top
class Top(implicit p: Parameters) extends System
  with testchipip.CanHaveTraceIO // Enables optionally adding trace IO
  with testchipip.CanHaveBackingScratchpad // Enables optionally adding a backing scratchpad
  with chipyard.CanHavePeripheryBlockDeviceSSITH // Enables optionally adding the block device, SSITH-edition to fix address
  with testchipip.CanHavePeripherySerial // Enables optionally adding the TSI serial-adapter and port
  with sifive.blocks.devices.uart.HasPeripheryUART // Enables optionally adding the sifive UART
  with sifive.blocks.devices.gpio.HasPeripheryGPIO // Enables optionally adding the sifive GPIOs
  with chipyard.CanHavePeripheryIceNICSSITH // Enables optionally adding the IceNIC for FireSim, SSITH-edition to fix address
  with chipyard.example.CanHavePeripheryInitZero // Enables optionally adding the initzero example widget
  with chipyard.example.CanHavePeripheryGCD // Enables optionally adding the GCD example widget
  with chipyard.CanHaveChosenDTSEntry // Adds a "chosen" entry to DTS
{
  override lazy val module = new TopModule(this)
}

class TopModule[+L <: Top](l: L) extends SystemModule(l)
  with testchipip.CanHaveTraceIOModuleImp
  with chipyard.CanHavePeripheryBlockDeviceSSITHModuleImp
  with testchipip.CanHavePeripherySerialModuleImp
  with sifive.blocks.devices.uart.HasPeripheryUARTModuleImp
  with sifive.blocks.devices.gpio.HasPeripheryGPIOModuleImp
  with chipyard.CanHavePeripheryIceNICSSITHModuleImp
  with chipyard.example.CanHavePeripheryGCDModuleImp
  with freechips.rocketchip.util.DontTouch
// DOC include end: Top
