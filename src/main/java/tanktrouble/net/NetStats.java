package tanktrouble.net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;

/**
 * Optional timing instrumentation for a networked match, written to a file for later reading.
 *
 * <p>Off unless {@code -Dtanktrouble.netStats=<file>} is set, so an ordinary game pays nothing
 * beyond a static boolean read.
 *
 * <p>It exists because the reported stutter resisted every measurement taken from outside the
 * client. Each server-side cost came back cheap (a full room cost the server 2.3% of a core under
 * real play), and so did every per-call client cost, which left the <em>timing</em> of frame
 * delivery as the remaining suspect. Whether the world is being rendered frozen because no newer
 * frame has arrived is a fact about a real match against a real network; it cannot be reproduced by
 * a benchmark.
 *
 * <p>Everything here is called from the JavaFX thread. The writer thread only reads, under the
 * class lock.
 */
public final class NetStats {
    private static final String PATH=System.getProperty("tanktrouble.netStats");
    private static final boolean ON=PATH!=null&&!PATH.isBlank();
    /** Bounded so an hours-long session cannot grow without limit. */
    private static final int CAPACITY=1<<16;
    private static final long DUMP_INTERVAL_MS=5000;

    private static final long[] arrivalIntervals=new long[CAPACITY];
    private static final long[] pumpNanos=new long[CAPACITY];
    private static final long[] drawNanos=new long[CAPACITY];
    private static int arrivalCount,pumpCount,drawCount;
    /** Counted separately from arrivalCount: the first frame seeds the interval baseline only. */
    private static int framesReceived;
    private static int starvedNoNewer,renderFrames;
    private static long firstArrival,lastArrival,startNanos;
    private static Thread dumper;

    private NetStats() {}

    /** True when instrumentation was requested; callers may skip work when this is false. */
    public static boolean enabled() {return ON;}

    /**
     * Starts the periodic writer. Idempotent and cheap; call it once the window exists.
     *
     * <p>The report is rewritten every few seconds rather than written on exit, because a window
     * that is force-closed never runs shutdown code and the session would be lost.
     */
    public static void start() {
        if(!ON||dumper!=null) return;
        startNanos=System.nanoTime();
        dumper=new Thread(()->{
            while(!Thread.currentThread().isInterrupted()) {
                try {Thread.sleep(DUMP_INTERVAL_MS);} catch(InterruptedException stop) {return;}
                dump();
            }
        },"net-stats");
        dumper.setDaemon(true);
        dumper.start();
        Runtime.getRuntime().addShutdownHook(new Thread(NetStats::dump,"net-stats-final"));
    }

    /** One world frame arrived from the server. Called from the render thread. */
    public static void frameArrived() {
        if(!ON) return;
        long now=System.nanoTime();
        framesReceived++;
        if(lastArrival!=0&&arrivalCount<CAPACITY) arrivalIntervals[arrivalCount++]=now-lastArrival;
        if(firstArrival==0) firstArrival=now;
        lastArrival=now;
    }

    /**
     * One drawn frame, and whether it had to be predicted forward.
     *
     * <p>Called from the render loop, not from {@code snapshot()}: the window asks for a snapshot
     * twice per frame (once to draw, once to check the result state), so counting there reported
     * double the real frame rate.
     */
    public static void renderFrame(boolean predicted) {
        if(!ON) return;
        renderFrames++;
        if(predicted) starvedNoNewer++;
    }

    public static void pumpTook(long nanos) {
        if(!ON||pumpCount>=CAPACITY) return;
        pumpNanos[pumpCount++]=nanos;
    }

    public static void drawTook(long nanos) {
        if(!ON||drawCount>=CAPACITY) return;
        drawNanos[drawCount++]=nanos;
    }

    /** Rewrites the report. Safe to call at any time; a failure to write is not worth crashing over. */
    public static synchronized void dump() {
        if(!ON) return;
        try {
            Files.writeString(Path.of(PATH),report(),StandardCharsets.UTF_8);
        } catch(IOException|RuntimeException ignored) {
            // Instrumentation must never be the reason a match fails.
        }
    }

    private static String report() {
        long elapsed=System.nanoTime()-startNanos;
        double seconds=elapsed/1e9;
        // Rates belong to the match, not to the session: a window sits in menus and lobbies for an
        // unknown share of its life, and dividing frames by that inflated span understates both the
        // frame rate and the arrival rate.
        double matchSeconds=framesReceived>1?(lastArrival-firstArrival)/1e9:0;
        StringBuilder out=new StringBuilder();
        out.append("TankTrouble net stats\n");
        out.append(String.format("session elapsed      %8.1f s%n",seconds));
        out.append(String.format("match window         %8.1f s   (first to last frame received)%n",matchSeconds));
        out.append(String.format("render frames        %8d   (%.1f/s over the match)%n",
                renderFrames,rate(renderFrames,matchSeconds)));
        out.append(String.format("frames received      %8d   (%.1f/s over the match)%n",
                framesReceived,rate(framesReceived,matchSeconds)));
        if(arrivalCount>0) {
            long[] sorted=Arrays.copyOf(arrivalIntervals,arrivalCount);
            Arrays.sort(sorted);
            out.append(String.format("arrival interval     mean %6.1f ms   p50 %6.1f   p95 %6.1f   p99 %6.1f   max %6.1f%n",
                    mean(sorted)/1e6,pct(sorted,.50)/1e6,pct(sorted,.95)/1e6,pct(sorted,.99)/1e6,
                    sorted[sorted.length-1]/1e6));
        }
        out.append('\n');
        out.append(String.format("extrapolated frames  %8d   (%.2f%%)  no newer frame in time, so predicted%n",
                starvedNoNewer,share(starvedNoNewer,renderFrames)));
        if(pumpCount>0) {
            long[] sorted=Arrays.copyOf(pumpNanos,pumpCount);
            Arrays.sort(sorted);
            out.append(String.format("%npump()               mean %6.3f ms   p99 %6.3f   max %6.3f%n",
                    mean(sorted)/1e6,pct(sorted,.99)/1e6,sorted[sorted.length-1]/1e6));
        }
        if(drawCount>0) {
            long[] sorted=Arrays.copyOf(drawNanos,drawCount);
            Arrays.sort(sorted);
            out.append(String.format("draw()               mean %6.3f ms   p99 %6.3f   max %6.3f%n",
                    mean(sorted)/1e6,pct(sorted,.99)/1e6,sorted[sorted.length-1]/1e6));
        }
        if(renderFrames==0) {
            out.append("\nNo render frames recorded - no match was played in this session.\n");
        }
        out.append("\n'Extrapolated' means the buffer ran dry and the frame was predicted forward from\n");
        out.append("the last known speed instead of being redrawn frozen. A high share is expected on\n");
        out.append("a jittery link and is not itself a fault; what matters is whether it still looks\n");
        out.append("like a hitch.\n");
        return out.toString();
    }

    private static double rate(int count,double seconds) {return seconds<=0?0:count/seconds;}
    private static double share(int part,int whole) {return whole<=0?0:100.0*part/whole;}
    private static double mean(long[] sorted) {
        long total=0;
        for(long v:sorted) total+=v;
        return sorted.length==0?0:(double)total/sorted.length;
    }
    private static long pct(long[] sorted,double fraction) {
        if(sorted.length==0) return 0;
        return sorted[Math.min(sorted.length-1,(int)(sorted.length*fraction))];
    }
}
