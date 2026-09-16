package tanktrouble.model.map;
import java.util.Arrays;
import tanktrouble.model.data.GameData.Rect;
import tanktrouble.model.data.Rules;
public final class TestMaps {
    private TestMaps() {}
    public static GridMap open() {return MapTest.openMap();}
    public static GridMap withWall(Rect... walls) {
        boolean[][] vertical=new boolean[Rules.COLS+1][Rules.ROWS];
        boolean[][] horizontal=new boolean[Rules.COLS][Rules.ROWS+1];
        Arrays.fill(vertical[0],true);Arrays.fill(vertical[Rules.COLS],true);
        for(int x=0;x<Rules.COLS;x++) {horizontal[x][0]=true;horizontal[x][Rules.ROWS]=true;}
        for(int x=1;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++)
            for(Rect wall:walls) vertical[x][y]|=new Rect(x*Rules.CELL-3,y*Rules.CELL-3,6,Rules.CELL+6).intersects(wall);
        return new GridMap(vertical,horizontal);
    }
}
