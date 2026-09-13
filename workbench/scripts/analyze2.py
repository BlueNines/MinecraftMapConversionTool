import sys, os, glob
sys.path.insert(0, sys.argv[2])
from nbtanalyse import iter_region
from collections import Counter

def decode_section(s):
    """Return (total_nonair, Counter of block descr, air_count)."""
    c = Counter(); air = 0; total = 0
    if "block_states" in s:                      # 1.18+ palette format
        bs = s["block_states"] or {}
        pal = bs.get("palette") or []
        names = []
        for e in pal:
            n = e.get("Name")
            props = e.get("Properties")
            names.append(n + ("|" + ",".join("%s=%s" % (k, v) for k, v in sorted(props.items())) if props else ""))
        data = bs.get("data")
        nsec = 4096
        if data is None:
            idx = [0] * nsec
        else:
            bits = max(4, (len(pal) - 1).bit_length())
            per = 64 // bits
            mask = (1 << bits) - 1
            idx = []
            for lv in data:
                u = lv & 0xFFFFFFFFFFFFFFFF
                for k in range(per):
                    if len(idx) < nsec:
                        idx.append((u >> (k * bits)) & mask)
            while len(idx) < nsec: idx.append(0)
        for i in idx:
            nm = names[i] if i < len(names) else "?"
            total += 1
            if nm.startswith("minecraft:air") or nm.startswith("minecraft:cave_air") or nm.startswith("minecraft:void_air"):
                air += 1
            else:
                c[nm] += 1
    elif "Blocks" in s:                          # legacy numeric ids
        blk = s.get("Blocks"); add = s.get("Add"); d = s.get("Data")
        for i, b in enumerate(blk or []):
            hi = 0
            if isinstance(add, (bytes, bytearray)) and len(add) * 2 > i:
                nib = add[i // 2]; hi = ((nib >> 4) & 15) if i % 2 == 0 else (nib & 15)
            full = (hi << 8) | b
            dv = 0
            if isinstance(d, (bytes, bytearray)) and len(d) * 2 > i:
                nib = d[i // 2]; dv = ((nib >> 4) & 15) if i % 2 == 0 else (nib & 15)
            total += 1
            if full == 0: air += 1
            else: c["id=%d:data=%d" % (full, dv)] += 1
    return total, air, c

folder = sys.argv[1]; label = sys.argv[3] if len(sys.argv) > 3 else folder
tot = 0; air = 0; blocks = Counter(); nsec = 0; nchunk = 0; ymin = 99999; ymax = -99999
for rp in sorted(glob.glob(os.path.join(folder, "region", "*.mca"))):
    for cx, cz, comp, ch in iter_region(rp):
        nchunk += 1
        lvl = ch.get("Level", ch)
        secs = lvl.get("sections") or lvl.get("Sections") or []
        for s in secs:
            y = s.get("Y")
            t, a, c = decode_section(s)
            if not c: continue
            nsec += 1
            tot += t; air += a; blocks.update(c)
            if y is not None:
                ymin = min(ymin, y); ymax = max(ymax, y)
print("### %s" % label)
print("  chunks=%d  sections_with_blocks=%d" % (nchunk, nsec))
print("  total slots=%d  air=%d  non-air=%d" % (tot, air, tot - air))
print("  distinct block descr=%d" % len(blocks))
print("  section Y range containing blocks: %s .. %s" % (ymin, ymax))
print("  TOP 20:")
for k, v in blocks.most_common(20):
    print("     %-46s %d" % (k, v))