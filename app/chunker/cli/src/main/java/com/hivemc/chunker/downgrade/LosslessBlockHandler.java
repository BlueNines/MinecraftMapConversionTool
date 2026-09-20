package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.util.LegacyIdentifier;
import java.util.Optional;

/** 无损处理接入点。调用发生在手动规则之后、1.12.2 方块编码写入之前；实现必须线程安全。 */
@FunctionalInterface
public interface LosslessBlockHandler {
    /** 坐标是高度调整后的输出坐标；identifier 保留中间格式的方块类型和状态。 */
    record Block(ChunkerBlockIdentifier identifier, Dimension dimension, int x, int y, int z) {}

    /** 返回处理后的 1.12.2 ID/Data；空表示尚未接入，继续原生编码并记录待处理报告。 */
    Optional<LegacyIdentifier> handle(Block block);
}
