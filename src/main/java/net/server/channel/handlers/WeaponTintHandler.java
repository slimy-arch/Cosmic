package net.server.channel.handlers;

import client.Character;
import client.Client;
import client.inventory.Equip;
import client.inventory.Inventory;
import client.inventory.InventoryType;
import client.inventory.Item;
import client.inventory.manipulator.InventoryManipulator;
import client.Skill;
import client.SkillFactory;
import constants.id.ItemId;
import net.AbstractPacketHandler;
import net.packet.InPacket;
import server.colorprism.ColorPrismPackets;
import server.colorprism.TintValues;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Handles {@code RecvOpcode.WEAPON_TINT_ACTION} (0x372E), emitted by the client DLL
 * ({@code weapontint.cpp}) for the Coloring Prism window.
 * <ul>
 *   <li>{@code 0} request - resend every tint this character owns</li>
 *   <li>{@code 1} apply       - invType(1) invPos(2) itemId(4) hue(2) chroma(1) bright(1) prismPos(2) layer(1)</li>
 *   <li>{@code 2} restore     - invType(1) invPos(2) itemId(4) prismPos(2) layer(1)</li>
 *   <li>{@code 3} applyLook   - kind(1) hue(2) chroma(1) bright(1) prismPos(2)</li>
 *   <li>{@code 4} restoreLook - kind(1) prismPos(2)</li>
 * </ul>
 * ONE item, {@link ItemId#COLORING_PRISM}, pays for all five of the window's tabs. Actions 1 and 2
 * name their target by inventory address, and it can be either an EQUIP, cash or not, whose
 * {@code layer} picks its body or its effect sprites, or a cash EFFECT item, which is a plain
 * {@link Item} rather than an {@link Equip} and so carries its one colour in three columns of its
 * own. Actions 3 and 4 dye the character's own HAIR, FACE or SKIN, which have no inventory address,
 * so they carry a kind instead and the server reads the look off the character.
 * <p>
 * Everything the client sends is a HINT and is re-derived here: the item at that position must
 * still exist and must still be the id the client thinks it is. There is deliberately no
 * {@code isCash} test, and the client's drop gate matches. The prism itself is validated the same
 * way - by ownership rather than by the slot it was double-clicked from - rather than trusting
 * the position the click came from.
 */
public final class WeaponTintHandler extends AbstractPacketHandler {

    private static final long SNAPSHOT_INTERVAL_MS = 500;
    // Bounded by the number of distinct characters that ever asked; one Long per character.
    private static final Map<Integer, Long> lastSnapshotRequest = new ConcurrentHashMap<>();

    @Override
    public void handlePacket(InPacket p, Client c) {
        Character player = c.getPlayer();
        if (player == null || !c.isLoggedIn() || !player.isLoggedinWorld()) {
            return;
        }
        if (p.available() < Byte.BYTES) {
            return;
        }

        byte action = p.readByte();
        switch (action) {
            case ColorPrismPackets.ACTION_REQUEST_SNAPSHOT -> {
                // The only action that costs nothing, so the only one worth spamming: each reply
                // walks two inventories and the skill maps.
                final long now = System.currentTimeMillis();
                final Long last = lastSnapshotRequest.put(player.getId(), now);
                if (last == null || now - last >= SNAPSHOT_INTERVAL_MS) {
                    player.sendPacket(ColorPrismPackets.snapshot(player));
                }
            }
            case ColorPrismPackets.ACTION_APPLY -> {
                if (p.available() < 1 + 2 + 4 + 2 + 1 + 1 + 2 + 1) {
                    return;
                }
                Target target = readTarget(p);
                // SIGNED. The sign selects the semantic: positive rotates, negative is an
                // absolute target encoded as -(degrees + 1). Masking to 0xFFFF turned every
                // absolute value into a nonsense rotation of ~65000 degrees.
                int hue = p.readShort();
                int chroma = p.readByte();
                int bright = p.readByte();
                short prismPos = p.readShort();
                int layer = p.readByte();
                if (!checkTint(player, hue, chroma, bright)) {
                    return;
                }
                handleApply(c, player, target, hue, chroma, bright, prismPos, layer);
            }
            case ColorPrismPackets.ACTION_RESTORE -> {
                if (p.available() < 1 + 2 + 4 + 2 + 1) {
                    return;
                }
                Target target = readTarget(p);
                short prismPos = p.readShort();
                int layer = p.readByte();
                handleRestore(c, player, target, prismPos, layer);
            }
            // Hair / Face. No target: hair and eyes have no inventory address, so
            // the server reads the character's own look instead and there is nothing here the
            // client could lie about beyond which of the two it means.
            case ColorPrismPackets.ACTION_APPLY_LOOK -> {
                if (p.available() < 1 + 2 + 1 + 1 + 2) {
                    return;
                }
                int kind = p.readByte();
                // SIGNED. The sign selects the semantic: positive rotates, negative is an
                // absolute target encoded as -(degrees + 1). Masking to 0xFFFF turned every
                // absolute value into a nonsense rotation of ~65000 degrees.
                int hue = p.readShort();
                int chroma = p.readByte();
                int bright = p.readByte();
                short prismPos = p.readShort();
                if (!checkTint(player, hue, chroma, bright)) {
                    return;
                }
                handleApplyLook(c, player, kind, hue, chroma, bright, prismPos);
            }
            case ColorPrismPackets.ACTION_RESTORE_LOOK -> {
                if (p.available() < 1 + 2) {
                    return;
                }
                int kind = p.readByte();
                short prismPos = p.readShort();
                handleRestoreLook(c, player, kind, prismPos);
            }
            // Skills. Named by SKILL ID: a skill has no inventory address, so unlike an equip
            // there is nothing to re-derive it from. What IS verified is that the character
            // actually knows the skill, which is what stops a client dyeing all 616.
            case ColorPrismPackets.ACTION_APPLY_SKILL -> {
                if (p.available() < 4 + 2 + 1 + 1 + 2) {
                    return;
                }
                int skillId = p.readInt();
                // SIGNED, for the same reason as every other hue on this opcode.
                int hue = p.readShort();
                int chroma = p.readByte();
                int bright = p.readByte();
                short prismPos = p.readShort();
                int layer = ColorPrismPackets.LAYER_BODY;
                if (p.available() >= 1) {
                    layer = p.readByte();
                }
                if (!checkTint(player, hue, chroma, bright)) {
                    return;
                }
                handleApplySkill(c, player, skillId, hue, chroma, bright, prismPos, layer);
            }
            case ColorPrismPackets.ACTION_RESTORE_SKILL -> {
                if (p.available() < 4 + 2) {
                    return;
                }
                int skillId = p.readInt();
                short prismPos = p.readShort();
                int layer = ColorPrismPackets.LAYER_BODY;
                if (p.available() >= 1) {
                    layer = p.readByte();
                }
                handleRestoreSkill(c, player, skillId, prismPos, layer);
            }
            default -> {
            }
        }
    }

    /**
     * Does this character know this skill?
     * <p>
     * The only validation available for a skill target. Every other action names its target by
     * inventory position and the server re-reads the item that is actually there; a skill id is
     * just four bytes on the wire with nothing behind it, so without this a client could write a
     * row for every skill in the game.
     */
    private static boolean knowsSkill(Character player, int skillId) {
        Skill skill = SkillFactory.getSkill(skillId);
        return skill != null && player.getSkillLevel(skill) > 0;
    }

    private void handleApplySkill(Client c, Character player, int skillId, int hue, int chroma,
                                  int bright, short prismPos, int layer) {
        if (!knowsSkill(player, skillId)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "You haven't learned that skill.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }
        if (layer == ColorPrismPackets.LAYER_EFFECTS) {
            player.setSkillFxTint(skillId, hue, chroma, bright);
        } else {
            player.setSkillTint(skillId, hue, chroma, bright);
        }
        InventoryManipulator.removeFromSlot(c, InventoryType.CASH, slot, (short) 1, false);
        // Forced now rather than left to logout, the same as every other apply path: a crash in
        // between would otherwise cost the player a prism and give nothing back.
        player.saveCharToDB();
        succeed(player);
    }

    private void handleRestoreSkill(Client c, Character player, int skillId, short prismPos, int layer) {
        if (!knowsSkill(player, skillId)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "You haven't learned that skill.");
            return;
        }
        final boolean tinted = (layer == ColorPrismPackets.LAYER_EFFECTS)
                ? player.isSkillFxTinted(skillId)
                : player.isSkillBodyTinted(skillId);
        if (!tinted) {
            // Nothing to undo. Refuse WITHOUT consuming the prism: this is the path Reset then
            // Confirm takes on a skill that was never dyed, and burning the item for a no-op
            // would be a trap.
            fail(player, ColorPrismPackets.RESULT_NOT_TINTED,
                    layer == ColorPrismPackets.LAYER_EFFECTS
                            ? "That skill's effects are already their original color."
                            : "That skill is already its original color.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }
        if (layer == ColorPrismPackets.LAYER_EFFECTS) {
            player.clearSkillFxTint(skillId);
        } else {
            player.clearSkillTint(skillId);
        }
        InventoryManipulator.removeFromSlot(c, InventoryType.CASH, slot, (short) 1, false);
        player.saveCharToDB();
        succeed(player);
    }

    private static boolean isLookKind(int kind) {
        return kind == ColorPrismPackets.TINT_KEY_HAIR
                || kind == ColorPrismPackets.TINT_KEY_FACE
                || kind == ColorPrismPackets.TINT_KEY_SKIN;
    }

    private void handleApplyLook(Client c, Character player, int kind, int hue, int chroma, int bright,
                                 short prismPos) {
        if (!isLookKind(kind)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "That can't be dyed with a Coloring Prism.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }
        if (kind == ColorPrismPackets.TINT_KEY_HAIR) {
            player.setHairTint(hue, chroma, bright);
        } else if (kind == ColorPrismPackets.TINT_KEY_SKIN) {
            player.setSkinTint(hue, chroma, bright);
        } else {
            player.setFaceTint(hue, chroma, bright);
        }
        InventoryManipulator.removeFromSlot(c, InventoryType.CASH, slot, (short) 1, false);
        player.saveCharToDB();
        succeed(player);
    }

    private void handleRestoreLook(Client c, Character player, int kind, short prismPos) {
        if (!isLookKind(kind)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "That can't be dyed with a Coloring Prism.");
            return;
        }
        final boolean tinted = (kind == ColorPrismPackets.TINT_KEY_HAIR) ? player.isHairTinted()
                : (kind == ColorPrismPackets.TINT_KEY_SKIN) ? player.isSkinTinted()
                : player.isFaceTinted();
        if (!tinted) {
            // Nothing to undo. Refuse WITHOUT consuming the prism -- this is the path a player
            // takes by pressing Reset then Confirm on an already-vanilla colour, and burning
            // their item for a no-op would be a trap.
            fail(player, ColorPrismPackets.RESULT_NOT_TINTED,
                    kind == ColorPrismPackets.TINT_KEY_HAIR
                            ? "Your hair is already its original color."
                            : kind == ColorPrismPackets.TINT_KEY_SKIN
                            ? "Your skin is already its original color."
                            : "Your eyes are already their original color.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }
        if (kind == ColorPrismPackets.TINT_KEY_HAIR) {
            player.clearHairTint();
        } else if (kind == ColorPrismPackets.TINT_KEY_SKIN) {
            player.clearSkinTint();
        } else {
            player.clearFaceTint();
        }
        InventoryManipulator.removeFromSlot(c, InventoryType.CASH, slot, (short) 1, false);
        player.saveCharToDB();
        succeed(player);
    }

    private record Target(byte invType, short invPos, int itemId) {
    }

    private static Target readTarget(InPacket p) {
        return new Target(p.readByte(), p.readShort(), p.readInt());
    }

    private void handleApply(Client c, Character player, Target target, int hue, int chroma, int bright,
                             short prismPos, int layer) {
        // A cash EFFECT item is not an equip, so it takes the short path: one colour, its own
        // three columns, and no body-versus-effect distinction to make.
        Item cashEffect = resolveCashEffect(player, target);
        if (cashEffect != null) {
            short effSlot = findItem(player, ItemId.COLORING_PRISM, prismPos);
            if (effSlot == 0) {
                fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
                return;
            }
            cashEffect.setEffTint(hue, chroma, bright);
            InventoryManipulator.removeFromSlot(c, InventoryType.CASH, effSlot, (short) 1, false);
            player.saveCharToDB();
            succeed(player);
            return;
        }
        Equip equip = resolve(player, target);
        if (equip == null) {
            fail(player, ColorPrismPackets.RESULT_NO_CASH_WEAPON,
                    "That item can't be dyed with a Coloring Prism.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }

        // The window's Equip and Effects tabs dye the same item under different keys.
        if (layer == ColorPrismPackets.LAYER_EFFECTS) {
            equip.setFxTint(hue, chroma, bright);
        } else {
            equip.setTint(hue, chroma, bright);
        }
        InventoryManipulator.removeFromSlot(c, InventoryType.CASH, slot, (short) 1, false);
        // The equip object is the live one in the player's inventory, so the tint is persisted by
        // the normal character save. Force one now so a crash between here and logout cannot lose a
        // consumed prism's worth of colour.
        player.saveCharToDB();
        succeed(player);
    }

    private void handleRestore(Client c, Character player, Target target, short prismPos, int layer) {
        Item cashEffect = resolveCashEffect(player, target);
        if (cashEffect != null) {
            if (!cashEffect.isEffTinted()) {
                // Nothing to undo. Refuse WITHOUT consuming the prism, the same as every other
                // restore path: this is what Reset then Confirm on an undyed item hits.
                fail(player, ColorPrismPackets.RESULT_NOT_TINTED,
                        "That item is already its original color.");
                return;
            }
            short effSlot = findItem(player, ItemId.COLORING_PRISM, prismPos);
            if (effSlot == 0) {
                fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
                return;
            }
            cashEffect.clearEffTint();
            InventoryManipulator.removeFromSlot(c, InventoryType.CASH, effSlot, (short) 1, false);
            player.saveCharToDB();
            succeed(player);
            return;
        }
        Equip equip = resolve(player, target);
        if (equip == null) {
            fail(player, ColorPrismPackets.RESULT_NO_CASH_WEAPON,
                    "That item can't be dyed with a Coloring Prism.");
            return;
        }
        final boolean fx = layer == ColorPrismPackets.LAYER_EFFECTS;
        if (fx ? !equip.isFxTinted() : !equip.isTinted()) {
            // Nothing to undo: report it and leave the item alone rather than burning it for no effect.
            fail(player, ColorPrismPackets.RESULT_NOT_TINTED, "That item is already its original color.");
            return;
        }
        short slot = findItem(player, ItemId.COLORING_PRISM, prismPos);
        if (slot == 0) {
            fail(player, ColorPrismPackets.RESULT_NO_ITEM, "You don't have a Coloring Prism.");
            return;
        }

        if (fx) {
            equip.clearFxTint();
        } else {
            equip.clearTint();
        }
        InventoryManipulator.removeFromSlot(c, InventoryType.CASH, slot, (short) 1, false);
        player.saveCharToDB();
        succeed(player);
    }

    /**
     * Rejects, WITHOUT consuming a prism, colour values outside the two legal hue bands or the
     * -100..100 chroma/brightness range, and an all-zero apply. Hue on the wire is
     * {@code -(degrees + 1)} for the window's absolute 0..359 Tone ({@code -1..-360}); positive
     * {@code 1..359} is a rotation. {@code 0/0/0} is what Reset sends, and Reset goes out as a
     * RESTORE; reaching an apply path with it would burn a prism to change nothing.
     */
    private boolean checkTint(Character player, int hue, int chroma, int bright) {
        if (!TintValues.isValid(hue, chroma, bright)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "Those color values are invalid.");
            return false;
        }
        if (TintValues.isIdentity(hue, chroma, bright)) {
            fail(player, ColorPrismPackets.RESULT_FAILED, "Move a slider first: those values change nothing.");
            return false;
        }
        return true;
    }

    /** True for the 5010000..5019999 group: cash EFFECT items, which are not equips. */
    private static boolean isCashEffect(int itemId) {
        return itemId >= 5010000 && itemId <= 5019999;
    }

    /**
     * The cash EFFECT item at {@code target}, or null. Separate from {@link #resolve} because
     * these are plain {@link Item}s in the CASH tab with no {@code inventoryequipment} row, so
     * they carry their colour in their own three columns instead of the equip six.
     */
    private static Item resolveCashEffect(Character player, Target target) {
        if (!isCashEffect(target.itemId())) {
            return null;
        }
        InventoryType type = InventoryType.getByType(target.invType());
        if (type == null) {
            return null;
        }
        Inventory inv = player.getInventory(type);
        inv.lockInventory();
        try {
            Item item = inv.getItem(target.invPos());
            if (item == null || item instanceof Equip || item.getItemId() != target.itemId()) {
                return null;
            }
            return item;
        } finally {
            inv.unlockInventory();
        }
    }

    /**
     * The Cash equip the client is pointing at, or null if it is not one, is not there, or is not
     * what the client said it was. The position is only a hint - an inventory move between the drop
     * and the Confirm would otherwise dye whatever landed in that slot instead.
     * <p>
     * <b>The SIGN of the position picks the inventory, not the reported type.</b> The v83 client
     * calls an equip's inventory EQUIP(1) whether it is worn or sitting in the tab, and distinguishes
     * the two by addressing a worn item at {@code -bodypart} and a worn CASH item at
     * {@code -(100 + bodypart)}. Server-side those both live in EQUIPPED(-1), which spans -1..-128
     * ({@link client.inventory.manipulator.InventoryManipulator} line 467). Trusting the client's
     * type byte for a negative position looks in EQUIP, finds nothing, and rejects every worn item -
     * which is every item a player actually wants to dye.
     */
    private static Equip resolve(Character player, Target target) {
        InventoryType type = target.invPos() < 0
                ? InventoryType.EQUIPPED
                : InventoryType.getByType(target.invType());
        if (type == null) {
            return null;
        }
        Inventory inv = player.getInventory(type);
        inv.lockInventory();
        try {
            Item item = inv.getItem(target.invPos());
            if (!(item instanceof Equip equip)) {
                return null;
            }
            if (equip.getItemId() != target.itemId()) {
                return null;
            }
            // No `isCash` gate. Ordinary equips dye exactly like Cash ones, and the client's
            // drop gate was opened in step with this: if only one side is relaxed, a drop is
            // accepted on the well and then refused a round trip later, which reads as the
            // window being broken rather than as a rule.

            return equip;
        } finally {
            inv.unlockInventory();
        }
    }

    private void succeed(Character player) {
        player.sendPacket(ColorPrismPackets.result(ColorPrismPackets.RESULT_OK));
        player.sendPacket(ColorPrismPackets.snapshot(player));
        ColorPrismPackets.broadcastMapTable(player.getMap());
    }

    /**
     * The result code is what the DLL reads; the chat line is what the player reads. Both are sent
     * because the prism window closes the moment Confirm is pressed, so a code alone would be a
     * silent no-op. The snapshot that follows undoes the client's optimistic recolour (see
     * {@code WeaponTint_AdoptOptimistic}).
     */
    private void fail(Character player, byte code, String message) {
        player.sendPacket(ColorPrismPackets.result(code));
        player.sendPacket(ColorPrismPackets.snapshot(player));
        player.dropMessage(1, message);
    }

    /**
     * The CASH-inventory slot holding {@code itemId}, preferring {@code hintPos} when it still holds
     * that item. Returns 0 when the player does not own one - 0 is not a valid inventory position,
     * so it doubles as "none".
     */
    private static short findItem(Character player, int itemId, short hintPos) {
        var cash = player.getInventory(InventoryType.CASH);
        cash.lockInventory();
        try {
            if (hintPos > 0) {
                Item at = cash.getItem(hintPos);
                if (at != null && at.getItemId() == itemId) {
                    return hintPos;
                }
            }
            for (Item item : cash.list()) {
                if (item.getItemId() == itemId) {
                    return item.getPosition();
                }
            }
        } finally {
            cash.unlockInventory();
        }
        return 0;
    }
}
