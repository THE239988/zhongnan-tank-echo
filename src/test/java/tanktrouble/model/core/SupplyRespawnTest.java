package tanktrouble.model.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.TestMaps;
import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.model.core.ModelTest.*;

class SupplyRespawnTest {
    /** A cell centre far from both DUEL spawns, so nothing collects it by accident. */
    private static final double SUPPLY_X=440,SUPPLY_Y=360;

    /**
     * DUEL has two stationary human players and no AI, so a long advance stays deterministic.
     * The seven generated supplies are replaced by a single tracked one at a known cell.
     */
    private static TankGameModel supplyGame(Item item) {
        TankGameModel game=game(Mode.DUEL);
        quiet(game);
        List<PickupView> pickups=field(game,"pickups");
        Map<Item,PickupView> seeded=field(game,"seeded");
        pickups.clear();
        seeded.clear();
        PickupView supply=new PickupView(SUPPLY_X,SUPPLY_Y,item);
        pickups.add(supply);
        seeded.put(item,supply);
        return game;
    }

    private static Map<Item,PickupView> slots(TankGameModel game) {return field(game,"seeded");}

    private static void advance(TankGameModel game,int ms) {
        for(int elapsed=0;elapsed<ms;elapsed+=8) game.tick(8);
    }

    /** Walks the player onto the tracked supply so the real pickup path runs. */
    private static void takeIt(TankGameModel game) {
        Tank player=tanks(game).get(0);
        player.x=SUPPLY_X;player.y=SUPPLY_Y;
        game.tick(8);
        assertTrue(game.snapshot().pickups().isEmpty(),"The supply should have been collected");
    }

    /** Frees the cell the supply was taken from, so a returning one could legally reuse it. */
    private static void stepAway(TankGameModel game) {
        Tank player=tanks(game).get(0);
        player.x=700;player.y=600;
    }

    @Test void aTakenSeedReturnsExactlyTenSecondsLater() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);

        advance(game,9992);
        assertTrue(game.snapshot().pickups().isEmpty(),"Still gone one tick short of ten seconds");

        game.tick(8);
        assertEquals(1,game.snapshot().pickups().size(),"The slot is back after ten seconds");
    }

    @Test void aReturningSeedKeepsItsItemAndAvoidsTheCellItWasTakenFrom() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);

        advance(game,10000);

        PickupView returned=game.snapshot().pickups().get(0);
        assertEquals(Item.SHIELD,returned.item(),"A slot returns as the item it was");
        assertFalse(returned.x()==SUPPLY_X && returned.y()==SUPPLY_Y,
                "A slot must not return to the cell it was taken from");
    }

    @Test void everyWaveSeedsExactlyOneTrackedSlotPerItem() {
        TankGameModel game=game(Mode.DUEL);
        Map<Item,PickupView> seeded=slots(game);

        assertEquals(EnumSet.allOf(Item.class),EnumSet.copyOf(seeded.keySet()));
        assertEquals(Item.values().length,game.snapshot().pickups().size());
        for(Item item:Item.values())
            assertTrue(game.snapshot().pickups().contains(seeded.get(item)),
                    "Every seeded slot must also be on the map: "+item);
    }

    /**
     * Covers every cell but two, then blocks those two with a terrain zone and a tank.
     *
     * <p>Built so the outcome cannot depend on which cell a random draw happens to pick: a slot
     * that only dodges the cell it came from has 107 places to land and will take one of them,
     * while a slot that respects occupancy has none and must wait.
     */
    @Test void aReturningSeedNeverLandsOnAnOccupiedCell() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        // One player out of the grid and one holding cell (8,6); neither may collect anything.
        Tank parked=tanks(game).get(0);
        parked.x=-500;parked.y=-500;
        Tank other=tanks(game).get(1);
        other.x=(8+.5)*Rules.CELL;other.y=(6+.5)*Rules.CELL;
        List<PickupView> pickups=field(game,"pickups");
        pickups.clear();
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++) {
            if((x==6&&y==4)||(x==8&&y==6)) continue;
            pickups.add(new PickupView((x+.5)*Rules.CELL,(y+.5)*Rules.CELL,Item.REPAIR));
        }
        ModelTest.<List<Zone>>field(game,"zones").add(new Zone(
                new Rect(6*Rules.CELL+8,4*Rules.CELL+8,Rules.CELL-16,Rules.CELL-16),Terrain.LAVA));

        advance(game,10000);

        assertEquals(Rules.COLS*Rules.ROWS-2,game.snapshot().pickups().size(),
                "The slot must wait rather than land on the zone, the tank, or another supply");
    }

    @Test void aReturningSeedWaitsWhenEveryCellIsTaken() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        // Park both players outside the grid: every cell is about to be covered, and a tank
        // standing on one would simply collect the supply we are trying to hold down.
        for(Tank tank:tanks(game)) {tank.x=-500;tank.y=-500;}
        List<PickupView> pickups=field(game,"pickups");
        pickups.clear();
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++)
            pickups.add(new PickupView((x+.5)*Rules.CELL,(y+.5)*Rules.CELL,Item.REPAIR));

        advance(game,10000);
        assertEquals(Rules.COLS*Rules.ROWS,game.snapshot().pickups().size(),
                "A full map must not gain a supply");

        pickups.clear();
        game.tick(8);
        assertEquals(1,game.snapshot().pickups().size(),"It lands as soon as a cell frees up");
    }

    /** MULTI/COOP need explicit player slots; there is no AI in these modes. */
    private static TankGameModel multiGame(Mode mode,int players) {
        TankGameModel game=new TankGameModel();
        game.selectMode(mode);
        game.setTerrainEnabled(false);
        game.startBattle(42,players);
        ModelTest.set(game,"map",TestMaps.open());
        for(Tank tank:tanks(game)) {tank.fireMs=1_000_000;tank.shieldMs=1_000_000;}
        ModelTest.<List<Cell>>field(game,"portals").clear();
        return game;
    }

    /** Walks the player onto an arbitrary supply, for drops that are not tracked slots. */
    private static void takeAt(TankGameModel game,double x,double y) {
        Tank player=tanks(game).get(0);
        player.x=x;player.y=y;
        game.tick(8);
    }

    @Test void anEnemyDropDoesNotReturnAfterBeingTaken() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);
        // A drop of a different item, on a cell nothing else can reach, so whichever supply does
        // come back is unambiguously the seeded one.
        List<PickupView> pickups=field(game,"pickups");
        pickups.add(new PickupView((2+.5)*Rules.CELL,(1+.5)*Rules.CELL,Item.REPAIR));
        takeAt(game,(2+.5)*Rules.CELL,(1+.5)*Rules.CELL);
        assertTrue(game.snapshot().pickups().isEmpty(),"Both the seed and the drop are gone");

        advance(game,30000);

        assertEquals(1,game.snapshot().pickups().size(),"Only the seeded slot comes back");
        assertEquals(Item.SHIELD,game.snapshot().pickups().get(0).item(),
                "A drop must never re-enter the map");
    }

    @Test void multiplayerLetsTheTurretSlotGoForGood() {
        TankGameModel game=multiGame(Mode.MULTI,2);
        List<PickupView> pickups=field(game,"pickups");
        pickups.clear();
        Map<Item,PickupView> seeded=slots(game);
        seeded.clear();
        PickupView turret=new PickupView(SUPPLY_X,SUPPLY_Y,Item.TURRET);
        pickups.add(turret);
        seeded.put(Item.TURRET,turret);

        takeAt(game,SUPPLY_X,SUPPLY_Y);
        assertTrue(game.snapshot().pickups().isEmpty(),"The turret supply was collected");

        advance(game,30000);

        assertTrue(game.snapshot().pickups().isEmpty(),
                "A networked client can never deploy a turret, so its slot must not come back");
    }

    @Test void nonNetworkedModesKeepCyclingTheTurretSlot() {
        TankGameModel game=supplyGame(Item.TURRET);
        takeIt(game);
        stepAway(game);

        advance(game,10000);

        assertEquals(Item.TURRET,game.snapshot().pickups().get(0).item(),
                "Deployment exists outside the networked modes, so the slot keeps cycling");
    }

    @Test void aFullTurretInventoryLeavesTheSlotOnTheMapAndOffTheClock() {
        TankGameModel game=supplyGame(Item.TURRET);
        Tank player=tanks(game).get(0);
        player.turretStock=0;
        List<Turret> turrets=ModelTest.field(game,"turrets");
        for(int i=0;i<Rules.TURRET_LIMIT;i++) {
            Turret turret=new Turret(player.id,200+i*40,600);
            turret.lifeMs=1_000_000;   // never expires, or capacity would free up mid-test
            turret.fireMs=1_000_000;   // never fires, so the duel cannot end mid-test
            turrets.add(turret);
        }

        takeAt(game,SUPPLY_X,SUPPLY_Y);

        assertEquals(1,game.snapshot().pickups().size(),"A refused pickup stays where it is");
        advance(game,30000);
        assertEquals(1,game.snapshot().pickups().size(),"And never starts a countdown");
    }

    @Test void startingAnotherBattleResetsEverySlot() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);
        advance(game,5000);

        game.home();
        game.selectMode(Mode.DUEL);
        game.startBattle(42);

        assertEquals(Item.values().length,game.snapshot().pickups().size(),
                "A fresh battle seeds a full set of slots");
        assertEquals(EnumSet.allOf(Item.class),EnumSet.copyOf(slots(game).keySet()));

        advance(game,9992);
        assertEquals(Item.values().length,game.snapshot().pickups().size(),
                "No stale countdown may survive into the new battle");
    }

    @Test void pauseFreezesTheReturnTimer() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);

        game.pause();
        advance(game,60000);
        assertTrue(game.snapshot().pickups().isEmpty(),"Paused time does not count");

        game.resume();
        advance(game,9992);
        assertTrue(game.snapshot().pickups().isEmpty(),"Still one tick short");
        game.tick(8);
        assertEquals(1,game.snapshot().pickups().size(),"The countdown resumes where it left off");
    }

    @Test void eachEndlessWaveStartsWithAFreshSetOfSlots() {
        TankGameModel game=new TankGameModel();
        game.selectMode(Mode.ENDLESS);
        game.setTerrainEnabled(false);
        game.startBattle(8);
        ModelTest.set(game,"map",TestMaps.open());
        for(Tank tank:tanks(game)) {tank.fireMs=1_000_000;tank.shieldMs=1_000_000;}
        ModelTest.<List<Cell>>field(game,"portals").clear();

        PickupView taken=slots(game).get(Item.SHIELD);
        Tank player=tanks(game).get(0);
        player.x=taken.x();player.y=taken.y();
        game.tick(8);
        advance(game,5000);

        tanks(game).stream().filter(tank->tank.player<0).forEach(tank->tank.hp=0);
        advance(game,6000);
        assertEquals(State.UPGRADE,game.snapshot().state());
        assertTrue(game.chooseUpgrade(Upgrade.REINFORCE));

        assertEquals(Item.values().length,game.snapshot().pickups().size(),
                "The new wave reseeds every slot");
        advance(game,9992);
        assertEquals(Item.values().length,game.snapshot().pickups().size(),
                "The old wave's countdown was discarded");
    }
}
