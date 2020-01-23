
package example

import chisel3._
import freechips.rocketchip.config.{Parameters, Config}
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.system._
import freechips.rocketchip.tile._
import freechips.rocketchip.tilelink._
import freechips.rocketchip.util._
import freechips.rocketchip.jtag.xilinxAddrs
import boom.common._

// scalastyle:off

class WithXilinxJtag extends Config ((site, here, up) => {
  // Xilinx requires an IR length of 18, special register addresses, and latching TDO on positive edge
  case JtagDTMKey => new JtagDTMConfig(
    idcodeVersion = 0, idcodePartNum = 0, idcodeManufId = 0, debugIdleCycles = 5,
    irLength = 18, tdoOnNegEdge = false, registerAddrs = new xilinxAddrs()
  )
})

class BoomP3FPGAConfig extends Config(
   new WithGFETop ++
   new WithXilinxJtag ++
   new WithGFECLINT ++
   new WithMediumBooms ++
   new WithNBoomCores(1) ++
   new WithoutTLMonitors ++
   new WithNExtTopInterrupts(16) ++
   new WithJtagDTM ++
   new freechips.rocketchip.system.BaseConfig
   )
