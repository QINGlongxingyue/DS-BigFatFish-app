package com.example.dsaiapp.ui.live2d

import com.example.dsaiapp.live2d.Live2DDelegate
import com.example.dsaiapp.live2d.Live2DGLView

/**
 * UI 层驱动 Live2D 的入口。
 *
 * <p>模型只在 GL 线程里更新，所以任何改参数的动作都必须 `queueEvent` 过去；
 * 直接在 Compose 线程改会和渲染抢同一个对象。
 */
object Live2DController {

    @Volatile
    private var glView: Live2DGLView? = null

    /** 记录 GL 上下文是否还活着，避免在已销毁的 view 上排队。 */
    @Volatile
    private var attached = false

    fun attach(view: Live2DGLView) {
        glView = view
        attached = true
    }

    fun detach() {
        attached = false
        glView = null
    }

    /** 在 GL 线程上跑一段模型操作。 */
    private fun onGl(block: () -> Unit) {
        val view = glView ?: return
        if (!attached) return
        view.queueEvent { if (attached) block() }
    }

    // ------------------------------------------------------------------
    // 基本控制
    // ------------------------------------------------------------------

    fun setExpression(name: String) = onGl {
        Live2DDelegate.getInstance().model?.setExpression(name)
    }

    /** 立刻取消当前表情，回到默认状态。 */
    fun clearExpression() = onGl {
        Live2DDelegate.getInstance().model?.clearExpression()
    }

    fun setMouthOpen(value: Float) = onGl {
        Live2DDelegate.getInstance().model?.setMouthOpen(value)
    }

    fun setMouthForm(value: Float) = onGl {
        Live2DDelegate.getInstance().model?.setMouthForm(value)
    }

    fun setViewScale(scale: Float) = onGl {
        Live2DDelegate.getInstance().setViewScale(scale)
    }

    fun setLook(x: Float, y: Float) = onGl {
        Live2DDelegate.getInstance().model?.setLook(x, y)
    }

    fun isModelReady(): Boolean = Live2DDelegate.getInstance().model?.isLoaded == true

    /** 当前模型名（主线程直接读）。 */
    fun currentModelName(): String = Live2DDelegate.getInstance().currentModelName

    /**
     * 切到下一个模型。
     *
     * <p>切换在 GL 线程完成：释放旧模型 → 加载新模型。
     * 大模型的贴图加载要一小会儿，期间画面会先清屏成舞台色。
     */
    fun switchModel() = onGl {
        Live2DDelegate.getInstance().switchToNextModel()
    }

    /**
     * 「说句话」：有动作组的模型播说话动作，没有的（大肥鱼）用口型开合。
     */
    fun speak() = onGl {
        Live2DDelegate.getInstance().model?.onSpeak()
    }

    /**
     * 播指定动作组的第 index 个动作（DeepSeek 的 Anima 组：0=说话 1=想完 2=思考中）。
     */
    fun playMotion(group: String, index: Int) = onGl {
        Live2DDelegate.getInstance().model?.playMotion(group, index, 3)
    }
}
