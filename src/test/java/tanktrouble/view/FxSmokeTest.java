package tanktrouble.view;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.canvas.Canvas;
import javafx.geometry.Point3D;
import javafx.scene.image.ImageView;
import javafx.scene.input.*;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tanktrouble.art.ArtAssets.Expression;
import tanktrouble.art.PortraitView;
import tanktrouble.controller.GameController;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.entity.Entities.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="fxSmoke",matches="true")
class FxSmokeTest {
    @SuppressWarnings("unchecked") private static <T> T get(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(object);
    }
    private static void set(Object object,String name,Object value) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);field.set(object,value);
    }
    @Test void actualJavaFxPagesRenderAndControlsDriveTheModel() throws Exception {
        CompletableFuture<Void> ready=new CompletableFuture<>();
        Platform.startup(()->ready.complete(null));ready.get(15,TimeUnit.SECONDS);
        CompletableFuture<Void> test=new CompletableFuture<>();
        Platform.runLater(()->{
            TankTroubleApp app=new TankTroubleApp();Stage stage=new Stage();
            try {
                app.start(stage);FxSmokeTest.<AnimationTimer>get(app,"timer").stop();
                FxSmokeTest.<HomePage>get(app,"homePage").cancelOpening();
                Scene scene=stage.getScene();TankGameModel model=get(app,"model");
                var uncaught=new ArrayList<Throwable>();Thread.currentThread().setUncaughtExceptionHandler((t,e)->uncaught.add(e));
                snapshot(app,stage,"01-home");
                assertNotNull(scene.lookup("#home-page"));
                assertNull(scene.lookup("#mode-DUEL"),"Mode selection belongs to preparation");
                assertTrue(MultiplayerWindow.serverBindAddress().isAnyLocalAddress(),
                        "Built-in hosting must accept other computers on the LAN");
                click(scene,"#multiplayer-button");
                Stage multiplayer=(Stage)Window.getWindows().stream()
                        .filter(window->window instanceof Stage other&&other!=stage&&other.isShowing())
                        .findFirst().orElseThrow();
                assertNotNull(multiplayer.getScene().lookup("#multiplayer-root"));
                click(multiplayer.getScene(),"#multiplayer-module-lan");
                ChoiceBox<?> roomType=(ChoiceBox<?>)multiplayer.getScene().lookup("#battle-type");
                assertNotNull(roomType);
                assertEquals(2,roomType.getItems().size());
                click(multiplayer.getScene(),"#multiplayer-module-back");
                click(multiplayer.getScene(),"#multiplayer-module-server");
                assertNotNull(multiplayer.getScene().lookup("#server-address"));
                assertNotNull(multiplayer.getScene().lookup("#server-password"));
                click(multiplayer.getScene(),"#multiplayer-module-back");
                click(multiplayer.getScene(),"#multiplayer-module-lan");
                snapshot(multiplayer,"01a-multiplayer");
                MultiplayerWindow multiplayerWindow=get(app,"multiplayerWindow");
                multiplayerWindow.close();
                click(scene,"#settings-button");assertNotNull(scene.lookup("#settings-page"));
                ((CheckBox)scene.lookup("#settings-terrain")).setSelected(false);
                click(scene,"#settings-save");
                assertEquals("设置已保存",((Label)scene.lookup("#settings-feedback")).getText());
                snapshot(app,stage,"01b-settings");
                click(scene,"#page-back");assertNotNull(scene.lookup("#home-page"));
                click(scene,"#leaderboard-button");assertNotNull(scene.lookup("#leaderboard-page"));
                snapshot(app,stage,"01c-leaderboard");
                click(scene,"#leaderboard-close");assertNotNull(scene.lookup("#home-page"));
                click(scene,"#home-start");assertEquals(State.READY,model.snapshot().state());
                assertNotNull(scene.lookup("#preparation-page"));
                assertTrue(FxSmokeTest.<Snapshot>get(app,"preview").zones().isEmpty(),"Saved terrain setting is applied to preparation");
                ComboBox<TankType> tankSelect=(ComboBox<TankType>)scene.lookup("#tank-select-0");
                assertEquals(6,tankSelect.getItems().size(),"Preparation must expose all six campus vehicles");
                for(TankType type:List.of(TankType.MEDIC,TankType.RESEARCH,TankType.BALANCED)) {
                    tankSelect=(ComboBox<TankType>)scene.lookup("#tank-select-0");
                    tankSelect.setValue(type);
                    Event.fireEvent(tankSelect,new javafx.event.ActionEvent());
                    assertNotNull(scene.lookup("#preparation-page"));
                }
                click(scene,"#mode-DUEL");assertNotNull(scene.lookup("#tank-select-1"));
                var previews=scene.getRoot().lookupAll(".pilot-preview");
                assertEquals(2,previews.size(),"Duel preparation must show P1 and P2 artwork");
                for(Node preview:previews) {
                    ImageView image=firstImage(preview);
                    assertNotNull(image,"Each pilot preview must contain an image");
                    assertTrue(image.getImage().getHeight()/image.getImage().getWidth()>1.2,
                            "Preparation must prefer the tall scene artwork over the square chibi fallback");
                }
                ((ToggleButton)scene.lookup("#mode-ENDLESS")).setSelected(true);
                assertEquals(Mode.ENDLESS,FxSmokeTest.<Snapshot>get(app,"preview").mode());
                assertNull(scene.lookup("#tank-select-1"));
                click(scene,"#mode-SINGLE");assertNull(scene.lookup("#tank-select-1"));
                snapshot(app,stage,"01d-preparation");
                click(scene,"#start-button");assertEquals(State.RUNNING,model.snapshot().state());
                scene.getRoot().applyCss();scene.getRoot().layout();
                Node radar=scene.lookup("#tactical-radar");assertNotNull(radar,"Battle sidebar has a visual tactical radar");
                HBox energyMeter=(HBox)scene.lookup("#energy-meter");assertNotNull(energyMeter);
                assertEquals(10,energyMeter.getChildren().size(),"Energy uses a ten-segment instrument meter");
                List<Tank> tanks=get(model,"tanks");Tank player=tanks.get(0);double original=player.angle;
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.D,false,false,false,false));
                model.tick(8);assertNotEquals(original,player.angle);
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.D,false,false,false,false));
                double stopped=player.angle;model.tick(8);assertEquals(stopped,player.angle);
                snapshot(app,stage,"02-battle");
                click(scene,"#pause-button");assertEquals(State.PAUSED,model.snapshot().state());
                snapshot(app,stage,"03-pause");click(scene,"#resume-button");
                set(model,"wallet",8);player.hp=5;
                click(scene,"#shop-button");assertEquals(State.SHOP,model.snapshot().state());
                click(scene,"#buy-REPAIR");assertEquals(8,player.hp);assertEquals(6,model.snapshot().coins());
                click(scene,"#buy-TURRET");assertEquals(1,model.snapshot().tanks().get(0).turretStock());
                assertTrue(model.snapshot().turrets().isEmpty());
                snapshot(app,stage,"04-shop");
                click(scene,"#shop-deploy");assertEquals(State.DEPLOYING,model.snapshot().state());
                clickCanvas(scene,0,0);assertEquals(State.DEPLOYING,model.snapshot().state());
                assertEquals(1,model.snapshot().tanks().get(0).turretStock());
                double[] previewLocation=findPlacement(model);
                mouseCanvas(scene,previewLocation[0],previewLocation[1],MouseEvent.MOUSE_MOVED);
                assertEquals(previewLocation[0],FxSmokeTest.<Double>get(app,"deploymentX"),.001);
                snapshot(app,stage,"04b-deployment");
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ESCAPE,false,false,false,false));
                assertEquals(State.SHOP,model.snapshot().state());
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ESCAPE,false,false,false,false));
                assertEquals(State.SHOP,model.snapshot().state(),"Held Esc must not resume after cancellation");
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.ESCAPE,false,false,false,false));
                click(scene,"#shop-deploy");
                double[] placement=findPlacement(model);
                clickCanvas(scene,placement[0],placement[1]);
                assertEquals(State.PAUSED,model.snapshot().state());
                assertEquals(1,model.snapshot().turrets().size());
                assertEquals(0,model.snapshot().tanks().get(0).turretStock());
                assertEquals(placement[0],model.snapshot().turrets().get(0).x(),.001);
                snapshot(app,stage,"04c-placed");
                click(scene,"#leaderboard-button");
                long pausedTime=model.snapshot().elapsedMs();
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.B,false,false,false,false));
                model.tick(8);assertEquals(pausedTime,model.snapshot().elapsedMs());
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ESCAPE,false,false,false,false));
                assertNotNull(scene.lookup("#battle-page"));
                assertEquals(State.PAUSED,model.snapshot().state(),"Esc closes leaderboard without resuming battle");
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ESCAPE,false,false,false,false));
                assertEquals(State.PAUSED,model.snapshot().state(),"Held Esc must not resume after closing leaderboard");
                Event.fireEvent(scene,new KeyEvent(KeyEvent.KEY_RELEASED,"","",KeyCode.ESCAPE,false,false,false,false));
                click(scene,"#resume-button");
                player.hp=0;model.tick(8);app.refresh();assertEquals(State.RESULT,model.snapshot().state());
                snapshot(app,stage,"05-result");
                PortraitView portrait=get(app,"portrait");
                assertEquals(Expression.DEFEAT,portrait.currentExpression());
                click(scene,"#restart-button");assertEquals(State.READY,model.snapshot().state());
                assertNotEquals(Expression.DEFEAT,portrait.currentExpression(),
                        "Defeat must not outlive the result screen it was shown on");
                click(scene,"#mode-ENDLESS");click(scene,"#start-button");
                tanks=get(model,"tanks");for(Tank tank:tanks) if(tank.player<0) tank.hp=0;
                for(int i=0;i<630;i++) model.tick(8);app.refresh();assertEquals(State.UPGRADE,model.snapshot().state());
                snapshot(app,stage,"06-upgrades");click(scene,"#upgrade-RESONANCE");assertEquals(2,model.snapshot().wave());
                assertEquals(100,model.snapshot().tanks().get(0).energy());
                GameController controller=get(app,"controller");controller.loseFocus();assertEquals(State.PAUSED,model.snapshot().state());
                model.home();app.refresh();click(scene,"#home-start");click(scene,"#mode-DUEL");
                stage.setWidth(980);stage.setHeight(700);
                PauseTransition waitForResize=new PauseTransition(Duration.millis(300));
                waitForResize.setOnFinished(event->{
                    try {
                        assertTrue(scene.getWidth()<980,"Native resize must reach the scene before capture");
                        snapshot(app,stage,"07-small-ready");
                        Node start=scene.lookup("#start-button");
                        assertTrue(start.localToScene(start.getBoundsInLocal()).getMaxY()<=scene.getHeight(),"Start must stay visible on small windows");
                        click(scene,"#start-button");assertEquals(Mode.DUEL,model.snapshot().mode());
                        assertNotNull(scene.lookup("#player-panel-1"));
                        assertNotNull(scene.lookup("#player-panel-2"));
                        assertNotNull(scene.lookup("#tactical-radar-1"));
                        assertNotNull(scene.lookup("#tactical-radar-2"));
                        assertNotNull(scene.lookup("#pulse-button-1"));
                        assertNotNull(scene.lookup("#pulse-button-2"));
                        snapshot(app,stage,"08-small-duel");
                        model.home();app.refresh();snapshot(app,stage,"09-small-home");
                        click(scene,"#settings-button");snapshot(app,stage,"10-small-settings");
                        click(scene,"#page-back");
                        click(scene,"#leaderboard-button");snapshot(app,stage,"10b-small-leaderboard");
                        click(scene,"#leaderboard-close");
                        click(scene,"#home-start");click(scene,"#mode-SINGLE");click(scene,"#start-button");
                        set(model,"wallet",8);click(scene,"#shop-button");
                        click(scene,"#buy-TURRET");
                        ScrollPane shopScroll=scene.getRoot().lookupAll(".scroll-pane").stream()
                                .filter(n->n instanceof ScrollPane s && s.getContent().lookup("#shop-close")!=null)
                                .map(n->(ScrollPane)n).findFirst().orElseThrow();
                        shopScroll.setVvalue(1);snapshot(app,stage,"11-small-shop");
                        assertTrue(shopScroll.getViewportBounds().getHeight()>350,"Shop should use the available vertical space");
                        Node close=scene.lookup("#shop-close");
                        assertTrue(close.localToScene(close.getBoundsInLocal()).getMaxY()<=scene.getHeight(),"Shop return remains reachable");
                        click(scene,"#shop-deploy");double[] smallPlacement=findPlacement(model);
                        clickCanvas(scene,smallPlacement[0],smallPlacement[1]);
                        assertEquals(1,model.snapshot().turrets().size());
                        assertEquals(smallPlacement[1],model.snapshot().turrets().get(0).y(),.001);
                        model.home();app.refresh();
                        verifyOpeningBoundary(app,stage,model,test,uncaught);
                    } catch(Throwable error) {test.completeExceptionally(error);}
                    finally {if(test.isDone()) {app.stop();stage.close();}}
                });
                waitForResize.play();
            } catch(Throwable error) {test.completeExceptionally(error);app.stop();stage.close();}
        });
        try {test.get(60,TimeUnit.SECONDS);} finally {Platform.exit();}
    }
    private static void verifyOpeningBoundary(TankTroubleApp app,Stage stage,TankGameModel model,
                                               CompletableFuture<Void> test,List<Throwable> uncaught) throws Exception {
        HomePage home=get(app,"homePage");
        PauseTransition opening=new PauseTransition(Duration.millis(80));home.setOpeningAnimation(opening);
        home.openingLayer().getChildren().add(new Label("INTRO TEST"));
        click(stage.getScene(),"#home-start");
        assertEquals(State.HOME,model.snapshot().state(),"Preparation waits for animation completion");
        assertTrue(home.openingLayer().isVisible());
        stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
        Node skip=stage.getScene().lookup("#opening-skip");
        assertNotNull(skip,"Opening provides a skip control");
        var skipBounds=skip.localToScene(skip.getBoundsInLocal());
        assertTrue(skipBounds.getMaxX()>stage.getScene().getWidth()-220
                        &&skipBounds.getMaxY()>stage.getScene().getHeight()-140,
                "Skip control stays in the bottom-right corner");
        Node controls=stage.getScene().lookup(".opening-controls");
        assertNotNull(controls);
        assertTrue(controls.getBoundsInParent().getWidth()<360 && controls.getBoundsInParent().getHeight()<100,
                "Opening controls must not stretch into a video-covering overlay");
        var openingBackground=home.openingLayer().getBackground();
        assertTrue(openingBackground==null || openingBackground.getFills().stream()
                        .allMatch(fill->fill.getFill()==javafx.scene.paint.Color.TRANSPARENT),
                "Opening layer must stay transparent over the source video");
        Event.fireEvent(stage.getScene(),new KeyEvent(KeyEvent.KEY_PRESSED,"","",KeyCode.ESCAPE,false,false,false,false));
        assertFalse(home.openingLayer().isVisible());
        assertFalse(stage.getScene().lookup("#home-start").isDisabled());
        PauseTransition completion=new PauseTransition(Duration.millis(200));
        completion.setOnFinished(event->{
            try {
                assertEquals(State.HOME,model.snapshot().state(),"Cancelled opening must not enter preparation later");
                click(stage.getScene(),"#home-start");
                PauseTransition replay=new PauseTransition(Duration.millis(200));
                replay.setOnFinished(finished->{
                    try {
                        assertEquals(State.READY,model.snapshot().state());
                        assertNotNull(stage.getScene().lookup("#preparation-page"));
                        assertTrue(uncaught.isEmpty(),uncaught.toString());test.complete(null);
                    } catch(Throwable error) {test.completeExceptionally(error);}
                    finally {app.stop();stage.close();}
                });replay.play();
            } catch(Throwable error) {test.completeExceptionally(error);app.stop();stage.close();}
        });completion.play();
    }
    private static double[] findPlacement(TankGameModel model) {
        for(int y=40;y<720;y+=80) for(int x=40;x<960;x+=80)
            if(model.canDeployTurret(x,y)) return new double[]{x,y};
        throw new AssertionError("No valid turret location");
    }
    private static ImageView firstImage(Node node) {
        if(node instanceof ImageView image) return image;
        if(node instanceof Parent parent) {
            for(Node child:parent.getChildrenUnmodifiable()) {
                ImageView found=firstImage(child);
                if(found!=null) return found;
            }
        }
        return null;
    }
    private static void clickCanvas(Scene scene,double worldX,double worldY) {
        mouseCanvas(scene,worldX,worldY,MouseEvent.MOUSE_CLICKED);
    }
    private static void mouseCanvas(Scene scene,double worldX,double worldY,javafx.event.EventType<MouseEvent> type) {
        scene.getRoot().applyCss();scene.getRoot().layout();
        Canvas canvas=(Canvas)scene.lookup("#battle-canvas");
        double x=worldX*canvas.getWidth()/960,y=worldY*canvas.getHeight()/720;
        var scenePoint=canvas.localToScene(x,y);
        MouseEvent event=new MouseEvent(type,scenePoint.getX(),scenePoint.getY(),x,y,MouseButton.PRIMARY,1,
                false,false,false,false,false,false,false,false,false,true,
                new PickResult(canvas,new Point3D(x,y,0),0));
        Event.fireEvent(canvas,event);
    }
    private static void click(Scene scene,String selector) {
        scene.getRoot().applyCss();scene.getRoot().layout();
        Node node=scene.lookup(selector);assertNotNull(node,selector);
        assertFalse(node.isDisabled(),selector);
        if(node instanceof ButtonBase button) {
            button.fire();
        } else {
            javafx.geometry.Point2D point=node.localToScene(node.getBoundsInLocal().getWidth()/2,
                    node.getBoundsInLocal().getHeight()/2);
            Event.fireEvent(node,new MouseEvent(MouseEvent.MOUSE_CLICKED,point.getX(),point.getY(),
                    point.getX(),point.getY(),MouseButton.PRIMARY,1,
                    false,false,false,false,true,false,false,true,false,false,
                    new PickResult(node,point.getX(),point.getY())));
        }
    }
    private static void snapshot(TankTroubleApp app,Stage stage,String name) throws Exception {
        stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
        var drawFrame=TankTroubleApp.class.getDeclaredMethod("drawFrame",double.class);drawFrame.setAccessible(true);drawFrame.invoke(app,1.0);
        var image=stage.getScene().snapshot(null);
        Path folder=Path.of(System.getProperty("tanktrouble.screenshots","target/screenshots"));Files.createDirectories(folder);
        ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",folder.resolve(name+".png").toFile());
        long different=0;int first=image.getPixelReader().getArgb(0,0);
        for(int x=0;x<(int)image.getWidth();x+=10) for(int y=0;y<(int)image.getHeight();y+=10)
            if(image.getPixelReader().getArgb(x,y)!=first) different++;
        boolean battlefield=stage.getScene().lookup("#battle-canvas")!=null;
        assertTrue(different>(battlefield?1000:80),"Scene must render its page content");
    }

    private static void snapshot(Stage stage,String name) throws Exception {
        stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
        var image=stage.getScene().snapshot(null);
        Path folder=Path.of(System.getProperty("tanktrouble.screenshots","target/screenshots"));Files.createDirectories(folder);
        ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",folder.resolve(name+".png").toFile());
        long different=0;int first=image.getPixelReader().getArgb(0,0);
        for(int x=0;x<(int)image.getWidth();x+=10) for(int y=0;y<(int)image.getHeight();y+=10)
            if(image.getPixelReader().getArgb(x,y)!=first) different++;
        assertTrue(different>200,"Secondary window must render visible content");
    }
}
