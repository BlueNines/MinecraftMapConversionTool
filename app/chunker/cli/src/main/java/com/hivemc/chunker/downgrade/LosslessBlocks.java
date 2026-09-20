package com.hivemc.chunker.downgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerCustomBlockType;
import com.hivemc.chunker.util.LegacyIdentifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** 每次任务独立的无损处理调度与聚合报告，不逐方块刷屏或保存全地图坐标。 */
public final class LosslessBlocks {
    private final Set<String> replacements = new HashSet<>();
    private final Map<ChunkerBlockIdentifier, LongAdder> pending = new ConcurrentHashMap<>();
    private final LosslessBlockHandler handler;
    private final Map<com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerBlockType, Boolean> candidates = new ConcurrentHashMap<>();

    /** 不接任何处理器的空调度：全部计入待处理报告，不自动选择近似方块。 */
    public LosslessBlocks() { this(block -> Optional.empty()); }

    /** 外部处理方直接注入此接口，复用扫描、转换、写盘、报告和预览流程。 */
    public LosslessBlocks(LosslessBlockHandler handler) {
        this.handler = Objects.requireNonNull(handler);
        for (var entry : Approximations.builtInRules()) {
            var rule = entry.getAsJsonObject();
            if (rule.has("old_identifier")) replacements.add(rule.get("old_identifier").getAsString());
        }
    }

    /** 用户显式映射不进调度；其余内置替换项和原生不支持项统一交给处理器。 */
    public boolean needsHandling(ChunkerBlockIdentifier block, LegacyIdentifier nativeValue) {
        return !block.isAir() && block.getPreservedIdentifier() == null
                && (nativeValue.id() == 0 || candidates.computeIfAbsent(block.getType(), key -> replacements.contains(name(block))));
    }

    /** 处理器返回空表示不接管，此时只聚合报告，不自动选择近似方块。 */
    public Optional<LegacyIdentifier> handle(LosslessBlockHandler.Block block) {
        var handled = handler.handle(block);
        if (handled.isPresent()) {
            var value = handled.get();
            if (value.id() < 0 || value.id() > 4095 || value.data() < 0 || value.data() > 15)
                throw new IllegalArgumentException("无损处理接口返回了无效的 1.12.2 ID/Data：" + value);
            return handled;
        }
        pending.computeIfAbsent(block.identifier(), key -> {
            System.out.println("[无损转换] 未接管，回落原生编码：" + name(key) + " " + key.toStateString()
                    + " 示例位置=" + block.dimension() + ":" + block.x() + "," + block.y() + "," + block.z());
            return new LongAdder();
        }).increment();
        return Optional.empty();
    }

    /** 保留自定义命名空间。与幽灵表、转换报告使用同一套写法，避免两边漂移。 */
    static String name(ChunkerBlockIdentifier block) {
        return block.getType() instanceof ChunkerCustomBlockType custom ? custom.getIdentifier()
                : "minecraft:" + block.getType().toString().toLowerCase(Locale.ROOT);
    }

    /** 完整列出未被接管、回落到原生编码的方块状态及数量。 */
    public JsonObject report() {
        JsonObject result = new JsonObject(); JsonArray rows = new JsonArray(); long total = 0;
        for (var entry : pending.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString())).toList()) {
            JsonObject row = new JsonObject(); row.addProperty("block", name(entry.getKey()));
            row.addProperty("states", entry.getKey().toStateString()); row.addProperty("count", entry.getValue().sum());
            total += entry.getValue().sum(); rows.add(row);
        }
        result.addProperty("pendingBlocks", total); result.add("pending", rows);
        result.addProperty("message", "这些方块未被接管，回落为原生编码。原生表示不了的（不在此表也不在 GhostBlocks 幽灵表里）会变成空气，数量见 unmapped。");
        return result;
    }
}
