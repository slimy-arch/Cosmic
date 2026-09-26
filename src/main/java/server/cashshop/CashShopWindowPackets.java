package server.cashshop;

import client.Character;
import net.opcodes.SendOpcode;
import net.packet.OutPacket;
import net.packet.Packet;
import server.CashShop;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire format for the standalone Cash Shop window (LP 0x3731 / CP 0x3730).
 *
 * <p>Field order here is AUTHORITATIVE and must match {@code CashShopWnd_HandleSync}
 * in {@code kaentake/src/cashshopwnd.cpp} exactly. The two opcodes are equal only by
 * convention -- nothing enforces it -- so change both sides or neither.
 */
public final class CashShopWindowPackets {

    // client -> server (CP 0x3730)
    public static final int ACTION_REQUEST_CATALOG = 0;
    public static final int ACTION_BUY = 1;
    public static final int ACTION_BUY_CART = 2;      // + byte count, count * int itemId
    public static final int ACTION_REQUEST_CATEGORY = 3;   // + byte tab, byte category

    // server -> client (LP 0x3731)
    public static final int RESP_OPEN = 0;
    public static final int RESP_CATALOG = 1;
    public static final int RESP_BUY = 2;
    public static final int RESP_CASH = 3;
    public static final int RESP_BUY_CART = 4;
    public static final int RESP_INDEX = 5;

    /**
     * Must stay below {@code kMaxNameLen} in kaentake/src/cashshopwnd.cpp (64). Names are written
     * as a byte count, so this is a CHARACTER cap on a byte budget -- 40 leaves room for
     * multi-byte characters without approaching the guard, which rejects the WHOLE chunk
     * rather than the offending entry.
     */
    public static final int MAX_NAME = 40;

    // Buy result codes, indexed by kBuyMsg in cashshopwnd.cpp. That table is bounds-guarded
    // at every use, so a code added here degrades to "Purchase failed." on an older DLL
    // rather than reading past the end -- append, never renumber.
    public static final int BUY_OK = 0;
    public static final int BUY_NO_NX = 1;
    public static final int BUY_UNKNOWN_ITEM = 2;
    public static final int BUY_INVENTORY_FULL = 3;
    public static final int BUY_NOT_ON_SALE = 4;
    public static final int BUY_BUSY = 5;
    public static final int BUY_BAD_CART = 6;

    /**
     * Entries per packet. The wire cap is a hard 64KB on both sides (16-bit length in
     * {@code MapleAESOFB.getPacketHeader}, and a 16-bit accumulator in the client's
     * {@code CInPacket::AppendBuffer}), and oversize does NOT fail loudly -- the length
     * wraps mod 65536 and the client sees a short packet full of garbage. An entry is
     * 18 fixed bytes plus a length-prefixed name, so ~64 bytes worst case; 400 keeps a
     * chunk near 25KB with room to spare.
     */
    private static final int CHUNK = 400;

    private CashShopWindowPackets() {
    }

    /** Tells the client to open the window, and seeds the NX display. */
    public static Packet open(Character chr) {
        final OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_OPEN);
        writeCash(p, chr);
        return p;
    }

    /** Refreshes the NX display without touching the catalog. */
    public static Packet cash(Character chr) {
        final OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_CASH);
        writeCash(p, chr);
        return p;
    }

    public static Packet buyResult(int code, int itemId) {
        final OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_BUY);
        p.writeByte(code);
        p.writeInt(itemId);
        return p;
    }

    /**
     * The cart outcome. It carries the counts as well as the code because a cart is
     * all-or-nothing and the player needs to be told which of the two happened -- the
     * plain {@link #buyResult} shape can say "it failed" but not "and you were not
     * charged for any of the other nine".
     *
     * @param itemId the item that stopped the cart, 0 when the whole cart succeeded
     */
    public static Packet cartResult(int code, int itemId, int delivered, int spent) {
        final OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_BUY_CART);
        p.writeByte(code);
        p.writeInt(itemId);
        p.writeByte(delivered);
        p.writeInt(spent);
        return p;
    }

    /**
     * The CATEGORY INDEX: which categories exist and how many items each holds, and nothing
     * else. This is all the window needs to draw its left column and its counters, and
     * sending it instead of the merchandise is what removes the size ceiling -- the whole
     * catalogue never has to fit in one burst, or in the client's list, at all.
     */
    public static Packet index(List<int[]> idx) {
        final OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
        p.writeByte(RESP_INDEX);
        p.writeShort(idx.size());
        for (int[] e : idx) {
            p.writeByte(e[0]);                    // tab
            p.writeByte(e[1]);                    // category
            p.writeInt(e[2]);                     // count -- an int, so a category is unbounded
        }
        return p;
    }

    /**
     * One CATEGORY's rows, chunked. The first chunk carries flag 1, which tells the client
     * to clear just that category; later chunks append to it.
     *
     * <p>Always returns at least one packet, so an empty category still clears whatever the
     * client had rather than leaving stale entries on screen, and still ends the "loading"
     * state.
     */
    public static List<Packet> category(int tab, int cat, List<CashShopCatalog.Row> rows) {
        final List<Packet> out = new ArrayList<>();
        final int total = rows.size();

        for (int i = 0; i < total || i == 0; i += CHUNK) {
            final int end = Math.min(i + CHUNK, total);
            final OutPacket p = OutPacket.create(SendOpcode.CASHSHOP_WINDOW_SYNC);
            p.writeByte(RESP_CATALOG);
            p.writeByte(i == 0 ? 1 : 0);          // 1 = clear this category, 0 = append
            p.writeByte(tab);
            p.writeByte(cat);
            p.writeShort(end - i);
            for (int j = i; j < end; j++) {
                final CashShopCatalog.Row r = rows.get(j);
                p.writeInt(r.itemId());
                p.writeInt(r.price());
                p.writeShort(r.count());
                p.writeByte(r.tab());
                p.writeByte(r.category());
                p.writeString(r.name());
            }
            out.add(p);
            if (total == 0) {
                break;                            // the i == 0 guard fired; one empty chunk is enough
            }
        }
        return out;
    }

    private static void writeCash(OutPacket p, Character chr) {
        final CashShop cs = chr.getCashShop();
        p.writeLong(cs.getNxCredit());   // long since the NX uncap (cashshopwnd.cpp Decode8)
        p.writeInt(cs.getCash(CashShop.MAPLE_POINT));
        p.writeInt(cs.getCash(CashShop.NX_PREPAID));
    }
}
