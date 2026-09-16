package tanktrouble.voice;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import tanktrouble.art.ArtAssets.Hero;
import tanktrouble.audio.Clips;

/**
 * The characters' spoken lines, played in response to what happens in a battle.
 *
 * <p>Each character has a handful of lines per situation, and one is chosen at random for each
 * occurrence. A single line repeated on every shot wears out within a minute, so the variation is
 * the point rather than a nicety.
 *
 * <p>The per-situation repeat guard matters for the same reason: firing is paced at roughly two
 * shots a second, and without it the same voice would talk over itself continuously.
 *
 * <p>Everything degrades to silence. Missing files, an unreadable directory, or the JavaFX media
 * module being absent all leave the game running, just quiet - the same contract the sound effects
 * follow, and for the same reason: audio is decoration and must never stop the game.
 */
public final class VoiceLines implements AutoCloseable {
    /** The situations a character can speak in. Ordinals match the file suffixes. */
    public enum Line {
        ATTACK("attack"),
        HIT("hit"),
        VICTORY("victory"),
        DEFEAT("defeat");

        private final String suffix;

        Line(String suffix) { this.suffix = suffix; }

        public String suffix() { return suffix; }
    }

    /** Shortest gap between two lines of the same kind, in milliseconds. */
    private static final long ATTACK_COOLDOWN_MS = 1400;
    private static final long HIT_COOLDOWN_MS = 900;
    /** Upper bound on numbered variants per situation; the loading stops at the first gap. */
    private static final int MAX_VARIANTS = 9;

    private final Path root;
    private final Random random = new Random();
    private final Map<Hero, EnumMap<Line, List<Object>>> clips = new EnumMap<>(Hero.class);
    private final Map<Line, Long> lastPlayed = new HashMap<>();
    private final boolean enabled;

    private Hero hero;
    private double volume = 0.85;
    /** Set when a battle ends, so the closing line is not cut off by a following hit. */
    private boolean closingPlayed;

    public VoiceLines() { this(Path.of("assets", "art", "voice")); }

    public VoiceLines(Path root) {
        this.root = root;
        this.enabled = Clips.available();
    }

    /** True when lines can actually be heard. */
    public boolean isEnabled() { return enabled; }

    public void setVolume(double volume) {
        this.volume = Math.max(0, Math.min(1, volume));
    }

    public double volume() { return volume; }

    /**
     * Chooses the speaking character.
     *
     * <p>Lines are loaded on first use rather than up front: six characters times seven files is
     * forty-odd clips, and a player only ever hears one character per battle.
     */
    public void setHero(Hero next) {
        if (next == hero) return;
        hero = next;
        closingPlayed = false;
        // Drop the previous character's clips: holding all six sets would be pointless memory.
        clips.clear();
        lastPlayed.clear();
    }

    public Hero hero() { return hero; }

    /** Announces the result of a battle; only plays once per battle. */
    public void announceResult(boolean won) {
        if (closingPlayed) return;
        closingPlayed = true;
        play(won ? Line.VICTORY : Line.DEFEAT, 0);
    }

    /** Called when a new battle starts, so the closing line can play again. */
    public void beginBattle() { closingPlayed = false; }

    /** Plays a line of the given kind, subject to the repeat guard. */
    public void say(Line line) {
        long cooldown = switch (line) {
            case ATTACK -> ATTACK_COOLDOWN_MS;
            case HIT -> HIT_COOLDOWN_MS;
            default -> 0;
        };
        play(line, cooldown);
    }

    private void play(Line line, long cooldownMs) {
        if (!enabled || hero == null) return;
        long now = System.currentTimeMillis();
        Long previous = lastPlayed.get(line);
        if (previous != null && now - previous < cooldownMs) return;

        List<Object> options = load(hero, line);
        if (options.isEmpty()) return;
        lastPlayed.put(line, now);
        Object clip = options.get(random.nextInt(options.size()));
        try {
            Clips.play(clip, volume);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A line that fails to play is not worth interrupting a battle over.
        }
    }

    /**
     * Loads every variant of one line for one character, skipping the ones that are absent.
     *
     * <p>Two naming shapes are accepted, because the recordings use both: numbered variants
     * ({@code 潇湘_attack1.wav}, {@code _attack2}, ...) where a situation has several alternatives,
     * and a bare name ({@code 潇湘_victory.wav}) where there is only one line to say. Assuming the
     * numbered form everywhere silently lost every victory and defeat line.
     */
    private List<Object> load(Hero who, Line line) {
        EnumMap<Line, List<Object>> forHero = clips.computeIfAbsent(who, k -> new EnumMap<>(Line.class));
        List<Object> cached = forHero.get(line);
        if (cached != null) return cached;

        List<Object> loaded = new ArrayList<>();
        for (int index = 1; index <= MAX_VARIANTS; index++) {
            Object clip = open(root.resolve(who.label() + "_" + line.suffix() + index + ".wav"));
            if (clip == null) break;
            loaded.add(clip);
        }
        if (loaded.isEmpty()) {
            Object single = open(root.resolve(who.label() + "_" + line.suffix() + ".wav"));
            if (single != null) loaded.add(single);
        }
        forHero.put(line, loaded);
        return loaded;
    }

    /**
     * Opens one voice file, from disk if present and otherwise from inside the jar.
     *
     * <p>The bundled copy is extracted to a temporary file first, because a clip must be given a
     * real location to stream from and a path inside a jar is not one.
     */
    private Object open(Path file) {
        try {
            if (Files.isRegularFile(file)) return Clips.loadClip(file);
            String name = file.getFileName().toString();
            InputStream stream = VoiceLines.class.getResourceAsStream("/art/voice/" + name);
            if (stream == null) return null;
            try (InputStream open = stream) {
                Path temp = Files.createTempFile("tank-voice-", ".wav");
                temp.toFile().deleteOnExit();
                Files.copy(open, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return Clips.loadClip(temp);
            }
        } catch (ReflectiveOperationException | IOException | RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** Number of distinct lines loaded for a character, for tests and diagnostics. */
    int loadedCount(Hero who, Line line) { return load(who, line).size(); }

    @Override public void close() {
        for (EnumMap<Line, List<Object>> forHero : clips.values()) {
            for (List<Object> list : forHero.values()) {
                for (Object clip : list) {
                    try {
                        Clips.stop(clip);
                    } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                        // Nothing actionable; the reference is being dropped anyway.
                    }
                }
            }
        }
        clips.clear();
    }
}
