package com.hivemc.chunker.downgrade;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hivemc.chunker.conversion.encoding.java.base.writer.IncrementalWriter;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.conversion.encoding.base.Converter;
import com.hivemc.chunker.conversion.encoding.base.Version;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.intermediate.column.chunk.identifier.ChunkerBlockIdentifier;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Produces the conversion report.
 * <p>
 * The point of this file is accountability: the tool deliberately throws things away - unmapped blocks become air,
 * anything above the height limit is trimmed, worlds are moved vertically - and none of that is visible in the
 * output world itself. Without a report the user sees holes in a build and has no way to tell whether that is a
 * bug, a missing mapping, or the height limit doing its job.
 */
public final class ConversionReport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private ConversionReport() {
    }

    /**
     * Build the report for a finished conversion.
     *
     * @param converter the converter which performed the conversion.
     * @param reader    the reader used for the source world.
     * @param writer    the writer used for the output world.
     * @return the report as JSON.
     */
    public static JsonObject build(WorldConverter converter, LevelReader reader, LevelWriter writer) {
        JsonObject report = new JsonObject();

        // What was converted into what.
        JsonObject versions = new JsonObject();
        versions.addProperty("sourceFormat", reader.getEncodingType().getName());
        versions.addProperty("sourceVersion", String.valueOf(reader.getVersion()));
        versions.addProperty("targetFormat", writer.getEncodingType().getName());
        versions.addProperty("targetVersion", String.valueOf(writer.getVersion()));
        report.add("versions", versions);

        // How the world was moved, and what that cost.
        report.add("shift", buildShift(converter));

        // What the world is made of.
        report.add("blocks", buildBlocks(converter));

        // What could not be mapped and was therefore replaced or dropped.
        report.add("unmapped", buildUnmapped(converter));

        // What was kept, but as a different block. This is the list that answers "what happened to my map" - the
        // built-in substitutions are a general table, whereas these are the blocks that actually changed here.
        report.add("substitutions", buildSubstitutions(converter));

        // What was deliberately dropped because the target version has no use for it.
        report.add("removed", buildRemoved(converter));

        // How much of the map actually had to be rewritten. When the same world is converted into the same folder
        // again after editing one mapping, almost every chunk comes out identical to what is already there and is
        // skipped - which is what lets the preview re-render only the handful that changed. Reporting the counts
        // turns "the preview updated straight away" into something the user can see the reason for.
        report.add("changed", buildChanged());

        return report;
    }

    /**
     * How many chunks were rewritten versus left as they were.
     *
     * @return the counts as JSON.
     */
    private static JsonObject buildChanged() {
        JsonObject changed = new JsonObject();
        changed.addProperty("chunksWritten", IncrementalWriter.getWritten());
        changed.addProperty("chunksUnchanged", IncrementalWriter.getUnchanged());
        return changed;
    }

    private static JsonObject buildRemoved(WorldConverter converter) {
        JsonObject removed = new JsonObject();
        removed.addProperty(
                "entities",
                converter.getEntitiesRemoved()
        );
        removed.addProperty(
                "containerItems",
                converter.getContainerItemsRemoved()
        );
        removed.addProperty("keepHangingEntities", converter.shouldKeepHangingEntities());
        removed.addProperty(
                "note",
                "Mobs, dropped items and other entities are dropped: this tool carries build appearance, not " +
                        "inhabitants. Paintings and item frames are kept as decorations, and containers keep their " +
                        "block but lose their contents because modern items often have no target equivalent."
        );
        return removed;
    }

    /**
     * List the blocks that were written as a different block, largest first.
     * <p>
     * This is the answer to "what did you do to my map". A general table of the tool's substitutions says nothing
     * about whether the build in front of the user will still look right; this lists only what actually changed here,
     * so the entries worth overriding are the ones at the top.
     *
     * @param converter the converter which performed the conversion.
     * @return the substitutions as JSON.
     */
    private static JsonObject buildSubstitutions(WorldConverter converter) {
        JsonObject substitutions = new JsonObject();
        JsonArray changes = new JsonArray();
        long total = 0;

        for (Map.Entry<String, Long> entry : converter.getSubstitutions().entrySet()) {
            // The key holds the source block, then a NUL, then the block it became.
            String[] parts = entry.getKey().split("\u0000", 2);
            if (parts.length != 2) continue;

            JsonObject change = new JsonObject();
            change.addProperty("from", parts[0]);
            change.addProperty("to", parts[1]);
            change.addProperty("count", entry.getValue());
            changes.add(change);
            total += entry.getValue();
        }

        substitutions.addProperty("totalBlocksChanged", total);
        substitutions.add("changes", changes);
        substitutions.addProperty(
                "note",
                "These blocks exist in the target version, so they were kept - but as a different block, because " +
                        "the original has no equivalent. Add a mapping to decide what any of them becomes instead."
        );
        return substitutions;
    }

    private static JsonObject buildShift(WorldConverter converter) {
        JsonObject shift = new JsonObject();
        SurveyResult result = converter.getSurveyResult();
        if (result == null) {
            shift.addProperty("applied", false);
            return shift;
        }

        shift.addProperty("applied", result.requiresShift());
        shift.addProperty("amountY", result.shiftY());
        shift.addProperty("amountSections", result.shiftSections());
        shift.addProperty("sourceLowestBlockY", result.lowestBlockY());
        shift.addProperty("sourceHighestSectionY", result.highestSectionY());
        shift.addProperty("resultLowestBlockY", result.projectedLowestY());
        shift.addProperty("resultHighestBlockY", result.projectedHighestY());
        shift.addProperty("sectionsAboveLimit", result.clippedSections());
        shift.addProperty("contentLostToHeightLimit", result.clipped());

        ShiftStats stats = converter.getShiftStats();
        shift.addProperty("sectionsMoved", stats.getShiftedSections());
        shift.addProperty("sectionsDropped", stats.getClippedSections());
        shift.addProperty("blockEntitiesDropped", stats.getDroppedBlockEntities());
        shift.addProperty("entitiesDropped", stats.getDroppedEntities());
        return shift;
    }

    private static JsonObject buildBlocks(WorldConverter converter) {
        JsonObject blocks = new JsonObject();
        BlockSurvey survey = converter.getCurrentSurvey();
        if (survey == null) {
            blocks.addProperty("totalBlocks", 0);
            blocks.addProperty("distinctTypes", 0);
            blocks.add("byType", new JsonArray());
            return blocks;
        }

        Map<ChunkerBlockIdentifier, Long> counts = survey.getBlockCounts();
        blocks.addProperty("totalBlocks", survey.getTotalBlocks());
        blocks.addProperty("distinctTypes", counts.size());

        JsonArray byType = new JsonArray();
        long limit = 400;
        for (Map.Entry<ChunkerBlockIdentifier, Long> entry : counts.entrySet()) {
            if (byType.size() >= limit) break;
            ChunkerBlockIdentifier identifier = entry.getKey();
            JsonObject item = new JsonObject();
            item.addProperty("block", identifier.getType().toString().toLowerCase(java.util.Locale.ROOT));
            item.addProperty("states", identifier.toStateString());
            item.addProperty("count", entry.getValue());
            byType.add(item);
        }
        blocks.add("byType", byType);
        blocks.addProperty("byTypeTruncated", counts.size() > limit);
        return blocks;
    }

    private static JsonObject buildUnmapped(WorldConverter converter) {
        JsonObject unmapped = new JsonObject();
        int total = 0;

        for (Map.Entry<Converter.MissingMappingType, Collection<String>> entry : converter.getMissingIdentifiers().asMap().entrySet()) {
            JsonArray values = new JsonArray();
            for (String value : entry.getValue()) {
                values.add(value);
            }
            total += values.size();
            unmapped.add(entry.getKey().getName(), values);
        }

        unmapped.addProperty("distinctUnmapped", total);

        // How many blocks were actually lost, not just how many kinds. A build that loses three stray blocks and a
        // build that loses its entire staircase produce the same distinct count, so the count alone cannot tell the
        // user which blocks are worth writing a mapping for. Largest sources of loss first.
        long instances = converter.getUnmappedBlockInstanceTotal();
        unmapped.addProperty("blocksLost", instances);
        JsonArray worst = new JsonArray();
        long limit = 200;
        for (Map.Entry<String, Long> entry : converter.getUnmappedBlockInstances().entrySet()) {
            if (worst.size() >= limit) break;
            JsonObject item = new JsonObject();
            item.addProperty("block", entry.getKey());
            item.addProperty("count", entry.getValue());
            worst.add(item);
        }
        unmapped.add("blocksLostByType", worst);
        unmapped.addProperty(
                "note",
                "Blocks listed here have no mapping for the target version and were replaced with air. " +
                        "Use blocksLostByType to see which ones cost the most blocks - a type can be listed once " +
                        "while covering thousands of blocks. Add them to a mappings file to control what they " +
                        "become instead."
        );
        return unmapped;
    }

    /**
     * Write the report next to the converted world.
     *
     * @param converter the converter which performed the conversion.
     * @param reader    the reader used for the source world.
     * @param writer    the writer used for the output world.
     * @param output    the output directory the world was written to.
     * @return the file the report was written to.
     * @throws IOException if the report could not be written.
     */
    public static File write(WorldConverter converter, LevelReader reader, LevelWriter writer, File output) throws IOException {
        JsonObject report = build(converter, reader, writer);
        File target = new File(output, "conversion-report.json");
        Files.write(
                target.toPath(),
                GSON.toJson(report).getBytes(StandardCharsets.UTF_8)
        );

        // A human readable summary alongside the JSON.
        File textTarget = new File(output, "conversion-report.txt");
        Files.write(textTarget.toPath(), summary(report).getBytes(StandardCharsets.UTF_8));
        return target;
    }

    /**
     * Render a short plain-text summary of the report.
     *
     * @param report the report JSON.
     * @return the summary text.
     */
    public static String summary(JsonObject report) {
        StringBuilder builder = new StringBuilder();
        JsonObject versions = report.getAsJsonObject("versions");
        builder.append("Conversion report").append(System.lineSeparator());
        builder.append("  Source: ").append(versions.get("sourceFormat").getAsString())
                .append(' ').append(versions.get("sourceVersion").getAsString()).append(System.lineSeparator());
        builder.append("  Target: ").append(versions.get("targetFormat").getAsString())
                .append(' ').append(versions.get("targetVersion").getAsString()).append(System.lineSeparator());

        JsonObject shift = report.getAsJsonObject("shift");
        if (shift.get("applied").getAsBoolean()) {
            builder.append("  World moved up by ").append(shift.get("amountY").getAsInt()).append(" blocks")
                    .append(System.lineSeparator());
        } else {
            builder.append("  World was not moved").append(System.lineSeparator());
        }
        if (shift.get("contentLostToHeightLimit").getAsBoolean()) {
            builder.append("  WARNING: ").append(shift.get("sectionsAboveLimit").getAsInt())
                    .append(" section(s) exceeded the height limit and were dropped")
                    .append(System.lineSeparator());
        }

        JsonObject blocks = report.getAsJsonObject("blocks");
        builder.append("  Total blocks: ").append(blocks.get("totalBlocks").getAsLong())
                .append(" across ").append(blocks.get("distinctTypes").getAsInt()).append(" types")
                .append(System.lineSeparator());

        JsonObject unmapped = report.getAsJsonObject("unmapped");
        int unmappedTypes = unmapped.get("distinctUnmapped").getAsInt();
        long blocksLost = unmapped.has("blocksLost") ? unmapped.get("blocksLost").getAsLong() : 0;
        if (unmappedTypes > 0) {
            builder.append("  NOTE: ").append(unmappedTypes)
                    .append(" identifier(s) had no mapping and were replaced with air (")
                    .append(blocksLost).append(" block(s) lost)")
                    .append(System.lineSeparator());
        }
        if (blocksLost > 0 && unmapped.has("blocksLostByType")) {
            JsonArray worst = unmapped.getAsJsonArray("blocksLostByType");
            int shown = Math.min(5, worst.size());
            for (int i = 0; i < shown; i++) {
                JsonObject item = worst.get(i).getAsJsonObject();
                builder.append("    lost ").append(item.get("count").getAsLong())
                        .append(" x ").append(summarise(item.get("block").getAsString()))
                        .append(System.lineSeparator());
            }
        }

        JsonObject removed = report.getAsJsonObject("removed");
        long entities = removed.get("entities").getAsLong();
        long items = removed.get("containerItems").getAsLong();
        if (entities > 0 || items > 0) {
            builder.append("  Dropped ").append(entities).append(" entity/entities and ")
                    .append(items).append(" container item(s)")
                    .append(System.lineSeparator());
        }
        return builder.toString();
    }

    /**
     * Reduce a block identifier to a short readable name for the text summary.
     * <p>
     * The identifiers arriving here have the shape
     * {@code ChunkerBlockIdentifier{type=WARPED_STAIRS, states=facing=NORTH,...}}, which is precise but unreadable
     * in a summary meant to be skimmed. Only the block name is kept, because the states are not what the user is
     * choosing between at this point.
     *
     * @param identifier the raw identifier.
     * @return a short lowercase name such as {@code warped_stairs}.
     */
    private static String summarise(String identifier) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("type=([A-Z0-9_]+)").matcher(identifier);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase(java.util.Locale.ROOT);
        }
        return identifier;
    }
}
