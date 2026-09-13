import sys, os, glob
sys.path.insert(0, sys.argv[2])
from nbtanalyse import iter_region
from collections import Counter
folder = sys.argv[1]
hist = Counter(); ymin=99999; ymax=-99999; nsec=0
for rp in sorted(glob.glob(os.path.join(folder,"region","*.mca"))):
    for cx, cz, comp, ch in iter_region(rp):
        lvl = ch.get("Level", ch)
        for s in (lvl.get("sections") or lvl.get("Sections") or []):
            if "block_states" not in s: continue
            bs = s.get("block_states") or {}
            pal = bs.get("palette") or []
            names = [e.get("Name") for e in pal]
            nonair = any(not (n or "").endswith("air") for n in names)
            if not nonair: continue
            y = s.get("Y"); nsec += 1
            hist[y] += 1
            ymin=min(ymin,y); ymax=max(ymax,y)
print("### 源 1.20.5 含方块的 section Y 直方图")
print("  sections=%d   Y范围=%d..%d" % (nsec, ymin, ymax))
for k in sorted(hist): print("   section Y=%-4d (方块Y %d..%d)  区块数=%d" % (k, k*16, k*16+15, hist[k]))