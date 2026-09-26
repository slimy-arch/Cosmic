package client.command.commands.gm0;

import client.inventory.InventoryType;

public class ChairBagCommand extends BagCommand {
    public ChairBagCommand() {
        super(2, "chair", "chairs", InventoryType.SETUP);
    }
}
