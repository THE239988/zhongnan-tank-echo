package tanktrouble.art;

import javafx.animation.AnimationTimer;
import javafx.scene.effect.ColorAdjust;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Rectangle;
import tanktrouble.art.ArtAssets.Hero;
import tanktrouble.art.ArtAssets.Expression;

/**
 * The character panel shown beside the battlefield: one portrait that reacts to what the player
 * is doing.
 *
 * <p>The drawings that exist today are a single standing pose per character, so the reactions are
 * motion rather than swapped artwork - a lean when firing, a shake and a flush when hit, grey and
 * sunk when the battle is lost. When expression variants are added later they are picked up
 * automatically through {@link ArtAssets}, and the motion keeps working on top of them.
 *
 * <p>Transient states (attack, hit) expire on their own. Sustained ones (low health, victory,
 * defeat) stay until told otherwise, and a transient state always falls back to whichever
 * sustained state is currently in force rather than straight to idle.
 */
public final class PortraitView extends StackPane {
    /** How long a one-shot reaction lasts, in milliseconds. */
    private static final long ATTACK_MS = 220;
    private static final long HIT_MS = 340;

    /**
     * How much of the panel the picture is allowed to fill.
     *
     * <p>The whole figure is kept - nothing is cropped - so the drawing is scaled to fit rather
     * than to cover, and the leftover panel shows around it. Backing off a few percent keeps the
     * idle breathe and the hit shake from pushing the drawing past the panel edge, where the clip
     * would shave a sliver off it.
     */
    private static final double FIT_MARGIN = 0.94;

    private final ImageView view = new ImageView();
    /** Reused every frame: allocating an effect 60 times a second is pure garbage. */
    private final ColorAdjust tint = new ColorAdjust();
    private final AnimationTimer animator;
    private Hero character;
    private Expression variant = Expression.IDLE;
    private boolean sustainedLowHealth;
    private boolean sustainedVictory;
    private boolean sustainedDefeat;
    private long transientUntilMs;
    private Expression transientState = Expression.IDLE;
    private long startedAtMs;
    private boolean running;
    /**
     * 开启后，等比高度变成「上限 + 下限」而不是固定值：所在列空间不够时把图缩小，而不是把
     * 同一列里其他内容顶出可视区。
     *
     * <p>默认关闭：菜单里的预览图要的正是图片本身的精确宽高比。
     */
    private boolean flexibleHeight;
    /** 弹性模式下的下限。再小人物就谈不上是个角色了。 */
    private static final double MIN_FLEX_HEIGHT=96;

    public PortraitView() {
        setId("portrait-view");
        // Width comes from the column; the height is set by fit() once a drawing is loaded.
        setMinWidth(0);
        view.setPreserveRatio(true);
        view.setSmooth(true);
        // The artwork is drawn on white; matching the panel to it hides the cut-out edge, since
        // the source PNGs carry no alpha channel.
        setStyle("-fx-background-color: #f4f6f8;");
        // A layout pane does not clip its children, and the reactions nudge the drawing a few
        // pixels past the panel edge; without this it would spill across the battlefield beside it.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        getChildren().add(view);
        widthProperty().addListener((o, was, now) -> fit());
        heightProperty().addListener((o, was, now) -> fit());

        animator = new AnimationTimer() {
            @Override public void handle(long now) {
                if (!running) return;
                long millis = now / 1_000_000L;
                if (transientState != Expression.IDLE && millis >= transientUntilMs) {
                    transientState = Expression.IDLE;
                    applyImage();
                }
                pose((millis - startedAtMs) / 1000.0);
            }
        };
    }

    /** Starts the animator. Called when the panel becomes visible rather than from a constructor. */
    public void start() {
        if (running) return;
        running = true;
        startedAtMs = System.nanoTime() / 1_000_000L;
        animator.start();
    }

    /** Stops the animator so a hidden panel costs nothing. */
    public void stop() {
        running = false;
        animator.stop();
    }

    /** Chooses which character to show. Passing the same one again is ignored. */
    /** 允许外层布局把这个视图压到低于图片的等比高度。 */
    public void setFlexibleHeight(boolean value) {
        if (flexibleHeight == value) return;
        flexibleHeight = value;
        fit();
    }

    public void setCharacter(Hero next) {
        if (next == character) return;
        character = next;
        applyImage();
        fit();
    }

    public Hero character() { return character; }

    /** Shows the drawing for one character, without a panel of its own. */
    public void show(Hero next, Expression expression) {
        setCharacter(next);
        transientState = expression;
        transientUntilMs = Long.MAX_VALUE;
        applyImage();
    }

    /** A burst of activity: leaning into the shot. */
    public void attack() {
        transientState = Expression.ATTACK;
        transientUntilMs = System.nanoTime() / 1_000_000L + ATTACK_MS;
        applyImage();
    }

    /** Taking a hit: shake and flush. */
    public void hit() {
        transientState = Expression.HIT;
        transientUntilMs = System.nanoTime() / 1_000_000L + HIT_MS;
        applyImage();
    }

    /**
     * Sustained conditions, each settable on its own.
     *
     * <p>They are kept separately rather than as one "current state" so that, for example, a
     * wounded player who wins still reads as victorious - whichever is set last wins, and the
     * caller does not have to remember what the previous state was.
     */
    public void setLowHealth(boolean low) {
        if (sustainedLowHealth == low) return;
        sustainedLowHealth = low;
        applyImage();
    }

    public void setVictory(boolean won) {
        if (sustainedVictory == won) return;
        sustainedVictory = won;
        if (won) sustainedDefeat = false;
        applyImage();
    }

    public void setDefeat(boolean lost) {
        if (sustainedDefeat == lost) return;
        sustainedDefeat = lost;
        if (lost) sustainedVictory = false;
        applyImage();
    }

    /** Clears the lasting states, for starting a fresh battle. */
    public void reset() {
        sustainedLowHealth = false;
        sustainedVictory = false;
        sustainedDefeat = false;
        transientState = Expression.IDLE;
        transientUntilMs = 0;
        applyImage();
    }

    /** The expression currently being drawn, for tests and diagnostics. */
    public Expression currentExpression() {
        return transientState != Expression.IDLE ? transientState : sustained();
    }

    private Expression sustained() {
        if (sustainedDefeat) return Expression.DEFEAT;
        if (sustainedVictory) return Expression.VICTORY;
        if (sustainedLowHealth) return Expression.HIT;
        return Expression.IDLE;
    }

    private void applyImage() {
        Expression wanted = currentExpression();
        variant = wanted;
        Image image = character == null ? null : ArtAssetsHolder.portrait(character, wanted);
        view.setImage(image);
        view.setVisible(image != null);
        fit();
    }

    /**
     * Sizes the panel to the drawing, and the drawing to the panel.
     *
     * <p>The column beside the battlefield is far taller than the drawings are wide. Stretching
     * to fill it would pull the figure out of proportion; filling it by height would crop the
     * figure; filling it by width would leave the whole lower half of the column bare. Taking the
     * drawing's own proportions instead does none of those, so the figure is shown whole and the
     * panel ends where the figure does.
     */
    private void fit() {
        double width = getWidth();
        Image image = view.getImage();
        if (width <= 0 || image == null || image.getWidth() <= 0) return;

        double wanted = width * image.getHeight() / image.getWidth();
        setMinHeight(flexibleHeight ? MIN_FLEX_HEIGHT : wanted);
        setPrefHeight(wanted);
        setMaxHeight(wanted);

        double height = getHeight();
        if (height <= 0) return;
        double scale = Math.min(width / image.getWidth(), height / image.getHeight()) * FIT_MARGIN;
        view.setFitWidth(image.getWidth() * scale);
        view.setFitHeight(image.getHeight() * scale);
    }

    /** Drives the motion. {@code seconds} is time since the panel started. */
    private void pose(double seconds) {
        Expression shown = currentExpression();
        double breathe = Math.sin(seconds * 2.4) * 3.0;
        double x = 0, y = breathe, scale = 1;
        double red = 0, grey = 0;

        switch (shown) {
            case ATTACK -> {
                x = 5;
                scale = 1.02;
                y = breathe * 0.4;
            }
            // A flinch, not a seizure: the shake is slow enough to read as a recoil and small
            // enough that the drawing stays where the eye left it.
            case HIT -> {
                x = Math.sin(seconds * 22) * 2.5;
                red = 0.13;
            }
            case VICTORY -> {
                scale = 1.06;
                y = breathe * 1.6 - 6;
            }
            case DEFEAT -> {
                grey = 1.0;
                y = breathe * 0.4 + 10;
                scale = 0.97;
            }
            default -> {
                if (sustainedLowHealth) red = 0.16;
            }
        }

        view.setTranslateX(x);
        view.setTranslateY(y);
        view.setScaleX(scale);
        view.setScaleY(scale);

        // Tinting is a colour adjustment: warming toward a flush when hurt, draining to grey when
        // beaten. One effect handles both, so the panel never swaps effect types mid-animation.
        if (red > 0 || grey > 0) {
            tint.setSaturation(grey > 0 ? -1 : red * 1.2);
            tint.setBrightness(red > 0 ? red * 0.18 : -0.06);
            view.setEffect(tint);
        } else if (view.getEffect() != null) {
            view.setEffect(null);
        }

        // A soft contact shadow, warmer while something dramatic is happening. Kept close to the
        // drawing's own paper white so the panel reads as one surface rather than a colour flash.
        setStyle(shown == Expression.HIT
                ? "-fx-background-color: #fbf1f0;"
                : shown == Expression.VICTORY ? "-fx-background-color: #f3f8ef;"
                : "-fx-background-color: #f4f6f8;");
    }

    /**
     * Bridges to the shared artwork.
     *
     * <p>The view does not own the images: they are loaded once and shared, so several panels
     * showing the same character do not each hold their own copy.
     */
    private static final class ArtAssetsHolder {
        private static ArtAssets shared = new ArtAssets();

        static Image portrait(Hero character, Expression expression) {
            return shared.portrait(character, expression);
        }

        static void replace(ArtAssets assets) { shared = assets; }
    }

    /** Points every panel at a specific asset set. Used by tests and by custom art locations. */
    public static void useAssets(ArtAssets assets) { ArtAssetsHolder.replace(assets); }
}
