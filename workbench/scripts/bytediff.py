# -*- coding: utf-8 -*-
"""直接找出 chunk 数据里第一个不同的字节位置，并打印两侧附近的文本，
用来看差异落在哪个 NBT 字段上（NBT 的字段名是明文）。

用法: py bytediff.py <A目录> <B目录>
"""
import struct, sys, os, glob, zlib


def load(path):
    with open(path, 'rb') as f:
        data = f.read()
    out = {}
    if len(data) < 8192:
        return out
    for i in range(1024):
        off = struct.unpack('>I', b'\x00' + data[i * 4:i * 4 + 3])[0]
        if off == 0:
            continue
        pos = off * 4096
        if pos + 5 > len(data):
            continue
        ln = struct.unpack('>I', data[pos:pos + 4])[0]
        comp = data[pos + 4]
        raw = data[pos + 5:pos + 4 + ln]
        payload = None
        if comp == 2:
            # Chunker 用 DeflaterOutputStream 写出，是完整的 zlib 流（带 2 字节头），
            # 所以直接解整段。之前写成 raw[2:]（跳过头部）会失败，
            # 一失败就回退成比较压缩字节，得出的结论是错的。
            try:
                payload = zlib.decompress(raw)
            except Exception:
                try:
                    payload = zlib.decompress(raw[2:], -15)
                except Exception:
                    payload = None
        out[i] = (raw, payload)
    return out


def show(b):
    return ''.join(chr(c) if 32 <= c < 127 else '.' for c in b)


def main():
    a_dir, b_dir = sys.argv[1], sys.argv[2]
    names = sorted(os.path.basename(p) for p in glob.glob(os.path.join(a_dir, '*.mca')))
    raw_eq = raw_ne = dec_eq = dec_ne = 0
    shown = 0
    for name in names:
        ca = load(os.path.join(a_dir, name))
        cb = load(os.path.join(b_dir, name))
        for k in sorted(set(ca) & set(cb)):
            ra, da = ca[k]
            rb, db = cb[k]
            if ra == rb:
                raw_eq += 1
            else:
                raw_ne += 1
            if da == db:
                dec_eq += 1
                continue
            dec_ne += 1
            if shown >= 2:
                continue
            shown += 1
            print('===== %s chunk#%d =====' % (name, k))
            print('  解压后长度 A=%s B=%s' % (len(da) if da else None, len(db) if db else None))
            if da is None or db is None:
                continue
            n = min(len(da), len(db))
            off = next((i for i in range(n) if da[i] != db[i]), n)
            print('  第一个不同字节位置: %d' % off)
            print('  A: ...%s...' % show(da[max(0, off - 70):off + 70]))
            print('  B: ...%s...' % show(db[max(0, off - 70):off + 70]))
    print('')
    print('压缩字节  相同 %d / 不同 %d' % (raw_eq, raw_ne))
    print('解压字节  相同 %d / 不同 %d' % (dec_eq, dec_ne))


if __name__ == '__main__':
    main()
