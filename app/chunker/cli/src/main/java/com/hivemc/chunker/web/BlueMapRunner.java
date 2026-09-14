package com.hivemc.chunker.web;

import com.hivemc.chunker.conversion.encoding.java.JavaDataVersion;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Drives the two BlueMap installations used for the side-by-side comparison.
 * <p>
 * Two versions are needed because no single BlueMap release can render both sides. BlueMap tracks the game's data
 * formats, and the release that understands modern worlds dropped support for 1.12.2 entirely, while anything old
 * enough to read 1.12.2 cannot read a 1.21 world. So the source world is rendered by the modern build and the
 * converted world by 1.5.5 - the last release that still understood 1.12.2.
 */
public class BlueMapRunner {
    public static final int SOURCE_PORT = 8101;
    public static final int RESULT_PORT = 8102;

    /**
     * The version the modern build was known to work with. Used when the world's own version cannot be read, and as
     * the retry when BlueMap cannot fetch resources for the newer one.
     */
    private static final String VERSION_FALLBACK = "1.21";

    /** Records which Minecraft version a work folder was rendered for, so its viewer can be told the same one. */
    private static final String VERSION_FILE = "mc-version.txt";

    private final Path installationDirectory;
    private final Path javaExecutable;

    /**
     * Create a new BlueMap runner.
     *
     * @param installationDirectory the folder holding both BlueMap jars and where their work files are kept.
     * @param javaExecutable        the java binary to launch them with. Both builds run on Java 21, which keeps the
     *                              packaged app to a single runtime.
     */
    public BlueMapRunner(Path installationDirectory, Path javaExecutable) {
        this.installationDirectory = installationDirectory;
        this.javaExecutable = javaExecutable;
    }

    /**
     * Render a world into a BlueMap web root.
     *
     * @param modern      true to use the modern build (for the source world), false for the 1.12.2-capable build.
     * @param worldFolder the world to render.
     * @param workFolder  a scratch folder for this render's config, data and output.
     * @param port        the port its web server should use once started.
     * @return the web root the render produced, which the web server should host.
     * @throws IOException          if the config or web root could not be prepared.
     * @throws InterruptedException if the render was interrupted.
     */
    public Path render(boolean modern, Path worldFolder, Path workFolder, int port)
            throws IOException, InterruptedException {
        String version = minecraftVersion(modern, worldFolder);
        try {
            return renderWith(modern, version, worldFolder, workFolder, port);
        } catch (IOException failure) {
            // The version read from the world may be newer than this BlueMap build knows about, in which case it
            // cannot fetch matching resources. Retrying with the version this build was known to work with is
            // better than refusing to show the user anything.
            if (!modern || version.equals(VERSION_FALLBACK)) throw failure;
            return renderWith(modern, VERSION_FALLBACK, worldFolder, workFolder, port);
        }
    }

    private Path renderWith(boolean modern, String version, Path worldFolder, Path workFolder, int port)
            throws IOException, InterruptedException {
        Path webRoot = workFolder.resolve("web");
        Files.createDirectories(workFolder);
        Files.createDirectories(webRoot);

        writeConfig(modern, workFolder, worldFolder, webRoot, port);
        // Remember which version this work folder was rendered for: the viewer starts later and has to be told the
        // same one, or it loads different resources than the render used.
        Files.writeString(workFolder.resolve(VERSION_FILE), version, StandardCharsets.UTF_8);

        String jarName = modern ? "BlueMap-5.16-cli.jar" : "BlueMap-1.5.5-cli.jar";
        File jar = installationDirectory.resolve(jarName).toFile();
        if (!jar.isFile()) {
            throw new IOException("BlueMap build is missing: " + jar.getAbsolutePath());
        }

        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable.toString(),
                "-Xmx2G",
                "-jar",
                jar.getAbsolutePath(),
                "-c",
                workFolder.toString(),
                "-v",
                version,
                "-r"
        );
        builder.directory(workFolder.toFile());
        builder.redirectErrorStream(true);
        Path log = workFolder.resolve("render.log");
        builder.redirectOutput(log.toFile());
        Process process = builder.start();
        try {
            int exit = process.waitFor();
            if (exit != 0) throw new IOException("BlueMap rendering failed (exit " + exit + "):\n" + tail(log));
        } finally {
            // waitFor is interruptible. Changing projects must also stop the renderer, not just its Java thread.
            if (process.isAlive()) stopProcess(process);
        }
        return webRoot;
    }

    /**
     * Start a viewer that keeps itself up to date.
     * <p>
     * This is what makes the preview follow a mapping change. The watcher notices the converted world being
     * rewritten and re-renders the chunks that were touched, without being told to and without re-rendering the rest.
     * What counts as "touched" is decided by each chunk's own timestamp, which the converter now dates when it
     * writes one - see the incremental writer. Chunks the conversion reproduced byte-for-byte are left alone, keep
     * their old timestamp, and are skipped.
     * <p>
     * The render flag is passed as well: the watch only starts after a render, and the initial pass costs a few
     * seconds when there is nothing new.
     *
     * @param modern     true to use the modern build (for the source world), false for the 1.12.2-capable build.
     * @param workFolder the same work folder used for the render, so it watches and serves what was rendered.
     * @return the running process, or null if the configuration is missing.
     * @throws IOException if the process could not be started.
     */
    public Process startWatcher(boolean modern, Path workFolder) throws IOException {
        String jarName = modern ? "BlueMap-5.16-cli.jar" : "BlueMap-1.5.5-cli.jar";
        File jar = installationDirectory.resolve(jarName).toFile();
        if (!jar.isFile() || !Files.isDirectory(workFolder)) {
            return null;
        }

        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable.toString(),
                "-Xmx1G",
                "-jar",
                jar.getAbsolutePath(),
                "-c",
                workFolder.toString(),
                "-v",
                versionOf(workFolder),
                "-r",
                "-u",
                "-w"
        );
        builder.directory(workFolder.toFile());
        builder.redirectErrorStream(true);
        // The watcher logs every request and every file it notices; nobody is reading it.
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    /**
     * Start a BlueMap web server which keeps serving until it is stopped.
     * <p>
     * The render itself is a one-shot job, but the viewer has to stay up for as long as the user is looking at it,
     * so this returns the running process and leaves the lifetime to the caller.
     *
     * @param modern     true to use the modern build (for the source world), false for the 1.12.2-capable build.
     * @param workFolder the same work folder used for the render, so it serves what was rendered.
     * @return the running process, or null if the configuration is missing.
     * @throws IOException if the process could not be started.
     */
    public Process startWebServer(boolean modern, Path workFolder) throws IOException {
        String jarName = modern ? "BlueMap-5.16-cli.jar" : "BlueMap-1.5.5-cli.jar";
        File jar = installationDirectory.resolve(jarName).toFile();
        if (!jar.isFile() || !Files.isDirectory(workFolder)) {
            return null;
        }

        ProcessBuilder builder = new ProcessBuilder(
                javaExecutable.toString(),
                "-Xmx1G",
                "-jar",
                jar.getAbsolutePath(),
                "-c",
                workFolder.toString(),
                "-v",
                versionOf(workFolder),
                "-w"
        );
        builder.directory(workFolder.toFile());
        builder.redirectErrorStream(true);
        // Discard the server's chatter: it logs every request, and nobody is reading it.
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        return builder.start();
    }

    /**
     * Write the configuration BlueMap needs for one render.
     */
    private void writeConfig(boolean modern, Path workFolder, Path worldFolder, Path webRoot, int port) throws IOException {
        String world = toSlashes(worldFolder.toAbsolutePath());
        String web = toSlashes(webRoot.toAbsolutePath());
        String data = toSlashes(workFolder.resolve("data").toAbsolutePath());

        // Accepting the download is what lets BlueMap fetch the matching Minecraft client jar from Mojang for its
        // textures. Without it the render produces nothing at all.
        write(workFolder.resolve("core.conf"), """
                accept-download: true
                metrics: false
                render-thread-count: 2
                data: "%s"
                """.formatted(data));

        write(workFolder.resolve("webserver.conf"), """
                webroot: "%s"
                port: %d
                """.formatted(web, port));

        if (modern) {
            // The modern build takes one config file per map.
            Path maps = workFolder.resolve("maps");
            Files.createDirectories(maps);
            write(maps.resolve("source.conf"), """
                    world: "%s"
                    dimension: "minecraft:overworld"
                    name: "Before"
                    sorting: 0
                    """.formatted(world));
        } else {
            // The 1.12.2-era build takes a single render.conf listing every map.
            write(workFolder.resolve("render.conf"), """
                    webroot: "%s"
                    maps: [
                      {
                        id: "result"
                        name: "After"
                        world: "%s"
                        skyColor: "#7dabff"
                        ambientLight: 0
                        renderCaves: false
                        renderEdges: true
                        useCompression: false
                      }
                    ]
                    """.formatted(web, world));
        }
    }

    /**
     * The Minecraft version to tell BlueMap to use for a given side.
     * <p>
     * This has to be explicit. Left to itself, BlueMap assumes the newest version it knows about, which for the
     * 1.12.2-capable build means 1.17. It would then load 1.17 block models and textures and try to render a
     * 1.12.2 world with them, which produces an empty map rather than an error - the viewer opens, shows a blank
     * scene, and gives no hint as to why.
     * <p>
     * For the source side the version is read from the world itself rather than assumed. A world from 1.21.11 holds
     * blocks that did not exist in 1.21, and rendering it with 1.21 resources leaves exactly those blocks blank.
     * BlueMap fetches the matching client jar by version, so naming the world's real version gets its real textures.
     *
     * @param modern      whether this is the modern build rendering the source world.
     * @param worldFolder the world being rendered, or null when only the version is needed for a viewer.
     * @return the version string to pass on the command line.
     */
    private static String minecraftVersion(boolean modern, Path worldFolder) {
        if (!modern) return "1.12.2";
        if (worldFolder != null) {
            try {
                var detected = JavaDataVersion.detect(worldFolder.toFile());
                if (detected.isPresent()) {
                    String version = detected.get().getVersion().toString();
                    if (!version.isEmpty()) return version;
                }
            } catch (RuntimeException ignored) {
                // An unreadable level.dat is not fatal: fall back to the version known to work.
            }
        }
        return VERSION_FALLBACK;
    }

    /**
     * The version a work folder was rendered for, so a viewer serving it uses the same resources.
     *
     * @param workFolder the folder the render wrote to.
     * @return the recorded version, or the fallback when there is no record.
     */
    private static String versionOf(Path workFolder) {
        try {
            Path file = workFolder.resolve(VERSION_FILE);
            if (Files.isRegularFile(file)) {
                String version = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!version.isEmpty()) return version;
            }
        } catch (IOException ignored) {
            // Fall through to the fallback.
        }
        return VERSION_FALLBACK;
    }

    /** Wait for this specific process; a dead child or a timeout must not be published as a ready iframe. */
    public void awaitServer(Process process, int port) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (!process.isAlive()) throw new IOException("BlueMap web server exited before becoming ready.");
            try (java.net.Socket socket = new java.net.Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 200);
                if (!process.isAlive()) throw new IOException("BlueMap web server exited.");
                return;
            } catch (IOException e) { Thread.sleep(200); }
        }
        throw new IOException("Timed out waiting for BlueMap on port " + port);
    }

    /** Terminate only a child process owned by this app, including on cancellation/shutdown. */
    static void stopProcess(Process process) {
        process.destroy();
        try {
            if (!process.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS)) process.destroyForcibly();
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static String tail(Path log) throws IOException {
        try (var channel = Files.newByteChannel(log)) {
            channel.position(Math.max(0, channel.size() - 65536));
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(65536);
            while (buffer.hasRemaining() && channel.read(buffer) > 0) { /* bounded log tail */ }
            return new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8);
        }
    }

    private static void write(Path path, String content) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, content, StandardCharsets.UTF_8);
    }

    private static String toSlashes(Path path) {
        return path.toString().replace('\\', '/');
    }
}
