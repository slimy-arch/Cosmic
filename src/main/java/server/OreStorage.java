package server;

import client.Client;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.ItemFactory;
import constants.game.GameConstants;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * kaentake Storage Bag: one per-account bag of a single kind (0 ore, 1 scroll, 2 chair, 3 cash). All four kinds
 * share one orestorages row and own disjoint capacity columns; items persist in inventoryitems under
 * ItemFactory OREBAG..CASHBAG. Ported from Kaentake/kaentake-main/implemented/storagebag, trimmed to what the bag
 * window uses (the native trunk-UI paths are gone).
 */
public class OreStorage {
    private static final Logger log = LoggerFactory.getLogger(OreStorage.class);

    public static final int KIND_COUNT = 4;
    public static final int MAX_SLOTS = 200;   // the client window holds 200 cells per tab

    // kind -> orestorages capacity column (a fixed whitelist, inlined into SQL) and a name for logging.
    private static final String[] SLOT_COLUMN = {"slots", "scrollSlots", "chairSlots", "cashSlots"};
    private static final String[] KIND_NAME = {"ore", "scroll", "chair", "cash"};

    private static ItemFactory factoryFor(int kind) {
        return switch (kind) {
            case 1 -> ItemFactory.SCROLLBAG;
            case 2 -> ItemFactory.CHAIRBAG;
            case 3 -> ItemFactory.CASHBAG;
            default -> ItemFactory.OREBAG;
        };
    }

    private final int kind;
    private final ItemFactory factory;
    private final int id;
    private final int slots;
    private List<Item> items = new LinkedList<>();
    private final Lock lock = new ReentrantLock(true);

    private OreStorage(int kind, int id, int slots) {
        this.kind = kind;
        this.factory = factoryFor(kind);
        this.id = id;
        this.slots = Math.min(slots, MAX_SLOTS);
    }

    private static void create(int accountId, int world) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT INTO orestorages (accountid, world) VALUES (?, ?)")) {
            ps.setInt(1, accountId);
            ps.setInt(2, world);
            ps.executeUpdate();
        }
    }

    public static OreStorage loadOrCreateFromDB(int kind, int accountId, int world) {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT storageid, slots, scrollSlots, chairSlots, cashSlots FROM orestorages WHERE accountid = ? AND world = ? ORDER BY storageid LIMIT 1")) {
            ps.setInt(1, accountId);
            ps.setInt(2, world);

            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    create(accountId, world);
                    return loadOrCreateFromDB(kind, accountId, world);
                }

                OreStorage ret = new OreStorage(kind, rs.getInt("storageid"), rs.getInt(SLOT_COLUMN[kind]));
                for (Pair<Item, InventoryType> item : ret.factory.loadItems(ret.id, false)) {
                    ret.items.add(item.getLeft());
                }
                return ret;
            }
        } catch (SQLException ex) {
            log.error("SQL error occurred when trying to load {} bag for accId {}, world {}", KIND_NAME[kind], accountId, GameConstants.WORLD_NAMES[world], ex);
            throw new RuntimeException(ex);
        }
    }

    public int getKind() {
        return kind;
    }

    /**
     * Every SQLException propagates to the caller's save transaction so it rolls back: swallowing one after
     * saveItems' delete-all would commit a wiped bag.
     */
    public void saveToDB(Connection con) throws SQLException {
        List<Pair<Item, InventoryType>> itemsWithType = new ArrayList<>();
        for (Item item : getItems()) {
            itemsWithType.add(new Pair<>(item, item.getInventoryType()));
        }
        factory.saveItems(itemsWithType, id, con);
    }

    public boolean takeOut(Item item) {
        lock.lock();
        try {
            return items.remove(item);
        } finally {
            lock.unlock();
        }
    }

    public boolean store(Item item) {
        lock.lock();
        try {
            if (isFull()) {
                return false;
            }
            items.add(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Stores {@code item}, topping up existing stacks of the same item and owner first. All-or-nothing: returns
     * false, with nothing changed, when the bag cannot take the whole quantity. The item's quantity may be reduced
     * as it is merged, so pass a copy.
     */
    public boolean storeMerge(Item item, Client c) {
        lock.lock();
        try {
            if (item == null) {
                return false;
            }
            ItemInformationProvider ii = ItemInformationProvider.getInstance();
            int itemId = item.getItemId();
            boolean stackable = item.getInventoryType() != InventoryType.EQUIP
                    && !ItemConstants.isRechargeable(itemId)
                    && !ii.isPickupRestricted(itemId);

            if (stackable) {
                short slotMax = ii.getSlotMax(c, itemId);
                int existingRoom = 0;
                for (Item ex : items) {
                    if (ex.getItemId() == itemId && ex.getQuantity() < slotMax
                            && Objects.equals(ex.getOwner(), item.getOwner())) {
                        existingRoom += slotMax - ex.getQuantity();
                    }
                }
                if (isFull() && existingRoom < item.getQuantity()) {
                    return false;
                }
                for (Item ex : items) {
                    if (item.getQuantity() <= 0) {
                        break;
                    }
                    if (ex.getItemId() != itemId || ex.getQuantity() >= slotMax
                            || !Objects.equals(ex.getOwner(), item.getOwner())) {
                        continue;
                    }
                    int move = Math.min(slotMax - ex.getQuantity(), item.getQuantity());
                    ex.setQuantity((short) (ex.getQuantity() + move));
                    item.setQuantity((short) (item.getQuantity() - move));
                }
                if (item.getQuantity() <= 0) {
                    return true;
                }
            }

            if (isFull()) {
                return false;
            }
            items.add(item);
            return true;
        } finally {
            lock.unlock();
        }
    }

    /** A copy, so callers (saveToDB, the snapshot packet) iterate off-lock. */
    public List<Item> getItems() {
        lock.lock();
        try {
            return new ArrayList<>(items);
        } finally {
            lock.unlock();
        }
    }

    /** Consolidates identical stacks up to slot-max and compacts; the window's sort/merge button. */
    public void mergeStacks(Client c) {
        lock.lock();
        try {
            StorageInventory msi = new StorageInventory(c, items);
            msi.mergeItems();
            items = msi.sortItems();
        } finally {
            lock.unlock();
        }
    }

    public boolean isFull() {
        lock.lock();
        try {
            return items.size() >= slots;
        } finally {
            lock.unlock();
        }
    }
}
