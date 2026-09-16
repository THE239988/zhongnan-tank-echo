package tanktrouble.model.map;

import java.util.*;
import tanktrouble.model.data.GameData.Cell;
import tanktrouble.model.data.Rules;

/** Randomized Prim and extra passages retained from the supplied prototype. */
public final class RandomMapGenerator {
    private record Edge(Cell from, Cell to) {}
    public GridMap generate(long seed) {
        Random random = new Random(seed);
        boolean[][] vertical = new boolean[Rules.COLS+1][Rules.ROWS];
        boolean[][] horizontal = new boolean[Rules.COLS][Rules.ROWS+1];
        for (boolean[] row: vertical) Arrays.fill(row,true);
        for (boolean[] row: horizontal) Arrays.fill(row,true);
        Set<Cell> visited = new HashSet<>();
        List<Edge> frontier = new ArrayList<>();
        Cell start = new Cell(random.nextInt(Rules.COLS),random.nextInt(Rules.ROWS));
        visited.add(start); addFrontier(start,frontier);
        while (!frontier.isEmpty()) {
            Edge edge=frontier.remove(random.nextInt(frontier.size()));
            if (!visited.add(edge.to())) continue;
            Cell a=edge.from(), b=edge.to();
            if (a.x()!=b.x()) vertical[Math.max(a.x(),b.x())][a.y()]=false;
            else horizontal[a.x()][Math.max(a.y(),b.y())]=false;
            addFrontier(b,frontier);
        }
        for (int x=1;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++) if(random.nextDouble()<.24) vertical[x][y]=false;
        for (int x=0;x<Rules.COLS;x++) for(int y=1;y<Rules.ROWS;y++) if(random.nextDouble()<.24) horizontal[x][y]=false;
        return new GridMap(vertical,horizontal);
    }
    private void addFrontier(Cell a,List<Edge> result) {
        for (int[] d: new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
            Cell b=new Cell(a.x()+d[0],a.y()+d[1]);
            if(b.x()>=0 && b.y()>=0 && b.x()<Rules.COLS && b.y()<Rules.ROWS) result.add(new Edge(a,b));
        }
    }
}
