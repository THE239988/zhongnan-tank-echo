package tanktrouble.net;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import tanktrouble.model.data.GameData.GameEvent;
import tanktrouble.net.Protocol.Frame;
import tanktrouble.net.Protocol.ServerMessage;

/** Coalescing rules shared by client input queues and server connection output queues. */
final class NetworkQueues {
    private static final int MAX_COALESCED_EVENTS=1024;

    private NetworkQueues() {}

    /** Drops stale world frames while preserving control messages and one-shot frame events. */
    static void enqueue(BlockingQueue<ServerMessage> queue,ServerMessage message) {
        synchronized(queue) {
            if(queue.offer(message)) return;
            List<ServerMessage> buffered=new ArrayList<>();
            queue.drainTo(buffered);
            int capacity=buffered.size();
            if(message instanceof Frame newest) {
                List<GameEvent> events=new ArrayList<>();
                buffered.removeIf(existing->{
                    if(existing instanceof Frame frame) {
                        events.addAll(frame.events());
                        return true;
                    }
                    return false;
                });
                events.addAll(newest.events());
                trimEvents(events);
                if(buffered.size()<capacity) buffered.add(new Frame(newest.snapshot(),events));
            } else {
                List<Integer> frames=new ArrayList<>();
                for(int i=0;i<buffered.size();i++)
                    if(buffered.get(i) instanceof Frame) frames.add(i);
                if(frames.size()>1) {
                    int oldIndex=frames.get(0),nextIndex=frames.get(1);
                    Frame removed=(Frame)buffered.get(oldIndex),next=(Frame)buffered.get(nextIndex);
                    List<GameEvent> events=new ArrayList<>(removed.events());
                    events.addAll(next.events());
                    trimEvents(events);
                    buffered.set(nextIndex,new Frame(next.snapshot(),events));
                    buffered.remove(oldIndex);
                } else {
                    int staleControl=-1;
                    for(int i=0;i<buffered.size();i++)
                        if(!(buffered.get(i) instanceof Frame)) {staleControl=i;break;}
                    if(staleControl>=0) buffered.remove(staleControl);
                    else if(!buffered.isEmpty()) buffered.remove(0);
                }
                buffered.add(message);
            }
            queue.addAll(buffered);
        }
    }

    private static void trimEvents(List<GameEvent> events) {
        if(events.size()>MAX_COALESCED_EVENTS)
            events.subList(0,events.size()-MAX_COALESCED_EVENTS).clear();
    }
}
