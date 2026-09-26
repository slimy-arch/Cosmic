package client.command.commands.gm0;

import client.inventory.InventoryType;

public class OreBagCommand extends BagCommand {
    public OreBagCommand() {
        super(0, "ore", "maker materials", InventoryType.ETC);
    }
}
