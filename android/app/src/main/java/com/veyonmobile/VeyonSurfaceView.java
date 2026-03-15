package com.veyonmobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class VeyonSurfaceView extends SurfaceView implements SurfaceHolder.Callback {

    public VeyonSurfaceView(Context context) {
        super(context);
        init();
    }

    public VeyonSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        getHolder().addCallback(this);
        setKeepScreenOn(true);
    }

    public void renderJpegBytes(byte[] jpeg) {
        Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
        if (bmp == null) return;
        Canvas canvas = getHolder().lockCanvas();
        if (canvas == null) { bmp.recycle(); return; }
        try {
            Rect dst = new Rect(0, 0, canvas.getWidth(), canvas.getHeight());
            canvas.drawBitmap(bmp, null, dst, null);
        } finally {
            getHolder().unlockCanvasAndPost(canvas);
            bmp.recycle();
        }
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        VeyonVncModule.registerSurface(this);
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {}

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        VeyonVncModule.registerSurface(null);
    }
}
