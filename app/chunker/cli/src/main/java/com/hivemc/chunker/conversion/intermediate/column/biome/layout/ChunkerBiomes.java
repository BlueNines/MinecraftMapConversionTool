package com.hivemc.chunker.conversion.intermediate.column.biome.layout;

import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;

import java.util.List;
import java.util.function.Function;

/**
 * The biomes for a column, represented in various formats.
 */
public interface ChunkerBiomes {
    /**
     * Get the biomes as a top-down view [x][z] array in the size of [16][16].
     *
     * @param fallbackBiome the biome to use if no biome is currently present.
     * @return the biome array.
     */
    ChunkerBiome[] asColumn(ChunkerBiome fallbackBiome);

    /**
     * 取俯视的 [x][z] 群系数组（长度 256），但每一列在指定的地表高度处取样。
     *
     * <p>3D 群系横跨多个 section，哪一层代表这一列取决于地形实际在哪。固定取某一个 section
     * 只在该 section 恰好与地表同群系时才是对的 —— 对悬空在虚空里的地图会整幅取到虚空群系。</p>
     *
     * @param fallbackBiome  没有任何群系时使用的群系。
     * @param surfaceHeights 每一列地表方块的 Y，下标为 {@code (z << 4) | x}；
     *                       该列完全没有方块时用 {@link Integer#MIN_VALUE}，此时用 fallbackBiome。
     * @return 群系数组。
     */
    default ChunkerBiome[] asColumn(ChunkerBiome fallbackBiome, int[] surfaceHeights) {
        return asColumn(fallbackBiome);
    }

    /**
     * 把群系数据整体上移若干 section，与方块数据保持同一坐标系。
     *
     * <p>世界在写入前会被整体上移（远低于 Y=0 的地形必须移入 0~255），此时方块的 section Y 变了，
     * 而带 Y 语义的群系数据必须一起变。否则按地表高度采样时会拿“移位后高度”去比“移位前的层号”，
     * 结果取到的是地表上方 shift 层的群系。</p>
     *
     * @param shiftSections 上移的 section 数。
     */
    default void shiftSections(int shiftSections) {
        // 默认无 Y 语义的实现（如俯视单层群系）不需要搬。
    }

    /**
     * Get the biomes as clusters of 4x4 for the column.
     *
     * @param fallbackBiome the biome to use if no biome is currently present.
     * @return the array which is 1024 in length as it's a fixed size.
     */
    ChunkerBiome[] as4X4(ChunkerBiome fallbackBiome);

    /**
     * Get the biomes as a list of present chunks with a palette for each chunk.
     *
     * @return a list of the palettes.
     */
    List<Palette<ChunkerBiome>> asPalette();

    /**
     * Get the biomes as a palette of 4 x 4 block clusters.
     *
     * @param chunkYIndex the chunkY index to fetch (0 based).
     * @return a palette of the biomes.
     */
    Palette<ChunkerBiome> as4X4Palette(int chunkYIndex);

    /**
     * Remap the biomes using a specific mapping.
     *
     * @param mapping the function to use to map the biomes.
     */
    void remap(Function<ChunkerBiome, ChunkerBiome> mapping);
}
