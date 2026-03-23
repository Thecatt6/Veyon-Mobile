package com.veyonmobile;

import androidx.annotation.NonNull;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;

import java.net.InetSocketAddress;
import java.net.Socket;

@ReactModule(name = NetworkProbeModule.NAME)
public class NetworkProbeModule extends ReactContextBaseJavaModule {

    public static final String NAME = "NetworkProbeModule";

    public NetworkProbeModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    @NonNull
    public String getName() { return NAME; }

    /**
     * Tenta una connessione TCP a host:port con timeout.
     * Risolve con true se la porta è aperta, false altrimenti.
     * Non usa HTTP — funziona con qualsiasi protocollo (VNC, WebAPI, ecc.)
     */
    @ReactMethod
    public void tcpProbe(String host, int port, int timeoutMs, Promise promise) {
        new Thread(() -> {
            Socket socket = new Socket();
            try {
                socket.connect(new InetSocketAddress(host, port), timeoutMs);
                socket.close();
                promise.resolve(true);
            } catch (Exception e) {
                try { socket.close(); } catch (Exception ignored) {}
                promise.resolve(false); // porta chiusa o irraggiungibile
            }
        }).start();
    }
}