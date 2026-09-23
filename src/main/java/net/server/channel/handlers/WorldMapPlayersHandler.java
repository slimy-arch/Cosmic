/*
    World-map tooltip "Players" section — server side of the Kaentake client
    worldmapinfo.cpp CP/LP_WORLD_MAP_PLAYERS protocol.

    The client's world-map tooltip lists a map's NPCs and monsters from the LOCAL WZ
    data, but it cannot know which PLAYERS are in a map, or on which channel — that is
    server state. When the cursor hovers a map spot, the Kaentake client sends this
    request (mapId, throttled + cached client-side); we reply with every player in
    that map across ALL channels of the requester's world: name, level, channel.

    The client only REQUESTS; the server is the source of truth. The reply is purely
    informational (no character preview), so there is nothing to validate beyond not
    leaking hidden GMs to non-GMs. If this handler does not exist, the client still
    works — the tooltip just shows "Players (loading...)" forever (Cosmic logs+drops
    the unknown opcode; no crash), so this is additive and safe to add any time.

    Wire (client -> server, RecvOpcode.WORLD_MAP_PLAYERS = 0x115):
        int   mapId

    Reply (server -> client, SendOpcode.WORLD_MAP_PLAYERS = 0x178) — field order is
    EXACT; the client decodes it positionally:
        int   mapId             echo back (client matches the hovered map by this)
        short count
        repeat count times:
            string name         (short length-prefixed, GMS ASCII — OutPacket.writeString)
            short  level
            byte   channel      1-based channel number
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.opcodes.SendOpcode;
import net.packet.InPacket;
import net.packet.OutPacket;
import net.server.channel.Channel;
import net.server.world.World;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class WorldMapPlayersHandler extends AbstractPacketHandler {

    private static final int MAX_PLAYERS = 40;   // must match the client cap (worldmapinfo.cpp players::MAX_PLAYERS)

    // Brief result cache so a burst of hovers doesn't each do a full multi-channel O(online) scan on the
    // netty thread. The tooltip is purely informational, so a sub-second-stale roster is acceptable.
    private static final long CACHE_TTL_MS = 750L;
    private record CacheEntry(long expiresAt, List<Found> found) {}
    private static final ConcurrentHashMap<String, CacheEntry> CACHE = new ConcurrentHashMap<>();

    private record Found(String name, int level, int channel) {}

    @Override
    public void handlePacket(InPacket p, Client c) {
        int mapId = p.readInt();
        Character self = c.getPlayer();
        if (self == null || mapId <= 0) {
            return;
        }

        World world = c.getWorldServer();
        if (world == null) {
            return;
        }

        boolean requesterIsGM = self.isGM();

        // Serve a fresh-enough cached roster (keyed per world + map + GM-visibility) to absorb hover bursts.
        long now = System.currentTimeMillis();
        String cacheKey = world.getId() + ":" + mapId + ":" + (requesterIsGM ? 1 : 0);
        CacheEntry cached = CACHE.get(cacheKey);
        List<Found> found;
        if (cached != null && now < cached.expiresAt()) {
            found = cached.found();
        } else {
            found = new ArrayList<>();
            // Walk every channel of THIS world; the channel id comes from the Channel
            // object, so it is correct even for a character whose client is momentarily null.
            try {
                outer:
                for (Channel ch : world.getChannels()) {
                    int channelId = ch.getId();
                    for (Character chr : ch.getPlayerStorage().getAllCharacters()) {
                        if (chr == null || chr.getMapId() != mapId) {
                            continue;
                        }
                        if (chr.isHidden() && !requesterIsGM) {
                            continue;   // hidden GMs are invisible to non-GMs
                        }
                        found.add(new Found(chr.getName(), chr.getLevel(), channelId));
                        if (found.size() >= MAX_PLAYERS) {
                            break outer;
                        }
                    }
                }
            } catch (Exception e) {
                // Never let a lookup error crash the channel packet thread — reply with
                // whatever was collected so far (possibly empty).
            }
            CACHE.values().removeIf(e -> now >= e.expiresAt());   // opportunistic prune so the cache stays bounded
            CACHE.put(cacheKey, new CacheEntry(now + CACHE_TTL_MS, found));
        }

        OutPacket out = OutPacket.create(SendOpcode.WORLD_MAP_PLAYERS);
        out.writeInt(mapId);
        out.writeShort(found.size());
        for (Found f : found) {
            out.writeString(f.name());
            out.writeShort(f.level());
            out.writeByte(f.channel());
        }
        c.sendPacket(out);
    }
}
