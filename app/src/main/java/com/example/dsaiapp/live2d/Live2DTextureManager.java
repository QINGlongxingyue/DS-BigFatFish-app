package com.example.dsaiapp.live2d;

import android.app.Activity;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.util.Log;

import com.live2d.sdk.cubism.framework.CubismFramework;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenGL 纹理管理器。
 * <p>
 * 跟官方 Sample 的版本差别：用 try-with-resources 关流，并且记录 GL 错误，
 * 免得纹理加载失败时只能看到"模型一片黑"。
 */
public final class Live2DTextureManager {

    public static final class TextureInfo {
        public final int id;
        public final int width;
        public final int height;
        public boolean isValid;

        TextureInfo(int id, int width, int height) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.isValid = true;
        }
    }

    private final Map<String, TextureInfo> textures = new HashMap<>();

    /** 从 assets 里的 PNG 建一张 GL 纹理。已加载过则直接复用。 */
    public TextureInfo createTextureFromPngFile(String filePath) {
        final TextureInfo cached = textures.get(filePath);
        if (cached != null && cached.isValid) {
            return cached;
        }

        final int[] texId = new int[1];
        GLES20.glGenTextures(1, texId, 0);
        // 显式声明单元 0：不依赖"调用前恰好停在哪个单元"这种隐含前提
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId[0]);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        // ⚠️ 必须 mipmap 过滤 + 下面 glGenerateMipmap：Cubism 框架每个 drawable 绘制前
        // 都会把 MIN_FILTER 改成 GL_LINEAR_MIPMAP_LINEAR（CubismShaderAndroid.setUpTexture）。
        // 纹理没生成 mipmap 就是"不完整纹理"，GLES 采样恒返回 (0,0,0,1) 不透明黑
        // —— 这就是"模型整体变黑色剪影"的根因。官方 Sample 的纹理管理器正是这个套路。
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR_MIPMAP_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        final android.graphics.Bitmap bitmap = loadBitmap(filePath);
        if (bitmap == null) {
            Log.e("Dafeiyu", "纹理加载失败: " + filePath);
            return null;
        }

        // 采样确认贴图里真的有东西 —— 全黑贴图是"模型发黑"的经典原因之一
        final String sample = sampleBitmap(bitmap);
        Log.d("Dafeiyu", "纹理 " + filePath + " " + bitmap.getWidth() + "x"
                + bitmap.getHeight() + " " + bitmap.getConfig() + " " + sample);

        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        // 生成 mipmap 链：没有它，上面设的 GL_LINEAR_MIPMAP_LINEAR 会让纹理不完整
        GLES20.glGenerateMipmap(GLES20.GL_TEXTURE_2D);
        final TextureInfo info = new TextureInfo(texId[0], bitmap.getWidth(), bitmap.getHeight());
        bitmap.recycle();

        final int err = GLES20.glGetError();
        if (err != GLES20.GL_NO_ERROR) {
            Log.e("Dafeiyu", "纹理上传 GL 错误 0x" + Integer.toHexString(err) + " : " + filePath);
        }

        textures.put(filePath, info);
        return info;
    }

    /**
     * 从 assets 解码 Bitmap。
     * <p>
     * 用 inScaled=false，避免系统按屏幕密度帮我们缩放 —— 贴图缩放会让
     * 模型 UV 对不上，表现为贴图错位。
     */
    private android.graphics.Bitmap loadBitmap(String filePath) {
        final android.app.Application app = Live2DDelegate.getApplication();
        if (app == null) {
            Log.e("Dafeiyu", "Application 还没初始化，读不了纹理: " + filePath);
            return null;
        }
        try (InputStream in = app.getAssets().open(filePath)) {
            final android.graphics.BitmapFactory.Options opts = new android.graphics.BitmapFactory.Options();
            opts.inScaled = false;
            opts.inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888;
            return android.graphics.BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            Log.e("Dafeiyu", "打不开纹理: " + filePath, e);
            return null;
        }
    }

    /** GL 上下文重建后，之前那些纹理句柄都失效了，标记为无效。 */
    public void releaseInvalidTextures() {
        for (TextureInfo info : textures.values()) {
            info.isValid = false;
        }
        textures.clear();
    }

    /**
     * 验证贴图是否正确上传到 GL。
     *
     * <p><b>实现要点：</b>先把贴图画进一个自己的 FBO，再从那个 FBO 回读像素。
     * 直接对绑定的纹理调 {@code glReadPixels} 是错的 —— 它读的是<b>当前 framebuffer</b>，
     * 不是纹理对象。我第一版就是这么写错的，结果读到了屏幕上的黑色剪影，
     * 白白误导了一轮排查。
     *
     * <p>另外不能画进默认 framebuffer：那会污染正在显示的模型画面，
     * 而且在 GLSurfaceView 里时序不保证。
     */
    public String verifyTextureOnGpu(TextureInfo info) {
        if (info == null || !info.isValid) return "纹理无效或未加载";

        final int w = info.width;
        final int h = info.height;
        if (w <= 0 || h <= 0) return "纹理尺寸异常";

        // ---- 1) 建 FBO + 颜色附件 ----
        final int[] fbo = new int[1];
        final int[] tex = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0,
                GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, tex[0], 0);

        final int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glDeleteFramebuffers(1, fbo, 0);
            GLES20.glDeleteTextures(1, tex, 0);
            return "FBO 不完整 (0x" + Integer.toHexString(status) + ")，没法验证";
        }

        // ---- 2) 把原贴图 blit 进 FBO，然后回读 ----
        GLES20.glViewport(0, 0, w, h);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        final boolean blitOk = blitTextureToCurrentTarget(info.id);

        // ---- 3) 回读 ----
        final int sampleW = Math.min(w, 512);
        final int sampleH = Math.min(h, 512);
        final java.nio.IntBuffer buf = java.nio.ByteBuffer
                .allocateDirect(sampleW * sampleH * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asIntBuffer();
        GLES20.glReadPixels(0, 0, sampleW, sampleH, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, buf);

        long opaque = 0;
        long sumR = 0, sumG = 0, sumB = 0;
        for (int i = 0; i < sampleW * sampleH; i++) {
            final int c = buf.get(i);
            final int a = (c >>> 24) & 0xFF;
            if (a > 0) {
                opaque++;
                sumR += c & 0xFF;
                sumG += (c >> 8) & 0xFF;
                sumB += (c >> 16) & 0xFF;
            }
        }

        // ---- 4) 收拾干净 ----
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glDeleteFramebuffers(1, fbo, 0);
        GLES20.glDeleteTextures(1, tex, 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

        if (!blitOk) {
            return "blit 用的 shader 编译失败，没法验证（看日志里的 GLSL 报错）";
        }
        if (opaque == 0) {
            return "FBO 回读: 全透明 —— 说明贴图确实没上到 GPU（上传环节坏了）";
        }
        return String.format("FBO 回读: 非透明像素 %d, 平均色 RGB(%d,%d,%d)",
                opaque, sumR / opaque, sumG / opaque, sumB / opaque);
    }

    /**
     * 用一条最简单的 shader 把贴图铺满当前渲染目标。
     *
     * <p>这一步是"把贴图真正画一遍"——只有画过，framebuffer 里才有像素可回读。
     * 这也顺便验证了 GLSL 编译链路是否正常。
     */
    private boolean blitTextureToCurrentTarget(int textureId) {
        final String vs =
                "attribute vec2 a_pos;\n" +
                "attribute vec2 a_uv;\n" +
                "varying vec2 v_uv;\n" +
                "void main() {\n" +
                "  v_uv = a_uv;\n" +
                "  gl_Position = vec4(a_pos, 0.0, 1.0);\n" +
                "}\n";
        // 直接输出纹理颜色，不做任何乘算 —— 这样回读到的就是贴图原样
        final String fs =
                "precision mediump float;\n" +
                "varying vec2 v_uv;\n" +
                "uniform sampler2D u_tex;\n" +
                "void main() {\n" +
                "  gl_FragColor = texture2D(u_tex, v_uv);\n" +
                "}\n";

        final int vsId = compileShader(GLES20.GL_VERTEX_SHADER, vs);
        final int fsId = compileShader(GLES20.GL_FRAGMENT_SHADER, fs);
        if (vsId == 0 || fsId == 0) return false;

        final int prog = GLES20.glCreateProgram();
        GLES20.glAttachShader(prog, vsId);
        GLES20.glAttachShader(prog, fsId);
        GLES20.glLinkProgram(prog);
        final int[] linked = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vsId);
        GLES20.glDeleteShader(fsId);
        if (linked[0] == 0) {
            Log.e("Dafeiyu", "blit program link 失败: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            return false;
        }

        // 全屏四边形的两个三角形
        final float[] quad = {
                -1f, -1f, 0f, 0f,
                 1f, -1f, 1f, 0f,
                -1f,  1f, 0f, 1f,
                 1f,  1f, 1f, 1f,
        };
        final java.nio.FloatBuffer vb = java.nio.ByteBuffer
                .allocateDirect(quad.length * 4)
                .order(java.nio.ByteOrder.nativeOrder())
                .asFloatBuffer();
        vb.put(quad).position(0);

        GLES20.glUseProgram(prog);

        final int posLoc = GLES20.glGetAttribLocation(prog, "a_pos");
        final int uvLoc = GLES20.glGetAttribLocation(prog, "a_uv");
        final int texLoc = GLES20.glGetUniformLocation(prog, "u_tex");

        vb.position(0);
        GLES20.glEnableVertexAttribArray(posLoc);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 16, vb);
        vb.position(2);
        GLES20.glEnableVertexAttribArray(uvLoc);
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 16, vb);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glUniform1i(texLoc, 0);

        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(posLoc);
        GLES20.glDisableVertexAttribArray(uvLoc);
        GLES20.glDeleteProgram(prog);
        return true;
    }

    private static int compileShader(int type, String src) {
        final int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, src);
        GLES20.glCompileShader(id);
        final int[] ok = new int[1];
        GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            Log.e("Dafeiyu", "shader 编译失败: " + GLES20.glGetShaderInfoLog(id));
            GLES20.glDeleteShader(id);
            return 0;
        }
        return id;
    }

    /**
     * 判据：只看 Java 侧解码结果。
     *
     * <p>这才是能用的证据 —— {@code verifyTextureOnGpu} 那条路需要额外的 blit 步骤，
     * 目前只是骨架。Java 侧采样已经能确定"像素数据本身有没有颜色"。
     */
    /**
     * 第一张贴图的描述（供 UI 显示）。
     */
    public String describeFirstTexture() {
        for (java.util.Map.Entry<String, TextureInfo> e : textures.entrySet()) {
            final TextureInfo info = e.getValue();
            return String.format("%s  %dx%d  GL#%d  有效=%s",
                    e.getKey(), info.width, info.height, info.id, info.isValid);
        }
        return "还没有任何纹理";
    }

    /** 第一张贴图的 TextureInfo，供 GPU 验证用。 */
    public TextureInfo getFirstTexture() {
        for (TextureInfo info : textures.values()) {
            return info;
        }
        return null;
    }

    /** 已加载纹理数量。 */
    public int getTextureCount() {
        return textures.size();
    }

    /**
     * 采样 Bitmap，报告不透明像素占比和平均色。
     *
     * <p>这是**唯一可信的"贴图有没有内容"证据** —— 它在 Java 侧直接读像素，
     * 不经过 GL，所以不受渲染状态影响。
     */
    private static String sampleBitmap(android.graphics.Bitmap bmp) {
        final int w = bmp.getWidth();
        final int h = bmp.getHeight();
        if (w <= 0 || h <= 0) return "(空图)";

        final int stepX = Math.max(1, w / 64);
        final int stepY = Math.max(1, h / 64);
        long count = 0;
        long opaque = 0;
        long sumR = 0;
        long sumG = 0;
        long sumB = 0;

        for (int y = 0; y < h; y += stepY) {
            for (int x = 0; x < w; x += stepX) {
                final int c = bmp.getPixel(x, y);
                final int a = (c >>> 24) & 0xFF;
                count++;
                if (a > 200) {
                    opaque++;
                    sumR += (c >> 16) & 0xFF;
                    sumG += (c >> 8) & 0xFF;
                    sumB += c & 0xFF;
                }
            }
        }

        final String ratio = String.format("不透明占比 %.1f%%", 100.0 * opaque / Math.max(1, count));
        if (opaque == 0) {
            return ratio + "【警告：整张图都是透明的】";
        }
        return String.format("%s 平均色 RGB(%d,%d,%d)", ratio, sumR / opaque, sumG / opaque, sumB / opaque);
    }
}
