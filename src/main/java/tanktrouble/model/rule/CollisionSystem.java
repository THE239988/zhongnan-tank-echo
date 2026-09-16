package tanktrouble.model.rule;

import java.util.*;
import tanktrouble.model.data.GameData.Rect;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.Tank;
import tanktrouble.model.map.GridMap;

public final class CollisionSystem {
    public record Hit(double time,int nx,int ny) {}
    public boolean canMove(Tank tank,double x,double y,GridMap map,List<Tank> tanks) {
        Rect candidate=Rect.centered(x,y,Rules.TANK_HALF);
        return !map.blocked(candidate) && tanks.stream().noneMatch(t->t!=tank && t.alive() && candidate.intersects(t.bounds()));
    }
    public void move(Tank tank,double dx,double dy,GridMap map,List<Tank> tanks) {
        if(canMove(tank,tank.x+dx,tank.y+dy,map,tanks)) { tank.x+=dx;tank.y+=dy;return; }
        if(canMove(tank,tank.x+dx,tank.y,map,tanks)) tank.x+=dx;
        if(canMove(tank,tank.x,tank.y+dy,map,tanks)) tank.y+=dy;
    }
    /** Slab sweep against an expanded AABB, including simultaneous corner normals. */
    public Optional<Hit> sweep(double x,double y,double dx,double dy,double half,Rect wall) {
        double left=wall.x()-half,right=wall.x()+wall.width()+half;
        double top=wall.y()-half,bottom=wall.y()+wall.height()+half;
        if(Math.abs(dx)<1e-12 && (x<=left || x>=right)) return Optional.empty();
        if(Math.abs(dy)<1e-12 && (y<=top || y>=bottom)) return Optional.empty();
        double tx0=dx==0?Double.NEGATIVE_INFINITY:Math.min((left-x)/dx,(right-x)/dx);
        double tx1=dx==0?Double.POSITIVE_INFINITY:Math.max((left-x)/dx,(right-x)/dx);
        double ty0=dy==0?Double.NEGATIVE_INFINITY:Math.min((top-y)/dy,(bottom-y)/dy);
        double ty1=dy==0?Double.POSITIVE_INFINITY:Math.max((top-y)/dy,(bottom-y)/dy);
        double entry=Math.max(tx0,ty0),exit=Math.min(tx1,ty1);
        if(entry>exit || exit<0 || entry>1 || entry< -1e-8) return Optional.empty();
        int nx=tx0>=ty0-1e-8 ? (dx>0?-1:1):0;
        int ny=ty0>=tx0-1e-8 ? (dy>0?-1:1):0;
        return Optional.of(new Hit(Math.max(0,entry),nx,ny));
    }
    public boolean clearShot(double x,double y,double endX,double endY,double half,GridMap map) {
        if(map.blocked(Rect.centered(endX,endY,half))) return false;
        return map.walls().stream().noneMatch(w->sweep(x,y,endX-x,endY-y,half,w).isPresent());
    }
}
