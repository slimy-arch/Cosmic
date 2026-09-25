package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import net.packet.Packet;
import server.cashshop.CashShopCatalog;
import server.cashshop.CashShopWindowPackets;
import server.cashshop.CashShopWindowPurchase;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * CP 0x3730 -- the standalone Cash Shop window.
 *
 * <p>Deliberately NOT {@code CashOperationHandler} (0xE5). That handler hard-gates on
 * {@code CashShop.isOpened()}, which is set only by {@link EnterCashShopHandler} after
 * the stage change this feature exists to avoid. Faking {@code open(true)} to get past
 * it is worse than a new opcode: while that flag is set,
 * {@code Character.commitBuffCoupon} silently refuses to apply rate-coupon buffs and
 * {@code saveCharToDB} persists the stale {@code mapid} field instead of the live map.
 *
 * <p>Purchases are delivered straight to the character's inventory rather than to the
 * cash locker: the locker's reply packets ({@code CASHSHOP_OPERATION} 0x145) are only
 * routed by the cash-shop stage UI, so a field client would render nothing.
 * {@code InventoryManipulator.addFromDrop} sends the ordinary
 * {@code LP_InventoryOperation} the field client already understands, and preserves the
 * expiration {@code CashItem.toItem()} stamps on the item. This is the same route the
 * mesos-purchase action (0x20) in CashOperationHandler already takes.
 */
public final class CashShopWindowHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(CashShopWindowHandler.class);

    @Override
    public void handlePacket(InPacket p, Client c) {
        final Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }
        // If the player really is inside the cash-shop stage, the stage UI owns them.
        if (chr.getCashShop().isOpened()) {
            return;
        }

        final int action = p.readByte();
        switch (action) {
            case CashShopWindowPackets.ACTION_REQUEST_CATALOG -> sendCatalog(c, chr);
            case CashShopWindowPackets.ACTION_REQUEST_CATEGORY -> sendCategory(c, p);
            case CashShopWindowPackets.ACTION_BUY -> buy(c, chr, p);
            case CashShopWindowPackets.ACTION_BUY_CART -> buyCart(c, chr, p);
            default -> log.warn("Cash Shop window: unhandled action {}", action);
        }
    }

    /**
     * On open the client gets the category INDEX only -- which categories exist and how many
     * items each holds. It does NOT get the merchandise, and that is what removes the size
     * ceiling: the catalogue can be any size because it is never sent in one go.
     */
    static void sendCatalog(Client c, Character chr) {
        c.sendPacket(CashShopWindowPackets.index(CashShopCatalog.index()));
        c.sendPacket(CashShopWindowPackets.cash(chr));
    }

    /** One category, on demand, when the player actually looks at it. */
    private void sendCategory(Client c, InPacket p) {
        if (p.available() < 2) {
            return;
        }
        final int tab = p.readByte() & 0xFF;
        final int cat = p.readByte() & 0xFF;
        for (Packet packet : CashShopWindowPackets.category(tab, cat, CashShopCatalog.bucket(tab, cat))) {
            c.sendPacket(packet);
        }
    }

    /**
     * A single Buy is a one-element cart. All the validation, affordability, space,
     * delivery and deduction logic lives in {@link CashShopWindowPurchase} so that the two
     * paths cannot drift -- in particular so the single buy inherits the cumulative space
     * check rather than the per-item {@code canHold} it used to do.
     */
    private void buy(Client c, Character chr, InPacket p) {
        if (p.available() < 4) {
            return;
        }
        final int itemId = p.readInt();
        final CashShopWindowPurchase.Result r =
                CashShopWindowPurchase.buy(c, chr, new int[]{itemId});
        c.sendPacket(CashShopWindowPackets.buyResult(r.code(), itemId));
        if (r.ok()) {
            c.sendPacket(CashShopWindowPackets.cash(chr));
        }
    }

    private void buyCart(Client c, Character chr, InPacket p) {
        if (p.available() < 1) {
            return;
        }
        final int count = p.readByte() & 0xFF;
        // Bounds BEFORE the reads, and the byte count is checked against what is actually
        // left in the packet: a crafted count would otherwise read past the end.
        if (count <= 0 || count > CashShopWindowPurchase.MAX_CART || p.available() < count * 4) {
            c.sendPacket(CashShopWindowPackets.cartResult(
                    CashShopWindowPackets.BUY_BAD_CART, 0, 0, 0));
            return;
        }
        final int[] sns = new int[count];
        for (int i = 0; i < count; i++) {
            sns[i] = p.readInt();
        }

        final CashShopWindowPurchase.Result r = CashShopWindowPurchase.buy(c, chr, sns);
        c.sendPacket(CashShopWindowPackets.cartResult(
                r.code(), r.failedSn(), r.delivered(), r.spent()));
        if (r.ok()) {
            c.sendPacket(CashShopWindowPackets.cash(chr));
        }
    }
}
