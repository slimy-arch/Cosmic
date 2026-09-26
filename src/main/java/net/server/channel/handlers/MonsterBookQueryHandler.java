/*
    This file is part of the OdinMS Maple Story Server
    Copyright (C) 2008 Patrick Huy <patrick.huy@frz.cc>
               Matthias Butz <matze@odinms.de>
               Jan Christian Meyer <vimes@odinms.de>

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/
package net.server.channel.handlers;

import client.Character;
import client.Client;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.DropSearchService;
import tools.PacketCreator;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * C2S MONSTER_BOOK_QUERY (0x3732) — everything the Kaentake Monster Book needs that only the server
 * knows. Read-only: it never mutates character state, so it is safe to answer at any time.
 *
 * <ul>
 *   <li>{@code 0 [int mobId]} — the mob's drop table with chances at the asking player's LIVE rates,
 *       for the % printed on each Dropping icon.</li>
 *   <li>{@code 1 [string query]} — item-name LIKE search for the "find an item" button, restricted to
 *       items at least one carded mob drops (the book cannot explain any other kind).</li>
 *   <li>{@code 2 [int itemId]} — the mobs that drop one item, drawn as monster-card icons when the
 *       player clicks a hit from the search above, each with ITS OWN chance for that item at the same
 *       live rates, for the % printed under each card.</li>
 * </ul>
 *
 * <p>The mob-name search (the other button) is answered entirely client-side: Kaentake already has
 * String.wz/Mob.img and the card list, so it needs no round trip.
 *
 * <p>Every list is capped. A three-letter search can legitimately match thousands of items, and the
 * book only ever renders a page of them — an uncapped reply would be a large packet nobody reads.
 */
public final class MonsterBookQueryHandler extends AbstractPacketHandler {

    /** Matches DropSearchService's own floor: shorter needles match almost everything. */
    private static final int MIN_QUERY_LENGTH = 3;

    private static final int MAX_SEARCH_HITS = 200;
    private static final int MAX_DROPPERS = 200;

    @Override
    public void handlePacket(InPacket p, Client c) {
        final Character chr = c.getPlayer();
        if (chr == null) {
            return;
        }

        final int type = p.readByte();
        switch (type) {
            case 0 -> {
                int mobId = p.readInt();
                LinkedHashMap<Integer, Integer> chances = DropSearchService.mobDropChances(chr, mobId);
                c.sendPacket(PacketCreator.monsterBookDropTable(mobId, chances));
            }
            case 1 -> {
                String query = p.readString();
                if (query == null || query.trim().length() < MIN_QUERY_LENGTH) {
                    c.sendPacket(PacketCreator.monsterBookItemHits(query == null ? "" : query, new int[0]));
                    return;
                }
                // findBookItems, not findItems: the book answers a hit with the cards of its droppers,
                // so an item no carded mob drops could only ever open an empty card grid
                int[] hits = DropSearchService.findBookItems(query.trim());
                if (hits.length > MAX_SEARCH_HITS) {
                    hits = Arrays.copyOf(hits, MAX_SEARCH_HITS);
                }
                c.sendPacket(PacketCreator.monsterBookItemHits(query.trim(), hits));
            }
            case 2 -> {
                int itemId = p.readInt();
                // each mob carries its own chance: the boss multiplier makes it a property of the pair
                LinkedHashMap<Integer, Integer> droppers = DropSearchService.itemDroppers(chr, itemId);
                c.sendPacket(PacketCreator.monsterBookItemDroppers(itemId, cap(droppers, MAX_DROPPERS)));
            }
            default -> {
                // unknown subtype: ignore rather than answer with a malformed reply
            }
        }
    }

    /**
     * First {@code max} entries of an already-ordered map, so the cap keeps the BEST chances rather
     * than an arbitrary slice.
     */
    private static LinkedHashMap<Integer, Integer> cap(LinkedHashMap<Integer, Integer> ordered, int max) {
        if (ordered.size() <= max) {
            return ordered;
        }
        LinkedHashMap<Integer, Integer> ret = new LinkedHashMap<>();
        for (Map.Entry<Integer, Integer> entry : ordered.entrySet()) {
            if (ret.size() >= max) {
                break;
            }
            ret.put(entry.getKey(), entry.getValue());
        }
        return ret;
    }
}
