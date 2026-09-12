package com.ysmef.geomodel.model;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the shared Epic Fight reference biped skeleton and the project-wide
 * bone-name normalization.
 *
 * Both used to exist in more than one copy that had drifted apart: the joint ids
 * in the joint mapper and in the bind armature, and three normalize()
 * implementations of which one omitted the default-form suffix strip (so
 * "LeftHand_Default" never matched the hand-bone table and the wrist pivot fell
 * back to the elbow, leaving held weapons floating).
 */
class JointTableTest {

    @Test
    void layoutMatchesEpicFightBipedJson() {
        assertEquals(20, JointTable.COUNT);
        assertEquals(20, JointTable.NAMES.length);
        assertEquals(20, JointTable.PARENTS.length);

        assertEquals("Root", JointTable.NAMES[JointTable.ID_ROOT]);
        assertEquals("Thigh_R", JointTable.NAMES[JointTable.ID_THIGH_R]);
        assertEquals("Knee_L", JointTable.NAMES[JointTable.ID_KNEE_L]);
        assertEquals("Torso", JointTable.NAMES[JointTable.ID_TORSO]);
        assertEquals("Chest", JointTable.NAMES[JointTable.ID_CHEST]);
        assertEquals("Head", JointTable.NAMES[JointTable.ID_HEAD]);
        assertEquals("Elbow_L", JointTable.NAMES[JointTable.ID_ELBOW_L]);
    }

    @Test
    void parentsFormATreeRootedAtRoot() {
        assertEquals(-1, JointTable.PARENTS[JointTable.ID_ROOT], "the root has no parent");
        for (int i = 0; i < JointTable.COUNT; i++) {
            int parent = JointTable.PARENTS[i];
            assertTrue(parent >= -1 && parent < JointTable.COUNT, "parent out of range at " + i);
            assertFalse(parent == i, "a joint cannot parent itself: " + JointTable.NAMES[i]);
        }
        // walking up from any joint must reach the root without cycling
        for (int i = 0; i < JointTable.COUNT; i++) {
            Set<Integer> seen = new HashSet<>();
            int cursor = i;
            while (cursor != -1) {
                assertTrue(seen.add(cursor), "parent chain cycles at " + JointTable.NAMES[i]);
                cursor = JointTable.PARENTS[cursor];
            }
            assertTrue(seen.contains(JointTable.ID_ROOT), "chain from " + JointTable.NAMES[i] + " never reaches Root");
        }
    }

    @Test
    void idLookupRoundTrips() {
        for (int i = 0; i < JointTable.COUNT; i++) {
            assertEquals(i, JointTable.idOf(JointTable.NAMES[i]));
            assertEquals(JointTable.NAMES[i], JointTable.nameOf(i));
        }
        assertEquals(-1, JointTable.idOf("NoSuchJoint"));
        assertEquals("Joint99", JointTable.nameOf(99));
    }

    @Test
    void plainNamesNormalizeAsBefore() {
        assertEquals("head", YSMJointMapper.normalizeBoneName("Head"));
        assertEquals("leftarm", YSMJointMapper.normalizeBoneName("Left_Arm"));
        assertEquals("leftarm", YSMJointMapper.normalizeBoneName("Left Arm"));
        assertEquals("leftarm", YSMJointMapper.normalizeBoneName("LeftArm2"));
        assertEquals("leftlegy", YSMJointMapper.normalizeBoneName("LeftLegY2"));
    }

    @Test
    void defaultFormSuffixIsStripped() {
        assertEquals("rightarm", YSMJointMapper.normalizeBoneName("RightArm_Default"));
        assertEquals("rightarm", YSMJointMapper.normalizeBoneName("RightArmDefault"));
        assertEquals("lefthand", YSMJointMapper.normalizeBoneName("LeftHand_Default"));
        assertEquals("leftarm", YSMJointMapper.normalizeBoneName("LeftArmDefault2"));
        assertEquals("torso", YSMJointMapper.normalizeBoneName("Torso_Default"));
    }

    @Test
    void namesAreNotOverStripped() {
        assertEquals("defaulthead", YSMJointMapper.normalizeBoneName("DefaultHead"));
        assertEquals("leftlegy", YSMJointMapper.normalizeBoneName("LeftLegY"));
        assertEquals("headdresslefttop", YSMJointMapper.normalizeBoneName("headdressLeftTop"));
        assertEquals("lefthandlocator", YSMJointMapper.normalizeBoneName("LeftHandLocator"));
        assertEquals("chestnut", YSMJointMapper.normalizeBoneName("chestnut"));
    }

    @Test
    void normalizeIsNullSafe() {
        assertEquals("", YSMJointMapper.normalizeBoneName(null));
        assertEquals("", YSMJointMapper.normalizeBoneName(""));
    }
}
