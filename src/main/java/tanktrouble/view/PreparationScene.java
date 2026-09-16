package tanktrouble.view;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import tanktrouble.model.data.GameData.TankType;

/** Static preparation artwork; it never advances or mutates the game model. */
final class PreparationScene extends Region {
    private final Canvas canvas=new Canvas();
    private final TankType type;
    PreparationScene(TankType type) {
        this.type=type;setMinSize(0,0);setMouseTransparent(true);
        canvas.setManaged(false);getChildren().add(canvas);
    }
    @Override protected void layoutChildren() {
        double w=getWidth(),h=getHeight();canvas.setWidth(w);canvas.setHeight(h);
        GraphicsContext g=canvas.getGraphicsContext2D();g.clearRect(0,0,w,h);
        if(type==null) {
            g.setFill(Color.web("#111a1e"));g.fillRect(0,0,w,h);
            g.setStroke(Color.web("#1c2b32"));g.setLineWidth(1);
            for(double x=22;x<w;x+=52)for(double y=22;y<h;y+=52) {g.strokeLine(x-2,y,x+2,y);g.strokeLine(x,y-2,x,y+2);}
            g.setStroke(Color.web("#27424c"));g.strokeLine(0,2,w,2);
            return;
        }
        double cx=w*.5,cy=h*.76,rx=w*.45,ry=Math.min(65,h*.16);
        g.setStroke(Color.web("#284750"));g.setLineWidth(1);
        for(int i=0;i<7;i++) {double y=h*.44+i*h*.085;g.strokeLine(0,y,w,y);}
        for(int i=-5;i<6;i++)g.strokeLine(cx+i*14,h*.44,cx+i*82,h);
        g.setStroke(Color.web("#638f97"));g.strokeOval(cx-rx,cy-ry,rx*2,ry*2);
        g.setStroke(Color.web("#345964"));g.strokeOval(cx-rx+12,cy-ry+6,rx*2-24,ry*2-12);
        g.setStroke(Color.web("#81dbdc"));
        for(int i=0;i<32;i++) {double angle=Math.PI*2*i/32;g.strokeLine(cx+Math.cos(angle)*rx,cy+Math.sin(angle)*ry,cx+Math.cos(angle)*(rx+6),cy+Math.sin(angle)*(ry+4));}
        MechaArtwork.portrait(g,type,4,0,w-8,h*.89);
        g.setStroke(Color.web("#94ddd9"));g.setLineWidth(2);
        for(int dx:new int[]{-1,1})for(int dy:new int[]{-1,1}) {
            double x=dx<0?12:w-12,y=dy<0?14:h-14;
            g.strokeLine(x,y,x-dx*20,y);g.strokeLine(x,y,x,y-dy*14);
        }
        g.setFill(Color.web("#c9eb7d"));
        for(int i=0;i<5;i++)g.fillRect(w-50+i*6,18,3,6+i*2);
    }
}
