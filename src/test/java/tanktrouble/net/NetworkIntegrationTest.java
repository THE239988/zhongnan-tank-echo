package tanktrouble.net;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;

import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.net.Protocol.*;

class NetworkIntegrationTest {
    @Test void serverRunsFreeForAllAndCoopBossRooms() throws Exception {
        try(GameServer server=new GameServer()) {
            int port=server.start(InetAddress.getLoopbackAddress(),0);
            try(Peer host=connect(port,"房主");Peer guest=connect(port,"队员")) {
                await(()->host.remote.inLobby()&&guest.remote.inLobby(),host,guest);

                host.remote.createRoom("混战测试",5,BattleType.FREE_FOR_ALL);
                await(()->host.remote.currentRoom()>0,host,guest);
                int ffaRoom=host.remote.currentRoom();
                await(()->guest.remote.roomList().stream().anyMatch(room->room.id()==ffaRoom),host,guest);
                guest.remote.joinRoom(ffaRoom);
                await(()->memberCount(host)==2&&memberCount(guest)==2,host,guest);
                readyAndStart(host,guest);
                await(()->host.remote.hasSnapshot()&&guest.remote.hasSnapshot(),host,guest);

                Snapshot ffa=host.remote.snapshot();
                assertEquals(Mode.MULTI,ffa.mode());
                assertEquals(2,ffa.tanks().stream().filter(tank->tank.player()>=0).count());
                assertTrue(ffa.tanks().stream().noneMatch(tank->tank.player()<0));

                host.remote.leaveRoom();
                await(host.remote::inLobby,host,guest);
                guest.remote.leaveRoom();
                await(guest.remote::inLobby,host,guest);

                host.remote.createRoom("合作测试",5,BattleType.COOP_BOSS);
                await(()->host.remote.currentRoom()>0,host,guest);
                int coopRoom=host.remote.currentRoom();
                await(()->guest.remote.roomList().stream().anyMatch(room->room.id()==coopRoom),host,guest);
                guest.remote.joinRoom(coopRoom);
                await(()->memberCount(host)==2&&memberCount(guest)==2,host,guest);
                readyAndStart(host,guest);
                await(()->host.remote.hasSnapshot()&&guest.remote.hasSnapshot(),host,guest);

                Snapshot coop=host.remote.snapshot();
                assertEquals(Mode.COOP,coop.mode());
                assertEquals(2,coop.tanks().stream().filter(tank->tank.player()>=0).count());
                TankView boss=coop.tanks().stream().filter(TankView::boss).findFirst().orElseThrow();
                assertEquals(60,boss.maxHp());
            }
        }
    }

    @Test void roomIgnoresPlayerNumbersSpoofedByAnotherConnection() throws Exception {
        try(GameServer server=new GameServer()) {
            int port=server.start(InetAddress.getLoopbackAddress(),0);
            try(Peer host=connect(port,"房主");Peer guest=connect(port,"访客")) {
                await(()->host.remote.inLobby()&&guest.remote.inLobby(),host,guest);
                host.remote.createRoom("身份测试",5,BattleType.FREE_FOR_ALL);
                await(()->host.remote.currentRoom()>0,host,guest);
                int room=host.remote.currentRoom();
                await(()->guest.remote.roomList().stream().anyMatch(info->info.id()==room),host,guest);
                guest.remote.joinRoom(room);
                await(()->memberCount(host)==2&&memberCount(guest)==2,host,guest);

                LobbyState before=host.remote.lobby();
                guest.client.send(new Ready(host.remote.localPlayer(),true));
                await(()->host.remote.lobby()!=before,host,guest);

                LobbyState.Member hostMember=host.remote.lobby().members().stream()
                        .filter(member->member.player()==host.remote.localPlayer()).findFirst().orElseThrow();
                LobbyState.Member guestMember=host.remote.lobby().members().stream()
                        .filter(member->member.player()==guest.remote.localPlayer()).findFirst().orElseThrow();
                assertFalse(hostMember.ready());
                assertTrue(guestMember.ready());
            }
        }
    }

    @Test void nonContiguousPlayersReceiveTheirOwnTanksAndLeaversAreEliminated() throws Exception {
        try(GameServer server=new GameServer()) {
            int port=server.start(InetAddress.getLoopbackAddress(),0);
            try(Peer host=connect(port,"P1");Peer leaving=connect(port,"P2");Peer remaining=connect(port,"P3")) {
                await(()->host.remote.inLobby()&&leaving.remote.inLobby()&&remaining.remote.inLobby(),host,leaving,remaining);
                host.remote.createRoom("空档测试",5,BattleType.FREE_FOR_ALL);
                await(()->host.remote.currentRoom()>0,host,leaving,remaining);
                int room=host.remote.currentRoom();
                await(()->leaving.remote.roomList().stream().anyMatch(info->info.id()==room)
                        &&remaining.remote.roomList().stream().anyMatch(info->info.id()==room),host,leaving,remaining);
                leaving.remote.joinRoom(room);remaining.remote.joinRoom(room);
                await(()->memberCount(host)==3&&memberCount(remaining)==3,host,leaving,remaining);
                Peer gapRemaining=leaving.remote.localPlayer()>remaining.remote.localPlayer()?leaving:remaining;
                Peer gapLeaver=gapRemaining==leaving?remaining:leaving;
                int remainingSlot=gapRemaining.remote.localPlayer();

                gapLeaver.remote.leaveRoom();
                await(()->memberCount(host)==2&&memberCount(gapRemaining)==2,host,gapLeaver,gapRemaining);
                readyAndStart(host,gapRemaining);
                await(()->host.remote.hasSnapshot()&&gapRemaining.remote.hasSnapshot(),host,gapRemaining);

                assertEquals(List.of(0,remainingSlot),host.remote.snapshot().tanks().stream()
                        .filter(tank->tank.player()>=0).map(TankView::player).sorted().toList());

                gapRemaining.remote.leaveRoom();
                await(()->host.remote.snapshot().state()==State.RESULT,host,gapRemaining);
                assertEquals(Result.WIN,host.remote.snapshot().result());
            }
        }
    }

    private static void readyAndStart(Peer host,Peer guest) throws Exception {
        host.remote.setReady(true);guest.remote.setReady(true);
        await(()->allReady(host)&&allReady(guest),host,guest);
        host.remote.requestStart();
    }

    private static boolean allReady(Peer peer) {
        LobbyState state=peer.remote.lobby();
        return state!=null&&!state.members().isEmpty()&&state.members().stream().allMatch(LobbyState.Member::ready);
    }

    private static int memberCount(Peer peer) {
        LobbyState state=peer.remote.lobby();
        return state==null?0:state.members().size();
    }

    private static Peer connect(int port,String name) throws Exception {
        GameClient client=new GameClient();
        client.connect("127.0.0.1",port,name,"",2000);
        return new Peer(client,new RemoteGameModel(client,0,name));
    }

    private static void await(BooleanSupplier condition,Peer... peers) throws Exception {
        long deadline=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while(System.nanoTime()<deadline) {
            for(Peer peer:peers) peer.remote.pump();
            if(condition.getAsBoolean()) return;
            Thread.sleep(5);
        }
        fail("Timed out waiting for network state");
    }

    private record Peer(GameClient client,RemoteGameModel remote) implements AutoCloseable {
        @Override public void close(){client.close();}
    }
}
