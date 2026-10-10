package com.example.dsaiapp.live2d;

import com.live2d.sdk.cubism.framework.CubismFramework;
import com.live2d.sdk.cubism.framework.CubismModelSettingJson;
import com.live2d.sdk.cubism.framework.ICubismModelSetting;
import com.live2d.sdk.cubism.framework.id.CubismId;
import com.live2d.sdk.cubism.framework.id.CubismIdManager;
import com.live2d.sdk.cubism.framework.math.CubismMatrix44;
import com.live2d.sdk.cubism.framework.motion.ACubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionMotion;
import com.live2d.sdk.cubism.framework.motion.CubismExpressionUpdater;
import com.live2d.sdk.cubism.framework.motion.CubismMotion;
import com.live2d.sdk.cubism.framework.motion.CubismMotionManager;
import com.live2d.sdk.cubism.framework.motion.CubismPhysicsUpdater;
import com.live2d.sdk.cubism.framework.model.CubismUserModel;
import com.live2d.sdk.cubism.framework.rendering.CubismRenderer;
import com.live2d.sdk.cubism.framework.rendering.android.CubismRendererAndroid;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用 Live2D 模型：不再绑定具体某只皮。
 *
 * <p>同一套代码要伺候两种模型：
 * <ul>
 *   <li><b>大肥鱼</b>：VTube Studio 导出，没有任何动作文件，待机动画（呼吸/眨眼/视线）
 *       全靠手写；表情是「参数开关」型 exp3。</li>
 *   <li><b>DeepSeek</b>：自带 Idle / Anima 动作组和标准 exp3 表情。动作播放期间
 *       关掉手写参数，让动作文件接管。</li>
 * </ul>
 */
public final class Live2DModel extends CubismUserModel {

    /** 模型的静态描述：名字 + assets 路径 + 默认视野缩放。 */
    public static final class ModelDef {
        public final String name;
        public final String assetDir;
        public final String modelJson;
        /** 模型在画面里的默认缩放（不同模型的画布大小不一样）。 */
        public final float viewScale;

        public ModelDef(String name, String assetDir, String modelJson, float viewScale) {
            this.name = name;
            this.assetDir = assetDir;
            this.modelJson = modelJson;
            this.viewScale = viewScale;
        }
    }

    // 动作优先级（对齐官方 Sample 的约定）
    private static final int PRIORITY_NONE = 0;
    private static final int PRIORITY_IDLE = 1;
    private static final int PRIORITY_FORCE = 3;

    private final ModelDef def;

    private final Map<String, ACubismMotion> expressionMap = new HashMap<>();
    /** 已加载的动作，key = "组名_序号"，如 "Idle_0"、"Anima_1"。 */
    private final Map<String, CubismMotion> motionMap = new HashMap<>();
    private boolean hasIdleMotion = false;

    /**
     * 水印隐藏参数：从 model3.json 注册的 watermark_off 表情（exp13 水印消除，
     * Paramcol_3 = 1.0）里解析出来，每帧压进模型。
     *
     * <p>不能只在加载时设一次：{@code update()} 开头的 {@code loadParameters()}
     * 会把保存值读回来。也不能用表情系统播一次了事：下一个表情（比如关键字触发的
     * happy）会顶掉它，水印就又回来了。
     */
    private final List<CubismId> watermarkParamIds = new ArrayList<>();
    private final List<Float> watermarkParamValues = new ArrayList<>();

    private final CubismId idParamEyeLOpen;
    private final CubismId idParamEyeROpen;
    private final CubismId idParamMouthOpenY;
    private final CubismId idParamMouthForm;
    private final CubismId idParamEyeBallX;
    private final CubismId idParamEyeBallY;
    private final CubismId idParamAngleX;
    private final CubismId idParamAngleY;
    private final CubismId idParamBodyAngleX;
    private final CubismId idParamBreath;

    private ICubismModelSetting modelSetting;

    // ---- 手动驱动的状态（仅在没播动作时生效）----
    private float mouthOpen = 0f;
    private float mouthForm = 0f;
    private float lookX = 0f;
    private float lookY = 0f;

    /** 眨眼状态机的相位：0=睁着等，1=正在闭，2=正在睁 */
    private int blinkPhase = 0;
    private float blinkTimer = 0f;
    private float blinkValue = 1f;

    private float breathPhase = 0f;

    private boolean loaded = false;

    /**
     * 渲染器是否按预乘 alpha 处理。
     *
     * <p>Android 的 BitmapFactory 解码 ARGB_8888 时会自动预乘，
     * GLUtils.texImage2D 原样上传，所以这里保持 true 才和纹理字节一致。
     */
    private boolean premultipliedAlpha = true;

    /**
     * 是否每帧把水印 / 参考图部件的不透明度压到 0。
     *
     * <p>⚠️ 必须每帧都压：{@code update()} 开头的 {@code model.loadParameters()} 会从
     * 保存状态把不透明度读回来，只在加载后设一次下一帧就恢复。
     */
    private boolean hideWatermark = true;

    public Live2DModel(ModelDef def) {
        this.def = def;
        final CubismIdManager idManager = CubismFramework.getIdManager();
        idParamEyeLOpen = idManager.getId("ParamEyeLOpen");
        idParamEyeROpen = idManager.getId("ParamEyeROpen");
        idParamMouthOpenY = idManager.getId("ParamMouthOpenY");
        idParamMouthForm = idManager.getId("ParamMouthForm");
        idParamEyeBallX = idManager.getId("ParamEyeBallX");
        idParamEyeBallY = idManager.getId("ParamEyeBallY");
        idParamAngleX = idManager.getId("ParamAngleX");
        idParamAngleY = idManager.getId("ParamAngleY");
        idParamBodyAngleX = idManager.getId("ParamBodyAngleX");
        idParamBreath = idManager.getId("ParamBreath");
    }

    public ModelDef getDef() {
        return def;
    }

    public boolean isLoaded() {
        return loaded;
    }

    // ------------------------------------------------------------------
    // 加载 / 释放
    // ------------------------------------------------------------------

    public void load(int viewWidth, int viewHeight) {
        final String modelJsonPath = def.assetDir + def.modelJson;
        final byte[] json = CubismPal.loadFileAsBytes(modelJsonPath);
        if (json.length == 0) {
            CubismPal.log("model3.json 读不到：" + modelJsonPath);
            return;
        }

        modelSetting = new CubismModelSettingJson(json);
        if (modelSetting.getJson() == null) {
            CubismPal.log("model3.json 解析失败：" + modelJsonPath);
            return;
        }

        // --- moc3 ---
        final String mocName = modelSetting.getModelFileName();
        if (mocName != null && !mocName.isEmpty()) {
            loadModel(CubismPal.loadFileAsBytes(def.assetDir + mocName), false);
        }
        if (model == null) {
            CubismPal.log("模型本体加载失败: " + mocName);
            return;
        }

        // --- 渲染器 ---
        // 必须在 create 之前把离屏目标尺寸设好：模型若启用高级混合模式，
        // initialize() 里会立刻按这个尺寸建离屏 FBO，给 0 就会画进 0×0 的缓冲。
        setRenderTargetSize(viewWidth, viewHeight);

        CubismPal.log("模型能力[" + def.name + "]: isBlendModeEnabled=" + model.isBlendModeEnabled()
                + " isUsingMasking=" + model.isUsingMasking()
                + " renderTarget=" + viewWidth + "x" + viewHeight);

        final CubismRenderer renderer = CubismRendererAndroid.create(viewWidth, viewHeight);
        setupRenderer(renderer);

        // 诊断：moc3 原生乘算/屏蔽色。乘算色若是黑的（某些 VTS 导出的皮有这毛病），
        // shader 里 贴图色×乘算色 = 黑，会再次出现"黑色剪影"。看日志即知，不用猜。
        final com.live2d.sdk.cubism.framework.model.CubismModelMultiplyAndScreenColor colorOverride =
                model.getOverrideMultiplyAndScreenColor();
        for (int i = 0; i < Math.min(4, model.getDrawableCount()); i++) {
            final CubismRenderer.CubismTextureColor mc = colorOverride.getDrawableMultiplyColor(i);
            final CubismRenderer.CubismTextureColor sc = colorOverride.getDrawableScreenColor(i);
            CubismPal.log(String.format("drawable[%d] 原生乘算色 (%.2f,%.2f,%.2f,%.2f) 屏蔽色 (%.2f,%.2f,%.2f,%.2f)",
                    i, mc.r, mc.g, mc.b, mc.a, sc.r, sc.g, sc.b, sc.a));
        }

        // --- 表情（走 modelSetting 的 Expressions 列表）---
        final int expCount = modelSetting.getExpressionCount();
        for (int i = 0; i < expCount; i++) {
            final String name = modelSetting.getExpressionName(i);
            final String file = modelSetting.getExpressionFileName(i);
            if (file == null || file.isEmpty()) continue;
            final byte[] buf = CubismPal.loadFileAsBytes(def.assetDir + file);
            if (buf.length == 0) continue;
            final CubismExpressionMotion motion = loadExpression(buf);
            if (motion != null) {
                expressionMap.put(name, motion);
            }
        }
        if (!expressionMap.isEmpty()) {
            updateScheduler.addUpdatableList(new CubismExpressionUpdater(expressionManager));
        }

        // --- 水印隐藏（watermark_off 表情 → 每帧压参数）---
        loadWatermarkParams();

        // --- 动作（Idle / Anima 等组，DeepSeek 模型才有）---
        loadAllMotions();

        // --- 物理（头发/尾巴摆动全靠它）---
        final String physicsName = modelSetting.getPhysicsFileName();
        if (physicsName != null && !physicsName.isEmpty()) {
            loadPhysics(CubismPal.loadFileAsBytes(def.assetDir + physicsName));
            if (physics != null) {
                updateScheduler.addUpdatableList(new CubismPhysicsUpdater(physics));
            }
        }

        updateScheduler.sortUpdatableList();

        // --- 布局 ---
        final Map<String, Float> layout = new HashMap<>();
        if (modelSetting.getLayoutMap(layout)) {
            modelMatrix.setupFromLayout(layout);
        }

        model.saveParameters();

        loaded = true;
        CubismPal.log(def.name + " 加载完成：表情 " + expressionMap.size()
                + " 个，动作 " + motionMap.size() + " 个"
                + "，贴图 " + modelSetting.getTextureCount() + " 张"
                + "，drawable " + model.getDrawableCount() + " 个");
    }

    /** 把 model3.json 里所有动作组全部加载进内存。 */
    private void loadAllMotions() {
        final int groupCount = modelSetting.getMotionGroupCount();
        for (int g = 0; g < groupCount; g++) {
            final String group = modelSetting.getMotionGroupName(g);
            if (group == null || group.isEmpty()) continue;
            final int count = modelSetting.getMotionCount(group);
            for (int i = 0; i < count; i++) {
                final String file = modelSetting.getMotionFileName(group, i);
                if (file == null || file.isEmpty()) continue;
                final byte[] buf = CubismPal.loadFileAsBytes(def.assetDir + file);
                if (buf.length == 0) continue;

                final CubismMotion motion = loadMotion(buf, false);
                if (motion == null) continue;

                // model3.json 里写了 Fade 就尊重它
                final float fadeIn = modelSetting.getMotionFadeInTimeValue(group, i);
                if (fadeIn != -1.0f) motion.setFadeInTime(fadeIn);
                final float fadeOut = modelSetting.getMotionFadeOutTimeValue(group, i);
                if (fadeOut != -1.0f) motion.setFadeOutTime(fadeOut);

                motionMap.put(group + "_" + i, motion);
                if ("Idle".equals(group)) {
                    hasIdleMotion = true;
                }
            }
        }
    }

    public void release() {
        delete();
        loaded = false;
    }

    /** GL 上下文重建后必须重建渲染器与纹理绑定。 */
    public void rebuildRenderer(int viewWidth, int viewHeight) {
        if (model == null || modelSetting == null) return;
        deleteRenderer();
        final CubismRenderer renderer = CubismRendererAndroid.create(viewWidth, viewHeight);
        setupRenderer(renderer);
        bindTextures();
    }

    private void bindTextures() {
        final Live2DTextureManager texManager = Live2DDelegate.getInstance().getTextureManager();
        if (texManager == null || modelSetting == null) return;

        for (int i = 0; i < modelSetting.getTextureCount(); i++) {
            final String name = modelSetting.getTextureFileName(i);
            if (name == null || name.isEmpty()) continue;

            final Live2DTextureManager.TextureInfo info =
                    texManager.createTextureFromPngFile(def.assetDir + name);
            if (info == null) {
                CubismPal.log("纹理 " + i + " 加载失败：" + name);
                continue;
            }

            final CubismRendererAndroid renderer = getRenderer();
            // 显式把单元切到 0，和 shader 里的 s_texture0 = 0 对齐
            android.opengl.GLES20.glActiveTexture(android.opengl.GLES20.GL_TEXTURE0);
            renderer.bindTexture(i, info.id);
            renderer.isPremultipliedAlpha(premultipliedAlpha);
            CubismPal.log("绑定纹理 " + i + " -> GL#" + info.id
                    + " " + info.width + "x" + info.height
                    + " premultipliedAlpha=" + premultipliedAlpha);
        }
    }

    /** 首次加载完纹理（需要在 GL 线程调用）。 */
    public void setupTextures() {
        bindTextures();
    }

    // ------------------------------------------------------------------
    // 每帧更新
    // ------------------------------------------------------------------

    public void update() {
        if (!loaded || model == null) return;

        CubismPal.updateTime();
        final float dt = CubismPal.getDeltaTime();

        isUpdated(false);
        model.loadParameters();

        // 必须在 loadParameters() 之后：它会把保存的值读回来
        if (hideWatermark) {
            applyWatermarkParams();
        }

        // --- 动作驱动（DeepSeek）---
        // 播完自动回到 Idle；动作在跑的时候手写参数全部让位。
        boolean motionDriving = false;
        if (hasIdleMotion) {
            if (motionManager.isFinished()) {
                playMotionGroup("Idle", PRIORITY_IDLE);
            } else {
                motionManager.updateMotion(model, dt);
                motionDriving = true;
            }
        }

        if (!motionDriving) {
            // --- 手写待机动画（大肥鱼）---
            updateBlink(dt);
            updateBreath(dt);

            model.setParameterValue(idParamEyeLOpen, blinkValue);
            model.setParameterValue(idParamEyeROpen, blinkValue);
            model.setParameterValue(idParamMouthOpenY, mouthOpen);
            model.setParameterValue(idParamMouthForm, mouthForm);
            model.setParameterValue(idParamEyeBallX, lookX);
            model.setParameterValue(idParamEyeBallY, lookY);
            model.setParameterValue(idParamAngleX, lookX * 30f);
            model.setParameterValue(idParamAngleY, lookY * 30f);
            model.setParameterValue(idParamBodyAngleX, lookX * 10f);
            model.setParameterValue(idParamBreath, 0.5f + 0.5f * (float) Math.sin(breathPhase));
        } else if (mouthOpen > 0f) {
            // 动作期间口型仍归 UI 管（LipSync 参数组是空的）
            model.setParameterValue(idParamMouthOpenY, mouthOpen);
            model.setParameterValue(idParamMouthForm, mouthForm);
        }

        model.saveParameters();

        // 表情 / 物理
        updateScheduler.onLateUpdate(model, dt);

        model.update();
        isUpdated(true);
    }

    public void draw(CubismMatrix44 projection) {
        if (!loaded || model == null) return;

        CubismMatrix44.multiply(
                modelMatrix.getArray(),
                projection.getArray(),
                projection.getArray());
        this.<CubismRendererAndroid>getRenderer().setMvpMatrix(projection);
        this.<CubismRendererAndroid>getRenderer().drawModel();
    }

    // ------------------------------------------------------------------
    // 手动动画
    // ------------------------------------------------------------------

    /**
     * 眨眼状态机。
     * <p>大肥鱼的 model3.json 里 EyeBlink 参数组是空的，框架不会自动眨眼，只能自己算；
     * DeepSeek 播动作时也不会走到这里。
     */
    private void updateBlink(float dt) {
        blinkTimer -= dt;
        switch (blinkPhase) {
            case 0: // 睁着
                blinkValue = 1f;
                if (blinkTimer <= 0f) {
                    blinkPhase = 1;
                    blinkTimer = 0.055f; // 闭眼用 55ms
                }
                break;
            case 1: // 闭
                blinkValue = 1f - (1f - blinkTimer / 0.055f);
                if (blinkTimer <= 0f) {
                    blinkPhase = 2;
                    blinkTimer = 0.09f; // 睁眼用 90ms
                }
                break;
            default: // 睁
                blinkValue = (0.09f - blinkTimer) / 0.09f;
                if (blinkTimer <= 0f) {
                    blinkPhase = 0;
                    // 1.6~5.4 秒随机间隔，比定时器自然
                    blinkTimer = 1.6f + (float) (Math.random() * 3.8f);
                }
                break;
        }
        if (blinkValue < 0f) blinkValue = 0f;
        if (blinkValue > 1f) blinkValue = 1f;
    }

    private void updateBreath(float dt) {
        breathPhase += dt * 1.6f;
        if (breathPhase > Math.PI * 2) breathPhase -= (float) (Math.PI * 2);
    }

    // ------------------------------------------------------------------
    // 外部控制接口
    // ------------------------------------------------------------------

    /** 口型张开度 0~1，接 TTS 的音量包络。 */
    public void setMouthOpen(float value) {
        mouthOpen = clamp01(value);
    }

    /** 嘴型 -1~1（正=笑）。 */
    public void setMouthForm(float value) {
        mouthForm = Math.max(-1f, Math.min(1f, value));
    }

    /** 视线方向 -1~1。 */
    public void setLook(float x, float y) {
        lookX = Math.max(-1f, Math.min(1f, x));
        lookY = Math.max(-1f, Math.min(1f, y));
    }

    /** 表情动作用完就自己淡出。 */
    public void setExpression(String name) {
        if (!loaded) return;
        final ACubismMotion motion = expressionMap.get(name);
        if (motion == null) {
            CubismPal.log("没有这个表情: " + name + "（现有: " + expressionMap.keySet() + "）");
            return;
        }
        expressionManager.startMotionPriority(motion, PRIORITY_FORCE);
    }

    public String[] expressionNames() {
        return expressionMap.keySet().toArray(new String[0]);
    }

    /**
     * 按组名播一个动作（随机挑组内一个）。
     *
     * @return 是否真的开始播了
     */
    public boolean playMotionGroup(String group, int priority) {
        if (!loaded || motionMap.isEmpty()) return false;

        // 数一下这组有几个
        final List<String> keys = new ArrayList<>();
        for (int i = 0; ; i++) {
            final CubismMotion m = motionMap.get(group + "_" + i);
            if (m == null) break;
            keys.add(group + "_" + i);
        }
        if (keys.isEmpty()) return false;

        final String key = keys.get((int) (Math.random() * keys.size()));
        final CubismMotion motion = motionMap.get(key);
        if (priority == PRIORITY_FORCE) {
            motionManager.setReservationPriority(priority);
        } else if (!motionManager.reserveMotion(priority)) {
            return false;
        }
        motionManager.startMotionPriority(motion, priority);
        CubismPal.log("播动作: " + key);
        return true;
    }

    /**
     * 播指定组内第 index 个动作。
     * <p>DeepSeek 模型的 Anima 组约定：0=说话 speak，1=想完 thinkend，2=思考中 thinking。
     */
    public boolean playMotion(String group, int index, int priority) {
        if (!loaded) return false;
        final CubismMotion motion = motionMap.get(group + "_" + index);
        if (motion == null) return false;

        if (priority == PRIORITY_FORCE) {
            motionManager.setReservationPriority(priority);
        } else if (!motionManager.reserveMotion(priority)) {
            return false;
        }
        motionManager.startMotionPriority(motion, priority);
        CubismPal.log("播动作: " + group + "_" + index);
        return true;
    }

    /** 说话时调用：有「说话动作」的模型播动作，没有的用口型开合。 */
    public void onSpeak() {
        if (!playMotion("Anima", 0, PRIORITY_FORCE)) {
            setMouthOpen(1f);
        }
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // ------------------------------------------------------------------
    // 水印隐藏（大肥鱼：watermark_off 表情 = Paramcol_3 → 1.0）
    // ------------------------------------------------------------------

    /**
     * 从 model3.json 的表情列表里找 watermark_off，把它的参数解析出来。
     * 用 org.json（Android 自带）直接读 exp3.json，不经过 Cubism 的 Json 层。
     */
    private void loadWatermarkParams() {
        if (modelSetting == null) return;
        for (int i = 0; i < modelSetting.getExpressionCount(); i++) {
            if (!"watermark_off".equals(modelSetting.getExpressionName(i))) continue;

            final String file = modelSetting.getExpressionFileName(i);
            if (file == null || file.isEmpty()) return;
            final byte[] buf = CubismPal.loadFileAsBytes(def.assetDir + file);
            if (buf.length == 0) return;

            try {
                final org.json.JSONObject json = new org.json.JSONObject(
                        new String(buf, java.nio.charset.StandardCharsets.UTF_8));
                final org.json.JSONArray params = json.getJSONArray("Parameters");
                for (int p = 0; p < params.length(); p++) {
                    final org.json.JSONObject o = params.getJSONObject(p);
                    watermarkParamIds.add(CubismFramework.getIdManager().getId(o.getString("Id")));
                    watermarkParamValues.add((float) o.optDouble("Value", 1.0));
                }
                CubismPal.log("水印隐藏参数已就位: " + watermarkParamIds.size() + " 个");
            } catch (Exception e) {
                CubismPal.log("watermark_off 表情解析失败: " + e);
            }
            return;
        }
    }

    /** 每帧把水印参数压进去（在 loadParameters 之后调用才有效）。 */
    private void applyWatermarkParams() {
        if (model == null) return;
        for (int i = 0; i < watermarkParamIds.size(); i++) {
            model.setParameterValue(watermarkParamIds.get(i), watermarkParamValues.get(i));
        }
    }

    public void setHideWatermark(boolean value) {
        hideWatermark = value;
    }

    // ------------------------------------------------------------------
    // 运行时切换预乘 alpha（排查用，正常情况别动）
    // ------------------------------------------------------------------

    public void setPremultipliedAlpha(boolean value) {
        premultipliedAlpha = value;
        if (getRenderer() != null) {
            this.<CubismRendererAndroid>getRenderer().isPremultipliedAlpha(value);
        }
        CubismPal.log("premultipliedAlpha -> " + value);
    }

    public boolean isPremultipliedAlpha() {
        return premultipliedAlpha;
    }

    // ------------------------------------------------------------------
    // 供 UI 自检用的安全取值
    // ------------------------------------------------------------------

    public int getPartCountSafe() {
        return model == null ? 0 : model.getPartCount();
    }

    public int getTextureCountSafe() {
        return modelSetting == null ? 0 : modelSetting.getTextureCount();
    }

    public int getExpressionCountSafe() {
        return expressionMap.size();
    }

    public String getModelSettingProblem() {
        if (modelSetting == null) return "model3.json 没读进来";
        if (modelSetting.getJson() == null) return "model3.json 解析失败";
        if (model == null) return "moc3 没加载成功（文件名: "
                + modelSetting.getModelFileName() + "）";
        return null;
    }
}
