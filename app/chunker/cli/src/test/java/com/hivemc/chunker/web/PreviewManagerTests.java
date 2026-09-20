package com.hivemc.chunker.web;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** 用可控制的渲染完成信号验证版本发布，不依赖渲染速度碰巧足够快。 */
class PreviewManagerTests {
    @TempDir Path root;

    /** 生产配置的首次、增量、手动重试均渲染固定稀疏副本，状态仍指向正式输出。 */
    @Test void usesSparseResultForInitialAndIncrementalRender() throws Exception {
        Path source=root.resolve("input"),output=root.resolve("output");
        for(Path path:java.util.List.of(source,output)) {
            java.nio.file.Files.createDirectories(path);
            java.nio.file.Files.write(path.resolve("level.dat"),new byte[]{1});
            PreparedWorldsTests.write(path.resolve("region/r.0.0.mca"),0,PreparedWorldsTests.column(true));
            PreparedWorldsTests.write(path.resolve("region/r.0.0.mca"),10,PreparedWorldsTests.column(false));
        }
        FakeRunner runner=new FakeRunner();
        try(PreviewManager previews=new PreviewManager(root.resolve("viewers"),runner,()->{},new PreparedWorlds(root.resolve("sources")))) {
            previews.prepareConversion(source,output);previews.result(source,output,false);await(previews,"done");
            Path first=runner.resultWorld;
            assertNotEquals(output,first);assertEquals(output.toAbsolutePath().toString(),previews.after().world());
            Path previous=first.getParent().resolveSibling(first.getParent().getFileName().toString().replace("result-sparse-","result-")).resolve("data");
            java.nio.file.Files.createDirectories(previous);
            java.nio.file.Files.write(previous.resolve("minecraft-client-1.12.0.jar"),new byte[]{2,3});
            try(var region=new java.io.RandomAccessFile(first.resolve("region/r.0.0.mca").toFile(),"r")) {
                region.seek(40);assertEquals(0,region.readInt());
            }
            PreparedWorldsTests.write(output.resolve("region/r.0.0.mca"),0,PreparedWorldsTests.column(false));
            runner.release.countDown();
            previews.prepareConversion(source,output);previews.result(source,output,false);await(previews,"done");
            assertEquals(first,runner.resultWorld);assertEquals(2,previews.resultRevision());
            assertArrayEquals(new byte[]{2,3},java.nio.file.Files.readAllBytes(first.getParent().resolve("data/minecraft-client-1.12.0.jar")));
            previews.result(source,output,true);await(previews,"done");
            assertEquals(first,runner.resultWorld);assertEquals(3,previews.resultRevision());
        }
    }

    /** 写盘完成但渲染还没完成时不能发布新版本，也不能重启已有查看器。 */
    @Test void publishesRevisionOnlyAfterIncrementalRender() throws Exception {
        FakeRunner runner = new FakeRunner();
        try (PreviewManager previews = new PreviewManager(root, runner, () -> {})) {
            Path source = root.resolve("input"), output = root.resolve("output");
            previews.prepareConversion(source, output);
            assertEquals("rendering", previews.after().status());
            assertEquals(0, previews.resultRevision());
            previews.result(source, output, false);
            await(previews, "done");
            var initial = previews.after();
            assertEquals(1, previews.resultRevision());
            previews.prepareConversion(source, output);
            assertEquals("rendering", previews.after().status());
            previews.result(source, output, false);
            assertTrue(runner.incrementalStarted.await(3, TimeUnit.SECONDS));
            assertEquals(1, previews.resultRevision());
            assertEquals("rendering", previews.after().status());
            assertEquals(initial.port(), previews.after().port());
            assertEquals(initial.generation(), previews.after().generation());
            runner.release.countDown();
            await(previews, "done");
            assertEquals(2, previews.resultRevision());
            assertEquals(2, runner.servers.get()); // 源与结果各一个 HTTP 服务。
        }
    }

    /** 内存错误等渲染失败不能把旧预览标记成已更新。 */
    @Test void failedRenderKeepsLastPublishedRevision() throws Exception {
        FakeRunner runner = new FakeRunner();
        try (PreviewManager previews = new PreviewManager(root, runner, () -> {})) {
            Path source = root.resolve("input"), output = root.resolve("output");
            previews.prepareConversion(source, output); previews.result(source, output, false);
            await(previews, "done");
            runner.fail = true;
            previews.prepareConversion(source, output); previews.result(source, output, false);
            runner.release.countDown();
            await(previews, "failed");
            assertEquals(1, previews.resultRevision());
            assertTrue(previews.after().message().contains("OutOfMemoryError"));
        }
    }

    /** 等待具体状态，有上限，不用任意长的固定休眠掩盖竞态。 */
    private static void await(PreviewManager previews, String state) {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            while (!previews.after().status().equals(state)) Thread.sleep(5);
        });
    }

    /** 第二轮结果渲染由测试显式放行。 */
    private static final class FakeRunner extends BlueMapRunner {
        final AtomicInteger renders = new AtomicInteger(), servers = new AtomicInteger();
        final CountDownLatch incrementalStarted = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile boolean fail;
        volatile Path resultWorld;
        /** 不启动任何外部程序。 */
        FakeRunner() { super(Path.of("unused"), Path.of("unused")); }
        /** 控制渲染任务的完成或失败时刻。 */
        @Override public Path render(boolean modern, Path world, Path work, int port) throws IOException, InterruptedException {
            if (!modern) resultWorld=world;
            if (!modern && renders.incrementAndGet() > 1) {
                incrementalStarted.countDown(); release.await();
                if (fail) throw new IOException("OutOfMemoryError");
            }
            return work;
        }
        /** 记录服务启动次数，验证增量过程中复用服务。 */
        @Override public Process startWebServer(boolean modern, Path work) { servers.incrementAndGet(); return new FakeProcess(); }
        /** 假服务直接就绪。 */
        @Override public void awaitServer(Process process, int port) {}
    }

    /** 可停止的内存内进程替身。 */
    private static final class FakeProcess extends Process {
        private boolean alive = true;
        /** 测试不发送进程输入。 */
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        /** 测试不读取标准输出。 */
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        /** 测试不读取错误输出。 */
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        /** 已停止的替身直接结束。 */
        @Override public int waitFor() { return 0; }
        /** 返回退出状态。 */
        @Override public int exitValue() { return 0; }
        /** 记录清理操作。 */
        @Override public void destroy() { alive = false; }
        /** 模拟服务是否仍然存活。 */
        @Override public boolean isAlive() { return alive; }
    }
}
