package tanktrouble.model.map;

import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import org.jgrapht.Graph;
import org.jgrapht.graph.DefaultEdge;
import org.jgrapht.graph.SimpleGraph;
import java.util.*;

public final class GridMap {
    private final List<Rect> walls;
    private final Graph<Cell, DefaultEdge> graph;
    GridMap(boolean[][] vertical, boolean[][] horizontal) {
        List<Rect> result = new ArrayList<>();
        graph = new SimpleGraph<>(DefaultEdge.class);
        for (int x=0; x<Rules.COLS; x++) for (int y=0; y<Rules.ROWS; y++) graph.addVertex(new Cell(x,y));
        for (int x=0; x<=Rules.COLS; x++) for (int y=0; y<Rules.ROWS; y++) {
            if (vertical[x][y]) result.add(new Rect(x*Rules.CELL-Rules.WALL/2, y*Rules.CELL-Rules.WALL/2, Rules.WALL, Rules.CELL+Rules.WALL));
            else if (x>0 && x<Rules.COLS) graph.addEdge(new Cell(x-1,y),new Cell(x,y));
        }
        for (int x=0; x<Rules.COLS; x++) for (int y=0; y<=Rules.ROWS; y++) {
            if (horizontal[x][y]) result.add(new Rect(x*Rules.CELL-Rules.WALL/2, y*Rules.CELL-Rules.WALL/2, Rules.CELL+Rules.WALL, Rules.WALL));
            else if (y>0 && y<Rules.ROWS) graph.addEdge(new Cell(x,y-1),new Cell(x,y));
        }
        walls = List.copyOf(result);
    }
    public List<Rect> walls() { return walls; }
    Graph<Cell, DefaultEdge> graph() { return graph; }
    public Cell cell(double x, double y) { return new Cell((int)(x/Rules.CELL),(int)(y/Rules.CELL)); }
    public boolean blocked(Rect box) {
        return box.x()<0 || box.y()<0 || box.x()+box.width()>Rules.WIDTH || box.y()+box.height()>Rules.HEIGHT
                || walls.stream().anyMatch(box::intersects);
    }
}
