package tanktrouble.model.rule;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.*;
import static org.junit.jupiter.api.Assertions.*;

class CombatTest {
    private final CollisionSystem collision=new CollisionSystem();
    private final CombatSystem combat=new CombatSystem();
    @Test void sweepDetectsThinWallBeforeFastProjectileCrossesIt() {
        var hit=collision.sweep(0,40,300,0,4,new Rect(100,0,6,80)).orElseThrow();
        assertEquals(.32,hit.time(),1e-9);assertEquals(-1,hit.nx());assertEquals(0,hit.ny());
    }
    @Test void movingAwayFromSurfaceDoesNotReflectAgain() {
        assertTrue(collision.sweep(96,40,-5,0,4,new Rect(100,0,6,80)).isEmpty());
    }
    @Test void simultaneousCornerCollisionReflectsBothAxes() {
        var hit=collision.sweep(0,0,100,100,0,new Rect(40,40,10,10)).orElseThrow();
        assertEquals(-1,hit.nx());assertEquals(-1,hit.ny());
    }
    @Test void muzzleNeverSpawnsInsideShooterAtAnyAngle() {
        GridMap map=TestMaps.open();Tank tank=new Tank(0,0,TankType.BALANCED,400,400);
        for(int angle=0;angle<360;angle++) {
            tank.angle=angle;Bullet bullet=combat.fire(tank,map);assertNotNull(bullet);
            assertFalse(tank.bounds().intersects(Rect.centered(bullet.x,bullet.y,Rules.BULLET_HALF)),"angle "+angle);
        }
    }
    @Test void wallFacingMuzzleIsBlockedButOpenFacingMuzzleCanFire() {
        Tank tank=new Tank(0,0,TankType.BALANCED,20,40);tank.angle=180;
        assertNull(combat.fire(tank,TestMaps.open()));tank.angle=0;assertNotNull(combat.fire(tank,TestMaps.open()));
    }
    @Test void wallWinsOverTankBehindIt() {
        Bullet bullet=new Bullet(0,920,300,0);bullet.vx=10000;
        Tank target=new Tank(1,1,TankType.BALANCED,1010,300);
        AtomicInteger hits=new AtomicInteger();
        combat.update(List.of(bullet),TestMaps.open(),List.of(target),8,(t,b)->hits.incrementAndGet(),(p,x,y)->{});
        assertEquals(0,hits.get());assertTrue(bullet.vx<0);assertEquals(1,bullet.bounces);
    }
    @Test void nearerTankWinsOverLaterWall() {
        Bullet bullet=new Bullet(0,860,300,0);bullet.vx=10000;
        Tank target=new Tank(1,1,TankType.BALANCED,910,300);
        AtomicInteger hits=new AtomicInteger();
        combat.update(List.of(bullet),TestMaps.open(),List.of(target),8,(t,b)->hits.incrementAndGet(),(p,x,y)->{});
        assertEquals(1,hits.get());assertTrue(bullet.dead);assertEquals(0,bullet.bounces);
    }
    @Test void sixthBounceDestroysAndChargesLivingOwner() {
        Bullet bullet=new Bullet(0,950,300,0);bullet.bounces=5;
        Tank owner=new Tank(0,0,TankType.BALANCED,300,300);
        combat.update(List.of(bullet),TestMaps.open(),List.of(owner),40,(t,b)->fail("unexpected hit"),(p,x,y)->{});
        assertEquals(6,bullet.bounces);assertTrue(bullet.dead);assertEquals(6,owner.energy);
    }
    @Test void reflectedBulletPassesThroughItsOwner() {
        Tank owner=new Tank(0,0,TankType.BALANCED,900,300);
        Bullet bullet=new Bullet(0,950,300,0);AtomicInteger hits=new AtomicInteger();
        for(int i=0;i<50 && !bullet.dead;i++) combat.update(List.of(bullet),TestMaps.open(),List.of(owner),8,(t,b)->hits.incrementAndGet(),(p,x,y)->{});
        assertEquals(0,hits.get());assertTrue(bullet.bounces>0);
        assertFalse(bullet.dead);assertTrue(bullet.x<owner.x-Rules.TANK_HALF);
    }
    @Test void bulletOverlappingOwnerContinuesToOpponent() {
        Tank owner=new Tank(7,0,TankType.BALANCED,100,100);
        Tank opponent=new Tank(8,1,TankType.BALANCED,150,100);
        Bullet bullet=new Bullet(owner.id,100,100,0);bullet.vx=10000;
        List<Integer> hitIds=new ArrayList<>();
        combat.update(List.of(bullet),TestMaps.open(),List.of(owner,opponent),8,(t,b)->hitIds.add(t.id),(p,x,y)->{});
        assertEquals(List.of(opponent.id),hitIds);assertTrue(bullet.dead);
    }
    @Test void fastBulletSweepsThroughOwnerToOpponent() {
        Tank owner=new Tank(3,0,TankType.BALANCED,120,100);
        Tank opponent=new Tank(4,-1,TankType.BALANCED,170,100);
        Bullet bullet=new Bullet(owner.id,80,100,0);bullet.vx=14000;
        List<Integer> hitIds=new ArrayList<>();
        combat.update(List.of(bullet),TestMaps.open(),List.of(owner,opponent),8,(t,b)->hitIds.add(t.id),(p,x,y)->{});
        assertEquals(List.of(opponent.id),hitIds);assertTrue(bullet.dead);
    }
    @Test void tankCannotOverlapAnotherTankOrBoundary() {
        Tank a=new Tank(0,0,TankType.BALANCED,100,100),b=new Tank(1,1,TankType.BALANCED,150,100);
        assertFalse(collision.canMove(a,135,100,TestMaps.open(),List.of(a,b)));
        assertFalse(collision.canMove(a,1,100,TestMaps.open(),List.of(a,b)));
    }
    @Test void tankMovingOntoBulletIsHitEvenWhenSweepStartsInside() {
        Tank target=new Tank(0,0,TankType.BALANCED,300,300);Bullet bullet=new Bullet(1,300,300,0);
        AtomicInteger hits=new AtomicInteger();
        combat.update(List.of(bullet),TestMaps.open(),List.of(target),8,(t,b)->hits.incrementAndGet(),(p,x,y)->{});
        assertEquals(1,hits.get());assertTrue(bullet.dead);
    }
    @Test void enemyBulletOverlappingAllyContinuesToPlayer() {
        Tank shooter=new Tank(3,-1,TankType.BALANCED,40,100);
        Tank ally=new Tank(4,-1,TankType.BALANCED,100,100);
        Tank player=new Tank(0,0,TankType.BALANCED,150,100);
        Bullet bullet=new Bullet(shooter.id,100,100,0);bullet.vx=10000;
        List<Integer> hitIds=new ArrayList<>();
        combat.update(List.of(bullet),TestMaps.open(),List.of(shooter,ally,player),8,(t,b)->hitIds.add(t.id),(p,x,y)->{});
        assertEquals(List.of(player.id),hitIds);assertTrue(bullet.dead);
    }
    @Test void fastEnemyBulletSweepsThroughAllyToPlayer() {
        Tank shooter=new Tank(3,-1,TankType.BALANCED,40,100);
        Tank ally=new Tank(4,-1,TankType.BALANCED,120,100);
        Tank player=new Tank(0,0,TankType.BALANCED,180,100);
        Bullet bullet=new Bullet(shooter.id,80,100,0);bullet.vx=16000;
        List<Integer> hitIds=new ArrayList<>();
        combat.update(List.of(bullet),TestMaps.open(),List.of(shooter,ally,player),8,(t,b)->hitIds.add(t.id),(p,x,y)->{});
        assertEquals(List.of(player.id),hitIds);assertTrue(bullet.dead);
    }
    @Test void reflectedEnemyBulletPassesThroughAllyAndHitsPlayer() {
        Tank shooter=new Tank(3,-1,TankType.BALANCED,400,300);
        Tank ally=new Tank(4,-1,TankType.BALANCED,910,300);
        Tank player=new Tank(0,0,TankType.BALANCED,860,300);
        Bullet bullet=new Bullet(shooter.id,950,300,0);bullet.vx=10000;
        List<Integer> hitIds=new ArrayList<>();
        combat.update(List.of(bullet),TestMaps.open(),List.of(shooter,ally,player),8,(t,b)->hitIds.add(t.id),(p,x,y)->{});
        assertEquals(List.of(player.id),hitIds);assertEquals(1,bullet.bounces);assertTrue(bullet.dead);
    }
    @Test void deadEnemyShooterStillCannotDamageItsAllies() {
        Tank shooter=new Tank(3,-1,TankType.BALANCED,40,100);shooter.hp=0;
        Tank ally=new Tank(4,-1,TankType.BALANCED,100,100);
        Tank player=new Tank(0,0,TankType.BALANCED,150,100);
        Bullet bullet=new Bullet(shooter.id,100,100,0);bullet.vx=10000;
        List<Integer> hitIds=new ArrayList<>();
        combat.update(List.of(bullet),TestMaps.open(),List.of(shooter,ally,player),8,(t,b)->hitIds.add(t.id),(p,x,y)->{});
        assertEquals(List.of(player.id),hitIds);assertTrue(bullet.dead);
    }
    @Test void secondHumanPlayerCanStillHitFirstHumanPlayer() {
        Tank shooter=new Tank(1,1,TankType.BALANCED,40,100);
        Tank opponent=new Tank(0,0,TankType.BALANCED,150,100);
        Bullet bullet=new Bullet(shooter.id,100,100,0);bullet.vx=10000;
        List<Integer> hitIds=new ArrayList<>();
        combat.update(List.of(bullet),TestMaps.open(),List.of(shooter,opponent),8,(t,b)->hitIds.add(t.id),(p,x,y)->{});
        assertEquals(List.of(opponent.id),hitIds);assertTrue(bullet.dead);
    }
    @Test void unknownOwnerDoesNotInferEnemyFactionFromItsId() {
        Tank enemy=new Tank(4,-1,TankType.BALANCED,150,100);
        Bullet bullet=new Bullet(99,100,100,0);bullet.vx=10000;
        List<Integer> hitIds=new ArrayList<>();
        combat.update(List.of(bullet),TestMaps.open(),List.of(enemy),8,(t,b)->hitIds.add(t.id),(p,x,y)->{});
        assertEquals(List.of(enemy.id),hitIds);assertTrue(bullet.dead);
    }

    @Test void penetratingBulletIgnoresWallsButDiesAtArenaBoundary() {
        GridMap map=TestMaps.withWall(new tanktrouble.model.data.GameData.Rect(400,0,6,720));
        Bullet bullet=new Bullet(0,350,300,0);bullet.penetrate=true;bullet.vx=10000;
        combat.update(List.of(bullet),map,List.of(),100,(t,b)->fail("unexpected hit"),(p,x,y)->{});
        assertTrue(bullet.dead,"penetrating shots still terminate at the arena edge");
        assertTrue(bullet.x>Rules.WIDTH-Rules.BULLET_HALF-1,"shot crossed the wall before termination");
        assertEquals(0,bullet.bounces,"walls do not bounce penetrating shots");
    }
}
