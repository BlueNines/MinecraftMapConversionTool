package com.hivemc.chunker.cli;

import com.google.common.base.Stopwatch;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.hivemc.chunker.cli.messenger.Messenger;
import com.hivemc.chunker.cli.messenger.messaging.DimensionPruningList;
import com.hivemc.chunker.conversion.WorldConverter;
import com.hivemc.chunker.downgrade.Approximations;
import com.hivemc.chunker.downgrade.ConversionReport;
import com.hivemc.chunker.downgrade.SurveyResult;
import com.hivemc.chunker.conversion.encoding.EncodingType;
import com.hivemc.chunker.conversion.encoding.base.reader.LevelReader;
import com.hivemc.chunker.conversion.encoding.base.writer.LevelWriter;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerBiome;
import com.hivemc.chunker.conversion.intermediate.column.biome.ChunkerCustomBiome;
import com.hivemc.chunker.conversion.intermediate.world.DimensionRegistry;
import com.hivemc.chunker.mapping.DimensionMapping;
import com.hivemc.chunker.mapping.DimensionMappingList;
import com.hivemc.chunker.mapping.MappingsFile;
import com.hivemc.chunker.mapping.resolver.MappingsFileResolvers;
import com.hivemc.chunker.scheduling.task.TrackedTask;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * This class is the entry-point for the command-line version of Chunker.
 * Example usage: java -jar Chunker.jar --inputDirectory myWorld --outputFormat JAVA_1_20_5 --outputDirectory converted
 * If no arguments are provided then usage is printed.
 * <p/>
 * Mappings can be put in the world folder and by default these are loaded from if there is no parameter, these are done
 * for the following:
 * - biome_mappings.chunker.json
 * - block_mappings.chunker.json
 * - converter_settings.chunker.json
 * - custom_dimensions.chunker.json
 * - dimension_mappings.chunker.json
 * - pruning.chunker.json
 * - world_settings.chunker.json
 * These can be generated using the Chunker web GUI in the Converter Settings tab.
 */
@CommandLine.Command(name = "Chunker", versionProvider = VersionProvider.class, mixinStandardHelpOptions = true)
public class CLI implements Runnable {
    private static final TypeToken<Map<String, String>> DIMENSION_INPUT_TO_OUTPUT_TYPE = new TypeToken<>() {
    };
    private static final TypeToken<Map<String, String>> BIOME_INPUT_TO_OUTPUT_TYPE = new TypeToken<>() {
    };
    private static final Gson GSON = new Gson();

    @CommandLine.Option(
            names = {"--inputDirectory", "-i"},
            required = true,
            description = "Directory to read the world from."
    )
    private File inputDirectory;

    @CommandLine.Option(
            names = {"--outputFormat", "-f"},
            required = true,
            description = "The format to convert the world to. Use INPUT to match the detected input format (with --keepOriginalNBT to keep retain NBT where possible).",
            converter = EncodingTypeValidator.class
    )
    private String format;

    @CommandLine.Option(
            names = {"--outputDirectory", "-o"},
            required = true,
            description = "Directory to write the world to."
    )
    private File outputDirectory;

    @CommandLine.Option(
            names = {"--blockMappings", "-m"},
            description = "A JSON file/object containing block mappings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile blockMappings;

    @CommandLine.Option(
            names = {"--worldSettings", "-s"},
            description = "A JSON file/object containing world settings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile worldSettings;

    @CommandLine.Option(
            names = {"--pruning", "-p"},
            description = "A JSON file/object containing pruning settings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile pruningSettings;

    @CommandLine.Option(
            names = {"--converterSettings", "-c"},
            description = "A JSON file/object containing converter settings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile converterSettings;

    @CommandLine.Option(
            names = {"--dimensionRegistry", "-r"},
            description = "A JSON file/object containing dimension information.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile dimensionRegistry;

    @CommandLine.Option(
            names = {"--dimensionMappings", "-d"},
            description = "A JSON file/object containing dimension mappings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile dimensionMappings;

    @CommandLine.Option(
            names = {"--biomeMappings", "-b"},
            description = "A JSON file/object containing biome mappings.",
            converter = JsonObjectOrFile.Converter.class
    )
    private JsonObjectOrFile biomeMappings;

    @CommandLine.Option(
            names = {"--keepOriginalNBT", "-k"},
            description = "Whether original NBT should be kept and written to the output world (only works if the output is the same as the input)."
    )
    private boolean keepOriginalNBT;

    @CommandLine.Option(
            names = {"--shiftToFit"},
            description = "Measure the world and shift it upwards so its lowest block lands on Y=0, trimming anything pushed " +
                    "past the target height limit. Required when downgrading worlds from 1.18+ which extend below Y=0, " +
                    "as out-of-range sections are otherwise discarded silently."
    )
    private boolean shiftToFit;

    @CommandLine.Option(
            names = {"--noApproximations"},
            description = "Do not substitute the nearest target-version block for blocks the target version does not have. " +
                    "By default a block such as a wall or a stripped log is kept as the closest equivalent, because " +
                    "otherwise whole categories of build are written as air. Any block supplied with --blockMappings " +
                    "always wins over a substitution."
    )
    private boolean noApproximations;

    /**
     * Main entry point for the CLI
     *
     * @param args the arguments which should be parsed by picocli.
     */
    public static void main(String[] args) {
        // Special case for running the messenger
        if (args.length == 1 && args[0].equals("messenger")) {
            Messenger.main(args);
            return;
        }

        // Parse args
        int exitCode = new CommandLine(new CLI()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        try {
            // Create the converter
            Stopwatch stopwatch = Stopwatch.createStarted();

            // Create the converter
            WorldConverter worldConverter = new WorldConverter(UUID.randomUUID());

            // If any of the mappings file aren't present, check if they're inside the input world

            // Apply block mappings if they're present and parse
            if (blockMappings == null) {
                Path file = inputDirectory.toPath().resolve("block_mappings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        blockMappings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated block mappings.");
                    throw new RuntimeException(e);
                }
            }
            // Apply block mappings if they're present and parse. The built-in approximations are layered underneath the
            // user's own mappings unless they are turned off, because without them whole categories of block - every
            // wall, every non-oak trapdoor, every stripped log - are written as air without a word.
            String userMappings = blockMappings == null ? null : blockMappings.getJSONObjectString();
            if (userMappings != null || !noApproximations) {
                try {
                    String combined = noApproximations
                            ? Approximations.userOnly(userMappings)
                            : Approximations.merge(userMappings);
                    worldConverter.setBlockMappings(new MappingsFileResolvers(MappingsFile.load(combined)));
                } catch (Exception e) {
                    System.err.println("Failed to parse block mappings.");
                    throw new RuntimeException(e);
                }
            }

            // Apply world settings if they're present and parse
            if (worldSettings == null) {
                Path file = inputDirectory.toPath().resolve("world_settings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        worldSettings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated world settings.");
                    throw new RuntimeException(e);
                }
            }
            if (worldSettings != null) {
                try {
                    worldConverter.setChangedSettings(GSON.fromJson(worldSettings.getJSONObjectString(), JsonObject.class));
                } catch (Exception e) {
                    System.err.println("Failed to parse world settings.");
                    throw new RuntimeException(e);
                }
            }

            // Apply dimension registry if they're present and parse
            if (dimensionRegistry == null) {
                Path file = inputDirectory.toPath().resolve("custom_dimensions.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        dimensionRegistry = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated custom dimensions.");
                    throw new RuntimeException(e);
                }
            }
            if (dimensionRegistry != null) {
                try {
                    DimensionMappingList dimensionMapping = GSON.fromJson(dimensionRegistry.getJSONObjectString(), DimensionMappingList.class);
                    if (dimensionMapping.getMappings() != null) {
                        DimensionRegistry registry = worldConverter.getDimensionRegistry();
                        for (DimensionMapping mapping : dimensionMapping.getMappings()) {
                            registry.register(mapping.identifier(), mapping.toDimension(registry.getNextCustomBedrockID()));
                        }
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse custom dimension.");
                    throw new RuntimeException(e);
                }
            }

            // Apply pruning settings if they're present and parse
            if (pruningSettings == null) {
                Path file = inputDirectory.toPath().resolve("pruning.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        pruningSettings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated pruning settings.");
                    throw new RuntimeException(e);
                }
            }
            if (pruningSettings != null) {
                try {
                    DimensionPruningList pruningList = GSON.fromJson(pruningSettings.getJSONObjectString(), DimensionPruningList.class);
                    if (pruningList.getConfigs() != null && !pruningList.getConfigs().isEmpty()) {
                        worldConverter.setPruningConfigs(pruningList.getConfigs());
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse pruning settings.");
                    throw new RuntimeException(e);
                }
            }

            // Apply dimension mappings if they're present and parse
            if (dimensionMappings == null) {
                Path file = inputDirectory.toPath().resolve("dimension_mappings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        dimensionMappings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated dimension mappings.");
                    throw new RuntimeException(e);
                }
            }
            if (dimensionMappings != null) {
                try {
                    Map<String, String> rawDimensionMapping = GSON.fromJson(dimensionMappings.getJSONObjectString(), DIMENSION_INPUT_TO_OUTPUT_TYPE);
                    if (rawDimensionMapping != null && !rawDimensionMapping.isEmpty()) {
                        worldConverter.setDimensionMapping(rawDimensionMapping);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse dimension mappings.");
                    throw new RuntimeException(e);
                }
            }

            // Apply biome mappings if they're present and parse
            if (biomeMappings == null) {
                Path file = inputDirectory.toPath().resolve("biome_mappings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        biomeMappings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated biome mappings.");
                    throw new RuntimeException(e);
                }
            }
            if (biomeMappings != null) {
                try {
                    Map<String, String> rawBiomeMapping = GSON.fromJson(biomeMappings.getJSONObjectString(), BIOME_INPUT_TO_OUTPUT_TYPE);
                    final Map<ChunkerBiome, ChunkerBiome> biomeMapping = new Object2ObjectOpenHashMap<>(rawBiomeMapping.size());
                    for (Map.Entry<String, String> entry : rawBiomeMapping.entrySet()) {
                        Optional<? extends ChunkerBiome> src = entry.getKey().startsWith("minecraft:")
                                ? ChunkerBiome.ChunkerVanillaBiome.find(entry.getKey())
                                : Optional.of(new ChunkerCustomBiome(entry.getKey()));

                        Optional<? extends ChunkerBiome> dst = entry.getValue().startsWith("minecraft:")
                                ? ChunkerBiome.ChunkerVanillaBiome.find(entry.getValue())
                                : Optional.of(new ChunkerCustomBiome(entry.getValue()));

                        if (src.isPresent() && dst.isPresent()) {
                            biomeMapping.put(src.get(), dst.get());

                        } else {
                            if (dst.isPresent()) {
                                throw new IllegalArgumentException("Unknown input biome for mapping \"" + entry.getKey() + "\": \"" + entry.getValue() + "\"");
                            } else if (src.isPresent()) {
                                throw new IllegalArgumentException("Unknown output biome for mapping \"" + entry.getKey() + "\": \"" + entry.getValue() + "\"");
                            } else {
                                throw new IllegalArgumentException("Unknown input and output biome for mapping \"" + entry.getKey() + "\": \"" + entry.getValue() + "\"");
                            }
                        }
                    }

                    worldConverter.setBiomeMapping(biomeMapping);
                } catch (Exception e) {
                    System.err.println("Failed to parse biome mappings.");
                    throw new RuntimeException(e);
                }
            }

            // Apply converter settings if they're present and parse
            if (converterSettings == null) {
                Path file = inputDirectory.toPath().resolve("converter_settings.chunker.json");
                try {
                    if (file.toFile().exists()) {
                        converterSettings = new JsonObjectOrFile(file);
                    }
                } catch (Exception e) {
                    System.err.println("Failed to parse integrated converter settings.");
                    throw new RuntimeException(e);
                }
            }
            if (converterSettings != null) {
                try {
                    JsonObject parsedConverterSettings = GSON.fromJson(converterSettings.getJSONObjectString(), JsonObject.class);

                    // Load the settings (the names are based on original Chunker field names)
                    boolean skipMaps = parsedConverterSettings.has("mapConversion") && !parsedConverterSettings.get("mapConversion").getAsBoolean();
                    boolean skipLootTables = parsedConverterSettings.has("lootTableConversion") && !parsedConverterSettings.get("lootTableConversion").getAsBoolean();
                    boolean skipItemConversion = parsedConverterSettings.has("itemConversion") && !parsedConverterSettings.get("itemConversion").getAsBoolean();
                    boolean skipBlockConnections = parsedConverterSettings.has("blockConnections") && !parsedConverterSettings.get("blockConnections").getAsBoolean();
                    boolean enableCompact = !parsedConverterSettings.has("enableCompact") || parsedConverterSettings.get("enableCompact").getAsBoolean();
                    boolean discardEmptyChunks = parsedConverterSettings.has("discardEmptyChunks") && parsedConverterSettings.get("discardEmptyChunks").getAsBoolean();
                    boolean preventYBiomeBlending = parsedConverterSettings.has("preventYBiomeBlending") && parsedConverterSettings.get("preventYBiomeBlending").getAsBoolean();
                    boolean customIdentifiers = !parsedConverterSettings.has("customIdentifiers") || parsedConverterSettings.get("customIdentifiers").getAsBoolean();

                    // Apply the settings
                    worldConverter.setProcessMaps(!skipMaps);
                    worldConverter.setProcessLootTables(!skipLootTables);
                    worldConverter.setProcessItems(!skipItemConversion);
                    worldConverter.setProcessColumnPreTransform(!skipBlockConnections);
                    worldConverter.setLevelDBCompaction(enableCompact);
                    worldConverter.setDiscardEmptyChunks(discardEmptyChunks);
                    worldConverter.setPreventYBiomeBlending(preventYBiomeBlending);
                    worldConverter.setCustomIdentifiers(customIdentifiers);
                } catch (Exception e) {
                    System.err.println("Failed to parse converter settings.");
                    throw new RuntimeException(e);
                }
            }

            // Check for the original NBT option
            worldConverter.setAllowNBTCopying(keepOriginalNBT);

            // Create the reader (note: converter settings cannot be set after this point)
            Optional<? extends LevelReader> reader = EncodingType.findReader(inputDirectory, worldConverter);
            if (reader.isEmpty()) {
                System.err.println("Failed to find suitable reader for the world.");
                return;
            }

            // Create the writer, resolving INPUT to the detected input format now that the reader is known
            Optional<? extends LevelWriter> writer;
            if (format.equalsIgnoreCase("INPUT")) {
                writer = reader.get().getEncodingType().createWriter(outputDirectory, reader.get().getVersion(), worldConverter);
            } else {
                writer = Messenger.findWriter(format, worldConverter, outputDirectory);
            }

            if (writer.isEmpty()) {
                System.err.println("Failed to find suitable writer for the world.");
                return;
            }
            
            System.out.println(MessageFormat.format(
                    "Converting from {0} {1} to {2} {3}",
                    reader.get().getEncodingType().getName(),
                    reader.get().getVersion(),
                    writer.get().getEncodingType().getName(),
                    writer.get().getVersion()
            ));

            // Safety check for NBT copying since it cannot break worlds
            if (worldConverter.shouldAllowNBTCopying()) {
                if (reader.get().getEncodingType() != writer.get().getEncodingType()) {
                    System.err.println("Original NBT is not available for this conversion due to differing formats. Please disable it to continue.");
                    System.exit(0);
                } else {
                    System.out.println("Original NBT will be copied for this world, if you experience issues consider turning this option off as incompatible NBT may be written.");
                }
            }

            // Add the handler for the compaction signal
            worldConverter.setCompactionSignal((started) -> {
                if (started) {
                    System.out.println("Compacting world, this may take a while...");
                } else {
                    System.out.println("Finished compacting world.");
                }
            });

            // Measure the world before writing anything. This has to happen up front: the writer discards
            // sections outside the target height range without reporting them, so a 1.18+ world which starts at
            // Y=-64 would otherwise convert to an entirely empty map. The measurements also feed the report.
            worldConverter.setShiftToFit(shiftToFit);
            System.out.println("Measuring the world...");
            SurveyResult survey = worldConverter.survey(reader.get(), true);
            System.out.println(MessageFormat.format(
                    "Found {0} block(s) across {1} type(s). Lowest block Y={2}, highest section Y={3}.",
                    worldConverter.getCurrentSurvey() == null ? 0L : worldConverter.getCurrentSurvey().getTotalBlocks(),
                    worldConverter.getCurrentSurvey() == null ? 0 : worldConverter.getCurrentSurvey().getBlockCounts().size(),
                    survey.lowestBlockY(),
                    survey.highestSectionY()
            ));
            if (survey.requiresShift()) {
                System.out.println(MessageFormat.format(
                        "The world will be shifted up {0} blocks ({1} sections) to fit the target height range.",
                        survey.shiftY(),
                        survey.shiftSections()
                ));
            } else if (survey.lowestBlockY() < 0 && !shiftToFit) {
                // Worth calling out: without the shift these blocks cannot be represented at all, and the writer
                // would drop them silently, leaving the user with a map full of holes and no explanation.
                System.out.println(MessageFormat.format(
                        "Warning: this world extends down to Y={0}, which the target version cannot store. " +
                                "Those blocks will be lost. Re-run with --shiftToFit to move the world into range.",
                        survey.lowestBlockY()
                ));
            }
            if (survey.clipped()) {
                System.out.println(MessageFormat.format(
                        "Warning: {0} section(s) will be trimmed off the top as they exceed the target height limit.",
                        survey.clippedSections()
                ));
            }

            // Run the conversion
            TrackedTask<Void> conversionTask = worldConverter.convert(reader.get(), writer.get());

            // Redirect any failure to an atomic, so we can exit our loop
            AtomicReference<Throwable> failed = new AtomicReference<>();
            conversionTask.future().exceptionally((exception) -> {
                failed.set(exception);
                return null;
            });

            double value = -1D;
            while (value != 1 && failed.get() == null) {
                double polled = conversionTask.getProgress();
                if (polled != value) {
                    System.out.printf("%.2f%%%n", (polled * 100D));
                    value = polled;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }

            // Handle result
            if (failed.get() != null) {
                System.err.println("Failed with exception");
                failed.get().printStackTrace();
                System.exit(1);
            } else {
                Duration duration = stopwatch.elapsed();
                System.out.println("Conversion complete! Took " + String.format("%sh %sm %ss %sms",
                        duration.toHoursPart(),
                        duration.toMinutesPart(),
                        duration.toSecondsPart(),
                        duration.toMillisPart()
                ));

                // Record what was changed and what was lost. The conversion throws content away by design, and
                // none of it shows up in the output world, so this file is the only way for the user to tell a
                // missing mapping apart from a real bug.
                //
                // A failure to write the report must not fail the conversion: the world itself is already written
                // and valid, and losing the report is far less serious than losing the result.
                try {
                    File reportFile = ConversionReport.write(worldConverter, reader.get(), writer.get(), outputDirectory);
                    System.out.println("Report written to " + reportFile.getAbsolutePath());
                    System.out.println(ConversionReport.summary(ConversionReport.build(worldConverter, reader.get(), writer.get())));
                } catch (IOException e) {
                    System.err.println("The conversion succeeded but the report could not be written: " + e.getMessage());
                }

                System.exit(0);
            }
        } catch (OutOfMemoryError e) {
            try {
                e.printStackTrace();
            } catch (OutOfMemoryError e2) {
                // We tried printing it
            }

            // Use error code 12 for OOM
            System.exit(12);
        }
    }
}
