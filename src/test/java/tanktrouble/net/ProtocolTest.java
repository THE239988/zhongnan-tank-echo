package tanktrouble.net;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.net.Protocol.*;

class ProtocolTest {
    @Test void integratedMultiplayerProtocolUsesTheFourthWireVersion() {
        assertEquals(4L,VERSION);
    }

    @Test void roomInfoCarriesBattleTypeAndShowsItInTheLobbyLabel() {
        RoomInfo room=new RoomInfo(7,"联合行动",2,5,Phase.LOBBY,BattleType.COOP_BOSS);

        assertEquals(BattleType.COOP_BOSS,room.battleType());
        assertTrue(room.label().contains("合作讨伐"));
    }

    @Test void missingCreateRoomBattleTypeDefaultsToFreeForAll() {
        CreateRoom request=new CreateRoom("训练室",5,null);

        assertEquals(BattleType.FREE_FOR_ALL,request.battleType());
    }
}
