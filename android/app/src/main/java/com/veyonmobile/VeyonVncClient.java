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
    private static final int ENC_TIGHT = 7;
    private static final int ENC_ZRLE = 16;
    private static final int ENC_NEW_FB_SIZE = -223;
    private static final int ENC_LAST_RECT = -224;
    private static final int QMT_INT = 2;
    private static final int QMT_BYTEARRAY = 12;
    private static final int MSG_VEYON_FEATURE = 0x29;
    private static final String[] SIGNATURE_ALGORITHMS = new String[] {
            "SHA512withRSA",
            "SHA256withRSA",
            "SHA1withRSA",
            "NONEwithRSA"
    };
    private static final int RECEIVE_LOOP_TIMEOUT_MS = 200;
    private static final long KEEPALIVE_FRAMEBUFFER_MS = 200;
    private static final long KEEPALIVE_MONITORING_PING_MS = 1000; // Ridotto per sicurezza

    public static final String FEATURE_MONITORING_MODE   = "edad8259-b4ef-4ca5-90e6-f238d0fda694";
    public static final String FEATURE_QUERY_APP_VERSION = "58f5d5d5-9929-48f4-a995-f221c150ae26";
    public static final String FEATURE_QUERY_ACTIVE      = "a0a96fba-425d-414a-aaf4-352b76d7c4f3";
    public static final String FEATURE_USER_INFO         = "79a5e74d-50bd-4aab-8012-0e70dc08cc72";
    public static final String FEATURE_SESSION_INFO      = "699ed9dd-f58b-477b-a0af-df8105571b3c";
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
    private final Inflater zrleInflater = new Inflater();
    private final Inflater tightInflater = new Inflater();
    private volatile String protocolStep = "init";
    private volatile String activeSignatureAlgorithm = SIGNATURE_ALGORITHMS[0];
    private volatile boolean cleanRemoteClose = false;

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
                    boolean canRetry =
                            i < SIGNATURE_ALGORITHMS.length - 1
                                    && "security_result".equals(protocolStep)
                                    && hasEofInChain(attemptError);
                    Log.w(TAG, "Connection attempt failed (alg=" + activeSignatureAlgorithm
                            + ", step=" + protocolStep + "): " + buildDetailedErrorMessage(attemptError));
                    closeSocketQuietly();
                    if (canRetry) {
                        Log.w(TAG, "Retrying with next signature algorithm");
                        continue;
                    }
                    throw attemptError;
                }
            }
            if (lastError != null) throw lastError;

        } catch (Exception e) {
            if (running.get()) {
                boolean remoteCloseDuringReceiveLoop =
                        "receive_loop".equals(protocolStep) &&
                                (hasEofInChain(e) || hasSocketResetInChain(e));
                if (remoteCloseDuringReceiveLoop) {
                    cleanRemoteClose = true;
                    Log.i(TAG, "Server closed connection during receive loop");
                } else {
                    String detailed = buildDetailedErrorMessage(e);
                    Log.e(TAG, "Connection error: " + detailed, e);
                    callback.onError(detailed);
                }
            }
        } finally {
            running.set(false);
            closeSocketQuietly();
            callback.onDisconnected(cleanRemoteClose ? "Remote closed stream" : "Connection closed");
        }
    }

    private void runConnectionAttempt() throws Exception {
        Log.d(TAG, "=== VeyonVNC starting connection ===");
        Log.d(TAG, "Connecting to " + host + ":" + port + " using " + activeSignatureAlgorithm);
        socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 8000);
        socket.setTcpNoDelay(true);
        socket.setSoTimeout(10000);
        pbIn = new PushbackInputStream(socket.getInputStream());
        in = new DataInputStream(pbIn);
        out = new DataOutputStream(socket.getOutputStream());

        protocolStep = "rfb_handshake";
        rfbHandshake();

        protocolStep = "security_negotiate";
        int secType = negotiateSecurity();
        if (secType != SEC_TYPE_VEYON)
            throw new IOException("No Veyon security type, got: " + secType);

        protocolStep = "veyon_auth";
        veyonAuthenticate();

        protocolStep = "security_result";
        int secResult = in.readInt();
        if (secResult != 0) {
            int errLen = in.readInt();
            byte[] errBytes = new byte[errLen];
            in.readFully(errBytes);
            throw new IOException("Auth failed: " + new String(errBytes));
        }
        Log.d(TAG, "Authentication successful (" + activeSignatureAlgorithm + ")");

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
        Log.d(TAG, "ServerInit: " + fbWidth + "x" + fbHeight + " name=" + new String(nameBytes));

        framebuffer = Bitmap.createBitmap(fbWidth, fbHeight, Bitmap.Config.ARGB_8888);
        callback.onConnected(fbWidth, fbHeight);

        sendSetPixelFormat();
        sendSetEncodings();
        sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, false);

        // Attiva monitoring mode immediatamente
        sendFeatureMessage(FEATURE_MONITORING_MODE, true, null);
        // Richiedi metadati
        sendFeatureMessage(FEATURE_QUERY_APP_VERSION, true, null);
        sendFeatureMessage(FEATURE_QUERY_ACTIVE, true, null);
        sendFeatureMessage(FEATURE_USER_INFO, true, null);
        sendFeatureMessage(FEATURE_SESSION_INFO, true, null);
        Log.d(TAG, "VNC session established; initial monitoring feature set sent");

        protocolStep = "receive_loop";
        receiveLoop();
    }

    // ─── RFB Handshake ─────────────────────────────────────────────────────────

    private void rfbHandshake() throws IOException {
        byte[] serverVersion = new byte[12];
        int read = 0;
        while (read < serverVersion.length) {
            int n = in.read(serverVersion, read, serverVersion.length - read);
            if (n < 0) {
                String partial = read > 0
                        ? new String(Arrays.copyOf(serverVersion, read)).replace("\n", "\\n")
                        : "<empty>";
                throw new IOException(
                        "Server closed before RFB handshake (received " + read + "/12 bytes, data=" + partial + "). " +
                                "Likely wrong port/service or Veyon Service not accepting VNC."
                );
            }
            read += n;
        }
        String serverVersionStr = new String(serverVersion).trim();
        Log.d(TAG, "Server version: " + serverVersionStr);
        if (!serverVersionStr.startsWith("RFB")) {
            throw new IOException("Invalid RFB banner: " + serverVersionStr);
        }
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
        if (veyonType == -1) throw new IOException("No Veyon security type offered");
        out.writeByte(SEC_TYPE_VEYON);
        out.flush();
        return veyonType;
    }

    // ─── Veyon Authentication ──────────────────────────────────────────────────

    private void veyonAuthenticate() throws Exception {
        // 1. Ricevi tipi auth (server manda con count)
        int[] authTypes = receiveVariantIntArray();
        Log.d(TAG, "Available auth types: " + Arrays.toString(authTypes));

        int selectedType = -1;
        for (int candidate : new int[]{3, 2, 4}) {
            for (int t : authTypes) {
                if (t == candidate) { selectedType = candidate; break; }
            }
            if (selectedType != -1) break;
        }
        if (selectedType == -1)
            throw new IOException("No KeyFile auth. Available: " + Arrays.toString(authTypes));
        Log.d(TAG, "Selected auth type: " + selectedType);

        // 2. Manda [authType, username] SENZA count
        sendAuthTypeAndUsername(selectedType, "");
        Log.d(TAG, "Auth type + username sent");

        // 3-4. Alcuni server inviano prima ACK vuoto (msgSize=0), altri inviano subito challenge.
        byte[] challenge = receiveChallengeWithOptionalAck();
        Log.d(TAG, "Challenge: " + challenge.length + " bytes");
        if (challenge.length == 0) throw new IOException("Empty challenge");

        // 5. Firma con RSA
        byte[] signature = signChallenge(challenge);
        Log.d(TAG, "Signature: " + signature.length + " bytes");

        // 6. Manda [keyName, signature] SENZA count
        sendVariantStringAndBytes(keyName, signature);
        Log.d(TAG, "Auth response sent");
    }

    // ─── VariantArrayMessage I/O ───────────────────────────────────────────────

    /** Server → Client: [msgSize] [QVariant<Int>:count] [QVariant<Int>:item ...] */
    private int[] receiveVariantIntArray() throws IOException {
        int msgSize = in.readInt();
        Log.d(TAG, "receiveVariantIntArray: msgSize=" + msgSize);
        if (msgSize <= 0 || msgSize > 65536) throw new IOException("Invalid msgSize: " + msgSize);

        byte[] buf = new byte[msgSize];
        in.readFully(buf);
        java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(buf));

        // Leggi count come QVariant<Int>: [type=2][isNull][value]
        dis.readInt();           // QMetaType::Int
        dis.readUnsignedByte();  // isNull
        int count = dis.readInt();
        Log.d(TAG, "receiveVariantIntArray: count=" + count);

        int[] result = new int[count];
        for (int i = 0; i < count; i++) {
            int qtype = dis.readInt();
            dis.readUnsignedByte(); // isNull
            result[i] = dis.readInt();
            Log.d(TAG, "  variant[" + i + "]: type=" + qtype + " value=" + result[i]);
        }
        return result;
    }

    /** Server → Client: challenge con ACK opzionale prima del payload. */
    private byte[] receiveChallengeWithOptionalAck() throws IOException {
        int firstMsgSize = in.readInt();
        Log.d(TAG, "receiveChallengeWithOptionalAck: firstMsgSize=" + firstMsgSize);

        if (firstMsgSize < 0 || firstMsgSize > 65536) {
            throw new IOException("Invalid first message size: " + firstMsgSize);
        }

        int challengeMsgSize = firstMsgSize;
        if (firstMsgSize == 0) {
            Log.d(TAG, "ACK received (empty VariantArrayMessage)");
            challengeMsgSize = in.readInt();
            Log.d(TAG, "receiveChallengeWithOptionalAck: challengeMsgSize=" + challengeMsgSize);
        }

        if (challengeMsgSize <= 0 || challengeMsgSize > 65536) {
            throw new IOException("Invalid challenge msgSize: " + challengeMsgSize);
        }

        byte[] payload = new byte[challengeMsgSize];
        in.readFully(payload);
        return parseVariantByteArrayPayload(payload, challengeMsgSize);
    }

    private byte[] parseVariantByteArrayPayload(byte[] payload, int msgSize) throws IOException {
        java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(payload));

        // QVariant<ByteArray>: [type=12][isNull][len][data]
        int type = dis.readInt();
        int isNull = dis.readUnsignedByte();
        Log.d(TAG, "parseVariantByteArrayPayload: type=" + type + " isNull=" + isNull);
        if (type != QMT_BYTEARRAY) {
            throw new IOException("Expected QVariant<QByteArray>, got type=" + type);
        }
        if (isNull != 0) return new byte[0];

        int len = dis.readInt();
        Log.d(TAG, "parseVariantByteArrayPayload: len=" + len);
        if (len < 0 || len > msgSize - 9) {
            throw new IOException("Invalid QByteArray length: " + len + " (msgSize=" + msgSize + ")");
        }
        byte[] data = new byte[len];
        dis.readFully(data);
        return data;
    }

    private void sendAuthTypeAndUsername(int authType, String username) throws IOException {
        byte[] usernameUtf16 = username.getBytes("UTF-16BE");

        // Client -> Server (Veyon): [msgSize][QVariant<Int>][QVariant<QString>] (senza count)
        int payloadSize = 9 + (4 + 1 + 4 + usernameUtf16.length);

        out.writeInt(payloadSize);

        // QVariant<Int>
        out.writeInt(QMT_INT);
        out.writeByte(0);
        out.writeInt(authType);

        // QVariant<QString> (Qt writes UTF-16BE bytes preceded by a byte length)
        out.writeInt(10); // QMetaType::QString
        out.writeByte(0);
        out.writeInt(usernameUtf16.length);
        if (usernameUtf16.length > 0) out.write(usernameUtf16);
        out.flush();
    }

    private void sendVariantStringAndBytes(String str, byte[] bytes) throws IOException {
        byte[] strUtf16 = str.getBytes("UTF-16BE");
        // Client -> Server (Veyon): [msgSize][QVariant<QString>][QVariant<QByteArray>] (senza count)
        int payloadSize = (4 + 1 + 4 + strUtf16.length) + (4 + 1 + 4 + bytes.length);

        out.writeInt(payloadSize);

        out.writeInt(10); out.writeByte(0);
        out.writeInt(strUtf16.length);
        if (strUtf16.length > 0) out.write(strUtf16);

        out.writeInt(QMT_BYTEARRAY); out.writeByte(0);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    // ─── RSA Signing ──────────────────────────────────────────────────────────

    private byte[] signChallenge(byte[] challenge) throws Exception {
        String pem = privateKeyPem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s+", "");
        byte[] keyBytes = Base64.decode(pem, Base64.DEFAULT);
        KeyFactory factory = KeyFactory.getInstance("RSA");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
        Signature sig = Signature.getInstance(activeSignatureAlgorithm);
        sig.initSign(privateKey);
        sig.update(challenge);
        return sig.sign();
    }

    // ─── RFB Pixel Format & Encodings ─────────────────────────────────────────

    private void sendSetPixelFormat() throws IOException {
        out.writeByte(MSG_SET_PIXEL_FORMAT);
        out.writeByte(0); out.writeByte(0); out.writeByte(0);
        out.writeByte(32); out.writeByte(24);
        out.writeByte(0);  // little endian
        out.writeByte(1);  // true colour
        out.writeShort(255); out.writeShort(255); out.writeShort(255);
        out.writeByte(16); out.writeByte(8); out.writeByte(0);
        out.writeByte(0); out.writeByte(0); out.writeByte(0);
        out.flush();
    }

    private void sendSetEncodings() throws IOException {
        // ✅ CORRETTO: Ordine compatibile Veyon - TIGHT prima, ZRLE dopo
        int[] encodings = {
                ENC_TIGHT,       // 7 - preferito da Veyon
                ENC_ZRLE,        // 16
                ENC_HEXTILE,     // 5
                ENC_COPY_RECT,   // 1
                ENC_RRE,         // 2
                ENC_CORRE,       // 4
                ENC_RAW,         // 0 - fallback sempre ultimo
                ENC_NEW_FB_SIZE, // -223
                ENC_LAST_RECT    // -224
        };
        out.writeByte(MSG_SET_ENCODINGS);
        out.writeByte(0);
        out.writeShort(encodings.length);
        for (int enc : encodings) out.writeInt(enc);
        out.flush();
        Log.d(TAG, "Sent encodings: TIGHT, ZRLE, HEXTILE, COPY_RECT, RRE, CORRE, RAW + pseudo");
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
        int fpsCount = 0;
        long fpsTime = System.currentTimeMillis();
        long lastFramebufferRequestMs = System.currentTimeMillis();
        long lastMonitoringPingMs = System.currentTimeMillis();
        socket.setSoTimeout(RECEIVE_LOOP_TIMEOUT_MS);

        while (running.get()) {
            // Keep requesting updates proactively
            long nowPreRead = System.currentTimeMillis();
            if (nowPreRead - lastFramebufferRequestMs >= KEEPALIVE_FRAMEBUFFER_MS) {
                sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                lastFramebufferRequestMs = nowPreRead;
            }

            try {
                int msgType = in.readUnsignedByte();
                Log.d(TAG, "Server msg type: 0x" + Integer.toHexString(msgType)); // Debug
                
                switch (msgType) {
                    case MSG_SERVER_FRAMEBUFFER_UPDATE:
                        in.readUnsignedByte(); // padding
                        int numRects = in.readUnsignedShort();
                        Log.d(TAG, "Framebuffer update: " + numRects + " rectangles");
                        for (int i = 0; i < numRects; i++) processRect();
                        renderFrame();
                        fpsCount++;
                        sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                        lastFramebufferRequestMs = System.currentTimeMillis();
                        break;
                    case MSG_SERVER_BELL:
                        Log.d(TAG, "Bell received");
                        break;
                    case MSG_SERVER_CUT_TEXT:
                        in.readUnsignedByte(); in.readUnsignedByte(); in.readUnsignedByte();
                        int textLen = in.readInt();
                        byte[] text = new byte[textLen];
                        in.readFully(text);
                        Log.d(TAG, "Cut text: " + textLen + " bytes");
                        break;
                    case 0x29: // Veyon FeatureMessage dal server
                        int fmSize = in.readInt();
                        Log.d(TAG, "FeatureMessage received: " + fmSize + " bytes");
                        if (fmSize > 0 && fmSize < 65536) {
                            byte[] fmData = new byte[fmSize];
                            in.readFully(fmData);
                            logFeatureMessageSummary(fmData);
                        }
                        break;
                    default:
                        Log.w(TAG, "Unknown server message type: 0x" + Integer.toHexString(msgType));
                        break;
                }
            } catch (SocketTimeoutException timeout) {
                // Normale, continua il loop
            }

            long now = System.currentTimeMillis();
            if (now - lastFramebufferRequestMs >= KEEPALIVE_FRAMEBUFFER_MS) {
                sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                lastFramebufferRequestMs = now;
            }
            if (now - lastMonitoringPingMs >= KEEPALIVE_MONITORING_PING_MS) {
                try {
                    sendFeatureMessage(FEATURE_MONITORING_MODE, true, null);
                    // ✅ AGGIUNTO: Ping aggiuntivo per mantenere attiva la sessione
                    sendFeatureMessage(FEATURE_QUERY_ACTIVE, true, null);
                } catch (IOException e) {
                    Log.w(TAG, "Monitoring ping send failed: " + e.getMessage());
                }
                lastMonitoringPingMs = now;
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
        int encoding = in.readInt();
        Log.d(TAG, "Rect: " + x + "," + y + " " + w + "x" + h + " enc=" + encoding);
        
        switch (encoding) {
            case ENC_RAW:       processRawRect(x, y, w, h);  break;
            case ENC_COPY_RECT: processCopyRect(x, y, w, h); break;
            case ENC_ZRLE:      processZrleRect(x, y, w, h); break;
            case ENC_TIGHT:     processTightRect(x, y, w, h); break; // ✅ AGGIUNTO
            case ENC_HEXTILE:   processHexTileRect(x, y, w, h); break; // ✅ AGGIUNTO
            case ENC_NEW_FB_SIZE: 
                Log.d(TAG, "New framebuffer size: " + w + "x" + h);
                // Ricrea framebuffer se necessario
                break;
            case ENC_LAST_RECT:
                Log.d(TAG, "Last rect marker");
                break;
            default: 
                Log.w(TAG, "Unsupported encoding: " + encoding);
                // Salta i dati se possibile
                break;
        }
    }

    private void processRawRect(int x, int y, int w, int h) throws IOException {
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++) {
            int r = in.readUnsignedByte();
            int g = in.readUnsignedByte();
            int b = in.readUnsignedByte();
            in.readUnsignedByte(); // padding
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        if (framebuffer != null) {
            synchronized (framebuffer) {
                framebuffer.setPixels(pixels, 0, w, x, y, w, h);
            }
        }
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

    // ✅ CORRETTO: Usa zrleInflater invece di tightInflater
    private void processZrleRect(int x, int y, int w, int h) throws IOException {
        int dataLen = in.readInt();
        Log.d(TAG, "ZRLE rect: " + dataLen + " bytes compressed");
        byte[] compressed = new byte[dataLen];
        in.readFully(compressed);
        
        zrleInflater.reset();  // ✅ CORRETTO: usa zrleInflater
        zrleInflater.setInput(compressed);
        
        int TILE = 64;
        for (int ty = y; ty < y + h; ty += TILE) {
            for (int tx = x; tx < x + w; tx += TILE) {
                processZrleTile(tx, ty, Math.min(TILE, x + w - tx), Math.min(TILE, y + h - ty));
            }
        }
    }

    private void processZrleTile(int x, int y, int w, int h) throws IOException {
        byte[] subbuf = new byte[1];
        try { 
            int result = zrleInflater.inflate(subbuf);  // ✅ CORRETTO: usa zrleInflater
            if (result < 0) throw new IOException("Inflater error");
        } catch (Exception e) { 
            Log.e(TAG, "ZRLE tile inflate failed: " + e.getMessage());
            return; 
        }
        int subenc = subbuf[0] & 0xFF;
        int[] pixels = new int[w * h];
        
        if (subenc == 0) {
            // Raw
            byte[] raw = new byte[w * h * 4];
            try { 
                int total = 0;
                while (total < raw.length) {
                    int r = zrleInflater.inflate(raw, total, raw.length - total);
                    if (r <= 0) break;
                    total += r;
                }
            } catch (Exception e) { 
                Log.e(TAG, "ZRLE raw inflate failed: " + e.getMessage());
                return; 
            }
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = 0xFF000000
                        | ((raw[i*4]   & 0xFF) << 16)
                        | ((raw[i*4+1] & 0xFF) << 8)
                        |  (raw[i*4+2] & 0xFF);
            }
        } else if (subenc == 1) {
            // Solid color
            byte[] color = new byte[3];
            try { 
                zrleInflater.inflate(color); 
            } catch (Exception e) { 
                Log.e(TAG, "ZRLE color inflate failed: " + e.getMessage());
                return; 
            }
            int c = 0xFF000000
                    | ((color[0] & 0xFF) << 16)
                    | ((color[1] & 0xFF) << 8)
                    |  (color[2] & 0xFF);
            java.util.Arrays.fill(pixels, c);
        } else {
            Log.w(TAG, "ZRLE subencoding not implemented: " + subenc);
            return;
        }
        
        if (framebuffer != null) {
            synchronized (framebuffer) {
                framebuffer.setPixels(pixels, 0, w, x, y, w, h);
            }
        }
    }

    // ✅ AGGIUNTO: Supporto TIGHT encoding (semplificato)
    private void processTightRect(int x, int y, int w, int h) throws IOException {
        int compressionControl = in.readUnsignedByte();
        boolean fillBackground = (compressionControl & 0x80) != 0;
        boolean jpegCompression = (compressionControl & 0x90) == 0x90;
        
        if (jpegCompression) {
            // JPEG non supportato in questa implementazione base
            Log.w(TAG, "TIGHT JPEG not supported, skipping");
            return;
        }
        
        if (fillBackground) {
            // Solid fill
            byte[] color = new byte[3];
            in.readFully(color);
            int c = 0xFF000000
                    | ((color[0] & 0xFF) << 16)
                    | ((color[1] & 0xFF) << 8)
                    |  (color[2] & 0xFF);
            int[] pixels = new int[w * h];
            java.util.Arrays.fill(pixels, c);
            synchronized (framebuffer) {
                framebuffer.setPixels(pixels, 0, w, x, y, w, h);
            }
        } else {
            // Basic compression - leggi lunghezza e dati compressi
            int dataLen = readTightDataLength();
            if (dataLen > 0) {
                byte[] compressed = new byte[dataLen];
                in.readFully(compressed);
                // Decompressione TIGHT richiede implementazione completa
                Log.d(TAG, "TIGHT basic: " + dataLen + " bytes (simplified handling)");
            }
        }
    }

    private int readTightDataLength() throws IOException {
        int len = in.readUnsignedByte();
        if ((len & 0x80) == 0) return len;
        
        len = (len & 0x7F) | (in.readUnsignedByte() << 7);
        if ((len & 0x4000) == 0) return len & 0x3FFF;
        
        len = (len & 0x3FFF) | (in.readUnsignedByte() << 14);
        return len & 0x7FFFFF;
    }

    // ✅ AGGIUNTO: Supporto HEXTILE encoding (semplificato)
    private void processHexTileRect(int x, int y, int w, int h) throws IOException {
        int TILE = 16;
        for (int ty = y; ty < y + h; ty += TILE) {
            for (int tx = x; tx < x + w; tx += TILE) {
                int tileW = Math.min(TILE, x + w - tx);
                int tileH = Math.min(TILE, y + h - ty);
                processHexTile(tx, ty, tileW, tileH);
            }
        }
    }

    private void processHexTile(int x, int y, int w, int h) throws IOException {
        int subenc = in.readUnsignedByte();
        
        boolean raw = (subenc & 1) != 0;
        boolean backgroundSpecified = (subenc & 2) != 0;
        boolean foregroundSpecified = (subenc & 4) != 0;
        boolean anySubrects = (subenc & 8) != 0;
        boolean subrectsColored = (subenc & 16) != 0;
        
        // Per semplicità, gestisci solo raw in questa implementazione base
        if (raw) {
            int[] pixels = new int[w * h];
            for (int i = 0; i < pixels.length; i++) {
                int r = in.readUnsignedByte();
                int g = in.readUnsignedByte();
                int b = in.readUnsignedByte();
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            synchronized (framebuffer) {
                framebuffer.setPixels(pixels, 0, w, x, y, w, h);
            }
        } else {
            Log.d(TAG, "HEXTILE encoded tile (simplified handling)");
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
                canvas.drawBitmap(framebuffer, null,
                        new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
            }
        } finally {
            if (canvas != null) {
                try { surfaceHolder.unlockCanvasAndPost(canvas); } catch (Exception ignored) {}
            }
        }
    }

    // ─── Feature Messages ──────────────────────────────────────────────────────

    public synchronized void sendFeatureMessage(String featureUid, boolean active,
                                                java.util.Map<String, String> arguments)
            throws IOException {
        if (out == null) return;

        // Converti UUID string → 16 bytes binari Qt
        String stripped = featureUid.replace("-", "");
        long data1 = Long.parseLong(featureUid.substring(0, 8), 16);
        int  data2 = Integer.parseInt(featureUid.substring(9, 13), 16);
        int  data3 = Integer.parseInt(featureUid.substring(14, 18), 16);
        byte[] data4 = new byte[8];
        String d4hex = featureUid.substring(19, 23) + featureUid.substring(24);
        for (int i = 0; i < 8; i++) {
            data4[i] = (byte) Integer.parseInt(d4hex.substring(i * 2, i * 2 + 2), 16);
        }

        int payloadSize = 21 + 9 + 9;

        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) MSG_VEYON_FEATURE);
        buf.putInt(payloadSize);

        // QVariant<QUuid>
        buf.putInt(30);
        buf.put((byte) 0);
        buf.putInt((int) data1);
        buf.putShort((short) data2);
        buf.putShort((short) data3);
        buf.put(data4);

        // QVariant<Int>
        buf.putInt(QMT_INT);
        buf.put((byte) 0);
        buf.putInt(active ? 0 : 1);

        // QVariant<QVariantMap>
        buf.putInt(8);
        buf.put((byte) 0);
        buf.putInt(0);

        out.write(buf.array());
        out.flush();
        Log.d(TAG, "Sent feature: " + featureUid + " active=" + active);
    }

    // ─── Utility ───────────────────────────────────────────────────────────────

    private String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02X ", b));
        return sb.toString();
    }

    private String buildDetailedErrorMessage(Exception e) {
        String msg = e.getMessage();
        if (msg != null && !msg.trim().isEmpty()) return msg;
        return e.getClass().getSimpleName();
    }

    private boolean hasEofInChain(Throwable t) {
        while (t != null) {
            if (t instanceof java.io.EOFException) return true;
            t = t.getCause();
        }
        return false;
    }

    private void closeSocketQuietly() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
    }

    private boolean hasSocketResetInChain(Throwable t) {
        while (t != null) {
            if (t instanceof java.net.SocketException) {
                String msg = t.getMessage();
                if (msg != null && msg.toLowerCase().contains("reset")) return true;
            }
            t = t.getCause();
        }
        return false;
    }

    private void logFeatureMessageSummary(byte[] payload) {
        try {
            java.io.DataInputStream dis = new java.io.DataInputStream(
                    new java.io.ByteArrayInputStream(payload));

            int uidType = dis.readInt();
            int uidNull = dis.readUnsignedByte();
            if (uidType != 30 || uidNull != 0) {
                Log.d(TAG, "FeatureMessage payload unexpected UID variant: type=" + uidType + " null=" + uidNull);
                return;
            }

            int data1 = dis.readInt();
            int data2 = dis.readUnsignedShort();
            int data3 = dis.readUnsignedShort();
            byte[] data4 = new byte[8];
            dis.readFully(data4);
            String featureUid = String.format("%08x-%04x-%04x-%02x%02x-%02x%02x%02x%02x%02x%02x",
                    data1, data2, data3,
                    data4[0] & 0xFF, data4[1] & 0xFF,
                    data4[2] & 0xFF, data4[3] & 0xFF, data4[4] & 0xFF,
                    data4[5] & 0xFF, data4[6] & 0xFF, data4[7] & 0xFF);

            int cmdType = dis.readInt();
            int cmdNull = dis.readUnsignedByte();
            int cmd = dis.readInt();

            Log.d(TAG, "FeatureMessage summary: uid=" + featureUid +
                    " cmdType=" + cmdType + " cmdNull=" + cmdNull + " cmd=" + cmd);
        } catch (Exception ex) {
            Log.d(TAG, "FeatureMessage summary parse failed: " + ex.getMessage());
        }
    }
}
