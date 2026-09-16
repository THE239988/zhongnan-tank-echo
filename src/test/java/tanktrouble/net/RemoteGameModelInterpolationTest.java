package tanktrouble.net;

import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Guards how the client blends two server frames.
 *
 * <p>Rounds carry no identity on the wire, so each one in the newer frame is paired with the nearest
 * round of the same owner in the older one. The gate deciding "too far apart to be the same round"
 * has to be expressed in the same units as the distance it is compared against - a squared bound
 * measured against an unsquared distance never rejects anything, and every new round inherits a
 * dead one's position.
 */
class RemoteGameModelInterpolationTest {
    private static Snapshot withBullets(List<BulletView> bullets) {
        return new Snapshot(State.RUNNING,Mode.MULTI,Result.NONE,1,0,0,0,0,0,0,0,
                List.of(),bullets,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }

    @Test void aRoundThatReplacedAnotherIsNotDraggedFromTheDeadOne() {
        Snapshot before=withBullets(List.of(new BulletView(900,400,0,0,7,0,false)));
        Snapshot after=withBullets(List.of(new BulletView(100,400,0,0,7,0,false)));

        BulletView blended=RemoteGameModel.interpolate(before,after,0.5).bullets().get(0);

        assertEquals(100,blended.x(),16,
                "A round fired at the muzzle must not be blended from a dead one 800 px away");
    }

    @Test void aRoundThatMovedALittleIsStillSmoothed() {
        Snapshot before=withBullets(List.of(new BulletView(100,400,0,0,7,0,false)));
        Snapshot after=withBullets(List.of(new BulletView(108,400,0,0,7,0,false)));

        BulletView blended=RemoteGameModel.interpolate(before,after,0.5).bullets().get(0);

        assertEquals(104,blended.x(),0.001,"A round in flight must still be blended across the gap");
    }

    @Test void roundsThatOnlySwappedOrderKeepTheirOwnTrack() {
        Snapshot before=withBullets(List.of(
                new BulletView(100,100,0,0,7,0,false),
                new BulletView(800,600,0,0,7,0,false)));
        Snapshot after=withBullets(List.of(
                new BulletView(801,601,0,0,7,0,false),
                new BulletView(101,101,0,0,7,0,false)));

        for(BulletView blended:RemoteGameModel.interpolate(before,after,0.5).bullets())
            assertTrue((Math.abs(blended.x()-100.5)<1&&Math.abs(blended.y()-100.5)<1)
                            ||(Math.abs(blended.x()-800.5)<1&&Math.abs(blended.y()-600.5)<1),
                    "Rounds that swapped list order must keep their own track: "
                            +blended.x()+","+blended.y());
    }

    @Test void aRoundKeepsTravellingWhenNoNewerFrameHasArrived() {
        Snapshot previous=withBullets(List.of(new BulletView(100,400,240,0,7,0,false)));
        Snapshot newest=withBullets(List.of(new BulletView(108,400,240,0,7,0,false)));

        // 16 ms past the newest frame, travelling at 240 px/s it should be a further ~3.8 px along.
        Snapshot ahead=RemoteGameModel.extrapolate(previous,0,newest,33_000_000L,16_000_000L);

        assertEquals(111.8,ahead.bullets().get(0).x(),0.5,
                "A round must carry on travelling rather than stand still");
    }

    @Test void aTankKeepsMovingOnTheSpeedItWasAlreadyGoing() {
        Snapshot previous=withTank(100,400);
        // 8 px over 33 ms is ~242 px/s - SCOUT's top speed, so a speed a tank can really reach.
        Snapshot newest=withTank(108,400);

        Snapshot ahead=RemoteGameModel.extrapolate(previous,0,newest,33_000_000L,16_000_000L);

        assertEquals(111.9,ahead.tanks().get(0).x(),0.5,
                "A tank must keep its last known speed, not freeze");
    }

    @Test void aTeleportDoesNotFlingTheTankAcrossTheArena() {
        Snapshot previous=withTank(100,400);
        // 700 px in one frame is far beyond any tank's speed: it is a portal, not motion.
        Snapshot newest=withTank(800,400);

        Snapshot ahead=RemoteGameModel.extrapolate(previous,0,newest,33_000_000L,16_000_000L);

        assertEquals(800,ahead.tanks().get(0).x(),1.0,
                "A teleport must land where the server put it, not be smeared onwards");
    }

    private static Snapshot withTank(double x,double y) {
        TankView tank=new TankView(1,0,TankType.BALANCED,x,y,0,10,10,0,0,0,0,0,0,0,0,0,false);
        return new Snapshot(State.RUNNING,Mode.MULTI,Result.NONE,1,0,0,0,0,0,0,0,
                List.of(tank),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }

    @Test void anotherPlayersRoundIsNeverBorrowedAsAPredecessor() {
        Snapshot before=withBullets(List.of(new BulletView(100,400,0,0,3,0,false)));
        Snapshot after=withBullets(List.of(new BulletView(104,400,0,0,7,0,false)));

        BulletView blended=RemoteGameModel.interpolate(before,after,0.5).bullets().get(0);

        assertEquals(104,blended.x(),0.001,
                "A round belonging to someone else must never stand in as the predecessor");
    }
}
