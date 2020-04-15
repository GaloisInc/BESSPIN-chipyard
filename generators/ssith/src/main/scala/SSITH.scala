package ssith

import chisel3._
import chisel3.util._
import chisel3.experimental.{ExtModule, IntParam, StringParam}

import scala.collection.mutable.ListBuffer
import freechips.rocketchip.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.tilelink._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{ICacheLogicalTreeNode, LogicalModuleTree, LogicalTreeNode, RocketLogicalTreeNode}
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.RocketCrossingParams
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.amba.axi4._


case object SSITHCrossingKey extends Field[Seq[RocketCrossingParams]](List(RocketCrossingParams()))

/**
  * Enable trace port
  */
class WithSSITHEnableTrace extends Config((site, here, up) => {
  case SSITHTilesKey => up(SSITHTilesKey) map (tile => tile.copy(trace = true))
})

/**
  * Makes cacheable region include to/from host addresses.
  * Speeds up operation... at the expense of not being able to use
  * to/fromhost communication unless those lines are evicted from L1.
  */
class WithToFromHostCaching extends Config((site, here, up) => {
  case SSITHTilesKey => up(SSITHTilesKey, site) map { a =>
    a.copy(core = a.core.copy(
      enableToFromHostCaching = true
    ))
  }
})

/**
  * Create multiple copies of a SSITH tile (and thus a core).
  * Override with the default mixins to control all params of the tiles.
  *
  * @param n amount of tiles to duplicate
  */
class WithNSSITHCores(n: Int) extends Config(
  new WithNormalSSITHSys ++
    new Config((site, here, up) => {
      case SSITHTilesKey => {
        List.tabulate(n)(i => SSITHTileParams(hartId = i))
      }
    })
)

/**
  * Setup default SSITH parameters.
  */
class WithNormalSSITHSys extends Config((site, here, up) => {
  case SystemBusKey => up(SystemBusKey, site).copy(beatBytes = 8)
  case XLen => 64
  case MaxHartIdBits => log2Up(site(SSITHTilesKey).size)
})

class WithSSITHMMIOPort extends Config((site, here, up) => {
  case ExtBus => Some(MasterPortParams(
    base = x"6000_0000",
    size = x"2000_0000",
    beatBytes = site(MemoryBusKey).beatBytes,
    idBits = 4))
})

class WithSSITHMemPort extends Config((site, here, up) => {
  case ExtMem => Some(MemoryPortParams(MasterPortParams(
    base = x"C000_0000",
    size = x"1000_0000",
    beatBytes = site(MemoryBusKey).beatBytes,
    idBits = 4), 1))
})

class WithSSITHBootROM extends Config((site, here, up) => {
  case BootROMParams => BootROMParams(address = 0x70000000,
    contentFileName = s"./bootrom/bootrom.ssith.rv${site(XLen)}.img")
})

case object SSITHTilesKey extends Field[Seq[SSITHTileParams]](Nil)

case class SSITHCoreParams(
                             bootFreqHz: BigInt = BigInt(50000000),
                             rasEntries: Int = 4,
                             btbEntries: Int = 16,
                             bhtEntries: Int = 16,
                             enableToFromHostCaching: Boolean = false,
                           ) extends CoreParams {
  /* DO NOT CHANGE BELOW THIS */
  val useVM: Boolean = true
  val useUser: Boolean = true
  val useDebug: Boolean = true
  val useAtomics: Boolean = true
  val useAtomicsOnlyForIO: Boolean = false // copied from Rocket
  val useCompressed: Boolean = true
  override val useVector: Boolean = false
  val useSCIE: Boolean = false
  val useRVE: Boolean = false
  val mulDiv: Option[MulDivParams] = Some(MulDivParams()) // copied from Rocket
  val fpu: Option[FPUParams] = Some(FPUParams()) // copied fma latencies from Rocket
  val nLocalInterrupts: Int = 0
  val nPMPs: Int = 0 // TODO: Check
  val pmpGranularity: Int = 4 // copied from Rocket
  val nBreakpoints: Int = 0 // TODO: Check
  val useBPWatch: Boolean = false
  val nPerfCounters: Int = 29
  val haveBasicCounters: Boolean = true
  val haveFSDirty: Boolean = false
  val misaWritable: Boolean = false
  val haveCFlush: Boolean = false
  val nL2TLBEntries: Int = 512 // copied from Rocket
  val mtvecInit: Option[BigInt] = Some(BigInt(0)) // copied from Rocket
  val mtvecWritable: Boolean = true // copied from Rocket
  val instBits: Int = if (useCompressed) 16 else 32
  val lrscCycles: Int = 80 // copied from Rocket
  val decodeWidth: Int = 1 // TODO: Check
  val fetchWidth: Int = 1 // TODO: Check
  val retireWidth: Int = 2
}

// TODO: Many parameters are incorrect (ex BTBParams, DCacheParams, ICacheParam)... figure out what to put in DTB
case class SSITHTileParams(
                             name: Option[String] = Some("ssith_tile"),
                             hartId: Int = 0,
                             beuAddr: Option[BigInt] = None,
                             blockerCtrlAddr: Option[BigInt] = None,
                             btb: Option[BTBParams] = Some(BTBParams()),
                             core: SSITHCoreParams = SSITHCoreParams(),
                             dcache: Option[DCacheParams] = Some(DCacheParams()),
                             icache: Option[ICacheParams] = Some(ICacheParams()),
                             boundaryBuffers: Boolean = false,
                             trace: Boolean = false
                           ) extends TileParams

class SSITHTile(
                  val SSITHParams: SSITHTileParams,
                  crossing: ClockCrossingType,
                  lookup: LookupByHartIdImpl,
                  q: Parameters,
                  logicalTreeNode: LogicalTreeNode)
  extends BaseTile(SSITHParams, crossing, lookup, q)
    with SinksExternalInterrupts
    with SourcesExternalNotifications
{
  /**
    * Setup parameters:
    * Private constructor ensures altered LazyModule.p is used implicitly
    */
  def this(params: SSITHTileParams, crossing: RocketCrossingParams, lookup: LookupByHartIdImpl, logicalTreeNode: LogicalTreeNode)(implicit p: Parameters) =
    this(params, crossing.crossingType, lookup, p, logicalTreeNode)

  val intOutwardNode = IntIdentityNode()
  val slaveNode = TLIdentityNode()
  val masterNode = visibilityNode

  tlOtherMastersNode := tlMasterXbar.node
  masterNode :=* tlOtherMastersNode
  DisableMonitors { implicit p => tlSlaveXbar.node :*= slaveNode }

  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq("eth-zurich,SSITH", "riscv")) {
    override def parent = Some(ResourceAnchors.cpus)
    override def describe(resources: ResourceBindings): Description = {
      val Description(name, mapping) = super.describe(resources)
      Description(name, mapping ++
        cpuProperties ++
        nextLevelCacheProperty ++
        tileProperties)
    }
  }

  ResourceBinding {
    Resource(cpuDevice, "reg").bind(ResourceAddress(hartId))
  }

  override def makeMasterBoundaryBuffers(implicit p: Parameters) = {
    if (!SSITHParams.boundaryBuffers) super.makeMasterBoundaryBuffers
    else TLBuffer(BufferParams.none, BufferParams.flow, BufferParams.none, BufferParams.flow, BufferParams(1))
  }

  override def makeSlaveBoundaryBuffers(implicit p: Parameters) = {
    if (!SSITHParams.boundaryBuffers) super.makeSlaveBoundaryBuffers
    else TLBuffer(BufferParams.flow, BufferParams.none, BufferParams.none, BufferParams.none, BufferParams.none)
  }

  val fakeRocketParams = RocketTileParams(
    dcache = SSITHParams.dcache,
    hartId = SSITHParams.hartId,
    name   = SSITHParams.name,
    btb    = SSITHParams.btb,
    core = RocketCoreParams(
      bootFreqHz          = SSITHParams.core.bootFreqHz,
      useVM               = SSITHParams.core.useVM,
      useUser             = SSITHParams.core.useUser,
      useDebug            = SSITHParams.core.useDebug,
      useAtomics          = SSITHParams.core.useAtomics,
      useAtomicsOnlyForIO = SSITHParams.core.useAtomicsOnlyForIO,
      useCompressed       = SSITHParams.core.useCompressed,
      useSCIE             = SSITHParams.core.useSCIE,
      mulDiv              = SSITHParams.core.mulDiv,
      fpu                 = SSITHParams.core.fpu,
      nLocalInterrupts    = SSITHParams.core.nLocalInterrupts,
      nPMPs               = SSITHParams.core.nPMPs,
      nBreakpoints        = SSITHParams.core.nBreakpoints,
      nPerfCounters       = SSITHParams.core.nPerfCounters,
      haveBasicCounters   = SSITHParams.core.haveBasicCounters,
      misaWritable        = SSITHParams.core.misaWritable,
      haveCFlush          = SSITHParams.core.haveCFlush,
      nL2TLBEntries       = SSITHParams.core.nL2TLBEntries,
      mtvecInit           = SSITHParams.core.mtvecInit,
      mtvecWritable       = SSITHParams.core.mtvecWritable
    )
  )
  val rocketLogicalTree: RocketLogicalTreeNode = new RocketLogicalTreeNode(cpuDevice, fakeRocketParams, None, p(XLen))

  override lazy val module = new SSITHTileModuleImp(this)

  /**
    * Setup AXI4 memory interface.
    * THESE ARE CONSTANTS.
    */
  val portName = "SSITH-mem-port-axi4"
  val idBits = 4
  val beatBytes = masterPortBeatBytes
  val sourceBits = 1 // equiv. to userBits (i think)

  val memAXI4Node = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = portName,
        id = IdRange(0, 1 << idBits))))))

  val mmioAXI4Node = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "SSITH-mmio-port-axi4",
        id = IdRange(0, 1 << idBits))))))

  val memoryTap = TLIdentityNode()
  (tlMasterXbar.node
    := memoryTap
    := TLBuffer()
    := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
    := TLWidthWidget(beatBytes) // reduce size of TL
    := AXI4ToTL() // convert to TL
    := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
    := AXI4Fragmenter() // deal with multi-beat xacts
    := memAXI4Node)

  val mmioTap = TLIdentityNode()
  (tlMasterXbar.node
    := mmioTap
    := TLBuffer()
    := TLFIFOFixer(TLFIFOFixer.all) // fix FIFO ordering
    := TLWidthWidget(beatBytes) // reduce size of TL
    := AXI4ToTL() // convert to TL
    := AXI4UserYanker(Some(2)) // remove user field on AXI interface. need but in reality user intf. not needed
    := AXI4Fragmenter() // deal with multi-beat xacts
    := mmioAXI4Node)

  def getSSITHInterrupts() = {
    val (interrupts, _) = intSinkNode.in(0)
    interrupts
  }
}

class SSITHTileModuleImp(outer: SSITHTile) extends BaseTileModuleImp(outer){
  // annotate the parameters
  Annotated.params(this, outer.SSITHParams)

  val debugBaseAddr = BigInt(0x0) // CONSTANT: based on default debug module
  val debugSz = BigInt(0x1000) // CONSTANT: based on default debug module
  val tohostAddr = BigInt(0x80001000L) // CONSTANT: based on default sw (assume within extMem region)
  val fromhostAddr = BigInt(0x80001040L) // CONSTANT: based on default sw (assume within extMem region)

  // have the main memory, bootrom, debug regions be executable
  val executeRegionBases = Seq(p(ExtMem).get.master.base,      p(BootROMParams).address, debugBaseAddr, BigInt(0x0), BigInt(0x0))
  val executeRegionSzs   = Seq(p(ExtMem).get.master.size, BigInt(p(BootROMParams).size),       debugSz, BigInt(0x0), BigInt(0x0))
  val executeRegionCnt   = executeRegionBases.length

  // have the main memory be cached, but don't cache tohost/fromhost addresses
  // TODO: current cache subsystem can only support 1 cacheable region... so cache AFTER the tohost/fromhost addresses
  val wordOffset = 0x40
  val (cacheableRegionBases, cacheableRegionSzs) = if (outer.SSITHParams.core.enableToFromHostCaching) {
    val bases = Seq(p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0), BigInt(0x0))
    val sizes   = Seq(p(ExtMem).get.master.size, BigInt(0x0), BigInt(0x0), BigInt(0x0), BigInt(0x0))
    (bases, sizes)
  } else {
    val bases = Seq(                                                          fromhostAddr + 0x40,              p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0))
    val sizes = Seq(p(ExtMem).get.master.size - (fromhostAddr + 0x40 - p(ExtMem).get.master.base), tohostAddr - p(ExtMem).get.master.base, BigInt(0x0), BigInt(0x0), BigInt(0x0))
    (bases, sizes)
  }
  val cacheableRegionCnt   = cacheableRegionBases.length

  val traceInstSz = (new freechips.rocketchip.rocket.TracedInstruction).getWidth

  // connect the SSITH core
  val core = Module(new SSITHCoreBlackbox(
    // traceport params
    traceportEnabled = outer.SSITHParams.trace,
    traceportSz = (outer.SSITHParams.core.retireWidth * traceInstSz),

    // general core params
    xLen = p(XLen),
    rasEntries = outer.SSITHParams.core.rasEntries,
    btbEntries = outer.SSITHParams.core.btbEntries,
    bhtEntries = outer.SSITHParams.core.bhtEntries,
    exeRegCnt = executeRegionCnt,
    exeRegBase = executeRegionBases,
    exeRegSz = executeRegionSzs,
    cacheRegCnt = cacheableRegionCnt,
    cacheRegBase = cacheableRegionBases,
    cacheRegSz = cacheableRegionSzs,
    debugBase = debugBaseAddr,
    axiAddrWidth = 64, // CONSTANT: addr width for TL can differ
    axiDataWidth = outer.beatBytes * 8,
    axiUserWidth = outer.sourceBits,
    axiIdWidth = outer.idBits
  ))

  core.CLK := clock
  core.RST_N := ~reset.asBool
  core.tv_verifier_info_tx_tready := true.B

//  core.io.boot_addr_i := constants.reset_vector
//  core.io.hart_id_i := constants.hartid

  core.cpu_external_interrupt_req := Cat(0.U(11.W), outer.getSSITHInterrupts().asUInt())
//  outer.connectSSITHInterrupts(core.cpu_external_interrupt_req(0), core.cpu_external_interrupt_req(1),
//    core.cpu_external_interrupt_req(2), core.cpu_external_interrupt_req(3))

  if (outer.SSITHParams.trace) {
    // unpack the trace io from a UInt into Vec(TracedInstructions)
    //outer.traceSourceNode.bundle <> core.io.trace_o.asTypeOf(outer.traceSourceNode.bundle)

//    for (w <- 0 until outer.SSITHParams.core.retireWidth) {
//      outer.traceSourceNode.bundle(w).clock     := core.io.trace_o(traceInstSz*w + 0).asClock
//      outer.traceSourceNode.bundle(w).reset     := core.io.trace_o(traceInstSz*w + 1)
//      outer.traceSourceNode.bundle(w).valid     := core.io.trace_o(traceInstSz*w + 2)
//      outer.traceSourceNode.bundle(w).iaddr     := core.io.trace_o(traceInstSz*w + 42, traceInstSz*w + 3)
//      outer.traceSourceNode.bundle(w).insn      := core.io.trace_o(traceInstSz*w + 74, traceInstSz*w + 43)
//      outer.traceSourceNode.bundle(w).priv      := core.io.trace_o(traceInstSz*w + 77, traceInstSz*w + 75)
//      outer.traceSourceNode.bundle(w).exception := core.io.trace_o(traceInstSz*w + 78)
//      outer.traceSourceNode.bundle(w).interrupt := core.io.trace_o(traceInstSz*w + 79)
//      outer.traceSourceNode.bundle(w).cause     := core.io.trace_o(traceInstSz*w + 87, traceInstSz*w + 80)
//      outer.traceSourceNode.bundle(w).tval      := core.io.trace_o(traceInstSz*w + 127, traceInstSz*w + 88)
//    }
  } else {
    outer.traceSourceNode.bundle := DontCare
    outer.traceSourceNode.bundle map (t => t.valid := false.B)
  }

  // connect the axi interfaces
  Seq(outer.memAXI4Node.out, outer.mmioAXI4Node.out) zip Seq(core.master0, core.master1) foreach { case (node, port) => 
  node foreach { case (out, edgeOut) =>
    port.awready           := out.aw.ready
    out.aw.valid                   := port.awvalid
    out.aw.bits.id                 := port.awid
    out.aw.bits.addr               := port.awaddr
    out.aw.bits.len                := port.awlen
    out.aw.bits.size               := port.awsize
    out.aw.bits.burst              := port.awburst
    out.aw.bits.lock               := port.awlock
    out.aw.bits.cache              := port.awcache
    out.aw.bits.prot               := port.awprot
    out.aw.bits.qos                := port.awqos

    port.wready            := out.w.ready
    out.w.valid                    := port.wvalid
    out.w.bits.data                := port.wdata
    out.w.bits.strb                := port.wstrb
    out.w.bits.last                := port.wlast

    out.b.ready             := port.bready
    port.bvalid     := out.b.valid
    port.bid        := out.b.bits.id
    port.bresp      := out.b.bits.resp

    port.arready           := out.ar.ready
    out.ar.valid                   := port.arvalid
    out.ar.bits.id                 := port.arid
    out.ar.bits.addr               := port.araddr
    out.ar.bits.len                := port.arlen
    out.ar.bits.size               := port.arsize
    out.ar.bits.burst              := port.arburst
    out.ar.bits.lock               := port.arlock
    out.ar.bits.cache              := port.arcache
    out.ar.bits.prot               := port.arprot
    out.ar.bits.qos                := port.arqos

    out.r.ready                    := port.rready
    port.rvalid     := out.r.valid
    port.rid   := out.r.bits.id
    port.rdata := out.r.bits.data
    port.rresp := out.r.bits.resp
    port.rlast := out.r.bits.last
  }}
}

class SSITHAXI4Bundle(params: AXI4BundleParameters) extends GenericParameterizedBundle(params) {
  val awvalid  = Output(Bool())
  val awid     = Output(UInt(width = params.idBits.W))
  val awaddr   = Output(UInt(width = params.addrBits.W))
  val awlen    = Output(UInt(width = params.lenBits.W))
  val awsize   = Output(UInt(width = params.sizeBits.W))
  val awburst  = Output(UInt(width = params.burstBits.W))
  val awlock   = Output(UInt(width = params.lockBits.W))
  val awcache  = Output(UInt(width = params.cacheBits.W))
  val awprot   = Output(UInt(width = params.protBits.W))
  val awqos    = Output(UInt(width = params.qosBits.W))
  val awregion = Output(UInt(width = 4.W))
  val awready  = Input(Bool())
  val wvalid   = Output(Bool())
  val wdata    = Output(UInt(width = params.dataBits.W ))
  val wstrb    = Output(UInt(width = (params.dataBits/8).W))
  val wlast    = Output(Bool())
  val wready   = Input(Bool())
  val bvalid   = Input(Bool())
  val bid      = Input(UInt(width = params.idBits.W))
  val bresp    = Input(UInt(width = params.respBits.W))
  val bready   = Output(Bool())
  val arvalid  = Output(Bool())
  val arid     = Output(UInt(width = params.idBits.W))
  val araddr   = Output(UInt(width = params.addrBits.W))
  val arlen    = Output(UInt(width = params.lenBits.W))
  val arsize   = Output(UInt(width = params.sizeBits.W))
  val arburst  = Output(UInt(width = params.burstBits.W))
  val arlock   = Output(UInt(width = params.lockBits.W))
  val arcache  = Output(UInt(width = params.cacheBits.W))
  val arprot   = Output(UInt(width = params.protBits.W))
  val arqos    = Output(UInt(width = params.qosBits.W))
  val arregion = Output(UInt(width = 4.W))
  val arready  = Input(Bool())
  val rvalid   = Input(Bool())
  val rid      = Input(UInt(width = params.idBits.W))
  val rdata    = Input(UInt(width = params.dataBits.W))
  val rresp    = Input(UInt(width = params.respBits.W))
  val rlast    = Input(Bool())
  val rready   = Output(Bool())
}

class SSITHCoreBlackbox(
                          traceportEnabled: Boolean,
                          traceportSz: Int,
                          xLen: Int,
                          rasEntries: Int,
                          btbEntries: Int,
                          bhtEntries: Int,
                          execRegAvail: Int = 5,
                          exeRegCnt: Int,
                          exeRegBase: Seq[BigInt],
                          exeRegSz: Seq[BigInt],
                          cacheRegAvail: Int = 5,
                          cacheRegCnt: Int,
                          cacheRegBase: Seq[BigInt],
                          cacheRegSz: Seq[BigInt],
                          debugBase: BigInt,
                          axiAddrWidth: Int,
                          axiDataWidth: Int,
                          axiUserWidth: Int,
                          axiIdWidth: Int)
  extends ExtModule(
//    Map(
//      "AXI_ADDRESS_WIDTH" -> IntParam(axiAddrWidth),
//      "AXI_DATA_WIDTH" -> IntParam(axiDataWidth),
//      "AXI_USER_WIDTH" -> IntParam(axiUserWidth),
//      "AXI_ID_WIDTH" -> IntParam(axiIdWidth))
  )
{
  val CLK = IO(Input(Clock()))
  val RST_N = IO(Input(Bool()))
  val master0 = IO(new SSITHAXI4Bundle(AXI4BundleParameters(64, 64, 4, 0, false)))
  val master1 = IO(new SSITHAXI4Bundle(AXI4BundleParameters(64, 64, 4, 0, false)))
  val cpu_external_interrupt_req = IO(Input(UInt(16.W)))
  val tv_verifier_info_tx_tvalid = IO(Output(Bool()))
  val tv_verifier_info_tx_tdata = IO(Output(UInt(608.W)))
  val tv_verifier_info_tx_tstrb = IO(Output(UInt(76.W)))
  val tv_verifier_info_tx_tkeep = IO(Output(UInt(76.W)))
  val tv_verifier_info_tx_tlast = IO(Output(Bool()))
  val tv_verifier_info_tx_tready = IO(Input(Bool()))
  val jtag_tdi = IO(Input(Bool()))
  val jtag_tms = IO(Input(Bool()))
  val jtag_tclk = IO(Input(Bool()))
  val jtag_tdo = IO(Output(Bool()))
  val CLK_jtag_tclk_out = IO(Output(Bool()))
  val CLK_GATE_jtag_tclk_out = IO(Output(Bool()))
//  require((exeRegCnt <= execRegAvail) && (exeRegBase.length <= execRegAvail) && (exeRegSz.length <= execRegAvail), s"Currently only supports $execRegAvail execution regions")
//  require((cacheRegCnt <= cacheRegAvail) && (cacheRegBase.length <= cacheRegAvail) && (cacheRegSz.length <= cacheRegAvail), s"Currently only supports $cacheRegAvail cacheable regions")
//
//  // pre-process the verilog to remove "includes" and combine into one file
//  val make = "make -C generators/SSITH/src/main/resources/vsrc default "
//  val proc = if (traceportEnabled) make + "EXTRA_PREPROC_OPTS=+define+FIRESIM_TRACE" else make
//  require (proc.! == 0, "Failed to run preprocessing step")

  // add wrapper/blackbox after it is pre-processed
  // addResource("/vsrc/SSITHP2Core.v")
}
