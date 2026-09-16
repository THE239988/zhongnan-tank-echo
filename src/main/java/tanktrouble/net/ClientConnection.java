package tanktrouble.net;

import java.io.*;
import java.net.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;
import tanktrouble.net.Protocol.ServerMessage;

/**
 * One connected client: its socket, its writer lock, and its lifecycle.
 *
 * <p>Separated from the room it belongs to because a connection outlives any particular room. A
 * player is connected to the server from the moment they type the address; joining a room does not
 * create a new socket, and leaving one does not close it. The connection is the durable thing, and
 * both the lobby and a room need to send through it.
 *
 * <p>Reading is not done here: who handles an incoming message depends on whether its sender is
 * still in the lobby or already in a room, so the dispatch lives one level up.
 */
final class ClientConnection {
    private static final int OUTGOING_DEPTH=64;
    /**
     * How many messages may ride the stream's caches before they are dropped.
     *
     * <p>{@code reset()} discards the class-descriptor and handle tables, so the next write re-sends
     * descriptors the peer already has. Doing it after <em>every</em> message measured 3.7x the bytes
     * and 13x the CPU of not doing it at all. Never resetting would instead grow the handle table for
     * the life of the match, so it is done on a counter: one message in {@value} pays the descriptor
     * cost and the rest ride the cache.
     */
    private static final int RESET_EVERY=30;
    private final Socket socket;
    private final ObjectOutputStream out;
    private final AtomicBoolean closeClaimed=new AtomicBoolean();
    private final AtomicBoolean accepting=new AtomicBoolean(true);
    private final BlockingQueue<ServerMessage> outgoing=new LinkedBlockingQueue<>(OUTGOING_DEPTH);
    private final Thread writer;
    private volatile boolean closed;
    private volatile boolean shutdownWhenDrained;
    private volatile String failure;

    /**
     * Invoked once when the connection ends, from whichever thread noticed.
     *
     * <p>Owned solely by the server, never by a room. A room used to register itself here too, and
     * because two rooms cannot both own one slot, the second overwrote the first - leaving the
     * abandoned room's loop still reacting to a connection that had moved on, emptying a room that
     * was not empty and closing a connection that was still in use.
     */
    private volatile Runnable onClosed=()->{};

    ClientConnection(Socket socket) throws IOException {
        this.socket=socket;
        this.socket.setTcpNoDelay(true);
        // Flush the header immediately so the peer's ObjectInputStream can construct.
        this.out=new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        this.out.flush();
        writer=new Thread(this::writeLoop,"tank-writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Replaces the end-of-life listener. The server keeps this slot for itself. */
    void setOnClosed(Runnable listener) {this.onClosed=listener;}

    boolean isOpen() {return !closed&&!socket.isClosed();}

    /** Why the connection ended, if it failed rather than closing cleanly. */
    String failure() {return failure;}

    InetAddress address() {return socket.getInetAddress();}

    /**
     * The socket's input stream, for the reader that dispatches this connection's messages.
     *
     * <p>Exposed rather than wrapped here because deciding who handles a message - the lobby or a
     * room - is a server concern; this class only owns the wire.
     */
    InputStream input() throws IOException {return new BufferedInputStream(socket.getInputStream());}

    void send(ServerMessage message) {
        if(closed||!accepting.get()) return;
        NetworkQueues.enqueue(outgoing,message);
    }

    /** Closes without explanation, for connection failures the peer cannot act on. */
    void close() {close(null);}

    /**
     * Closes, optionally delivering a farewell first. The connection writer drains the farewell
     * queue and closes the socket; the caller returns immediately even when the peer is slow.
     */
    void close(String reason) {
        if(!closeClaimed.compareAndSet(false,true)) return;
        if(reason!=null) {
            NetworkQueues.enqueue(outgoing,new Protocol.Disconnected(reason));
            accepting.set(false);
            shutdownWhenDrained=true;
        } else {
            accepting.set(false);
            closeSocketNow();
        }
    }

    /**
     * A connection owns its writer, so a slow peer can only fill that peer's queue. The room thread
     * never blocks on a socket again: stale frames are coalesced and dropped once the queue is full.
     */
    private void writeLoop() {
        int sinceReset=0;
        try {
            while(!closed) {
                if(shutdownWhenDrained&&outgoing.isEmpty()) {
                    Thread.sleep(60);
                    return;
                }
                ServerMessage message=outgoing.poll(200,TimeUnit.MILLISECONDS);
                if(message==null) continue;
                out.writeObject(message);
                out.flush();
                if(++sinceReset>=RESET_EVERY) {
                    sinceReset=0;
                    out.reset();
                }
            }
        } catch(InterruptedException stopped) {
            Thread.currentThread().interrupt();
        } catch(IOException dead) {
            if(!closed) failure="发送失败：" + dead.getMessage();
        } finally {
            closeSocketNow();
        }
    }

    private void closeSocketNow() {
        if(closed) return;
        closed=true;
        try {socket.close();} catch(IOException ignored) {}
        try {onClosed.run();} catch(RuntimeException ignored) {
            // A listener that throws must not stop the connection from being torn down.
        }
    }
}
