package tanktrouble.net;

import java.io.*;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static tanktrouble.net.Protocol.*;

/**
 * The client end of a connection: a socket, a reader thread, and two queues.
 *
 * <p>Incoming frames land in a bounded queue rather than being applied directly, so the JavaFX
 * thread decides when to take them. A bounded queue with "drop the oldest" is deliberate: if the
 * render thread falls behind, the right thing to do is skip ahead to the newest world state, not
 * fall further behind replaying stale ones.
 *
 * <p>Outgoing messages go through a writer thread for the same reason in reverse - a slow network
 * must not stall the render loop that produced them.
 */
public final class GameClient implements AutoCloseable {
    private static final int QUEUE_DEPTH=64;

    private final BlockingQueue<ServerMessage> incoming=new LinkedBlockingQueue<>(QUEUE_DEPTH);
    private final BlockingQueue<ClientMessage> outgoing=new LinkedBlockingQueue<>();
    private final AtomicBoolean running=new AtomicBoolean();

    private Socket socket;
    private Thread reader;
    private Thread writer;
    private volatile String failure;

    /** Connects and completes the join handshake. Throws if the server refuses. */
    public void connect(String host,int port,String name,long timeoutMillis) throws IOException {
        connect(host,port,name,"",timeoutMillis);
    }

    /**
     * Connects and completes the join handshake.
     *
     * @param password the server's room password; empty when the server requires none
     */
    public void connect(String host,int port,String name,String password,long timeoutMillis) throws IOException {
        socket=new Socket();
        socket.connect(new InetSocketAddress(host,port),(int)timeoutMillis);
        socket.setTcpNoDelay(true); // a held-key change is tiny and latency-sensitive

        ObjectOutputStream out=new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
        out.flush();
        ObjectInputStream in=new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));

        out.writeObject(new Join(VERSION,name,password==null?"":password));
        out.flush();
        Object reply;
        try {
            reply=in.readObject();
        } catch(ClassNotFoundException broken) {
            throw new IOException("无法识别的服务器响应",broken);
        }
        // A refusal may arrive either as an explicit Rejected or as the Disconnected that the
        // server sends before hanging up, depending on where in the handshake it was refused.
        // Both carry a reason the player needs to see.
        if(reply instanceof Rejected rejected) throw new IOException(rejected.reason());
        if(reply instanceof Disconnected gone) throw new IOException(gone.reason());
        if(!(reply instanceof Joined joined)) throw new IOException("服务器返回了无法识别的响应："+reply);

        running.set(true);
        incoming.add(joined);
        reader=new Thread(()->readLoop(in),"tank-reader");
        reader.setDaemon(true);
        reader.start();
        writer=new Thread(()->writeLoop(out),"tank-writer");
        writer.setDaemon(true);
        writer.start();
    }

    public boolean isConnected() {return running.get()&&socket!=null&&!socket.isClosed();}

    /** Why the connection ended, if it ended unexpectedly. */
    public String failure() {return failure;}

    /** Queues a message for the server. Ignored once the connection is gone. */
    public void send(ClientMessage message) {
        if(!isConnected()) return;
        outgoing.offer(message);
    }

    /** Newest message from the server, or null. Messages are taken in the order they arrived. */
    public ServerMessage poll() {synchronized(incoming) {return incoming.poll();}}

    private void readLoop(ObjectInputStream in) {
        try {
            while(running.get()) {
                ServerMessage message=(ServerMessage)in.readObject();
                enqueue(incoming,message);
            }
        } catch(EOFException|SocketException closed) {
            if(running.get()) failure="与服务器断开连接";
        } catch(IOException|ClassNotFoundException broken) {
            failure="连接异常："+broken.getMessage();
        } finally {
            running.set(false);
        }
    }

    /** Kept as a package-level test seam; the shared implementation lives in NetworkQueues. */
    static void enqueue(BlockingQueue<ServerMessage> queue,ServerMessage message) {
        NetworkQueues.enqueue(queue,message);
    }

    private void writeLoop(ObjectOutputStream out) {
        try {
            while(running.get()) {
                ClientMessage message=outgoing.poll(200,TimeUnit.MILLISECONDS);
                if(message==null) continue;
                out.writeObject(message);
                out.flush();
            }
        } catch(InterruptedException stop) {
            Thread.currentThread().interrupt();
        } catch(IOException closed) {
            running.set(false);
        }
    }

    @Override public void close() {
        running.set(false);
        try {
            if(socket!=null) socket.close();
        } catch(IOException ignored) {
            // Already gone.
        }
        if(reader!=null) reader.interrupt();
        if(writer!=null) writer.interrupt();
    }
}
