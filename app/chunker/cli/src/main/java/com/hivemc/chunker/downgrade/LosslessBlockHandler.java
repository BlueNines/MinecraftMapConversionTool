package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.util.LegacyIdentifier;
import java.util.Optional;

/** 无损处理接入点。调用发生在手动规则之后、1.12.2 方块编码写入之前；实现必须线程安全。 */
@FunctionalInterface
public interface LosslessBlockHandler {
    /**
     * 坐标是高度调整后的输出坐标；identifier 保留中间格式的方块类型和状态。
     * <p>
     * nativeValue 是该方块原本会被写成的 1.12.2 编码。id 为 0 表示原生无法表示（不接管就会变成
     * 空气），非 0 表示原生已经能表示——交回原生编码更好（少一层插件依赖）。
     */
    record Block(ChunkerBlockIdentifier identifier, Dimension dimension, int x, int y, int z,
                 LegacyIdentifier nativeValue) {}

    /** 返回处理后的 1.12.2 ID/Data；空表示不接管，交回原生编码并计入待处理报告。 */
    Optional<LegacyIdentifier> handle(Block block);
}
