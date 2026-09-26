package client.command.commands.gm0;

import client.inventory.InventoryType;

public class ScrollBagCommand extends BagCommand {
    public ScrollBagCommand() {
        super(1, "scroll", "scrolls", InventoryType.USE);
    }
}
