package tanktrouble.art;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import tanktrouble.art.ArtAssets.Hero;

/**
 * A chibi character that bounces around an empty area of a page.
 *
 * <p>Deliberately a Pane rather than something added to the page's own layout: it is decoration,
 * and it must never take space from the menu or intercept a click. {@link #setMouseTransparent}
 * is what keeps a bouncing picture from swallowing button presses underneath it.
 *
 * <p>The motion is a plain velocity-and-gravity simulation rather than an animation curve, because
 * the character has to change without the movement restarting. A new drawing every few seconds
 * would otherwise mean a visible reset; here it is just a different picture on the same ball.
 */
public final class ChibiBall extends Pane {
    private static final double GRAVITY = 900;        // px per second squared
    private static final double BOUNCE = 0.82;        // energy kept on impact
    private static final double WALL_BOUNCE = 0.94;   // sideways energy kept
    private static final double MAX_SPEED = 520;
    private static final long SWAP_MS = 4000;

    private final ImageView view = new ImageView();
    private final List<Hero> cast = new ArrayList<>();
    private final Random random = new Random();
    private final AnimationTimer animator;
    private double x, y, vx, vy;
    private long lastNanos;
    private long lastSwapMs;
    private int index;
    private boolean placed;
    private boolean running;

    public ChibiBall() {
        setId("chibi-ball");
        setMouseTransparent(true);
        setPickOnBounds(false);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        getChildren().add(view);
        widthProperty().addListener((o, was, now) -> seedIfUnplaced());
        heightProperty().addListener((o, was, now) -> seedIfUnplaced());
        animator = new AnimationTimer() {
            @Override public void handle(long now) {
                if (!running) return;
                step(now);
            }
        };
    }

    /** Starts bouncing. Does nothing while no artwork is available, so the page stays quiet. */
    public void start() {
        if (running) return;
        reloadCast();
        if (cast.isEmpty()) return;
        running = true;
        showCurrent();
        lastNanos = 0;
        long nowMs = System.nanoTime() / 1_000_000L;
        lastSwapMs = nowMs;
        animator.start();
    }

    public void stop() {
        running = false;
        animator.stop();
    }

    /** Re-reads which characters have chibi art; call after artwork is replaced. */
    public void reloadCast() {
        cast.clear();
        for (Hero character : Hero.values()) {
            if (ArtAssetsHolder.chibi(character) != null) cast.add(character);
        }
    }

    private void showCurrent() {
        if (cast.isEmpty()) return;
        Hero character = cast.get(index % cast.size());
        Image image = ArtAssetsHolder.chibi(character);
        view.setImage(image);
        if (image == null) return;
        view.setFitHeight(image.getHeight());
        view.setFitWidth(image.getWidth());
    }

    /**
     * Drops the character into the middle of the area once the pane knows its own size.
     *
     * <p>The size arrives after layout, so the starting position cannot be set in the constructor.
     * Once placed, the ball is also given a push: starting from rest would leave it sitting still
     * until the first bounce, which looks broken rather than calm.
     */
    private void seedIfUnplaced() {
        double areaW = getWidth();
        double areaH = getHeight();
        double w = view.getFitWidth();
        double h = view.getFitHeight();
        if (placed || areaW <= w || areaH <= h) return;
        placed = true;
        x = (areaW - w) / 2;
        y = areaH - h;
        vx = 130 + random.nextDouble() * 90;
        vy = -280 - random.nextDouble() * 140;
        place();
    }

    private void step(long nowNanos) {
        long nowMs = nowNanos / 1_000_000L;
        if (lastNanos == 0) {
            lastNanos = nowNanos;
            return;
        }
        // Clamped so a stalled frame cannot fling the character through a wall.
        double dt = Math.min(0.05, (nowNanos - lastNanos) / 1e9);
        lastNanos = nowNanos;

        if (nowMs - lastSwapMs >= SWAP_MS) {
            lastSwapMs = nowMs;
            index++;
            showCurrent();
        }

        seedIfUnplaced();
        double areaW = getWidth();
        double areaH = getHeight();
        double w = view.getFitWidth();
        double h = view.getFitHeight();
        if (areaW <= w || areaH <= h) return;

        vy += GRAVITY * dt;
        vx = clamp(vx);
        vy = clamp(vy);
        x += vx * dt;
        y += vy * dt;

        if (x < 0) { x = 0; vx = Math.abs(vx) * WALL_BOUNCE; }
        else if (x + w > areaW) { x = areaW - w; vx = -Math.abs(vx) * WALL_BOUNCE; }

        if (y + h > areaH) {
            y = areaH - h;
            vy = -Math.abs(vy) * BOUNCE;
            // Settling: a bounce too small to see is stopped outright, or the character keeps
            // twitching on the spot forever.
            if (Math.abs(vy) < 60) vy = -180 - random.nextDouble() * 120;
        } else if (y < 0) {
            y = 0;
            vy = Math.abs(vy) * BOUNCE;
        }

        place();
    }

    private double clamp(double v) {
        return Math.max(-MAX_SPEED, Math.min(MAX_SPEED, v));
    }

    /** Moves the drawn character to the simulated position. */
    private void place() {
        view.relocate(x, y);
    }

    /** Bridges to the shared artwork, same as the portrait panel. */
    private static final class ArtAssetsHolder {
        private static ArtAssets shared = new ArtAssets();

        static Image chibi(Hero character) { return shared.chibi(character); }

        static void replace(ArtAssets assets) { shared = assets; }
    }

    public static void useAssets(ArtAssets assets) { ArtAssetsHolder.replace(assets); }
}
