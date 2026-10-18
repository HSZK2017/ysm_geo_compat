package com.ysmef.geomodel.renderer.layer;

import com.ysmef.geomodel.config.YSMCompatConfig;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedItemInHandLayer;
import yesman.epicfight.client.renderer.patched.layer.PatchedLayer;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Wraps the maid renderer's current held-item layers before Epic Fight draws them. */
public final class YsmMaidItemLayers {
    private static volatile List<? extends String> cachedConfig;
    private static volatile Set<String> cachedExtraClasses = Set.of();

    private YsmMaidItemLayers() {}

    /** Existing layers keep their drawing code; later replacements are picked up on the next render. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static boolean wrapAll(Map<Class<?>, ? extends PatchedLayer> patchedLayers,
                                  List<? extends PatchedLayer> customLayers,
                                  Map<PatchedLayer, YsmAnchoredItemLayer> wrappers) {
        return wrapAll(patchedLayers, customLayers, wrappers, extraClasses());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static boolean wrapAll(Map<Class<?>, ? extends PatchedLayer> patchedLayers,
                           List<? extends PatchedLayer> customLayers,
                           Map<PatchedLayer, YsmAnchoredItemLayer> wrappers,
                           Set<String> extraClasses) {
        Map<Class<?>, PatchedLayer> map = (Map<Class<?>, PatchedLayer>) patchedLayers;
        List<PatchedLayer> custom = (List<PatchedLayer>) customLayers;
        boolean changed = false;
        for (Map.Entry<Class<?>, PatchedLayer> entry : map.entrySet()) {
            if (drawsHeldItems(entry.getKey(), entry.getValue(), extraClasses)) {
                entry.setValue(wrap(entry.getValue(), wrappers));
                changed = true;
            }
        }
        for (int i = 0; i < custom.size(); i++) {
            PatchedLayer layer = custom.get(i);
            if (drawsHeldItems(null, layer, extraClasses)) {
                custom.set(i, wrap(layer, wrappers));
                changed = true;
            }
        }
        return changed;
    }

    static boolean drawsHeldItems(Class<?> vanillaKey, Object layer, Set<String> extraClasses) {
        if (layer == null || layer instanceof YsmAnchoredItemLayer) return false;
        if (vanillaKey != null && ItemInHandLayer.class.isAssignableFrom(vanillaKey)) return true;
        if (layer instanceof PatchedItemInHandLayer) return true;
        for (Class<?> type = layer.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            if (extraClasses.contains(type.getName())) return true;
        }
        return false;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static YsmAnchoredItemLayer wrap(PatchedLayer layer,
                                              Map<PatchedLayer, YsmAnchoredItemLayer> wrappers) {
        return wrappers.computeIfAbsent(layer, YsmAnchoredItemLayer::new);
    }

    private static Set<String> extraClasses() {
        List<? extends String> configured;
        try {
            configured = YSMCompatConfig.EXTRA_WEAPON_LAYER_CLASSES.get();
        } catch (RuntimeException notLoaded) {
            configured = YSMCompatConfig.EXTRA_WEAPON_LAYER_CLASSES.getDefault();
        }
        if (configured != cachedConfig) {
            Set<String> names = new java.util.HashSet<>();
            for (String name : configured) names.add(name.trim());
            cachedExtraClasses = Set.copyOf(names);
            cachedConfig = configured;
        }
        return cachedExtraClasses;
    }
}
