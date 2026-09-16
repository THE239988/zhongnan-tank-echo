package tanktrouble.view;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;

/** Original campus illustration, scaled as a single artboard without distorting geometry. */
final class CampusArtwork extends Region {
    static final Color INK=Color.web("#303729");
    static final Color GREEN=Color.web("#95b952");
    private final Canvas canvas=new Canvas();
    private final boolean scenic;

    CampusArtwork(boolean scenic) {
        this.scenic=scenic;
        setMouseTransparent(true);setMinSize(0,0);
        canvas.setManaged(false);getChildren().add(canvas);
    }
    @Override protected void layoutChildren() {
        canvas.setWidth(getWidth());canvas.setHeight(getHeight());
        GraphicsContext g=canvas.getGraphicsContext2D();
        g.setFill(Color.web("#b9d67b"));g.fillRect(0,0,getWidth(),getHeight());
        double scale=Math.max(getWidth()/1440,getHeight()/960);
        g.save();g.translate((getWidth()-1440*scale)/2,(getHeight()-960*scale)/2);g.scale(scale,scale);
        drawCampus(g);
        if(!scenic) {g.setFill(Color.web("#e7efd8",.20));g.fillRect(0,0,1440,960);}
        g.restore();
    }
    private static double px(double x,double y) {return 720+x-y;}
    private static double py(double x,double y,double z) {return -230+(x+y)*.48-z;}
    private static void face(GraphicsContext g,String color,double... xyz) {
        int n=xyz.length/3;double[] x=new double[n],y=new double[n];
        for(int i=0;i<n;i++) {x[i]=px(xyz[i*3],xyz[i*3+1]);y[i]=py(xyz[i*3],xyz[i*3+1],xyz[i*3+2]);}
        g.setFill(Color.web(color));g.fillPolygon(x,y,n);g.setStroke(INK);g.setLineWidth(2.5);g.strokePolygon(x,y,n);
    }
    private static void ground(GraphicsContext g) {g.transform(1,.48,-1,.48,720,-230);}
    private static void drawCampus(GraphicsContext g) {
        g.save();ground(g);
        g.setFill(Color.web("#b4d375"));g.fillRect(-1200,-1200,3600,3600);
        g.setFill(Color.web("#c9df8c"));
        for(int x=-500;x<1800;x+=130) for(int y=-500;y<1800;y+=130)
            if(Math.floorMod(x+y,3)==0) g.fillRect(x+8,y+8,110,110);
        g.setStroke(Color.web("#faf3d3"));g.setLineWidth(105);
        for(int road:new int[]{20,390,1010,1390}) {g.strokeLine(road,-900,road,2200);g.strokeLine(-900,road,2200,road);}
        g.setStroke(Color.web("#c6c5b8"));g.setLineWidth(73);
        for(int road:new int[]{20,390,1010,1390}) {g.strokeLine(road,-900,road,2200);g.strokeLine(-900,road,2200,road);}
        g.setStroke(Color.web("#f8f5e1"));g.setLineWidth(3);g.setLineDashes(20,22);
        for(int road:new int[]{20,390,1010,1390}) {g.strokeLine(road,-900,road,2200);g.strokeLine(-900,road,2200,road);}
        g.setLineDashes();
        g.setFill(Color.web("#f1eccc"));g.fillRoundRect(465,470,470,455,70,70);
        g.setFill(Color.web("#d9855f"));g.fillRoundRect(485,490,430,415,150,150);
        g.setStroke(Color.web("#fff0cb"));g.setLineWidth(3);
        for(int i=0;i<4;i++) g.strokeRoundRect(497+i*12,502+i*12,406-i*24,391-i*24,142-i*15,142-i*15);
        g.setFill(Color.web("#acd060"));g.fillRoundRect(553,558,294,279,58,58);
        g.setFill(Color.web("#bbdb76"));for(int i=0;i<4;i++) g.fillRect(561+i*70,589,35,215);
        g.setStroke(Color.web("#f6f2cf"));g.setLineWidth(3);
        g.strokeRect(568,581,263,231);g.strokeLine(568,696,831,696);g.strokeOval(658,658,84,76);
        g.strokeRect(637,581,125,35);g.strokeRect(637,777,125,35);
        g.setFill(Color.web("#85b860"));g.fillRect(455,100,475,220);
        g.setStroke(Color.web("#f9f1cf"));g.strokeRect(469,114,447,192);
        g.strokeRect(510,114,365,192);g.strokeLine(693,114,693,306);g.strokeLine(469,210,916,210);
        g.strokeLine(510,161,875,161);g.strokeLine(510,258,875,258);
        g.setStroke(Color.web("#87af57"));g.setLineWidth(3);
        for(int i=0;i<140;i++) {
            int x=Math.floorMod(i*173,1640)-100,y=Math.floorMod(i*277,1580)-100;
            if((x<345 || x>1065) && (y<335 || y>1065)) {g.strokeLine(x,y,x+3,y-6);g.strokeLine(x+6,y,x+8,y-5);}
        }
        g.restore();
        building(g,85,80,245,230,113,true);
        building(g,1060,85,265,225,134,true);
        for(int i=0;i<7;i++) tree(g,468+i*68,349,24+(i%3)*3);
        for(int i=0;i<6;i++) tree(g,351,100+i*43,25);
        building(g,90,505,238,280,109,true);
        building(g,1100,485,240,300,132,true);
        for(int i=0;i<7;i++) tree(g,957,496+i*68,27);
        for(int i=0;i<7;i++) tree(g,464+i*68,948,27+(i%2)*4);
        for(int i=0;i<5;i++) tree(g,74,825+i*31,27);
        tank(g,px(769,638),py(769,638,0),.96,false);
        tank(g,px(841,801),py(841,801,0),1.10,true);
        building(g,85,1085,246,240,110,false);
        building(g,548,1095,334,205,117,true);
        for(int i=0;i<5;i++) tree(g,927,1100+i*60,27);
        for(int i=0;i<5;i++) tree(g,1130+i*51,881,28);
        tank(g,px(1218,1005),py(1218,1005,0),1.12,false);
        tank(g,px(1001,1240),py(1001,1240,0),1.05,true);
        tree(g,1220,1220,40);tree(g,1328,1220,33);tree(g,1180,1340,36);
    }
    private static void building(GraphicsContext g,double x,double y,double w,double d,double h,boolean entry) {
        g.save();ground(g);g.setFill(Color.web("#647c42",.24));g.fillRect(x+16,y+18,w+16,d+18);g.restore();
        face(g,"#f5ecd0",x-10,y-10,0,x+w+10,y-10,0,x+w+10,y+d+10,0,x-10,y+d+10,0);
        face(g,"#b95f48",x+w,y,0,x+w,y+d,0,x+w,y+d,h,x+w,y,h);
        face(g,"#e5946b",x,y+d,0,x+w,y+d,0,x+w,y+d,h,x,y+d,h);
        face(g,"#f8edc9",x-6,y-6,h,x+w+6,y-6,h,x+w+6,y+d+6,h,x-6,y+d+6,h);
        face(g,"#ded3b2",x+12,y+12,h+1,x+w-12,y+12,h+1,x+w-12,y+d-12,h+1,x+12,y+d-12,h+1);
        face(g,"#fff5d8",x+23,y+23,h+2,x+w-23,y+23,h+2,x+w-23,y+d-23,h+2,x+23,y+d-23,h+2);
        for(int row=0;row<2;row++) {
            double z=22+row*43;
            for(int i=0;i<5;i++) {
                double wx=x+19+i*(w-38)/5;
                face(g,"#b7e0df",wx,y+d+.5,z,wx+23,y+d+.5,z,wx+23,y+d+.5,z+27,wx,y+d+.5,z+27);
                g.setStroke(Color.web("#fff5d8"));g.setLineWidth(1.5);
                g.strokeLine(px(wx+12,y+d+1),py(wx+12,y+d+1,z+2),px(wx+12,y+d+1),py(wx+12,y+d+1,z+25));
            }
            for(int i=0;i<4;i++) {
                double wy=y+24+i*(d-35)/4;
                face(g,"#a4cbd1",x+w+.5,wy,z,x+w+.5,wy+23,z,x+w+.5,wy+23,z+27,x+w+.5,wy,z+27);
            }
        }
        if(entry) {
            double cx=x+w*.5;
            face(g,"#fff0ce",cx-35,y+d+3,0,cx+35,y+d+3,0,cx+35,y+d+3,h+15,cx-35,y+d+3,h+15);
            face(g,"#87bdc7",cx-24,y+d+4,5,cx+24,y+d+4,5,cx+24,y+d+4,49,cx-24,y+d+4,49);
            face(g,"#fff5dc",cx-42,y+d+1,53,cx+42,y+d+1,53,cx+42,y+d+27,53,cx-42,y+d+27,53);
            for(int i=0;i<4;i++) face(g,"#efe4c2",cx-36-i*3,y+d+i*9,9-i*3,cx+36+i*3,y+d+i*9,9-i*3,cx+36+i*3,y+d+(i+1)*9,9-i*3,cx-36-i*3,y+d+(i+1)*9,9-i*3);
            double sx=px(cx,y+d+5),sy=py(cx,y+d+5,h-10);
            g.setFill(Color.web("#d98b56"));g.fillOval(sx-8,sy-10,16,20);g.setStroke(INK);g.setLineWidth(2);g.strokeOval(sx-8,sy-10,16,20);
        }
    }
    private static void tree(GraphicsContext g,double x,double y,double r) {
        double sx=px(x,y),sy=py(x,y,0);
        g.setFill(Color.web("#536b39",.23));g.fillOval(sx-r+10,sy-8,r*2,18);
        g.setFill(Color.web("#ac8050"));g.fillRoundRect(sx-5,sy-33,10,37,3,3);g.setStroke(INK);g.setLineWidth(2.5);g.strokeRoundRect(sx-5,sy-33,10,37,3,3);
        g.setFill(Color.web("#8fb851"));g.fillOval(sx-r,sy-r*2.2,r*2,r*1.8);g.strokeOval(sx-r,sy-r*2.2,r*2,r*1.8);
        g.setFill(Color.web("#b9d778"));g.fillOval(sx-r*.72,sy-r*2.12,r*1.3,r*1.12);
        g.setFill(Color.web("#d1e690"));g.fillOval(sx-r*.3,sy-r*2.04,r*.5,r*.42);
    }
    static void tank(GraphicsContext g,double x,double y,double scale,boolean red) {
        g.save();g.translate(x,y);g.scale(scale,scale);g.setLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        g.setFill(Color.web("#526533",.24));g.fillOval(-62,10,144,37);
        g.setLineWidth(3);g.setStroke(INK);
        g.setFill(Color.web("#494e37"));g.fillRoundRect(-48,0,106,31,24,24);g.strokeRoundRect(-48,0,106,31,24,24);
        for(int i=0;i<6;i++) {g.setFill(Color.web("#aaa17b"));g.fillOval(-41+i*16,8,14,17);g.strokeOval(-41+i*16,8,14,17);g.setFill(INK);g.fillOval(-36+i*16,14,4,5);}
        g.setFill(Color.web(red?"#b95f48":"#729340"));g.fillPolygon(new double[]{-53,25,60,60,-17,-53},new double[]{-11,-25,-8,5,20,4},6);g.strokePolygon(new double[]{-53,25,60,60,-17,-53},new double[]{-11,-25,-8,5,20,4},6);
        g.setFill(Color.web(red?"#e9986d":"#bdd778"));g.fillPolygon(new double[]{-53,25,60,-17},new double[]{-11,-25,-8,8},4);g.strokePolygon(new double[]{-53,25,60,-17},new double[]{-11,-25,-8,8},4);
        g.setFill(Color.web(red?"#cb7555":"#99b957"));g.fillOval(-23,-46,58,42);g.strokeOval(-23,-46,58,42);
        g.setFill(Color.web(red?"#f5b88a":"#d4e895"));g.fillOval(-15,-49,43,27);g.strokeOval(-15,-49,43,27);
        g.setFill(Color.web("#6f7950"));g.fillOval(0,-45,16,9);g.strokeOval(0,-45,16,9);
        g.setStroke(INK);g.setLineCap(StrokeLineCap.ROUND);g.setLineWidth(12);g.strokeLine(-11,-26,-67,-52);
        g.setStroke(Color.web(red?"#e7a477":"#c1d87f"));g.setLineWidth(6);g.strokeLine(-11,-27,-67,-53);
        g.setStroke(INK);g.setLineWidth(2);g.strokeLine(-66,-56,-70,-50);
        double[] starX=new double[10],starY=new double[10];
        for(int i=0;i<10;i++) {double a=-Math.PI/2+i*Math.PI/5,r=i%2==0?6:2.8;starX[i]=22+Math.cos(a)*r;starY[i]=-20+Math.sin(a)*r;}
        g.setFill(Color.web("#fff5ce"));g.fillPolygon(starX,starY,10);
        g.restore();
    }
    static Canvas icon(String name,double size) {
        Canvas icon=new Canvas(size,size);GraphicsContext g=icon.getGraphicsContext2D();g.scale(size/24,size/24);
        g.setStroke(INK);g.setFill(INK);g.setLineWidth(2);g.setLineCap(StrokeLineCap.ROUND);
        switch(name) {
            case "play" -> g.fillPolygon(new double[]{6,20,6},new double[]{3,12,21},3);
            case "settings" -> {
                g.strokeOval(5,5,14,14);g.strokeOval(9,9,6,6);
                for(int i=0;i<8;i++) {double a=i*Math.PI/4;g.strokeLine(12+Math.cos(a)*8,12+Math.sin(a)*8,12+Math.cos(a)*11,12+Math.sin(a)*11);}
            }
            case "trophy" -> {
                g.strokePolyline(new double[]{6,6,9,15,18,18},new double[]{3,10,15,15,10,3},6);g.strokeLine(6,3,18,3);
                g.strokePolyline(new double[]{6,2,2,7},new double[]{5,5,10,12},4);g.strokePolyline(new double[]{18,22,22,17},new double[]{5,5,10,12},4);
                g.strokeLine(12,15,12,21);g.strokeLine(7,21,17,21);
            }
            case "exit" -> {g.strokeArc(3,3,18,18,130,280,javafx.scene.shape.ArcType.OPEN);g.strokeLine(12,1,12,11);}
            case "back" -> {g.strokePolyline(new double[]{10,3,10},new double[]{5,12,19},3);g.strokeLine(3,12,21,12);}
            case "flag" -> {g.strokeLine(5,3,5,22);g.fillPolygon(new double[]{5,21,17,21,5},new double[]{3,3,7,12,12},5);}
            default -> g.strokeRect(4,4,16,16);
        }
        return icon;
    }
}
