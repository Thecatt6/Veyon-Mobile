package com.veyonmobile;

import android.view.SurfaceHolder;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.modules.core.DeviceEventManagerModule;

public class VeyonVncModule extends ReactContextBaseJavaModule {

    private static final String MODULE_NAME = "VeyonVncModule";
    private static VeyonVncClient activeClient;
    private static VeyonSurfaceView activeSurface;

    public VeyonVncModule(ReactApplicationContext context) {
        super(context);
    }

    @Override
    public String getName() { return MODULE_NAME; }

    public static void registerSurface(VeyonSurfaceView surface) {
        activeSurface = surface;
    }

    @ReactMethod
    public void connect(String host, int port, String keyName, String privateKey, Promise promise) {
        if (activeClient != null) {
            activeClient.disconnect();
            activeClient = null;
        }

        // Retry fino a 10 volte con 100ms di intervallo (1 secondo totale)
        // per aspettare che surfaceCreated venga chiamato
        tryConnect(host, port, keyName, privateKey, promise, 10);
    }

    private void tryConnect(String host, int port, String keyName, String privateKey,
                            Promise promise, int retriesLeft) {
        if (activeSurface == null) {
            if (retriesLeft <= 0) {
                promise.reject("NO_SURFACE", "VeyonSurfaceView not mounted after retries");
                return;
            }
            // Riprova dopo 100ms
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                            tryConnect(host, port, keyName, privateKey, promise, retriesLeft - 1),
                    100
            );
            return;
        }

        SurfaceHolder holder = activeSurface.getHolder();

        VeyonVncClient.Callback cb = new VeyonVncClient.Callback() {
            @Override
            public void onConnected(int w, int h) {
                sendEvent("veyonVncConnected", w + "x" + h);
                promise.resolve(w + "x" + h);
            }
            @Override
            public void onDisconnected(String reason) {
                sendEvent("veyonVncDisconnected", reason);
            }
            @Override
            public void onError(String error) {
                sendEvent("veyonVncError", error);
                try { promise.reject("VNC_ERROR", error); } catch (Exception ignored) {}
            }
            @Override
            public void onFpsUpdate(int fps) {
                sendEvent("veyonVncFps", String.valueOf(fps));
            }
        };

        activeClient = new VeyonVncClient(host, port, keyName, privateKey, holder, cb);
        activeClient.connect();
    }

    @ReactMethod
    public void disconnect(Promise promise) {
        if (activeClient != null) {
            activeClient.disconnect();
            activeClient = null;
        }
        promise.resolve(null);
    }

    private void sendEvent(String eventName, String data) {
        try {
            getReactApplicationContext()
                    .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                    .emit(eventName, data);
        } catch (Exception ignored) {}
    }
}