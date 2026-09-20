import de.bluecolored.bluemap.core.MinecraftVersion;
import de.bluecolored.bluemap.core.resourcepack.*;
import de.bluecolored.bluemap.core.world.BlockState;
import java.io.File;
import java.lang.reflect.Field;
import java.util.*;

/** 使用随应用提供的 BlueMap 读取真实原版模型，回归朝向、开合和贴墙模型选择。 */
public final class LegacyPreviewStates {
    /** 参数：原版客户端 jar、BlueMap 资源扩展 zip、本项目兼容包 zip。 */
    public static void main(String[] args) throws Exception {
        var vanilla = new ResourcePack(MinecraftVersion.of("1.12.2"));
        vanilla.load(List.of(new File(args[0]), new File(args[1])));
        var fixed = new ResourcePack(MinecraftVersion.of("1.12.2"));
        fixed.load(List.of(new File(args[0]), new File(args[1]), new File(args[2])));
        int gates = 0, traps = 0, reproduced = 0;
        for (String wood : List.of("oak", "spruce", "birch", "jungle", "acacia", "dark_oak")) {
            String id = "minecraft:" + (wood.equals("oak") ? "fence_gate" : wood + "_fence_gate");
            for (String facing : List.of("north", "east", "south", "west")) {
                for (String open : List.of("true", "false")) for (String powered : List.of("true", "false")) {
                    var state = new BlockState(id, Map.of("facing", facing, "open", open, "powered", powered));
                    if (!matches(vanilla.getBlockStateResource(state), state)) reproduced++;
                    verify(fixed, state, vanilla, state.with("in_wall", "false"));
                    verify(fixed, state.with("in_wall", "true"), vanilla, state.with("in_wall", "true"));
                    gates++;
                }
            }
        }
        for (String id : List.of("minecraft:trapdoor", "minecraft:iron_trapdoor")) {
            for (String facing : List.of("north", "east", "south", "west")) {
                for (String open : List.of("true", "false")) for (String half : List.of("top", "bottom")) {
                    var state = new BlockState(id, Map.of("facing", facing, "open", open, "half", half));
                    verify(fixed, state, vanilla, state); traps++;
                }
            }
        }
        if (reproduced != 96) throw new AssertionError("Expected 96 original missing-property failures: " + reproduced);
        System.out.println("PASS gates=" + gates + " wallVariants=" + gates + " trapdoors=" + traps);
        System.exit(0);
    }

    /** 必须命中真实模型条件，并与补齐属性后的原版模型几何和旋转一致。 */
    private static void verify(ResourcePack actual, BlockState state, ResourcePack expected, BlockState reference) throws Exception {
        var resource = actual.getBlockStateResource(state);
        if (!matches(resource, state)) throw new AssertionError("Unexpected fallback: " + state);
        if (!signature(resource, state).equals(signature(expected.getBlockStateResource(reference), reference)))
            throw new AssertionError("Geometry or rotation differs: " + state);
    }

    /** 对比模型元素与模型旋转，不依赖不同资源包实例间的对象地址。 */
    private static String signature(BlockStateResource resource, BlockState state) {
        StringBuilder value = new StringBuilder();
        for (var model : resource.getModels(state)) {
            value.append(model.getRotation()).append(model.isUVLock());
            for (var element : model.getModel().getElements()) value.append(element.getFrom()).append(element.getTo());
        }
        return value.toString();
    }

    /** BlueMap 无匹配时静默返回首个模型，测试须额外检查原始匹配条件。 */
    private static boolean matches(BlockStateResource resource, BlockState state) throws Exception {
        for (String name : List.of("variants", "multipart")) {
            Field field = BlockStateResource.class.getDeclaredField(name); field.setAccessible(true);
            for (Object variant : (Collection<?>) field.get(resource)) {
                Field condition = variant.getClass().getDeclaredField("condition"); condition.setAccessible(true);
                if (((PropertyCondition)condition.get(variant)).matches(state)) return true;
            }
        }
        return false;
    }
}
