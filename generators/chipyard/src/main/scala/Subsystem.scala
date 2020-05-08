//******************************************************************************
// Copyright (c) 2019 - 2019, The Regents of the University of California (Regents).
// All Rights Reserved. See LICENSE and LICENSE.SiFive for license details.
//------------------------------------------------------------------------------

package chipyard

import chisel3._
import chisel3.internal.sourceinfo.SourceInfo
import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.devices.debug.{HasPeripheryDebug, HasPeripheryDebugModuleImp}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.model.OMInterrupt
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{LogicalModuleTree, RocketTileLogicalTreeNode}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._
import boom.common.{BoomCrossingKey, BoomTile, BoomTileParams, BoomTilesKey}
import ariane.{ArianeCrossingKey, ArianeTile, ArianeTileParams, ArianeTilesKey}
import ssith.{SSITHCrossingKey, SSITHTile, SSITHTileParams, SSITHTilesKey}

trait HasChipyardTiles extends HasTiles
  with CanHavePeripheryPLIC
  with CanHavePeripheryCLINT
  with HasPeripheryDebug
{ this: BaseSubsystem =>

  val module: HasChipyardTilesModuleImp

  protected val rocketTileParams = p(RocketTilesKey)
  protected val boomTileParams = p(BoomTilesKey)
  protected val arianeTileParams = p(ArianeTilesKey)
  protected val ssithTileParams = p(SSITHTilesKey)

  // crossing can either be per tile or global (aka only 1 crossing specified)
  private val rocketCrossings = perTileOrGlobalSetting(p(RocketCrossingKey), rocketTileParams.size)
  private val boomCrossings = perTileOrGlobalSetting(p(BoomCrossingKey), boomTileParams.size)
  private val arianeCrossings = perTileOrGlobalSetting(p(ArianeCrossingKey), arianeTileParams.size)
  private val ssithCrossings = perTileOrGlobalSetting(p(SSITHCrossingKey), ssithTileParams.size)

  val allTilesInfo = (rocketTileParams ++ boomTileParams ++ arianeTileParams ++ ssithTileParams) zip (rocketCrossings ++ boomCrossings ++ arianeCrossings ++ ssithCrossings)

  // Make a tile and wire its nodes into the system,
  // according to the specified type of clock crossing.
  // Note that we also inject new nodes into the tile itself,
  // also based on the crossing type.
  // This MUST be performed in order of hartid
  // There is something weird with registering tile-local interrupt controllers to the CLINT.
  // TODO: investigate why
  val tiles = allTilesInfo.sortWith(_._1.hartId < _._1.hartId).map {
    case (param, crossing) => {
      val (tile, rocketLogicalTree) = param match {
        case r: RocketTileParams => {
          val t = LazyModule(new RocketTile(r, crossing, PriorityMuxHartIdFromSeq(rocketTileParams), logicalTreeNode))
          (t, t.rocketLogicalTree)
        }
        case b: BoomTileParams => {
          val t = LazyModule(new BoomTile(b, crossing, PriorityMuxHartIdFromSeq(boomTileParams), logicalTreeNode))
          (t, t.rocketLogicalTree) // TODO FIX rocketLogicalTree is not a member of the superclass, both child classes define it separately
        }
        case a: ArianeTileParams => {
          val t = LazyModule(new ArianeTile(a, crossing, PriorityMuxHartIdFromSeq(arianeTileParams), logicalTreeNode))
          (t, t.rocketLogicalTree) // TODO FIX rocketLogicalTree is not a member of the superclass, both child classes define it separately
        }
        case s: SSITHTileParams => {
          val t = LazyModule(new SSITHTile(s, crossing, PriorityMuxHartIdFromSeq(ssithTileParams), logicalTreeNode))
          t.plicNode :=* ibus.toPLIC
          (t, t.rocketLogicalTree)
        }
      }
      connectMasterPortsToSBus(tile, crossing)
      connectSlavePortsToCBus(tile, crossing)

      def treeNode: RocketTileLogicalTreeNode = new RocketTileLogicalTreeNode(rocketLogicalTree.getOMInterruptTargets)
      LogicalModuleTree.add(logicalTreeNode, rocketLogicalTree)

      connectInterrupts(tile, debugOpt, clintOpt, plicOpt)

      tile
    }
  }


  def coreMonitorBundles = tiles.map {
    case r: RocketTile => r.module.core.rocketImpl.coreMonitorBundle
    case b: BoomTile => b.module.core.coreMonitorBundle
  }.toList
}

// Have to recreate the Rocket HasTileModuleImp here to avoid creating the unnecessary meipNode
trait HasSSITHTilesModuleImp extends LazyModuleImp {
  val outer: HasTiles

  def resetVectorBits: Int = {
    // Consider using the minimum over all widths, rather than enforcing homogeneity
    val vectors = outer.tiles.map(_.module.constants.reset_vector)
    require(vectors.tail.forall(_.getWidth == vectors.head.getWidth))
    vectors.head.getWidth
  }

  val tile_inputs = outer.tiles.map(_.module.constants)

  val meip = if(outer.meipNode.isDefined && p(SSITHTilesKey).isEmpty) {
    Some(IO(Input(Vec(outer.meipNode.get.out.size, Bool()))))
  } else None

  meip.foreach { m =>
    m.zipWithIndex.foreach{ case (pin, i) =>
      (outer.meipNode.get.out(i)._1)(0) := pin
    }
  }
}

trait HasChipyardTilesModuleImp extends HasSSITHTilesModuleImp
  with HasPeripheryDebugModuleImp
{
  val outer: HasChipyardTiles
}

class Subsystem(implicit p: Parameters) extends BaseSubsystem
  with HasChipyardTiles
{
  override lazy val module = new SubsystemModuleImp(this)

  def getOMInterruptDevice(resourceBindingsMap: ResourceBindingsMap): Seq[OMInterrupt] = Nil
}

class SubsystemModuleImp[+L <: Subsystem](_outer: L) extends BaseSubsystemModuleImp(_outer)
  with HasResetVectorWire
  with HasChipyardTilesModuleImp
{
  tile_inputs.zip(outer.hartIdList).foreach { case(wire, i) =>
    wire.hartid := i.U
    wire.reset_vector := global_reset_vector
  }

  // create file with boom params
  ElaborationArtefacts.add("""core.config""", outer.tiles.map(x => x.module.toString).mkString("\n"))
}
