package com.atticcorner.windowfix;

import java.lang.instrument.Instrumentation;

/**
 * infdev-windowfix-agent premain 入口。
 *
 * 挂载形态：启动器 javaagent 展开固定为 -javaagent:<path>=<gameDir>，
 * agentArgs 为 gameDir 路径或 null —— 本 agent 不需要它，容忍并忽略（前案交接简报 §1）。
 *
 * 版本自门控（案卷 §2.2 指纹，0627/0629 独有）：仅当主类形态匹配才变换，不匹配 = 直通。
 * 刀面（全部有案卷出处，勿自创；0.0.5 按 §3e 判词 canvas-null 整刀移除——
 * 置空即毒：canvas==null 使 run() 判空把 robot 回中分支整体熄火，hn.a(Point) 供数死
 * → 相机死；0.0.1 基线实证不置空形态下窗（deawt setParent 自出独立窗）+ 相机皆绿）：
 *   1. Display.create 后插 setResizable + 居中（§28 修复 2）
 *      + 尺寸同步半刀（0.0.4 §3d 重写）：run() canvas 尺寸读点 POP+Display.getWidth/getHeight
 *        纯表达式替换（判空跳转保持原样）+ IFNULL 目标处插 standalone 同步块
 *        （fork resize 同步块语义 §26 第 5 条；0.0.3 门中和栈不平已定罪）
 *   2. 关窗兜底自轮询 isCloseRequested + wasCloseRequested → System.exit（§29 r13）
 *   3. grab 绑屏（0.0.13 §3m 结构版）：游戏 update 路每个 Display.update 后注入
 *      字节码算 desired = (this.<screen>==null && this.<d>!=null)（m:Lcz/n:Lcz + d:Lez/d:Ley，
 *      GETFIELD 由 JVM 校验期沿父链解析），调 GrabHelper.tick(desired)——helper 零混淆字段反射，
 *      只管边沿触发（§3j：setGrabbed 仅翻转时调）+ 遥测（-Dwindowfix.debug=true：
 *      desired 变化行 + EDGE 行 + 每 600 帧计数行）。回中序列一字不碰。
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
        // agentArgs = gameDir（=javaagent 展开注入），本 agent 不需要它。
        // 例外：含 "dump=<dir>" 时开取证落盘（试跑判词 §3b 迭代要求 1——补丁后字节需 javap 实证）；
        // 亦可用 -Dwindowfix.dump=<dir> 注入（启动器 --jvm-arg 尾注入通道）；两处任一在场即开。
        java.io.File dumpDir = parseDump(agentArgs != null && agentArgs.contains("dump=")
                ? agentArgs : System.getProperty("windowfix.dump"));
        // 逐刀 disable（§3c 迭代要求 1：运行时 A/B 分毒刀）：
        //   -Dwindowfix.disable=size-sync,display-create,close-exit,grab-bind
        //   （canvas-null 刀已按 §3e 整刀移除；未知 id 记账警告——防 A/B 打空，
        //   disable=canvas-null 在 0.0.5 上会命中警告行，即刀已不在场的实证。）
        java.util.Set<String> disabled = parseDisable(System.getProperty("windowfix.disable"));
        // §3d 迭代要求 1：-Dwindowfix.verify=true → 每刀产出当场 BasicVerifier 栈收支验证，
        // VerifyError 类问题在 premain 当场报行（0.0.3 教训：JVM 链接失败会推迟方法验证）。
        boolean verify = "true".equals(System.getProperty("windowfix.verify"));
        HonestLog.log("premain", "loaded, agentArgs ignored (gameDir not needed)"
                + (dumpDir != null ? "; dump=" + dumpDir : "")
                + (disabled.isEmpty() ? "" : "; disabled=" + disabled)
                + (verify ? "; verify=true" : ""));
        inst.addTransformer(new WindowFixTransformer(dumpDir, disabled, verify), false);
    }

    private static java.util.Set<String> parseDisable(String spec) {
        java.util.Set<String> out = new java.util.LinkedHashSet<String>();
        if (spec == null || spec.trim().isEmpty()) {
            return out;
        }
        java.util.Set<String> known = new java.util.HashSet<String>(java.util.Arrays.asList(
                WindowFixTransformer.ID_SIZE_SYNC,
                WindowFixTransformer.ID_DISPLAY_CREATE, WindowFixTransformer.ID_CLOSE_EXIT,
                WindowFixTransformer.ID_GRAB_BIND));
        for (String tok : spec.split(",")) {
            String id = tok.trim();
            if (id.isEmpty()) {
                continue;
            }
            if (!known.contains(id)) {
                HonestLog.log("disable", "unknown id '" + id + "' ignored"
                        + " (known: size-sync,display-create,close-exit,grab-bind)");
                continue;
            }
            out.add(id);
        }
        return out;
    }

    private static java.io.File parseDump(String spec) {
        if (spec == null) {
            return null;
        }
        int i = spec.indexOf("dump=");
        if (i < 0) {
            return null;
        }
        String path = spec.substring(i + 5);
        int sep = path.indexOf(';');
        if (sep >= 0) {
            path = path.substring(0, sep);
        }
        if (path.isEmpty()) {
            return null;
        }
        java.io.File dir = new java.io.File(path);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            HonestLog.log("premain", "dump dir unusable: " + dir);
            return null;
        }
        return dir;
    }
}
