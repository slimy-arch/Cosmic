/*
    Kaentake damage skin picker "Apply" (RecvOpcode.DAMAGE_SKIN_APPLY = 0x110): [int skinId].
    The skin must be owned (0 = stock digits, always allowed). The map is told so every client draws this
    character's hits in the new skin.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import tools.PacketCreator;

public final class DamageSkinApplyHandler extends AbstractPacketHandler {
    private static final int OP_APPLY = 1;

    @Override
    public void handlePacket(InPacket p, Client c) {
        int skinId = p.readInt();
        Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }

        if (!chr.getDamageSkinInventory().ownsSkin(skinId)) {
            c.sendPacket(PacketCreator.damageSkinResult(OP_APPLY, false, skinId, chr.getMeso()));
            return;
        }

        chr.setActiveDamageSkin(skinId);
        c.sendPacket(PacketCreator.damageSkinResult(OP_APPLY, true, skinId, chr.getMeso()));
        if (chr.getMap() != null) {
            chr.getMap().broadcastMessage(PacketCreator.damageSkinBroadcast(chr.getId(), skinId));
        }
    }
}
