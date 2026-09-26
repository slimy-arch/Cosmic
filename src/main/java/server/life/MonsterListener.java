package server.life;

import client.Character;

public interface MonsterListener {

    void monsterKilled(int aniTime);
    void monsterDamaged(Character from, long trueDmg);
    void monsterHealed(long trueHeal);
}
