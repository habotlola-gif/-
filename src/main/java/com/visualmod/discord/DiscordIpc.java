package com.visualmod.discord;

import com.google.gson.JsonObject;

import java.io.Closeable;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * Минимальный клиент Discord IPC (Rich Presence) без внешних библиотек.
 * Windows: именованный канал \\.\pipe\discord-ipc-N. Linux/macOS: unix-сокет discord-ipc-N.
 * Все методы синхронные; вызывай их из одного фонового потока.
 */
public final class DiscordIpc implements Closeable {
    private static final int OP_HANDSHAKE = 0, OP_FRAME = 1, OP_CLOSE = 2, OP_PING = 3, OP_PONG = 4;

    private final String clientId;
    private InputStream in;
    private OutputStream out;
    private Closeable handle;
    private volatile boolean connected;

    public DiscordIpc(String clientId) {
        this.clientId = clientId;
    }

    public boolean isConnected() {
        return this.connected;
    }

    /** Подключается к запущенному Discord. Возвращает false, если клиента нет или он не ответил. */
    public boolean connect() {
        for (int i = 0; i < 10 && !this.connected; i++) {
            try {
                if (!open(i)) continue;
                JsonObject hs = new JsonObject();
                hs.addProperty("v", 1);
                hs.addProperty("client_id", this.clientId);
                write(OP_HANDSHAKE, hs.toString());
                // ответ READY читаем синхронно один раз
                DataInputStream din = new DataInputStream(this.in);
                readFrame(din);
                this.connected = true;
                Thread reader = new Thread(this::readLoop, "VisualMod-DiscordIPC-Reader");
                reader.setDaemon(true);
                reader.start();
            } catch (Exception e) {
                closeQuietly();
            }
        }
        return this.connected;
    }

    private boolean open(int index) throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            RandomAccessFile raf;
            try {
                raf = new RandomAccessFile("\\\\.\\pipe\\discord-ipc-" + index, "rw");
            } catch (IOException e) {
                return false;
            }
            this.handle = raf;
            this.in = Channels.newInputStream(raf.getChannel());
            this.out = Channels.newOutputStream(raf.getChannel());
            return true;
        }
        for (Path dir : unixDirs()) {
            Path sock = dir.resolve("discord-ipc-" + index);
            if (!Files.exists(sock)) continue;
            try {
                SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX);
                ch.connect(UnixDomainSocketAddress.of(sock));
                this.handle = ch;
                this.in = Channels.newInputStream(ch);
                this.out = Channels.newOutputStream(ch);
                return true;
            } catch (IOException ignored) {
            }
        }
        return false;
    }

    private static Path[] unixDirs() {
        String xdg = System.getenv("XDG_RUNTIME_DIR");
        String tmp = firstNonNull(System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP"), "/tmp");
        java.util.List<Path> dirs = new java.util.ArrayList<>();
        if (xdg != null) {
            dirs.add(Path.of(xdg));
            dirs.add(Path.of(xdg, "app", "com.discordapp.Discord"));   // flatpak
            dirs.add(Path.of(xdg, "snap.discord"));                    // snap
        }
        dirs.add(Path.of(tmp));
        return dirs.toArray(new Path[0]);
    }

    private static String firstNonNull(String... v) {
        for (String s : v) if (s != null) return s;
        return "/tmp";
    }

    /** Ставит статус. activity == null убирает статус. */
    public synchronized void setActivity(JsonObject activity) {
        if (!this.connected) return;
        JsonObject args = new JsonObject();
        args.addProperty("pid", ProcessHandle.current().pid());
        if (activity != null) args.add("activity", activity);
        JsonObject cmd = new JsonObject();
        cmd.addProperty("cmd", "SET_ACTIVITY");
        cmd.add("args", args);
        cmd.addProperty("nonce", UUID.randomUUID().toString());
        try {
            write(OP_FRAME, cmd.toString());
        } catch (IOException e) {
            closeQuietly();
        }
    }

    private synchronized void write(int op, String json) throws IOException {
        byte[] data = json.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(8 + data.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(op).putInt(data.length).put(data);
        this.out.write(buf.array());
        this.out.flush();
    }

    private int[] readFrame(DataInputStream din) throws IOException {
        byte[] head = new byte[8];
        din.readFully(head);
        ByteBuffer hb = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int op = hb.getInt(), len = hb.getInt();
        if (len < 0 || len > 1 << 20) throw new IOException("bad frame");
        byte[] body = new byte[len];
        din.readFully(body);
        return new int[]{op, len};
    }

    private void readLoop() {
        DataInputStream din = new DataInputStream(this.in);
        try {
            while (this.connected) {
                int[] f = readFrame(din);
                if (f[0] == OP_PING) {
                    try { write(OP_PONG, "{}"); } catch (IOException ignored) { }
                } else if (f[0] == OP_CLOSE) {
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            closeQuietly();
        }
    }

    private void closeQuietly() {
        this.connected = false;
        try { if (this.handle != null) this.handle.close(); } catch (IOException ignored) { }
        this.handle = null;
    }

    @Override
    public void close() {
        if (this.connected) {
            try { setActivity(null); } catch (Exception ignored) { }
        }
        closeQuietly();
    }
}
