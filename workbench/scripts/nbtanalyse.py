# -*- coding: utf-8 -*-
"""Analyse a Java Edition Anvil region folder: block id distribution, Y range, entities."""
import gzip, zlib, struct, sys, os, glob
from collections import Counter

class R:
    def __init__(self, b, i=0): self.b=b; self.i=i
    def u1(self): v=self.b[self.i]; self.i+=1; return v
    def i1(self): v=struct.unpack_from('>b',self.b,self.i)[0]; self.i+=1; return v
    def i2(self): v=struct.unpack_from('>h',self.b,self.i)[0]; self.i+=2; return v
    def i4(self): v=struct.unpack_from('>i',self.b,self.i)[0]; self.i+=4; return v
    def i8(self): v=struct.unpack_from('>q',self.b,self.i)[0]; self.i+=8; return v
    def f4(self): v=struct.unpack_from('>f',self.b,self.i)[0]; self.i+=4; return v
    def f8(self): v=struct.unpack_from('>d',self.b,self.i)[0]; self.i+=8; return v
    def s(self):
        n=struct.unpack_from('>H',self.b,self.i)[0]; self.i+=2
        v=self.b[self.i:self.i+n].decode('utf-8','replace'); self.i+=n; return v
    def payload(self, t, depth=0):
        if depth>80: raise ValueError('too deep')
        if t==1: return self.i1()
        if t==2: return self.i2()
        if t==3: return self.i4()
        if t==4: return self.i8()
        if t==5: return self.f4()
        if t==6: return self.f8()
        if t==7:
            n=self.i4(); v=self.b[self.i:self.i+n]; self.i+=n; return bytes(v)
        if t==8: return self.s()
        if t==9:
            it=self.u1(); n=self.i4()
            return [self.payload(it, depth+1) for _ in range(n)]
        if t==10:
            d={}
            while True:
                tt=self.u1()
                if tt==0: break
                nm=self.s(); d[nm]=self.payload(tt, depth+1)
            return d
        if t==11:
            n=self.i4(); return [self.i4() for _ in range(n)]
        if t==12:
            n=self.i4(); return [self.i8() for _ in range(n)]
        raise ValueError('bad tag %d'%t)

def read_nbt(b):
    r=R(b); t=r.u1()
    nm = r.s() if t!=0 else ''
    return nm, r.payload(t)

def iter_region(path):
    with open(path,'rb') as f: hdr=f.read(4096)
    size=os.path.getsize(path)
    for idx in range(1024):
        raw=struct.unpack_from('>I', hdr, idx*4)[0]
        off=raw>>8; sec=raw&0xFF
        if off==0 or sec==0: continue
        if off*4096 >= size: continue
        with open(path,'rb') as f:
            f.seek(off*4096); head=f.read(5)
            if len(head)<5: continue
            ln,comp=struct.unpack('>IB', head)
            data=f.read(ln-1)
        try:
            raw_b = gzip.decompress(data) if comp==1 else (zlib.decompress(data) if comp==2 else data)
            yield (idx%32, idx//32, comp, read_nbt(raw_b)[1])
        except Exception as ex:
            yield (idx%32, idx//32, comp, {'__error__':str(ex)})

def analyse(folder, label):
    print('#'*64)
    print('# %s  (%s)'%(label, folder))
    print('#'*64)
    regions=sorted(glob.glob(os.path.join(folder,'region','*.mca')))
    print('region files: %d'%len(regions))
    nchunk=0; nerr=0; nsec=0
    ids=Counter(); yr=[]; ents=Counter(); tes=Counter()
    has_add=0; has_data=0; has_bl=0; has_sl=0; sec_y=set()
    dvers=Counter()
    for rp in regions:
        for cx,cz,comp,ch in iter_region(rp):
            if '__error__' in ch: nerr+=1; continue
            nchunk+=1
            lvl=ch.get('Level', ch)
            if 'DataVersion' in lvl: dvers[lvl['DataVersion']]+=1
            for s in (lvl.get('Sections') or []):
                nsec+=1
                y=s.get('Y'); sec_y.add(y)
                blk=s.get('Blocks')
                if blk is None: continue
                add=s.get('Add')
                if add is not None: has_add+=1
                if 'Data' in s: has_data+=1
                if 'BlockLight' in s: has_bl+=1
                if 'SkyLight' in s: has_sl+=1
                for i,b in enumerate(blk):
                    bid=b if b>=0 else b+256
                    hi=0
                    if add is not None:
                        nib=add[i//2]
                        nv=(nib>>4)&0xF if i%2==0 else nib&0xF
                        hi=nv
                    full=(hi<<8)|bid
                    if full==0: continue
                    ids[full]+=1
                    yr.append(y*16 + (i%4096)//16)
            for e in (lvl.get('Entities') or []):
                ents[e.get('id')]+=1
            for t in (lvl.get('TileEntities') or []):
                tes[t.get('id')]+=1
    print('chunks read: %d   errors: %d   sections: %d'%(nchunk,nerr,nsec))
    print('DataVersion values in chunks:', dict(dvers))
    print('section Y values present:', sorted(sec_y))
    print('sections with Add=%d  Data=%d  BlockLight=%d  SkyLight=%d'%(has_add,has_data,has_bl,has_sl))
    print('--- block ids (non-air), top 25 ---')
    tot=sum(ids.values())
    print('total non-air blocks: %d   distinct ids: %d'%(tot,len(ids)))
    if ids: print('id range: min=%d max=%d'%(min(ids),max(ids)))
    for bid,c in ids.most_common(25):
        print('   id=%-5d %6d  (%.1f%%)'%(bid,c,100.0*c/tot if tot else 0))
    if yr:
        print('--- Y range of non-air blocks: min=%d max=%d ---'%(min(yr),max(yr)))
        hist=Counter(y//16*16 for y in yr)
        print('   per 16-block band:', dict(sorted(hist.items())))
    print('--- entities: %d total ---'%sum(ents.values()))
    for k,v in ents.most_common(15): print('   %-32s %d'%(k,v))
    print('--- tile entities: %d total ---'%sum(tes.values()))
    for k,v in tes.most_common(15): print('   %-32s %d'%(k,v))
    print()

if __name__=='__main__':
    for arg in sys.argv[1:]:
        if '#' in arg:
            folder, label = arg.split('#',1)
        else:
            folder, label = arg, os.path.basename(arg.rstrip('/\\'))
        if os.path.isdir(folder): analyse(folder, label)
