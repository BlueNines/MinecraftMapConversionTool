package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.ChunkerCustomBlockType;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.BlockState;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.type.block.states.BlockStateValue;
import com.hivemc.chunker.util.LegacyIdentifier;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * 把 1.12.2 装不下的方块写成「幽灵编号」，交给后端服务端的 GhostBlocks 插件复原。
 * <p>
 * <b>保留状态</b>：一个幽灵编号对应高版本方块的**一个具体状态**（不只是方块名），
 * 所以楼梯的朝向/上下半/形状、活板门的开合都能原样保留。
 * 数据表由 GhostBlocks 项目的 {@code tools/plan-extended.js} 生成，
 * 本资源目录里的是它的副本（带溯源注释头）。
 * <p>
 * <b>编号空间</b>：幽灵编号从 32368 起排，高于所有版本映射空间的上界（32366），
 * 真实数据永远产生不了这些值；它们靠 GhostBlocks 插件在 ViaVersion 映射表外套的
 * 「编号透传层」穿过中间版本，直到目标方块「首次出现的那一段」才被换成真实状态。
 * <p>
 * <b>只处理原生表示不了的方块</b>：nativeValue 已经有非 0 id 时一律放弃（返回空），
 * 交回原生编码——它能保留状态且不依赖插件，接管只会让结果变差。
 * <p>
 * 线程安全：索引在构造时建好，之后只读。
 */
public final class GhostBlockHandler implements LosslessBlockHandler {
    private static final String RESOURCE = "/ghostblocks/channel-patches.txt";

    /**
     * 方块名（Chunker 侧）→ 幽灵表里的方块名。
     * <p>
     * 只有 3 个方块两边叫法不同（对全表逐方块比对得出，不是抽查）：
     * Chunker 把洞穴藤蔓拆成身体/头部，把移动中的活塞单独命名成 {@code MOVING_PISTON_JAVA}。
     */
    private static final Map<String, String> BLOCK_ALIASES = Map.of(
            "cave_vines_body", "cave_vines",
            "cave_vines_head", "cave_vines_plant",
            "moving_piston_java", "moving_piston"
    );

    /**
     * 状态属性名（Chunker 侧）→ 幽灵表里的属性名。
     * <p>
     * 同样由全表比对得出：Chunker 对这三个属性用了自己的内部叫法，
     * 而幽灵表用的是 Java（vanilla）的名字。
     */
    private static final Map<String, String> STATE_ALIASES = Map.of(
            "grindstone_face", "face",
            "rehydration_level", "hydration",
            "creaking", "creaking_heart_state"
    );

    /** Chunker 自己的内部字段，Java 侧不存在这个概念，不参与签名。 */
    private static final Set<String> INTERNAL_STATES =
            Set.of("update", "dead", "drop_bit", "coral_fan_direction");

    /** 方块名 → 该方块的全部状态。 */
    private final Map<String, BlockStates> table;

    /** 实际载入的记录数（一个方块会有很多条）。 */
    private final int rows;

    /** 精确命中状态的次数。 */
    private final java.util.concurrent.atomic.LongAdder exactHits = new java.util.concurrent.atomic.LongAdder();

    /** 回落到同方块其它状态的次数（目标版本里没有这个状态）。 */
    private final java.util.concurrent.atomic.LongAdder fallbackHits = new java.util.concurrent.atomic.LongAdder();

    /** 首次回落时打一条示例，避免刷屏（这种情形很少，但用户应该知道）。 */
    private final java.util.concurrent.atomic.AtomicBoolean fallbackLogged =
            new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * 用打包在 jar 里的幽灵表建立处理器。
     *
     * @throws IOException 表读不到或一条都解不出时抛出，宁可失败也不静默退化成“什么都不做”。
     */
    public GhostBlockHandler() throws IOException {
        Loaded loaded = load();
        this.table = loaded.table();
        this.rows = loaded.rows();
        if (table.isEmpty()) {
            throw new IOException("幽灵表里一条记录也没有：" + RESOURCE);
        }
    }

    /**
     * 建立一个幽灵方块处理器；若表加载不了，则退化为“什么都不做”并打一条警告。
     * <p>
     * 转换任务不应因为一张可选表而整个失败：表缺失时退化回原本的“变空气”行为，
     * 结果与接入前一致，而且报告里依旧会列出这些方块。
     *
     * @return 可用处理器，或退回的空实现。
     */
    public static LosslessBlockHandler createOrEmpty() {
        try {
            GhostBlockHandler handler = new GhostBlockHandler();
            System.out.println("[幽灵方块] 已加载 " + handler.rows + " 个状态（"
                    + handler.blockCount() + " 个方块）；1.12.2 表示不了的方块将连状态一起保留。");
            return handler;
        } catch (IOException e) {
            System.out.println("[幽灵方块] 未能加载幽灵表，本次转换按普通无损方式处理：" + e.getMessage());
            return block -> Optional.empty();
        }
    }

    /**
     * 表里登记了多少条记录（一个方块有很多状态）。
     *
     * @return 记录数。
     */
    public int size() {
        return rows;
    }

    /**
     * 表里涉及多少个方块。
     *
     * @return 方块数。
     */
    public int blockCount() {
        return table.size();
    }

    /** 精确命中状态的块数（状态完全保留的）。 */
    public long exactHits() {
        return exactHits.sum();
    }

    /** 回落到默认态的块数（目标版本里没有这个状态）。 */
    public long fallbackHits() {
        return fallbackHits.sum();
    }

    /**
     * 把一个方块写成幽灵编号。
     * <p>
     * <b>位序换算</b>：这里返回的 LegacyIdentifier 并不是 raw id 本身。
     * 写盘时 Chunker 把 id 拆成 {@code Blocks[i] = id & 0xFF}、
     * {@code Add 半字节 = (id >> 8) & 0xFF}；而服务端读档时拼回去的是
     * {@code raw = (Add << 12) | (Blocks << 4) | Data}。
     * 所以要让存档里出现幽灵编号 R，必须传 {@code (R >> 4, R & 15)}，
     * 直接传 {@code (R, 0)} 会得到完全不同的编号。
     */
    @Override
    public Optional<LegacyIdentifier> handle(Block block) {
        // 原生已经能表示这个方块：交给原生编码，它能保留状态且不依赖插件。
        if (block.nativeValue().id() != 0) return Optional.empty();

        ChunkerBlockIdentifier identifier = block.identifier();
        if (identifier == null || identifier.isAir()) return Optional.empty();
        // 自定义方块不在幽灵表里（表只覆盖 1.13+ 的原版新增方块）。
        if (identifier.getType() instanceof ChunkerCustomBlockType) return Optional.empty();

        BlockStates states = table.get(nativeName(identifier));
        if (states == null) return Optional.empty();

        String signature = buildSignature(identifier, states.attributes());
        Integer rawId = states.bySignature().get(signature);
        if (rawId != null) {
            exactHits.increment();
            return Optional.of(new LegacyIdentifier(rawId >> 4, (byte) (rawId & 15)));
        }

        // 精确签名查不到：这个状态在目标版本里没有对应物（映射链里没有定义，
        // 生成器宁可跳过也不编值）。此时回落到同一方块的默认态——
        // 「状态不精确」远好于「整块变空气」。
        Integer fallback = states.fallbackRawId();
        if (fallback == null) return Optional.empty();
        fallbackHits.increment();
        if (fallbackLogged.compareAndSet(false, true)) {
            System.out.println("[幽灵方块] 状态 " + signature + " 在目标版本里没有对应物（"
                    + nativeName(identifier) + "），已回落到默认态——方块对、状态不精确。"
                    + "该方块在表里的状态：" + states.knownSignatures());
        }
        return Optional.of(new LegacyIdentifier(fallback >> 4, (byte) (fallback & 15)));
    }

    /** 取方块在幽灵表里的写法（不带命名空间），必要时换成表里用的名字。 */
    private static String nativeName(ChunkerBlockIdentifier identifier) {
        String name = LosslessBlocks.name(identifier);
        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        String alias = BLOCK_ALIASES.get(name);
        return alias != null ? alias : name;
    }

    /**
     * 按幽灵表的写法生成状态签名：属性名升序、小写，值小写。
     * <p>
     * 只写出 {@code expected} 里列出的属性——幽灵表对每个方块只记它真正需要的状态，
     * 多写或少写都会查不到（而两边都不会报错，只是状态没保留，属于静默失败）。
     *
     * @param identifier 方块
     * @param expected   该方块在幽灵表里出现过的全部属性名
     * @return 签名；有属性取不到值时返回 null（宁可不接管，也不编一个值）
     */
    private static String buildSignature(ChunkerBlockIdentifier identifier, Set<String> expected) {
        // 先把 Chunker 的状态按「幽灵表里的属性名」归位。
        Map<String, BlockStateValue> byName = new HashMap<>();
        for (BlockState<?> state : identifier.getType().getStates()) {
            String chunkerName = state.getName();
            if (INTERNAL_STATES.contains(chunkerName)) continue;
            String tableName = STATE_ALIASES.getOrDefault(chunkerName, chunkerName);
            BlockStateValue value = stateValue(identifier, state);
            if (value != null) byName.put(tableName, value);
        }

        // 按属性名升序拼（TreeSet 的顺序与生成器里的 sort() 一致）。
        StringBuilder builder = new StringBuilder();
        for (String name : new TreeSet<>(expected)) {
            BlockStateValue value = byName.get(name);
            if (value == null) return null;
            if (!builder.isEmpty()) builder.append(',');
            builder.append(name).append('=').append(format(value));
        }
        return builder.toString();
    }

    /** 取状态值（未设置时回落到方块类型的默认值）。 */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static BlockStateValue stateValue(ChunkerBlockIdentifier identifier, BlockState<?> state) {
        return identifier.getState((BlockState) state);
    }

    /**
     * 状态值 → 幽灵表里的写法。
     * <p>
     * Chunker 的值是枚举常量名（全大写，数字型带前导下划线）：{@code FALSE}、{@code TOP}、
     * {@code OUTER_LEFT}、{@code _6}。幽灵表里要求：布尔写成 {@code 0}/{@code 1}，
     * 其余转小写并去掉前导下划线。
     */
    private static String format(BlockStateValue value) {
        String text = value.toString();
        if (text.equals("TRUE")) return "1";
        if (text.equals("FALSE")) return "0";
        if (text.startsWith("_")) text = text.substring(1);
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * 一个方块的幽灵状态集合。
     *
     * @param attributes     该方块在幽灵表里出现过的全部属性名（拼签名时只写这些）
     * @param bySignature    状态签名 → 幽灵 raw id
     * @param fallbackRawId  默认态的 raw id（目标状态 id 最小的那条）
     * @param knownSignatures 该方块已知的全部签名，回落时打印用
     */
    private record BlockStates(Set<String> attributes, Map<String, Integer> bySignature,
                               Integer fallbackRawId, String knownSignatures) {
    }

    /** 载入结果。 */
    private record Loaded(Map<String, BlockStates> table, int rows) {
    }

    /**
     * 读取幽灵表。
     * <p>
     * 格式：{@code 幽灵rawId|目标方块名|首次出现阶段|目标状态id|状态签名}
     * （注释行与空行跳过；文件尾部的 STAGES 表也是注释，同样跳过）。
     */
    private static Loaded load() throws IOException {
        Map<String, BlockStates> result = new HashMap<>(1024);
        Map<String, Map<String, Integer>> ids = new HashMap<>(1024);
        Map<String, Set<String>> attrs = new HashMap<>(1024);
        InputStream in = GhostBlockHandler.class.getResourceAsStream(RESOURCE);
        if (in == null) {
            throw new IOException("找不到幽灵表：" + RESOURCE);
        }
        int rows = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                String text = line.trim();
                if (text.isEmpty() || text.charAt(0) == '#') continue;
                String[] parts = text.split("\\|", -1);
                if (parts.length != 5) {
                    throw new IOException("幽灵表第 " + lineNo + " 行字段数应为 5，实际 "
                            + parts.length + "：" + text);
                }
                int rawId;
                try {
                    rawId = Integer.parseInt(parts[0].trim());
                } catch (NumberFormatException e) {
                    throw new IOException("幽灵表第 " + lineNo + " 行的 raw id 不是整数：" + text, e);
                }
                String name = parts[1].trim();
                if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
                if (name.isEmpty()) continue;

                String signature = parts[4].trim();
                ids.computeIfAbsent(name, key -> new HashMap<>(64)).put(signature, rawId);

                // 顺便收集该方块用到哪些属性名：拼签名时只写这些，
                // 多写或少写都会查不到（而两边都不会报错，属于静默失败）。
                Set<String> names = attrs.computeIfAbsent(name, key -> new TreeSet<>());
                for (String field : signature.split(",")) {
                    int eq = field.indexOf('=');
                    if (eq > 0) names.add(field.substring(0, eq).trim());
                }
                rows++;
            }
        }
        for (Map.Entry<String, Map<String, Integer>> e : ids.entrySet()) {
            Map<String, Integer> bySignature = e.getValue();
            // 默认态 = 目标状态 id 最小的那条（与生成器的枚举顺序一致）。
            // raw id 是单调递增分配的，所以直接取最小的 raw id 即可。
            Integer fallback = null;
            for (Integer r : bySignature.values()) {
                if (fallback == null || r < fallback) fallback = r;
            }
            StringBuilder known = new StringBuilder();
            for (String s : new TreeSet<>(bySignature.keySet())) {
                if (known.length() > 0) known.append(" / ");
                known.append(s.isEmpty() ? "（无状态）" : s);
                if (known.length() > 300) { known.append(" …"); break; }
            }
            result.put(e.getKey(), new BlockStates(attrs.get(e.getKey()), bySignature, fallback, known.toString()));
        }
        return new Loaded(result, rows);
    }
}
