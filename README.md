
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

The rocket-chip repo has been updated to a SSITH-specific Rocket, commit [edd6d71d2f0cf45b2565a14aa3d387123fcf8e9b](https://gitlab-ext.galois.com/ssith/rocket-chip/-/tree/edd6d71d2f0cf45b2565a14aa3d387123fcf8e9b). This is a slightly altered version of the official rocket-chip commit `4f0cdea85c8a2b849fd582ccc8497892001d06b0`, which is referenced by FireSim v1.9.0.

This SSITH specific commit has modifications to support the Xilinx JTAG connection used in the local GFE.

## Incompatibilities with GFE Rocket

The inconsistencies versus the previously provided GFE Rocket have been resolved, although Xilinx JTAG support is untested at this time.

Tandem Verification is missing and not currently planned to be re-implemented.

# FireSim

Building a HARD-enabled FireSim image will be more automated shortly. To implement it now:
1. Checkout [firesim](https://github.com/DARPA-SSITH-Demonstrators/firesim) from the DARPA-SSITH-Demonstrators group.
2. Run the normal setup steps found in the README on the page above, up to the point of _Build Your Own Image_. This will generate all the prerequesites and populate the submodule tree.
3. On your manager instance, `cd ~/firesim/target-design/chipyard`
4. `git checkout ssith-lmco; git pull`
5. `./scripts/init-submodules-no-riscv-tools-nolog.sh`
6. `make -f Makefile.lmco patch` --> Make sure the patch applies successfully before continuing
7. `cd ~/firesim/deploy` --> Edit `config_build.ini` and `config_build_recipes.ini` as described in the link in Step 1 to build a `CloudGFERocketConfig`
8. `firesim buildafi`

Once the LMCO patch has been applied, all future `Rocket` builds will contain the Hard pipeline by default.

# Building a HARD-enabled Rocket core (non-FireSim)

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

## Build CloudGFE Rocket

This closely matches the existing GFE Rocket.

```
cd sims/verilator or sims/vcs
make CONFIG=CloudGFERocketConfig
```

## Build GFE-Compatible Netlist (Untested)

You can also use SSITH Chipyard's ability to create drop-in replacement netlists for the GFE verilog files.

At the top chipyard folder:
```
make -f Makefile.lmco lmco_p2
```

This will generate `generated-src/chipyard.GaloisTop.P2Config/galois.system.P2TVFPGAConfig.v` and `generated-src/chipyard.GaloisTop.P2Config/galois.system.P2TVFPGAConfig.behav_srams.v` files that can replace the existing files in `gfe/chisel_processors/P2/xilinx_ip/hdl/`.

This has not yet been fully tested to a final bitstream running on a VCU118.
