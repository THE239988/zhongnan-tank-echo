package tanktrouble.net;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;

import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.net.Protocol.*;

class GameClientQueueTest {
    @Test void newerFramesReplaceOldSnapshotsWithoutDroppingTheirEventsOrControlMessages() {
        var queue=new ArrayBlockingQueue<ServerMessage>(2);
        GameEvent explosion=GameEvent.of(GameEventType.EXPLOSION,false);
        GameClient.enqueue(queue,new RoomLeft());
        GameClient.enqueue(queue,new Frame(snapshot(1),List.of(explosion)));

        GameClient.enqueue(queue,new Frame(snapshot(2),List.of()));

        assertTrue(queue.stream().anyMatch(RoomLeft.class::isInstance));
        Frame frame=queue.stream().filter(Frame.class::isInstance).map(Frame.class::cast).findFirst().orElseThrow();
        assertEquals(2,frame.snapshot().seed());
        assertEquals(List.of(explosion),frame.events());
    }

    @Test void incomingControlMessageDoesNotEvictTheOnlyFrameWithPendingEvents() {
        var queue=new ArrayBlockingQueue<ServerMessage>(2);
        GameEvent explosion=GameEvent.of(GameEventType.EXPLOSION,false);
        GameClient.enqueue(queue,new RoomList(List.of()));
        GameClient.enqueue(queue,new Frame(snapshot(1),List.of(explosion)));

        GameClient.enqueue(queue,new RoomLeft());

        assertTrue(queue.stream().anyMatch(RoomLeft.class::isInstance));
        Frame frame=queue.stream().filter(Frame.class::isInstance).map(Frame.class::cast).findFirst().orElseThrow();
        assertEquals(List.of(explosion),frame.events());
    }

    @Test void coalescedFrameEventsStayBoundedDuringLongUiStalls() {
        var queue=new ArrayBlockingQueue<ServerMessage>(1);
        List<GameEvent> events=java.util.stream.IntStream.range(0,1500)
                .mapToObj(i->GameEvent.of(GameEventType.BOUNCE,true)).toList();
        GameClient.enqueue(queue,new Frame(snapshot(1),events));

        GameClient.enqueue(queue,new Frame(snapshot(2),List.of()));

        Frame frame=(Frame)queue.remove();
        assertEquals(2,frame.snapshot().seed());
        assertTrue(frame.events().size()<=1024);
    }

    private static Snapshot snapshot(long seed) {
        return new Snapshot(State.RUNNING,Mode.MULTI,Result.NONE,1,0,0,0,0,0,seed,0,
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
}
