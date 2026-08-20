package com.ysmef.geomodel.model;

import com.ysmef.geomodel.YSMGeoModel;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps YSM bone names to Epic Fight biped armature joints, without needing a
 * runtime Armature instance (the biped armature layout is fixed).
 *
 * Joint ids follow the order of the "joints" array in Epic Fight's
 * assets/epicfight/animmodels/entity/biped.json:
 * Root, Thigh_R, Leg_R, Knee_R, Thigh_L, Leg_L, Knee_L, Torso, Chest, Head,
 * Shoulder_R, Arm_R, Hand_R, Tool_R, Elbow_R, Shoulder_L, Arm_L, Hand_L, Tool_L, Elbow_L.
 */
public final class YSMJointMapper {

    private static final Map<String, Integer> JOINT_IDS = createJointIds();
    private static final Map<String, String> STANDARD_MAPPING = createMapping();

    private static Map<String, Integer> createJointIds() {
        Map<String, Integer> map = new HashMap<>();
        map.put("Root", 0);
        map.put("Thigh_R", 1);
        map.put("Leg_R", 2);
        map.put("Knee_R", 3);
        map.put("Thigh_L", 4);
        map.put("Leg_L", 5);
        map.put("Knee_L", 6);
        map.put("Torso", 7);
        map.put("Chest", 8);
        map.put("Head", 9);
        map.put("Shoulder_R", 10);
        map.put("Arm_R", 11);
        map.put("Hand_R", 12);
        map.put("Tool_R", 13);
        map.put("Elbow_R", 14);
        map.put("Shoulder_L", 15);
        map.put("Arm_L", 16);
        map.put("Hand_L", 17);
        map.put("Tool_L", 18);
        map.put("Elbow_L", 19);
        return map;
    }

    private static Map<String, String> createMapping() {
        Map<String, String> map = new HashMap<>();
        map.put("root", "Root");
        map.put("allbody", "Torso");
        map.put("body", "Torso");
        map.put("waist", "Torso");
        map.put("torso", "Torso");
        map.put("downbody", "Torso");
        map.put("hip", "Torso");
        map.put("hips", "Torso");
        map.put("pelvis", "Torso");
        map.put("skirt", "Torso");
        map.put("upbody", "Chest");
        map.put("upperbody", "Chest");
        map.put("chest", "Chest");
        map.put("breast", "Chest");
        map.put("boob", "Chest");
        map.put("collar", "Chest");
        map.put("backpack", "Chest");
        map.put("cape", "Chest");
        map.put("elytra", "Chest");
        map.put("elytralocator", "Chest");
        map.put("arm", "Chest");
        map.put("leg", "Torso");
        map.put("center", "Root");

        map.put("allhead", "Head");
        map.put("head", "Head");

        map.put("leftarm", "Arm_L");
        map.put("armleft", "Arm_L");
        map.put("rightarm", "Arm_R");
        map.put("armright", "Arm_R");

        map.put("leftforearm", "Hand_L");
        map.put("forearmleft", "Hand_L");
        map.put("lefthand", "Hand_L");
        map.put("handleft", "Hand_L");
        map.put("rightforearm", "Hand_R");
        map.put("forearmright", "Hand_R");
        map.put("righthand", "Hand_R");
        map.put("handright", "Hand_R");

        map.put("lefthandlocator", "Tool_L");
        map.put("leftitem", "Tool_L");
        map.put("itemleft", "Tool_L");
        map.put("righthandlocator", "Tool_R");
        map.put("rightitem", "Tool_R");
        map.put("itemright", "Tool_R");

        map.put("leftleg", "Thigh_L");
        map.put("legleft", "Thigh_L");
        map.put("rightleg", "Thigh_R");
        map.put("legright", "Thigh_R");

        map.put("leftlowerleg", "Leg_L");
        map.put("lowerlegleft", "Leg_L");
        map.put("leftcalf", "Leg_L");
        map.put("leftfoot", "Leg_L");
        map.put("footleft", "Leg_L");
        map.put("rightlowerleg", "Leg_R");
        map.put("lowerlegright", "Leg_R");
        map.put("rightcalf", "Leg_R");
        map.put("rightfoot", "Leg_R");
        map.put("footright", "Leg_R");
        return map;
    }

    private YSMJointMapper() {}

    /**
     * The normalized mapping-table key of a bone name, or null when the name
     * does not map to any EF joint.
     *
     * Blockbench-style authors often append variant markers to primary bones:
     * zhiban_new_year's legs are named "LeftLegY2" / "LeftLowerLegY" /
     * "LeftFootH2" (empty "LeftLeg" shells carry the locator hierarchy). The
     * plain normalization (lower case, digits stripped) leaves the trailing
     * letter in place ("leftlegy2" -> "leftlegy"), which is NOT in the table,
     * so those geometry-carrying bones were treated as unmapped decorations and
     * the whole limb lost its pivot. When the normalized name misses, one
     * trailing letter is additionally stripped and the lookup retried
     * ("leftlegy2" -> "leftlegy" -> "leftleg"). Only ONE letter is ever
     * stripped: real multi-letter suffixes ("LeftHandLocator", "chestnut",
     * "headdressLeftTop"...) must keep missing and fall back to the ancestor
     * walk instead of being mis-mapped onto an unrelated joint.
     */
    private static String mappingKey(String boneName) {
        String normalized = normalize(boneName);
        if (STANDARD_MAPPING.containsKey(normalized)) {
            return normalized;
        }
        if (!normalized.isEmpty() && Character.isLetter(normalized.charAt(normalized.length() - 1))) {
            String withoutTailLetter = normalized.substring(0, normalized.length() - 1);
            if (STANDARD_MAPPING.containsKey(withoutTailLetter)) {
                return withoutTailLetter;
            }
        }
        return null;
    }

    /**
     * Resolve the EF joint id for a YSM bone, walking up the bone hierarchy when
     * the bone name itself is not a standard body part.
     */
    public static int resolveJointId(YSMGeoModel.Bone bone) {
        for (YSMGeoModel.Bone current = bone; current != null; current = current.parent) {
            String key = mappingKey(current.name);
            if (key != null) {
                return JOINT_IDS.get(STANDARD_MAPPING.get(key));
            }
        }
        return JOINT_IDS.get("Root");
    }

    /**
     * Whether the bone's own name maps directly to an EF joint (without walking up
     * to ancestors). Directly mapped bones are driven by Epic Fight's animations;
     * other bones follow YSM's evaluated bone transforms on top.
     */
    public static boolean isDirectlyMapped(YSMGeoModel.Bone bone) {
        return mappingKey(bone.name) != null;
    }

    /**
     * Normalizes a bone name for mapping lookup: lower case, underscores/spaces
     * removed, and trailing digits stripped so alternate-form subtrees of a model
     * (e.g. "LeftArm2" of a fox variant) map to the same EF joint as the primary
     * form ("LeftArm").
     *
     * YSM models may additionally name the default form's geometry
     * "&lt;bone&gt;_Default" (e.g. winefox_momo's "RightArm_Default" carries the actual
     * upper-arm cubes while "RightArm" is an empty locator shell). The suffix is
     * stripped so the default-form geometry maps to its EF joint instead of
     * being treated as an unmapped decoration - without this the whole limb
     * loses its geometry and the bind-armature pivot falls onto whatever stray
     * replacement-form bone resolves to the joint (visible as a badly detached
     * arm).
     */
    private static String normalize(String boneName) {
        String suffixStripped = boneName.toLowerCase().replace("_default", "");
        String normalized = suffixStripped.replace("_", "").replace(" ", "");
        int end = normalized.length();
        while (end > 0 && Character.isDigit(normalized.charAt(end - 1))) {
            end--;
        }
        return normalized.substring(0, end);
    }
}
