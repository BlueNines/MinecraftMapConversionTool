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
            "_button", "_pressure_plate"
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

    /**
     * Render the icon for a resolved block name.
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
        return encode(entry != null && (entry.contains("/item/") || entry.contains("/items/")) ? side : compose(top, side));
    }

    /**
     * Draw a cube using the given textures: the top face uses one image, the two side faces the other.
     * <p>
     * The projection places the top face as a diamond and the sides as parallelograms, and shades each face
     * differently - the same trick the game itself uses to make a flat texture read as a solid block.
     *
     * @param top  the texture for the top face.
     * @param side the texture for the two side faces.
     * @return the rendered icon.
     */
    private static BufferedImage compose(BufferedImage top, BufferedImage side) {
        BufferedImage icon = new BufferedImage(ICON_SIZE, ICON_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        double s = ICON_SIZE / 32.0;
        double half = ICON_SIZE / 2.0;
        double quarter = ICON_SIZE / 4.0;

        AffineTransform original = g.getTransform();

        // Top face: texture u goes right-down, v goes left-down.
        AffineTransform topTransform = new AffineTransform(s, s * 0.5, -s, s * 0.5, half, 0);
        drawFace(g, top, topTransform, new int[]{
                (int) half, ICON_SIZE, (int) half, 0
        }, new int[]{
                0, (int) quarter, (int) half, (int) quarter
        }, 0f);
        g.setTransform(original);

        // Left face: texture u goes right-down, v goes straight down.
        AffineTransform leftTransform = new AffineTransform(s, s * 0.5, 0, s, 0, quarter);
        drawFace(g, side, leftTransform, new int[]{
                0, (int) half, (int) half, 0
        }, new int[]{
                (int) quarter, (int) half, ICON_SIZE, (int) (quarter * 3)
        }, 0.20f);
        g.setTransform(original);

        // Right face: the mirror of the left one.
        AffineTransform rightTransform = new AffineTransform(s, -s * 0.5, 0, s, half, half);
        drawFace(g, side, rightTransform, new int[]{
                (int) half, ICON_SIZE, ICON_SIZE, (int) half
        }, new int[]{
                (int) half, (int) quarter, (int) (quarter * 3), ICON_SIZE
        }, 0.38f);
        g.setTransform(original);

        g.dispose();
        return icon;
    }

    /**
     * Draw one face of the cube.
     *
     * @param g         the graphics context.
     * @param texture   the texture to map onto the face.
     * @param transform the texture-to-icon transform for this face.
     * @param xs        the face's x co-ordinates.
     * @param ys        the face's y co-ordinates.
     * @param shade     how much to darken this face, 0 being flat lit.
     */
    private static void drawFace(Graphics2D g, BufferedImage texture, AffineTransform transform,
                                 int[] xs, int[] ys, float shade) {
        Polygon face = new Polygon(xs, ys, xs.length);

        // Clipping keeps the textured parallelogram inside its own face: without it the corners overlap the
        // neighbouring faces by a pixel and the cube gets faint diagonal seams.
        java.awt.Shape previousClip = g.getClip();
        g.setClip(face);
        g.setTransform(transform);
        g.drawImage(texture, 0, 0, null);
        g.setTransform(new AffineTransform());
        if (shade > 0) {
            g.setColor(new Color(0f, 0f, 0f, shade));
            g.fillPolygon(face);
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
            png = encode(compose(flat, flat));
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
