package client.command.commands.gm0;

import client.inventory.InventoryType;

public class CashBagCommand extends BagCommand {
    public CashBagCommand() {
        super(3, "cash", "cash items", InventoryType.CASH, InventoryType.EQUIP);
    }
}
