package com.serenemobmodifier.event;

import com.serenemobmodifier.logic.MobModifierApplier;
import com.serenemobmodifier.logic.cerbon.CerbonsApiBridge;
import com.serenemobmodifier.logic.spine.SereneBodyPartEntity;
import com.serenemobmodifier.logic.spine.SpineSegmentManager;
import com.serenemobmodifier.network.NetworkRegistration;
import com.serenemobmodifier.util.HitboxParticleOutliner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

public class EntitySpawnHandler {

    public static void onEntityAttributeModification(EntityAttributeModificationEvent event) {
        for (EntityType<? extends LivingEntity> type : event.getTypes()) {
            // Players and armor stands are never configurable; don't touch their attribute sets.
            if (type == EntityType.PLAYER || type == EntityType.ARMOR_STAND) continue;
            if (!event.has(type, Attributes.SCALE)) {
                event.add(type, Attributes.SCALE, 1.0);
            }
            if (!event.has(type, Attributes.ATTACK_DAMAGE)) {
                event.add(type, Attributes.ATTACK_DAMAGE, 2.0);
            }
            if (!event.has(type, Attributes.MOVEMENT_SPEED)) {
                event.add(type, Attributes.MOVEMENT_SPEED, 0.25);
            }
            if (!event.has(type, Attributes.FOLLOW_RANGE)) {
                event.add(type, Attributes.FOLLOW_RANGE, 32.0);
            }
            if (!event.has(type, Attributes.KNOCKBACK_RESISTANCE)) {
                event.add(type, Attributes.KNOCKBACK_RESISTANCE, 0.0);
            }
        }
    }

    @SubscribeEvent
    public static void onEntitySize(EntityEvent.Size event) {
        if (event.getEntity() instanceof LivingEntity living) {
            float scale = living.getScale();
            if (Math.abs(scale - 1.0F) > 0.0001F) {
                EntityDimensions defaultDims = living.getType().getDimensions();
                float expectedWidth = defaultDims.width() * scale;
                // If the newSize width is not scaled to expectedWidth (e.g. custom mob overriding default dimensions), scale it forcefully
                if (Math.abs(event.getNewSize().width() - expectedWidth) > 0.01F) {
                    EntityDimensions currentNew = event.getNewSize();
                    event.setNewSize(EntityDimensions.scalable(currentNew.width() * scale, currentNew.height() * scale)
                            .withEyeHeight(currentNew.eyeHeight() * scale));
                }
            }
        } else if (event.getEntity() instanceof PartEntity<?> part) {
            if (part.getParent() instanceof LivingEntity livingParent) {
                float parentScale = livingParent.getScale();
                if (Math.abs(parentScale - 1.0F) > 0.0001F) {
                    EntityDimensions defaultDims = part.getType().getDimensions();
                    float expectedWidth = defaultDims.width() * parentScale;
                    if (Math.abs(event.getNewSize().width() - expectedWidth) > 0.01F) {
                        EntityDimensions currentNew = event.getNewSize();
                        event.setNewSize(EntityDimensions.scalable(currentNew.width() * parentScale, currentNew.height() * parentScale)
                                .withEyeHeight(currentNew.eyeHeight() * parentScale));
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity living) {
            SpineSegmentManager.tick(living);
            MobModifierApplier.enforceHitbox(living);
            HitboxParticleOutliner.tickEntity(living);
        } else if (event.getEntity() instanceof Projectile projectile && !projectile.level().isClientSide()) {
            handleProjectileTrajectory(projectile);
        }
    }

    private static void handleProjectileTrajectory(Projectile projectile) {
        if (!projectile.isAlive()) return;
        Vec3 pos = projectile.position();
        Vec3 mov = projectile.getDeltaMovement();
        if (mov.lengthSqr() < 0.005) return;
        Vec3 nextPos = pos.add(mov);

        AABB searchBox = projectile.getBoundingBox().expandTowards(mov).inflate(2.0);
        for (LivingEntity nearby : projectile.level().getEntitiesOfClass(LivingEntity.class, searchBox, e -> e.isAlive() && e != projectile.getOwner())) {
            // Check CerbonsAPI scaled segments
            if (CerbonsApiBridge.isCerbonsMultipart(nearby)) {
                if (CerbonsApiBridge.checkRaycastHit(nearby, pos, nextPos)) {
                    DamageSource source = projectile.damageSources().arrow(
                            (projectile instanceof AbstractArrow arrow) ? arrow : null,
                            projectile.getOwner()
                    );
                    nearby.hurt(source, (float) (projectile instanceof AbstractArrow arrow ? arrow.getBaseDamage() * 2.0 : 4.0));
                    projectile.discard();
                    return;
                }
            }

            // Check Serene Spine segments
            SereneBodyPartEntity[] spineParts = SpineSegmentManager.getSpineSegments(nearby);
            if (spineParts != null) {
                for (SereneBodyPartEntity part : spineParts) {
                    if (part.getBoundingBox().inflate(0.3).clip(pos, nextPos).isPresent()) {
                        DamageSource source = projectile.damageSources().arrow(
                                (projectile instanceof AbstractArrow arrow) ? arrow : null,
                                projectile.getOwner()
                        );
                        part.hurt(source, (float) (projectile instanceof AbstractArrow arrow ? arrow.getBaseDamage() * 2.0 : 4.0));
                        projectile.discard();
                        return;
                    }
                }
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            NetworkRegistration.syncRulesTo(serverPlayer);
        }
    }

    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            if (event.getEntity() instanceof LivingEntity living) {
                MobModifierApplier.enforceHitbox(living);
            }
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity living)) return;

        // Apply permanent attributes and ensure transient AI goals are attached on every join (chunk load or initial spawn).
        // When no rule matches (or modifications are globally disabled) this also removes modifiers added earlier.
        MobModifierApplier.refreshEntity(living);
    }
}
