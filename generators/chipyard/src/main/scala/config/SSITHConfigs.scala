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

class SSITHConfig extends Config(
    new chipyard.iobinders.WithUARTAdapter ++                      // display UART with a SimUARTAdapter
    new chipyard.iobinders.WithTieOffInterrupts ++                 // tie off top-level interrupts
    new chipyard.iobinders.WithSimAXIMem ++                        // drive the master AXI4 memory with a SimAXIMem (SimDRAM has some width issue with Verilator)
    new chipyard.iobinders.WithTiedOffDebug ++                     // tie off debug (since we are using SimSerial for testing)
    new chipyard.iobinders.WithSimSerial ++                        // drive TSI with SimSerial for testing
    new testchipip.WithTSI ++                                      // use testchipip serial offchip link
    new WithIceBlockAddress(BigInt(0x40015000)) ++
    new WithIceNICAddress(BigInt(0x62100000)) ++
    new WithMMIntDevice(BigInt(0x2000000)) ++
    new WithSSITHTimebase ++
    new chipyard.config.WithNoGPIO ++                              // no top-level GPIO pins (overrides default set in sifive-blocks)
    new chipyard.config.WithUART ++                                // add a UART
    new ssith.WithSSITHMemPort ++                                  // Change location of main memory
    new chipyard.config.WithCloudGFEBootROM ++                     // Use the SSITH Bootrom to workaround incompatibility issues
    new ssith.WithIntegratedPlicClintDebug ++                      // Removes duplicated PLIC, CLINT, and Debug Module, which are all inside core
    new freechips.rocketchip.subsystem.WithNoMMIOPort ++           // no top-level MMIO master port (overrides default set in rocketchip)
    new freechips.rocketchip.subsystem.WithNoSlavePort ++          // no top-level MMIO slave port (overrides default set in rocketchip)
    new freechips.rocketchip.subsystem.WithInclusiveCache ++       // use Sifive L2 cache
    new freechips.rocketchip.subsystem.WithNExtTopInterrupts(0) ++ // no external interrupts
    new ssith.WithNSSITHCores(1) ++                                // single SSITH core
    new freechips.rocketchip.system.BaseConfig)                    // "base" rocketchip system

class WithSSITHTimebase(timebase: Option[BigInt] = None) extends Config((site, here, up) => {
    case DTSTimebase => timebase.getOrElse(up(PeripheryBusKey).frequency)
    case SSITHTilesKey => up(SSITHTilesKey, site) map { r =>
        r.copy(core = r.core.copy(bootFreqHz = up(PeripheryBusKey).frequency)) }
    case RocketTilesKey => up(RocketTilesKey, site) map { r =>
        r.copy(core = r.core.copy(bootFreqHz = up(PeripheryBusKey).frequency)) }
})

// There's no good way to change the block or net device address, so override the traits here

case object IceBlockAddress extends Field[Option[BigInt]](None)

class WithIceBlockAddress(address: BigInt) extends Config((site, here, up) => {
    case IceBlockAddress => Some(address)
})

trait CanHavePeripheryBlockDeviceSSITH { this: BaseSubsystem =>
    private val address = p(IceBlockAddress).getOrElse(BigInt(0x10015000))
    private val portName = "blkdev-controller"

    val controller = p(BlockDeviceKey).map { _ =>
        val c = LazyModule(new BlockDeviceController(
            address, pbus.beatBytes))

        pbus.toVariableWidthSlave(Some(portName))  { c.mmio }
        fbus.fromPort(Some(portName))() :=* c.mem
        ibus.fromSync := c.intnode
        c
    }
}

trait CanHavePeripheryBlockDeviceSSITHModuleImp extends LazyModuleImp {
    val outer: CanHavePeripheryBlockDeviceSSITH

    val bdev = p(BlockDeviceKey).map { _ =>
        val io = IO(new BlockDeviceIO)
        io <> outer.controller.get.module.io.bdev
        io
    }

    def connectSimBlockDevice(clock: Clock, reset: Bool) {
        val sim = Module(new SimBlockDevice)
        sim.io.clock := clock
        sim.io.reset := reset
        sim.io.bdev <> bdev.get
    }

    def connectBlockDeviceModel() {
        val model = Module(new BlockDeviceModel(16))
        model.io <> bdev.get
    }
}

case object IceNICAddress extends Field[Option[BigInt]](None)

class WithIceNICAddress(address: BigInt) extends Config((site, here, up) => {
    case IceNICAddress => Some(address)
})

trait CanHavePeripheryIceNICSSITH { this: BaseSubsystem =>
    private val address = p(IceNICAddress).getOrElse(BigInt(0x10016000))
    private val portName = "Ice-NIC"

    val icenicOpt = p(NICKey).map { params =>
        val icenic = LazyModule(new IceNIC(address, pbus.beatBytes))
        pbus.toVariableWidthSlave(Some(portName)) { icenic.mmionode }
        fbus.fromPort(Some(portName))() :=* icenic.dmanode
        ibus.fromSync := icenic.intnode
        icenic
    }
}

trait CanHavePeripheryIceNICSSITHModuleImp extends LazyModuleImp {
    val outer: CanHavePeripheryIceNICSSITH

    val net = outer.icenicOpt.map { icenic =>
        val nicio = IO(new NICIOvonly)
        nicio <> NICIOvonly(icenic.module.io.ext)
        nicio
    }

    import PauseConsts.BT_PER_QUANTA

    val nicConf = p(NICKey).getOrElse(NICConfig())
    private val packetWords = nicConf.packetMaxBytes / IceNetConsts.NET_IF_BYTES
    private val packetQuanta = (nicConf.packetMaxBytes * 8) / BT_PER_QUANTA

    def connectNicLoopback(qDepth: Int = 4 * packetWords, latency: Int = 10) {
        val netio = net.get
        netio.macAddr := PlusArg("macaddr")
        netio.rlimit.inc := PlusArg("rlimit-inc", 1)
        netio.rlimit.period := PlusArg("rlimit-period", 1)
        netio.rlimit.size := PlusArg("rlimit-size", 8)
        netio.pauser.threshold := PlusArg("pauser-threshold", 2 * packetWords + latency)
        netio.pauser.quanta := PlusArg("pauser-quanta", 2 * packetQuanta)
        netio.pauser.refresh := PlusArg("pauser-refresh", packetWords)

        if (nicConf.usePauser) {
            val pauser = Module(new PauserComplex(qDepth))
            pauser.io.ext.flipConnect(NetDelay(NICIO(netio), latency))
            pauser.io.int.out <> pauser.io.int.in
            pauser.io.macAddr := netio.macAddr + (1 << 40).U
            pauser.io.settings := netio.pauser
        } else {

            netio.in := Pipe(netio.out, latency)
        }
        netio.in.bits.keep := IceNetConsts.NET_FULL_KEEP
    }

    def connectSimNetwork(clock: Clock, reset: Bool) {
        val sim = Module(new SimNetwork)
        sim.io.clock := clock
        sim.io.reset := reset
        sim.io.net <> net.get
    }
}

trait CanHaveChosenDTSEntry {this: BaseSubsystem => {

    val chosen = new Device {
      def describe(resources: ResourceBindings): Description = {
        Description("chosen", Map(
            "bootargs" -> Seq(ResourceString("earlyprintk console=hvc0 earlycon=sbi"))) ++
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