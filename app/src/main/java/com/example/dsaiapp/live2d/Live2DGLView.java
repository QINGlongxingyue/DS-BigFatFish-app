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

    /**
     * 编辑模式：拖动=移动模型，双指捏合=缩放。
     * 打开时不做视线跟随（否则一拖眼睛就乱瞟）。
     */
    private volatile boolean editMode = false;

    /** 单指拖拽的上一次位置 / 双指的上一次间距。切手指数时必须重置，否则会跳一下。 */
    private float lastX;
    private float lastY;
    private float lastSpan;
    private boolean dragging;

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

    /** 切换编辑模式（主线程/Binder 都行，只写一个 volatile 标志）。 */
    public void setEditMode(boolean on) {
        this.editMode = on;
        this.dragging = false;
        this.lastSpan = 0f;
        if (on) {
            final Live2DModel model = Live2DDelegate.getInstance().getModel();
            if (model != null) model.setLook(0f, 0f);
        }
    }

    public boolean isEditMode() {
        return editMode;
    }

    /** 两指之间的距离。 */
    private static float span(MotionEvent e) {
        if (e.getPointerCount() < 2) return 0f;
        final float dx = e.getX(0) - e.getX(1);
        final float dy = e.getY(0) - e.getY(1);
        return (float) Math.sqrt(dx * dx + dy * dy);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (editMode) {
            return handleEditTouch(event);
        }

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

    /**
     * 编辑模式下的手势：
     * - 单指拖动 → 移动（像素换算成 NDC：整屏宽 = 2）
     * - 双指捏合 → 缩放（用两指间距的比例）
     *
     * <p>手指数变化的那一帧只重置基准、不做位移，避免"抬起一根手指模型跳一下"。
     */
    private boolean handleEditTouch(MotionEvent event) {
        final Live2DDelegate delegate = Live2DDelegate.getInstance();
        final int w = Math.max(1, getWidth());
        final int h = Math.max(1, getHeight());

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastX = event.getX();
                lastY = event.getY();
                dragging = true;
                lastSpan = 0f;
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                // 第二根手指落下：记录基准间距，暂停单指拖拽
                if (event.getPointerCount() >= 2) {
                    lastSpan = span(event);
                    dragging = false;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                if (event.getPointerCount() >= 2) {
                    final float s = span(event);
                    if (lastSpan > 1f && s > 1f) {
                        delegate.scaleViewBy(s / lastSpan);
                    }
                    lastSpan = s;
                    // 双指期间不做平移：取中点容易和"缩放"的感觉打架
                } else if (dragging) {
                    final float dx = event.getX() - lastX;
                    final float dy = event.getY() - lastY;
                    // 屏幕 Y 向下，NDC 的 Y 向上，所以 dy 取负
                    delegate.moveViewBy(dx / w * 2f, -dy / h * 2f);
                    lastX = event.getX();
                    lastY = event.getY();
                }
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                // 抬起一根手指：重置基准，剩下的手指从"当前"位置继续，不会跳
                lastSpan = 0f;
                final int remain = event.getActionIndex() == 0 ? 1 : 0;
                if (event.getPointerCount() > remain) {
                    lastX = event.getX(remain);
                    lastY = event.getY(remain);
                    dragging = true;
                }
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                lastSpan = 0f;
                return true;

            default:
                return true;
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
