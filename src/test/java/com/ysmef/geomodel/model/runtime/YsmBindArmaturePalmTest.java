package com.ysmef.geomodel.model.runtime;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class YsmBindArmaturePalmTest {
    @Test
    void namedHandGeometryCentersBothPalmsWithoutAccessoryOrWeaponVertices() {
        YSMRuntimeModel.BoneRt[] bones = {
                bone("RightForeArm", 12, true), bone("RightHand_Default", 12, true),
                bone("sq", 12, false), bone("sword", 12, false),
                bone("LeftHand", 17, true)
        };
        Map<Integer, List<Vector3f>> geometry = Map.of(
                0, List.of(v(0.2f, 1.0f), v(0.2f, 0.8f)),
                1, List.of(v(0.3f, 0.6f), v(0.5f, 0.4f)),
                2, List.of(v(5.0f, 0.5f), v(6.0f, 0.5f)),
                3, List.of(v(0.4f, -3.0f)),
                4, List.of(v(-0.3f, 0.6f), v(-0.5f, 0.4f)));

        Vector3f right = YsmBindArmature.geometricPalm(bones, geometry, 12, v(0.2f, 1.0f));
        Vector3f left = YsmBindArmature.geometricPalm(bones, geometry, 17, v(-0.2f, 1.0f));
        assertEquals(0.4f, right.x, 0.0001f);
        assertEquals(0.5f, right.y, 0.0001f);
        assertEquals(-0.4f, left.x, 0.0001f);
        assertEquals(0.5f, left.y, 0.0001f);
    }

    @Test
    void unnamedHandFallsBackToDistalMappedForearm() {
        YSMRuntimeModel.BoneRt[] bones = {
                bone("arm", 12, true), bone("opaqueAccessory", 12, false)
        };
        Map<Integer, List<Vector3f>> geometry = Map.of(
                0, List.of(v(0.2f, 0.8f), v(0.3f, 0.2f)),
                1, List.of(v(7.0f, 0.5f), v(8.0f, 0.5f)));

        Vector3f palm = YsmBindArmature.geometricPalm(bones, geometry, 12, v(0.2f, 1.0f));
        assertEquals(0.25f, palm.x, 0.0001f);
        assertEquals(0.5f, palm.y, 0.0001f);
    }

    @Test
    void missingGeometryReturnsNullForWristFallback() {
        assertNull(YsmBindArmature.geometricPalm(new YSMRuntimeModel.BoneRt[0], Map.of(),
                12, v(0.2f, 1.0f)));
    }

    private static YSMRuntimeModel.BoneRt bone(String name, int joint, boolean mapped) {
        YSMRuntimeModel.BoneRt bone = new YSMRuntimeModel.BoneRt();
        bone.name = name;
        bone.joint = joint;
        bone.mapped = mapped;
        return bone;
    }

    private static Vector3f v(float x, float y) {
        return new Vector3f(x, y, 0.0f);
    }
}
