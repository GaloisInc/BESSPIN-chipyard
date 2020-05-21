package ssith

import chisel3._
import freechips.rocketchip.config._
import freechips.rocketchip.diplomacy._
import freechips.rocketchip.diplomaticobjectmodel.logicaltree.{LogicalTreeNode, RocketLogicalTreeNode}
import freechips.rocketchip.rocket._
import freechips.rocketchip.subsystem.RocketCrossingParams
import freechips.rocketchip.tilelink._
import freechips.rocketchip.interrupts._
import freechips.rocketchip.util._
import freechips.rocketchip.tile._
import freechips.rocketchip.amba.axi4._
import freechips.rocketchip.devices.tilelink.BootROMParams
import ssith.SSITHCoreType.SSITHCoreType

case object SSITHCrossingKey extends Field[Seq[RocketCrossingParams]](List(RocketCrossingParams()))

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

object SSITHCoreType extends Enumeration {
  type SSITHCoreType = Value
  val BLUESPECP1 = Value("bluespec_p1")
  val BLUESPECP2 = Value("bluespec_p2")
  val BLUESPECP3 = Value("bluespec_p3")
  val CHISELP1   = Value("chisel_p1")
  val CHISELP2   = Value("chisel_p2")
  val CHISELP3   = Value("chisel_p3")
}

// TODO: Many parameters are incorrect (ex BTBParams, DCacheParams, ICacheParam)... figure out what to put in DTB
case class SSITHTileParams(
                             name: Option[String] = Some("ssith_tile"),
                             coreType: SSITHCoreType = SSITHCoreType.BLUESPECP2,
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
    // This is order dependent! Must be debug -> clint -> plic
    // traits are loaded top-down
    with HasIntegratedGFEDebug
    with HasIntegratedGFECLINT
    with HasIntegratedGFEPLIC
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

  val cpuDevice: SimpleDevice = new SimpleDevice("cpu", Seq(s"galois,SSITH-${SSITHParams.coreType}", "riscv")) {
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
    icache = SSITHParams.icache,
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
  val idBits = 4
  val beatBytes = masterPortBeatBytes
  val sourceBits = 1 // equiv. to userBits (i think)

  val memAXI4Node = AXI4MasterNode(
    Seq(AXI4MasterPortParameters(
      masters = Seq(AXI4MasterParameters(
        name = "SSITH-mem-port-axi4",
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
}

class SSITHTileModuleImp(outer: SSITHTile) extends BaseTileModuleImp(outer)
  with HasIntegratedGFEPLICModuleImp {
  // annotate the parameters
  Annotated.params(this, outer.SSITHParams)

  // connect the SSITH core
  val core = Module(new SSITHCoreBlackbox(
    coreType = outer.SSITHParams.coreType,
    // general core params
    axiAddrWidth = 64, // CONSTANT: addr width for TL can differ
    axiDataWidth = outer.beatBytes * 8,
    axiUserWidth = outer.sourceBits,
    axiIdWidth = outer.idBits
  ))

  core.CLK := clock
  core.RST_N := ~reset.asBool
  core.tv_verifier_info_tx_tready := true.B

  core.cpu_external_interrupt_req := outer.getSSITHInterrupts()

  if (outer.SSITHParams.trace) {
//    require(false, "Not currently implemented!")
    val valInstruction = Wire(Bool())
    val iaddr = RegInit(p(BootROMParams).hang.asUInt(40.W))
    when (valInstruction === true.B) {
      iaddr := iaddr + Mux(core.tv_verifier_info_tx_tdata(23, 16) === 17.U, 4.U, 2.U)
    }
    valInstruction := core.tv_verifier_info_tx_tvalid && core.tv_verifier_info_tx_tdata(15,0) === 769.U(16.W)
    outer.traceSourceNode.bundle(0).clock     := core.CLK
    outer.traceSourceNode.bundle(0).reset     := reset
    outer.traceSourceNode.bundle(0).valid     := valInstruction
    outer.traceSourceNode.bundle(0).iaddr     := iaddr
    outer.traceSourceNode.bundle(0).insn      := Mux(core.tv_verifier_info_tx_tdata(23, 16) === 17.U,
      core.tv_verifier_info_tx_tdata(55, 24), core.tv_verifier_info_tx_tdata(39, 24).pad(32))
    outer.traceSourceNode.bundle(0).priv      := 0.U
    outer.traceSourceNode.bundle(0).exception := 0.U
    outer.traceSourceNode.bundle(0).interrupt := 0.U
    outer.traceSourceNode.bundle(0).cause     := 0.U
    outer.traceSourceNode.bundle(0).tval      := 0.U
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
