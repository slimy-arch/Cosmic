package server.colorprism;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TintValuesTest {

    @Test
    void skillPartKeyRoundTripsEightDigitSkillIds() {
        // Cygnus and Aran skill ids are 8 digits; the old +30M/+40M keys broke on both.
        for (int skillId : new int[]{1121008, 11001004, 15121004, 21001001, 21120009}) {
            for (int part = TintValues.SKILL_PART_MIN; part <= TintValues.SKILL_PART_MAX; part++) {
                final int key = TintValues.skillPartKey(skillId, part);
                assertTrue(key > 0, "fits a signed int");
                assertTrue(key >= 200_000_000, "clear of item, item-effect and look keys");
                assertEquals(skillId, TintValues.skillOfPartKey(key));
                assertEquals(part, TintValues.partOfPartKey(key));
            }
        }
    }

    @Test
    void negativeHueIsAbsoluteAndNotWrapped() {
        assertEquals(-1, TintValues.normalizeHue(-1));
        assertEquals(-360, TintValues.normalizeHue(-500));
        assertEquals(10, TintValues.normalizeHue(370));
    }

    @Test
    void identityIsRejectedAsATintButValid() {
        assertTrue(TintValues.isValid(0, 0, 0));
        assertTrue(TintValues.isIdentity(0, 0, 0));
        assertFalse(TintValues.isValid(360, 0, 0));
        assertFalse(TintValues.isValid(-361, 0, 0));
        assertFalse(TintValues.isValid(0, 101, 0));
        assertFalse(TintValues.isValidSkillPart(0));
        assertFalse(TintValues.isValidSkillPart(18));
    }
}
