package tanktrouble.view;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.entity.Entities.Tank;

/**
 * Renders the arena in every state worth <em>looking</em> at, so the shot and the aim beam can be
 * judged at real size instead of guessed at from the code.
 *
 * <p>A probe, not a regression test: it asserts nothing about what the frame contains - only that it
 * produced five real frames. The timer is stopped and the model is ticked by hand, because one frame
 * that depends on wall-clock pacing cannot be compared between two runs, and the subject here is how
 * a single frame looks - not how it animates. That is also why this does not need
 * {@code Robot.getScreenCapture}: the whole arena is a JavaFX canvas in this scene, and
 * {@code scene.snapshot} sees all of it.
 *
 * <p>Run on its own - it owns the toolkit for the life of the JVM:
 * {@code mvn -B test -Dtest=BulletAimProbeTest -DfxProbe=true -Dtanktrouble.probeDir=target/probe}
 */
@EnabledIfSystemProperty(named="fxProbe",matches="true")
class BulletAimProbeTest {
    @SuppressWarnings("unchecked") private static <T> T get(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return (T)field.get(object);
    }
    @BeforeAll static void startToolkit() throws Exception {
        CompletableFuture<Void> ready=new CompletableFuture<>();
        try {Platform.startup(()->ready.complete(null));}
        catch(IllegalStateException alreadyRunning) {return;}
        ready.get(15,TimeUnit.SECONDS);
    }
    @AfterAll static void stopToolkit() {Platform.exit();}

    /** One frame at a chosen clock, then the whole scene to a PNG. Returns what it wrote. */
    private static Path shot(TankTroubleApp app,Stage stage,double clock,String name) throws Exception {
        stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
        var drawFrame=TankTroubleApp.class.getDeclaredMethod("drawFrame",double.class);
        drawFrame.setAccessible(true);drawFrame.invoke(app,clock);
        Path folder=Path.of(System.getProperty("tanktrouble.probeDir","target/probe"));
        Files.createDirectories(folder);
        Path file=folder.resolve(name+".png");
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null),null),"png",file.toFile());
        return file;
    }

    @Test void everyStateWorthJudging() throws Exception {
        CompletableFuture<Void> done=new CompletableFuture<>();
        Platform.runLater(()->{
            try {
                TankTroubleApp app=new TankTroubleApp();
                Stage stage=new Stage();
                app.start(stage);
                BulletAimProbeTest.<AnimationTimer>get(app,"timer").stop();
                TankGameModel model=get(app,"model");
                BattleRenderer renderer=get(app,"renderer");

                // Single player: the AI fires on its own every second, so after ~1.8s of ticks there
                // are real shots in flight, some of them already bounced off a wall.
                model.selectMode(Mode.SINGLE);
                model.startBattle(7L);
                app.refresh();
                model.handleInput(0,Action.FIRE,true);
                for(int i=0;i<220;i++) model.tick(8);
                model.handleInput(0,Action.FIRE,false);
                app.refresh();
                List<Tank> tanks=get(model,"tanks");
                Tank p1=tanks.stream().filter(t->t.player==0).findFirst().orElseThrow();

                // One clock per shot, a fifth of a second apart. The bounce registered here walks
                // its whole 0.45s life across the series: 01 catches it fresh, 03 nearly gone.
                renderer.addBounce(0,p1.x,p1.y+40);
                List<Path> frames=new ArrayList<>();
                frames.add(shot(app,stage,1.00,"01-normal"));
                p1.aimMs=9000;
                frames.add(shot(app,stage,1.20,"02-aim"));
                p1.scatterMs=9000;
                frames.add(shot(app,stage,1.40,"03-scatter"));
                p1.scatterMs=0;p1.penetrateMs=9000;
                frames.add(shot(app,stage,1.60,"04-penetrate"));
                p1.aimMs=0;p1.penetrateMs=0;

                // Duel, so P1 and P2 shots are in the air at once and the seat colours can be
                // compared side by side. The pre-aim is a pickup, so only the bolts show here.
                model.home();
                model.selectMode(Mode.DUEL);
                model.startBattle(7L);
                app.refresh();
                model.handleInput(0,Action.FIRE,true);
                model.handleInput(1,Action.FIRE,true);
                for(int i=0;i<200;i++) model.tick(8);
                model.handleInput(0,Action.FIRE,false);
                model.handleInput(1,Action.FIRE,false);
                app.refresh();
                frames.add(shot(app,stage,2.00,"05-duel"));

                // The probe's own contract: five frames, each a real render rather than an empty
                // file. It still asserts nothing about what is *in* them - that is what a human
                // looking at the PNGs is for. A blank canvas of this size compresses to a few
                // hundred bytes, so the threshold only has to separate "rendered" from "empty".
                for(Path frame:frames)
                    assertTrue(Files.size(frame)>5000,frame.getFileName()+" must be a real frame, not a blank canvas");
                done.complete(null);
            } catch(Throwable failed) {done.completeExceptionally(failed);}
        });
        done.get(120,TimeUnit.SECONDS);
    }
}
