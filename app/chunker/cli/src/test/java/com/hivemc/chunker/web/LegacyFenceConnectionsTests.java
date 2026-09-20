package com.hivemc.chunker.web;

import com.flowpowered.math.vector.Vector3i;
import com.hivemc.chunker.nbt.TagType;
import com.hivemc.chunker.nbt.tags.Tag;
import com.hivemc.chunker.nbt.tags.collection.CompoundTag;
import com.hivemc.chunker.nbt.tags.collection.ListTag;
import de.bluecolored.bluemap.core.MinecraftVersion;
import de.bluecolored.bluemap.core.config.BlockIdConfig;
import de.bluecolored.bluemap.core.mca.MCAWorld;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.world.Biome;
import de.bluecolored.bluemap.core.world.BlockProperties;
import de.bluecolored.shadow.configurate.gson.GsonConfigurationLoader;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 直接读写最小 1.12.2 Anvil 世界，验证 BlueMap 的邻接扩展与跨区块读取。 */
class LegacyFenceConnectionsTests {
    @TempDir Path root;
    private record Expected(Vector3i pos, String property, boolean connected) {}

    /** 六种门的四朝向、开合、通电状态均按门平面连接，跨木种和跨区块也相同。 */
    @Test void fencesConnectToGateEndsAcrossChunkBorders() throws Exception {
        Map<Vector3i, int[]> blocks = new HashMap<>();
        List<Expected> expected = new ArrayList<>();
        int[] gates = {107,183,184,185,186,187}, fences = {85,188,189,190,191,192};
        Direction[] sides = {Direction.NORTH,Direction.EAST,Direction.SOUTH,Direction.WEST};
        String[] back = {"south","west","north","east"};
        int sample=0;
        for (int wood=0;wood<6;wood++) for (int meta=0;meta<16;meta++) {
            Vector3i gate = new Vector3i(15+4*(sample%12),4,15+4*(sample/12)); sample++;
            blocks.put(gate,new int[]{gates[wood],meta});
            expected.add(new Expected(gate,"in_wall",false));
            for(int side=0;side<4;side++) {
                Vector3i fence = gate.add(sides[side].toVector());
                blocks.put(fence,new int[]{fences[(wood+1)%6],0});
                // 元数据 0/2 朝南北，横杆沿东西；1/3 则相反。
                expected.add(new Expected(fence,back[side],(meta&1)!=(side&1)));
                expected.add(new Expected(fence,sides[side].name().toLowerCase(Locale.ROOT),false));
            }
        }
        MCAWorld world = world(blocks);
        for (Expected item:expected) assertEquals(Boolean.toString(item.connected()),
                world.getBlock(item.pos()).getBlockState().getProperties().get(item.property()),item.toString());
    }

    /** 墙在门两端才降低门，普通方块、空气、原有木栅栏连接保持正确。 */
    @Test void reconstructsWallStateAndKeepsExistingFenceConnections() throws Exception {
        Map<Vector3i,int[]> blocks = new HashMap<>();
        for(int meta=0;meta<4;meta++) for(int side=0;side<4;side++) {
            Vector3i gate=new Vector3i(3+meta*5,4,3+side*5);
            blocks.put(gate,new int[]{187,meta});
            Vector3i offset=new Vector3i[]{new Vector3i(0,0,-1),new Vector3i(1,0,0),new Vector3i(0,0,1),new Vector3i(-1,0,0)}[side];
            blocks.put(gate.add(offset),new int[]{139,side%2});
        }
        Vector3i fence=new Vector3i(30,4,30);
        blocks.put(fence,new int[]{192,0}); blocks.put(fence.add(1,0,0),new int[]{85,0});
        blocks.put(fence.add(0,0,1),new int[]{1,0}); blocks.put(fence.add(-1,0,0),new int[]{113,0});
        MCAWorld world=world(blocks);
        for(int meta=0;meta<4;meta++) for(int side=0;side<4;side++)
            assertEquals(Boolean.toString((meta&1)!=(side&1)),world.getBlock(new Vector3i(3+meta*5,4,3+side*5))
                    .getBlockState().getProperties().get("in_wall"));
        var state=world.getBlock(fence).getBlockState();
        assertEquals("true",state.getProperties().get("east"));
        assertEquals("true",state.getProperties().get("south"));
        assertEquals("false",state.getProperties().get("west"));
        assertEquals("false",state.getProperties().get("north"));
    }

    /** 写小型原版 ID/Data 区块，使用 BlueMap 自带 ID 表及真实世界读取器加载。 */
    private MCAWorld world(Map<Vector3i,int[]> blocks) throws Exception {
        CompoundTag level=new CompoundTag(), data=new CompoundTag();
        data.put("LevelName","connections");level.put("Data",data);
        Tag.writeGZipJavaNBT(root.resolve("level.dat").toFile(),level);
        Map<Integer,byte[][]> chunks=new TreeMap<>();
        for(var item:blocks.entrySet()) {
            var p=item.getKey(); int slot=(p.getX()>>4)+(p.getZ()>>4)*32;
            byte[][] arrays=chunks.computeIfAbsent(slot,k->new byte[][]{new byte[4096],new byte[2048]});
            int index=p.getY()*256+(p.getZ()&15)*16+(p.getX()&15);
            arrays[0][index]=(byte)item.getValue()[0];
            arrays[1][index>>1]|=(byte)(item.getValue()[1]<<((index&1)*4));
        }
        Files.createDirectories(root.resolve("region"));
        try(var file=new RandomAccessFile(root.resolve("region/r.0.0.mca").toFile(),"rw")) {
            file.setLength(8192);int sector=2;
            for(var entry:chunks.entrySet()) {
                CompoundTag chunk=new CompoundTag(), body=new CompoundTag(), section=new CompoundTag();
                chunk.put("DataVersion",1343);chunk.put("Level",body);
                body.put("xPos",entry.getKey()%32);body.put("zPos",entry.getKey()/32);
                body.put("TerrainPopulated",(byte)1);body.put("LightPopulated",(byte)1);body.put("Biomes",new byte[256]);
                section.put("Y",(byte)0);section.put("Blocks",entry.getValue()[0]);section.put("Data",entry.getValue()[1]);
                section.put("SkyLight",new byte[2048]);section.put("BlockLight",new byte[2048]);
                var sections=new ListTag<CompoundTag,Map<String,Tag<?>>>(TagType.COMPOUND);sections.add(section);body.put("Sections",sections);
                byte[] compressed=Tag.writeZLibJavaNBT(chunk);int sectors=(compressed.length+5+4095)/4096;
                file.seek(entry.getKey()*4L);file.writeInt((sector<<8)|sectors);
                file.seek(sector*4096L);file.writeInt(compressed.length+1);file.writeByte(2);file.write(compressed);
                sector+=sectors;file.setLength(sector*4096L);
            }
        }
        var mapping=new BlockIdConfig(GsonConfigurationLoader.builder().url(MCAWorld.class.getResource(
                "/de/bluecolored/bluemap/mc1_12/blockIds.json")).build().load());
        return MCAWorld.load(root,UUID.randomUUID(),MinecraftVersion.of("1.12.2"),mapping,
                state->state.getFullId().equals("minecraft:stone")?BlockProperties.SOLID:BlockProperties.TRANSPARENT,
                id->Biome.DEFAULT);
    }
}
