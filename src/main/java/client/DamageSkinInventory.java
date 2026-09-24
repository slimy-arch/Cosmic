/*
    Damage skins owned by one character (Kaentake damageskin.cpp). Skin 0 is the stock digits and is
    always owned without a row. Purchases are written to damageskin_inventory immediately.
*/
package client;

import tools.DatabaseConnection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

public final class DamageSkinInventory {
    public static final int DEFAULT_SKIN_ID = 0;

    private final Set<Integer> owned = new HashSet<>();

    public synchronized boolean ownsSkin(int skinId) {
        return skinId == DEFAULT_SKIN_ID || owned.contains(skinId);
    }

    /** Owned skins without the implicit default, ascending. */
    public synchronized Set<Integer> getOwnedIds() {
        return Collections.unmodifiableSet(new TreeSet<>(owned));
    }

    /** Returns false if the skin was already owned. */
    public boolean addSkin(int characterId, int skinId) throws SQLException {
        if (skinId == DEFAULT_SKIN_ID) {
            return false;
        }
        synchronized (this) {
            if (owned.contains(skinId)) {
                return false;
            }
        }

        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("INSERT IGNORE INTO damageskin_inventory (characterId, skinId) VALUES (?, ?)")) {
            ps.setInt(1, characterId);
            ps.setInt(2, skinId);
            ps.executeUpdate();
        }

        synchronized (this) {
            owned.add(skinId);
        }
        return true;
    }

    public void loadSkins(int characterId) throws SQLException {
        Set<Integer> loaded = new HashSet<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement("SELECT skinId FROM damageskin_inventory WHERE characterId = ?")) {
            ps.setInt(1, characterId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    loaded.add(rs.getInt("skinId"));
                }
            }
        }

        synchronized (this) {
            owned.clear();
            owned.addAll(loaded);
        }
    }
}
