package com.ysmef.geomodel.renderer;

import net.minecraft.world.entity.LivingEntity;
import yesman.epicfight.world.capabilities.EpicFightCapabilities;
import yesman.epicfight.world.capabilities.entitypatch.LivingEntityPatch;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Central battle-mode check for the converted-mesh features.
 *
 * "Battle mode" means the state where Epic Fight plays its own combat animations
 * on the entity: for EpicFight_TouhouLittleMaid's MaidPatch that is the patch's
 * isFightMode() method, looked up reflectively so this mod keeps no compile-time
 * dependency on that addon.
 *
 * In that state the compat mod renders the plain converted base mesh only -
 * no script animations, no variant forms.
 */
public final class YSMBattleMode {

    private static final Map<Class<?>, Method> FIGHT_MODE_METHODS = new ConcurrentHashMap<>();
    private static final Method NO_FIGHT_MODE = NO_FIGHT_MODE_MARKER();
    private static boolean fightModeLookupFailed = false;

    private YSMBattleMode() {}

    /**
     * True when the given entity is currently in battle mode (reflective
     * isFightMode() on its Epic Fight patch, e.g. EFTLM's MaidPatch).
     */
    public static boolean isBattleMode(LivingEntity entity) {
        if (entity == null) {
            return false;
        }
        LivingEntityPatch<?> patch = EpicFightCapabilities.getEntityPatch(entity, LivingEntityPatch.class);
        if (patch == null) {
            return false;
        }
        Method method = FIGHT_MODE_METHODS.computeIfAbsent(patch.getClass(), YSMBattleMode::findFightModeMethod);
        if (method == NO_FIGHT_MODE) {
            return false;
        }
        try {
            return (boolean) method.invoke(patch);
        } catch (Exception e) {
            if (!fightModeLookupFailed) {
                fightModeLookupFailed = true;
                com.ysmef.geomodel.YSMGeoCompat.LOGGER.warn(
                        "YSM-GEO Compat: failed to query isFightMode on {}", patch.getClass().getName(), e);
            }
            return false;
        }
    }

    private static Method findFightModeMethod(Class<?> patchClass) {
        try {
            return patchClass.getMethod("isFightMode");
        } catch (NoSuchMethodException e) {
            return NO_FIGHT_MODE;
        }
    }

    private static Method NO_FIGHT_MODE_MARKER() {
        try {
            return YSMBattleMode.class.getDeclaredMethod("noFightModeMarker");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unused")
    private static void noFightModeMarker() {}
}
