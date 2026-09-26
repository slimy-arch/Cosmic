package server;

import client.Character;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import provider.Data;
import provider.DataProviderFactory;
import provider.wz.WZFiles;
import server.life.MonsterDropEntry;
import server.life.MonsterInformationProvider;
import tools.DatabaseConnection;
import tools.Pair;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Query layer behind the Kaentake Monster Book (C2S MONSTER_BOOK_QUERY / S2C MONSTER_BOOK_RESULT).
 * Read-only: nothing here touches character state.
 *
 * <p>Chances are reported in parts per million and use exactly the formula
 * {@code MapleMap.dropItemsFromMonsterOnMap} rolls with:
 * {@code chance * (boss ? bossDropRate : dropRate) * getCardRate(item) * (100 + equipDropBonus) / 100}
 * against {@code Randomizer.nextInt(999999)}. Keep the two in step, or the book lies about drops.
 * (The monsterbook-publish reference also multiplied monster cards by a world card rate; this server
 * has no such multiplier, so it is deliberately absent.) Per-instance modifiers such as SHOWDOWN are
 * not knowable here and are left out.
 *
 * <p>Liveness: {@code retrieveDrop} and the card caches here are only dropped by {@code !reloaddrops}
 * (which calls {@link #clearDropCaches}) or a restart. {@link #loadDroppers} reads drop_data on every
 * call. The book's own item/map lists come from String.wz/MonsterBook.img on the client and need a
 * regenerated client image, not a server command.
 */
public final class DropSearchService {
    private static final Logger log = LoggerFactory.getLogger(DropSearchService.class);

    /** One roll succeeds when {@code nextInt(999999) < chance}; the book prints {@code ppm / 10000.0}. */
    private static final int DROP_DENOMINATOR = 1_000_000;

    /** Matches kept before ranking; a broad needle should not rank the whole item table. */
    private static final int SEARCH_HARD_CAP = 2_000;

    private record NameEntry(int id, String name, String key) {
    }

    private static volatile List<NameEntry> itemIndex;
    private static volatile Set<Integer> bookItemIds;
    private static volatile Map<Integer, Integer> cardByMob;
    private static volatile Set<Integer> cardIconIds;

    private DropSearchService() {
    }

    // ---------------------------------------------------------------------------------------------
    // Searches
    // ---------------------------------------------------------------------------------------------

    /**
     * Item-name search for the book (type 1), restricted to items at least one CARDED mob drops: the
     * book answers a hit with the cards of its droppers, so any other item could only open an empty grid.
     */
    public static int[] findBookItems(String query) {
        Set<Integer> allowed = bookItemIds();
        // empty means the read failed, not that nothing is droppable: answer unfiltered, not nothing
        return search(itemIndex(), query, allowed.isEmpty() ? null : allowed);
    }

    /**
     * Case/accent/punctuation-insensitive substring search, ranked exact, prefix, word prefix, then
     * anywhere; ties go to the shorter name, then the lower id.
     *
     * @param allowed when non-null, ids outside it never match (applied before the hard cap)
     */
    private static int[] search(List<NameEntry> index, String query, Set<Integer> allowed) {
        final String needle = normalize(query);
        if (needle.isEmpty()) {
            return new int[0];
        }

        List<int[]> hits = new ArrayList<>();   // {id, rank, nameLength}
        for (NameEntry entry : index) {
            if (allowed != null && !allowed.contains(entry.id())) {
                continue;
            }
            int at = entry.key().indexOf(needle);
            if (at < 0) {
                continue;
            }
            int rank;
            if (entry.key().equals(needle)) {
                rank = 0;
            } else if (at == 0) {
                rank = 1;
            } else if (entry.key().contains(" " + needle)) {
                rank = 2;
            } else {
                rank = 3;
            }
            hits.add(new int[]{entry.id(), rank, entry.name().length()});
            if (hits.size() >= SEARCH_HARD_CAP) {
                break;
            }
        }

        hits.sort(Comparator.<int[]>comparingInt(h -> h[1]).thenComparingInt(h -> h[2]).thenComparingInt(h -> h[0]));
        int[] ret = new int[hits.size()];
        for (int i = 0; i < ret.length; i++) {
            ret[i] = hits.get(i)[0];
        }
        return ret;
    }

    private static String normalize(String s) {
        if (s == null) {
            return "";
        }
        String folded = Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
        return folded.replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static List<NameEntry> itemIndex() {
        List<NameEntry> idx = itemIndex;
        if (idx != null) {
            return idx;
        }
        List<NameEntry> built = new ArrayList<>();
        Set<Integer> seen = new HashSet<>();
        for (Pair<Integer, String> item : ItemInformationProvider.getInstance().getAllItems()) {
            String name = item.getRight();
            if (name == null || name.isBlank() || name.equals("NO-NAME") || !seen.add(item.getLeft())) {
                continue;
            }
            String key = normalize(name);
            if (!key.isEmpty()) {
                built.add(new NameEntry(item.getLeft(), name, key));
            }
        }
        itemIndex = Collections.unmodifiableList(built);
        return itemIndex;
    }

    // ---------------------------------------------------------------------------------------------
    // Chances
    // ---------------------------------------------------------------------------------------------

    /**
     * One mob's drop table as {@code itemId -> ppm} at {@code chr}'s live rates, best chance first.
     * Meso rows and items with no String.wz name are skipped: the client can draw neither. The same
     * item can have one row per gating quest; the best one is kept.
     */
    public static LinkedHashMap<Integer, Integer> mobDropChances(Character chr, int mobId) {
        final int rate = MonsterInformationProvider.getInstance().isBoss(mobId) ? chr.getBossDropRate() : chr.getDropRate();

        List<int[]> rows = new ArrayList<>();   // {itemId, ppm}
        for (MonsterDropEntry de : MonsterInformationProvider.getInstance().retrieveDrop(mobId)) {
            if (de.chance <= 0 || de.itemId == 0 || !hasName(de.itemId)) {
                continue;
            }
            rows.add(new int[]{de.itemId, ppm(chr, de.chance, rate, de.itemId)});
        }
        rows.sort(Comparator.<int[]>comparingInt(row -> -row[1]).thenComparingInt(row -> row[0]));

        LinkedHashMap<Integer, Integer> ret = new LinkedHashMap<>();
        for (int[] row : rows) {
            ret.putIfAbsent(row[0], row[1]);
        }
        return ret;
    }

    /**
     * Mobs that drop {@code itemId} as {@code mobId -> ppm} at {@code chr}'s live rates, best chance
     * first. Only mobs the book can draw are kept: they need a monster card with {@code info/iconRaw}
     * artwork, since the reply is rendered as card icons. The boss multiplier is resolved per mob, so
     * the % under each card equals the one on that mob's own Dropping tab.
     */
    public static LinkedHashMap<Integer, Integer> itemDroppers(Character chr, int itemId) {
        final Map<Integer, Integer> cards = cardByMob();
        final Set<Integer> drawable = cardIconIds();
        final MonsterInformationProvider mi = MonsterInformationProvider.getInstance();

        List<int[]> rows = new ArrayList<>();   // {mobId, ppm}
        for (Map.Entry<Integer, Integer> dropper : loadDroppers(itemId).entrySet()) {
            final int mobId = dropper.getKey();
            Integer cardId = cards.get(mobId);
            if (cardId == null || !drawable.contains(cardId)) {
                continue;
            }
            int rate = mi.isBoss(mobId) ? chr.getBossDropRate() : chr.getDropRate();
            rows.add(new int[]{mobId, ppm(chr, dropper.getValue(), rate, itemId)});
        }
        rows.sort(Comparator.<int[]>comparingInt(row -> -row[1]).thenComparingInt(row -> row[0]));

        LinkedHashMap<Integer, Integer> ret = new LinkedHashMap<>();
        for (int[] row : rows) {
            ret.put(row[0], row[1]);
        }
        return ret;
    }

    /** MapleMap.dropItemsFromMonsterOnMap's dropChanceF, clamped to [1, 1_000_000]. */
    private static int ppm(Character chr, int chance, int rate, int itemId) {
        double eff = (double) chance * rate * chr.getCardRate(itemId);
        int equipDropBonus = chr.getEquipDropRateBonus();
        if (equipDropBonus != 0) {
            eff *= (100 + equipDropBonus) / 100.0;
        }
        return (int) Math.min(DROP_DENOMINATOR, Math.max(1, Math.round(eff)));
    }

    private static boolean hasName(int itemId) {
        String name = ItemInformationProvider.getInstance().getName(itemId);
        return name != null && !name.isEmpty() && !name.equals("null");
    }

    // ---------------------------------------------------------------------------------------------
    // Table reads and caches
    // ---------------------------------------------------------------------------------------------

    /** {@code mobId -> best chance} for one item, straight from drop_data (never cached). */
    private static Map<Integer, Integer> loadDroppers(int itemId) {
        Map<Integer, Integer> ret = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT dropperid, MAX(chance) AS chance FROM drop_data "
                             + "WHERE itemid = ? AND chance > 0 GROUP BY dropperid")) {
            ps.setInt(1, itemId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ret.put(rs.getInt("dropperid"), rs.getInt("chance"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load the droppers of item {}", itemId, e);
        }
        return ret;
    }

    /** Every item at least one carded mob drops; the allow-list behind {@link #findBookItems}. */
    private static Set<Integer> bookItemIds() {
        Set<Integer> ids = bookItemIds;
        if (ids != null) {
            return ids;
        }
        Set<Integer> loading = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(
                     "SELECT DISTINCT d.itemid FROM drop_data d "
                             + "JOIN monstercarddata m ON m.mobid = d.dropperid "
                             + "WHERE d.chance > 0 AND d.itemid > 0");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                loading.add(rs.getInt("itemid"));
            }
        } catch (SQLException e) {
            // not cached: an unfiltered search for one player beats a permanently empty one for everybody
            log.error("Failed to index the items carded mobs drop", e);
            return Set.of();
        }
        bookItemIds = loading;
        return loading;
    }

    /** {@code mobId -> cardId} from monstercarddata (lowest card id if a mob has several). */
    private static Map<Integer, Integer> cardByMob() {
        Map<Integer, Integer> map = cardByMob;
        if (map != null) {
            return map;
        }
        Map<Integer, Integer> loading = new HashMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT cardid, mobid FROM monstercarddata");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                loading.merge(rs.getInt("mobid"), rs.getInt("cardid"), Math::min);
            }
        } catch (SQLException e) {
            log.error("Failed to load monstercarddata", e);
            return Map.of();
        }
        cardByMob = loading;
        return loading;
    }

    /** Card ids whose Item.wz entry has {@code info/iconRaw}: the art the book draws for a card. */
    private static Set<Integer> cardIconIds() {
        Set<Integer> ids = cardIconIds;
        if (ids != null) {
            return ids;
        }
        Set<Integer> loading = new HashSet<>();
        Data cards = DataProviderFactory.getDataProvider(WZFiles.ITEM).getData("Consume/0238.img");
        if (cards != null) {
            for (Data card : cards.getChildren()) {
                if (card.getChildByPath("info/iconRaw") != null) {
                    try {
                        loading.add(Integer.parseInt(card.getName()));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        int missing = cardByMob().size() - (int) cardByMob().values().stream().filter(loading::contains).count();
        if (missing > 0) {
            log.warn("{} monstercarddata row(s) point at cards without info/iconRaw in Item.wz; the Monster Book cannot draw them", missing);
        }
        cardIconIds = loading;
        return loading;
    }

    /**
     * Drops what comes from the drop/card TABLES, for {@code !reloaddrops}. The WZ-derived indexes stay:
     * no table edit can change a name or a card canvas.
     */
    public static void clearDropCaches() {
        bookItemIds = null;
        cardByMob = null;
    }
}
