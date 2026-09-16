package tanktrouble.model.map;

import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MapTest {
    @Test void primMapsHaveReachableCellsAndSafeCentersAcrossSeeds() {
        Pathfinder pathfinder=new Pathfinder();
        for(int seed=0;seed<100;seed++) {
            GridMap map=new RandomMapGenerator().generate(seed);
            for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++) {
                Cell cell=new Cell(x,y);
                assertFalse(map.blocked(Rect.centered(cell.centerX(),cell.centerY(),Rules.TANK_HALF)));
                assertFalse(pathfinder.findPath(map,new Cell(0,0),cell).isEmpty(),"unreachable seed "+seed);
            }
        }
    }
    @Test void aStarMatchesBreadthFirstMinimumLength() {
        GridMap map=new RandomMapGenerator().generate(55);
        Cell start=new Cell(0,0),goal=new Cell(11,8);
        Map<Cell,Integer> distances=new HashMap<>();ArrayDeque<Cell> queue=new ArrayDeque<>();
        queue.add(start);distances.put(start,0);
        while(!queue.isEmpty()) {
            Cell current=queue.remove();
            for(var edge:map.graph().edgesOf(current)) {
                Cell next=map.graph().getEdgeSource(edge).equals(current)?map.graph().getEdgeTarget(edge):map.graph().getEdgeSource(edge);
                if(!distances.containsKey(next)) {distances.put(next,distances.get(current)+1);queue.add(next);}
            }
        }
        assertEquals(distances.get(goal)+1,new Pathfinder().findPath(map,start,goal).size());
        assertTrue(new Pathfinder().findPath(map,new Cell(-1,0),goal).isEmpty());
    }
    @Test void seedsReproduceSameWallsAndWallsAreReadOnly() {
        assertEquals(new RandomMapGenerator().generate(7).walls(),new RandomMapGenerator().generate(7).walls());
        assertThrows(UnsupportedOperationException.class,()->new RandomMapGenerator().generate(7).walls().clear());
    }
    public static GridMap openMap() {
        boolean[][] v=new boolean[Rules.COLS+1][Rules.ROWS],h=new boolean[Rules.COLS][Rules.ROWS+1];
        Arrays.fill(v[0],true);Arrays.fill(v[Rules.COLS],true);
        for(int x=0;x<Rules.COLS;x++) {h[x][0]=true;h[x][Rules.ROWS]=true;}
        return new GridMap(v,h);
    }
}
