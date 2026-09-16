package tanktrouble.model.entity;

import java.util.*;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;

/** Mutable simulation entities never escape through the public model API. */
public final class Entities {
    private Entities() {}
    public static final class Tank {
        public final int id, player;
        public final TankType type;
        public int team;
        public double x,y,angle,energy,vx,vy;
        public int hp,maxHp,fireMs,portalMs,replanMs,heatMs;
        public long shieldMs,rapidMs,pulseMs,scatterMs,aimMs,penetrateMs;
        public double reloadMultiplier=1;
        public int resonance,turretStock;
        public int dodgeMs,reverseMs,stuckMs;
        /** Non-player boss timing/identity; kept on the mutable entity so snapshots stay compact. */
        public int skillMs;
        public boolean boss;
        public double dodgeAngle;
        public List<Cell> path=List.of();
        public Cell target;
        public Tank(int id,int player,TankType type,double x,double y) {
            this.id=id; this.player=player; this.team=player; this.type=type; this.x=x; this.y=y;
            hp=maxHp=player<0?1:type.hp;
        }
        public boolean alive() { return hp>0; }
        public Rect bounds() { return Rect.centered(x,y,Rules.TANK_HALF); }
        public TankView view() { return new TankView(id,player,type,x,y,angle,hp,maxHp,energy,shieldMs,rapidMs,pulseMs,scatterMs,aimMs,penetrateMs,resonance,turretStock,boss); }
    }
    public static final class Bullet {
        public final int owner;
        public double x,y,vx,vy;
        public int bounces,lifeMs=Rules.BULLET_LIFE_MS;
        public boolean penetrate;
        public boolean dead;
        public Bullet(int owner,double x,double y,double angle) {
            this.owner=owner; this.x=x; this.y=y;
            vx=Math.cos(Math.toRadians(angle))*Rules.BULLET_SPEED;
            vy=Math.sin(Math.toRadians(angle))*Rules.BULLET_SPEED;
        }
    }
    public static final class Turret {
        public final int owner;
        public final double x,y;
        public int lifeMs=12000,fireMs=500;
        public double angle;
        public Turret(int owner,double x,double y) { this.owner=owner;this.x=x;this.y=y; }
    }
}
