package chipyard.config

import java.io.File

import chisel3._
import chisel3.util.log2Up
import freechips.rocketchip.config.{Config, Field, Parameters}
import freechips.rocketchip.subsystem.{CacheBlockBytes, ExtMem, MasterPortParams, MemoryBusKey, MemoryPortParams, RocketTilesKey, SystemBusKey, WithNBigCores, WithNMemoryChannels, WithRV32, WithRoccExample}
import freechips.rocketchip.diplomacy.{LazyModule, ValName}
import freechips.rocketchip.devices.tilelink.{BootROMParams, CLINTKey, CLINTParams}
import freechips.rocketchip.devices.debug.Debug
import freechips.rocketchip.tile.{BuildRoCC, LazyRoCC, MaxHartIdBits, RocketTileParams, TileKey, XLen}
import freechips.rocketchip.rocket.{DCacheParams, ICacheParams, MulDivParams, RocketCoreParams}
import freechips.rocketchip.util.AsyncResetReg
import boom.common.BoomTilesKey
import testchipip._
import hwacha.Hwacha
import sifive.blocks.devices.gpio._
import sifive.blocks.devices.uart._
import chipyard.BuildTop

/**
 * TODO: Why do we need this?
 */
object ConfigValName {
  implicit val valName = ValName("TestHarness")
}
import ConfigValName._

// -----------------------
// Common Config Fragments
// -----------------------

class WithBootROM extends Config((site, here, up) => {
  case BootROMParams => BootROMParams(
    contentFileName = s"./bootrom/bootrom.rv${site(XLen)}.img")
})

class WithSSITHBlackBoxBootROM extends Config((site, here, up) => {
  case BootROMParams => {
    val chipyardBootROM = new File(s"./bootrom/bootrom.ssithblackbox.rv${site(XLen)}.img")
    val firesimBootROM = new File(s"./target-rtl/chipyard/bootrom/bootrom.ssithblackbox.rv${site(XLen)}.img")

    val bootROMPath = if (chipyardBootROM.exists()) {
      chipyardBootROM.getAbsolutePath()
    } else {
      firesimBootROM.getAbsolutePath()
    }
    BootROMParams(0x70000000L, hang = 0x70000000L, contentFileName = bootROMPath)
  }
})

class WithCloudGFEBootROM extends Config((site, here, up) => {
  case BootROMParams => {
    val chipyardBootROM = new File(s"./bootrom/bootrom.cloudgfe.rv${site(XLen)}.img")
    val firesimBootROM = new File(s"./target-rtl/chipyard/bootrom/bootrom.cloudgfe.rv${site(XLen)}.img")

    val bootROMPath = if (chipyardBootROM.exists()) {
      chipyardBootROM.getAbsolutePath()
    } else {
      firesimBootROM.getAbsolutePath()
    }
    BootROMParams(0x70000000L, hang = 0x70000000L, contentFileName = bootROMPath)
  }
})

class WithGFEClint extends Config((site, here, up) => {
  case CLINTKey => Some(CLINTParams(0x10000000))
})

class WithGFEMem extends Config((site, here, up) => {
  case ExtMem => Some(MemoryPortParams(MasterPortParams(
    base = 0x80000000L,
    size = 0x80000000L,
    beatBytes = site(MemoryBusKey).beatBytes,
    idBits = 4), 1))
})

// DOC include start: gpio config fragment
class WithGPIO extends Config((site, here, up) => {
  case PeripheryGPIOKey => Seq(
    GPIOParams(address = 0x62330000, width = 4, includeIOF = false))
})
// DOC include end: gpio config fragment

class WithUART extends Config((site, here, up) => {
  case PeripheryUARTKey => Seq(
    UARTParams(address = 0x62300000L, nTxEntries = 256, nRxEntries = 256))
})

class WithNoGPIO extends Config((site, here, up) => {
  case PeripheryGPIOKey => Seq()
})

class WithL2TLBs(entries: Int) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey) map (tile => tile.copy(
    core = tile.core.copy(nL2TLBEntries = entries)
  ))
  case BoomTilesKey => up(BoomTilesKey) map (tile => tile.copy(
    core = tile.core.copy(nL2TLBEntries = entries)
  ))
})

class WithTracegenTop extends Config((site, here, up) => {
  case BuildTop => (p: Parameters) => Module(LazyModule(new tracegen.TraceGenSystem()(p)).suggestName("Top").module)
})


class WithRenumberHarts(rocketFirst: Boolean = false) extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site).zipWithIndex map { case (r, i) =>
    r.copy(hartId = i + (if(rocketFirst) 0 else up(BoomTilesKey, site).length))
  }
  case BoomTilesKey => up(BoomTilesKey, site).zipWithIndex map { case (b, i) =>
    b.copy(hartId = i + (if(rocketFirst) up(RocketTilesKey, site).length else 0))
  }
  case MaxHartIdBits => log2Up(up(BoomTilesKey, site).size + up(RocketTilesKey, site).size)
})



// ------------------
// Multi-RoCC Support
// ------------------

/**
 * Map from a hartId to a particular RoCC accelerator
 */
case object MultiRoCCKey extends Field[Map[Int, Seq[Parameters => LazyRoCC]]](Map.empty[Int, Seq[Parameters => LazyRoCC]])

/**
 * Config fragment to enable different RoCCs based on the hartId
 */
class WithMultiRoCC extends Config((site, here, up) => {
  case BuildRoCC => site(MultiRoCCKey).getOrElse(site(TileKey).hartId, Nil)
})

/**
 * Config fragment to add Hwachas to cores based on hart
 *
 * For ex:
 *   Core 0, 1, 2, 3 have been defined earlier
 *     with hartIds of 0, 1, 2, 3 respectively
 *   And you call WithMultiRoCCHwacha(0,1)
 *   Then Core 0 and 1 will get a Hwacha
 *
 * @param harts harts to specify which will get a Hwacha
 */
class WithMultiRoCCHwacha(harts: Int*) extends Config((site, here, up) => {
  case MultiRoCCKey => {
    require(harts.max <= ((up(RocketTilesKey, site).length + up(BoomTilesKey, site).length) - 1))
    up(MultiRoCCKey, site) ++ harts.distinct.map{ i =>
      (i -> Seq((p: Parameters) => {
        LazyModule(new Hwacha()(p)).suggestName("hwacha")
      }))
    }
  }
})


/**
 * Config fragment to add a small Rocket core to the system as a "control" core.
 * Used as an example of a PMU core.
 */
class WithControlCore extends Config((site, here, up) => {
  case RocketTilesKey => up(RocketTilesKey, site) :+
    RocketTileParams(
      core = RocketCoreParams(
        useVM = false,
        fpu = None,
        mulDiv = Some(MulDivParams(mulUnroll = 8))),
      btb = None,
      dcache = Some(DCacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBEntries = 4,
        nMSHRs = 0,
        blockBytes = site(CacheBlockBytes))),
      icache = Some(ICacheParams(
        rowBits = site(SystemBusKey).beatBits,
        nSets = 64,
        nWays = 1,
        nTLBEntries = 4,
        blockBytes = site(CacheBlockBytes))),
      hartId = up(RocketTilesKey, site).size + up(BoomTilesKey, site).size
    )
  case MaxHartIdBits => log2Up(up(RocketTilesKey, site).size + up(BoomTilesKey, site).size + 1)
})
