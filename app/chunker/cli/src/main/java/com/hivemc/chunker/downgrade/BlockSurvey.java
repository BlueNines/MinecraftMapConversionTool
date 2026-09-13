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

    private final AtomicInteger lowestBlockY = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger lowestSectionY = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger highestSectionY = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger highestOccupiedSectionY = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger nonEmptySections = new AtomicInteger();
    private final AtomicInteger blockEntityCount = new AtomicInteger();
    private final AtomicInteger entityCount = new AtomicInteger();
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
            return new SurveyResult(0, 0, 0, 0, 0, 0, false);
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
                clipped
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
