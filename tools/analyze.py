import struct, sys, re
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_ARM
import os
path = os.environ.get('MCPE_SO', 'libminecraftpe.so')
d = open(path,'rb').read()
RELA_OFF, RELA_SZ = 0xaf40d8, 0x17c95b0
by_off = {}
for off, info, add in struct.iter_unpack('<QQq', d[RELA_OFF:RELA_OFF+RELA_SZ]):
    if info & 0xffffffff == 1027: by_off[off] = add
TEXT = (0x7ad3840, 0x7ad3840 + 0xd0ea4c0)
RO = (0x23ca2c0, 0x23ca2c0 + 0x24d2dc4)
md = Cs(CS_ARCH_ARM64, CS_MODE_ARM); md.detail = False
def vtable(slot):
    fs=[]; p=slot+8
    while p in by_off and TEXT[0] <= by_off[p] < TEXT[1]: fs.append(by_off[p]); p+=8
    return fs
def cstr(v):
    if not (RO[0] <= v < RO[1]): return None
    e = d.find(b'\0', v, v+120)
    s = d[v:e]
    if len(s)>=3 and all(32<=c<127 for c in s): return s.decode()
def analyze(addr, maxn=400):
    code = d[addr:addr+maxn*4]
    n=0; bl=0; strs=[]; pend={}
    for i in md.disasm(code, addr):
        n+=1
        m=i.mnemonic; o=i.op_str
        if m=='bl': bl+=1
        if m=='adrp':
            r,imm=o.split(', '); pend[r]=int(imm.lstrip('#'),16)
        elif m=='add' and o.count(',')==2:
            rd,rn,imm=[x.strip() for x in o.split(',')]
            if rn in pend and imm.startswith('#'):
                s=cstr(pend[rn]+int(imm[1:],0))
                if s: strs.append(s)
        if m in('ret','br') and n>2: break
        if n>=maxn: break
    return n, bl, strs
if __name__=='__main__':
    slot=int(sys.argv[1],16)
    for i,f in enumerate(vtable(slot)):
        n,bl,s=analyze(f)
        print(f'{i:3d} {f:#x} ins={n:3d} bl={bl:2d}', ' | '.join(x[:50] for x in s[:3]))
