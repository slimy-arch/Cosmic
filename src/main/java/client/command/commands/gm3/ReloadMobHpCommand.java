package client.command.commands.gm3;

import client.Character;
import client.Client;
import client.command.Command;
import server.life.LifeFactory;
import server.life.MobHpOverrides;

public class ReloadMobHpCommand extends Command {
    {
        setDescription("Reload mob-hp-overrides.yaml (applies to mobs spawned afterwards).");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        int count = MobHpOverrides.reload();
        if (count < 0) {
            player.dropMessage(5, "Could not read " + MobHpOverrides.FILE_NAME + "; previous overrides kept. See the server log.");
            return;
        }
        LifeFactory.clearCachedMonsterStats();
        player.dropMessage(5, "Reloaded " + count + " mob HP override(s).");
    }
}
