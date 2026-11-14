package com.serenemobmodifier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.serenemobmodifier.logic.MobModifierApplier;
import com.serenemobmodifier.logic.ai.AttackAndBoardShipGoal;
import com.serenemobmodifier.logic.cerbon.CerbonsApiBridge;
import com.serenemobmodifier.logic.spine.SereneBodyPartEntity;
import com.serenemobmodifier.logic.spine.SpineSegmentManager;
import com.serenemobmodifier.util.HitboxParticleOutliner;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public class MobModifierCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mobmodifier")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("inspect").executes(MobModifierCommand::inspect))
                        .then(Commands.literal("hitbox").executes(MobModifierCommand::hitbox))
                        .then(Commands.literal("reload").executes(MobModifierCommand::reload))
        );
        dispatcher.register(
                Commands.literal("serenemob")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("inspect").executes(MobModifierCommand::inspect))
                        .then(Commands.literal("hitbox").executes(MobModifierCommand::hitbox))
                        .then(Commands.literal("reload").executes(MobModifierCommand::reload))
        );
    }

    private static int inspect(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can execute inspect."));
            return 0;
        }

        TargetResult targetResult = findTargetEntity(player, 128.0);
        if (targetResult == null || targetResult.entity == null) {
            player.sendSystemMessage(Component.literal("§c[SereneMobModifier] No entity in crosshair or view within 128 blocks."));
            return 0;
        }

        LivingEntity target = targetResult.entity;
        if (targetResult.isFallback) {
            player.sendSystemMessage(Component.literal("§7(Selected nearest entity in view: §f" + target.getType().getDescriptionId() + "§7)"));
        }

        // Trigger visual particle hitbox outliner for 10 seconds (200 ticks)
        HitboxParticleOutliner.startOutline(target, 200);

        player.sendSystemMessage(Component.literal("§6=== [SereneMobModifier] Entity Inspection ==="));
        player.sendSystemMessage(Component.literal("§eEntity: §f" + target.getType().getDescriptionId() + " §7(UUID: " + target.getUUID() + ")"));
        player.sendSystemMessage(Component.literal("§bJava Class: §f" + target.getClass().getName()));

        double hp = target.getHealth();
        double maxHp = target.getMaxHealth();
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bHealth: §f%.1f / %.1f", hp, maxHp)));

        if (target.getAttribute(Attributes.ATTACK_DAMAGE) != null) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bAttack Damage: §f%.2f", target.getAttributeValue(Attributes.ATTACK_DAMAGE))));
        }
        if (target.getAttribute(Attributes.MOVEMENT_SPEED) != null) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bMovement Speed: §f%.3f", target.getAttributeValue(Attributes.MOVEMENT_SPEED))));
        }
        if (target.getAttribute(Attributes.SCALE) != null) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bScale: §f%.2f", target.getAttributeValue(Attributes.SCALE))));
        }

        // Base EntityType dimensions
        EntityDimensions typeDims = target.getType().getDimensions();
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bBase Dimensions: §f%.2fw x %.2fh", typeDims.width(), typeDims.height())));

        // Live EntityDimensions and Eye Height
        float bbWidth = target.getBbWidth();
        float bbHeight = target.getBbHeight();
        float eyeHeight = target.getEyeHeight();
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bEntityDimensions: §f%.2fw x %.2fh (Eye: %.2f)", bbWidth, bbHeight, eyeHeight)));

        // Live physical AABB bounding box dimensions
        AABB aabb = target.getBoundingBox();
        player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bLive AABB Size: §f%.2fw x %.2fh x %.2fd", aabb.getXsize(), aabb.getYsize(), aabb.getZsize())));

        // Hitbox synchronization status
        float scale = target.getScale();
        float expectedWidth = typeDims.width() * scale;
        float expectedHeight = typeDims.height() * scale;
        boolean inSync = Math.abs(bbWidth - expectedWidth) <= 0.05f
                && Math.abs(aabb.getXsize() - expectedWidth) <= 0.05f
                && Math.abs(aabb.getYsize() - expectedHeight) <= 0.05f;

        String syncStatus = inSync ? "§aSynchronized (Hitbox matches Scale 1:1)" : "§cAdjusted/Custom (Elongated or Custom Dims)";
        player.sendSystemMessage(Component.literal("§bSync Status: " + syncStatus));

        // Discovered Reflection Fields
        List<String> fieldInfo = MobModifierApplier.getDiscoveredSubPartFieldInfo(target);
        if (!fieldInfo.isEmpty()) {
            player.sendSystemMessage(Component.literal("§bDiscovered Segment Fields: §f" + fieldInfo.size()));
            for (String fi : fieldInfo) {
                player.sendSystemMessage(Component.literal("  §7- " + fi));
            }
        } else {
            player.sendSystemMessage(Component.literal("§bDiscovered Segment Fields: §7None (No sub-entity fields)"));
        }

        // All Declared Instance Fields on Class and Superclass
        List<String> declaredFields = listAllDeclaredFields(target);
        if (!declaredFields.isEmpty()) {
            player.sendSystemMessage(Component.literal("§bDeclared Instance Fields (" + declaredFields.size() + "):"));
            for (String f : declaredFields) {
                player.sendSystemMessage(Component.literal("  §7- §f" + f));
            }
        }

        // Cerbons API / Myths of the Sea Multipart Inspection
        if (CerbonsApiBridge.isCerbonsMultipart(target)) {
            List<String> cerbonDetails = CerbonsApiBridge.getCerbonsPartDetails(target);
            player.sendSystemMessage(Component.literal("§bCerbonsAPI EntityBounds Parts (" + cerbonDetails.size() + " scaled):"));
            for (String detail : cerbonDetails) {
                player.sendSystemMessage(Component.literal("  §7- §f" + detail));
            }
        }

        // Serene IK Spine Segments
        List<SereneBodyPartEntity> spineSegs = SpineSegmentManager.getSegments(target);
        if (!spineSegs.isEmpty()) {
            player.sendSystemMessage(Component.literal("§bSerene IK Spine Segments (" + spineSegs.size() + "):"));
            for (int i = 0; i < spineSegs.size(); i++) {
                SereneBodyPartEntity seg = spineSegs.get(i);
                AABB segBox = seg.getBoundingBox();
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "  §7[Spine #%d]: §f%.2fw x %.2fh x %.2fd §7(Pos: %.1f, %.1f, %.1f)",
                        i, segBox.getXsize(), segBox.getYsize(), segBox.getZsize(),
                        seg.getX(), seg.getY(), seg.getZ()
                )));
            }
        }

        // Sub-Parts enumeration
        List<Entity> subParts = MobModifierApplier.getAllSubParts(target);
        if (!subParts.isEmpty()) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bSub-Parts: §f%d parts (Scaled)", subParts.size())));
            for (int i = 0; i < subParts.size(); i++) {
                Entity part = subParts.get(i);
                AABB partBox = part.getBoundingBox();
                player.sendSystemMessage(Component.literal(String.format(Locale.ROOT,
                        "  §7[Part #%d] %s: §f%.2fw x %.2fh x %.2fd §7(Pos: %.1f, %.1f, %.1f)",
                        i, part.getClass().getSimpleName(),
                        partBox.getXsize(), partBox.getYsize(), partBox.getZsize(),
                        part.getX(), part.getY(), part.getZ()
                )));
            }
        } else {
            player.sendSystemMessage(Component.literal("§bSub-Parts: §70 (Single Hitbox Entity)"));
        }

        if (target.getAttribute(Attributes.FOLLOW_RANGE) != null) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bFollow Range: §f%.1f", target.getAttributeValue(Attributes.FOLLOW_RANGE))));
        }
        if (target.getAttribute(Attributes.KNOCKBACK_RESISTANCE) != null) {
            player.sendSystemMessage(Component.literal(String.format(Locale.ROOT, "§bKnockback Resistance: §f%.2f", target.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE))));
        }

        if (target instanceof Mob mob) {
            boolean hasPlayerGoal = mob.targetSelector.getAvailableGoals().stream()
                    .anyMatch(g -> g.getGoal() instanceof MobModifierApplier.SereneAttackPlayerGoal);
            boolean hasBoatGoal = mob.goalSelector.getAvailableGoals().stream()
                    .anyMatch(g -> g.getGoal() instanceof AttackAndBoardShipGoal);
            player.sendSystemMessage(Component.literal(String.format("§bAI Goals: §fHostileToPlayers=%b, TargetBoats=%b", hasPlayerGoal, hasBoatGoal)));
        }

        player.sendSystemMessage(Component.literal("§a[Hitbox Outliner] Displaying particle wireframe for 10s (white = parent, flame = parts)."));
        return 1;
    }

    private static int hitbox(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can execute hitbox."));
            return 0;
        }

        TargetResult targetResult = findTargetEntity(player, 128.0);
        if (targetResult == null || targetResult.entity == null) {
            player.sendSystemMessage(Component.literal("§c[SereneMobModifier] No entity in crosshair or view within 128 blocks."));
            return 0;
        }

        LivingEntity target = targetResult.entity;
        if (targetResult.isFallback) {
            player.sendSystemMessage(Component.literal("§7(Selected nearest entity in view: §f" + target.getType().getDescriptionId() + "§7)"));
        }

        HitboxParticleOutliner.startOutline(target, 200);
        player.sendSystemMessage(Component.literal(String.format(
                "§a[SereneMobModifier] Showing particle hitbox outline for §e%s§a for 10 seconds (white = parent, flame = parts).",
                target.getType().getDescriptionId()
        )));
        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int count = 0;
        if (source.getServer() != null) {
            for (ServerLevel level : source.getServer().getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof LivingEntity living && living.isAlive()) {
                        if (MobModifierApplier.applyMatchingRules(living)) {
                            count++;
                        }
                    }
                }
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("§a[SereneMobModifier] Reloaded rules and refreshed " + finalCount + " active entities."), true);
        return count;
    }

    private record TargetResult(LivingEntity entity, boolean isFallback) {}

    private static TargetResult findTargetEntity(ServerPlayer player, double maxDist) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 viewVec = player.getViewVector(1.0F).normalize();
        Vec3 reachVec = eyePos.add(viewVec.scale(maxDist));
        ServerLevel level = player.serverLevel();

        LivingEntity bestHitEntity = null;
        double closestHitDistSq = Double.MAX_VALUE;

        // Phase 1: Direct Raycast against all entities in the level (and their sub-parts) up to maxDist
        for (Entity entity : level.getAllEntities()) {
            if (entity == player || !entity.isAlive()) continue;

            double distSqToPlayer = entity.distanceToSqr(player);
            if (distSqToPlayer > (maxDist + 16.0) * (maxDist + 16.0)) continue;

            if (entity instanceof LivingEntity living) {
                // Test main bounding box
                AABB box = living.getBoundingBox().inflate(0.5);
                Optional<Vec3> clip = box.clip(eyePos, reachVec);
                if (clip.isPresent()) {
                    double hitDistSq = eyePos.distanceToSqr(clip.get());
                    if (hitDistSq < closestHitDistSq) {
                        closestHitDistSq = hitDistSq;
                        bestHitEntity = living;
                    }
                }

                // Test any child sub-parts (whether PartEntity or reflected segments)
                List<Entity> parts = MobModifierApplier.getAllSubParts(living);
                for (Entity part : parts) {
                    if (part != null && part != living) {
                        AABB partBox = part.getBoundingBox().inflate(0.5);
                        Optional<Vec3> partClip = partBox.clip(eyePos, reachVec);
                        if (partClip.isPresent()) {
                            double hitDistSq = eyePos.distanceToSqr(partClip.get());
                            if (hitDistSq < closestHitDistSq) {
                                closestHitDistSq = hitDistSq;
                                bestHitEntity = living;
                            }
                        }
                    }
                }

                // Test CerbonsAPI scaled segment boxes if present
                for (AABB cerbonBox : CerbonsApiBridge.getCerbonsPartBoxes(living)) {
                    AABB inflated = cerbonBox.inflate(0.5);
                    Optional<Vec3> cerbonClip = inflated.clip(eyePos, reachVec);
                    if (cerbonClip.isPresent()) {
                        double hitDistSq = eyePos.distanceToSqr(cerbonClip.get());
                        if (hitDistSq < closestHitDistSq) {
                            closestHitDistSq = hitDistSq;
                            bestHitEntity = living;
                        }
                    }
                }

                // Test Serene IK spine segments if present
                for (SereneBodyPartEntity segment : SpineSegmentManager.getSegments(living)) {
                    AABB segBox = segment.getBoundingBox().inflate(0.5);
                    Optional<Vec3> segClip = segBox.clip(eyePos, reachVec);
                    if (segClip.isPresent()) {
                        double hitDistSq = eyePos.distanceToSqr(segClip.get());
                        if (hitDistSq < closestHitDistSq) {
                            closestHitDistSq = hitDistSq;
                            bestHitEntity = living;
                        }
                    }
                }
            } else if (entity instanceof PartEntity<?> part && part.getParent() instanceof LivingEntity parentLiving) {
                AABB partBox = part.getBoundingBox().inflate(0.5);
                Optional<Vec3> partClip = partBox.clip(eyePos, reachVec);
                if (partClip.isPresent()) {
                    double hitDistSq = eyePos.distanceToSqr(partClip.get());
                    if (hitDistSq < closestHitDistSq) {
                        closestHitDistSq = hitDistSq;
                        bestHitEntity = parentLiving;
                    }
                }
            }
        }

        // ProjectileUtil raycast fallback
        if (bestHitEntity == null) {
            AABB searchBox = player.getBoundingBox().expandTowards(viewVec.scale(maxDist)).inflate(2.0);
            EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                    level, player, eyePos, reachVec, searchBox,
                    e -> e != player && e.isAlive()
            );
            if (hit != null) {
                if (hit.getEntity() instanceof LivingEntity living) {
                    bestHitEntity = living;
                } else if (hit.getEntity() instanceof PartEntity<?> part && part.getParent() instanceof LivingEntity parentLiving) {
                    bestHitEntity = parentLiving;
                }
            }
        }

        if (bestHitEntity != null) {
            return new TargetResult(bestHitEntity, false);
        }

        // Phase 2: Cone Fallback (up to 96 blocks, 35-degree angle cone)
        LivingEntity bestConeEntity = null;
        double bestDot = 0.819; // cos(35 degrees)

        for (Entity entity : level.getAllEntities()) {
            if (!(entity instanceof LivingEntity living) || living == player || !living.isAlive()) continue;

            double distSq = eyePos.distanceToSqr(living.getBoundingBox().getCenter());
            if (distSq > 96.0 * 96.0) continue;

            Vec3 toCenter = living.getBoundingBox().getCenter().subtract(eyePos);
            double dist = toCenter.length();
            if (dist < 0.001) continue;

            Vec3 dir = toCenter.scale(1.0 / dist);
            double dot = viewVec.dot(dir);

            if (dot > bestDot) {
                bestDot = dot;
                bestConeEntity = living;
            }
        }

        // Phase 3: Nearest Entity in Front (up to 64 blocks)
        if (bestConeEntity == null) {
            double nearestDistSq = 64.0 * 64.0;
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof LivingEntity living) || living == player || !living.isAlive()) continue;

                double distSq = eyePos.distanceToSqr(living.getBoundingBox().getCenter());
                if (distSq < nearestDistSq) {
                    Vec3 toCenter = living.getBoundingBox().getCenter().subtract(eyePos);
                    if (viewVec.dot(toCenter) > 0.0) { // in front of player
                        nearestDistSq = distSq;
                        bestConeEntity = living;
                    }
                }
            }
        }

        if (bestConeEntity != null) {
            return new TargetResult(bestConeEntity, true);
        }

        return null;
    }

    private static List<String> listAllDeclaredFields(LivingEntity target) {
        List<String> list = new ArrayList<>();
        Class<?> curr = target.getClass();
        while (curr != null && curr != LivingEntity.class && curr != Mob.class && curr != Object.class) {
            for (Field field : curr.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                try {
                    Object val = field.get(target);
                    String valStr = val == null ? "null" : val.getClass().getSimpleName();
                    list.add(field.getName() + " (" + field.getType().getSimpleName() + ") = " + valStr);
                } catch (Exception e) {
                    list.add(field.getName() + " (" + field.getType().getSimpleName() + ")");
                }
            }
            curr = curr.getSuperclass();
        }
        return list;
    }
}
