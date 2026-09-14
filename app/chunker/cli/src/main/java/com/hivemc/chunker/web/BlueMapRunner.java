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
        // The webapp and its language files are written by the render itself, so anything that edits
        // them has to run afterwards.
        completeWebRoot(modern, webRoot);
        return webRoot;
    }

    /**
     * Finish the web root once the render has produced it.
     * <p>
     * Two things can only be done here, because the files they touch do not exist until BlueMap has
     * written them: the handful of untranslated strings, and pointing the 1.12.2-era viewer - which
     * has no other way to load a script - at the files installed before the render.
     *
     * @param modern  which build produced this web root.
     * @param webRoot the web root the render produced.
     */
    private void completeWebRoot(boolean modern, Path webRoot) throws IOException {
        completeChineseTranslations(webRoot);
        if (!modern) loadViewerFilesFromIndex(webRoot);
    }

    /**
     * Make the 1.12.2-era viewer load the files this tool installs.
     * <p>
     * The modern build can be told about extra scripts and styles through its config. The older build
     * cannot - its config has no such setting - so the only way to reach it is the page it serves. Its
     * index.html is a plain file in the web root and BlueMap only writes it when it is missing, so
     * adding the references there is stable, and an upgrade replaces the surrounding BlueMap files
     * while leaving these two lines untouched.
     *
     * @param webRoot the web root the render produced.
     */
    private void loadViewerFilesFromIndex(Path webRoot) throws IOException {
        Path index = webRoot.resolve("index.html");
        if (!Files.isRegularFile(index)) return;

        String html = Files.readString(index, StandardCharsets.UTF_8);
        // Already done, or a page whose shape is not recognised: leave it rather than guess.
        if (html.contains(VIEWER_MARKER) || !html.contains("</body>")) return;

        String links = "  <link rel=\"stylesheet\" href=\"js/viewer.css\">\n"
                + "    <script src=\"js/viewer.js\"></script>\n"
                + "    <!-- " + VIEWER_MARKER + " -->\n    ";
        Files.writeString(index, html.replace("</body>", links + "</body>"), StandardCharsets.UTF_8);
    }

    /** Marks the page as already carrying the installed files, so a second pass changes nothing. */
    private static final String VIEWER_MARKER = "loaded by the map conversion tool";

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
            installViewerFiles(workFolder, webRoot);
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
            // This build has no setting for extra files; loadViewerFilesFromIndex reaches it instead.
            installViewerFiles(null, webRoot);
        }
    }

    /**
     * Install the viewer files this tool ships, and tell BlueMap to load them.
     * <p>
     * Everything here lands in the web root instead of inside the BlueMap jar, so a BlueMap upgrade
     * replaces the jar freely and cannot conflict with any of it. Three things are installed:
     * <ol>
     *   <li>a camera-placement fix, without which free-flight mode puts the camera underground and
     *       the map goes black (see the script for the full account);
     *   <li>an interface theme, so the viewer's controls match the rest of this tool;
     *   <li>tile-level live updates, so a re-conversion refreshes only the tiles that changed;
     *   <li>the few translation strings BlueMap ships untranslated in every language.
     * </ol>
     *
     * @param workFolder the config folder BlueMap was pointed at.
     * @param webRoot    the web root the webapp is served from.
     */
    private void installViewerFiles(Path workFolder, Path webRoot) throws IOException {
        write(webRoot.resolve("js/viewer.js"), VIEWER_SCRIPT);
        write(webRoot.resolve("js/viewer.css"), VIEWER_CSS);
        if (workFolder == null) return;
        // Only the keys needed here are written: BlueMap keeps its defaults for everything absent, so
        // this remains correct as the webapp gains settings.
        write(workFolder.resolve("webapp.conf"), """
                webroot: "%s"
                scripts: [
                  "js/viewer.js"
                ]
                styles: [
                  "js/viewer.css"
                ]
                """.formatted(toSlashes(webRoot.toAbsolutePath())));
    }

    /**
     * Fill in the strings BlueMap leaves untranslated, in the copy of the webapp this render produced.
     * <p>
     * BlueMap does translate its interface - the Chinese it ships is complete apart from two entries
     * that are missing from almost every language, not just Chinese. Rather than ship a whole
     * translation, only those two are added, and only when the file is there to add them to. An
     * unrecognised shape is left alone: this must never be the reason a preview fails to appear.
     *
     * @param webRoot the web root holding the generated language files.
     */
    private void completeChineseTranslations(Path webRoot) throws IOException {
        Path language = webRoot.resolve("lang/zh-CN.conf");
        if (!Files.isRegularFile(language)) return;

        String text = Files.readString(language, StandardCharsets.UTF_8);
        String patched = text;
        if (!patched.contains("chunkBorders")) {
            // Sits directly above "debug", which every shipped file has.
            patched = patched.replace("  debug: {",
                    "  chunkBorders: {\n"
                            + "    button: \"显示区块边界\"\n"
                            + "  }\n"
                            + "  debug: {");
        }
        if (!patched.contains("clipboard")) {
            // Last entry of blockTooltip, so it goes just before that block's closing brace - which is
            // the first line starting with "  }" after the block opens.
            int tooltip = patched.indexOf("blockTooltip");
            int insert = tooltip < 0 ? -1 : patched.indexOf("\n  }", tooltip);
            if (insert >= 0) {
                patched = patched.substring(0, insert)
                        + "\n    clipboard: \"点击复制\""
                        + patched.substring(insert);
            }
        }
        if (!patched.equals(text)) {
            Files.writeString(language, patched, StandardCharsets.UTF_8);
        }
    }

    /**
     * Everything this tool adds to the viewer's web root: the camera fix, the interface theme, the
     * live-update behaviour and the untranslated strings' replacements are next to it in
     * {@code lang/}. Written by the conversion tool, and safe to delete.
     */
    private static final String VIEWER_SCRIPT = """
            /*
             * What this script does, and why it is a script rather than a change to BlueMap: everything
             * here patches the viewer from the outside, so BlueMap itself stays untouched and a BlueMap
             * upgrade cannot conflict with any of it.
             *
             * 1. Free-flight camera placement.
             *    BlueMap places the camera with:  y = map.terrainHeightAt(x, z) + 3 || currentY
             *
             *    terrainHeightAt() finds the ground by raycasting down against the hires tiles, which
             *    only exist within the hires view distance of the camera (100 blocks by default). The
             *    point free-flight aims at is the one you were looking at, often a thousand blocks
             *    away, so the raycast misses and terrainHeightAt() returns 0 for "no terrain found".
             *    Because 0 + 3 = 3 is truthy, the "|| currentY" fallback never runs and the camera is
             *    put at y = 3 - underground, in first person, with nothing but the block it sits inside
             *    filling the screen. That is the black map.
             *
             *    Passing the target height explicitly makes BlueMap skip its own calculation. When the
             *    ground cannot be measured, this stands above the highest ground seen so far or above
             *    sea level, rather than at y = 3.
             *
             * 2. Tile-level live updates, offered as window.__vantaloomRefreshTiles().
             *    A re-conversion rewrites only the chunks whose blocks actually changed, and BlueMap
             *    re-renders only those - but the page has already fetched the tiles for that area once,
             *    and BlueMap serves tiles with "Cache-Control: max-age=86400" and no validator, so
             *    waiting changes nothing: the browser keeps showing the old picture until it is asked
             *    again. This re-fetches just the tiles currently in the scene, with a cache-busting
             *    parameter, and puts them back in place. Nothing is reloaded and the camera does not
             *    move, so the update happens where the user is looking instead of flashing a new frame.
             *
             *    The page that embeds this viewer calls it; see the result side of the compare view.
             *
             * Written by the conversion tool. It is safe to delete, together with its entries in the
             * "scripts" and "styles" lists of webapp.conf.
             */
            (function () {
              "use strict";

              var state = { patched: false, highestGround: null };
              window.__vantaloomViewer = state;

              function targetHeight(app) {
                var viewer = app.mapViewer;
                var controls = viewer && viewer._controlsManager;
                if (!controls || !viewer.map) return null;

                var ground = null;
                try {
                  ground = viewer.map.terrainHeightAt(controls.position.x, controls.position.z);
                } catch (ignored) {
                  ground = null;
                }
                if (typeof ground === "number" && ground > 0) {
                  if (state.highestGround === null || ground > state.highestGround) {
                    state.highestGround = ground;
                  }
                  return ground + 3;
                }

                // Nothing loaded under that point to measure. Stand above the highest ground seen
                // so far, or above sea level when none has been seen yet.
                var base = state.highestGround === null ? 63 : state.highestGround;
                return Math.max(80, base + 12);
              }

              function patch() {
                var app = window.bluemap;
                if (!app || !app.mapViewer || typeof app.setFreeFlight !== "function") return false;
                if (state.patched) return true;

                var original = app.setFreeFlight;
                app.setFreeFlight = function (duration, height) {
                  if (height === undefined) {
                    var computed = targetHeight(app);
                    if (computed !== null) height = computed;
                  }
                  return original.call(this, duration, height);
                };
                state.patched = true;
                return true;
              }

              // The viewer is created as the page boots, so wait for it to exist.
              var attempts = 0;
              var timer = setInterval(function () {
                if (patch() || ++attempts > 400) clearInterval(timer);
              }, 50);

              // ---- tile-level live updates ----

              var live = { refreshing: false, refreshes: 0, lastRefreshed: 0, lastTileCount: 0 };
              state.live = live;

              function managers(map) {
                var list = [];
                // Each entry carries both the scene to walk and the loader that can rebuild a geometry
                // tile: the loader belongs to the manager, not to the individual tile.
                function add(manager) {
                  if (!manager) return;
                  list.push({
                    scene: manager.scene,
                    loader: manager.tileLoader || manager.loader || null
                  });
                }
                add(map.hiresTileManager);
                // The modern viewer keeps one manager per lowres level; the 1.12.2-era viewer keeps a
                // single one. Both shapes are accepted rather than assuming either.
                var low = map.lowresTileManager;
                if (Array.isArray(low)) {
                  for (var i = 0; i < low.length; i++) add(low[i]);
                } else {
                  add(low);
                }
                return list;
              }

              // Tiles are drawn one of two ways, and which one decides how a replacement is applied: the
              // modern viewer keeps a texture per tile, while the 1.12.2-era viewer keeps geometry that
              // it builds into a mesh. What the tile actually holds is inspected, so both are handled by
              // the same code instead of guessing from the version.
              function textureOf(tile) {
                var uniforms = tile && tile.material && tile.material.uniforms;
                var image = uniforms && uniforms.textureImage ? uniforms.textureImage.value : null;
                return image && typeof image === "object" ? image : null;
              }

              /** The tile's own x and z, taken from the URL it was loaded from. */
              function tileCoordinates(url) {
                var parts = String(url || "").split("/");
                for (var i = 0; i < parts.length - 1; i++) {
                  var a = parts[i];
                  var b = parts[i + 1];
                  if (a.charAt(0) === "x" && b.charAt(0) === "z") {
                    var x = Number(a.slice(1));
                    var z = parseFloat(b.slice(1));
                    if (!isNaN(x) && !isNaN(z)) return { x: x, z: z };
                  }
                }
                return null;
              }

              function tileUrlOf(tile) {
                return (tile.userData && tile.userData.tileUrl) || null;
              }

              // A tile URL is recorded relative to the site root and already includes the map's data folder,
              // so it resolves against the origin and nothing is prefixed - prefixing it again would ask for
              // a path that does not exist.
              function absolute(url) {
                if (!url) return null;
                if (/^https?:/i.test(url)) return url;
                if (url.charAt(0) === "/") return location.origin + url;
                if (url.slice(0, 2) === "./") return location.origin + "/" + url.slice(2);
                return location.origin + "/" + url;
              }

              // BlueMap serves tiles as "public, max-age=86400" with no ETag or Last-Modified, so the only
              // way to see a rewritten tile is to ask for a URL the browser has not cached.
              function bust(url, stamp) {
                return url + (url.indexOf("?") >= 0 ? "&" : "?") + "v=" + stamp;
              }

              /** Replace a tile that is drawn from a texture. */
              function refreshTexture(texture, url, stamp) {
                return new Promise(function (resolve) {
                  var replacement = new Image();
                  replacement.crossOrigin = "anonymous";
                  replacement.onload = function () {
                    texture.image = replacement;
                    texture.needsUpdate = true;
                    resolve(true);
                  };
                  replacement.onerror = function () { resolve(false); };
                  replacement.src = bust(url, stamp);
                });
              }

              /**
               * Replace a tile that is drawn from geometry, by having its own loader rebuild it.
               *
               * Geometry cannot be swapped underneath a mesh, so the rebuilt mesh takes the old one's
               * place in the scene and inherits its transform - that is what makes this an update in
               * place rather than a reload.
               */
              function refreshGeometry(layer, tile, url) {
                var loader = layer.loader;
                var coords = tileCoordinates(url);
                var scene = layer.scene;
                if (!loader || typeof loader.load !== "function" || !coords || !scene) {
                  return Promise.resolve(false);
                }

                return new Promise(function (resolve) {
                  // This viewer stamps its tile requests with a value it picks once per page load, which
                  // is exactly what keeps a re-request out of the browser cache. A fresh value makes
                  // this a URL the browser has not already answered.
                  try { loader.tileCacheHash = Math.round(1e6 * Math.random()); } catch (ignored) { /* not fatal */ }

                  var settled = false;
                  var finish = function (ok) { if (!settled) { settled = true; resolve(ok); } };
                  // A tile that cannot be rebuilt must not hold the whole refresh open.
                  var guard = setTimeout(function () { finish(false); }, 10000);

                  try {
                    loader.load(coords.x, coords.z, function (rebuilt) {
                      clearTimeout(guard);
                      if (!rebuilt || !rebuilt.isObject3D) { finish(false); return; }
                      rebuilt.position.copy(tile.position);
                      rebuilt.scale.copy(tile.scale);
                      rebuilt.rotation.copy(tile.rotation);
                      if (tile.layers && rebuilt.layers) rebuilt.layers.mask = tile.layers.mask;
                      rebuilt.userData = tile.userData;
                      scene.add(rebuilt);
                      scene.remove(tile);
                      if (tile.geometry && tile.geometry.dispose) tile.geometry.dispose();
                      finish(true);
                    });
                  } catch (ignored) {
                    clearTimeout(guard);
                    finish(false);
                  }
                });
              }

              /**
               * Re-fetch every tile currently in the scene, in place. Returns how many were replaced.
               */
              function refreshTiles(reason) {
                var app = window.bluemap;
                var viewer = app && app.mapViewer;
                var map = viewer && viewer.map;
                if (!map) return Promise.resolve(0);
                if (live.refreshing) return Promise.resolve(0);

                live.refreshing = true;
                live.reason = reason || "requested";

                var stamp = String(Date.now());
                var pending = [];
                var layers = managers(map);
                for (var i = 0; i < layers.length; i++) {
                  var layer = layers[i];
                  var tiles = (layer.scene && layer.scene.children) || [];
                  // Copied first: replacing a tile changes the list being walked.
                  var current = tiles.slice();
                  for (var j = 0; j < current.length; j++) {
                    var tile = current[j];
                    var url = absolute(tileUrlOf(tile));
                    if (!url) continue;
                    var texture = textureOf(tile);
                    pending.push(texture
                      ? refreshTexture(texture, url, stamp)
                      : refreshGeometry(layer, tile, url));
                  }
                }

                return Promise.all(pending).then(function (results) {
                  var done = 0;
                  for (var k = 0; k < results.length; k++) if (results[k]) done++;
                  live.refreshing = false;
                  live.refreshes++;
                  live.lastRefreshed = done;
                  live.lastTileCount = results.length;
                  return done;
                }, function () {
                  live.refreshing = false;
                  return 0;
                });
              }

              // The page embedding this viewer asks for a refresh once it knows a conversion has rewritten
              // the world. Deliberately not polled here: the page already knows, and asking it means one
              // mechanism instead of two that can disagree.
              window.__vantaloomRefreshTiles = refreshTiles;

              // The viewer is shown in an iframe on its own port, so the page around it is a different
              // origin and cannot reach this function directly. postMessage is the one channel that
              // works across that boundary, which is why the request arrives as a message rather than
              // as a direct call.
              var REFRESH_REQUEST = "vantaloom:refresh-tiles";
              var REFRESH_DONE = "vantaloom:tiles-refreshed";

              window.addEventListener("message", function (event) {
                var request = event.data;
                if (!request || request.type !== REFRESH_REQUEST) return;
                refreshTiles(request.reason).then(function (count) {
                  try {
                    event.source.postMessage({
                      type: REFRESH_DONE,
                      count: count,
                      revision: request.revision
                    }, "*");
                  } catch (ignored) { /* the page moved on; nothing to report to */ }
                });
              });
            })();
            """;

    /**
     * The interface theme written into every web root.
     * <p>
     * The viewer's controls are BlueMap's own, so they cannot be restyled by editing this tool's page.
     * What is changed here is only how a control answers to a press - rounded geometry, a springy scale
     * on hover and a squash on click - which is the same feel the rest of this tool uses. Nothing here
     * changes what a control does.
     */
    private static final String VIEWER_CSS = """
            :root {
              --vantaloom-radius: 14px;
              --vantaloom-spring: cubic-bezier(.34, 1.56, .64, 1);
              --vantaloom-speed: 300ms;
            }

            /* Controls: rounded, and springy on hover and press. */
            button,
            a[role="button"],
            [role="button"],
            .menu-button,
            .menuButton,
            [class*="button"] {
              border-radius: var(--vantaloom-radius) !important;
              transition:
                transform var(--vantaloom-speed) var(--vantaloom-spring),
                box-shadow var(--vantaloom-speed) var(--vantaloom-spring),
                background-color 160ms ease !important;
              will-change: transform;
            }

            button:hover:not(:disabled),
            a[role="button"]:hover,
            .menu-button:hover,
            [class*="button"]:hover {
              transform: scale(1.06) !important;
              box-shadow: 0 8px 20px rgba(0, 0, 0, .26) !important;
            }

            button:active:not(:disabled),
            a[role="button"]:active,
            .menu-button:active,
            [class*="button"]:active {
              transform: scale(.93) !important;
              transition-duration: 90ms !important;
            }

            button:focus-visible,
            a[role="button"]:focus-visible {
              outline: 2px solid rgba(255, 255, 255, .7) !important;
              outline-offset: 2px !important;
            }

            /* Panels and popups share the same soft geometry. */
            [class*="menu"] > div,
            [class*="popup"],
            [class*="panel"],
            [class*="dialog"] {
              border-radius: 18px !important;
            }

            /* A short settle after a panel appears, so it lands instead of merely showing up. */
            @keyframes vantaloom-settle {
              0%   { transform: scale(.94); opacity: 0; }
              62%  { transform: scale(1.015); opacity: 1; }
              100% { transform: scale(1); opacity: 1; }
            }

            [class*="menu"] > div,
            [class*="popup"] {
              animation: vantaloom-settle 340ms var(--vantaloom-spring) both;
            }

            /* A press that answers the pointer, rather than just changing colour. */
            input[type="range"] {
              border-radius: 999px !important;
            }

            input[type="range"]::-webkit-slider-thumb {
              border-radius: 999px !important;
              transition: transform var(--vantaloom-speed) var(--vantaloom-spring) !important;
            }

            input[type="range"]:active::-webkit-slider-thumb {
              transform: scale(1.25) !important;
            }

            @media (prefers-reduced-motion: reduce) {
              button,
              a[role="button"],
              [role="button"],
              [class*="button"],
              [class*="menu"] > div,
              [class*="popup"],
              input[type="range"]::-webkit-slider-thumb {
                transition: none !important;
                animation: none !important;
                transform: none !important;
              }
            }
            """;

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
