package com.example.dsaiapp.live2d;

import android.app.Application;
import android.opengl.GLES20;

import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismFrameworkConfig;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.rendering.android.CubismShaderAndroid;

/**
 * Live2D 的全局单例：管框架生命周期、纹理、模型和投影矩阵。
 *
 * <p>支持多模型注册表，默认大肥鱼，可来回切换。
 *
 * <p>所有 GL 相关方法都必须在 GL 线程调用。
 */
public final class Live2DDelegate {

    // ------------------------------------------------------------------
    // 模型注册表：默认第 0 个
    // ------------------------------------------------------------------

    private static final Live2DModel.ModelDef[] MODELS = {
        new Live2DModel.ModelDef(
            "大肥鱼",
            "live2d/dafeiyu/",
            "dafeiyu.model3.json",
            1.0f
        ),
        new Live2DModel.ModelDef(
            "DeepSeek",
            "live2d/deepseeklive2dmodel2/deepseek_l2d/",
            "deepseek.model3.json",
            1.15f
        ),
    };

    /**
     * 舞台清屏色（不透明）。
     *
     * <p>⚠️ 为什么必须是【浅色且不透明】：模型的乘算混合 drawable 走
     * {@code glBlendFuncSeparate(GL_DST_COLOR, ...)}，也就是「结果 = 源 × 目标」。
     * 目标被清成黑色的话任何颜色乘 0 都是黑——这正是之前「模型变黑色剪影」的根因之一。
     * 清成接近白的浅色，乘算部件基本原样通过，又比纯白柔和一点。
     */
    private static final float STAGE_CLEAR_R = 0.925f;
    private static final float STAGE_CLEAR_G = 0.960f;
    private static final float STAGE_CLEAR_B = 0.990f;

    private static volatile Live2DDelegate instance;
    private static Application application;

    public static void initApplication(Application app) {
        application = app;
        CubismPal.init(app);
    }

    public static Application getApplication() {
        return application;
    }

    public static Live2DDelegate getInstance() {
        Live2DDelegate local = instance;
        if (local == null) {
            synchronized (Live2DDelegate.class) {
                local = instance;
                if (local == null) {
                    local = new Live2DDelegate();
                    instance = local;
                }
            }
        }
        return local;
    }

    private final CubismFramework.Option cubismOption = new CubismFramework.Option();
    private Live2DTextureManager textureManager;
    private Live2DModel model;
    private int currentModelIndex = 0;

    private int viewWidth = 1;
    private int viewHeight = 1;

    /** 逻辑视窗比例：决定模型在屏幕上占多大、怎么裁。 */
    private float viewScale = 1.0f;

    /** 模型在画面里的平移（NDC 单位：±1 = 半个屏幕宽/高）。volatile：UI 线程改，GL 线程读。 */
    private volatile float viewOffsetX = 0f;
    private volatile float viewOffsetY = 0f;

    /** 用户手调过的变换（位置+大小），按模型下标记住，加载模型时优先用它。 */
    private final float[] userScale = new float[MODELS.length];
    private final float[] userOffsetX = new float[MODELS.length];
    private final float[] userOffsetY = new float[MODELS.length];
    private final boolean[] userTransformSet = new boolean[MODELS.length];

    /** 缩放/平移的合理范围，防止手滑把模型拖没影或者缩成一个点。 */
    public static final float MIN_USER_SCALE = 0.25f;
    public static final float MAX_USER_SCALE = 3.0f;
    public static final float MAX_ABS_OFFSET = 1.2f;

    /** 标记模型是否已经尝试过初始化，避免每帧重复尝试。 */
    private boolean modelInitAttempted = false;

    /** 离屏渲染目标尺寸：必须在 CubismRendererAndroid 创建之前设好。 */
    private int modelRenderTargetWidth = 1;
    private int modelRenderTargetHeight = 1;

    private Live2DDelegate() {
        cubismOption.logFunction = new CubismPal.LogFunction();
        cubismOption.loggingLevel = CubismFrameworkConfig.LogLevel.WARNING;
        cubismOption.loadFileFunction = new CubismPal.LoadFileFunction();
        CubismFramework.cleanUp();
        CubismFramework.startUp(cubismOption);
        CubismPal.log("Cubism 框架已启动");
    }

    public Live2DTextureManager getTextureManager() {
        return textureManager;
    }

    public Live2DModel getModel() {
        return model;
    }

    /** 当前模型名（给 UI 显示用，主线程读安全）。 */
    public String getCurrentModelName() {
        return MODELS[currentModelIndex].name;
    }

    /** 所有模型名（UI 用来遍历推/存各自的变换）。 */
    public String[] getModelNames() {
        final String[] names = new String[MODELS.length];
        for (int i = 0; i < MODELS.length; i++) {
            names[i] = MODELS[i].name;
        }
        return names;
    }

    /** 切到下一个模型（GL 线程调用）。 */
    public void switchToNextModel() {
        // 先释放旧的
        if (model != null) {
            model.release();
            model = null;
        }
        currentModelIndex = (currentModelIndex + 1) % MODELS.length;
        modelInitAttempted = false;

        CubismPal.log("切换模型 -> " + MODELS[currentModelIndex].name);

        // 尺寸早就绪了，直接加载；失败的话下一帧 ensureModelReady 还会重试
        ensureModelReady();
    }

    /** 调整模型在画面里的整体缩放（1.0 为默认）。 */
    public void setViewScale(float scale) {
        viewScale = scale <= 0.05f ? 0.05f : scale;
    }

    public float getViewScale() {
        return viewScale;
    }

    public float getViewOffsetX() {
        return viewOffsetX;
    }

    public float getViewOffsetY() {
        return viewOffsetY;
    }

    /**
     * 拖动模型：参数是 NDC 位移（±1 = 半个屏幕）。
     * 直接累加并夹在合理范围内，越界也不会把模型弄丢。
     */
    public void moveViewBy(float dx, float dy) {
        viewOffsetX = clamp(viewOffsetX + dx, -MAX_ABS_OFFSET, MAX_ABS_OFFSET);
        viewOffsetY = clamp(viewOffsetY + dy, -MAX_ABS_OFFSET, MAX_ABS_OFFSET);
    }

    /** 双指缩放：在当前大小上乘一个系数。 */
    public void scaleViewBy(float factor) {
        if (factor <= 0f || Float.isNaN(factor) || Float.isInfinite(factor)) return;
        viewScale = clamp(viewScale * factor, MIN_USER_SCALE, MAX_USER_SCALE);
    }

    /** 把当前的位置/大小记为「用户手调的」，并记住属于当前模型。 */
    public void rememberUserTransform() {
        final int i = currentModelIndex;
        userScale[i] = viewScale;
        userOffsetX[i] = viewOffsetX;
        userOffsetY[i] = viewOffsetY;
        userTransformSet[i] = true;
    }

    /** 恢复当前模型的默认位置/大小（不改变"是否手调过"的记录）。 */
    public void resetToDefaultTransform() {
        viewScale = MODELS[currentModelIndex].viewScale;
        viewOffsetX = 0f;
        viewOffsetY = 0f;
    }

    /**
     * 由 UI 层把本地存好的变换推给某个模型（按模型名匹配）。
     *
     * <p>如果推的正好是当前模型，立刻生效；否则先存着，等切过去加载时用。
     */
    public void setUserTransformFor(String modelName, float scale, float ox, float oy) {
        for (int i = 0; i < MODELS.length; i++) {
            if (!MODELS[i].name.equals(modelName)) continue;
            userScale[i] = clamp(scale, MIN_USER_SCALE, MAX_USER_SCALE);
            userOffsetX[i] = clamp(ox, -MAX_ABS_OFFSET, MAX_ABS_OFFSET);
            userOffsetY[i] = clamp(oy, -MAX_ABS_OFFSET, MAX_ABS_OFFSET);
            userTransformSet[i] = true;
            if (i == currentModelIndex) {
                viewScale = userScale[i];
                viewOffsetX = userOffsetX[i];
                viewOffsetY = userOffsetY[i];
            }
            return;
        }
    }

    /** 当前模型的变换（scale, offX, offY），给 UI 存盘用。 */
    public float[] getCurrentTransform() {
        return new float[]{viewScale, viewOffsetX, viewOffsetY};
    }

    /** 加载模型后决定用谁的大小：手调过就用手调的，否则用模型自带的默认值。 */
    private void applyDefaultOrUserTransform(Live2DModel.ModelDef def) {
        final int i = currentModelIndex;
        if (userTransformSet[i]) {
            viewScale = userScale[i];
            viewOffsetX = userOffsetX[i];
            viewOffsetY = userOffsetY[i];
        } else {
            viewScale = def.viewScale;
            viewOffsetX = 0f;
            viewOffsetY = 0f;
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // ------------------------------------------------------------------
    // GL 回调（都在 GL 线程）
    // ------------------------------------------------------------------

    public void onSurfaceCreated() {
        if (textureManager == null) {
            textureManager = new Live2DTextureManager();
        } else {
            textureManager.releaseInvalidTextures();
        }

        // GL 上下文重建后 shader 程序也失效了
        CubismShaderAndroid.getInstance().releaseInvalidShaderProgram();
        CubismShaderAndroid.deleteInstance();

        if (!CubismFramework.isInitialized()) {
            CubismFramework.initialize();
        }

        CubismPal.updateTime();

        // ⚠️ 这里【不】加载模型。
        //
        // 原因：Android 的回调顺序是 onSurfaceCreated → onSurfaceChanged。
        // 在 onSurfaceCreated 里加载，viewWidth/viewHeight 还是初始值，
        // 而模型若使用裁剪蒙版（isUsingMasking），渲染器会按当时的尺寸
        // 创建蒙版缓冲 —— 结果就是 1×1 的蒙版，把所有 drawable 全部裁掉。

        // GL 上下文重建后，已有渲染器要重建（同样需要正确的尺寸）
        if (model != null && model.isLoaded()) {
            model.rebuildRenderer(viewWidth, viewHeight);
        }
    }

    /**
     * 在尺寸就绪之后初始化模型。在 onDrawFrame 里调用。
     *
     * @return 模型是否可用
     */
    private boolean ensureModelReady() {
        if (model != null && model.isLoaded()) return true;
        if (modelInitAttempted) return false;

        // 尺寸还没就绪就先不加载 —— 否则蒙版缓冲会被建成 1×1
        if (viewWidth <= 1 || viewHeight <= 1) {
            return false;
        }

        modelInitAttempted = true;

        final Live2DModel.ModelDef def = MODELS[currentModelIndex];
        model = new Live2DModel(def);
        model.load(viewWidth, viewHeight);
        if (!model.isLoaded()) {
            CubismPal.log("模型加载失败: " + def.name);
            model = null;
            return false;
        }

        model.setupTextures();
        applyDefaultOrUserTransform(def);
        return true;
    }

    public void onSurfaceChanged(int width, int height) {
        viewWidth = Math.max(1, width);
        viewHeight = Math.max(1, height);
        GLES20.glViewport(0, 0, viewWidth, viewHeight);

        // ⚠️ 关键：必须把视口尺寸告诉「还没被创建」的渲染器。
        //
        // 渲染器的 initialize() 里，如果模型启用了高级混合模式
        // （model.isBlendModeEnabled()），会立刻创建离屏渲染目标，
        // 这两个字段默认是 0 —— 不设好就是 0×0 的 FBO，画面上什么都没有。
        modelRenderTargetWidth = viewWidth;
        modelRenderTargetHeight = viewHeight;
        if (model != null) {
            model.setRenderTargetSize(viewWidth, viewHeight);
        }
    }

    public void onDrawFrame() {
        // 清成浅色不透明舞台（原因见 STAGE_CLEAR_* 的注释）
        GLES20.glClearColor(STAGE_CLEAR_R, STAGE_CLEAR_G, STAGE_CLEAR_B, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glEnable(GLES20.GL_BLEND);

        // 模型在尺寸就绪后才加载（见 ensureModelReady 的注释）
        if (!ensureModelReady()) return;

        final CubismMatrix44 projection = buildProjection();
        model.update();
        model.draw(projection);
    }

    /**
     * 构建正交投影矩阵。
     *
     * <p>注意：{@link CubismMatrix44} 上没有 {@code setScreenRect()}（那属于
     * {@code CubismViewMatrix}）。这里直接按列主序填 4x4，等价于把
     * [-right, right] × [-top, top] 映射到 NDC：
     * <pre>
     *   2/right    0         0  0
     *     0     2/top       0  0
     *     0       0        -1  0
     *     0       0         0  1
     * </pre>
     * 设备坐标 Y 向下，所以这里不额外翻转 —— 翻转交给 {@code CubismModelMatrix} 处理。
     */
    private CubismMatrix44 buildProjection() {
        final CubismMatrix44 projection = CubismMatrix44.create();

        float aspect = (float) viewWidth / (float) viewHeight;
        if (aspect <= 0f) aspect = 1f;

        // 竖屏（aspect < 1）时模型本来就偏窄，把 X 半宽收窄才能填满
        final float halfWidth = aspect < 1f ? aspect : 1f;
        final float halfHeight = 1f;

        final float scale = viewScale;
        final float[] m = new float[16];
        m[0] = scale / halfWidth;   // X 缩放
        m[5] = scale / halfHeight;  // Y 缩放
        m[10] = -1f;                // Z 压平
        m[12] = viewOffsetX;        // X 平移（NDC）
        m[13] = viewOffsetY;        // Y 平移（NDC）
        m[15] = 1f;                 // W
        projection.setMatrix(m);
        return projection;
    }

    public void release() {
        if (model != null) {
            model.release();
            model = null;
        }
        textureManager = null;
        CubismFramework.dispose();
        instance = null;
    }
}
