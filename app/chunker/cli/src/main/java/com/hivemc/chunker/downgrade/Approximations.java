package com.hivemc.chunker.downgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * The built-in substitutions for blocks the target version never had.
 * <p>
 * 1.12.2 is missing whole categories that later versions added - there are no walls, no trapdoors other than oak
 * and iron, no stone stairs, no stripped logs. Such a block cannot be written, and Chunker's response is to write
 * air and say nothing, which turns a build into a colander. This supplies an alternative instead: the nearest block
 * 1.12.2 does have.
 * <p>
 * Two things make this safe rather than presumptuous. The rules live in a readable json file next to the code so the
 * choices can be argued with, and every rule is a fallback: a mapping the user wrote always wins, because the
 * mapping lookup keeps the first entry it sees for any given input.
 */
public final class Approximations {
    private static final String RESOURCE = "/downgrade/approximations.json";
    private static volatile JsonArray cachedBuiltIn;

    private Approximations() {
    }

    /**
     * Get the built-in substitution rules.
     *
     * @return the rules, empty if the resource could not be read.
     */
    public static JsonArray builtInRules() {
        JsonArray cached = cachedBuiltIn;
        if (cached != null) return cached;
        synchronized (Approximations.class) {
            if (cachedBuiltIn == null) {
                cachedBuiltIn = readRules();
            }
            return cachedBuiltIn;
        }
    }

    /**
     * Get how many built-in substitution rules are available.
     *
     * @return the rule count.
     */
    public static int ruleCount() {
        return builtInRules().size();
    }

    /**
     * Merge the built-in substitutions into a mappings json, with the user's own mappings taking precedence.
     * <p>
     * Order is what decides precedence, and the mapping lookup keeps the earliest entry for a given input. The user's
     * entries therefore go first: a block they have explicitly chosen a home for is never quietly redirected by a
     * built-in guess.
     *
     * @param userMappingsJson the user's mappings json, or null if they have not written any.
     * @return a mappings json containing the user's entries followed by the built-in ones.
     */
    public static String merge(String userMappingsJson) {
        JsonArray rules = builtInRules();
        JsonArray identifiers = new JsonArray();

        if (userMappingsJson != null && !userMappingsJson.isBlank()) {
            try {
                JsonElement parsed = JsonParser.parseString(userMappingsJson);
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("identifiers")) {
                    for (JsonElement entry : parsed.getAsJsonObject().getAsJsonArray("identifiers")) {
                        identifiers.add(entry);
                    }
                }
            } catch (Exception ignored) {
                // A malformed user file is the caller's problem to report; the built-in rules still apply.
            }
        }

        for (JsonElement rule : rules) {
            identifiers.add(mappingOnly(rule));
        }

        JsonObject merged = new JsonObject();
        merged.add("identifiers", identifiers);
        return merged.toString();
    }

    /**
     * Build a mappings json from the user's own entries only, leaving the built-in substitutions out.
     * <p>
     * The built-in substitutions are a judgement about what a block should look like, not a fact, so they are offered
     * rather than imposed: turning them off has to leave the user's own rules working exactly as before.
     *
     * @param userMappingsJson the user's mappings json, or null if they have not written any.
     * @return a mappings json containing only the user's entries.
     */
    public static String userOnly(String userMappingsJson) {
        JsonArray identifiers = new JsonArray();
        if (userMappingsJson != null && !userMappingsJson.isBlank()) {
            try {
                JsonElement parsed = JsonParser.parseString(userMappingsJson);
                if (parsed.isJsonObject() && parsed.getAsJsonObject().has("identifiers")) {
                    for (JsonElement entry : parsed.getAsJsonObject().getAsJsonArray("identifiers")) {
                        identifiers.add(entry);
                    }
                }
            } catch (Exception ignored) {
                // Nothing usable in the user's file; an empty set of mappings is the honest result.
            }
        }

        JsonObject result = new JsonObject();
        result.add("identifiers", identifiers);
        return result.toString();
    }

    /**
     * Read the rules from the bundled resource, keeping the grouping notes as a field on each rule.
     *
     * @return the usable rules, each carrying the heading it appeared under.
     */
    private static JsonArray readRules() {
        JsonArray described = new JsonArray();
        try (InputStream stream = Approximations.class.getResourceAsStream(RESOURCE)) {
            if (stream == null) return described;
            String text = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();
            if (!root.has("identifiers")) return described;

            String group = "";
            for (JsonElement element : root.getAsJsonArray("identifiers")) {
                if (!element.isJsonObject()) continue;
                JsonObject source = element.getAsJsonObject();

                // The file is written to be read by people, so it carries "_group" headings between the rules. They
                // are notes rather than mappings, so they become a field on the rules that follow instead of being
                // mistaken for one - which also gives the interface something to group the list by.
                if (source.has("_group")) {
                    group = source.get("_group").getAsString();
                    continue;
                }
                if (!source.has("old_identifier") || !source.has("new_identifier")) continue;

                JsonObject rule = new JsonObject();
                for (Map.Entry<String, JsonElement> field : source.entrySet()) {
                    if (field.getKey().startsWith("_")) continue;
                    rule.add(field.getKey(), field.getValue());
                }
                rule.addProperty("group", group);
                described.add(rule);
            }
        } catch (Exception ignored) {
            // Without the rules the conversion still works; it just falls back to dropping these blocks.
        }
        return described;
    }

    /**
     * Strip the display-only fields from a rule so it can be handed to the converter.
     *
     * @param rule the rule as listed by {@link #builtInRules()}.
     * @return the same rule with only mapping fields left.
     */
    private static JsonObject mappingOnly(JsonElement rule) {
        JsonObject mapping = new JsonObject();
        if (rule == null || !rule.isJsonObject()) return mapping;
        for (Map.Entry<String, JsonElement> field : rule.getAsJsonObject().entrySet()) {
            if (field.getKey().equals("group")) continue;
            mapping.add(field.getKey(), field.getValue());
        }
        return mapping;
    }
}
