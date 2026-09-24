package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.biome.layout.ChunkerClusterPaletteBasedBiomes;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.ShortBasedPalette;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.SingleValuePalette;
import it.unimi.dsi.fastutil.Pair;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 上移之后按地表取样群系，坐标系是否仍然对得上。
 *
 * <p>写入端用 {@code column.getHighestBlock()} 算地表，它读的是「方块 section」的 Y；而群系的
 * {@code sectionYs} 是读取时记下的 section Y。上移只搬方块不搬群系时两者差一个 shift，取到的是
 * <b>地表上方 shift 层</b>的群系 —— 不是 fallback，而是一个看上去合理、却错了的群系。</p>
 *
 * <p>所以用例故意让「地表层」与「地表上方隔一层」是不同群系：若两层恰好同值，错值就等于对值，
 * 测不出任何东西。</p>
 */
class BiomeSurfaceSamplingTests {
    private static final ChunkerBlockIdentifier STONE = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.STONE);

    /** 一整层单一群系，贡献一个 4x4x4 的 palette。 */
    private static Palette<ChunkerBiome> layer(ChunkerBiome biome) {
        ShortBasedPalette<ChunkerBiome> palette = new ShortBasedPalette<>(1, 4);
        palette.getOrCreateKey(biome);
        for (int x = 0; x < 4; x++) {
            for (int y = 0; y < 4; y++) {
                for (int z = 0; z < 4; z++) palette.setPaletteIndex(x, y, z, (short) 0);
            }
        }
        return palette;
    }

    /**
     * 只在指定 section 有石头的柱子；群系比地形多一层。
     *
     * <p>地表层 sectionY 为 SNOWY_PLAINS，地表上方隔两层再放一层 THE_VOID（那里没有方块）。
     * 一旦坐标错位一个 shift，取到的就是这层虚空群系。</p>
     */
    private static ChunkerColumn columnWithTerrainAt(int sectionY) {
        ChunkerColumn column = new ChunkerColumn(new ChunkCoordPair(0, 0));

        ChunkerChunk chunk = new ChunkerChunk((byte) sectionY);
        chunk.setPalette(SingleValuePalette.chunk(STONE));
        column.getChunks().put((byte) sectionY, chunk);

        List<Palette<ChunkerBiome>> layers = new ArrayList<>();
        layers.add(layer(ChunkerBiome.ChunkerVanillaBiome.SNOWY_PLAINS));
        layers.add(layer(ChunkerBiome.ChunkerVanillaBiome.THE_VOID));
        column.setBiomes(new ChunkerClusterPaletteBasedBiomes(layers, new int[]{sectionY, sectionY + 2}));
        return column;
    }

    /** 与 JavaColumnWriter.surfaceHeights 相同的算法。 */
    private static int[] surfaceHeights(ChunkerColumn column) {
        int[] heights = new int[256];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                Pair<Integer, ChunkerBlockIdentifier> highest = column.getHighestBlock(x, z, id -> !id.isAir());
                heights[(z << 4) | x] = highest == null ? Integer.MIN_VALUE : highest.left();
            }
        }
        return heights;
    }

    private static ChunkerColumn shift(ChunkerColumn column, int sections) {
        ChunkerColumn[] captured = new ChunkerColumn[1];
        ColumnConversionHandler delegate = new ColumnConversionHandler() {
            @Override public void convertColumn(ChunkerColumn c) { captured[0] = c; }
            @Override public void flushRegion(RegionCoordPair p) { }
            @Override public void flushColumns() { }
        };
        new ShiftColumnHandler(delegate, sections, 0, 15, new ShiftStats()).convertColumn(column);
        assertNotNull(captured[0], "柱子应被传递下去");
        return captured[0];
    }

    private static ChunkerBiome sampled(ChunkerColumn column) {
        return column.getBiomes().asColumn(
                ChunkerBiome.ChunkerVanillaBiome.PLAINS, surfaceHeights(column))[0];
    }

    /** 不上移：取地表自己那一层，而不是更低或更高的层。 */
    @Test
    void samplesTerrainLayerWithoutShift() {
        assertEquals(ChunkerBiome.ChunkerVanillaBiome.SNOWY_PLAINS, sampled(columnWithTerrainAt(4)));
    }

    /**
     * 上移两段：方块与群系必须一起搬。
     *
     * <p>只搬方块的话，地表层号变成 6，而群系层仍记着 4 与 6 → 取到的是原本属于「地形上方」的
     * THE_VOID，整张图会被写成一色的虚空群系。</p>
     */
    @Test
    void samplesTerrainLayerAfterShift() {
        ChunkerColumn shifted = shift(columnWithTerrainAt(4), 2);
        assertEquals(6, shifted.getChunks().firstByteKey(), "方块 section 应已上移 2 层");
        assertEquals(ChunkerBiome.ChunkerVanillaBiome.SNOWY_PLAINS, sampled(shifted),
                "应取地表自己那一层；得到 the_void 说明群系层号没跟着方块搬，取到了地表上方的层");
    }

    /** 上移为 0（仅在越界时才补齐）时不能顺手把群系也搬了。 */
    @Test
    void doesNotMoveBiomesWhenShiftIsZero() {
        assertEquals(ChunkerBiome.ChunkerVanillaBiome.SNOWY_PLAINS, sampled(shift(columnWithTerrainAt(4), 0)));
    }
}
