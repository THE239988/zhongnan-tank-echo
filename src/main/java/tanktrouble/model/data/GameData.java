package tanktrouble.model.data;

import java.io.Serializable;
import java.util.List;

public final class GameData {
    private GameData() {}
    public enum Mode { SINGLE, DUEL, ENDLESS, MULTI, COOP }
    public enum State { HOME, READY, RUNNING, PAUSED, SHOP, DEPLOYING, UPGRADE, RESULT, EXITED }
    public enum Result { NONE, VICTORY, DEFEAT, P1_WIN, P2_WIN, DRAW, WIN }
    public enum Action { FORWARD, BACKWARD, LEFT, RIGHT, FIRE, PULSE }
    public enum TankType {
        BALANCED(10, 182, 480, 25, 0, 0, 0, 12000),
        HEAVY(15, 132, 680, 0, 0, 0, 0, 12000),
        ENGINEER(9, 170, 500, 50, 0, 1, 0, 18000),
        SCOUT(7, 240, 390, 0, 0, 0, 0, 12000),
        MEDIC(11, 156, 560, 0, 2500, 1, 0, 12000),
        RESEARCH(8, 168, 520, 20, 0, 0, 2, 12000);
        public final int hp, cooldown, startingEnergy, startingShieldMs, repairBonus, bounceBonus, turretLifeMs;
        public final double speed;
        TankType(int hp, double speed, int cooldown, int startingEnergy, int startingShieldMs,
                 int repairBonus, int bounceBonus, int turretLifeMs) {
            this.hp = hp;
            this.speed = speed;
            this.cooldown = cooldown;
            this.startingEnergy = startingEnergy;
            this.startingShieldMs = startingShieldMs;
            this.repairBonus = repairBonus;
            this.bounceBonus = bounceBonus;
            this.turretLifeMs = turretLifeMs;
        }
    }
    public enum Item {
        REPAIR(2, GameEventType.ITEM_REPAIR), SHIELD(3, GameEventType.ITEM_SHIELD),
        RAPID(3, GameEventType.ITEM_RAPID), TURRET(4, GameEventType.ITEM_TURRET),
        SCATTER(5, GameEventType.ITEM_SCATTER), AIM(4, GameEventType.ITEM_AIM),
        PENETRATE(5, GameEventType.ITEM_PENETRATE);
        public final int price;
        /** Event raised when the item is picked up, so each supply can have its own cue. */
        public final GameEventType event;
        Item(int price, GameEventType event) { this.price = price; this.event = event; }
    }
    public enum Upgrade { REINFORCE, OVERCLOCK, RESONANCE }
    public enum Terrain { ICE, LAVA, GRAVITY }
    /**
     * Things the simulation did, reported to the view once per occurrence.
     *
     * <p>Events exist so the view can react to what happened rather than inferring it from frame
     * deltas, which would miss anything spawned and destroyed inside one tick. They carry no
     * behaviour and no JavaFX types, keeping the model free of view concerns.
     */
    public enum GameEventType {
        PLAYER_FIRE, ENEMY_FIRE, TURRET_FIRE, TURRET_DEPLOY,
        HIT_ENEMY, HIT_PLAYER, SHIELD_BLOCK, KILL,
        COIN, ITEM_REPAIR, ITEM_SHIELD, ITEM_RAPID, ITEM_TURRET, ITEM_SCATTER, ITEM_AIM, ITEM_PENETRATE,
        PULSE, TELEPORT, LAVA, BOUNCE, BOSS_SPAWN, BOSS_SKILL, EXPLOSION,
        WAVE_START, BATTLE_START, BATTLE_END
    }
    /**
     * One occurrence of a {@link GameEventType}.
     *
     * @param player {@code true} when the event belongs to a human player rather than the enemy
     *               side. Lets the view pick a different sound for "you were hit" versus
     *               "you hit something" without duplicating the event types.
     * @param x      world position, for events that have one; 0 otherwise
     * @param y      world position, for events that have one; 0 otherwise
     */
    public record GameEvent(GameEventType type, int player, double x, double y,
                            int sourceSeat, double incomingAngle) implements Serializable {
        public GameEvent(GameEventType type,int player,double x,double y) {
            this(type,player,x,y,-1,Double.NaN);
        }
        /** Compatibility for old callers that only distinguish human from enemy. */
        public GameEvent(GameEventType type,boolean player,double x,double y) {
            this(type,player?0:-1,x,y,-1,Double.NaN);
        }
        /** An event with no meaningful position, such as a wave transition. */
        public static GameEvent of(GameEventType type, int player) {
            return new GameEvent(type, player, 0, 0);
        }
        public static GameEvent of(GameEventType type, boolean player) {
            return new GameEvent(type, player?0:-1, 0, 0);
        }
    }
    public record Cell(int x, int y) implements Serializable {
        public double centerX() { return (x + .5) * Rules.CELL; }
        public double centerY() { return (y + .5) * Rules.CELL; }
    }
    public record Rect(double x, double y, double width, double height) implements Serializable {
        public boolean intersects(Rect b) {
            return x < b.x + b.width && x + width > b.x && y < b.y + b.height && y + height > b.y;
        }
        public static Rect centered(double x, double y, double half) { return new Rect(x-half, y-half, half*2, half*2); }
    }
    public record TankView(int id, int player, TankType type, double x, double y, double angle,
                           int hp, int maxHp, double energy, long shieldMs, long rapidMs, long pulseMs,
                           long scatterMs, long aimMs, long penetrateMs, int resonance, int turretStock,
                           boolean boss) implements Serializable {}
    public record BulletView(double x, double y, double vx, double vy, int owner, int bounces, boolean penetrate) implements Serializable {}
    public record CoinView(double x, double y) implements Serializable {}
    public record PickupView(double x, double y, Item item) implements Serializable {}
    public record TurretView(double x, double y, double angle, long remainingMs, int owner) implements Serializable {}
    public record Zone(Rect bounds, Terrain terrain) implements Serializable {}
    public record Snapshot(State state, Mode mode, Result result, int wave, int score, int coins, int kills,
                           int shields, long elapsedMs, long seed, long intermissionMs, List<TankView> tanks,
                           List<BulletView> bullets, List<Rect> walls, List<CoinView> drops,
                           List<PickupView> pickups, List<Cell> portals, List<Zone> zones,
                           List<TurretView> turrets, List<Upgrade> upgrades) implements Serializable {
        public Snapshot {
            tanks=List.copyOf(tanks); bullets=List.copyOf(bullets); walls=List.copyOf(walls);
            drops=List.copyOf(drops); pickups=List.copyOf(pickups); portals=List.copyOf(portals);
            zones=List.copyOf(zones); turrets=List.copyOf(turrets); upgrades=List.copyOf(upgrades);
        }
    }
}
