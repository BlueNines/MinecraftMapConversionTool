package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerVanillaBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.PreservedIdentifier;
import com.hivemc.chunker.mapping.identifier.Identifier;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.util.LegacyIdentifier;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 手动规则优先，未接入项聚合报告，接入返回值用于写盘。 */
class LosslessBlocksTests {
    /** 内置替换候选和未知方块会进入接口，普通石头和空气不会。 */
    @Test void dispatchesOnlySpecialBlocksWithoutManualMappings() {
        var service = new LosslessBlocks();
        var special = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.QUARTZ_BRICKS);
        assertTrue(service.needsHandling(special,new LegacyIdentifier(0,(byte)0)));
        assertFalse(service.needsHandling(ChunkerBlockIdentifier.AIR,new LegacyIdentifier(0,(byte)0)));
        assertFalse(service.needsHandling(new ChunkerBlockIdentifier(ChunkerVanillaBlockType.STONE),new LegacyIdentifier(1,(byte)0)));
        var manual = new ChunkerBlockIdentifier(special.getType(),Map.of(),new PreservedIdentifier(true,new Identifier("minecraft:stone",Map.of())));
        assertFalse(service.needsHandling(manual,new LegacyIdentifier(1,(byte)0)));
    }

    /** 占位不会自行选择替换方块，多个实例只累计到一条状态记录。 */
    @Test void placeholderReportsPendingAndInjectedHandlerProducesEncoding() {
        var source = new ChunkerBlockIdentifier(ChunkerVanillaBlockType.QUARTZ_BRICKS);
        var block = new LosslessBlockHandler.Block(source,Dimension.OVERWORLD,10,20,30);
        var air = new LegacyIdentifier(0,(byte)0);
        var service = new LosslessBlocks();
        assertTrue(service.handle(block).isEmpty());service.handle(block);
        assertEquals(2,service.report().get("pendingBlocks").getAsLong());
        assertEquals(1,service.report().getAsJsonArray("pending").size());
        var real = new LosslessBlocks(context -> {assertEquals(10,context.x());return Optional.of(new LegacyIdentifier(5,(byte)2));});
        assertEquals(new LegacyIdentifier(5,(byte)2),real.handle(block).orElseThrow());
        assertEquals(0,real.report().get("pendingBlocks").getAsLong());
    }
}
