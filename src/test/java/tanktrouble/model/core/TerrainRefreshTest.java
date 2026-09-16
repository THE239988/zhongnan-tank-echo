package tanktrouble.model.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.*;
import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.model.core.ModelTest.*;

class TerrainRefreshTest {
    private static TankGameModel terrainGame(Mode mode,long seed) {
        TankGameModel game=new TankGameModel();game.selectMode(mode);game.startBattle(seed);
        // Keep combat alive while advancing the real fixed-step simulation.
        for(Tank tank:tanks(game)) {tank.fireMs=1_000_000;tank.shieldMs=1_000_000;}
        return game;
    }
    private static void advance(TankGameModel game,int ms) {
        for(int elapsed=0;elapsed<ms;elapsed+=8) game.tick(8);
    }
    private static void assertRelocated(List<Zone> before,List<Zone> after) {
        assertEquals(3,after.size());
        assertEquals(EnumSet.allOf(Terrain.class),EnumSet.copyOf(after.stream().map(Zone::terrain).toList()));
        for(Zone zone:after) {
            assertTrue(before.stream().noneMatch(old->old.bounds().intersects(zone.bounds())),
                    "Every terrain must move away from the previous layout: "+zone.terrain());
        }
    }
    private static void assertSafe(Snapshot snapshot) {
        assertEquals(3,snapshot.zones().size());
        for(Zone zone:snapshot.zones()) {
            Rect bounds=zone.bounds();
            assertTrue(bounds.x()>=0 && bounds.y()>=0);
            assertTrue(bounds.x()+bounds.width()<=Rules.WIDTH && bounds.y()+bounds.height()<=Rules.HEIGHT);
            assertTrue(snapshot.walls().stream().noneMatch(bounds::intersects));
            assertTrue(snapshot.zones().stream().filter(other->other!=zone).noneMatch(other->bounds.intersects(other.bounds())));
            assertTrue(snapshot.tanks().stream().filter(t->t.hp()>0).noneMatch(t->bounds.intersects(Rect.centered(t.x(),t.y(),Rules.TANK_HALF))));
            assertTrue(snapshot.portals().stream().noneMatch(p->bounds.intersects(Rect.centered(p.centerX(),p.centerY(),Rules.PORTAL_HALF))));
            assertTrue(snapshot.pickups().stream().noneMatch(p->bounds.intersects(Rect.centered(p.x(),p.y(),11))));
            assertTrue(snapshot.turrets().stream().noneMatch(t->bounds.intersects(Rect.centered(t.x(),t.y(),Rules.TURRET_HALF))));
        }
    }
    @Test void allThreeRelocateExactlyEveryTenSecondsWithoutChangingTheMap() {
        TankGameModel game=terrainGame(Mode.DUEL,42);Snapshot initial=game.snapshot();
        List<Zone> initialZones=new ArrayList<>(initial.zones());assertSafe(initial);
        advance(game,9992);assertEquals(initial.zones(),game.snapshot().zones());
        game.tick(8);Snapshot first=game.snapshot();
        assertEquals(10000,first.elapsedMs());assertRelocated(initial.zones(),first.zones());assertSafe(first);
        assertEquals(initial.walls(),first.walls());assertEquals(initial.portals(),first.portals());
        assertEquals(initial.pickups(),first.pickups());assertEquals(State.RUNNING,first.state());
        advance(game,9992);assertEquals(first.zones(),game.snapshot().zones());
        game.tick(8);assertRelocated(first.zones(),game.snapshot().zones());assertSafe(game.snapshot());
        assertEquals(20000,game.snapshot().elapsedMs());
        assertEquals(initialZones,initial.zones(),"Previously published snapshots remain unchanged");
        assertThrows(UnsupportedOperationException.class,()->first.zones().clear());
    }
    @Test void pauseShopAndDeploymentFreezeTheRemainingRefreshTime() {
        TankGameModel game=terrainGame(Mode.SINGLE,42);
        advance(game,9992);List<Zone> initial=game.snapshot().zones();
        game.pause();advance(game,20000);assertEquals(initial,game.snapshot().zones());
        game.openShop();advance(game,20000);assertEquals(initial,game.snapshot().zones());
        tanks(game).get(0).turretStock=1;
        assertTrue(game.beginDeployment());advance(game,20000);assertEquals(initial,game.snapshot().zones());
        assertEquals(9992,game.snapshot().elapsedMs());
        game.cancelDeployment();assertEquals(State.SHOP,game.snapshot().state());
        game.resume();game.tick(8);assertRelocated(initial,game.snapshot().zones());
    }
    @Test void startingAnotherBattleResetsTheRefreshTimer() {
        TankGameModel game=terrainGame(Mode.DUEL,42);advance(game,7000);
        game.home();game.selectMode(Mode.DUEL);game.startBattle(42);
        List<Zone> initial=game.snapshot().zones();
        advance(game,9992);assertEquals(initial,game.snapshot().zones());
        game.tick(8);assertRelocated(initial,game.snapshot().zones());
    }
    @Test void eachEndlessWaveStartsWithAFreshTenSecondInterval() {
        TankGameModel game=terrainGame(Mode.ENDLESS,8);advance(game,800);
        for(Tank tank:tanks(game)) if(tank.player<0) tank.hp=0;
        advance(game,6000);assertEquals(State.UPGRADE,game.snapshot().state());
        long elapsed=game.snapshot().elapsedMs();List<Zone> old=game.snapshot().zones();
        advance(game,20000);assertEquals(elapsed,game.snapshot().elapsedMs());assertEquals(old,game.snapshot().zones());
        assertTrue(game.chooseUpgrade(Upgrade.REINFORCE));
        for(Tank tank:tanks(game)) {tank.fireMs=1_000_000;tank.shieldMs=1_000_000;}
        List<Zone> initial=game.snapshot().zones();
        advance(game,9992);assertEquals(initial,game.snapshot().zones());
        game.tick(8);assertRelocated(initial,game.snapshot().zones());assertEquals(2,game.snapshot().wave());
    }
    @Test void disabledTerrainStaysAbsentAndResultsFreezeTerrain() {
        TankGameModel disabled=game(Mode.DUEL);advance(disabled,30000);assertTrue(disabled.snapshot().zones().isEmpty());
        TankGameModel game=terrainGame(Mode.DUEL,42);advance(game,9992);
        tanks(game).get(0).hp=0;game.tick(8);assertEquals(State.RESULT,game.snapshot().state());
        Snapshot result=game.snapshot();advance(game,30000);
        assertEquals(result.zones(),game.snapshot().zones());assertEquals(result.elapsedMs(),game.snapshot().elapsedMs());
    }
    @Test void sameSeedReproducesInitialAndRefreshedTerrainAcrossMultipleCycles() {
        Set<List<Zone>> layouts=new HashSet<>();
        for(int seed=0;seed<20;seed++) {
            TankGameModel first=terrainGame(Mode.DUEL,seed),second=terrainGame(Mode.DUEL,seed);
            layouts.add(first.snapshot().zones());
            for(int cycle=0;cycle<3;cycle++) {
                assertSafe(first.snapshot());assertEquals(first.snapshot().zones(),second.snapshot().zones());
                List<Zone> old=first.snapshot().zones();advance(first,10000);advance(second,10000);
                assertRelocated(old,first.snapshot().zones());
            }
            assertSafe(first.snapshot());assertEquals(first.snapshot().zones(),second.snapshot().zones());
        }
        assertTrue(layouts.size()>1,"Different battle seeds must produce different terrain layouts");
    }
    @Test void refreshAvoidsTanksAndTurretsStraddlingCellBoundaries() {
        for(int seed=0;seed<12;seed++) {
            TankGameModel game=terrainGame(Mode.DUEL,seed);advance(game,9992);
            Tank player=tanks(game).get(0);player.x=160;player.y=160;
            Turret turret=new Turret(player.id,400,320);turret.fireMs=100_000;
            ModelTest.<List<Turret>>field(game,"turrets").add(turret);
            game.tick(8);assertSafe(game.snapshot());
        }
    }
    @Test void retiringLavaClearsHeatBeforeApplyingDamageOnTheRefreshTick() {
        TankGameModel game=terrainGame(Mode.DUEL,42);advance(game,9992);
        Zone lava=game.snapshot().zones().stream().filter(z->z.terrain()==Terrain.LAVA).findFirst().orElseThrow();
        Tank player=tanks(game).get(0);player.x=lava.bounds().x()+32;player.y=lava.bounds().y()+32;
        player.shieldMs=0;player.heatMs=992;int hp=player.hp;
        game.tick(8);assertEquals(hp,player.hp,"Expired lava must stop damaging immediately");assertEquals(0,player.heatMs);
    }
    @Test void insufficientSpaceKeepsThreeZonesAndRetriesAtTheNextInterval() {
        TankGameModel game=terrainGame(Mode.DUEL,42);List<Zone> initial=game.snapshot().zones();
        List<PickupView> pickups=field(game,"pickups");
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++)
            pickups.add(new PickupView((x+.5)*Rules.CELL,(y+.5)*Rules.CELL,Item.REPAIR));
        advance(game,10000);assertEquals(State.RUNNING,game.snapshot().state());assertEquals(initial,game.snapshot().zones());
        pickups.clear();advance(game,9992);assertEquals(initial,game.snapshot().zones());
        game.tick(8);assertRelocated(initial,game.snapshot().zones());
    }
}
