package com.hivemc.chunker.conversion.encoding.java.base.writer;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps track of which chunks actually changed, and timestamps the ones that did.
 * <p>
 * This exists to make the preview viewer update live instead of only after a full re-render.
 * <p>
 * The viewer works out what to re-render by asking each region file, for every chunk in it, whether the chunk's own
 * timestamp (a 4-byte field in the region header, in seconds) is at or after the time the map was last rendered. Any
 * chunk whose timestamp is older is skipped. The converted worlds used to come out with that field left at zero -
 * nothing in the writer ever set it - so every chunk looked ancient, and the viewer considered the entire map already
 * up to date no matter what had changed. Re-converting a world after editing a mapping therefore changed nothing on
 * screen.
 * <p>
 * Writing a fresh timestamp to every chunk would swing the problem the other way: the whole map would look new and
 * the viewer would re-render everything. So the timestamp is only written where the chunk's bytes differ from what is
 * already on disk. Unchanged chunks keep their old timestamp and are skipped; changed chunks get the current time and
 * are re-rendered. On the very first conversion there is nothing on disk to compare against, so everything is
 * written and timestamped, exactly as before.
 * <p>
 * The comparison is deliberately done against the bytes on disk rather than a remembered hash: it needs no state
 * carried between runs, and it stays correct even if the output folder was written by a different process or left
 * over from an earlier session.
 */
public class IncrementalWriter implements AutoCloseable {
    /**
     * Totals across every writer in the process.
     * <p>
     * A conversion creates one writer per world, and the interesting number - how much of the map actually changed -
     * spans all of them, so the count is kept here rather than per instance. One conversion runs at a time, so a
     * single set of counters is enough; {@link #resetCounters()} is called at the start of each run.
     */
    private static final java.util.concurrent.atomic.AtomicLong CHANGED = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong UNCHANGED_COUNT = new java.util.concurrent.atomic.AtomicLong();
    private static final java.util.concurrent.atomic.AtomicLong EMPTY_SKIPPED = new java.util.concurrent.atomic.AtomicLong();

    private final Map<File, CompressedChunkLookup> lookups = new ConcurrentHashMap<>();
    private final int timestampSeconds;

    /**
     * Forget the counts from the previous conversion.
     */
    public static void resetCounters() {
        CHANGED.set(0);
        UNCHANGED_COUNT.set(0);
        EMPTY_SKIPPED.set(0);
    }

    /** 报告首次输出省略的全空气区块柱。 */
    public static long getEmptySkipped() { return EMPTY_SKIPPED.get(); }

    /** 仅跳过磁盘上不存在的空柱，保留热更新写空旧建筑的机会。 */
    public boolean skipNewEmptyColumn(File file, int x, int z) throws IOException {
        if (file.isFile() && lookups.computeIfAbsent(file, CompressedChunkLookup::new).contains(x, z)) return false;
        EMPTY_SKIPPED.incrementAndGet();
        return true;
    }

    /** 关闭全部比较句柄，使反复分析不依赖 GC 解锁临时输出。 */
    @Override public void close() throws IOException {
        IOException failure = null;
        for (CompressedChunkLookup lookup : lookups.values()) {
            try { lookup.close(); } catch (IOException e) { failure = e; }
        }
        lookups.clear();
        if (failure != null) throw failure;
    }

    /**
     * How many chunks differed from what was already in the output folder and were rewritten.
     *
     * @return the count.
     */
    public static long getWritten() {
        return CHANGED.get();
    }

    /**
     * How many chunks were identical to what was already in the output folder and were left untouched.
     *
     * @return the count.
     */
    public static long getUnchanged() {
        return UNCHANGED_COUNT.get();
    }

    /**
     * @param timestampSeconds the value to stamp onto chunks that changed, in seconds since the epoch. Passing the
     *                         current time means the viewer treats those chunks as newer than its last render.
     */
    public IncrementalWriter(int timestampSeconds) {
        this.timestampSeconds = timestampSeconds;
    }

    /**
     * Decide whether a chunk needs writing, and record that decision.
     *
     * @param regionFile the region file this chunk belongs to.
     * @param chunkX     the chunk's world X, used to locate its slot in the region header.
     * @param chunkZ     the chunk's world Z.
     * @param bytes      the compressed chunk data about to be written.
     * @return true if the data differs from what is on disk and should be written.
     */
    public boolean shouldWrite(File regionFile, int chunkX, int chunkZ, byte[] bytes) {
        try {
            CompressedChunkLookup lookup = lookups.computeIfAbsent(regionFile, CompressedChunkLookup::new);
            byte[] existing = lookup.read(chunkX, chunkZ);
            if (existing != null && java.util.Arrays.equals(existing, bytes)) {
                UNCHANGED_COUNT.incrementAndGet();
                return false;
            }
            CHANGED.incrementAndGet();
            return true;
        } catch (Exception e) {
            // If the existing data cannot be read for any reason, write the chunk. Being conservative here costs a
            // re-render of that chunk, whereas skipping it could leave a stale chunk on screen.
            CHANGED.incrementAndGet();
            return true;
        }
    }

    /**
     * The timestamp to write for a chunk that has changed.
     *
     * @return seconds since the epoch.
     */
    public int timestampSeconds() {
        return timestampSeconds;
    }

    /**
     * Reads compressed chunk data back out of a region file, by offset table rather than by decoding it.
     */
    private static final class CompressedChunkLookup {
        private final File file;
        private RandomAccessFile handle;

        private CompressedChunkLookup(File file) {
            this.file = file;
        }

        /** 仅读位置表，不为一个空柱分配压缩数据缓冲。 */
        private synchronized boolean contains(int x, int z) throws IOException {
            if (!file.isFile() || file.length() < 8192) return false;
            if (handle == null) handle = new RandomAccessFile(file, "r");
            handle.seek(((x & 31) + ((z & 31) << 5)) * 4L);
            return (handle.readInt() >>> 8) != 0;
        }

        /** 与读取使用同一把锁，正常结束后立即释放句柄。 */
        private synchronized void close() throws IOException {
            if (handle != null) { handle.close(); handle = null; }
        }

        private synchronized byte[] read(int chunkX, int chunkZ) throws IOException {
            if (!file.isFile() || file.length() < 8192) return null;
            if (handle == null) {
                handle = new RandomAccessFile(file, "r");
            }

            int slot = ((chunkX & 31) + ((chunkZ & 31) << 5)) << 2;
            handle.seek(slot);
            int offset = (handle.read() << 16) | (handle.read() << 8) | handle.read();
            handle.read(); // sector count, not needed to read the data back
            if (offset == 0) return null;

            long position = (long) offset << 12;
            if (position + 5 > handle.length()) return null;
            handle.seek(position);
            int length = handle.readInt();
            if (length <= 1 || position + 4 + length > handle.length()) return null;

            // The stored length counts the compression type byte plus the data. Only inline zlib is compared: an
            // oversized chunk lives in a separate .mcc file, and anything else cannot be meaningfully diffed here.
            byte[] payload = new byte[length];
            handle.readFully(payload);
            if (payload[0] != 2) return null;
            return java.util.Arrays.copyOfRange(payload, 1, payload.length);
        }

        @Override
        public String toString() {
            return file.getName();
        }
    }
}
