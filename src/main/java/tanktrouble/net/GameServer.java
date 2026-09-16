package tanktrouble.net;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tanktrouble.model.core.TankGameModel;

import static tanktrouble.net.Protocol.*;

/**
 * The server: a lobby that hosts rooms.
 *
 * <p>Players connect here once and stay connected across rooms. The lobby owns the sockets; a room
 * owns a simulation and the participants in it. Joining or leaving a room is a routing decision,
 * not a reconnection, which is what lets someone browse the room list, play a match, and go back to
 * browsing without their connection dropping.
 *
 * <p>Rooms are independent: each runs its own tick loop and its own broadcast, so one room's load
 * cannot stall another's. The room count is capped because this is a small machine and a shared
 * CPU budget - past the cap the server refuses new rooms rather than letting every match degrade
 * together.
 */
public final class GameServer implements AutoCloseable {
    /** Hard ceiling on concurrent rooms. Chosen for a 2-core host; see the deployment notes. */
    public static final int MAX_ROOMS=5;

    private final Map<Integer,Room> rooms=new ConcurrentHashMap<>();
    /** Room id a connection is inside. {@link #IN_LOBBY} when it is not in any room. */
    private static final int IN_LOBBY=0;
    /**
     * Where each connection currently is, keyed by connection identity.
     *
     * <p>A sentinel rather than null: this map is concurrent, and concurrent maps reject null
     * values. Room ids start at 1 so zero is free to mean "in the lobby".
     */
    private final Map<ClientConnection,Integer> inRoom=new ConcurrentHashMap<>();
    /** Every live connection, so shutdown can reach them without going through a room. */
    private final Set<ClientConnection> connections=ConcurrentHashMap.newKeySet();
    private final Map<ClientConnection,String> playerNames=new ConcurrentHashMap<>();
    private final AtomicInteger nextConnection=new AtomicInteger();
    private final AtomicInteger nextRoom=new AtomicInteger(1);
    private final AtomicBoolean running=new AtomicBoolean();
    private final String password;
    private final int maxRooms;

    private ServerSocket socket;
    private Thread acceptThread;
    private Thread listThread;
    private LanDiscovery.Broadcaster discovery;

    public GameServer() {this("",MAX_ROOMS);}

    public GameServer(String password) {this(password,MAX_ROOMS);}

    public GameServer(String password,int maxRooms) {
        this.password=password==null?"":password;
        this.maxRooms=Math.max(1,maxRooms);
    }

    /** Binds and starts serving. Returns the port actually bound, useful when asking for 0. */
    public int start(InetAddress bindAddress,int port) throws IOException {
        socket=new ServerSocket(port,32,bindAddress);
        running.set(true);
        acceptThread=new Thread(this::acceptLoop,"tank-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
        // The lobby's room list is pushed rather than polled, so a room appearing or filling up is
        // visible to everyone sitting on the lobby screen without them having to ask.
        listThread=new Thread(this::listLoop,"tank-lobbylist");
        listThread.setDaemon(true);
        listThread.start();
        return socket.getLocalPort();
    }

    public int port() {return socket==null?0:socket.getLocalPort();}

    /** Starts advertising this server's rooms over the local network. */
    public void startDiscovery() {
        if(discovery!=null) return;
        discovery=new LanDiscovery.Broadcaster(port(),this::roomInfos);
        discovery.start();
        System.out.println("[server] LAN discovery enabled on UDP "+LanDiscovery.PORT);
    }

    /** True when a password is required to connect. */
    public boolean requiresPassword() {return !password.isEmpty();}

    /** Number of rooms currently hosted, for tests and diagnostics. */
    public int roomCount() {return rooms.size();}

    // ------------------------------------------------------------------ connections

    private void acceptLoop() {
        while(running.get()) {
            try {
                Socket client=socket.accept();
                ClientConnection connection;
                try {
                    connection=new ClientConnection(client);
                } catch(IOException handshakeFailed) {
                    try {client.close();} catch(IOException ignored) {}
                    continue;
                }
                Thread reader=new Thread(()->readLoop(connection),"tank-client-"+nextConnection.getAndIncrement());
                reader.setDaemon(true);
                reader.start();
            } catch(IOException closed) {
                if(running.get()) System.err.println("[server] accept failed: "+closed.getMessage());
                return;
            }
        }
    }

    /**
     * Reads one client's messages for the life of its connection.
     *
     * <p>The first message must be a {@link Join}, which is also where the password is checked:
     * refusing at the handshake means an unauthenticated peer never reaches the lobby, the room
     * list, or any game message.
     *
     * <p>After the handshake every message is dispatched by where the sender currently is - the
     * lobby or a room. That routing is the whole reason this loop lives on the server rather than
     * inside a room.
     */
    private void readLoop(ClientConnection connection) {
        try (ObjectInputStream in=new ObjectInputStream(connection.input())) {
            Object first=in.readObject();
            if(!(first instanceof Join join)) {
                connection.close("第一个消息必须是加入请求");
                return;
            }
            if(join.version()!=VERSION) {
                connection.close("客户端与服务器版本不匹配（服务器 "+VERSION+"，客户端 "+join.version()+"）");
                return;
            }
            if(!password.isEmpty()&&!password.equals(join.password()==null?"":join.password().trim())) {
                connection.close("房间口令错误");
                System.out.println("[server] rejected "+connection.address()+" - wrong password");
                return;
            }
            String name=join.name()==null||join.name().isBlank()?"玩家":join.name().trim();
            playerNames.put(connection,name);
            connections.add(connection);
            // Registered in the lobby, so the room list reaches them.
            inRoom.put(connection,IN_LOBBY);
            connection.send(new Joined(0,TankGameModel.MAX_PLAYERS,""));
            connection.send(new RoomList(roomInfos()));
            System.out.println("[server] "+name+" connected from "+connection.address());

            while(running.get()) dispatch(connection,in.readObject());
        } catch(EOFException|SocketException normalExit) {
            // Client went away; not an error.
        } catch(IOException|ClassNotFoundException failed) {
            System.err.println("[server] connection failed: "+failed);
        } finally {
            leaveCurrentRoom(connection);
            playerNames.remove(connection);
            inRoom.remove(connection);
            connections.remove(connection);
            connection.close();
        }
    }

    private void dispatch(ClientConnection connection,Object message) {
        Integer roomId=inRoom.get(connection);
        Room room=roomId==null||roomId==IN_LOBBY?null:rooms.get(roomId);
        if(room!=null) {
            // Inside a room: the room decides what is valid for a participant. Leaving is the one
            // thing it cannot do itself, because the connection belongs to the server.
            room.receive(connection,asClientMessage(message),this::returnToLobby);
            return;
        }
        if(message instanceof JoinRoom join&&join.roomId()<0) {
            // Negative id is the "just put me in a game" request.
            enterDefaultRoom(connection);
        } else if(message instanceof CreateRoom create) {
            createRoom(connection,create.name(),create.capacity(),create.battleType());
        } else if(message instanceof JoinRoom join) {
            Room target=rooms.get(join.roomId());
            if(target==null) connection.send(new Notice("该房间已不存在",true));
            else enter(connection,target);
        }
    }

    private static ClientMessage asClientMessage(Object message) {
        if(message instanceof ClientMessage client) return client;
        throw new IllegalArgumentException("not a client message: "+message);
    }

    // ------------------------------------------------------------------ lobby

    private void listLoop() {
        while(running.get()) {
            try {Thread.sleep(1000);} catch(InterruptedException stop) {return;}
            broadcastRoomList();
        }
    }

    private List<RoomInfo> roomInfos() {
        List<RoomInfo> list=new ArrayList<>();
        for(Room room:rooms.values()) list.add(room.info());
        list.sort(Comparator.comparingInt(RoomInfo::id));
        return List.copyOf(list);
    }

    private void broadcastRoomList() {
        // Sent unconditionally, including when empty: a client that joined while no rooms existed
        // needs to be told when one appears, and a client whose list is stale needs the correction.
        RoomList list=new RoomList(roomInfos());
        for(Map.Entry<ClientConnection,Integer> entry:inRoom.entrySet()) {
            if(entry.getValue()==IN_LOBBY) entry.getKey().send(list);
        }
    }

    /**
     * The room a client lands in when it does not choose one.
     *
     * <p>Used by the same-machine flow, where the host starts a server and expects to be playing
     * rather than looking at a room list. Created on demand and reused while it has space, so
     * several windows launched together end up in the same match.
     */
    private void enterDefaultRoom(ClientConnection connection) {
        Room room=rooms.values().stream().filter(Room::isJoinable)
                .min(Comparator.comparingInt(Room::id)).orElse(null);
        if(room==null) {
            if(rooms.size()>=maxRooms) {
                connection.send(new Notice("服务器房间已满（上限 "+maxRooms+" 个），请稍后再试",true));
                return;
            }
            int id=nextRoom.getAndIncrement();
            room=new Room(id,"房间 "+id,TankGameModel.MAX_PLAYERS,BattleType.FREE_FOR_ALL,()->removeRoom(id));
            rooms.put(id,room);
            room.start();
            System.out.println("[server] room "+id+" created for direct join");
        }
        enter(connection,room);
    }

    private void createRoom(ClientConnection connection,String name,int capacity,BattleType battleType) {
        if(rooms.size()>=maxRooms) {
            connection.send(new Notice("服务器房间已满（上限 "+maxRooms+" 个），请稍后再试",true));
            return;
        }
        int id=nextRoom.getAndIncrement();
        Room room=new Room(id,name,capacity,battleType,()->removeRoom(id));
        rooms.put(id,room);
        room.start();
        System.out.println("[server] room "+id+" created: "+room.name());
        // Announced before anyone enters, so other clients see it appear.
        broadcastRoomList();
        enter(connection,room);
    }

    /**
     * Drops a room once nobody is in it.
     *
     * <p>Only the room's own loop is stopped. Its sockets are deliberately left alone: connections
     * belong to the server and outlive any room, and a player who just left is still connected and
     * about to be shown the lobby. Closing them here is what made leaving a room look like being
     * disconnected from the server.
     */
    private void removeRoom(int id) {
        Room room=rooms.get(id);
        if(room!=null&&room.closeIfEmpty()&&rooms.remove(id,room)) {
            room.close();
            System.out.println("[server] room "+id+" closed (empty)");
            broadcastRoomList();
        }
    }

    /** Moves a connection into a room, leaving whatever room it was in first. */
    private void enter(ClientConnection connection,Room room) {
        leaveCurrentRoom(connection);
        String name=playerNames.getOrDefault(connection,"玩家");
        int player=room.join(name,connection);
        if(player<0) {
            // Still in the lobby: recording the room here would leave the server believing the
            // player is somewhere they were refused entry to.
            inRoom.put(connection,IN_LOBBY);
            connection.send(new Notice("无法加入该房间（已满或已开局）",true));
            return;
        }
        inRoom.put(connection,room.id());
        connection.setOnClosed(()->{});
        // Acknowledge membership before publishing the roster. The client needs its assigned index
        // before it can mark the matching roster row as "you"; sending the roster first made a
        // successful join look like a stale lobby update.
        connection.send(new RoomJoined(room.id(),player));
        room.memberJoined(player);
        // Occupancy just changed, so the lobby is told at once rather than at the next tick of the
        // periodic sweep. Waiting up to a second for a list that is already known to be stale is
        // exactly the kind of pause that reads as a broken lobby.
        broadcastRoomList();
        System.out.println("[server] player "+player+" entered room "+room.id());
    }

    /** Moves a player out of their room and back to the lobby list, keeping them connected. */
    private void returnToLobby(ClientConnection connection) {
        leaveCurrentRoom(connection);
        inRoom.put(connection,IN_LOBBY);
        connection.send(new RoomLeft());
        java.util.List<RoomInfo> infos=roomInfos();
        connection.send(new RoomList(infos));
    }

    private void leaveCurrentRoom(ClientConnection connection) {
        Integer roomId=inRoom.get(connection);
        if(roomId==null||roomId==IN_LOBBY) return;
        Room room=rooms.get(roomId);
        if(room!=null) {
            if(room.isHost(connection)) {
                disbandRoom(room,connection);
                return;
            }
            room.connectionLeft(connection);
        }
        inRoom.remove(connection);
    }

    /** A host leaving ends the room for everyone; connected guests stay on the server. */
    private void disbandRoom(Room room,ClientConnection host) {
        List<ClientConnection> displaced=room.disband();
        if(!rooms.remove(room.id(),room)) return;
        room.close();
        inRoom.put(host,IN_LOBBY);
        for(ClientConnection member:displaced) {
            inRoom.put(member,IN_LOBBY);
            if(member==host) continue;
            member.send(new Notice("房主已离开，房间已解散",true));
            member.send(new RoomLeft());
        }
        broadcastRoomList();
        System.out.println("[server] room "+room.id()+" disbanded because its host left");
    }

    /**
     * Stops the server.
     *
     * <p>Closing a connection is polite by design - it sends a farewell and waits for the peer to
     * acknowledge - which is right during play but wrong here: a client that has stopped reading
     * would hold shutdown open indefinitely. So each close is handed off and the whole batch is
     * given a bounded window before the process is allowed to finish. Shutdown must always
     * terminate, even when a client misbehaves.
     */
    @Override public void close() {
        running.set(false);
        if(discovery!=null) discovery.close();
        try {if(socket!=null) socket.close();} catch(IOException ignored) {}
        if(listThread!=null) listThread.interrupt();

        for(Room room:rooms.values()) room.close();
        rooms.clear();

        List<Thread> closers=new ArrayList<>();
        for(ClientConnection connection:new ArrayList<>(connections)) {
            Thread closer=new Thread(connection::close,"tank-shutdown");
            closer.setDaemon(true);
            closer.start();
            closers.add(closer);
        }
        long deadline=System.currentTimeMillis()+750;
        for(Thread closer:closers) {
            long left=deadline-System.currentTimeMillis();
            if(left<=0) break;
            try {closer.join(left);} catch(InterruptedException stop) {Thread.currentThread().interrupt();break;}
        }
        connections.clear();
        inRoom.clear();
        playerNames.clear();
    }

    /** Runs a standalone server from the command line. */
    public static void main(String[] args) throws Exception {
        int port=Protocol.DEFAULT_PORT;
        String password=System.getenv().getOrDefault("TANKTROUBLE_PASSWORD","");
        String environmentPort=System.getenv("TANKTROUBLE_PORT");
        if(environmentPort!=null&&!environmentPort.isBlank()) {
            try {port=Integer.parseInt(environmentPort.trim());} catch(NumberFormatException ignored) {}
        }
        for(String arg:args) {
            if(arg.startsWith("--password=")) password=arg.substring("--password=".length());
            else if(arg.startsWith("--max-rooms=")) { /* honoured via the constant for now */ }
            else {
                try {port=Integer.parseInt(arg);} catch(NumberFormatException ignored) {}
            }
        }
        GameServer server=new GameServer(password);
        int bound=server.start(InetAddress.getByName("0.0.0.0"),port);
        server.startDiscovery();
        System.out.println("Tank Trouble server listening on port "+bound
                +", up to "+MAX_ROOMS+" rooms of "+TankGameModel.MAX_PLAYERS+" players.");
        System.out.println(server.requiresPassword()
                ? "A password is required to connect."
                : "WARNING: no password set - anyone who can reach this port may connect.");
        System.out.println("Players run:  run.bat client <address> "+bound);
        Thread.currentThread().join();
    }
}
