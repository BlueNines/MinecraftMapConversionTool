# -*- coding: utf-8 -*-
"""把 NBT 递归规范化（Compound 字段按名字排序）后比对，
判定两次转换的差异是不是纯粹来自字段顺序。

结论如果是「全部相同」，就说明内容确实一致，只有字段写出顺序不稳定。

用法: py nbtcanon.py <A目录> <B目录>
"""
import struct, sys, os, glob, zlib
from nbtkeydiff import R, parse


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
        if comp == 2:
            try:
                out[i] = zlib.decompress(raw)
            except Exception:
                pass
    return out


def canon(v):
    """递归地把所有 Compound 变成按 key 排序的有序元组，便于比较。"""
    if isinstance(v, dict):
        # v 的每个值是 (tagType, payload)，排序后连 tagType 一起保留
        return tuple((k, v[k][0], canon(v[k][1])) for k in sorted(v))
    if isinstance(v, list):
        return tuple(canon(x) for x in v)
    if isinstance(v, tuple):
        return v
    return v


def main():
    a_dir, b_dir = sys.argv[1], sys.argv[2]
    names = sorted(os.path.basename(p) for p in glob.glob(os.path.join(a_dir, '*.mca')))
    raw_same = raw_diff = 0
    canon_same = canon_diff = 0
    examples = []

    for name in names:
        ca = load(os.path.join(a_dir, name))
        cb = load(os.path.join(b_dir, name))
        for k in sorted(set(ca) & set(cb)):
            if ca[k] == cb[k]:
                raw_same += 1
            else:
                raw_diff += 1
            try:
                _, _, va = parse(R(ca[k]))
                _, _, vb = parse(R(cb[k]))
            except Exception:
                canon_diff += 1
                continue
            xa, xb = canon(va), canon(vb)
            if xa == xb:
                canon_same += 1
            else:
                canon_diff += 1
                if len(examples) < 3:
                    examples.append((name, k))

    print('原始字节  相同 %d / 不同 %d' % (raw_same, raw_diff))
    print('规范化后  相同 %d / 不同 %d' % (canon_same, canon_diff))
    for name, k in examples:
        print('  仍不同: %s chunk#%d' % (name, k))


if __name__ == '__main__':
    main()
