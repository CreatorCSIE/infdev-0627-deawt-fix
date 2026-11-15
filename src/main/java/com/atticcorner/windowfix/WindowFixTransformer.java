package com.atticcorner.windowfix;

import com.atticcorner.windowfix.cuts.CanvasNullCut;
import com.atticcorner.windowfix.cuts.CloseExitCut;
import com.atticcorner.windowfix.cuts.DisplayCreateCut;
import com.atticcorner.windowfix.cuts.GrabBindScreenCut;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.tree.ClassNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

/**
 * 变换调度：
 *   主类（混淆名未知，按候选预筛 + §2.2 指纹门控）→ 第一/三/四刀；
 *   org/lwjgl/opengl/Display（仅指纹命中后 armed）→ 第二刀。
 *
 * 本 agent 由启动器钉在 legacyfix 之后尾注入（前案交接简报 §2），收到的是
 * legacyfix 输出字节——匹配器全部结构化（不依赖 bci），对 MousePatch 清零形态
 * 与原始形态两式通吃（Fingerprint.rawForm 记账）。
 *
 * 任何异常 = 直通返回 null（宁可不修，不可修坏）。
 */
public final class WindowFixTransformer implements ClassFileTransformer {

    private volatile boolean armed = false;

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        String internal = className.replace('.', '/');
        try {
            if (armed && internal.equals(Fingerprint.DISPLAY)) {
                byte[] out = DisplayCreateCut.apply(classfileBuffer);
                if (out != null) {
                    HonestLog.log("display-create", "applied (setResizable + center)");
                    return out;
                }
                HonestLog.log("display-create", "SKIPPED (create()V/RETURN not found)");
                return null;
            }
            if (!armed && Fingerprint.looksLikeCandidate(classfileBuffer)) {
                ClassNode cn = Fingerprint.read(classfileBuffer, false);
                Fingerprint.MainClassInfo info = Fingerprint.analyze(cn);
                if (info == null) {
                    HonestLog.log("fingerprint", "miss on candidate " + internal + " — pass-through");
                    return null;
                }
                HonestLog.log("fingerprint", "HIT " + internal + " (form="
                        + (info.rawForm ? "raw-direct-reads" : "post-mousepatch") + ")");

                int n1 = CanvasNullCut.apply(cn, info.canvasField);
                HonestLog.log("ctor-canvas-null", n1 > 0
                        ? "applied (" + n1 + " site, field=" + info.canvasField + ")"
                        : "SKIPPED (ctor PUTFIELD not found)");

                int[] n3 = CloseExitCut.apply(cn);
                HonestLog.log("close-exit", (n3[0] > 0 && n3[1] > 0)
                        ? "applied (polls=" + n3[0] + ", guards=" + n3[1] + ")"
                        : "PARTIAL (polls=" + n3[0] + ", guards=" + n3[1] + ")");

                if (info.screenOwner != null) {
                    int n4 = GrabBindScreenCut.apply(info.screenOwner, info.screenField,
                            info.recenterSites);
                    HonestLog.log("grab-bind", n4 > 0
                            ? "applied (" + n4 + " sites, screen=" + info.screenOwner + "." + info.screenField + ")"
                            : "SKIPPED (recenter sites vanished)");
                } else {
                    HonestLog.log("grab-bind", "SKIPPED (GuiScreen static not uniquely identified)");
                }

                armed = true;
                ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cn.accept(w);
                return w.toByteArray();
            }
        } catch (Throwable t) {
            HonestLog.log("error", internal + ": " + t);
        }
        return null;
    }
}
