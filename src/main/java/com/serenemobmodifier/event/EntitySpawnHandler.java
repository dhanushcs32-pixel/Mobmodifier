package com.serenemobmodifier.event;

import com.serenemobmodifier.SereneMobModifier;
import com.serenemobmodifier.logic.MobModifierApplier;
import com.serenemobmodifier.logic.cerbon.CerbonsApiBridge;
import com.serenemobmodifier.logic.spine.SereneBodyPartEntity;
import com.serenemobmodifier.logic.spine.SpineSegmentManager;
import com.serenemobmodifier.network.NetworkRegistration;
import com.serenemobmodifier.util.HitboxParticleOutliner;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.entity.PartEntity;
import net.neoforged.neoforge.event.entity.EntityAttributeModificationEvent;
import net.neoforged.neoforge.event.entity.EntityEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.EntityTickEvent;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class EntitySpawnHandler {

    private static Method PROJECTILE_ON_HIT_METHOD;
    static {
        try {
            PROJECTILE_ON_HIT_METHOD = Projectile.class.getDeclaredMethod("onHit", HitResult.class);
            PROJECTILE_ON_HIT_METHOD.setAccessible(true);
        } catch (Throwable t) {
            for (Method m : Projectile.class.getDeclaredMethods()) {
                if (m.getParameterCount() == 1
                        && HitResult.class.isAssignableFrom(m.getParameterTypes()[0])
                        && m.getReturnType() == void.class) {
                    m.setAccessible(true);
                    PROJECTILE_ON_HIT_METHOD = m;
                    break;
                }
            }
        }
    }

    private static void invokeProjectileOnHit(Projectile projectile, EntityHitResult hitResult) throws Exception {
        if (PROJECTILE_ON_HIT_METHOD != null) {
            PROJECTILE_ON_HIT_METHOD.invoke(projectile, hitResult);
        }
    }

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
            if (CerbonsApiBridge.isCerbonsMultipart(living)
                    || (living.getBoundingBox() != null && living.getBoundingBox().getClass().getSimpleName().contains("CompoundOrientedBox"))) {
                return;
            }
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
    public static void onEntityTickPre(EntityTickEvent.Pre event) {
        if (event.getEntity() instanceof Projectile projectile && !projectile.level().isClientSide()) {
            handleProjectileSweep(projectile, true);
        }
    }

    @SubscribeEvent
    public static void onEntityTickPost(EntityTickEvent.Post event) {
        if (event.getEntity() instanceof LivingEntity living) {
            SpineSegmentManager.tick(living);
            MobModifierApplier.enforceHitbox(living);
            HitboxParticleOutliner.tickEntity(living);
        } else if (event.getEntity() instanceof Projectile projectile && !projectile.level().isClientSide()) {
            handleProjectileSweep(projectile, false);
        }
    }

    private static void handleProjectileSweep(Projectile projectile, boolean isPre) {
        if (!projectile.isAlive()) return;
        Vec3 mov = projectile.getDeltaMovement();
        if (mov.lengthSqr() < 0.001) return;

        Vec3 startVec;
        Vec3 endVec;
        if (isPre) {
            startVec = projectile.position();
            endVec = startVec.add(mov);
        } else {
            startVec = new Vec3(projectile.xo, projectile.yo, projectile.zo);
            endVec = projectile.position();
        }

        // Gather candidate targets
        List<LivingEntity> candidates = new ArrayList<>();

        // 1. All tracked scaled/multipart mobs alive in the current level within 128 blocks
        // (Solves chunk section spatial culling when long creatures span across neighboring chunk boundaries)
        for (LivingEntity tracked : MobModifierApplier.getTrackedScaledEntities()) {
            if (tracked != null && tracked.isAlive() && !tracked.isRemoved()
                    && tracked.level() == projectile.level()
                    && tracked != projectile.getOwner()) {
                if (tracked.position().distanceToSqr(startVec) <= 128.0 * 128.0) {
                    candidates.add(tracked);
                }
            }
        }

        // 2. Spatial query fallback with generous 32-block radius
        AABB broadBox = projectile.getBoundingBox().expandTowards(mov).inflate(32.0);
        for (LivingEntity nearby : projectile.level().getEntitiesOfClass(LivingEntity.class, broadBox,
                e -> e.isAlive() && !e.isRemoved() && e != projectile.getOwner())) {
            if (!candidates.contains(nearby)) {
                candidates.add(nearby);
            }
        }

        if (candidates.isEmpty()) return;

        LivingEntity closestTarget = null;
        AABB hitSegmentBox = null;
        Vec3 closestHitVec = null;
        double closestDistSq = Double.MAX_VALUE;

        for (LivingEntity target : candidates) {
            List<AABB> segments = MobModifierApplier.getActiveHitboxSegments(target);
            for (AABB seg : segments) {
                AABB inflated = seg.inflate(0.35);
                Optional<Vec3> clip = inflated.clip(startVec, endVec);
                if (clip.isPresent()) {
                    double d = startVec.distanceToSqr(clip.get());
                    if (d < closestDistSq) {
                        closestDistSq = d;
                        closestTarget = target;
                        hitSegmentBox = inflated;
                        closestHitVec = clip.get();
                    }
                }
            }

            // Also check CerbonsApiBridge raycast if applicable
            if (CerbonsApiBridge.isCerbonsMultipart(target) && CerbonsApiBridge.checkRaycastHit(target, startVec, endVec)) {
                double d = startVec.distanceToSqr(target.position());
                if (d < closestDistSq) {
                    closestDistSq = d;
                    closestTarget = target;
                    hitSegmentBox = target.getBoundingBox();
                    closestHitVec = startVec.add(mov.scale(0.5));
                }
            }
        }

        if (closestTarget == null || closestHitVec == null) return;

        // Apply hit
        AABB originalBb = closestTarget.getBoundingBox();
        float preHealth = closestTarget.getHealth();

        try {
            // Temporarily set target's bounding box to the hit segment so vanilla onHitEntity validation passes
            if (hitSegmentBox != null) {
                closestTarget.setBoundingBox(hitSegmentBox);
            }
            invokeProjectileOnHit(projectile, new EntityHitResult(closestTarget, closestHitVec));
        } catch (Throwable t) {
            SereneMobModifier.LOGGER.debug("[SereneMobModifier] Error invoking projectile onHit: {}", t.getMessage());
        } finally {
            closestTarget.setBoundingBox(originalBb);
        }

        // Fallback: If onHit did not reduce health and projectile is still alive, deal damage directly!
        boolean damageDealt = closestTarget.getHealth() < preHealth;
        if (!damageDealt && projectile.isAlive()) {
            DamageSource source = projectile.damageSources().arrow(
                    (projectile instanceof AbstractArrow arrow) ? arrow : null,
                    projectile.getOwner()
            );
            float damage = (projectile instanceof AbstractArrow arrow)
                    ? (float) Math.max(6.0, arrow.getBaseDamage() * mov.length() * 1.5)
                    : 6.0f;
            closestTarget.hurt(source, damage);

            projectile.level().playSound(null, closestHitVec.x, closestHitVec.y, closestHitVec.z,
                    SoundEvents.ARROW_HIT, SoundSource.PLAYERS, 1.0f,
                    1.2f / (projectile.level().random.nextFloat() * 0.2f + 0.9f));

            if (projectile instanceof AbstractArrow arrow) {
                if (arrow.getPierceLevel() <= 0) {
                    arrow.discard();
                }
            } else {
                projectile.discard();
            }
        }
    }

    @SubscribeEvent
    public static void onAttackEntity(AttackEntityEvent event) {
        if (event.getTarget() instanceof LivingEntity target && !target.level().isClientSide()) {
            if (CerbonsApiBridge.isCerbonsMultipart(target) || SpineSegmentManager.hasSpineSegments(target)) {
                MobModifierApplier.trackScaledEntity(target);
                // Temporarily expand bounding box to encompass reach so server interaction checks pass
                AABB bb = target.getBoundingBox();
                if (bb != null) {
                    target.setBoundingBox(bb.inflate(3.0));
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
                MobModifierApplier.trackScaledEntity(living);
            }
            return;
        }

        if (!(event.getEntity() instanceof LivingEntity living)) return;

        MobModifierApplier.trackScaledEntity(living);

        // Apply permanent attributes and ensure transient AI goals are attached on every join (chunk load or initial spawn).
        // When no rule matches (or modifications are globally disabled) this also removes modifiers added earlier.
        MobModifierApplier.refreshEntity(living);
    }
}
