package com.example.dsaiapp

import android.app.Application
import com.example.dsaiapp.live2d.Live2DDelegate

/**
 * 在进程启动时就初始化 Cubism。
 *
 * <p>放 Application 而不是 Activity：GL 线程重建上下文的时机不受 Activity 控制，
 * 而 CubismFramework.startUp() 必须在任何模型加载之前完成。
 */
class DSAIApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Live2DDelegate.initApplication(this)
    }
}
