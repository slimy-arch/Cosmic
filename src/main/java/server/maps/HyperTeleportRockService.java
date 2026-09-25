package server.maps;

import client.Character;
import client.Client;
import client.inventory.Inventory;
import client.inventory.Item;
import constants.id.ItemId;
import constants.id.MapId;
import constants.inventory.ItemConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.PacketCreator;

/**
 * Custom: Kaentake Hyper Teleport Rock. The rock (5590001) is not consumed; owning an unexpired one is
 * the only item requirement, so its duration is set by however it was granted.
 */
public final class HyperTeleportRockService {
    private static final Logger log = LoggerFactory.getLogger(HyperTeleportRockService.class);

    private HyperTeleportRockService() {
    }

    public static boolean tryTeleport(Client c, int targetMapId) {
        Character player = c.getPlayer();
        if (player == null || !c.isLoggedIn() || !player.isLoggedinWorld()) {
            return false;
        }

        String rejection = checkPlayer(player);
        MapleMap targetMap = null;
        if (rejection == null) {
            targetMap = getTargetMap(c, targetMapId);
            rejection = checkTarget(targetMap);
        }
        if (rejection != null) {
            log.info("[HyperTeleportRock] chr={} id={} from={} to={} result={}",
                    player.getName(), player.getId(), player.getMapId(), targetMapId, rejection);
            player.dropMessage(1, message(rejection));
            return false;
        }

        log.info("[HyperTeleportRock] chr={} id={} from={} to={} result=accepted",
                player.getName(), player.getId(), player.getMapId(), targetMapId);
        player.forceChangeMap(targetMap, targetMap.getRandomPlayerSpawnpoint());
        return true;
    }

    private static String checkPlayer(Character player) {
        if (player.isChangingMaps() || player.isBanned()) {
            return "busy";
        }
        if (!player.isAlive()) {
            return "dead";
        }
        if (player.getCashShop().isOpened() || player.getTrade() != null || player.getShop() != null
                || player.getPlayerShop() != null || player.getMiniGame() != null || player.getHiredMerchant() != null) {
            return "occupied";
        }
        if (player.getEventInstance() != null) {
            return "event-instance";
        }
        if (!hasUnexpiredItem(player, ItemId.HYPER_TELEPORT_ROCK)) {
            return "missing-item";
        }
        int currentMapId = player.getMapId();
        if (MapId.isTimeTemple(currentMapId) || MapId.isBossExpeditionMap(currentMapId)
                || FieldLimit.CANNOTVIPROCK.check(player.getMap().getFieldLimit())) {
            return "from-restricted";
        }
        return null;
    }

    private static String checkTarget(MapleMap targetMap) {
        if (targetMap == null) {
            return "invalid-target";
        }
        int targetMapId = targetMap.getId();
        if (MapId.isTimeTemple(targetMapId) || MapId.isBossExpeditionMap(targetMapId)
                || FieldLimit.CANNOTVIPROCK.check(targetMap.getFieldLimit())
                || (targetMap.getForcedReturnId() != MapId.NONE && !MapId.isMapleIsland(targetMapId))) {
            return "to-restricted";
        }
        return null;
    }

    private static String message(String rejection) {
        return switch (rejection) {
            case "dead" -> "You cannot use Hyper Teleport Rocks while dead.";
            case "event-instance" -> "Hyper Teleport Rocks cannot be used inside event instances.";
            case "missing-item" -> "You need a Hyper Teleport Rock to use this.";
            case "from-restricted" -> "You cannot use Hyper Teleport Rocks here.";
            case "invalid-target", "to-restricted" -> "You cannot teleport to this map.";
            default -> "You cannot use Hyper Teleport Rocks right now.";
        };
    }

    private static MapleMap getTargetMap(Client c, int targetMapId) {
        if (targetMapId <= 0 || targetMapId == MapId.NONE) {
            return null;
        }
        try {
            return c.getChannelServer().getMapFactory().getMap(targetMapId);
        } catch (RuntimeException e) {
            log.warn("[HyperTeleportRock] failed to load map {}", targetMapId, e);
            return null;
        }
    }

    private static boolean hasUnexpiredItem(Character player, int itemId) {
        Inventory inventory = player.getInventory(ItemConstants.getInventoryType(itemId));
        if (inventory == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        for (Item item : inventory.listById(itemId)) {
            long expiration = item.getExpiration();
            if (item.getQuantity() > 0 && (expiration == -1 || expiration > now)) {
                return true;
            }
        }
        return false;
    }

    public static void enableActions(Client c) {
        c.sendPacket(PacketCreator.enableActions());
    }
}
