package tanktrouble.view;

import java.util.List;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;

import static org.junit.jupiter.api.Assertions.*;

class BattleRendererTest {
    @Test void aimAssistUsesThePlayerAssignedToThisClient() {
        TankView p1=tank(0,0,0);
        TankView p2=tank(1,1,9000);
        Snapshot snapshot=snapshot(Mode.MULTI,List.of(p1,p2));

        assertEquals(p2,BattleRenderer.localAimTank(snapshot,1));
        assertNull(BattleRenderer.localAimTank(snapshot,0));
    }

    @Test void freeForAllUsesDistinctPlayerColorsWhileCoopUsesOneTeamColor() {
        assertNotEquals(BattleRenderer.tankColor(Mode.MULTI,0),BattleRenderer.tankColor(Mode.MULTI,1));
        assertNotEquals(BattleRenderer.tankColor(Mode.MULTI,1),BattleRenderer.tankColor(Mode.MULTI,2));
        assertEquals(BattleRenderer.tankColor(Mode.COOP,0),BattleRenderer.tankColor(Mode.COOP,4));
    }

    @Test void bulletStateMovesIntoBrightnessAndLength() {
        assertTrue(BattleRenderer.bulletAlpha(3)>BattleRenderer.bulletAlpha(0));
        assertTrue(BattleRenderer.bulletTailSeconds(3)>BattleRenderer.bulletTailSeconds(0));
        assertEquals(1,BattleRenderer.bulletAlpha(20));
    }

    @Test void aimFadesFromTheMuzzleAndClampsAtTheEndpoints() {
        assertEquals(1,BattleRenderer.aimAlpha(0));
        assertEquals(.08,BattleRenderer.aimAlpha(1),1e-9);
        assertEquals(1,BattleRenderer.aimAlpha(-1));
        assertEquals(0,BattleRenderer.aimAlpha(2),1e-9);
    }

    @Test void bulletOwnerIsResolvedBackToItsPlayerSeat() {
        List<TankView> tanks=List.of(tank(4,2,0),tank(9,-1,0));

        assertEquals(2,BattleRenderer.ownerSeat(4,tanks));
        assertEquals(-1,BattleRenderer.ownerSeat(9,tanks));
        assertEquals(-1,BattleRenderer.ownerSeat(99,tanks));
    }

    private static TankView tank(int id,int player,long aimMs) {
        return new TankView(id,player,TankType.BALANCED,100+player*80,100,0,10,10,0,
                0,0,0,0,aimMs,0,0,0,false);
    }

    private static Snapshot snapshot(Mode mode,List<TankView> tanks) {
        return new Snapshot(State.RUNNING,mode,Result.NONE,1,0,0,0,0,0,1,0,tanks,
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
}
