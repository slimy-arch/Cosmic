package client.command.commands.gm0;

import client.Character;
import client.Client;
import client.command.Command;
import client.inventory.InventoryType;
import client.inventory.Item;
import constants.inventory.ItemConstants;
import tools.PacketCreator;

import java.util.ArrayList;

/**
 * kaentake Storage Bag commands: {@code @<bag>} opens the window on that tab, {@code @<bag> store} sweeps every
 * matching inventory stack into the bag, {@code @<bag> on|off} toggles pickup auto-collect.
 */
public abstract class BagCommand extends Command {
    private final int kind;
    private final String name;
    private final String contents;
    private final InventoryType[] sources;

    protected BagCommand(int kind, String name, String contents, InventoryType... sources) {
        this.kind = kind;
        this.name = name;
        this.contents = contents;
        this.sources = sources;
        setDescription("Manages your " + name + " bag. Usage: @" + name + "bag [store|on|off] (no argument opens it)");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        if (player.getBag(kind) == null) {
            return;
        }
        String arg = params.length < 1 ? "open" : params[0];
        switch (arg) {
            case "open" -> {
                player.openBag(kind);
                c.sendPacket(PacketCreator.enableActions());
            }
            case "store" -> {
                int moved = 0, refused = 0;
                for (InventoryType type : sources) {
                    for (Item item : new ArrayList<>(player.getInventory(type).list())) {
                        if (!ItemConstants.isBagAllowed(kind, item.getItemId())) {
                            continue;
                        }
                        if (player.moveToBag(kind, type, item.getPosition())) {
                            moved++;
                        } else {
                            refused++;
                        }
                    }
                }
                player.yellowMessage("Moved " + moved + " stack(s) of " + contents + " to your " + name + " bag."
                        + (refused > 0 ? " " + refused + " did not fit or cannot be stored." : ""));
            }
            case "on" -> {
                player.setAutoBag(kind, true);
                player.refreshBagIfActive(kind);   // the window's AUTO button follows
                player.yellowMessage(name.substring(0, 1).toUpperCase() + name.substring(1) + " bag auto-collect ON: " + contents + " go straight to the bag.");
            }
            case "off" -> {
                player.setAutoBag(kind, false);
                player.refreshBagIfActive(kind);
                player.yellowMessage(name.substring(0, 1).toUpperCase() + name.substring(1) + " bag auto-collect OFF.");
            }
            default -> player.yellowMessage("Syntax: @" + name + "bag [store | on | off] (no argument opens the bag)");
        }
    }
}
