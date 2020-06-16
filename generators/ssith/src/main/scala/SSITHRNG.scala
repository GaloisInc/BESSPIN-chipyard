package ssith

import chipsalliance.rocketchip.config.{Config, Field, Parameters}
import chisel3._
import chisel3.util.{DecoupledIO, Queue}
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.devices.tilelink.CanHavePeripheryPLIC
import freechips.rocketchip.regmapper.{RegField, RegFieldDesc, RegReadFn}
import freechips.rocketchip.subsystem.BaseSubsystem
import freechips.rocketchip.tile.BaseTile
import freechips.rocketchip.tilelink.{TLFragmenter, TLRegisterNode}

// Basic implementation of a random number "generator". It is dependent on an external source of
// random numbers.
//
// Loosely implements the ST RNG. It has been designed to work with the Linux driver found here:
// https://github.com/torvalds/linux/blob/9bf9511e3d9f328c03f6f79bfb741c3d18f2f2c0/drivers/char/hw_random/st-rng.c
//

class RNGIO extends Bundle {
  val randIn = Flipped(DecoupledIO(UInt(32.W)))
}

class SSITHRNG(address: BigInt, beatBytes: Int)(implicit p: Parameters) extends LazyModule {

  val device = new SimpleDevice("ssithrng", Seq("st,rng")) {
    override val alwaysExtended = true
  }

  val node: TLRegisterNode = TLRegisterNode(
    address = Seq(AddressSet(address, 0xff)),
    device = device,
    beatBytes = beatBytes)

  lazy val module = new LazyModuleImp(this) {
    val io = IO(new RNGIO)

    val status = RegInit(0.U(8.W))
    val randQueue = Module(new Queue(UInt(32.W), 4))
    randQueue.io.enq.valid := io.randIn.valid
    randQueue.io.enq.bits  := io.randIn.bits
    io.randIn.ready := randQueue.io.enq.ready

    // Bit 5 -> FIFO is full
    // Bit 6 -> FIFO has at least 1 value in it
    status := ((randQueue.io.count === 4.U) << 5).asUInt() | ((randQueue.io.count =/= 0.U) << 6).asUInt() | 0.U(8.W)

    node.regmap(
      0x20 -> Seq(RegField.r(8, status, RegFieldDesc(s"status", s"Status Register", reset=Some(0)))),
      0x24 -> Seq(RegField.r(32, RegReadFn(randQueue.io.deq), RegFieldDesc(s"datareg", s"Random Data Register", reset=Some(0)))))
  }
}

case object SSITHRNGDeviceKey extends Field[Option[BigInt]](None)

class WithSSITHRNGDevice(address: BigInt) extends Config((site, here, up) => {
  case SSITHRNGDeviceKey => Some(address)
})

trait CanHavePeripherySSITHRNGDevice { this: BaseSubsystem =>
  val hwrng = p(SSITHRNGDeviceKey).map { hwrng =>
    val clockNode = new FixedClockResource(s"rng_clock", p(DTSTimebase).toDouble/1000000)
    val SSITHRNG = LazyModule(new SSITHRNG(hwrng, pbus.beatBytes))
    clockNode.bind(SSITHRNG.device)
    SSITHRNG.node := pbus.coupleTo("coupler_to_SSITHRNG") { TLFragmenter(pbus) := _ }
    SSITHRNG
  }
}

trait CanHavePeripherySSITHRNGModuleImp extends LazyModuleImp {
  val outer: CanHavePeripherySSITHRNGDevice

  val hwrngio = p(SSITHRNGDeviceKey).map { hwrng =>
    val io = IO(new RNGIO)
    io.randIn <> outer.hwrng.get.module.io.randIn
    io
  }
}