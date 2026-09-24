/*
    Damage skin shop catalog (Kaentake damageskin.cpp). The skins live only in the client's Custom.wz, so
    the rows are seeded by changeset 28, which kaentake/tools/gen_damageskin_catalog.py generates. Prices are
    edited in damageskin_catalog and picked up on the next server start.
*/
package client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public final class DamageSkinCatalog {
    private static final Logger log = LoggerFactory.getLogger(DamageSkinCatalog.class);

    private static volatile Map<Integer, Long> prices = Collections.emptyMap();

    public static void load() {
        Map<Integer, Long> loaded = new TreeMap<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT skinId, priceMesos FROM damageskin_catalog");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                int skinId = rs.getInt("skinId");
                if (skinId != DamageSkinInventory.DEFAULT_SKIN_ID) {
                    loaded.put(skinId, rs.getLong("priceMesos"));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to load the damage skin catalog", e);
            return;
        }
        prices = Collections.unmodifiableMap(loaded);
        log.info("Loaded {} damage skins", loaded.size());
    }

    /** Price in mesos, or -1 if the skin is not for sale. */
    public static long getPrice(int skinId) {
        Long price = prices.get(skinId);
        return price == null ? -1L : price;
    }

    /** skinId -> price, ascending by id. */
    public static Map<Integer, Long> getAll() {
        return prices;
    }
}
