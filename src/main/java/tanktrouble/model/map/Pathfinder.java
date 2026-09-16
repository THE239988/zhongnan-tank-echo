package tanktrouble.model.map;

import org.jgrapht.alg.shortestpath.AStarShortestPath;
import tanktrouble.model.data.GameData.Cell;
import java.util.List;

public final class Pathfinder {
    public List<Cell> findPath(GridMap map, Cell start, Cell goal) {
        if(!map.graph().containsVertex(start) || !map.graph().containsVertex(goal)) return List.of();
        var path=new AStarShortestPath<>(map.graph(), (Cell a, Cell b)->(double)(Math.abs(a.x()-b.x())+Math.abs(a.y()-b.y()))).getPath(start,goal);
        return path==null ? List.of() : List.copyOf(path.getVertexList());
    }
}
