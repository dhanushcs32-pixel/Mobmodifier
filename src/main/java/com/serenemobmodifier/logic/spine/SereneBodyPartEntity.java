package com.serenemobmodifier.logic.spine;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.neoforged.neoforge.entity.PartEntity;

/**
 * Physical sub-part hitbox entity for elongated serpent/leviathan mobs.
 * Trails behind the parent entity along its swimming spine, receiving damage and passing it to the parent.
 */
public class SereneBodyPartEntity extends PartEntity<LivingEntity> {

    public final String segmentName;
    public final boolean isHead;
    private EntityDimensions dimensions;

    public SereneBodyPartEntity(LivingEntity parent, String segmentName, float width, float height, boolean isHead) {
        super(parent);
        this.segmentName = segmentName;
        this.isHead = isHead;
        this.dimensions = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
    }

    public SereneBodyPartEntity(LivingEntity parent, String segmentName, float width, float height) {
        this(parent, segmentName, width, height, false);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.isInvulnerableTo(source)) {
            return false;
        }
        return this.getParent().hurt(source, amount);
    }

    @Override
    public boolean is(Entity entity) {
        return this == entity || this.getParent() == entity;
    }

    @Override
    public EntityDimensions getDimensions(Pose pose) {
        return this.dimensions;
    }

    public void updateDimensions(float width, float height) {
        this.dimensions = EntityDimensions.scalable(width, height);
        this.refreshDimensions();
    }
}

