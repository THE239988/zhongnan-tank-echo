package tanktrouble.net;

import java.net.InetAddress;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LanDiscoveryTest {
    @Test void aRunningServerBroadcastsADiscoverableBeacon() throws Exception {
        GameServer server=new GameServer("",1);
        int port=server.start(InetAddress.getByName("127.0.0.1"),0);
        GameClient host=new GameClient();
        host.connect("127.0.0.1",port,"主机","",4000);
        host.poll();
        host.send(new Protocol.CreateRoom("局域网测试房",5));
        Thread.sleep(300);
        server.startDiscovery();
        try(LanDiscovery.Listener listener=new LanDiscovery.Listener()) {
            listener.start();
            long deadline=System.currentTimeMillis()+5000;
            LanDiscovery.Found found=null;
            while(System.currentTimeMillis()<deadline&&found==null) {
                found=listener.rooms().stream().filter(room->room.port()==port).findFirst().orElse(null);
                if(found==null) Thread.sleep(100);
            }
            assertNotNull(found,"the server beacon did not reach a local listener");
            assertEquals("局域网测试房",found.name());
            assertTrue(found.joinable());
            GameClient guest=new GameClient();
            try {
                guest.connect("127.0.0.1",found.port(),"加入者","",4000);
                guest.poll();
                guest.send(new Protocol.JoinRoom(found.roomId()));
                Protocol.RoomJoined joined=null;
                for(int i=0;i<40&&joined==null;i++) {
                    Object message=guest.poll();
                    if(message instanceof Protocol.RoomJoined room) joined=room;
                    else if(message==null) Thread.sleep(50);
                }
                assertNotNull(joined,"the discovered room could not be joined directly");
                assertEquals(found.roomId(),joined.roomId());
            } finally {
                guest.close();
            }
        } finally {
            host.close();
            server.close();
        }
    }
}
