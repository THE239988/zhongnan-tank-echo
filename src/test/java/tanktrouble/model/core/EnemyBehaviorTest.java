package tanktrouble.model.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.*;
import static org.junit.jupiter.api.Assertions.*;

class EnemyBehaviorTest {
    @Test void enemiesDoNotKillEachOtherWhilePlayerDoesNotFire() {
        int firingScenarios=0;
        for(int seed=0;seed<8;seed++) {
            TankGameModel game=new TankGameModel();game.selectMode(Mode.SINGLE);game.setTerrainEnabled(false);game.startBattle(seed);
            List<Tank> tanks=ModelTest.tanks(game);Tank player=tanks.get(0);player.shieldMs=1_000_000;
            GridMap map=ModelTest.field(game,"map");boolean fired=false;
            Map<Integer,Double> originalX=new HashMap<>(),originalY=new HashMap<>();
            for(Tank enemy:tanks) if(enemy.player<0) {originalX.put(enemy.id,enemy.x);originalY.put(enemy.id,enemy.y);}
            double furthestTravel=0;
            for(int step=0;step<7500;step++) {
                game.tick(8);
                assertEquals(State.RUNNING,game.snapshot().state(),"Idle protected player must not win from enemy friendly fire");
                fired|=!game.snapshot().bullets().isEmpty();
                assertEquals(0,game.snapshot().kills());assertTrue(game.snapshot().drops().isEmpty());
                for(Tank tank:tanks) {
                    assertTrue(tank.alive());assertFalse(map.blocked(tank.bounds()));
                    if(tank.player<0) furthestTravel=Math.max(furthestTravel,Math.hypot(tank.x-originalX.get(tank.id),tank.y-originalY.get(tank.id)));
                    for(Tank other:tanks) if(tank!=other) assertFalse(tank.bounds().intersects(other.bounds()));
                }
            }
            assertTrue(furthestTravel>160,"Enemy pursuit must make meaningful progress, seed "+seed);
            if(fired) firingScenarios++;
        }
        assertTrue(firingScenarios>=6,"Enemies must still reach firing opportunities across maps");
    }
    @Test void modelOnlyFiresAtClearPlayerAndStartsPostShotMovement() {
        TankGameModel game=ModelTest.game(Mode.SINGLE);ModelTest.quiet(game);
        List<Tank> tanks=ModelTest.tanks(game);Tank player=tanks.get(0),enemy=tanks.get(1);
        enemy.x=280;enemy.y=100;enemy.angle=180;enemy.fireMs=0;
        game.tick(8);
        assertTrue(game.snapshot().bullets().stream().anyMatch(b->b.owner()==enemy.id));
        assertTrue(enemy.fireMs>0);assertTrue(enemy.dodgeMs>0);
        double y=enemy.y;
        for(int i=0;i<50;i++) game.tick(8);
        assertTrue(Math.abs(enemy.y-y)>15);
    }
    @Test void enemyBlockedByWallDoesNotSpendFireCooldown() {
        TankGameModel game=ModelTest.game(Mode.SINGLE);ModelTest.quiet(game);
        List<Tank> tanks=ModelTest.tanks(game);Tank enemy=tanks.get(1);
        enemy.x=220;enemy.y=100;enemy.angle=180;enemy.fireMs=0;
        ModelTest.set(game,"map",TestMaps.withWall(new Rect(157,80,6,80)));
        game.tick(8);
        assertTrue(game.snapshot().bullets().stream().noneMatch(b->b.owner()==enemy.id));
        assertTrue(enemy.fireMs<=0);assertEquals(0,enemy.dodgeMs);
    }
}
