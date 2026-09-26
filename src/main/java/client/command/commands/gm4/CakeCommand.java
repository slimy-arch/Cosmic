/*
    This file is part of the HeavenMS MapleStory Server, commands OdinMS-based
    Copyleft (L) 2016 - 2019 RonanLana

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU Affero General Public License as
    published by the Free Software Foundation version 3 as published by
    the Free Software Foundation. You may not use, modify or distribute
    this program under any other version of the GNU Affero General Public
    License.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU Affero General Public License for more details.

    You should have received a copy of the GNU Affero General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

/*
   @Author: Arthur L - Refactored command content into modules
*/
package client.command.commands.gm4;

import client.Character;
import client.Client;
import client.command.Command;
import constants.id.MobId;
import server.life.LifeFactory;
import server.life.Monster;

public class CakeCommand extends Command {
    {
        setDescription("Spawn Cake boss with specified HP.");
    }

    @Override
    public void execute(Client c, String[] params) {
        Character player = c.getPlayer();
        Monster monster = LifeFactory.getMonster(MobId.GIANT_CAKE);
        if (params.length == 1) {
            long newHp;
            try {
                newHp = Long.parseLong(params[0]);
            } catch (NumberFormatException e) {
                newHp = Math.round(Double.parseDouble(params[0])); // "5e9"; saturates at Long.MAX_VALUE
            }
            if (newHp <= 0) {
                newHp = Long.MAX_VALUE;
            }

            monster.setStartingHp(newHp);
        }

        player.getMap().spawnMonsterOnGroundBelow(monster, player.getPosition());
    }
}
