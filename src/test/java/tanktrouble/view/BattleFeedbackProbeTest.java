package tanktrouble.view;

import java.lang.reflect.Field;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javafx.application.Platform;
import javafx.animation.AnimationTimer;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Point2D;
import javafx.scene.canvas.Canvas;
import javafx.scene.image.*;
import javafx.stage.Stage;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import static org.junit.jupiter.api.Assertions.*;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.Tank;

/**
 * Renders each of the six battlefield-feedback groups in the real game, samples the pixels, and
 * writes the frame, so every group can be judged from numbers instead of from a thumbnail.
 *
 * <p>A probe, not a regression test: it is behind {@code -DfxProbe=true} and asserts only that each
 * group put pixels on the screen that the same arena does not have without it. What the effect
 * <em>looks</em> like is still a human's call; what is checked here is that it is there at all, is
 * the right size to matter, and is where it claims to be.
 *
 * <p><b>The comparison is a diff, not a colour count.</b> Each group is drawn twice at the very same
 * clock - once through a renderer that never raises an effect and once through the app's - and the
 * two PNGs are subtracted pixel by pixel. Both frames agree on everything the arena animates on its
 * own (portals breathe, gravity rings spin, the turret lamps pulse: all keyed on the clock), so the
 * difference that survives is the effect and nothing else. Counting "pixels near the effect's
 * nominal colour" instead was tried first and was not usable: everything here is painted additively
 * at a fading alpha, so the pixels an effect leaves are a blend with whatever was underneath, and
 * the nominal colour is mostly absent from the frame.
 *
 * <p><b>The clock is hand-made.</b> Each group is raised through the renderer's own {@code addEffect}
 * seam and stamped on one draw, then painted on a second draw exactly {@code age} later - see
 * {@link BattleRenderer}, which has no clock at the call site and stamps on the first frame it sees.
 * Wall-clock pacing would make two runs differ in how far every effect had got, which is the one
 * thing this probe exists to rule out; that is also why the six groups are drawn with the
 * {@link AnimationTimer} stopped.
 *
 * <p><b>The live path is checked first, and there the timer is left alone.</b> A stopped timer is
 * what keeps {@code FxSmokeTest} from exercising the real path at all: it stops the timer before the
 * first pulse, so nothing ever drains the model's events into {@code reactTo}. This probe lets the
 * running timer deliver events on its own and asserts that effects arrived that way, before taking
 * the clock over for the pictures.
 *
 * <p><b>Only the battle page can be captured on this machine.</b> The home page carries the opening
 * video's {@link javafx.scene.media.MediaView} over the whole scene, and a session that cannot
 * decode video gets an opaque black frame from it: {@code scene.snapshot} then returns the entire
 * window as one black colour, which is what {@code FxSmokeTest:333} trips over. That is a property
 * of the machine and not of this code - a build of the pre-change sources emits the same black frame
 * - so every capture here is taken with {@code page==BATTLE}, where the home page is not in the
 * scene at all.
 *
 * <p>Run on its own - it owns the toolkit for the life of the JVM:
 * {@code mvn -B test -Dtest=BattleFeedbackProbeTest -DfxProbe=true -Dtanktrouble.probeDir=target/probe-feedback}
 */
@EnabledIfSystemProperty(named="fxProbe",matches="true")
class BattleFeedbackProbeTest {
    /**
     * Where the hand-made clock starts.
     *
     * <p>Well above any wall-clock reading the live segment drew at: the app draws with
     * {@code System.nanoTime()/1e9}, whose origin is arbitrary, and whatever the live segment left
     * in the renderer has to be expired by the first manual frame rather than painted into a
     * picture. One frame at this clock clears it, because expiry is a comparison against the clock
     * the frame carries and every effect stamped so far is then a billion seconds old.
     */
    private static final double BASE_CLOCK=1e9;
    /** How far the clock jumps between groups, so one group's effect cannot bleed into the next. */
    private static final double GROUP_GAP_S=2.0;
    /**
     * How far apart two channels have to be before the pixel counts as changed.
     *
     * <p>Both frames are drawn at the same clock by the same code, so an identical pixel is exactly
     * identical and even a threshold of one would do; this only has to absorb the last bit of a
     * float, and one in 32 of the scale is far below anything an effect lays down.
     */
    private static final int CHANNEL_DELTA=8;
    /**
     * The colour resolution the "what is new here" report quantises to: five bits a channel.
     *
     * <p>Fine enough that a signature colour is still recognisable in the top entry, coarse enough
     * that the antialiased cloud around it lands in a handful of buckets instead of thousands.
     */
    private static final int QUANTISE_DROP_BITS=3;
    /**
     * How many pixels a group has to change, inside its own anchor box, before the frame counts as
     * showing it.
     *
     * <p>Set per group from what the effect actually covers: the smallest (the block's impact core)
     * is a twelve-pixel disc with sparks around it, the largest (a wave scan) crosses the whole
     * field. Every threshold is an order of magnitude below the observed value and two orders above
     * the antialiasing noise, so it fails on a missing effect and on nothing else.
     */
    private static final Map<String,Long> MINIMUM_CHANGE=new LinkedHashMap<>();
    static {
        MINIMUM_CHANGE.put("01-hit",400L);
        MINIMUM_CHANGE.put("02-muzzle",300L);
        MINIMUM_CHANGE.put("03-pickup",200L);
        MINIMUM_CHANGE.put("04-shield-pulse-portal",400L);
        MINIMUM_CHANGE.put("05-coin-lava",300L);
        MINIMUM_CHANGE.put("06-wave",2000L);
    }
    /**
     * A blank canvas of this size compresses to a few hundred bytes and this arena to a quarter of a
     * megabyte, so this only has to separate "rendered" from "empty".
     */
    private static final long REAL_FRAME_BYTES=20000;

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

    /** One frame's pixels, flat. */
    private record Frame(int width,int height,int[] pixels) {}
    /** What one group changed, where, and in what colours. */
    private record Diff(String name,long changed,long changedInAnchor,long anchorArea,
                        int[] changedBounds,List<String> newColours) {}

    /**
     * What the FX thread hands the test thread, and the frame work that has to run on it.
     *
     * <p>A holder rather than a stack of locals because the live check has to run from the test
     * thread: the FX thread cannot both hold the app and wait for its own pulses to deliver events.
     */
    private static final class Probe {
        TankTroubleApp app;Stage stage;Canvas canvas;TankGameModel model;
        /** The app's own renderer - the one carrying every effect this probe raises. */
        BattleRenderer renderer;
        /**
         * A second renderer on the same canvas that is never given an effect.
         *
         * <p>It exists so a group's frame can be compared with the same arena at the same instant.
         * Drawing the app's renderer at a later clock to clear the last group is what makes any
         * other comparison drift: the arena's own animated detail moves with the clock, and that
         * movement would outnumber the effect in the small boxes these frames are read in.
         */
        BattleRenderer bare;
        int effectsFromTheLiveLoop;
        final Map<String,Diff> diffs=new LinkedHashMap<>();
        final Map<String,Path> files=new LinkedHashMap<>();

        Path folder() {return Path.of(System.getProperty("tanktrouble.probeDir","target/probe-feedback"));}
        void write(String name,Image image) throws Exception {
            Path folder=folder();Files.createDirectories(folder);
            Path file=folder.resolve(name+".png");
            ImageIO.write(SwingFXUtils.fromFXImage(image,null),"png",file.toFile());
            files.put(name,file);
        }
        Image snap() throws Exception {
            stage.getScene().getRoot().applyCss();stage.getScene().getRoot().layout();
            return stage.getScene().snapshot(null);
        }
        /**
         * The frame the live loop last drew, with no manual draw at all.
         *
         * <p>Drawing here would move the renderer's clock off the live one and repaint every effect
         * the live loop had just stamped at an age it never had, and the picture would stop being
         * evidence of what the real path produced.
         */
        void liveFrame(String name) throws Exception {write(name,snap());}

        /** The anchor box in window pixels, for an effect whose world-space rect is given. */
        int[] anchor(double wx0,double wy0,double wx1,double wy1) {
            Point2D a=toScene(wx0,wy0),b=toScene(wx1,wy1);
            return new int[]{(int)Math.floor(Math.min(a.getX(),b.getX())),(int)Math.floor(Math.min(a.getY(),b.getY())),
                    (int)Math.ceil(Math.max(a.getX(),b.getX())),(int)Math.ceil(Math.max(a.getY(),b.getY()))};
        }
        private Point2D toScene(double worldX,double worldY) {
            return canvas.localToScene(worldX*canvas.getWidth()/Rules.WIDTH,worldY*canvas.getHeight()/Rules.HEIGHT);
        }

        /**
         * Raises one group and writes the two frames it is read between, both at the same clock.
         *
         * <p>The order matters. {@code raisedAtStamp} adds the effects the frame is about, and the
         * draw at {@code stampClock} gives them a start clock; the before/after pair is then taken
         * at {@code stampClock+age}, which is the age the frame is meant to show. Both draws come
         * from one {@link Snapshot} instance, so the model is not even asked twice.
         *
         * <p>{@code raisedOnRead} exists for a group whose halves have different lives and can only
         * be caught together: an effect added there is stamped on the draw that produces the after
         * frame, so it lands at age zero while everything from the stamp draw has already aged by
         * {@code age}. Without the split, a lava patch with a third of a second to live would be
         * long gone by the time a coin was old enough to show its "+1".
         */
        void group(String name,double stampClock,double age,int[] anchor,
                   Runnable raisedAtStamp,Runnable raisedOnRead) throws Exception {
            Snapshot scene=model.snapshot();
            raisedAtStamp.run();
            renderer.draw(scene,stampClock);
            double clock=stampClock+age;
            raisedOnRead.run();
            bare.draw(scene,clock);
            Frame before=read(snap());
            renderer.draw(scene,clock);
            Frame after=read(snap());
            write(name,toImage(after));
            diffs.put(name,compare(name,before,after,anchor));
        }
        /** The common case: everything this group shows is raised at the stamp, and shares one age. */
        void group(String name,double stampClock,double age,int[] anchor,Runnable raise) throws Exception {
            group(name,stampClock,age,anchor,raise,()->{ });
        }
    }

    @Test void sixFeedbackGroupsAreDrawnAndSampled() throws Exception {
        Probe probe=new Probe();
        CompletableFuture<Void> booted=new CompletableFuture<>();
        Platform.runLater(()->{
            try {
                probe.app=new TankTroubleApp();probe.stage=new Stage();
                probe.app.start(probe.stage);
                probe.model=get(probe.app,"model");
                probe.model.selectMode(Mode.SINGLE);
                probe.model.startBattle(7L);
                // Enough ticks for wave 1 to be placed and moving, then a shot queued the ordinary
                // way. The model does not drain its own events - whoever calls drainEvents does, and
                // that is the animation timer, which is still running at this point.
                for(int i=0;i<70;i++) probe.model.tick(8);
                probe.model.handleInput(0,Action.FIRE,true);
                probe.app.refresh();
                assertEquals(State.RUNNING,probe.model.snapshot().state(),"the probe needs a live arena");
                // Only now: on the home page the battle canvas is not in the scene graph at all, and
                // the lookup would answer null.
                probe.stage.getScene().getRoot().applyCss();probe.stage.getScene().getRoot().layout();
                probe.canvas=(Canvas)probe.stage.getScene().lookup("#battle-canvas");
                assertNotNull(probe.canvas,"the battle canvas has to be on screen before it can be read");
                probe.renderer=get(probe.app,"renderer");
                probe.bare=new BattleRenderer(probe.canvas);
                booted.complete(null);
            } catch(Throwable failed) {booted.completeExceptionally(failed);}
        });
        booted.get(60,TimeUnit.SECONDS);

        // The live path: the timer is NOT stopped, so the queued events are drained by the real
        // frame loop and routed through TankTroubleApp.reactTo into the renderer. Polling from this
        // thread rather than from the FX thread is what lets pulses actually run between reads.
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
        while(probe.effectsFromTheLiveLoop==0&&System.nanoTime()<deadline) {
            CompletableFuture<Integer> effects=new CompletableFuture<>();
            Platform.runLater(()->effects.complete(probe.renderer.effectCount()));
            probe.effectsFromTheLiveLoop=effects.get(5,TimeUnit.SECONDS);
            if(probe.effectsFromTheLiveLoop==0) Thread.sleep(40);
        }
        assertTrue(probe.effectsFromTheLiveLoop>0,
            "The running timer must drain the model's events into the renderer; otherwise the live "
                +"path carries no battlefield feedback at all, whatever the hand-made clock shows");

        CompletableFuture<Void> drawn=new CompletableFuture<>();
        Platform.runLater(()->{
            try {sixGroups(probe);drawn.complete(null);}
            catch(Throwable failed) {drawn.completeExceptionally(failed);}
        });
        drawn.get(240,TimeUnit.SECONDS);

        System.out.print(report(probe.diffs));
        // The live frame is not read against anything - it is whatever the real loop happened to be
        // drawing - so size is the only thing that can be asked of it, and it is the whole point of
        // the picture: a blank one would leave the live path unproven.
        assertTrue(Files.size(probe.files.get("07-live"))>REAL_FRAME_BYTES,
            "the frame the live loop drew must be a real rendered frame too");
        for(Map.Entry<String,Long> expected:MINIMUM_CHANGE.entrySet()) {
            String name=expected.getKey();
            Diff diff=probe.diffs.get(name);
            assertNotNull(diff,name+" must have been drawn");
            assertTrue(Files.size(probe.files.get(name))>REAL_FRAME_BYTES,
                name+" must be a real rendered frame, not a blank canvas");
            assertTrue(diff.changedInAnchor()>expected.getValue(),
                name+" must change the arena where its effect sits: "+diff.changedInAnchor()
                    +" pixels changed inside the "+(long)Math.sqrt(diff.anchorArea())+"px box, against a "
                    +"floor of "+expected.getValue());
            // The anchor doubles as the bound on how far this effect may reach: everything it paints
            // has to land inside it. Vacuously true for the wave, whose anchor is the whole window -
            // a scan line is the width of the field by design - and a real bound on the other five.
            assertEquals(diff.changed(),diff.changedInAnchor(),
                name+" must paint inside its own anchor and nowhere else; it changed "
                    +diff.changed()+" pixels in total and "+diff.changedInAnchor()+" of them in the box");
            assertFalse(diff.newColours().isEmpty(),
                name+" must put down at least one colour the arena did not have there");
        }
    }

    /** The six groups, one before/after pair each, plus the frame the live loop drew. */
    private static void sixGroups(Probe probe) throws Exception {
        probe.liveFrame("07-live");
        // From here the clock is the probe's. The live segment's effects were stamped against
        // System.nanoTime()/1e9, so the first manual frame - a billion seconds later - expires them
        // and leaves the app's renderer as empty as the bare one. The same draw writes 00-baseline,
        // which is the arena the frames are read against before any group exists.
        BattleFeedbackProbeTest.<AnimationTimer>get(probe.app,"timer").stop();
        // The arena is rebuilt from a fixed seed and a fixed number of ticks before anything is
        // measured. The live segment ran for however many pulses it took for an effect to arrive,
        // which is not the same number twice, so its arena is not the same twice either - and a
        // figure that only means something against last run's layout is not worth writing down.
        // From here on nothing wall-clock enters, so two runs produce identical bytes.
        probe.model.home();
        probe.model.selectMode(Mode.SINGLE);
        probe.model.startBattle(7L);
        for(int i=0;i<70;i++) probe.model.tick(8);
        probe.app.refresh();
        // One layout before anything is measured: the anchors are world positions mapped through the
        // canvas's own transform, and a canvas that has not been placed maps them all to one point.
        probe.stage.getScene().getRoot().applyCss();probe.stage.getScene().getRoot().layout();
        probe.group("00-baseline",BASE_CLOCK,0,probe.anchor(0,0,Rules.WIDTH,Rules.HEIGHT),()->{ });

        List<Tank> tanks=get(probe.model,"tanks");
        Tank p1=tanks.stream().filter(t->t.player==0).findFirst().orElseThrow();
        Tank enemy=tanks.stream().filter(t->t.player<0).findFirst().orElse(null);
        double px=p1.x,py=p1.y;
        double clock=BASE_CLOCK;

        clock+=GROUP_GAP_S;
        // A hit lands on P1's hull, fired from the west by the AI side (-1): the flash rides the
        // hull, the ring and sparks ride the impact point, and the sparks fan back down the bearing.
        probe.group("01-hit",clock,.05,probe.anchor(px-60,py-60,px+60,py+60),
            ()->probe.renderer.addEffect(GameEventType.HIT_ENEMY,0,px,py,-1,Math.PI));

        clock+=GROUP_GAP_S;
        // P1's own shot: the flash sits on the barrel tip, the ring opens around it, and the hull is
        // pushed back along the reverse of its heading for the length of the recoil window.
        probe.group("02-muzzle",clock,.04,probe.anchor(px-70,py-70,px+70,py+70),
            ()->probe.renderer.addEffect(GameEventType.PLAYER_FIRE,0,px,py,-1,Double.NaN));

        clock+=GROUP_GAP_S;
        // Scatter: the glyph has flown to P1 and the preview is under way, which is the moment the
        // item's own effect is shown rather than just the box that was picked up.
        probe.group("03-pickup",clock,.45,probe.anchor(px-70,py-70,px+70,py+70),
            ()->probe.renderer.addEffect(GameEventType.ITEM_SCATTER,0,px+150,py-90,-1,Double.NaN));

        clock+=GROUP_GAP_S;
        // Three at once, all a tenth of a second old: the block's arc and core on the west flank of
        // P1's shield, a pulse ring beside it, a teleport ring expanding on the other side.
        // The anchor has to hold the whole of the pulse: its ring opens to Rules.PULSE_RADIUS, which
        // is wider than the offset between the three effects.
        probe.group("04-shield-pulse-portal",clock,.10,probe.anchor(px-200,py-170,px+280,py+170),()->{
            probe.renderer.addEffect(GameEventType.SHIELD_BLOCK,0,px,py,1,Math.PI);
            probe.renderer.addEffect(GameEventType.PULSE,0,px+110,py,-1,Double.NaN);
            probe.renderer.addEffect(GameEventType.TELEPORT,0,px-110,py,-1,Double.NaN);
        });

        clock+=GROUP_GAP_S;
        // The coin is stamped first and read half a second later, when the flight is over and the
        // "+1" is up; the lava is stamped on that same frame, so its patch and body tint are fresh -
        // the two halves of this group have very different lives and only overlap at this instant.
        probe.group("05-coin-lava",clock,.55,probe.anchor(px-80,py-80,px+80,py+80),
            ()->probe.renderer.addEffect(GameEventType.COIN,0,enemy!=null?enemy.x:px+220,
                    enemy!=null?enemy.y:py-160,-1,Double.NaN),
            ()->probe.renderer.addEffect(GameEventType.LAVA,0,px,py,-1,Double.NaN));

        clock+=GROUP_GAP_S;
        // The wave's own moment: the scan line partway down, the ring partway out, the banner up.
        // Its anchor is the whole window, because a scan line is the width of the field by design.
        probe.group("06-wave",clock,.18,probe.anchor(0,0,Rules.WIDTH,Rules.HEIGHT),
            ()->probe.renderer.addEffect(GameEventType.WAVE_START,0,0,0,-1,Double.NaN));
    }

    private static Frame read(Image image) {
        int width=(int)image.getWidth(),height=(int)image.getHeight();
        int[] pixels=new int[width*height];
        image.getPixelReader().getPixels(0,0,width,height,PixelFormat.getIntArgbInstance(),pixels,0,width);
        return new Frame(width,height,pixels);
    }
    private static Image toImage(Frame frame) {
        WritableImage image=new WritableImage(frame.width(),frame.height());
        image.getPixelWriter().setPixels(0,0,frame.width(),frame.height(),
                PixelFormat.getIntArgbInstance(),frame.pixels(),0,frame.width());
        return image;
    }

    /**
     * What the second frame has that the first does not, in total and inside the anchor box.
     *
     * <p>The palette is reported as well as the count: it is the one piece of evidence a human can
     * check against the effect they meant to see, without having to open the PNG. Quantised rather
     * than exact for the reason in {@link #QUANTISE_DROP_BITS} - an antialiased edge is a colour ramp,
     * and exact values would spread one effect over thousands of buckets.
     */
    private static Diff compare(String name,Frame before,Frame after,int[] anchor) {
        long changed=0,inAnchor=0;
        int[] palette=new int[1<<(3*(8-QUANTISE_DROP_BITS))];
        int shift=8-QUANTISE_DROP_BITS;
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE;
        for(int y=0;y<after.height();y++) for(int x=0;x<after.width();x++) {
            int index=y*after.width()+x,b=before.pixels()[index],a=after.pixels()[index];
            if(Math.abs(((a>>16)&255)-((b>>16)&255))<=CHANNEL_DELTA
                    &&Math.abs(((a>>8)&255)-((b>>8)&255))<=CHANNEL_DELTA
                    &&Math.abs((a&255)-(b&255))<=CHANNEL_DELTA) continue;
            changed++;
            minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);
            if(x<anchor[0]||x>=anchor[2]||y<anchor[1]||y>=anchor[3]) continue;
            inAnchor++;
            palette[(((a>>16)&255)>>shift<<(2*shift))|((((a>>8)&255)>>shift)<<shift)|((a&255)>>shift)]++;
        }
        long area=(long)(anchor[2]-anchor[0])*(anchor[3]-anchor[1]);
        int[] bounds=changed==0?new int[]{0,0,0,0}:new int[]{minX,minY,maxX,maxY};
        return new Diff(name,changed,inAnchor,area,bounds,dominant(palette,shift));
    }
    /** The handful of colours the effect put down most of, as hex, biggest first. */
    private static List<String> dominant(int[] palette,int shift) {
        record Bucket(int colour,int count) {}
        List<Bucket> buckets=new ArrayList<>();
        for(int i=0;i<palette.length;i++) if(palette[i]>0) {
            int r=((i>>(2*shift))<<shift),g=(((i>>shift)&((1<<shift)-1))<<shift),b=((i&((1<<shift)-1))<<shift);
            buckets.add(new Bucket((r<<16)|(g<<8)|b,palette[i]));
        }
        buckets.sort((left,right)->right.count()-left.count());
        List<String> top=new ArrayList<>();
        for(int i=0;i<Math.min(5,buckets.size());i++)
            top.add(String.format("#%06x x%d",buckets.get(i).colour(),buckets.get(i).count()));
        return top;
    }

    /** One line per group, so a run leaves the numbers behind as well as the pictures. */
    static String report(Map<String,Diff> diffs) {
        StringBuilder text=new StringBuilder("battle-feedback probe: pixels changed against the same arena at the same clock\n");
        for(Diff diff:diffs.values())
            text.append("  ").append(diff.name())
                .append(" : changed=").append(diff.changed())
                .append(" inAnchor=").append(diff.changedInAnchor())
                .append('/').append(diff.anchorArea())
                .append(" bounds=").append(Arrays.toString(diff.changedBounds()))
                .append(" newColours=").append(diff.newColours())
                .append('\n');
        return text.toString();
    }
}
