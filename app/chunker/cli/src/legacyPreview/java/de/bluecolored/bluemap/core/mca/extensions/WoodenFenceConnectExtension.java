package de.bluecolored.bluemap.core.mca.extensions;

import com.flowpowered.math.vector.Vector3i;
import de.bluecolored.bluemap.core.MinecraftVersion;
import de.bluecolored.bluemap.core.mca.MCAWorld;
import de.bluecolored.bluemap.core.util.Direction;
import de.bluecolored.bluemap.core.world.BlockState;
import java.util.HashSet;
import java.util.Set;

/** 为 BlueMap 1.5.5 补齐木栅栏与栅栏门的邻接状态；仅加载在旧版预览进程。 */
public class WoodenFenceConnectExtension extends ConnectSameOrFullBlockExtension {
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private final Set<String> fences;
    private final Set<String> gates;
    private final Set<String> affected;

    /** 保持旧扩展的构造签名，通过其现有注册入口同时处理栅栏和门。 */
    public WoodenFenceConnectExtension(MinecraftVersion version) {
        boolean legacy = version.isBefore(MinecraftVersion.THE_FLATTENING);
        fences = Set.of("minecraft:" + (legacy ? "fence" : "oak_fence"),
                "minecraft:spruce_fence", "minecraft:birch_fence", "minecraft:jungle_fence",
                "minecraft:acacia_fence", "minecraft:dark_oak_fence");
        gates = Set.of("minecraft:" + (legacy ? "fence_gate" : "oak_fence_gate"),
                "minecraft:spruce_fence_gate", "minecraft:birch_fence_gate", "minecraft:jungle_fence_gate",
                "minecraft:acacia_fence_gate", "minecraft:dark_oak_fence_gate");
        Set<String> ids = new HashSet<>(fences);
        ids.addAll(gates);
        affected = Set.copyOf(ids);
    }

    /** 注册六种木栅栏及六种木栅栏门。 */
    @Override public Set<String> getAffectedBlockIds() { return affected; }

    /** 从原始邻块计算状态，避免递归扩展相邻栅栏，也正确处理区块边界。 */
    @Override public BlockState extend(MCAWorld world, Vector3i pos, BlockState state) {
        if (gates.contains(state.getFullId())) {
            boolean alongX = facesAlongX(state);
            Vector3i side = alongX ? Direction.NORTH.toVector() : Direction.EAST.toVector();
            boolean inWall = isWall(world.getBlockState(pos.add(side))) || isWall(world.getBlockState(pos.sub(side)));
            return state.with("in_wall", Boolean.toString(inWall));
        }
        for (Direction side : SIDES) {
            BlockState neighbor = world.getBlockState(pos.add(side.toVector()));
            boolean connected = gates.contains(neighbor.getFullId())
                    ? connectsGate(neighbor, side)
                    : fences.contains(neighbor.getFullId()) || world.getBlockPropertiesMapper().get(neighbor).isCulling();
            state = state.with(side.name().toLowerCase(java.util.Locale.ROOT), Boolean.toString(connected));
        }
        return state;
    }

    /** 原版 1.12.2：门只在横杆两端连接栅栏，开合和通电不改变连接轴。 */
    static boolean connectsGate(BlockState gate, Direction side) {
        boolean neighborAlongX = side == Direction.EAST || side == Direction.WEST;
        return facesAlongX(gate) != neighborAlongX;
    }

    /** 朝向东西的门沿南北轴连接。 */
    private static boolean facesAlongX(BlockState state) {
        String facing = state.getProperties().get("facing");
        return "east".equals(facing) || "west".equals(facing);
    }

    /** 两种圆石墙在 1.12.2 中均可使相邻栅栏门降低模型高度。 */
    private static boolean isWall(BlockState state) {
        return "minecraft:cobblestone_wall".equals(state.getFullId())
                || "minecraft:mossy_cobblestone_wall".equals(state.getFullId());
    }
}
