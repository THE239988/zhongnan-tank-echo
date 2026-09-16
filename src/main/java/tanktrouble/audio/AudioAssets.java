package tanktrouble.audio;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Entry point that writes the game's audio assets to {@code assets/audio/}.
 *
 * <p>Run once after cloning, and again whenever a synthesis parameter changes:
 * <pre>
 *   javac -encoding UTF-8 -d target/audio-gen src/main/java/tanktrouble/audio/*.java
 *   java -cp target/audio-gen tanktrouble.audio.AudioAssets
 * </pre>
 *
 * <p>The generated files are ordinary WAV files, so any of them can be replaced with a recording
 * of the same name and the game will pick it up with no code change. Keep the names and the game
 * keeps working; the mixer only ever looks files up by name.
 */
public final class AudioAssets {
    /** Directory the game loads from, relative to the working directory. */
    static final String DIRECTORY = "assets/audio";
    static final String EXTENSION = ".wav";

    private AudioAssets() {}

    public static void main(String[] args) throws IOException {
        Path directory = Path.of(args.length > 0 ? args[0] : DIRECTORY);
        Files.createDirectories(directory);

        Map<String, double[]> everything = new LinkedHashMap<>();
        everything.putAll(SoundEffects.renderAll());
        everything.putAll(Music.renderAll());

        long totalBytes = 0;
        for (Map.Entry<String, double[]> entry : everything.entrySet()) {
            Path file = directory.resolve(entry.getKey() + EXTENSION);
            ToneSynth.writeWav(file, entry.getValue());
            long bytes = Files.size(file);
            totalBytes += bytes;
            System.out.printf("%-14s %6.2fs  %7d bytes%n",
                    entry.getKey() + EXTENSION, entry.getValue().length / (double) ToneSynth.RATE, bytes);
        }
        System.out.printf("%n%d files, %.1f KB total, written to %s%n",
                everything.size(), totalBytes / 1024.0, directory.toAbsolutePath());
    }
}
