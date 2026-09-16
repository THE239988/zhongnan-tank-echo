package tanktrouble.model.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.entity.Entities.*;
import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.model.core.ModelTest.*;

/**
 * Covers the event channel the view uses to drive sound.
 *
 * <p>The contract under test is delivery, not behaviour: events must appear at the moment the
 * simulation does the thing, exactly once, and draining must leave nothing behind. Sound design
 * itself is verified by ear, not here.
 */
class ModelEventTest {
    private static List<GameEventType> types(List<GameEvent> events) {
        return events.stream().map(GameEvent::type).toList();
    }
    private static List<GameEvent> drain(TankGameModel game) {return game.drainEvents();}

    @Test void startingABattleAnnouncesTheBattleAndFirstWave() {
        TankGameModel game=new TankGameModel();game.selectMode(Mode.SINGLE);
        List<GameEventType> announced=types(game.drainEvents());
        assertTrue(announced.isEmpty(),"Nothing happens before the battle starts");
        game.startBattle(7);
        List<GameEventType> started=types(drain(game));
        assertEquals(List.of(GameEventType.BATTLE_START,GameEventType.WAVE_START),started);
    }

    @Test void drainingClearsTheBufferSoEachEventIsDeliveredOnce() {
        TankGameModel game=game(Mode.SINGLE);
        drain(game);
        tanks(game).get(0).fireMs=0;
        game.handleInput(0,Action.FIRE,true);game.tick(8);
        assertEquals(List.of(GameEventType.PLAYER_FIRE),types(drain(game)));
        assertTrue(drain(game).isEmpty(),"A drained event must not be replayed");
        assertTrue(drain(game).isEmpty());
    }

    /**
     * The two sides must be told apart, because the whole point of the flag is to let the view
     * choose "your gun" versus "their gun". Only the player is forced to fire here; whether the AI
     * chooses to shoot on a given tick depends on line of sight, which is not what this asserts.
     */
    @Test void playerAndEnemyFireAreDistinguishable() {
        TankGameModel game=game(Mode.SINGLE);drain(game);
        Tank player=tanks(game).get(0);
        player.fireMs=0;
        game.handleInput(0,Action.FIRE,true);
        game.tick(8);
        List<GameEvent> playerShot=drain(game).stream()
                .filter(e->e.type()==GameEventType.PLAYER_FIRE).toList();
        assertEquals(1,playerShot.size());
        assertTrue(playerShot.get(0).player()>=0);
        assertTrue(playerShot.get(0).x()>0||playerShot.get(0).y()>0,"Fire carries a position for panning");

        TankGameModel enemySide=game(Mode.SINGLE);drain(enemySide);
        Tank enemy=tanks(enemySide).stream().filter(t->t.player<0).findFirst().orElseThrow();
        // Put the enemy in the open with a clear line to the player and no friendly fire to fear.
        for(Tank tank:tanks(enemySide)) if(tank.player>=0) {tank.x=100;tank.y=100;}
        enemy.x=enemy.y=100;enemy.angle=0;enemy.fireMs=0;
        List<Bullet> stray=field(enemySide,"bullets");
        stray.clear();
        enemySide.tick(8);
        assertTrue(drain(enemySide).stream()
                        .filter(e->e.type()==GameEventType.ENEMY_FIRE).noneMatch(e->e.player()>=0),
                "Enemy fire must never be flagged as the player's");
    }

    @Test void collectingACoinReportsItsPositionAndPaysOut() {
        TankGameModel game=game(Mode.SINGLE);drain(game);
        Tank player=tanks(game).get(0);
        List<CoinView> coins=field(game,"coins");
        coins.add(new CoinView(player.x,player.y));
        game.tick(8);
        List<GameEvent> events=drain(game);
        assertTrue(types(events).contains(GameEventType.COIN));
        GameEvent coin=events.stream().filter(e->e.type()==GameEventType.COIN).findFirst().orElseThrow();
        assertEquals(player.x,coin.x(),1e-9);
        assertEquals(player.y,coin.y(),1e-9);
        assertEquals(0,coin.player());
        assertEquals(10,game.snapshot().score());
    }

    /** Each supply carries its own event so the view can give them distinct cues. */
    @Test void eachPickupTypeReportsItsOwnEvent() {
        for(Item item:Item.values()) {
            TankGameModel game=game(Mode.SINGLE);drain(game);
            Tank player=tanks(game).get(0);
            player.hp=player.maxHp/2;
            ModelTest.<List<PickupView>>field(game,"pickups").add(new PickupView(player.x,player.y,item));
            game.tick(8);
            assertTrue(types(drain(game)).contains(item.event),item+" should report "+item.event);
        }
    }

    @Test void damagingAndKillingAnEnemyReportsBothEvents() {
        TankGameModel game=game(Mode.SINGLE);drain(game);
        Tank enemy=tanks(game).stream().filter(t->t.player<0).findFirst().orElseThrow();
        enemy.hp=1;
        List<Bullet> bullets=field(game,"bullets");
        bullets.add(new Bullet(tanks(game).get(0).id,enemy.x,enemy.y,0));
        game.tick(8);
        List<GameEventType> reported=types(drain(game));
        assertTrue(reported.contains(GameEventType.HIT_ENEMY));
        assertTrue(reported.contains(GameEventType.KILL));
    }

    /**
     * A player taking fire must not be reported as an enemy hit; the two drive different cues and
     * are easy to transpose.
     */
    @Test void damageToAPlayerReportsThePlayerHitEvent() {
        TankGameModel game=game(Mode.SINGLE);drain(game);
        Tank player=tanks(game).get(0);
        Bullet fromEnemy=new Bullet(999,player.x,player.y,0);
        List<Bullet> incoming=field(game,"bullets");
        incoming.add(fromEnemy);
        game.tick(8);
        List<GameEvent> events=drain(game);
        assertTrue(types(events).contains(GameEventType.HIT_PLAYER));
        assertFalse(types(events).contains(GameEventType.HIT_ENEMY));
        GameEvent hit=events.stream().filter(e->e.type()==GameEventType.HIT_PLAYER).findFirst().orElseThrow();
        assertEquals(0,hit.player());
        assertTrue(hit.sourceSeat()<0,
                "The hit came from the enemy side");
    }

    /** Lava damage is environmental, so it must not be attributed to a player shot. */
    @Test void lavaDamageIsNotAttributedToAPlayer() {
        TankGameModel game=game(Mode.SINGLE);drain(game);
        Tank player=tanks(game).get(0);
        set(game,"terrainEnabled",true);
        // Keep the enemies alive but far away and harmless. Removing them would end the battle on
        // the next tick (single player ends when no enemy remains), and a zone wide enough to hold
        // the player would otherwise catch whoever spawned nearby and report their tick first.
        for(Tank enemy:tanks(game)) if(enemy.player<0) {
            enemy.maxHp=10_000;enemy.hp=10_000;enemy.x=900;enemy.y=40;enemy.fireMs=1_000_000;
        }
        List<Zone> zones=field(game,"zones");
        zones.clear();
        zones.add(new Zone(Rect.centered(player.x,player.y,40),Terrain.LAVA));
        for(int i=0;i<130;i++) game.tick(8);
        List<GameEvent> lava=drain(game).stream().filter(e->e.type()==GameEventType.LAVA).toList();
        assertFalse(lava.isEmpty(),"Standing in lava should report the terrain event");
        assertEquals(0,lava.get(0).player(),"The player is the one standing in it");
        assertFalse(lava.get(0).x()==900,"The enemy is not in the zone");
    }

    /**
     * A blocked hit is reported as a block, not as damage. Without a separate event the player
     * gets no feedback that the shield worked at all.
     */
    @Test void shieldAbsorptionIsReportedSeparatelyFromDamage() {
        TankGameModel game=game(Mode.SINGLE);drain(game);
        Tank player=tanks(game).get(0);
        player.shieldMs=5000;
        int before=game.snapshot().shields();
        ModelTest.<List<Bullet>>field(game,"bullets").add(new Bullet(9999,player.x,player.y,0));
        game.tick(8);
        List<GameEvent> events=drain(game);
        List<GameEventType> reported=types(events);
        assertTrue(reported.contains(GameEventType.SHIELD_BLOCK));
        assertFalse(reported.contains(GameEventType.HIT_PLAYER),"A blocked hit deals no damage");
        GameEvent block=events.stream()
                .filter(event->event.type()==GameEventType.SHIELD_BLOCK)
                .findFirst().orElseThrow();
        assertEquals(0,block.player(),"The blocked player is the event target");
        assertTrue(block.sourceSeat()<0,"The enemy side owns the blocked shell");
        assertFalse(Double.isNaN(block.incomingAngle()),
                "Shield feedback needs the flank the shell arrived from");
        assertEquals(before+1,game.snapshot().shields());
        assertEquals(player.maxHp,player.hp,"A shield must absorb the hit entirely");
    }

    @Test void endingABattleAnnouncesItAndStopsEmitting() {
        TankGameModel game=game(Mode.SINGLE);drain(game);
        tanks(game).get(0).hp=0;
        game.tick(8);
        assertTrue(types(drain(game)).contains(GameEventType.BATTLE_END));
        assertTrue(drain(game).isEmpty());
    }

    /**
     * Selecting an upgrade happens outside {@link TankGameModel#tick}, so the events it produces
     * must be buffered rather than dropped until the next frame.
     */
    @Test void eventsOutsideTheTickLoopAreStillDelivered() {
        TankGameModel game=game(Mode.ENDLESS);drain(game);
        for(Tank tank:tanks(game)) if(tank.player<0) tank.hp=0;
        for(int i=0;i<700;i++) game.tick(8);
        assertEquals(State.UPGRADE,game.snapshot().state());
        drain(game);
        assertTrue(game.chooseUpgrade(Upgrade.REINFORCE));
        List<GameEventType> afterUpgrade=types(drain(game));
        assertEquals(List.of(GameEventType.WAVE_START),afterUpgrade,
                "Choosing an upgrade begins the next wave without a tick in between");
    }

    @Test void deployedTurretIsTheOnlyThingThatReportsDeployment() {
        TankGameModel game=game(Mode.SINGLE);drain(game);
        Tank player=tanks(game).get(0);
        player.turretStock=1;
        assertTrue(game.beginDeployment());
        drain(game);
        assertTrue(types(drain(game)).isEmpty(),"Entering deployment mode is not itself an event");
        assertTrue(game.deployTurret(player.x+60,player.y));
        assertTrue(types(drain(game)).contains(GameEventType.TURRET_DEPLOY));
    }

    @Test void drainedEventsAreAnImmutableCopy() {
        TankGameModel game=game(Mode.SINGLE);
        List<GameEvent> events=drain(game);
        assertFalse(events.isEmpty());
        assertThrows(UnsupportedOperationException.class,()->events.clear());
    }
}
