package ssith

import chisel3._
import chisel3.util._
import chisel3.experimental.{ChiselAnnotation, ExtModule, IntParam, RunFirrtlTransform, StringParam}
import firrtl.transforms.{BlackBoxResourceAnno, BlackBoxSourceHelper}

import scala.collection.mutable.ListBuffer
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{ICacheLogicalTreeNode, LogicalModuleTree, LogicalTreeNode, RocketLogicalTreeNode}
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.RocketCrossingParams
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.debug.DebugModuleKey
import ssith.SSITHCoreType.SSITHCoreType


/**
  * Enable trace port
  */
class WithSSITHEnableTrace extends Config((site, here, up) => {
  case SSITHTilesKey => up(SSITHTilesKey) map (tile => tile.copy(trace = true))
})

/**
  * Makes cacheable region include to/from host addresses.
  * Speeds up operation... at the expense of not being able to use
  * to/fromhost communication unless those lines are evicted from L1.
  */
class WithToFromHostCaching extends Config((site, here, up) => {
  case SSITHTilesKey => up(SSITHTilesKey, site) map { a =>
    a.copy(core = a.core.copy(
      enableToFromHostCaching = true
    ))
  }
})

/**
  * Create multiple copies of a SSITH tile (and thus a core).
  * Override with the default mixins to control all params of the tiles.
  *
  * @param n amount of tiles to duplicate
  */
class WithNSSITHCores(n: Int) extends Config(
  new WithNormalSSITHSys ++
  new WithTimebase(BigInt(50000000)) ++
    new Config((site, here, up) => {
      case SSITHTilesKey => {
        List.tabulate(n)(i => SSITHTileParams(hartId = i))
      }
    })
)

/**
  * Setup default SSITH parameters.
  */
class WithNormalSSITHSys extends Config((site, here, up) => {
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(SSITHTilesKey).size)
})

class WithSSITHMMIOPort extends Config((site, here, up) => {
  case ExtBus => Some(MasterPortParams(
    base = x"2000_0000",
    size = x"6000_0000",
    beatBytes = site(MemoryBusKey).beatBytes,
    idBits = 4))
})

class WithSSITHMemPort extends Config((site, here, up) => {
  case ExtMem => Some(MemoryPortParams(MasterPortParams(
    base = x"8000_0000",
    size = x"8000_0000",
    beatBytes = site(MemoryBusKey).beatBytes,
    idBits = 4), 1))
})

class WithIntegratedPlicClintDebug extends Config((site, here, up) => {
  case PLICKey => None
  case CLINTKey => None
  case DebugModuleKey => None
})

class WithSSITHBootROM extends Config((site, here, up) => {
  case BootROMParams => BootROMParams(address = 0x70000000, hang = 0x70000000,
    contentFileName = s"./bootrom/bootrom.gfemem.rv${site(XLen)}.img")
})

class WithSSITHCoreType(coreType: SSITHCoreType) extends Config((site, here, up) => {
  case SSITHTilesKey => up(SSITHTilesKey) map (tile => tile.copy(coreType = coreType))
})