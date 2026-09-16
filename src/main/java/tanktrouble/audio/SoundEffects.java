package tanktrouble.audio;

import java.util.LinkedHashMap;
import java.util.Map;

import static tanktrouble.audio.ToneSynth.*;

/**
 * The game's sound-effect library, authored as code so the assets are reproducible from source
 * and carry no third-party licensing.
 *
 * <p>Each constant returns a self-contained buffer; {@link #render()} maps the asset filename used
 * at runtime to the generated samples. Design notes for the whole set:
 * <ul>
 *   <li>Player effects are brighter and louder than enemy effects, so the two never blur together
 *       when several fire in the same tick.</li>
 *   <li>Everything is short. Rapid fire lands roughly every 420 ms, so a shot over ~250 ms would
 *       smear into the next one.</li>
 *   <li>Impacts get a 1-2 ms attack ramp: a percussive onset straight to full amplitude clicks.</li>
 * </ul>
 */
enum SoundEffects {
    /** Bright descending shot. The square layer gives it bite against the low-passed ambience. */
    FIRE("fire", 0.34) {
        @Override double[] render() {
            double duration = 0.13;
            double[] out = new double[seconds(duration)];
            int length = out.length;
            double sweepPhase = 0;
            double bodyPhase = 0;
            for (int i = 0; i < length; i++) {
                double t = (double) i / RATE;
                double sweep = 880 - 560 * (t / duration);
                sweepPhase += TAU * sweep / RATE;
                bodyPhase += TAU * 220 / RATE;
                double envelope = Math.exp(-t * 26);
                out[i] = sine(sweepPhase) * 0.55 * envelope
                        + square(sweepPhase, 5) * 0.30 * envelope
                        + sine(bodyPhase) * 0.22 * Math.exp(-t * 40);
            }
            return normalize(saturate(rampIn(out, 0.001), 0.35), 0.85);
        }
    },

    /** Duller, quieter shot so enemy fire reads as background threat rather than your own. */
    ENEMY_FIRE("enemy-fire", 0.30) {
        @Override double[] render() {
            double duration = 0.14;
            double[] out = new double[seconds(duration)];
            double phase = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                phase += TAU * (420 - 210 * (t / duration)) / RATE;
                out[i] = (saw(phase, 6) * 0.4 + sine(phase) * 0.6) * Math.exp(-t * 24) * 0.7;
            }
            return normalize(saturate(rampIn(lowPass(out, 2200), 0.001), 0.25), 0.6);
        }
    },

    /** You took a hit: noise crack plus a falling alarm tone so it is hard to miss. */
    HIT_PLAYER("hit-player", 0.55) {
        @Override double[] render() {
            double duration = 0.26;
            double[] out = new double[seconds(duration)];
            double[] crack = normalize(rampIn(lowPass(noise(out.length, 11), 3800), 0.001), 1.0);
            double phase = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                phase += TAU * (520 - 300 * (t / duration)) / RATE;
                out[i] = crack[i] * 0.45 * Math.exp(-t * 28)
                        + (square(phase, 4) * 0.35 + sine(phase) * 0.65) * 0.55 * Math.exp(-t * 12);
            }
            return normalize(saturate(rampIn(out, 0.0015), 0.4), 0.9);
        }
    },

    /** You hit an enemy: tight and dry, deliberately lower in the mix than HIT_PLAYER. */
    HIT_ENEMY("hit-enemy", 0.40) {
        @Override double[] render() {
            double duration = 0.14;
            double[] out = new double[seconds(duration)];
            double[] crack = normalize(rampIn(lowPass(noise(out.length, 23), 2600), 0.001), 1.0);
            double phase = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                phase += TAU * 180 / RATE;
                out[i] = crack[i] * 0.75 * Math.exp(-t * 42) + sine(phase) * 0.45 * Math.exp(-t * 30);
            }
            return normalize(saturate(rampIn(out, 0.001), 0.3), 0.7);
        }
    },

    /** Enemy destroyed: sustained low-passed rumble with a distorted core. */
    EXPLOSION("explosion", 0.85) {
        @Override double[] render() {
            double duration = 0.55;
            double[] out = new double[seconds(duration)];
            double[] raw = noise(out.length, 37);
            double[] rumble = lowPass(raw, 320);
            double[] crackle = lowPass(raw, 1400);
            double phase = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                phase += TAU * (95 - 55 * (t / duration)) / RATE;
                out[i] = rumble[i] * 0.75 * Math.exp(-t * 7)
                        + sine(phase) * 0.55 * Math.exp(-t * 5)
                        + crackle[i] * 0.25 * Math.exp(-t * 22);
            }
            return normalize(saturate(rampIn(out, 0.002), 0.6), 0.95);
        }
    },

    /** Coin pickup: a two-note ping. Both notes are pure enough to sit above the mix cleanly. */
    COIN("coin", 0.65) {
        @Override double[] render() {
            double[] out = concat(note(84, 0.075, 0.0, 0.35), note(91, 0.33, 0.0, 0.35));
            return normalize(rampIn(out, 0.001), 0.72);
        }
    },

    /** Supply pickup: a three-note rising blip, brighter than COIN so the two are distinguishable. */
    ITEM("item", 0.70) {
        @Override double[] render() {
            double[] out = concat(
                    note(79, 0.06, 0.0, 0.35),
                    note(84, 0.06, 0.0, 0.35),
                    note(88, 0.30, 0.0, 0.35));
            return normalize(saturate(rampIn(out, 0.001), 0.15), 0.75);
        }
    },

    /** Resonance pulse: a ring-modulated sweep. Nods at the energy mechanic that fires it. */
    PULSE("pulse", 0.80) {
        @Override double[] render() {
            double duration = 0.48;
            double[] out = new double[seconds(duration)];
            double phase = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                phase += TAU * (180 + 700 * (t / duration)) / RATE;
                double envelope = Math.sin(Math.PI * Math.min(1, t / duration));
                out[i] = sine(phase) * (0.65 + 0.35 * sine(TAU * 62 * t)) * envelope;
            }
            return normalize(saturate(rampIn(out, 0.004), 0.2), 0.85);
        }
    },

    /** Portal: a short upward warp, deliberately opposite in pitch direction to PULSE. */
    TELEPORT("teleport", 0.65) {
        @Override double[] render() {
            double duration = 0.30;
            double[] out = new double[seconds(duration)];
            double phase = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                phase += TAU * (260 + 900 * (t / duration)) / RATE;
                out[i] = triangle(phase) * Math.exp(-t * 9) * 0.6
                        + sine(phase * 2.02) * Math.exp(-t * 13) * 0.3;
            }
            return normalize(rampIn(out, 0.003), 0.65);
        }
    },

    /** Lava tick: low and bubbling, mixed near the noise floor so it never masks a shot. */
    LAVA("lava", 0.35) {
        @Override double[] render() {
            double duration = 0.35;
            double[] out = new double[seconds(duration)];
            double[] raw = noise(out.length, 53);
            double[] rumble = lowPass(raw, 420);
            double phase = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                phase += TAU * (150 - 60 * (t / duration)) / RATE;
                out[i] = rumble[i] * 0.55 * Math.exp(-t * 8) + sine(phase) * 0.35 * Math.exp(-t * 11);
            }
            return normalize(rampIn(out, 0.004), 0.5);
        }
    },

    /**
     * Shield absorbed a hit: a metallic clang with a rising tail. Distinct from HIT_PLAYER so the
     * player can tell "blocked" from "damaged" without looking at the health bar.
     */
    SHIELD_BLOCK("shield-block", 0.70) {
        @Override double[] render() {
            double duration = 0.30;
            double[] out = new double[seconds(duration)];
            double[] raw = noise(out.length, 89);
            double[] clang = lowPass(raw, 5000);
            double[] ring = highPass(clang, 900);
            double rootPhase = 0;
            for (int i = 0; i < out.length; i++) {
                double t = (double) i / RATE;
                rootPhase += TAU * (740 + 260 * (t / duration)) / RATE;
                double envelope = Math.exp(-t * 13);
                out[i] = ring[i] * 0.5 * Math.exp(-t * 30)
                        + (sine(rootPhase) * 0.45 + sine(rootPhase * 2.76) * 0.20) * envelope;
            }
            return normalize(saturate(rampIn(out, 0.002), 0.2), 0.8);
        }
    },

    /** Turret placed: a mechanical thunk followed by a short two-note "ready" confirmation. */
    DEPLOY("deploy", 0.60) {
        @Override double[] render() {
            double duration = 0.10;
            double[] thunk = new double[seconds(duration)];
            double[] noise = noise(thunk.length, 71);
            double[] thunkBody = lowPass(noise, 900);
            for (int i = 0; i < thunk.length; i++) thunk[i] = thunkBody[i] * Math.exp(-(double) i / RATE * 40);
            double[] out = concat(normalize(rampIn(thunk, 0.001), 0.6), note(72, 0.07, 0.0, 0.2), note(79, 0.22, 0.0, 0.25));
            return normalize(rampIn(out, 0.001), 0.7);
        }
    },

    /** Wave cleared: a bright rising major triad with a shimmer on top. */
    WAVE_CLEAR("wave-clear", 0.80) {
        @Override double[] render() {
            double[] out = concat(
                    note(72, 0.09, 0.0, 0.3),
                    note(76, 0.09, 0.0, 0.3),
                    note(79, 0.09, 0.0, 0.3),
                    note(84, 0.50, 0.0, 0.5));
            double[] tail = new double[out.length];
            int tailStart = seconds(0.27);
            for (int i = tailStart; i < out.length; i++) {
                double t = (double) (i - tailStart) / RATE;
                tail[i] = sine(TAU * 1200 * t) * 0.12 * Math.exp(-t * 6);
            }
            return normalize(saturate(rampIn(concat(out, tail), 0.002), 0.1), 0.8);
        }
    },

    /** Battle over and lost: a slow descending minor triad, long low-passed tail. */
    GAME_OVER("game-over", 0.85) {
        @Override double[] render() {
            double[] out = new double[seconds(1.6)];
            int[] notes = {69, 65, 60};
            double[] offsets = {0.0, 0.22, 0.46};
            for (int n = 0; n < notes.length; n++) {
                mix(out, note(notes[n], 0.85, 0.0, n == 2 ? 0.9 : 0.4), seconds(offsets[n]), n == 2 ? 1.0 : 0.75);
            }
            return normalize(fadeOut(lowPass(rampIn(out, 0.006), 2600), 0.2), 0.8);
        }
    };

    private final String file;
    private final double gain;

    SoundEffects(String file, double gain) {
        this.file = file;
        this.gain = gain;
    }

    /** Asset filename without extension, e.g. {@code fire} for {@code fire.wav}. */
    String file() { return file; }

    /** Per-effect trim applied after synthesis, so the mix is balanced without touching volumes. */
    double gain() { return gain; }

    abstract double[] render();

    /** Synthesises every effect, keyed by asset filename (no extension). */
    static Map<String, double[]> renderAll() {
        Map<String, double[]> rendered = new LinkedHashMap<>();
        for (SoundEffects effect : values()) {
            rendered.put(effect.file(), normalize(rampIn(effect.render(), 0.001), effect.gain()));
        }
        return rendered;
    }

    /**
     * A single pitched note with a mild two-partial timbre and percussive decay.
     *
     * @param midi     pitch as a MIDI note number
     * @param duration seconds the note is held before the release tail
     */
    private static double[] note(int midi, double duration, double attack, double decay) {
        double hz = midiToHz(midi);
        double total = duration + 0.06;
        double[] out = new double[seconds(total)];
        double phase = 0;
        double detunePhase = 0;
        for (int i = 0; i < out.length; i++) {
            double t = (double) i / RATE;
            phase += TAU * hz / RATE;
            detunePhase += TAU * hz * 1.004 / RATE;
            double envelope = t < duration ? 1.0 : Math.exp(-(t - duration) * 70);
            double onset = Math.min(1, t / Math.max(1e-4, attack));
            out[i] = (sine(phase) * 0.7 + sine(phase * 2) * 0.16 + sine(detunePhase) * 0.14)
                    * envelope * onset * Math.exp(-t * decay);
        }
        return out;
    }

    private static final double TAU = Math.PI * 2;
}
