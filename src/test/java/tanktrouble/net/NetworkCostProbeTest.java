package tanktrouble.net;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Measures what one broadcast frame actually costs, so a reported stutter can be attributed to a
 * number instead of to the first suspicious line of code.
 *
 * <p>A probe, not a regression test. Run it on its own:
 * {@code mvn -B test -Dtest=NetworkCostProbeTest -DnetProbe=true}
 */
@EnabledIfSystemProperty(named="netProbe",matches="true")
class NetworkCostProbeTest {
    private static final int PLAYERS=5;
    /** Room.broadcastLoop runs every 8 ms and builds a snapshot on each pass; the wire wants 30 Hz. */
    private static final int SNAPSHOTS_PER_SECOND=1000/8+30;

    /** A maximal room with rounds in the air - the worst case a frame is ever sent in. */
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

    private static int bytes(Object value) throws IOException {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(ObjectOutputStream out=new ObjectOutputStream(buffer)) {out.writeObject(value);}
        return buffer.size();
    }

    /**
     * Streams {@code count} <em>distinct</em> frames, the way a room actually sends them. Reusing one
     * frame object would let the stream back-reference it and report a size no real broadcast has.
     */
    private static int streamed(TankGameModel game,int count,int resetEvery) throws IOException {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try(ObjectOutputStream out=new ObjectOutputStream(buffer)) {
            for(int i=0;i<count;i++) {
                out.writeObject(new Protocol.Frame(game.snapshot(),List.of()));
                out.flush();
                if(resetEvery>0&&(i+1)%resetEvery==0) out.reset();
            }
        }
        return buffer.size();
    }

    private static Snapshot withoutWalls(Snapshot s) {
        return new Snapshot(s.state(),s.mode(),s.result(),s.wave(),s.score(),s.coins(),s.kills(),
                s.shields(),s.elapsedMs(),s.seed(),s.intermissionMs(),s.tanks(),s.bullets(),
                List.of(),s.drops(),s.pickups(),s.portals(),s.zones(),s.turrets(),s.upgrades());
    }

    @Test void reportFrameEconomics() throws Exception {
        TankGameModel game=busyWorld();
        Snapshot snapshot=game.snapshot();
        Protocol.Frame frame=new Protocol.Frame(snapshot,List.of());

        System.out.println("=== world ===");
        System.out.printf("tanks=%d bullets=%d walls=%d pickups=%d coins=%d zones=%d turrets=%d%n",
                snapshot.tanks().size(),snapshot.bullets().size(),snapshot.walls().size(),
                snapshot.pickups().size(),snapshot.drops().size(),snapshot.zones().size(),
                snapshot.turrets().size());

        System.out.println("\n=== frame size on the wire ===");
        int full=bytes(frame);
        int noWalls=bytes(new Protocol.Frame(withoutWalls(snapshot),List.of()));
        System.out.printf("full frame    = %,d bytes%n",full);
        System.out.printf("without walls = %,d bytes%n",noWalls);
        System.out.printf("walls share   = %,d bytes (%.1f%%)%n",full-noWalls,100.0*(full-noWalls)/full);
        System.out.printf("at 30 Hz      = %.1f KB/s per client%n",full*30.0/1024);
        System.out.printf("x %d clients   = %.1f KB/s out of the box%n",PLAYERS,full*30.0*PLAYERS/1024);

        System.out.println("\n=== cost of TankGameModel.snapshot() ===");
        for(int i=0;i<200;i++) game.snapshot();
        int reps=2000;
        long t0=System.nanoTime();
        for(int i=0;i<reps;i++) game.snapshot();
        long perCall=(System.nanoTime()-t0)/reps;
        System.out.printf("per call      = %,d ns (%.3f ms)%n",perCall,perCall/1e6);
        System.out.printf("~%d calls/s   = %.0f ms of every second, per room%n",
                SNAPSHOTS_PER_SECOND,perCall*SNAPSHOTS_PER_SECOND/1e6);
        System.out.printf("only 30 of those reach the wire; %.1f%% is the state check at Room.java:415%n",
                100.0*125/SNAPSHOTS_PER_SECOND);

        System.out.println("\n=== ObjectOutputStream.reset() cadence (ClientConnection) ===");
        long e0=System.nanoTime();
        int everyMessage=streamed(game,30,1);
        long everyNanos=System.nanoTime()-e0;
        long p0=System.nanoTime();
        int periodic=streamed(game,30,30);
        long periodicNanos=System.nanoTime()-p0;
        long n0=System.nanoTime();
        int never=streamed(game,30,0);
        long neverNanos=System.nanoTime()-n0;
        double perEvery=everyNanos/1e6/30,perPeriodic=periodicNanos/1e6/30,perNever=neverNanos/1e6/30;
        System.out.printf("every message = %,7d bytes, %.4f ms/frame (was)%n",everyMessage,perEvery);
        System.out.printf("every 30      = %,7d bytes, %.4f ms/frame (now)%n",periodic,perPeriodic);
        System.out.printf("never         = %,7d bytes, %.4f ms/frame%n",never,perNever);
        System.out.printf("x5 clients @30Hz = %.0f / %.0f / %.0f ms per second%n",
                perEvery*30*5,perPeriodic*30*5,perNever*30*5);

        System.out.println("\n=== serializing the same world once per connection ===");
        long s0=System.nanoTime();
        for(int i=0;i<PLAYERS;i++) bytes(frame);
        long perConnection=(System.nanoTime()-s0)/PLAYERS;
        System.out.printf("one serialize = %.3f ms; x%d clients = %.3f ms per broadcast%n",
                perConnection/1e6,PLAYERS,perConnection*PLAYERS/1e6);
        System.out.printf("at 30 Hz      = %.0f ms/s spent re-serializing an identical world%n",
                perConnection*PLAYERS*30/1e6);
    }

    /** What the JavaFX thread pays per rendered frame, and what the reader thread pays per arrival. */
    @Test void reportClientSideCost() throws Exception {
        TankGameModel game=busyWorld();
        Snapshot from=game.snapshot();
        for(int i=0;i<50;i++) game.tick(8);
        Snapshot to=game.snapshot();
        System.out.printf("blending %d bullets and %d tanks%n",to.bullets().size(),to.tanks().size());

        Method interpolate=RemoteGameModel.class.getDeclaredMethod(
                "interpolate",Snapshot.class,Snapshot.class,double.class);
        interpolate.setAccessible(true);
        for(int i=0;i<200;i++) interpolate.invoke(null,from,to,0.5);
        int reps=2000;
        long t0=System.nanoTime();
        for(int i=0;i<reps;i++) interpolate.invoke(null,from,to,0.5);
        long perInterpolate=(System.nanoTime()-t0)/reps;
        System.out.printf("interpolate() = %,d ns (%.3f ms)%n",perInterpolate,perInterpolate/1e6);
        System.out.printf("at 60 fps     = %.0f ms of every second on the JavaFX thread%n",
                perInterpolate*60/1e6);
        int bullets=Math.max(1,to.bullets().size());
        System.out.printf("matching is O(n^2) with Math.hypot: ~%,d hypot calls/s at 60 fps%n",
                bullets*bullets*60);

        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        int frames=60;
        try(ObjectOutputStream out=new ObjectOutputStream(buffer)) {
            for(int i=0;i<frames;i++) {out.writeObject(new Protocol.Frame(game.snapshot(),List.of()));out.flush();}
        }
        byte[] wire=buffer.toByteArray();
        for(int warm=0;warm<3;warm++) {
            try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(wire))) {
                for(int i=0;i<frames;i++) in.readObject();
            }
        }
        long d0=System.nanoTime();
        try(ObjectInputStream in=new ObjectInputStream(new ByteArrayInputStream(wire))) {
            for(int i=0;i<frames;i++) in.readObject();
        }
        long perRead=(System.nanoTime()-d0)/frames;
        System.out.printf("%nwire stream   = %,d bytes for %d frames (%,d bytes/frame)%n",wire.length,frames,wire.length/frames);
        System.out.printf("readObject()  = %,d ns (%.3f ms) per frame%n",perRead,perRead/1e6);
        System.out.printf("at 30 Hz      = %.0f ms of every second on the reader thread%n",perRead*30/1e6);
        System.out.printf("and %.0f KB/s of wire bytes allocated and discarded, before object overhead%n",
                wire.length/(double)frames*30/1024);
    }

    /**
     * The client holds frames back by 45 ms so it always has two to blend between, but frames only
     * arrive every 33.3 ms - about 12 ms of slack. Past that there is no newer frame, and
     * {@code snapshot()} returns the newest one unchanged rather than extrapolating, so the world
     * stands still until the next arrival.
     */
    @Test void reportInterpolationStarvationUnderJitter() {
        final long DELAY=45_000_000L,FRAME=33_333_333L,RENDER=16_666_667L;
        System.out.println("delivery jitter   render frames with no newer snapshot");
        for(int jitterMs:new int[]{0,2,5,10,15,20,30}) {
            Random random=new Random(12345);
            java.util.ArrayDeque<Long> arrivals=new java.util.ArrayDeque<>();
            long nextArrival=0;
            int starved=0,total=0;
            for(long now=0;now<10_000_000_000L;now+=RENDER) {
                while(nextArrival<=now) {
                    arrivals.addLast(nextArrival);
                    while(arrivals.size()>12) arrivals.removeFirst();
                    nextArrival+=FRAME+(long)((random.nextDouble()*2-1)*jitterMs*1_000_000L);
                }
                long target=now-DELAY;
                boolean hasNewer=false;
                for(long arrival:arrivals) if(arrival>target) {hasNewer=true;break;}
                total++;
                if(!hasNewer) starved++;
            }
            System.out.printf("%8d ms       %5.1f%%   (%d of %d)%n",jitterMs,100.0*starved/total,starved,total);
        }
    }

    private static Snapshot withBullets(List<BulletView> bullets) {
        return new Snapshot(State.RUNNING,Mode.MULTI,Result.NONE,1,0,0,0,0,0,0,0,
                List.of(),bullets,List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }

    private static Snapshot interpolate(Snapshot from,Snapshot to,double amount) throws Exception {
        Method method=RemoteGameModel.class.getDeclaredMethod(
                "interpolate",Snapshot.class,Snapshot.class,double.class);
        method.setAccessible(true);
        return (Snapshot)method.invoke(null,from,to,amount);
    }

    /**
     * A bullet despawns and the same player fires a new one. The two are unrelated, but they share an
     * owner, and the distance gate that should separate them compares a real distance against a
     * squared bound, so it never fires: the new round is matched to the dead one and rendered
     * streaking across the arena instead of appearing at the muzzle.
     */
    @Test void reportBulletMatchingWhenOneBulletReplacesAnother() throws Exception {
        Snapshot from=withBullets(List.of(new BulletView(900,400,0,0,7,0,false)));
        Snapshot to=withBullets(List.of(new BulletView(100,400,0,0,7,0,false)));

        Snapshot blended=interpolate(from,to,0.5);
        BulletView result=blended.bullets().get(0);
        System.out.printf("dead round at x=900, new round fired at x=100%n");
        System.out.printf("rendered half-way at x=%.1f (should be 100.0, the muzzle)%n",result.x());
        assertTrue(Math.abs(result.x()-100)<16,
                "A freshly fired bullet must appear at the muzzle, not be dragged from a dead round: x="+result.x());
    }

    /** Two live rounds trading places in the list must still be matched to their own predecessors. */
    @Test void reportBulletMatchingWhenTwoRoundsSwapOrder() throws Exception {
        Snapshot from=withBullets(List.of(
                new BulletView(100,100,0,0,7,0,false),
                new BulletView(800,600,0,0,7,0,false)));
        Snapshot to=withBullets(List.of(
                new BulletView(801,601,0,0,7,0,false),
                new BulletView(101,101,0,0,7,0,false)));

        Snapshot blended=interpolate(from,to,0.5);
        for(BulletView b:blended.bullets()) System.out.printf("half-way round at (%.1f, %.1f)%n",b.x(),b.y());
        boolean sane=blended.bullets().stream().allMatch(b->
                (Math.abs(b.x()-100.5)<2&&Math.abs(b.y()-100.5)<2)
                        ||(Math.abs(b.x()-800.5)<2&&Math.abs(b.y()-600.5)<2));
        assertTrue(sane,"Rounds that merely swapped list order must not jump between positions");
    }
}
