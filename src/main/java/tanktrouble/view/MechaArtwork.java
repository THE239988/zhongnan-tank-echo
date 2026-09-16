package tanktrouble.view;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.PixelReader;
import javafx.scene.image.PixelWriter;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import tanktrouble.model.data.GameData.TankType;

/** Local, pre-rendered artwork keeps the menu independent of GPU and web runtimes. */
final class MechaArtwork extends Region {
    private static final Map<TankType,Image> IMAGES=new EnumMap<>(TankType.class);
    private final Canvas canvas=new Canvas();
    private final boolean scenic;

    MechaArtwork(boolean scenic) {
        this.scenic=scenic;setMouseTransparent(true);setMinSize(0,0);
        canvas.setManaged(false);getChildren().add(canvas);
    }
    private static Image image(TankType type) {
        return IMAGES.computeIfAbsent(type,t->{
            return switch(t) {
                case MEDIC -> tinted("heavy",true);
                case RESEARCH -> tinted("engineer",false);
                default -> load(switch(t) {
                    case BALANCED->"balanced";
                    case SCOUT->"scout";
                    case HEAVY->"heavy";
                    case ENGINEER->"engineer";
                    default->throw new IllegalArgumentException("No base artwork for "+t);
                });
            };
        });
    }
    private static Image load(String name) {
        return new Image(Objects.requireNonNull(
                MechaArtwork.class.getResource("/art/mecha-"+name+".png")).toExternalForm());
    }
    /**
     * Derives the two later campus vehicles from the existing renders. Recoloring in place keeps
     * the same silhouette, perspective, line weight, highlights, and transparent edges as the
     * original garage artwork instead of introducing a visibly different illustration style.
     */
    private static Image tinted(String sourceName,boolean medical) {
        Image source=load(sourceName);
        int width=(int)source.getWidth(),height=(int)source.getHeight();
        WritableImage target=new WritableImage(width,height);
        PixelReader reader=source.getPixelReader();
        PixelWriter writer=target.getPixelWriter();
        for(int y=0;y<height;y++) for(int x=0;x<width;x++) {
            Color color=reader.getColor(x,y);
            if(color.getOpacity()==0) continue;
            double hue=color.getHue(),saturation=color.getSaturation(),brightness=color.getBrightness();
            Color changed=color;
            if(medical) {
                if(saturation>.20 && hue>=5 && hue<=55) {
                    changed=Color.hsb(354,Math.max(.58,saturation*1.05),
                            Math.min(1,brightness*1.02),color.getOpacity());
                } else if(saturation>.10 && hue>=140 && hue<=220) {
                    changed=Color.hsb(204,Math.min(.42,saturation*.72),
                            Math.min(1,brightness*1.14+.08),color.getOpacity());
                    changed=whiten(changed,.12);
                }
            } else {
                if(saturation>.18 && hue>=35 && hue<=115) {
                    changed=Color.hsb(164,Math.max(.48,saturation*.95),
                            Math.min(1,brightness*1.10),color.getOpacity());
                } else if(saturation>.10 && hue>=135 && hue<=220) {
                    changed=Color.hsb(170,Math.min(.45,saturation*.78),
                            Math.min(1,brightness*1.12+.06),color.getOpacity());
                } else if(saturation>.20 && (hue<=20 || hue>=340)) {
                    changed=Color.hsb(282,Math.min(.36,saturation*.68),
                            Math.min(1,brightness*1.02),color.getOpacity());
                }
            }
            writer.setColor(x,y,changed);
        }
        return target;
    }
    private static Color whiten(Color color,double amount) {
        return Color.color(mix(color.getRed(),1,amount),mix(color.getGreen(),1,amount),
                mix(color.getBlue(),1,amount),color.getOpacity());
    }
    private static double mix(double a,double b,double amount) { return a+(b-a)*amount; }
    static void portrait(GraphicsContext g,TankType type,double x,double y,double width,double height) {
        Image art=image(type);double scale=Math.min(width/art.getWidth(),height/art.getHeight());
        double w=art.getWidth()*scale,h=art.getHeight()*scale;
        g.drawImage(art,x+(width-w)/2,y+(height-h)/2,w,h);
    }
    static Region portrait(TankType type) {
        return new Region() {
            private final Canvas art=new Canvas();
            {setMinSize(150,170);setPrefSize(400,330);art.setManaged(false);getChildren().add(art);}
            @Override protected void layoutChildren() {
                art.setWidth(getWidth());art.setHeight(getHeight());
                MechaArtwork.portrait(art.getGraphicsContext2D(),type,0,0,getWidth(),getHeight());
            }
        };
    }
    @Override protected void layoutChildren() {
        double w=getWidth(),h=getHeight();canvas.setWidth(w);canvas.setHeight(h);
        GraphicsContext g=canvas.getGraphicsContext2D();
        g.setFill(Color.web("#e2e8e5"));g.fillRect(0,0,w,h);
        g.setStroke(Color.web("#ced7d0"));g.setLineWidth(1);
        double horizon=scenic?h*.36:h*.72;
        for(int i=0;i<8;i++) {double y=horizon+Math.pow(i/7.,1.6)*(h-horizon);g.strokeLine(scenic?w*.44:0,y,w,y);}
        for(int i=-4;i<12;i++) g.strokeLine(w*.66+i*37,horizon,w*.5+i*150,h);
        if(scenic) {
            g.setFill(Color.web("#c9d4ce"));g.setFont(Font.font("Arial",FontWeight.BOLD,108));
            g.fillText("ECHO",w*.55,112);
            portrait(g,TankType.BALANCED,w*.43,h*.09,w*.56,h*.80);
            g.setFill(Color.web("#435b4d"));g.setFont(Font.font("Consolas",FontWeight.BOLD,23));
            g.fillText("LS / 01",w-180,h-52);
            g.setFont(Font.font("Microsoft YaHei",12));g.fillText("麓山 · 均衡型",w-180,h-29);
        }
    }
    static Canvas icon(String name,double size,boolean light) {
        if(name.equals("pause") || name.equals("fullscreen") || name.equals("pulse") || name.equals("supply")) {
            Canvas c=new Canvas(size,size);GraphicsContext g=c.getGraphicsContext2D();g.scale(size/24,size/24);
            g.setStroke(light?Color.web("#dce9d8"):Color.web("#293d2e"));g.setLineWidth(1.8);
            switch(name) {
                case "pause" -> {g.strokeRect(5,4,4,16);g.strokeRect(15,4,4,16);}
                case "fullscreen" -> {for(int i=0;i<4;i++){g.save();g.translate(12,12);g.rotate(i*90);g.strokePolyline(new double[]{-9,-9,-3},new double[]{-3,-9,-9},3);g.restore();}}
                case "pulse" -> g.strokePolygon(new double[]{13,5,11,10,19,13},new double[]{2,13,13,22,10,10},6);
                default -> {g.strokeRect(4,7,16,14);g.strokeRect(8,3,8,4);g.strokeLine(12,11,12,17);g.strokeLine(9,14,15,14);}
            }
            return c;
        }
        Canvas c=CampusArtwork.icon(name,size);ColorAdjust tone=new ColorAdjust();tone.setSaturation(-1);tone.setBrightness(light?.88:-.2);c.setEffect(tone);return c;
    }
}
