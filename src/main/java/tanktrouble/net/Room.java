package tanktrouble.net;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import tanktrouble.model.core.TankGameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;

import static tanktrouble.net.Protocol.*;

/**
 * One game room: its members, its lobby, and the single authoritative simulation for a match.
 *
 * <p>Extracted from what used to be the whole server. The simulation loop, the broadcast schedule
 * and the input-ownership checks are carried over unchanged - they were verified against real
 * matches and there is no reason to rewrite them. What is new is that a room no longer owns its
 * sockets: connections belong to the server and outlive any room, so a player can leave one room
 * and join another without reconnecting.
 *
 * <p>Threading is unchanged in shape. One reader thread per client may call {@link #receive} at any
 * time, and one broadcast thread runs the simulation. Room state is guarded by {@link #lock}; the
 * simulation itself is only ever touched by the broadcast thread.
 */
final class Room implements AutoCloseable {
    /** Identifies a room to clients; unique for the lifetime of the server. */
    private final int number;

    private final String name;
    private final int capacity;
    private final BattleType battleType;
    private final TankGameModel model=new TankGameModel();
    private final List<Member> members=new CopyOnWriteArrayList<>();
    private final Object lock=new Object();
    private final AtomicBoolean running=new AtomicBoolean();
    private final Map<Integer,String> names=new HashMap<>();
    private final Set<Integer> ready=new HashSet<>();
    private final Map<Integer,Long> chatCooldown=new HashMap<>();
    private final Set<Integer> usedPlayers=new HashSet<>();
    /** Told when the last member leaves, so the server can drop this room. */
    private final Runnable onEmpty;

    private Thread loopThread;
    private Phase phase=Phase.LOBBY;
    private volatile int hostPlayer=-1;

    /** A player inside this room, paired with the connection carrying them. */
    private static final class Member {
        final int player;
        final String name;
        final ClientConnection connection;
        Member(int player,String name,ClientConnection connection) {
            this.player=player;
            this.name=name;
            this.connection=connection;
        }
    }

    Room(int id,String name,int capacity,BattleType battleType,Runnable onEmpty) {
        this.number=id;
        this.name=name==null||name.isBlank()?"房间 "+id:name.trim();
        this.capacity=Math.max(2,Math.min(capacity,TankGameModel.MAX_PLAYERS));
        this.battleType=battleType==null?BattleType.FREE_FOR_ALL:battleType;
        this.onEmpty=onEmpty;
        // The server owns the mode: a network battle is player-versus-player with no AI. This also
        // moves the model out of its HOME state, which startBattle requires - without it every
        // start request is silently refused.
        model.selectMode(this.battleType==BattleType.COOP_BOSS?Mode.COOP:Mode.MULTI);
    }

    /** Identifier shown to clients and used to route them back into this room. */
    int id() {return number;}

    String name() {return name;}

    int playerCount() {return members.size();}

    int capacity() {return capacity;}

    boolean isJoinable() {return phase==Phase.LOBBY&&members.size()<capacity;}

    Phase phase() {synchronized(lock) {return phase;}}

    void start() {
        if(!running.compareAndSet(false,true)) return;
        loopThread=new Thread(this::broadcastLoop,"tank-room-"+number);
        loopThread.setDaemon(true);
        loopThread.start();
    }

    // ------------------------------------------------------------------ membership

    /**
     * Adds a player.
     *
     * @return the assigned player index, or -1 when the room cannot take them
     */
    int join(String playerName,ClientConnection connection) {
        synchronized(lock) {
            if(!running.get()||phase!=Phase.LOBBY||members.size()>=capacity) return -1;
            int player=freePlayer();
            if(player<0) return -1;
            usedPlayers.add(player);
            if(members.isEmpty()) hostPlayer=player;
            String resolved=playerName==null||playerName.isBlank()?"玩家 "+(player+1):playerName.trim();
            names.put(player,resolved);
            members.add(new Member(player,resolved,connection));
            // The room deliberately does not hook the connection's lifecycle: the server owns it,
            // and a room that also claimed it would fight whatever room the player joined next.
            return player;
        }
    }

    /** Announced by the server after RoomJoined has been sent, so the client knows its own index. */
    void memberJoined(int player) {
        String playerName=names.getOrDefault(player,"P"+(player+1));
        system(playerName+" 加入了房间");
        broadcastLobby();
    }

    private int freePlayer() {
        for(int player=0;player<TankGameModel.MAX_PLAYERS;player++)
            if(!usedPlayers.contains(player)) return player;
        return -1;
    }

    private void leave(int player) {
        String leftName=null;
        synchronized(lock) {
            Member member=find(player);
            if(member==null) return;
            members.remove(member);
            usedPlayers.remove(player);
            leftName=names.remove(player);
            ready.remove(player);
            chatCooldown.remove(player);
            model.disconnectPlayer(player);
            if(player==hostPlayer) hostPlayer=members.stream().mapToInt(member_ -> member_.player).min().orElse(-1);
            // A room mid-match whose players have all gone is no longer a room.
            if(phase==Phase.PLAYING&&members.isEmpty()) phase=Phase.LOBBY;
        }
        if(leftName!=null) system(leftName+" 离开了房间");
        broadcastLobby();
        if(members.isEmpty()) onEmpty.run();
    }

    private Member find(int player) {
        for(Member member:members) if(member.player==player) return member;
        return null;
    }

    /** The player index a connection is using, or -1 if it is not in this room. */
    int playerOf(ClientConnection connection) {
        for(Member member:members) if(member.connection==connection) return member.player;
        return -1;
    }

    /**
     * Removes a connection from this room without closing it.
     *
     * <p>Called when a player returns to the lobby or moves to another room. The connection itself
     * stays open - that is the point of separating it from the room - so this must not be a
     * disconnect.
     */
    void connectionLeft(ClientConnection connection) {
        int player=playerOf(connection);
        if(player>=0) leave(player);
    }

    /** True when this connection owns the current host slot. */
    boolean isHost(ClientConnection connection) {
        int player=playerOf(connection);
        return player>=0&&player==hostPlayer;
    }

    /**
     * Removes every member because the host left. The server owns connection routing, so it
     * receives the displaced sockets and returns them to the lobby after this room is gone.
     */
    List<ClientConnection> disband() {
        synchronized(lock) {
            List<ClientConnection> displaced=members.stream().map(member -> member.connection).toList();
            members.clear();
            names.clear();
            ready.clear();
            chatCooldown.clear();
            usedPlayers.clear();
            hostPlayer=-1;
            phase=Phase.LOBBY;
            return displaced;
        }
    }

    // ------------------------------------------------------------------ messages

    /**
     * Handles a message from a member.
     *
     * <p>Runs on that member's reader thread, so everything it touches is guarded.
     */
    /**
     * Handles a message, with a callback for the one case the room cannot handle alone.
     *
     * @param onLeaving invoked with the sender when they ask to return to the lobby. Leaving is a
     *                  routing decision that belongs to the server; closing the connection here
     *                  instead would drop the client entirely rather than move them.
     */
    void receive(ClientConnection from,ClientMessage message,java.util.function.Consumer<ClientConnection> onLeaving) {
        int player=playerOf(from);
        if(player<0) return;
        // Explicitly LeaveRoom rather than Leave: the two are separate message types, and matching
        // the wrong one silently swallows the request, leaving the player stuck in a room they
        // asked to leave.
        if(message instanceof LeaveRoom) {
            onLeaving.accept(from);
            return;
        }

        if(message instanceof SelectTank select) {
            synchronized(lock) {model.selectTank(player,select.type());}
        } else if(message instanceof Ready state) {
            synchronized(lock) {
                if(state.ready()) ready.add(player); else ready.remove(player);
            }
            broadcastLobby();
        } else if(message instanceof StartRequest) {
            startIfReady(player);
        } else if(message instanceof Input input) {
            // Only the player that owns this connection may drive that tank.
            if(input.player()==player) synchronized(lock) {model.handleInput(player,input.action(),input.active());}
        } else if(message instanceof Kick kick) {
            kickPlayer(player,kick.target());
        } else if(message instanceof Say say) {
            if(say.player()==player) relayChat(player,say.text());
        }
    }

    // ------------------------------------------------------------------ moderation

    /**
     * Removes a player at the host's request.
     *
     * <p>Only player 0 may do this, and only for someone else: a host that could remove itself
     * would leave a room nobody can start.
     */
    private void kickPlayer(int requester,int targetPlayer) {
        if(requester!=hostPlayer||requester==targetPlayer) return;
        Member target;
        synchronized(lock) {target=find(targetPlayer);}
        if(target==null) return;
        System.out.println("[room "+number+"] player "+requester+" removed "+target.name);
        target.connection.close("你已被房主移出房间");
        announce("已将 "+target.name+" 移出房间",false);
    }

    private void system(String text) {
        Chat line=new Chat(-1,"",text);
        broadcast(line);
    }

    /**
     * Sends a message to every member, outside the room lock.
     *
     * <p>Transmitting while holding the lock is a trap: a write to a client that has stopped
     * reading blocks once the socket buffer fills, and the lock is then held for as long as that
     * client stays stuck. Every other thread that needs the room - a player leaving, the room list
     * being assembled, the next simulation tick - waits behind it. Building the message under the
     * lock and sending after is the whole fix.
     */
    private void broadcast(ServerMessage message) {
        List<Member> current=List.copyOf(members);
        for(Member member:current) member.connection.send(message);
    }

    /**
     * Tells everyone something transient that changes the room.
     *
     * <p>Only for news that concerns the whole room. A refusal aimed at one player must not come
     * through here: telling everyone "only the host may start" puts that message on the screens of
     * players who did nothing, where it reads as a malfunction.
     */
    private void announce(String text,boolean problem) {
        broadcast(new Notice(text,problem));
    }

    /** Tells one player something about their own action, such as why their start was refused. */
    private void tellPlayer(int player,String text,boolean problem) {
        Member member;
        synchronized(lock) {member=find(player);}
        if(member!=null) member.connection.send(new Notice(text,problem));
    }

    /**
     * Passes a chat line on to the room.
     *
     * <p>The text is flattened to a single line and length-capped before it goes out: it is drawn
     * into a fixed-height panel, so newlines and very long strings would either break the layout or
     * push everything else out of it.
     */
    private void relayChat(int player,String raw) {
        if(raw==null) return;
        long now=System.currentTimeMillis();
        synchronized(lock) {
            Long last=chatCooldown.get(player);
            if(last!=null&&now-last<CHAT_COOLDOWN_MILLIS) return;
            chatCooldown.put(player,now);
        }
        String text=raw.replaceAll("[\\r\\n\\t]+"," ").strip();
        if(text.isEmpty()) return;
        if(text.length()>MAX_CHAT_LENGTH) text=text.substring(0,MAX_CHAT_LENGTH)+"…";

        String who;
        synchronized(lock) {who=names.getOrDefault(player,"P"+(player+1));}
        broadcast(new Chat(player,who,text));
    }

    // ------------------------------------------------------------------ lobby

    /**
     * Starts the match if the room is in a fit state, and says why when it is not.
     *
     * <p>The refusals are reported rather than silent. A refused start with no feedback looks
     * exactly like a hung game, which is how it was first reported.
     */
    private void startIfReady(int requester) {
        int playerCount;
        synchronized(lock) {
            if(phase==Phase.FINISHED) {
                if(requester!=hostPlayer) {tellPlayer(requester,"只有房主可以重新开始对局",true);return;}
                if(members.size()<2) {tellPlayer(requester,"至少需要 2 名玩家才能重新开始",true);return;}
                model.restart();
                model.startBattle(System.nanoTime(),playerSlots());
                playerCount=members.size();
                phase=Phase.PLAYING;
            } else {
            if(phase!=Phase.LOBBY) {tellPlayer(requester,"对局已经在进行中",true);return;}
            if(requester!=hostPlayer) {tellPlayer(requester,"只有房主可以开始对局",true);return;}
            if(members.size()<2) {tellPlayer(requester,"至少需要 2 名玩家才能开始",true);return;}
            List<String> waiting=new ArrayList<>();
            for(Member member:members) if(!ready.contains(member.player)) waiting.add(member.name);
            if(!waiting.isEmpty()) {
                tellPlayer(requester,"无法开始：" + String.join("、",waiting) + " 尚未准备",true);
                return;
            }
            // Prepare the model before exposing PLAYING to the broadcast thread. Otherwise the
            // broadcaster can snapshot the tank list while startBattle is rebuilding it.
            model.startBattle(System.nanoTime(),playerSlots());
            playerCount=members.size();
            ready.clear();
            phase=Phase.PLAYING;
            }
        }
        broadcastLobby();
        System.out.println("[room "+number+"] battle started with "+playerCount+" players");
    }

    private List<Integer> playerSlots() {
        return members.stream().map(member->member.player).sorted().toList();
    }

    /** What the server advertises about this room in the room list. */
    RoomInfo info() {
        synchronized(lock) {
            return new RoomInfo(number,name,members.size(),capacity,phase,battleType);
        }
    }

    /**
     * Rebuilds and sends the roster.
     *
     * <p>The roster is built under the lock so concurrent joins cannot interleave its fields, then
     * sent after releasing it. Sending while locked is avoided because a slow client would stall
     * room membership and simulation work.
     */
    private void broadcastLobby() {
        LobbyState state;
        synchronized(lock) {
            List<LobbyState.Member> list=new ArrayList<>();
            for(Member member:members)
                list.add(new LobbyState.Member(member.player,names.getOrDefault(member.player,"P"+(member.player+1)),
                        model.selectedTankType(member.player),ready.contains(member.player)));
            list.sort(Comparator.comparingInt(LobbyState.Member::player));
            String status=switch(phase) {
                case LOBBY -> list.size()<2?"等待玩家加入（至少 2 人）":"等待所有玩家准备";
                case PLAYING -> "对局进行中";
                case FINISHED -> "对局结束";
            };
            state=new LobbyState(phase,hostPlayer,List.copyOf(list),status);
        }
        // Sent after the lock is released: see broadcast() for why that matters.
        broadcast(state);
    }

    // ------------------------------------------------------------------ simulation

    private void broadcastLoop() {
        final long stepNanos=Rules.STEP_MS*1_000_000L;
        final long broadcastNanos=1_000_000_000L/BROADCAST_HZ;
        long previous=System.nanoTime();
        long nextBroadcast=previous;
        long accumulated=0;

        while(running.get()) {
            long now=System.nanoTime();
            long delta=Math.min(Math.max(0,now-previous),50_000_000L);
            previous=now;
            if(phase()==Phase.PLAYING) {
                accumulated=Math.min(accumulated+delta,50_000_000L);
                boolean finished=false;
                synchronized(lock) {
                    while(accumulated>=stepNanos) {
                        model.tick(Rules.STEP_MS);
                        accumulated-=stepNanos;
                    }
                    if(model.snapshot().state()==State.RESULT&&phase==Phase.PLAYING) {
                        phase=Phase.FINISHED;finished=true;
                    }
                }
                if(finished) broadcastLobby();
            } else {
                accumulated=0;
            }
            if(now>=nextBroadcast) {
                nextBroadcast=now+broadcastNanos;
                broadcastFrame();
            }
            long after=System.nanoTime();
            long untilBroadcast=Math.max(0,nextBroadcast-after);
            long untilTick=phase()==Phase.PLAYING?Math.max(0,stepNanos-accumulated):untilBroadcast;
            long sleepNanos=Math.min(untilTick,untilBroadcast);
            if(sleepNanos>0) try {
                Thread.sleep(sleepNanos/1_000_000L,(int)(sleepNanos%1_000_000L));
            } catch(InterruptedException stop) {return;}
        }
    }

    boolean closeIfEmpty() {
        synchronized(lock) {
            if(!members.isEmpty()) return false;
            running.set(false);
            return true;
        }
    }

    private void broadcastFrame() {
        Frame frame;
        synchronized(lock) {
            if(members.isEmpty()) return;
            // Only once a battle exists. Before it starts the model still reports its pre-battle
            // state, and shipping those frames would tell clients a match had begun when it has
            // not; after it ends the state stays out of LOBBY so the result keeps being sent.
            if(phase==Phase.LOBBY) return;
            // Draining here means events are delivered exactly once even though the broadcast runs
            // at a lower rate than the simulation that produced them.
            frame=new Frame(model.snapshot(),model.drainEvents());
        }
        broadcast(frame);
    }

    @Override public void close() {
        running.set(false);
        if(loopThread!=null) loopThread.interrupt();
    }
}
