package com.serenemobmodifier.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.serenemobmodifier.logic.MobModifierApplier;
import com.serenemobmodifier.logic.ai.AttackAndBoardShipGoal;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Locale;

public class MobModifierCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("mobmodifier")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("inspect").executes(MobModifierCommand::inspect))
                        .then(Commands.literal("reload").executes(MobModifierCommand::reload))
        );
        dispatcher.register(
                Commands.literal("serenemob")
                        .requires(source -> source.hasPermission(2))
                        .then(Commands.literal("inspect").executes(MobModifierCommand::inspect))
                        .then(Commands.literal("reload").executes(MobModifierCommand::reload))
        );
    }

    private static int inspect(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Only players can execute inspect."));
            return 0;
        }

        LivingEntity target = findTargetEntity(player, 16.0);
        if (target == null) {
            player.sendSystemMessage(Component.literal("§c[SereneMobModifier] No living entity in crosshair within 16 blocks."));
            return 0;
        }

        player.sendSystemMessage(Component.literal("§6=== [SereneMobModifier] Entity Inspection ==="));
        player.sendSystemMessage(Component.literal("§eEntity: §f" + target.getType().getDescriptionId() + " §7(UUID: " + target.getUUID() + ")"));

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

        return 1;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        int count = 0;
        if (source.getServer() != null) {
            for (ServerLevel level : source.getServer().getAllLevels()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof LivingEntity living && living.isAlive()) {
                        if (MobModifierApplier.refreshEntity(living)) {
                            count++;
                        }
                    }
                }
            }
        }
        int finalCount = count;
        source.sendSuccess(() -> Component.literal("§a[SereneMobModifier] Re-applied the current rules to " + finalCount + " active entities. (Edits to the config file are picked up automatically.)"), true);
        return count;
    }

    private static LivingEntity findTargetEntity(ServerPlayer player, double maxDist) {
        Vec3 eyePos = player.getEyePosition();
        Vec3 viewVec = player.getViewVector(1.0F);
        Vec3 reachVec = eyePos.add(viewVec.scale(maxDist));
        AABB box = player.getBoundingBox().expandTowards(viewVec.scale(maxDist)).inflate(1.0);

        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                player.level(), player, eyePos, reachVec, box,
                e -> e instanceof LivingEntity living && living.isAlive()
        );

        if (hit != null && hit.getEntity() instanceof LivingEntity living) {
            return living;
        }

        // Fallback: nearest living entity in front within 6 blocks
        LivingEntity closest = null;
        double closestDist = maxDist * maxDist;
        for (LivingEntity nearby : player.level().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(6.0), e -> e != player && e.isAlive())) {
            double d = player.distanceToSqr(nearby);
            if (d < closestDist) {
                closestDist = d;
                closest = nearby;
            }
        }
        return closest;
    }
}
