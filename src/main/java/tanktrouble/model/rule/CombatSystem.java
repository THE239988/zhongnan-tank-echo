package tanktrouble.model.rule;

import java.util.*;
import java.util.function.BiConsumer;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.GridMap;

public final class CombatSystem {
    private final CollisionSystem collision=new CollisionSystem();
    /**
     * Raised once per bounce, at the point of impact, with the owning player flag already resolved.
     *
     * <p>A callback rather than a return value: bounces happen mid-integration, and threading a
     * per-bounce result back out would mean re-walking the bullet list on every tick.
     */
    public interface BounceListener { void onBounce(int player,double x,double y); }
    /** Shared by collision and AI threat detection, including bullets from defeated owners. */
    public static boolean canHit(Bullet bullet,Tank target,List<Tank> tanks) {
        if(!target.alive() || target.id==bullet.owner) return false;
        Tank owner=tanks.stream().filter(t->t.id==bullet.owner).findFirst().orElse(null);
        return owner==null || owner.team!=target.team;
    }
    public Bullet fire(Tank tank,GridMap map) {
        return fire(tank,map,tank.angle,false);
    }
    public Bullet fire(Tank tank,GridMap map,double angle,boolean penetrate) {
        double radians=Math.toRadians(angle),dx=Math.cos(radians),dy=Math.sin(radians);
        double offset=(Rules.TANK_HALF+Rules.BULLET_HALF+2)/Math.max(Math.abs(dx),Math.abs(dy));
        double x=tank.x+dx*offset,y=tank.y+dy*offset;
        if(!penetrate && !collision.clearShot(tank.x,tank.y,x,y,Rules.BULLET_HALF,map)) return null;
        Bullet bullet=new Bullet(tank.id,x,y,angle);bullet.penetrate=penetrate;return bullet;
    }
    public void update(List<Bullet> bullets,GridMap map,List<Tank> tanks,int ms,BiConsumer<Tank,Bullet> onHit,
                       BounceListener onBounce) {
        for(Bullet bullet:bullets) {
            if(bullet.dead) continue;
            bullet.lifeMs-=ms;
            if(bullet.lifeMs<=0) {bullet.dead=true;continue;}
            double remaining=ms/1000.0;
            for(int iteration=0;iteration<8 && remaining>1e-8 && !bullet.dead;iteration++) {
                double dx=bullet.vx*remaining,dy=bullet.vy*remaining;
                double time=1.000001; int nx=0,ny=0; Tank target=null;
                for(var wall:map.walls()) {
                    if(bullet.penetrate) break;
                    var hit=collision.sweep(bullet.x,bullet.y,dx,dy,Rules.BULLET_HALF,wall);
                    if(hit.isEmpty()) continue;
                    var h=hit.get();
                    if(h.time()<time-1e-8) {time=h.time();nx=h.nx();ny=h.ny();}
                    else if(Math.abs(h.time()-time)<1e-8) {nx=nx==0?h.nx():nx;ny=ny==0?h.ny():ny;}
                }
                for(Tank tank:tanks) {
                    if(!canHit(bullet,tank,tanks)) continue;
                    if(tank.bounds().intersects(tanktrouble.model.data.GameData.Rect.centered(bullet.x,bullet.y,Rules.BULLET_HALF))) {
                        time=0;target=tank;break;
                    }
                    var hit=collision.sweep(bullet.x,bullet.y,dx,dy,Rules.BULLET_HALF,tank.bounds());
                    if(hit.isPresent() && hit.get().time()<time-1e-8) {time=hit.get().time();target=tank;}
                }
                if(time>1) {
                    bullet.x+=dx;bullet.y+=dy;
                    if(bullet.penetrate && (bullet.x<Rules.BULLET_HALF || bullet.x>Rules.WIDTH-Rules.BULLET_HALF
                            || bullet.y<Rules.BULLET_HALF || bullet.y>Rules.HEIGHT-Rules.BULLET_HALF)) bullet.dead=true;
                    break;
                }
                bullet.x+=dx*time;bullet.y+=dy*time;
                remaining*=1-time;
                if(target!=null) {bullet.dead=true;onHit.accept(target,bullet);break;}
                if(bullet.penetrate) {
                    if(bullet.x<Rules.BULLET_HALF || bullet.x>Rules.WIDTH-Rules.BULLET_HALF
                            || bullet.y<Rules.BULLET_HALF || bullet.y>Rules.HEIGHT-Rules.BULLET_HALF) bullet.dead=true;
                    continue;
                }
                if(nx!=0) bullet.vx=-bullet.vx;
                if(ny!=0) bullet.vy=-bullet.vy;
                bullet.x+=nx*.001;bullet.y+=ny*.001;
                bullet.bounces++;
                for(Tank owner:tanks) if(owner.id==bullet.owner && owner.player>=0 && owner.alive())
                    owner.energy=Math.min(Rules.PULSE_COST,
                            owner.energy+Rules.BOUNCE_ENERGY+owner.type.bounceBonus+owner.resonance*2);
                onBounce.onBounce(tanks.stream().filter(t->t.id==bullet.owner)
                        .mapToInt(t->t.player).findFirst().orElse(-1),bullet.x,bullet.y);
                if(bullet.bounces>=Rules.MAX_BOUNCES) bullet.dead=true;
            }
        }
    }
}
