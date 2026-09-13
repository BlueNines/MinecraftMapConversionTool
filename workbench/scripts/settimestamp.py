# -*- coding: utf-8 -*-
"""修改 region 文件中指定 chunk 的时间戳，或把它们全部置为某个值。

用途：验证 BlueMap 的增量渲染判定——MCARegion.listChunks(renderTime)
对每个 chunk 取头部时间戳(秒)*1000 与 renderTime 比较，>= 才加入重渲列表。

用法:
  py settimestamp.py <region目录> all <秒值>           # 全部设为该值
  py settimestamp.py <region目录> pick <秒值> <数量>   # 随机选 N 个 chunk 设为该值
  py settimestamp.py <region目录> report               # 报告时间戳分布
"""
import struct, sys, os, glob, random


def ts_offset(i):
    return i * 4 + 4096


def read_ts(path):
    with open(path, 'rb') as f:
        data = f.read()
    out = {}
    if len(data) < 8192:
        return out
    for i in range(1024):
        off = struct.unpack('>I', b'\x00' + data[i * 4:i * 4 + 3])[0]
        if off == 0:
            continue
        out[i] = struct.unpack('>I', data[ts_offset(i):ts_offset(i) + 4])[0]
    return out


def write_ts(path, targets, value):
    with open(path, 'r+b') as f:
        for i in targets:
            f.seek(ts_offset(i))
            f.write(struct.pack('>I', value & 0xFFFFFFFF))


def main():
    d = sys.argv[1]
    mode = sys.argv[2] if len(sys.argv) > 2 else 'report'
    files = sorted(glob.glob(os.path.join(d, '*.mca')))

    if mode == 'report':
        allts = {}
        n = 0
        for p in files:
            for k, v in read_ts(p).items():
                allts[v] = allts.get(v, 0) + 1
                n += 1
        print('区域文件 %d 个, chunk %d 个' % (len(files), n))
        for v in sorted(allts)[:10]:
            print('  时间戳 %d : %d 个 chunk' % (v, allts[v]))
        return

    if mode == 'all':
        value = int(sys.argv[3])
        total = 0
        for p in files:
            ts = read_ts(p)
            write_ts(p, list(ts.keys()), value)
            total += len(ts)
        print('已把 %d 个 chunk 的时间戳设为 %d' % (total, value))
        return

    if mode == 'pick':
        value = int(sys.argv[3])
        count = int(sys.argv[4])
        # 收集所有 (文件, chunk索引)
        pool = []
        for p in files:
            for k in read_ts(p).keys():
                pool.append((p, k))
        random.seed(42)
        chosen = random.sample(pool, min(count, len(pool)))
        by_file = {}
        for p, k in chosen:
            by_file.setdefault(p, []).append(k)
        for p, ks in by_file.items():
            write_ts(p, ks, value)
        print('已把 %d 个 chunk 的时间戳设为 %d，分布于 %d 个区域文件' % (len(chosen), value, len(by_file)))
        for p, ks in list(by_file.items())[:5]:
            print('   %s : %s' % (os.path.basename(p), ks[:8]))
        return

    print('未知模式，用 all / pick / report')


if __name__ == '__main__':
    main()
