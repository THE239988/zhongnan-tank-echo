package tanktrouble.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ClipsTest {
    @Test void replayStopsTheExistingVoiceBeforePlayingAgain() throws Exception {
        ReplayProbe probe = new ReplayProbe();

        Clips.replay(probe, 0.4);

        assertEquals("stop:play:0.4", probe.calls);
    }

    @Test void disposingAPlayerUsesTheJavaFxMethodName() throws Exception {
        DisposeProbe probe = new DisposeProbe();

        Clips.disposePlayer(probe);

        assertEquals(1, probe.disposed);
    }

    public static final class ReplayProbe {
        private String calls = "";

        public void stop() {
            calls += "stop:";
        }

        public void play(double volume) {
            calls += "play:" + volume;
        }
    }

    public static final class DisposeProbe {
        private int disposed;

        public void dispose() {
            disposed++;
        }
    }
}
