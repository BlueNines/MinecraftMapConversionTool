package com.hivemc.chunker.conversion.intermediate.column.biome.layout;

import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

/**
 * Biomes represented as 4x4 clusters for a column using a palette for each chunk.
 */
public class ChunkerClusterPaletteBasedBiomes implements ChunkerBiomes {
    private final List<Palette<ChunkerBiome>> chunks;

    /** 每个 palette 对应的 section Y；不知道时为 null（退回“取最低 section”的旧行为）。 */
    private final int[] sectionYs;

    /**
     * Create a new set of biomes based on a clustered palette per chunk.
     *
     * @param chunks the biomes with a 4x4x4 palette per chunk.
     */
    public ChunkerClusterPaletteBasedBiomes(List<Palette<ChunkerBiome>> chunks) {
        this(chunks, null);
    }

    /**
     * Create a new set of biomes based on a clustered palette per chunk, keeping track of which section each
     * palette belongs to so a column can be sampled at its surface.
     *
     * @param chunks    the biomes with a 4x4x4 palette per chunk.
     * @param sectionYs the section Y for each palette, in the same order as chunks.
     */
    public ChunkerClusterPaletteBasedBiomes(List<Palette<ChunkerBiome>> chunks, int[] sectionYs) {
        this.chunks = chunks;
        this.sectionYs = sectionYs;
    }

    @Override
    public ChunkerBiome[] asColumn(ChunkerBiome fallbackBiome) {
        ChunkerBiome[] output = new ChunkerBiome[256];

        // Use the first entry (chunk at 0)
        if (!chunks.isEmpty()) {
            Palette<ChunkerBiome> palette = chunks.get(0);
            for (int i = 0; i < 4; ++i) {
                for (int j = 0; j < 4; ++j) {
                    int x = j << 2;
                    int z = i << 2;
                    int newIndex = z << 4 | x;

                    // Lookup the key
                    ChunkerBiome biome = palette.get(j, 0, i, fallbackBiome);
                    for (int k = 0; k < 4; k++) {
                        int offX = newIndex + (k << 4);
                        Arrays.fill(output, offX, offX + 4, biome);
                    }
                }
            }
        } else {
            // Fill with the fallback if empty
            Arrays.fill(output, fallbackBiome);
        }

        return output;
    }

    @Override
    public ChunkerBiome[] asColumn(ChunkerBiome fallbackBiome, int[] surfaceHeights) {
        // 没有 section Y 或地表信息时，保持旧行为（取最低的 section）。
        if (sectionYs == null || surfaceHeights == null || chunks.isEmpty()) {
            return asColumn(fallbackBiome);
        }

        ChunkerBiome[] output = new ChunkerBiome[256];
        for (int i = 0; i < output.length; i++) {
            int x = i & 0xF;
            int z = (i >> 4) & 0xF;
            int surfaceY = surfaceHeights[i];
            if (surfaceY == Integer.MIN_VALUE) {
                // 这一列完全没有方块，用 fallback。
                output[i] = fallbackBiome;
                continue;
            }

            // 找到地表所在 section 对应的 palette。
            int paletteIndex = -1;
            for (int c = 0; c < sectionYs.length && c < chunks.size(); c++) {
                if (sectionYs[c] == (surfaceY >> 4)) {
                    paletteIndex = c;
                    break;
                }
            }
            output[i] = paletteIndex < 0
                    ? fallbackBiome
                    : chunks.get(paletteIndex).get(x >> 2, 0, z >> 2, fallbackBiome);
        }
        return output;
    }

    @Override
    public void shiftSections(int shiftSections) {
        if (sectionYs == null || shiftSections == 0) return;
        // 群系不是按方块位置存的，搬动不能靠重标 section key，只能把记下的层号一起改。
        for (int i = 0; i < sectionYs.length; i++) {
            sectionYs[i] += shiftSections;
        }
    }

    @Override
    public ChunkerBiome[] as4X4(ChunkerBiome fallbackBiome) {
        ChunkerBiome[] output = new ChunkerBiome[1024];

        if (!chunks.isEmpty()) {
            // Loop through chunks 0-15
            for (int chunkY = 0; chunkY < 16; ++chunkY) {
                Palette<ChunkerBiome> palette = chunks.size() <= chunkY ? chunks.get(chunks.size() - 1) : chunks.get(chunkY);
                for (int localX = 0; localX < 4; ++localX) {
                    for (int localY = 0; localY < 4; ++localY) {
                        for (int localZ = 0; localZ < 4; ++localZ) {
                            // Lookup the key
                            output[chunkY << 6 | localY << 4 | localZ << 2 | localX] = palette.get(localX, localY, localZ, fallbackBiome);
                        }
                    }
                }
            }
        } else {
            // Fill with the fallback if empty
            Arrays.fill(output, fallbackBiome);
        }

        return output;
    }

    @Override
    public List<Palette<ChunkerBiome>> asPalette() {
        // Loop through each block of biome data
        ArrayList<Palette<ChunkerBiome>> chunkPalettes = new ArrayList<>(chunks.size());

        // Loop through valid chunks
        for (Palette<ChunkerBiome> oldPalette : chunks) {
            if (oldPalette.isEmpty()) {
                chunkPalettes.add(EmptyPalette.instance(16));
            } else if (oldPalette.getKeyCount() == 1) {
                chunkPalettes.add(new SingleValuePalette<>(16, oldPalette.getKey(0)));
            } else {
                // Create palette
                WriteablePalette<ChunkerBiome> palette = new ShortBasedPalette<>(oldPalette.getKeyCount(), 16);

                // Loop through each cluster and map it to a full palette
                for (int clusterX = 0; clusterX < 4; ++clusterX) {
                    for (int clusterY = 0; clusterY < 4; ++clusterY) {
                        for (int clusterZ = 0; clusterZ < 4; ++clusterZ) {
                            int x = clusterX << 2;
                            int y = clusterY << 2;
                            int z = clusterZ << 2;

                            // Lookup the key
                            ChunkerBiome biome = oldPalette.get(clusterX, clusterY, clusterZ);

                            // Add to palette
                            short paletteIndex = palette.getOrCreateKey(biome);

                            // Apply to the new palette
                            for (int localX = 0; localX < 4; localX++) {
                                for (int localY = 0; localY < 4; localY++) {
                                    for (int localZ = 0; localZ < 4; localZ++) {
                                        palette.setPaletteIndex(x + localX, y + localY, z + localZ, paletteIndex);
                                    }
                                }
                            }
                        }
                    }
                }

                // Add to chunk list
                chunkPalettes.add(palette);
            }
        }

        // Return the palettes which were read
        return chunkPalettes;
    }

    @Override
    public Palette<ChunkerBiome> as4X4Palette(int chunkY) {
        // If there are no chunks return an empty palette
        if (chunks.isEmpty()) {
            return EmptyPalette.instance(4);
        }
        return chunkY < 0 || chunkY >= chunks.size() ? chunks.get(chunks.size() - 1) : chunks.get(chunkY);
    }

    @Override
    public void remap(Function<ChunkerBiome, ChunkerBiome> mapping) {
        chunks.replaceAll(chunk -> chunk.map(mapping));
    }
}
