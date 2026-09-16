package tanktrouble.art;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javafx.scene.image.Image;

/**
 * Loads the character artwork: the battle portraits, the full scene pictures and the chibi
 * sprites, one set per character.
 *
 * <p>Every lookup is allowed to fail. Art is decoration - a missing file must produce a silent
 * null, never an exception that reaches the render loop, because the game has to stay playable
 * when the drawings are absent, incomplete, or being replaced while it runs.
 *
 * <p>Images are scaled down at load time rather than at draw time. The source files are around
 * 1536x2048; held at full size, six of them would occupy roughly 70 MB of texture memory for
 * pictures that are never drawn larger than a few hundred pixels. Scaling on load costs one pass
 * and leaves about 1 MB each.
 */
public final class ArtAssets {
    /** Characters, in the order they are offered to the player. */
    public enum Hero {
        XIAOXIANG("潇湘", "新校区 · 主角型"),
        LUYELUSHAN("岳麓山", "校本部 · 工程型"),
        LUNAN("麓南", "南校区 · 后勤型"),
        TIANXIN("天心", "铁道校区 · 速度型"),
        KAIFU("开福", "湘雅老校区 · 医疗型"),
        XINGLIN("杏林", "湘雅新校区 · 青囊实验");

        private final String label;
        private final String caption;

        Hero(String label, String caption) {
            this.label = label;
            this.caption = caption;
        }

        public String label() { return label; }

        public String caption() { return caption; }
    }

    /**
     * The emotional states a portrait can show.
     *
     * <p>{@link #IDLE} is the only one that must exist; the rest fall back to it. That is what
     * lets the drawings arrive one at a time - a character with no expression variants behaves
     * exactly like one whose variants are all finished.
     */
    public enum Expression { IDLE, ATTACK, HIT, VICTORY, DEFEAT }

    /** Target widths. Portraits are the tall ones; scenes and chibi are wide and square-ish. */
    private static final int PORTRAIT_WIDTH = 520;
    private static final int SCENE_WIDTH = 620;
    private static final int CHIBI_SIZE = 220;

    private final Path root;
    private final Map<Hero, EnumMap<Expression, Image>> portraits = new EnumMap<>(Hero.class);
    private final Map<Hero, Image> scenes = new EnumMap<>(Hero.class);
    private final Map<Hero, Image> chibi = new EnumMap<>(Hero.class);
    private final Map<String, Image> loaded = new HashMap<>();

    public ArtAssets() { this(Path.of("assets", "art")); }

    public ArtAssets(Path root) {
        this.root = root;
        for (Hero character : Hero.values()) scan(character);
    }

    private void scan(Hero character) {
        EnumMap<Expression, Image> variants = new EnumMap<>(Expression.class);
        for (Expression expression : Expression.values()) {
            Image image = load("portrait/" + portraitName(character, expression), PORTRAIT_WIDTH, 0);
            if (image != null) variants.put(expression, image);
        }
        portraits.put(character, variants);

        Image scene = load("scene/" + character.label() + ".png", SCENE_WIDTH, 0);
        if (scene != null) scenes.put(character, scene);

        Image small = load("chibi/" + character.label() + ".png", CHIBI_SIZE, CHIBI_SIZE);
        if (small != null) chibi.put(character, small);
    }

    private String portraitName(Hero character, Expression expression) {
        return expression == Expression.IDLE
                ? character.label() + ".png"
                : character.label() + "_" + expression.name().toLowerCase() + ".png";
    }

    /**
     * Reads one image, scaled to {@code width} (and {@code height}, or 0 to keep the ratio).
     *
     * <p>Two places are searched, in order: the {@code assets/art} directory beside the game, and
     * then the copy bundled inside the jar. The directory wins so that artwork can be replaced
     * without rebuilding; the bundled copy is what makes the packaged release work when started
     * from anywhere, where no such directory exists.
     *
     * <p>Loading never throws: a corrupt or missing file yields null, and the caller draws nothing
     * rather than failing.
     */
    private Image load(String relative, int width, int height) {
        String key = relative + "|" + width + "x" + height;
        if (loaded.containsKey(key)) return loaded.get(key);

        Image image = null;
        Path file = root.resolve(relative);
        try {
            if (Files.isRegularFile(file)) {
                image = decode(file.toUri().toString(), width, height);
            } else {
                InputStream stream = ArtAssets.class.getResourceAsStream("/art/" + relative);
                if (stream != null) {
                    try (InputStream open = stream) {
                        image = decode(open, width, height);
                    }
                }
            }
        } catch (RuntimeException | java.io.IOException unreadable) {
            // Fall through: the caller treats a missing image and a broken one the same way.
        }
        loaded.put(key, image);
        return image;
    }

    /**
     * Decodes synchronously.
     *
     * <p>JavaFX loads images on a background thread by default. That is the wrong trade here: the
     * panels size themselves from the image the moment it is set, so an image that has not finished
     * loading reports zero width and the layout settles on nothing. Loading up front costs a moment
     * at startup and removes an entire class of "sometimes blank" bugs.
     */
    private Image decode(String url, int width, int height) {
        Image candidate = new Image(url, width, height, true, true, false);
        return candidate.isError() ? null : candidate;
    }

    /**
     * Decodes a bundled image.
     *
     * <p>The bytes are read out in full before decoding. There is no background-loading switch on
     * the stream constructor, and handing JavaFX a stream it may read later is exactly the
     * asynchronous behaviour that leaves panels measuring themselves against a zero-sized image.
     */
    private Image decode(InputStream stream, int width, int height) throws java.io.IOException {
        Image candidate = new Image(new java.io.ByteArrayInputStream(stream.readAllBytes()), width, height, true, true);
        return candidate.isError() ? null : candidate;
    }

    /**
     * The portrait for a character in a given state.
     *
     * @return the state-specific drawing when it exists, otherwise the idle one, otherwise null
     */
    public Image portrait(Hero character, Expression expression) {
        EnumMap<Expression, Image> variants = portraits.get(character);
        if (variants == null) return null;
        Image image = variants.get(expression);
        return image != null ? image : variants.get(Expression.IDLE);
    }

    public Image portrait(Hero character) { return portrait(character, Expression.IDLE); }

    public Image scene(Hero character) { return scenes.get(character); }

    public Image chibi(Hero character) { return chibi.get(character); }

    /** Characters that have at least a battle portrait available. */
    public List<Hero> available() {
        List<Hero> found = new ArrayList<>();
        for (Hero character : Hero.values()) if (portrait(character) != null) found.add(character);
        return found;
    }

    /** True when no artwork was found at all, so the UI can skip drawing empty panels. */
    public boolean isEmpty() { return available().isEmpty(); }

    /** Where the artwork is expected, for the "art is missing" hint. */
    public Path root() { return root; }
}
