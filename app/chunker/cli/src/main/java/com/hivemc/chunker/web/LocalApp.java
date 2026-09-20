package com.hivemc.chunker.web;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.java.base.resolver.identifier.legacy.JavaLegacyBlockIDResolver;
import com.hivemc.chunker.downgrade.Approximations;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The local application: a small HTTP server, a conversion, and two map viewers.
 * <p>
 * Everything runs on the user's own machine and stops when they close it. The interface is served over plain HTTP
 * because {@code com.sun.net.httpserver} ships with the JDK - it keeps the whole tool to a single jar with no
 * dependency tree to resolve, which matters for something meant to be opened and used rather than deployed.
 */
public class LocalApp {
    private static final Gson GSON = new Gson();

    private final Path toolDirectory;
    private final BlueMapRunner blueMap;
    private final BlockIcons blockIcons;
    private HttpServer server;
    private java.util.concurrent.ExecutorService httpWorkers;

    private final AtomicReference<ConversionJob> job = new AtomicReference<>();
    private final AtomicReference<Path> lastResultWorld = new AtomicReference<>();
    private final AtomicReference<Path> lastSourceWorld = new AtomicReference<>();
    private final PreviewManager previews;
    private final PreparedWorlds preparedWorlds;
    private final java.util.concurrent.atomic.AtomicBoolean scanning = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Path iconWorld;

    // The unmapped blocks found by the most recent analysis, so the editor can suggest what to map.
    private final AtomicReference<String> lastScan = new AtomicReference<>();

    private int port = 8123;

    /**
     * Create the application.
     *
     * @param toolDirectory   where converted worlds and viewer work files are kept.
     * @param blueMapDirectory the folder holding the two BlueMap builds.
     * @param javaExecutable  the java binary used to launch BlueMap.
     */
    public LocalApp(Path toolDirectory, Path blueMapDirectory, Path javaExecutable) {
        this(toolDirectory, new BlueMapRunner(blueMapDirectory, javaExecutable), new BlockIcons());
    }

    /** Injectable renderer for HTTP lifecycle tests; production always uses the real BlueMap runner. */
    LocalApp(Path toolDirectory, BlueMapRunner runner, BlockIcons icons) {
        this.toolDirectory = toolDirectory.toAbsolutePath().normalize();
        this.blueMap = runner;
        this.blockIcons = icons;
        this.preparedWorlds = new PreparedWorlds(this.toolDirectory.resolve("sources"));
        this.previews = new PreviewManager(this.toolDirectory.resolve("viewers"), blueMap,
                () -> blockIcons.refresh(iconWorld, this.toolDirectory.resolve("viewers")), preparedWorlds);
    }

    /**
     * Start serving. Returns once the server is listening.
     *
     * @param port the port to listen on.
     * @throws IOException if the port could not be bound.
     */
    public void start(int port) throws IOException {
        this.port = port;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handleIndex);
        server.createContext("/api/pick-folder", this::handlePickFolder);
        server.createContext("/api/find-worlds", this::handleFindWorlds);
        server.createContext("/api/resolve-dropped", this::handleResolveDropped);
        server.createContext("/api/convert", this::handleConvert);
        server.createContext("/api/status", this::handleStatus);
        server.createContext("/api/report", this::handleReport);
        server.createContext("/api/target-blocks", this::handleTargetBlocks);
        server.createContext("/api/scan", this::handleScan);
        server.createContext("/api/approximations", this::handleApproximations);
        server.createContext("/api/block-icon", this::handleBlockIcon);
        server.createContext("/api/icon-status", this::handleIconStatus);
        server.createContext("/api/render-source", this::handleSourceRender);
        server.createContext("/api/render", this::handleRender);
        httpWorkers = Executors.newFixedThreadPool(8);
        server.setExecutor(httpWorkers);
        server.start();
        this.port = server.getAddress().getPort();
    }

    /**
     * Get the port the application is serving on.
     *
     * @return the port.
     */
    public int getPort() {
        return port;
    }

    /**
     * Stop the map viewers this application started.
     */
    public void stopViewers() {
        previews.close();
        if (server != null) server.stop(0);
        if (httpWorkers != null) httpWorkers.shutdownNow();
    }

    private void handleIndex(HttpExchange exchange) throws IOException {
        try (InputStream stream = LocalApp.class.getResourceAsStream("/web/index.html")) {
            if (stream == null) {
                respond(exchange, 500, "text/plain; charset=utf-8", "The interface is missing from the jar.");
                return;
            }
            respond(exchange, 200, "text/html; charset=utf-8", new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * Ask the operating system for a folder.
     * <p>
     * A browser cannot hand a page a real path, so the picker has to be opened by the process itself. It is opened
     * on the Swing thread because that is where the dialog belongs, and the browser waits on the reply.
     * <p>
     * The dialog is raised explicitly through a tiny always-on-top owner. An unowned Swing dialog opens behind the
     * browser often enough that the user, who has just dropped a folder and is watching the page, sees nothing
     * happen at all - which reads as the feature being broken rather than the dialog being hidden.
     */
    private void handlePickFolder(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        try {
            if (GraphicsEnvironment.isHeadless()) {
                result.addProperty("error", "no-display");
            } else {
                AtomicReference<File> chosen = new AtomicReference<>();
                // Worked out before entering the swing thread: it walks the disk, and doing that on the event
                // thread would freeze the very dialog it is about to show.
                File startDirectory = suggestWorldDirectory();
                SwingUtilities.invokeAndWait(() -> {
                    // Everything here stays on the swing thread, including showing the owner. Touching a window from
                    // any other thread is undefined behaviour and a common cause of a dialog that never appears.
                    JFrame owner = new JFrame();
                    // The owner is always on top so the dialog, which is its child, cannot open hidden behind the
                    // browser. An unowned dialog that lands underneath the window the user is watching reads as the
                    // button having done nothing at all. Filtering a directory-only chooser can also leave the
                    // listing looking empty, which is why the all-files filter stays enabled.
                    owner.setUndecorated(true);
                    owner.setSize(1, 1);
                    owner.setAlwaysOnTop(true);
                    owner.setLocationRelativeTo(null);
                    owner.setVisible(true);
                    try {
                        JFileChooser chooser = new JFileChooser();
                        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                        chooser.setDialogTitle("选择地图存档文件夹（里面应该能直接看到 level.dat）");
                        chooser.setAcceptAllFileFilterUsed(true);
                        chooser.setCurrentDirectory(startDirectory);
                        if (chooser.showOpenDialog(owner) == JFileChooser.APPROVE_OPTION) {
                            chosen.set(chooser.getSelectedFile());
                        }
                    } finally {
                        owner.dispose();
                    }
                });
                if (chosen.get() != null) {
                    File selected = chosen.get();
                    result.addProperty("path", selected.getAbsolutePath());
                    // Say straight away whether it is usable. Picking the wrong level of folder is the most common
                    // mistake, and finding out later - after a conversion has already failed - is much less helpful.
                    result.addProperty("isWorld", new File(selected, "level.dat").isFile());
                }
            }
        } catch (Exception e) {
            result.addProperty("error", describe(e));
        }
        respondJson(exchange, GSON.toJson(result));
    }

    /**
     * Pick a folder for the file dialog to open in.
     * <p>
     * Opening on the Desktop is actively unhelpful: the dialog then lists dozens of unrelated folders, and after a
     * drop it reads as the drop having gone wrong. So the worlds the tool can already find are used instead - the
     * parent of the most recently played one is a folder that really does contain worlds, and it is also where the
     * world the user just dragged in most likely lives.
     *
     * @return a folder to start the dialog in.
     */
    private static File suggestWorldDirectory() {
        for (File world : discoverWorlds()) {
            File parent = world.getParentFile();
            if (parent != null && parent.isDirectory()) return parent;
        }

        String home = System.getProperty("user.home");
        File[] candidates = {
                new File(home, "AppData/Roaming/.minecraft/saves"),
                new File(home, ".minecraft/saves"),
                new File(home)
        };
        for (File candidate : candidates) {
            if (candidate.isDirectory()) return candidate;
        }
        return new File(home);
    }

    /**
     * Look for existing worlds and offer them as a list.
     * <p>
     * Dragging a folder into a browser is a dead end: for security the page is told the folder's name and nothing
     * else, so there is no way to turn a drop into a path. Rather than making the user fight that, the tool looks in
     * the places worlds actually live - including third-party launcher layouts, where each game version keeps its own
     * saves folder - and lists what it finds.
     */
    private void handleFindWorlds(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        JsonArray worlds = new JsonArray();
        try {
            for (File candidate : discoverWorlds()) {
                JsonObject world = new JsonObject();
                world.addProperty("path", candidate.getAbsolutePath());
                world.addProperty("name", candidate.getName());
                File level = new File(candidate, "level.dat");
                world.addProperty("modified", level.lastModified());
                worlds.add(world);
            }
            result.add("worlds", worlds);
        } catch (Exception e) {
            result.add("worlds", worlds);
            result.addProperty("error", describe(e));
        }
        safeRespondJson(exchange, GSON.toJson(result));
    }

    /**
     * Search the usual locations for world folders.
     * <p>
     * The search is deliberately shallow and capped. Its job is to recognise the handful of layouts launchers
     * actually use, not to walk the disk: a deep search would take minutes on a large drive and would turn opening
     * the page into a wait.
     *
     * @return the worlds found, most recently played first.
     */
    private static List<File> discoverWorlds() {
        String home = System.getProperty("user.home");
        List<File> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        List<File> roots = new ArrayList<>();
        // Standard launcher installs.
        roots.add(new File(home, "AppData/Roaming/.minecraft"));
        roots.add(new File(home, ".minecraft"));

        // Third-party launchers keep a whole game directory somewhere obvious - next to the desktop, or beside the
        // launcher itself - with one saves folder per game version underneath. Those are covered by looking one and
        // two levels below the usual parent folders, rather than by searching the entire disk.
        List<File> parents = new ArrayList<>();
        parents.add(new File(home, "Desktop"));
        parents.add(new File(home, "Documents"));
        parents.add(new File(home, "Downloads"));
        for (File parent : parents) {
            File[] children = parent.listFiles(File::isDirectory);
            if (children == null) continue;
            for (File child : children) {
                if (found.size() > 400) break;
                roots.add(new File(child, ".minecraft"));
                roots.add(child);
                // A launcher keeps the game under ".minecraft/versions", one folder per game version, and each
                // version has its own saves. Missing the ".minecraft" level here is the difference between finding
                // these worlds and reporting that there are none.
                File versions = new File(new File(child, ".minecraft"), "versions");
                File[] versionDirs = versions.listFiles(File::isDirectory);
                if (versionDirs != null) {
                    for (File versionDir : versionDirs) {
                        roots.add(new File(versionDir, "saves"));
                        // Some launchers put saves directly under the version folder instead.
                        roots.add(versionDir);
                    }
                }
            }
        }

        for (File root : roots) {
            if (found.size() > 400) break;
            collectWorlds(new File(root, "saves"), found, seen, 0);
            collectWorlds(root, found, seen, 0);
        }

        found.sort((left, right) -> Long.compare(
                new File(right, "level.dat").lastModified(),
                new File(left, "level.dat").lastModified()
        ));
        return found;
    }

    /**
     * Add a folder and its immediate children as worlds, if they look like worlds.
     *
     * @param folder the folder to examine.
     * @param found  the list to add results to.
     * @param seen   paths already added, so the same world is not listed twice.
     * @param depth  how deep the current call is.
     */
    private static void collectWorlds(File folder, List<File> found, Set<String> seen, int depth) {
        if (folder == null || depth > 1 || found.size() > 400) return;
        if (new File(folder, "level.dat").isFile()) {
            if (seen.add(folder.getAbsolutePath())) found.add(folder);
            return;
        }
        File[] children = folder.listFiles(File::isDirectory);
        if (children == null) return;
        for (File child : children) {
            if (found.size() > 400) return;
            // Recognising level.dat is what makes a folder a world, so only one level of nesting is worth checking.
            if (new File(child, "level.dat").isFile() && seen.add(child.getAbsolutePath())) {
                found.add(child);
            }
        }
    }

    /**
     * Try to find a folder the user dropped by looking for it by name.
     * <p>
     * A dropped folder is a security boundary in the browser: the page learns the folder's name but never where it
     * lives. Rather than make the user hunt for it by hand, this looks for a world of that name anywhere the tool
     * already knows how to search - which includes third-party launcher layouts such as
     * {@code <launcher>/.minecraft/versions/<version>/saves/<world>}, the very layout a plain search of
     * {@code .minecraft/saves} would miss.
     * <p>
     * When exactly one world matches, its path is returned and the caller can select it without opening anything.
     * Several matches are reported as candidates rather than guessed at, because silently picking one of two
     * same-named worlds is worse than asking.
     */
    private void handleResolveDropped(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        JsonObject result = new JsonObject();
        String name = null;
        if (query != null) {
            for (String pair : query.split("&")) {
                int index = pair.indexOf('=');
                if (index > 0 && pair.substring(0, index).equals("name")) {
                    name = java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
                }
            }
        }
        if (name == null || name.isBlank() || name.contains("/") || name.contains("\\")) {
            respondJson(exchange, GSON.toJson(result));
            return;
        }

        List<File> matches = new ArrayList<>();
        for (File world : discoverWorlds()) {
            if (world.getName().equalsIgnoreCase(name)) matches.add(world);
        }

        if (matches.size() == 1) {
            result.addProperty("path", matches.get(0).getAbsolutePath());
        } else if (matches.size() > 1) {
            JsonArray candidates = new JsonArray();
            for (File match : matches) {
                JsonObject candidate = new JsonObject();
                candidate.addProperty("path", match.getAbsolutePath());
                candidate.addProperty("name", match.getName());
                candidates.add(candidate);
            }
            result.add("candidates", candidates);
        }
        respondJson(exchange, GSON.toJson(result));
    }

    private void handleConvert(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();
        try {
            handleConvertInner(exchange, response);
        } catch (Exception e) {
            // A handler must never let an exception escape: the connection is closed without a reply, the browser
            // reports a network failure, and the user is left with no idea what went wrong. Whatever happens, the
            // caller gets a JSON answer it can display.
            response.addProperty("ok", false);
            response.addProperty("error", "处理请求时出错：" + describe(e));
            safeRespondJson(exchange, GSON.toJson(response));
        }
    }

    private synchronized void handleConvertInner(HttpExchange exchange, JsonObject response) throws IOException {
        ConversionJob running = job.get();
        if (scanning.get() || previews.after().status().equals("rendering") || (running != null && !running.isFinished())) {
            response.addProperty("ok", false);
            response.addProperty("error", "已经有一个转换正在进行。");
            respondJson(exchange, GSON.toJson(response));
            return;
        }

        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        JsonObject request;
        try {
            request = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            response.addProperty("ok", false);
            response.addProperty("error", "请求格式无法解析。");
            respondJson(exchange, GSON.toJson(response));
            return;
        }

        String inputText = request.has("input") ? request.get("input").getAsString() : "";
        inputText = cleanPath(inputText);
        if (inputText.isEmpty()) {
            response.addProperty("ok", false);
            response.addProperty("error", "请先指定源地图文件夹。");
            respondJson(exchange, GSON.toJson(response));
            return;
        }

        Path input;
        try {
            input = Path.of(inputText).toAbsolutePath().normalize();
        } catch (Exception e) {
            // Invalid characters in a path make Path.of throw, and pasting a path from another program is a
            // perfectly normal way to end up with those.
            response.addProperty("ok", false);
            response.addProperty("error", "这个路径无法识别，请只填写文件夹路径本身：" + inputText);
            respondJson(exchange, GSON.toJson(response));
            return;
        }

        if (!Files.isDirectory(input)) {
            response.addProperty("ok", false);
            response.addProperty("error", "找不到这个文件夹：" + inputText);
            respondJson(exchange, GSON.toJson(response));
            return;
        }
        if (!Files.isRegularFile(input.resolve("level.dat"))) {
            response.addProperty("ok", false);
            response.addProperty(
                    "error",
                    "这个文件夹里没有 level.dat，所以它不是存档文件夹。请注意要选到存档文件夹本身" +
                            "（里面应该能直接看到 level.dat、region 这些），而不是它的上一级或 saves 目录。\n" +
                            "当前路径：" + inputText
            );
            respondJson(exchange, GSON.toJson(response));
            return;
        }

        input = input.toRealPath();

        // A folder window hands out a path with quotes around it, and some tools prefix it with file://. None of
        // that is part of the path, so it is stripped before use rather than being reported as "not a world".
        String outputText = request.has("output") ? cleanPath(request.get("output").getAsString()) : "";
        Path output = outputText.isEmpty()
                ? toolDirectory.resolve("converted").resolve(input.getFileName().toString() + "_1.12.2")
                : Path.of(outputText);
        output = output.toAbsolutePath().normalize();

        boolean shiftToFit = !request.has("shiftToFit") || request.get("shiftToFit").getAsBoolean();
        boolean clearContainers = !request.has("clearContainers") || request.get("clearContainers").getAsBoolean();

        try {
            // Resolve the existing ancestor BEFORE creating anything, including symlinked parents.
            output = resolveFutureDirectory(output);
            if (output.equals(input) || output.startsWith(input) || input.startsWith(output))
                throw new IOException("输出目录必须与源地图分开，不能相同或互相嵌套。");
            Files.createDirectories(output);
            output = output.toRealPath();
            if (output.startsWith(input) || input.startsWith(output))
                throw new IOException("输出目录与源地图重叠。");
        } catch (IOException e) {
            response.addProperty("ok", false);
            response.addProperty("error", "无法创建输出文件夹：" + e.getMessage());
            respondJson(exchange, GSON.toJson(response));
            return;
        }

        // Reuse the independently started source; only invalidate a result belonging to a different project.
        iconWorld = input;
        blockIcons.refresh(input, toolDirectory.resolve("viewers"));
        lastSourceWorld.set(input);
        lastResultWorld.set(output);

        // User-supplied block mappings, as the same JSON the command line accepts. The built-in approximations are
        // layered underneath them unless the user turns them off, since a build losing every wall and every stripped
        // log is a much worse surprise than seeing a substituted block in the report.
        String mappings = request.has("mappings") && !request.get("mappings").isJsonNull()
                ? request.get("mappings").getAsString()
                : null;
        boolean approximate = !request.has("approximate") || request.get("approximate").getAsBoolean();

        ConversionJob newJob = new ConversionJob(input, output, output, shiftToFit, clearContainers, mappings, approximate);
        newJob.prepareWith(preparedWorlds);
        previews.prepareConversion(input, output);
        job.set(newJob);

        // 输出完全写入后才开始一次增量渲染；保留 HTTP 服务，不让后台 watcher 边写边读。
        // Snapshots for the worker: the locals above are reassigned earlier in this method, so they are not
        // effectively final and cannot be captured by a lambda.
        Path resultWorld = output;
        Path sourceWorld = input;
        Thread worker = new Thread(() -> {
            newJob.run();
            if (newJob.isFinished() && !newJob.isFailed()) {
                try {
                    // PreviewManager 在渲染成功后才发布新版本，前端随后等待全部可见瓦片加载完成。
                    previews.result(sourceWorld, resultWorld, false);
                } catch (RuntimeException ignored) {
                    // A preview that could not be started must not affect the conversion, which has already
                    // succeeded and been written to disk. The user can still start it by hand.
                    previews.conversionFailed();
                }
            } else {
                previews.conversionFailed();
            }
        }, "conversion");
        worker.setDaemon(true);
        worker.start();

        response.addProperty("ok", true);
        response.addProperty("output", output.toAbsolutePath().toString());
        respondJson(exchange, GSON.toJson(response));
    }

    /** Resolve a not-yet-created directory through its nearest existing real parent, without writing. */
    private static Path resolveFutureDirectory(Path path) throws IOException {
        Path parent = path.toAbsolutePath().normalize();
        java.util.ArrayDeque<Path> suffix = new java.util.ArrayDeque<>();
        while (!Files.exists(parent, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            suffix.addFirst(parent.getFileName());
            parent = parent.getParent();
            if (parent == null) throw new IOException("输出目录没有可用的父目录。");
        }
        Path resolved = parent.toRealPath();
        for (Path part : suffix) resolved = resolved.resolve(part);
        return resolved;
    }

    private void handleStatus(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();
        ConversionJob current = job.get();
        if (current == null) {
            response.addProperty("status", "idle");
            response.addProperty("message", "");
            response.addProperty("progress", 0);
        } else {
            response.addProperty("status", current.getStatus());
            response.addProperty("message", current.getMessage());
            response.addProperty("progress", current.getProgress());
            // How much of the map actually changed. A first conversion rewrites everything; a second one after a
            // single mapping edit rewrites a handful of chunks, and saying so is what turns "the preview updated
            // almost immediately" into something the user can see the reason for.
            response.addProperty("changedChunks", current.getChangedChunks());
            response.addProperty("unchangedChunks", current.getUnchangedChunks());
            String survey = current.describeSurvey();
            if (!survey.isEmpty()) {
                response.addProperty("survey", survey);
            }
        }

        addPreviewStatus(response);
        respondJson(exchange, GSON.toJson(response));
    }

    private void handleReport(HttpExchange exchange) throws IOException {
        ConversionJob current = job.get();
        String report = current == null ? null : current.readReport();
        if (report == null) {
            respond(exchange, 404, "application/json; charset=utf-8", "{}");
            return;
        }
        respond(exchange, 200, "application/json; charset=utf-8", report);
    }

    /**
     * List every block the target version can actually store.
     * <p>
     * This is what the mapping editor offers as choices. Offering only blocks that exist in 1.12.2 is the whole
     * point: a mapping onto a block the target does not have would fail to write, and the user would have no way of
     * knowing that from the name alone.
     */
    private void handleTargetBlocks(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        JsonArray blocks = new JsonArray();
        try {
            // The legacy id table is exactly the set of blocks 1.12.2 understands, so walking it gives the list.
            JavaLegacyBlockIDResolver resolver = new JavaLegacyBlockIDResolver(new Version(1, 12, 2));
            for (int id = 0; id <= 255; id++) {
                resolver.to(id).ifPresent(blocks::add);
            }
            result.add("blocks", blocks);
        } catch (Exception e) {
            result.addProperty("error", describe(e));
        }
        safeRespondJson(exchange, GSON.toJson(result));
    }

    /** Render the converted side without restarting an already rendered/in-flight source. */
    private synchronized void handleRender(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();
        try {
            ConversionJob current = job.get();
            Path source = lastSourceWorld.get(), result = lastResultWorld.get();
            if (source == null || result == null || current == null || !current.isFinished() || current.isFailed())
                throw new IOException("还没有成功转换的结果可供预览。");
            if (!previews.before().world().equals(source.toString()))
                throw new IOException("当前选择的源地图已改变，请先转换当前地图。");
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean force = !body.isBlank() && JsonParser.parseString(body).getAsJsonObject().has("force")
                    && JsonParser.parseString(body).getAsJsonObject().get("force").getAsBoolean();
            boolean started = previews.result(source, result, force);
            response.addProperty("ok", true);
            response.addProperty("alreadyRunning", !started);
            addPreviewStatus(response);
        } catch (Exception e) {
            response.addProperty("ok", false); response.addProperty("error", describe(e));
        }
        respondJson(exchange, GSON.toJson(response));
    }

    /** Source-only rendering is available before a conversion and never writes to the source world. */
    private synchronized void handleSourceRender(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();
        try {
            JsonObject request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8)).getAsJsonObject();
            Path source = Path.of(cleanPath(request.get("input").getAsString())).toRealPath();
            if (!Files.isRegularFile(source.resolve("level.dat"))) throw new IOException("源目录中没有 level.dat。");
            ConversionJob running = job.get();
            if ((scanning.get() || previews.after().status().equals("rendering") || (running != null && !running.isFinished()))
                    && !previews.before().world().equals(source.toString()))
                throw new IOException("任务进行中，不能切换源地图。");
            response.addProperty("started", previews.source(source));
            iconWorld = source;
            blockIcons.refresh(source, toolDirectory.resolve("viewers"));
            response.addProperty("ok", true);
            addPreviewStatus(response);
        } catch (Exception e) {
            response.addProperty("ok", false); response.addProperty("error", describe(e));
        }
        respondJson(exchange, GSON.toJson(response));
    }

    private void addPreviewStatus(JsonObject response) {
        PreviewManager.Snapshot source = previews.before(), result = previews.after();
        response.addProperty("sourceWorld", source.world());
        response.addProperty("sourceRenderStatus", source.status());
        response.addProperty("sourceRenderMessage", source.message());
        response.addProperty("sourcePreviewGeneration", source.generation());
        response.addProperty("sourceRenderElapsedSeconds", source.elapsedSeconds());
        response.addProperty("beforePort", source.port());
        response.addProperty("sourceCamera", previews.sourceCamera());
        response.addProperty("resultWorld", result.world());
        response.addProperty("renderStatus", result.status());
        response.addProperty("renderMessage", result.message());
        response.addProperty("resultPreviewGeneration", result.generation());
        response.addProperty("renderElapsedSeconds", result.elapsedSeconds());
        response.addProperty("afterPort", result.port());
        // Counts conversions, not viewer starts: the viewer is reused when only the world's contents changed, and
        // the page needs to know that what it is showing is stale.
        response.addProperty("resultRevision", previews.resultRevision());
        response.addProperty("iconRevision", blockIcons.revision());
    }

    private void handleIconStatus(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();
        response.addProperty("available", blockIcons.isAvailable());
        response.addProperty("revision", blockIcons.revision());
        response.addProperty("message", blockIcons.describe());
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        respondJson(exchange, GSON.toJson(response));
    }

    /**
     * List the built-in substitutions for blocks the target version never had.
     * <p>
     * These are the tool's own decisions about what a wall or a stripped log should look like in 1.12.2, and they are
     * listed rather than hidden for a reason: a substitution is a judgement, not a fact, and the user is the one who
     * has to look at the result. Seeing the table is also the only way to know which blocks need a hand-written rule
     * to override it.
     */
    private void handleApproximations(HttpExchange exchange) throws IOException {
        JsonObject result = new JsonObject();
        result.add("rules", Approximations.builtInRules());
        safeRespondJson(exchange, GSON.toJson(result));
    }

    /**
     * Render a small isometric icon of a block, using the textures from the user's own Minecraft installation.
     * <p>
     * The page asks for these one at a time rather than being handed a sprite sheet: the rows depend on which world
     * was analysed and what the user typed, so there is no fixed set to pre-render. Results are cached by block name,
     * so the cost is one decode per distinct block.
     */
    private void handleBlockIcon(HttpExchange exchange) throws IOException {
        String query = exchange.getRequestURI().getQuery();
        String name = "";
        if (query != null) {
            for (String pair : query.split("&")) {
                int index = pair.indexOf('=');
                if (index > 0 && pair.substring(0, index).equals("name")) {
                    name = java.net.URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8);
                }
            }
        }

        byte[] png = blockIcons.hasTexture(name) ? blockIcons.icon(name) : new byte[0];
        if (png.length == 0) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            respond(exchange, 404, "image/png", "");
            return;
        }

        exchange.getResponseHeaders().set("Content-Type", "image/png");
        // Icons are deterministic for a given block, so the browser may keep them.
        exchange.getResponseHeaders().set("Cache-Control", "max-age=3600");
        exchange.sendResponseHeaders(200, png.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(png);
        }
    }

    /**
     * Work out what would be lost, without converting anything.
     * <p>
     * This exists because the unmapped list is only useful before a conversion: afterwards the blocks are already
     * gone. Running the conversion into a throwaway folder and reading the report it produces is the most faithful
     * way to answer "what would I lose" - it goes through the same mapping code the real run uses, rather than
     * trying to predict it separately and drifting out of step.
     */
    private void handleScan(HttpExchange exchange) throws IOException {
        JsonObject response = new JsonObject();
        synchronized (this) {
            ConversionJob running = job.get();
            if (scanning.get() || (running != null && !running.isFinished())) {
                response.addProperty("ok", false);
                response.addProperty("error", "已有分析或转换正在进行。");
                respondJson(exchange, GSON.toJson(response));
                return;
            }
            scanning.set(true);
        }
        try {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            String inputText = cleanPath(request.has("input") ? request.get("input").getAsString() : "");
            if (inputText.isEmpty()) {
                response.addProperty("ok", false);
                response.addProperty("error", "请先指定源地图文件夹。");
                respondJson(exchange, GSON.toJson(response));
                return;
            }

            Path input = Path.of(inputText).toRealPath();
            if (!Files.isDirectory(input) || !Files.isRegularFile(input.resolve("level.dat"))) {
                response.addProperty("ok", false);
                response.addProperty("error", "这个文件夹不是存档文件夹（里面没有 level.dat）。");
                respondJson(exchange, GSON.toJson(response));
                return;
            }

            // Start before the synchronous trial conversion, not after its HTTP response.
            previews.source(input);
            iconWorld = input;
            blockIcons.refresh(input, toolDirectory.resolve("viewers"));

            String mappings = request.has("mappings") && !request.get("mappings").isJsonNull()
                    ? request.get("mappings").getAsString()
                    : null;
            boolean approximate = !request.has("approximate") || request.get("approximate").getAsBoolean();

            Path scratch = toolDirectory.resolve("analysis");
            removeTree(scratch);
            Files.createDirectories(scratch);

            ConversionJob analysis = new ConversionJob(input, scratch, scratch, true, true, mappings, approximate);
            analysis.prepareWith(preparedWorlds);
            analysis.run();
            if (analysis.isFailed()) {
                response.addProperty("ok", false);
                response.addProperty("error", analysis.getMessage());
                respondJson(exchange, GSON.toJson(response));
                return;
            }

            String report = analysis.readReport();
            lastScan.set(report);
            response.addProperty("ok", true);
            response.add("report", report == null ? new JsonObject() : JsonParser.parseString(report));
            addPreviewStatus(response);
            removeTree(scratch);
        } catch (Exception e) {
            response.addProperty("ok", false);
            response.addProperty("error", describe(e));
        } finally {
            scanning.set(false);
        }
        respondJson(exchange, GSON.toJson(response));
    }

    private static void removeTree(Path root) {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A leftover file is harmless; the next analysis overwrites it.
                }
            });
        } catch (IOException ignored) {
            // Nothing useful to do here.
        }
    }

    /**
     * Tidy up a path that was pasted or dropped in, rather than treating its packaging as part of the path.
     * <p>
     * Pasting a folder path from a file window arrives wrapped in quotes, and some tools prefix it with file:// URL.
     * Neither is something the user typed deliberately, and rejecting them as "not a world" sends the user looking
     * in the wrong place entirely.
     *
     * @param raw the path as it arrived.
     * @return the path with quotes, a file:// prefix and stray whitespace removed.
     */
    private static String cleanPath(String raw) {
        if (raw == null) return "";
        String value = raw.trim();

        // Strip surrounding quotes, repeatedly, in case a path is both quoted and prefixed.
        while (value.length() >= 2 && (
                (value.startsWith("\"") && value.endsWith("\""))
                        || (value.startsWith("'") && value.endsWith("'")))) {
            value = value.substring(1, value.length() - 1).trim();
        }

        if (value.regionMatches(true, 0, "file:///", 0, 8)) {
            String rest = value.substring(8);
            // A file:// URL writes the drive as /C:/..., which is not a valid path on disk.
            if (rest.length() >= 3 && rest.charAt(0) == '/' && rest.charAt(2) == ':') {
                rest = rest.substring(1);
            } else if (rest.startsWith("/")) {
                rest = rest.substring(1);
            }
            value = rest;
        } else if (value.regionMatches(true, 0, "file://", 0, 7)) {
            value = value.substring(7);
        }

        // Forward slashes are understood by the platform everywhere, so there is no need to rewrite them.
        return value.trim();
    }

    /**
     * Describe an exception in a way that is useful without a stack trace.
     *
     * @param e the exception.
     * @return the message, or the class name if there is no message.
     */
    private static String describe(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) return e.getClass().getSimpleName();
        return message;
    }

    private static void respondJson(HttpExchange exchange, String json) throws IOException {
        respond(exchange, 200, "application/json; charset=utf-8", json);
    }

    /**
     * Reply to a request that is already being handled after a failure.
     * <p>
     * At this point the response may already be partly sent, so a second attempt can legitimately fail - and an
     * exception thrown here would escape the handler that was trying to contain the original problem.
     *
     * @param exchange the request being answered.
     * @param json     the payload to send.
     */
    private static void safeRespondJson(HttpExchange exchange, String json) {
        try {
            respondJson(exchange, json);
        } catch (Exception ignored) {
            // Nothing useful can be done; the connection is already going away.
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream stream = exchange.getResponseBody()) {
            stream.write(bytes);
        }
    }
}
