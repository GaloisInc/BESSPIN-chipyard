package ssith

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._

trait HasIntegratedGFEPLIC { this: SSITHTile =>
  val plicDevice: SimpleDevice = new SimpleDevice("interrupt-controller", Seq("riscv,plic0")) {
    override val alwaysExtended = true
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      val Seq(Binding(_, ResourceAddress(address, perms))) = resources("reg")
      val base_address = address.head.base
      val control_map = AddressSet.misaligned(base_address, 0x400000)
      val extra = Map(
        "reg-names" -> Seq(ResourceString("control")),
        "reg" -> Seq(ResourceAddress(control_map, perms)),
        "interrupt-controller" -> Nil,
        "riscv,ndev" -> Seq(ResourceInt(16)),
        "riscv,max-priority" -> Seq(ResourceInt(7)),
        "#interrupt-cells" -> Seq(ResourceInt(1)))
      Description(name, mapping ++ extra)
    }
  }

  val plicNode = IntNexusNode(
    sourceFn = { _ => IntSourcePortParameters(Seq(IntSourceParameters(2, Seq(Resource(plicDevice, "int"))))) },
    sinkFn   = { _ => IntSinkPortParameters(Seq(IntSinkParameters())) },
    outputRequiresInput = false,
    inputRequiresOutput = false)

  ResourceBinding {
    Resource(plicDevice, "reg").bind(ResourceAddress(0xc000000))
  }

  intInwardNode := plicNode // Connects both interrupts in one line

  // Assign all the devices unique ranges
  lazy val sources = plicNode.edges.in.map(_.source)
  lazy val flatSources = (sources zip sources.map(_.num).scanLeft(0)(_+_).init).map {
    case (s, o) => s.sources.map(z => z.copy(range = z.range.offset(o)))
  }.flatten

  def nDevices: Int = plicNode.edges.in.map(_.source.num).sum
  ResourceBinding {
    flatSources.foreach { s => s.resources.foreach { r =>
      // +1 because interrupt 0 is reserved
      (s.range.start until s.range.end).foreach { i => r.bind(plicDevice, ResourceInt(i+1)) }
    } }
  }

  def getSSITHInterrupts() = {
    val interrupts = plicNode.in.map { case (i, e) => i.take(e.source.num) }.flatten.asUInt()
    interrupts
  }
}

trait HasIntegratedGFEPLICModuleImp extends LazyModuleImp {
  val outer: HasIntegratedGFEPLIC

  println(s"Interrupt map (${1} harts ${outer.nDevices} interrupts):")
  outer.flatSources.foreach { s =>
    // +1 because 0 is reserved, +1-1 because the range is half-open
    println(s"  [${s.range.start+1}, ${s.range.end}] => ${s.name}")
  }
  println("")
}