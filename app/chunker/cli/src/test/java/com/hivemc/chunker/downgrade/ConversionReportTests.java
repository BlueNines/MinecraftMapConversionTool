package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 源方块候选必须完整，并保留不同命名空间。 */
class ConversionReportTests {
    /** 同名的不同状态合并计数，自定义命名空间不能被改成 minecraft。 */
    @Test void mergesStatesAndPreservesNamespace() {
        var rows = ConversionReport.sourceBlockTypes(Map.of(
                ChunkerBlockIdentifier.custom("example:beam", Map.of("axis", "x")), 7L,
                ChunkerBlockIdentifier.custom("example:beam", Map.of("axis", "y")), 11L,
                ChunkerBlockIdentifier.custom("other:beam", Map.of()), 2L));
        assertEquals(2, rows.size());
        assertEquals("example:beam", rows.get(0).getAsJsonObject().get("block").getAsString());
        assertEquals(18L, rows.get(0).getAsJsonObject().get("count").getAsLong());
        assertEquals("other:beam", rows.get(1).getAsJsonObject().get("block").getAsString());
    }

    /** 选择器清单不受报告前 400 条展示限制，且不收录未出现的方块。 */
    @Test void includesMoreThanFourHundredTypes() {
        Map<ChunkerBlockIdentifier, Long> counts = new HashMap<>();
        for (int i = 0; i < 450; i++) {
            counts.put(ChunkerBlockIdentifier.custom("example:block_" + i, Map.of()), 1L);
        }
        counts.put(ChunkerBlockIdentifier.custom("example:absent", Map.of()), 0L);
        var rows = ConversionReport.sourceBlockTypes(counts);
        assertEquals(450, rows.size());
        assertEquals("example:block_0", rows.get(0).getAsJsonObject().get("block").getAsString());
        assertEquals("example:block_99", rows.get(449).getAsJsonObject().get("block").getAsString());
    }
}
