package tanktrouble.model.persistence;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import static org.junit.jupiter.api.Assertions.*;

class LeaderboardTest {
    @TempDir Path folder;
    @Test void recordsSurviveRepositoryRecreationAndActiveMatchesAreNotSaved() throws Exception {
        Path file=folder.resolve("scores.properties");LeaderboardRepository repo=new LeaderboardRepository(file);
        TankGameModel game=new TankGameModel();game.selectMode(Mode.ENDLESS);game.startBattle(1);
        repo.save(game.snapshot());assertFalse(Files.exists(file));
        game.pause();game.finishRun();repo.save(game.snapshot());
        var entries=new LeaderboardRepository(file).load();assertEquals(1,entries.size());assertEquals(Mode.ENDLESS,entries.get(0).mode());
        assertEquals(1,Files.list(folder).count());
    }
    @Test void malformedRecordDoesNotPreventLoadingOtherRecords() throws Exception {
        Path file=folder.resolve("scores.properties");
        Files.writeString(file,"count=2\n0.mode=INVALID\n1.mode=SINGLE\n1.score=20\n1.wave=1\n1.kills=3\n1.time=1000\n");
        var entries=new LeaderboardRepository(file).load();assertEquals(1,entries.size());assertEquals(20,entries.get(0).score());
    }
}
