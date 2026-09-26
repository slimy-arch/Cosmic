package server.cashshop;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.Pet;
import client.inventory.manipulator.InventoryManipulator;
import constants.id.ItemId;
import constants.inventory.ItemConstants;
import net.server.Server;
import server.CashShop;
import server.ItemInformationProvider;
import tools.Pair;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.HOURS;

/**
 * The purchase path for the standalone Cash Shop window.
 *
 * <p>Merchandise is resolved from {@link CashShopCatalog} -- the server's own file -- and
 * never from {@code CashItemFactory}/{@code Commodity.img}. That also means the item is
 * built here rather than by {@code CashShop.CashItem.toItem()}, whose class has a private
 * constructor, and the NX is debited with the plain {@code gainCash(type, cash)} rather than
 * the {@code CashItem} overload. Skipping that overload is deliberate: it also calls
 * {@code World.addCashItemBought}, which indexes a NINE-element list with
 * {@code itemId / 10000000} and does not bounds-check it. Dropping it is what frees serials from
 * having to encode a tab at all, and it only costs the "best sellers" bookkeeping of a stock
 * cash-shop UI this window replaces.
 *
 * <p>THE CART IS THE PRIMITIVE and a single Buy is a one-element cart, so there is exactly
 * one copy of the validate/afford/space/deliver/deduct sequence. Writing the cart as a loop
 * over the old single buy would have been the obvious shape and the wrong one: it charges
 * per item, so a cart whose seventh item does not fit leaves the player paid-up for six.
 *
 * <p>The five phases are strictly ordered and the first three mutate nothing:
 * <ol>
 *   <li>resolve and validate every serial,</li>
 *   <li>total the price ONCE and check it against NX,</li>
 *   <li>check inventory space ONCE and CUMULATIVELY,</li>
 *   <li>deliver,</li>
 *   <li>deduct.</li>
 * </ol>
 *
 * <p>Phase 3 is the one that does not survive being written the obvious way. A per-item
 * {@code chr.canHold(itemId)} asks "is there room for this one item" -- ten calls to it all
 * pass against a single free slot. {@link Inventory#checkSpots} takes the whole list and
 * accounts for stacking, which is the only question a cart can usefully ask.
 *
 * <p>Nothing here calls {@code saveCharToDB}. The NX balance and the inventory both persist
 * inside that method's single transaction already, and calling it from the packet thread is
 * a synchronized full-character save -- one whose inventory write, if it fails, silently
 * discards the whole character.
 */
public final class CashShopWindowPurchase {
    private static final Logger log = LoggerFactory.getLogger(CashShopWindowPurchase.class);

    /** Looser than the client's own cap (kCartMax = 20), which is safe; the wire count is a single byte regardless. */
    public static final int MAX_CART = 24;

    private CashShopWindowPurchase() {
    }

    /**
     * @param code      one of {@code CashShopWindowPackets.BUY_*}
     * @param failedSn  the serial that stopped the cart, or 0 when there is no single culprit
     * @param delivered how many items reached the inventory
     * @param spent     NX actually deducted
     */
    public record Result(int code, int failedSn, int delivered, int spent) {
        public boolean ok() {
            return code == CashShopWindowPackets.BUY_OK;
        }
    }

    /**
     * Builds the deliverable item for a catalogue row. Mirrors {@code CashItem.toItem()},
     * with one deliberate difference: a period of 0 means PERMANENT here, where the WZ path
     * silently rewrote 0 to 90 days. A server-owned catalogue can say "forever" and mean it.
     */
    private static Item toItem(CashShopCatalog.Row r) {
        final int itemId = r.itemId();
        int petId = -1;
        if (ItemConstants.isPet(itemId)) {
            petId = Pet.createPet(itemId);          // INSERTs a pets row; never call in a check
        }

        final Item item;
        if (ItemConstants.getInventoryType(itemId) == InventoryType.EQUIP) {
            item = ItemInformationProvider.getInstance().getEquipById(itemId);
        } else {
            item = new Item(itemId, (byte) 0, (short) r.count(), petId);
        }
        if (item == null) {
            return null;
        }

        if (ItemConstants.EXPIRING_ITEMS && r.period() > 0) {
            final long now = Server.getInstance().getCurrentTime();
            if (r.period() == 1) {
                // The stock special-cases: a "1" on these coupons means hours, not a day.
                switch (itemId) {
                    case ItemId.DROP_COUPON_2X_4H, ItemId.EXP_COUPON_2X_4H ->
                            item.setExpiration(now + HOURS.toMillis(4));
                    case ItemId.EXP_COUPON_3X_2H ->
                            item.setExpiration(now + HOURS.toMillis(2));
                    default -> item.setExpiration(now + DAYS.toMillis(1));
                }
            } else {
                item.setExpiration(now + DAYS.toMillis(r.period()));
            }
        }
        item.setSN(r.itemId());
        return item;
    }

    public static Result buy(Client c, Character chr, int[] sns) {
        if (sns == null || sns.length == 0 || sns.length > MAX_CART) {
            return new Result(CashShopWindowPackets.BUY_BAD_CART, 0, 0, 0);
        }
        // Serialized per client, the same way CashOperationHandler does it: two carts in
        // flight together could otherwise both pass the price check.
        if (!c.tryacquireClient()) {
            return new Result(CashShopWindowPackets.BUY_BUSY, 0, 0, 0);
        }
        try {
            final ItemInformationProvider ii = ItemInformationProvider.getInstance();

            // ---- PHASE 1: resolve and validate. Nothing is mutated. ----
            final List<CashShopCatalog.Row> items = new ArrayList<>(sns.length);
            final Set<Integer> seenSn = new HashSet<>();
            for (int itemId : sns) {
                if (!seenSn.add(itemId)) {
                    return new Result(CashShopWindowPackets.BUY_BAD_CART, itemId, 0, 0);
                }
                // The catalogue IS the offer list, so membership is the whole gate -- there
                // is no second "is it on sale" question that could disagree with it.
                final CashShopCatalog.Row r = CashShopCatalog.byItemId(itemId);
                if (r == null) {
                    return new Result(CashShopWindowPackets.BUY_UNKNOWN_ITEM, itemId, 0, 0);
                }
                // A zero stack ceiling means the item cannot be placed anywhere. The space
                // check below would not catch it, and addFromDrop would then refuse in
                // phase 4 with part of the cart already delivered.
                if (ii.getSlotMax(c, r.itemId()) <= 0) {
                    return new Result(CashShopWindowPackets.BUY_UNKNOWN_ITEM, itemId, 0, 0);
                }
                items.add(r);
            }

            // ---- PHASE 2: affordability, once, for the whole cart ----
            // Summed as a long: 24 items near Integer.MAX_VALUE would overflow an int into a
            // negative total and pass the check.
            long total = 0;
            for (CashShopCatalog.Row r : items) {
                total += r.price();
            }
            final CashShop cs = chr.getCashShop();
            if (total > cs.getNxCredit()) {
                return new Result(CashShopWindowPackets.BUY_NO_NX, 0, 0, 0);
            }

            // ---- PHASE 3: space, once, cumulatively ----
            final List<Pair<Item, InventoryType>> probes = new LinkedList<>();
            final List<Integer> uniques = new LinkedList<>();
            final Set<Integer> restricted = new HashSet<>();
            int petLines = 0;
            for (CashShopCatalog.Row r : items) {
                final int itemId = r.itemId();
                // Inventory.checkItemRestricted only rejects a quantity above 1 WITHIN one
                // probe, so two separate cart lines of the same one-of-a-kind item walk
                // straight past it.
                if (ii.isPickupRestricted(itemId) && !restricted.add(itemId)) {
                    return new Result(CashShopWindowPackets.BUY_BAD_CART, r.itemId(), 0, 0);
                }
                uniques.add(itemId);

                // PETS ARE NOT PROBEABLE, and modelling them as ordinary stackables is
                // unsafe in the direction that matters. No pet img defines info/slotMax, so
                // getSlotMax returns the CASH default of 100; checkSpaceProgressively then
                // sees an existing row for that pet id, "absorbs" the incoming one into it,
                // computes numSlotsNeeded == 0 and short-circuits to hasSpace == 1 WITHOUT
                // ever consulting the inventory. Delivery does the opposite: toItem() gives
                // the pet a unique petid, so addFromDropInternal takes the petId != -1
                // branch and always demands a fresh slot.
                if (ItemConstants.isPet(itemId)) {
                    petLines++;
                    continue;
                }
                // A lightweight probe, deliberately NOT toItem(): that calls Pet.createPet
                // for a pet, which INSERTs a row there and then. A check that can fail must
                // not leave rows behind.
                probes.add(new Pair<>(new Item(itemId, (short) 0, (short) r.count()),
                        ItemConstants.getInventoryType(itemId)));
            }
            if (!chr.canHoldUniques(uniques)) {
                return new Result(CashShopWindowPackets.BUY_BAD_CART, 0, 0, 0);
            }
            if (!probes.isEmpty() && !Inventory.checkSpots(chr, probes)) {
                return new Result(CashShopWindowPackets.BUY_INVENTORY_FULL, 0, 0, 0);
            }
            if (petLines > 0) {
                // Every pet needs its own CASH slot. Deliberately CONSERVATIVE: the cart's
                // other CASH lines are counted as needing a slot each too, even though some
                // of them may merge into an existing stack. Over-reserving refuses a cart
                // that would just have fitted; under-reserving hands out items nobody paid
                // for, and only one of those is recoverable.
                int cashLines = petLines;
                for (CashShopCatalog.Row r : items) {
                    final int id = r.itemId();
                    if (!ItemConstants.isPet(id)
                            && ItemConstants.getInventoryType(id) == InventoryType.CASH) {
                        cashLines++;
                    }
                }
                if (chr.getInventory(InventoryType.CASH).isFull(cashLines - 1)) {
                    return new Result(CashShopWindowPackets.BUY_INVENTORY_FULL, 0, 0, 0);
                }
            }

            // ---- PHASE 4: deliver ----
            int delivered = 0;
            for (CashShopCatalog.Row r : items) {
                final Item built = toItem(r);
                if (built == null) {
                    log.error("Cash Shop cart: could not build item {} (itemId {}) for {}",
                            r.itemId(), r.itemId(), chr.getName());
                    return new Result(CashShopWindowPackets.BUY_UNKNOWN_ITEM, r.itemId(), delivered, 0);
                }
                if (!InventoryManipulator.addFromDrop(c, built, false)) {
                    // Unreachable if phase 3 is honest. If it ever fires, stop and charge
                    // NOTHING -- including for what already went in. Over-delivering is
                    // recoverable by a GM; over-charging is not.
                    log.error("Cash Shop cart: addFromDrop refused itemId {} for {} even though "
                                    + "the cumulative space check passed; {} item(s) delivered free",
                            r.itemId(), chr.getName(), delivered);
                    return new Result(CashShopWindowPackets.BUY_INVENTORY_FULL, r.itemId(), delivered, 0);
                }
                delivered++;
            }

            // ---- PHASE 5: deduct, and only now ----
            cs.gainNxCredit(-total);

            log.info("{} bought {} cash item(s) for {} NX from the Cash Shop window",
                    chr.getName(), delivered, total);
            return new Result(CashShopWindowPackets.BUY_OK, 0, delivered, (int) Math.min(total, Integer.MAX_VALUE));
        } catch (Exception e) {
            log.error("Cash Shop window: cart purchase failed for {}", chr.getName(), e);
            return new Result(CashShopWindowPackets.BUY_UNKNOWN_ITEM, 0, 0, 0);
        } finally {
            c.releaseClient();
        }
    }
}
