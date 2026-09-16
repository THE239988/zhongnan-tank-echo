package tanktrouble.model.persistence;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tanktrouble.model.data.GameData.*;

public final class LeaderboardRepository {
    public record Entry(Mode mode,int score,int wave,int kills,long elapsedMs,String date) {}
    private final Path file;
    public LeaderboardRepository(Path file) {this.file=file;}
    public List<Entry> load() throws IOException {
        if(!Files.exists(file)) return List.of();
        Properties data=new Properties();
        try(Reader reader=Files.newBufferedReader(file)) {data.load(reader);}
        catch(IllegalArgumentException e) {throw new IOException("Invalid leaderboard file",e);}
        List<Entry> result=new ArrayList<>();
        int count;
        try {count=Math.min(100,Integer.parseInt(data.getProperty("count","0")));} catch(NumberFormatException e) {return List.of();}
        for(int i=0;i<count;i++) {
            String prefix=i+".";
            try {
                result.add(new Entry(Mode.valueOf(data.getProperty(prefix+"mode")),Integer.parseInt(data.getProperty(prefix+"score")),
                        Integer.parseInt(data.getProperty(prefix+"wave")),Integer.parseInt(data.getProperty(prefix+"kills")),
                        Long.parseLong(data.getProperty(prefix+"time")),data.getProperty(prefix+"date","")));
            } catch(IllegalArgumentException | NullPointerException ignored) { /* Skip a damaged record, retain the rest. */ }
        }
        return List.copyOf(result);
    }
    public void save(Snapshot snapshot) throws IOException {
        if(snapshot.state()!=State.RESULT || snapshot.mode()==Mode.DUEL) return;
        List<Entry> entries=new ArrayList<>(load());
        entries.add(new Entry(snapshot.mode(),snapshot.score(),snapshot.wave(),snapshot.kills(),snapshot.elapsedMs(),Instant.now().toString()));
        entries.sort(Comparator.comparingInt(Entry::score).reversed().thenComparing(Comparator.comparingInt(Entry::wave).reversed()).thenComparingLong(Entry::elapsedMs));
        entries=new ArrayList<>(entries.subList(0,Math.min(30,entries.size())));
        Properties data=new Properties();data.setProperty("count",Integer.toString(entries.size()));
        for(int i=0;i<entries.size();i++) {
            Entry entry=entries.get(i);String p=i+".";
            data.setProperty(p+"mode",entry.mode().name());data.setProperty(p+"score",""+entry.score());
            data.setProperty(p+"wave",""+entry.wave());data.setProperty(p+"kills",""+entry.kills());
            data.setProperty(p+"time",""+entry.elapsedMs());data.setProperty(p+"date",entry.date());
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temp=Files.createTempFile(file.toAbsolutePath().getParent(),"scores-",".tmp");
        try {
            try(Writer writer=Files.newBufferedWriter(temp)) {data.store(writer,"Tank Trouble leaderboard v2");}
            try {Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e) {Files.move(temp,file,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }
}
