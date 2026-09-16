package tanktrouble.model.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.TestMaps;

import static org.junit.jupiter.api.Assertions.*;

class MultiplayerModeTest {
    private static TankGameModel game(Mode mode,int players) {
        TankGameModel game=new TankGameModel();
        game.selectMode(mode);
        game.setTerrainEnabled(false);
        game.startBattle(42,players);
        ModelTest.set(game,"map",TestMaps.open());
        ModelTest.<List<Cell>>field(game,"portals").clear();
        return game;
    }

    @Test void freeForAllSpawnsOnlyRequestedHumanPlayersOnSeparateTeams() {
        TankGameModel game=game(Mode.MULTI,5);
        List<Tank> tanks=ModelTest.tanks(game);

        assertEquals(5,tanks.size());
        assertTrue(tanks.stream().allMatch(tank->tank.player>=0));
        assertEquals(5,tanks.stream().map(tank->tank.team).distinct().count());
    }

    @Test void freeForAllPlayersCanDamageEachOtherAndLastSurvivorWins() {
        TankGameModel game=game(Mode.MULTI,3);
        List<Tank> tanks=ModelTest.tanks(game);
        Tank attacker=tanks.get(0),target=tanks.get(1);
        attacker.x=100;attacker.y=100;target.x=160;target.y=100;
        int before=target.hp;
        ModelTest.<List<Bullet>>field(game,"bullets").add(new Bullet(attacker.id,139,100,0));

        game.tick(8);

        assertEquals(before-1,target.hp);
        target.hp=0;tanks.get(2).hp=0;
        game.tick(8);
        assertEquals(State.RESULT,game.snapshot().state());
        assertEquals(Result.WIN,game.snapshot().result());
    }

    @Test void coopSpawnsOneScaledBossAndPlayersShareATeam() {
        TankGameModel game=game(Mode.COOP,3);
        List<Tank> tanks=ModelTest.tanks(game);
        List<Tank> players=tanks.stream().filter(tank->tank.player>=0).toList();
        Tank boss=tanks.stream().filter(tank->tank.boss).findFirst().orElseThrow();

        assertEquals(3,players.size());
        assertEquals(1,players.stream().map(tank->tank.team).distinct().count());
        assertEquals(70,boss.maxHp);
        assertEquals(70,boss.hp);
    }

    @Test void coopPlayersCannotDamageEachOther() {
        TankGameModel game=game(Mode.COOP,2);
        List<Tank> tanks=ModelTest.tanks(game);
        Tank attacker=tanks.get(0),teammate=tanks.get(1);
        attacker.x=100;attacker.y=100;teammate.x=160;teammate.y=100;
        int before=teammate.hp;
        ModelTest.<List<Bullet>>field(game,"bullets").add(new Bullet(attacker.id,139,100,0));

        game.tick(8);

        assertEquals(before,teammate.hp);
    }

    @Test void coopVictoryRequiresBossDeathAndDefeatRequiresAllPlayersDead() {
        TankGameModel victory=game(Mode.COOP,2);
        ModelTest.tanks(victory).stream().filter(tank->tank.boss).forEach(tank->tank.hp=0);
        victory.tick(8);
        assertEquals(Result.VICTORY,victory.snapshot().result());

        TankGameModel defeat=game(Mode.COOP,2);
        ModelTest.tanks(defeat).stream().filter(tank->tank.player>=0).forEach(tank->tank.hp=0);
        defeat.tick(8);
        assertEquals(Result.DEFEAT,defeat.snapshot().result());
    }

    @Test void explicitPlayerSlotsAndDisconnectsAreReflectedInTheWorld() {
        TankGameModel game=new TankGameModel();game.selectMode(Mode.MULTI);game.setTerrainEnabled(false);
        game.startBattle(42,List.of(0,2));
        assertEquals(List.of(0,2),game.snapshot().tanks().stream().map(TankView::player).sorted().toList());

        game.disconnectPlayer(2);game.tick(8);

        assertEquals(Result.WIN,game.snapshot().result());
        assertTrue(game.drainEvents().stream().anyMatch(event->event.type()==GameEventType.EXPLOSION));
    }
}
