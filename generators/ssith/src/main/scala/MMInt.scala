package ssith

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.regmapper.{RegField, RegFieldDesc}
import freechips.rocketchip.tile.BaseTile
import freechips.rocketchip.tilelink.TLRegisterNode

// Very simple memory mapped module that converts a write to an interrupt. Can be used to replace
// CLINT in FireSim/Chipyard simulations for reset functionality.

class MMInt(address: BigInt, beatBytes: Int)(implicit p: Parameters) extends LazyModule {

  val device = new SimpleDevice("mmint", Seq("ssith,mmint")) {
    override val alwaysExtended = true
  }

  val node: TLRegisterNode = TLRegisterNode(
    address = Seq(AddressSet(address, 0xff)),
    device = device,
    beatBytes = beatBytes)

  val intnode: IntNexusNode = IntNexusNode(
    sourceFn = { _ => IntSourcePortParameters(Seq(IntSourceParameters(1, Seq(Resource(device, "int"))))) },
    sinkFn = { _ => IntSinkPortParameters(Seq(IntSinkParameters())) },
    outputRequiresInput = false)

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new Bundle {})
    val intReg = RegInit(0.U(1.W))

    val (intnode_out, _) = intnode.out.unzip
    intnode_out.head(0) := intReg.asBool()

    node.regmap(0 -> Seq(RegField(1, intReg, RegFieldDesc(s"int_0", s"Interrupt 0", reset=Some(0)))))
  }
}

trait HasMMIntDevice { this: BaseTile =>
  // Create memory mapped interrupt device
  val mmint = LazyModule(new MMInt(0x2000000, 4))
  connectTLSlave(mmint.node, 4)
  val tsiInterruptNode = IntSinkNode(IntSinkPortSimple())
  tsiInterruptNode := mmint.intnode
}