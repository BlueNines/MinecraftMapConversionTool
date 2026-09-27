package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 1.12.2 的空闲群系编号表：把高版本群系登记到它用不到的编号上。
 *
 * <p>1.12.2 存档里每个 chunk 的群系是 256 个 **byte**，只能表达 0-127，而 1.13+ 的群系
 * （深暗、繁茂洞穴、红树林…）根本不在这个编号空间里。不登记的话，它们写进存档时会
 * 掉到默认群系（平原）—— 地图的群系分布整个丢掉。</p>
 *
 * <p>编号由服务端的 GhostBlocks 插件翻译回真实群系：插件的维度注册表补丁把这些编号
 * 指向正确的现代群系名，Geyser 再按名字映射到基岩内置群系。所以
 * <b>本表与插件注册表必须逐行一致</b> —— 不一致会把编号写成另一个群系，且看不出异常。</p>
 *
 * <p>表由 GhostBlocks 的 {@code tools/gen-biome-registry.js} 生成并导出（同一个脚本同时
 * 产出插件侧的注册表补丁），资源目录里的是副本，两份不一致时以插件侧为准。</p>
 */
public final class CustomBiomeSlots {
    private static final String RESOURCE = "/ghostblocks/biome-slots.txt";

    /** 表里写裸名（好读），而群系枚举认的是带命名空间的标识符。 */
    private static final String JAVA_PREFIX = "minecraft:";

    private CustomBiomeSlots() {
    }

    /**
     * 读取槽位表。
     *
     * @return 群系 → 1.12.2 编号，按编号升序。
     */
    public static Map<ChunkerBiome.ChunkerVanillaBiome, Integer> load() {
        try (InputStream stream = CustomBiomeSlots.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) throw new IOException("找不到资源 " + RESOURCE + "（应在 jar 里）");

            Map<ChunkerBiome.ChunkerVanillaBiome, Integer> slots = new LinkedHashMap<>();
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;

                // 每行形如 91|deep_dark
                String[] parts = trimmed.split("\\|", -1);
                if (parts.length != 2) throw new IOException("槽位表行格式不对：" + trimmed);

                int id = Integer.parseInt(parts[0]);
                ChunkerBiome.ChunkerVanillaBiome biome = ChunkerBiome.ChunkerVanillaBiome
                        .find(JAVA_PREFIX + parts[1])
                        .orElseThrow(() -> new IOException("槽位表里的群系无法识别：" + parts[1]));
                slots.put(biome, id);
            }
            if (slots.isEmpty()) throw new IOException("槽位表 " + RESOURCE + " 是空的");
            return slots;
        } catch (IOException e) {
            // 表是随包发布的构建产物，读不了/不合格式都是构建问题，不能静默降级成“没有槽位”，
            // 否则产物看起来正常、群系却全没了。
            throw new UncheckedIOException("读取群系槽位表失败：" + RESOURCE, e);
        }
    }
}
