//See LICENSE for license details.

package firesim.firesim

import chisel3._
import chisel3.experimental.annotate
import freechips.rocketchip.config.{Config, Field, Parameters}
import freechips.rocketchip.diplomacy.{LazyModule, ValName}
import freechips.rocketchip.devices.debug.HasPeripheryDebugModuleImp
import freechips.rocketchip.subsystem.CanHaveMasterAXI4MemPortModuleImp
import freechips.rocketchip.tile.RocketTile
import sifive.blocks.devices.uart.HasPeripheryUARTModuleImp
import testchipip.{CanHavePeripheryBlockDeviceModuleImp, CanHavePeripherySerialModuleImp, CanHaveTraceIOModuleImp}
import junctions.{NastiKey, NastiParameters}
import midas.models.{AXI4EdgeSummary, CompleteConfig, FASEDBridge}
import midas.targetutils.MemModelAnnotation
import firesim.bridges._
import firesim.configs.MemModelKey
import tracegen.HasTraceGenTilesModuleImp
import ariane.ArianeTile
import boom.common.BoomTile
import chipyard.iobinders.{ComposeIOBinder, IOBinders, OverrideIOBinder}
import chipyard.HasChipyardTilesModuleImp
import freechips.rocketchip.interrupts.{IntSinkNode, IntSinkPortSimple, IntSourceNode, IntSourcePortSimple}
import icenet.CanHavePeripheryIceNICModuleImp
import ssith._

class WithSerialBridge extends OverrideIOBinder({
  (c, r, s, target: CanHavePeripherySerialModuleImp) => target.serial.map(s => SerialBridge(s)(target.p)).toSeq
})

class WithNICBridge extends OverrideIOBinder({
  (c, r, s, target: CanHavePeripheryIceNICModuleImp) => target.net.map(n => NICBridge(n)(target.p)).toSeq
})

class WithUARTBridge extends OverrideIOBinder({
  (c, r, s, target: HasPeripheryUARTModuleImp) => target.uart.map(u => UARTBridge(u)(target.p)).toSeq
})

class WithBlockDeviceBridge extends OverrideIOBinder({
  (c, r, s, target: CanHavePeripheryBlockDeviceModuleImp) => target.bdev.map(b => BlockDevBridge(b, target.reset.toBool)(target.p)).toSeq
})

class WithRandomBridge extends OverrideIOBinder({
  (c, r, s, target: CanHavePeripherySSITHRNGModuleImp) => target.hwrngio.map(r => RandomBridge(r)(target.p)).toSeq
})

class WithDMIBridge extends OverrideIOBinder({
  (c, r, s, target: HasPeripheryDebugModuleImp) => target.debug.map(db => db.clockeddmi.map(cdmi => {
    implicit val p = target.p
    cdmi.dmiClock := c
    cdmi.dmiReset := r
    target match {
      case t: CanHavePeripheryMMIntDeviceImp => {
        if (t.mmint_io.isDefined) {
          val bridge = DMIBridge(cdmi.dmi, true)
          t.mmint_io.get.get.connected := bridge.io.dbg_connected
          bridge.io.dbg_memloaded := t.mmint_io.get.get.startSignal
          bridge
        } else {
          DMIBridge(cdmi.dmi, false)
        }
      }
      case _ => DMIBridge(cdmi.dmi, false)
    }
  })).toSeq
})

class WithDMIBridgeConfig extends Config((site, here, up) => {
  case MMIntDeviceKey => up(MMIntDeviceKey) map { m => m.copy(exposeTopIO = true) }
})

class WithFASEDBridge extends OverrideIOBinder({
  (c, r, s, t: CanHaveMasterAXI4MemPortModuleImp) => {
    implicit val p = t.p
    (t.mem_axi4 zip t.outer.memAXI4Node).flatMap({ case (io, node) =>
      (io zip node.in).map({ case (axi4Bundle, (_, edge)) =>
        val nastiKey = NastiParameters(axi4Bundle.r.bits.data.getWidth,
                                       axi4Bundle.ar.bits.addr.getWidth,
                                       axi4Bundle.ar.bits.id.getWidth)
        FASEDBridge(axi4Bundle, t.reset.toBool,
          CompleteConfig(p(firesim.configs.MemModelKey), nastiKey, Some(AXI4EdgeSummary(edge))))
      })
    }).toSeq
  }
})

class WithTracerVBridge extends OverrideIOBinder({
  (c, r, s, target: CanHaveTraceIOModuleImp) => target.traceIO.map(t => TracerVBridge(t)(target.p)).toSeq
})

class WithTraceGenBridge extends OverrideIOBinder({
  (c, r, s, target: HasTraceGenTilesModuleImp) => Seq(GroundTestBridge(target.success)(target.p))
})

class WithFireSimMultiCycleRegfile extends ComposeIOBinder({
  (c, r, s, target: HasChipyardTilesModuleImp) => {
    target.outer.tiles.map {
      case r: RocketTile => {
        annotate(MemModelAnnotation(r.module.core.rocketImpl.rf.rf))
        r.module.fpuOpt.foreach(fpu => annotate(MemModelAnnotation(fpu.fpuImpl.regfile)))
      }
      case b: BoomTile => {
        val core = b.module.core
        core.iregfile match {
          case irf: boom.exu.RegisterFileSynthesizable => annotate(MemModelAnnotation(irf.regfile))
          case _ => Nil
        }
        if (core.fp_pipeline != null) core.fp_pipeline.fregfile match {
          case frf: boom.exu.RegisterFileSynthesizable => annotate(MemModelAnnotation(frf.regfile))
          case _ => Nil
        }
      }
      case a: ArianeTile => Nil
      case s: SSITHTile => Nil
    }
    Nil
  }
})



// Shorthand to register all of the provided bridges above
class WithDefaultFireSimBridges extends Config(
  new chipyard.iobinders.WithGPIOTiedOff ++
  //new chipyard.iobinders.WithTiedOffDebug ++
  new chipyard.iobinders.WithTieOffInterrupts ++
  new WithRandomBridge ++
  new WithDMIBridge ++
  new WithDMIBridgeConfig ++
  new WithSerialBridge ++
  new WithNICBridge ++
  new WithUARTBridge ++
  new WithBlockDeviceBridge ++
  new WithFASEDBridge ++
  new WithFireSimMultiCycleRegfile ++
  new WithTracerVBridge
)
