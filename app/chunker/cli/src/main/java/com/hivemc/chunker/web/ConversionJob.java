package com.hivemc.chunker.web;

import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.cli.messenger.Messenger;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.encoding.java.base.writer.IncrementalWriter;
import com.hivemc.chunker.downgrade.Approximations;
import com.hivemc.chunker.downgrade.ConversionReport;
import com.hivemc.chunker.downgrade.SurveyResult;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.resolver.MappingsFileResolvers;
import com.hivemc.chunker.scheduling.task.TrackedTask;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs one conversion, from reading the source world to writing the report.
 * <p>
 * Kept separate from the web layer so the same work can be driven from a test or a script without a browser
 * involved, which is how the behaviour was verified before any of the interface existed.
 */
public class ConversionJob {
    /**
     * Gives every conversion a distinct number.
     * <p>
     * The interface needs to tell "the conversion I already know about" from "a new one just finished": both look
     * identical otherwise, since the status is simply "done" in either case. Comparing the reported progress or the
     * block counts would be a guess - two runs can legitimately agree on both - so each job is identified instead.
     */
    private static final java.util.concurrent.atomic.AtomicLong NEXT_ID = new java.util.concurrent.atomic.AtomicLong(0);

    private final long id = NEXT_ID.incrementAndGet();
    private final Path input;
    private final Path output;
    private final Path reportDirectory;
    private final boolean shiftToFit;
    private final boolean clearContainers;
    private final String mappingsJson;
    private final boolean approximate;
    private final boolean lossless;

    /**
     * 无损模式下的特殊方块处理器。默认是幽灵方块通道；表缺失时自动退化为空实现，
     * 结果与未接入时一致（这些方块变空气），不拖垮整个转换任务。
     * <p>
     * 只读、无状态、线程安全：任务会并发调用它。
     */
    private com.hivemc.chunker.downgrade.LosslessBlockHandler losslessHandler =
            com.hivemc.chunker.downgrade.GhostBlockHandler.createOrEmpty();

    /** 后续处理方在 run 前注入线程安全实现，分析与正式转换均复用此任务入口。 */
    public void setLosslessHandler(com.hivemc.chunker.downgrade.LosslessBlockHandler handler) {
        this.losslessHandler = java.util.Objects.requireNonNull(handler);
    }
    private PreparedWorlds preparedWorlds;

    /** 与源预览共享内容索引；必须在提交后台任务前设置。 */
    public void prepareWith(PreparedWorlds preparedWorlds) { this.preparedWorlds = preparedWorlds; }

    private final AtomicReference<String> status = new AtomicReference<>("idle");
    private final AtomicReference<String> message = new AtomicReference<>("");
    private final AtomicReference<SurveyResult> survey = new AtomicReference<>();
    private volatile double progress;
    private volatile boolean finished;
    private volatile boolean failed;

    /**
     * Create a conversion job.
     *
     * @param input           the source world folder.
     * @param output          where the converted world should be written.
     * @param reportDirectory where the report should be written (usually the output folder).
     * @param shiftToFit      whether to move the world vertically into the target height range.
     * @param clearContainers whether to keep container blocks but drop their contents.
     * @param mappingsJson    optional user block mappings, or null to use the built-in behaviour.
     * @param approximate     whether to substitute the nearest target-version block for blocks that do not exist
     *                        there, instead of dropping them.
     */
    public ConversionJob(Path input, Path output, Path reportDirectory, boolean shiftToFit, boolean clearContainers, String mappingsJson, boolean approximate) {
        this(input, output, reportDirectory, shiftToFit, clearContainers, mappingsJson, approximate, false);
    }

    /** 两种模式共用同一任务；无损模式关闭内置替换，用户规则始终保留。 */
    public ConversionJob(Path input, Path output, Path reportDirectory, boolean shiftToFit, boolean clearContainers, String mappingsJson, boolean approximate, boolean lossless) {
        this.input = input;
        this.output = output;
        this.reportDirectory = reportDirectory;
        this.shiftToFit = shiftToFit;
        this.clearContainers = clearContainers;
        this.mappingsJson = mappingsJson;
        this.approximate = approximate;
        this.lossless = lossless;
    }

    /**
     * Run the conversion. Intended to be called on a background thread.
     */
    public void run() {
        try {
            PreparedWorlds.Prepared prepared = null;
            if (preparedWorlds != null) {
                status.set("preparing");
                message.set("正在核查内容区块并准备稀疏输入。");
                prepared = preparedWorlds.prepare(input);
            }
            status.set("reading");
            WorldConverter converter = new WorldConverter(UUID.randomUUID());
            converter.setSkipNewEmptyColumns(prepared != null);
            converter.setShiftToFit(shiftToFit);
            converter.setClearContainers(clearContainers);
            if (lossless) converter.setLosslessBlocks(new com.hivemc.chunker.downgrade.LosslessBlocks(losslessHandler));

            // The built-in approximations are always applied, with the user's own mappings taking precedence. Without
            // them whole categories of block - every wall, every non-oak trapdoor, every stripped log - are written
            // as air, which is the difference between a build and a colander.
            converter.setBlockMappings(new MappingsFileResolvers(MappingsFile.load(
                    approximate && !lossless ? Approximations.merge(mappingsJson) : Approximations.userOnly(mappingsJson)
            )));

            Optional<? extends LevelReader> reader = EncodingType.findReader((prepared == null ? input : prepared.directory()).toFile(), converter);
            if (reader.isEmpty()) {
                fail("This folder does not look like a Minecraft world that can be read.");
                return;
            }

            Optional<? extends LevelWriter> writer = Messenger.findWriter("JAVA_1_12_2", converter, output.toFile());
            if (writer.isEmpty()) {
                fail("The target version could not be prepared for writing.");
                return;
            }

            message.set("Measuring the world to work out how far it needs to move.");
            SurveyResult result = converter.survey(reader.get(), true);
            survey.set(result);

            status.set("converting");
            message.set("Converting blocks.");
            // Count from scratch: the numbers are reported per conversion, and the previous run's totals would
            // otherwise be added to this one's.
            IncrementalWriter.resetCounters();
            TrackedTask<Void> task = converter.convert(reader.get(), writer.get());

            double reported = -1;
            while (!task.future().isDone()) {
                double polled = task.getProgress();
                if (polled != reported) {
                    progress = polled;
                    reported = polled;
                }
                Thread.sleep(100);
            }

            // 等待异常完成本身，不能在 isDone 与 exceptionally 回调之间竞态误报成功。
            task.future().join();

            status.set("reporting");
            message.set("Writing the conversion report.");
            Files.createDirectories(reportDirectory);
            ConversionReport.write(converter, reader.get(), writer.get(), reportDirectory.toFile());
            if (prepared != null) writeSelectionReport(prepared.summary());

            progress = 1;
            status.set("done");
            // Reporting how many chunks actually changed is what makes the live preview understandable: a second
            // conversion that only touched a handful of chunks updates almost instantly, and without this number
            // that would look like nothing happened.
            long changedChunks = IncrementalWriter.getWritten();
            long unchangedChunks = IncrementalWriter.getUnchanged();
            if (changedChunks == 0 && unchangedChunks == 0) {
                message.set("Conversion finished.");
            } else {
                message.set("Conversion finished - " + changedChunks + " chunk(s) changed, "
                        + unchangedChunks + " already up to date.");
            }
        } catch (Exception e) {
            fail(e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            finished = true;
        }
    }

    private void fail(String reason) {
        failed = true;
        status.set("failed");
        message.set(reason);
    }

    /** 将核查/上下文/跳过数量同时写入机器报告与可读报告。 */
    private void writeSelectionReport(PreparedWorlds.Summary summary) throws java.io.IOException {
        var gson = new com.google.gson.GsonBuilder().setPrettyPrinting().create();
        Path json = reportDirectory.resolve("conversion-report.json");
        var report = com.google.gson.JsonParser.parseString(Files.readString(json, StandardCharsets.UTF_8)).getAsJsonObject();
        report.add("selection", gson.toJsonTree(summary));
        Files.writeString(json, gson.toJson(report), StandardCharsets.UTF_8);
        String detail = "\n区块筛选：扫描 " + summary.storedChunks() + "；内容 " + summary.contentChunks()
                + "；邻区上下文 " + summary.contextChunks() + "；跳过空白 " + summary.skippedChunks() + "。\n";
        Files.writeString(reportDirectory.resolve("conversion-report.txt"), detail, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
    }

    /**
     * Get the current stage of the job.
     *
     * @return a short stage name.
     */
    public String getStatus() {
        return status.get();
    }

    /**
     * Get a human readable description of what is happening.
     *
     * @return the current message.
     */
    public String getMessage() {
        return message.get();
    }

    /**
     * Get how far the conversion has progressed.
     *
     * @return a value between 0 and 1.
     */
    public double getProgress() {
        return progress;
    }

    /**
     * How many chunks this conversion rewrote because their contents differ from what was already in the output
     * folder. Chunks that came out identical are left untouched, timestamp and all, which is what lets the preview
     * re-render only what changed.
     *
     * @return the number of chunks written.
     */
    public long getChangedChunks() {
        return IncrementalWriter.getWritten();
    }

    /**
     * The number identifying this particular conversion.
     *
     * @return a value that differs for every conversion in this session.
     */
    public long getId() {
        return id;
    }

    /**
     * How many chunks were byte-for-byte identical to the output folder and therefore skipped.
     *
     * @return the number of chunks left as they were.
     */
    public long getUnchangedChunks() {
        return IncrementalWriter.getUnchanged();
    }

    /**
     * Get the measurements taken before the conversion.
     *
     * @return the survey result, or null if the survey has not finished yet.
     */
    public SurveyResult getSurvey() {
        return survey.get();
    }

    /**
     * 本次任务是否为无损模式（目标版本装不下的方块保持原样，而不是替换成近似方块）。
     *
     * @return true 表示无损模式。
     */
    public boolean isLossless() {
        return lossless;
    }

    /**
     * Whether the job has stopped, successfully or not.
     *
     * @return true if the job is no longer running.
     */
    public boolean isFinished() {
        return finished;
    }

    /**
     * Whether the job ended in failure.
     *
     * @return true if it failed.
     */
    public boolean isFailed() {
        return failed;
    }

    /**
     * Read the report this job wrote.
     *
     * @return the report JSON, or null if it could not be read.
     */
    public String readReport() {
        try {
            Path report = reportDirectory.resolve("conversion-report.json");
            if (!Files.isRegularFile(report)) return null;
            return Files.readString(report, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Get the survey result, phrased for display.
     *
     * @return a short summary, or an empty string if the survey has not run.
     */
    public String describeSurvey() {
        SurveyResult result = survey.get();
        if (result == null) return "";
        StringBuilder builder = new StringBuilder();
        builder.append("Lowest block Y: ").append(result.lowestBlockY());
        if (result.requiresShift()) {
            builder.append(" - world moved up ").append(result.shiftY()).append(" blocks");
        } else {
            builder.append(" - world left in place");
        }
        if (result.clipped()) {
            builder.append(" - ").append(result.clippedSections()).append(" section(s) over the height limit");
        }
        return builder.toString();
    }
}
