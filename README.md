
# Chipyard - LMCO Example Build

This branch is an example of building LMCO's pipeline into a modern chipyard/rocket-chip, 
which is both FireSim and (mostly) existing GFE compatible. More on GFE compatibility below.

# TA1-LMCO Repo

The `ta1-lmco` repo has been updated on the [chipyard-compat](https://gitlab-ext.galois.com/ssith/ta1-lmco/-/tree/chipyard-compat) 
branch to be used directly as a chipyard "generator". This only required a few minor modifications. First, the chipyard `build.sbt` 
file has been updated to include `ta1lmco` as a package. This package is then added to the dependency list for rocketchip. All files in
`ta1-lmco/modSmoke/src/main/scala` are included automatically without having to copy them into the rocket-chip folder.

The second modification still requires direct manipulation of the Rocket files. Rather than maintaining a full copy of them, the 
modifications are now applied using a patch file ([see here](https://gitlab-ext.galois.com/ssith/ta1-lmco/-/blob/9d051999e1512114de4bb9bb03fcc3c44b5408d8/pipeline_interrupt/resourcesGFE/rocket-chip-4f0cdea85.patch)). There are patches for both this version of rocket-chip as well as the GFE version. Presumably this patch should be applicable
to _many_ future versions of Rocket, so only the git short hash in the filename would need to be updated to support a new Rocket.

Finally, the Makefile was updated to reflect these differences. You may need to make other modifications to satisfy your actual build environment.

## Side Note

There was an issue with using the normal Verilator simulation flow with the smoke test as-is. Chisel randomizes uninitialized registers by default, so with some
high probability, the added pipeline stall control registers were being assigned `1'b1` on startup and causing unrecoverable stalls. I updated two registers
to be initialized to zero on reset.

# Rocket

The rocket-chip repo is now a completely unaltered version, commit `4f0cdea85c8a2b849fd582ccc8497892001d06b0`. This is the same commit referenced by
FireSim v1.9.0.

## Incompatibilities with GFE Rocket

There are two current known incompatibilities with the existing GFE Rocket:
* Two new debug signals have been introduced at the top level: `debug_systemjtag_part_number` and `debug_systemjtag_version`. 
* The Debug Module IR length and register addresses are back at the original locations, so the Xilinx JTAG connection will not work.

I have tried to align these to the GFE, but have not been able to without making modifications directly to the rocket-chip repo. This will be done if necessary.

Tandem Verification is also missing and not currently planned to be re-implemented.

# FireSim

More details on how to build a LMCO-enabled FireSim image can be found here (TBD).

# Building a HARD-enabled Rocket core

## Initialize chipyard

You can follow the normal chipyard instructions to build a vanilla rocket core. Or the short version is just run this command:
```
./scripts/init-submodules-no-riscv-tools-nolog.sh
```
**Note**: You should always use this script to update submodules in chipyard. If you use `git submodules --recursive`, you'll get a ton of unnecessary cruft.

## Applying the patch

At the root chipyard directory, run:
```
make -f Makefile.lmco patch
```
This will apply the patch file to rocket-chip, making all new builds include the HARD pipeline modifications.

## Build (early) CloudGFE Rocket

Coming soon.. This is already included in the branch, but not packaged up nicely to run.

## Build Generic Rocket

As the HARD patch is applied to Rocket directly, you can just build the normal chipyard config to test it:
```
cd sims/verilator or sims/vcs
make
```

This will make the default `RocketConfig`.

## Build (nearly) GFE-Compatible Netlist (Untested)

With the caveats mentioned earlier, there is a basic setup included in the top-level Makefile to generate a drop-in replacement for the GFE verilog files.

At the top chipyard folder:
```
make -f Makefile.lmco lmco_p2
```

This will generate `generated-src/chipyard.GaloisTop.P2Config/galois.system.P2TVFPGAConfig.v` and `generated-src/chipyard.GaloisTop.P2Config/galois.system.P2TVFPGAConfig.behav_srams.v` files that can replace the existing files in `gfe/chisel_processors/P2/xilinx_ip/hdl/`.

This has not yet been fully tested to a final bitstream running on a VCU118.
