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

    /** Web 默认接入位置：后续用真正的处理器替换这里的日志占位实现。 */
    public LosslessBlocks() { this(block -> Optional.empty()); }

    /** 外部处理方直接注入此接口，复用扫描、转换、写盘、报告和预览流程。 */
    public LosslessBlocks(LosslessBlockHandler handler) {
        this.handler = Objects.requireNonNull(handler);
        for (var entry : Approximations.builtInRules()) {
            var rule = entry.getAsJsonObject();
            if (rule.has("old_identifier")) replacements.add(rule.get("old_identifier").getAsString());
        }
    }

    /** 用户显式映射不进入占位接口；其余旧替换项和原生不支持项统一调度。 */
    public boolean needsHandling(ChunkerBlockIdentifier block, LegacyIdentifier nativeValue) {
        return !block.isAir() && block.getPreservedIdentifier() == null
                && (nativeValue.id() == 0 || candidates.computeIfAbsent(block.getType(), key -> replacements.contains(name(block))));
    }

    /** 真处理器可返回编码；占位只聚合日志，不自动选择近似方块。 */
    public Optional<LegacyIdentifier> handle(LosslessBlockHandler.Block block) {
        var handled = handler.handle(block);
        if (handled.isPresent()) {
            var value = handled.get();
            if (value.id() < 0 || value.id() > 4095 || value.data() < 0 || value.data() > 15)
                throw new IllegalArgumentException("无损处理接口返回了无效的 1.12.2 ID/Data：" + value);
            return handled;
        }
        pending.computeIfAbsent(block.identifier(), key -> {
            System.out.println("[无损转换待接入] " + name(key) + " " + key.toStateString()
                    + " 示例位置=" + block.dimension() + ":" + block.x() + "," + block.y() + "," + block.z());
            return new LongAdder();
        }).increment();
        return Optional.empty();
    }

    /** 保留自定义命名空间。 */
    private static String name(ChunkerBlockIdentifier block) {
        return block.getType() instanceof ChunkerCustomBlockType custom ? custom.getIdentifier()
                : "minecraft:" + block.getType().toString().toLowerCase(Locale.ROOT);
    }

    /** 完整列出尚未处理的方块状态及数量，避免将占位阶段误认为真正无损完成。 */
    public JsonObject report() {
        JsonObject result = new JsonObject(); JsonArray rows = new JsonArray(); long total = 0;
        for (var entry : pending.entrySet().stream().sorted(Comparator.comparing(e -> e.getKey().toString())).toList()) {
            JsonObject row = new JsonObject(); row.addProperty("block", name(entry.getKey()));
            row.addProperty("states", entry.getKey().toStateString()); row.addProperty("count", entry.getValue().sum());
            total += entry.getValue().sum(); rows.add(row);
        }
        result.addProperty("pendingBlocks", total); result.add("pending", rows);
        result.addProperty("message", "特殊方块处理接口尚未接入；未处理项使用原生 1.12.2 编码，不支持的方块为空气占位。此阶段不保证无损。");
        return result;
    }
}
