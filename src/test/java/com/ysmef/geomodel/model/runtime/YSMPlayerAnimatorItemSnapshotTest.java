package com.ysmef.geomodel.model.runtime;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YSMPlayerAnimatorItemSnapshotTest {
    @Test
    void capturedItemIdentityKeepsHoldAndUseConditionMatching() {
        YSMPlayerAnimator.HeldItem sword = new YSMPlayerAnimator.HeldItem(
                true, ResourceLocation.parse("minecraft:diamond_sword"));
        YSMPlayerAnimator.HeldItem empty = new YSMPlayerAnimator.HeldItem(false, null);
        YSMPlayerAnimator.HeldItem unregistered = new YSMPlayerAnimator.HeldItem(true, null);

        assertTrue(YSMPlayerAnimator.itemMatches(sword, "$minecraft:diamond_sword"));
        assertTrue(YSMPlayerAnimator.itemMatches(sword, ":sword"));
        assertFalse(YSMPlayerAnimator.itemMatches(sword, "$minecraft:iron_sword"));
        assertFalse(YSMPlayerAnimator.itemMatches(unregistered, ":sword"));

        assertTrue(YSMPlayerAnimator.itemMatchesLoose(sword, "$minecraft:diamond_sword"));
        assertTrue(YSMPlayerAnimator.itemMatchesLoose(sword, "minecraft"));
        assertTrue(YSMPlayerAnimator.itemMatchesLoose(sword, ":diamond"));
        assertFalse(YSMPlayerAnimator.itemMatchesLoose(sword, "empty"));
        assertTrue(YSMPlayerAnimator.itemMatchesLoose(empty, ":empty"));
        assertFalse(YSMPlayerAnimator.itemMatchesLoose(empty, "$minecraft:diamond_sword"));
    }
}
