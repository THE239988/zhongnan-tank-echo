package tanktrouble.view;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpeningVideoTest {
    @Test
    void openingVideoResolvesFromAssetsDirectory() {
        Path projectDir = Path.of("C:", "games", "tank-trouble");

        assertEquals(projectDir.resolve("assets").resolve("opening.mp4"),
                TankTroubleApp.openingVideoPath(projectDir));
    }
}
