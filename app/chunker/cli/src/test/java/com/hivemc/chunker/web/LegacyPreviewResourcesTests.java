package com.hivemc.chunker.web;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 资源兼容补丁不能破坏预览的增量渲染。 */
class LegacyPreviewResourcesTests {
    @TempDir Path root;

    /** 重复渲染不得改变兼容包时间戳，损坏的兼容包必须能重新生成。 */
    @Test void reusesUnchangedPackAndRepairsDamagedPack() throws Exception {
        LegacyPreviewResources.install(root);
        Path pack = root.resolve("resourcepacks").resolve(LegacyPreviewResources.PACK_NAME);
        byte[] expected = Files.readAllBytes(pack);
        FileTime old = FileTime.fromMillis(1000000000000L);
        Files.setLastModifiedTime(pack, old);
        LegacyPreviewResources.install(root);
        assertEquals(old, Files.getLastModifiedTime(pack));
        Files.writeString(pack, "broken", java.nio.charset.StandardCharsets.UTF_8);
        LegacyPreviewResources.install(root);
        assertArrayEquals(expected, Files.readAllBytes(pack));
    }
}
