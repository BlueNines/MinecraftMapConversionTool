package com.hivemc.chunker.web;

import com.google.gson.JsonObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** 仅修正 BlueMap 1.5.5 的模型匹配，不修改导出存档的方块数据。 */
final class LegacyPreviewResources {
    static final String PACK_NAME = "chunker-legacy-states-v1.zip";

    /** 提取单独的邻接修补类，仅放在旧 BlueMap 的类路径前端。 */
    static Path installPatch(Path workFolder) throws IOException {
        Path target = workFolder.resolve("legacy-preview-patch.jar");
        Files.createDirectories(workFolder);
        try (var input = LegacyPreviewResources.class.getResourceAsStream("/web/legacy-preview-patch.jar")) {
            if (input == null) throw new IOException("缺少旧版预览连接状态补丁。");
            byte[] content = input.readAllBytes();
            if (!Files.isRegularFile(target) || Files.size(target) != content.length
                    || !Arrays.equals(Files.readAllBytes(target), content)) Files.write(target, content);
        }
        return target.toAbsolutePath();
    }

    /** 旧读取器缺少 in_wall：保留贴墙模型条件，并为其余状态按朝向和开合匹配。 */
    static void install(Path workFolder) throws IOException {
        Path packs = workFolder.resolve("resourcepacks");
        Files.createDirectories(packs);
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            entry(zip, "pack.mcmeta", "{\"pack\":{\"pack_format\":3,\"description\":\"Chunker legacy preview state compatibility\"}}");
            for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")) {
                String id = wood.equals("oak") ? "fence_gate" : wood + "_fence_gate";
                entry(zip, "assets/minecraft/blockstates/" + id + ".json", gateVariants(wood).toString());
            }
        }
        Path target = packs.resolve(PACK_NAME);
        byte[] content = bytes.toByteArray();
        // 相同兼容包不重写，避免每次增量预览都触发资源变化检查。
        if (Files.isRegularFile(target) && Files.size(target) == content.length
                && Arrays.equals(Files.readAllBytes(target), content)) return;
        Files.write(target, content);
    }

    /** 先匹配明确的贴墙状态，再以普通栅栏门兼容缺少 in_wall 的旧状态。 */
    static JsonObject gateVariants(String wood) {
        JsonObject root = new JsonObject(), variants = new JsonObject();
        String[] directions = {"south", "west", "north", "east"};
        for (boolean inWall : new boolean[]{true, false}) {
            for (boolean open : new boolean[]{false, true}) {
                for (int i = 0; i < directions.length; i++) {
                    JsonObject model = new JsonObject();
                    model.addProperty("model", wood + (inWall ? "_wall_gate_" : "_fence_gate_") + (open ? "open" : "closed"));
                    model.addProperty("uvlock", true);
                    model.addProperty("y", i * 90);
                    variants.add("facing=" + directions[i] + (inWall ? ",in_wall=true" : "") + ",open=" + open, model);
                }
            }
        }
        root.add("variants", variants);
        return root;
    }

    /** 写入少量 UTF-8 模型定义，不复制客户端贴图，也不加载世界到内存。 */
    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(text.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
