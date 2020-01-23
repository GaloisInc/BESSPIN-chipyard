package bluespec.system

import chisel3._
import chisel3.util._
import chisel3.internal.sourceinfo.{SourceInfo}
import chisel3.util.experimental.BoringUtils

import freechips.rocketchip.config.{Field, Parameters}
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.devices.debug.{HasPeripheryDebug, HasPeripheryDebugModuleImp}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.model.{OMInterrupt}
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{RocketTileLogicalTreeNode, LogicalModuleTree}
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.amba.axi4._

import boom.common.{BoomTile, BoomTilesKey, BoomCrossingKey, BoomTileParams}

import bluespec.trace._
import bluespec.ports._
import utilities._

class GFESubsystem(implicit p: Parameters) extends BaseSubsystem
  with HasBoomAndRocketTiles
{
  override lazy val module = new GFESubsystemModuleImp(this)

  def getOMInterruptDevice(resourceBindingsMap: ResourceBindingsMap): Seq[OMInterrupt] = Nil
}

class GFESubsystemModuleImp[+L <: GFESubsystem](_outer: L) extends BaseSubsystemModuleImp(_outer)
  with HasResetVectorWire
  with HasBoomAndRocketTilesModuleImp
{
  tile_inputs.zip(outer.hartIdList).foreach { case(wire, i) =>
    wire.hartid := i.U
    wire.reset_vector := global_reset_vector
  }

  // create file with boom params
  ElaborationArtefacts.add("""core.config""", outer.tiles.map(x => x.module.toString).mkString("\n"))

  val retireWidth: Int = outer.tiles(0).p(TileKey).core.retireWidth
  val preencoder = Seq.fill(retireWidth) { Module(new TracePreEncoder()(outer.tiles(0).p)) }
  val encoder = Seq.fill(retireWidth) { Module(new TraceEncoder()(outer.tiles(0).p)) }

  require(outer.tiles.length == 1, "Tandem verification is currently only supported on single tile systems!")

  for (i <- 0 to (retireWidth-1)) {
    encoder(i).io.input <> preencoder(i).io.trace_output
    encoder(i).io.output.ready := false.B
  }

  outer.tiles.map {
    case b: BoomTile => {
      val core = b.module.core
      for (i <- 0 to (retireWidth-1)) {
	BoringUtils.bore(core.rob.io.commit.valids(i), Seq(preencoder(i).commit_valid))
	BoringUtils.bore(core.rob.io.commit.uops(i).debug_inst, Seq(preencoder(i).commit_inst))
	BoringUtils.bore(core.rob.io.commit.uops(i).is_rvc, Seq(preencoder(i).commit_is_rvc))
	BoringUtils.bore(core.rob.io.commit.uops(i).debug_wdata, Seq(preencoder(i).commit_wdata))
	BoringUtils.bore(core.rob.io.commit.uops(i).ldst, Seq(preencoder(i).commit_ldst))
	BoringUtils.bore(core.rob.io.commit.uops(i).dst_rtype, Seq(preencoder(i).commit_dst_rtype))
	BoringUtils.bore(core.rob.io.commit.uops(i).is_br_or_jmp, Seq(preencoder(i).is_br_or_jmp))
	BoringUtils.bore(core.rob.io.commit.uops(i).taken, Seq(preencoder(i).taken))
	BoringUtils.bore(core.rob.io.commit.uops(i).bj_addr, Seq(preencoder(i).bj_addr))

	BoringUtils.bore(core.rob.io.commit.fflags.valid, Seq(preencoder(i).commit_fflags_valid))
	BoringUtils.bore(core.rob.io.commit.fflags.bits, Seq(preencoder(i).commit_fflags))
	BoringUtils.bore(core.csr.csr_trace_waddr, Seq(preencoder(i).csr_addr_p))
	BoringUtils.bore(core.csr.csr_trace_wdata, Seq(preencoder(i).csr_wdata_p))
	BoringUtils.bore(core.csr.csr_wen, Seq(preencoder(i).csr_wen_p))
	BoringUtils.bore(core.csr.io.pc, Seq(preencoder(i).epc_p))
	BoringUtils.bore(core.csr.io.trace(0).exception, Seq(preencoder(i).exception_p))
	BoringUtils.bore(core.csr.io.trace(0).cause, Seq(preencoder(i).cause_p))
	BoringUtils.bore(core.csr.read_mstatus, Seq(preencoder(i).status_p))
	BoringUtils.bore(core.csr.io.trace(0).tval, Seq(preencoder(i).tval_p))
	BoringUtils.bore(core.tval_valid, Seq(preencoder(i).tval_valid_p))
	BoringUtils.bore(core.csr.io.trace(0).priv, Seq(preencoder(i).priv_p))
	BoringUtils.bore(core.csr.io.eret, Seq(preencoder(i).csr_eret_p))
	BoringUtils.bore(core.csr.io.evec, Seq(preencoder(i).csr_evec_p))

	BoringUtils.bore(encoder(0).io.stall, Seq(core.trace_stall))
      }
    }
  }
}
