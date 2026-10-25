package com.serenemobmodifier.config;

import com.serenemobmodifier.SereneMobModifier;
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
