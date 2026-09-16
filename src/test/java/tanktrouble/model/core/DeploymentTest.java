package tanktrouble.model.core;

import java.util.List;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.entity.Entities.*;
import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.model.core.ModelTest.*;

class DeploymentTest {
    private static TankGameModel stockedGame() {
        TankGameModel game=game(Mode.SINGLE);quiet(game);set(game,"wallet",20);game.openShop();
        assertTrue(game.purchase(Item.TURRET));return game;
    }
    @Test void buyingAddsVisibleInventoryWithoutPlacingOrAdvancingTime() {
        TankGameModel game=stockedGame();
        assertEquals(1,game.snapshot().tanks().get(0).turretStock());
        assertTrue(game.snapshot().turrets().isEmpty());
        assertEquals(State.SHOP,game.snapshot().state());assertEquals(16,game.snapshot().coins());
        game.tick(8);assertEquals(0,game.snapshot().elapsedMs());
    }
    @Test void deploymentFreezesTheBattleAndClearsHeldInput() {
        TankGameModel game=stockedGame();game.resume();
        Tank player=tanks(game).get(0);game.handleInput(0,Action.FORWARD,true);
        assertTrue(game.beginDeployment());assertEquals(State.DEPLOYING,game.snapshot().state());
        game.tick(8);game.resume();game.openShop();game.pause();
        assertEquals(State.DEPLOYING,game.snapshot().state());assertEquals(0,game.snapshot().elapsedMs());
        game.cancelDeployment();assertEquals(State.RUNNING,game.snapshot().state());
        game.tick(8);assertEquals(100,player.x);
    }
    @Test void cancellingRestoresEachOriginWithoutSpendingInventoryOrCoins() {
        for(State origin:List.of(State.RUNNING,State.PAUSED,State.SHOP)) {
            TankGameModel game=stockedGame();
            if(origin!=State.SHOP) game.resume();
            if(origin==State.PAUSED) game.pause();
            assertTrue(game.beginDeployment());assertFalse(game.beginDeployment());
            game.cancelDeployment();game.cancelDeployment();
            assertEquals(origin,game.snapshot().state());
            assertEquals(1,game.snapshot().tanks().get(0).turretStock());assertEquals(16,game.snapshot().coins());
        }
    }
    @Test void validPlacementConsumesStockAndReturnsToPauseWithoutChargingAgain() {
        TankGameModel game=stockedGame();assertTrue(game.beginDeployment());
        assertTrue(game.canDeployTurret(900,600));assertTrue(game.deployTurret(900,600));
        Snapshot placed=game.snapshot();assertEquals(State.PAUSED,placed.state());
        assertEquals(0,placed.tanks().get(0).turretStock());assertEquals(16,placed.coins());
        TurretView turret=placed.turrets().get(0);assertEquals(900,turret.x());assertEquals(600,turret.y());
        assertEquals(placed.tanks().get(0).id(),turret.owner());assertEquals(12000,turret.remainingMs());
        game.tick(8);assertEquals(12000,game.snapshot().turrets().get(0).remainingMs());
        assertFalse(game.deployTurret(300,300));
    }
    @Test void invalidLocationsKeepThePendingPlacementAndInventory() {
        TankGameModel game=stockedGame();assertTrue(game.beginDeployment());
        ModelTest.<List<Cell>>field(game,"portals").add(new Cell(4,4));
        ModelTest.<List<Turret>>field(game,"turrets").add(new Turret(tanks(game).get(0).id,500,300));
        double[][] invalid={{Double.NaN,200},{200,Double.NaN},{Double.POSITIVE_INFINITY,200},
                {200,Double.NEGATIVE_INFINITY},{-1,200},{970,200},{200,-1},{200,730},
                {18,200},{942,200},{200,18},{200,702},{100,100},{700,500},{360,360},{500,300}};
        for(double[] point:invalid) {
            assertFalse(game.canDeployTurret(point[0],point[1]),"Unexpected valid point: "+List.of(point[0],point[1]));
            assertFalse(game.deployTurret(point[0],point[1]));
            assertEquals(State.DEPLOYING,game.snapshot().state());
            assertEquals(1,game.snapshot().tanks().get(0).turretStock());assertEquals(16,game.snapshot().coins());
        }
        assertTrue(game.canDeployTurret(400,200));
    }
    @Test void placementRejectsInteriorWallsButIgnoresDeadTanks() {
        TankGameModel game=stockedGame();
        set(game,"map",new tanktrouble.model.map.RandomMapGenerator().generate(42));
        assertTrue(game.beginDeployment());
        Rect wall=game.snapshot().walls().stream().filter(w->w.x()>40 && w.y()>40 && w.x()<900 && w.y()<650).findFirst().orElseThrow();
        assertFalse(game.deployTurret(wall.x()+wall.width()/2,wall.y()+wall.height()/2));
        set(game,"map",tanktrouble.model.map.TestMaps.open());
        tanks(game).get(1).hp=0;tanks(game).get(1).x=250;tanks(game).get(1).y=250;
        assertTrue(game.canDeployTurret(250,250));
    }
    @Test void deploymentRequiresStockAndALivingPlayerInAnAllowedState() {
        TankGameModel game=game(Mode.SINGLE);assertFalse(game.beginDeployment());
        assertFalse(game.canDeployTurret(300,300));assertFalse(game.deployTurret(300,300));
        TankGameModel stocked=stockedGame();tanks(stocked).get(0).hp=0;assertFalse(stocked.beginDeployment());
        for(State state:List.of(State.HOME,State.READY,State.UPGRADE,State.RESULT,State.EXITED)) {
            stocked=stockedGame();set(stocked,"state",state);assertFalse(stocked.beginDeployment());
        }
    }
    @Test void purchaseEligibilityMatchesChargeRulesAndIncludesStoredPlusActiveTurrets() {
        TankGameModel game=stockedGame();
        assertFalse(game.canPurchase(Item.REPAIR));assertFalse(game.purchase(Item.REPAIR));
        assertFalse(game.canPurchase(null));assertTrue(game.canPurchase(Item.TURRET));
        assertTrue(game.purchase(Item.TURRET));assertTrue(game.purchase(Item.TURRET));
        assertEquals(3,game.snapshot().tanks().get(0).turretStock());
        assertFalse(game.canPurchase(Item.TURRET));assertFalse(game.purchase(Item.TURRET));
        assertEquals(8,game.snapshot().coins());
        assertTrue(game.beginDeployment());assertTrue(game.deployTurret(300,300));game.openShop();
        assertFalse(game.canPurchase(Item.TURRET));assertFalse(game.purchase(Item.TURRET));
        assertEquals(8,game.snapshot().coins());assertEquals(2,game.snapshot().tanks().get(0).turretStock());
        set(game,"wallet",0);assertFalse(game.canPurchase(Item.SHIELD));assertFalse(game.purchase(Item.SHIELD));
        game.resume();set(game,"wallet",10);assertFalse(game.canPurchase(Item.SHIELD));
    }
    @Test void turretPickupBecomesInventoryAndRemainsOnGroundWhenCapacityIsFull() {
        TankGameModel game=stockedGame();assertTrue(game.purchase(Item.TURRET));assertTrue(game.purchase(Item.TURRET));
        List<PickupView> pickups=field(game,"pickups");pickups.clear();pickups.add(new PickupView(100,100,Item.TURRET));
        game.resume();game.tick(8);
        assertEquals(1,game.snapshot().pickups().size());assertEquals(3,game.snapshot().tanks().get(0).turretStock());
        assertTrue(game.beginDeployment());assertTrue(game.deployTurret(300,300));
        ModelTest.<List<Turret>>field(game,"turrets").get(0).lifeMs=8;
        game.resume();game.tick(8);
        assertTrue(game.snapshot().pickups().isEmpty());assertTrue(game.snapshot().turrets().isEmpty());
        assertEquals(3,game.snapshot().tanks().get(0).turretStock());
    }
    @Test void engineerDeploymentLastsEighteenSecondsAndExpiresOnlyWhileRunning() {
        TankGameModel game=new TankGameModel();game.selectMode(Mode.SINGLE);game.selectTank(0,TankType.ENGINEER);
        game.setTerrainEnabled(false);game.startBattle(42);quiet(game);set(game,"wallet",4);game.openShop();
        assertTrue(game.purchase(Item.TURRET));assertTrue(game.beginDeployment());assertTrue(game.deployTurret(300,300));
        assertEquals(18000,game.snapshot().turrets().get(0).remainingMs());
        game.tick(8);assertEquals(18000,game.snapshot().turrets().get(0).remainingMs());
        game.resume();game.tick(8);assertEquals(17992,game.snapshot().turrets().get(0).remainingMs());
        ModelTest.<List<Turret>>field(game,"turrets").get(0).lifeMs=8;game.tick(8);
        assertTrue(game.snapshot().turrets().isEmpty());
    }
    @Test void stockSurvivesEndlessWavesAndResetsForANewRun() {
        TankGameModel game=game(Mode.ENDLESS);quiet(game);set(game,"wallet",4);game.openShop();
        assertTrue(game.purchase(Item.TURRET));game.resume();
        for(Tank tank:tanks(game)) if(tank.player<0) tank.hp=0;
        for(int i=0;i<630;i++) game.tick(8);
        assertEquals(State.UPGRADE,game.snapshot().state());assertTrue(game.chooseUpgrade(Upgrade.REINFORCE));
        assertEquals(1,game.snapshot().tanks().get(0).turretStock());
        game.home();game.selectMode(Mode.ENDLESS);game.startBattle(4);
        assertEquals(0,game.snapshot().tanks().get(0).turretStock());
    }
    @Test void turretBulletsPassThroughTheirOwnerAndStillDamageAnOpponent() {
        TankGameModel game=game(Mode.DUEL);quiet(game);Tank owner=tanks(game).get(0),opponent=tanks(game).get(1);
        owner.x=150;owner.y=100;opponent.x=260;opponent.y=100;
        Turret turret=new Turret(owner.id,100,100);turret.fireMs=0;
        ModelTest.<List<Turret>>field(game,"turrets").add(turret);
        for(int i=0;i<65;i++) game.tick(8);
        assertEquals(owner.maxHp,owner.hp);assertEquals(opponent.maxHp-1,opponent.hp);
    }
    @Test void duelShopRemainsUnavailable() {
        TankGameModel game=game(Mode.DUEL);set(game,"wallet",20);game.openShop();
        assertEquals(State.RUNNING,game.snapshot().state());assertFalse(game.canPurchase(Item.TURRET));
        assertFalse(game.purchase(Item.TURRET));assertEquals(20,game.snapshot().coins());
    }
}
