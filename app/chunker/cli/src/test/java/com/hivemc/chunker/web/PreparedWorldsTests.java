package com.hivemc.chunker.web;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.conversion.encoding.java.base.writer.IncrementalWriter;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 验证筛选副本、邻区保留、源完整性和清空旧输出的语义。 */
class PreparedWorldsTests {
    @TempDir Path temporary;

    /** 保留近邻、跳过远方全空区块，缓存不触碰原图。 */
    @Test void keepsContextAndOriginalBytes() throws Exception {
        Path source = temporary.resolve("original"); Files.createDirectories(source);
        Files.write(source.resolve("level.dat"), new byte[]{1, 2, 3});
        Path file = source.resolve("region/r.0.0.mca");
        write(file, 0, column(true)); write(file, 1, column(false)); write(file, 10, column(false));
        byte[] original = Files.readAllBytes(file);
        PreparedWorlds service = new PreparedWorlds(temporary.resolve("cache"));
        var prepared = service.prepare(source);
        assertEquals(3, prepared.summary().storedChunks());
        assertEquals(1, prepared.summary().contentChunks());
        assertEquals(1, prepared.summary().contextChunks());
        assertEquals(1, prepared.summary().skippedChunks());
        assertArrayEquals(original, Files.readAllBytes(file));
        try (RandomAccessFile copy = new RandomAccessFile(prepared.directory().resolve("region/r.0.0.mca").toFile(), "r")) {
            assertNotEquals(0, copy.readInt()); assertNotEquals(0, copy.readInt());
            copy.seek(40); assertEquals(0, copy.readInt());
        }
        assertEquals(prepared.directory(), service.prepare(source).directory());
        write(file, 11, column(true));
        assertNotEquals(prepared.directory(), service.prepare(source).directory());
    }

    /** 旧柱变空必须写入，不能因跳过空白而残留旧建筑。 */
    @Test void existingChunkMustStillBeCleared() throws Exception {
        Path region = temporary.resolve("region/r.0.0.mca");
        try (IncrementalWriter writer = new IncrementalWriter(123)) {
            assertTrue(writer.skipNewEmptyColumn(region.toFile(), 0, 0));
            write(region, 0, column(true));
            assertFalse(writer.skipNewEmptyColumn(region.toFile(), 0, 0));
            assertTrue(writer.skipNewEmptyColumn(region.toFile(), 1, 0));
        }
        Files.delete(region);
    }

    /** 构造完整旧版列，其非空内容由 Blocks 确定。 */
    static CompoundTag column(boolean content) {
        CompoundTag root = new CompoundTag(), level = new CompoundTag(), section = new CompoundTag();
        byte[] blocks = new byte[4096]; if (content) blocks[0] = 1;
        section.put("Blocks", blocks); section.put("Y", (byte) 0);
        ListTag<CompoundTag, Map<String, Tag<?>>> sections = new ListTag<>(TagType.COMPOUND); sections.add(section);
        level.put("Sections", sections); root.put("Level", level); root.put("DataVersion", 1343);
        return root;
    }

    /** 追加真实压缩 MCA 记录，更新时间戳与位置表。 */
    static void write(Path file, int slot, CompoundTag root) throws Exception {
        Files.createDirectories(file.getParent());
        byte[] bytes = Tag.writeZLibJavaNBT(root);
        try (RandomAccessFile output = new RandomAccessFile(file.toFile(), "rw")) {
            if (output.length() < 8192) output.setLength(8192);
            int offset = (int) (output.length() / 4096);
            output.seek(output.length()); output.writeInt(bytes.length + 1); output.writeByte(2); output.write(bytes);
            int sectors = (bytes.length + 5 + 4095) / 4096;
            output.setLength((offset + sectors) * 4096L);
            output.seek(slot * 4L); output.writeInt(offset << 8 | sectors);
            output.seek(4096 + slot * 4L); output.writeInt(123456);
        }
    }
}
