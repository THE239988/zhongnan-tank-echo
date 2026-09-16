package tanktrouble.view;

import java.util.concurrent.*;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;

/**
 * Times one {@link BattleRenderer#draw} against a full room, because every other per-frame cost on
 * the client was measured at well under a millisecond and rendering was the only one left.
 *
 * <p>A probe, not a regression test. It draws to an offscreen canvas that is never added to a scene:
 * the cost being measured is the drawing itself, not compositing or display. Run on its own:
 * {@code mvn -B test -Dtest=RenderCostProbeTest -DfxProbe=true}
 */
@EnabledIfSystemProperty(named="fxProbe",matches="true")
class RenderCostProbeTest {
    private static final int PLAYERS=5;

    @BeforeAll static void startToolkit() throws Exception {
        CompletableFuture<Void> ready=new CompletableFuture<>();
        try {Platform.startup(()->ready.complete(null));}
        catch(IllegalStateException alreadyRunning) {return;}
        ready.get(15,TimeUnit.SECONDS);
    }

    @AfterAll static void stopToolkit() {Platform.exit();}

    /** A maximal room with rounds in the air - the heaviest frame the renderer ever has to draw. */
    private static TankGameModel busyWorld() {
        TankGameModel game=new TankGameModel();
        game.selectMode(Mode.MULTI);
        game.setTerrainEnabled(true);
        game.startBattle(42,PLAYERS);
        for(int step=0;step<400;step++) {
            for(int player=0;player<PLAYERS;player++) game.handleInput(player,Action.FIRE,true);
            game.tick(8);
        }
        return game;
    }

    @Test void reportRenderCostPerFrame() throws Exception {
        CompletableFuture<Void> done=new CompletableFuture<>();
        Platform.runLater(()->{
            try {
                Snapshot snapshot=busyWorld().snapshot();
                Canvas canvas=new Canvas(Rules.WIDTH,Rules.HEIGHT);
                BattleRenderer renderer=new BattleRenderer(canvas);

                System.out.printf("drawing %d tanks, %d bullets, %d walls, %d zones, %d pickups "
                                + "onto a %dx%d canvas%n",
                        snapshot.tanks().size(),snapshot.bullets().size(),snapshot.walls().size(),
                        snapshot.zones().size(),snapshot.pickups().size(),Rules.WIDTH,Rules.HEIGHT);

                // Burn in, so the first measured draw is not paying for JIT and canvas setup.
                for(int i=0;i<300;i++) renderer.draw(snapshot,1.0+i*0.001);
                int reps=1000;
                long t0=System.nanoTime();
                for(int i=0;i<reps;i++) renderer.draw(snapshot,1.0+i*0.001);
                long perDraw=(System.nanoTime()-t0)/reps;

                System.out.printf("one draw()      = %,d ns (%.3f ms)%n",perDraw,perDraw/1e6);
                System.out.printf("frame budget    = 16.667 ms at 60 fps; draw uses %.1f%% of it%n",
                        100.0*perDraw/1e6/16.667);
                System.out.printf("at 60 fps       = %.0f ms of every second on the JavaFX thread%n",
                        perDraw*60/1e6);

                // A quiet frame, for contrast: the same canvas with nothing in the air.
                TankGameModel calm=new TankGameModel();
                calm.selectMode(Mode.MULTI);
                calm.startBattle(42,PLAYERS);
                Snapshot quiet=calm.snapshot();
                for(int i=0;i<300;i++) renderer.draw(quiet,1.0+i*0.001);
                long q0=System.nanoTime();
                for(int i=0;i<reps;i++) renderer.draw(quiet,1.0+i*0.001);
                long perQuietDraw=(System.nanoTime()-q0)/reps;
                System.out.printf("same draw with %d bullets = %.3f ms%n",
                        quiet.bullets().size(),perQuietDraw/1e6);

                assertTrue(perDraw>0,"the probe must actually have drawn something");
                done.complete(null);
            } catch(Throwable failed) {done.completeExceptionally(failed);}
        });
        done.get(120,TimeUnit.SECONDS);
    }
}
