#define DRAM_BASE 0x80000000

// this boot ROM doesn't know about any boot devices, so it just spins,
// waiting for the serial interface to load the program and interrupt it
.section .text.hang, "ax", @progbits
.globl _hang
_hang: // reset vector
  la a0, _start      // on MSIP interrupt, go to _start
  csrw mtvec, a0
  li a0, 0x808           // MIE or MSIP bit
  li a1, 0x8
  csrw mie, a0       // set only MSIP in MIE CSR
  csrw mideleg, zero // no delegation
  csrs mstatus, a1   // set MIE in mstatus CSR
  li a1, 0xC200000
  sw zero, 0(a1)
  li a1, 0xC002000
  li a0, 0x10000
  sw a0, 0(a1)
  li a0, 7
  li a1, 0xC000000
  sw a0, 0x40(a1)
wfi_loop: // wait for MSIP interrupt to start program execution
  wfi
  j wfi_loop

// boot all cores (only hart 0) and jump to main program execution
.section .text.start, "ax", @progbits
.globl _start
_start:
  li s0, 0xC200004
  lw a2, 0(s0)
  li a1, 0x10000000 // base address of clint
  csrr a0, mhartid
  bnez a0, boot_core
  addi a2, a1, 4
  li a3, 1
interrupt_loop: // trigger MSIP interrupts for other harts so that they also boot
  sw a3, 0(a2)
  addi a2, a2, 4
  lw a3, -4(a2)
  bnez a3, interrupt_loop
  j boot_core_hart0

// other harts must wait until MSIP of hart 0 is cleared to continue
boot_core:
  lw t0, 0(a1)       // load hart 0 MSIP
  bnez t0, boot_core // block until hart 0 clears own MSIP
  sll a0, a0, 2      // offset for hart MSIP
  add a1, a0, a1
boot_core_hart0:   // begin executing code at DRAM_BASE
  sw zero, 0(a1)   // clear the interrupt
  sw a2, 0(s0)     // clear the PLIC
  li s0, 0xC002000 // Disable the PLIC
  li s1, 0xC000000 
  sw zero, 0(s0)
  sw zero, 0x40(s1)
  li a0, DRAM_BASE // program reset vector
  csrw mepc, a0    // return from interrupt to start of user program
  csrr a0, mhartid // hartid for next level bootloader
  la a1, _dtb      // dtb address for next level bootloader
  li a2, 0x80      // set mstatus MPIE to 0
  csrc mstatus, a2
  mret

_dtb:
