package tanktrouble.view;

import java.nio.file.*;
import java.util.*;
import javafx.animation.AnimationTimer;
import javafx.beans.binding.Bindings;
import javafx.application.Application;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.canvas.Canvas;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.paint.Color;
import javafx.scene.input.MouseButton;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCombination;
import javafx.stage.Stage;
import javafx.stage.Screen;
import tanktrouble.art.ArtAssets;
import tanktrouble.art.ChibiBall;
import tanktrouble.art.PortraitView;
import tanktrouble.audio.AudioManager;
import tanktrouble.voice.VoiceLines;
import tanktrouble.controller.GameController;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.persistence.LeaderboardRepository;

public final class TankTroubleApp extends Application {
    private enum Page { HOME, PREPARATION, SETTINGS, LEADERBOARD, BATTLE }
    private final TankGameModel model=new TankGameModel();
    private final GameController controller=new GameController(model);
    private final LeaderboardRepository leaderboard=new LeaderboardRepository(Path.of(System.getProperty("tanktrouble.dataDir","data"),"leaderboard.properties"));
    private final AudioManager audio=new AudioManager();
    private final BorderPane root=new BorderPane();
    private final VBox sidebar=new VBox(12);
    private final VBox sidebarActions=new VBox(8);
    private final StackPane field=new StackPane();
    private final Canvas canvas=new Canvas(Rules.WIDTH,Rules.HEIGHT);
    private final BattleRenderer renderer=new BattleRenderer(canvas);
    private final Label status=new Label(),score=new Label(),wave=new Label(),wallet=new Label(),notice=new Label();
    /** Armour readout split in two: the name yields space so the numbers never get ellipsized. */
    private final Label healthName=new Label(),healthValue=new Label(),energy=new Label(),effects=new Label(),situation=new Label();
    private final Label duelCompact=new Label();
    private final HBox hpMeter=new HBox(3),energyMeter=new HBox(3);
    private Canvas tacticalRadar;
    private final Button pauseButton=button("Ⅱ","暂停 / Esc",()->{model.pause();refresh();});
    private Mode selectedMode=Mode.SINGLE;
    private final TankType[] selectedTank={TankType.BALANCED,TankType.BALANCED};
    private State renderedState;
    private boolean terrain=true,savedResult;
    private Page page=Page.HOME,leaderboardReturn=Page.HOME;
    private final Path settingsFile=Path.of(System.getProperty("tanktrouble.dataDir","data"),"settings.properties");
    private HomePage homePage;
    private MultiplayerWindow multiplayerWindow;
    private BorderPane combatPage;
    private BorderPane side;
    private VBox battle;
    private HBox hud;
    private final HBox battleFooter=new HBox(10);
    private double deploymentX=Double.NaN,deploymentY=Double.NaN;
    private Stage stage;
    private Snapshot preview;
    private AnimationTimer timer;
    /** Hero art, shared by the battle panel and the home page decoration. */
    private final ArtAssets art=new ArtAssets();
    private final VoiceLines voice=new VoiceLines();
    /** 第二席的语音。原先全项目只有一个 VoiceLines，双人时 P2 的开火与受击整个不发声。 */
    private final VoiceLines voiceTwo=new VoiceLines();
    private final PortraitView portrait=new PortraitView();
    private final ChibiBall chibi=new ChibiBall();
    /** Which character the player picked. Kept apart from the tank: one is looks, one is stats. */
    private final ArtAssets.Hero[] selectedCharacter={ArtAssets.Hero.XIAOXIANG,ArtAssets.Hero.XIAOXIANG};
    private BorderPane portraitColumn;
    private PlayerPanel playerOne;
    private PlayerPanel playerTwo;
    /**
     * The character column's lower half, rebuilt on every refresh like the sidebar: what it holds
     * depends on the mode and the state, and the widgets it shows are the same ones the sidebar
     * used to borrow.
     */
    private VBox driverPanel;
    /** Below this window width the artwork steps aside so the battlefield keeps its size. */
    private static final double PORTRAIT_MIN_WIDTH=1350;
    /** Two duel columns take more room than one, so they give up earlier. */
    private static final double DUEL_PORTRAIT_MIN_WIDTH=1450;
    /** Width reserved for the artwork column when it is showing. */
    private static final double PORTRAIT_COLUMN=250;

    @Override public void start(Stage stage) {
        this.stage=stage;
        GameSettings settings=GameSettings.defaults();
        try {settings=GameSettings.load(settingsFile);} catch(java.io.IOException | IllegalArgumentException e) {notice.setText("无法读取设置："+e.getMessage());}
        terrain=settings.terrain();
        audio.setSfxVolume(settings.sfxVolume());audio.setMusicVolume(settings.musicVolume());
        voice.setVolume(settings.sfxVolume());voiceTwo.setVolume(settings.sfxVolume());
        updatePreview();
        root.getStyleClass().add("root-shell");
        healthName.getStyleClass().add("health-readout");healthValue.getStyleClass().add("health-readout");energy.getStyleClass().add("energy-readout");
        healthName.getStyleClass().add("combat-armor-name");healthValue.getStyleClass().add("combat-armor-value");
        effects.getStyleClass().add("effects-readout");wallet.getStyleClass().add("wallet");
        situation.getStyleClass().add("situation");
        // Added once here rather than in the builders: those run on every refresh, and appending
        // the same style class over and over grows the list without changing how it looks.
        configureMeter(hpMeter,"hp-meter","armor-meter");
        configureMeter(energyMeter,"energy-meter","energy-meter");
        root.setTop(header());
        sidebar.setMinWidth(264);sidebar.setPrefWidth(278);sidebar.setMaxWidth(278);sidebar.getStyleClass().add("sidebar");
        ScrollPane configuration=new ScrollPane(sidebar);configuration.setFitToWidth(true);configuration.setFitToHeight(true);
        configuration.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);configuration.setPrefWidth(294);configuration.setMinWidth(294);
        configuration.getStyleClass().add("configuration-scroll");
        sidebarActions.getStyleClass().add("sidebar-actions");
        side=new BorderPane(configuration);side.setBottom(sidebarActions);side.setPrefWidth(294);side.getStyleClass().add("side-shell");
        side.setId("combat-sidebar");
        combatPage=new BorderPane();combatPage.setLeft(side);
        battle=new VBox(14);battle.setPadding(new Insets(18,20,16,18));battle.getStyleClass().add("battle-area");
        hud=new HBox(18,status,spacer(),duelCompact,wave,score,wallet);hud.setAlignment(Pos.CENTER_LEFT);hud.getStyleClass().add("hud");
        duelCompact.setId("duel-compact");duelCompact.setVisible(false);duelCompact.setManaged(false);
        VBox.setVgrow(field,Priority.ALWAYS);field.setMinSize(0,0);
        canvas.setManaged(false);field.getChildren().add(canvas);
        canvas.setId("battle-canvas");
        canvas.setOnMouseMoved(event->{
            deploymentX=event.getX()*Rules.WIDTH/canvas.getWidth();
            deploymentY=event.getY()*Rules.HEIGHT/canvas.getHeight();
            if(model.snapshot().state()==State.DEPLOYING) {
                notice.setText(model.canDeployTurret(deploymentX,deploymentY)?"":"此位置无法部署");
                drawFrame(System.nanoTime()/1_000_000_000.);
            }
        });
        canvas.setOnMouseExited(event->{deploymentX=deploymentY=Double.NaN;});
        canvas.setOnMouseClicked(event->{
            if(model.snapshot().state()!=State.DEPLOYING) return;
            if(event.getButton()==MouseButton.SECONDARY) {model.cancelDeployment();refresh();}
            else if(event.getButton()==MouseButton.PRIMARY) {
                double x=event.getX()*Rules.WIDTH/canvas.getWidth(),y=event.getY()*Rules.HEIGHT/canvas.getHeight();
                if(model.deployTurret(x,y)) refresh();
                else notice.setText("此位置无法部署");
            }
            event.consume();
        });
        field.widthProperty().addListener((o,a,b)->resizeCanvas());field.heightProperty().addListener((o,a,b)->resizeCanvas());
        battle.getChildren().addAll(hud,field,battleFooter);combatPage.setCenter(battle);
        homePage=new HomePage(preview,this::beginPreparation,this::showMultiplayer,this::showSettings,this::showLeaderboard,stage::close);
        homePage.installDecoration(chibi);
        // The intro carries its own soundtrack, so menu music steps aside while it plays.
        homePage.setOnOpeningChanged(active->audio.setMusicPaused(active));
        installOpeningVideo(Path.of("."));
        Scene scene=new Scene(root,1524,860);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/mecha.css")).toExternalForm());
        stage.setFullScreenExitKeyCombination(KeyCombination.NO_MATCH);
        scene.addEventFilter(KeyEvent.KEY_PRESSED,event->{
            if(event.getCode()==KeyCode.ESCAPE && page!=Page.BATTLE) {
                if(stage.isFullScreen()) {stage.setFullScreen(false);event.consume();return;}
                if(page==Page.LEADERBOARD) closeLeaderboard();
                else if(page==Page.SETTINGS || page==Page.PREPARATION) returnHome();
                else homePage.cancelOpening();
                event.consume();
            }
        });
        // Both the panel and the home decoration read from the same loaded set.
        PortraitView.useAssets(art);ChibiBall.useAssets(art);
        playerOne=new PlayerPanel(0,model,selectedCharacter[0]);
        playerTwo=new PlayerPanel(1,model,selectedCharacter[1]);
        // The panel is only as tall as the drawing it holds, so the column is filled by what sits
        // under it - the driver's readouts - rather than by stretching the picture.
        StackPane portraitInner=new StackPane(portrait);portraitInner.getStyleClass().add("portrait-inner");
        StackPane portraitFrame=new StackPane(portraitInner);portraitFrame.getStyleClass().add("portrait-frame");
        for(Pos position:List.of(Pos.TOP_LEFT,Pos.TOP_RIGHT,Pos.BOTTOM_LEFT,Pos.BOTTOM_RIGHT)) {
            Region corner=new Region();corner.setMouseTransparent(true);corner.setMinSize(16,16);corner.setMaxSize(16,16);
            corner.getStyleClass().addAll("combat-frame-corner","corner-"+position.name().toLowerCase(java.util.Locale.ROOT));
            StackPane.setAlignment(corner,position);portraitFrame.getChildren().add(corner);
        }
        driverPanel=new VBox(10);driverPanel.setId("driver-panel");
        VBox portraitHolder=new VBox(10,combatHeading("驾驶员终端","PILOT / 01"),portraitFrame,driverPanel);
        portraitHolder.setAlignment(Pos.TOP_CENTER);portraitHolder.setFillWidth(true);
        portraitHolder.setPadding(new Insets(12,12,0,12));
        // Scrollable for the same reason the sidebar is: the readouts underneath include the pulse
        // button, and a short window must not put a working control out of reach.
        ScrollPane driverScroll=new ScrollPane(portraitHolder);
        driverScroll.setFitToWidth(true);driverScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        driverScroll.getStyleClass().add("configuration-scroll");
        portraitColumn=new BorderPane(driverScroll);
        portraitColumn.setId("portrait-column");
        portraitColumn.setPrefWidth(PORTRAIT_COLUMN);portraitColumn.setMinWidth(0);
        combatPage.setRight(portraitColumn);
        // Driven by the listener alone. Calling this from refresh() does not work: refresh runs
        // before the scene exists, so the width reads as zero and the panel is judged too narrow
        // and hidden - and nothing re-evaluates until an unrelated action forces a redraw.
        scene.widthProperty().addListener((o,was,now)->applyPortraitVisibility(now.doubleValue()));
        applyPortraitVisibility(scene.getWidth());
        controller.attach(scene,this::refresh,()->page==Page.BATTLE);
        stage.getIcons().setAll(windowIcon());
        stage.setTitle("重生之我在中南大学打坦克 · 回响计划");stage.setScene(scene);
        stage.setMinWidth(980);stage.setMinHeight(700);
        var screen=Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(1524,screen.getWidth()-32));stage.setHeight(Math.min(890,screen.getHeight()-32));
        stage.focusedProperty().addListener((o,a,focused)->{if(!focused) {controller.loseFocus();if(page==Page.BATTLE) refresh();}});
        stage.setOnCloseRequest(event->{controller.resetClock();model.exit();if(timer!=null) timer.stop();if(multiplayerWindow!=null) multiplayerWindow.close();});
        stage.show();stage.setFullScreen(settings.fullscreen());refresh();
        homePage.playVideo(this::refresh);
        timer=new AnimationTimer() {
            @Override public void handle(long now) {
                // Drain before advancing: a paused model still accepts input events, and draining
                // once per frame keeps each occurrence tied to exactly one sound.
                List<GameEvent> events=model.drainEvents();
                audio.play(events);
                reactTo(events);
                controller.frame(now);
                Snapshot s=model.snapshot();
                if(s.state()!=renderedState) {refresh();announcePortrait(s);}
                drawFrame(now/1_000_000_000.);
                updateHud(s);
            }
        };
        timer.start();
        if(System.getProperty("tanktrouble.autoJoin")!=null) javafx.application.Platform.runLater(this::showMultiplayer);
    }
    private Node header() {
        Label mark=label("中南坦克","brand");mark.setGraphic(MechaArtwork.icon("flag",25,true));mark.setGraphicTextGap(12);
        VBox title=new VBox(3,mark,label("CSU / PROJECT ECHO 02","subtitle"));
        Label edition=label("回响计划","edition");
        Button board=button("战绩榜","本地排行榜",this::showLeaderboard);board.setId("leaderboard-button");
        board.setDisable(model.snapshot().state()==State.DEPLOYING);
        Button full=button("⛶","切换全屏",()->stage.setFullScreen(!stage.isFullScreen()));
        pauseButton.setId("pause-button");
        pauseButton.setText("");pauseButton.setGraphic(MechaArtwork.icon("pause",19,true));pauseButton.getStyleClass().setAll("button","icon-button");pauseButton.setAccessibleText("暂停");
        full.setText("");full.setGraphic(MechaArtwork.icon("fullscreen",19,true));full.getStyleClass().add("icon-button");full.setAccessibleText("切换全屏");
        board.setGraphic(MechaArtwork.icon("trophy",18,true));board.setGraphicTextGap(8);
        HBox header=new HBox(18,title,edition,spacer(),board,full,pauseButton);
        header.setAlignment(Pos.CENTER_LEFT);header.setPadding(new Insets(13,24,14,24));header.getStyleClass().add("header");
        return header;
    }
    private void beginPreparation() {
        homePage.playOpening(()->{model.selectMode(selectedMode);page=Page.PREPARATION;refresh();});
    }

    static Path openingVideoPath(Path projectDir) {
        return projectDir.resolve("assets").resolve("opening.mp4");
    }

    private void installOpeningVideo(Path projectDir) {
        Path videoPath=openingVideoPath(projectDir);
        if(!Files.isRegularFile(videoPath)) return;
        try {
            MediaPlayer player=new MediaPlayer(new Media(videoPath.toAbsolutePath().toUri().toString()));
            homePage.setOpeningVideo(new MediaView(player),player);
        } catch(IllegalArgumentException | IllegalStateException ignored) {
            // A missing/unsupported optional intro must never prevent the game from starting.
        }
    }

    /**
     * Turns simulation events into something the character panel shows.
     *
     * <p>Only the player's own actions move their portrait. An enemy firing, or the player being
     * hit by someone else, is already represented by hit(); reacting to enemy shots too would keep
     * the panel twitching through every exchange.
     */
    private void reactTo(List<GameEvent> events) {
        for(GameEvent event:events) {
            int seat=event.player();
            if(event.type()==GameEventType.BOUNCE) {
                renderer.addBounce(seat,event.x(),event.y());
                continue;
            }
            boolean noShot=(event.type()==GameEventType.HIT_ENEMY
                    ||event.type()==GameEventType.HIT_PLAYER)
                    &&Double.isNaN(event.incomingAngle());
            if(!noShot)
                renderer.addEffect(event.type(),seat,event.x(),event.y(),
                        event.sourceSeat(),event.incomingAngle());
            if(playerOne!=null) playerOne.react(event);
            if(playerTwo!=null) playerTwo.react(event);
            if(seat<0||seat>1) continue;
            // 按座位选发声的那一位。原先这里只放行 seat 0，双人时 P2 的语音整个被丢掉。
            VoiceLines speaker=seat==0?voice:voiceTwo;
            switch(event.type()) {
                case PLAYER_FIRE -> {if(seat==0) portrait.attack();speaker.say(VoiceLines.Line.ATTACK);}
                case HIT_PLAYER -> {if(seat==0) portrait.hit();speaker.say(VoiceLines.Line.HIT);}
                default -> { }
            }
        }
    }

    /**
     * Points the panel at the chosen character and applies the lasting conditions it shows:
     * wounded, won, lost.
     *
     * <p>Called from {@link #refresh()} rather than only from the frame loop's state-change
     * branch. refresh() stamps {@code renderedState} before the loop compares it, so a transition
     * that goes through refresh - which is every player-driven one, including entering the battle
     * - never reaches that branch. Without this the panel kept a null character and drew nothing
     * until some later, unrelated state change happened to slip past refresh().
     *
     * <p>Everything here is idempotent and silent, so rebuilding the page mid-battle is free.
     */
    private void syncPortrait(Snapshot snapshot) {
        portrait.setCharacter(selectedCharacter[0]);
        voice.setHero(selectedCharacter[0]);voiceTwo.setHero(selectedCharacter[1]);
        if(playerOne!=null) {
            playerOne.setCharacter(selectedCharacter[0]);
            playerOne.update(snapshot);
        }
        if(playerTwo!=null) {
            playerTwo.setCharacter(selectedCharacter[1]);
            playerTwo.update(snapshot);
        }
        if(snapshot.state()==State.HOME) {portrait.reset();return;}
        snapshot.tanks().stream().filter(t->t.player()==0).findFirst().ifPresent(t->{
            boolean low=t.hp()>0&&t.maxHp()>0&&t.hp()/(double)t.maxHp()<=0.3;
            portrait.setLowHealth(low);
        });
        if(snapshot.state()==State.RESULT) {
            boolean won=snapshot.result()==Result.VICTORY||snapshot.result()==Result.P1_WIN;
            boolean lost=snapshot.result()==Result.DEFEAT||snapshot.result()==Result.P2_WIN||snapshot.result()==Result.DRAW;
            portrait.setVictory(won);portrait.setDefeat(lost);
        } else {
            // Outside the result screen there is no outcome to show. Only HOME used to clear
            // these, so a defeat greyed the panel out and it stayed grey through every battle
            // started from the result screen's own restart button.
            portrait.setVictory(false);
            portrait.setDefeat(false);
        }
    }

    /**
     * The spoken half of the panel update.
     *
     * <p>Kept on the state-change branch alone: the opening and closing lines belong to a
     * transition, and replaying them from every rebuild would have the character announcing the
     * result again each time the player opened the shop.
     */
    private void announcePortrait(Snapshot snapshot) {
        syncPortrait(snapshot);
        if(snapshot.state()==State.RESULT) {
            boolean won=snapshot.result()==Result.VICTORY||snapshot.result()==Result.P1_WIN;
            boolean lost=snapshot.result()==Result.DEFEAT||snapshot.result()==Result.P2_WIN||snapshot.result()==Result.DRAW;
            if(won||lost) {
                voice.announceResult(won);
                // 双人时两名战姬各自播自己的胜负。
                if(snapshot.mode()==Mode.DUEL) voiceTwo.announceResult(snapshot.result()==Result.P2_WIN);
            }
        }
        if(snapshot.state()==State.RUNNING) {voice.beginBattle();voiceTwo.beginBattle();}
    }

    /**
     * Shows or hides the artwork column.
     *
     * <p>The battlefield keeps its size either way: the column is given up first so a narrow window
     * loses the decoration rather than squashing the thing being played.
     */
    private void applyPortraitVisibility(double sceneWidth) {
        boolean duel=page==Page.BATTLE&&model.snapshot().mode()==Mode.DUEL;
        if(duel&&playerOne!=null&&playerTwo!=null) {
            boolean show=sceneWidth>=DUEL_PORTRAIT_MIN_WIDTH;
            portrait.stop();
            for(PlayerPanel panel:List.of(playerOne,playerTwo)) {
                panel.setVisible(show);panel.setManaged(show);
                panel.setPrefWidth(show?PORTRAIT_COLUMN:0);
                if(show) panel.start(); else panel.stop();
            }
            return;
        }
        boolean show=sceneWidth>=PORTRAIT_MIN_WIDTH&&page==Page.BATTLE;
        if(portraitColumn!=null) {
            portraitColumn.setVisible(show);portraitColumn.setManaged(show);
            portraitColumn.setPrefWidth(show?PORTRAIT_COLUMN:0);
        }
        if(playerOne!=null) playerOne.stop();
        if(playerTwo!=null) playerTwo.stop();
        if(show) portrait.start(); else portrait.stop();
    }

    private void returnHome() {homePage.cancelOpening();model.home();page=Page.HOME;refresh();}
    private void showMultiplayer() {
        if(multiplayerWindow==null || !multiplayerWindow.isShowing()) multiplayerWindow=new MultiplayerWindow();
        multiplayerWindow.show();
    }
    private void drawFrame(double clock) {
        if(page!=Page.PREPARATION && page!=Page.BATTLE) return;
        Snapshot s=model.snapshot();renderer.draw(page==Page.PREPARATION?preview:s,clock);
        if(page==Page.BATTLE&&s.mode()==Mode.DUEL) {
            playerOne.updateRadar(s);playerTwo.updateRadar(s);
        } else if(page==Page.BATTLE&&tacticalRadar!=null) updateTacticalRadar(tacticalRadar,s,0);
        if(s.state()==State.DEPLOYING && Double.isFinite(deploymentX))
            renderer.drawDeployment(deploymentX,deploymentY,model.canDeployTurret(deploymentX,deploymentY));
    }
    private void resizeCanvas() {
        double scale=Math.max(.1,Math.min(field.getWidth()/Rules.WIDTH,field.getHeight()/Rules.HEIGHT));
        canvas.setWidth(Rules.WIDTH*scale);canvas.setHeight(Rules.HEIGHT*scale);
        canvas.relocate((field.getWidth()-canvas.getWidth())/2,(field.getHeight()-canvas.getHeight())/2);
    }
    /**
     * Music for the page currently shown. Battle keeps its own track across pause, shop and
     * deployment, so opening a modal never interrupts the music.
     */
    private AudioManager.Track activeTrack() {
        if(page!=Page.BATTLE) return AudioManager.Track.MENU;
        return switch(model.snapshot().state()) {
            case RUNNING,PAUSED,SHOP,DEPLOYING,UPGRADE,RESULT -> AudioManager.Track.BATTLE;
            default -> AudioManager.Track.MENU;
        };
    }
    public void refresh() {
        Snapshot snapshot=model.snapshot();renderedState=snapshot.state();
        // Before the page is settled: the panel has to be showing the right drawing by the time
        // the battle page is laid out, not one state change later.
        syncPortrait(snapshot);
        audio.setTrack(activeTrack());
        if(page==Page.HOME) chibi.start(); else chibi.stop();
        if(page==Page.SETTINGS || page==Page.LEADERBOARD) return;
        if(snapshot.state()==State.HOME) page=Page.HOME;
        else if(snapshot.state()==State.READY) page=Page.PREPARATION;
        else page=Page.BATTLE;
        if(page==Page.HOME) {
            if(playerOne!=null) playerOne.stop();
            if(playerTwo!=null) playerTwo.stop();
            root.setTop(null);root.setCenter(homePage);return;
        }
        if(snapshot.state()==State.READY) {
            if(playerOne!=null) playerOne.stop();
            if(playerTwo!=null) playerTwo.stop();
            showSelectionPage();return;
        }
        boolean duel=page==Page.BATTLE&&snapshot.mode()==Mode.DUEL;
        if(duel) {
            combatPage.setLeft(playerOne);
            combatPage.setRight(playerTwo);
        } else {
            combatPage.setLeft(side);
            combatPage.setRight(portraitColumn);
        }
        // Evaluated here as well as from the width listener, and only once page is settled: moving
        // between the menu and a battle does not change the window size, so a listener alone never
        // fires on the transition, and reading page before it is assigned judges the wrong page.
        applyPortraitVisibility(stage==null||stage.getScene()==null?0:stage.getScene().getWidth());
        root.setTop(header());root.setCenter(combatPage);
        combatPage.setId(page==Page.PREPARATION?"preparation-page":"battle-page");
        hud.setVisible(page==Page.BATTLE);hud.setManaged(page==Page.BATTLE);
        while(field.getChildren().size()>1) field.getChildren().remove(1);
        sidebar.getChildren().clear();sidebarActions.getChildren().clear();notice.setText("");
        driverPanel.getChildren().clear();
        if(!duel) {
            switch(snapshot.state()) {
                default -> battleSidebar(snapshot);
            }
            driverPanel(snapshot);
        } else {
            playerOne.update(snapshot);
            playerTwo.update(snapshot);
        }
        fillBattleFooter(snapshot);
        if(snapshot.state()==State.PAUSED) showPause();
        if(snapshot.state()==State.SHOP) showShop();
        if(snapshot.state()==State.UPGRADE) showUpgrades();
        if(snapshot.state()==State.RESULT) showResult(snapshot);
        if(snapshot.state()==State.DEPLOYING) {
            canvas.setCursor(Cursor.CROSSHAIR);
            Button cancel=button("取消部署","取消部署 / Esc",()->{model.cancelDeployment();refresh();});
            cancel.setId("cancel-deployment");sidebarActions.getChildren().addAll(cancel,notice);
        } else canvas.setCursor(Cursor.DEFAULT);
        pauseButton.setDisable(snapshot.state()!=State.RUNNING);
        controller.resetClock();updateHud(snapshot);drawFrame(System.nanoTime()/1_000_000_000.);
    }

    /** Dedicated preflight page: choices are confirmed before the battle model starts. */
    /**
     * 标题栏和任务栏用的图标。
     *
     * <p>项目现有的字形（flag / play / trophy …）都是描边线条，缩到标题栏那种 16px 会糊成
     * 一团，所以这里单独画一个实心坦克剪影，小尺寸下也分得清。
     */
    private static javafx.scene.image.Image windowIcon() {
        // 直接写像素，不走 Canvas.snapshot()：那个 API 对未挂进场景的节点行为不确定。
        int size=64;
        javafx.scene.image.WritableImage image=new javafx.scene.image.WritableImage(size,size);
        javafx.scene.image.PixelWriter px=image.getPixelWriter();
        javafx.scene.paint.Color backing=javafx.scene.paint.Color.web("#16283a");
        javafx.scene.paint.Color body=javafx.scene.paint.Color.web("#00d4ff");
        for(int y=0;y<size;y++) for(int x=0;x<size;x++) px.setColor(x,y,backing);
        for(int y=42;y<57;y++) for(int x=5;x<59;x++) px.setColor(x,y,body);    // 履带
        for(int y=46;y<53;y++) for(int x=9;x<55;x++) px.setColor(x,y,backing); // 履带内衬
        for(int y=28;y<43;y++) for(int x=12;x<52;x++) px.setColor(x,y,body);   // 车体
        for(int y=17;y<29;y++) for(int x=24;x<44;x++) px.setColor(x,y,body);   // 炮塔
        for(int y=20;y<25;y++) for(int x=43;x<61;x++) px.setColor(x,y,body);   // 炮管
        return image;
    }

    private void showSelectionPage() {
        updatePreview();
        page=Page.PREPARATION;
        VBox content=menuPage("出击准备","preparation-page");
        GridPane columns=new GridPane();columns.setHgap(28);columns.setVgap(24);columns.getStyleClass().add("preparation-grid");
        VBox modeBox=new VBox(12,label("01  /  作战配置","eyebrow"));
        modeBox.setMinWidth(245);modeBox.setMinHeight(Region.USE_PREF_SIZE);modeBox.setMaxWidth(Double.MAX_VALUE);modeBox.getStyleClass().add("preparation-mode");
        ToggleGroup modes=new ToggleGroup();
        for(Mode mode:List.of(Mode.SINGLE,Mode.DUEL,Mode.ENDLESS)) {
            ToggleButton choice=new ToggleButton(Labels.mode(mode));choice.setId("mode-"+mode.name());choice.setMaxWidth(Double.MAX_VALUE);
            choice.getStyleClass().add("mode-choice");choice.setGraphicTextGap(12);
            String code=switch(mode) {case SINGLE->"01 / BREAKTHROUGH";case DUEL->"02 / LOCAL DUEL";default->"03 / ENDLESS ECHO";};
            VBox caption=new VBox(5,label(Labels.mode(mode),"prep-mode-name"),label(code,"prep-mode-code"));
            caption.setMaxHeight(Region.USE_PREF_SIZE);
            HBox graphic=new HBox(14,MechaArtwork.icon(mode==Mode.ENDLESS?"flag":"play",20,true),caption);graphic.setAlignment(Pos.CENTER_LEFT);
            graphic.setMaxHeight(Region.USE_PREF_SIZE);choice.setMinHeight(82);choice.setPrefHeight(82);choice.setMaxHeight(82);
            choice.setGraphic(graphic);choice.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);choice.setAccessibleText(Labels.mode(mode));
            choice.setToggleGroup(modes);choice.setSelected(selectedMode==mode);
            choice.setTooltip(new Tooltip(modeTooltip(mode)));
            choice.selectedProperty().addListener((o,oldValue,selected)->{if(selected && selectedMode!=mode){selectedMode=mode;model.selectMode(mode);showSelectionPage();}});
            modeBox.getChildren().add(choice);
        }
        CheckBox ground=new CheckBox("启用特殊地形");ground.setId("preparation-terrain");ground.setSelected(terrain);
        ground.setOnAction(e->{terrain=ground.isSelected();model.setTerrainEnabled(terrain);updatePreview();});
        VBox tankBox=new VBox(10,label("机体选型 / VEHICLE","eyebrow"));
        tankBox.setMinWidth(0);tankBox.setMaxWidth(Double.MAX_VALUE);tankBox.getStyleClass().add("prep-selection-block");
        for(int player=0;player<(selectedMode==Mode.DUEL?2:1);player++) {
            final int id=player;
            if(selectedMode==Mode.DUEL) tankBox.getChildren().add(label("玩家 "+(player+1),"muted"));
            ComboBox<TankType> tanks=new ComboBox<>();tanks.getItems().setAll(TankType.values());tanks.setValue(selectedTank[player]);
            tanks.setId("tank-select-"+player);tanks.setMaxWidth(Double.MAX_VALUE);
            tanks.setConverter(new javafx.util.StringConverter<>() {public String toString(TankType t){return t==null?"":Labels.tank(t);}public TankType fromString(String s){return TankType.BALANCED;}});
            preparationPicker(tanks,Labels::tank,t->t.hp+" HP  /  "+(int)t.speed+" PX/S  /  "+t.cooldown+" MS");
            tanks.setOnAction(e->{selectedTank[id]=tanks.getValue();model.selectTank(id,tanks.getValue());showSelectionPage();});tankBox.getChildren().add(tanks);
        }
        modeBox.getChildren().addAll(new Separator(),tankBox,new Separator(),label("环境配置 / TERRAIN","eyebrow"),ground);
        TankType type=selectedTank[0];Region portraitRegion=new PreparationScene(type);
        portraitRegion.setMinHeight(260);VBox.setVgrow(portraitRegion,Priority.ALWAYS);
        HBox unitHeader=new HBox(12,label("02  /  机体整备","eyebrow"),spacer(),label("UNIT 0"+(type.ordinal()+1),"prep-unit-code"));
        unitHeader.setAlignment(Pos.CENTER_LEFT);
        HBox metrics=new HBox(18,
                preparationMetric("装甲",type.hp+"","HP",type.hp/15.0),
                preparationMetric("机动",(int)type.speed+"","PX/S",type.speed/240.0),
                preparationMetric("装填",type.cooldown+"","MS",390.0/type.cooldown));
        metrics.getStyleClass().add("prep-metrics");
        VBox display=new VBox(12,unitHeader,portraitRegion,label(Labels.tank(type),"prep-vehicle-name"),
                label(Labels.tankRole(type),"prep-role"),label(Labels.tankTrait(type),"muted"),metrics);
        display.setAlignment(Pos.TOP_CENTER);display.setMinWidth(300);display.getStyleClass().add("prep-vehicle-display");
        VBox characterBox=new VBox(12,label("03  /  驾驶员链接","eyebrow"));
        characterBox.setMinWidth(245);characterBox.setMinHeight(Region.USE_PREF_SIZE);characterBox.setMaxWidth(Double.MAX_VALUE);characterBox.getStyleClass().add("prep-pilot-column");
        characterBox.setAlignment(Pos.TOP_CENTER);
        for(int player=0;player<(selectedMode==Mode.DUEL?2:1);player++) {
            final int id=player;
            if(selectedMode==Mode.DUEL) characterBox.getChildren().add(label("玩家 "+(player+1),"muted"));
            ComboBox<ArtAssets.Hero> picker=new ComboBox<>();
            picker.getItems().setAll(ArtAssets.Hero.values());
            picker.setValue(selectedCharacter[player]);picker.setId("character-select-"+player);
            picker.setMaxWidth(Double.MAX_VALUE);
            picker.setConverter(new javafx.util.StringConverter<>() {
                public String toString(ArtAssets.Hero c){return c==null?"":c.label();}
                public ArtAssets.Hero fromString(String s){return ArtAssets.Hero.XIAOXIANG;}
            });
            preparationPicker(picker,ArtAssets.Hero::label,ArtAssets.Hero::caption);
            picker.setOnAction(e->{selectedCharacter[id]=picker.getValue();showSelectionPage();});
            characterBox.getChildren().add(picker);
        }
        characterBox.getChildren().add(new Separator());
        if(selectedMode==Mode.DUEL) {
            HBox pair=new HBox(9,
                    preparationPilot(selectedCharacter[0],195,content),
                    preparationPilot(selectedCharacter[1],195,content));
            pair.setAlignment(Pos.TOP_CENTER);
            HBox.setHgrow(pair.getChildren().get(0),Priority.ALWAYS);
            HBox.setHgrow(pair.getChildren().get(1),Priority.ALWAYS);
            characterBox.getChildren().add(pair);
        } else {
            characterBox.getChildren().add(preparationPilot(selectedCharacter[0],400,content));
        }
        columns.getChildren().addAll(modeBox,display,characterBox);
        Runnable arrange=()->{
            boolean compact=columns.getWidth()<1120;
            if(Objects.equals(columns.getProperties().put("compact",compact),compact)) return;
            columns.getColumnConstraints().clear();
            for(double percent:compact?new double[]{50,50}:new double[]{24,46,30}) {
                ColumnConstraints column=new ColumnConstraints();column.setPercentWidth(percent);columns.getColumnConstraints().add(column);
            }
            GridPane.setConstraints(modeBox,0,0);GridPane.setConstraints(display,compact?0:1,compact?1:0,compact?2:1,1);
            GridPane.setConstraints(characterBox,compact?1:2,0);
            for(Node node:List.of(modeBox,display,characterBox)) {GridPane.setValignment(node,VPos.TOP);GridPane.setVgrow(node,Priority.ALWAYS);}
            columns.setMinHeight(compact?Region.USE_PREF_SIZE:520);display.setPrefHeight(compact?520:600);
        };
        columns.widthProperty().addListener((o,was,now)->arrange.run());arrange.run();
        ScrollPane scroll=new ScrollPane(columns);scroll.setFitToWidth(true);scroll.setFitToHeight(true);scroll.setMinHeight(0);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);scroll.getStyleClass().add("prep-workspace-scroll");VBox.setVgrow(scroll,Priority.ALWAYS);
        content.getChildren().add(scroll);scroll.applyCss();
        Button confirm=button("确认出击  →","确认模式与坦克选择，进入战场",()->{
            model.selectMode(selectedMode);for(int i=0;i<2;i++) model.selectTank(i,selectedTank[i]);model.setTerrainEnabled(terrain);
            model.startBattle(System.nanoTime());savedResult=false;refresh();
        });confirm.setId("start-button");confirm.getStyleClass().add("primary");confirm.setMaxWidth(Double.MAX_VALUE);
        Button back=button("← 返回首页","返回首页",this::returnHome);back.setId("preparation-back");
        confirm.setGraphic(MechaArtwork.icon("play",18,false));confirm.setGraphicTextGap(12);
        VBox selection=new VBox(4,label(Labels.mode(selectedMode),"prep-footer-mode"),label(Labels.tank(type),"muted"));
        HBox actions=new HBox(22,back,selection,spacer(),confirm);actions.setAlignment(Pos.CENTER_LEFT);actions.getStyleClass().add("preparation-actions");
        content.getChildren().add(actions);
    }
    private static VBox preparationMetric(String title,String value,String unit,double progress) {
        HBox reading=new HBox(5,label(value,"prep-metric-value"),label(unit,"prep-metric-unit"));reading.setAlignment(Pos.BASELINE_LEFT);
        ProgressBar bar=new ProgressBar(progress);bar.setMaxWidth(Double.MAX_VALUE);
        VBox metric=new VBox(8,label(title,"eyebrow"),reading,bar);metric.setMinWidth(80);metric.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(metric,Priority.ALWAYS);return metric;
    }
    private <T> void preparationPicker(ComboBox<T> picker,java.util.function.Function<T,String> name,java.util.function.Function<T,String> detail) {
        picker.getStyleClass().add("prep-picker");
        picker.setCellFactory(list->new ListCell<>() {
            {getStyleClass().add("prep-option-cell");}
            @Override protected void updateItem(T item,boolean empty) {
                super.updateItem(item,empty);setText(null);
                if(empty||item==null) {setGraphic(null);return;}
                Label heading=label(name.apply(item),"prep-option-name"),subtitle=label(detail.apply(item),"prep-option-detail");
                setGraphic(new VBox(5,heading,subtitle));
            }
        });
    }
    private Region preparationPilot(ArtAssets.Hero hero,double width,VBox content) {
        Region preview=characterPreview(hero,width);
        preview.setMinHeight(Region.USE_PREF_SIZE);
        if(preview instanceof VBox holder && holder.getChildren().get(0) instanceof javafx.scene.image.ImageView image) {
            image.fitWidthProperty().bind(Bindings.min(width,Bindings.max(90,holder.widthProperty().subtract(12))));
            image.fitHeightProperty().bind(Bindings.min(580,Bindings.max(180,content.heightProperty().subtract(selectedMode==Mode.DUEL?500:450))));
        }
        return preview;
    }
    private void setupPreparation() {
        updatePreview();
        sidebar.getChildren().addAll(label("出击准备","section-title"),label("01 / 战斗模式","eyebrow"));
        ToggleGroup modes=new ToggleGroup();
        for(Mode mode:List.of(Mode.SINGLE,Mode.DUEL,Mode.ENDLESS)) {
            ToggleButton choice=new ToggleButton(Labels.mode(mode));choice.setId("mode-"+mode.name());choice.setMaxWidth(Double.MAX_VALUE);
            choice.getStyleClass().add("mode-choice");choice.setGraphic(CampusArtwork.icon(mode==Mode.ENDLESS?"flag":"play",15));choice.setGraphicTextGap(12);
            choice.setToggleGroup(modes);choice.setSelected(selectedMode==mode);
            choice.setTooltip(new Tooltip(modeTooltip(mode)));
            choice.selectedProperty().addListener((o,wasSelected,isSelected)->{
                if(isSelected && selectedMode!=mode) {selectedMode=mode;model.selectMode(mode);refresh();}
            });
            choice.setOnAction(e->{if(!choice.isSelected()) choice.setSelected(true);});
            sidebar.getChildren().add(choice);
        }
        sidebar.getChildren().addAll(new Separator(),label("02 / 坦克配置","eyebrow"));
        for(int player=0;player<(selectedMode==Mode.DUEL?2:1);player++) {
            final int id=player;
            if(selectedMode==Mode.DUEL) sidebar.getChildren().add(label("玩家 "+(player+1),"muted"));
            ComboBox<TankType> tanks=new ComboBox<>();tanks.getItems().setAll(TankType.values());tanks.setValue(selectedTank[player]);
            tanks.setId("tank-select-"+player);tanks.setMaxWidth(Double.MAX_VALUE);
            tanks.setConverter(new javafx.util.StringConverter<>() {public String toString(TankType t) {return t==null?"":Labels.tank(t);}public TankType fromString(String s){return TankType.BALANCED;}});
            tanks.setOnAction(e->{selectedTank[id]=tanks.getValue();model.selectTank(id,tanks.getValue());refresh();});
            sidebar.getChildren().add(tanks);
        }
        TankType type=selectedTank[0];
        Canvas portrait=new Canvas(220,102);
        var art=portrait.getGraphicsContext2D();
        art.setFill(Color.web("#e2eabb"));art.fillRoundRect(0,0,220,102,8,8);
        CampusArtwork.tank(art,112,62,tankPreviewScale(type),false);
        sidebar.getChildren().addAll(portrait,attribute("装甲",type.hp/15.0,Integer.toString(type.hp)),
                attribute("机动",type.speed/240.,Integer.toString((int)type.speed)),
                attribute("装填",390./type.cooldown,type.cooldown+"ms"),
                label(Labels.tankRole(type)+" / "+Labels.tankTrait(type),"muted"));
        CheckBox ground=new CheckBox("特殊地形");ground.setSelected(terrain);
        ground.setTooltip(new Tooltip("冰面惯性 / 岩浆灼烧 / 重力减速"));ground.setOnAction(e->{terrain=ground.isSelected();model.setTerrainEnabled(terrain);updatePreview();});
        sidebar.getChildren().add(ground);
        Region flexible=new Region();VBox.setVgrow(flexible,Priority.ALWAYS);sidebar.getChildren().add(flexible);
        Button start=button("开始游戏  →","进入战场",()->{
            model.selectMode(selectedMode);for(int i=0;i<2;i++) model.selectTank(i,selectedTank[i]);
            model.setTerrainEnabled(terrain);model.startBattle(System.nanoTime());savedResult=false;refresh();
        });start.setId("start-button");start.getStyleClass().add("primary");start.setMaxWidth(Double.MAX_VALUE);
        sidebarActions.getChildren().add(start);
        Button back=button("← 返回首页","返回首页",this::returnHome);back.setId("preparation-back");sidebarActions.getChildren().add(back);
    }
    private static double tankPreviewScale(TankType type) {
        return switch(type) {
            case HEAVY -> 1.15;
            case MEDIC -> 1.08;
            case SCOUT -> .86;
            case RESEARCH -> .96;
            default -> 1.0;
        };
    }
    /**
     * Large scene artwork for a character. Source images are 1536x2048, so the fitted view keeps a
     * .75 width/height ratio; chibi/portrait remain fallbacks for incomplete builds.
     */
    private Region characterPreview(ArtAssets.Hero character,double width) {
        javafx.scene.image.Image image=art.scene(character);
        if(image==null) image=art.chibi(character);
        if(image==null) image=art.portrait(character);
        if(image==null) return label(character.caption(),"muted");
        javafx.scene.image.ImageView view=new javafx.scene.image.ImageView(image);
        view.setPreserveRatio(true);view.setSmooth(true);
        view.setFitWidth(width);
        view.setFitHeight(width/.75);
        VBox identity=selectedMode==Mode.DUEL
                ?new VBox(3,label(character.label(),"item-title"))
                :new VBox(4,label(character.label(),"item-title"),label(character.caption(),"muted"),
                        label("PILOT LINK / READY","pilot-ready"));
        VBox holder=new VBox(7,view,identity);
        holder.setAlignment(Pos.CENTER);
        holder.setFillWidth(true);
        holder.getStyleClass().add("pilot-preview");
        holder.setMinHeight(110);
        return holder;
    }

    private HBox attribute(String name,double value,String number) {
        Label title=label(name,"specs"),amount=label(number,"specs");amount.setMinWidth(55);amount.setAlignment(Pos.CENTER_RIGHT);
        ProgressBar bar=new ProgressBar(value);bar.setMaxWidth(Double.MAX_VALUE);HBox.setHgrow(bar,Priority.ALWAYS);
        HBox row=new HBox(9,title,bar,amount);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("attribute");return row;
    }

    private static String modeTooltip(Mode mode) {
        return switch(mode) {
            case SINGLE -> "单人对抗 3 辆 AI 坦克";
            case DUEL -> "同屏本地双人对战";
            case ENDLESS -> "连续波次，局间永久强化";
            case MULTI -> "进入多人大厅后创建混战房间";
            case COOP -> "进入多人大厅后创建合作讨伐房间";
        };
    }
    private static void configureMeter(HBox meter,String id,String kind) {
        meter.setId(id);meter.getStyleClass().addAll("segment-meter",kind);
        for(int i=0;i<10;i++) {Region segment=new Region();segment.getStyleClass().add("meter-segment");HBox.setHgrow(segment,Priority.ALWAYS);meter.getChildren().add(segment);}
    }
    private static void setMeter(HBox meter,double progress) {
        int active=(int)Math.ceil(Math.max(0,Math.min(1,progress))*meter.getChildren().size());
        for(int i=0;i<meter.getChildren().size();i++) {
            var classes=meter.getChildren().get(i).getStyleClass();
            if(i<active) {if(!classes.contains("active")) classes.add("active");}
            else classes.remove("active");
        }
    }
    private VBox metric(String name,String value) {
        Label number=label(value,"metric-value");
        VBox box=new VBox(2,label(name,"metric-label"),number);box.getStyleClass().add("metric");HBox.setHgrow(box,Priority.ALWAYS);return box;
    }
    private void updatePreview() {
        TankGameModel demo=new TankGameModel();demo.selectMode(selectedMode);
        for(int i=0;i<2;i++) demo.selectTank(i,selectedTank[i]);
        demo.setTerrainEnabled(terrain);demo.startBattle(20260911);preview=demo.snapshot();
    }
    /**
     * The character column's readouts: who is driving, how the machine is doing, what they can do.
     *
     * <p>Split out of the sidebar so each column says one thing - this one is the player's own
     * character, the sidebar is the state of the battlefield.
     */
    private void driverPanel(Snapshot snapshot) {
        ArtAssets.Hero hero=selectedCharacter[0];
        VBox identity=new VBox(3,label(hero.label(),"driver-name"),label(hero.caption(),"driver-caption"));
        identity.getStyleClass().add("driver-identity-card");
        HBox healthLine=new HBox(8,label("HP","combat-unit"),spacer(),healthValue);
        healthLine.setAlignment(Pos.CENTER_LEFT);
        healthName.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(healthName,Priority.ALWAYS);
        // Pinned to its preferred width: the numbers must survive whatever the panel is squeezed to.
        healthValue.setMinWidth(Region.USE_PREF_SIZE);
        VBox healthCard=new VBox(7,combatHeading("装甲完整度","ARMOR"),healthName,healthLine,hpMeter);healthCard.getStyleClass().add("driver-stat-card");
        VBox energyCard=new VBox(7,combatHeading("共振能量","ENERGY"),energy,energyMeter);energyCard.getStyleClass().add("driver-stat-card");
        driverPanel.getChildren().addAll(identity,healthCard,energyCard);
        Button pulse=button("脉冲  Q", "Q",()->model.handleInput(0,Action.PULSE,true));
        pulse.setGraphic(MechaArtwork.icon("pulse",17,false));pulse.setGraphicTextGap(9);pulse.getStyleClass().add("primary");
        pulse.setId("pulse-button");pulse.setMaxWidth(Double.MAX_VALUE);
        VBox effectsCard=new VBox(7,combatHeading("当前增幅","BUFF"),effects);effectsCard.getStyleClass().add("effects-card");
        driverPanel.getChildren().addAll(pulse,effectsCard);
        Region flexible=new Region();VBox.setVgrow(flexible,Priority.ALWAYS);driverPanel.getChildren().add(flexible);
    }
    /**
     * The strip under the battlefield mirrors the reference layout: duel controls move here so the
     * two character columns stay symmetrical and the canvas keeps the full width between them.
     */
    private void fillBattleFooter(Snapshot snapshot) {
        battleFooter.getChildren().clear();
        battleFooter.getChildren().add(label("中南大学 / 麓山训练场","muted"));
        if(snapshot.mode()==Mode.DUEL)
            battleFooter.getChildren().add(label("双人对决 · ⌨ P1 W/S/A/D · P2 方向键 · Esc","muted"));
        battleFooter.getChildren().addAll(spacer(),label("TANK TROUBLE  /  02","muted"));
    }
    private void battleSidebar(Snapshot snapshot) {
        sidebar.getChildren().addAll(label("TACTICAL / 战术终端","combat-kicker"),label(Labels.mode(snapshot.mode()),"section-title"),new Separator());
        HBox legend=new HBox(12,label("◎ 本机","combat-radar-own"),label("● 战场目标","combat-radar-target"));
        VBox tactical=new VBox(8,combatHeading("战区状态","LIVE SCAN"),tacticalRadar(snapshot),legend,situation);
        tactical.getStyleClass().add("tactical-card");
        HBox metrics=new HBox(8);
        long enemies=snapshot.tanks().stream().filter(p->p.player()<0&&p.hp()>0).count();
        metrics.getChildren().addAll(metric("敌军",Long.toString(enemies)),metric("波次",String.format("%02d",snapshot.wave())),metric("积分",Integer.toString(snapshot.score())));
        sidebar.getChildren().addAll(tactical,metrics,new Separator(),combatHeading("战术提示","TACTICS"),label("利用墙壁反弹，保持移动并观察能量。","tactical-tip"));
        int stock=snapshot.tanks().stream().filter(t->t.player()==0).mapToInt(TankView::turretStock).findFirst().orElse(0);
        if(snapshot.mode()!=Mode.DUEL) {
            Button deploy=button("炮塔  ×"+stock,"T",()->{if(model.beginDeployment()) refresh();});
            deploy.setId("deploy-button");deploy.setMaxWidth(Double.MAX_VALUE);
            deploy.setDisable(stock==0 || (snapshot.state()!=State.RUNNING && snapshot.state()!=State.PAUSED && snapshot.state()!=State.SHOP));
            deploy.setGraphic(MechaArtwork.icon("flag",16,true));deploy.setGraphicTextGap(8);
            VBox turretCard=new VBox(8,combatHeading("炮塔库存","TURRET"),deploy);turretCard.getStyleClass().add("tactical-card");
            sidebar.getChildren().addAll(turretCard);
        }
        Region flexible=new Region();VBox.setVgrow(flexible,Priority.ALWAYS);sidebar.getChildren().add(flexible);
        snapshot.tanks().stream().filter(t->t.player()==0).findFirst().ifPresent(t->{
            HBox specs=new HBox(12,metric("基础速度 / PX/S",Integer.toString((int)t.type().speed)),metric("基础装填 / MS",Integer.toString(t.type().cooldown)));
            VBox loadout=new VBox(10,combatHeading("机体参数","LOADOUT"),specs);loadout.getStyleClass().add("combat-loadout");
            sidebar.getChildren().add(loadout);
        });
        if(snapshot.mode()!=Mode.DUEL) {
            Button shop=button("补给  B","B",()->{model.openShop();refresh();});
            shop.setGraphic(MechaArtwork.icon("supply",17,true));shop.setGraphicTextGap(9);
            shop.setId("shop-button");shop.setMaxWidth(Double.MAX_VALUE);shop.setDisable(snapshot.state()!=State.RUNNING && snapshot.state()!=State.PAUSED);
            sidebarActions.getChildren().add(shop);
        }
        Label controls=label("⌨","control-hint");
        String shortcuts="P1  W/S 移动 · A/D 转向 · Space 开火 · Q 脉冲\nP2  方向键 · Enter 开火 · Shift 脉冲\nEsc 暂停   B 补给   T 炮塔";
        controls.setAccessibleText(shortcuts);Tooltip.install(controls,new Tooltip(shortcuts));
        sidebarActions.getChildren().addAll(new Separator(),controls,label("SEED / "+snapshot.seed(),"seed"));
    }
    private Canvas tacticalRadar(Snapshot snapshot) {
        double width=208,height=144;
        Canvas radar=new Canvas(width,height);radar.setId("tactical-radar");
        tacticalRadar=radar;
        updateTacticalRadar(radar,snapshot,0);
        return radar;
    }
    private void updateTacticalRadar(Canvas radar,Snapshot snapshot,int viewer) {
        double width=radar.getWidth(),height=radar.getHeight();
        var g=radar.getGraphicsContext2D();
        g.clearRect(0,0,width,height);
        g.setFill(Color.web("#122125"));g.fillRoundRect(0,0,width,height,4,4);
        g.setStroke(Color.web("#29434b"));g.setLineWidth(1);
        for(int x=16;x<width;x+=16) g.strokeLine(x,0,x,height);
        for(int y=16;y<height;y+=16) g.strokeLine(0,y,width,y);
        double cx=width/2,cy=height/2;
        g.setStroke(Color.web("#487e87"));g.strokeOval(cx-52,cy-52,104,104);g.strokeOval(cx-27,cy-27,54,54);
        g.strokeLine(cx,7,cx,height-7);g.strokeLine(12,cy,width-12,cy);
        g.setStroke(Color.web("#8ad4db"));
        for(int x=8;x<width-8;x+=8) {double tick=x%32==8?6:3;g.strokeLine(x,2,x,2+tick);g.strokeLine(x,height-2,x,height-2-tick);}
        g.strokeLine(1,1,16,1);g.strokeLine(1,1,1,16);g.strokeLine(width-1,height-1,width-16,height-1);g.strokeLine(width-1,height-1,width-1,height-16);
        TankView own=null;
        for(TankView tank:snapshot.tanks()) {
            if(tank.hp()<=0) continue;
            if(tank.player()==viewer) own=tank;
            double x=8+tank.x()/Rules.WIDTH*(width-16),y=8+tank.y()/Rules.HEIGHT*(height-16);
            boolean mine=tank.player()==viewer;
            g.setGlobalAlpha(mine?1:.42);
            g.setFill(BattleRenderer.tankColor(snapshot.mode(),tank.player()));
            g.fillOval(x-3.5,y-3.5,7,7);
            if(mine) {g.setStroke(Color.WHITE);g.setLineWidth(1.3);g.strokeOval(x-6,y-6,12,12);}
        }
        g.setGlobalAlpha(1);
        double angle=own==null?0:Math.toRadians(own.angle());
        g.setStroke(Color.web("#c8ef64",.55));g.setLineWidth(1.2);
        g.strokeLine(cx,cy,cx+Math.cos(angle)*56,cy+Math.sin(angle)*56);
    }
    private static HBox combatHeading(String title,String code) {
        Label heading=label(title,"combat-heading"),identifier=label(code,"combat-code");
        HBox row=new HBox(6,heading,spacer(),identifier);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("combat-heading-row");
        return row;
    }
    private void updateHud(Snapshot snapshot) {
        boolean setup=snapshot.state()==State.HOME || snapshot.state()==State.READY;
        Snapshot displayed=setup?preview:snapshot;
        status.setText(setup?"● 待命":snapshot.state()==State.RUNNING?"● 战斗中":snapshot.state()==State.DEPLOYING?"● 炮塔部署":snapshot.state()==State.RESULT?"● 行动结束":"● 战术暂停");
        status.getStyleClass().setAll("status");
        score.setText("积分  "+String.format("%04d",displayed.score()));
        wave.setText("波次  "+String.format("%02d",displayed.wave()));wallet.setText("金币  "+displayed.coins());
        double sceneWidth=stage==null||stage.getScene()==null?0:stage.getScene().getWidth();
        boolean compactDuel=page==Page.BATTLE&&snapshot.mode()==Mode.DUEL
                &&sceneWidth<DUEL_PORTRAIT_MIN_WIDTH;
        duelCompact.setVisible(compactDuel);duelCompact.setManaged(compactDuel);
        duelCompact.setText(compactDuel?snapshot.tanks().stream()
                .filter(t->t.player()>=0)
                .map(t->"P"+(t.player()+1)+" "+t.hp()+"/"+t.maxHp())
                .reduce((a,b)->a+" · "+b).orElse(""):"");
        if(page==Page.BATTLE&&snapshot.mode()==Mode.DUEL) {
            if(playerOne!=null) playerOne.update(snapshot);
            if(playerTwo!=null) playerTwo.update(snapshot);
            return;
        }
        snapshot.tanks().stream().filter(t->t.player()==0).findFirst().ifPresent(t->{
            healthName.setText(Labels.tank(t.type()));healthValue.setText(t.hp()+" / "+t.maxHp());setMeter(hpMeter,t.hp()/(double)t.maxHp());
            energy.setText(String.format("%03d",(int)t.energy())+"  /  100");setMeter(energyMeter,t.energy()/100.);
            // Two columns, two audiences: the character's own conditions sit under the character,
            // the state of the fight sits in the sidebar.
            String buffs=(t.shieldMs()>0?"护盾  "+(int)Math.ceil(t.shieldMs()/1000.)+"s\n":"")+(t.rapidMs()>0?"急速  "+(int)Math.ceil(t.rapidMs()/1000.)+"s\n":"")
                    +(t.scatterMs()>0?"散射  "+(int)Math.ceil(t.scatterMs()/1000.)+"s\n":"")
                    +(t.aimMs()>0?"预瞄  "+(int)Math.ceil(t.aimMs()/1000.)+"s\n":"")
                    +(t.penetrateMs()>0?"穿透  "+(int)Math.ceil(t.penetrateMs()/1000.)+"s\n":"");
            effects.setText(buffs.isEmpty()?"—":buffs.replace("\n","   ·   "));
            situation.setText(snapshot.mode()==Mode.DUEL
                    ? snapshot.tanks().stream().filter(p->p.player()==1).map(p->"P2 装甲 "+p.hp()+" / "+p.maxHp()+"\nP2 能量 "+(int)p.energy()).findFirst().orElse("")
                    : snapshot.tanks().stream().filter(p->p.player()<0 && p.boss() && p.hp()>0).findFirst()
                            .map(p->"BOSS  装甲 "+p.hp()+" / "+p.maxHp())
                            .orElse("剩余敌军  "+snapshot.tanks().stream().filter(p->p.player()<0 && p.hp()>0).count()));
            // Looked up from the root: the pulse button lives in the character column now, not the sidebar.
            Node pulse=root.lookup("#pulse-button");if(pulse!=null) pulse.setDisable(t.energy()<100 || snapshot.state()!=State.RUNNING || t.hp()<=0);
            Node deployment=sidebar.lookup("#deploy-button");
            if(deployment instanceof Button deploy) {
                deploy.setText("部署炮塔  /  "+t.turretStock());
                deploy.setDisable(t.turretStock()==0 || (snapshot.state()!=State.RUNNING && snapshot.state()!=State.PAUSED && snapshot.state()!=State.SHOP));
            }
        });
    }
    private VBox modal(String eyebrow,String title) {
        VBox content=new VBox(16);content.getStyleClass().add("modal");content.setMaxWidth(Double.MAX_VALUE);content.setMaxHeight(Region.USE_PREF_SIZE);
        content.getChildren().addAll(label(eyebrow,"eyebrow"),label(title,"modal-title"));
        ScrollPane scroll=new ScrollPane(content);scroll.setFitToWidth(true);scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setMaxWidth(502);scroll.setMinHeight(0);scroll.setMaxHeight(Region.USE_PREF_SIZE);
        scroll.prefViewportHeightProperty().bind(Bindings.createDoubleBinding(
                ()->content.prefHeight(Math.max(200,Math.min(500,field.getWidth()-40))),
                content.getChildren(),content.layoutBoundsProperty(),field.widthProperty()));
        StackPane scrim=new StackPane(scroll);scrim.getStyleClass().add("scrim");scrim.setPadding(new Insets(20));
        field.getChildren().add(scrim);return content;
    }
    private void showPause() {
        VBox modal=modal("TACTICAL PAUSE","战术暂停");
        Button resume=button("继续战斗  →","Esc",()->{model.resume();refresh();});resume.setId("resume-button");resume.getStyleClass().add("primary");
        modal.getChildren().add(resume);
        if(model.snapshot().mode()==Mode.ENDLESS) modal.getChildren().add(button("撤离并结算","保存当前无尽战绩",()->{model.finishRun();refresh();}));
        modal.getChildren().add(button("返回首页","放弃本局进度",this::returnHome));
    }
    private void showShop() {
        VBox modal=modal("FIELD SUPPLY","战地补给  /  "+model.snapshot().coins()+" 金币");
        for(Item item:Item.values()) {
            VBox text=new VBox(4,label(Labels.item(item),"item-title"),label(Labels.effect(item),"muted"));
            Button buy=button(item.price+" 金币","购买"+Labels.item(item),()->{
                boolean purchased=model.purchase(item);refresh();notice.setText(purchased?(item==Item.TURRET?"炮塔已入库":"补给已装配"):"当前无法购买");
            });buy.setId("buy-"+item.name());buy.setDisable(!model.canPurchase(item));
            buy.setMinWidth(82);text.setMinWidth(0);HBox.setHgrow(text,Priority.ALWAYS);
            HBox row=new HBox(12,text,buy);row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("shop-row");modal.getChildren().add(row);
        }
        notice.getStyleClass().setAll("notice");modal.getChildren().add(notice);
        Button deploy=button("部署炮塔","选择战场部署位置",()->{if(model.beginDeployment()) refresh();});
        deploy.setId("shop-deploy");deploy.setDisable(model.snapshot().tanks().stream().noneMatch(t->t.player()==0 && t.turretStock()>0));
        modal.getChildren().add(deploy);
        Button back=button("返回战场  →","继续仿真",()->{model.resume();refresh();});back.setId("shop-close");modal.getChildren().add(back);
    }
    private void showUpgrades() {
        VBox modal=modal("WAVE "+model.snapshot().wave()+" / CLEAR","选择下一轮强化");
        for(Upgrade upgrade:model.snapshot().upgrades()) {
            Button choice=button(Labels.upgrade(upgrade),Labels.upgradeEffect(upgrade),()->{model.chooseUpgrade(upgrade);refresh();});
            choice.setId("upgrade-"+upgrade.name());choice.setMaxWidth(Double.MAX_VALUE);
            modal.getChildren().addAll(choice,label(Labels.upgradeEffect(upgrade),"muted"));
        }
        modal.getChildren().add(button("撤离并结算","结束本次无尽行动",()->{model.finishRun();refresh();}));
    }
    private void showResult(Snapshot snapshot) {
        String title=switch(snapshot.result()) {case VICTORY->snapshot.mode()==Mode.ENDLESS?"撤离成功":"突围成功";case DEFEAT->"行动结束";case P1_WIN->"P1 赢得对决";case P2_WIN->"P2 赢得对决";case DRAW->"同归于尽";default->"行动结束";};
        VBox modal=modal("MISSION REPORT",title);
        modal.getChildren().addAll(label("积分 "+snapshot.score()+"    击破 "+snapshot.kills()+"    波次 "+snapshot.wave(),"result-stats"),
                label("行动时长  "+formatTime(snapshot.elapsedMs()),"muted"));
        if(!savedResult) {
            try {leaderboard.save(snapshot);savedResult=true;} catch(java.io.IOException e) {notice.setText("战绩保存失败："+e.getMessage());}
        }
        modal.getChildren().add(notice);
        Button restart=button("再次出击  →","返回准备页",()->{model.restart();refresh();});restart.setId("restart-button");restart.getStyleClass().add("primary");
        modal.getChildren().addAll(restart,button("返回首页","返回首页",this::returnHome));
    }
    private void showLeaderboard() {
        if(page==Page.LEADERBOARD) return;
        leaderboardReturn=page;
        if(model.snapshot().state()==State.RUNNING) model.pause();
        controller.resetClock();page=Page.LEADERBOARD;
        VBox content=menuPage("排行榜","leaderboard-page");
        try {
            var entries=leaderboard.load();
            if(entries.isEmpty()) {
                VBox empty=new VBox(20,MechaArtwork.icon("trophy",48,false),label("暂无战绩","item-title"));empty.setAlignment(Pos.CENTER);
                VBox.setVgrow(empty,Priority.ALWAYS);content.getChildren().add(empty);
            }
            else {
                VBox rows=new VBox(0);int rank=1;
                for(var entry:entries) {
                    HBox row=new HBox(12,label(String.format("%02d",rank++),"rank"),label(Labels.mode(entry.mode()),"item-title"),spacer(),label(entry.score()+" 分 · "+entry.wave()+" 波","muted"));
                    row.setAlignment(Pos.CENTER_LEFT);row.getStyleClass().add("leaderboard-row");
                    rows.getChildren().add(row);
                }
                ScrollPane scroll=new ScrollPane(rows);scroll.setFitToWidth(true);scroll.setMinHeight(100);
                VBox.setVgrow(scroll,Priority.ALWAYS);content.getChildren().add(scroll);
            }
        } catch(java.io.IOException e) {content.getChildren().add(label("无法读取战绩："+e.getMessage(),"notice"));}
        Button close=button("← 返回","关闭排行榜",this::closeLeaderboard);close.setId("leaderboard-close");content.getChildren().add(close);
    }
    private void closeLeaderboard() {page=leaderboardReturn;refresh();}
    private VBox menuPage(String title,String id) {
        root.setTop(null);
        boolean preparation=id.equals("preparation-page");
        if(preparation) {
            VBox content=new VBox(20);content.setId(id);content.getStyleClass().add("menu-page");
            content.setMaxSize(1840,Double.MAX_VALUE);content.setPadding(new Insets(26,32,22,32));
            VBox heading=new VBox(6,label("PRE-LAUNCH / CSU ARSENAL","prep-page-code"),label(title,"page-title"));
            HBox masthead=new HBox(18,heading,spacer(),label("PROJECT ECHO  /  机体整备终端","eyebrow"));masthead.setAlignment(Pos.CENTER_LEFT);
            content.getChildren().addAll(masthead,new Separator());
            StackPane container=new StackPane(new PreparationScene(null),content);container.setPadding(new Insets(12));
            root.setCenter(container);return content;
        }
        VBox content=new VBox(preparation?12:18);content.setId(id);content.setMaxWidth(preparation?1160:780);content.setMaxHeight(preparation?680:620);
        content.setPadding(new Insets(preparation?18:26));content.getStyleClass().add("menu-page");
        Label heading=label(title,"page-title");
        HBox masthead=new HBox(18,heading,spacer(),label("CSU / PROJECT ECHO","eyebrow"));masthead.setAlignment(Pos.CENTER_LEFT);
        content.getChildren().addAll(masthead,new Separator());
        StackPane inset=new StackPane(content);inset.setPadding(new Insets(preparation?8:24));
        if(preparation) inset.setAlignment(Pos.TOP_CENTER);
        StackPane container=new StackPane(new MechaArtwork(false),inset);
        if(preparation) container.setAlignment(Pos.TOP_CENTER);
        root.setCenter(container);return content;
    }
    private void showSettings() {
        page=Page.SETTINGS;VBox content=menuPage("设置","settings-page");
        CheckBox ground=new CheckBox("特殊地形");ground.setId("settings-terrain");ground.setSelected(terrain);
        CheckBox fullscreen=new CheckBox("全屏显示");fullscreen.setId("settings-fullscreen");fullscreen.setSelected(stage.isFullScreen());
        content.getChildren().addAll(label("游戏","eyebrow"),ground,new Separator(),label("显示","eyebrow"),fullscreen);
        content.getChildren().addAll(new Separator(),label("音频","eyebrow"),
                volumeRow("settings-music","音乐",audio.musicVolume(),audio::setMusicVolume),
                volumeRow("settings-sfx","音效",audio.sfxVolume(),audio::setSfxVolume));
        Region flexible=new Region();VBox.setVgrow(flexible,Priority.ALWAYS);content.getChildren().add(flexible);
        Label feedback=label("","notice");feedback.setId("settings-feedback");
        Button save=button("保存设置","保存游戏设置",()->{
            GameSettings settings=new GameSettings(ground.isSelected(),fullscreen.isSelected(),
                    audio.musicVolume(),audio.sfxVolume());
            try {
                settings.save(settingsFile);
                terrain=settings.terrain();
                // Apply the saved terrain before rebuilding the preview, or the preview keeps
                // showing zones from the setting the player just turned off.
                model.setTerrainEnabled(terrain);updatePreview();
                stage.setFullScreen(settings.fullscreen());
                feedback.setText("设置已保存");
            } catch(java.io.IOException e) {feedback.setText("设置保存失败："+e.getMessage());}
        });save.setId("settings-save");save.getStyleClass().add("primary");
        Button back=button("← 返回首页","返回首页",this::returnHome);back.setId("page-back");
        content.getChildren().addAll(feedback,new HBox(12,back,save));
    }
    private static String formatTime(long ms) {return String.format("%02d:%02d",ms/60000,ms/1000%60);}
    private static Label label(String text,String style) {Label label=new Label(text);label.getStyleClass().add(style);label.setWrapText(true);return label;}
    private static Region spacer() {Region space=new Region();HBox.setHgrow(space,Priority.ALWAYS);return space;}
    private static Button button(String text,String tooltip,Runnable action) {
        Button button=new Button(text);button.setTooltip(new Tooltip(tooltip));button.setFocusTraversable(false);button.setOnAction(e->action.run());return button;
    }
    /**
     * A volume row: slider plus live percentage readout.
     *
     * <p>The slider keeps the caller-supplied id so tests and styles can reach it, while the row
     * carries a separate id for layout targeting. Changes are reported as the player drags, so the
     * new level is audible while it is being set rather than only after saving.
     */
    private static HBox volumeRow(String id,String label,double initial,java.util.function.DoubleConsumer onChange) {
        Slider slider=new Slider(0,1,initial);slider.setId(id);slider.setMaxWidth(Double.MAX_VALUE);
        Label readout=label(percent(initial),"volume-value");
        slider.valueProperty().addListener((o,was,now)->{
            readout.setText(percent(now.doubleValue()));
            onChange.accept(now.doubleValue());
        });
        HBox row=new HBox(12,label(label,"volume-name"),slider,readout);
        row.setId(id+"-row");row.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(slider,Priority.ALWAYS);
        return row;
    }
    private static String percent(double value) {return Math.round(value*100)+"%";}
    @Override public void stop() {
        if(timer!=null) timer.stop();
        if(homePage!=null) homePage.cancelOpening();
        audio.close();
        voice.close();voiceTwo.close();
        model.exit();
    }
}
