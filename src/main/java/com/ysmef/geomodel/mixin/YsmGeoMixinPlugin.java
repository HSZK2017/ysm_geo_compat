package com.ysmef.geomodel.mixin;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Early-load mixin filter for the optional YSM animation-transition guard.
 *
 * The guard mixin targets a YSM class. When the main YSM_EpicFight_Compat mod
 * is installed it already applies the identical guard, and applying a second
 * redirect from this mod would conflict with it. This plugin therefore
 * disables the guard whenever ysm_epicfight_compat is present.
 */
public final class YsmGeoMixinPlugin implements IMixinConfigPlugin {

    private static final String GUARD_MIXIN = "com.ysmef.geomodel.mixin.YsmAnimationTransitionGuardMixin";
    private static final String MAIN_MOD = "ysm_epicfight_compat";

    private final boolean mainCompatInstalled = isMainCompatInstalled();

    private static boolean isMainCompatInstalled() {
        try {
            return net.minecraftforge.fml.loading.LoadingModList.get().getModFileById(MAIN_MOD) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public void onLoad(String mixinPackage) {
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        boolean isGuard = GUARD_MIXIN.equals(mixinClassName)
                || (mixinClassName != null && mixinClassName.endsWith("YsmAnimationTransitionGuardMixin"));
        return !isGuard || !mainCompatInstalled;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
