package chipyard

import freechips.rocketchip.config.{Config}

class SSITHConfig extends Config(
    new chipyard.iobinders.WithUARTAdapter ++                      // display UART with a SimUARTAdapter
    new chipyard.iobinders.WithTieOffInterrupts ++                 // tie off top-level interrupts
    new chipyard.iobinders.WithSimAXIMem ++                        // drive the master AXI4 memory with a SimAXIMem
    new chipyard.iobinders.WithTiedOffDebug ++                     // tie off debug (since we are using SimSerial for testing)
    new chipyard.iobinders.WithSimSerial ++                        // drive TSI with SimSerial for testing
    new testchipip.WithTSI ++                                      // use testchipip serial offchip link
    new chipyard.config.WithNoGPIO ++                              // no top-level GPIO pins (overrides default set in sifive-blocks)
    new chipyard.config.WithUART ++                                // add a UART
    new ssith.WithSSITHMemPort ++                                  // Change location of main memory
    new chipyard.config.WithGFEBootROM ++                          // Use the SSITH Bootrom to workaround incompatibility issues
    new ssith.WithIntegratedPlicClintDebug ++                      // Removes duplicated PLIC, CLINT, and Debug Module, which are all inside core
    new freechips.rocketchip.subsystem.WithNoMMIOPort ++           // no top-level MMIO master port (overrides default set in rocketchip)
    new freechips.rocketchip.subsystem.WithNoSlavePort ++          // no top-level MMIO slave port (overrides default set in rocketchip)
    new freechips.rocketchip.subsystem.WithInclusiveCache ++       // use Sifive L2 cache
    new freechips.rocketchip.subsystem.WithNExtTopInterrupts(0) ++ // no external interrupts
    new ssith.WithNSSITHCores(1) ++                                // single SSITH core
    new freechips.rocketchip.system.BaseConfig)                    // "base" rocketchip system