# -*- coding: utf-8 -*-
"""只比较 chunk 的 NBT 内容本身，忽略 zlib 压缩层面的差异。

背景：region 文件里每个 chunk 是「30 字节左右的 NBT 数据」经 zlib 压缩后的产物。
同一份 NBT，zlib 输出可能因压缩器版本/参数/字典状态而不同字节，
但解压后应完全一致。若只比压缩后的字节，会把「内容其实没变」误判为「变了」。

用法: py nbtcompare.py <A目录> <B目录>
"""
import struct, sys, os, glob, zlib


def load_chunks(path):
    with open(path, 'rb') as f:
        data = f.read()
    out = {}
    if len(data) < 8192:
        return out
    for i in range(1024):
        off = struct.unpack('>I', b'\x00' + data[i * 4:i * 4 + 3])[0]
        ts = struct.unpack('>I', data[i * 4 + 4096:i * 4 + 4100])[0]
        if off == 0:
            continue
        pos = off * 4096
        if pos + 5 > len(data):
            continue
        ln = struct.unpack('>I', data[pos:pos + 4])[0]
        comp = data[pos + 4]
        raw = data[pos + 5:pos + 4 + ln]
        payload = None
        if comp == 2 and len(raw) > 2:
            try:
                payload = zlib.decompress(raw[2:])   # 跳过 2 字节 zlib 头
            except Exception:
                pass
        if payload is None:
            payload = raw                            # 解压失败则退化为比原始字节
        out[i] = (ts, payload)
    return out


def main(a_dir, b_dir, detail=0):
    files = sorted(os.path.basename(p) for p in glob.glob(os.path.join(a_dir, '*.mca')))
    tot = same = diff = 0
    ts_zero = ts_nonzero = 0
    changed = []
    for name in files:
        pa, pb = os.path.join(a_dir, name), os.path.join(b_dir, name)
        if not os.path.exists(pb):
            continue
        ca, cb = load_chunks(pa), load_chunks(pb)
        for k in set(ca) & set(cb):
            tot += 1
            ta, da = ca[k]
            tb, db = cb[k]
            if ta == 0:
                ts_zero += 1
            else:
                ts_nonzero += 1
            if da == db:
                same += 1
            else:
                diff += 1
                if len(changed) < detail:
                    changed.append((name, k, ta, tb))
    print('区域文件数        : %d' % len(files))
    print('共有 chunk 数     : %d' % tot)
    print('  解压后内容相同  : %d' % same)
    print('  解压后内容不同  : %d' % diff)
    pct = 100.0 * diff / tot if tot else 0
    print('  内容变化占比    : %.2f%%' % pct)
    print('时间戳为 0 的 chunk: %d' % ts_zero)
    print('时间戳非 0 的 chunk: %d' % ts_nonzero)
    for c in changed:
        print('   变化: %s chunk#%d  ts %s -> %s' % c)


if __name__ == '__main__':
    d = int(sys.argv[3]) if len(sys.argv) > 3 else 0
    main(sys.argv[1], sys.argv[2], d)
