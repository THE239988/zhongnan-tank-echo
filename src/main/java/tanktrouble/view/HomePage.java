package tanktrouble.view;

import java.util.function.Consumer;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.effect.DropShadow;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import tanktrouble.model.data.GameData.Snapshot;
import tanktrouble.model.data.GameData.TankType;

/** Home menu and the completion boundary for the optional opening video/animation. */
final class HomePage extends StackPane {
    private final StackPane openingLayer=new StackPane();
    private final BorderPane menu=new BorderPane();
    /** Free space where the bouncing chibi travels; keeps it off the buttons and the copy. */
    private final Pane decoration=new Pane();
    private Animation openingAnimation;
    private MediaPlayer openingPlayer;
    private FadeTransition openingExit;
    private boolean playing;
    private Button openingSkip;
    private Button openingMute;
    private HBox openingControls;
    private boolean openingMuted;
    private Runnable openingCompletion;
    /**
     * Notified with {@code true} when an intro starts and {@code false} when it ends. The
     * application uses this to duck the menu music while the opening video plays its own audio.
     */
    private Consumer<Boolean> onOpeningChanged=playingNow->{};

    HomePage(Snapshot preview,Runnable start,Runnable multiplayer,Runnable settings,Runnable leaderboard,Runnable exit) {
        setId("home-page");
        Label brand=styled("中南坦克","brand");brand.setGraphic(MechaArtwork.icon("flag",24,true));brand.setGraphicTextGap(10);
        VBox identity=new VBox(3,brand,styled("CSU / PROJECT ECHO 02","subtitle"));
        Region headerSpace=new Region();HBox.setHgrow(headerSpace,Priority.ALWAYS);
        HBox header=new HBox(18,identity,headerSpace,
                command("多人作战","multiplayer-button","play",multiplayer,false),
                command("设置","settings-button","settings",settings,false),
                command("战绩榜","leaderboard-button","trophy",leaderboard,false),
                command("退出","exit-button","exit",exit,false));
        header.setAlignment(Pos.CENTER_LEFT);header.getStyleClass().add("home-header");
        Region signal=new Region();signal.getStyleClass().add("home-signal");
        HBox kicker=new HBox(10,signal,styled("PROJECT ECHO  /  回响计划","home-kicker"));kicker.setAlignment(Pos.CENTER_LEFT);
        Label wordmark=styled("中南坦克","home-title");
        Text echo=new Text("回响计划");echo.getStyleClass().add("home-title-outline");
        VBox title=new VBox(0,wordmark,echo);
        Label designation=styled("RESONANCE  /  COMBAT SYSTEM","home-designation");
        Label slogan=styled("反弹，即反击。","home-subtitle");
        VBox statement=new VBox(8,slogan,styled("让每一次折返，都成为下一击的能量。","home-tagline"));
        statement.getStyleClass().add("home-statement");
        TankType type=TankType.BALANCED;
        HBox specs=new HBox(24,
                specification("装甲强度",Integer.toString(type.hp),"HP",type.hp/15.0),
                specification("机动速度",Integer.toString((int)type.speed),"PX/S",type.speed/240.0),
                specification("基础装填",Integer.toString(type.cooldown),"MS",390.0/type.cooldown));
        specs.getStyleClass().add("home-specifications");
        VBox copy=new VBox(17,kicker,title,designation,statement,specs);
        copy.setAlignment(Pos.TOP_LEFT);copy.setMaxSize(430,Region.USE_PREF_SIZE);copy.setManaged(false);
        HomeScene artwork=new HomeScene();
        menu.visibleProperty().addListener((o,was,now)->artwork.setVisible(now));
        StackPane actionArea=new StackPane(artwork,copy) {
            @Override protected void layoutChildren() {
                super.layoutChildren();
                double sceneWidth=Math.min(1640,getWidth()),left=(getWidth()-sceneWidth)/2+36;
                boolean shortView=getHeight()<530;
                designation.setVisible(!shortView);designation.setManaged(!shortView);
                copy.setSpacing(shortView?11:17);
                copy.resize(430,copy.prefHeight(430));
                copy.relocate(left,Math.max(18,(getHeight()-copy.getHeight())*.34));
                if(decoration.isVisible()) decoration.relocate(left,getHeight()-decoration.getHeight()-12);
            }
        };
        actionArea.setMinSize(0,0);
        HBox modes=new HBox();
        String[] names={"单人突围","双人对决","无尽回响"},codes={"BREAKTHROUGH","LOCAL DUEL","ENDLESS ECHO"};
        for(int i=0;i<names.length;i++) {
            HBox item=new HBox(13,styled("0"+(i+1),"rank"),new VBox(5,styled(names[i],"item-title"),styled(codes[i],"subtitle")));
            item.setAlignment(Pos.CENTER_LEFT);item.getStyleClass().add("home-mode");HBox.setHgrow(item,Priority.ALWAYS);modes.getChildren().add(item);
        }
        HBox.setHgrow(modes,Priority.ALWAYS);
        Button launch=command("开始游戏","home-start","play",start,true);launch.setMinWidth(265);launch.setMaxHeight(Double.MAX_VALUE);
        HBox bottom=new HBox(modes,launch);bottom.getStyleClass().add("home-bottom");
        HBox footer=new HBox(new Label("CSU  /  麓山训练场"),new Region(),new Label("TANK TROUBLE   ·   02"));
        HBox.setHgrow(footer.getChildren().get(1),Priority.ALWAYS);footer.getStyleClass().add("home-footer");
        menu.setTop(header);menu.setCenter(actionArea);menu.setBottom(new VBox(bottom,footer));
        decoration.setId("home-decoration");
        decoration.setMouseTransparent(true);
        decoration.pickOnBoundsProperty().set(false);
        decoration.setMaxSize(240,240);
        decoration.visibleProperty().bind(actionArea.heightProperty().greaterThan(800));
        javafx.scene.shape.Rectangle clip=new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(decoration.widthProperty());clip.heightProperty().bind(decoration.heightProperty());decoration.setClip(clip);
        StackPane.setAlignment(decoration,Pos.BOTTOM_LEFT);StackPane.setMargin(decoration,new Insets(0,0,10,36));
        actionArea.getChildren().add(decoration);
        openingLayer.setVisible(false);openingLayer.setManaged(false);
        openingLayer.setId("opening-layer");
        openingSkip=new Button("跳过开场  →");
        openingSkip.setId("opening-skip");
        openingSkip.getStyleClass().addAll("opening-control","opening-skip");
        openingSkip.setOnAction(e->skipOpening());
        openingMute=new Button("音效  开");
        openingMute.setId("opening-mute");
        openingMute.getStyleClass().add("opening-control");
        openingMute.setOnAction(e->{
            openingMuted=!openingMuted;
            openingMute.setText(openingMuted?"音效  关":"音效  开");
            if(openingPlayer!=null) openingPlayer.setMute(openingMuted);
        });
        openingControls=new HBox(8,openingSkip,openingMute);
        openingControls.getStyleClass().add("opening-controls");
        openingControls.setMaxSize(Region.USE_PREF_SIZE,Region.USE_PREF_SIZE);
        StackPane.setAlignment(openingControls,Pos.BOTTOM_RIGHT);
        StackPane.setMargin(openingControls,new Insets(0,24,24,0));
        openingLayer.getChildren().add(openingControls);
        getChildren().addAll(menu,openingLayer);
    }
    private static VBox specification(String name,String value,String unit,double amount) {
        HBox number=new HBox(5,styled(value,"home-spec-value"),styled(unit,"home-spec-unit"));number.setAlignment(Pos.BASELINE_LEFT);
        ProgressBar meter=new ProgressBar(amount);meter.setPrefWidth(105);meter.getStyleClass().add("home-spec-meter");
        VBox spec=new VBox(7,styled(name,"home-spec-name"),number,meter);spec.setMinWidth(105);return spec;
    }
    private static Label styled(String text,String style) {Label label=new Label(text);label.getStyleClass().add(style);return label;}
    private Button command(String text,String id,String icon,Runnable action,boolean primary) {
        Button button=new Button(text,MechaArtwork.icon(icon,primary?22:17,!primary));button.setId(id);
        button.setGraphicTextGap(primary?18:8);button.setTooltip(new Tooltip(text));
        button.setOnAction(e->action.run());
        button.getStyleClass().add("home-command");if(primary) button.getStyleClass().add("home-primary");
        return button;
    }
    StackPane openingLayer() {return openingLayer;}

    /**
     * Places a bouncing sprite in the empty middle of the menu.
     *
     * <p>Added to a pass-through pane rather than into the layout: decoration that takes space
     * would shove the menu around, and decoration that accepts clicks would swallow button
     * presses it happens to drift over.
     */
    void installDecoration(Region sprite) {
        decoration.getChildren().setAll(sprite);
        sprite.prefWidthProperty().bind(decoration.widthProperty());
        sprite.prefHeightProperty().bind(decoration.heightProperty());
    }
    void setOnOpeningChanged(Consumer<Boolean> listener) {onOpeningChanged=listener;}

    /**
     * Whether an intro is on screen right now.
     *
     * <p>The opening layer is created before the first {@code refresh()}, and starting the video is
     * asynchronous, so a listener installed after the fact would never hear about an opening that
     * had already begun. Callers ask this once at startup to learn the state they missed.
     */
    boolean isOpeningActive() {return playing;}
    void setOpeningAnimation(Animation animation) {cancelOpening();openingAnimation=animation;}
    void setOpeningVideo(MediaView video,MediaPlayer player) {
        cancelOpening();
        openingPlayer=player;
        openingLayer.getChildren().setAll(video,openingControls);
        video.setPreserveRatio(true);video.setSmooth(true);
        video.fitWidthProperty().bind(openingLayer.widthProperty());
        video.fitHeightProperty().bind(openingLayer.heightProperty());
    }
    void playVideo(Runnable completed) {
        if(openingPlayer==null) {completed.run();return;}
        if(playing) return;
        beginOpening();
        openingCompletion=completed;
        Runnable finish=()->{if(!playing)return;cancelOpening();completed.run();};
        openingPlayer.setOnEndOfMedia(()->{
            if(!playing || openingExit!=null) return;
            menu.setVisible(true);
            openingExit=new FadeTransition(javafx.util.Duration.millis(500),openingLayer);
            openingExit.setFromValue(1);openingExit.setToValue(0);
            openingExit.setOnFinished(event->finish.run());
            openingExit.play();
        });
        openingPlayer.setOnError(finish);
        openingPlayer.seek(javafx.util.Duration.ZERO);openingPlayer.play();
    }
    void playOpening(Runnable completed) {
        if(playing) return;
        if(openingAnimation==null) {completed.run();return;}
        beginOpening();
        openingCompletion=completed;
        openingAnimation.setOnFinished(event->{
            cancelOpening();completed.run();
        });
        openingAnimation.playFromStart();
    }
    private void beginOpening() {
        playing=true;menu.setDisable(true);menu.setVisible(false);
        openingLayer.setOpacity(1);
        openingLayer.setManaged(true);openingLayer.setVisible(true);
        onOpeningChanged.accept(true);
    }

    private void skipOpening() {
        if(!playing) return;
        Runnable done=openingCompletion;
        cancelOpening();
        if(done!=null) done.run();
    }

    /**
     * Ends any intro and restores the menu.
     *
     * <p>Also the single point where the opening video's {@link MediaPlayer} is released. Stopping
     * the player leaves its native decoder and buffers allocated, so a player that is stopped and
     * replaced on every visit to the home page accumulates native resources until disposed.
     */
    void cancelOpening() {
        if(openingExit!=null) {
            openingExit.stop();openingExit.setOnFinished(null);openingExit=null;
        }
        if(openingAnimation!=null) {openingAnimation.stop();openingAnimation.setOnFinished(null);}
        if(openingPlayer!=null) {
            openingPlayer.setOnEndOfMedia(null);openingPlayer.setOnError(null);
            openingPlayer.stop();openingPlayer.dispose();
            openingPlayer=null;
        }
        playing=false;menu.setDisable(false);menu.setVisible(true);
        openingLayer.setVisible(false);openingLayer.setManaged(false);
        openingLayer.setOpacity(1);
        onOpeningChanged.accept(false);
        openingMuted=false;
        if(openingMute!=null) openingMute.setText("音效  开");
        openingCompletion=null;
    }
}
