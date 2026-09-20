package com.hivemc.chunker.web;

import com.google.gson.Gson;
import com.hivemc.chunker.downgrade.ChunkContent;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/** 固定路径的 1.12.2 稀疏预览副本：保留邻区与清空记录，不改写正式输出。 */
final class ResultPreviewWorld {
    private static final Gson GSON = new Gson();
    private static final String STATE_FILE = "selection-v1.json";
    record Prepared(Path directory, long contentChunks, long selectedChunks, long skippedChunks, long changedChunks) {}
    private record State(String fingerprint, long contentChunks, long selectedChunks, long skippedChunks) {}
    private record Region(int x, int z) {
        /** 区域坐标对应的标准 Anvil 文件名。 */
        String name() { return "r." + x + "." + z + ".mca"; }
    }

    /** 重复输入复用副本；变化时仅重写受影响区域，并保留未变区块的预览时间戳。 */
    static Prepared prepare(Path source, Path directory) throws IOException {
        source = source.toRealPath(); directory = directory.toAbsolutePath().normalize();
        if (directory.startsWith(source) || source.startsWith(directory)) throw new IOException("预览副本不能与转换输出重叠。");
        Files.createDirectories(directory.resolve("region"));
        Map<Region, Path> input = regions(source.resolve("region"));
        String fingerprint = fingerprint(source, input.values());
        Path stateFile = directory.resolve(STATE_FILE);
        copyLevel(source, directory);
        if (Files.isRegularFile(stateFile)) {
            State saved = GSON.fromJson(Files.readString(stateFile, StandardCharsets.UTF_8), State.class);
            if (saved != null && fingerprint.equals(saved.fingerprint()))
                return new Prepared(directory, saved.contentChunks(), saved.selectedChunks(), saved.skippedChunks(), 0);
        }

        Set<Long> content = new HashSet<>();
        long stored = 0;
        for (var region : input.entrySet()) {
            interrupted();
            try (var file = new RandomAccessFile(region.getValue().toFile(), "r")) {
                int[] offsets = offsets(file);
                for (int slot = 0; slot < 1024; slot++) {
                    if (offsets[slot] == 0) continue;
                    interrupted(); stored++;
                    byte[] record = record(file, offsets[slot]);
                    if (ChunkContent.hasContent(Tag.readZLibJavaNBT(Arrays.copyOfRange(record, 1, record.length))))
                        content.add(coordinate(region.getKey().x()*32+slot%32, region.getKey().z()*32+slot/32));
                }
            }
        }
        Set<Long> keep = neighbors(content);
        Map<Region, Path> old = regions(directory.resolve("region"));
        Map<Region, BitSet> selected = new HashMap<>();
        Set<Region> allRegions = new HashSet<>(input.keySet()); allRegions.addAll(old.keySet());
        Set<Long> changed = new HashSet<>();
        long selectedCount = 0, selectedInput = 0;
        for (Region region : allRegions) {
            try (var incoming = open(input.get(region)); var previous = open(old.get(region))) {
                int[] sourceOffsets = offsets(incoming), oldOffsets = offsets(previous);
                BitSet slots = new BitSet(1024);
                for (int slot=0; slot<1024; slot++) {
                    int x=region.x()*32+slot%32, z=region.z()*32+slot/32;
                    // 曾参与预览的柱保留空气记录，确保删掉建筑后能清除旧瓦片。
                    if (oldOffsets[slot]==0 && (sourceOffsets[slot]==0 || !keep.contains(coordinate(x,z)))) continue;
                    interrupted(); slots.set(slot); selectedCount++;
                    if (sourceOffsets[slot]!=0) selectedInput++;
                    byte[] next = sourceOffsets[slot]==0 ? emptyColumn(x,z) : record(incoming,sourceOffsets[slot]);
                    byte[] before = oldOffsets[slot]==0 ? null : record(previous,oldOffsets[slot]);
                    if (!Arrays.equals(next,before)) changed.add(coordinate(x,z));
                }
                if (!slots.isEmpty()) selected.put(region,slots);
            }
        }
        // 连接、遮挡与光照依赖相邻柱；边界变化时一并标记近邻，避免只更新半边栅栏。
        Set<Long> dirty = neighbors(changed);
        int timestamp = (int)(System.currentTimeMillis()/1000);
        for (var entry : selected.entrySet()) {
            Region region=entry.getKey(); BitSet slots=entry.getValue();
            boolean write=false;
            for(int slot=slots.nextSetBit(0);slot>=0;slot=slots.nextSetBit(slot+1))
                if(dirty.contains(coordinate(region.x()*32+slot%32,region.z()*32+slot/32))) {write=true;break;}
            if (!write) continue;
            Path target=directory.resolve("region").resolve(region.name()), temporary=target.resolveSibling(region.name()+".tmp");
            try(var incoming=open(input.get(region));var previous=open(old.get(region));var output=new RandomAccessFile(temporary.toFile(),"rw")) {
                int[] sourceOffsets=offsets(incoming),oldOffsets=offsets(previous);
                output.setLength(0);output.setLength(8192);
                for(int slot=slots.nextSetBit(0);slot>=0;slot=slots.nextSetBit(slot+1)) {
                    interrupted();int x=region.x()*32+slot%32,z=region.z()*32+slot/32;
                    byte[] bytes=sourceOffsets[slot]==0?emptyColumn(x,z):record(incoming,sourceOffsets[slot]);
                    int stamp=timestamp;
                    if(!dirty.contains(coordinate(x,z)) && oldOffsets[slot]!=0) {previous.seek(4096+slot*4L);stamp=previous.readInt();}
                    int sector=(int)(output.length()/4096),sectors=(bytes.length+4+4095)/4096;
                    output.seek(output.length());output.writeInt(bytes.length);output.write(bytes);output.setLength((sector+sectors)*4096L);
                    output.seek(slot*4L);output.writeInt((sector<<8)|sectors);
                    output.seek(4096+slot*4L);output.writeInt(stamp);
                }
            }
            Files.move(temporary,target,StandardCopyOption.REPLACE_EXISTING);
        }
        if (!fingerprint.equals(fingerprint(source, regions(source.resolve("region")).values())))
            throw new IOException("准备预览时转换输出发生变化，请等待写入结束后重试。");
        State state=new State(fingerprint,content.size(),selectedCount,stored-selectedInput);
        Files.writeString(stateFile,GSON.toJson(state),StandardCharsets.UTF_8);
        return new Prepared(directory,state.contentChunks(),selectedCount,state.skippedChunks(),changed.size());
    }

    /** 只扫描当前预览使用的主世界区域，不复制其他维度与玩家文件。 */
    private static Map<Region,Path> regions(Path directory) throws IOException {
        Map<Region,Path> result=new HashMap<>();
        if (!Files.isDirectory(directory)) return result;
        try(var paths=Files.list(directory)) {
            for(Path file:paths.filter(Files::isRegularFile).toList()) {
                String[] parts=file.getFileName().toString().split("\\.");
                if(parts.length==4 && parts[0].equals("r") && parts[3].equals("mca"))
                    result.put(new Region(Integer.parseInt(parts[1]),Integer.parseInt(parts[2])),file);
            }
        }
        return result;
    }

    /** 只读打开现有区域；缺失区域视为空，便于处理跨区域删除。 */
    private static RandomAccessFile open(Path file) throws IOException { return file==null?null:new RandomAccessFile(file.toFile(),"r"); }

    /** 读取位置表，拒绝损坏头部而不是默默漏掉内容。 */
    private static int[] offsets(RandomAccessFile file) throws IOException {
        int[] values=new int[1024];if(file==null||file.length()==0)return values;
        if(file.length()<8192)throw new IOException("结果区域文件头不完整。");
        file.seek(0);for(int i=0;i<1024;i++)values[i]=file.readInt()>>>8;return values;
    }

    /** 转换器输出固定为内置 zlib；每次仅读一个记录，不缓存整张世界。 */
    private static byte[] record(RandomAccessFile file,int offset) throws IOException {
        long start=offset*4096L;if(offset<2||start+5>file.length())throw new IOException("结果区块位置越界。");
        file.seek(start);int length=file.readInt();
        if(length<2||length>64*1024*1024||start+4+length>file.length())throw new IOException("结果区块长度异常。");
        byte[] bytes=new byte[length];file.readFully(bytes);
        if(bytes[0]!=2)throw new IOException("结果预览需要转换器生成的 zlib 区块。");
        return bytes;
    }

    /** 原区块完全删除时写一根可渲染的空气柱，让旧建筑消失而非留在缓存中。 */
    private static byte[] emptyColumn(int x,int z) throws IOException {
        CompoundTag root=new CompoundTag(),level=new CompoundTag();root.put("DataVersion",1343);root.put("Level",level);
        level.put("xPos",x);level.put("zPos",z);level.put("TerrainPopulated",(byte)1);level.put("LightPopulated",(byte)1);
        level.put("Biomes",new byte[256]);byte[] compressed=Tag.writeZLibJavaNBT(root),record=new byte[compressed.length+1];
        record[0]=2;System.arraycopy(compressed,0,record,1,compressed.length);return record;
    }

    /** 地图配置独立同步，不因 level.dat 每次重写就重新扫描所有区块。 */
    private static void copyLevel(Path source,Path target) throws IOException {
        Path input=source.resolve("level.dat"),output=target.resolve("level.dat");
        if(!Files.isRegularFile(output)||Files.mismatch(input,output)!=-1) Files.copy(input,output,StandardCopyOption.REPLACE_EXISTING);
    }

    /** 内容未变时仅检查区域路径、大小和高精度修改时间。 */
    private static String fingerprint(Path source,Collection<Path> files) throws IOException {
        try {
            var hash=MessageDigest.getInstance("SHA-256");hash.update(source.toString().getBytes(StandardCharsets.UTF_8));
            for(Path file:files.stream().sorted().toList())hash.update((file.getFileName()+":"+Files.size(file)+":"+Files.getLastModifiedTime(file)+"\n").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash.digest());
        } catch(NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }

    /** 保留一圈邻区，用于栅栏连接、遮挡及区块边界的重渲染。 */
    private static Set<Long> neighbors(Set<Long> chunks) {
        Set<Long> result=new HashSet<>();for(long c:chunks) {int x=(int)(c>>32),z=(int)c;
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)result.add(coordinate(x+dx,z+dz));}
        return result;
    }

    /** 无损保存两个有符号区块坐标。 */
    private static long coordinate(int x,int z) {return ((long)x<<32)|(z&0xffffffffL);}

    /** 取消时及时结束，未提交的副本不会发布为新预览。 */
    private static void interrupted() throws InterruptedIOException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("结果预览准备已取消。");
    }
}
