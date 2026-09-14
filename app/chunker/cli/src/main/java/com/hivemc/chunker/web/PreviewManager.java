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
    private final Side before = new Side(true);
    private final Side after = new Side(false);
    private long generation;
    private boolean closed;

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
        final long generation, started = System.currentTimeMillis();
        String status = "rendering", message = "准备渲染";
        int port;
        Process server;
        Future<?> future;
        Task(Path source, Path world, Path work, long generation) {
            this.source = source; this.world = world; this.work = work; this.generation = generation;
        }
    }

    public PreviewManager(Path root, BlueMapRunner runner, Runnable texturesChanged) {
        this.root = root.toAbsolutePath().normalize();
        this.runner = runner;
        this.texturesChanged = texturesChanged;
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
    }

    /** Only a successful conversion may call this. A manual refresh restarts the result, not the source. */
    public synchronized boolean result(Path source, Path output, boolean force) {
        source = source.toAbsolutePath().normalize();
        output = output.toAbsolutePath().normalize();
        source(source);
        Task old = after.task;
        if (old != null && old.source.equals(source) && old.world.equals(output)
                && reusable(old) && (!force || old.status.equals("rendering"))) return false;
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
        Task task = new Task(source, world, root.resolve((side.modern ? "source-" : "result-") + key), ++generation);
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
                task.message = side.modern ? "正在渲染源地图（与分析并行）" : "正在渲染 1.12.2 转换结果";
            }
            runner.render(side.modern, task.world, task.work, port);
            if (!current(side, task)) return;
            try { texturesChanged.run(); } catch (RuntimeException ignored) { /* icon discovery is non-fatal */ }
            reservation.close();
            server = side.modern ? runner.startWebServer(true, task.work) : runner.startWatcher(false, task.work);
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
                task.message = side.modern ? "源地图预览已就绪" : "转换结果预览已就绪";
            }
        } catch (Exception e) {
            if (server != null) BlueMapRunner.stopProcess(server);
            synchronized (this) {
                if (side.task == task && !closed) {
                    task.port = 0;
                    task.status = "failed";
                    task.message = "预览失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                }
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }

    public synchronized Snapshot before() { return snapshot(before); }
    public synchronized Snapshot after() { return snapshot(after); }

    private Snapshot snapshot(Side side) {
        Task t = side.task;
        if (t == null) return new Snapshot("", "idle", "", 0, 0, 0);
        if (t.status.equals("done") && (t.server == null || !t.server.isAlive())) {
            t.status = "failed"; t.port = 0; t.message = "预览进程已退出，请重新渲染。";
        }
        return new Snapshot(t.world.toString(), t.status, t.message, t.port, t.generation,
                Math.max(0, (System.currentTimeMillis() - t.started) / 1000));
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
