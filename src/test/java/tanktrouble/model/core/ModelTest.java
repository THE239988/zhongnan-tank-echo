package tanktrouble.model.core;

import java.lang.reflect.Field;
import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.TestMaps;
import static org.junit.jupiter.api.Assertions.*;

class ModelTest {
    static TankGameModel game(Mode mode) {
        TankGameModel game=new TankGameModel();game.selectMode(mode);game.setTerrainEnabled(false);game.startBattle(42);return game;
    }
    @SuppressWarnings("unchecked") static <T> T field(Object object,String name) {
        try {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(object);}
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    static void set(Object object,String name,Object value) {
        try {Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
        catch(ReflectiveOperationException e) {throw new AssertionError(e);}
    }
    static List<Tank> tanks(TankGameModel game) {return field(game,"tanks");}
    static void quiet(TankGameModel game) {
        set(game,"map",TestMaps.open());
        for(Tank tank:tanks(game)) {tank.fireMs=1_000_000;tank.x=700;tank.y=500;}
        Tank player=tanks(game).get(0);player.x=100;player.y=100;
        ModelTest.<List<Cell>>field(game,"portals").clear();
    }
    @Test void stateGuardsAndFixedStepContract() {
        TankGameModel game=new TankGameModel();game.startBattle(1);assertEquals(State.HOME,game.snapshot().state());
        game.selectMode(Mode.SINGLE);game.startBattle(1);
        assertThrows(IllegalArgumentException.class,()->game.tick(16));
        assertEquals(4,game.snapshot().tanks().size());game.selectMode(Mode.DUEL);assertEquals(Mode.SINGLE,game.snapshot().mode());
        game.restart();assertEquals(State.RUNNING,game.snapshot().state());
    }
    @Test void snapshotsDoNotExposeLiveEntitiesOrCollections() {
        TankGameModel game=game(Mode.SINGLE);Snapshot old=game.snapshot();
        assertThrows(UnsupportedOperationException.class,()->old.tanks().clear());
        tanks(game).get(0).hp=1;assertEquals(10,old.tanks().get(0).hp());
    }
    @Test void pauseFreezesSimulationAndClearsHeldMovement() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);Tank player=tanks(game).get(0);
        game.handleInput(0,Action.FORWARD,true);game.pause();game.tick(8);
        assertEquals(0,game.snapshot().elapsedMs());game.resume();game.tick(8);assertEquals(100,player.x);
    }
    @Test void coinCanOnlyBeCollectedOnceAndOnlyByLivingPlayer() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);
        ModelTest.<List<CoinView>>field(game,"coins").add(new CoinView(100,100));
        game.tick(8);game.tick(8);assertEquals(1,game.snapshot().coins());assertEquals(10,game.snapshot().score());
    }
    @Test void shopChargesOnlySuccessfulPurchaseAndDoesNotChangeScore() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);set(game,"wallet",5);game.openShop();
        assertFalse(game.purchase(Item.REPAIR));assertEquals(5,game.snapshot().coins());
        tanks(game).get(0).hp=5;assertTrue(game.purchase(Item.REPAIR));assertEquals(8,tanks(game).get(0).hp);
        assertEquals(3,game.snapshot().coins());assertTrue(game.purchase(Item.SHIELD));assertFalse(game.purchase(Item.RAPID));
        long shield=tanks(game).get(0).shieldMs;game.tick(8);assertEquals(shield,tanks(game).get(0).shieldMs);
        assertEquals(0,game.snapshot().score());game.resume();assertFalse(game.purchase(Item.REPAIR));
    }
    @Test void timedPickupExpiresDuringRunning() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);
        ModelTest.<List<PickupView>>field(game,"pickups").add(new PickupView(100,100,Item.SHIELD));
        game.tick(8);assertEquals(9000,tanks(game).get(0).shieldMs);
        tanks(game).get(0).shieldMs=8;game.tick(8);assertEquals(0,tanks(game).get(0).shieldMs);
    }

    @Test void scatterPickupSpawnsFourShotsAcrossOneHundredTwentyDegrees() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);Tank player=tanks(game).get(0);player.angle=0;
        ModelTest.<List<PickupView>>field(game,"pickups").add(new PickupView(player.x,player.y,Item.SCATTER));
        game.tick(8);player.fireMs=0;game.handleInput(0,Action.FIRE,true);game.tick(8);
        List<Bullet> bullets=field(game,"bullets");assertEquals(4,bullets.size());
        double min=bullets.stream().mapToDouble(b->Math.toDegrees(Math.atan2(b.vy,b.vx))).min().orElse(0);
        double max=bullets.stream().mapToDouble(b->Math.toDegrees(Math.atan2(b.vy,b.vx))).max().orElse(0);
        assertEquals(120,max-min,1e-6);assertTrue(player.scatterMs>0);
    }
    @Test void portalCooldownPreventsInstantReturnAndBlockedExitIsRejected() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);
        List<Cell> portals=field(game,"portals");portals.addAll(List.of(new Cell(1,1),new Cell(3,3)));
        Tank player=tanks(game).get(0);player.x=120;player.y=120;
        game.tick(8);assertEquals(280,player.x);assertEquals(1500,player.portalMs);
        game.tick(8);assertEquals(280,player.x);assertEquals(1492,player.portalMs);
        player.portalMs=0;player.x=120;player.y=120;
        Tank enemy=tanks(game).get(1);enemy.x=280;enemy.y=280;
        game.tick(8);assertEquals(120,player.x);
    }
    @Test void playerDeathHasPriorityOverFinalEnemyDeath() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);for(Tank tank:tanks(game)) tank.hp=0;
        game.tick(8);assertEquals(Result.DEFEAT,game.snapshot().result());
    }
    @Test void singleVictoryDoesNotAutomaticallyCollectLastCoin() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);
        for(Tank tank:tanks(game)) if(tank.player<0) tank.hp=0;
        ModelTest.<List<CoinView>>field(game,"coins").add(new CoinView(600,500));
        game.tick(8);assertEquals(State.RUNNING,game.snapshot().state());
        Tank boss=tanks(game).stream().filter(t->t.player<0 && t.alive()).findFirst().orElseThrow();boss.hp=0;
        game.tick(8);assertEquals(Result.VICTORY,game.snapshot().result());assertEquals(0,game.snapshot().score());
        game.restart();game.startBattle(1);assertEquals(0,game.snapshot().coins());assertEquals(0,game.snapshot().elapsedMs());
    }

    @Test void singleModeSpawnsHeavyBossAfterAllEnemyKills() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);
        for(Tank tank:tanks(game)) if(tank.player<0) tank.hp=0;
        game.tick(8);
        Tank boss=tanks(game).stream().filter(t->t.player<0 && t.alive()).findFirst().orElseThrow();
        assertEquals(TankType.HEAVY,boss.type);
        assertTrue(boss.maxHp>=12);
        assertTrue(game.drainEvents().stream().anyMatch(e->e.type()==GameEventType.BOSS_SPAWN));
    }

    @Test void bossPeriodicallyEmitsRadialShells() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);
        for(Tank tank:tanks(game)) if(tank.player<0) tank.hp=0;
        game.tick(8);
        Tank boss=tanks(game).stream().filter(t->t.player<0 && t.alive()).findFirst().orElseThrow();
        boss.skillMs=1;ModelTest.<List<Bullet>>field(game,"bullets").clear();game.drainEvents();game.tick(8);
        assertTrue(ModelTest.<List<Bullet>>field(game,"bullets").size()>=6);
        assertTrue(game.drainEvents().stream().anyMatch(e->e.type()==GameEventType.BOSS_SKILL));
    }
    @Test void deathDropsCoinInSameTickAndCannotDropTwice() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);Tank enemy=tanks(game).get(1);enemy.x=300;enemy.y=100;
        List<Bullet> bullets=field(game,"bullets");bullets.add(new Bullet(0,279,100,0));bullets.add(new Bullet(0,279,100,0));
        game.tick(8);assertEquals(0,enemy.hp);assertEquals(1,game.snapshot().drops().size());assertEquals(1,game.snapshot().kills());
    }
    @Test void endlessIntermissionOffersUpgradeAndPreservesWalletAndHealth() {
        TankGameModel game=game(Mode.ENDLESS);quiet(game);set(game,"wallet",4);
        for(Tank tank:tanks(game)) if(tank.player<0) tank.hp=0;
        for(int i=0;i<630;i++) game.tick(8);
        assertEquals(State.UPGRADE,game.snapshot().state());assertEquals(3,game.snapshot().upgrades().size());
        assertTrue(game.chooseUpgrade(Upgrade.REINFORCE));assertEquals(2,game.snapshot().wave());
        assertEquals(12,tanks(game).get(0).maxHp);assertEquals(4,game.snapshot().coins());
        assertEquals(4,game.snapshot().tanks().stream().filter(t->t.player()<0).count());
        assertFalse(game.chooseUpgrade(Upgrade.OVERCLOCK));
    }
    @Test void pulseRequiresEnergyClearsBulletsAndDamagesVisibleEnemy() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);Tank player=tanks(game).get(0),enemy=tanks(game).get(1);
        enemy.x=200;enemy.y=100;List<Bullet> bullets=field(game,"bullets");bullets.add(new Bullet(1,140,140,0));
        game.handleInput(0,Action.PULSE,true);game.tick(8);assertEquals(1,enemy.hp);
        player.energy=100;game.handleInput(0,Action.PULSE,true);game.tick(8);
        assertEquals(0,enemy.hp);assertEquals(0,player.energy);assertTrue(bullets.isEmpty());assertTrue(player.shieldMs>0);
    }
    @Test void duelHasTwoHumanPlayersAndIndependentInputs() {
        TankGameModel game=game(Mode.DUEL);quiet(game);
        assertEquals(2,game.snapshot().tanks().size());
        game.handleInput(1,Action.LEFT,true);game.tick(8);assertNotEquals(180,tanks(game).get(1).angle);
        assertEquals(0,tanks(game).get(0).angle);game.openShop();assertEquals(State.RUNNING,game.snapshot().state());
        tanks(game).get(0).hp=0;game.tick(8);assertEquals(Result.P2_WIN,game.snapshot().result());
    }
    @Test void shieldBlocksPulseAndLavaDamage() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);Tank player=tanks(game).get(0),enemy=tanks(game).get(1);
        enemy.x=200;enemy.y=100;enemy.shieldMs=9000;player.energy=100;
        game.handleInput(0,Action.PULSE,true);game.tick(8);assertEquals(1,enemy.hp);
        player.shieldMs=9000;player.heatMs=992;
        ModelTest.<List<Zone>>field(game,"zones").add(new Zone(new Rect(80,80,60,60),Terrain.LAVA));
        game.tick(8);assertEquals(10,player.hp);
        player.shieldMs=0;player.heatMs=992;game.tick(8);assertEquals(9,player.hp);
    }
    @Test void turretPurchaseStoresInventoryUntilManualDeployment() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);set(game,"wallet",20);game.openShop();
        assertTrue(game.purchase(Item.TURRET));
        assertTrue(game.snapshot().turrets().isEmpty(),"Purchasing must not deploy a turret at the player's position");
        assertEquals(16,game.snapshot().coins());
    }
    @Test void sustainedSimulationNeverProducesInvalidEntityCoordinates() {
        for(int seed=0;seed<12;seed++) {
            TankGameModel game=new TankGameModel();game.selectMode(Mode.ENDLESS);game.startBattle(seed);
            for(int step=0;step<5000 && game.snapshot().state()==State.RUNNING;step++) {
                game.handleInput(0,Action.FORWARD,step%250<150);game.handleInput(0,Action.RIGHT,step%100<40);
                game.handleInput(0,Action.FIRE,true);game.tick(8);
                for(TankView tank:game.snapshot().tanks()) {assertTrue(Double.isFinite(tank.x()));assertTrue(tank.x()>=0 && tank.x()<=960);assertTrue(tank.y()>=0 && tank.y()<=720);}
            }
        }
    }
}
