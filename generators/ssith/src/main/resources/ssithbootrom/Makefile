bootrom_img = bootrom.cloudgfe.rv64.img bootrom.cloudgfe.rv32.img bootrom.ssithblackbox.rv64.img bootrom.ssithblackbox.rv32.img
bootrom_dump = bootrom.cloudgfe.rv64.dump bootrom.cloudgfe.rv32.dump bootrom.ssithblackbox.rv64.dump bootrom.ssithblackbox.rv32.dump

GCC=riscv64-unknown-elf-gcc
CFLAGS_RV64=-mabi=lp64 -march=rv64ima
CFLAGS_RV32=-mabi=ilp32 -march=rv32ima
OBJCOPY=riscv64-unknown-elf-objcopy
OBJDUMP=riscv64-unknown-elf-objdump

img: $(bootrom_img)

dump: $(bootrom_dump)

%.img: %.elf
	$(OBJCOPY) -O binary --change-addresses=-0x10000 $< $@

%.rv32.elf: %.S linker.ld
	$(GCC) $(CFLAGS_RV32) -Tlinker.ld $< -nostdlib -static -o $@

%.rv64.elf: %.S linker.ld
	$(GCC) $(CFLAGS_RV64) -Tlinker.ld $< -nostdlib -static -o $@

%.dump: %.elf
	$(OBJDUMP) -d $< > $@

clean:
	rm -f *.elf *.dump *.img
