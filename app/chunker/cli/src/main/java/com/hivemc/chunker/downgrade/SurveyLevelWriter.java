package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.writer.ColumnWriter;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.encoding.base.writer.WorldWriter;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkerChunk;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import it.unimi.dsi.fastutil.Pair;

import java.util.Set;

/**
 * A writer which measures the world instead of writing it.
 * <p>
 * Feeding this to the reader runs the entire read pipeline - every region parsed, every block mapped - but the
 * output goes nowhere except an accumulating summary. Because each column is inspected and then dropped, memory
 * stays flat no matter how large the world is.
 * <p>
 * The reason a whole pass is spent on this, rather than working it out during the real conversion, is that the
 * target format silently discards anything outside its vertical range. Writing has to know the shift before it
 * starts, not halfway through.
 */
public class SurveyLevelWriter implements LevelWriter {
    private final BlockSurvey survey;
    private final boolean countBlocks;

    /**
     * Create a new survey writer.
     *
     * @param survey      the collector to record everything into.
     * @param countBlocks whether to tally every block type. This roughly doubles the cost of the pass, so it is
     *                    skipped when the measurements are only needed to decide the vertical shift.
     */
    public SurveyLevelWriter(BlockSurvey survey, boolean countBlocks) {
        this.survey = survey;
        this.countBlocks = countBlocks;
    }

    @Override
    public WorldWriter writeLevel(ChunkerLevel chunkerLevel) {
        return new SurveyWorldWriter(survey, countBlocks);
    }

    @Override
    public EncodingType getEncodingType() {
        // Reported as the internal preview type so this pass can never be mistaken for a real output format.
        return EncodingType.PREVIEW;
    }

    @Override
    public Version getVersion() {
        return new Version(1, 0, 0);
    }

    @Override
    public Set<ChunkerBiome.ChunkerVanillaBiome> getSupportedBiomes() {
        // Nothing is written, so biomes are accepted as-is rather than reported as unsupported.
        return Set.of();
    }

    /**
     * A world writer which hands out measuring column writers.
     */
    private static class SurveyWorldWriter implements WorldWriter {
        private final BlockSurvey survey;
        private final boolean countBlocks;

        SurveyWorldWriter(BlockSurvey survey, boolean countBlocks) {
            this.survey = survey;
            this.countBlocks = countBlocks;
        }

        @Override
        public ColumnWriter writeWorld(ChunkerWorld chunkerWorld) {
            // Only the overworld counts when working out where a player should arrive: it is the dimension they are
            // placed in, and folding the nether's bedrock ceiling into the same tally would drag the answer
            // somewhere no player can stand.
            boolean overworld = "minecraft:overworld".equals(chunkerWorld.getDimension().getIdentifier());
            return new SurveyColumnWriter(survey, countBlocks, overworld);
        }
    }

    /**
     * A column writer which records the vertical extent of every column it is handed.
     */
    private static class SurveyColumnWriter implements ColumnWriter {
        /**
         * How far apart the ground samples are taken, in blocks. Sampling every block would multiply the cost of the
         * pass by two hundred and fifty-six for no better answer: ground a player can stand on comes in surfaces
         * much wider than a few blocks.
         */
        private static final int GROUND_SAMPLE_STEP = 4;

        private final BlockSurvey survey;
        private final boolean countBlocks;
        private final boolean overworld;

        SurveyColumnWriter(BlockSurvey survey, boolean countBlocks, boolean overworld) {
            this.survey = survey;
            this.countBlocks = countBlocks;
            this.overworld = overworld;
        }

        @Override
        public void writeColumn(ChunkerColumn column) {
            for (ChunkerChunk chunk : column.getChunks().values()) {
                survey.observeSection(chunk.getY(), !chunk.isEmpty());
                if (countBlocks && !chunk.isEmpty()) {
                    survey.observeBlocks(chunk.getPalette());
                }
            }
            if (overworld) observeGround(column);
            for (BlockEntity blockEntity : column.getBlockEntities()) {
                survey.observeBlockEntityY(blockEntity.getY());
            }
            for (Entity entity : column.getEntities()) {
                survey.observeEntityY(entity.getPositionY());
            }
        }

        /**
         * Record how high the ground sits across this column.
         * <p>
         * This is what lets the spawn land on the surface rather than on a rooftop: within a cell the lowest surface
         * wins, and a square ringed by halls offers its floor while the halls offer their roofs.
         *
         * @param column the column to measure.
         */
        private void observeGround(ChunkerColumn column) {
            if (column.getChunks().isEmpty()) return;

            int chunkX = column.getPosition().chunkX();
            int chunkZ = column.getPosition().chunkZ();
            int baseX = chunkX * BlockSurvey.SECTION_SIZE;
            int baseZ = chunkZ * BlockSurvey.SECTION_SIZE;

            for (int x = 0; x < BlockSurvey.SECTION_SIZE; x += GROUND_SAMPLE_STEP) {
                for (int z = 0; z < BlockSurvey.SECTION_SIZE; z += GROUND_SAMPLE_STEP) {
                    Pair<Integer, ChunkerBlockIdentifier> highest = column.getHighestBlock(x, z, identifier -> !identifier.isAir());
                    if (highest == null) continue;
                    survey.observeGround(chunkX, chunkZ, baseX + x, baseZ + z, highest.left());
                }
            }
        }
    }
}
