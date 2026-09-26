package client.keybind;

import net.packet.OutPacket;

import java.util.Arrays;

/**
 * @author Shavit
 */
public class QuickslotBinding {
    // kaentake longkeyboard.cpp widens the client quickslot from 8 to 26 keys (13 x 2).
    // CP_QuickslotKeyMappedModified and LP_QuickslotMappedInit carry QUICKSLOT_SIZE ints.
    public static final int QUICKSLOT_SIZE = 26;
    // DIK scan codes; must match g_aDefaultQKM in kaentake longkeyboard.cpp byte for byte.
    // Row 1: Shift Ins Home PgUp 1 2 3 4 5 A S D F / Row 2: Ctrl Del End PgDn Q W E R T Z X C V
    public static final byte[] DEFAULT_QUICKSLOTS = {
            0x2A, 0x52, 0x47, 0x49, 0x02, 0x03, 0x04, 0x05, 0x06, 0x1E, 0x1F, 0x20, 0x21,
            0x1D, 0x53, 0x4F, 0x51, 0x10, 0x11, 0x12, 0x13, 0x14, 0x2C, 0x2D, 0x2E, 0x2F
    };
    // Stock 8-key layout (still stored in the legacy quickslotkeymapped.keymap BIGINT).
    public static final int LEGACY_QUICKSLOT_SIZE = 8;

    private final byte[] m_aQuickslotKeyMapped;

    // Initializes quickslot object for the user.
    // aKeys' length has to be QUICKSLOT_SIZE.
    public QuickslotBinding(byte[] aKeys) {
        if (aKeys.length != QUICKSLOT_SIZE) {
            throw new IllegalArgumentException(String.format("aKeys' size should be %d", QUICKSLOT_SIZE));
        }

        this.m_aQuickslotKeyMapped = aKeys.clone();
    }

    public void encode(OutPacket p) {
        // Quickslots are default.
        // The client will skip them and call CQuickslotKeyMappedMan::DefaultQuickslotKeyMap.
        if (Arrays.equals(this.m_aQuickslotKeyMapped, DEFAULT_QUICKSLOTS)) {
            p.writeBool(false);
            return;
        }

        p.writeBool(true);

        for (byte nKey : this.m_aQuickslotKeyMapped) {
            // For some reason Nexon sends these as integers, similar to CFuncKeyMappedMan.
            // However there's no evidence any key can be above 0xFF anyhow.
            // Regardless, we need to encode an integer to avoid an error 38 crash; as CQuickslotKeyMappedMan::m_aQuickslotKeyMapped is int[26].
            p.writeInt(nKey & 0xFF);
        }
    }

    // Expands a stock 8-key map: the 4x2 grid becomes the first 4 columns of the 13x2 grid.
    public static byte[] fromLegacy(byte[] aLegacyKeys) {
        if (aLegacyKeys.length != LEGACY_QUICKSLOT_SIZE) {
            throw new IllegalArgumentException(String.format("aLegacyKeys' size should be %d", LEGACY_QUICKSLOT_SIZE));
        }

        byte[] aKeys = DEFAULT_QUICKSLOTS.clone();
        System.arraycopy(aLegacyKeys, 0, aKeys, 0, 4);
        System.arraycopy(aLegacyKeys, 4, aKeys, 13, 4);
        return aKeys;
    }

    public byte[] GetKeybindings() {
        return m_aQuickslotKeyMapped;
    }

}