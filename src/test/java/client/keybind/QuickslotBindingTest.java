package client.keybind;

import org.junit.jupiter.api.Test;
import tools.LongTool;

import static org.junit.jupiter.api.Assertions.*;

class QuickslotBindingTest {

    @Test
    void defaultsCoverEveryLongKeyboardSlot() {
        assertEquals(26, QuickslotBinding.QUICKSLOT_SIZE);
        assertEquals(QuickslotBinding.QUICKSLOT_SIZE, QuickslotBinding.DEFAULT_QUICKSLOTS.length);
    }

    @Test
    void fromLegacyKeepsBothStockRowsInTheirColumns() {
        byte[] aLegacy = {0x10, 0x11, 0x12, 0x13, 0x1E, 0x1F, 0x20, 0x21};

        byte[] aKeys = QuickslotBinding.fromLegacy(aLegacy);

        assertEquals(QuickslotBinding.QUICKSLOT_SIZE, aKeys.length);
        for (int i = 0; i < 4; i++) {
            assertEquals(aLegacy[i], aKeys[i]);          // top row, columns 0-3
            assertEquals(aLegacy[4 + i], aKeys[13 + i]); // bottom row, columns 0-3
        }
        for (int i = 4; i < 13; i++) {
            assertEquals(QuickslotBinding.DEFAULT_QUICKSLOTS[i], aKeys[i]);
            assertEquals(QuickslotBinding.DEFAULT_QUICKSLOTS[13 + i], aKeys[13 + i]);
        }
    }

    @Test
    void stockDefaultBigintExpandsToLongKeyboardDefaults() {
        byte[] aStockDefault = {0x2A, 0x52, 0x47, 0x49, 0x1D, 0x53, 0x4F, 0x51};
        long nStored = LongTool.BytesToLong(aStockDefault);

        assertArrayEquals(QuickslotBinding.DEFAULT_QUICKSLOTS, QuickslotBinding.fromLegacy(LongTool.LongToBytes(nStored)));
    }

    @Test
    void rejectsWrongSizes() {
        assertThrows(IllegalArgumentException.class, () -> new QuickslotBinding(new byte[8]));
        assertThrows(IllegalArgumentException.class, () -> QuickslotBinding.fromLegacy(new byte[26]));
    }
}
