package tanktrouble.model.rule;

import java.util.*;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.*;

public final class AiSystem {
    private final Pathfinder pathfinder=new Pathfinder();
    private final CollisionSystem collision=new CollisionSystem();
    private final CombatSystem combat=new CombatSystem();

    /** Returns a fire request; the model owns cooldown and successful-shot feedback. */
    public boolean update(Tank enemy,Tank player,GridMap map,List<Tank> tanks,List<Bullet> bullets,int ms,int wave) {
        if(!enemy.alive() || !player.alive()) return false;
        if(enemy.reverseMs>0) {
            enemy.reverseMs=Math.max(0,enemy.reverseMs-ms);
            moveAlong(enemy,enemy.angle,-speed(wave)*Rules.REVERSE*ms/1000.,map,tanks);
            enemy.replanMs=0;enemy.stuckMs=0;return false;
        }
        if(enemy.dodgeMs<=0) {
            Bullet threat=findThreat(enemy,bullets,map,tanks);
            if(threat!=null) beginDodge(enemy,threat,map,tanks,Rules.AI_DODGE_MS);
        }
        if(enemy.dodgeMs>0) {
            enemy.dodgeMs=Math.max(0,enemy.dodgeMs-ms);
            double difference=turn(enemy,enemy.dodgeAngle,ms);
            if(Math.abs(difference)<=135) drive(enemy,enemy.angle,speed(wave)*ms/1000.,map,tanks,ms);
            else enemy.stuckMs=0;
            if(enemy.dodgeMs==0) enemy.replanMs=0;
            return false;
        }
        double[] destination=pursuitTarget(enemy,player,map,ms);
        double dx=destination[0]-enemy.x,dy=destination[1]-enemy.y,distance=Math.hypot(dx,dy);
        if(distance>1) {
            double difference=turn(enemy,Math.toDegrees(Math.atan2(dy,dx)),ms);
            if(Math.abs(difference)<Rules.AI_TURN_BAND && (distance>Rules.TANK_HALF*2 || enemy.path.size()>1))
                drive(enemy,Math.toDegrees(Math.atan2(dy,dx)),Math.min(distance,speed(wave)*ms/1000.),map,tanks,ms);
            else enemy.stuckMs=0;
        } else enemy.stuckMs=0;
        return enemy.reverseMs==0 && shouldFire(enemy,player,map,tanks);
    }

    private double[] pursuitTarget(Tank enemy,Tank player,GridMap map,int ms) {
        enemy.replanMs-=ms;
        Cell goal=map.cell(player.x,player.y),here=map.cell(enemy.x,enemy.y);
        if(enemy.replanMs<=0 || !goal.equals(enemy.target) || enemy.path.isEmpty() || !here.equals(enemy.path.get(0))) {
            enemy.path=pathfinder.findPath(map,here,goal);
            enemy.target=goal;enemy.replanMs=Rules.REPLAN_MS;
        }
        if(collision.clearShot(enemy.x,enemy.y,player.x,player.y,Rules.TANK_HALF,map)) return new double[]{player.x,player.y};
        if(enemy.path.size()>1) {
            Cell current=enemy.path.get(0),next=enemy.path.get(1);
            // Align across the corridor without returning to a cell center already passed.
            if(current.x()!=next.x() && Math.abs(current.centerY()-enemy.y)>2)
                return new double[]{enemy.x,current.centerY()};
            if(current.y()!=next.y() && Math.abs(current.centerX()-enemy.x)>2)
                return new double[]{current.centerX(),enemy.y};
            return new double[]{next.centerX(),next.centerY()};
        }
        return new double[]{player.x,player.y};
    }

    private Bullet findThreat(Tank enemy,List<Bullet> bullets,GridMap map,List<Tank> tanks) {
        Bullet nearest=null;double soonest=Double.POSITIVE_INFINITY;
        for(Bullet bullet:bullets) {
            if(bullet.dead || bullet.lifeMs<=0 || !CombatSystem.canHit(bullet,enemy,tanks)) continue;
            double dx=enemy.x-bullet.x,dy=enemy.y-bullet.y,distance=Math.hypot(dx,dy);
            double speed=Math.hypot(bullet.vx,bullet.vy);
            if(distance>Rules.AI_DODGE_RANGE || distance<1e-6 || speed<1e-6) continue;
            if((bullet.vx*dx+bullet.vy*dy)/(speed*distance)<Rules.AI_DODGE_DOT) continue;
            double horizon=Math.min(bullet.lifeMs/1000.,Rules.AI_DODGE_RANGE/speed);
            var contact=collision.sweep(bullet.x,bullet.y,bullet.vx*horizon,bullet.vy*horizon,Rules.BULLET_HALF+3,enemy.bounds());
            if(contact.isEmpty()) continue;
            double time=contact.get().time()*horizon;
            if(time>=soonest || !collision.clearShot(bullet.x,bullet.y,
                    bullet.x+bullet.vx*time,bullet.y+bullet.vy*time,Rules.BULLET_HALF,map)) continue;
            nearest=bullet;soonest=time;
        }
        return nearest;
    }

    private void beginDodge(Tank enemy,Bullet threat,GridMap map,List<Tank> tanks,int duration) {
        double speed=Math.hypot(threat.vx,threat.vy),nx=-threat.vy/speed,ny=threat.vx/speed;
        double cross=nx*(enemy.x-threat.x)+ny*(enemy.y-threat.y);
        double heading=escapeHeading(enemy,nx,ny,cross,map,tanks);
        if(Double.isFinite(heading)) {enemy.dodgeAngle=heading;enemy.dodgeMs=duration;enemy.stuckMs=0;}
    }

    private double escapeHeading(Tank enemy,double nx,double ny,double cross,GridMap map,List<Tank> tanks) {
        double a=room(enemy,nx,ny,map,tanks),b=room(enemy,-nx,-ny,map,tanks);
        if(Math.max(a,b)<Rules.AI_PROBE_STEP) return Double.NaN;
        double required=Math.max(1,Rules.TANK_HALF+Rules.BULLET_HALF+.5-Math.abs(cross));
        boolean first;
        if(a<required && b>=required) first=false;
        else if(b<required && a>=required) first=true;
        else if(a<Rules.AI_PROBE_STEP) first=false;
        else if(b<Rules.AI_PROBE_STEP) first=true;
        else if(Math.abs(cross)>.5) first=cross>0;
        else if(Math.abs(a-b)>.5) first=a>b;
        else first=enemy.id%2==0;
        return Math.toDegrees(Math.atan2(first?ny:-ny,first?nx:-nx));
    }

    private double room(Tank enemy,double dx,double dy,GridMap map,List<Tank> tanks) {
        for(double distance=Rules.AI_PROBE_STEP;distance<=Rules.AI_PROBE;distance+=Rules.AI_PROBE_STEP)
            if(!collision.canMove(enemy,enemy.x+dx*distance,enemy.y+dy*distance,map,tanks)) return distance-Rules.AI_PROBE_STEP;
        return Rules.AI_PROBE;
    }

    private boolean shouldFire(Tank enemy,Tank player,GridMap map,List<Tank> tanks) {
        if(enemy.fireMs>0) return false;
        double heading=Math.toDegrees(Math.atan2(player.y-enemy.y,player.x-enemy.x));
        if(Math.abs(normalize(heading-enemy.angle))>Rules.AI_FIRE_BAND) return false;
        Bullet shot=combat.fire(enemy,map);
        if(shot==null) return false;
        double travel=Math.hypot(player.x-shot.x,player.y-shot.y)+Rules.TANK_HALF*2;
        double dx=Math.cos(Math.toRadians(enemy.angle))*travel,dy=Math.sin(Math.toRadians(enemy.angle))*travel;
        var target=collision.sweep(shot.x,shot.y,dx,dy,Rules.BULLET_HALF,player.bounds());
        boolean overlapping=player.bounds().intersects(Rect.centered(shot.x,shot.y,Rules.BULLET_HALF));
        if(target.isEmpty() && !overlapping) return false;
        double time=overlapping?0:target.orElseThrow().time();
        if(!collision.clearShot(shot.x,shot.y,shot.x+dx*time,shot.y+dy*time,Rules.BULLET_HALF,map)) return false;
        for(Tank ally:tanks) if(ally!=enemy && ally.player<0 && ally.alive()) {
            if(ally.bounds().intersects(Rect.centered(shot.x,shot.y,Rules.BULLET_HALF))) return false;
            var obstruction=collision.sweep(shot.x,shot.y,dx,dy,Rules.BULLET_HALF,ally.bounds());
            if(obstruction.isPresent() && obstruction.get().time()<=time) return false;
        }
        return true;
    }

    public void onFired(Tank enemy,GridMap map,List<Tank> tanks) {
        double angle=Math.toRadians(enemy.angle);
        double heading=escapeHeading(enemy,-Math.sin(angle),Math.cos(angle),0,map,tanks);
        if(Double.isFinite(heading)) {enemy.dodgeAngle=heading;enemy.dodgeMs=Rules.AI_SCOOT_MS;enemy.stuckMs=0;}
    }

    private void drive(Tank enemy,double heading,double step,GridMap map,List<Tank> tanks,int ms) {
        double x=enemy.x,y=enemy.y;moveAlong(enemy,heading,step,map,tanks);
        enemy.stuckMs=Math.hypot(enemy.x-x,enemy.y-y)<step*.1?enemy.stuckMs+ms:0;
        if(enemy.stuckMs>=Rules.AI_STUCK_MS) {enemy.stuckMs=0;enemy.dodgeMs=0;enemy.reverseMs=Rules.AI_REVERSE_MS;}
    }
    private void moveAlong(Tank enemy,double heading,double step,GridMap map,List<Tank> tanks) {
        double angle=Math.toRadians(heading);collision.move(enemy,Math.cos(angle)*step,Math.sin(angle)*step,map,tanks);
    }
    private static double speed(int wave) {return 90+Math.min(55,Math.max(0,wave-1)*5);}
   private static double turn(Tank enemy,double heading,int ms) {
        double difference=normalize(heading-enemy.angle),limit=Rules.TURN_SPEED*ms/1000.;
        enemy.angle=(enemy.angle+Math.max(-limit,Math.min(limit,difference))+360)%360;
        return normalize(heading-enemy.angle);
    }
    private static double normalize(double angle) {return ((angle%360)+540)%360-180;}
    public void reset(Tank enemy) {
        enemy.path=List.of();enemy.target=null;enemy.replanMs=0;
        enemy.dodgeMs=enemy.reverseMs=enemy.stuckMs=0;
    }
}
