SECTIONS
{
    ROM_BASE = 0x70000000; /* ... but actually position independent */

    . = ROM_BASE;
    .text.hang : { *(.text.hang) }
    . = ROM_BASE + 0x80;
    .text.start : { *(.text.start) }
    . = ROM_BASE + 0x140;
    .text.other : { *(.text.other) }
}
