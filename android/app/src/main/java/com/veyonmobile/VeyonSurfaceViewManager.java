package com.veyonmobile;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.uimanager.SimpleViewManager;
import com.facebook.react.uimanager.ThemedReactContext;
import com.facebook.react.uimanager.annotations.ReactProp;

/**
 * React Native ViewManager that exposes VeyonSurfaceView to JS.
 * Usage in JS: <VeyonSurfaceViewNative frameBase64={...} />
 */
public class VeyonSurfaceViewManager extends SimpleViewManager<VeyonSurfaceView> {

    public static final String REACT_CLASS = "VeyonSurfaceView";

    @NonNull
    @Override
    public String getName() {
        return REACT_CLASS;
    }

    @NonNull
    @Override
    protected VeyonSurfaceView createViewInstance(@NonNull ThemedReactContext reactContext) {
        return new VeyonSurfaceView(reactContext);
    }

    /**
     * Accepts raw JPEG bytes as base64 string from JS.
     * The JS side sends base64 to minimize bridge overhead.
     */
    @ReactProp(name = "frameBase64")
    public void setFrameBase64(VeyonSurfaceView view, @Nullable String frameBase64) {
        if (frameBase64 == null || frameBase64.isEmpty()) return;
        try {
            // Strip data URI prefix if present
            String b64 = frameBase64.startsWith("data:")
                    ? frameBase64.substring(frameBase64.indexOf(',') + 1)
                    : frameBase64;
            byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
            view.renderJpegBytes(bytes);
        } catch (Exception e) {
            // Ignore malformed frames
        }
    }
}
