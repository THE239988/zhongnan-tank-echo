package tanktrouble.model.rule;

import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.*;
import static org.junit.jupiter.api.Assertions.*;

class AiTest {
    private final AiSystem ai=new AiSystem();
    private static Tank enemy(double x,double y) {return new Tank(1,-1,TankType.BALANCED,x,y);}
    private static Tank player(double x,double y) {return new Tank(0,0,TankType.BALANCED,x,y);}

    @Test void approachingBulletTriggersLateralEscapeAndSuppressesShooting() {
        Tank enemy=enemy(400,360),player=player(640,360);
        Bullet bullet=new Bullet(player.id,590,360,180);
        double originalY=enemy.y;
        for(int i=0;i<48;i++) {
            assertFalse(ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(bullet),8,1));
            bullet.x+=bullet.vx*.008;
        }
        assertTrue(Math.abs(enemy.y-originalY)>15,"Enemy must actually move away from the bullet lane");
        assertEquals(1,enemy.hp);
    }
    @Test void recedingAndFriendlyBulletsDoNotInterruptPursuit() {
        Tank enemy=enemy(400,360),player=player(640,360),ally=new Tank(2,-1,TankType.BALANCED,600,500);
        List<Bullet> bullets=List.of(new Bullet(0,590,360,0),new Bullet(2,580,360,180),new Bullet(1,570,360,180));
        double y=enemy.y;
        for(int i=0;i<20;i++) ai.update(enemy,player,TestMaps.open(),List.of(enemy,player,ally),bullets,8,1);
        assertEquals(y,enemy.y,.001);assertTrue(enemy.x>400);
    }
    @Test void blockedDodgeSideIsAvoided() {
        Tank enemy=enemy(400,20),player=player(640,20);
        Bullet bullet=new Bullet(0,590,20,180);
        for(int i=0;i<48;i++) {
            ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(bullet),8,1);
            bullet.x+=bullet.vx*.008;
        }
        assertTrue(enemy.y>35,"Escape must use the space below the top boundary");
        assertFalse(TestMaps.open().blocked(enemy.bounds()));
    }
    @Test void allyOccupyingOneDodgeSideIsAvoided() {
        Tank enemy=enemy(400,360),player=player(640,360),ally=new Tank(2,-1,TankType.BALANCED,400,325);
        Bullet bullet=new Bullet(0,590,360,180);
        for(int i=0;i<48;i++) ai.update(enemy,player,TestMaps.open(),List.of(enemy,player,ally),List.of(bullet),8,1);
        assertTrue(enemy.y>375);assertFalse(enemy.bounds().intersects(ally.bounds()));
    }
    @Test void dodgeDirectionIsHeldWhenAnotherBulletArrives() {
        Tank enemy=enemy(400,360),player=player(640,360);
        Bullet first=new Bullet(0,590,360,180);
        for(int i=0;i<16;i++) ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(first),8,1);
        double before=enemy.y;
        Bullet second=new Bullet(0,enemy.x,enemy.y+100,270);
        for(int i=0;i<8;i++) ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(second),8,1);
        assertTrue((before-360)*(enemy.y-before)>0,"A new threat must not flip an active dodge");
    }
    @Test void wallOccludedBulletDoesNotTriggerDodge() {
        Tank enemy=enemy(120,120),player=player(140,120);
        Bullet hidden=new Bullet(0,210,120,180);
        GridMap map=TestMaps.withWall(new Rect(157,80,6,80));
        for(int i=0;i<15;i++) ai.update(enemy,player,map,List.of(enemy,player),List.of(hidden),8,1);
        assertEquals(120,enemy.y,.001);
    }
    @Test void enemyDoesNotFireAtWallOrAcrossAlly() {
        Tank enemy=enemy(120,120),player=player(320,120),ally=new Tank(2,-1,TankType.BALANCED,220,120);
        assertFalse(ai.update(enemy,player,TestMaps.withWall(new Rect(157,80,6,80)),List.of(enemy,player),List.of(),8,1));
        enemy=enemy(120,120);
        assertFalse(ai.update(enemy,player,TestMaps.open(),List.of(enemy,player,ally),List.of(),8,1));
        enemy=enemy(120,120);
        assertTrue(ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(),8,1));
    }
    @Test void firingRequiresAimAndCooldownAndAfterShotMovementHasFiniteDuration() {
        Tank enemy=enemy(120,120),player=player(320,120);enemy.angle=180;
        assertFalse(ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(),8,1));
        enemy.angle=0;enemy.fireMs=100;
        assertFalse(ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(),8,1));
        enemy.fireMs=0;ai.onFired(enemy,TestMaps.open(),List.of(enemy,player));
        for(int i=0;i<48;i++) assertFalse(ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(),8,1));
        assertTrue(Math.abs(enemy.y-120)>15);
        boolean aimedAgain=false;
        for(int i=0;i<400;i++) aimedAgain|=ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(),8,1);
        assertTrue(aimedAgain,"Post-shot evasion must return to attacking the player");
    }
    @Test void pursuitMakesProgressAcrossCorridorsWithoutThreats() {
        Tank enemy=enemy(40,40),player=player(920,680);
        GridMap map=new RandomMapGenerator().generate(42);
        for(int i=0;i<22000;i++) {
            ai.update(enemy,player,map,List.of(enemy,player),List.of(),8,1);
            assertFalse(map.blocked(enemy.bounds()),"AI movement must remain inside corridors");
        }
        assertTrue(Math.hypot(enemy.x-player.x,enemy.y-player.y)<80,"Enemy must reach the player through the maze");
    }
    @Test void pointBlankTargetCanStillBeShot() {
        Tank enemy=enemy(120,120),player=player(153,120);
        assertTrue(ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(),8,1));
    }
    @Test void dodgeSurvivesAnActualProjectileInTheExistingNarrowCorridor() {
        Tank enemy=enemy(440,360),player=player(440,600);enemy.angle=90;
        GridMap map=TestMaps.withWall(new Rect(397,0,6,720),new Rect(477,0,6,720));
        Bullet bullet=new Bullet(0,440,550,270);
        CombatSystem combat=new CombatSystem();
        for(int i=0;i<100 && !bullet.dead;i++) {
            ai.update(enemy,player,map,List.of(enemy,player),List.of(bullet),8,1);
            combat.update(List.of(bullet),map,List.of(enemy,player),8,(tank,b)->tank.hp--,(p,x,y)->{});
            assertFalse(map.blocked(enemy.bounds()));
        }
        assertEquals(1,enemy.hp,"Incoming fire should miss the dodging enemy even in an 80 px corridor");
    }
    @Test void blockedMovementReversesButTurningInPlaceDoesNot() {
        Tank enemy=enemy(120,120),player=player(320,120),ally=new Tank(2,-1,TankType.BALANCED,155,120);
        boolean reversing=false,backedUp=false;double furthest=enemy.x;
        for(int i=0;i<180;i++) {
            ai.update(enemy,player,TestMaps.open(),List.of(enemy,player,ally),List.of(),8,1);
            furthest=Math.max(furthest,enemy.x);reversing|=enemy.reverseMs>0;
            backedUp|=enemy.reverseMs>0 && enemy.x<furthest-2;
        }
        assertTrue(reversing,"Blocked pursuit needs reverse recovery");
        assertTrue(backedUp,"Reverse recovery must produce actual backward movement");
        Tank turning=enemy(120,120);turning.angle=180;
        for(int i=0;i<90;i++) {
            ai.update(turning,player,TestMaps.open(),List.of(turning,player),List.of(),8,1);
            assertEquals(0,turning.reverseMs,"Normal turning must not trigger reverse");
        }
    }
    @Test void incomingBulletBlockedByAllyDoesNotMakeTankOverlapDuringEscape() {
        Tank enemy=enemy(400,360),player=player(640,360),ally=new Tank(2,-1,TankType.BALANCED,435,360);
        Bullet bullet=new Bullet(0,590,360,180);
        for(int i=0;i<100;i++) {
            ai.update(enemy,player,TestMaps.open(),List.of(enemy,player,ally),List.of(bullet),8,1);
            assertFalse(enemy.bounds().intersects(ally.bounds()));
        }
    }
    @Test void expiredAndOffCourseBulletsAreNotThreats() {
        Tank enemy=enemy(400,360),player=player(640,360);
        Bullet expired=new Bullet(0,590,360,180);expired.lifeMs=8;
        Bullet offCourse=new Bullet(0,580,400,180);
        ai.update(enemy,player,TestMaps.open(),List.of(enemy,player),List.of(expired,offCourse),8,1);
        assertEquals(0,enemy.dodgeMs);
    }
    @Test void teleportResetDropsOldDodgeAndRecoveryMemory() {
        Tank enemy=enemy(120,120);enemy.dodgeMs=400;enemy.reverseMs=200;enemy.stuckMs=500;
        enemy.path=List.of(new Cell(1,1),new Cell(2,1));enemy.target=new Cell(8,8);
        ai.reset(enemy);
        assertEquals(0,enemy.dodgeMs+enemy.reverseMs+enemy.stuckMs+enemy.replanMs);
        assertTrue(enemy.path.isEmpty());assertNull(enemy.target);
    }
}
