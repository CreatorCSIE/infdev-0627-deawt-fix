package com.atticcorner.windowfix;

import java.lang.reflect.Method;

/**
 * 第四刀运行体（0.0.13 §3m 结构版：零混淆字段反射）。
 *
 * 主类注入点在字节码里算好 desired（GETFIELD m / GETFIELD d —— JVM 校验期沿父链解析，
 * 层级/命名/参数绑错三类事故物理消失），只把 boolean 传进来。本类职责收缩为：
 *   边沿缓存（§3j：补丁后 Mouse.setGrabbed 只在 desired 翻转时调用，菜单稳态零调用零事件泵）、
 *   Mouse.setGrabbed 反射调用（固定 FQCN，非混淆字段，不属 §3l/§3m bug 类）、
 *   遥测（-Dwindowfix.debug=true：desired 变化一行 + EDGE 行 + 每 600 帧计数行）。
 *
 * 演进账：§3g 绑点 → §3i d 门 → §3j 边沿 → §3k 删 active + helper 化 →
 * §3l d 反射父链 → §3m screen 改注入点算（ae.a 是恒非 null 单例，绑错）。
 */
public final class GrabHelper {

    private static final boolean DEBUG =
            "true".equalsIgnoreCase(System.getProperty("windowfix.debug", "false"));

    private static boolean last;          // 边沿缓存（初值 false = 菜单 desired，零首帧调用）
    private static long frames;           // 站点执行计数（§3k 假说 2 遥测）
    private static Method setGrabbed;     // org.lwjgl.input.Mouse.setGrabbed 反射缓存
    private static boolean errLogged;     // 同类错误只报一次（§3l 要求 2）
    private static Boolean lDesired;      // 变化行去重

    private GrabHelper() {
    }

    /** 每个 Display.update()V 之后调用一次（desired 已在注入点算好）。异常自吞，不炸游戏循环。 */
    public static void tick(boolean desired) {
        frames++;
        if (DEBUG && (lDesired == null || lDesired.booleanValue() != desired)) {
            System.err.println("[windowfix.grab] state frame=" + frames
                    + " desired=" + desired + " last=" + last);
            lDesired = Boolean.valueOf(desired);
        }
        if (desired != last) {
            last = desired;
            try {
                grab(desired);
                if (DEBUG) {
                    System.err.println("[windowfix.grab] EDGE -> setGrabbed(" + desired
                            + ") frame=" + frames);
                }
            } catch (Throwable t) {
                logOnce("setGrabbed failed: " + t);
            }
        }
        if (DEBUG && frames % 600 == 0) {
            System.err.println("[windowfix.grab] tick frames=" + frames
                    + " desired=" + desired + " last=" + last);
        }
    }

    private static void grab(boolean on) throws Exception {
        if (setGrabbed == null) {
            // 主类即由系统 loader 加载，Mouse 同 loader；本类与其同源
            Class<?> mouse = Class.forName("org.lwjgl.input.Mouse", false,
                    GrabHelper.class.getClassLoader());
            setGrabbed = mouse.getMethod("setGrabbed", boolean.class);
        }
        setGrabbed.invoke(null, on);
    }

    private static void logOnce(String msg) {
        if (!errLogged) {
            errLogged = true;
            System.err.println("[windowfix.grab] " + msg);
        }
    }
}
