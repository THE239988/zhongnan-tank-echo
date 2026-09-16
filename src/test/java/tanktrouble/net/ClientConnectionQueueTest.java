package tanktrouble.net;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;

import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.net.Protocol.*;

class ClientConnectionQueueTest {
    @Test void aStalledPeerCannotBlockTheRoomBroadcastThread() throws Exception {
        try(ServerSocket listener=new ServerSocket(0,1,InetAddress.getLoopbackAddress())) {
            try(Socket client=new Socket()) {
                client.connect(listener.getLocalSocketAddress(),1000);
                try(Socket serverSide=listener.accept()) {
                    ClientConnection connection=new ClientConnection(serverSide);
                    long started=System.nanoTime();
                    for(int i=0;i<10_000;i++)
                        connection.send(new Frame(snapshot(i),List.of()));
                    long elapsedMs=(System.nanoTime()-started)/1_000_000L;
                    assertTrue(elapsedMs<1500,"enqueueing 10,000 frames blocked for "+elapsedMs+"ms");
                    connection.close();
                }
            }
        }
    }

    private static Snapshot snapshot(long seed) {
        return new Snapshot(State.RUNNING,Mode.MULTI,Result.NONE,1,0,0,0,0,0,seed,0,
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
}
