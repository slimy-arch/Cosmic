package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.inventory.manipulator.KarmaManipulator;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import server.ItemInformationProvider;
import server.OreStorage;
import tools.PacketCreator;

import java.util.List;

/**
 * kaentake Storage Bag window (RecvOpcode.BAG_WINDOW 0x3724). The client only asks; every change is validated
 * here and answered with a full snapshot of the bag (PacketCreator.bagWindowSnapshot).
 *
 * <pre>
 * byte action  0 OPEN, 1 WITHDRAW, 2 DEPOSIT, 3 MERGE, 4 CLOSE, 5 SET_AUTO
 * byte bagKind 0 ore, 1 scroll, 2 chair, 3 cash
 * WITHDRAW: short bagSlot                 (dense index from the last snapshot -> inventory)
 * DEPOSIT : short invType, short invPos   (whole inventory stack -> bag)
 * SET_AUTO: byte on                       (pickup auto-collect for this tab, same as @<bag> on|off)
 * </pre>
 * Ported from Kaentake/kaentake-main/implemented/storagebag.
 */
public final class BagWindowHandler extends AbstractPacketHandler {
    private static final Logger log = LoggerFactory.getLogger(BagWindowHandler.class);
    private static final int REQ_OPEN = 0, REQ_WITHDRAW = 1, REQ_DEPOSIT = 2, REQ_MERGE = 3, REQ_CLOSE = 4, REQ_SET_AUTO = 5;
    private static final String[] BAG_NAME = {"ore bag", "scroll bag", "chair bag", "cash bag"};

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character player = c.getPlayer();
        if (player == null) {
            return;
        }
        int action = p.readByte();
        int bagKind = p.readByte();
        if (action == REQ_CLOSE) {
            player.activateBag(-1);   // stop auto-collect refreshes
            return;
        }
        OreStorage bag = player.getBag(bagKind);
        if (bag == null) {
            return;
        }
        player.activateBag(bagKind);

        switch (action) {
            case REQ_OPEN -> {
            }
            case REQ_WITHDRAW -> withdraw(c, player, bag, bagKind, p.readShort());
            case REQ_DEPOSIT -> {
                short invType = p.readShort();
                short invPos = p.readShort();
                deposit(player, bagKind, invType, invPos);
            }
            case REQ_MERGE -> {
                bag.mergeStacks(c);
                player.setUsedBag(bagKind);
            }
            case REQ_SET_AUTO -> {
                boolean on = p.readByte() != 0;
                player.setAutoBag(bagKind, on);
                player.yellowMessage("Auto-collect into your " + BAG_NAME[bagKind] + " is " + (on ? "ON." : "OFF."));
            }
            default -> {
                return;
            }
        }
        c.sendPacket(PacketCreator.bagWindowSnapshot(bagKind, bag, true, player.isAutoBag(bagKind)));
    }

    private static void withdraw(Client c, Character player, OreStorage bag, int kind, int bagSlot) {
        List<Item> items = bag.getItems();
        if (bagSlot < 0 || bagSlot >= items.size()) {
            return;   // stale index; the snapshot below resyncs the client
        }
        Item item = items.get(bagSlot);
        if (!player.canUseBags()) {
            player.dropMessage(1, "You cannot use the storage as a GM of this level.");
            return;
        }
        ItemInformationProvider ii = ItemInformationProvider.getInstance();
        if (ii.isPickupRestricted(item.getItemId()) && player.haveItemWithId(item.getItemId(), true)) {
            player.dropMessage(1, "You already hold that one-of-a-kind item.");
            return;
        }
        if (!InventoryManipulator.checkSpace(c, item.getItemId(), item.getQuantity(), item.getOwner())) {
            player.dropMessage(1, "Not enough inventory space to withdraw that item.");
            return;
        }
        if (!bag.takeOut(item)) {
            return;
        }
        KarmaManipulator.toggleKarmaFlagToUntradeable(item);
        if (!InventoryManipulator.addFromDrop(c, item, false)) {
            bag.store(item);   // it just came out, so there is room for it again
            player.dropMessage(1, "Could not move that item to your inventory.");
            log.warn("Chr {} bag withdraw rejected by addFromDrop: {} x{}", player.getName(), item.getItemId(), item.getQuantity());
            return;
        }
        player.setUsedBag(kind);
    }

    private static void deposit(Character player, int kind, short invType, short invPos) {
        InventoryType type = InventoryType.getByType((byte) invType);
        if (type == null || type == InventoryType.EQUIPPED || type == InventoryType.UNDEFINED) {
            return;
        }
        Inventory inv = player.getInventory(type);
        if (invPos < 1 || invPos > inv.getSlotLimit()) {
            return;
        }
        Item item = inv.getItem(invPos);
        if (item == null) {
            return;
        }
        if (!player.moveToBag(kind, type, invPos)) {
            player.dropMessage(1, "The " + BAG_NAME[kind] + " cannot hold that item.");
        }
    }
}
