package com.hivemc.chunker.downgrade;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Running totals for everything the shift had to leave behind.
 * <p>
 * A world can be split across several dimensions, each of which gets its own handler, so the counters are shared
 * rather than owned - the report the user sees should add up across the whole world, not just the last dimension
 * written.
 */
public class ShiftStats {
    private final AtomicInteger shiftedSections = new AtomicInteger();
    private final AtomicInteger clippedSections = new AtomicInteger();
    private final AtomicInteger droppedBlockEntities = new AtomicInteger();
    private final AtomicInteger droppedEntities = new AtomicInteger();

    /**
     * Record that a section was moved and kept.
     */
    public void recordShiftedSection() {
        shiftedSections.incrementAndGet();
    }

    /**
     * Record that a section was pushed past the top of the writable range and discarded.
     */
    public void recordClippedSection() {
        clippedSections.incrementAndGet();
    }

    /**
     * Record that a block entity was discarded.
     */
    public void recordDroppedBlockEntity() {
        droppedBlockEntities.incrementAndGet();
    }

    /**
     * Record that an entity was discarded.
     */
    public void recordDroppedEntity() {
        droppedEntities.incrementAndGet();
    }

    /**
     * Get how many sections were successfully moved into range.
     *
     * @return the count of moved sections.
     */
    public int getShiftedSections() {
        return shiftedSections.get();
    }

    /**
     * Get how many sections were lost to the top of the writable range.
     *
     * @return the count of discarded sections.
     */
    public int getClippedSections() {
        return clippedSections.get();
    }

    /**
     * Get how many block entities were lost.
     *
     * @return the count of discarded block entities.
     */
    public int getDroppedBlockEntities() {
        return droppedBlockEntities.get();
    }

    /**
     * Get how many entities were lost.
     *
     * @return the count of discarded entities.
     */
    public int getDroppedEntities() {
        return droppedEntities.get();
    }

    /**
     * Whether anything at all was discarded while shifting.
     *
     * @return true if some content did not survive the move.
     */
    public boolean hasLosses() {
        return clippedSections.get() > 0 || droppedBlockEntities.get() > 0 || droppedEntities.get() > 0;
    }
}
