package com.veyonmobile;

import android.content.ContentResolver;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.module.annotations.ReactModule;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;

@ReactModule(name = FileReaderModule.NAME)
public class FileReaderModule extends ReactContextBaseJavaModule {

    public static final String NAME = "FileReaderModule";

    public FileReaderModule(ReactApplicationContext reactContext) {
        super(reactContext);
    }

    @Override
    @NonNull
    public String getName() {
        return NAME;
    }

    @Override
    public boolean canOverrideExistingModule() {
        return false;
    }

    @ReactMethod
    public void readContentUri(String uriString, Promise promise) {
        try {
            Uri uri = Uri.parse(uriString);
            ContentResolver resolver = getReactApplicationContext().getContentResolver();
            InputStream inputStream = resolver.openInputStream(uri);
            if (inputStream == null) {
                promise.reject("READ_ERROR", "Could not open stream for: " + uriString);
                return;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            reader.close();
            inputStream.close();
            promise.resolve(sb.toString());
        } catch (Exception e) {
            promise.reject("READ_ERROR", e.getMessage(), e);
        }
    }
}