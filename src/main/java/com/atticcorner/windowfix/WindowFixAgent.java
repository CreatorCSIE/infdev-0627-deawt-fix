package com.atticcorner.windowfix;

import java.lang.instrument.Instrumentation;

/**
 * infdev-windowfix-agent premain 入口。
 *
 * 挂载形态：启动器 javaagent 展开固定为 -javaagent:<path>=<gameDir>，
 * agentArgs 为 gameDir 路径或 null —— 本 agent 不需要它，容忍并忽略。
 *
 * 版本自门控（案卷 §2.2 指纹，0627/0629 独有）：仅当主类形态匹配才变换——
 * implements Runnable + 持 Ljava/awt/Canvas; 字段 + run() 有 Display.setParent
 * 嵌入路 + Mouse.getDX/getDY 直调回中形态。不匹配 = 直通。
 *
 * 四刀（全部有案卷出处，勿自创）：
 *   1. ctor canvas 置空（§28）
 *   2. Display.create 后插 setResizable + 居中（§28 修复 2）
 *   3. 关窗兜底自轮询 isCloseRequested（§29 r13）
 *   4. grab 绑屏 Mouse.setGrabbed(screenField == null)（§32 正解）
 *
 * 诚实记账（§13 教训：注入 ≠ 执行）：每刀应用时 stderr 打一行
 * "[windowfix] <site> applied"，运行期可见。
 */
public final class WindowFixAgent {

    private WindowFixAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        // Phase 0 骨架：直通，不变换任何类。
        // Phase 1 在此注册 ClassFileTransformer，指纹匹配后交由 cuts 包四刀实现。
        System.err.println("[windowfix] loaded (skeleton, pass-through)");
    }
}
