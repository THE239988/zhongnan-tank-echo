package tanktrouble.view;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class GameSettingsTest {
    @TempDir Path directory;
    @Test void missingSettingsUseSafeDefaults() throws Exception {
        assertEquals(GameSettings.defaults(),GameSettings.load(directory.resolve("settings.properties")));
    }
    @Test void savedSettingsSurviveReloadAndReplacement() throws Exception {
        Path file=directory.resolve("data/settings.properties");
        new GameSettings(false,true,.25,.4).save(file);
        assertEquals(new GameSettings(false,true,.25,.4),GameSettings.load(file));
        new GameSettings(true,false,.9,.1).save(file);
        assertEquals(new GameSettings(true,false,.9,.1),GameSettings.load(file));
        try(var files=Files.list(file.getParent())) {assertEquals(1,files.count());}
    }
    @Test void unknownValuesFallBackToDefaults() throws Exception {
        Path file=directory.resolve("settings.properties");
        Files.writeString(file,"terrain=invalid\nfullscreen=invalid\n");
        assertEquals(GameSettings.defaults(),GameSettings.load(file));
    }
    /**
     * Settings files written before audio existed have no volume keys. They must keep loading with
     * default levels instead of resetting the player's other preferences.
     */
    @Test void settingsWrittenBeforeAudioKeepDefaultsAndStayLoadable() throws Exception {
        Path file=directory.resolve("settings.properties");
        Files.writeString(file,"terrain=false\nfullscreen=true\n");
        GameSettings loaded=GameSettings.load(file);
        assertFalse(loaded.terrain());
        assertTrue(loaded.fullscreen());
        assertEquals(GameSettings.DEFAULT_MUSIC_VOLUME,loaded.musicVolume());
        assertEquals(GameSettings.DEFAULT_SFX_VOLUME,loaded.sfxVolume());
    }
    @Test void volumesAreClampedToTheAudibleRange() throws Exception {
        Path file=directory.resolve("settings.properties");
        Files.writeString(file,"volume.music=4.5\nvolume.sfx=-2\n");
        GameSettings loaded=GameSettings.load(file);
        assertEquals(1,loaded.musicVolume());
        assertEquals(0,loaded.sfxVolume());
    }
    @Test void malformedVolumeFallsBackWithoutDiscardingOtherSettings() throws Exception {
        Path file=directory.resolve("settings.properties");
        Files.writeString(file,"terrain=false\nvolume.music=loud\nvolume.sfx=NaN\n");
        GameSettings loaded=GameSettings.load(file);
        assertFalse(loaded.terrain());
        assertEquals(GameSettings.DEFAULT_MUSIC_VOLUME,loaded.musicVolume());
        assertEquals(GameSettings.DEFAULT_SFX_VOLUME,loaded.sfxVolume());
    }
}
