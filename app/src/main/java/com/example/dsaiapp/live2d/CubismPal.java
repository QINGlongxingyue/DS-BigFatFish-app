/*
 * 大肥鱼 AI 对话 App —— Live2D 渲染层
 *
 * Cubism SDK 框架部分版权归 Live2D Inc.，遵循 Live2D Open Software License。
 * 本文件为应用侧胶水代码。
 */
package com.example.dsaiapp.live2d;

import android.app.Activity;
import android.content.Context;
import android.util.Log;

import com.live2d.sdk.cubism.framework.ICubismLoadFileFunction;
import com.live2d.sdk.cubism.core.ICubismLogger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 平台相关的小工具：日志、从 assets 读文件、帧间隔。
 * <p>
 * 不需要持有 Activity —— 用 ApplicationContext 就够，避免内存泄漏。
 */
public final class CubismPal {
    private static final String TAG = "Dafeiyu";

    private static Context appContext;

    private static long lastNanoTime;
    private static long deltaNanoTime;

    private CubismPal() {}

    public static void init(Context context) {
        appContext = context.getApplicationContext();
        lastNanoTime = System.nanoTime();
    }

    /** 每帧开头调用一次，更新帧间隔。 */
    public static void updateTime() {
        final long now = System.nanoTime();
        deltaNanoTime = now - lastNanoTime;
        lastNanoTime = now;
    }

    /** 上一帧耗时（秒）。 */
    public static float getDeltaTime() {
        return (float) (deltaNanoTime / 1_000_000_000.0);
    }

    /**
     * 从 assets 读取整个文件。
     * <p>
     * 不能用 InputStream.available() 取长度 —— 对压缩过的资源它不可靠，
     * 这里老老实实读到字节数组流里。
     */
    public static byte[] loadFileAsBytes(String path) {
        if (appContext == null) {
            Log.e(TAG, "CubismPal.init() 还没调用");
            return new byte[0];
        }
        try (InputStream in = appContext.getAssets().open(path);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            final byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            Log.e(TAG, "读不到 assets 文件: " + path, e);
            return new byte[0];
        }
    }

    public static void log(String message) {
        Log.d(TAG, message);
        // 同时缓存最近若干行，方便在没接电脑的情况下直接在界面上看
        recentLogs.add(message);
        while (recentLogs.size() > 120) {
            recentLogs.remove(0);
        }
    }

    private static final java.util.List<String> recentLogs =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>());

    /** 取出最近缓存的自检日志（含模型加载、纹理绑定等关键信息）。 */
    public static String[] recentLogLines() {
        synchronized (recentLogs) {
            return recentLogs.toArray(new String[0]);
        }
    }

    public static void clearRecentLogs() {
        recentLogs.clear();
    }

    /** 接进 Cubism 框架的日志回调。 */
    public static final class LogFunction implements ICubismLogger {
        @Override
        public void print(String message) {
            Log.d(TAG, message);
        }
    }

    /** 接进 Cubism 框架的文件加载回调。 */
    public static final class LoadFileFunction implements ICubismLoadFileFunction {
        @Override
        public byte[] load(String path) {
            return loadFileAsBytes(path);
        }
    }
}
