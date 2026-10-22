package com.gathin.tvhome;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A small client for the TV's own adb daemon, used for one thing: {@code am force-stop <package>}. Some TV firmware
 * ignores the launcher's request to close other apps (killBackgroundProcesses) but honours the same close from the
 * adb shell, so "close all" and auto-trim ask the TV's adb to do it, the way tools/tv.py does from a computer.
 *
 * It is deliberately not a general shell. The only public entry point builds its command from validated package names,
 * refuses system packages, caps the number of packages per call, talks to the loopback address only, holds no
 * connection open between calls, does not do TLS (wireless debugging) and rejects oversized messages. The first
 * connection makes the TV show "Allow USB debugging?", which the person at the TV accepts once.
 *
 * Pure Java (no Android types) so the key format and the signing are tested on the JVM against adb's own.
 */
final class AdbClient {
    enum Result {
        /** The command ran. */
        OK,
        /** Nothing is listening on the adb port: USB debugging is off on the TV. */
        ADB_OFF,
        /** The TV has not accepted this app's key, or nobody accepted the prompt in time. */
        NOT_ALLOWED,
        /** Anything else: a refused package, a protocol error, an error printed by the command. */
        FAILED
    }

    static final int PORT = 5555;
    static final int MAX_PACKAGES = 40;

    private static final int A_CNXN = 0x4e584e43;
    private static final int A_AUTH = 0x48545541;
    private static final int A_OPEN = 0x4e45504f;
    private static final int A_OKAY = 0x59414b4f;
    private static final int A_CLSE = 0x45534c43;
    private static final int A_WRTE = 0x45545257;
    private static final int A_STLS = 0x534c5453;
    private static final int VERSION = 0x01000001;
    private static final int MAX_DATA = 4096;
    private static final int MAX_PAYLOAD = 1 << 20;   // a longer message is refused
    private static final int AUTH_TOKEN = 1;
    private static final int AUTH_SIGNATURE = 2;
    private static final int AUTH_RSAPUBLICKEY = 3;
    private static final int CONNECT_MS = 1500;
    private static final int READ_MS = 8000;
    private static final int PROMPT_MS = 30000;   // time given to the person at the TV to accept the prompt
    private static final int MODULUS_BYTES = 256;

    /** DER prefix of a SHA-1 DigestInfo: adb signs its 20-byte token as if it were already a SHA-1 hash. */
    private static final byte[] SHA1_PREFIX = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");

    private AdbClient() {
    }

    /** Whether {@code pkg} is a well-formed Android package name: letters, digits, underscores and dots, nothing else. */
    static boolean validPackage(String pkg) {
        return pkg != null && pkg.length() <= 255 && PACKAGE.matcher(pkg).matches();
    }

    /** Packages that are never stopped, whatever the caller asks: the system and what the TV needs to work. */
    static boolean refused(String pkg) {
        return pkg.equals("android")
                || pkg.equals("com.android.systemui")
                || pkg.equals("com.android.settings")
                || pkg.equals("com.google.android.gms")
                || pkg.equals("com.google.android.gsf")
                || pkg.startsWith("com.android.providers.")
                || pkg.startsWith("com.android.server.");
    }

    /**
     * The one command this client runs: {@code am force-stop} for each package, joined with "; ". Throws if a name is
     * malformed, refused, or there are too many; nothing but validated names can reach the shell.
     */
    static String forceStopCommand(List<String> packages) {
        if (packages.isEmpty() || packages.size() > MAX_PACKAGES) throw new IllegalArgumentException("1 to " + MAX_PACKAGES + " packages");
        StringBuilder sb = new StringBuilder();
        for (String pkg : packages) {
            if (!validPackage(pkg)) throw new IllegalArgumentException("not a package name: " + pkg);
            if (refused(pkg)) throw new IllegalArgumentException("not stopped: " + pkg);
            if (sb.length() > 0) sb.append("; ");
            sb.append("am force-stop ").append(pkg);
        }
        return sb.toString();
    }

    /** Stops the packages through the TV's own adb on the loopback address. {@code allowPrompt} false never shows the TV's prompt. */
    static Result forceStop(KeyPair keys, List<String> packages, boolean allowPrompt) {
        return forceStop(keys, "127.0.0.1", PORT, packages, allowPrompt);
    }

    /** Same, to another address: only for tests against a real TV from a computer. */
    static Result forceStop(KeyPair keys, String host, int port, List<String> packages, boolean allowPrompt) {
        final String command;
        try {
            command = forceStopCommand(packages);
        } catch (IllegalArgumentException e) {
            return Result.FAILED;
        }
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), CONNECT_MS);
            socket.setSoTimeout(READ_MS);
            OutputStream out = socket.getOutputStream();
            DataInputStream in = new DataInputStream(socket.getInputStream());
            Result connected = connect(keys, out, in, socket, allowPrompt);
            if (connected != Result.OK) return connected;
            String printed = shell(out, in, command);
            // am prints nothing when it works; a message is an error.
            return printed.trim().isEmpty() ? Result.OK : Result.FAILED;
        } catch (ConnectException e) {
            return Result.ADB_OFF;
        } catch (SocketTimeoutException e) {
            return Result.NOT_ALLOWED;
        } catch (IOException | GeneralSecurityException e) {
            return Result.FAILED;
        }
    }

    private static Result connect(KeyPair keys, OutputStream out, DataInputStream in, Socket socket, boolean allowPrompt)
            throws IOException, GeneralSecurityException {
        send(out, A_CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
        boolean signed = false;
        for (int i = 0; i < 4; i++) {
            Packet p = read(in);
            if (p.command == A_CNXN) return Result.OK;
            if (p.command == A_STLS) return Result.FAILED;   // wireless debugging (TLS): not supported
            if (p.command != A_AUTH || p.arg0 != AUTH_TOKEN) return Result.FAILED;
            if (!signed) {
                send(out, A_AUTH, AUTH_SIGNATURE, 0, sign(keys, p.data));
                signed = true;
            } else if (allowPrompt) {
                // The TV did not know the key: offer it, and wait for the person at the TV to accept.
                send(out, A_AUTH, AUTH_RSAPUBLICKEY, 0, authPublicKey((RSAPublicKey) keys.getPublic(), "tvhome@launcher"));
                socket.setSoTimeout(PROMPT_MS);
            } else {
                return Result.NOT_ALLOWED;
            }
        }
        return Result.NOT_ALLOWED;
    }

    private static String shell(OutputStream out, DataInputStream in, String command) throws IOException {
        final int local = 1;
        send(out, A_OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream printed = new ByteArrayOutputStream();
        int remote = 0;
        while (true) {
            Packet p = read(in);
            if (p.command == A_OKAY) {
                remote = p.arg0;
            } else if (p.command == A_WRTE) {
                if (printed.size() < 4096) printed.write(p.data, 0, Math.min(p.data.length, 4096 - printed.size()));
                send(out, A_OKAY, local, remote, new byte[0]);
            } else if (p.command == A_CLSE) {
                send(out, A_CLSE, local, remote, new byte[0]);
                return new String(printed.toByteArray(), StandardCharsets.UTF_8);
            } else {
                throw new IOException("unexpected message");
            }
        }
    }

    // ---- messages

    private static final class Packet {
        final int command;
        final int arg0;
        final byte[] data;

        Packet(int command, int arg0, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.data = data;
        }
    }

    private static void send(OutputStream out, int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer h = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(0).putInt(command ^ 0xffffffff);
        out.write(h.array());
        if (data.length > 0) out.write(data);
        out.flush();
    }

    private static Packet read(DataInputStream in) throws IOException {
        byte[] header = new byte[24];
        in.readFully(header);
        ByteBuffer h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);
        int command = h.getInt();
        int arg0 = h.getInt();
        h.getInt();   // arg1
        int length = h.getInt();
        h.getInt();   // checksum: newer adbd does not check it, so it is not checked here either
        int magic = h.getInt();
        if (magic != (command ^ 0xffffffff)) throw new IOException("bad message");
        if (length < 0 || length > MAX_PAYLOAD) throw new IOException("message too long");
        byte[] data = new byte[length];
        in.readFully(data);
        return new Packet(command, arg0, data);
    }

    // ---- keys and signing (the formats adb uses)

    /** The 20-byte token the TV sends, signed with the private key as adb does: PKCS#1 v1.5 over a SHA-1 DigestInfo. */
    static byte[] sign(KeyPair keys, byte[] token) throws GeneralSecurityException, IOException {
        if (token.length != 20) throw new IOException("unexpected token");
        Signature s = Signature.getInstance("NONEwithRSA");
        s.initSign(keys.getPrivate());
        s.update(SHA1_PREFIX);
        s.update(token);
        return s.sign();
    }

    /** The public key as the TV's prompt expects it: base64 of adb's key structure, a space, a name and a NUL. */
    static byte[] authPublicKey(RSAPublicKey key, String name) {
        String text = Base64.getEncoder().encodeToString(publicKeyStructure(key)) + " " + name + "\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * adb's RSAPublicKey structure (android_pubkey.c), little-endian: the modulus size in words (64), n0inv = -1 / n[0]
     * mod 2^32, the modulus, rr = (2^2048)^2 mod n, and the exponent. 524 bytes for a 2048-bit key.
     */
    static byte[] publicKeyStructure(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        if (n.bitLength() != MODULUS_BYTES * 8) throw new IllegalArgumentException("adb needs a 2048-bit key");
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        long n0inv = r32.subtract(n.mod(r32).modInverse(r32)).longValue() & 0xffffffffL;
        BigInteger rr = BigInteger.ONE.shiftLeft(MODULUS_BYTES * 8).pow(2).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + MODULUS_BYTES + MODULUS_BYTES + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(MODULUS_BYTES / 4);
        b.putInt((int) n0inv);
        b.put(littleEndian(n));
        b.put(littleEndian(rr));
        if (key.getPublicExponent().bitLength() > 31) throw new IllegalArgumentException("exponent too large");
        b.putInt(key.getPublicExponent().intValue());
        return b.array();
    }

    private static byte[] littleEndian(BigInteger v) {
        byte[] big = v.toByteArray();   // may carry a leading zero byte
        byte[] out = new byte[MODULUS_BYTES];
        for (int i = 0; i < MODULUS_BYTES && i < big.length; i++) out[i] = big[big.length - 1 - i];
        return out;
    }
}
