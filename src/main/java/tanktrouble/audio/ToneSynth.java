package tanktrouble.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

/**
 * Offline waveform synthesis used to author the game's audio assets.
 *
 * <p>Deliberately dependency-free: it only produces float buffers and writes 16-bit PCM WAV
 * files, so it runs from a plain {@code java} invocation with no JavaFX or classpath setup.
 * Keep every method here free of JavaFX types so the asset generator stays runnable when the
 * JavaFX Media module is unavailable.
 */
final class ToneSynth {
    /** 22050 Hz is plenty for synthesised tones and keeps the generated WAV files small. */
    static final int RATE = 22050;
    private static final double TAU = Math.PI * 2;

    private ToneSynth() {}

    // ---------------------------------------------------------------- oscillators

    /** Band-limited saw in [-1,1]: only the first {@code harmonics} partials are summed. */
    static double saw(double phase, int harmonics) {
        double value = 0;
        for (int h = 1; h <= harmonics; h++) value += Math.sin(phase * h) / h;
        return value * (2 / Math.PI);
    }

    /** Band-limited square in [-1,1], built from odd partials only. */
    static double square(double phase, int harmonics) {
        double value = 0;
        for (int h = 1; h <= harmonics; h += 2) value += Math.sin(phase * h) / h;
        return value * (4 / Math.PI);
    }

    static double triangle(double phase) {
        return 2 / Math.PI * Math.asin(Math.sin(phase));
    }

    static double sine(double phase) { return Math.sin(phase); }

    /** Deterministic white noise in [-1,1]. Seeded so regenerating assets is reproducible. */
    static double[] noise(int length, long seed) {
        double[] out = new double[length];
        Random random = new Random(seed);
        for (int i = 0; i < length; i++) out[i] = random.nextDouble() * 2 - 1;
        return out;
    }

    // ---------------------------------------------------------------- envelopes

    /**
     * ADSR envelope as a function of time. Holds sustain until {@code duration}, then decays
     * over {@code release}.
     *
     * @param attack  seconds to ramp from 0 to 1
     * @param decay   seconds to fall from 1 to {@code sustain}
     * @param sustain level held during the body, in [0,1]
     * @param release seconds to fall from {@code sustain} to 0
     */
    static double adsr(double t, double duration, double attack, double decay, double sustain, double release) {
        if (t < 0) return 0;
        if (t < attack) return t / attack;
        if (t < attack + decay) return 1 - (1 - sustain) * ((t - attack) / decay);
        if (t < duration) return sustain;
        double out = t - duration;
        return out < release ? sustain * (1 - out / release) : 0;
    }

    /** Percussive envelope: instant attack then exponential decay. Unit peak at t=0. */
    static double percussive(double t, double decay) {
        return t < 0 ? 0 : Math.exp(-t / decay);
    }

    // ---------------------------------------------------------------- composition

    static double[] silence(int length) { return new double[length]; }

    /** Mixes {@code source} into {@code target} at {@code offset}, clamped to bounds. */
    static void mix(double[] target, double[] source, int offset, double gain) {
        for (int i = 0; i < source.length; i++) {
            int j = offset + i;
            if (j < 0 || j >= target.length) continue;
            target[j] += source[i] * gain;
        }
    }

    /** Concatenates buffers end to end into a single track. */
    static double[] concat(double[]... parts) {
        int total = 0;
        for (double[] part : parts) total += part.length;
        double[] out = new double[total];
        int offset = 0;
        for (double[] part : parts) {
            System.arraycopy(part, 0, out, offset, part.length);
            offset += part.length;
        }
        return out;
    }

    // ---------------------------------------------------------------- effects

    /** One-pole low-pass. {@code cutoffHz <= 0} means "no filtering". */
    static double[] lowPass(double[] source, double cutoffHz) {
        if (cutoffHz <= 0) return source.clone();
        double alpha = 1 - Math.exp(-TAU * cutoffHz / RATE);
        double[] out = new double[source.length];
        double state = 0;
        for (int i = 0; i < source.length; i++) {
            state += alpha * (source[i] - state);
            out[i] = state;
        }
        return out;
    }

    /** One-pole high-pass, used to thin out rumble-heavy material. */
    static double[] highPass(double[] source, double cutoffHz) {
        if (cutoffHz <= 0) return source.clone();
        double alpha = 1 - Math.exp(-TAU * cutoffHz / RATE);
        double[] out = new double[source.length];
        double state = 0;
        for (int i = 0; i < source.length; i++) {
            state += alpha * (source[i] - state);
            out[i] = source[i] - state;
        }
        return out;
    }

    /**
     * Linear attack ramp over {@code seconds}, applied in place to the head of the buffer.
     * Short (1-3 ms) ramps are what keep a percussive onset from clicking.
     */
    static double[] rampIn(double[] source, double seconds) {
        int ramp = (int) Math.min(source.length, seconds * RATE);
        for (int i = 0; i < ramp; i++) source[i] *= (double) i / ramp;
        return source;
    }

    /**
     * Exponential tail fade over {@code seconds}, applied in place to the end of the buffer.
     * Long tails risk truncation clicks; a fade is cheaper than extending every note.
     */
    static double[] fadeOut(double[] source, double seconds) {
        int fade = (int) Math.min(source.length, seconds * RATE);
        int start = source.length - fade;
        for (int i = 0; i < fade; i++) source[start + i] *= Math.exp(-6.0 * i / fade);
        return source;
    }

    /** Short crossfade at the loop seam so consecutive repeats do not click. */
    static double[] seamlessLoop(double[] source, double seconds) {
        int seam = (int) Math.min(source.length / 4, seconds * RATE);
        if (seam < 2) return source;
        double[] out = source.clone();
        for (int i = 0; i < seam; i++) {
            double t = (double) i / seam;
            int head = i;
            int tail = out.length - seam + i;
            out[head] = out[head] * t + out[tail] * (1 - t);
        }
        return java.util.Arrays.copyOf(out, out.length - seam);
    }

    /** Blend between clean and hard-clipped signal. {@code drive} 0 = untouched, 1 = fully clipped. */
    static double[] saturate(double[] source, double drive) {
        double[] out = new double[source.length];
        for (int i = 0; i < source.length; i++) {
            out[i] = source[i] * (1 - drive) + Math.tanh(source[i] * 2.5) * drive;
        }
        return out;
    }

    /** Scales so the loudest sample sits at {@code peak}, guarding against a silent buffer. */
    static double[] normalize(double[] source, double peak) {
        double max = 0;
        for (double sample : source) max = Math.max(max, Math.abs(sample));
        if (max < 1e-9) return source.clone();
        double gain = peak / max;
        double[] out = source.clone();
        for (int i = 0; i < out.length; i++) out[i] *= gain;
        return out;
    }

    // ---------------------------------------------------------------- units

    static int seconds(double seconds) { return (int) Math.round(seconds * RATE); }

    static double midiToHz(double midi) { return 440.0 * Math.pow(2, (midi - 69) / 12.0); }

    // ---------------------------------------------------------------- I/O

    /**
     * Writes a mono float buffer as a stereo 16-bit PCM WAV file.
     *
     * <p>Written by hand rather than through {@code javax.sound.sampled} so the generator has no
     * dependency beyond the JDK, and so the exact container layout is under our control.
     */
    static void writeWav(Path file, double[] samples) throws IOException {
        int frames = samples.length;
        int dataBytes = frames * 2 * 2; // two channels, two bytes per sample
        ByteBuffer buffer = ByteBuffer.allocate(44 + dataBytes).order(ByteOrder.LITTLE_ENDIAN);

        buffer.put(new byte[] {'R', 'I', 'F', 'F'});
        buffer.putInt(36 + dataBytes);
        buffer.put(new byte[] {'W', 'A', 'V', 'E'});
        buffer.put(new byte[] {'f', 'm', 't', ' '});
        buffer.putInt(16);              // PCM header size
        buffer.putShort((short) 1);     // PCM, uncompressed
        buffer.putShort((short) 2);     // stereo
        buffer.putInt(RATE);
        buffer.putInt(RATE * 2 * 2);    // byte rate
        buffer.putShort((short) 4);     // block align
        buffer.putShort((short) 16);    // bits per sample
        buffer.put(new byte[] {'d', 'a', 't', 'a'});
        buffer.putInt(dataBytes);

        for (double sample : samples) {
            short value = (short) Math.round(Math.max(-1, Math.min(1, sample)) * 32767);
            buffer.putShort(value);
            buffer.putShort(value);
        }

        if (file.getParent() != null) Files.createDirectories(file.getParent());
        Files.write(file, buffer.array());
    }
}
