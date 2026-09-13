# -*- coding: utf-8 -*-
"""逐 chunk 比对两个 region 目录，区分「内容变了」与「只是时间戳变了」。
Anvil region 文件结构：
  0..4096      1024 * 4B  偏移表 (3B sector offset + 1B sector count)
  4096..8192   1024 * 4B  时间戳表
  之后是 chunk 数据：4B 长度 + 1B 压缩类型 + 压缩载荷
"""
import struct, sys, os, glob


def load_chunks(path):
    with open(path, 'rb') as f:
        data = f.read()
    if len(data) < 8192:
        return {}
    out = {}
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
        payload = data[pos + 5:pos + 4 + ln]
        out[i] = (ts, comp, payload)
    return out


def main(a_dir, b_dir):
    files = sorted(os.path.basename(p) for p in glob.glob(os.path.join(a_dir, '*.mca')))
    tot_chunks = 0
    content_same = 0
    content_diff = 0
    ts_same = 0
    ts_diff = 0
    file_diff = 0
    only_a = 0
    only_b = 0
    diff_ts_values = set()
    same_ts_values = set()

    for name in files:
        pa = os.path.join(a_dir, name)
        pb = os.path.join(b_dir, name)
        if not os.path.exists(pb):
            only_a += 1
            continue
        ca = load_chunks(pa)
        cb = load_chunks(pb)
        if set(ca) != set(cb):
            only_a += len(set(ca) - set(cb))
            only_b += len(set(cb) - set(ca))
        for k in set(ca) & set(cb):
            tot_chunks += 1
            ta, compa, da = ca[k]
            tb, compb, db = cb[k]
            if da == db and compa == compb:
                content_same += 1
            else:
                content_diff += 1
                diff_ts_values.add((ta, tb))
            if ta == tb:
                ts_same += 1
                same_ts_values.add(ta)
            else:
                ts_diff += 1

    print('区域文件数        : %d' % len(files))
    print('共有 chunk 数     : %d' % tot_chunks)
    print('  内容相同        : %d' % content_same)
    print('  内容不同        : %d' % content_diff)
    print('  仅 A 有         : %d' % only_a)
    print('  仅 B 有         : %d' % only_b)
    print('chunk 时间戳相同  : %d' % ts_same)
    print('chunk 时间戳不同  : %d' % ts_diff)
    if content_diff:
        print('变化 chunk 的时间戳样例 (A->B): %s' % sorted(diff_ts_values)[:6])
    if same_ts_values:
        print('未变 chunk 的时间戳样例      : %s' % sorted(same_ts_values)[:6])
    pct = 100.0 * content_diff / tot_chunks if tot_chunks else 0
    print('内容变化占比      : %.2f%%' % pct)


if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
