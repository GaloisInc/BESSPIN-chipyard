package ssith

import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._

trait HasIntegratedGFECLINT { this: SSITHTile =>
  val clintDevice: SimpleDevice = new SimpleDevice("clint", Seq("riscv,clint0")) {
    override val alwaysExtended = true
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      val Seq(Binding(_, ResourceAddress(address, perms))) = resources("reg")
      val base_address = address.head.base
      val control_map = AddressSet.misaligned(base_address, 0x10000)
      val extra = Map(
        "reg-names" -> Seq(ResourceString("control")),
        "reg" -> Seq(ResourceAddress(control_map, perms)),
      )
      Description(name, mapping ++ extra)
    }
  }

  val clintIntNode : IntNexusNode = IntNexusNode(
    sourceFn = { _ => IntSourcePortParameters(Seq(IntSourceParameters(2, Seq(Resource(clintDevice, "int"))))) },
    sinkFn   = { _ => IntSinkPortParameters(Seq(IntSinkParameters())) },
    outputRequiresInput = false)

  ResourceBinding {
    Resource(clintDevice, "reg").bind(ResourceAddress(0x10000000))
  }

  intInwardNode := clintIntNode // Connects both interrupts in one node connection
}