package tanktrouble.view;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import tanktrouble.art.ArtAssets;
import tanktrouble.art.PortraitView;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;

/**
 * One player's own half of a duel battle screen.
 *
 * <p>The panel owns every piece of information that can differ between the two seats: the chosen
 * character, armour and energy meters, active buffs, pulse control and radar. Keeping those
 * together is what prevents P2 from accidentally reading P1's state, which was the bug in the old
 * shared sidebar/portrait layout.
 */
final class PlayerPanel extends BorderPane {
    private static final double RADAR_WIDTH=208;
    private static final double RADAR_HEIGHT=104;

    private final int player;
    private final TankGameModel model;
    private ArtAssets.Hero hero;
    private final PortraitView portrait=new PortraitView();
    private final Label name=new Label();
    private final Label caption=new Label();
    /**
     * Armour readout, split in two.
     *
     * <p>Held as one label until a long model name pushed the numbers past the card's width, and
     * JavaFX duly ellipsized the end - which was the half that mattered. Separating them lets the
     * name give up its space and the numbers keep theirs.
     */
    private final Label healthName=new Label();
    private final Label healthValue=new Label();
    private final Label energy=new Label();
    private final Label effects=new Label("—");
    private final HBox healthMeter=new HBox(3);
    private final HBox energyMeter=new HBox(3);
    private final Button pulse=new Button();
    private final Canvas radar=new Canvas(RADAR_WIDTH,RADAR_HEIGHT);
    private boolean running;

    PlayerPanel(int player,TankGameModel model,ArtAssets.Hero hero) {
        this.player=player;
        this.model=model;
        this.hero=hero;
        setId("player-panel-"+(player+1));
        getStyleClass().addAll("side-shell","player-panel");
        setPrefWidth(250);
        setMinWidth(218);
        setMaxWidth(310);

        portrait.setCharacter(hero);
        // 立绘是这一列里唯一允许伸缩的项：空间不够时先压它，其余卡片保持原高。
        portrait.setFlexibleHeight(true);
        name.setText("P"+(player+1)+" / "+hero.label());
        name.getStyleClass().add("driver-name");
        caption.setText(hero.caption());
        caption.getStyleClass().add("driver-caption");

        VBox identity=new VBox(3,name,caption);
        identity.getStyleClass().add("driver-identity-card");

        healthName.getStyleClass().add("health-readout");
        healthValue.getStyleClass().add("health-readout");
        energy.getStyleClass().add("energy-readout");
        effects.getStyleClass().add("effects-readout");
        configureMeter(healthMeter,"hp-meter","armor-meter");
        configureMeter(energyMeter,"energy-meter","energy-meter");
        HBox healthLine=new HBox(8,healthName,healthValue);
        healthLine.setAlignment(Pos.CENTER_LEFT);
        healthName.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(healthName,Priority.ALWAYS);
        // Pinned to its preferred width: the numbers must survive whatever the panel is squeezed to.
        healthValue.setMinWidth(Region.USE_PREF_SIZE);
        VBox healthCard=new VBox(7,label("装甲完整度","meter-label"),healthLine,healthMeter);
        healthCard.getStyleClass().add("driver-stat-card");
        VBox energyCard=new VBox(7,label("共振能量","meter-label"),energy,energyMeter);
        energyCard.getStyleClass().add("driver-stat-card");
        VBox effectsCard=new VBox(5,label("当前增幅","meter-label"),effects);
        effectsCard.getStyleClass().add("effects-card");

        pulse.setId("pulse-button-"+(player+1));
        pulse.setText(player==0?"脉冲  Q":"脉冲  Shift");
        pulse.setTooltip(new javafx.scene.control.Tooltip(player==0?"Q":"Shift"));
        pulse.setGraphic(MechaArtwork.icon("pulse",17,false));
        pulse.setGraphicTextGap(9);
        pulse.getStyleClass().add("primary");
        pulse.setMaxWidth(Double.MAX_VALUE);
        pulse.setFocusTraversable(false);
        pulse.setOnAction(event->model.handleInput(player,Action.PULSE,true));

        StackPane portraitFrame=new StackPane(portrait);
        portraitFrame.getStyleClass().addAll("portrait-frame","player-portrait");
        VBox.setVgrow(portraitFrame,Priority.ALWAYS);

        radar.setId("tactical-radar-"+(player+1));
        radar.getStyleClass().add("tactical-radar");
        VBox radarCard=new VBox(8,label("战区状态  /  P"+(player+1)+" SCAN","eyebrow"),radar);
        radarCard.getStyleClass().add("tactical-card");

        VBox body=new VBox(8,
                label("驾驶员 / P"+(player+1),"eyebrow"),
                portraitFrame,
                radarCard,
                identity,
                healthCard,
                energyCard,
                pulse,
                effectsCard);
        body.setPadding(new Insets(12));
        body.setFillWidth(true);
        body.setAlignment(Pos.TOP_CENTER);
        body.getStyleClass().add("driver-panel");
        ScrollPane scroll=new ScrollPane(body);
        scroll.setFitToWidth(true);
        // 必须同时 fit 高度，否则 ScrollPane 会给内容「首选高度」然后滚动 ——
        // 那样 VBox 永远拿不到一个受限的高度，立绘的弹性就完全不起作用。
        // 内容被压到视口高度后，多出来的空间由立绘让出；真到让无可让时才会重新出现滚动条。
        scroll.setFitToHeight(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("configuration-scroll");
        setCenter(scroll);
    }

    int player() {return player;}

    void setCharacter(ArtAssets.Hero next) {
        if(next==null||next==hero) return;
        hero=next;
        portrait.setCharacter(hero);
        name.setText("P"+(player+1)+" / "+hero.label());
        caption.setText(hero.caption());
    }

    void start() {
        if(running) return;
        running=true;
        portrait.start();
    }

    void stop() {
        if(!running) return;
        running=false;
        portrait.stop();
    }

    void react(GameEvent event) {
        if(event.player()!=player) return;
        switch(event.type()) {
            case PLAYER_FIRE -> portrait.attack();
            case HIT_PLAYER -> portrait.hit();
            default -> { }
        }
    }

    void update(Snapshot snapshot) {
        setCharacter(hero);
        portrait.setCharacter(hero);
        TankView tank=snapshot.tanks().stream()
                .filter(candidate->candidate.player()==player)
                .findFirst().orElse(null);
        if(tank==null) {
            healthName.setText("");
            healthValue.setText("-- / --");
            energy.setText("--- / 100");
            effects.setText("—");
            setMeter(healthMeter,0);
            setMeter(energyMeter,0);
            pulse.setDisable(true);
            drawRadar(snapshot);
            return;
        }

        healthName.setText(Labels.tank(tank.type()));
        healthValue.setText(tank.hp()+" / "+tank.maxHp());
        setMeter(healthMeter,tank.maxHp()<=0?0:tank.hp()/(double)tank.maxHp());
        energy.setText(String.format("%03d  /  100",(int)tank.energy()));
        setMeter(energyMeter,tank.energy()/100.);
        String buffs=buffText(tank);
        effects.setText(buffs.isEmpty()?"—":buffs.replace("\n","   ·   "));
        pulse.setDisable(tank.energy()<Rules.PULSE_COST || snapshot.state()!=State.RUNNING || tank.hp()<=0);

        portrait.setLowHealth(tank.hp()>0&&tank.maxHp()>0&&tank.hp()/(double)tank.maxHp()<=.3);
        if(snapshot.state()==State.RESULT) {
            boolean won=snapshot.mode()==Mode.DUEL
                    ?snapshot.result()==(player==0?Result.P1_WIN:Result.P2_WIN)
                    :snapshot.result()==Result.VICTORY;
            boolean lost=snapshot.result()==Result.DEFEAT||snapshot.result()==Result.DRAW
                    ||(snapshot.mode()==Mode.DUEL&&snapshot.result()==(player==0?Result.P2_WIN:Result.P1_WIN));
            portrait.setVictory(won);
            portrait.setDefeat(lost);
        } else {
            portrait.setVictory(false);
            portrait.setDefeat(false);
        }
        drawRadar(snapshot);
    }

    void updateRadar(Snapshot snapshot) {
        drawRadar(snapshot);
    }

    private void drawRadar(Snapshot snapshot) {
        double width=radar.getWidth(),height=radar.getHeight();
        var g=radar.getGraphicsContext2D();
        g.clearRect(0,0,width,height);
        g.setFill(Color.web("#18251f"));g.fillRoundRect(0,0,width,height,6,6);
        g.setStroke(Color.web("#40594a"));g.setLineWidth(1);
        for(int x=16;x<width;x+=16) g.strokeLine(x,0,x,height);
        for(int y=16;y<height;y+=16) g.strokeLine(0,y,width,y);
        double cx=width/2,cy=height/2;
        g.setStroke(Color.web("#6d8d70"));g.strokeOval(cx-38,cy-38,76,76);
        g.strokeLine(cx,7,cx,height-7);g.strokeLine(22,cy,width-22,cy);

        TankView own=null;
        for(TankView tank:snapshot.tanks()) {
            if(tank.hp()<=0) continue;
            if(tank.player()==player) own=tank;
            double x=8+tank.x()/Rules.WIDTH*(width-16);
            double y=8+tank.y()/Rules.HEIGHT*(height-16);
            boolean mine=tank.player()==player;
            Color color=BattleRenderer.tankColor(snapshot.mode(),tank.player());
            g.setGlobalAlpha(mine?1:.42);
            g.setFill(color);
            double radius=tank.boss()?4.5:3.5;
            g.fillOval(x-radius,y-radius,radius*2,radius*2);
            if(mine) {
                g.setStroke(Color.WHITE);g.setLineWidth(1.4);
                g.strokeOval(x-6,y-6,12,12);
            }
        }
        g.setGlobalAlpha(1);
        double angle=own==null?0:Math.toRadians(own.angle());
        g.setStroke(Color.web("#c8ef64",.6));g.setLineWidth(1.3);
        g.strokeLine(cx,cy,cx+Math.cos(angle)*58,cy+Math.sin(angle)*58);
    }

    private static String buffText(TankView tank) {
        return (tank.shieldMs()>0?"护盾  "+(int)Math.ceil(tank.shieldMs()/1000.)+"s\n":"")
                +(tank.rapidMs()>0?"急速  "+(int)Math.ceil(tank.rapidMs()/1000.)+"s\n":"")
                +(tank.scatterMs()>0?"散射  "+(int)Math.ceil(tank.scatterMs()/1000.)+"s\n":"")
                +(tank.aimMs()>0?"预瞄  "+(int)Math.ceil(tank.aimMs()/1000.)+"s\n":"")
                +(tank.penetrateMs()>0?"穿透  "+(int)Math.ceil(tank.penetrateMs()/1000.)+"s\n":"");
    }

    private static Label label(String text,String style) {
        Label label=new Label(text);
        label.setWrapText(true);
        label.getStyleClass().add(style);
        return label;
    }

    private static void configureMeter(HBox meter,String id,String kind) {
        meter.setId(id);
        meter.getStyleClass().addAll("segment-meter",kind);
        for(int i=0;i<10;i++) {
            Region segment=new Region();
            segment.getStyleClass().add("meter-segment");
            HBox.setHgrow(segment,Priority.ALWAYS);
            meter.getChildren().add(segment);
        }
    }

    private static void setMeter(HBox meter,double progress) {
        int active=(int)Math.ceil(Math.max(0,Math.min(1,progress))*meter.getChildren().size());
        for(int i=0;i<meter.getChildren().size();i++) {
            var classes=meter.getChildren().get(i).getStyleClass();
            if(i<active) {
                if(!classes.contains("active")) classes.add("active");
            } else classes.remove("active");
        }
    }
}
