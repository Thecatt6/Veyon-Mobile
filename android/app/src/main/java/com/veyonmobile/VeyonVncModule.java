package com.veyonmobile;

import android.view.SurfaceHolder;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Promise;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import java.util.HashMap;
import java.util.Map;

/**
 * React Native module for VeyonVncClient.
 * Exposes connect/disconnect to JS.
 * Frame rendering is handled natively via VeyonSurfaceView.
 */
public class VeyonVncModule extends ReactContextBaseJavaModule {

    private static final String MODULE_NAME = "VeyonVncModule";
    private static VeyonVncClient activeClient;
    private static VeyonSurfaceView activeSurface;

    public VeyonVncModule(ReactApplicationContext context) {
        super(context);
    }

    @Override
    public String getName() { return MODULE_NAME; }

    /** Called from VeyonSurfaceView to register itself */
    public static void registerSurface(VeyonSurfaceView surface) {
        activeSurface = surface;
    }

    @ReactMethod
    public void connect(String host, int port, String keyName, String privateKey, Promise promise) {
        if (activeClient != null) {
            activeClient.disconnect();
            activeClient = null;
        }
        if (activeSurface == null) {
            promise.reject("NO_SURFACE", "VeyonSurfaceView not mounted");
            return;
        }

        SurfaceHolder holder = activeSurface.getHolder();
        ReactApplicationContext ctx = getReactApplicationContext();

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