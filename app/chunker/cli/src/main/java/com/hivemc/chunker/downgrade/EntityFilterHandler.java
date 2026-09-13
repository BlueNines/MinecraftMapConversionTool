package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.entity.Entity;
import com.hivemc.chunker.conversion.intermediate.column.entity.ItemFrameEntity;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Removes entities which the target format has no business carrying.
 * <p>
 * The tool exists to move a build's appearance across versions, not its inhabitants. Mobs, dropped items and the
 * rest are scenery that would either fail to convert or come across as broken data, so they are dropped.
 * <p>
 * Hanging entities are the exception. A painting is part of a wall, and an item frame is the whole point of keeping
 * an item visible in a build - both are decorations a builder placed deliberately, so they are kept.
 */
public class EntityFilterHandler implements ColumnConversionHandler {
    private final ColumnConversionHandler delegate;
    private final boolean keepHangingEntities;
    private final AtomicInteger removed;

    /**
     * Create a new entity filter.
     *
     * @param delegate            the next handler in the pipeline.
     * @param keepHangingEntities whether to keep paintings and item frames. Everything else is removed either way.
     * @param removed             a shared counter for removed entities, so totals span every dimension.
     */
    public EntityFilterHandler(ColumnConversionHandler delegate, boolean keepHangingEntities, AtomicInteger removed) {
        this.delegate = delegate;
        this.keepHangingEntities = keepHangingEntities;
        this.removed = removed;
    }

    @Override
    public void convertColumn(ChunkerColumn column) {
        column.getEntities().removeIf(entity -> {
            if (keepHangingEntities && isHanging(entity)) {
                return false;
            }
            removed.incrementAndGet();
            return true;
        });
        delegate.convertColumn(column);
    }

    @Override
    public void flushRegion(RegionCoordPair regionCoordPair) {
        delegate.flushRegion(regionCoordPair);
    }

    @Override
    public void flushColumns() {
        delegate.flushColumns();
    }

    /**
     * Whether an entity is a wall decoration rather than a living thing or a loose object.
     */
    private static boolean isHanging(Entity entity) {
        return entity instanceof ItemFrameEntity;
    }
}
