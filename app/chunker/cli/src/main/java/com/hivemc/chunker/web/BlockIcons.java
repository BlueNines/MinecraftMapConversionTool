package com.hivemc.chunker.web;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Renders small isometric block icons using the textures from the user's own Minecraft installation.
 * <p>
 * A table of block names is not enough to choose between two substitutes: "quartz_bricks becomes quartz_block" says
 * nothing about whether the wall will still look like a wall. Rendering the block settles it at a glance, which is
 * exactly what a mapping decision needs.
 * <p>
 * The textures are read from the game files already on the machine rather than shipped with this tool. They are
 * Mojang's assets, they are tens of megabytes, and the user's own installation already holds the right ones for the
 * version they are converting from.
 * <p>
 * The block name to texture name step is a guess by necessity: Minecraft has no need to keep a name-to-texture table,
 * because the game reads blockstate and model files that this deliberately does not. A short list of conventions
 * (a name, then _side/_top/_front variants, then the material behind a shape suffix) covers almost every block, and
 * anything unresolved gets a plain grey cube rather than a broken image.
 */
public final class BlockIcons {
    /**
     * The size of the rendered icon. Large enough to read the texture, small enough for a list of them.
     */
    private static final int ICON_SIZE = 48;

    /**
     * Textures are normalised to this square before being mapped onto the cube, so animation frames and
     * higher-resolution packs both behave the same way.
     */
    private static final int TEXTURE_SIZE = 16;

    /**
     * 1.12.2 names for blocks that were renamed later, so the modern installation still has a texture for them.
     * <p>
     * This matters more than it looks: the target side of every mapping is a 1.12.2 name, and without this table
     * most of them would render as grey squares even though the block plainly still exists.
     */
    private static final Map<String, String> LEGACY_NAMES = Map.ofEntries(
            Map.entry("chiseled_quartz_block", "quartz_block_chiseled"),
            Map.entry("stripped_jungle_wood", "stripped_jungle_log"),
            Map.entry("log", "oak_log"),
            Map.entry("log2", "acacia_log"),
            Map.entry("planks", "oak_planks"),
            Map.entry("wooden_slab", "oak_slab"),
            Map.entry("trapdoor", "oak_trapdoor"),
            Map.entry("fence", "oak_fence"),
            Map.entry("fence_gate", "oak_fence_gate"),
            Map.entry("wooden_button", "oak_button"),
            Map.entry("wooden_door", "oak_door"),
            Map.entry("wooden_pressure_plate", "oak_pressure_plate"),
            Map.entry("stone_stairs", "cobblestone_stairs"),
            Map.entry("stonebrick", "stone_bricks"),
            Map.entry("stone_slab", "smooth_stone"),
            Map.entry("stone_slab2", "red_sandstone_slab"),
            Map.entry("brick_block", "bricks"),
            Map.entry("nether_brick", "nether_bricks"),
            Map.entry("red_nether_brick", "red_nether_bricks"),
            Map.entry("grass", "grass_block"),
            Map.entry("hardened_clay", "terracotta"),
            Map.entry("stained_hardened_clay", "white_terracotta"),
            Map.entry("wool", "white_wool"),
            Map.entry("carpet", "white_carpet"),
            Map.entry("concrete", "white_concrete"),
            Map.entry("concrete_powder", "white_concrete_powder"),
            Map.entry("stained_glass", "white_stained_glass"),
            Map.entry("stained_glass_pane", "white_stained_glass_pane"),
            Map.entry("web", "cobweb"),
            Map.entry("waterlily", "lily_pad"),
            Map.entry("yellow_flower", "dandelion"),
            Map.entry("red_flower", "poppy"),
            Map.entry("deadbush", "dead_bush"),
            Map.entry("tallgrass", "short_grass"),
            Map.entry("snow_layer", "snow"),
            Map.entry("snow", "snow_block"),
            Map.entry("quartz_ore", "nether_quartz_ore"),
            Map.entry("mob_spawner", "spawner"),
            Map.entry("noteblock", "note_block"),
            Map.entry("lit_pumpkin", "jack_o_lantern"),
            Map.entry("pumpkin", "carved_pumpkin"),
            Map.entry("standing_sign", "oak_sign"),
            Map.entry("wall_sign", "oak_wall_sign"),
            Map.entry("standing_banner", "white_banner"),
            Map.entry("wall_banner", "white_wall_banner"),
            Map.entry("reeds", "sugar_cane"),
            Map.entry("slime", "slime_block"),
            Map.entry("portal", "nether_portal"),
            Map.entry("water", "water_still"),
            Map.entry("lava", "lava_still"),
            Map.entry("fire", "fire_0"),
            Map.entry("leaves", "oak_leaves"),
            Map.entry("leaves2", "acacia_leaves"),
            Map.entry("cauldron", "cauldron"),
            Map.entry("end_portal_frame", "end_portal_frame"),
            Map.entry("dragon_egg", "dragon_egg"),
            Map.entry("enchanting_table", "enchanting_table"),
            Map.entry("brewing_stand", "brewing_stand"),
            Map.entry("jukebox", "jukebox_side"),
            Map.entry("tnt", "tnt_side"),
            Map.entry("hay_block", "hay_block_side"),
            Map.entry("bone_block", "bone_block_side"),
            Map.entry("quartz_block", "quartz_block_side"),
            Map.entry("sandstone", "sandstone"),
            Map.entry("red_sandstone", "red_sandstone"),
            Map.entry("purpur_block", "purpur_block"),
            Map.entry("sea_lantern", "sea_lantern"),
            Map.entry("prismarine", "prismarine"),
            Map.entry("mycelium", "mycelium_side"),
            Map.entry("podzol", "podzol_side"),
            Map.entry("farmland", "farmland"),
            Map.entry("cocoa", "cocoa_stage_2"),
            Map.entry("redstone_lamp", "redstone_lamp"),
            Map.entry("redstone_wire", "redstone_dust_line0"),
            Map.entry("unlit_redstone_torch", "redstone_torch"),
            Map.entry("skull", "skeleton_skull"),
            Map.entry("command_block", "command_block"),
            Map.entry("structure_block", "structure_block"),
            Map.entry("beacon", "beacon"),
            Map.entry("cake", "cake_side"),
            Map.entry("dispenser", "dispenser_front"),
            Map.entry("dropper", "dropper_front"),
            Map.entry("piston", "piston_side"),
            Map.entry("sticky_piston", "piston_top_sticky"),
            Map.entry("piston_head", "piston_top"),
            Map.entry("observer", "observer_front"),
            Map.entry("hopper", "hopper_outside"),
            Map.entry("chest", "oak_planks"),
            Map.entry("trapped_chest", "oak_planks"),
            Map.entry("ender_chest", "obsidian"),
            Map.entry("crafting_table", "crafting_table_front"),
            Map.entry("furnace", "furnace_front"),
            Map.entry("lit_furnace", "furnace_front_on"),
            Map.entry("bookshelf", "bookshelf"),
            Map.entry("ladder", "ladder"),
            Map.entry("vine", "vine"),
            Map.entry("torch", "torch"),
            Map.entry("iron_bars", "iron_bars"),
            Map.entry("glass_pane", "glass"),
            Map.entry("rail", "rail"),
            Map.entry("golden_rail", "powered_rail"),
            Map.entry("detector_rail", "detector_rail"),
            Map.entry("activator_rail", "activator_rail"),
            Map.entry("smooth_stone", "smooth_stone")
    );

    /**
     * Shapes whose texture belongs to the material they are made of, not to the shape.
     */
    private static final String[] SHAPE_SUFFIXES = {
            "_stairs", "_slab", "_wall", "_fence_gate", "_fence", "_trapdoor", "_door",
            "_button", "_pressure_plate", "_pane", "_bars"
    };

    private Path clientJar;
    private String archiveStamp = "";
    private long revision = System.currentTimeMillis();
    private final List<Path> searchRoots;
    private final Map<String, String> texturePaths = new HashMap<>();
    private static final String[] PREFIXES = {"assets/minecraft/textures/block/", "assets/minecraft/textures/blocks/",
            "assets/minecraft/textures/item/", "assets/minecraft/textures/items/"};
    private final Set<String> textureNames = new HashSet<>();
    private final Map<String, BufferedImage> textureCache = new ConcurrentHashMap<>();
    private final Map<String, byte[]> iconCache = new ConcurrentHashMap<>();
    private volatile byte[] placeholder;

    /**
     * Create an icon renderer, locating the game files once.
     */
    public BlockIcons() { this(defaultRoots()); }

    /** Explicit roots make discovery testable without accessing a user's real Minecraft installation. */
    BlockIcons(List<Path> roots) {
        searchRoots = List.copyOf(roots);
        refresh(null, null);
    }

    /** Rediscover after a source selection or BlueMap asset download. Misses never become permanent. */
    public synchronized void refresh(Path world, Path viewersRoot) {
        Path found = findClientJar(world, viewersRoot);
        String stamp = "";
        try {
            if (found != null) stamp = found + ":" + Files.size(found) + ":" + Files.getLastModifiedTime(found).toMillis();
        } catch (IOException e) { found = null; }
        if (stamp.equals(archiveStamp)) return;
        clientJar = found; archiveStamp = stamp;
        textureNames.clear(); texturePaths.clear(); textureCache.clear(); iconCache.clear();
        if (clientJar != null) indexTextures();
        revision++;
    }

    public synchronized long revision() { return revision; }

    public synchronized boolean hasTexture(String rawName) {
        return firstExisting(mainCandidates(normalise(rawName))) != null;
    }

    /**
     * Whether block icons can be produced at all.
     *
     * @return true if a Minecraft installation with block textures was found.
     */
    public synchronized boolean isAvailable() {
        return clientJar != null && !textureNames.isEmpty();
    }

    /**
     * Describe where the textures came from, for diagnostics.
     *
     * @return a short description.
     */
    public synchronized String describe() {
        if (clientJar == null) return "尚未找到本地客户端贴图；选择存档后会沿启动器目录查找，BlueMap 下载完成后会自动重试。";
        return clientJar + " (" + textureNames.size() + " block textures)";
    }

    /**
     * Render an icon for a block.
     *
     * @param rawName the block identifier, with or without a namespace, or a Chunker block description.
     * @return PNG bytes, never null - a plain grey cube stands in when the texture cannot be found.
     */
    public synchronized byte[] icon(String rawName) {
        String name = normalise(rawName);
        byte[] cached = iconCache.get(name);
        if (cached != null) return cached;

        byte[] png = null;
        if (isAvailable()) {
            try {
                png = render(name);
            } catch (Exception ignored) {
                // A texture that will not decode is the same as one that is not there.
            }
        }
        if (png == null) png = placeholder();

        iconCache.put(name, png);
        return png;
    }

    /** Shapes the tool knows how to draw. A cube is the fallback, not the default answer. */
    private enum Shape { CUBE, SLAB, STAIRS, FENCE, WALL, PANE, TRAPDOOR, DOOR, FLAT, RAIL, BUTTON, POST, LIQUID, CROSS }

    /**
     * Blocks drawn as two intersecting planes. Standing a flower up as a cube is the one thing a plant icon must not
     * do: the shape is most of what the user is comparing.
     */
    private static final Set<String> CROSS_BLOCKS = Set.of(
            "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip",
            "white_tulip", "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose",
            "sunflower", "lilac", "rose_bush", "peony", "short_grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "sugar_cane", "vine", "wheat", "carrots", "potatoes", "beetroots",
            "sweet_berry_bush", "torchflower", "pitcher_plant", "nether_sprouts", "warped_roots",
            "crimson_roots", "kelp", "seagrass", "bamboo", "cocoa", "red_mushroom", "brown_mushroom",
            "crimson_fungus", "warped_fungus", "glow_lichen", "big_dripleaf"
    );

    /**
     * Render the icon for a resolved block name.
     * <p>
     * The shape matters as much as the texture: a staircase drawn as a cube, or a flower drawn as a cube, tells the
     * user nothing about whether a substitution is acceptable.
     *
     * @param name the normalised block name.
     * @return PNG bytes, or null if no texture could be found.
     * @throws IOException if the icon could not be encoded.
     */
    private byte[] render(String name) throws IOException {
        List<String> mainCandidates = mainCandidates(name);
        String sideName = firstExisting(mainCandidates);
        if (sideName == null) return null;

        // The top face is usually the same texture, but grass, furnaces, logs and the like differ, and using the
        // side texture on the roof of those reads as the wrong block.
        String topName = firstExisting(topCandidates(name));
        if (topName == null) topName = sideName;

        BufferedImage side = loadTexture(sideName);
        if (side == null) return null;
        BufferedImage top = topName.equals(sideName) ? side : loadTexture(topName);
        if (top == null) top = side;

        String entry = texturePaths.get(sideName);
        boolean itemSprite = entry != null && (entry.contains("/item/") || entry.contains("/items/"));

        // The shape carries as much as the texture does. "birch_fence becomes fence" says nothing about whether the
        // fence still reads as a fence if both sides are drawn as solid cubes, and a staircase is not a cube either.
        Shape shape = shape(name);
        if (itemSprite) return encode(shape == Shape.CROSS ? cross(side) : fit(side));
        return encode(switch (shape) {
            case CROSS -> cross(side);
            case SLAB -> box(top, side, 0, 0, 0, 1, 0.5, 1);
            case STAIRS -> stairs(top, side);
            case FENCE -> fence(top, side);
            case WALL -> wall(top, side);
            case PANE -> pane(top, side);
            case TRAPDOOR -> box(top, side, 0, 0, 0, 1, 0.1875, 1);
            case DOOR -> box(top, side, 0, 0, 0.4375, 1, 1, 0.5625);
            case FLAT -> box(top, side, 0, 0, 0, 1, 0.0625, 1);
            case RAIL -> box(top, side, 0.0625, 0, 0, 0.9375, 0.0625, 1);
            case BUTTON -> box(top, side, 0.3125, 0.375, 0.375, 0.6875, 0.625, 0.625);
            case POST -> box(top, side, 0.4375, 0, 0.4375, 0.5625, 0.6875, 0.5625);
            case LIQUID -> box(top, side, 0, 0, 0, 1, 0.875, 1);
            default -> box(top, side, 0, 0, 0, 1, 1, 1);
        });
    }

    /**
     * Work out how a block should be drawn.
     * <p>
     * The name is checked first, then its modern equivalent, because the 1.12.2 names on the target side of a mapping
     * are the ones that still need interpreting: "grass" means the full cube, while "tallgrass" means the plant.
     *
     * @param name the normalised block name.
     * @return the shape to draw.
     */
    private static Shape shape(String name) {
        Shape direct = shapeOf(name);
        if (direct != Shape.CUBE) return direct;
        String resolved = LEGACY_NAMES.get(name);
        return resolved == null ? Shape.CUBE : shapeOf(resolved);
    }

    private static Shape shapeOf(String name) {
        if (CROSS_BLOCKS.contains(name) || name.endsWith("_sapling") || name.endsWith("_flower")
                || name.endsWith("_roots") || name.endsWith("_sprouts") || name.endsWith("_fungus")) {
            return Shape.CROSS;
        }
        if (name.equals("iron_bars") || name.endsWith("_pane")) return Shape.PANE;
        if (name.endsWith("_stairs")) return Shape.STAIRS;
        if (name.endsWith("_slab")) return Shape.SLAB;
        if (name.endsWith("_trapdoor")) return Shape.TRAPDOOR;
        if (name.endsWith("_door")) return Shape.DOOR;
        if (name.endsWith("_fence_gate") || name.endsWith("_fence")) return Shape.FENCE;
        if (name.endsWith("_wall")) return Shape.WALL;
        if (name.endsWith("_button")) return Shape.BUTTON;
        if (name.endsWith("_pressure_plate") || name.endsWith("_carpet") || name.equals("lily_pad")
                || name.equals("snow") || name.equals("moss_carpet") || name.equals("dirt_path")) {
            return Shape.FLAT;
        }
        if (name.equals("rail") || name.endsWith("_rail")) return Shape.RAIL;
        if (name.equals("torch") || name.equals("chain") || name.equals("lantern") || name.equals("soul_lantern")
                || name.equals("end_rod") || name.equals("lightning_rod") || name.equals("lever")
                || name.endsWith("_torch") || name.endsWith("_candle")) {
            return Shape.POST;
        }
        if (name.startsWith("water") || name.startsWith("lava")) return Shape.LIQUID;
        return Shape.CUBE;
    }

    /**
     * Draw a block from its bounding box in block space, where each axis runs from 0 to 1.
     */
    private static BufferedImage box(BufferedImage top, BufferedImage side,
                                     double x0, double y0, double z0, double x1, double y1, double z1) {
        BufferedImage icon = newIcon();
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        drawBox(g, top, side, x0, y0, z0, x1, y1, z1, 0f);
        g.dispose();
        return icon;
    }

    /**
     * A staircase: a half-height box with a second half-height box on top of one half of its footprint.
     */
    private static BufferedImage stairs(BufferedImage top, BufferedImage side) {
        BufferedImage icon = newIcon();
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        drawBox(g, top, side, 0, 0, 0, 1, 0.5, 1, 0f);
        drawBox(g, top, side, 0, 0.5, 0, 0.5, 1, 1, 0f);
        g.dispose();
        return icon;
    }

    /** Two rails per axis, plus the post, which is drawn last so it reads as being in front. */
    private static BufferedImage fence(BufferedImage top, BufferedImage side) {
        BufferedImage icon = newIcon();
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        drawBox(g, top, side, 0, 0.25, 0.4375, 1, 0.375, 0.5625, 0.06f);
        drawBox(g, top, side, 0, 0.6875, 0.4375, 1, 0.8125, 0.5625, 0.06f);
        drawBox(g, top, side, 0.4375, 0.25, 0, 0.5625, 0.375, 1, 0.06f);
        drawBox(g, top, side, 0.4375, 0.6875, 0, 0.5625, 0.8125, 1, 0.06f);
        drawBox(g, top, side, 0.375, 0, 0.375, 0.625, 1, 0.625, 0f);
        g.dispose();
        return icon;
    }

    /** A wall is a fence with a thicker post and a single wide rail. */
    private static BufferedImage wall(BufferedImage top, BufferedImage side) {
        BufferedImage icon = newIcon();
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        drawBox(g, top, side, 0, 0.5, 0.375, 1, 0.75, 0.625, 0.06f);
        drawBox(g, top, side, 0.25, 0, 0.25, 0.75, 1, 0.75, 0f);
        g.dispose();
        return icon;
    }

    /** A pane or a set of bars: two thin sheets crossing the block, both faces visible through the gaps. */
    private static BufferedImage pane(BufferedImage top, BufferedImage side) {
        BufferedImage icon = newIcon();
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        drawBox(g, top, side, 0, 0, 0.4375, 1, 1, 0.5625, 0f);
        drawBox(g, top, side, 0.4375, 0, 0, 0.5625, 1, 1, 0f);
        g.dispose();
        return icon;
    }

    /**
     * A plant: two planes crossing in the middle, the way the game draws flowers and crops.
     */
    private static BufferedImage cross(BufferedImage texture) {
        BufferedImage icon = newIcon();
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        face(g, texture, at(0, 1, 0.5), at(1, 1, 0.5), at(1, 0, 0.5), at(0, 0, 0.5), 0f);
        face(g, texture, at(0.5, 1, 1), at(0.5, 1, 0), at(0.5, 0, 0), at(0.5, 0, 1), 0.08f);
        g.dispose();
        return icon;
    }

    private static BufferedImage newIcon() {
        return new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
    }

    /** Blow a 16 by 16 texture, or an item sprite, up to the icon size without smoothing the pixels. */
    private static BufferedImage fit(BufferedImage source) {
        BufferedImage icon = newIcon();
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, ICON_SIZE, ICON_SIZE, null);
        g.dispose();
        return icon;
    }

    /**
     * Project a point from block space into icon pixels.
     * <p>
     * The usual isometric arrangement: x runs to the right and down, z to the left and down, y straight up.
     *
     * @param x the position along x, 0 to 1.
     * @param y the position along y, 0 at the bottom of the block.
     * @param z the position along z, 0 to 1.
     * @return the x and y pixel co-ordinates.
     */
    private static double[] at(double x, double y, double z) {
        double half = ICON_SIZE / 2.0;
        double quarter = ICON_SIZE / 4.0;
        return new double[]{half + (x - z) * half, (x + z) * quarter + (1 - y) * half};
    }

    /**
     * Draw a box into an existing icon.
     *
     * @param extra an extra darkening applied to every face, used to push thin parts into the background.
     */
    private static void drawBox(Graphics2D g, BufferedImage top, BufferedImage side,
                                double x0, double y0, double z0, double x1, double y1, double z1, float extra) {
        // Top face, then the two faces turned towards the viewer. The shading is what makes a flat texture read as a
        // solid, and it is the same trick the game itself uses.
        face(g, top, at(x0, y1, z0), at(x1, y1, z0), at(x1, y1, z1), at(x0, y1, z1), extra);
        face(g, side, at(x1, y1, z0), at(x1, y1, z1), at(x1, y0, z1), at(x1, y0, z0), 0.38f + extra);
        face(g, side, at(x0, y1, z1), at(x1, y1, z1), at(x1, y0, z1), at(x0, y0, z1), 0.20f + extra);
    }

    /**
     * Draw one four-sided face, mapping the texture across it. The corners run round the face in order, starting
     * at the texture's top-left.
     */
    private static void face(Graphics2D g, BufferedImage texture, double[] p0, double[] p1, double[] p2, double[] p3,
                             float shade) {
        Polygon outline = new Polygon(
                new int[]{(int) Math.round(p0[0]), (int) Math.round(p1[0]), (int) Math.round(p2[0]), (int) Math.round(p3[0])},
                new int[]{(int) Math.round(p0[1]), (int) Math.round(p1[1]), (int) Math.round(p2[1]), (int) Math.round(p3[1])}, 4);

        // Clipping keeps the textured parallelogram inside its own face: without it the corners overlap the
        // neighbouring faces by a pixel and the shape gets faint diagonal seams.
        java.awt.Shape previousClip = g.getClip();
        g.setClip(outline);
        g.setTransform(new AffineTransform(
                (p1[0] - p0[0]) / TEXTURE_SIZE, (p1[1] - p0[1]) / TEXTURE_SIZE,
                (p3[0] - p0[0]) / TEXTURE_SIZE, (p3[1] - p0[1]) / TEXTURE_SIZE,
                p0[0], p0[1]));
        g.drawImage(texture, 0, 0, null);
        g.setTransform(new AffineTransform());
        if (shade > 0) {
            g.setColor(new Color(0f, 0f, 0f, Math.min(0.85f, shade)));
            g.fillPolygon(outline);
        }
        g.setClip(previousClip);
    }

    /**
     * Build the candidate texture names for a block's side faces, best first.
     *
     * @param name the normalised block name.
     * @return the candidates to try in order.
     */
    private static List<String> mainCandidates(String name) {
        List<String> candidates = new ArrayList<>();
        String modern = LEGACY_NAMES.getOrDefault(name, name);
        addSideCandidates(candidates, modern);
        if (!modern.equals(name)) addSideCandidates(candidates, name);
        return candidates;
    }

    private static void addSideCandidates(List<String> candidates, String base) {
        candidates.add(base);
        candidates.add(base + "_side");
        candidates.add(base + "_front");
        candidates.add(base + "_still");
        candidates.add(base + "_bottom");
        candidates.add(base + "_end");
        candidates.add(base + "_outside");
        // All-bark wood uses the corresponding log side texture, not a non-existent *_wood.png.
        if (base.endsWith("_wood")) candidates.add(base.substring(0, base.length() - 5) + "_log");
        if (base.endsWith("_planks")) candidates.add("planks_" + base.substring(0, base.length() - 7));
        if (base.endsWith("_log")) candidates.add("log_" + base.substring(0, base.length() - 4));
        if (base.endsWith("_wool")) candidates.add("wool_colored_" + base.substring(0, base.length() - 5));

        // A shape made of a material: the texture belongs to the material. Both the plural and the plain form are
        // tried because Minecraft is inconsistent about it - nether_brick_stairs is made of nether_bricks, while
        // stone_brick_stairs is made of stone_bricks and warped_stairs of warped_planks.
        for (String suffix : SHAPE_SUFFIXES) {
            if (!base.endsWith(suffix) || base.length() <= suffix.length()) continue;
            String material = base.substring(0, base.length() - suffix.length());
            candidates.add(material + "s");
            candidates.add(material + "_planks");
            candidates.add(material);
            candidates.add(material + "_bricks");
            candidates.add(material + "_block");
            candidates.add("polished_" + material);
            candidates.add(material + "_side");
            break;
        }
    }

    /**
     * Build the candidate texture names for a block's top face, best first.
     *
     * @param name the normalised block name.
     * @return the candidates to try in order.
     */
    private static List<String> topCandidates(String name) {
        String modern = LEGACY_NAMES.getOrDefault(name, name);
        List<String> candidates = new ArrayList<>();
        candidates.add(modern + "_top");
        candidates.add(modern);
        candidates.add(modern + "_end");
        candidates.addAll(mainCandidates(name));
        return candidates;
    }

    /**
     * Find the first candidate that exists in the installation.
     *
     * @param candidates the names to try in order.
     * @return the first name present, or null if none are.
     */
    private String firstExisting(List<String> candidates) {
        for (String candidate : candidates) {
            if (textureNames.contains(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Read a texture, normalised to a 16 by 16 square.
     *
     * @param name the texture name without path or extension.
     * @return the texture, or null if it could not be read.
     */
    private BufferedImage loadTexture(String name) {
        BufferedImage cached = textureCache.get(name);
        if (cached != null) return cached;

        BufferedImage texture = null;
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            String path = texturePaths.get(name);
            ZipEntry entry = path == null ? null : zip.getEntry(path);
            if (entry != null) {
                try (InputStream stream = zip.getInputStream(entry)) {
                    BufferedImage raw = ImageIO.read(stream);
                    if (raw != null) texture = normalise(raw);
                }
            }
        } catch (Exception ignored) {
            // Treated as a missing texture.
        }

        if (texture != null) textureCache.put(name, texture);
        return texture;
    }

    /**
     * Scale a texture to a fixed square, taking the first frame of animated textures.
     *
     * @param source the texture as stored.
     * @return a 16 by 16 copy.
     */
    private static BufferedImage normalise(BufferedImage source) {
        // Animated textures are stored as a vertical strip; the top square is the first frame.
        int size = Math.min(source.getWidth(), source.getHeight());
        BufferedImage out = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(source, 0, 0, TEXTURE_SIZE, TEXTURE_SIZE, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    /**
     * Get the icon used when a block has no texture, so the list keeps a consistent shape instead of showing a
     * broken image.
     *
     * @return PNG bytes for a plain grey cube.
     */
    private byte[] placeholder() {
        byte[] cached = placeholder;
        if (cached != null) return cached;

        BufferedImage flat = new BufferedImage(TEXTURE_SIZE, TEXTURE_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = flat.createGraphics();
        g.setColor(new Color(0x7a, 0x7f, 0x88));
        g.fillRect(0, 0, TEXTURE_SIZE, TEXTURE_SIZE);
        g.dispose();

        byte[] png = null;
        try {
            png = encode(box(flat, flat, 0, 0, 0, 1, 1, 1));
        } catch (IOException ignored) {
            // Encoding a 48 by 48 image cannot realistically fail.
        }
        if (png == null) png = new byte[0];
        placeholder = png;
        return png;
    }

    private static byte[] encode(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    /**
     * Reduce a block reference to a plain name.
     * <p>
     * The names arriving here come from three places - mappings, reports and Chunker's own identifiers - and only
     * some of them are the bare form the texture files use.
     *
     * @param rawName the name as it was given.
     * @return the normalised name, or an empty string if there is nothing usable.
     */
    private static String normalise(String rawName) {
        if (rawName == null) return "";
        String name = rawName.trim().toLowerCase(Locale.ROOT);

        // Chunker describes blocks as ChunkerBlockIdentifier{type=WARPED_STAIRS, states=[...]}; the type is the part
        // that names the block.
        if (name.startsWith("chunkerblockidentifier")) {
            int type = name.indexOf("type=");
            if (type < 0) return "";
            int end = name.indexOf(',', type);
            if (end < 0) end = name.indexOf('}', type);
            if (end < 0) end = name.length();
            name = name.substring(type + 5, end).trim();
        }

        if (name.startsWith("minecraft:")) name = name.substring("minecraft:".length());
        int colon = name.indexOf(':');
        if (colon >= 0) name = name.substring(colon + 1);

        // States may be appended in brackets by some callers; the block name is what precedes them.
        int bracket = name.indexOf('[');
        if (bracket >= 0) name = name.substring(0, bracket);

        return name.trim();
    }

    /**
     * Read the block texture names from the located installation.
     */
    private void indexTextures() {
        try (ZipFile zip = new ZipFile(clientJar.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String path = entries.nextElement().getName();
                for (String prefix : PREFIXES) {
                    if (path.startsWith(prefix) && path.endsWith(".png")) {
                        String name = path.substring(prefix.length(), path.length() - 4);
                        // Prefer block texture over an item sprite with the same name.
                        boolean block = prefix.contains("/block/") || prefix.contains("/blocks/");
                        if (block || !texturePaths.containsKey(name)) texturePaths.put(name, path);
                        textureNames.add(name);
                    }
                }
            }
        } catch (IOException ignored) { /* status reports unavailable; a later refresh can retry */ }
    }

    private static List<Path> defaultRoots() {
        String home = System.getProperty("user.home");
        List<Path> roots = new ArrayList<>();
        roots.add(Path.of(home, "AppData/Roaming/.minecraft"));
        roots.add(Path.of(home, ".minecraft"));
        roots.add(Path.of(home, "Library/Application Support/minecraft"));
        String appData = System.getenv("APPDATA");
        if (appData != null) roots.add(Path.of(appData, ".minecraft"));
        for (String name : List.of("Desktop", "Documents", "Downloads")) {
            Path parent = Path.of(home, name);
            try (DirectoryStream<Path> children = Files.newDirectoryStream(parent)) {
                int n = 0;
                for (Path child : children) {
                    if (++n > 128) break;
                    if (Files.isDirectory(child)) { roots.add(child); roots.add(child.resolve(".minecraft")); }
                }
            } catch (IOException ignored) { }
        }
        return roots;
    }

    /** Search the selected world's ancestors first, including version-isolated third-party launcher layouts. */
    private Path findClientJar(Path world, Path viewersRoot) {
        Set<Path> roots = new LinkedHashSet<>();
        if (world != null) {
            Path parent = world.toAbsolutePath().normalize();
            for (int n = 0; parent != null && n < 12; n++, parent = parent.getParent()) {
                roots.add(parent);
                roots.add(parent.resolve(".minecraft"));
            }
        }
        roots.addAll(searchRoots);
        Set<Path> jars = new LinkedHashSet<>();
        for (Path root : roots) {
            // Version-isolated worlds: .../versions/<version>/saves/<world>, where the jar is at an ancestor.
            if (world != null && root.getParent() != null && root.getParent().getFileName() != null
                    && root.getParent().getFileName().toString().equals("versions")) addArchives(root, jars);
            Path versions = root.resolve("versions");
            try (DirectoryStream<Path> children = Files.newDirectoryStream(versions)) {
                int n = 0;
                for (Path version : children) { if (++n > 128) break; if (Files.isDirectory(version)) addArchives(version, jars); }
            } catch (IOException ignored) { }
        }
        // BlueMap downloads a client archive under its configured data directory. Never traverse rendered tiles.
        if (viewersRoot != null) {
            try (DirectoryStream<Path> workdirs = Files.newDirectoryStream(viewersRoot)) {
                int n = 0;
                for (Path work : workdirs) {
                    if (++n > 64) break;
                    Path data = work.resolve("data");
                    if (!Files.isDirectory(data)) continue;
                    try (var files = Files.find(data, 4, (p, attrs) -> attrs.isRegularFile() && archive(p))) {
                        files.limit(32).forEach(jars::add);
                    } catch (IOException ignored) { }
                }
            } catch (IOException ignored) { }
        }
        if (clientJar != null && Files.isRegularFile(clientJar)) jars.add(clientJar);
        Path best = null; int bestCount = 0;
        for (Path jar : jars) {
            int count = countBlockTextures(jar);
            if (count > bestCount) { bestCount = count; best = jar; }
        }
        return best;
    }

    private static boolean archive(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".jar") || name.endsWith(".zip");
    }

    private static void addArchives(Path folder, Set<Path> found) {
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder)) {
            int n = 0;
            for (Path file : files) { if (++n > 128) break; if (Files.isRegularFile(file) && archive(file)) found.add(file); }
        } catch (IOException ignored) { }
    }

    private static int countBlockTextures(Path jar) {
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            int count = 0;
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                String path = entries.nextElement().getName();
                if (path.endsWith(".png") && (path.startsWith(PREFIXES[0]) || path.startsWith(PREFIXES[1]))) count++;
            }
            return count;
        } catch (IOException ignored) { return 0; }
    }
}
