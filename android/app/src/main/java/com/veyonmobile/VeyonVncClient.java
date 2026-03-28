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

    // Byte counter for debugging
    private static class ByteCounter extends java.io.FilterInputStream {
        long count = 0;
        ByteCounter(java.io.InputStream in) { super(in); }
        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) count++;
            return b;
        }
        @Override
        public int read(byte[] b) throws IOException {
            int n = super.read(b);
            if (n > 0) count += n;
            return n;
        }
        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) count += n;
            return n;
        }
        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(n);
            count += skipped;
            return skipped;
        }
        void resetCount() { count = 0; }
        long getCount() { return count; }
    }

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
    private static final int ENC_ZLIBHEX = 6;
    private static final int ENC_ZLIB = 9;
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
    private static final int RECEIVE_LOOP_TIMEOUT_MS = 1000;
    private static final long KEEPALIVE_FRAMEBUFFER_MS = 2000;
    private static final long KEEPALIVE_MONITORING_PING_MS = 30000;

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
    private ByteCounter byteCounter;
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
    private volatile boolean initialFrameReceived = false;
    private volatile boolean initialFeaturesSent = false;
    private volatile boolean firstFramebufferReceived = false;
    private int fpsCount = 0; // Frame counter for FPS display
    private byte[] skipBuffer = new byte[65536]; // For skipping large data blocks

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
        Log.d(TAG, "VeyonVncClient created: host=" + host + ":" + port + ", keyName=" + keyName +
              ", keyLen=" + (privateKeyPem != null ? privateKeyPem.length() : 0));
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
                    Log.i(TAG, "Server closed connection during receive loop", e);
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
        socket.setSoTimeout(10000); // 10 second timeout
        byteCounter = new ByteCounter(socket.getInputStream());
        pbIn = new PushbackInputStream(byteCounter, 16);
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
        // Check for optional ACK after signature before reading security result
        consumeOptionalPostAuthAck();
        
        // Read the security result (0=success, non-zero=failure with error message)
        Log.d(TAG, "Reading security result, available bytes: " + pbIn.available());
        int secResult = in.readInt();
        Log.d(TAG, "Security result: 0x" + Integer.toHexString(secResult) + " (0=success)");
        if (secResult != 0) {
            int errLen = in.readInt();
            byte[] errBytes = new byte[errLen];
            in.readFully(errBytes);
            throw new IOException("Auth failed: " + new String(errBytes));
        }
        Log.d(TAG, "Authentication successful (" + activeSignatureAlgorithm + ")");

        protocolStep = "client_init";
        // Send ClientInit: 1 byte shared-desktop-flag (1 = shared mode)
        out.writeByte(1);
        out.flush();
        Log.d(TAG, "ClientInit sent (shared=1), output buffer flushed");
        
        // Small delay to ensure ClientInit reaches server before we read
        try { Thread.sleep(50); } catch (InterruptedException e) {}

        protocolStep = "server_init";
        Log.d(TAG, "Reading ServerInit, available bytes: " + pbIn.available());
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
        out.flush();

        // Send ONLY monitoring mode feature message
        // This tells the server we want to RECEIVE frames (monitoring mode)
        sendFeatureMessage(FEATURE_MONITORING_MODE, true, null);
        out.flush();
        
        // Wait for server to process and activate monitoring
        try { Thread.sleep(500); } catch (InterruptedException e) {}

        // NOW send FBU requests to start receiving frames
        for (int i = 0; i < 3; i++) {
            sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, false);
            out.flush();
            try { Thread.sleep(50); } catch (InterruptedException e) {}
        }

        Log.d(TAG, "VNC session established; waiting for frames");

        protocolStep = "receive_loop";
        Log.d(TAG, "Entering receive loop, available bytes: " + pbIn.available());
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
        sendAuthTypeAndUsername(selectedType, "veyon-mobile");
        Log.d(TAG, "Auth type + username sent");

        // 3-4. Alcuni server inviano prima ACK vuoto (msgSize=0), altri inviano subito challenge.
        byte[] challenge = receiveChallengeWithOptionalAck();
        Log.d(TAG, "Challenge: " + challenge.length + " bytes");
        if (challenge.length == 0) throw new IOException("Empty challenge");

        // 5. Firma con RSA
        byte[] signature = signChallenge(challenge);
        Log.d(TAG, "Signature: " + signature.length + " bytes");

        // 6. Manda [keyName, signature] SENZA count
        String sigHex = String.format("%02X%02X%02X%02X%02X%02X%02X%02X...",
                signature[0] & 0xFF, signature[1] & 0xFF, signature[2] & 0xFF,
                signature[3] & 0xFF, signature[4] & 0xFF, signature[5] & 0xFF,
                signature[6] & 0xFF, signature[7] & 0xFF);
        Log.d(TAG, "Sending signature for keyName: '" + keyName + "' (sigLen=" + signature.length + 
              ", first8=" + sigHex + ")");
        sendVariantStringAndBytes(keyName, signature);
        Log.d(TAG, "Auth response sent");
    }

    /** Consume optional ACK (empty VariantArrayMessage) after signature response */
    private void consumeOptionalPostAuthAck() throws IOException {
        // Only check if data is immediately available - don't block
        if (pbIn.available() < 4) {
            Log.d(TAG, "No post-auth ACK available (available=" + pbIn.available() + ")");
            return;
        }
        
        byte[] peekBuf = new byte[4];
        int bytesRead = pbIn.read(peekBuf);
        if (bytesRead < 4) {
            if (bytesRead > 0) pbIn.unread(peekBuf, 0, bytesRead);
            return;
        }

        int msgSize = ((peekBuf[0] & 0xFF) << 24) | ((peekBuf[1] & 0xFF) << 16) |
                      ((peekBuf[2] & 0xFF) << 8) | (peekBuf[3] & 0xFF);
        
        if (msgSize == 0) {
            Log.d(TAG, "Post-auth ACK consumed (msgSize=0)");
            // ACK consumed, don't push back
        } else {
            // Not an ACK, push back for security_result reading
            pbIn.unread(peekBuf, 0, 4);
            Log.d(TAG, "No post-auth ACK, msgSize=" + msgSize + " (will be security result)");
        }
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
        // QVariant<QString>: [type=10 (4 bytes)][isNull=0 (1 byte)][length (4 bytes)][data (length bytes)]
        // QVariant<QByteArray>: [type=12 (4 bytes)][isNull=0 (1 byte)][length (4 bytes)][data (length bytes)]
        int payloadSize = (4 + 1 + 4 + strUtf16.length) + (4 + 1 + 4 + bytes.length);

        out.writeInt(payloadSize);

        // QVariant<QString>
        out.writeInt(10); // QMetaType::QString
        out.writeByte(0); // isNull
        out.writeInt(strUtf16.length);
        if (strUtf16.length > 0) out.write(strUtf16);

        // QVariant<QByteArray>
        out.writeInt(QMT_BYTEARRAY);
        out.writeByte(0); // isNull
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
        // BGR format (Veyon sends BGR, not RGB)
        out.writeByte(0); out.writeByte(8); out.writeByte(16);  // BGR: blue shift 0, green shift 8, red shift 16
        out.writeByte(0); out.writeByte(0); out.writeByte(0);
        out.flush();
    }

    private void sendSetEncodings() throws IOException {
        // Send encodings in priority order - HEXTILE first (most reliable)
        // ZLIBHEX/ZLIB disabled due to decompression bugs
        int[] encodings = {
            0x00000005,  // HEXTILE (preferred - efficient and reliable)
            0x00000000,  // RAW (fallback - always works)
            0x00000001,  // CopyRect
            0x00000002,  // RRE
            0x00000004,  // CoRRE
            0xFFFFFFFA,  // DesktopSize
            0xFFFF0010,  // Cursor pseudo-encoding
            0xFFFFFF21,  // QEMUExtendedKeyEvent
            0xFFFFFF17,  // CursorPos
            0xFFFFFF18,  // PointerPos
            0xFFFFFF19,  // KeyboardLedState
            0xFFFFFF20,  // QEMUPointerMotionChange
            0xFFFFFF22,  // QEMUAudio
            0xFFFFFF23,  // QEMULEDState
            0xFFFFFECC,  // VMWareCursor
            // ZLIBHEX (6) and ZLIB (9) disabled - decompression bugs
        };
        out.writeByte(MSG_SET_ENCODINGS);
        out.writeByte(0);
        out.writeShort(encodings.length);
        for (int enc : encodings) out.writeInt(enc);
        out.flush();
        Log.d(TAG, "Sent encodings: " + encodings.length + " entries (HEXTILE preferred, ZLIB disabled)");
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
        long lastFramebufferRequestMs = System.currentTimeMillis();
        long lastMonitoringPingMs = System.currentTimeMillis();

        // Timeout più ragionevole: 1 secondo invece di 200ms
        socket.setSoTimeout(1000); // Increased timeout to prevent premature disconnections

        // Send an initial incremental FBU request to keep the connection alive
        try {
            sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
        } catch (IOException e) {
            Log.w(TAG, "Failed to send initial FBU request: " + e.getMessage());
        }

        while (running.get()) {
            try {
                Log.d(TAG, "Waiting for next message, available=" + pbIn.available());

                // Peek ahead to log what's coming
                if (pbIn.available() > 0) {
                    byte[] peek = new byte[Math.min(16, pbIn.available())];
                    int n = pbIn.read(peek);
                    StringBuilder sb = new StringBuilder("Next bytes: ");
                    for (int pi = 0; pi < n; pi++) sb.append(String.format("%02x ", peek[pi] & 0xFF));
                    Log.d(TAG, sb.toString());
                    pbIn.unread(peek, 0, n);
                }

                int msgType = in.readUnsignedByte();
                Log.d(TAG, "Server msg type: 0x" + Integer.toHexString(msgType) + 
                      ", available=" + pbIn.available());

                switch (msgType) {
                    case MSG_SERVER_FRAMEBUFFER_UPDATE:
                        in.readUnsignedByte(); // padding
                        int numRects = in.readUnsignedShort();
                        Log.d(TAG, "Framebuffer update: numRects=" + numRects);

                        // VALIDAZIONE - allow up to 500 rects for HEXTILE encoding
                        if (numRects < 0 || numRects > 500) {
                            Log.e(TAG, "Invalid numRects: " + numRects + ", possible desync");
                            throw new IOException("Invalid number of rectangles: " + numRects);
                        }
                        Log.d(TAG, "Processing " + numRects + " rectangles...");
                        for (int i = 0; i < numRects; i++) {
                            processRect();
                        }
                        Log.d(TAG, "All rects processed");

                        // Send FBU request IMMEDIATELY after processing rects, BEFORE rendering
                        // This keeps the pipeline full while we're slow Java rendering
                        sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                        sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                        out.flush();

                        // Renderizza (slow operation)
                        renderFrame();
                        fpsCount++;

                        if (!firstFramebufferReceived) {
                            firstFramebufferReceived = true;
                            Log.d(TAG, "First framebuffer received");
                        }

                        lastFramebufferRequestMs = System.currentTimeMillis();
                        break;

                    case MSG_SERVER_BELL:
                        Log.d(TAG, "Bell received");
                        break;

                    case MSG_SERVER_CUT_TEXT:
                        // Skip 3 bytes (padding)
                        in.readUnsignedByte(); in.readUnsignedByte(); in.readUnsignedByte();
                        int textLen = in.readInt();
                        byte[] text = new byte[textLen];
                        in.readFully(text);
                        Log.d(TAG, "Server cut text: " + textLen + " bytes");
                        break;

                    case 0x29: // Veyon FeatureMessage
                        int fmSize = in.readInt();
                        if (fmSize > 0 && fmSize < 65536) {
                            byte[] fmData = new byte[fmSize];
                            in.readFully(fmData);
                            // Parse and respond to feature messages
                            handleFeatureMessage(fmData);
                        }
                        // Don't send FBU requests here - let the normal flow handle it
                        break;

                    default:
                        Log.w(TAG, "Unknown server message type: 0x" + Integer.toHexString(msgType));
                        // Se il server invia un tipo sconosciuto, potrebbe essere un errore
                        // o una desincronizzazione. Logghiamo e continuiamo.
                        break;
                }

            } catch (SocketTimeoutException timeout) {
                // Send a keepalive FBU request to prevent server idle timeout
                try {
                    sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                } catch (IOException ignored) {}
            }
            if (Thread.interrupted()) break;

            long now = System.currentTimeMillis();

            // KEEPALIVE: re-request frame every 200ms if server goes quiet
            if (now - lastFramebufferRequestMs >= 200) {
                sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                sendFramebufferUpdateRequest(0, 0, fbWidth, fbHeight, true);
                out.flush();
                lastFramebufferRequestMs = now;
            }

            // Keepalive monitoring (molto meno frequente)
            if (now - lastMonitoringPingMs >= KEEPALIVE_MONITORING_PING_MS) {
                try {
                    // Invia solo monitoring mode, non tutti i feature
                    sendFeatureMessage(FEATURE_MONITORING_MODE, true, null);
                } catch (IOException e) {
                    Log.w(TAG, "Monitoring ping failed: " + e.getMessage());
                }
                lastMonitoringPingMs = now;
            }

            // FPS counter
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

        Log.d(TAG, "processRect: x=" + x + " y=" + y + " w=" + w + " h=" + h + " encoding=" + encoding + 
              " (0x" + Integer.toHexString(encoding) + ")");

        // Pseudo-encodings can have unusual dimensions - check after identifying encoding
        boolean isPseudoEncoding = encoding < 0;
        
        // Pseudo-encodings (negative values or special values)
        if (encoding == ENC_NEW_FB_SIZE) {
            Log.d(TAG, "New framebuffer size: " + w + "x" + h);
            return;
        }
        if (encoding == ENC_LAST_RECT) {
            Log.d(TAG, "Last rect marker");
            return;
        }
        
        // PointerPos pseudo-encoding (0xFFFFFF18 = -232)
        if (encoding == -232 || encoding == 0xFFFFFF18) {
            // PointerPos: cursor position is in x,y from rect header
            // NO additional data payload!
            Log.d(TAG, "PointerPos skipped: cursor at (" + x + "," + y + ")");
            return;
        }

        // Validate dimensions for non-pseudo encodings
        if (!isPseudoEncoding && (x > fbWidth || y > fbHeight || w > fbWidth || h > fbHeight)) {
            Log.e(TAG, "Rect dimensions exceed framebuffer: x=" + x + " y=" + y + " w=" + w + " h=" + h +
                  " (fb=" + fbWidth + "x" + fbHeight + ") enc=" + encoding);
            throw new IOException("Protocol desync - rect dimensions invalid");
        }
        // Cursor pseudo-encoding - skip the cursor data
        if (encoding == -239 || encoding == 0xFFFF1710 || encoding == -306) {
            // Cursor: w*h*4 bytes for colors + 2 bytes for hotspot x,y
            int cursorSize = w * h * 4 + 4;
            byte[] cursorData = new byte[cursorSize];
            in.readFully(cursorData);
            Log.d(TAG, "Cursor update skipped: " + w + "x" + h);
            return;
        }
        // VMWareCursor pseudo-encoding (0xFFFFFECC = -308)
        if (encoding == -308 || encoding == 0xFFFFFECC) {
            // Veyon's VMWareCursor has a 4-byte payload (usage unknown, possibly flags)
            int flags = in.readInt();
            Log.d(TAG, "VMWareCursor: Veyon marker with flags=0x" + Integer.toHexString(flags));
            return;
        }
        // DesktopSize pseudo-encoding
        if (encoding == -223 || encoding == 0xFFFF21) {
            Log.d(TAG, "DesktopSize update: " + w + "x" + h);
            return;
        }
        // QEMU extended key events
        if (encoding == -259 || encoding == 0xFFFF23) {
            Log.d(TAG, "QEMU extended keys skipped");
            return;
        }
        // QEMU pointer motion change
        if (encoding == -260 || encoding == 0xFFFF24) {
            Log.d(TAG, "QEMU pointer motion skipped");
            return;
        }
        // QEMU audio
        if (encoding == -261 || encoding == 0xFFFF25) {
            Log.d(TAG, "QEMU audio skipped");
            return;
        }
        // QEMU LED state
        if (encoding == -263 || encoding == 0xFFFF27) {
            Log.d(TAG, "QEMU LED state skipped");
            return;
        }
        // Unknown pseudo-encoding - try to skip
        if (encoding < 0) {
            Log.w(TAG, "Unknown pseudo-encoding: " + encoding + " skipping " + w + "x" + h);
            // For pseudo-encodings, there's typically no rect data to skip
            // But some might have data - be safe and skip w*h*4 bytes if it looks like pixel data
            if (w > 0 && h > 0 && w < 1000 && h < 1000) {
                int skipSize = w * h * 4;
                if (skipSize > 0 && skipSize < 10000000) {
                    Log.d(TAG, "Skipping " + skipSize + " bytes for unknown pseudo-encoding");
                    long skipped = in.skip(skipSize);
                    if (skipped < skipSize) {
                        Log.w(TAG, "Only skipped " + skipped + "/" + skipSize + " bytes");
                    }
                }
            }
            return;
        }

        // ✅ VALIDAZIONE: Se i valori sono assurdi, siamo desincronizzati
        if (x > 10000 || y > 10000 || w > 5000 || h > 5000 || w < 0 || h < 0) {
            Log.e(TAG, "Invalid rect values, protocol desync: x=" + x + " y=" + y + " w=" + w + " h=" + h + " enc=" + encoding);
            throw new IOException("Protocol desynchronization detected");
        }

        switch (encoding) {
            case ENC_RAW:       processRawRect(x, y, w, h);     break;
            case ENC_COPY_RECT: processCopyRect(x, y, w, h);    break;
            case ENC_RRE:       processRreRect(x, y, w, h);     break;
            case ENC_CORRE:     processCorreRect(x, y, w, h);   break;
            case ENC_HEXTILE:   processHexTileRect(x, y, w, h); break;
            case ENC_ZLIB:      processZlibRect(x, y, w, h);    break;
            case ENC_ZLIBHEX:   processZlibHexRect(x, y, w, h); break;
            default:
                Log.w(TAG, "Unsupported encoding: " + encoding + " — skipping rect " + w + "x" + h);
                // For now, throw to reconnect - we can't safely skip unknown encodings
                throw new IOException("Unsupported encoding: " + encoding);
        }
    }

    private void processRawRect(int x, int y, int w, int h) throws IOException {
        int pixelCount = w * h;
        int[] pixels = new int[pixelCount];
        
        // Read all pixel data at once for better performance
        byte[] pixelData = new byte[pixelCount * 4]; // 4 bytes per pixel (BGRA)
        in.readFully(pixelData);
        
        // Convert from server's BGR format to Android's ARGB format
        // Server sends: B, G, R, padding (per pixel)
        // We want: 0xAARRGGBB
        for (int i = 0; i < pixelCount; i++) {
            int offset = i * 4;
            int b = pixelData[offset] & 0xFF;
            int g = pixelData[offset + 1] & 0xFF;
            int r = pixelData[offset + 2] & 0xFF;
            // padding is at offset + 3, ignored
            pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
        }
        
        if (framebuffer != null) {
            synchronized (framebuffer) {
                framebuffer.setPixels(pixels, 0, w, x, y, w, h);
            }
            // Debug: log first pixel of first rect
            if (x == 0 && y == 0) {
                Log.d(TAG, "RawRect setPixels: " + w + "x" + h + " at (" + x + "," + y + 
                      "), firstPixel=0x" + Integer.toHexString(pixels[0] & 0xFFFFFFFF));
            }
        } else {
            Log.w(TAG, "RawRect: framebuffer is null!");
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

    private void processRreRect(int x, int y, int w, int h) throws IOException {
        int numSubrects = in.readInt();
        in.readUnsignedByte(); // padding
        // BGR format
        int bgB = in.readUnsignedByte(), bgG = in.readUnsignedByte(), bgR = in.readUnsignedByte();
        in.readUnsignedByte(); // padding
        int bg = 0xFF000000 | (bgR << 16) | (bgG << 8) | bgB;
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, bg);
        for (int i = 0; i < numSubrects; i++) {
            in.readUnsignedByte(); // padding
            int fgB = in.readUnsignedByte(), fgG = in.readUnsignedByte(), fgR = in.readUnsignedByte();
            in.readUnsignedByte(); // padding
            int fg = 0xFF000000 | (fgR << 16) | (fgG << 8) | fgB;
            int sx = in.readUnsignedShort(), sy = in.readUnsignedShort();
            int sw = in.readUnsignedShort(), sh = in.readUnsignedShort();
            for (int row = sy; row < sy + sh; row++)
                for (int col = sx; col < sx + sw; col++)
                    if (row < h && col < w) pixels[row * w + col] = fg;
        }
        if (framebuffer != null) synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    private void processCorreRect(int x, int y, int w, int h) throws IOException {
        int numSubrects = in.readInt();
        in.readUnsignedByte(); // padding
        // BGR format
        int bgB = in.readUnsignedByte(), bgG = in.readUnsignedByte(), bgR = in.readUnsignedByte();
        in.readUnsignedByte(); // padding
        int bg = 0xFF000000 | (bgR << 16) | (bgG << 8) | bgB;
        int[] pixels = new int[w * h];
        java.util.Arrays.fill(pixels, bg);
        for (int i = 0; i < numSubrects; i++) {
            in.readUnsignedByte(); // padding
            int fgB = in.readUnsignedByte(), fgG = in.readUnsignedByte(), fgR = in.readUnsignedByte();
            in.readUnsignedByte(); // padding
            int fg = 0xFF000000 | (fgR << 16) | (fgG << 8) | fgB;
            int sx = in.readUnsignedByte(), sy = in.readUnsignedByte();
            int sw = in.readUnsignedByte(), sh = in.readUnsignedByte();
            for (int row = sy; row < sy + sh; row++)
                for (int col = sx; col < sx + sw; col++)
                    if (row < h && col < w) pixels[row * w + col] = fg;
        }
        if (framebuffer != null) synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    private void processHexTileRect(int x, int y, int w, int h) throws IOException {
        int TILE = 16;
        int tilesX = (w + TILE - 1) / TILE;
        int tilesY = (h + TILE - 1) / TILE;
        int totalTiles = tilesX * tilesY;
        for (int ty = 0; ty < tilesY; ty++) {
            for (int tx = 0; tx < tilesX; tx++) {
                int tileX = x + tx * TILE;
                int tileY = y + ty * TILE;
                int tileW = Math.min(TILE, w - tx * TILE);
                int tileH = Math.min(TILE, h - ty * TILE);
                int subenc = in.readUnsignedByte();
                processHexTileFromStream(in, tileX, tileY, tileW, tileH, subenc);
            }
        }
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
        } catch (Exception e) { throw new IOException("ZLIB inflate failed: " + e.getMessage()); }
        int[] pixels = new int[w * h];
        for (int i = 0; i < pixels.length; i++)
            // Server sends BGRA pixels (4 bytes per pixel)
            pixels[i] = 0xFF000000 | ((raw[i*4+2] & 0xFF) << 16) | ((raw[i*4+1] & 0xFF) << 8) | (raw[i*4] & 0xFF);
        if (framebuffer != null) synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    private void processZlibHexRect(int x, int y, int w, int h) throws IOException {
        // ZlibHex: each 16x16 tile has a 1-byte subencoding; if bit 0x10 set, tile is zlib-compressed
        int TILE = 16;
        for (int ty = y; ty < y + h; ty += TILE) {
            for (int tx = x; tx < x + w; tx += TILE) {
                int tileW = Math.min(TILE, x + w - tx);
                int tileH = Math.min(TILE, y + h - ty);
                processZlibHexTile(tx, ty, tileW, tileH);
            }
        }
    }

    private void processZlibHexTile(int x, int y, int w, int h) throws IOException {
        int subenc = in.readUnsignedByte();
        boolean zlibEncoded = (subenc & 0x10) != 0;
        int hextileSubenc = subenc & 0x0F;
        if (zlibEncoded) {
            // Read zlib-compressed hextile data
            int dataLen = in.readUnsignedShort();
            byte[] compressed = new byte[dataLen];
            in.readFully(compressed);
            // Decompress and process as raw hextile — simplified: treat as raw
            Inflater inf = new Inflater();
            inf.setInput(compressed);
            byte[] raw = new byte[w * h * 4 + 16];
            int total = 0;
            try { while (total < raw.length) { int r = inf.inflate(raw, total, raw.length - total); if (r <= 0) break; total += r; } }
            catch (Exception e) { throw new IOException("ZlibHex inflate failed"); }
            // Parse as hextile from the decompressed buffer
            java.io.DataInputStream dis = new java.io.DataInputStream(new java.io.ByteArrayInputStream(raw, 0, total));
            processHexTileFromStream(dis, x, y, w, h, hextileSubenc);
        } else {
            processHexTileFromStream(in, x, y, w, h, hextileSubenc);
        }
    }

    private int hextileBg = 0xFF000000, hextileFg = 0xFFFFFFFF;

    private void processHexTileFromStream(DataInputStream src, int x, int y, int w, int h, int subenc) throws IOException {
        boolean raw = (subenc & 1) != 0;
        boolean bgSpec = (subenc & 2) != 0;
        boolean fgSpec = (subenc & 4) != 0;
        boolean anySubrects = (subenc & 8) != 0;
        boolean subrectsColored = (subenc & 16) != 0;

        int[] pixels = new int[w * h];
        if (raw) {
            // Raw hextile: 4 bytes per pixel (B, G, R, padding) - BGR format
            for (int i = 0; i < pixels.length; i++) {
                int b = src.readUnsignedByte();
                int g = src.readUnsignedByte();
                int r = src.readUnsignedByte();
                src.readUnsignedByte(); // padding
                pixels[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
        } else {
            // Background color if specified (4 bytes: B, G, R, padding)
            if (bgSpec) {
                int b = src.readUnsignedByte();
                int g = src.readUnsignedByte();
                int r = src.readUnsignedByte();
                src.readUnsignedByte(); // padding
                hextileBg = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            // Foreground color if specified (4 bytes: B, G, R, padding)
            if (fgSpec) {
                int b = src.readUnsignedByte();
                int g = src.readUnsignedByte();
                int r = src.readUnsignedByte();
                src.readUnsignedByte(); // padding
                hextileFg = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            // Fill with background
            java.util.Arrays.fill(pixels, hextileBg);
            // Subrectangles
            if (anySubrects) {
                int nSubrects = src.readUnsignedByte();
                for (int i = 0; i < nSubrects; i++) {
                    int color = hextileFg;
                    if (subrectsColored) {
                        // Colored subrect: 4 bytes (B, G, R, padding)
                        int b = src.readUnsignedByte();
                        int g = src.readUnsignedByte();
                        int r = src.readUnsignedByte();
                        src.readUnsignedByte(); // padding
                        color = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                    // xy: packed (x<<4)|y
                    int xy = src.readUnsignedByte();
                    // wh: packed ((w-1)<<4)|(h-1)
                    int wh = src.readUnsignedByte();
                    int sx = (xy >> 4) & 0xF;
                    int sy = xy & 0xF;
                    int sw = ((wh >> 4) & 0xF) + 1;
                    int sh = (wh & 0xF) + 1;
                    for (int row = sy; row < sy + sh && row < h; row++) {
                        for (int col = sx; col < sx + sw && col < w; col++) {
                            pixels[row * w + col] = color;
                        }
                    }
                }
            }
        }
        if (framebuffer != null) synchronized (framebuffer) { framebuffer.setPixels(pixels, 0, w, x, y, w, h); }
    }

    // ─── Render ────────────────────────────────────────────────────────────────

    private void renderFrame() {
        if (framebuffer == null || surfaceHolder == null) {
            Log.w(TAG, "renderFrame skipped: framebuffer=" + (framebuffer == null) + 
                  ", surfaceHolder=" + (surfaceHolder == null));
            return;
        }
        Canvas canvas = null;
        try {
            canvas = surfaceHolder.lockCanvas();
            if (canvas == null) {
                Log.w(TAG, "renderFrame: canvas is null");
                return;
            }
            Log.d(TAG, "renderFrame: canvas=" + canvas.getWidth() + "x" + canvas.getHeight() + 
                  ", framebuffer=" + framebuffer.getWidth() + "x" + framebuffer.getHeight());
            synchronized (framebuffer) {
                // Draw the framebuffer scaled to canvas size
                canvas.drawBitmap(framebuffer, null,
                        new Rect(0, 0, canvas.getWidth(), canvas.getHeight()), null);
                
                // Draw FPS counter in top-left corner (green text indicator)
                android.graphics.Paint paint = new android.graphics.Paint();
                paint.setColor(0xFF00FF00); // Green
                paint.setTextSize(48);
                paint.setStyle(android.graphics.Paint.Style.FILL);
                canvas.drawText("VeyonMobile: " + fpsCount + " fps", 20, 60, paint);
            }
        } finally {
            if (canvas != null) {
                try { surfaceHolder.unlockCanvasAndPost(canvas); } catch (Exception ignored) {}
            }
        }
    }

    // ─── Feature Messages ──────────────────────────────────────────────────────

    private void handleFeatureMessage(byte[] fmData) throws IOException {
        try {
            java.io.DataInputStream dis = new java.io.DataInputStream(
                new java.io.ByteArrayInputStream(fmData));
            
            // Read feature UID
            int uidType = dis.readInt();
            dis.readUnsignedByte(); // isNull
            long data1 = dis.readInt();
            int data2 = dis.readUnsignedShort();
            int data3 = dis.readUnsignedShort();
            byte[] data4 = new byte[8];
            dis.readFully(data4);
            
            // Convert to UUID string
            String featureUid = String.format("%08x-%04x-%04x-%02x%02x%02x%02x%02x%02x%02x%02x",
                (int)data1, data2, data3,
                data4[0], data4[1], data4[2], data4[3],
                data4[4], data4[5], data4[6], data4[7]);
            
            // Read command
            int cmdType = dis.readInt();
            dis.readUnsignedByte();
            int command = dis.readInt();
            
            // Read arguments map
            int mapType = dis.readInt();
            dis.readUnsignedByte();
            int mapCount = dis.readInt();
            
            Log.d(TAG, "FeatureMessage: uid=" + featureUid + " cmd=" + command + " args=" + mapCount);
            
            // Respond to ALL feature queries from server
            if (featureUid.equals(FEATURE_MONITORING_MODE)) {
                Log.d(TAG, "Responding to MonitoringMode feature with cmd=" + command);
                sendSimpleFeatureReply(featureUid, 0);
                Log.d(TAG, "MonitoringMode response sent");
            } else if (featureUid.equals(FEATURE_QUERY_ACTIVE)) {
                Log.d(TAG, "Responding to QueryActiveFeatures");
                // Respond with list of active features
                sendFeatureReplyWithList(featureUid, 2, "activeFeatures",
                    "edad8259-b4ef-4ca5-90e6-f238d0fda694");
            } else if (featureUid.equals(FEATURE_USER_INFO)) {
                Log.d(TAG, "Responding to UserInfo");
                sendFeatureReplyWithMap(featureUid, 2,
                    "userLoginName", "veyon-user",
                    "userFullName", "Veyon User");
            } else if (featureUid.equals(FEATURE_SESSION_INFO)) {
                Log.d(TAG, "Responding to SessionInfo");
                sendFeatureReplyWithMap(featureUid, 2,
                    "sessionClientName", "veyon-mobile");
            } else if (featureUid.equals(FEATURE_QUERY_SCREENS)) {
                Log.d(TAG, "Responding to QueryScreens");
                sendFeatureReplyWithList(featureUid, 2, "screens",
                    "{\"name\":\"Screen 1\",\"width\":1920,\"height\":1080}");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Failed to handle feature message: " + e.getMessage(), e);
        }
    }
    
    // Send a simple feature reply with just UUID and command (no arguments)
    private void sendSimpleFeatureReply(String featureUid, int command) throws IOException {
        if (out == null) return;
        
        // Convert UUID to bytes
        long data1 = Long.parseLong(featureUid.substring(0, 8), 16);
        int data2 = Integer.parseInt(featureUid.substring(9, 13), 16);
        int data3 = Integer.parseInt(featureUid.substring(14, 18), 16);
        byte[] data4 = new byte[8];
        String d4hex = featureUid.substring(19, 23) + featureUid.substring(24);
        for (int i = 0; i < 8; i++) {
            data4[i] = (byte) Integer.parseInt(d4hex.substring(i * 2, i * 2 + 2), 16);
        }
        
        // payloadSize = UUID(21) + Command(9) + EmptyMap(9) = 39 bytes
        int payloadSize = 39;
        
        // Write message type byte FIRST (0x29 = FeatureMessage)
        out.writeByte(MSG_VEYON_FEATURE);
        
        ByteBuffer buf = ByteBuffer.allocate(4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);
        
        buf.putInt(payloadSize);
        
        // UUID - type=30, isNull=0, 16 bytes
        buf.putInt(30);
        buf.put((byte) 0);
        buf.putInt((int) data1);
        buf.putShort((short) data2);
        buf.putShort((short) data3);
        buf.put(data4);
        
        // Command - type=2, isNull=0, value
        buf.putInt(QMT_INT);
        buf.put((byte) 0);
        buf.putInt(command);
        
        // Empty arguments map - type=8, isNull=0, count=0
        buf.putInt(8);
        buf.put((byte) 0);
        buf.putInt(0);
        
        out.write(buf.array());
        out.flush();
        Log.d(TAG, "Sent simple feature reply: " + featureUid + " cmd=" + command);
    }
    
    // Send feature reply with a map of string key-value pairs
    private void sendFeatureReplyWithMap(String featureUid, int command, Object... args) throws IOException {
        if (out == null || args.length % 2 != 0) return;
        
        // Convert UUID to bytes
        long data1 = Long.parseLong(featureUid.substring(0, 8), 16);
        int data2 = Integer.parseInt(featureUid.substring(9, 13), 16);
        int data3 = Integer.parseInt(featureUid.substring(14, 18), 16);
        byte[] data4 = new byte[8];
        String d4hex = featureUid.substring(19, 23) + featureUid.substring(24);
        for (int i = 0; i < 8; i++) {
            data4[i] = (byte) Integer.parseInt(d4hex.substring(i * 2, i * 2 + 2), 16);
        }
        
        // Build arguments map
        java.util.Map<String, String> arguments = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            arguments.put((String)args[i], (String)args[i+1]);
        }
        
        // Calculate payload size
        int uuidSize = 21;
        int cmdSize = 9;
        int mapSize = 9; // type + isNull + count
        for (java.util.Map.Entry<String, String> entry : arguments.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            mapSize += 9 + key.length() * 2; // key
            mapSize += 9 + value.length() * 2; // value
        }
        
        int payloadSize = uuidSize + cmdSize + mapSize;
        
        // Write message type byte FIRST (0x29 = FeatureMessage)
        out.writeByte(MSG_VEYON_FEATURE);
        
        ByteBuffer buf = ByteBuffer.allocate(4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);
        
        buf.putInt(payloadSize);
        
        // UUID
        buf.putInt(30);
        buf.put((byte) 0);
        buf.putInt((int) data1);
        buf.putShort((short) data2);
        buf.putShort((short) data3);
        buf.put(data4);
        
        // Command
        buf.putInt(QMT_INT);
        buf.put((byte) 0);
        buf.putInt(command);
        
        // Arguments map
        buf.putInt(8);
        buf.put((byte) 0);
        buf.putInt(arguments.size());
        
        for (java.util.Map.Entry<String, String> entry : arguments.entrySet()) {
            // Key as QString
            String key = entry.getKey();
            byte[] keyUtf16 = key.getBytes("UTF-16BE");
            buf.putInt(10);
            buf.put((byte) 0);
            buf.putInt(keyUtf16.length);
            buf.put(keyUtf16);
            
            // Value as QString
            String value = entry.getValue();
            byte[] valUtf16 = value.getBytes("UTF-16BE");
            buf.putInt(10);
            buf.put((byte) 0);
            buf.putInt(valUtf16.length);
            buf.put(valUtf16);
        }
        
        out.write(buf.array());
        out.flush();
        Log.d(TAG, "Sent feature reply with map: " + featureUid);
    }
    
    // Send feature reply with a list of strings
    private void sendFeatureReplyWithList(String featureUid, int command, String listKey, String... items) throws IOException {
        if (out == null) return;
        
        // Convert UUID to bytes
        long data1 = Long.parseLong(featureUid.substring(0, 8), 16);
        int data2 = Integer.parseInt(featureUid.substring(9, 13), 16);
        int data3 = Integer.parseInt(featureUid.substring(14, 18), 16);
        byte[] data4 = new byte[8];
        String d4hex = featureUid.substring(19, 23) + featureUid.substring(24);
        for (int i = 0; i < 8; i++) {
            data4[i] = (byte) Integer.parseInt(d4hex.substring(i * 2, i * 2 + 2), 16);
        }
        
        // Calculate payload size (simplified - just enough for the list)
        int listDataSize = 0;
        for (String item : items) {
            listDataSize += 9 + item.length() * 2;
        }
        
        int uuidSize = 21;
        int cmdSize = 9;
        int mapSize = 9 + (9 + listKey.length() * 2) + 9 + listDataSize;
        
        int payloadSize = uuidSize + cmdSize + mapSize;
        
        // Write message type byte FIRST (0x29 = FeatureMessage)
        out.writeByte(MSG_VEYON_FEATURE);
        
        ByteBuffer buf = ByteBuffer.allocate(4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);
        
        buf.putInt(payloadSize);
        
        // UUID
        buf.putInt(30);
        buf.put((byte) 0);
        buf.putInt((int) data1);
        buf.putShort((short) data2);
        buf.putShort((short) data3);
        buf.put(data4);
        
        // Command
        buf.putInt(QMT_INT);
        buf.put((byte) 0);
        buf.putInt(command);
        
        // Arguments map with 1 entry (the list)
        buf.putInt(8);
        buf.put((byte) 0);
        buf.putInt(1);
        
        // Key (list name)
        byte[] keyUtf16 = listKey.getBytes("UTF-16BE");
        buf.putInt(10);
        buf.put((byte) 0);
        buf.putInt(keyUtf16.length);
        buf.put(keyUtf16);
        
        // Value (list of strings as QVariantList)
        buf.putInt(10); // QVariantList type
        buf.put((byte) 0);
        buf.putInt(items.length * 20); // Simplified size
        
        for (String item : items) {
            // Each item as QString
            byte[] itemUtf16 = item.getBytes("UTF-16BE");
            buf.putInt(10);
            buf.put((byte) 0);
            buf.putInt(itemUtf16.length);
            buf.put(itemUtf16);
        }
        
        out.write(buf.array());
        out.flush();
        Log.d(TAG, "Sent feature reply with list: " + featureUid);
    }
    
    private void sendFeatureMessageReply(String featureUid, int command, Object... args) throws IOException {
        if (out == null) return;
        if (args.length % 2 != 0 && args.length != 0) {
            Log.w(TAG, "sendFeatureMessageReply: args must be even count");
            return;
        }
        
        // Convert UUID to bytes
        long data1 = Long.parseLong(featureUid.substring(0, 8), 16);
        int data2 = Integer.parseInt(featureUid.substring(9, 13), 16);
        int data3 = Integer.parseInt(featureUid.substring(14, 18), 16);
        byte[] data4 = new byte[8];
        String d4hex = featureUid.substring(19, 23) + featureUid.substring(24);
        for (int i = 0; i < 8; i++) {
            data4[i] = (byte) Integer.parseInt(d4hex.substring(i * 2, i * 2 + 2), 16);
        }
        
        // Build arguments map
        java.util.Map<String, Object> arguments = new java.util.HashMap<>();
        for (int i = 0; i < args.length; i += 2) {
            arguments.put((String)args[i], args[i+1]);
        }
        
        // Calculate payload size
        int uuidSize = 21;
        int cmdSize = 9;
        int mapSize = 9; // type + isNull + count
        for (java.util.Map.Entry<String, Object> entry : arguments.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                String s = (String) value;
                mapSize += 9 + s.length() * 2;
            } else if (value instanceof java.util.List) {
                mapSize += 9 + 9;
            } else if (value instanceof java.util.Map) {
                mapSize += 9 + 9;
            }
        }
        
        int payloadSize = uuidSize + cmdSize + mapSize;
        
        ByteBuffer buf = ByteBuffer.allocate(4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);
        
        buf.putInt(payloadSize);
        
        // UUID
        buf.putInt(30);
        buf.put((byte) 0);
        buf.putInt((int) data1);
        buf.putShort((short) data2);
        buf.putShort((short) data3);
        buf.put(data4);
        
        // Command
        buf.putInt(QMT_INT);
        buf.put((byte) 0);
        buf.putInt(command);
        
        // Arguments map
        buf.putInt(8);
        buf.put((byte) 0);
        buf.putInt(arguments.size());
        
        for (java.util.Map.Entry<String, Object> entry : arguments.entrySet()) {
            // Key as QString
            String key = entry.getKey();
            byte[] keyUtf16 = key.getBytes("UTF-16BE");
            buf.putInt(10);
            buf.put((byte) 0);
            buf.putInt(keyUtf16.length);
            buf.put(keyUtf16);
            
            // Value
            Object value = entry.getValue();
            if (value instanceof String) {
                String s = (String) value;
                byte[] valUtf16 = s.getBytes("UTF-16BE");
                buf.putInt(10);
                buf.put((byte) 0);
                buf.putInt(valUtf16.length);
                buf.put(valUtf16);
            } else if (value instanceof java.util.List) {
                java.util.List<?> list = (java.util.List<?>) value;
                buf.putInt(10);
                buf.put((byte) 0);
                buf.putInt(list.size() * 20);
                for (Object item : list) {
                    if (item instanceof java.util.Map) {
                        java.util.Map<?, ?> map = (java.util.Map<?, ?>) item;
                        buf.putInt(8);
                        buf.put((byte) 0);
                        buf.putInt(map.size());
                        for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                            String k = e.getKey().toString();
                            byte[] kUtf16 = k.getBytes("UTF-16BE");
                            buf.putInt(10);
                            buf.put((byte) 0);
                            buf.putInt(kUtf16.length);
                            buf.put(kUtf16);
                            String v = e.getValue().toString();
                            byte[] vUtf16 = v.getBytes("UTF-16BE");
                            buf.putInt(10);
                            buf.put((byte) 0);
                            buf.putInt(vUtf16.length);
                            buf.put(vUtf16);
                        }
                    }
                }
            } else if (value instanceof java.util.Map) {
                java.util.Map<?, ?> map = (java.util.Map<?, ?>) value;
                buf.putInt(8);
                buf.put((byte) 0);
                buf.putInt(map.size());
                for (java.util.Map.Entry<?, ?> e : map.entrySet()) {
                    String k = e.getKey().toString();
                    byte[] kUtf16 = k.getBytes("UTF-16BE");
                    buf.putInt(10);
                    buf.put((byte) 0);
                    buf.putInt(kUtf16.length);
                    buf.put(kUtf16);
                    String v = e.getValue().toString();
                    byte[] vUtf16 = v.getBytes("UTF-16BE");
                    buf.putInt(10);
                    buf.put((byte) 0);
                    buf.putInt(vUtf16.length);
                    buf.put(vUtf16);
                }
            }
        }
        
        out.write(buf.array());
        out.flush();
        Log.d(TAG, "Sent feature reply: " + featureUid + " cmd=" + command);
    }

    public synchronized void sendFeatureMessage(String featureUid, boolean active,
                                                java.util.Map<String, String> arguments)
            throws IOException {
        if (out == null) return;

        // Convert UUID string to 16 bytes (big-endian RFC 4122 format)
        long data1 = Long.parseLong(featureUid.substring(0, 8), 16);
        int data2 = Integer.parseInt(featureUid.substring(9, 13), 16);
        int data3 = Integer.parseInt(featureUid.substring(14, 18), 16);
        byte[] data4 = new byte[8];
        String d4hex = featureUid.substring(19, 23) + featureUid.substring(24);
        for (int i = 0; i < 8; i++) {
            data4[i] = (byte) Integer.parseInt(d4hex.substring(i * 2, i * 2 + 2), 16);
        }

        // payloadSize = UUID(21) + Command(9) + Map(9) = 39 bytes
        int payloadSize = 21 + 9 + 9;

        ByteBuffer buf = ByteBuffer.allocate(1 + 4 + payloadSize);
        buf.order(ByteOrder.BIG_ENDIAN);

        buf.put((byte) MSG_VEYON_FEATURE);
        buf.putInt(payloadSize);

        // QVariant<QUuid> - type=30, isNull=0, then 16 bytes
        buf.putInt(30);
        buf.put((byte) 0);
        buf.putInt((int) data1);
        buf.putShort((short) data2);
        buf.putShort((short) data3);
        buf.put(data4);

        // QVariant<Int> Command - use Command::Default = 0
        buf.putInt(QMT_INT);
        buf.put((byte) 0);
        buf.putInt(0);

        // QVariant<QVariantMap> - type=8, isNull=0, count=0
        buf.putInt(8);
        buf.put((byte) 0);
        buf.putInt(0);

        out.write(buf.array());
        out.flush();
        Log.d(TAG, "Sent feature: " + featureUid);
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
