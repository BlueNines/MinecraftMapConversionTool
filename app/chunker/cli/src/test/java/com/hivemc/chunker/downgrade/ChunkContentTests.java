package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import com.hivemc.chunker.nbt.tags.Tag;
import java.util.Map;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 内容筛选的关键边界：实际索引、旧版 ID、损坏数据和悬挂实体。 */
class ChunkContentTests {
    /** 调色板包含石头但没有位置使用石头时，仍然是空区块。 */
    @Test void unusedPaletteEntryIsNotContent() throws Exception {
        CompoundTag section = section("minecraft:air", "minecraft:stone");
        section.getCompound("block_states").put("data", new long[256]);
        assertFalse(ChunkContent.hasBlocks(section, 4671));
        long[] values = new long[256]; values[255] = 1L << 60;
        section.getCompound("block_states").put("data", values);
        assertTrue(ChunkContent.hasBlocks(section, 4671));
    }

    /** 三种空气名称等价。 */
    @Test void allAirNamesAreEmpty() throws Exception {
        for (String name : new String[]{"minecraft:air", "minecraft:cave_air", "minecraft:void_air"})
            assertFalse(ChunkContent.hasBlocks(section(name), 4671));
    }

    /** 无索引和越界索引须失败，不能悄悄删除损坏记录。 */
    @Test void malformedPaletteFailsClosed() {
        CompoundTag section = section("minecraft:air", "minecraft:stone");
        assertThrows(IOException.class, () -> ChunkContent.hasBlocks(section, 4671));
        long[] values = new long[256]; values[0] = 15;
        section.getCompound("block_states").put("data", values);
        assertThrows(IOException.class, () -> ChunkContent.hasBlocks(section, 4671));
    }

    /** 旧格式的 Add 高位不能漏掉。 */
    @Test void legacyHighBlockIdIsContent() throws Exception {
        CompoundTag section = new CompoundTag(); section.put("Blocks", new byte[4096]);
        assertFalse(ChunkContent.hasBlocks(section, 1343));
        byte[] add = new byte[2048]; add[0] = 1; section.put("Add", add);
        assertTrue(ChunkContent.hasBlocks(section, 1343));
    }

    /** 仅含展示框的区块也必须保留。 */
    @Test void hangingEntityIsContent() throws Exception {
        CompoundTag root = new CompoundTag();
        ListTag<CompoundTag, Map<String, Tag<?>>> entities = new ListTag<>(TagType.COMPOUND);
        CompoundTag frame = new CompoundTag(); frame.put("id", "minecraft:glow_item_frame"); entities.add(frame);
        root.put("Entities", entities);
        assertTrue(ChunkContent.hasContent(root));
    }

    /** 构造最小现代方块段。 */
    private static CompoundTag section(String... names) {
        CompoundTag section = new CompoundTag(), states = new CompoundTag();
        ListTag<CompoundTag, Map<String, Tag<?>>> palette = new ListTag<>(TagType.COMPOUND);
        for (String name : names) { CompoundTag entry = new CompoundTag(); entry.put("Name", name); palette.add(entry); }
        states.put("palette", palette); section.put("block_states", states);
        return section;
    }
}
