package tanktrouble.view;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Stage;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;

/**
 * Renders the armour readout once per tank model, because the defect it guards only appears with the
 * longest name: the card is a fixed width, and a name-and-numbers string that overflows it is
 * ellipsized from the end - which is where the numbers are.
 *
 * <p>A probe, not a regression test: it asserts only that a real frame was produced. Judging the
 * frame is what the PNGs are for. Run on its own:
 * {@code mvn -B test -Dtest=HealthReadoutProbeTest -DfxProbe=true -Dtanktrouble.probeDir=target/probe}
 */
@EnabledIfSystemProperty(named="fxProbe",matches="true")
class HealthReadoutProbeTest {
    @SuppressWarnings("unchecked") private static <T> T get(Object object,String name) throws Exception {
        Field field=object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T)field.get(object);
    }

    @BeforeAll static void startToolkit() throws Exception {
        CompletableFuture<Void> ready=new CompletableFuture<>();
        try {Platform.startup(()->ready.complete(null));}
        catch(IllegalStateException alreadyRunning) {return;}
        ready.get(15,TimeUnit.SECONDS);
    }

    @AfterAll static void stopToolkit() {Platform.exit();}

    private static Path shot(TankTroubleApp app,Stage stage,double clock,String name) throws Exception {
        stage.getScene().getRoot().applyCss();
        stage.getScene().getRoot().layout();
        var drawFrame=TankTroubleApp.class.getDeclaredMethod("drawFrame",double.class);
        drawFrame.setAccessible(true);
        drawFrame.invoke(app,clock);
        Path folder=Path.of(System.getProperty("tanktrouble.probeDir","target/probe"));
        Files.createDirectories(folder);
        Path file=folder.resolve(name+".png");
        ImageIO.write(SwingFXUtils.fromFXImage(stage.getScene().snapshot(null),null),"png",file.toFile());
        return file;
    }

    @Test void everyTankModelFitsItsArmourReadout() throws Exception {
        CompletableFuture<Void> done=new CompletableFuture<>();
        Platform.runLater(()->{
            try {
                TankTroubleApp app=new TankTroubleApp();
                Stage stage=new Stage();
                app.start(stage);
                HealthReadoutProbeTest.<AnimationTimer>get(app,"timer").stop();
                TankGameModel model=get(app,"model");

                for(TankType type:TankType.values()) {
                    // Duel, so both character columns are on screen at once.
                    model.home();
                    model.selectMode(Mode.DUEL);
                    model.selectTank(0,type);
                    model.selectTank(1,type);
                    model.startBattle(7L);
                    app.refresh();
                    Path frame=shot(app,stage,1.0,"armour-"+type.name().toLowerCase());
                    assertTrue(Files.size(frame)>5000,frame.getFileName()+" must be a real frame");
                }
                done.complete(null);
            } catch(Throwable failed) {done.completeExceptionally(failed);}
        });
        done.get(120,TimeUnit.SECONDS);
    }
}
