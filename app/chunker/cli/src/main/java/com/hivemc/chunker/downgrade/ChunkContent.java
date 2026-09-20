package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import java.io.IOException;

/** 按实际方块索引判断内容，不把生成状态或玩家停留时间当作建筑证据。 */
public final class ChunkContent {
    /** 纯工具类。 */
    private ChunkContent() {}

    /** 保留方块、方块实体及实体；未知或损坏的调色板必须报错。 */
    public static boolean hasContent(CompoundTag root) throws IOException {
        CompoundTag column = root.getCompound("Level", root);
        for (String name : new String[]{"Entities", "entities", "TileEntities", "block_entities"}) {
            var list = column.getList(name, CompoundTag.class, null);
            if (list != null && list.size() > 0) return true;
        }
        var sections = column.getList("sections", CompoundTag.class, null);
        if (sections == null) sections = column.getList("Sections", CompoundTag.class, null);
        if (sections == null) return false;
        for (CompoundTag section : sections) {
            if (hasBlocks(section, root.getInt("DataVersion", 0))) return true;
        }
        return false;
    }

    /** 支持旧数字 ID、1.13 连续位数组和 1.16+ 对齐位数组。 */
    static boolean hasBlocks(CompoundTag section, int version) throws IOException {
        byte[] blocks = section.getByteArray("Blocks", null);
        if (blocks != null) return anyNonzero(blocks) || anyNonzero(section.getByteArray("Add", null));
        CompoundTag states = section.getCompound("block_states");
        var palette = states == null ? section.getList("Palette", CompoundTag.class, null)
                : states.getList("palette", CompoundTag.class, null);
        if (palette == null) {
            if (states != null || section.contains("BlockStates")) throw new IOException("区块调色板缺失，不能判定为空。 ");
            return false; // 只有光照/生物群系数据的 section。
        }
        if (palette.size() == 0) throw new IOException("区块调色板为空。 ");
        boolean[] nonair = new boolean[palette.size()];
        int position = 0;
        for (CompoundTag entry : palette) {
            String name = entry.getString("Name", null);
            if (name == null) throw new IOException("调色板条目缺少 Name。 ");
            nonair[position++] = !name.equals("minecraft:air") && !name.equals("minecraft:cave_air") && !name.equals("minecraft:void_air");
        }
        if (nonair.length == 1) return nonair[0];
        long[] values = states == null ? section.getLongArray("BlockStates", null) : states.getLongArray("data", null);
        if (values == null) throw new IOException("多值调色板缺少方块索引。 ");
        int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(nonair.length - 1));
        boolean padded = states != null || version >= 2529;
        int perLong = 64 / bits;
        int required = padded ? (4096 + perLong - 1) / perLong : (4096 * bits + 63) / 64;
        if (values.length < required) throw new IOException("方块索引长度不足，不能跳过这个区块。 ");
        long mask = (1L << bits) - 1;
        for (int i = 0; i < 4096; i++) {
            int word = padded ? i / perLong : i * bits / 64;
            int shift = padded ? i % perLong * bits : i * bits % 64;
            long value = values[word] >>> shift;
            if (!padded && shift + bits > 64) value |= values[word + 1] << (64 - shift);
            int index = (int) (value & mask);
            if (index >= nonair.length) throw new IOException("方块索引超出调色板范围。 ");
            if (nonair[index]) return true;
        }
        return false;
    }

    /** 旧版高位 ID 也必须检查，避免把 ID 256 的方块误认为空气。 */
    private static boolean anyNonzero(byte[] bytes) {
        if (bytes != null) for (byte value : bytes) if (value != 0) return true;
        return false;
    }
}
