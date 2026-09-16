package tanktrouble.audio;

import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tanktrouble.model.data.GameData.GameEvent;
import tanktrouble.model.data.GameData.GameEventType;

/**
 * Owns every sound the game makes: one-shot effects driven by model events, and one looping music
 * track chosen by page.
 *
 * <p>Everything degrades to a silent no-op when the audio files or the JavaFX Media module are
 * absent - see {@link Clips}. The game must never fail to start because a sound is missing, so
 * every entry point here swallows load failures rather than propagating them.
 *
 * <p>All calls are expected on the JavaFX application thread. The model already emits events during
 * the render loop, so audio naturally runs there; touching {@code MediaPlayer} off-thread would be
 * a bug.
 */
public final class AudioManager implements AutoCloseable {
    /** Music track requested by the view. Values are plain strings so the view keeps no coupling. */
    public enum Track { NONE, MENU, BATTLE }

    /** Directory the game loads audio from, relative to the working directory. */
    static final String DIRECTORY = "assets/audio";

    /**
     * Minimum gap between two plays of the same effect, in milliseconds.
     *
     * <p>Guards the effects that can fire many times in a single tick - a reflected bullet can
     * bounce several times, and every bounce is its own event. Without this the mix turns into a
     * buzz and, worse, stacks dozens of overlapping voices.
     */
    private static final long MIN_REPEAT_MS = 45;

    /** Effects that should restart instead of layering over themselves. */
    private static final Set<String> EXCLUSIVE_SOUNDS =
            Set.of("fire", "enemy-fire", "hit-enemy", "hit-player");

    /** Maps each simulation event to the asset that represents it. */
    private static final Map<GameEventType, String> EVENT_SOUNDS = new EnumMap<>(GameEventType.class);
    static {
        EVENT_SOUNDS.put(GameEventType.PLAYER_FIRE, "fire");
        EVENT_SOUNDS.put(GameEventType.ENEMY_FIRE, "enemy-fire");
        EVENT_SOUNDS.put(GameEventType.TURRET_FIRE, "enemy-fire");
        EVENT_SOUNDS.put(GameEventType.TURRET_DEPLOY, "deploy");
        EVENT_SOUNDS.put(GameEventType.HIT_ENEMY, "hit-enemy");
        EVENT_SOUNDS.put(GameEventType.HIT_PLAYER, "hit-player");
        EVENT_SOUNDS.put(GameEventType.SHIELD_BLOCK, "shield-block");
        EVENT_SOUNDS.put(GameEventType.KILL, "explosion");
        EVENT_SOUNDS.put(GameEventType.COIN, "coin");
        EVENT_SOUNDS.put(GameEventType.ITEM_REPAIR, "item");
        EVENT_SOUNDS.put(GameEventType.ITEM_SHIELD, "item");
        EVENT_SOUNDS.put(GameEventType.ITEM_RAPID, "item");
        EVENT_SOUNDS.put(GameEventType.ITEM_TURRET, "item");
        EVENT_SOUNDS.put(GameEventType.ITEM_SCATTER, "item");
        EVENT_SOUNDS.put(GameEventType.ITEM_AIM, "item");
        EVENT_SOUNDS.put(GameEventType.ITEM_PENETRATE, "item");
        EVENT_SOUNDS.put(GameEventType.PULSE, "pulse");
        EVENT_SOUNDS.put(GameEventType.TELEPORT, "teleport");
        EVENT_SOUNDS.put(GameEventType.LAVA, "lava");
        EVENT_SOUNDS.put(GameEventType.BOUNCE, "hit-enemy");
        EVENT_SOUNDS.put(GameEventType.WAVE_START, "deploy");
    }

    private final Map<String, Object> clips = new HashMap<>();
    private final Map<String, Long> lastPlayed = new HashMap<>();
    private final Path directory;
    private final boolean enabled;

    private Object music;
    private Track track = Track.NONE;
    private double sfxVolume = 0.8;
    private double musicVolume = 0.6;
    private boolean musicPaused;

    /** Loads with the default asset directory. */
    public AudioManager() { this(Path.of(DIRECTORY)); }

    public AudioManager(Path directory) {
        this.directory = directory;
        this.enabled = Clips.available();
        if (!enabled) return;
        for (String name : EVENT_SOUNDS.values()) loadClip(name);
    }

    /** True when audio can actually be heard; false means every call here is a no-op. */
    public boolean isEnabled() { return enabled; }

    private void loadClip(String name) {
        if (clips.containsKey(name)) return;
        Path file = directory.resolve(name + ".wav");
        if (!Clips.isFile(file)) return;
        try {
            Object clip = Clips.loadClip(file);
            Clips.setClipVolume(clip, sfxVolume);
            clips.put(name, clip);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A single unreadable sound must not stop the others from loading.
        }
    }

    /** Plays the effect for every event in the batch, honouring the per-effect repeat guard. */
    public void play(List<GameEvent> events) {
        if (!enabled || events.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (GameEvent event : events) playOne(event, now);
    }

    private void playOne(GameEvent event, long now) {
        String name = EVENT_SOUNDS.get(event.type());
        if (name == null) return;
        Object clip = clips.get(name);
        if (clip == null) return;
        Long previous = lastPlayed.get(name);
        if (previous != null && now - previous < MIN_REPEAT_MS) return;
        lastPlayed.put(name, now);
        try {
            if (EXCLUSIVE_SOUNDS.contains(name)) Clips.replay(clip, sfxVolume);
            else Clips.play(clip, sfxVolume);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Audio is best-effort; never let a playback failure reach the game loop.
        }
    }

    /** Switches the looping music track, ignoring requests for the track already playing. */
    public void setTrack(Track track) {
        if (!enabled || this.track == track) return;
        disposeMusic();
        this.track = track;
        Path file = switch (track) {
            case MENU -> directory.resolve("bgm-menu.wav");
            case BATTLE -> directory.resolve("bgm-battle.wav");
            case NONE -> null;
        };
        if (file == null || !Clips.isFile(file)) return;
        try {
            music = Clips.loadLoopingPlayer(file);
            Clips.setPlayerVolume(music, musicVolume);
            if (!musicPaused) Clips.playPlayer(music);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            music = null;
        }
    }

    /**
     * Silences music without forgetting which track is selected, so the same track resumes after.
     * Used while the opening video plays, since that has its own soundtrack.
     */
    public void setMusicPaused(boolean paused) {
        if (!enabled || musicPaused == paused) return;
        musicPaused = paused;
        if (music == null) return;
        try {
            if (paused) Clips.pausePlayer(music);
            else Clips.playPlayer(music);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Ignored: a paused track that fails to resume is not worth interrupting play.
        }
    }

    public void setSfxVolume(double volume) {
        sfxVolume = clamp(volume);
        for (Object clip : clips.values()) {
            try {
                Clips.setClipVolume(clip, sfxVolume);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Ignored: one clip failing to take the new volume must not block the rest.
            }
        }
    }

    public void setMusicVolume(double volume) {
        musicVolume = clamp(volume);
        if (music == null) return;
        try {
            Clips.setPlayerVolume(music, musicVolume);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Ignored: volume is cosmetic and the next track load will pick it up.
        }
    }

    public double sfxVolume() { return sfxVolume; }

    public double musicVolume() { return musicVolume; }

    /** Number of effects that loaded successfully; zero means the assets are missing. */
    int loadedClipCount() { return clips.size(); }

    private void disposeMusic() {
        if (music == null) return;
        try {
            Clips.disposePlayer(music);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Nothing actionable: the reference is dropped either way.
        }
        music = null;
    }

    @Override public void close() {
        disposeMusic();
        clips.clear();
    }

    private static double clamp(double volume) { return Math.max(0, Math.min(1, volume)); }
}
