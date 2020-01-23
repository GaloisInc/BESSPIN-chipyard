
package bluespec.ports

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy.LazyModuleImp
import freechips.rocketchip.tile._
import freechips.rocketchip.util._

import bluespec.trace._
import bluespec.system._
import bluespec.system.{GFESystem, GFESubsystem}
import boom.common._
import boom.util.Compactor

trait CanHaveTracePort { this: GFESubsystem =>
  implicit val p: Parameters
}

trait HasTraceBundle {
  implicit val p: Parameters
  val traceout: DecoupledIO[TraceVector]
}

trait CanHaveTracePortModuleImp extends LazyModuleImp with HasTraceBundle {
  this: GFESubsystemModuleImp[GFESystem] =>
  val outer: CanHaveTracePort
  val traceout = IO(Decoupled(new TraceVector))

  val compactor = Module(new Compactor(144, traceout.bits.vec.length, UInt(8.W)))
  val q = Module(new Queue(Vec(compactor.io.out.length, Valid(UInt(8.W))), 128))

  val c0 = Mux(encoder(0).io.output.valid, encoder(0).io.output.bits.count, 0.U)
  val c1 = Mux(encoder(1).io.output.valid, encoder(1).io.output.bits.count, 0.U)

  val v0 = ~(0.U(72.W)) >> (72.U - c0)
  val v1 = ~(0.U(72.W)) >> (72.U - c1)

  compactor.io.in.slice(0, 72) zip encoder(0).io.output.bits.vec zip v0.asBools foreach {case ((a,b), c) => a.bits := b; a.valid := c }

  when (encoder(0).io.output.bits.count + encoder(1).io.output.bits.count <=
	traceout.bits.vec.length.asUInt) {
    compactor.io.in.slice(72, 144) zip encoder(1).io.output.bits.vec zip v1.asBools foreach {case ((a,b), c) => a.bits := b; a.valid := c }
  }
  .otherwise {
    compactor.io.in.slice(72, 144) foreach {case (a) => a.bits := 0.U; a.valid := false.B }
    printf("trace data dropped\n");
  }

  encoder map {_.io.output.ready := q.io.enq.ready}

  q.io.enq.valid := (c0 > 0.U) || (c1 > 0.U)

  q.io.enq.bits zip compactor.io.out foreach {case (a,b) =>
    a.valid := b.valid
    a.bits := b.bits
    b.ready := q.io.enq.ready
  }

  q.io.deq.ready := traceout.ready
  traceout.valid := q.io.deq.valid
  traceout.bits.count := PopCount(q.io.deq.bits map {_.valid})
  traceout.bits.vec zip q.io.deq.bits foreach {case (a,b) => a := b.bits}

  val stall = RegInit(false.B)

  when (stall && (q.io.count === 0.U)) {
    stall := false.B
  }
  .elsewhen (!stall && q.io.count >= (q.io.entries - 64).asUInt) {
    stall := true.B
  }
  .otherwise {
    stall := stall
  }

  encoder map {_.io.stall := stall}
}
