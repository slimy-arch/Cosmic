/*
    Kaentake damage skin picker "Purchase" (RecvOpcode.DAMAGE_SKIN_PURCHASE = 0x111): [int skinId].
    The skin must be in damageskin_catalog, not already owned, and affordable. Ownership is written before
    the mesos are taken; handlers for one client run sequentially, so the balance cannot change in between.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.DamageSkinCatalog;
import client.DamageSkinInventory;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

import java.sql.SQLException;

public final class DamageSkinPurchaseHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(DamageSkinPurchaseHandler.class);
    private static final int OP_PURCHASE = 2;

    @Override
    public void handlePacket(InPacket p, Client c) {
        int skinId = p.readInt();
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }

        DamageSkinInventory inv = chr.getDamageSkinInventory();
        long price = DamageSkinCatalog.getPrice(skinId);
        if (price < 0 || inv.ownsSkin(skinId) || chr.getMeso() < price) {
            c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, chr.getMeso()));
            return;
        }

        try {
            if (!inv.addSkin(chr.getId(), skinId)) {
                c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, chr.getMeso()));
                return;
            }
        } catch (SQLException e) {
            log.error("Damage skin purchase failed for chr {} skin {}", chr.getId(), skinId, e);
            c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, false, skinId, chr.getMeso()));
            return;
        }

        chr.gainMeso(-price, true);
        c.sendPacket(PacketCreator.damageSkinResult(OP_PURCHASE, true, skinId, chr.getMeso()));
    }
}
