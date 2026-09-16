package tanktrouble.audio;

import java.util.LinkedHashMap;
import java.util.Map;

import static tanktrouble.audio.ToneSynth.*;

/**
 * Procedural background music: two looping tracks, one for the menus and one for combat.
 *
 * <p>Both tracks are a fixed chord progression played as layered voices. Everything is sequenced
 * on a 16th-note grid expressed in seconds, so nothing accumulates rounding drift and the loop
 * length is exactly {@code bars * 4 beats}. The final buffer is run through
 * {@link ToneSynth#seamlessLoop} so repeats do not click.
 *
 * <p>Voices are written to {@code note} buffers that are one step shorter than the grid, leaving a
 * gap between consecutive notes. Without that gap every note-on lands on the previous note's
 * decaying tail and the whole mix turns to mud.
 */
enum Music {
    /**
     * Menu track: slow, unobtrusive, no percussion. Meant to sit under menus without competing
     * with UI feedback, so it stays well below the sound effects in level.
     */
    MENU("bgm-menu", 92, 8, 0.34, 0.7,
            new int[][] {{57, 60, 64}, {53, 57, 60}, {60, 64, 67}, {55, 59, 62}},
            new int[][] {{69, 72, 76, 79}, {65, 69, 72, 76}, {72, 76, 79, 83}, {67, 71, 74, 79}},
            2, 8, 6, 7, 0.6),

    /**
     * Battle track: faster, driving eighth-note bass and a steady kick. Kept melodic rather than
     * percussive so it does not mask gunfire, and mixed louder than the menu track since it has to
     * carry over combat noise.
     */
    BATTLE("bgm-battle", 138, 8, 0.40, 0.78,
            new int[][] {{45, 48, 52}, {41, 45, 48}, {48, 52, 55}, {43, 47, 50}},
            new int[][] {{69, 72, 76, 79}, {65, 69, 72, 76}, {72, 76, 79, 83}, {67, 71, 74, 79}},
            2, 8, 6, 5, 0.55);

    private final String file;
    private final int bpm;
    private final int bars;
    private final double level;
    private final double bassLevel;
    private final int[][] chords;
    private final int[][] melodies;
    private final int bassOctaveShift;
    private final int melodyOctaveShift;
    private final int melodySteps;
    private final int leadEvery;
    private final double padDetune;

    Music(String file, int bpm, int bars, double level, double bassLevel, int[][] chords,
          int[][] melodies, int bassOctaveShift, int melodyOctaveShift, int melodySteps,
          int leadEvery, double padDetune) {
        this.file = file;
        this.bpm = bpm;
        this.bars = bars;
        this.level = level;
        this.bassLevel = bassLevel;
        this.chords = chords;
        this.melodies = melodies;
        this.bassOctaveShift = bassOctaveShift;
        this.melodyOctaveShift = melodyOctaveShift;
        this.melodySteps = melodySteps;
        this.leadEvery = leadEvery;
        this.padDetune = padDetune;
    }

    String file() { return file; }

    double[] render() {
        double step = 60.0 / bpm / 4;              // one 16th note
        double[] out = new double[seconds(step * 16 * bars)];
        boolean battle = this == BATTLE;
        int chordSteps = battle ? 16 : 32;         // battle chords move twice as fast

        for (int bar = 0; bar < bars; bar++) {
            int[] chord = chords[bar % chords.length];
            int[] melody = melodies[bar % melodies.length];
            double barStart = bar * 16 * step;

            for (int stepIndex = 0; stepIndex < 16; stepIndex++) {
                double at = barStart + stepIndex * step;
                if (stepIndex % chordSteps == 0) {
                    int[] voicing = new int[chord.length];
                    for (int i = 0; i < chord.length; i++) voicing[i] = chord[i] - 12;
                    mix(out, pad(voicing, step * chordSteps, at), seconds(at), 1.0);
                }
                int bassNote = chord[0] - bassOctaveShift * 12;
                if (battle) {
                    mix(out, note(bassNote, step * 0.7, 0.03, 0.8, 0.0), seconds(at), bassLevel);
                    mix(out, note(bassNote, step * 0.7, 0.03, 0.8, 0.0), seconds(at + step / 2), bassLevel * 0.7);
                } else if (stepIndex % 8 == 0) {
                    mix(out, note(bassNote, step * 5.5, 0.04, 0.6, 0.0), seconds(at), bassLevel);
                }

                if (stepIndex % leadEvery == 0) {
                    int scaleIndex = (bar * 16 + stepIndex) / leadEvery % melody.length;
                    mix(out, note(melody[scaleIndex] - melodyOctaveShift * 12, step * 2.2, 0.05, 0.5, 0.35),
                            seconds(at), 0.5);
                }

                if (battle) {
                    if (stepIndex % 8 == 0 || stepIndex == 6 || stepIndex == 14) {
                        mix(out, kick(), seconds(at), 0.9);
                    }
                    if (stepIndex == 4 || stepIndex == 12) mix(out, snare(), seconds(at), 0.5);
                    if (stepIndex % 2 == 0) mix(out, hat(step * 0.5), seconds(at), stepIndex % 4 == 0 ? 0.3 : 0.18);
                }
            }
        }

        double[] looped = seamlessLoop(out, 0.01);
        return normalize(saturate(looped, 0.12), level);
    }

    static Map<String, double[]> renderAll() {
        Map<String, double[]> rendered = new LinkedHashMap<>();
        for (Music track : values()) rendered.put(track.file(), track.render());
        return rendered;
    }

    // ---------------------------------------------------------------- voices

    /**
     * Sustained chord voice. Each chord tone is doubled at a slightly detuned pitch so the pad
     * shimmers instead of sounding like a bare additive stack.
     */
    private double[] pad(int[] notes, double duration, double at) {
        double[] out = new double[seconds(duration)];
        for (int midi : notes) {
            double hz = midiToHz(midi);
            double phase = 0;
            double detunePhase = 0;
            double phase2 = 0;
            double detunePhase2 = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                phase += TAU * hz / RATE;
                detunePhase += TAU * hz * (1 + padDetune * 0.004) / RATE;
                phase2 += TAU * hz * 2 / RATE;
                detunePhase2 += TAU * hz * 2 * (1 - padDetune * 0.003) / RATE;
                double envelope = adsr(t, duration, 0.09, 0.22, 0.55, 0.28);
                out[i] += (sine(phase) * 0.42 + sine(detunePhase) * 0.42
                        + sine(phase2) * 0.10 + sine(detunePhase2) * 0.10) * envelope;
            }
        }
        return lowPass(out, 2400);
    }

    /** Pitched note with an optional doubling partial for a metallic lead tone. */
    private static double[] note(int midi, double duration, double attack, double decay, double detune) {
        double hz = midiToHz(midi);
        double[] out = new double[seconds(duration + 0.10)];
        double phase = 0;
        double detunePhase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            phase += TAU * hz / RATE;
            detunePhase += TAU * hz * (1 + detune * 0.01) / RATE;
            double envelope = (t < duration ? 1.0 : Math.exp(-(t - duration) * 60)) * Math.exp(-t * decay);
            double onset = Math.min(1, t / Math.max(1e-4, attack));
            out[i] = (sine(phase) * 0.62 + triangle(phase) * 0.28 + sine(detunePhase) * detune * 0.5)
                    * envelope * onset;
        }
        return out;
    }

    // ---------------------------------------------------------------- drums

    private static double[] kick() {
        double duration = 0.26;
        double[] out = new double[seconds(duration + 0.08)];
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            double hz = 48 + 132 * Math.exp(-t * 45);
            phase += TAU * hz / RATE;
            out[i] = sine(phase) * Math.exp(-t * 11);
        }
        return normalize(rampIn(out, 0.002), 0.9);
    }

    private static double[] snare() {
        double duration = 0.22;
        double[] out = new double[seconds(duration + 0.08)];
        double[] noise = highPass(lowPass(noise(out.length, 97), 6500), 700);
        double phase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            phase += TAU * 185 / RATE;
            out[i] = noise[i] * Math.exp(-t * 17) * 0.85 + sine(phase) * Math.exp(-t * 26) * 0.3;
        }
        return normalize(rampIn(out, 0.002), 0.6);
    }

    private static double[] hat(double duration) {
        double[] out = new double[seconds(duration + 0.03)];
        double[] noise = highPass(noise(out.length, 131), 5500);
        for (int i = 0; i < out.length; i++) out[i] = noise[i] * Math.exp(-(double) i / RATE * 75);
        return normalize(rampIn(out, 0.001), 0.35);
    }

    private static final double TAU = Math.PI * 2;
}
