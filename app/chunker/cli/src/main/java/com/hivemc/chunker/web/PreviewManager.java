package com.hivemc.chunker.web;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Independent source/result lifecycles. Analysis may start the source without a converted world.
 * Each side has one worker; generation checks prevent an old render from publishing into a new project.
 * World-specific work directories prevent BlueMap tiles from leaking between unrelated worlds.
 */
public final class PreviewManager implements AutoCloseable {
    public record Snapshot(String world, String status, String message, int port, long generation,
                           long elapsedSeconds) {}

    private final Path root;
    private final BlueMapRunner runner;
    private final Runnable texturesChanged;
    private final PreparedWorlds preparedWorlds;
    private String sourceCamera = "";
    private final Side before = new Side(true);
    private final Side after = new Side(false);
    private long generation;
    private boolean closed;

    /** 仅在本轮所有瓦片渲染成功后发布版本，转换写盘完成并不代表预览完成。 */
    private long resultRevision;
    private boolean awaitingResult;

    private static final class Side {
        final boolean modern;
        final ExecutorService worker;
        Task task;
        Side(boolean modern) {
            this.modern = modern;
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, modern ? "source-preview" : "result-preview");
                t.setDaemon(true);
                return t;
            });
        }
    }

    private static final class Task {
        final Path source, world, work;
        final long generation;
        long started = System.currentTimeMillis(), finished;
        String status = "rendering", message = "准备渲染";
        int port;
        Process server;
        Future<?> future;
        boolean preparing;
        Task(Path source, Path world, Path work, long generation) {
            this.source = source; this.world = world; this.work = work; this.generation = generation;
        }
    }

    public PreviewManager(Path root, BlueMapRunner runner, Runnable texturesChanged) {
        this(root, runner, texturesChanged, null);
    }

    /** 两侧生命周期独立，源预览与转换共享同一次内容核查。 */
    public PreviewManager(Path root, BlueMapRunner runner, Runnable texturesChanged, PreparedWorlds preparedWorlds) {
        this.root = root.toAbsolutePath().normalize();
        this.runner = runner;
        this.texturesChanged = texturesChanged;
        this.preparedWorlds = preparedWorlds;
    }

    /** Start once, reuse an in-flight/live source, retry a failed source on an explicit new request. */
    public synchronized boolean source(Path world) {
        ensureOpen();
        world = world.toAbsolutePath().normalize();
        Task old = before.task;
        if (old != null && old.world.equals(world) && reusable(old)) return false;
        if (old == null || !old.world.equals(world)) cancel(after);
        start(before, world, world);
        return true;
    }

    /** Keep the source (and a matching live result watcher) when converting again. */
    public synchronized void prepareConversion(Path source, Path output) {
        source(source);
        Task old = after.task;
        if (old != null && (!old.source.equals(source.toAbsolutePath().normalize())
                || !old.world.equals(output.toAbsolutePath().normalize()) || !reusable(old))) cancel(after);
        awaitingResult = true;
    }

    /**
     * 无损模式不需要转换结果预览：丢弃结果侧、只留源预览，省掉一次完整渲染与一个常驻进程。
     * 需要时仍可照常启动结果侧。
     */
    public synchronized void discardResult() {
        cancel(after);
        awaitingResult = false;
    }

    /** Only a successful conversion may call this. A manual refresh restarts the result, not the source. */
    public synchronized boolean result(Path source, Path output, boolean force) {
        source = source.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        source(source);
        Task old = after.task;
        if (old != null && old.source.equals(source) && old.world.equals(output) && reusable(old)) {
            if (old.status.equals("rendering")) return false;
            if (!force) {
                if (!awaitingResult) return false;
                old.status = "rendering";
                old.message = "转换已写入，正在增量渲染变更区块";
                old.started = System.currentTimeMillis(); old.finished = 0;
                old.future = after.worker.submit(() -> refreshResult(old));
                return true;
            }
        }
        start(after, source, output);
        return true;
    }

    private static boolean reusable(Task task) {
        return task.status.equals("rendering") || (task.status.equals("done")
                && task.server != null && task.server.isAlive());
    }

    private void start(Side side, Path source, Path world) {
        cancel(side);
        String key = digest(source + "\n" + world);
        // 新结果缓存单独命名，旧版全世界瓦片不能混入稀疏预览。
        Task task = new Task(source, world, root.resolve((side.modern ? "source-" : "result-sparse-") + key), ++generation);
        side.task = task;
        task.future = side.worker.submit(() -> render(side, task));
    }

    private synchronized boolean current(Side side, Task task) {
        return !closed && side.task == task && !Thread.currentThread().isInterrupted();
    }

    private void render(Side side, Task task) {
        Process server = null;
        // Reserve a unique local port during the render; release it only immediately before starting the viewer.
        try (ServerSocket reservation = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
            int port = reservation.getLocalPort();
            synchronized (this) {
                if (!current(side, task)) return;
                task.message = side.modern ? "正在核查内容区块（与分析共用）" : "正在渲染 1.12.2 转换结果";
            }
            Path renderWorld = task.world;
            if (side.modern && preparedWorlds != null) {
                var prepared = preparedWorlds.prepare(task.world);
                renderWorld = prepared.directory();
                synchronized (this) {
                    if (!current(side, task)) return;
                    sourceCamera = prepared.summary().camera();
                    task.message = "保留 " + prepared.summary().contentChunks() + " 个内容区块，跳过 " + prepared.summary().skippedChunks() + " 个空白区块，正在渲染";
                }
            }
            if (!side.modern) renderWorld = prepareResult(task);
            if (!current(side, task)) return;
            runner.render(side.modern, renderWorld, task.work, port);
            if (!current(side, task)) return;
            try { texturesChanged.run(); } catch (RuntimeException ignored) { /* icon discovery is non-fatal */ }
            reservation.close();
            // 服务进程只提供文件；渲染由转换完成后的一次增量任务驱动。
            server = runner.startWebServer(side.modern, task.work);
            if (server == null) throw new IOException("BlueMap 预览进程未启动。");
            synchronized (this) {
                if (!current(side, task)) { BlueMapRunner.stopProcess(server); return; }
                task.server = server;
                task.message = "正在启动预览服务";
            }
            runner.awaitServer(server, port);
            synchronized (this) {
                if (!current(side, task)) { BlueMapRunner.stopProcess(server); return; }
                task.port = port;
                task.status = "done";
                task.finished = System.currentTimeMillis();
                task.message = side.modern ? "源地图预览已就绪" : "转换结果预览已就绪";
                if (!side.modern) { resultRevision++; awaitingResult = false; }
            }
        } catch (Exception e) {
            if (server != null) BlueMapRunner.stopProcess(server);
            synchronized (this) {
                if (side.task == task && !closed) {
                    task.port = 0;
                    task.status = "failed";
                    task.finished = System.currentTimeMillis();
                    if (!side.modern) awaitingResult = false;
                    task.message = "预览失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    public synchronized Snapshot before() { return snapshot(before); }
    public synchronized Snapshot after() { return snapshot(after); }

    /** 首次打开源地图时使用内容边界，之后保留用户操作的镜头。 */
    public synchronized String sourceCamera() { return sourceCamera; }

    /** 原有 HTTP 服务和镜头保持不动，等待这次增量渲染的进程成功退出。 */
    private void refreshResult(Task task) {
        try {
            Path renderWorld = prepareResult(task);
            if (!current(after, task)) return;
            runner.render(false, renderWorld, task.work, task.port);
            synchronized (this) {
                if (!current(after, task)) return;
                task.status = "done"; task.message = "增量渲染完成，正在更新画面";
                task.finished = System.currentTimeMillis();
                resultRevision++; awaitingResult = false;
            }
        } catch (Exception e) {
            synchronized (this) {
                if (after.task != task || closed) return;
                task.status = "failed"; task.message = "增量预览失败：" + e.getMessage();
                task.finished = System.currentTimeMillis(); awaitingResult = false;
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    /** 首次和增量更新共用固定副本路径，清空记录也能通知 BlueMap 更新旧瓦片。 */
    private Path prepareResult(Task task) throws IOException {
        if (preparedWorlds == null) return task.world;
        synchronized (this) { task.preparing = true; task.message = "正在筛除结果中的空白区块"; }
        try {
            reuseResultResources(task);
            var prepared = ResultPreviewWorld.prepare(task.world, task.work.resolve("world"));
            synchronized (this) {
                task.message = "保留 " + prepared.contentChunks() + " 个内容区块，跳过 " + prepared.skippedChunks()
                        + " 个空白区块，正在渲染";
            }
            return prepared.directory();
        } finally {
            synchronized (this) { task.preparing = false; }
        }
    }

    /** 升级到稀疏缓存时复用原有客户端资源，避免用户重新下载同一份贴图。 */
    private void reuseResultResources(Task task) throws IOException {
        Path previous = root.resolve("result-" + digest(task.source + "\n" + task.world)).resolve("data");
        Path data = task.work.resolve("data");
        java.nio.file.Files.createDirectories(data);
        for (String name : java.util.List.of("minecraft-client-1.12.0.jar", "resourceExtensions.zip")) {
            Path input = previous.resolve(name), output = data.resolve(name);
            if (!java.nio.file.Files.exists(output) && java.nio.file.Files.isRegularFile(input))
                java.nio.file.Files.copy(input, output);
        }
    }

    /** 原转换失败时解除预览等待，保留旧画面但不标记成新结果。 */
    public synchronized void conversionFailed() { awaitingResult = false; }

    /** The number of conversions written to the result world so far. */
    public synchronized long resultRevision() { return resultRevision; }

    private Snapshot snapshot(Side side) {
        Task t = side.task;
        if (t == null) return new Snapshot("", side == after && awaitingResult ? "rendering" : "idle", "等待转换输出", 0, 0, 0);
        if (t.status.equals("done") && (t.server == null || !t.server.isAlive())) {
            t.status = "failed"; t.port = 0; t.message = "预览进程已退出，请重新渲染。";
        }
        String status = side == after && awaitingResult && t.status.equals("done") ? "rendering" : t.status;
        String progress = t.status.equals("rendering") && !t.preparing ? runner.progress(t.work) : "";
        String message = side == after && awaitingResult && t.status.equals("done") ? "等待本次转换输出并更新预览" : t.message;
        return new Snapshot(t.world.toString(), status, progress.isEmpty() ? message : progress, t.port, t.generation,
                Math.max(0, ((t.finished == 0 ? System.currentTimeMillis() : t.finished) - t.started) / 1000));
    }

    private void cancel(Side side) {
        Task old = side.task;
        side.task = null; // invalidate BEFORE interrupting a worker that may finish concurrently
        if (old == null) return;
        if (old.future != null) old.future.cancel(true);
        if (old.server != null) BlueMapRunner.stopProcess(old.server);
    }

    private void ensureOpen() { if (closed) throw new IllegalStateException("Preview manager is closed"); }

    @Override public synchronized void close() {
        closed = true;
        cancel(before); cancel(after);
        before.worker.shutdownNow(); after.worker.shutdownNow();
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8))).substring(0, 20);
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
