package tanktrouble.audio;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * The only place in the codebase that touches {@code javafx.scene.media}, and it does so entirely
 * through reflection.
 *
 * <p>Why reflection rather than a plain import: JavaFX Media ships as a separate artifact from the
 * rest of JavaFX, and it has already gone missing from this project's {@code target/lib} once,
 * which took the whole game down with a {@code NoClassDefFoundError} before the first frame. Going
 * through {@code Class.forName} turns that hard failure into "the game runs silently", which is a
 * far better outcome for anyone handed a partial build.
 *
 * <p>The cost is that every call here is reflection. That is acceptable because there are only two
 * object types, they are created once at startup, and nothing here runs per-frame.
 */
public final class Clips {
    private static final String AUDIO_CLIP = "javafx.scene.media.AudioClip";
    private static final String MEDIA = "javafx.scene.media.Media";
    private static final String MEDIA_PLAYER = "javafx.scene.media.MediaPlayer";

    /** {@code Double.MAX_VALUE} is JavaFX's constant for "loop forever". */
    private static final double INDEFINITE = Double.MAX_VALUE;

    private static final boolean AVAILABLE = probe();

    private Clips() {}

    /** True when the JavaFX Media module is on the classpath and usable. */
    public static boolean available() { return AVAILABLE; }

    private static boolean probe() {
        try {
            Class.forName(AUDIO_CLIP);
            Class.forName(MEDIA_PLAYER);
            return true;
        } catch (Throwable missing) {
            return false;
        }
    }

    /** Human-readable reason audio is off, for logging at startup. */
    static String unavailableReason() {
        return "javafx.scene.media is not on the classpath - running without audio";
    }

    public static boolean isFile(Path file) { return Files.isRegularFile(file); }

    /**
     * Wraps a decoded one-shot sound. JavaFX decodes the whole file up front, so playback has no
     * start latency and several instances can overlap freely - both of which matter for gunfire.
     */
    public static Object loadClip(Path file) throws ReflectiveOperationException {
        Class<?> clipClass = Class.forName(AUDIO_CLIP);
        return clipClass.getConstructor(String.class).newInstance(file.toAbsolutePath().toUri().toString());
    }

    /** Starts a clip at {@code volume}; the same clip may be playing several times over. */
    public static void play(Object clip, double volume) throws ReflectiveOperationException {
        invoke(clip, "play", new Class<?>[] {double.class}, volume);
    }

    /** Stops any voices already playing this clip, then starts it again from the beginning. */
    public static void replay(Object clip, double volume) throws ReflectiveOperationException {
        stop(clip);
        play(clip, volume);
    }

    public static void setClipVolume(Object clip, double volume) throws ReflectiveOperationException {
        invoke(clip, "setVolume", new Class<?>[] {double.class}, volume);
    }

    public static void stop(Object clip) throws ReflectiveOperationException {
        invoke(clip, "stop", new Class<?>[0]);
    }

    /**
     * Wraps a streaming music track.
     *
     * <p>Music uses {@code MediaPlayer} rather than {@code AudioClip} because looping an
     * {@code AudioClip} means holding the entire decoded track in memory and restarting it by
     * hand; {@code MediaPlayer} loops natively and streams.
     */
    static Object loadLoopingPlayer(Path file) throws ReflectiveOperationException {
        Class<?> mediaClass = Class.forName(MEDIA);
        Class<?> playerClass = Class.forName(MEDIA_PLAYER);
        Object media = mediaClass.getConstructor(String.class).newInstance(file.toAbsolutePath().toUri().toString());
        Object player = playerClass.getConstructor(mediaClass).newInstance(media);
        invoke(player, "setCycleCount", new Class<?>[] {int.class}, (int) INDEFINITE);
        return player;
    }

    static void setPlayerVolume(Object player, double volume) throws ReflectiveOperationException {
        invoke(player, "setVolume", new Class<?>[] {double.class}, volume);
    }

    /** Reads a {@code double} property such as {@code volume}, used to verify applied settings. */
    static double readDouble(Object target, String getter) throws ReflectiveOperationException {
        return ((Number) invoke(target, getter, new Class<?>[0])).doubleValue();
    }

    static void playPlayer(Object player) throws ReflectiveOperationException {
        invoke(player, "play", new Class<?>[0]);
    }

    static void pausePlayer(Object player) throws ReflectiveOperationException {
        invoke(player, "pause", new Class<?>[0]);
    }

    /**
     * Releases the native decoder behind a {@code MediaPlayer}.
     *
     * <p>{@code stop()} alone leaves the decoder and its buffers alive, so anything that creates a
     * player repeatedly - the opening video, or a track swap on every page change - leaks native
     * resources until {@code dispose()} is called.
     */
    static void disposePlayer(Object player) throws ReflectiveOperationException {
        invoke(player, "dispose", new Class<?>[0]);
    }

    /** Invokes a media method, unwrapping reflection's checked exceptions into their real cause. */
    private static Object invoke(Object target, String name, Class<?>[] parameterTypes, Object... arguments)
            throws ReflectiveOperationException {
        Method method = target.getClass().getMethod(name, parameterTypes);
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw wrapped;
        }
    }
}
