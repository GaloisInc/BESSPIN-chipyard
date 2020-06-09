package chipyard

import chipsalliance.rocketchip.config.Field
import chisel3._
import chisel3.util.Pipe
import freechips.rocketchip.config.Config
import freechips.rocketchip.diplomacy.{Binding, DTSTimebase, Description, Device, LazyModule, LazyModuleImp, Resource, ResourceAlias, ResourceAnchors, ResourceBinding, ResourceBindings, ResourceInt, ResourceString}
import freechips.rocketchip.subsystem.{BaseSubsystem, PeripheryBusKey, RocketTilesKey, WithTimebase}
import freechips.rocketchip.util.PlusArg
import icenet._
import testchipip.{BlockDeviceController, BlockDeviceIO, BlockDeviceKey, BlockDeviceModel, SimBlockDevice}
import sifive.blocks.devices.uart.TLUART
import ssith.{SSITHTilesKey, WithMMIntDevice}

class SSITHConfig extends SSITHDropInConfig

class SSITHDropInConfig extends Config(
    new chipyard.iobinders.WithSimAXIMem ++                        // drive the master AXI4 memory with a SimAXIMem (SimDRAM has some width issue with Verilator)
    new ssith.WithIntegratedPlicClintDebug ++                      // Removes duplicated PLIC, CLINT, and Debug Module, which are all inside core
    new ssith.WithNSSITHDropInCores(1) ++                          // single SSITH core
    new chipyard.BaseSSITHConfig)                                  // modified "base" system

class BaseSSITHConfig extends Config(
    new chipyard.iobinders.WithUARTAdapter ++                      // display UART with a SimUARTAdapter
    new chipyard.iobinders.WithTieOffInterrupts ++                 // tie off top-level interrupts
    new chipyard.iobinders.WithTiedOffDebug ++                     // tie off debug (since we are using SimSerial for testing)
    new chipyard.iobinders.WithSimSerial ++                        // drive TSI with SimSerial for testing
    new testchipip.WithTSI ++                                      // use testchipip serial offchip link
    new WithMMIntDevice(BigInt(0x2000000)) ++
    new WithSSITHTimebase ++
    new chipyard.config.WithNoGPIO ++                              // no top-level GPIO pins (overrides default set in sifive-blocks)
    new chipyard.config.WithUART ++                                // add a UART
    new ssith.WithSSITHMemPort ++                                  // Change location of main memory
    new chipyard.config.WithCloudGFEBootROM ++                     // Use the SSITH Bootrom to workaround incompatibility issues
    new freechips.rocketchip.subsystem.WithNoMMIOPort ++           // no top-level MMIO master port (overrides default set in rocketchip)
    new freechips.rocketchip.subsystem.WithNoSlavePort ++          // no top-level MMIO slave port (overrides default set in rocketchip)
    new freechips.rocketchip.subsystem.WithInclusiveCache ++       // use Sifive L2 cache
    new freechips.rocketchip.subsystem.WithNExtTopInterrupts(0) ++ // no external interrupts
    new freechips.rocketchip.subsystem.WithDTS("galois,gfe", Nil) ++ // Set model / compat name
    new freechips.rocketchip.system.BaseConfig)                    // "base" rocketchip system

class WithSSITHTimebase(timebase: Option[BigInt] = None) extends Config((site, here, up) => {
    case DTSTimebase => timebase.getOrElse(up(PeripheryBusKey).frequency)
    case SSITHTilesKey => up(SSITHTilesKey, site) map { r =>
        r.copy(core = r.core.copy(bootFreqHz = up(PeripheryBusKey).frequency)) }
    case RocketTilesKey => up(RocketTilesKey, site) map { r =>
        r.copy(core = r.core.copy(bootFreqHz = up(PeripheryBusKey).frequency)) }
})

trait CanHaveChosenDTSEntry {this: BaseSubsystem => {

    val chosen = new Device {
      def describe(resources: ResourceBindings): Description = {
        Description("chosen", Map(
            "bootargs" -> Seq(ResourceString("earlyprintk console=ttySIF0"))) ++
          resources("stdout").zipWithIndex.map { case (Binding(_, value), i) =>
              (s"stdout-path" -> Seq(value))})
      }
    }

    val timebase = new Device {
        def describe(resources: ResourceBindings): Description =
            Description("cpus", Map() ++
              resources("time").zipWithIndex.map { case (Binding(_, value), i) =>
                  (s"timebase-frequency" -> Seq(value))})
    }

    ResourceBinding {
        Resource(timebase, "time").bind(ResourceInt(p(DTSTimebase)))
    }

    this.getChildren.foreach { m =>
      m match {
        case (mod: TLUART) => ResourceBinding {
            Resource(chosen, "stdout").bind(ResourceAlias(mod.device.label))
        }
        case _ => {}
      }
    }
}}
