package server.life;

import com.esotericsoftware.yamlbeans.YamlReader;
import constants.string.CharsetConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side max HP overrides from {@code mob-hp-overrides.yaml}, next to {@code config.yaml}.
 * <p>
 * WZ {@code maxHP} is a 32-bit int (and Cosmic's XML provider has no long type), so a mob with more
 * than 2,147,483,647 HP is declared here instead of in the data. The client never needs the real
 * value: the boss bar and the mob HP gauge are sent as ratios. Format, one mob per line:
 * <pre>
 *   8800002: 5000000000   # Zakum body
 * </pre>
 * Reload with {@code !reloadmobhp}; mobs already spawned keep their HP.
 */
public final class MobHpOverrides {
    private static final Logger log = LoggerFactory.getLogger(MobHpOverrides.class);
    public static final String FILE_NAME = "mob-hp-overrides.yaml";

    private static volatile Map<Integer, Long> hpByMobId = Map.of();

    static {
        reload();
    }

    private MobHpOverrides() {
    }

    public static void apply(int mobId, MonsterStats stats) {
        Long hp = hpByMobId.get(mobId);
        if (hp != null) {
            stats.setHp(hp);
        }
    }

    /** Re-reads the file; returns the number of overrides loaded, or -1 if it could not be read. */
    public static synchronized int reload() {
        Path path = Path.of(FILE_NAME);
        if (!Files.isRegularFile(path)) {
            hpByMobId = Map.of();
            return 0;
        }

        Map<Integer, Long> loaded = new HashMap<>();
        try (Reader in = Files.newBufferedReader(path, CharsetConstants.CHARSET)) {
            Object root = new YamlReader(in).read();
            if (root instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    try {
                        int mobId = Integer.parseInt(String.valueOf(e.getKey()).trim());
                        long hp = Long.parseLong(String.valueOf(e.getValue()).trim().replace("_", "").replace(",", ""));
                        if (mobId > 0 && hp > 0) {
                            loaded.put(mobId, hp);
                        } else {
                            log.warn("{}: ignoring {}: {} (id and HP must be positive)", FILE_NAME, e.getKey(), e.getValue());
                        }
                    } catch (NumberFormatException nfe) {
                        log.warn("{}: ignoring {}: {} (not a whole number)", FILE_NAME, e.getKey(), e.getValue());
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to read {} - keeping the previous overrides.", FILE_NAME, e);
            return -1;
        }

        hpByMobId = Map.copyOf(loaded);
        log.info("Loaded {} mob HP override(s) from {}.", loaded.size(), path.toAbsolutePath());
        return loaded.size();
    }
}
