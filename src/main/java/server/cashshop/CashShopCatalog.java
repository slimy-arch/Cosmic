package server.cashshop;

import constants.inventory.ItemConstants;
import client.inventory.InventoryType;
import server.ItemInformationProvider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Cash Shop window's merchandise, owned entirely by the server.
 *
 * <p>THIS DOES NOT READ {@code Commodity.img}. The WZ table was the original source and it
 * imposed three limits that have nothing to do with what a shop should be able to sell:
 * a serial has to encode its own tab and category ({@code sn/10000000}, {@code (sn/100000)%100}),
 * and with it the per-category ceiling. There are no serials here at all: the ITEM ID is the key,
 * which caps a category at 100,000 rows and forbids tab 9 outright because
 * {@code World.addCashItemBought} indexes a nine-element list with it; every edit has to be
 * written into two byte-identical WZ copies; and {@code CashItemFactory} loads the whole
 * thing whether the shop wants it or not. Here the catalogue is a plain text file with the
 * tab and category as their own columns, so a row is just a row.
 *
 * <p>ON DISK, NOT ON THE CLASSPATH. A resource under {@code src/main/resources} lives inside
 * {@code Cosmic.jar}, and this server is launched from the jar -- so every catalogue tweak
 * would need a {@code mvn package}, and a forgotten one fails silently with stale data.
 * The file is resolved the same way {@code WZFiles} resolves the wz directory: a
 * {@code cashshop-path} system property if set, otherwise {@code ./cashshop}.
 *
 * <p>FORMAT -- tab-separated, {@code #} comments and blank lines ignored:
 * <pre>
 *   itemId  price  count  tab  category  period  gender  [name]
 * </pre>
 * {@code name} is optional; when absent it is resolved from {@code String.wz} at load, so
 * renaming an item in the WZ renames it in the shop. {@code period} is in days, 0 meaning
 * permanent. {@code gender} is 0 male / 1 female / 2 both, carried for display only.
 *
 * <p>Serials are OURS. They no longer encode anything and only have to be unique, which is
 * what removes the per-category ceiling.
 */
public final class CashShopCatalog {
    private static final Logger log = LoggerFactory.getLogger(CashShopCatalog.class);

    private static final String DEFAULT_DIR = "cashshop";
    private static final String FILE_NAME = "catalog.tsv";

    /**
     * A single line of merchandise.
     *
     * @param period days before the item expires, 0 = permanent
     */
    public record Row(int itemId, int price, int count,
                      int tab, int category, int period, int gender, String name) {
        public int bucket() {
            return (tab << 8) | category;
        }
    }

    private static volatile List<Row> all = List.of();
    private static volatile Map<Integer, Row> byItemId = Map.of();
    private static volatile Map<Integer, List<Row>> byBucket = Map.of();
    private static volatile List<int[]> index = List.of();      // {tab, category, count}

    private CashShopCatalog() {
    }

    public static Path file() {
        final String prop = System.getProperty("cashshop-path");
        return Path.of(prop != null ? prop : DEFAULT_DIR, FILE_NAME);
    }

    public static List<Row> all() {
        return all;
    }

    /**
     * The ITEM ID is the key. There are no serial numbers: one row per item, so what the
     * client sends to buy something is the item it wants.
     */
    public static Row byItemId(int itemId) {
        return byItemId.get(itemId);
    }

    /** Rows of one category, in file order. Never null. */
    public static List<Row> bucket(int tab, int category) {
        return byBucket.getOrDefault((tab << 8) | category, List.of());
    }

    /** {tab, category, count} for every populated category, in display order. */
    public static List<int[]> index() {
        return index;
    }

    public static int size() {
        return all.size();
    }

    /**
     * Loads the catalogue, replacing whatever was there. Safe to call again at runtime: the
     * four views are swapped in as immutable snapshots, so a reader mid-request keeps the
     * set it started with rather than seeing a half-built one.
     */
    public static void load() {
        final Path path = file();
        if (!Files.isRegularFile(path)) {
            log.warn("Cash Shop catalog: {} not found; the shop will be empty", path.toAbsolutePath());
            swap(List.of());
            return;
        }

        final ItemInformationProvider ii = ItemInformationProvider.getInstance();
        final List<Row> rows = new ArrayList<>();
        final Map<Integer, Row> seen = new HashMap<>();
        int lineNo = 0, bad = 0, dupe = 0, unusable = 0;

        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                lineNo++;
                final String s = line.strip();
                if (s.isEmpty() || s.charAt(0) == '#') {
                    continue;
                }
                final String[] f = s.split("\t");
                if (f.length < 7) {
                    if (bad++ < 5) {
                        log.warn("Cash Shop catalog line {}: expected 7+ columns, got {}", lineNo, f.length);
                    }
                    continue;
                }
                final int itemId, price, count, tab, cat, period, gender;
                try {
                    itemId = Integer.parseInt(f[0].strip());
                    price = Integer.parseInt(f[1].strip());
                    count = Integer.parseInt(f[2].strip());
                    tab = Integer.parseInt(f[3].strip());
                    cat = Integer.parseInt(f[4].strip());
                    period = Integer.parseInt(f[5].strip());
                    gender = Integer.parseInt(f[6].strip());
                } catch (NumberFormatException e) {
                    if (bad++ < 5) {
                        log.warn("Cash Shop catalog line {}: non-numeric field", lineNo);
                    }
                    continue;
                }

                // An item with no inventory to land in can be listed but never delivered --
                // cash PACKAGES (91xxxxx) are the real case, since getInventoryType computes
                // itemId/1000000 == 9 and returns UNDEFINED.
                if (ItemConstants.getInventoryType(itemId) == InventoryType.UNDEFINED) {
                    unusable++;
                    continue;
                }

                String name = f.length > 7 ? f[7].strip() : "";
                if (name.isEmpty()) {
                    name = ii.getName(itemId);
                }
                // A nameless row reaches the player as a nameless item. Dropping it is the
                // only honest option, and it is counted so the log says how many went.
                if (name == null || name.isEmpty()) {
                    unusable++;
                    continue;
                }
                if (name.length() > CashShopWindowPackets.MAX_NAME) {
                    name = name.substring(0, CashShopWindowPackets.MAX_NAME);
                }

                final Row row = new Row(itemId, Math.max(0, price), Math.max(1, count),
                        tab, cat, Math.max(0, period), gender, name);
                final Row prev = seen.putIfAbsent(itemId, row);
                if (prev != null) {
                    if (dupe++ < 5) {
                        log.warn("Cash Shop catalog line {}: duplicate item id {}, keeping the first", lineNo, itemId);
                    }
                    continue;
                }
                rows.add(row);
            }
        } catch (IOException e) {
            log.error("Cash Shop catalog: could not read {}", path.toAbsolutePath(), e);
            return;                                   // keep whatever was already loaded
        }

        swap(rows);
        log.info("Cash Shop catalog: {} rows across {} categories from {} "
                        + "({} unusable, {} duplicate serials, {} malformed lines)",
                all.size(), index.size(), path, unusable, dupe, bad);
    }

    private static void swap(List<Row> rows) {
        final Map<Integer, Row> byId = new HashMap<>(rows.size() * 2);
        final Map<Integer, List<Row>> buckets = new LinkedHashMap<>();
        for (Row r : rows) {
            byId.put(r.itemId(), r);
            buckets.computeIfAbsent(r.bucket(), k -> new ArrayList<>()).add(r);
        }
        final List<int[]> idx = new ArrayList<>(buckets.size());
        final List<Integer> keys = new ArrayList<>(buckets.keySet());
        Collections.sort(keys);
        for (int k : keys) {
            final List<Row> b = buckets.get(k);
            buckets.put(k, List.copyOf(b));
            idx.add(new int[]{k >> 8, k & 0xFF, b.size()});
        }

        all = List.copyOf(rows);
        byItemId = Map.copyOf(byId);
        byBucket = Map.copyOf(buckets);
        index = List.copyOf(idx);
    }
}
