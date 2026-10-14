package com.ysmef.geomodel.model;

import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MeshTextureLifecycleTest {
    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> state(String fieldName) throws Exception {
        Field field = YSMMeshLibrary.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (Map<String, T>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, TlmModelLibrary.TlmMeshEntry> entries() throws Exception {
        Field field = TlmModelLibrary.class.getDeclaredField("MESHES");
        field.setAccessible(true);
        return (Map<String, TlmModelLibrary.TlmMeshEntry>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> loadedMeshes() throws Exception {
        Field field = YSMMeshLibrary.class.getDeclaredField("LOADED_MODELS");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    @Test
    void sharedMeshRestoresEveryVariantTextureAfterEviction() throws Exception {
        String owner = "base_" + UUID.randomUUID();
        String variant = owner + "_variant";
        ResourceLocation baseTexture = ResourceLocation.fromNamespaceAndPath(
                "ysm_geo_compat", "textures/test/base_" + owner + ".png");
        ResourceLocation variantTexture = ResourceLocation.fromNamespaceAndPath(
                "ysm_geo_compat", "textures/test/variant_" + owner + ".png");
        Map<String, TlmModelLibrary.TlmMeshEntry> registry = entries();
        registry.put(owner, new TlmModelLibrary.TlmMeshEntry(owner, null, baseTexture));
        registry.put(variant, new TlmModelLibrary.TlmMeshEntry(owner, null, variantTexture));
        try {
            assertEquals(owner, TlmModelLibrary.meshOwnerOf(variant));
            YSMMeshLibrary.markMeshLoaded(variant);
            assertTrue(loadedMeshes().contains(owner));
            assertFalse(loadedMeshes().contains(variant));
            assertEquals(Set.of(baseTexture, variantTexture),
                    Set.copyOf(YSMMeshLibrary.releaseTexturesOfModel(owner)));
            loadedMeshes().remove(owner); // the eviction path removes this marker
            YSMMeshLibrary.markMeshLoaded(owner);
            assertEquals(Set.of(baseTexture, variantTexture),
                    Set.copyOf(YSMMeshLibrary.releaseTexturesOfModel(owner)));
        } finally {
            loadedMeshes().remove(owner);
            state("ACCESS_ORDER").remove(owner);
            registry.remove(owner);
            registry.remove(variant);
            YSMMeshLibrary.releaseTexturesOfModel(owner);
            com.ysmef.geomodel.model.runtime.YSMRuntimeModel.invalidate(owner);
            for (ResourceLocation rl : new ResourceLocation[]{baseTexture, variantTexture}) {
                String key = rl.toString();
                state("PENDING_TEXTURE_RELEASES").remove(key);
                state("TEXTURE_TOKENS").remove(key);
            }
        }
    }

    @Test
    void evictionRevokesOldDecodeWithoutRemovingNewReservation() throws Exception {
        String owner = "base_" + UUID.randomUUID();
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                "ysm_geo_compat", "textures/test/" + owner + ".png");
        String key = texture.toString();
        Map<String, Long> tokens = state("TEXTURE_TOKENS");
        Map<String, Long> pending = state("PENDING_TEXTURE_DECODES");
        try {
            YSMMeshLibrary.bindModelTexture(owner, texture);
            Long oldToken = tokens.get(key);
            assertNotNull(oldToken);
            pending.put(key, oldToken);
            YSMMeshLibrary.releaseTexturesOfModel(owner);
            YSMMeshLibrary.bindModelTexture(owner, texture);
            Long newToken = tokens.get(key);
            assertNotNull(newToken);
            assertNotEquals(oldToken, newToken);
            Method isCurrent = YSMMeshLibrary.class.getDeclaredMethod("isCurrentTexture", String.class, long.class);
            isCurrent.setAccessible(true);
            assertFalse((Boolean) isCurrent.invoke(null, key, oldToken.longValue()));
            assertTrue((Boolean) isCurrent.invoke(null, key, newToken.longValue()));
            assertFalse(state("PENDING_TEXTURE_RELEASES").containsKey(key));
            pending.put(key, newToken);
            assertFalse(pending.remove(key, oldToken));
            assertEquals(newToken, pending.get(key));
        } finally {
            YSMMeshLibrary.releaseTexturesOfModel(owner);
            pending.remove(key);
            state("PENDING_TEXTURE_RELEASES").remove(key);
            tokens.remove(key);
        }
    }

    @Test
    void sharedTextureSurvivesUntilTheLastMeshOwnerIsEvicted() throws Exception {
        String firstOwner = "first_" + UUID.randomUUID();
        String secondOwner = "second_" + UUID.randomUUID();
        ResourceLocation texture = ResourceLocation.fromNamespaceAndPath(
                "ysm_geo_compat", "textures/test/shared_" + UUID.randomUUID() + ".png");
        String key = texture.toString();
        try {
            YSMMeshLibrary.bindModelTexture(firstOwner, texture);
            YSMMeshLibrary.bindModelTexture(secondOwner, texture);
            assertEquals(Set.of(), Set.copyOf(YSMMeshLibrary.releaseTexturesOfModel(firstOwner)));
            assertEquals(Set.of(texture), Set.copyOf(YSMMeshLibrary.releaseTexturesOfModel(secondOwner)));
        } finally {
            YSMMeshLibrary.releaseTexturesOfModel(firstOwner);
            YSMMeshLibrary.releaseTexturesOfModel(secondOwner);
            state("PENDING_TEXTURE_RELEASES").remove(key);
            state("TEXTURE_TOKENS").remove(key);
        }
    }
}
