package ssith

import chipsalliance.rocketchip.config.{Config, Field, Parameters}
import chisel3._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.devices.tilelink.CanHavePeripheryPLIC
import freechips.rocketchip.regmapper.{RegField, RegFieldDesc}
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.tile.BaseTile
import freechips.rocketchip.tilelink.{TLFragmenter, TLRegisterNode}

// Very simple memory mapped module that converts a write to an interrupt. Can be used to replace
// CLINT in FireSim/Chipyard simulations for reset functionality.

class DebugBridgeStatus extends Bundle {
  val connected = Input(Bool())
  val startSignal = Output(Bool())
}

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
    val io = IO(new Bundle {
      val debugStatus = new DebugBridgeStatus
    })
    val intReg = RegInit(0.U(1.W))
    val dbgConnectedReg = RegInit(false.B)

    val (intnode_out, _) = intnode.out.unzip
    intnode_out.head(0) := intReg.asBool() && !dbgConnectedReg

    dbgConnectedReg := io.debugStatus.connected
    io.debugStatus.startSignal := intReg.asBool()

    node.regmap(0 -> Seq(RegField(1, intReg, RegFieldDesc(s"int_0", s"Interrupt 0", reset=Some(0)))),
      0x4 -> Seq(RegField.r(1, dbgConnectedReg, RegFieldDesc("dbgconnected", "Debug Bridge Connected", reset=Some(0)))))
  }
}

case object MMIntDeviceKey extends Field[Option[MMIntDeviceParams]](None)

case class MMIntDeviceParams(address: BigInt, exposeTopIO: Boolean = false)

class WithMMIntDevice(address: BigInt) extends Config((site, here, up) => {
  case MMIntDeviceKey => Some(MMIntDeviceParams(address))
})

trait CanHavePeripheryMMIntDevice { this: BaseSubsystem with CanHavePeripheryPLIC =>
  // Create memory mapped interrupt device
  val mmint = p(MMIntDeviceKey).map { mmi =>
    val mmint = LazyModule(new MMInt(mmi.address, cbus.beatBytes))
    mmint.node := cbus.coupleTo("coupler_to_mmint") { TLFragmenter(cbus) := _ }
    ibus.fromSync := mmint.intnode
    mmint
  }
}

trait CanHavePeripheryMMIntDeviceImp extends LazyModuleImp {
  val outer: CanHavePeripheryMMIntDevice
  val mmint_io = outer.mmint.map {m => {
    m.module.io.debugStatus.connected := false.B
    if (p(MMIntDeviceKey).get.exposeTopIO) {
      val io = IO(new DebugBridgeStatus)
      io <> m.module.io.debugStatus
      Some(io)
    } else None
  }}
}