package ssith

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._

trait HasIntegratedGFEDebug { this: SSITHTile =>
  val debugDevice = new SimpleDevice("debug-controller", Seq("sifive,debug-013","riscv,debug-013")){
    override val alwaysExtended = true
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      val Seq(Binding(_, ResourceAddress(address, perms))) = resources("reg")
      val base_address = address.head.base
      val control_map = AddressSet.misaligned(base_address, 0x1000)
      val extra = Map(
        "reg-names" -> Seq(ResourceString("control")),
        "reg" -> Seq(ResourceAddress(control_map, perms)),
      )
      Description(name, mapping ++ extra)
    }
  }

  val debugIntNode : IntNexusNode = IntNexusNode(
    sourceFn = { _ => IntSourcePortParameters(Seq(IntSourceParameters(1, Seq(Resource(debugDevice, "int"))))) },
    sinkFn   = { _ => IntSinkPortParameters(Seq(IntSinkParameters())) },
    outputRequiresInput = false)

  ResourceBinding {
    Resource(debugDevice, "reg").bind(ResourceAddress(0))
  }

  intInwardNode := debugIntNode
}