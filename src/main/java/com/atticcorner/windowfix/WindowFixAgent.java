package com.atticcorner.windowfix;

import java.lang.instrument.Instrumentation;

/**
 * infdev-windowfix-agent premain 入口。
 *
 * 挂载形态：启动器 javaagent 展开固定为 -javaagent:<path>=<gameDir>，
 * agentArgs 为 gameDir 路径或 null —— 本 agent 不需要它，容忍并忽略（前案交接简报 §1）。
 *
 * 版本自门控（案卷 §2.2 指纹，0627/0629 独有）：仅当主类形态匹配才变换，不匹配 = 直通。
 * 四刀（全部有案卷出处，勿自创）：
 *   1. ctor canvas 置空（§28）
 *   2. Display.create 后插 setResizable + 居中（§28 修复 2）
 *   3. 关窗兜底自轮询 isCloseRequested + wasCloseRequested → System.exit（§29 r13）
 *   4. grab 绑屏 Mouse.setGrabbed(screenField == null)（§32 正解）
 *
 * 相机不动：真 attic.10 的 MousePatch helper 供数链在 deawt 下已绿（r13 实证），
 * agent 不做 hn/MouseHelper 手术（§30 教训：勿删回中调用，那是 b/c 唯一供数通道）。
 *
 * 诚实记账（§13 教训：注入 ≠ 执行）：每刀应用时 stderr 打一行 "[windowfix] <site> applied"。
 */
public final class WindowFixAgent {

    private WindowFixAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        // agentArgs = gameDir（=javaagent 展开注入），本 agent 忽略，仅容忍其存在。
        HonestLog.log("premain", "loaded, agentArgs ignored (gameDir not needed)");
        inst.addTransformer(new WindowFixTransformer(), false);
    }
}
