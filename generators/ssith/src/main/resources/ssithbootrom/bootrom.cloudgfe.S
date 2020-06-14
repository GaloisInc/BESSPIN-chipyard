#define DRAM_BASE 0xC0000000

// this boot ROM doesn't know about any boot devices, so it just spins,
// waiting for the serial interface to load the program and interrupt it
.section .text.hang, "ax", @progbits
.globl _hang
_hang: // reset vector
  li t1, 0x2000000   // set mmint in t1
  lw a1, 4(t1)
  li s0, 0           // tells set_regs we arrived from _hang
  bnez a1, set_regs  // If debug is enabled, branch to set regs and loop 
  la a0, _start      // on MSIP interrupt, go to _start
  csrw mtvec, a0
  li a0, 0x808           // MIE or MSIP bit
  li a1, 0x8
  csrw mie, a0       // set only MSIP in MIE CSR
#if __riscv_xlen == 64
  csrw mideleg, zero // no delegation
#endif
  csrs mstatus, a1   // set MIE in mstatus CSR
  li a1, 0xC200000
  sw zero, 0(a1)
  li a1, 0xC002000
  li a0, 0x2
  sw a0, 0(a1)
  li a0, 7
  li t2, 0xC000000   // store PLIC base address in t2
  sw a0, 0x04(t2)
wfi_loop: // wait for MSIP interrupt to start program execution
  wfi
  j wfi_loop

// boot all cores (only hart 0) and jump to main program execution
.section .text.start, "ax", @progbits
.globl _start
_start:
  li s0, 0xC200004
  lw a2, 4(t2)
  sw zero, 0(t1)   // clear the interrupt in MMInt
  sw a2, 0(s0)     // clear the PLIC
  li s0, 0xC002000 // Disable the PLIC
  sw zero, 0(s0)
  sw zero, 4(t2)
  li a0, DRAM_BASE // program reset vector
  csrw mepc, a0    // return from interrupt to start of user program
set_regs:
  csrr a0, mhartid // hartid for next level bootloader
  la a1, _dtb      // dtb address for next level bootloader
  beqz s0, wfi_loop // If we reached this code due to wait for debug, go to loop
  li a2, 0x80      // set mstatus MPIE to 0
  csrc mstatus, a2
  mret

_dtb:
