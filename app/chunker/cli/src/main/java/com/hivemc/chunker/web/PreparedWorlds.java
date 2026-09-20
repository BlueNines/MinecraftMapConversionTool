package com.hivemc.chunker.web;

import com.google.gson.Gson;
import com.hivemc.chunker.downgrade.ChunkContent;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 一次只读内容核查，生成供分析、转换和源预览共同使用的稀疏副本。 */
public final class PreparedWorlds {
    private static final Gson GSON = new Gson();
    private final Path root;

    /** 统计与镜头边界只含内容区块；邻区块单列统计。 */
    public record Summary(long storedChunks, long contentChunks, long contextChunks, long skippedChunks,
                          int minX, int maxX, int minZ, int maxZ) {
        /** 将源预览定位到实际内容，避免默认世界原点的极远镜头。 */
        public String camera() {
            if (contentChunks == 0 || minX > maxX) return "";
            double x = ((long) minX + maxX + 1) * 8.0;
            double z = ((long) minZ + maxZ + 1) * 8.0;
            double distance = Math.max(100, Math.max((long) maxX - minX + 1, (long) maxZ - minZ + 1) * 16);
            return "source:" + x + ":0:" + z + ":" + distance + ":0:0:0:0:perspective";
        }
    }
    public record Prepared(Path directory, Summary summary) {}
    private record Region(Path path, Path dimension, int x, int z) {}
    private record Copied(long terrainChunks, long contextChunks) {}

    /** 缓存目录与原地图、正式输出分开，原文件不作任何改写。 */
    public PreparedWorlds(Path root) { this.root = root.toAbsolutePath().normalize(); }

    /** 同一源同时发起分析和预览时，只执行一次核查；缓存随源文件变更失效。 */
    public synchronized Prepared prepare(Path source) throws IOException {
        source = source.toRealPath();
        if (root.startsWith(source) || source.startsWith(root)) throw new IOException("缓存目录不能与源地图重叠。 ");
        List<Path> files;
        try (var walk = Files.walk(source)) {
            files = walk.filter(Files::isRegularFile).filter(PreparedWorlds::isWorldData).sorted().toList();
        }
        String fingerprint = fingerprint(source, files);
        Path directory = root.resolve(fingerprint);
        Path complete = directory.resolve("selection.json");
        if (Files.isRegularFile(complete)) {
            return new Prepared(directory, GSON.fromJson(Files.readString(complete, StandardCharsets.UTF_8), Summary.class));
        }
        Files.createDirectories(directory);
        List<Region> regions = new ArrayList<>();
        Map<Path, Set<Long>> content = new HashMap<>();
        long stored = 0;
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        // 每次只解码一个记录；内存与区块数量无关，仅索引存储坐标。
        for (Path file : files) {
            interrupted();
            Region region = region(file, source);
            if (region == null) continue;
            regions.add(region);
            String kind = file.getParent().getFileName().toString();
            if (kind.equals("poi")) continue;
            try (RandomAccessFile input = new RandomAccessFile(file.toFile(), "r")) {
                if (input.length() == 0) continue;
                if (input.length() < 8192) throw new IOException("区块文件头不完整：" + file);
                int[] offsets = offsets(input);
                for (int slot = 0; slot < 1024; slot++) {
                    if (offsets[slot] == 0) continue;
                    interrupted();
                    int x = region.x * 32 + slot % 32, z = region.z * 32 + slot / 32;
                    if (kind.equals("region")) stored++;
                    byte[] record = record(input, offsets[slot]);
                    CompoundTag nbt = decode(file, x, z, record);
                    if (ChunkContent.hasContent(nbt)) {
                        content.computeIfAbsent(region.dimension, ignored -> new HashSet<>()).add(coordinate(x, z));
                        if (region.dimension.toString().isEmpty()) {
                            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
                            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
                        }
                    }
                }
            }
        }
        Map<Path, Set<Long>> selected = new HashMap<>();
        long contentCount = 0;
        for (var entry : content.entrySet()) {
            contentCount += entry.getValue().size();
            Set<Long> neighbors = new HashSet<>(entry.getValue());
            for (long coordinate : entry.getValue()) {
                int x = (int) (coordinate >> 32), z = (int) coordinate;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) neighbors.add(coordinate(x + dx, z + dz));
            }
            selected.put(entry.getKey(), neighbors);
        }
        long context = 0, copiedTerrain = 0;
        for (Region region : regions) {
            Set<Long> keep = selected.getOrDefault(region.dimension, Set.of());
            Set<Long> actual = content.getOrDefault(region.dimension, Set.of());
            Path target = directory.resolve(source.relativize(region.path));
            Copied copied = copyRegion(region, target, keep, actual);
            context += copied.contextChunks(); copiedTerrain += copied.terrainChunks();
        }
        // 保留世界配置、数据包和生成器；不复制玩家、截图或无关启动器资料。
        for (Path file : files) {
            if (file.toString().endsWith(".mca") || file.toString().endsWith(".mcc")) continue;
            Path target = directory.resolve(source.relativize(file));
            Files.createDirectories(target.getParent());
            Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING);
        }
        if (!fingerprint.equals(fingerprint(source, files))) throw new IOException("核查过程中源地图发生变化，请关闭游戏后重新分析。 ");
        Summary summary = new Summary(stored, contentCount, context, stored - copiedTerrain, minX, maxX, minZ, maxZ);
        Files.writeString(complete, GSON.toJson(summary), StandardCharsets.UTF_8);
        return new Prepared(directory, summary);
    }

    /** 只索引 Minecraft 世界数据及其生成配置。 */
    private static boolean isWorldData(Path file) {
        String name = file.getFileName().toString();
        if (name.equals("level.dat") || name.equals("icon.png")) return true;
        for (Path part : file) if (part.toString().equals("datapacks") || part.toString().equals("data")) return true;
        String folder = file.getParent().getFileName().toString();
        return Set.of("region", "entities", "poi").contains(folder) && (name.endsWith(".mca") || name.endsWith(".mcc"));
    }

    /** 从文件名确定区域坐标，不依赖 NBT 中可能过时的坐标。 */
    private static Region region(Path file, Path source) {
        String[] parts = file.getFileName().toString().split("\\.");
        if (parts.length != 4 || !parts[0].equals("r") || !parts[3].equals("mca")) return null;
        return new Region(file, source.relativize(file.getParent().getParent()), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
    }

    /** 用路径、大小和修改时间标识源版本；不会为了缓存哈希再读一遍所有区块。 */
    private static String fingerprint(Path source, List<Path> files) throws IOException {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            hash.update(("content-v1\n" + source).getBytes(StandardCharsets.UTF_8));
            for (Path file : files) hash.update((source.relativize(file) + ":" + Files.size(file) + ":" + Files.getLastModifiedTime(file) + "\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest()).substring(0, 24);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    /** 将两个有符号区块坐标无损打包。 */
    private static long coordinate(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    /** 读取 1024 个区块的位置表。 */
    private static int[] offsets(RandomAccessFile input) throws IOException {
        input.seek(0);
        int[] offsets = new int[1024];
        for (int i = 0; i < offsets.length; i++) offsets[i] = input.readInt() >>> 8;
        return offsets;
    }

    /** 读取含压缩标记的原始记录，并验证边界以避免错误的巨额分配。 */
    private static byte[] record(RandomAccessFile input, int offset) throws IOException {
        long start = offset * 4096L;
        if (offset < 2 || start + 5 > input.length()) throw new IOException("区块位置越界。 ");
        input.seek(start);
        int length = input.readInt();
        if (length < 1 || length > 64 * 1024 * 1024 || start + 4 + length > input.length()) throw new IOException("区块长度异常。 ");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return bytes;
    }

    /** 支持 MCA 的全部现用压缩方式，包括外置超大区块；未知格式直接报错。 */
    private static CompoundTag decode(Path file, int x, int z, byte[] record) throws IOException {
        int compression = record[0] & 127;
        byte[] bytes = (record[0] & 128) == 0 ? java.util.Arrays.copyOfRange(record, 1, record.length)
                : Files.readAllBytes(file.getParent().resolve("c." + x + "." + z + ".mcc"));
        return switch (compression) {
            case 1 -> Tag.readGZipJavaNBT(bytes);
            case 2 -> Tag.readZLibJavaNBT(bytes);
            case 3 -> Tag.readUncompressedJavaNBT(bytes);
            case 4 -> Tag.readLZ4JavaNBT(bytes);
            default -> throw new IOException("不支持的区块压缩类型：" + compression);
        };
    }

    /** 原样复制选中的压缩记录和时间戳；从不重新编码源方块或光照数据。 */
    private static Copied copyRegion(Region region, Path target, Set<Long> keep, Set<Long> actual) throws IOException {
        long context = 0, terrain = 0;
        if (Files.size(region.path) == 0 || keep.isEmpty()) return new Copied(0, 0);
        try (RandomAccessFile input = new RandomAccessFile(region.path.toFile(), "r")) {
            int[] offsets = offsets(input);
            RandomAccessFile output = null;
            try {
                for (int slot = 0; slot < 1024; slot++) {
                    int x = region.x * 32 + slot % 32, z = region.z * 32 + slot / 32;
                    long coordinate = coordinate(x, z);
                    if (offsets[slot] == 0 || !keep.contains(coordinate)) continue;
                    interrupted();
                    byte[] record = record(input, offsets[slot]);
                    if (output == null) {
                        Files.createDirectories(target.getParent());
                        output = new RandomAccessFile(target.toFile(), "rw");
                        output.setLength(0); output.setLength(8192);
                    }
                    int sector = (int) (output.length() / 4096);
                    int sectors = (record.length + 4 + 4095) / 4096;
                    output.seek(output.length()); output.writeInt(record.length); output.write(record);
                    output.setLength((long) (sector + sectors) * 4096);
                    output.seek(slot * 4L); output.writeInt((sector << 8) | sectors);
                    input.seek(4096 + slot * 4L); int timestamp = input.readInt();
                    output.seek(4096 + slot * 4L); output.writeInt(timestamp);
                    if ((record[0] & 128) != 0) {
                        String external = "c." + x + "." + z + ".mcc";
                        Files.copy(region.path.getParent().resolve(external), target.getParent().resolve(external), StandardCopyOption.REPLACE_EXISTING);
                    }
                    if (region.path.getParent().getFileName().toString().equals("region")) {
                        terrain++;
                        if (!actual.contains(coordinate)) context++;
                    }
                }
            } finally { if (output != null) output.close(); }
        }
        return new Copied(terrain, context);
    }

    /** 项目切换或退出时及时停止扫描。 */
    private static void interrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("内容核查已取消。 ");
    }
}
