package tanktrouble.view;

import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.ArcType;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Window;
import tanktrouble.model.data.GameData.TankType;

/** A menu-only instrument overlay around the original raster tank artwork. */
final class HomeScene extends Region {
    private final Canvas base=new Canvas();
    private final Canvas motion=new Canvas();
    private boolean running;
    private long lastFrame;
    private double phase;
    private final ChangeListener<Boolean> windowListener=(o,was,now)->syncAnimation();
    private final ChangeListener<Window> sceneWindowListener=(o,was,now)->watchWindow(was,now);
    private final AnimationTimer timer=new AnimationTimer() {
        @Override public void handle(long now) {
            if(now-lastFrame<33_000_000L) return;
            lastFrame=now;phase=(now/1_000_000_000.0)%8/8.0;drawMotion();
        }
    };

    HomeScene() {
        setMinSize(0,0);setMouseTransparent(true);
        base.setManaged(false);motion.setManaged(false);getChildren().addAll(base,motion);
        sceneProperty().addListener((o,was,now)->{
            if(was!=null) {was.windowProperty().removeListener(sceneWindowListener);watchWindow(was.getWindow(),null);}
            if(now!=null) {now.windowProperty().addListener(sceneWindowListener);watchWindow(null,now.getWindow());}
            syncAnimation();
        });
        visibleProperty().addListener((o,was,now)->syncAnimation());
    }
    private boolean isTreeVisible() {
        for(javafx.scene.Node node=this;node!=null;node=node.getParent()) if(!node.isVisible()) return false;
        return true;
    }
    private void watchWindow(Window before,Window after) {
        if(before!=null) {before.showingProperty().removeListener(windowListener);before.focusedProperty().removeListener(windowListener);}
        if(after!=null) {after.showingProperty().addListener(windowListener);after.focusedProperty().addListener(windowListener);}
        syncAnimation();
    }
    private void syncAnimation() {
        Scene scene=getScene();Window window=scene==null?null:scene.getWindow();
        boolean animate=window!=null&&window.isShowing()&&window.isFocused()&&isTreeVisible();
        if(animate==running) return;
        running=animate;
        if(animate) timer.start();else timer.stop();
    }
    @Override protected void layoutChildren() {
        for(Canvas canvas:new Canvas[]{base,motion}) {canvas.setWidth(getWidth());canvas.setHeight(getHeight());}
        drawBase();drawMotion();
    }
    private double span() {return Math.min(1640,getWidth());}
    private double left() {return (getWidth()-span())/2;}
    private void drawBase() {
        double w=getWidth(),h=getHeight(),s=span(),x0=left();
        GraphicsContext g=base.getGraphicsContext2D();
        g.setFill(Color.web("#10191d"));g.fillRect(0,0,w,h);
        g.setStroke(Color.web("#1b2c32"));g.setLineWidth(1);
        for(double x=18;x<w;x+=44) for(double y=18;y<h;y+=44) {
            g.strokeLine(x-2,y,x+2,y);g.strokeLine(x,y-2,x,y+2);
        }
        // Perspective deck joins the title and the tank, instead of leaving a blank centre.
        double horizon=h*.48,centre=x0+s*.67;
        g.setStroke(Color.web("#29434b"));
        for(int i=0;i<9;i++) {double y=horizon+Math.pow(i/8.0,1.7)*(h-horizon);g.strokeLine(x0+s*.30,y,x0+s,y);}
        for(int i=-5;i<=7;i++) g.strokeLine(centre+i*27,horizon,centre+i*135,h);
        double cx=x0+s*.68,cy=h*.73,rx=s*.275,ry=Math.min(108,h*.14);
        g.setStroke(Color.web("#39636c"));g.setLineWidth(1.2);g.strokeOval(cx-rx,cy-ry,rx*2,ry*2);
        g.setStroke(Color.web("#25434d"));g.strokeOval(cx-rx+16,cy-ry+7,rx*2-32,ry*2-14);
        g.setStroke(Color.web("#8ad6d9"));
        for(int i=0;i<48;i++) {
            double a=Math.PI*2*i/48,outer=i%4==0?11:5;
            g.strokeLine(cx+Math.cos(a)*rx,cy+Math.sin(a)*ry,
                    cx+Math.cos(a)*(rx+outer),cy+Math.sin(a)*(ry+outer*.5));
        }
        g.setFont(Font.font("Consolas",FontWeight.BOLD,16));g.setFill(Color.web("#85b8c3"));
        g.fillText("01  /  BALANCED",x0+s*.47,Math.max(30,h*.105));
        g.setFont(Font.font("Consolas",12));g.setFill(Color.web("#728d97"));
        g.fillText("CSU  /  MECHA ARCHIVE",x0+s*.70,Math.max(30,h*.105));
        double tankX=x0+s*.345,tankY=h*.13,tankW=s*.625,tankH=h*.66;
        MechaArtwork.portrait(g,TankType.BALANCED,tankX,tankY,tankW,tankH);
        double edgeX=x0+s*.96,top=h*.19,bottom=h*.79;
        g.setStroke(Color.web("#568793"));g.setLineWidth(1);
        g.strokeLine(edgeX,top,edgeX,bottom);
        for(int i=0;i<=24;i++) {double y=top+(bottom-top)*i/24;g.strokeLine(edgeX-(i%4==0?13:6),y,edgeX,y);}
        g.setStroke(Color.web("#80e1df"));g.setLineWidth(2);
        corner(g,x0+s*.43,h*.16,1,1);corner(g,x0+s*.92,h*.16,-1,1);
        corner(g,x0+s*.43,h*.82,1,-1);corner(g,x0+s*.92,h*.82,-1,-1);
        double captionY=h*.88;
        g.setFill(Color.web("#d3f780"));g.fillRect(x0+s*.46,captionY-17,4,40);
        g.setFont(Font.font("Microsoft YaHei",FontWeight.BOLD,18));g.setFill(Color.web("#e4f4f4"));
        g.fillText("潇湘 · 天元主战",x0+s*.46+16,captionY);
        g.setFont(Font.font("Consolas",12));g.setFill(Color.web("#90aeb6"));
        g.fillText("BALANCED / RESONANCE UNIT",x0+s*.46+16,captionY+23);
        if(s>1150) {
            g.setFont(Font.font("Microsoft YaHei",12));g.setFill(Color.web("#a4b9c0"));
            g.fillText("初始共振",x0+s*.79,captionY-6);
            g.setFont(Font.font("Consolas",FontWeight.BOLD,25));g.setFill(Color.web("#73dadd"));
            g.fillText(TankType.BALANCED.startingEnergy+" / 100",x0+s*.79,captionY+23);
        }
        g.setStroke(Color.web("#355961"));g.setLineWidth(1);
        double wireX=x0+s*.31,wireY=h*.79;
        if(s>1200) {g.strokePolyline(new double[]{wireX,wireX+42,x0+s*.45},new double[]{wireY,wireY+22,wireY+22},3);}
    }
    private static void corner(GraphicsContext g,double x,double y,int dx,int dy) {
        g.strokeLine(x,y,x+dx*22,y);g.strokeLine(x,y,x,y+dy*16);
    }
    private void drawMotion() {
        GraphicsContext g=motion.getGraphicsContext2D();double s=span(),h=getHeight(),x0=left();
        g.clearRect(0,0,getWidth(),h);
        if(!isTreeVisible()) return;
        double cx=x0+s*.68,cy=h*.73,rx=s*.275,ry=Math.min(108,h*.14);
        g.setStroke(Color.web("#6fe0e2",.75));g.setLineWidth(2);
        g.strokeArc(cx-rx,cy-ry,rx*2,ry*2,phase*360,34,ArcType.OPEN);
        g.setStroke(Color.web("#c9ee71",.8));g.strokeArc(cx-rx,cy-ry,rx*2,ry*2,phase*360+180,18,ArcType.OPEN);
        double y=h*(.19+phase*.56),edge=x0+s*.96;
        g.setFill(Color.web("#7ae4df"));g.fillRect(edge-18,y,22,3);
        g.setFill(Color.web("#d4ef86",.6+.3*Math.sin(phase*Math.PI*2)));
        for(int i=0;i<6;i++) g.fillRect(x0+s*.89+i*5,h*.88,2,6+i*2);
    }
}
