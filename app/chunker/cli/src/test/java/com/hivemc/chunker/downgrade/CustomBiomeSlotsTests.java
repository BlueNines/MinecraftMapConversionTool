package com.hivemc.chunker.downgrade;

import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.biome.JavaBiomeIDResolver;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 自定义群系槽位表本身的校验。
 *
 * <p>这张表跨界：转换工具按它决定「写哪个编号」，服务端插件按同一张表决定「那个编号是什么群系」。
 * 两边靠同一份 {@code biome-slots.txt}（由插件侧生成脚本导出），所以这里除了自洽性，
 * 还要钉住「编号不越界、不与 1.12.2 原生占用重叠」这类跨界契约。</p>
 */
public class CustomBiomeSlotsTests {
    private static final Version LEGACY = new Version(1, 12, 2);

    /** 1.12.2 原生占用的群系编号。槽位选在这些上面会把原有群系显示弄坏。 */
    private static final List<Integer> VANILLA_1_12_2 = List.of(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20,
            21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39,
            127, 129, 130, 131, 132, 133, 134, 140, 149, 151, 155, 156, 157, 158, 160, 161,
            162, 163, 164, 165, 166, 167);

    @Test
    public void slotsFileIsNotEmpty() {
        assertFalse(CustomBiomeSlots.load().isEmpty());
    }

    @Test
    public void slotsFitInASignedByte() {
        // 1.12.2 存档里群系是 256 个有符号 byte：128-255 会被读成负数，无法还原成群系。
        for (Map.Entry<ChunkerBiome.ChunkerVanillaBiome, Integer> slot : CustomBiomeSlots.load().entrySet()) {
            int id = slot.getValue();
            assertTrue(id >= 0 && id <= 127, slot.getKey() + " 的槽位 " + id + " 超出 0-127");
        }
    }

    @Test
    public void slotsDoNotCollideWithVanillaBiomes() {
        for (Map.Entry<ChunkerBiome.ChunkerVanillaBiome, Integer> slot : CustomBiomeSlots.load().entrySet()) {
            assertFalse(VANILLA_1_12_2.contains(slot.getValue()),
                    slot.getKey() + " 的槽位 " + slot.getValue() + " 与 1.12.2 原生群系编号重叠");
        }
    }

    @Test
    public void slotsAreUnique() {
        // 两个群系共用一个编号时，写进去的是同一个值，其中一个会静默丢失。
        assertFalse(hasDuplicate(CustomBiomeSlots.load().values()), "槽位表里有重复编号");
    }

    @Test
    public void slotsAreRegisteredWhenEnabled() {
        // 槽位只在无损模式启用，此时每个群系都必须能查到自己的编号（否则会掉到默认群系）。
        JavaBiomeIDResolver resolver = new JavaBiomeIDResolver(LEGACY, true);
        for (Map.Entry<ChunkerBiome.ChunkerVanillaBiome, Integer> slot : CustomBiomeSlots.load().entrySet()) {
            Optional<Integer> resolved = resolver.from(slot.getKey());
            assertTrue(resolved.isPresent(), slot.getKey() + " 没有登记到任何编号");
            assertEquals(slot.getValue(), resolved.get(), slot.getKey() + " 登记到的编号与槽位表不一致");
        }
    }

    @Test
    public void slotsAreAbsentWhenDisabled() {
        // 降级模式的产物要能被原版 1.12.2 打开，所以不能带上自定义编号。
        JavaBiomeIDResolver resolver = new JavaBiomeIDResolver(LEGACY, false);
        for (ChunkerBiome.ChunkerVanillaBiome biome : CustomBiomeSlots.load().keySet()) {
            assertTrue(resolver.from(biome).isEmpty(),
                    biome + " 在降级模式下不应该有编号（否则会写出自定义槽位）");
        }
    }

    @Test
    public void slotsRoundTrip() {
        // 写进去的编号要能读回同一个群系，否则转换产物再转一次就变形了。
        JavaBiomeIDResolver resolver = new JavaBiomeIDResolver(LEGACY, true);
        for (Map.Entry<ChunkerBiome.ChunkerVanillaBiome, Integer> slot : CustomBiomeSlots.load().entrySet()) {
            Optional<ChunkerBiome> readBack = resolver.to(slot.getValue());
            assertTrue(readBack.isPresent(), "槽位 " + slot.getValue() + " 读不回任何群系");
            assertEquals(slot.getKey(), readBack.get(), "槽位 " + slot.getValue() + " 读回的不是 " + slot.getKey());
        }
    }

    @Test
    public void slotsDoNotChangeNewerVersions() {
        // 1.13-1.17 有一批群系自己就在 40-50（末地/海洋细分类），
        // 若此时把自定义槽位也登记上去，就会把这些群系从它们的原生编号挪走。
        // 例如 BAMBOO_JUNGLE 在 1.14+ 是 168，而槽位表把它放在 47。
        // 所以契约是：启用槽位不得改变 1.13+ 任何群系的映射结果。
        Map<ChunkerBiome.ChunkerVanillaBiome, Integer> slots = CustomBiomeSlots.load();
        for (Version version : List.of(new Version(1, 13, 0), new Version(1, 16, 5), new Version(1, 17, 1))) {
            JavaBiomeIDResolver plain = new JavaBiomeIDResolver(version, false);
            JavaBiomeIDResolver withSlots = new JavaBiomeIDResolver(version, true);
            for (ChunkerBiome.ChunkerVanillaBiome biome : slots.keySet()) {
                assertEquals(plain.from(biome), withSlots.from(biome),
                        version + " 下 " + biome + " 的编号被自定义槽位改动了");
            }
        }
    }

    /**
     * 槽位表要和插件侧逐行一致。
     *
     * <p>这是跨仓库契约：不一致时转换工具写 91，而插件可能把 91 当成别的群系 ——
     * 产物看起来正常，群系却是错的。两份文件的拷贝由插件的生成脚本统一导出。</p>
     */
    @Test
    public void slotsMatchThePluginSideCopy() throws IOException {
        // 插件侧原始位置（同机开发环境）。不在时跳过：CI 上只跑本仓库的测试。
        java.io.File pluginCopy = new java.io.File(
                "../../../../../../../GhostBlocks/src/main/resources/biome-slots.txt");
        if (!pluginCopy.isFile()) {
            java.io.File alt = new java.io.File("C:/Users/xskj/IdeaProjects/GhostBlocks/src/main/resources/biome-slots.txt");
            if (!alt.isFile()) return;
            pluginCopy = alt;
        }

        List<String> plugin = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(pluginCopy), StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.trim().isEmpty()) plugin.add(line.trim());
            }
        }

        List<String> ours = new ArrayList<>();
        for (Map.Entry<ChunkerBiome.ChunkerVanillaBiome, Integer> slot : CustomBiomeSlots.load().entrySet()) {
            ours.add(slot.getValue() + "|" + slot.getKey().getJavaIdentifier().orElseThrow()
                    .substring("minecraft:".length()));
        }
        ours.sort(String::compareTo);
        plugin.sort(String::compareTo);
        assertEquals(plugin, ours, "本仓库的槽位表与插件侧不一致（插件侧为唯一事实源）");
    }

    private static boolean hasDuplicate(java.util.Collection<Integer> values) {
        return values.size() != new java.util.HashSet<>(values).size();
    }

    @Test
    public void slotsFileParsesEveryLineAsIdName() throws IOException {
        // 表里一行写错（多余分隔符/空名字）会让某个群系静默拿到错编号。
        try (InputStream stream = CustomBiomeSlots.class.getResourceAsStream("/ghostblocks/biome-slots.txt")) {
            assertNotNull(stream);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            int lines = 0;
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (line.trim().isEmpty()) continue;
                lines++;
                String[] parts = line.split("\\|", -1);
                assertEquals(2, parts.length, "槽位表行应该有且仅有一个分隔符：" + line);
                assertFalse(parts[1].trim().isEmpty(), "槽位表行的群系名不能为空：" + line);
                assertFalse(parts[1].contains(":"), "槽位表写裸名，不带命名空间：" + line);
            }
            assertTrue(lines > 0);
        }
    }
}
