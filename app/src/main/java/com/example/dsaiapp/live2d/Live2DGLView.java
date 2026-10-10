package com.example.dsaiapp.live2d;

import android.content.Context;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 承载 Live2D 模型的 GLSurfaceView。
 *
 * <p>触摸事件直接换算成视线方向喂给模型 —— 这是挂件类 Live2D 最讨喜的交互，
 * 手指点哪儿，她就看哪儿。
 */
public class Live2DGLView extends GLSurfaceView {

    /** 视线回调，UI 层可以据此做别的反应。 */
    public interface OnModelTouchListener {
        void onTouchModel(float normalizedX, float normalizedY);
    }

    private OnModelTouchListener touchListener;
    private final Live2DRenderer renderer = new Live2DRenderer();

    public Live2DGLView(Context context) {
        this(context, null);
    }

    public Live2DGLView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        // 让 GLSurfaceView 背景透明，才能叠在 Compose 的背景上
        setZOrderOnTop(true);
        getHolder().setFormat(android.graphics.PixelFormat.TRANSLUCENT);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_CONTINUOUSLY);
    }

    public void setOnModelTouchListener(OnModelTouchListener listener) {
        this.touchListener = listener;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final Live2DModel model = Live2DDelegate.getInstance().getModel();
        if (model == null || !model.isLoaded()) {
            return super.onTouchEvent(event);
        }

        // 归一化到 -1~1，Y 轴翻过来（屏幕 Y 向下，模型 Y 向上）
        final float nx = (event.getX() / Math.max(1f, getWidth())) * 2f - 1f;
        final float ny = 1f - (event.getY() / Math.max(1f, getHeight())) * 2f;

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
            case MotionEvent.ACTION_MOVE:
                model.setLook(nx, ny);
                if (touchListener != null) {
                    touchListener.onTouchModel(nx, ny);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                model.setLook(0f, 0f);
                return true;
            default:
                return super.onTouchEvent(event);
        }
    }

    /** GL 回调转发到单例。 */
    private static final class Live2DRenderer implements GLSurfaceView.Renderer {
        @Override
        public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            Live2DDelegate.getInstance().onSurfaceCreated();
        }

        @Override
        public void onSurfaceChanged(GL10 gl, int width, int height) {
            Live2DDelegate.getInstance().onSurfaceChanged(width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            Live2DDelegate.getInstance().onDrawFrame();
        }
    }
}
