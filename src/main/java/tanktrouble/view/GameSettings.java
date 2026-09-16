package tanktrouble.view;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

record GameSettings(boolean terrain,boolean fullscreen,double musicVolume,double sfxVolume) {
    static final double DEFAULT_MUSIC_VOLUME=0.6;
    static final double DEFAULT_SFX_VOLUME=0.8;

    /** Settings for a player who has never opened the settings page. */
    static GameSettings defaults() {return new GameSettings(true,false,DEFAULT_MUSIC_VOLUME,DEFAULT_SFX_VOLUME);}

    static GameSettings load(Path file) throws IOException {
        Properties values=new Properties();
        if(Files.exists(file)) try(Reader reader=Files.newBufferedReader(file)) {values.load(reader);}
        return new GameSettings(!"false".equals(values.getProperty("terrain")),
                "true".equals(values.getProperty("fullscreen")),
                volume(values.getProperty("volume.music"),DEFAULT_MUSIC_VOLUME),
                volume(values.getProperty("volume.sfx"),DEFAULT_SFX_VOLUME));
    }

    /**
     * Parses a stored volume, falling back to {@code fallback} for a missing or malformed entry.
     * Settings files written before volumes existed simply lack the keys, so this keeps them
     * loadable instead of forcing the player to reset their preferences.
     */
    private static double volume(String stored,double fallback) {
        if(stored==null) return fallback;
        try {
            double value=Double.parseDouble(stored.trim());
            return Double.isFinite(value)?Math.max(0,Math.min(1,value)):fallback;
        } catch(NumberFormatException malformed) {return fallback;}
    }

    void save(Path file) throws IOException {
        Path destination=file.toAbsolutePath();Files.createDirectories(destination.getParent());
        Properties values=new Properties();
        values.setProperty("terrain",Boolean.toString(terrain));
        values.setProperty("fullscreen",Boolean.toString(fullscreen));
        values.setProperty("volume.music",Double.toString(musicVolume));
        values.setProperty("volume.sfx",Double.toString(sfxVolume));
        Path temp=Files.createTempFile(destination.getParent(),"settings-",".tmp");
        try {
            try(Writer writer=Files.newBufferedWriter(temp)) {values.store(writer,"Tank Trouble settings");}
            try {Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e) {Files.move(temp,destination,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }
}
