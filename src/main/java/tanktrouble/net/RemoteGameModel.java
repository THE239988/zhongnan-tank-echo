package tanktrouble.net;

import java.util.*;
import tanktrouble.model.api.GameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.net.Protocol.*;

/**
 * The {@link GameModel} implementation the UI runs against during a network battle.
 *
 * <p>This is the piece that makes networking cheap here: the view, the renderer and the audio layer
 * all talk to {@code GameModel} and know nothing about where a world comes from. Swapping the local
 * simulation for this class is the whole client-side change.
 *
 * <p>Everything is decided by the server. Local calls that would change the world - start, tick,
 * pause, purchase - are either forwarded as requests or recorded for display only; nothing here
 * simulates.
 */
public final class RemoteGameModel implements GameModel {
    private final GameClient client;
    /**
     * The index the server assigned this client.
     *
     * <p>Not final and not decided at construction: which tank a player owns is the server's
     * decision, and it arrives with RoomJoined. Until then this is a placeholder, which is why
     * nothing that acts on the world may run before a room is entered.
     */
    private volatile int localPlayer;
    private final String playerName;

    private volatile Snapshot latest;
    /**
     * Whether the most recent {@link #snapshot()} had to predict forward because no newer frame had
     * arrived yet. Read by the render loop, which reports it once per drawn frame - counting inside
     * {@code snapshot()} would double every frame, since the window asks for one twice.
     */
    private volatile boolean predicted;
    /** Recent server frames used to render smoothly between the 30Hz authoritative updates. */
    private final java.util.ArrayDeque<TimedSnapshot> frameHistory=new java.util.ArrayDeque<>();
    private static final long INTERPOLATION_DELAY_NANOS=45_000_000L;
    private static final int MAX_FRAME_HISTORY=12;
    private record TimedSnapshot(long receivedNanos,Snapshot snapshot) {}
    private volatile LobbyState lobby;
    /** Rooms the server is hosting, as last advertised. Empty until the first list arrives. */
    private volatile List<RoomInfo> rooms=List.of();
    /** Room this client is inside, or 0 when it is in the lobby. */
    private volatile int currentRoom;
    /** True between sending a room request and receiving its acknowledgement or refusal. */
    private volatile boolean joining;
    /**
     * Transient messages awaiting display.
     *
     * <p>A queue rather than a single slot: a kick arrives as a notice and a disconnect in quick
     * succession, and overwriting would show the generic message instead of the reason.
     */
    private final List<Notice> notices=new ArrayList<>();
    /**
     * Chat lines in arrival order.
     *
     * <p>Bounded: a long match would otherwise accumulate every line forever. Dropping the oldest
     * keeps the panel showing recent conversation, which is what a player actually reads.
     */
    private final java.util.Deque<Chat> chat=new java.util.ArrayDeque<>();
    private static final int MAX_CHAT_HISTORY=60;
    private final List<GameEvent> pending=new ArrayList<>();
    private final List<ClientMessage> heldInputs=new ArrayList<>();
    private Mode mode=Mode.MULTI;
    private final Map<Integer,TankType> chosen=new HashMap<>();

    public RemoteGameModel(GameClient client,int localPlayer,String playerName) {
        this.client=client;
        this.localPlayer=localPlayer;
        this.playerName=playerName;
    }

    /** The local player's index changes only when a room is entered. */
    void adoptPlayerIndex(int player) {localPlayer=player;}

    /** The index the server assigned this client, which is the tank it controls. */
    public int localPlayer() {return localPlayer;}

    /** True while this client is the current room host. */
    public boolean isHost() {return lobby!=null&&localPlayer==lobby.hostPlayer();}

    /** Whether the last drawn snapshot had to be predicted forward instead of blended. */
    public boolean lastSnapshotPredicted() {return predicted;}

    public String playerName() {return playerName;}

    /** Current room membership, or null before the first lobby update arrives. */
    public LobbyState lobby() {return lobby;}

    /** True once this client is in a room and the server has sent its first world frame. */
    public boolean hasSnapshot() {return currentRoom!=0&&latest!=null;}

    /** True while a create/join request is waiting for the server. */
    public boolean isJoining() {return joining;}

    public boolean isConnected() {return client.isConnected();}

    public String failure() {return client.failure();}

    /**
     * Applies everything the server has sent since the last call. Must run on the JavaFX thread.
     *
     * @return true when the world changed and the view should redraw
     */
    public boolean pump() {
        boolean changed=false;
        ServerMessage message;
        while((message=client.poll())!=null) {
            if(message instanceof Frame frame) {
                NetStats.frameArrived();
                latest=frame.snapshot();
                synchronized(frameHistory) {
                    frameHistory.addLast(new TimedSnapshot(System.nanoTime(),latest));
                    while(frameHistory.size()>MAX_FRAME_HISTORY) frameHistory.removeFirst();
                }
                synchronized(pending) {pending.addAll(frame.events());}
                changed=true;
            } else if(message instanceof LobbyState state) {
                lobby=state;
                changed=true;
            } else if(message instanceof RoomList list) {
                rooms=list.rooms();
                changed=true;
            } else if(message instanceof RoomJoined entered) {
                currentRoom=entered.roomId();
                localPlayer=entered.player();
                latest=null;
                synchronized(frameHistory) {frameHistory.clear();}
                joining=false;
                synchronized(pending) {pending.clear();}
                changed=true;
            } else if(message instanceof RoomLeft) {
                currentRoom=0;
                lobby=null;
                latest=null;
                synchronized(frameHistory) {frameHistory.clear();}
                joining=false;
                synchronized(pending) {pending.clear();}
                changed=true;
            } else if(message instanceof Notice message_) {
                joining=false;
                synchronized(notices) {notices.add(message_);}
            } else if(message instanceof Disconnected gone) {
                joining=false;
                synchronized(notices) {notices.add(new Notice(gone.reason(),true));}
            } else if(message instanceof Chat line) {
                synchronized(chat) {
                    chat.addLast(line);
                    while(chat.size()>MAX_CHAT_HISTORY) chat.removeFirst();
                }
            }
        }
        return changed;
    }

    /**
     * Takes the last undisplayed message, discarding any that piled up behind it.
     *
     * <p>Consuming rather than reading: the view shows it once, so a refusal that has already been
     * read does not sit on screen looking current. Older messages are dropped because showing a
     * queue of stale refusals one per frame is worse than showing the latest state of affairs.
     */
    public Notice takeNotice() {
        synchronized(notices) {
            if(notices.isEmpty()) return null;
            Notice latest=notices.get(notices.size()-1);
            notices.clear();
            return latest;
        }
    }

    /** Rooms the server is currently hosting, for the lobby screen. */
    public List<RoomInfo> roomList() {return rooms;}

    /** Room this client is inside, or 0 while it is browsing the lobby. */
    public int currentRoom() {return currentRoom;}

    /** True while the client is in the lobby rather than inside a room. */
    public boolean inLobby() {return currentRoom==0;}

    /** Creates a room on the server and enters it. */
    public void createRoom(String name,int capacity,BattleType battleType) {
        if(!client.isConnected()) return;
        joining=true;
        lobby=null;
        mode=battleType==BattleType.COOP_BOSS?Mode.COOP:Mode.MULTI;
        client.send(new CreateRoom(name,capacity,battleType));
    }

    /** Enters an existing room by its advertised id. */
    public void joinRoom(int roomId) {
        if(!client.isConnected()||roomId<=0) return;
        rooms.stream().filter(room->room.id()==roomId).findFirst().ifPresent(room->
                mode=room.battleType()==BattleType.COOP_BOSS?Mode.COOP:Mode.MULTI);
        joining=true;
        lobby=null;
        latest=null;
        synchronized(pending) {pending.clear();}
        client.send(new JoinRoom(roomId));
    }

    /** Leaves the current room and returns to the lobby list. */
    public void leaveRoom() {
        if(!client.isConnected()) return;
        currentRoom=0;
        lobby=null;
        latest=null;
        joining=false;
        synchronized(pending) {pending.clear();}
        client.send(new LeaveRoom());
    }

    /** Asks the server to remove a player. Honoured only when this client is the host. */
    public void kick(int target) {client.send(new Kick(localPlayer,target));}

    /** Sends a chat line. The server decides the sender's name, so none is sent. */
    public void say(String text) {
        if(text==null||text.isBlank()) return;
        client.send(new Say(localPlayer,text.strip()));
    }

    /** Chat lines received so far, oldest first. A copy, safe to read from the UI thread. */
    public List<Chat> chatLog() {
        synchronized(chat) {return List.copyOf(chat);}
    }

    // ------------------------------------------------------------------ GameModel

    @Override public void selectMode(Mode mode) {
        if(mode!=null) this.mode=mode;
    }

    @Override public void selectTank(int player,TankType type) {
        if(type==null) return;
        chosen.put(player,type);
        client.send(new SelectTank(player,type));
    }

    @Override public void setTerrainEnabled(boolean enabled) {
        // Terrain is a host decision; a client cannot change it mid-match.
    }

    @Override public void startBattle(long seed) {startBattle(seed,0);}

    @Override public void startBattle(long seed,int players) {
        // Only the host's request is honoured; the server ignores anyone else's.
        client.send(new StartRequest(localPlayer));
    }

    @Override public void tick(long deltaMs) {
        // The server owns the clock. The client only applies what it is told.
    }

    @Override public void handleInput(int player,Action action,boolean active) {
        if(action==null) return;
        recordHeld(player,action,active);
        client.send(new Input(player,action,active));
    }

    /**
     * Mirrors the held-action set locally.
     *
     * <p>Kept so the pending input can be resent as a complete state rather than as the deltas that
     * produced it - a single dropped message would otherwise leave an action stuck on forever.
     */
    private void recordHeld(int player,Action action,boolean active) {
        synchronized(heldInputs) {
            heldInputs.removeIf(message -> message instanceof Input input
                    && input.player()==player && input.action()==action);
            if(active) heldInputs.add(new Input(player,action,true));
        }
    }

    /** Re-sends every currently held action. Called after a reconnection. */
    public void resendHeldInputs() {
        synchronized(heldInputs) {for(ClientMessage message:heldInputs) client.send(message);}
    }

    /** Marks this client ready in the lobby. */
    public void setReady(boolean ready) {client.send(new Ready(localPlayer,ready));}

    /**
     * Asks the server to begin the match. Only the host's request is honoured.
     *
     * <p>Separate from {@link #startBattle} because a network start carries no parameters - the
     * player count is whatever the room holds and the seed is the server's to choose.
     */
    public void requestStart() {client.send(new StartRequest(localPlayer));}

    /** Tank currently chosen for a player, for the lobby list. */
    public TankType chosenTank(int player) {return chosen.getOrDefault(player,TankType.BALANCED);}

    /**
     * A network battle has no pause, shop or deployment: those are single-player systems and the
     * server does not model them. Requests are accepted and ignored so the UI stays consistent.
     */
    @Override public void clearInput() {
        for(Action action:Action.values()) client.send(new Input(localPlayer,action,false));
        synchronized(heldInputs) {heldInputs.clear();}
    }

    @Override public void pause() {}

    @Override public void resume() {}

    @Override public void openShop() {}

    @Override public boolean canPurchase(Item item) {return false;}

    @Override public boolean purchase(Item item) {return false;}

    @Override public boolean beginDeployment() {return false;}

    @Override public boolean canDeployTurret(double x,double y) {return false;}

    @Override public boolean deployTurret(double x,double y) {return false;}

    @Override public void cancelDeployment() {}

    @Override public boolean chooseUpgrade(Upgrade upgrade) {return false;}

    @Override public void restart() {client.send(new StartRequest(localPlayer));}

    @Override public void home() {client.send(new Leave(localPlayer));}

    @Override public void finishRun() {}

    @Override public void exit() {client.send(new Leave(localPlayer));client.close();}

    /**
     * The last world the server sent, or an empty placeholder before the first frame.
     *
     * <p>Never null: the view calls this before a match starts, when there is nothing to show yet.
     */
    @Override public Snapshot snapshot() {
        long targetNanos=System.nanoTime()-INTERPOLATION_DELAY_NANOS;
        TimedSnapshot older=null,newer=null,first=null,last=null;
        synchronized(frameHistory) {
            if(frameHistory.isEmpty()) {
                Snapshot current=latest;
                return current!=null?current:emptySnapshot();
            }
            first=frameHistory.peekFirst();
            last=frameHistory.peekLast();
            for(TimedSnapshot candidate:frameHistory) {
                if(candidate.receivedNanos()<=targetNanos) older=candidate;
                else {
                    newer=candidate;
                    break;
                }
            }
        }
        // Recorded because these two branches are the stutter: no frame to blend towards, so the
        // world is redrawn at its last known position until the next arrival.
        if(older==null) {predicted=false;return first.snapshot();}
        if(newer==null) {
            // Everything on hand is older than the moment being drawn, so there is nothing to blend
            // towards. Carrying the last known speed forward beats standing still: the error is a
            // few pixels, where a frozen frame is a visible hitch.
            predicted=true;
            TimedSnapshot prior=null;
            synchronized(frameHistory) {
                for(TimedSnapshot candidate:frameHistory)
                    if(candidate.receivedNanos()<last.receivedNanos()) prior=candidate;
            }
            return prior==null
                    ? last.snapshot()
                    : extrapolate(prior.snapshot(),prior.receivedNanos(),last.snapshot(),
                            last.receivedNanos(),targetNanos-last.receivedNanos());
        }
        predicted=false;
        long span=newer.receivedNanos()-older.receivedNanos();
        if(span<=0) return newer.snapshot();
        double amount=Math.max(0,Math.min(1,
                (targetNanos-older.receivedNanos())/(double)span));
        return interpolate(older.snapshot(),newer.snapshot(),amount);
    }

    /** How far a round may travel between two server frames and still count as the same one. */
    private static final double MATCH_RADIUS=64;
    /** Longest extrapolation allowed, so a late frame cannot fling anything out of the arena. */
    private static final long MAX_EXTRAPOLATE_NANOS=50_000_000L;
    /** Faster than any tank can drive; a derived speed above this means a teleport, not motion. */
    private static final double MAX_TANK_SPEED=300;

    /**
     * Blends two server frames. Package-private so the pairing rules can be tested without a socket.
     */
    static Snapshot interpolate(Snapshot from,Snapshot to,double amount) {
        List<TankView> tanks=new ArrayList<>(to.tanks().size());
        for(TankView next:to.tanks()) {
            TankView previous=findTank(from.tanks(),next.id(),next.player());
            if(previous==null) {
                tanks.add(next);
            } else {
                tanks.add(new TankView(next.id(),next.player(),next.type(),
                        lerp(previous.x(),next.x(),amount),
                        lerp(previous.y(),next.y(),amount),
                        lerpAngle(previous.angle(),next.angle(),amount),
                        next.hp(),next.maxHp(),next.energy(),next.shieldMs(),next.rapidMs(),next.pulseMs(),
                        next.scatterMs(),next.aimMs(),next.penetrateMs(),next.resonance(),next.turretStock(),next.boss()));
            }
        }

        List<BulletView> bullets=new ArrayList<>(to.bullets().size());
        boolean[] used=new boolean[from.bullets().size()];
        for(BulletView next:to.bullets()) {
            int match=-1;
            // Compared squared against squared: measuring a plain distance against a squared bound
            // rejects nothing at all, which silently pairs every new round with a dead one.
            double best=MATCH_RADIUS*MATCH_RADIUS;
            for(int i=0;i<from.bullets().size();i++) {
                BulletView previous=from.bullets().get(i);
                if(used[i]||previous.owner()!=next.owner()) continue;
                double dx=previous.x()-next.x(),dy=previous.y()-next.y();
                double distance=dx*dx+dy*dy;
                if(distance<best) {
                    best=distance;
                    match=i;
                }
            }
            if(match<0) {
                bullets.add(next);
            } else {
                used[match]=true;
                BulletView previous=from.bullets().get(match);
                bullets.add(new BulletView(
                        lerp(previous.x(),next.x(),amount),
                        lerp(previous.y(),next.y(),amount),
                        lerp(previous.vx(),next.vx(),amount),
                        lerp(previous.vy(),next.vy(),amount),
                        next.owner(),next.bounces(),next.penetrate()));
            }
        }

        List<TurretView> turrets=new ArrayList<>(to.turrets().size());
        for(TurretView next:to.turrets()) {
            TurretView previous=from.turrets().stream()
                    .filter(candidate->candidate.owner()==next.owner()
                            && Math.hypot(candidate.x()-next.x(),candidate.y()-next.y())<1)
                    .findFirst().orElse(null);
            turrets.add(previous==null?next:new TurretView(next.x(),next.y(),
                    lerpAngle(previous.angle(),next.angle(),amount),next.remainingMs(),next.owner()));
        }

        return new Snapshot(to.state(),to.mode(),to.result(),to.wave(),to.score(),to.coins(),to.kills(),
                to.shields(),to.elapsedMs(),to.seed(),to.intermissionMs(),tanks,bullets,to.walls(),
                to.drops(),to.pickups(),to.portals(),to.zones(),turrets,to.upgrades());
    }

    /**
     * Carries the world forward when no frame newer than the moment being drawn has arrived.
     *
     * <p>What it replaces is redrawing the newest frame unchanged, which is what a frozen frame is.
     * Against a real network about two percent of rendered frames had nothing to blend towards -
     * the 45 ms buffer leaves only ~12 ms of slack against a 33 ms frame interval, and delivery
     * jitter routinely exceeds that.
     *
     * <p>Rounds carry their own velocity. Tanks do not, so theirs is read off the two newest frames;
     * a derived speed beyond anything a tank can drive means it came through a portal and is left
     * where the server put it rather than smeared onwards.
     *
     * @param aheadNanos how far past the newest frame to predict, clamped to {@link #MAX_EXTRAPOLATE_NANOS}
     */
    static Snapshot extrapolate(Snapshot previous,long previousNanos,
                                Snapshot newest,long newestNanos,long aheadNanos) {
        double seconds=Math.min(Math.max(0,aheadNanos),MAX_EXTRAPOLATE_NANOS)/1e9;
        double spanSeconds=(newestNanos-previousNanos)/1e9;

        List<TankView> tanks=new ArrayList<>(newest.tanks().size());
        for(TankView tank:newest.tanks()) {
            TankView before=spanSeconds>0?findTank(previous.tanks(),tank.id(),tank.player()):null;
            double vx=0,vy=0;
            if(before!=null) {
                vx=(tank.x()-before.x())/spanSeconds;
                vy=(tank.y()-before.y())/spanSeconds;
                if(vx*vx+vy*vy>MAX_TANK_SPEED*MAX_TANK_SPEED) {vx=0;vy=0;}
            }
            tanks.add(new TankView(tank.id(),tank.player(),tank.type(),
                    tank.x()+vx*seconds,tank.y()+vy*seconds,tank.angle(),
                    tank.hp(),tank.maxHp(),tank.energy(),tank.shieldMs(),tank.rapidMs(),tank.pulseMs(),
                    tank.scatterMs(),tank.aimMs(),tank.penetrateMs(),tank.resonance(),tank.turretStock(),
                    tank.boss()));
        }

        List<BulletView> bullets=new ArrayList<>(newest.bullets().size());
        for(BulletView bullet:newest.bullets())
            bullets.add(new BulletView(bullet.x()+bullet.vx()*seconds,bullet.y()+bullet.vy()*seconds,
                    bullet.vx(),bullet.vy(),bullet.owner(),bullet.bounces(),bullet.penetrate()));

        return new Snapshot(newest.state(),newest.mode(),newest.result(),newest.wave(),newest.score(),
                newest.coins(),newest.kills(),newest.shields(),newest.elapsedMs(),newest.seed(),
                newest.intermissionMs(),tanks,bullets,newest.walls(),newest.drops(),newest.pickups(),
                newest.portals(),newest.zones(),newest.turrets(),newest.upgrades());
    }

    private static TankView findTank(List<TankView> tanks,int id,int player) {
        for(TankView tank:tanks) if(tank.id()==id&&tank.player()==player) return tank;
        return null;
    }

    private static double lerp(double from,double to,double amount) {
        return from+(to-from)*amount;
    }

    private static double lerpAngle(double from,double to,double amount) {
        double delta=((to-from+540)%360)-180;
        return (from+delta*amount+360)%360;
    }

    @Override public List<GameEvent> drainEvents() {
        synchronized(pending) {
            if(pending.isEmpty()) return List.of();
            List<GameEvent> drained=List.copyOf(pending);
            pending.clear();
            return drained;
        }
    }

    private Snapshot emptySnapshot() {
        return new Snapshot(State.HOME,mode,Result.NONE,1,0,0,0,0,0,0,0,
                List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of(),List.of());
    }
}
