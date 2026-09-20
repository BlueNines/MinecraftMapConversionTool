package com.hivemc.chunker.conversion;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.handlers.ColumnConversionHandler;
import com.hivemc.chunker.conversion.handlers.LevelConversionHandler;
import com.hivemc.chunker.conversion.handlers.WorldConversionHandler;
import com.hivemc.chunker.conversion.handlers.pipeline.Pipeline;
import com.hivemc.chunker.conversion.handlers.pretransform.ColumnPreTransformConversionHandler;
import com.hivemc.chunker.conversion.handlers.pretransform.ColumnPreTransformWriterConversionHandler;
import com.hivemc.chunker.conversion.handlers.writer.LevelWriterConversionHandler;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.chunk.ChunkCoordPair;
import com.hivemc.chunker.conversion.intermediate.column.chunk.RegionCoordPair;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevel;
import com.hivemc.chunker.conversion.intermediate.level.ChunkerLevelSettings;
import com.hivemc.chunker.conversion.intermediate.level.map.ChunkerMap;
import com.hivemc.chunker.conversion.intermediate.world.ChunkerWorld;
import com.hivemc.chunker.conversion.intermediate.world.Dimension;
import com.hivemc.chunker.conversion.intermediate.world.DimensionRegistry;
import com.hivemc.chunker.downgrade.BlockSurvey;
import com.hivemc.chunker.downgrade.ContainerClearHandler;
import com.hivemc.chunker.downgrade.EntityFilterHandler;
import com.hivemc.chunker.downgrade.ShiftColumnHandler;
import com.hivemc.chunker.downgrade.ShiftStats;
import com.hivemc.chunker.downgrade.SurveyLevelWriter;
import com.hivemc.chunker.downgrade.SurveyResult;
import com.hivemc.chunker.mapping.resolver.MappingsFileResolvers;
import com.hivemc.chunker.pruning.PruningConfig;
import com.hivemc.chunker.pruning.PruningRegion;
import com.hivemc.chunker.scheduling.task.Environment;
import com.hivemc.chunker.scheduling.task.Task;
import com.hivemc.chunker.scheduling.task.TaskWeight;
import com.hivemc.chunker.scheduling.task.TrackedTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * A converter which allows a session to be created with settings and taking a reader and writer to use for conversion.
 */
public class WorldConverter implements Converter {
    /**
     * Signal used to indicate compaction has started, this is used to indicate the progress bar should change.
     */
    public static final String SIGNAL_COMPACTION = "signal_compaction";

    private final UUID sessionID;
    // State
    @Nullable
    protected Consumer<Boolean> compactionSignalConsumer;
    @Nullable
    protected ChunkerLevel level;
    @Nullable
    protected LevelReader reader = null;
    @Nullable
    protected LevelWriter writer = null;
    @Nullable
    protected Environment environment = null;
    protected Multimap<Converter.MissingMappingType, String> missingIdentifiers = Multimaps.synchronizedSetMultimap(
            MultimapBuilder.enumKeys(Converter.MissingMappingType.class)
                    .hashSetValues()
                    .build()
    );
    // Settings
    @Nullable
    private Map<String, PruningConfig> pruningConfigs;
    @Nullable
    private Map<String, String> dimensionMapping;
    private DimensionRegistry dimensionRegistry = new DimensionRegistry();
    @Nullable
    private Map<ChunkerBiome, ChunkerBiome> biomeMapping;
    @Nullable
    private JsonObject changedSettings;
    @Nullable
    private List<ChunkerMap> maps;

    /**
     * The lowest section index the target format can store, which is Y=0.
     */
    public static final int OUTPUT_MIN_SECTION_Y = 0;

    /**
     * The highest section index the target format can store, covering Y=240 to Y=255.
     */
    public static final int OUTPUT_MAX_SECTION_Y = 15;

    // Downgrade support
    private boolean shiftToFit;
    @Nullable
    private SurveyResult surveyResult;
    @Nullable
    private BlockSurvey currentSurvey;
    private final ShiftStats shiftStats = new ShiftStats();
    private boolean clearContainers = true;
    private boolean keepHangingEntities = true;
    private final AtomicInteger entitiesRemoved = new AtomicInteger();
    private final AtomicInteger containerItemsRemoved = new AtomicInteger();
    /**
     * How many block instances were dropped for want of a mapping, tallied per block identifier.
     * <p>
     * The deliberate counterpart to {@link #missingIdentifiers}: that collection deduplicates because a list of
     * missing mappings ought to, but a deduplicated list reads the same whether a build lost three stray blocks or
     * its entire staircase. Deciding which blocks deserve a hand-written mapping needs the count, not the presence.
     */
    private final ConcurrentHashMap<String, LongAdder> unmappedBlockInstances = new ConcurrentHashMap<>();
    /**
     * Which source blocks were written as something else, and how often.
     * <p>
     * Keyed by "source -> target" because the same source block can legitimately end up as different targets in
     * different versions of the mapping rules, and the user needs to see the target that was actually used.
     */
    private final ConcurrentHashMap<String, LongAdder> substitutions = new ConcurrentHashMap<>();
    @Nullable
    private MappingsFileResolvers blockMappings;
    private com.hivemc.chunker.downgrade.LosslessBlocks losslessBlocks;

    /** 为当前任务接入无损方块处理器；默认 null 保持降级模式行为。 */
    public void setLosslessBlocks(com.hivemc.chunker.downgrade.LosslessBlocks blocks) { this.losslessBlocks = blocks; }

    /** 旧版写入器与报告共用当前任务的处理入口。 */
    public com.hivemc.chunker.downgrade.LosslessBlocks getLosslessBlocks() { return losslessBlocks; }
    private boolean levelDBCompaction = true;
    private boolean processMaps = true;
    private boolean processItems = true;
    private boolean processEntities = true;
    private boolean processBlockEntities = true;
    private boolean processLootTables = true;
    private boolean processBiomes = true;
    private boolean processHeightMap = true;
    private boolean processLighting = true;
    private boolean processColumnPreTransform = true;
    private boolean allowNBTCopying = false;
    private boolean discardEmptyChunks = false;
    private boolean skipNewEmptyColumns;

    /** 建筑导出省略新的全空区块柱，已存在的柱仍写空以清除旧建筑。 */
    public void setSkipNewEmptyColumns(boolean value) { skipNewEmptyColumns = value; }

    /** 不改变 section 光照写入或上游通用转换的默认行为。 */
    public boolean shouldSkipNewEmptyColumns() { return skipNewEmptyColumns; }
    private boolean preventYBiomeBlending = false;
    private boolean customIdentifiers = true;
    private boolean exceptions = false;
    private boolean cancelled = false;

    /**
     * Create a new WorldConverter with a sessionID.
     *
     * @param sessionID the sessionID to identify the conversion.
     */
    public WorldConverter(UUID sessionID) {
        this.sessionID = sessionID;
    }

    /**
     * Set the handler for if compaction is signalled by the converter as started / stopped.
     *
     * @param compactionSignalConsumer a consumer which is called when compaction starts.
     */
    public void setCompactionSignal(@Nullable Consumer<Boolean> compactionSignalConsumer) {
        this.compactionSignalConsumer = compactionSignalConsumer;
    }

    /**
     * Set the pruning configuration used for discarding columns from the input worlds.
     *
     * @param pruningConfigs the configurations or null if none present.
     */
    public void setPruningConfigs(@Nullable Map<String, PruningConfig> pruningConfigs) {
        this.pruningConfigs = pruningConfigs;
    }


    /**
     * Set which dimension should map to which output dimension, if a dimension is not in the map it is discarded.
     *
     * @param dimensionMapping the mappings or null if it should keep the same input as output.
     */
    public void setDimensionMapping(@Nullable Map<String, String> dimensionMapping) {
        this.dimensionMapping = dimensionMapping;
    }

    /**
     * Set the dimension registry
     *
     * @param dimensionRegistry the registry.
     */
    public void setDimensionRegistry(@Nullable DimensionRegistry dimensionRegistry) {
        this.dimensionRegistry = dimensionRegistry;
    }

    /*
     * Set which biomes should map to which output biomes.
     *
     * @param biomeMapping the mappings or null if it should keep the same input as output.
     */
    public void setBiomeMapping(@Nullable Map<ChunkerBiome, ChunkerBiome> biomeMapping) {
        this.biomeMapping = biomeMapping;
    }

    /**
     * Set whether LevelDB should compact after writing.
     *
     * @param levelDBCompaction true if it should compact (Java only).
     */
    public void setLevelDBCompaction(boolean levelDBCompaction) {
        this.levelDBCompaction = levelDBCompaction;
    }

    /**
     * Set whether in-game maps should be converted.
     *
     * @param processMaps true if they should be converted.
     */
    public void setProcessMaps(boolean processMaps) {
        this.processMaps = processMaps;
    }

    /**
     * Set whether biome blending should be avoided (Java only).
     *
     * @param preventYBiomeBlending true if it should be avoided.
     */
    public void setPreventYBiomeBlending(boolean preventYBiomeBlending) {
        this.preventYBiomeBlending = preventYBiomeBlending;
    }

    /**
     * Set whether items should be converted otherwise air will be used.
     *
     * @param processItems true if they should be converted.
     */
    public void setProcessItems(boolean processItems) {
        this.processItems = processItems;
    }

    /**
     * Set whether entities should be converted.
     *
     * @param processEntities true if they should be converted.
     */
    public void setProcessEntities(boolean processEntities) {
        this.processEntities = processEntities;
    }

    /**
     * Set whether block-entities should be converted.
     *
     * @param processBlockEntities true if they should be converted.
     */
    public void setProcessBlockEntities(boolean processBlockEntities) {
        this.processBlockEntities = processBlockEntities;
    }

    /**
     * Set whether container loot tables should be converted.
     *
     * @param processLootTables true if they should be converted.
     */
    public void setProcessLootTables(boolean processLootTables) {
        this.processLootTables = processLootTables;
    }

    /**
     * Set whether biomes should be converted.
     *
     * @param processBiomes true if they should be converted.
     */
    public void setProcessBiomes(boolean processBiomes) {
        this.processBiomes = processBiomes;
    }

    /**
     * Set whether the height-map should be converted.
     *
     * @param processHeightMap true if it should be converted.
     */
    public void setProcessHeightMap(boolean processHeightMap) {
        this.processHeightMap = processHeightMap;
    }

    /**
     * Set whether lighting should be converted.
     *
     * @param processLighting true if it should be converted.
     */
    public void setProcessLighting(boolean processLighting) {
        this.processLighting = processLighting;
    }

    /**
     * Set whether this conversion allows copying of NBT from input to output.
     *
     * @param allowNBTCopying true if the output supports the same NBT as the input.
     */
    public void setAllowNBTCopying(boolean allowNBTCopying) {
        this.allowNBTCopying = allowNBTCopying;
    }

    /**
     * Set whether empty chunks should be removed.
     *
     * @param discardEmptyChunks true if they should be removed.
     */
    public void setDiscardEmptyChunks(boolean discardEmptyChunks) {
        this.discardEmptyChunks = discardEmptyChunks;
    }

    /**
     * Set whether pre-transform should be used (block-connections and other neighbour chunk fetching).
     *
     * @param processColumnPreTransform true if it should be enabled.
     */
    public void setProcessColumnPreTransform(boolean processColumnPreTransform) {
        this.processColumnPreTransform = processColumnPreTransform;
    }

    /**
     * Set whether custom identifiers should be allowed.
     *
     * @param customIdentifiers true if custom identifiers should be converted.
     */
    public void setCustomIdentifiers(boolean customIdentifiers) {
        this.customIdentifiers = customIdentifiers;
    }

    @Override
    public boolean shouldLevelDBCompaction() {
        return levelDBCompaction;
    }

    @Override
    public boolean shouldProcessMaps() {
        return processMaps;
    }

    @Override
    public boolean shouldProcessItems() {
        return processItems;
    }

    @Override
    public boolean shouldProcessEntities() {
        return processEntities;
    }

    @Override
    public boolean shouldProcessBlockEntities() {
        return processBlockEntities;
    }

    @Override
    public boolean shouldProcessLootTables() {
        return processLootTables;
    }

    @Override
    public boolean shouldProcessBiomes() {
        return processBiomes;
    }

    @Override
    public boolean shouldProcessHeightMap() {
        return processHeightMap;
    }

    @Override
    public boolean shouldProcessColumnPreTransform() {
        return processColumnPreTransform;
    }

    @Override
    public boolean shouldProcessLighting() {
        return processLighting;
    }

    @Override
    public boolean shouldPreventYBiomeBlending() {
        return preventYBiomeBlending;
    }

    @Override
    public boolean shouldProcessDimension(Dimension dimension) {
        return getNewDimension(dimension).isPresent();
    }

    /**
     * Whether this converter was cancelled.
     *
     * @return true if it was cancelled.
     */
    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Whether any exceptions occurred and were logged during conversion.
     *
     * @return true if any exceptions occurred.
     */
    public boolean isExceptions() {
        return exceptions;
    }

    /**
     * Get a map of all the missing identifiers found during conversion.
     *
     * @return the backing map of missing identifiers sorted by the type.
     */
    public Multimap<MissingMappingType, String> getMissingIdentifiers() {
        return missingIdentifiers;
    }

    @Override
    public void countUnmappedBlockInstance(String identifier) {
        unmappedBlockInstances.computeIfAbsent(identifier, ignored -> new LongAdder()).increment();
    }

    @Override
    public void countSubstitution(String source, String target) {
        substitutions.computeIfAbsent(source + "\u0000" + target, ignored -> new LongAdder()).increment();
    }

    /**
     * Get the blocks that were written as something else, largest first.
     *
     * @return a map whose key is the source block, a NUL, then the block it became, mapped to how many blocks
     *         changed. The NUL keeps the two names in one key without needing a separator block names could contain.
     */
    public Map<String, Long> getSubstitutions() {
        Map<String, Long> sorted = new LinkedHashMap<>();
        substitutions.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue().sum()));
        return sorted;
    }

    /**
     * Get how many blocks were dropped for want of a mapping, per block identifier, largest first.
     *
     * @return a map of block identifier to the number of blocks that became air, ordered by descending count.
     */
    public Map<String, Long> getUnmappedBlockInstances() {
        Map<String, Long> sorted = new LinkedHashMap<>();
        unmappedBlockInstances.entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue().sum(), left.getValue().sum()))
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue().sum()));
        return sorted;
    }

    /**
     * Get the total number of blocks that were dropped for want of a mapping.
     *
     * @return the total count.
     */
    public long getUnmappedBlockInstanceTotal() {
        long total = 0;
        for (LongAdder adder : unmappedBlockInstances.values()) {
            total += adder.sum();
        }
        return total;
    }

    /**
     * Set whether the world should be measured and moved vertically so its lowest block lands on Y=0.
     *
     * @param shiftToFit true to shift the world before writing.
     */
    public void setShiftToFit(boolean shiftToFit) {
        this.shiftToFit = shiftToFit;
    }

    /**
     * Whether the world will be moved vertically before being written.
     *
     * @return true if shifting is enabled.
     */
    public boolean shouldShiftToFit() {
        return shiftToFit;
    }

    /**
     * Get the result of the most recent survey, which describes how far the world moved and what that cost.
     *
     * @return the survey result or null if no survey has been run.
     */
    @Nullable
    public SurveyResult getSurveyResult() {
        return surveyResult;
    }

    /**
     * Get the raw measurements from the most recent survey, including the per-block-type tallies.
     *
     * @return the survey or null if no survey has been run.
     */
    @Nullable
    public BlockSurvey getCurrentSurvey() {
        return currentSurvey;
    }

    /**
     * Get the totals for anything the shift had to leave behind.
     *
     * @return the shift statistics, which accumulate as the world is written.
     */
    public ShiftStats getShiftStats() {
        return shiftStats;
    }

    /**
     * Set whether container contents should be emptied while keeping the containers themselves.
     *
     * @param clearContainers true to drop the contents of chests, furnaces and the like.
     */
    public void setClearContainers(boolean clearContainers) {
        this.clearContainers = clearContainers;
    }

    /**
     * Whether container contents will be emptied.
     *
     * @return true if contents are dropped.
     */
    public boolean shouldClearContainers() {
        return clearContainers;
    }

    /**
     * Set whether paintings and item frames should survive while other entities are removed.
     *
     * @param keepHangingEntities true to keep wall decorations.
     */
    public void setKeepHangingEntities(boolean keepHangingEntities) {
        this.keepHangingEntities = keepHangingEntities;
    }

    /**
     * Whether paintings and item frames will be kept.
     *
     * @return true if wall decorations survive.
     */
    public boolean shouldKeepHangingEntities() {
        return keepHangingEntities;
    }

    /**
     * Get how many entities were discarded for having no place in the target version.
     *
     * @return the number of removed entities.
     */
    public int getEntitiesRemoved() {
        return entitiesRemoved.get();
    }

    /**
     * Get how many container items were discarded.
     *
     * @return the number of removed items.
     */
    public int getContainerItemsRemoved() {
        return containerItemsRemoved.get();
    }

    /**
     * Read the world once purely to measure it, without writing anything.
     * <p>
     * This has to run as a separate pass because the target format drops out-of-range sections silently, so the
     * decision about how far to move the world is needed before any column is written. The pass walks every
     * region but discards each column immediately, so it costs one extra read rather than extra memory.
     *
     * @param reader      the reader for the world being converted.
     * @param countBlocks whether to tally every block type as well. Counting is what lets the report say "this
     *                    build was 90% stone", but it walks every mixed section, so it is worth skipping when
     *                    only the vertical shift is needed.
     * @return how far the world has to move and what that move costs.
     */
    public SurveyResult survey(@NotNull LevelReader reader, boolean countBlocks) {
        BlockSurvey survey = new BlockSurvey();
        // The measuring pass must not itself be shifted, otherwise it would measure the already-moved result and a
        // second survey would report a different answer than the first. Clearing the previous result for the
        // duration of the pass is what keeps the pass honest.
        SurveyResult previous = surveyResult;
        surveyResult = null;
        try {
            TrackedTask<Void> task = convert(reader, new SurveyLevelWriter(survey, countBlocks));
            task.future().join();
        } finally {
            surveyResult = previous;
        }
        SurveyResult result = survey.finish(OUTPUT_MIN_SECTION_Y, OUTPUT_MAX_SECTION_Y);
        this.surveyResult = result;
        this.currentSurvey = survey;
        return result;
    }

    @Override
    public boolean shouldProcessRegion(Dimension dimension, RegionCoordPair regionPair) {
        if (pruningConfigs == null || pruningConfigs.isEmpty()) return true;

        PruningConfig pruningConfig = pruningConfigs.get(dimension.getIdentifier());

        // Ensure the config / regions are present
        if (pruningConfig == null || pruningConfig.getRegions() == null || pruningConfig.getRegions().isEmpty())
            return true;

        ChunkCoordPair minRegionChunk = regionPair.getChunk(0, 0);
        ChunkCoordPair maxRegionChunk = regionPair.getChunk(31, 31);

        for (PruningRegion region : pruningConfig.getRegions()) {
            if (pruningConfig.isInclude()) {
                // If the region overlaps then we should process the region
                boolean overlap = maxRegionChunk.chunkX() >= region.getMinChunkX() &&
                        minRegionChunk.chunkX() <= region.getMaxChunkX() &&
                        maxRegionChunk.chunkZ() >= region.getMinChunkZ() &&
                        minRegionChunk.chunkZ() <= region.getMaxChunkZ();
                if (overlap) return true;
            } else {
                // Ensure the region is fully inside the exclusion zone before returning false
                boolean fullyContained = minRegionChunk.chunkX() >= region.getMinChunkX() &&
                        maxRegionChunk.chunkX() <= region.getMaxChunkX() &&
                        minRegionChunk.chunkZ() >= region.getMinChunkZ() &&
                        maxRegionChunk.chunkZ() <= region.getMaxChunkZ();
                if (fullyContained) return false;
            }
        }

        return !pruningConfig.isInclude();
    }

    @Override
    public boolean shouldProcessColumn(Dimension dimension, ChunkCoordPair columnPair) {
        if (pruningConfigs == null || pruningConfigs.isEmpty()) return true;

        PruningConfig pruningConfig = pruningConfigs.get(dimension.getIdentifier());

        // Ensure the config / regions are present
        if (pruningConfig == null || pruningConfig.getRegions() == null || pruningConfig.getRegions().isEmpty())
            return true;

        // Find any matching area
        for (PruningRegion region : pruningConfig.getRegions()) {
            if (columnPair.chunkX() >= region.getMinChunkX() && columnPair.chunkX() <= region.getMaxChunkX() &&
                    columnPair.chunkZ() >= region.getMinChunkZ() && columnPair.chunkZ() <= region.getMaxChunkZ()) {
                return pruningConfig.isInclude();
            }
        }
        return !pruningConfig.isInclude();
    }

    @Override
    public boolean shouldAllowNBTCopying() {
        return allowNBTCopying;
    }

    @Override
    public boolean shouldAllowCustomIdentifiers() {
        return customIdentifiers;
    }

    @Override
    @Nullable
    public MappingsFileResolvers getBlockMappings() {
        return blockMappings;
    }

    @Override
    public DimensionRegistry getDimensionRegistry() {
        return dimensionRegistry;
    }

    /**
     * Set the block mappings to use.
     *
     * @param blockMappings the mappings to use or null if not present.
     */
    public void setBlockMappings(@Nullable MappingsFileResolvers blockMappings) {
        this.blockMappings = blockMappings;
    }

    @Override
    public boolean shouldDiscardEmptyChunks() {
        return discardEmptyChunks;
    }

    @Override
    public Optional<Dimension> getNewDimension(Dimension dimension) {
        return dimensionMapping == null ? Optional.of(dimension)
                : Optional.ofNullable(dimensionMapping.get(dimension.getIdentifier())).map(dimensionRegistry::getByIdentifier);
    }

    @Override
    public ChunkerBiome getNewBiome(ChunkerBiome biome) {
        return biomeMapping == null ? biome : biomeMapping.getOrDefault(biome, biome);
    }

    @Override
    public void logNonFatalException(Throwable throwable) {
        exceptions = true;

        // Log to console
        Converter.super.logNonFatalException(throwable);
    }

    /**
     * Log a fatal exception and cancel conversion.
     *
     * @param throwable the throwable being logged.
     */
    public void logFatalException(Throwable throwable) {
        // Cancel
        cancel(throwable);

        // Call the non-fatal logger
        logNonFatalException(throwable);
    }

    /**
     * Handle a signal sent by the tasks.
     *
     * @param signalName  the name of the signal.
     * @param signalValue the value that is being signalled.
     */
    public void handleSignal(String signalName, Object signalValue) {
        // If it's the compaction signal forward it to the handler
        if (signalName.equals(SIGNAL_COMPACTION)) {
            if (compactionSignalConsumer == null) return;
            compactionSignalConsumer.accept((Boolean) signalValue);
        }
    }

    @Override
    public void logMissingMapping(MissingMappingType type, String identifier) {
        // Add to map
        if (missingIdentifiers.put(type, identifier)) {
            // Log if it's new
            Converter.super.logMissingMapping(type, identifier);
        }
    }

    @Override
    public Optional<ChunkerLevel> level() {
        return Optional.ofNullable(level);
    }

    /**
     * The settings which should be modified for the level.dat.
     *
     * @return the settings as a key-value object, otherwise null if no changes.
     */
    @Nullable
    public JsonObject getChangedSettings() {
        return changedSettings;
    }

    /**
     * Set the settings which should be applied to the level.dat.
     *
     * @param changedSettings the changed settings.
     */
    public void setChangedSettings(@Nullable JsonObject changedSettings) {
        this.changedSettings = changedSettings;
    }

    /**
     * Set the maps used for the world.
     *
     * @param maps the list of maps for the world.
     */
    public void setMaps(@Nullable List<ChunkerMap> maps) {
        this.maps = maps;
    }

    /**
     * Create and start the conversion task.
     *
     * @param reader the reader to use for conversion.
     * @param writer the writer to use for conversion.
     * @return a task which completes when the conversion completes.
     */
    public TrackedTask<Void> convert(@NotNull LevelReader reader, @NotNull LevelWriter writer) {
        // Create the environment
        this.reader = reader;
        this.writer = writer;
        cancelled = false;
        exceptions = false;
        missingIdentifiers.clear();
        environment = Task.environment("World Conversion", 8, this::logFatalException, this::handleSignal);

        try {
            // Create the handler that calls the writer
            LevelWriterConversionHandler writerHandler = new LevelWriterConversionHandler(writer);

            // Create the conversion pipeline (it always uses the writer as a base)
            Pipeline pipeline = new Pipeline(writerHandler);

            // Add handler for the level, this handles saving the level to the world converter instance
            level = null;
            pipeline.levelHandlers((delegate) -> new LevelHandler(this, delegate));

            // Add handler for the world, handles the remapping of dimensions
            pipeline.worldHandlers((delegate, level) -> new WorldHandler(this, delegate));

            // Pre-transforming is allowing columns to be processed together
            // If it's enabled, we need to hold the chunks using the handler
            // The Reader is responsible for solving which edges are needed
            // But we need to call the writer PreTransformManager ourselves as it's before writing.
            if (shouldProcessColumnPreTransform()) {
                // Add the pre-transform writer conversion handler, this ensures columns know which edges are needed
                // Add pre-transform to the pipeline (this is required to handle processes that need adjacent chunks)
                pipeline.columnHandlers(
                        (delegate, world) -> new ColumnPreTransformWriterConversionHandler(
                                writer::getPreTransformManager,
                                delegate,
                                true
                        ),
                        ColumnPreTransformConversionHandler::new
                );
            } else {
                // Add the writer handler, this ensures that the writer is still called just without connected chunks
                pipeline.columnHandlers(
                        (delegate, world) -> new ColumnPreTransformWriterConversionHandler(
                                writer::getPreTransformManager,
                                delegate,
                                false
                        )
                );
            }

            // Move the world vertically if a survey decided it needs it. This must sit before the writer in the
            // chain: the writer discards sections outside the output's height range without reporting them, so a
            // world starting below Y=0 would silently produce an empty map.
            if (shiftToFit && surveyResult != null) {
                final int shiftSections = surveyResult.shiftSections();
                pipeline.addColumnHandler((delegate, world) -> new ShiftColumnHandler(delegate, shiftSections, OUTPUT_MIN_SECTION_Y, OUTPUT_MAX_SECTION_Y, shiftStats));
            }

            // Drop the entities and container contents which have no place in a build-only conversion. Counting is
            // done through shared totals because each world (dimension) builds its own handler.
            pipeline.addColumnHandler((delegate, world) -> new EntityFilterHandler(delegate, keepHangingEntities, entitiesRemoved));
            pipeline.addColumnHandler((delegate, world) -> new ContainerClearHandler(delegate, clearContainers, containerItemsRemoved));

            // Get the composed handler to use for conversion
            LevelConversionHandler handler = pipeline.build();

            // Level reading
            Task.asyncConsume("Reading Level", TaskWeight.NORMAL, reader::readLevel, handler);

            // Return the environment to allow for progress tracking
            return environment;
        } finally {
            environment.close(); // Close indicates that we're done scheduling the base tasks

            // Ensure free is called for the reader & writer (always)
            environment.setFreeCallback(() -> {
                // Free reader
                try {
                    reader.free();
                } catch (Throwable e) {
                    try {
                        logNonFatalException(e);
                    } catch (Throwable e2) {
                        // We tried, this is likely an OOM
                    }
                }

                // Free writer
                try {
                    writer.free();
                } catch (Throwable e) {
                    try {
                        logNonFatalException(e);
                    } catch (Throwable e2) {
                        // We tried, this is likely an OOM
                    }
                }
            });
        }

    }

    /**
     * Cancel the conversion task.
     *
     * @param fatalException an exception to use as the reason for the future.
     * @return the environment future which is complete when the environment has fully cancelled.
     */
    public CompletableFuture<Void> cancel(@Nullable Throwable fatalException) {
        cancelled = true;

        // Cancel the environment with the exception
        if (environment != null) {
            environment.cancel(fatalException);

            // Return the future for the environment (useful for waiting)
            return environment.future();
        } else {
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * WorldConversionHandler which applies dimension remapping.
     */
    static class WorldHandler implements WorldConversionHandler {
        private final WorldConverter worldConverter;
        private final WorldConversionHandler delegate;

        public WorldHandler(WorldConverter worldConverter, WorldConversionHandler delegate) {
            this.worldConverter = worldConverter;
            this.delegate = delegate;
        }

        @Override
        public Task<ColumnConversionHandler> convertWorld(ChunkerWorld world) {
            // Apply dimension remapping
            Optional<Dimension> newDimension = worldConverter.getNewDimension(world.getDimension());
            if (newDimension.isPresent()) {
                world.setDimension(newDimension.get());
                return delegate.convertWorld(world);
            }

            // Dimension no longer exists
            return Task.asyncUnwrap("Empty Dimension", TaskWeight.LOW, () -> null);
        }

        @Override
        public void flushWorld(ChunkerWorld world) {
            delegate.flushWorld(world);
        }

        @Override
        public void flushWorlds() {
            delegate.flushWorlds();
        }
    }

    /**
     * LevelHandler that integrates with WorldConverter:
     * - Replacing the maps with specified ones.
     * - Moving maps and portals to the correct dimension.
     * - Applies any changed settings to the level.dat.
     * - Saves the level to the WorldConverter for context.
     */
    static class LevelHandler implements LevelConversionHandler {
        private final WorldConverter worldConverter;
        private final LevelConversionHandler delegate;

        public LevelHandler(WorldConverter worldConverter, LevelConversionHandler delegate) {
            this.worldConverter = worldConverter;
            this.delegate = delegate;
        }

        @Override
        public Task<WorldConversionHandler> convertLevel(ChunkerLevel level) {
            // Apply the maps to the level
            if (worldConverter.maps != null) {
                level.setMaps(worldConverter.maps);
            }

            // Apply dimension remapping to maps
            level.getMaps().removeIf(map -> {
                Optional<Dimension> newDimension = worldConverter.getNewDimension(map.getDimension());
                if (newDimension.isPresent()) {
                    map.setDimension(newDimension.get());
                    return false;
                } else {
                    return true; // Dimension no longer exists
                }
            });

            // Apply dimension remapping to portals
            level.getPortals().removeIf(portal -> {
                Optional<Dimension> newDimension = worldConverter.getNewDimension(portal.getDimension());
                if (newDimension.isPresent()) {
                    portal.setDimension(newDimension.get());
                    return false;
                } else {
                    return true; // Dimension no longer exists
                }
            });

            // Turn the current settings to JSON
            JsonObject baseSettings = level.getSettings().toJSON();

            // Apply any changed settings
            if (worldConverter.getChangedSettings() != null) {
                baseSettings.asMap().putAll(worldConverter.getChangedSettings().asMap());
            }

            // Save settings (turning them from json to object)
            level.setSettings(ChunkerLevelSettings.fromJSON(baseSettings));

            // Save level to converter
            worldConverter.level = level;

            // Call delegate so it's converted
            return delegate.convertLevel(level);
        }

        @Override
        public void flushLevel() {
            delegate.flushLevel();
        }
    }
}
