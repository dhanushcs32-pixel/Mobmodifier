package com.serenemobmodifier.config;

import com.serenemobmodifier.SereneMobModifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Configuration holder for Serene Mob Modifier.
 *
 * <p>Rules follow a pipe-delimited format:
 * {@code entity_id|scale_multiplier|health_multiplier|damage_multiplier|speed_multiplier|follow_range|make_hostile_to_players|target_boats}
 *
 * <p>This class exposes {@link #updateRule(String, String)} and {@link #saveToDisk()} so that
 * the in-game GUI and network handler can modify rules at runtime without requiring a restart.</p>
 */
public class MobModifierConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> MODIFIER_RULES;
    public static final ModConfigSpec.BooleanValue GLOBAL_ENABLE;
    public static final ModConfigSpec.BooleanValue LOG_MODIFICATIONS;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.comment("Serene Mob Modifier Configuration").push("general");

        GLOBAL_ENABLE = builder
                .comment("Enable or disable all entity modifications globally.")
                .define("globalEnable", true);

        LOG_MODIFICATIONS = builder
                .comment("Print debug log messages to the console when an entity is modified.")
                .define("logModifications", true);

        MODIFIER_RULES = builder
                .comment("Entity modification rules formatted as:",
                        "entity_id|scale_multiplier|health_multiplier|damage_multiplier|speed_multiplier|follow_range|make_hostile_to_players|target_boats",
                        "When target_boats is true, mobs hunt vanilla boats and intercept, ladder-board, or ram assembled Sable / Serene Sailing ships.",
                        "Wildcards like 'mythsofthesea:*' are supported.")
                .defineListAllowEmpty(
                        "rules",
                        List.of(
                                "minecraft:drowned|1.4|2.0|1.5|1.2|32.0|true|true",
                                "minecraft:guardian|1.8|3.0|2.0|1.1|40.0|true|true",
                                "mythsofthesea:*|2.0|4.0|2.5|1.3|64.0|true|true"
                        ),
                        obj -> obj instanceof String str && str.contains("|")
                );

        builder.pop();
        SPEC = builder.build();
    }

    /**
     * Strictly validates a rule string received from a client before it is stored.
     *
     * <p>This is intentionally <em>not</em> used as the config-file validator: when one list element fails
     * validation NeoForge resets the entire list to its defaults, so a single typo in a hand-edited file
     * would wipe every rule. Hand-edited bad rules are instead skipped (and reported once) at apply time.</p>
     *
     * @return {@code null} if the rule is acceptable, otherwise a human-readable reason
     */
    public static String validateRule(String rule) {
        if (rule == null || rule.isEmpty() || rule.length() > 512) {
            return "rule is empty or longer than 512 characters";
        }
        String[] t = rule.split("\\|", -1);
        if (t.length < 8 || t.length > 9) {
            return "expected 8 or 9 '|'-separated fields but found " + t.length;
        }

        String id = t[0].trim();
        if (id.endsWith(":*")) {
            String namespace = id.substring(0, id.length() - 2);
            if (namespace.isEmpty() || !ResourceLocation.isValidNamespace(namespace)) {
                return "invalid namespace wildcard '" + id + "'";
            }
        } else {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) {
                return "unknown entity type '" + id + "'";
            }
        }

        String[] names = {"scale", "health", "damage", "speed", "follow range"};
        double[] maxes = {16.0, 1000.0, 1000.0, 1000.0, 2048.0};
        for (int i = 0; i < names.length; i++) {
            double v;
            try {
                v = Double.parseDouble(t[i + 1].trim());
            } catch (NumberFormatException e) {
                return names[i] + " is not a number: '" + t[i + 1].trim() + "'";
            }
            if (!Double.isFinite(v) || v <= 0.0 || v > maxes[i]) {
                return names[i] + " must be greater than 0 and at most " + maxes[i];
            }
        }

        for (int i = 6; i <= 7; i++) {
            String b = t[i].trim();
            if (!b.equalsIgnoreCase("true") && !b.equalsIgnoreCase("false")) {
                return "field " + (i + 1) + " must be true or false but was '" + b + "'";
            }
        }

        if (t.length == 9) {
            try {
                double kb = Double.parseDouble(t[8].trim());
                if (!Double.isFinite(kb) || kb < 0.0 || kb > 1.0) {
                    return "knockback resistance must be between 0 and 1";
                }
            } catch (NumberFormatException e) {
                return "knockback resistance is not a number: '" + t[8].trim() + "'";
            }
        }
        return null;
    }

    /**
     * Updates (or inserts) a rule for the given entity ID in the in-memory config.
     *
     * <p>If a rule already exists for {@code entityId}, it is replaced. Otherwise, the new
     * rule is appended to the list.</p>
     *
     * @param entityId   the entity identifier (e.g. {@code "minecraft:drowned"})
     * @param ruleString the full pipe-delimited rule string
     */
    public static void updateRule(String entityId, String ruleString) {
        List<? extends String> existing = MODIFIER_RULES.get();
        List<String> updated = new ArrayList<>();

        boolean replaced = false;
        for (String rule : existing) {
            String ruleId = rule.split("\\|")[0].trim();
            if (ruleId.equalsIgnoreCase(entityId)) {
                updated.add(ruleString);
                replaced = true;
            } else {
                updated.add(rule);
            }
        }

        if (!replaced) {
            updated.add(ruleString);
        }

        MODIFIER_RULES.set(updated);
        SereneMobModifier.LOGGER.info("[SereneMobModifier] In-memory rule updated for: {}", entityId);
    }

    /**
     * Persists the current in-memory configuration state to disk
     * ({@code serenemobmodifier-common.toml}).
     *
     * <p>This is called after rule changes have been committed to ensure they survive
     * game restarts.</p>
     */
    public static void saveToDisk() {
        try {
            SPEC.save();
            SereneMobModifier.LOGGER.info("[SereneMobModifier] Configuration saved to disk.");
        } catch (Exception e) {
            SereneMobModifier.LOGGER.error("[SereneMobModifier] Failed to save configuration to disk.", e);
        }
    }
}
