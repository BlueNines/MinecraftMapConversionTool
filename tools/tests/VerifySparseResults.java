package com.hivemc.chunker.web;

import com.google.gson.JsonParser;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** 用真实 BlueMap 验证稀疏副本首次渲染、复用、改材质和整块删除。 */
public class VerifySparseResults {
    /** 参数为正式输出只读路径、独立测试目录、BlueMap路径和Java路径。 */
    public static void main(String[] args) throws Exception {
        Path source=Path.of(args[0]),root=Path.of(args[1]);
        var runner=new BlueMapRunner(Path.of(args[2]),Path.of(args[3]));
        var before=fingerprints(source);
        render(runner,source,root.resolve("real"),"REAL_FIRST");
        var first=fingerprints(root.resolve("real/web/data/result"));
        render(runner,source,root.resolve("real"),"REAL_REPEAT");
        if(!first.equals(fingerprints(root.resolve("real/web/data/result"))))throw new AssertionError("Unchanged render rewrote tiles");
        if(!before.equals(fingerprints(source)))throw new AssertionError("Official output changed");
        Path fixture=root.resolve("fixture");Files.createDirectories(fixture);
        Files.copy(source.resolve("level.dat"),fixture.resolve("level.dat"),StandardCopyOption.REPLACE_EXISTING);
        Path tiny=root.resolve("tiny");seed(root.resolve("real"),tiny);
        cube(fixture,1);render(runner,fixture,tiny,"CUBE");
        if(vertices(tiny)==0)throw new AssertionError("Initial cube not rendered");
        var stone=meshFingerprints(tiny.resolve("web/data/result"));
        cube(fixture,5);render(runner,fixture,tiny,"MATERIAL_CHANGE");
        if(stone.equals(meshFingerprints(tiny.resolve("web/data/result"))))throw new AssertionError("Changed block did not update mesh");
        Files.delete(fixture.resolve("region/r.0.0.mca"));render(runner,fixture,tiny,"DELETE_REGION");
        if(vertices(tiny)!=0)throw new AssertionError("Deleted cube remains visible");
        System.out.println("PASS official-output-unchanged; repeat-no-tile-rewrite; material-updated; deleted-geometry-empty");
    }

    /** 分别计时核查与真正的渲染进程，不计下载时间。 */
    private static void render(BlueMapRunner runner,Path source,Path work,String name) throws Exception {
        long start=System.nanoTime();var prepared=ResultPreviewWorld.prepare(source,work.resolve("world"));
        double preparation=(System.nanoTime()-start)/1e9;start=System.nanoTime();
        runner.render(false,prepared.directory(),work,38901);
        System.out.println(name+" PREPARE="+preparation+" RENDER="+(System.nanoTime()-start)/1e9+" "+prepared);
    }

    /** 仅复用只读客户端资源，两个测试渲染缓存相互隔离。 */
    private static void seed(Path source,Path target) throws Exception {
        Files.createDirectories(target.resolve("data"));
        for(String name:List.of("minecraft-client-1.12.0.jar","resourceExtensions.zip"))
            Files.copy(source.resolve("data").resolve(name),target.resolve("data").resolve(name),StandardCopyOption.REPLACE_EXISTING);
    }

    /** 合成一个有光照的可见方块体，地图仅供本测试改写。 */
    private static void cube(Path root,int id) throws Exception {
        CompoundTag chunk=new CompoundTag(),level=new CompoundTag(),section=new CompoundTag();
        chunk.put("DataVersion",1343);chunk.put("Level",level);level.put("xPos",0);level.put("zPos",0);
        level.put("TerrainPopulated",(byte)1);level.put("LightPopulated",(byte)1);level.put("Biomes",new byte[256]);
        byte[] blocks=new byte[4096],sky=new byte[2048];Arrays.fill(sky,(byte)255);
        for(int y=2;y<6;y++)for(int z=2;z<6;z++)for(int x=2;x<6;x++)blocks[y*256+z*16+x]=(byte)id;
        section.put("Y",(byte)1);section.put("Blocks",blocks);section.put("Data",new byte[2048]);
        section.put("SkyLight",sky);section.put("BlockLight",new byte[2048]);
        var sections=new ListTag<CompoundTag,Map<String,Tag<?>>>(TagType.COMPOUND);sections.add(section);level.put("Sections",sections);
        byte[] bytes=Tag.writeZLibJavaNBT(chunk);Files.createDirectories(root.resolve("region"));
        try(var file=new RandomAccessFile(root.resolve("region/r.0.0.mca").toFile(),"rw")) {
            file.setLength(0);file.setLength(12288);file.writeInt((2<<8)|1);file.seek(4096);file.writeInt((int)(System.currentTimeMillis()/1000));
            file.seek(8192);file.writeInt(bytes.length+1);file.writeByte(2);file.write(bytes);
        }
    }

    /** 逐个文件取指纹，避免同时加载整套真实地图模型。 */
    private static Map<String,String> fingerprints(Path root) throws Exception {
        Map<String,String> values=new TreeMap<>();try(var paths=Files.walk(root)) {
            for(Path file:paths.filter(Files::isRegularFile).filter(p->!p.getFileName().toString().equals(".rstate")).toList()) {
                var hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];
                try(var input=Files.newInputStream(file)){int n;while((n=input.read(buffer))>0)hash.update(buffer,0,n);}
                values.put(root.relativize(file).toString(),HexFormat.of().formatHex(hash.digest()));
            }
        }return values;
    }

    /** 空瓦片可以仍存在，但不能再包含任何三角形顶点。 */
    private static long vertices(Path work) throws Exception {
        long result=0;try(var paths=Files.walk(work.resolve("web/data/result/hires"))) {
            for(Path path:paths.filter(p->p.toString().endsWith(".json")).toList()) {
                var mesh=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();
                result+=mesh.getAsJsonObject("data").getAsJsonObject("attributes").getAsJsonObject("position").getAsJsonArray("array").size();
            }
        }return result;
    }

    /** 小型材质用例排除随机 UUID，必须实际改变网格材质数据才算更新成功。 */
    private static Map<String,String> meshFingerprints(Path root) throws Exception {
        Map<String,String> result=new TreeMap<>();
        try(var paths=Files.walk(root)) {
            for(Path path:paths.filter(p->p.toString().endsWith(".json")).toList()) {
                var mesh=JsonParser.parseString(Files.readString(path,StandardCharsets.UTF_8)).getAsJsonObject();mesh.remove("uuid");
                var hash=MessageDigest.getInstance("SHA-256");
                result.put(root.relativize(path).toString(),HexFormat.of().formatHex(hash.digest(mesh.toString().getBytes(StandardCharsets.UTF_8))));
            }
        }
        return result;
    }
}
