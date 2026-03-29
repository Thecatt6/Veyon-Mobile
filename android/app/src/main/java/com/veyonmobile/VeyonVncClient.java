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
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.Inflater;

public class VeyonVncClient {

    private static final String TAG = "VeyonVncClient";

    private static final int SEC_TYPE_VEYON = 40;
    private static final int MSG_SET_PIXEL_FORMAT = 0;
    private static final int MSG_SET_ENCODINGS = 2;
    private static final int MSG_FRAMEBUFFER_UPDATE_REQUEST = 3;
    private static final int MSG_SERVER_FRAMEBUFFER_UPDATE = 0;
    private static final int MSG_SERVER_BELL = 2;
    private static final int MSG_SERVER_CUT_TEXT = 3;
    private static final int ENC_RAW = 0;
    private static final int ENC_COPY_RECT = 1;
    private static final int ENC_RRE = 2;
    private static final int ENC_CORRE = 4;
    private static final int ENC_HEXTILE = 5;
    private static final int ENC_ZLIB = 9;
    private static final int QMT_INT = 2;
    private static final int QMT_BYTEARRAY = 12;
    private static final int MSG_VEYON_FEATURE = 0x29;
    private static final long KEEPALIVE_MONITORING_PING_MS = 30000;

    private static final String[] SIGNATURE_ALGORITHMS = {
        "SHA512withRSA", "SHA256withRSA", "SHA1withRSA", "NONEwithRSA"
    };

    public static final String FEATURE_MONITORING_MODE   = "edad8259-b4ef-4ca5-90e6-f238d0fda694";
    public static final String FEATURE_QUERY_APP_VERSION = "58f5d5d5-9929-48f4-a995-f221c150ae26";
    public static final String FEATURE_QUERY_ACTIVE      = "a0a96fba-425d-414a-aaf4-352b76d7c4f3";
    public static final String FEATURE_USER_INFO         = "79a5e74d-50bd-4aab-8012-0e70dc08cc72";
    public static final String FEATURE_SESSION_INFO      = "699ed9dd-f58b-477b-a0af-df8105571b3c";
    public static final String FEATURE_QUERY_SCREENS     = "d5bbc486-7bc5-4c36-a9a8-1566c8b0091a";
    public static final String FEATURE_SCREEN_LOCK       = "ccb535a2-1d24-4cc1-a709-8b47d2b2ac79";
    public static final String FEATURE_INPUT_LOCK        = "e4a77879-e544-4fec-bc18-e534f33b934c";
    public static final String FEATURE_LOGOFF            = "7311d43d-ab53-439e-a03a-8cb25f7ed526";
    public static final String FEATURE_REBOOT            = "4f7d98f0-395a-4fff-b968-e49b8d0f748c";
    public static final String FEATURE_POWERDOWN         = "6f5a27a0-0e2f-496e-afcc-7aae62eede10";
    public static final String FEATURE_TEXT_MESSAGE      = "e75ae9c8-ac17-4d00-8f0d-019348346208";
    public static final String FEATURE_START_APP         = "da9ca56a-b2ad-4fff-8f8a-929b2927b442";
    public static final String FEATURE_OPEN_WEBSITE      = "8a11a75d-b3db-48b6-b9cb-f8422ddd5b0c";

    private final String host;
    private final int port;
    private final String keyName;
    private final String privateKeyPem;
    private final SurfaceHolder surfaceHolder;
    private final Callback callback;

    private Socket socket;
    private PushbackInputStream pbIn;
    private DataInputStream in;
    private DataOutputStream out;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread connectionThread;
    private int fbWidth;
    private int fbHeight;
    private Bitmap framebuffer;
    private volatile String protocolStep = "init";
    private volatile String activeSignatureAlgorithm = SIGNATURE_ALGORITHMS[0];
    private volatile boolean cleanRemoteClose = false;
    private int fpsCount = 0;
    // FIX #1: track whether we've gotten the initial full frame
    private volatile boolean fullFrameReceived = false;

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
            cleanRemoteClose = false;
            Exception lastError = null;
            for (int i = 0; i < SIGNATURE_ALGORITHMS.length; i++) {
                activeSignatureAlgorithm = SIGNATURE_ALGORITHMS[i];
                protocolStep = "connect";
                try {
                    runConnectionAttempt();
                    return;
                } catch (Exception attemptError) {
                    lastError = attemptError;
                    boolean canRetry = i < SIGNATURE_ALGORITHMS.length - 1
                            && "security_result".equals(protocolStep)
                            && hasEofInChain(attemptError);
                    Log.w(TAG, "Attempt failed (alg=" + activeSignatureAlgorithm + "): " + attemptError.getMessage());
                    closeSocketQuietly();
                    if (canRetry) continue;
                    throw attemptError;
                }
            }
            if (lastError != null) throw lastError;
        } catch (Exception e) {
            if (running.get()) {
                boolean remoteClose = "receive_loop".equals(protocolStep)
                        && (hasEofInChain(e) || hasSocketResetInChain(e));
                if (remoteClose) {
                    cleanRemoteClose = true;
                } else {
                    Log.e(TAG, "Connection error: " + e.getMessage(), e);
                    callback.onError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                }
            }
        } finally {
            running.set(false);
            closeSocketQuietly();
            callback.onDisconnected(cleanRemoteClose ? "Remote closed" : "Connection closed");
        }
    }

    private void runConnectionAttempt() throws Exception {
        Log.d(TAG, "Connecting to " + host + ":" + port + " alg=" + activeSignatureAlgorithm);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10000);
        pbIn = new PushbackInputStream(socket.getInputStream(), 16);
        in = new DataInputStream(pbIn);
        out = new DataOutputStream(socket.getOutputStream());

        protocolStep = "rfb_handshake";
        rfbHandshake();

        protocolStep = "security_negotiate";
        if (negotiateSecurity() != SEC_TYPE_VEYON)
            throw new IOException("No Veyon security type");

        protocolStep = "veyon_auth";
        veyonAuthenticate();

        protocolStep = "security_result";
        consumeOptionalPostAuthAck();
        int secResult = in.readInt();
        if (secResult != 0) {
            int errLen = in.readInt();
            byte[] errBytes = new byte[errLen];
            in.readFully(errBytes);
            throw new IOException("Auth failed: " + new String(errBytes));
        }
        Log.d(TAG, "Auth OK (" + activeSignatureAlgorithm + ")");

        protocolStep = "client_init";
        out.writeByte(1);
        out.flush();

        protocolStep = "server_init";
        fbWidth = in.readUnsignedShort();
        fbHeight = in.readUnsignedShort();
        byte[] pixelFormat = new byte[16];
        in.readFully(pixelFormat);
        int nameLen = in.readInt();
        byte[] nameBytes = new byte[nameLen];
        in.readFully(nameBytes);
        Log.d(TAG, "ServerInit: " + fbWidth + "x" + fbHeight + " " + new String(nameBytes));

        framebuffer = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888);
        fullFrameReceived = false;
        callback.onConnected(fbWidth, fbHeight);

        // FIX #2: pixel format — little-endian, red shift=0 means server sends [B,G,R,X]
        // We read byte0=B, byte1=G, byte2=R → fix in processRawRect: swap R and B
        sendSetPixelFormat();
        sendSetEncodings();
        sendFeatureMessage(FEATURE_MONITORING_MODE, true, null);
        out.flush();

        // FIX #2: richiedi un SOLO full update iniziale, senza incrementale duplicato
        sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, false);
        out.flush();

        protocolStep = "receive_loop";
        receiveLoop();
    }

    // ─── RFB Handshake ─────────────────────────────────────────────────────────

    private void rfbHandshake() throws IOException {
        byte[] ver = new byte[12];
        int read = 0;
        while (read < 12) {
            int n = in.read(ver, read, 12 - read);
            if (n < 0) throw new IOException("Server closed during handshake");
            read += n;
        }
        String vs = new String(ver).trim();
        Log.d(TAG, "Server version: " + vs);
        if (!vs.startsWith("RFB")) throw new IOException("Invalid RFB: " + vs);
        out.write("RFB 003.008\n".getBytes());
        out.flush();
    }

    private int negotiateSecurity() throws IOException {
        int n = in.readUnsignedByte();
        if (n == 0) {
            int el = in.readInt();
            byte[] eb = new byte[el];
            in.readFully(eb);
            throw new IOException("Server error: " + new String(eb));
        }
        int veyon = -1;
        for (int i = 0; i < n; i++) {
            int t = in.readUnsignedByte();
            Log.d(TAG, "Security type: " + t);
            if (t == SEC_TYPE_VEYON) veyon = t;
        }
        if (veyon == -1) throw new IOException("No Veyon security type");
        out.writeByte(SEC_TYPE_VEYON);
        out.flush();
        return veyon;
    }

    // ─── Veyon Authentication ──────────────────────────────────────────────────

    private void veyonAuthenticate() throws Exception {
        int[] authTypes = receiveVariantIntArray();
        Log.d(TAG, "Auth types: " + Arrays.toString(authTypes));
        int selected = -1;
        for (int candidate : new int[]{3, 2, 4})
            for (int t : authTypes)
                if (t == candidate) { selected = candidate; break; }
        if (selected == -1) throw new IOException("No KeyFile auth");
        Log.d(TAG, "Selected auth: " + selected);

        sendAuthTypeAndUsername(selected, "veyon-mobile");
        byte[] challenge = receiveChallengeWithOptionalAck();
        if (challenge.length == 0) throw new IOException("Empty challenge");
        Log.d(TAG, "Challenge: " + challenge.length + " bytes");

        byte[] signature = signChallenge(challenge);
        sendVariantStringAndBytes(keyName, signature);
        Log.d(TAG, "Auth response sent");
    }

    private void consumeOptionalPostAuthAck() throws IOException {
        if (pbIn.available() < 4) return;
        byte[] peek = new byte[4];
        int n = pbIn.read(peek);
        if (n < 4) { if (n > 0) pbIn.unread(peek, 0, n); return; }
        int msgSize = ((peek[0] & 0xFF) << 24) | ((peek[1] & 0xFF) << 16)
                    | ((peek[2] & 0xFF) << 8)  |  (peek[3] & 0xFF);
        if (msgSize != 0) pbIn.unread(peek, 0, 4);
        else Log.d(TAG, "Post-auth ACK consumed");
    }

    private int[] receiveVariantIntArray() throws IOException {
        int msgSize = in.readInt();
        if (msgSize <= 0 || msgSize > 65536) throw new IOException("Bad msgSize: " + msgSize);
        byte[] buf = new byte[msgSize];
        in.readFully(buf);
        java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(buf));
        d.readInt(); d.readUnsignedByte(); // count QVariant header
        int count = d.readInt();
        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            d.readInt(); d.readUnsignedByte();
            result[i] = d.readInt();
        }
        return result;
    }

    private byte[] receiveChallengeWithOptionalAck() throws IOException {
        int first = in.readInt();
        if (first < 0 || first > 65536) throw new IOException("Bad msg size: " + first);
        int challengeSize = (first == 0) ? in.readInt() : first;
        if (challengeSize <= 0 || challengeSize > 65536) throw new IOException("Bad challenge size: " + challengeSize);
        byte[] payload = new byte[challengeSize];
        in.readFully(payload);
        java.io.DataInputStream d = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload));
        int type = d.readInt();
        int isNull = d.readUnsignedByte();
        if (type != QMT_BYTEARRAY) throw new IOException("Expected ByteArray, got type=" + type);
        if (isNull != 0) return new byte[0];
        int len = d.readInt();
        byte[] data = new byte[len];
        d.readFully(data);
        return data;
    }

    private void sendAuthTypeAndUsername(int authType, String username) throws IOException {
        byte[] u = username.getBytes("UTF-16BE");
        int size = 9 + (4 + 1 + 4 + u.length);
        out.writeInt(size);
        out.writeInt(QMT_INT); out.writeByte(0); out.writeInt(authType);
        out.writeInt(10); out.writeByte(0); out.writeInt(u.length);
        if (u.length > 0) out.write(u);
        out.flush();
    }

    private void sendVariantStringAndBytes(String str, byte[] bytes) throws IOException {
        byte[] s = str.getBytes("UTF-16BE");
        int size = (4 + 1 + 4 + s.length) + (4 + 1 + 4 + bytes.length);
        out.writeInt(size);
        out.writeInt(10); out.writeByte(0); out.writeInt(s.length);
        if (s.length > 0) out.write(s);
        out.writeInt(QMT_BYTEARRAY); out.writeByte(0); out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    private byte[] signChallenge(byte[] challenge) throws Exception {
        String pem = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.decode(pem, Base64.DEFAULT);
        PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        Signature sig = Signature.getInstance(activeSignatureAlgorithm);
        sig.initSign(pk);
        sig.update(challenge);
        return sig.sign();
    }

    // ─── RFB Pixel Format & Encodings ─────────────────────────────────────────

    private void sendSetPixelFormat() throws IOException {
        // FIX #1 (colori): little-endian, red-shift=0, green-shift=8, blue-shift=16
        // Il server manda bytes nell'ordine [R, G, B, X] con questi shift in little-endian
        out.writeByte(MSG_SET_PIXEL_FORMAT);
        out.writeByte(0); out.writeByte(0); out.writeByte(0); // padding
        out.writeByte(32); // bpp
        out.writeByte(24); // depth
        out.writeByte(0);  // little-endian
        out.writeByte(1);  // true colour
        out.writeShort(255); out.writeShort(255); out.writeShort(255); // max R/G/B
        out.writeByte(0);  // red-shift   → R in byte 0
        out.writeByte(8);  // green-shift → G in byte 1
        out.writeByte(16); // blue-shift  → B in byte 2
        out.writeByte(0); out.writeByte(0); out.writeByte(0); // padding
        out.flush();
    }

    private void sendSetEncodings() throws IOException {
        int[] encodings = {
            ENC_HEXTILE,   // 5  - efficiente, supportato
            ENC_ZLIB,      // 9  - compresso
            ENC_RRE,       // 2
            ENC_CORRE,     // 4
            ENC_COPY_RECT, // 1
            ENC_RAW,       // 0  - fallback
            0xFFFFFF21,    // DesktopSize
        };
        out.writeByte(MSG_SET_ENCODINGS);
        out.writeByte(0);
        out.writeShort(encodings.length);
        for (int enc : encodings) out.writeInt(enc);
        out.flush();
    }

    private void sendFramebufferUpdateRequest(int x, int y, int w, int h, boolean incremental)
            throws IOException {
        out.writeByte(MSG_FRAMEBUFFER_UPDATE_REQUEST);
        out.writeByte(incremental ? 1 : 0);
        out.writeShort(x); out.writeShort(y);
        out.writeShort(w); out.writeShort(h);
        out.flush();
    }

    // ─── Receive Loop ─────────────────────────────────────────────────────────

    private void receiveLoop() throws IOException {
        long fpsTime = System.currentTimeMillis();
        long lastMonitoringPing = System.currentTimeMillis();
        // FIX #4 (FPS): timeout più lungo, no Log.d nel loop caldo
        socket.setSoTimeout(5000);

        while (running.get()) {
            try {
                int msgType = in.readUnsignedByte();

                switch (msgType) {
                    case MSG_SERVER_FRAMEBUFFER_UPDATE: {
                        in.readUnsignedByte(); // padding
                        int numRects = in.readUnsignedShort();
                        if (numRects > 1000) throw new IOException("Invalid numRects: " + numRects);
                        for (int i = 0; i < numRects; i++) processRect();
                        renderFrame();
                        fpsCount++;
                        // FIX #2 (schermo iniziale): dopo il primo full frame, passa a incrementale
                        if (!fullFrameReceived) {
                            fullFrameReceived = true;
                            Log.d(TAG, "Full frame received, switching to incremental");
                        }
                        sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                        break;
                    }
                    case MSG_SERVER_BELL:
                        break;
                    case MSG_SERVER_CUT_TEXT: {
                        in.readUnsignedByte(); in.readUnsignedByte(); in.readUnsignedByte();
                        int tl = in.readInt();
                        byte[] tb = new byte[tl];
                        in.readFully(tb);
                        break;
                    }
                    case MSG_VEYON_FEATURE: {
                        int fmSize = in.readInt();
                        if (fmSize > 0 && fmSize < 65536) {
                            byte[] fmData = new byte[fmSize];
                            in.readFully(fmData);
                            handleFeatureMessage(fmData);
                        }
                        break;
                    }
                    default:
                        Log.w(TAG, "Unknown msg: 0x" + Integer.toHexString(msgType));
                        break;
                }

            } catch (SocketTimeoutException timeout) {
                // keepalive
                sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
            }

            if (Thread.interrupted()) break;

            long now = System.currentTimeMillis();
            if (now - lastMonitoringPing >= KEEPALIVE_MONITORING_PING_MS) {
                try { sendFeatureMessage(FEATURE_MONITORING_MODE, true, null); } catch (IOException ignored) {}
                lastMonitoringPing = now;
            }
            if (now - fpsTime >= 1000) {
                callback.onFpsUpdate(fpsCount);
                fpsCount = 0;
                fpsTime = now;
            }
        }
    }

    // ─── Rectangle Processing ─────────────────────────────────────────────────

    private void processRect() throws IOException {
        int x = in.readUnsignedShort(), y = in.readUnsignedShort();
        int w = in.readUnsignedShort(), h = in.readUnsignedShort();
        int enc = in.readInt();

        // Pseudo-encodings senza payload
        if (enc == -223 || enc == -224) return; // DesktopSize / LastRect
        if (enc == -232) return; // PointerPos
        if (enc < 0) {
            // Pseudo-encoding sconosciuto — non ha payload dati standard
            Log.w(TAG, "Unknown pseudo-enc: " + enc);
            return;
        }

        if (w == 0 || h == 0) return;

        switch (enc) {
            case ENC_RAW:       processRawRect(x, y, w, h);     break;
            case ENC_COPY_RECT: processCopyRect(x, y, w, h);    break;
            case ENC_RRE:       processRreRect(x, y, w, h);     break;
            case ENC_CORRE:     processCorreRect(x, y, w, h);   break;
            case ENC_HEXTILE:   processHexTileRect(x, y, w, h); break;
            case ENC_ZLIB:      processZlibRect(x, y, w, h);    break;
            default:
                Log.w(TAG, "Unsupported enc: " + enc);
                throw new IOException("Unsupported encoding: " + enc);
        }
    }

    // FIX #1 (colori): con red-shift=0, i bytes arrivano [R, G, B, X]
    // quindi leggiamo byte0=R, byte1=G, byte2=B correttamente
    private void processRawRect(int x, int y, int w, int h) throws IOException {
        int n = w * h;
        byte[] data = new byte[n * 4];
        in.readFully(data);
        int[] pixels = new int[n];
        for (int i = 0; i < n; i++) {
            int o = i * 4;
            pixels[i] = 0xFF000000
                    | ((data[o]   & 0xFF) << 16)  // R
                    | ((data[o+1] & 0xFF) << 8)   // G
                    |  (data[o+2] & 0xFF);         // B
        }
        if (framebuffer != null)
            synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    private void processCopyRect(int x, int y, int w, int h) throws IOException {
        int srcX = in.readUnsignedShort(), srcY = in.readUnsignedShort();
        if (framebuffer == null) return;
        synchronized (framebuffer) {
            int[] pixels = new int[w * h];
            framebuffer.getPixels(pixels, 0, w, srcX, srcY, w, h);
            framebuffer.setPixels(pixels, 0, w, x, y, w, h);
        }
    }

    // Legge 4 bytes pixel (R,G,B,X) con red-shift=0
    private int readPixel() throws IOException {
        int r = in.readUnsignedByte();
        int g = in.readUnsignedByte();
        int b = in.readUnsignedByte();
        in.readUnsignedByte(); // padding
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private void processRreRect(int x, int y, int w, int h) throws IOException {
        int n = in.readInt();
        int bg = readPixel();
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, bg);
        for (int i = 0; i < n; i++) {
            int fg = readPixel();
            int sx = in.readUnsignedShort(), sy = in.readUnsignedShort();
            int sw = in.readUnsignedShort(), sh = in.readUnsignedShort();
            for (int row = sy; row < sy + sh && row < h; row++)
                for (int col = sx; col < sx + sw && col < w; col++)
                    pixels[row * w + col] = fg;
        }
        if (framebuffer != null)
            synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    private void processCorreRect(int x, int y, int w, int h) throws IOException {
        int n = in.readInt();
        int bg = readPixel();
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, bg);
        for (int i = 0; i < n; i++) {
            int fg = readPixel();
            int sx = in.readUnsignedByte(), sy = in.readUnsignedByte();
            int sw = in.readUnsignedByte(), sh = in.readUnsignedByte();
            for (int row = sy; row < sy + sh && row < h; row++)
                for (int col = sx; col < sx + sw && col < w; col++)
                    pixels[row * w + col] = fg;
        }
        if (framebuffer != null)
            synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    private void processHexTileRect(int x, int y, int w, int h) throws IOException {
        int TILE = 16;
        for (int ty = 0; ty < h; ty += TILE) {
            for (int tx = 0; tx < w; tx += TILE) {
                int tw = Math.min(TILE, w - tx);
                int th = Math.min(TILE, h - ty);
                processHexTile(x + tx, y + ty, tw, th);
            }
        }
    }

    private int hxBg = 0xFF000000, hxFg = 0xFFFFFFFF;

    private void processHexTile(int x, int y, int w, int h) throws IOException {
        int sub = in.readUnsignedByte();
        int[] pixels = new int[w * h];
        if ((sub & 1) != 0) {
            // Raw
            byte[] data = new byte[w * h * 4];
            in.readFully(data);
            for (int i = 0; i < w * h; i++) {
                int o = i * 4;
                pixels[i] = 0xFF000000
                        | ((data[o]   & 0xFF) << 16)
                        | ((data[o+1] & 0xFF) << 8)
                        |  (data[o+2] & 0xFF);
            }
        } else {
            if ((sub & 2) != 0) hxBg = readPixel();
            if ((sub & 4) != 0) hxFg = readPixel();
            java.util.Arrays.fill(pixels, hxBg);
            if ((sub & 8) != 0) {
                int ns = in.readUnsignedByte();
                boolean colored = (sub & 16) != 0;
                for (int i = 0; i < ns; i++) {
                    int color = colored ? readPixel() : hxFg;
                    int xy = in.readUnsignedByte(), wh = in.readUnsignedByte();
                    int sx = (xy >> 4) & 0xF, sy = xy & 0xF;
                    int sw = ((wh >> 4) & 0xF) + 1, sh = (wh & 0xF) + 1;
                    for (int row = sy; row < sy + sh && row < h; row++)
                        for (int col = sx; col < sx + sw && col < w; col++)
                            pixels[row * w + col] = color;
                }
            }
        }
        if (framebuffer != null)
            synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    private final Inflater zlibInflater = new Inflater();

    private void processZlibRect(int x, int y, int w, int h) throws IOException {
        int dataLen = in.readInt();
        byte[] compressed = new byte[dataLen];
        in.readFully(compressed);
        zlibInflater.setInput(compressed);
        byte[] raw = new byte[w * h * 4];
        try {
            int total = 0;
            while (total < raw.length) {
                int r = zlibInflater.inflate(raw, total, raw.length - total);
                if (r <= 0) break;
                total += r;
            }
        } catch (Exception e) { throw new IOException("ZLIB: " + e.getMessage()); }
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++)
            pixels[i] = 0xFF000000
                    | ((raw[i*4]   & 0xFF) << 16)
                    | ((raw[i*4+1] & 0xFF) << 8)
                    |  (raw[i*4+2] & 0xFF);
        if (framebuffer != null)
            synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    // ─── Render ────────────────────────────────────────────────────────────────

    private void renderFrame() {
        if (framebuffer == null || surfaceHolder == null) return;
        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockCanvas();
            if (canvas == null) return;
            synchronized (framebuffer) {
                canvas.drawBitmap(framebuffer, null,
                        new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
            }
        } finally {
            if (canvas != null)
                try { surfaceHolder.unlockCanvasAndPost(canvas); } catch (Exception ignored) {}
        }
    }

    // ─── Feature Messages ──────────────────────────────────────────────────────

    private void handleFeatureMessage(byte[] fmData) {
        try {
            java.io.DataInputStream d = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(fmData));
            d.readInt(); d.readUnsignedByte(); // UUID type + isNull
            int data1 = d.readInt();
            int data2 = d.readUnsignedShort();
            int data3 = d.readUnsignedShort();
            byte[] data4 = new byte[8];
            d.readFully(data4);
            String uid = String.format("%08x-%04x-%04x-%02x%02x%02x%02x%02x%02x%02x%02x",
                    data1, data2, data3,
                    data4[0], data4[1], data4[2], data4[3],
                    data4[4], data4[5], data4[6], data4[7]);
            d.readInt(); d.readUnsignedByte();
            int cmd = d.readInt();
            Log.d(TAG, "FeatureMsg: " + uid + " cmd=" + cmd);
            // Rispondi solo al MonitoringMode per mantenersi connesso
            if (FEATURE_MONITORING_MODE.equals(uid)) {
                sendSimpleFeatureReply(uid, 0);
            }
        } catch (Exception e) {
            Log.w(TAG, "handleFeatureMessage: " + e.getMessage());
        }
    }

    // FIX #3: sendFeatureMessage pubblico — chiamabile da VeyonVncModule
    public synchronized void sendFeatureMessage(String featureUid, boolean active,
                                                java.util.Map<String, String> arguments)
            throws IOException {
        if (out == null) return;
        byte[] uuidBytes = uuidToBytes(featureUid);
        int payloadSize = 21 + 9 + 9; // UUID + Command + EmptyMap
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) MSG_VEYON_FEATURE);
        buf.putInt(payloadSize);
        // QVariant<QUuid> type=30
        buf.putInt(30); buf.put((byte) 0); buf.put(uuidBytes);
        // QVariant<Int> command: 0=Start, 1=Stop, 2=Trigger
        buf.putInt(QMT_INT); buf.put((byte) 0); buf.putInt(active ? 0 : 1);
        // QVariant<Map> empty
        buf.putInt(8); buf.put((byte) 0); buf.putInt(0);
        out.write(buf.array());
        out.flush();
        Log.d(TAG, "sendFeatureMessage: " + featureUid + " active=" + active);
    }

    // sendFeatureMessage con argomenti stringa (per TextMessage, StartApp, OpenWebsite)
    public synchronized void sendFeatureMessageWithArgs(String featureUid, boolean active,
                                                        java.util.Map<String, String> args)
            throws IOException {
        if (out == null) return;
        byte[] uuidBytes = uuidToBytes(featureUid);

        // Calcola dimensione mappa argomenti
        int mapDataSize = 4; // count
        if (args != null) {
            for (java.util.Map.Entry<String, String> e : args.entrySet()) {
                byte[] k = e.getKey().getBytes("UTF-16BE");
                byte[] v = e.getValue() != null ? e.getValue().getBytes("UTF-16BE") : new byte[0];
                mapDataSize += (4+1+4+k.length) + (4+1+4+v.length);
            }
        }

        int payloadSize = 21 + 9 + (4 + 1 + mapDataSize);
        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) MSG_VEYON_FEATURE);
        buf.putInt(payloadSize);
        buf.putInt(30); buf.put((byte) 0); buf.put(uuidBytes);
        buf.putInt(QMT_INT); buf.put((byte) 0); buf.putInt(0); // 0=Default/Trigger per features con args
        buf.putInt(8); buf.put((byte) 0);
        buf.putInt(args != null ? args.size() : 0);
        if (args != null) {
            for (java.util.Map.Entry<String, String> e : args.entrySet()) {
                byte[] k = e.getKey().getBytes("UTF-16BE");
                byte[] v = e.getValue() != null ? e.getValue().getBytes("UTF-16BE") : new byte[0];
                buf.putInt(10); buf.put((byte) 0); buf.putInt(k.length); buf.put(k);
                buf.putInt(10); buf.put((byte) 0); buf.putInt(v.length); buf.put(v);
            }
        }
        out.write(buf.array());
        out.flush();
    }

    private void sendSimpleFeatureReply(String featureUid, int command) throws IOException {
        if (out == null) return;
        byte[] uuidBytes = uuidToBytes(featureUid);
        int payloadSize = 21 + 9 + 9;
        out.writeByte(MSG_VEYON_FEATURE);
        ByteBuffer buf = ByteBuffer.allocate(4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.putInt(payloadSize);
        buf.putInt(30); buf.put((byte) 0); buf.put(uuidBytes);
        buf.putInt(QMT_INT); buf.put((byte) 0); buf.putInt(command);
        buf.putInt(8); buf.put((byte) 0); buf.putInt(0);
        out.write(buf.array());
        out.flush();
    }

    private byte[] uuidToBytes(String uid) {
        // RFC 4122 binary: data1(4) data2(2) data3(2) data4(8)
        long data1 = Long.parseLong(uid.substring(0, 8), 16);
        int  data2 = Integer.parseInt(uid.substring(9, 13), 16);
        int  data3 = Integer.parseInt(uid.substring(14, 18), 16);
        byte[] data4 = new byte[8];
        String d4 = uid.substring(19, 23) + uid.substring(24);
        for (int i = 0; i < 8; i++)
            data4[i] = (byte) Integer.parseInt(d4.substring(i*2, i*2+2), 16);
        ByteBuffer b = ByteBuffer.allocate(16);
        b.order(ByteOrder.BIG_ENDIAN);
        b.putInt((int) data1);
        b.putShort((short) data2);
        b.putShort((short) data3);
        b.put(data4);
        return b.array();
    }

    // ─── Utility ───────────────────────────────────────────────────────────────

    private boolean hasEofInChain(Throwable t) {
        while (t != null) { if (t instanceof java.io.EOFException) return true; t = t.getCause(); }
        return false;
    }

    private boolean hasSocketResetInChain(Throwable t) {
        while (t != null) {
            if (t instanceof java.net.SocketException) {
                String m = t.getMessage();
                if (m != null && m.toLowerCase().contains("reset")) return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void closeSocketQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    /**
     * Connessione VNC headless (senza SurfaceHolder) solo per mandare una feature.
     * Usato dal broadcast della HomeScreen.
     */
    public static void sendFeatureDirect(String host, int port, String keyName,
                                          String privateKeyPem, String featureUid,
                                          boolean active, java.util.Map<String, String> args)
            throws Exception {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 5000);
        s.setTcpNoDelay(true);
        s.setSoTimeout(8000);
        DataInputStream din = new DataInputStream(new PushbackInputStream(s.getInputStream(), 16));
        DataOutputStream dout = new DataOutputStream(s.getOutputStream());

        try {
            // Handshake
            byte[] ver = new byte[12];
            din.readFully(ver);
            dout.write("RFB 003.008\n".getBytes());
            dout.flush();

            // Security
            int n = din.readUnsignedByte();
            if (n == 0) throw new IOException("Server error");
            int veyon = -1;
            for (int i = 0; i < n; i++) {
                int t = din.readUnsignedByte();
                if (t == 40) veyon = t;
            }
            if (veyon == -1) throw new IOException("No Veyon security");
            dout.writeByte(40); dout.flush();

            // Auth types
            int msgSize = din.readInt();
            byte[] buf = new byte[msgSize];
            din.readFully(buf);

            // Send authType + username
            byte[] uBytes = "veyon-mobile".getBytes("UTF-16BE");
            int pSize = 9 + (4 + 1 + 4 + uBytes.length);
            dout.writeInt(pSize);
            dout.writeInt(2); dout.writeByte(0); dout.writeInt(3);
            dout.writeInt(10); dout.writeByte(0); dout.writeInt(uBytes.length); dout.write(uBytes);
            dout.flush();

            // ACK opzionale
            int first = din.readInt();
            int challengeSize = (first == 0) ? din.readInt() : first;
            byte[] payload = new byte[challengeSize];
            din.readFully(payload);

            // Parse challenge
            java.io.DataInputStream pd = new java.io.DataInputStream(new java.io.ByteArrayInputStream(payload));
            pd.readInt(); pd.readUnsignedByte();
            int len = pd.readInt();
            byte[] challenge = new byte[len];
            pd.readFully(challenge);

            // Sign
            String pem = privateKeyPem
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", "");
            byte[] keyBytes = Base64.decode(pem, Base64.DEFAULT);
            PrivateKey pk = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            Signature sig = Signature.getInstance("SHA512withRSA");
            sig.initSign(pk);
            sig.update(challenge);
            byte[] signature = sig.sign();

            // Send keyName + signature
            byte[] kBytes = keyName.getBytes("UTF-16BE");
            int sz = (4+1+4+kBytes.length) + (4+1+4+signature.length);
            dout.writeInt(sz);
            dout.writeInt(10); dout.writeByte(0); dout.writeInt(kBytes.length); dout.write(kBytes);
            dout.writeInt(12); dout.writeByte(0); dout.writeInt(signature.length); dout.write(signature);
            dout.flush();

            // Security result
            int result = din.readInt();
            if (result != 0) throw new IOException("Auth failed");

            // ClientInit
            dout.writeByte(1); dout.flush();

            // ServerInit — leggi e scarta
            din.readUnsignedShort(); din.readUnsignedShort(); // w, h
            din.readFully(new byte[16]); // pixel format
            int nameLen = din.readInt();
            din.readFully(new byte[nameLen]);

            // SetPixelFormat + SetEncodings + MonitoringMode
            dout.writeByte(0); dout.writeByte(0); dout.writeByte(0); dout.writeByte(0);
            dout.writeByte(32); dout.writeByte(24); dout.writeByte(0); dout.writeByte(1);
            dout.writeShort(255); dout.writeShort(255); dout.writeShort(255);
            dout.writeByte(0); dout.writeByte(8); dout.writeByte(16);
            dout.writeByte(0); dout.writeByte(0); dout.writeByte(0);
            dout.flush();

            // Manda la feature
            VeyonVncClient tmp = new VeyonVncClient(host, port, keyName, privateKeyPem, null, null);
            tmp.out = dout;
            if (args != null && !args.isEmpty()) {
                tmp.sendFeatureMessageWithArgs(featureUid, active, args);
            } else {
                tmp.sendFeatureMessage(featureUid, active, null);
            }

            // Aspetta un momento per assicurarsi che il server riceva
            Thread.sleep(300);

        } finally {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}