# -*- coding: utf-8 -*-
"""解析 chunk 的 NBT，逐字段比对两个输出，找出到底哪个字段不确定。

转换两次得到的内容不同，但需要知道「差在哪里」才能修：
  - 如果差在 palette 顺序、Entities 顺序 → 语义相同，只是写出顺序没固定
  - 如果差在方块数据本身 → 转换逻辑真的不确定

用法: py nbtkeydiff.py <A目录> <B目录> [最多看几个]
"""
import struct, sys, os, glob, zlib, io


def load_chunks(path):
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
        if comp == 2 and len(raw) > 2:
            try:
                out[i] = zlib.decompress(raw[2:])
            except Exception:
                pass
    return out


class R:
    def __init__(self, b):
        self.b, self.i = b, 0

    def u1(self):
        v = self.b[self.i]; self.i += 1; return v

    def u2(self):
        v = struct.unpack('>H', self.b[self.i:self.i + 2])[0]; self.i += 2; return v

    def i4(self):
        v = struct.unpack('>i', self.b[self.i:self.i + 4])[0]; self.i += 4; return v

    def i8(self):
        v = struct.unpack('>q', self.b[self.i:self.i + 8])[0]; self.i += 8; return v

    def f4(self):
        v = struct.unpack('>f', self.b[self.i:self.i + 4])[0]; self.i += 4; return v

    def f8(self):
        v = struct.unpack('>d', self.b[self.i:self.i + 8])[0]; self.i += 8; return v

    def raw(self, n):
        v = self.b[self.i:self.i + n]; self.i += n; return v

    def s(self):
        n = self.u2()
        return self.raw(n).decode('utf-8', 'replace')


def parse(r):
    """返回 (tagType, name, value)；value 类型依 tagType 而定。"""
    t = r.u1()
    if t == 0:
        return (0, '', None)
    name = r.s()
    return (t, name, payload(r, t))


def payload(r, t):
    if t == 1:
        v = struct.unpack('>b', r.raw(1))[0]; return v
    if t == 2:
        v = struct.unpack('>h', r.raw(2))[0]; return v
    if t == 3:
        return r.i4()
    if t == 4:
        return r.i8()
    if t == 5:
        return r.f4()
    if t == 6:
        return r.f8()
    if t == 7:
        n = r.i4(); return ('bytes', r.raw(n))
    if t == 8:
        return r.s()
    if t == 9:
        it = r.u1(); n = r.i4()
        return [payload(r, it) for _ in range(n)]
    if t == 10:
        out = {}
        while True:
            ct = r.u1()
            if ct == 0:
                break
            cn = r.s()
            out[cn] = (ct, payload(r, ct))
        return out
    if t == 11:
        n = r.i4(); return tuple(struct.unpack('>%di' % n, r.raw(4 * n)))
    if t == 12:
        n = r.i4(); return tuple(struct.unpack('>%dq' % n, r.raw(8 * n)))
    raise ValueError('tag %d' % t)


def diff(a, b, path, out, limit):
    if len(out) >= limit:
        return
    if type(a) != type(b):
        out.append('%s: 类型不同 %s vs %s' % (path, type(a).__name__, type(b).__name__)); return
    if isinstance(a, dict):
        for k in sorted(set(a) | set(b)):
            if k not in a:
                out.append('%s.%s: 只在 B' % (path, k))
            elif k not in b:
                out.append('%s.%s: 只在 A' % (path, k))
            else:
                diff(a[k][1], b[k][1], '%s.%s' % (path, k), out, limit)
    elif isinstance(a, list):
        if len(a) != len(b):
            out.append('%s: 列表长度 %d vs %d' % (path, len(a), len(b))); return
        for i in range(len(a)):
            diff(a[i], b[i], '%s[%d]' % (path, i), out, limit)
    elif isinstance(a, tuple):
        if a != b:
            # 找第一个不同的下标
            for i in range(min(len(a), len(b))):
                if a[i] != b[i]:
                    out.append('%s: 数组第 %d 项 %s vs %s (共 %d 项)' % (path, i, a[i], b[i], len(a)))
                    return
            out.append('%s: 数组长度 %s vs %s' % (path, len(a), len(b)))
    elif a != b:
        sa, sb = str(a), str(b)
        out.append('%s: %s vs %s' % (path, sa[:60], sb[:60]))


def main():
    a_dir, b_dir = sys.argv[1], sys.argv[2]
    show = int(sys.argv[3]) if len(sys.argv) > 3 else 2
    names = sorted(os.path.basename(p) for p in glob.glob(os.path.join(a_dir, '*.mca')))
    shown = 0
    errors = 0
    for name in names:
        if shown >= show:
            break
        ca = load_chunks(os.path.join(a_dir, name))
        cb = load_chunks(os.path.join(b_dir, name))
        for k in sorted(set(ca) & set(cb)):
            if ca[k] == cb[k]:
                continue
            try:
                ta, _, va = parse(R(ca[k]))
                tb, _, vb = parse(R(cb[k]))
            except Exception as e:
                errors += 1
                if errors <= 3:
                    print('!! %s chunk#%d 解析失败: %r' % (name, k, e))
                    print('   A 前 40 字节: %s' % ca[k][:40].hex())
                continue
            out = []
            diff(va, vb, 'chunk', out, 12)
            print('===== %s chunk#%d (%d 字节) =====' % (name, k, len(ca[k])))
            for line in out:
                print('   ' + line)
            shown += 1
            if shown >= show:
                break
    print('（解析失败 %d 个）' % errors)


if __name__ == '__main__':
    try:
        main()
    except Exception as e:
        import traceback
        traceback.print_exc()
