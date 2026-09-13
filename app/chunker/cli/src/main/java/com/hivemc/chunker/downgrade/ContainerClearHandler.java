package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.ChunkerColumn;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.BlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.blockentity.container.ContainerBlockEntity;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Empties containers while leaving the containers themselves in place.
 * <p>
 * A chest is part of a build's look; what is inside it is not. Modern items frequently have no counterpart in the
 * target version, and a container holding items that cannot be represented is a good way to make a world fail to
 * load cleanly. Keeping the block and dropping the contents serves both goals at once.
 */
public class ContainerClearHandler implements ColumnConversionHandler {
    private final ColumnConversionHandler delegate;
    private final boolean clear;
    private final AtomicInteger itemsRemoved;

    /**
     * Create a new container clearing handler.
     *
     * @param delegate     the next handler in the pipeline.
     * @param clear        whether contents should be dropped.
     * @param itemsRemoved a shared counter, so totals span every dimension.
     */
    public ContainerClearHandler(ColumnConversionHandler delegate, boolean clear, AtomicInteger itemsRemoved) {
        this.delegate = delegate;
        this.clear = clear;
        this.itemsRemoved = itemsRemoved;
    }

    @Override
    public void convertColumn(ChunkerColumn column) {
        if (clear) {
            for (BlockEntity blockEntity : column.getBlockEntities()) {
                if (blockEntity instanceof ContainerBlockEntity container) {
                    int size = container.getItems().size();
                    if (size > 0) {
                        itemsRemoved.addAndGet(size);
                        container.getItems().clear();
                    }
                }
            }
        }
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
}
