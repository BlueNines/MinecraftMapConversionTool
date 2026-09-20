package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.palette.Palette;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Collects the vertical extent of a world during a survey pass.
 * <p>
 * The survey pass runs the normal conversion pipeline but throws every column away after inspecting it, so its
 * memory footprint stays flat regardless of world size. The only goal is to answer one question before the real
 * conversion starts: how far does the world have to move so that its lowest block lands on Y=0?
 * <p>
 * This value must be known up front because the target format rejects out-of-range sections outright (the writer
 * drops them without a trace), so a shift discovered halfway through writing would be too late.
 */
public final class BlockSurvey {
    /**
     * Number of blocks in one section along each axis.
     */
    public static final int SECTION_SIZE = 16;

    /**
     * How many chunks wide a cell is, as a power of two. Cells exist only to bound the memory used to remember where
     * the ground is, so they want to be large enough that the count stays small and small enough that the ground in
     * one cell is fairly uniform.
     */
    private static final int CELL_SHIFT = 2;

    private final AtomicInteger lowestBlockY = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger lowestSectionY = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger highestSectionY = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger highestOccupiedSectionY = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger nonEmptySections = new AtomicInteger();
    private final AtomicInteger blockEntityCount = new AtomicInteger();
    private final AtomicInteger entityCount = new AtomicInteger();
    private final AtomicInteger minChunkX = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger maxChunkX = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger minChunkZ = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger maxChunkZ = new AtomicInteger(Integer.MIN_VALUE);
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, int[]>> cellSurfaces = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkerBlockIdentifier, LongAdder> blockCounts = new ConcurrentHashMap<>();

    /**
     * Record a section which may contain blocks.
     *
     * @param sectionY the section index on the Y axis.
     * @param nonEmpty whether the section holds anything other than air.
     */
    public void observeSection(int sectionY, boolean nonEmpty) {
        updateMin(lowestSectionY, sectionY);
        updateMax(highestSectionY, sectionY);
        if (!nonEmpty) return;

        nonEmptySections.incrementAndGet();
        // Only occupied sections matter when forecasting what the ceiling will cost: a tall world of empty air
        // loses nothing by being trimmed, and warning about it would be noise.
        updateMax(highestOccupiedSectionY, sectionY);
        // The lowest block of a section sits at its base, which is precise enough for choosing a shift.
        updateMin(lowestBlockY, sectionY * SECTION_SIZE);
    }

    /**
     * Count the blocks held in one section.
     * <p>
     * A section whose palette holds a single entry is uniform, so its count is known without touching a single
     * position. Only genuinely mixed sections are walked, which keeps this cheap on large worlds where most
     * sections are solid stone, solid air or solid water.
     *
     * @param palette the palette of the section to count.
     */
    public void observeBlocks(Palette<ChunkerBlockIdentifier> palette) {
        if (palette == null || palette.isEmpty()) return;

        int positions = SECTION_SIZE * SECTION_SIZE * SECTION_SIZE;
        if (palette.getKeyCount() == 1) {
            ChunkerBlockIdentifier only = palette.getKey(0);
            if (only != null && !only.isAir()) {
                count(only, positions);
            }
            return;
        }

        for (int y = 0; y < SECTION_SIZE; y++) {
            for (int z = 0; z < SECTION_SIZE; z++) {
                for (int x = 0; x < SECTION_SIZE; x++) {
                    ChunkerBlockIdentifier identifier = palette.get(x, y, z);
                    if (identifier == null || identifier.isAir()) continue;
                    count(identifier, 1);
                }
            }
        }
    }

    private void count(ChunkerBlockIdentifier identifier, int amount) {
        blockCounts.computeIfAbsent(identifier, ignored -> new LongAdder()).add(amount);
    }

    /**
     * Record a block entity position.
     *
     * @param y the block entity Y co-ordinate.
     */
    public void observeBlockEntityY(int y) {
        blockEntityCount.incrementAndGet();
        updateMin(lowestBlockY, y);
    }

    /**
     * Record an entity position.
     *
     * @param y the entity Y co-ordinate.
     */
    public void observeEntityY(double y) {
        entityCount.incrementAndGet();
        updateMin(lowestBlockY, (int) Math.floor(y));
    }

    /**
     * Record the ground level of one block column, grouped into a coarse cell.
     * <p>
     * A modern world keeps its spawn at Y=-60 or lower, which the target format has no room for, and a server handed
     * such a value reports "safe spawn not found" and refuses the world. The columns themselves know where the ground
     * is, so the survey records it while it is already looking at them.
     * <p>
     * Each cell keeps a tally of the heights its columns reported, which is what lets the spawn be placed on ground
     * the world actually has: the prevailing height is worked out across every cell at the end, and a column measured
     * at exactly that height is one whose top block sits level with the floor rather than on a roof.
     *
     * @param chunkX   the chunk X co-ordinate of the column.
     * @param chunkZ   the chunk Z co-ordinate of the column.
     * @param blockX   the block X co-ordinate the surface was measured at.
     * @param blockZ   the block Z co-ordinate the surface was measured at.
     * @param surfaceY the Y of the highest non-air block. This is routinely negative: worlds from 1.18 onwards
     *                 reach down to Y=-64, so a build can perfectly well have its floor below zero.
     */
    public void observeGround(int chunkX, int chunkZ, int blockX, int blockZ, int surfaceY) {
        updateMin(minChunkX, chunkX);
        updateMax(maxChunkX, chunkX);
        updateMin(minChunkZ, chunkZ);
        updateMax(maxChunkZ, chunkZ);

        // {times seen, an X and a Z which had it}, so a representative column travels with the count.
        ConcurrentHashMap<Integer, int[]> heights = cellSurfaces.computeIfAbsent(
                cellKey(chunkX >> CELL_SHIFT, chunkZ >> CELL_SHIFT), ignored -> new ConcurrentHashMap<>());
        // compute is used rather than merge because the array is mutated in place and must not be raced on.
        heights.compute(surfaceY, (height, tally) -> {
            if (tally == null) return new int[]{1, blockX, blockZ};
            tally[0]++;
            return tally;
        });
    }

    /**
     * Choose where a player should arrive, in the co-ordinates of the source world.
     * <p>
     * The height is deliberately not adjusted for any shift here: the writer decides whether the world moves and by
     * how much, and baking a guess into the measurement would only be wrong when no shift happens.
     *
     * @return the spawn position, or null if the world held no ground to stand on.
     */
    private SpawnPoint chooseSpawn() {
        if (cellSurfaces.isEmpty()) return null;

        // The world's prevailing surface height is what a floor looks like. Taking it from the whole world rather than
        // from one cell is what makes it trustworthy: over a build of any size the floor outweighs every other height
        // put together, whereas inside a single cell the roofs of whatever stands there can easily outnumber it.
        int groundY = globalGroundHeight();

        // Aim for the middle of the build rather than weighing every cell equally: a long tail of outlying cells would
        // otherwise drag the answer away from the part which is actually built up.
        int centerCellX = ((minChunkX.get() + maxChunkX.get()) / 2) >> CELL_SHIFT;
        int centerCellZ = ((minChunkZ.get() + maxChunkZ.get()) / 2) >> CELL_SHIFT;

        // Only cells containing a column at that height are eligible, and among those the nearest to the middle wins.
        // A column measured at exactly the prevailing height is one whose top block sits level with the rest of the
        // floor, so it offers somewhere to land; a cell with nothing at that height offers only roofs.
        SpawnPoint best = null;
        long bestDistance = Long.MAX_VALUE;
        for (Map.Entry<Long, ConcurrentHashMap<Integer, int[]>> entry : cellSurfaces.entrySet()) {
            int[] tally = entry.getValue().get(groundY);
            if (tally == null) continue;

            long cellX = entry.getKey() >> 32;
            long cellZ = (int) entry.getKey().longValue();
            long distance = (cellX - centerCellX) * (cellX - centerCellX) + (cellZ - centerCellZ) * (cellZ - centerCellZ);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = new SpawnPoint(tally[1], groundY, tally[2]);
            }
        }

        // Every cell that exists holds at least one measurement and the prevailing height was picked from those
        // measurements, so a match is guaranteed. The check is the method's documented contract, not a repair.
        if (best == null) return null;

        // One block above the ground so the player lands on it rather than inside it.
        return new SpawnPoint(best.x(), best.y() + 1, best.z());
    }

    /**
     * Find the world's prevailing surface height by counting every column's surface.
     * <p>
     * Only call this once ground has been measured: it returns -1 otherwise, and -1 is itself a perfectly ordinary
     * surface height in a world which reaches below zero.
     *
     * @return the most common ground height.
     */
    private int globalGroundHeight() {
        ConcurrentHashMap<Integer, LongAdder> totals = new ConcurrentHashMap<>();
        for (ConcurrentHashMap<Integer, int[]> cell : cellSurfaces.values()) {
            for (Map.Entry<Integer, int[]> height : cell.entrySet()) {
                totals.computeIfAbsent(height.getKey(), ignored -> new LongAdder()).add(height.getValue()[0]);
            }
        }

        int modeY = -1;
        long most = -1;
        for (Map.Entry<Integer, LongAdder> entry : totals.entrySet()) {
            if (entry.getValue().sum() > most) {
                most = entry.getValue().sum();
                modeY = entry.getKey();
            }
        }
        return modeY;
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) | (cellZ & 0xFFFFFFFFL);
    }

    /**
     * Whether anything at all was found in the world.
     *
     * @return true if no sections, blocks or entities were observed.
     */
    public boolean isEmpty() {
        return nonEmptySections.get() == 0 && blockEntityCount.get() == 0 && entityCount.get() == 0;
    }

    /**
     * Turn the collected data into a decision about how far to shift and what that shift costs.
     *
     * @param minSectionY the lowest section index the output format can store.
     * @param maxSectionY the highest section index the output format can store.
     * @return the survey result, describing the shift and any content it pushes out of range.
     */
    public SurveyResult finish(int minSectionY, int maxSectionY) {
        if (isEmpty()) {
            return new SurveyResult(0, 0, 0, 0, 0, 0, false, null);
        }

        int lowest = lowestBlockY.get();
        int lowestSection = lowestSectionY.get();
        int highestSection = highestSectionY.get();

        // Only move the world when it actually starts below the output floor. Worlds which already sit at or above
        // Y=0 are left exactly where they are so their co-ordinates stay meaningful.
        int shift = 0;
        if (lowest < minSectionY * SECTION_SIZE) {
            int needed = minSectionY * SECTION_SIZE - lowest;
            // Round up to a whole section: sections can then be re-labelled rather than rebuilt block by block, and
            // their light data travels with them for free.
            shift = ceilDiv(needed, SECTION_SIZE) * SECTION_SIZE;
        }

        int shiftSections = shift / SECTION_SIZE;
        // Forecast against the highest section that actually holds blocks, not the highest air-filled one.
        int highestOccupied = highestOccupiedSectionY.get();
        int highestAfter = (highestOccupied == Integer.MIN_VALUE ? 0 : highestOccupied) + shiftSections;
        boolean clipped = highestAfter > maxSectionY;
        int clippedSections = clipped ? highestAfter - maxSectionY : 0;

        return new SurveyResult(
                lowest,
                lowestSection,
                highestSection,
                nonEmptySections.get(),
                shift,
                clippedSections,
                clipped,
                chooseSpawn()
        );
    }

    /**
     * Get the number of block entities seen during the survey.
     *
     * @return the block entity count.
     */
    public int getBlockEntityCount() {
        return blockEntityCount.get();
    }

    /**
     * Get the number of entities seen during the survey.
     *
     * @return the entity count.
     */
    public int getEntityCount() {
        return entityCount.get();
    }

    /**
     * Get how many blocks of each type the world contains, including air types.
     *
     * @return a map of block identifier to occurrence count, ordered by descending count.
     */
    public Map<ChunkerBlockIdentifier, Long> getBlockCounts() {
        Map<ChunkerBlockIdentifier, Long> sorted = new LinkedHashMap<>();
        blockCounts.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue().sum()));
        return sorted;
    }

    /**
     * Get the total number of non-air blocks counted.
     *
     * @return the total block count.
     */
    public long getTotalBlocks() {
        long total = 0;
        for (LongAdder adder : blockCounts.values()) {
            total += adder.sum();
        }
        return total;
    }

    private static void updateMin(AtomicInteger holder, int value) {
        holder.accumulateAndGet(value, Math::min);
    }

    private static void updateMax(AtomicInteger holder, int value) {
        holder.accumulateAndGet(value, Math::max);
    }

    private static int ceilDiv(int value, int divisor) {
        return (value + divisor - 1) / divisor;
    }
}
