package com.ysmef.geomodel.model.runtime;

import com.ysmef.geomodel.YSMGeoCompat;
import com.ysmef.geomodel.model.EFMeshJsonWriter;
import com.ysmef.geomodel.model.YSMMesh;
import org.joml.Vector3f;
import yesman.epicfight.api.animation.Joint;
import yesman.epicfight.api.animation.Pose;
import yesman.epicfight.api.client.model.MeshPart;
import yesman.epicfight.api.client.model.VertexBuilder;
import yesman.epicfight.api.model.Armature;
import yesman.epicfight.api.utils.math.OpenMatrix4f;
import yesman.epicfight.gameasset.Armatures;
import yesman.epicfight.model.armature.HumanoidArmature;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-model bind-pose retarget for converted YSM/GEO meshes.
 *
 * Ported from the main project (YSM_EpicFight_Compat, YsmBindArmature):
 * the model-part pose-correction architecture that makes Epic Fight's combat
 * rotations pivot at the model's own joints (correct torso/limb correspondence)
 * instead of the Steve bind pose.
 *
 * Epic Fight's combat animations are joint-local rotations that pivot around the
 * bind pose joints of the biped armature (Steve proportions). Converted meshes
 * carry their own joint pivots (shoulder width, limb lengths, torso height...),
 * and every vertex is rigidly bound to one EF joint, so a swung limb rigidly
 * rotates around a Steve joint whose pivot does not sit inside the model's
 * geometry - the visible arm/leg detachment during weapon swings.
 *
 * The skinning pipeline uploads poseWorld x invBindWorld (see
 * VanillaComputeShaderSetup#drawWithShader); the bind pose is therefore fully
 * retargetable: a per-model armature with the SAME 20-joint topology/names/ids
 * but joint translations derived from the model's own geometry makes every
 * combat rotation pivot at the model's own joints, while the bind pose (identity
 * animation) still maps to the authored mesh shape unchanged (pose x toOrigin
 * degenerates to identity).
 *
 * Pivot selection is GEOMETRY-DRIVEN, not name-driven: bone names are unreliable
 * (alternate-form bones like "RightArm2" carry bind pivots in the base form's
 * space, and locator/decoration bones resolve to the same joint), so each joint's
 * pivot is computed from the converted mesh itself:
 *
 * - Thigh_R/L: top of the thigh geometry (the hip); the hip = their midpoint.
 *   Root and Torso both pivot at the hip (the reference biped has them there).
 * - Head: top of the chest geometry (the neck).
 * - Chest: midpoint between the hip and the neck (the reference biped's Chest
 *   joint sits at the chest center, halfway between the waist and the neck).
 * - Arm_R/L: top of the upper-arm geometry (the shoulder).
 * - Hand_R/L: top of the forearm+hand geometry (the elbow - Epic Fight's
 *   Hand_R joint is the forearm joint).
 * - Leg_R/L: top of the lower-leg geometry (the knee).
 * - Shoulder_R/L follow Arm_R/L, Elbow_R/L and Knee_R/L follow Hand_R/L and
 *   Leg_R/L (the duplicated joints of the reference armature).
 * - Tool_R/L: top of the hand-bone geometry (the wrist) when a separately
 *   named hand bone exists, otherwise the Hand_R/L elbow pivot.
 *
 * All positions are read from the RUNTIME mesh: Epic Fight's JsonAssetLoader
 * applies the Blender-to-Minecraft rotation (BLENDER_TO_MINECRAFT_COORD, -90 deg
 * about X) while loading, so at runtime the mesh positions, the biped armature's
 * joint local transforms and its pose matrices all live in the SAME Minecraft
 * frame (up = +Y). The pivots below are therefore Minecraft-frame world
 * positions; copyHierarchy expresses them in the parent joint's (Minecraft
 * frame) local space. The reference rotation part of every local transform is
 * preserved, so the joint frames (and thus every animation arc) keep the exact
 * orientation Epic Fight expects.
 *
 * Per frame Epic Fight applies the animation pose to the entity's own armature
 * (captured by YsmArmaturePoseMixin). YSMMesh#draw then re-evaluates that pose
 * on this model's bind armature and draws with its pose matrices. Only the
 * translation components change, so the animations stay intact. YSM script
 * deltas (part transforms) are bind-space and orthogonal to this retarget.
 */
public final class YsmBindArmature {

    private record Entry(YSMRuntimeModel runtime, HumanoidArmature armature) {}

    /** modelId -> re-bound armature (rebuilt when the runtime model is recompiled). */
    private static final Map<String, Entry> BY_MODEL = new ConcurrentHashMap<>();

    /** Models whose bind armature could not be built; not retried until invalidateAll. */
    private static final Set<String> UNSUPPORTED = ConcurrentHashMap.newKeySet();

    /**
     * entity armature instance -> the pose Epic Fight last applied to it.
     *
     * Access-ordered so the staleness guard can evict ONE least-recently-used
     * entry. Clearing the whole map at the cap (the previous behaviour) dropped
     * every other entity's pose for that frame, silently switching their pose
     * correction off until the next Armature#setPose.
     */
    private static final Map<Armature, Pose> POSES =
            new java.util.LinkedHashMap<>(64, 0.75f, true);

    /** Stale-entry guard: armatures of unloaded entities accumulate otherwise. */
    private static final int POSE_MAP_CAP = 256;

    private YsmBindArmature() {}

    // ------------------------------------------------------------------
    // Per-frame pose capture
    // ------------------------------------------------------------------

    /** Called from YsmArmaturePoseMixin (Armature#setPose TAIL), render thread. */
    public static void onArmatureSetPose(Armature armature, Pose pose) {
        if (armature == null || pose == null) {
            return;
        }
        synchronized (POSES) {
            while (POSES.size() >= POSE_MAP_CAP) {
                java.util.Iterator<Armature> oldest = POSES.keySet().iterator();
                if (!oldest.hasNext()) {
                    break;
                }
                oldest.next();
                oldest.remove();
            }
            POSES.put(armature, pose);
        }
    }

    /** The pose Epic Fight applied to this armature instance, or null. */
    public static Pose findPose(Armature armature) {
        if (armature == null) {
            return null;
        }
        synchronized (POSES) {
            return POSES.get(armature);
        }
    }

    // ------------------------------------------------------------------
    // Per-model re-bound armature
    // ------------------------------------------------------------------

    /**
     * The bind armature of a model, built lazily. Stale entries (the model was
     * re-converted, a new YSMRuntimeModel instance replaced the old one) are
     * detected by runtime-instance identity and rebuilt.
     */
    public static HumanoidArmature getArmature(String modelId, YSMMesh mesh) {
        if (modelId == null || UNSUPPORTED.contains(modelId)) {
            return null;
        }
        YSMRuntimeModel runtime = YSMRuntimeModel.get(modelId);
        if (runtime == null || runtime.bones.length == 0) {
            return null;
        }
        Entry entry = BY_MODEL.get(modelId);
        if (entry != null && entry.runtime == runtime) {
            return entry.armature;
        }
        HumanoidArmature built;
        try {
            built = build(modelId, runtime, mesh);
        } catch (Throwable t) {
            // A malformed mesh must never break the entity render: log once and
            // fall back to the un-corrected armature instead of re-throwing
            // every frame (the render thread would die inside YSMMesh#draw).
            YSMGeoCompat.LOGGER.warn(
                    "YSM-GEO Compat: failed to build the bind armature for '{}', pose correction disabled for this model",
                    modelId, t);
            UNSUPPORTED.add(modelId);
            return null;
        }
        if (built == null) {
            return null;
        }
        BY_MODEL.put(modelId, new Entry(runtime, built));
        return built;
    }

    /** Forget every re-bound armature and captured pose (model reload paths). */
    public static void invalidateAll() {
        BY_MODEL.clear();
        UNSUPPORTED.clear();
        synchronized (POSES) {
            POSES.clear();
        }
    }

    // ------------------------------------------------------------------
    // Geometry-driven pivot computation
    // ------------------------------------------------------------------

    // Joint ids come from JointTable (the single source of truth shared with
    // YSMJointMapper): a renumbered Epic Fight biped can no longer bind geometry
    // to the wrong joints in one place and not the other.
    private static final int JOINT_ROOT = com.ysmef.geomodel.model.JointTable.ID_ROOT;
    private static final int JOINT_THIGH_R = com.ysmef.geomodel.model.JointTable.ID_THIGH_R;
    private static final int JOINT_LEG_R = com.ysmef.geomodel.model.JointTable.ID_LEG_R;
    private static final int JOINT_KNEE_R = com.ysmef.geomodel.model.JointTable.ID_KNEE_R;
    private static final int JOINT_THIGH_L = com.ysmef.geomodel.model.JointTable.ID_THIGH_L;
    private static final int JOINT_LEG_L = com.ysmef.geomodel.model.JointTable.ID_LEG_L;
    private static final int JOINT_KNEE_L = com.ysmef.geomodel.model.JointTable.ID_KNEE_L;
    private static final int JOINT_TORSO = com.ysmef.geomodel.model.JointTable.ID_TORSO;
    private static final int JOINT_CHEST = com.ysmef.geomodel.model.JointTable.ID_CHEST;
    private static final int JOINT_HEAD = com.ysmef.geomodel.model.JointTable.ID_HEAD;
    private static final int JOINT_SHOULDER_R = com.ysmef.geomodel.model.JointTable.ID_SHOULDER_R;
    private static final int JOINT_ARM_R = com.ysmef.geomodel.model.JointTable.ID_ARM_R;
    private static final int JOINT_HAND_R = com.ysmef.geomodel.model.JointTable.ID_HAND_R;
    private static final int JOINT_TOOL_R = com.ysmef.geomodel.model.JointTable.ID_TOOL_R;
    private static final int JOINT_ELBOW_R = com.ysmef.geomodel.model.JointTable.ID_ELBOW_R;
    private static final int JOINT_SHOULDER_L = com.ysmef.geomodel.model.JointTable.ID_SHOULDER_L;
    private static final int JOINT_ARM_L = com.ysmef.geomodel.model.JointTable.ID_ARM_L;
    private static final int JOINT_HAND_L = com.ysmef.geomodel.model.JointTable.ID_HAND_L;
    private static final int JOINT_TOOL_L = com.ysmef.geomodel.model.JointTable.ID_TOOL_L;
    private static final int JOINT_ELBOW_L = com.ysmef.geomodel.model.JointTable.ID_ELBOW_L;

    /** Normalized bone names that denote the hand bone itself (its top = the wrist). */
    private static final Set<String> HAND_BONE_NAMES = new HashSet<>(List.of(
            "righthand", "handright", "lefthand", "handleft"));

    /**
     * Directly-mapped accessory bones whose geometry extends far beyond the body
     * (a cape/wing reaches above the head, a backpack sits behind/above the
     * shoulders), so they must not contribute to the segment pivots - otherwise
     * the "neck" (top of the chest geometry) lands on the accessory and the head
     * detaches from the body.
     *
     * Only reachable through the unmapped fallback tier: these names resolve to
     * the Chest joint by walking the ancestor chain, and a model can also map
     * them directly, in which case they are excluded here too.
     */
    private static final Set<String> PIVOT_EXCLUDED_BONE_NAMES = new HashSet<>(List.of(
            "cape", "elytra", "elytralocator", "backpack"));

    /**
     * Weapon-ish name fragments excluded from the geometry collection: a held
     * item (sword, spoon, phone...) resolves to the Hand joint but extends far
     * past the fist, so it must not drag the elbow/wrist pivot - and, in the
     * unmapped fallback tier, must not become the only "hand geometry" of a
     * model whose real hand bones carry no name the joint mapper knows.
     */
    private static final String[] WEAPON_NAME_PARTS = {
            "sword", "gun", "weapon", "blade", "knife", "axe", "bow", "tool", "item", "besom", "key", "wand",
            "staff", "spear", "dagger", "katana", "shield", "scythe", "sickle", "hammer", "pole", "stick",
            "spoon", "fork", "cup", "coaster", "phone", "computer", "book", "grimoire", "lantern", "circle",
            "arrow", "quiver", "mic"
    };

    /** Ring height tolerance for the segment-top centroid (Minecraft frame, up = +Y). */
    private static final float TOP_RING_EPSILON = 0.05f;

    /**
     * A chest "top" at or below hip height + this margin means the model has no
     * real chest/upper-body geometry (its Chest joint only carries accessories
     * or nothing); the neck is then derived from the head geometry instead.
     */
    private static final float CHEST_TOP_MIN_ABOVE_HIP = 0.1f;

    private static HumanoidArmature build(String modelId, YSMRuntimeModel runtime, YSMMesh mesh) {
        HumanoidArmature ref;
        try {
            ref = Armatures.BIPED.get();
        } catch (Throwable t) {
            YSMGeoCompat.LOGGER.warn(
                    "YSM-GEO Compat: cannot load the biped armature, bind retarget disabled for '{}'", modelId);
            return null;
        }
        if (ref == null) {
            return null;
        }

        GeometryData geometry = collectGeometry(runtime, mesh);
        Map<Integer, List<Vector3f>> byJoint = geometry.byJoint();
        Map<Integer, List<Vector3f>> byBone = geometry.byBone();

        // Segment pivots derived from the model's own geometry (Minecraft frame).
        Vector3f thighR = topOf(geometryOf(byJoint, JOINT_THIGH_R));
        Vector3f thighL = topOf(geometryOf(byJoint, JOINT_THIGH_L));
        Vector3f hip = midpoint(thighR, thighL);
        Vector3f neck = topOf(geometryOf(byJoint, JOINT_CHEST));
        // Chest-geometry degradation: models without a chest/upper-body bone
        // (the tiny Touhou Q-style maids have only head/body/limb bones) leave
        // the Chest joint empty, so its "top" is null or sits at hip height.
        // Falling back to Epic Fight's Steve reference position then pivots the
        // head far above the model. Derive the neck from the model instead: the
        // midpoint between the hip and the top of the head geometry.
        if (neck == null || hip == null || neck.y <= hip.y + CHEST_TOP_MIN_ABOVE_HIP) {
            Vector3f headTop = topOf(geometryOf(byJoint, JOINT_HEAD));
            neck = headTop != null && hip != null ? midpoint(hip, headTop) : null;
        }
        // Anchor the neck's x/z on the hip center line. The Chest joint's
        // geometry can include mapped accessory bones ("backpack", "breast",
        // "collar"... are direct table entries) whose tops sit off the torso
        // center line - e.g. the astronaut winefox's backpack pulls the neck
        // centroid forward (z=+0.1), making the head swing around an offset
        // point (head-body separation). A neck on the torso center line keeps
        // the head pivot where the neck actually is.
        if (neck != null && hip != null) {
            neck = new Vector3f(hip.x, neck.y, hip.z);
        }
        Vector3f chest = midpoint(hip, neck);
        Vector3f kneeR = topOf(geometryOf(byJoint, JOINT_LEG_R));
        Vector3f kneeL = topOf(geometryOf(byJoint, JOINT_LEG_L));
        Vector3f shoulderR = topOf(geometryOf(byJoint, JOINT_ARM_R));
        Vector3f shoulderL = topOf(geometryOf(byJoint, JOINT_ARM_L));
        Vector3f elbowR = topOf(geometryOf(byJoint, JOINT_HAND_R));
        Vector3f elbowL = topOf(geometryOf(byJoint, JOINT_HAND_L));
        Vector3f wristR = handPivot(runtime, byBone, JOINT_HAND_R);
        Vector3f wristL = handPivot(runtime, byBone, JOINT_HAND_L);

        // Missing segment joints: models without separate forearm/lower-leg
        // bones (again the tiny Q-style maids - a single "legLeft" bone is the
        // whole leg) leave the Hand/Leg joints without geometry, and the Steve
        // reference pivot (adult proportions) is completely wrong for them.
        // Approximate the elbow/knee as the midpoint between the segment top
        // (shoulder/hip) and the segment bottom - exactly the real joint when
        // the whole limb is one bone.
        if (kneeR == null) {
            kneeR = midpoint(thighR, bottomOf(geometryOf(byJoint, JOINT_THIGH_R)));
        }
        if (kneeL == null) {
            kneeL = midpoint(thighL, bottomOf(geometryOf(byJoint, JOINT_THIGH_L)));
        }
        if (elbowR == null) {
            elbowR = midpoint(shoulderR, bottomOf(geometryOf(byJoint, JOINT_ARM_R)));
        }
        if (elbowL == null) {
            elbowL = midpoint(shoulderL, bottomOf(geometryOf(byJoint, JOINT_ARM_L)));
        }

        Map<Integer, OpenMatrix4f> pivots = new HashMap<>();
        putPivot(pivots, JOINT_ROOT, hip);
        putPivot(pivots, JOINT_TORSO, hip);
        putPivot(pivots, JOINT_CHEST, chest);
        putPivot(pivots, JOINT_HEAD, neck);
        putPivot(pivots, JOINT_THIGH_R, thighR);
        putPivot(pivots, JOINT_THIGH_L, thighL);
        putPivot(pivots, JOINT_LEG_R, kneeR);
        putPivot(pivots, JOINT_LEG_L, kneeL);
        putPivot(pivots, JOINT_KNEE_R, kneeR);
        putPivot(pivots, JOINT_KNEE_L, kneeL);
        putPivot(pivots, JOINT_ARM_R, shoulderR);
        putPivot(pivots, JOINT_ARM_L, shoulderL);
        putPivot(pivots, JOINT_HAND_R, elbowR);
        putPivot(pivots, JOINT_HAND_L, elbowL);
        putPivot(pivots, JOINT_SHOULDER_R, shoulderR);
        putPivot(pivots, JOINT_SHOULDER_L, shoulderL);
        putPivot(pivots, JOINT_ELBOW_R, elbowR);
        putPivot(pivots, JOINT_ELBOW_L, elbowL);
        putPivot(pivots, JOINT_TOOL_R, wristR != null ? wristR : elbowR);
        putPivot(pivots, JOINT_TOOL_L, wristL != null ? wristL : elbowL);

        if (BIND_PIVOT_LOG_LOGGED.add(modelId)) {
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: [bind] model='{}' pivots root={},torso={},chest={},head={},shoulderR={},shoulderL={},elbowR={},elbowL={},wristR={},wristL={}",
                    modelId,
                    fmt(hip), fmt(hip), fmt(chest), fmt(neck), fmt(shoulderR), fmt(shoulderL),
                    fmt(elbowR), fmt(elbowL), fmt(wristR), fmt(wristL));
        }

        Map<String, Joint> jointMap = new HashMap<>();
        Joint newRoot = copyHierarchy(ref.rootJoint, new OpenMatrix4f(), pivots, jointMap, true);
        newRoot.initOriginTransform(new OpenMatrix4f());
        if (DIAG_JOINTS_LOGGED.add(modelId)) {
            // Per-joint pivot diag: the exact world position every joint was
            // re-anchored to (Minecraft frame, up = +Y). A head that swings
            // around the chest instead of the neck is immediately visible here.
            StringBuilder sb = new StringBuilder();
            sb.append("YSM-GEO Compat: [diag] bind armature joints: model=").append(modelId);
            Joint joint = newRoot;
            java.util.ArrayDeque<Joint> queue = new java.util.ArrayDeque<>();
            queue.add(joint);
            while (!queue.isEmpty()) {
                joint = queue.poll();
                OpenMatrix4f pivot = pivots.get(joint.getId());
                sb.append(" ").append(joint.getName()).append("=");
                if (pivot == null) {
                    sb.append("ref");
                } else {
                    sb.append(String.format("(%.3f,%.3f,%.3f)", pivot.m30, pivot.m31, pivot.m32));
                }
                queue.addAll(joint.getSubJoints());
            }
            YSMGeoCompat.LOGGER.info(sb.toString());
        }
        if (DIAG_LOGGED.add(modelId)) {
            float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
            for (OpenMatrix4f pivot : pivots.values()) {
                if (pivot == null) {
                    continue;
                }
                minX = Math.min(minX, pivot.m30);
                minY = Math.min(minY, pivot.m31);
                minZ = Math.min(minZ, pivot.m32);
                maxX = Math.max(maxX, pivot.m30);
                maxY = Math.max(maxY, pivot.m31);
                maxZ = Math.max(maxZ, pivot.m32);
            }
            YSMGeoCompat.LOGGER.info(
                    "YSM-GEO Compat: [diag] bind armature built: model={} bones={} joints={} pivotRange=([{},{}],[{},{}],[{},{}])",
                    modelId, runtime.bones.length, ref.getJointNumber(),
                    minX, maxX, minY, maxY, minZ, maxZ);
        }
        return new HumanoidArmature("ysm_bind_" + modelId, ref.getJointNumber(), newRoot, jointMap);
    }

    private static final java.util.Set<String> DIAG_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> DIAG_JOINTS_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.Set<String> BIND_PIVOT_LOG_LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static String fmt(Vector3f v) {
        if (v == null) {
            return "null";
        }
        return String.format("(%.3f,%.3f,%.3f)", v.x, v.y, v.z);
    }

    /** Armature-frame world position of a pivot, or null to keep the reference translation. */
    private static void putPivot(Map<Integer, OpenMatrix4f> pivots, int joint, Vector3f pos) {
        if (pos == null) {
            return;
        }
        OpenMatrix4f matrix = new OpenMatrix4f();
        // OpenMatrix4f is row-vector convention: the translation lives in the
        // LAST ROW (m30/m31/m32), not m03/m13/m23.
        matrix.m30 = pos.x;
        matrix.m31 = pos.y;
        matrix.m32 = pos.z;
        pivots.put(joint, matrix);
    }

    /**
     * Collect the bind-pose geometry of every mesh part, keyed both by the
     * runtime bone index (for the hand-bone lookup) and by EF joint (for the
     * segment pivots). At runtime the mesh positions are in Minecraft frame
     * (up = +Y, Epic Fight's loader rotated them out of the JSON's Blender frame
     * while loading), the same frame the pivot matrices are expressed in.
     *
     * The joint map is two-tier. Directly-mapped body bones fill the preferred
     * tier, and every other bone fills the fallback tier; a joint is served from
     * the preferred tier when it has anything there and from the fallback tier
     * only when it has nothing at all.
     *
     * The fallback tier is what makes models with unrecognized bone naming work.
     * A model authored in Chinese ("头", "左手") maps almost no bone directly, so
     * a mapped-only collection left every segment pivot empty and the whole
     * armature fell back to Steve's adult proportions - the model then bends at
     * points that are not its joints. The fallback still resolves each bone to a
     * joint through its ancestor chain, so it supplies geometry for exactly the
     * joints the model itself has no better answer for.
     *
     * The tiering is also why the filters below matter more than they did: with
     * the fallback in place, a decorative or held-item bone can now reach a
     * joint that previously stayed empty. So variant bones (alternate forms),
     * bones hidden in the default form, accessories and weapon bones are all
     * dropped before either tier is filled.
     */
    private static GeometryData collectGeometry(YSMRuntimeModel runtime, YSMMesh mesh) {
        Map<Integer, List<Vector3f>> mappedByJoint = new HashMap<>();
        Map<Integer, List<Vector3f>> unmappedByJoint = new HashMap<>();
        Map<Integer, List<Vector3f>> byBone = new HashMap<>();
        float[] positions = mesh.positions();

        // Names of the bones hidden in the model's default (battle-mode) form:
        // those parts never render, so their geometry must not pollute the
        // segment pivots - e.g. a shipgirl's rigging (hidden by default) sits
        // forward of the chest and would otherwise drag the neck pivot off the
        // body, and a hidden variant's bound geometry sits at the base form's
        // or a completely different position.
        Set<String> hiddenBones = runtime.defaultHiddenBoneNames();

        // Pass 1: count the bind-pose vertices of every bone and collect the raw
        // (case-preserving) bone names. The counts tell alternate-form variant
        // bones apart from genuinely numbered primary bones: Blockbench
        // auto-numbers duplicate names, and models often keep an EMPTY parent
        // bone ("LeftForeArm", 0 cubes) whose actual geometry lives in the
        // numbered child ("LeftForeArm5", 12 cubes) - that numbered bone is the
        // primary geometry and must participate in the pivot computation, while
        // a numbered bone that shadows a GEOMETRY-CARRYING twin ("RightArm2"
        // next to a solid "RightArm") is an alternate-form variant to skip.
        int[] vertexCountByBone = new int[runtime.bones.length];
        Set<String> rawNames = new HashSet<>();
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer boneIdx = runtime.boneIndex.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (boneIdx == null || boneIdx >= runtime.bones.length) {
                continue;
            }
            rawNames.add(runtime.bones[boneIdx].name);
            vertexCountByBone[boneIdx] += entry.getValue().getVertices().size();
        }

        // Pass 2: collect the geometry, skipping only true variant bones.
        for (Map.Entry<String, MeshPart> entry : mesh.getPartEntrySetSafe()) {
            String partName = entry.getKey();
            if (!partName.startsWith(EFMeshJsonWriter.BONE_PART_PREFIX)) {
                continue;
            }
            Integer boneIdx = runtime.boneIndex.get(partName.substring(EFMeshJsonWriter.BONE_PART_PREFIX.length()));
            if (boneIdx == null || boneIdx >= runtime.bones.length) {
                continue;
            }
            YSMRuntimeModel.BoneRt bone = runtime.bones[boneIdx];
            if (hiddenBones.contains(bone.name)) {
                continue;
            }
            if (PIVOT_EXCLUDED_BONE_NAMES.contains(normalize(bone.name))) {
                continue;
            }
            if (isWeaponBone(normalize(bone.name))) {
                continue;
            }
            // Alternate-form variant bones ("RightLeg2", "Head2"...) carry bind
            // geometry at the base form's (or a completely different) position;
            // they would pollute the per-joint pivot computation. A trailing
            // digit alone is NOT enough to identify them though: TLM GEO models
            // are often authored with Blockbench, which auto-numbers duplicate
            // bone names, so a model's actual primary arm bone may well be
            // called "LeftArm2" with no digit-less twin. Only skip a numbered
            // bone when a same-named (digits stripped, case preserved) bone
            // exists AND carries geometry of its own - otherwise the numbered
            // bone is the primary geometry (an empty "LeftForeArm" parent with
            // all real cubes in "LeftForeArm5") and skipping it would leave the
            // whole limb without a pivot (the visible one-armed detachment).
            if (isDigitSuffixed(bone.name)) {
                String base = stripTrailingDigitsRaw(bone.name);
                Integer baseIdx = runtime.boneIndex.get(base);
                if (rawNames.contains(base) && baseIdx != null
                        && vertexCountByBone[baseIdx] > 0) {
                    continue;
                }
            }
            List<Vector3f> boneList = byBone.computeIfAbsent(boneIdx, k -> new ArrayList<>());
            Map<Integer, List<Vector3f>> target = bone.mapped ? mappedByJoint : unmappedByJoint;
            List<Vector3f> list = target.computeIfAbsent(bone.joint, k -> new ArrayList<>());
            for (VertexBuilder vb : entry.getValue().getVertices()) {
                int p = vb.position * 3;
                if (p + 2 < positions.length) {
                    Vector3f v = new Vector3f(positions[p], positions[p + 1], positions[p + 2]);
                    boneList.add(v);
                    list.add(v);
                }
            }
        }

        // Per joint: prefer the mapped geometry; fall back to the unmapped bones
        // only when the joint has no mapped geometry at all.
        Map<Integer, List<Vector3f>> byJoint = new HashMap<>(unmappedByJoint);
        for (Map.Entry<Integer, List<Vector3f>> entry : mappedByJoint.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                byJoint.put(entry.getKey(), entry.getValue());
            }
        }
        return new GeometryData(byJoint, byBone);
    }

    /**
     * byJoint: joint-indexed geometry (mapped preferred, unmapped as the
     * fallback) for the segment pivots; byBone: bone-indexed for the hand-bone
     * lookup.
     */
    private record GeometryData(Map<Integer, List<Vector3f>> byJoint, Map<Integer, List<Vector3f>> byBone) {}

    private static boolean isWeaponBone(String normalized) {
        // "bow" must not match "elbow": an elbow is body geometry, and dropping
        // it would empty the joint (or thin out the arm's fallback geometry) for
        // any model that names its forearm bones after the elbow. A held bow is
        // named for the item ("bow", "itembow", "bow_left"), never for the joint
        // it hangs from.
        if (normalized.contains("elbow")) {
            return false;
        }
        for (String part : WEAPON_NAME_PARTS) {
            if (normalized.contains(part)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDigitSuffixed(String boneName) {
        return !boneName.isEmpty() && Character.isDigit(boneName.charAt(boneName.length() - 1));
    }

    /** Name with trailing digits stripped, case preserved (e.g. "LeftArm2" -> "LeftArm"). */
    private static String stripTrailingDigitsRaw(String boneName) {
        int end = boneName.length();
        while (end > 0 && Character.isDigit(boneName.charAt(end - 1))) {
            end--;
        }
        return boneName.substring(0, end);
    }

    /** All bind-pose vertices bound to one EF joint (mapped geometry preferred). */
    private static List<Vector3f> geometryOf(Map<Integer, List<Vector3f>> byJoint, int joint) {
        return byJoint.get(joint);
    }

    /**
     * The proximal end of a limb segment: the centroid of the vertices at the
     * segment's top (the ring within {@link #TOP_RING_EPSILON} of the maximum Y).
     * The runtime mesh is in Minecraft frame, so "up" = +Y (hip, knee, shoulder,
     * elbow, neck...). Returns null when the segment has no geometry.
     */
    private static Vector3f topOf(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        float maxY = -Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            maxY = Math.max(maxY, v.y);
        }
        Vector3f acc = new Vector3f();
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y >= maxY - TOP_RING_EPSILON) {
                acc.add(v);
                n++;
            }
        }
        if (n == 0) {
            return new Vector3f(vertices.get(0));
        }
        return acc.div(n);
    }

    /**
     * The distal end of a limb segment: the centroid of the vertices at the
     * segment's bottom (the ring within {@link #TOP_RING_EPSILON} of the
     * minimum Y). Used by the missing-joint fallbacks (elbow/knee of models
     * without separate forearm/lower-leg bones). Returns null when the segment
     * has no geometry.
     */
    private static Vector3f bottomOf(List<Vector3f> vertices) {
        if (vertices == null || vertices.isEmpty()) {
            return null;
        }
        float minY = Float.MAX_VALUE;
        for (Vector3f v : vertices) {
            minY = Math.min(minY, v.y);
        }
        Vector3f acc = new Vector3f();
        int n = 0;
        for (Vector3f v : vertices) {
            if (v.y <= minY + TOP_RING_EPSILON) {
                acc.add(v);
                n++;
            }
        }
        if (n == 0) {
            return new Vector3f(vertices.get(0));
        }
        return acc.div(n);
    }

    /**
     * The wrist: the top of the geometry of the separately named hand bone
     * ("righthand"/"lefthand" and their mirrors), or null when the model has no
     * such bone (the Tool joints then fall back to the Hand elbow pivot).
     */
    private static Vector3f handPivot(YSMRuntimeModel runtime, Map<Integer, List<Vector3f>> byBone, int joint) {
        for (Map.Entry<Integer, List<Vector3f>> entry : byBone.entrySet()) {
            if (runtime.bones[entry.getKey()].joint != joint) {
                continue;
            }
            String normalized = normalize(runtime.bones[entry.getKey()].name);
            if (HAND_BONE_NAMES.contains(normalized)) {
                return topOf(entry.getValue());
            }
        }
        return null;
    }

    /**
     * Delegates to the project's single normalization (YSMJointMapper). This class
     * used to carry its own copy that omitted the default-form suffix strip, so
     * "LeftHand_Default" never matched HAND_BONE_NAMES and the wrist pivot fell
     * back to the elbow (held weapons floating).
     */
    private static String normalize(String boneName) {
        return com.ysmef.geomodel.model.YSMJointMapper.normalizeBoneName(boneName);
    }

    private static Vector3f midpoint(Vector3f a, Vector3f b) {
        if (a == null) {
            return b == null ? null : new Vector3f(b);
        }
        if (b == null) {
            return new Vector3f(a);
        }
        return new Vector3f((a.x + b.x) * 0.5f, (a.y + b.y) * 0.5f, (a.z + b.z) * 0.5f);
    }

    /**
     * Copy the reference hierarchy, replacing the translation (m30/m31/m32 - the
     * last row of OpenMatrix4f's row-vector convention) of every joint that has
     * a computed pivot with the pivot offset in the parent's frame. The reference
     * rotation part of each local transform is preserved, so the joint frames
     * (and thus every animation arc) are identical to the biped's. Joints
     * without a computed pivot keep the reference local transform entirely.
     */
    private static Joint copyHierarchy(Joint refJoint, OpenMatrix4f newParentWorld,
                                       Map<Integer, OpenMatrix4f> pivots, Map<String, Joint> out, boolean root) {
        OpenMatrix4f refLocal = refJoint.getLocalTransform();
        OpenMatrix4f newLocal = new OpenMatrix4f(refLocal);
        OpenMatrix4f pivot = pivots.get(refJoint.getId());
        if (pivot != null) {
            if (root) {
                // The root has no parent: its world transform IS its local, so
                // the pivot (the model's hip) becomes the local translation directly.
                newLocal.m30 = pivot.m30;
                newLocal.m31 = pivot.m31;
                newLocal.m32 = pivot.m32;
            } else {
                // local translation = (new parent world)^-1 x pivot world, i.e. the
                // pivot offset expressed in the parent's (already re-positioned) frame.
                OpenMatrix4f parentInv = OpenMatrix4f.invert(newParentWorld, null);
                OpenMatrix4f offset = OpenMatrix4f.mul(parentInv, pivot, null);
                newLocal.m30 = offset.m30;
                newLocal.m31 = offset.m31;
                newLocal.m32 = offset.m32;
            }
        }
        Joint joint = new Joint(refJoint.getName(), refJoint.getId(), newLocal);
        out.put(joint.getName(), joint);
        OpenMatrix4f newWorld = OpenMatrix4f.mul(newParentWorld, newLocal, null);
        for (Joint child : refJoint.getSubJoints()) {
            joint.addSubJoints(copyHierarchy(child, newWorld, pivots, out, false));
        }
        return joint;
    }
}
