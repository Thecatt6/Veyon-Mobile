package com.veyonmobile;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.Base64;
import android.util.Log;
import android.view.SurfaceHolder;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.PushbackInputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;

/**
 * VeyonVncClient
 *
 * Implements:
 *   1. RFB 3.8 handshake
 *   2. Veyon security type 40 with KeyFile auth (type 4)
 *   3. Framebuffer streaming with Tight/ZRLE/Raw encoding
 *
 * VariantArrayMessage format (Qt QDataStream, big-endian):
 *   [uint32: total_size] [uint32: list_count]
 *   For each QVariant:
 *     [uint32: type] [uint8: is_null] [data...]
 *
 * QMetaType::Int = 2     -> [int32: value]
 * QMetaType::QByteArray = 12 -> [uint32: len] [bytes...]
 */
public class VeyonVncClient {

    private static final String TAG = "VeyonVncClient";

    // RFB constants
    private static final int SEC_TYPE_VEYON = 40;
    private static final int VEYON_AUTH_KEYFILE = 3;
    private static final int VEYON_AUTH_LOGON = 2;

    // RFB message types
    private static final int MSG_SET_PIXEL_FORMAT = 0;
    private static final int MSG_SET_ENCODINGS = 2;
    private static final int MSG_FRAMEBUFFER_UPDATE_REQUEST = 3;
    private static final int MSG_SERVER_FRAMEBUFFER_UPDATE = 0;
    private static final int MSG_SERVER_BELL = 2;
    private static final int MSG_SERVER_CUT_TEXT = 3;

    // Encodings
    private static final int ENC_RAW = 0;
    private static final int ENC_COPY_RECT = 1;
    private static final int ENC_ZRLE = 16;
    private static final int ENC_TIGHT = -260; // 0xFFFFFF0C
    private static final int ENC_TIGHT_PNG = -260;
    private static final int ENC_COMPRESS_LEVEL_0 = -256;
    private static final int ENC_QUALITY_LEVEL_0 = -512;

    // Qt QMetaType IDs
    private static final int QMT_INT = 2;
    private static final int QMT_BYTEARRAY = 12;

    private final String host;
    private final int port;
    private final String keyName;
    private final String privateKeyPem;
    private final SurfaceHolder surfaceHolder;
    private final Callback callback;

    private Socket socket;
    // Utilizziamo PushbackInputStream per poter rimettere byte nello stream se necessario
    private PushbackInputStream pbIn;
    private DataInputStream in;
    private DataOutputStream out;
    private AtomicBoolean running = new AtomicBoolean(false);
    private Thread connectionThread;

    private int fbWidth;
    private int fbHeight;
    private Bitmap framebuffer;
    private Inflater zrleInflater = new Inflater();
    private Inflater tightInflater = new Inflater();

    public interface Callback {
        void onConnected(int width, int height);
        void onDisconnected(String reason);
        void onError(String error);
        void onFpsUpdate(int fps);
    }

    public VeyonVncClient(String host, int port, String keyName,
                          String privateKeyPem, SurfaceHolder holder, Callback cb) {
        this.host = host;
        this.port = port;
        this.keyName = keyName;
        this.privateKeyPem = privateKeyPem;
        this.surfaceHolder = holder;
        this.callback = cb;
    }

    public void connect() {
        if (running.get()) return;
        running.set(true);
        connectionThread = new Thread(this::runConnection, "VeyonVNC");
        connectionThread.start();
    }

    public void disconnect() {
        running.set(false);
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        if (connectionThread != null) connectionThread.interrupt();
    }

    // ─── Main connection loop ──────────────────────────────────────────────────

    private void runConnection() {
        try {
            Log.d(TAG, "=== VeyonVNC starting connection ===");
            Log.d(TAG, "Connecting to " + host + ":" + port);
            socket = new Socket(host, port);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(10000);
            // Avvolgiamo in PushbackInputStream per poter rimettere byte
            pbIn = new PushbackInputStream(socket.getInputStream());
            in = new DataInputStream(pbIn);
            out = new DataOutputStream(socket.getOutputStream());

            // Step 1: RFB version handshake
            rfbHandshake();

            // Step 2: Security negotiation
            int secType = negotiateSecurity();
            if (secType != SEC_TYPE_VEYON) {
                throw new IOException("Server did not offer Veyon security type 40, got: " + secType);
            }

            // Step 3: Veyon authentication
            veyonAuthenticate();

            // Step 4: Read SecurityResult
            int secResult = in.readInt();
            if (secResult != 0) {
                int errLen = in.readInt();
                byte[] errBytes = new byte[errLen];
                in.readFully(errBytes);
                throw new IOException("Authentication failed: " + new String(errBytes));
            }
            Log.d(TAG, "Authentication successful");

            // Step 5: ClientInit (shared = 1)
            out.writeByte(1);
            out.flush();

            // Step 6: ServerInit
            fbWidth = in.readUnsignedShort();
            fbHeight = in.readUnsignedShort();
            // Skip pixel format (16 bytes)
            byte[] pixelFormat = new byte[16];
            in.readFully(pixelFormat);
            // Read server name
            int nameLen = in.readInt();
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            Log.d(TAG, "ServerInit: " + fbWidth + "x" + fbHeight + " name=" + new String(nameBytes));

            // Initialize framebuffer
            framebuffer = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888);
            callback.onConnected(fbWidth, fbHeight);

            // Step 7: Set pixel format (32bpp BGRA)
            sendSetPixelFormat();

            // Step 8: Set encodings
            sendSetEncodings();

            // Step 9: Request first framebuffer update
            sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, false);

            // Step 10: Main receive loop
            receiveLoop();

        } catch (Exception e) {
            Log.e(TAG, "Connection error: " + e.getMessage(), e);
            if (running.get()) {
                callback.onError(e.getMessage());
            }
        } finally {
            running.set(false);
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            callback.onDisconnected("Connection closed");
        }
    }

    // ─── RFB Handshake ─────────────────────────────────────────────────────────

    private void rfbHandshake() throws IOException {
        // Read server version (12 bytes: "RFB 003.008\n")
        byte[] serverVersion = new byte[12];
        in.readFully(serverVersion);
        String version = new String(serverVersion);
        Log.d(TAG, "Server version: " + version.trim());

        // Send client version — always use 3.8
        out.write("RFB 003.008\n".getBytes());
        out.flush();
    }

    // ─── Security Negotiation ──────────────────────────────────────────────────

    private int negotiateSecurity() throws IOException {
        int numTypes = in.readUnsignedByte();
        if (numTypes == 0) {
            int errLen = in.readInt();
            byte[] err = new byte[errLen];
            in.readFully(err);
            throw new IOException("Server error: " + new String(err));
        }

        int veyonType = -1;
        for (int i = 0; i < numTypes; i++) {
            int t = in.readUnsignedByte();
            Log.d(TAG, "Security type offered: " + t);
            if (t == SEC_TYPE_VEYON) veyonType = t;
        }

        if (veyonType == -1) {
            throw new IOException("Server does not support Veyon security type 40");
        }

        // Select Veyon security type
        out.writeByte(SEC_TYPE_VEYON);
        out.flush();
        return veyonType;
    }


    // ─── Veyon Authentication (versione migliorata con debug) ──────────────────

    private void veyonAuthenticate() throws Exception {
        int[] authTypes = receiveVariantIntArray();
        Log.d(TAG, "Available auth types raw: " + Arrays.toString(authTypes));

        int selectedType = -1;
        int[] keyFileCandidates = { 3, 2, 4, 0 };
        for (int candidate : keyFileCandidates) {
            for (int t : authTypes) {
                if (t == candidate) { selectedType = candidate; break; }
            }
            if (selectedType != -1) break;
        }

        if (selectedType == -1) {
            throw new IOException("No supported auth type found. Available: " + Arrays.toString(authTypes));
        }

        Log.d(TAG, "Selected auth type: " + selectedType);
        Log.d(TAG, "Tentativo di invio tipo autenticazione come intero grezzo: " + selectedType);
        out.writeInt(selectedType);
        out.flush();
        Log.d(TAG, "Auth type sent, waiting for response...");

        // Tentativo di leggere un singolo byte per vedere se arriva qualcosa
        int firstByte = -1;
        try {
            // Imposta un timeout breve per non bloccare all'infinito
            socket.setSoTimeout(5000);
            firstByte = in.read(); // Legge un byte (bloccante con timeout)
            Log.d(TAG, "First byte read: " + firstByte + " (hex: " + Integer.toHexString(firstByte) + ")");
        } catch (java.net.SocketTimeoutException e) {
            Log.e(TAG, "Timeout reading first byte after auth type");
            throw new IOException("Timeout waiting for server response");
        } catch (IOException e) {
            Log.e(TAG, "IOException reading first byte: " + e.getMessage(), e);
            throw e;
        } finally {
            socket.setSoTimeout(10000); // ripristina timeout
        }

        if (firstByte == -1) {
            Log.e(TAG, "Server closed connection (EOF) after auth type");
            throw new IOException("Server closed connection");
        }

        // RIMETTIAMO IL BYTE LETTO NELLO STREAM
        pbIn.unread(firstByte);

        // Ora possiamo procedere con la logica originale, ma abbiamo già consumato un byte.
        // Dobbiamo ricostruire l'header di 4 byte (mancano 3 byte)
        byte[] remainingHeader = new byte[3];
        int readRemaining = pbIn.read(remainingHeader);
        if (readRemaining != 3) {
            Log.e(TAG, "Failed to read remaining header bytes, got: " + readRemaining);
            throw new IOException("Incomplete header after auth type");
        }

        // Ricomponiamo i 4 byte originali
        byte[] fullHeader = new byte[4];
        fullHeader[0] = (byte) firstByte;
        System.arraycopy(remainingHeader, 0, fullHeader, 1, 3);
        Log.d(TAG, "Full 4-byte header: " + bytesToHex(fullHeader));

        // Rimettere i 4 byte nello stream per permettere le letture successive
        pbIn.unread(fullHeader);

        // Ora interpretiamo come intero
        int possibleResult = ByteBuffer.wrap(fullHeader).order(ByteOrder.BIG_ENDIAN).getInt();
        Log.d(TAG, "Interpreted as int: " + possibleResult + " (hex: " + Integer.toHexString(possibleResult) + ")");

        if (possibleResult == 0 && selectedType != 0) {
            // Potrebbe essere SecurityResult = 0
            Log.d(TAG, "Received 0, checking if more data available...");
            if (pbIn.available() > 0) {
                Log.d(TAG, "More data available, assuming challenge follows");
                // Procedi con la lettura della sfida normalmente (riutilizzando i metodi esistenti)
                byte[] challenge = receiveVariantByteArray();
                Log.d(TAG, "Challenge received, length: " + challenge.length);
                byte[] signature = signChallenge(challenge);
                sendVariantStringAndBytes(keyName, signature);
            } else {
                Log.d(TAG, "No more data, assuming authentication successful without challenge");
                return; // Autenticazione completata
            }
        } else {
            // Non è 0, quindi probabilmente è la dimensione di un messaggio
            byte[] challenge = receiveVariantByteArray();
            Log.d(TAG, "Challenge received, length: " + challenge.length);
            if (challenge.length == 0) {
                throw new IOException("Empty challenge received");
            }
            byte[] signature = signChallenge(challenge);
            sendVariantStringAndBytes(keyName, signature);
            Log.d(TAG, "Auth response sent");
        }
    }

    // ─── VariantArrayMessage I/O ───────────────────────────────────────────────

    /**
     * Reads a VariantArrayMessage containing integers.
     * Handles both Qt4 (no isNull) and Qt5 (with isNull) formats.
     */
    private int[] receiveVariantIntArray() throws IOException {
        int msgSize = in.readInt();
        Log.d(TAG, "receiveVariantIntArray: msgSize=" + msgSize);
        if (msgSize <= 0 || msgSize > 65536) {
            throw new IOException("Invalid VariantArrayMessage size: " + msgSize);
        }

        // Read exactly msgSize bytes into a buffer to avoid desync
        byte[] buf = new byte[msgSize];
        in.readFully(buf);
        java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(buf));

        int count = dis.readInt();
        Log.d(TAG, "receiveVariantIntArray: count=" + count);
        if (count < 0 || count > 256) throw new IOException("Invalid variant count: " + count);

        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int type = dis.readInt();
            Log.d(TAG, "  variant[" + i + "]: QMetaType=" + type);
            switch (type) {
                case 0:  // Invalid — no data
                    // In Qt5, still has isNull byte; in Qt4, nothing
                    // Try to skip isNull if there are enough bytes
                    try { dis.readUnsignedByte(); } catch (Exception e) { /* Qt4 compat */ }
                    result[i] = 0;
                    break;
                case 2:  // Int
                    dis.readUnsignedByte(); // isNull
                    result[i] = dis.readInt();
                    break;
                case 3:  // UInt
                    dis.readUnsignedByte();
                    result[i] = dis.readInt() & 0xFFFF;
                    break;
                default:
                    Log.w(TAG, "  Unknown QMetaType " + type + " for variant[" + i + "]");
                    dis.readUnsignedByte(); // try to read isNull
                    result[i] = 0;
                    break;
            }
            Log.d(TAG, "  variant[" + i + "]: value=" + result[i]);
        }
        return result;
    }

    /**
     * Sends a VariantArrayMessage containing integers.
     */
    private void sendVariantIntArray(int[] values) throws IOException {
        // Each int variant: 4 (type) + 1 (null) + 4 (value) = 9 bytes
        // List header: 4 bytes (count)
        // Total: 4 + count*9
        int listSize = 4 + values.length * 9;
        out.writeInt(listSize);       // message size
        out.writeInt(values.length);  // list count
        for (int v : values) {
            out.writeInt(QMT_INT);    // type = Int
            out.writeByte(0);         // not null
            out.writeInt(v);          // value
        }
        out.flush();
    }

    /**
     * Reads a VariantArrayMessage containing one QByteArray.
     */
    private byte[] receiveVariantByteArray() throws IOException {
        int msgSize = in.readInt();
        Log.d(TAG, "receiveVariantByteArray: msgSize=" + msgSize);
        if (msgSize <= 0 || msgSize > 65536) {
            throw new IOException("Invalid VariantArrayMessage size for bytearray: " + msgSize);
        }

        byte[] buf = new byte[msgSize];
        in.readFully(buf);
        java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(buf));

        int count = dis.readInt();
        Log.d(TAG, "receiveVariantByteArray: count=" + count);
        if (count == 0) return new byte[0];

        int type = dis.readInt();
        Log.d(TAG, "receiveVariantByteArray: type=" + type);
        int isNull = dis.readUnsignedByte();
        if (isNull != 0) {
            Log.w(TAG, "Challenge variant is null");
            return new byte[0];
        }
        int len = dis.readInt();
        Log.d(TAG, "receiveVariantByteArray: dataLen=" + len);
        if (len < 0) return new byte[0];
        byte[] data = new byte[len];
        dis.readFully(data);
        return data;
    }


    /**
     * Sends a VariantArrayMessage with [QString(keyName), QByteArray(signature)].
     * QString in QDataStream: [uint32: byteLen] [UTF-16BE bytes] (or -1 for null)
     */
    private void sendVariantStringAndBytes(String str, byte[] bytes) throws IOException {
        // Encode QString: UTF-16BE, length in bytes
        byte[] strUtf16 = str.getBytes("UTF-16BE");
        // QVariant<QString>: 4(type=10) + 1(null) + 4(byteLen) + strBytes
        // QVariant<QByteArray>: 4(type=12) + 1(null) + 4(len) + bytes
        int strVariantSize = 4 + 1 + 4 + strUtf16.length;
        int bytesVariantSize = 4 + 1 + 4 + bytes.length;
        int listSize = 4 + strVariantSize + bytesVariantSize; // 4 = count field

        out.writeInt(listSize);
        out.writeInt(2); // 2 items in list

        // Item 1: QString (type 10)
        out.writeInt(10);              // QMetaType::QString
        out.writeByte(0);              // not null
        out.writeInt(strUtf16.length); // byte length of UTF-16 string
        out.write(strUtf16);

        // Item 2: QByteArray (type 12)
        out.writeInt(QMT_BYTEARRAY);   // QMetaType::QByteArray
        out.writeByte(0);              // not null
        out.writeInt(bytes.length);
        out.write(bytes);

        out.flush();
    }

    // ─── RSA Signing ──────────────────────────────────────────────────────────

    private byte[] signChallenge(byte[] challenge) throws Exception {
        // Parse PEM private key (PKCS8)
        String pem = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.decode(pem, Base64.DEFAULT);
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(keyBytes);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = factory.generatePrivate(spec);

        // Prova con SHA256, ma Veyon potrebbe usare SHA1
        Signature sig;
        try {
            sig = Signature.getInstance("SHA256withRSA");
        } catch (Exception e) {
            Log.w(TAG, "SHA256withRSA not available, trying SHA1withRSA");
            sig = Signature.getInstance("SHA1withRSA");
        }
        sig.initSign(privateKey);
        sig.update(challenge);
        return sig.sign();
    }

    // ─── RFB Pixel Format & Encodings ─────────────────────────────────────────

    private void sendSetPixelFormat() throws IOException {
        out.writeByte(MSG_SET_PIXEL_FORMAT);
        out.writeByte(0); out.writeByte(0); out.writeByte(0); // padding
        // PixelFormat: bpp=32, depth=24, big-endian=0, true-colour=1
        // R: max=255 shift=16, G: max=255 shift=8, B: max=255 shift=0
        out.writeByte(32);  // bits-per-pixel
        out.writeByte(24);  // depth
        out.writeByte(0);   // big-endian-flag (little endian)
        out.writeByte(1);   // true-colour-flag
        out.writeShort(255); // red-max
        out.writeShort(255); // green-max
        out.writeShort(255); // blue-max
        out.writeByte(16);  // red-shift
        out.writeByte(8);   // green-shift
        out.writeByte(0);   // blue-shift
        out.writeByte(0); out.writeByte(0); out.writeByte(0); // padding
        out.flush();
    }

    private void sendSetEncodings() throws IOException {
        int[] encodings = { ENC_ZRLE, ENC_RAW };
        out.writeByte(MSG_SET_ENCODINGS);
        out.writeByte(0); // padding
        out.writeShort(encodings.length);
        for (int enc : encodings) {
            out.writeInt(enc);
        }
        out.flush();
    }

    private void sendFramebufferUpdateRequest(int x, int y, int w, int h, boolean incremental)
            throws IOException {
        out.writeByte(MSG_FRAMEBUFFER_UPDATE_REQUEST);
        out.writeByte(incremental ? 1 : 0);
        out.writeShort(x);
        out.writeShort(y);
        out.writeShort(w);
        out.writeShort(h);
        out.flush();
    }

    // ─── Main Receive Loop ────────────────────────────────────────────────────

    private void receiveLoop() throws IOException {
        int fpsCount = 0;
        long fpsTime = System.currentTimeMillis();
        socket.setSoTimeout(30000);

        while (running.get()) {
            int msgType = in.readUnsignedByte();
            switch (msgType) {
                case MSG_SERVER_FRAMEBUFFER_UPDATE:
                    in.readUnsignedByte(); // padding
                    int numRects = in.readUnsignedShort();
                    for (int i = 0; i < numRects; i++) {
                        processRect();
                    }
                    renderFrame();
                    fpsCount++;
                    sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                    break;

                case MSG_SERVER_BELL:
                    // ignore
                    break;

                case MSG_SERVER_CUT_TEXT:
                    in.readUnsignedByte(); in.readUnsignedByte(); in.readUnsignedByte(); // padding
                    int textLen = in.readInt();
                    byte[] text = new byte[textLen];
                    in.readFully(text);
                    break;

                default:
                    Log.w(TAG, "Unknown server message type: " + msgType);
                    break;
            }

            // FPS update every second
            long now = System.currentTimeMillis();
            if (now - fpsTime >= 1000) {
                callback.onFpsUpdate(fpsCount);
                fpsCount = 0;
                fpsTime = now;
            }
        }
    }

    // ─── Rectangle Processing ─────────────────────────────────────────────────

    private void processRect() throws IOException {
        int x = in.readUnsignedShort();
        int y = in.readUnsignedShort();
        int w = in.readUnsignedShort();
        int h = in.readUnsignedShort();
        int encoding = in.readInt();

        switch (encoding) {
            case ENC_RAW:
                processRawRect(x, y, w, h);
                break;
            case ENC_COPY_RECT:
                processCopyRect(x, y, w, h);
                break;
            case ENC_ZRLE:
                processZrleRect(x, y, w, h);
                break;
            default:
                Log.w(TAG, "Unknown encoding: " + encoding + " for rect " + w + "x" + h);
                // Skip — we can't decode unknown encodings
                break;
        }
    }

    private void processRawRect(int x, int y, int w, int h) throws IOException {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int r = in.readUnsignedByte();
            int g = in.readUnsignedByte();
            int b = in.readUnsignedByte();
            in.readUnsignedByte(); // padding byte
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (framebuffer != null) {
            synchronized (framebuffer) {
                framebuffer.setPixels(pixels, 0, w, x, y, w, h);
            }
        }
    }

    private void processCopyRect(int x, int y, int w, int h) throws IOException {
        int srcX = in.readUnsignedShort();
        int srcY = in.readUnsignedShort();
        if (framebuffer == null) return;
        synchronized (framebuffer) {
            int[] pixels = new int[w * h];
            framebuffer.getPixels(pixels, 0, w, srcX, srcY, w, h);
            framebuffer.setPixels(pixels, 0, w, x, y, w, h);
        }
    }

    private void processZrleRect(int x, int y, int w, int h) throws IOException {
        int dataLen = in.readInt();
        byte[] compressed = new byte[dataLen];
        in.readFully(compressed);

        tightInflater.reset();
        tightInflater.setInput(compressed);

        // ZRLE: tiles of 64x64 pixels
        int TILE = 64;
        for (int ty = y; ty < y + h; ty += TILE) {
            for (int tx = x; tx < x + w; tx += TILE) {
                int tw = Math.min(TILE, x + w - tx);
                int th = Math.min(TILE, y + h - ty);
                processZrleTile(tx, ty, tw, th);
            }
        }
    }

    private void processZrleTile(int x, int y, int w, int h) throws IOException {
        // Read subencoding byte from inflater
        byte[] subbuf = new byte[1];
        try { tightInflater.inflate(subbuf); } catch (Exception e) { return; }
        int subenc = subbuf[0] & 0xFF;

        int[] pixels = new int[w * h];

        if (subenc == 0) {
            // Raw tile
            byte[] raw = new byte[w * h * 4];
            try { tightInflater.inflate(raw); } catch (Exception e) { return; }
            for (int i = 0; i < pixels.length; i++) {
                int r = raw[i*4] & 0xFF;
                int g = raw[i*4+1] & 0xFF;
                int b = raw[i*4+2] & 0xFF;
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else if (subenc == 1) {
            // Solid tile
            byte[] color = new byte[3];
            try { tightInflater.inflate(color); } catch (Exception e) { return; }
            int c = 0xFF000000 | ((color[0]&0xFF)<<16) | ((color[1]&0xFF)<<8) | (color[2]&0xFF);
            java.util.Arrays.fill(pixels, c);
        }
        // Other ZRLE subtypes omitted for brevity — fallback to solid black

        if (framebuffer != null) {
            synchronized (framebuffer) {
                framebuffer.setPixels(pixels, 0, w, x, y, w, h);
            }
        }
    }

    // ─── Render ────────────────────────────────────────────────────────────────

    private void renderFrame() {
        if (framebuffer == null || surfaceHolder == null) return;
        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockCanvas();
            if (canvas == null) return;
            synchronized (framebuffer) {
                // Scale to fit surface
                Rect dst = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
                canvas.drawBitmap(framebuffer, null, dst, null);
            }
        } finally {
            if (canvas != null) {
                try { surfaceHolder.unlockCanvasAndPost(canvas); } catch (Exception ignored) {}
            }
        }
    }

    // Utility: bytes to hex
    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b));
        }
        return sb.toString();
    }
}