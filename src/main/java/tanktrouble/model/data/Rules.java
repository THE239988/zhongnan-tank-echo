package tanktrouble.model.data;

public final class Rules {
    private Rules() {}
    public static final int STEP_MS = 8, MAX_FRAME_MS = 50;
    public static final int COLS = 12, ROWS = 9, CELL = 80;
    public static final int WIDTH = COLS * CELL, HEIGHT = ROWS * CELL;
    public static final double WALL = 6, TANK_HALF = 16, BULLET_HALF = 4;
    public static final double TURRET_HALF = 16, PORTAL_HALF = 18;
    public static final int TURRET_LIMIT = 3;
    public static final double BULLET_SPEED = 240, TURN_SPEED = 260, REVERSE = .5;
    public static final int MAX_BOUNCES = 6, BULLET_LIFE_MS = 10000;
    public static final int ENEMY_FIRE_MS = 1000, REPLAN_MS = 200;
    public static final int AI_DODGE_MS = 480, AI_SCOOT_MS = 400, AI_STUCK_MS = 600, AI_REVERSE_MS = 350;
    public static final double AI_DODGE_RANGE = 250, AI_DODGE_DOT = .85, AI_PROBE = 48, AI_PROBE_STEP = 1;
    public static final double AI_FIRE_BAND = 8, AI_TURN_BAND = 24;
    public static final int COIN_SCORE = 10, PULSE_COST = 100, BOUNCE_ENERGY = 6;
    public static final double PULSE_RADIUS = 145;
    public static final int PORTAL_COOLDOWN_MS = 1500, EFFECT_MS = 9000;
    public static final int TERRAIN_REFRESH_MS = 10000;
    /** How long a consumed seeded supply stays gone before returning at a different cell. */
    public static final int ITEM_RESPAWN_MS = 10000;
}
