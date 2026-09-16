package tanktrouble.net;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/**
 * Finds rooms on the local network over UDP broadcast, so nobody has to read an IP address aloud.
 *
 * <p>Discovery is a separate, optional layer. It uses its own port and its own protocol, and
 * nothing in the game depends on it: if broadcast is blocked - which it often is on guest and
 * corporate networks - the player simply types an address, exactly as before. That independence is
 * the reason it can be added without touching the connection path at all.
 *
 * <p>Deliberately unauthenticated, like the game itself. It advertises that a room exists and
 * where, and no more; the room list is a convenience, not a trust boundary.
 */
public final class LanDiscovery {
    /** Broadcast port. Distinct from the game port so a lost broadcast cannot disturb a match. */
    public static final int PORT = 7778;
    /** Multicast fallback for adapters and hotspots that suppress subnet broadcast. */
    private static final String MULTICAST_GROUP = "239.255.77.77";
    private static final String MAGIC = "TANKTROUBLE";
    private static final long STALE_MILLIS = 3000;

    private LanDiscovery() {}

    /** A room heard from the network. */
    public record Found(int roomId,String host,int port,String name,int players,int capacity,
                        Protocol.Phase phase,long heardAt) {
        public boolean joinable() {return phase==Protocol.Phase.LOBBY&&players<capacity;}
        public String label() {
            String status=switch(phase) {
                case LOBBY -> players<capacity?"等待中":"已满";
                case PLAYING -> "对局中";
                case FINISHED -> "已结束";
            };
            return name + "  ·  " + host + ":" + port + "  ·  "
                    + players + "/" + capacity + " 人  ·  " + status;
        }
    }

    // ------------------------------------------------------------------ announcing

    /** Periodically announces a room; stops when closed. */
    public static final class Broadcaster implements AutoCloseable {
        private final AtomicBoolean running=new AtomicBoolean();
        private final String serverId=UUID.randomUUID().toString();
        private final int gamePort;
        private final java.util.function.Supplier<List<Protocol.RoomInfo>> rooms;
        private Thread thread;

        public Broadcaster(int gamePort,java.util.function.Supplier<List<Protocol.RoomInfo>> rooms) {
            this.gamePort=gamePort;
            this.rooms=rooms;
        }

        public void start() {
            if(!running.compareAndSet(false,true)) return;
            thread=new Thread(this::loop,"tank-broadcast");
            thread.setDaemon(true);
            thread.start();
        }

        private void loop() {
            try (DatagramSocket socket=new DatagramSocket()) {
                socket.setBroadcast(true);
                List<InetAddress> targets=broadcastTargets();
                while(running.get()) {
                    // Every room gets its own beacon, so a joining client can enter it directly.
                    for(Protocol.RoomInfo room:rooms.get()) {
                        String safeName=room.name().replace("|"," ");
                        String payload=MAGIC+"/3|"+gamePort+"|"+serverId+"|"+room.id()+"|"
                                +safeName+"|"+room.players()+"|"+room.capacity()+"|"+room.phase().name();
                        byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
                        for(InetAddress target:targets) {
                            try {
                                socket.send(new DatagramPacket(bytes,bytes.length,target,PORT));
                            } catch(IOException transientFailure) {
                                // One interface failing must not stop the other network paths.
                            }
                        }
                    }
                    Thread.sleep(1000);
                }
            } catch(IOException|InterruptedException stopped) {
                // Socket closed or thread interrupted: the room is going away either way.
            }
        }

        private static List<InetAddress> broadcastTargets() {
            LinkedHashSet<InetAddress> targets=new LinkedHashSet<>();
            targets.add(InetAddress.getLoopbackAddress());
            try {
                targets.add(InetAddress.getByName("255.255.255.255"));
                targets.add(InetAddress.getByName(MULTICAST_GROUP));
            } catch(UnknownHostException impossible) {
                // Every IPv4 stack has the limited broadcast address.
            }
            try {
                for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if(!network.isUp()||network.isLoopback()) continue;
                    for(InterfaceAddress address:network.getInterfaceAddresses()) {
                        InetAddress broadcast=address.getBroadcast();
                        if(broadcast!=null) targets.add(broadcast);
                    }
                }
            } catch(SocketException ignored) {
                // The limited and loopback addresses still cover the common case.
            }
            return List.copyOf(targets);
        }

        @Override public void close() {
            running.set(false);
            if(thread!=null) thread.interrupt();
        }
    }

    // ------------------------------------------------------------------ listening

    /** Collects rooms heard from the network, dropping any that stop announcing themselves. */
    public static final class Listener implements AutoCloseable {
        private final Map<String,Found> found=new LinkedHashMap<>();
        private final AtomicBoolean running=new AtomicBoolean();
        private Thread thread;

        public void start() {
            if(!running.compareAndSet(false,true)) return;
            thread=new Thread(this::loop,"tank-discover");
            thread.setDaemon(true);
            thread.start();
        }

        private void loop() {
            try (MulticastSocket socket=new MulticastSocket(null)) {
                // Reuse lets several windows listen at once, which is the normal case on one machine.
                socket.setReuseAddress(true);
                socket.bind(new InetSocketAddress(PORT));
                socket.setSoTimeout(1000);
                socket.setLoopbackMode(false);
                joinMulticastGroups(socket);
                byte[] buffer=new byte[512];
                while(running.get()) {
                    DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
                    try {
                        socket.receive(packet);
                    } catch(SocketTimeoutException beat) {
                        continue; // also the moment to sweep out rooms that went quiet
                    }
                    accept(packet);
                }
            } catch(IOException failed) {
                // No discovery available; the player can still join by address.
            }
        }

        private static void joinMulticastGroups(MulticastSocket socket) {
            boolean joined=false;
            try {
                InetAddress group=InetAddress.getByName(MULTICAST_GROUP);
                for(NetworkInterface network:Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if(!network.isUp()||network.isLoopback()) continue;
                    try {
                        socket.joinGroup(new InetSocketAddress(group,PORT),network);
                        joined=true;
                    } catch(IOException unsupportedInterface) {
                        // Try the next interface; one usable address is enough.
                    }
                }
                if(!joined) socket.joinGroup(group);
            } catch(IOException ignored) {
                // Broadcast reception still works when multicast is unavailable.
            }
        }

        private void accept(DatagramPacket packet) {
            String text=new String(packet.getData(),packet.getOffset(),packet.getLength(),StandardCharsets.UTF_8);
            String[] parts=text.split("\\|");
            if(parts.length<8||!parts[0].equals(MAGIC+"/3")) return;
            try {
                int port=Integer.parseInt(parts[1]);
                String serverId=parts[2];
                int roomId=Integer.parseInt(parts[3]);
                String name=parts[4];
                int players=Integer.parseInt(parts[5]);
                int capacity=Integer.parseInt(parts[6]);
                Protocol.Phase phase=Protocol.Phase.valueOf(parts[7]);
                String host=packet.getAddress().getHostAddress();
                String key=serverId+":"+roomId;
                synchronized(found) {
                    found.put(key,new Found(roomId,host,port,name,players,capacity,phase,
                            System.currentTimeMillis()));
                }
            } catch(IllegalArgumentException malformed) {
                // Anything can send to this port; ignore what we cannot parse.
            }
        }

        /** Rooms heard from recently, newest first. */
        public List<Found> rooms() {
            long now=System.currentTimeMillis();
            synchronized(found) {
                found.values().removeIf(room -> now-room.heardAt()>STALE_MILLIS);
                List<Found> live=new ArrayList<>(found.values());
                live.sort(Comparator.comparing(Found::label));
                return List.copyOf(live);
            }
        }

        @Override public void close() {
            running.set(false);
            if(thread!=null) thread.interrupt();
        }
    }
}
