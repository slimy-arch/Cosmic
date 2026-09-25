package net.server.channel.handlers;

import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.maps.HyperTeleportRockService;

/**
 * Custom: Kaentake world map click-to-teleport (kaentake/src/hyperteleportrock.cpp).
 * CP 0x105 [int targetMapId]. All validation lives in {@link HyperTeleportRockService}.
 */
public final class HyperTeleportRockHandler extends AbstractPacketHandler {
    @Override
    public void handlePacket(InPacket p, Client c) {
        if (p.available() < Integer.BYTES) {
            HyperTeleportRockService.enableActions(c);
            return;
        }

        int targetMapId = p.readInt();
        if (!HyperTeleportRockService.tryTeleport(c, targetMapId)) {
            HyperTeleportRockService.enableActions(c);
        }
    }
}
