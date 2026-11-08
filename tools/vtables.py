import struct, sys, re
path = sys.argv[1]
names = sys.argv[2:]
d = open(path, 'rb').read()
RELA_OFF, RELA_SZ = 0xaf40d8, 0x17c95b0
def v2o(v):  # vaddr -> file offset
    if v < 0x14c712f0: return v
    if v < 0x14c752f0 + 0x95e970: return v - 0x4000
    if 0x155d7c60 <= v < 0x155d7c60 + 0x38c80: return v - 0x155d7c60 + 0x155cfc60
    return None
# addend->[r_offset] и r_offset->addend (только RELATIVE)
by_addend, by_off = {}, {}
for off, info, add in struct.iter_unpack('<QQq', d[RELA_OFF:RELA_OFF+RELA_SZ]):
    if info & 0xffffffff == 1027:
        by_off[off] = add
        by_addend.setdefault(add, []).append(off)
print('relative relocs:', len(by_off))
TEXT = (0x7ad3840, 0x7ad3840 + 0xd0ea4c0)
for n in names:
    s = (str(len(n)) + n).encode() + b'\0'
    for m in re.finditer(re.escape(b'\0' + s), d[0x23ca2c0:0x23ca2c0+0x24d2dc4]):
        sv = 0x23ca2c0 + m.start() + 1
        print(f'\n{n}: name string @ {sv:#x}')
        for x in by_addend.get(sv, []):
            T = x - 8
            for L in by_addend.get(T, []):
                funcs = []
                p = L + 8
                while p in by_off and TEXT[0] <= by_off[p] < TEXT[1]:
                    funcs.append(by_off[p]); p += 8
                print(f'  typeinfo {T:#x} -> vtable typeinfo-slot {L:#x}, virtual funcs: {len(funcs)}')
                if funcs: print('   first:', ' '.join(f'{f:#x}' for f in funcs[:6]))
