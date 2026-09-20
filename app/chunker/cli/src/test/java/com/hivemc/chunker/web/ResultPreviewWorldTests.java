package com.hivemc.chunker.web;

import com.hivemc.chunker.downgrade.ChunkContent;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static com.hivemc.chunker.web.PreparedWorldsTests.*;
import static org.junit.jupiter.api.Assertions.*;

/** 稀疏结果必须保留真实内容、连接上下文与删除通知，且不破坏增量时间戳。 */
class ResultPreviewWorldTests {
    @TempDir Path root;

    /** 首次排除历史空区块，相同输入复用固定副本，正式输出字节保持不变。 */
    @Test void filtersHistoricalEmptyChunksAndReusesStableCopy() throws Exception {
        Path source=source(),region=source.resolve("region/r.0.0.mca"),target=root.resolve("preview");
        write(region,0,column(true));write(region,1,column(false));write(region,10,column(false));
        byte[] original=Files.readAllBytes(region);
        var first=ResultPreviewWorld.prepare(source,target);
        assertEquals(1,first.contentChunks());assertEquals(2,first.selectedChunks());assertEquals(1,first.skippedChunks());
        Path preview=target.resolve("region/r.0.0.mca");byte[] snapshot=Files.readAllBytes(preview);
        var modified=Files.getLastModifiedTime(preview);
        assertEquals(0,ResultPreviewWorld.prepare(source,target).changedChunks());
        assertArrayEquals(snapshot,Files.readAllBytes(preview));assertEquals(modified,Files.getLastModifiedTime(preview));
        assertArrayEquals(original,Files.readAllBytes(region));
        assertNotNull(read(preview,0));assertNotNull(read(preview,1));assertNull(read(preview,10));
    }

    /** 同区域远处时间戳不变，跨区域相邻柱即使字节没变也需刷新连接状态。 */
    @Test void updatesNeighborAcrossRegionBoundaryWithoutInvalidatingDistantChunks() throws Exception {
        Path source=source(),left=source.resolve("region/r.0.0.mca"),right=source.resolve("region/r.1.0.mca"),target=root.resolve("preview");
        write(left,31,column(true));write(right,0,column(false));write(left,10,column(true));
        ResultPreviewWorld.prepare(source,target);
        Path a=target.resolve("region/r.0.0.mca"),b=target.resolve("region/r.1.0.mca");
        stamp(a,10,123);stamp(a,31,123);stamp(b,0,123);
        write(left,31,column(false));
        var next=ResultPreviewWorld.prepare(source,target);
        assertEquals(1,next.changedChunks());assertEquals(1,next.contentChunks());
        assertEquals(123,stamp(a,10,null));assertTrue(stamp(a,31,null)>123);assertTrue(stamp(b,0,null)>123);
        assertFalse(ChunkContent.hasContent(read(a,31)));assertNotNull(read(b,0));
    }

    /** 所有内容和整个区域文件被删除后，仍以带新时间戳的空气记录清除旧模型。 */
    @Test void deletedRegionLeavesRenderableTombstone() throws Exception {
        Path source=source(),region=source.resolve("region/r.0.0.mca"),target=root.resolve("preview");
        write(region,0,column(true));ResultPreviewWorld.prepare(source,target);
        Files.delete(region);
        var empty=ResultPreviewWorld.prepare(source,target);
        assertEquals(0,empty.contentChunks());assertEquals(1,empty.changedChunks());
        var tombstone=read(target.resolve("region/r.0.0.mca"),0);
        assertNotNull(tombstone);assertFalse(ChunkContent.hasContent(tombstone));
        assertEquals(1,tombstone.getCompound("Level").getByte("TerrainPopulated"));
        assertEquals(0,ResultPreviewWorld.prepare(source,target).changedChunks());
    }

    /** 元数据更新不应重新筛选，也不改变地图模型的更新时间。 */
    @Test void levelUpdateDoesNotInvalidateRegionCache() throws Exception {
        Path source=source(),region=source.resolve("region/r.0.0.mca"),target=root.resolve("preview");
        write(region,0,column(true));ResultPreviewWorld.prepare(source,target);
        byte[] before=Files.readAllBytes(target.resolve("region/r.0.0.mca"));
        Files.write(source.resolve("level.dat"),new byte[]{4,5,6});
        assertEquals(0,ResultPreviewWorld.prepare(source,target).changedChunks());
        assertArrayEquals(before,Files.readAllBytes(target.resolve("region/r.0.0.mca")));
        assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(target.resolve("level.dat")));
    }

    /** 建立仅供文件同步测试使用的源目录。 */
    private Path source() throws Exception {
        Path path=root.resolve("source");Files.createDirectories(path);Files.write(path.resolve("level.dat"),new byte[]{1,2,3});return path;
    }

    /** 从实际位置表读取一条压缩 NBT，缺失槽返回空。 */
    private static CompoundTag read(Path region,int slot) throws Exception {
        try(var file=new RandomAccessFile(region.toFile(),"r")) {
            file.seek(slot*4L);int offset=file.readInt()>>>8;if(offset==0)return null;
            file.seek(offset*4096L);int length=file.readInt();assertEquals(2,file.readByte());
            byte[] bytes=new byte[length-1];file.readFully(bytes);return Tag.readZLibJavaNBT(bytes);
        }
    }

    /** 设置或查询区块时间戳，避免依赖测试执行时碰巧跨过一秒。 */
    private static int stamp(Path region,int slot,Integer value) throws Exception {
        try(var file=new RandomAccessFile(region.toFile(),"rw")) {
            file.seek(4096+slot*4L);if(value!=null){file.writeInt(value);return value;}return file.readInt();
        }
    }
}
