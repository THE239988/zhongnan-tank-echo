package tanktrouble.net;

import java.io.Serializable;
import java.util.List;
import tanktrouble.model.data.GameData.*;

/**
 * Everything that crosses the wire.
 *
 * <p>Messages are Java-serialised objects rather than hand-rolled binary. The alternative would be
 * a byte protocol plus a parallel set of DTOs, and the entire reason {@link Snapshot} is
 * {@link Serializable} is that it already is the one true description of the world - copying it
 * into a second shape would just create something else to keep in sync. The trade-offs are real
 * (larger frames, a class-version hazard if the two ends are built from different sources), but
 * this is a same-build, same-machine prototype where neither matters yet.
 */
public final class Protocol {
    private Protocol() {}

    /** Bumped whenever a message shape changes, so mismatched builds fail loudly. */
    public static final long VERSION = 4L;

    /** How often the server broadcasts the world, in hertz. */
    public static final int BROADCAST_HZ = 30;

    public static final int DEFAULT_PORT = 7777;

    /** Lobby lifecycle, reported to every client. */
    public enum Phase { LOBBY, PLAYING, FINISHED }
    public enum BattleType {
        FREE_FOR_ALL("多人混战"), COOP_BOSS("合作讨伐");
        private final String label;
        BattleType(String label) {this.label=label;}
        public String label() {return label;}
    }

    public sealed interface ClientMessage extends Serializable
            permits Join, SelectTank, Ready, Input, StartRequest, Leave, Kick, Say,
                    CreateRoom, JoinRoom, LeaveRoom {}

    public sealed interface ServerMessage extends Serializable
            permits Joined, Rejected, LobbyState, Frame, Disconnected, Notice, Chat,
                    RoomList, RoomJoined, RoomLeft {}

    /** Longest chat line accepted; anything beyond this is truncated rather than rejected. */
    public static final int MAX_CHAT_LENGTH = 120;

    /** Fastest a player may send chat lines, to keep one person from flooding the room. */
    public static final long CHAT_COOLDOWN_MILLIS = 500;

    /**
     * First message a client sends.
     *
     * @param name display name, shown in the lobby
     */
    public record Join(long version, String name, String password) implements ClientMessage {
        public static final long serialVersionUID = 1L;
        /** Joining a server that has no password configured. */
        public Join(long version,String name) {this(version,name,"");}
    }

    /**
     * What the lobby advertises about a room.
     *
     * @param id       opaque handle the client sends back to join
     * @param players  current occupancy
     * @param capacity maximum occupancy
     * @param phase    whether the room is waiting, mid-match, or finished
     */
    public record RoomInfo(int id, String name, int players, int capacity, Phase phase, BattleType battleType) implements Serializable {
        public static final long serialVersionUID = 1L;
        public boolean joinable() {return phase==Phase.LOBBY&&players<capacity;}
        public String label() {
            String status=switch(phase) {
                case LOBBY -> players<capacity?"等待中":"已满";
                case PLAYING -> "对局中";
                case FINISHED -> "已结束";
            };
            return "#" + id + "  " + battleType.label() + "  ·  " + name + "  ·  " + players + "/" + capacity + "  ·  " + status;
        }
    }

    /**
     * Every room the server is currently hosting.
     *
     * <p>Carries {@link RoomInfo} records rather than a hand-rolled string encoding because the
     * lobby and the client both want the same structured fields; the label is derived on the client
     * so the wording can change without a protocol revision.
     */
    public record RoomList(java.util.List<RoomInfo> rooms) implements ServerMessage {
        public static final long serialVersionUID = 1L;
        public RoomList {rooms=java.util.List.copyOf(rooms);}
    }

    public record CreateRoom(String name, int capacity, BattleType battleType) implements ClientMessage {
        public static final long serialVersionUID = 1L;
        public CreateRoom(String name,int capacity) {this(name,capacity,BattleType.FREE_FOR_ALL);}
        public CreateRoom {if(battleType==null) battleType=BattleType.FREE_FOR_ALL;}
    }

    public record JoinRoom(int roomId) implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    /** Leaves the current room and returns to the lobby, without disconnecting. */
    public record LeaveRoom() implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    /** Confirms a room was entered and says which player index was assigned. */
    public record RoomJoined(int roomId, int player) implements ServerMessage {
        public static final long serialVersionUID = 1L;
    }

    /** Confirms a return to the lobby. */
    public record RoomLeft() implements ServerMessage {
        public static final long serialVersionUID = 1L;
    }

    /** {@code player} is the index the server assigned; the client uses it for its own HUD. */
    public record Joined(int player, int maxPlayers, String mapSeedNote) implements ServerMessage {
        public static final long serialVersionUID = 1L;
    }

    public record Rejected(String reason) implements ServerMessage {
        public static final long serialVersionUID = 1L;
    }

    /** A player's tank choice, made in the lobby before the battle starts. */
    public record SelectTank(int player, TankType type) implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    public record Ready(int player, boolean ready) implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    public record StartRequest(int player) implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    /**
     * Removes a player from the room. Honoured only when sent by the host.
     *
     * @param player the host asking for the removal
     * @param target the player being removed
     */
    public record Kick(int player, int target) implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    /**
     * A change in one player's held actions.
     *
     * <p>Sent as an absolute "this action is now held/released" rather than a frame delta, which
     * makes the stream self-correcting: a dropped message costs at most one action until the next
     * change, and a client that reconnects can resend its whole held set.
     */
    public record Input(int player, Action action, boolean active) implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    public record Leave(int player) implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    /** Who is in the room and what they picked. Sent on every membership change. */
    public record LobbyState(Phase phase, int hostPlayer, List<Member> members, String status)
            implements ServerMessage {
        public static final long serialVersionUID = 1L;
        public record Member(int player, String name, TankType tank, boolean ready) implements Serializable {
            public static final long serialVersionUID = 1L;
        }
    }

    /**
     * One broadcast: the world plus everything that happened since the last broadcast.
     *
     * <p>Snapshot and events travel together because they describe the same interval. Sending them
     * separately would let a client render a frame whose sounds belong to a different one.
     */
    public record Frame(Snapshot snapshot, List<GameEvent> events) implements ServerMessage {
        public static final long serialVersionUID = 1L;
        public Frame {
            events = List.copyOf(events);
        }
    }

    public record Disconnected(String reason) implements ServerMessage {
        public static final long serialVersionUID = 1L;
    }

    /**
     * Something the player needs to be told that is not a change of room state - why a start was
     * refused, why a tank choice was rejected, and so on.
     *
     * <p>Separate from {@link LobbyState} because it is a transient message about one attempt, not
     * the room's current situation; folding it into the roster would leave it on screen forever.
     */
    public record Notice(String text, boolean problem) implements ServerMessage {
        public static final long serialVersionUID = 1L;
    }

    /** A chat line from one player. {@code player} is filled in by the server, never trusted. */
    public record Say(int player, String text) implements ClientMessage {
        public static final long serialVersionUID = 1L;
    }

    /**
     * A chat line shown to the room.
     *
     * @param player the sender's index, or -1 for a system message such as "X joined"
     */
    public record Chat(int player, String name, String text) implements ServerMessage {
        public static final long serialVersionUID = 1L;
        public boolean isSystem() { return player < 0; }
    }
}
