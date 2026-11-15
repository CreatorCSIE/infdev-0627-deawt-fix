package com.atticcorner.windowfix;

import com.atticcorner.windowfix.cuts.CloseExitCut;
import com.atticcorner.windowfix.cuts.DisplayCreateCut;
import com.atticcorner.windowfix.cuts.GrabBindScreenCut;
import com.atticcorner.windowfix.cuts.SizeSyncCut;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 变换调度（0.0.5 §3e：canvas-null 整刀移除——置空即毒刀，robot 回中分支熄火
 *   → hn.a(Point) 供数死 → 相机死；0.0.1 基线实证不置空形态窗+相机皆绿）：
 *   主类（混淆名未知，按候选预筛 + §2.2 指纹门控）→ 尺寸同步/关窗兜底/grab 绑屏；
 *   org/lwjgl/opengl/Display（仅指纹命中后 armed）→ 开窗半刀。
 *
 * 本 agent 由启动器钉在 legacyfix 之后尾注入（前案交接简报 §2），收到的是
 * legacyfix 输出字节——匹配器全部结构化（不依赖 bci），对 MousePatch 清零形态
 * 与原始形态两式通吃（Fingerprint.rawForm 记账）。
 *
 * 逐刀 disable 开关（§3c 迭代要求 1：运行时 A/B，一次构建分出毒刀）：
 * -Dwindowfix.disable=size-sync,display-create,close-exit,grab-bind
 *
 * 任何异常 = 直通返回 null（宁可不修，不可修坏）。
 */
public final class WindowFixTransformer implements ClassFileTransformer {

    /** 逐刀 id（与 HonestLog 记账行 tag 一致）。 */
    public static final String ID_SIZE_SYNC = "size-sync";
    public static final String ID_DISPLAY_CREATE = "display-create";
    public static final String ID_CLOSE_EXIT = "close-exit";
    public static final String ID_GRAB_BIND = "grab-bind";

    private volatile boolean armed = false;
    /** 取证目录（试跑判词 §3b：补丁后字节需 javap 实证）；null = 不落盘。 */
    private final java.io.File dumpDir;
    /** 被关掉的刀 id 集（disable 开关）；空集 = 全开。 */
    private final Set<String> disabled;
    /** §3d 迭代要求 1：-Dwindowfix.verify=true → 每刀产出当场 BasicVerifier 报行。 */
    private final boolean verify;

    public WindowFixTransformer() {
        this(null, Collections.<String>emptySet(), false);
    }

    public WindowFixTransformer(java.io.File dumpDir) {
        this(dumpDir, Collections.<String>emptySet(), false);
    }

    public WindowFixTransformer(java.io.File dumpDir, Set<String> disabled) {
        this(dumpDir, disabled, false);
    }

    public WindowFixTransformer(java.io.File dumpDir, Set<String> disabled, boolean verify) {
        this.dumpDir = dumpDir;
        this.disabled = disabled == null
                ? Collections.<String>emptySet()
                : new HashSet<String>(disabled);
        this.verify = verify;
    }

    private void verifyAndLog(String internal, byte[] out) {
        if (!verify || out == null) {
            return;
        }
        java.util.List<String> errs = VerifyGate.verify(out);
        if (errs.isEmpty()) {
            HonestLog.log("verify", internal + " OK (BasicVerifier, all methods)");
        } else {
            for (String e : errs) {
                HonestLog.log("verify", internal + " FAIL " + e);
            }
        }
    }

    private boolean enabled(String id) {
        return !disabled.contains(id);
    }

    private void dump(String tag, String internal, byte[] data) {
        if (dumpDir == null || data == null) {
            return;
        }
        try {
            java.io.File f = new java.io.File(dumpDir,
                    internal.replace('/', '_') + "." + tag + ".class");
            java.io.FileOutputStream os = new java.io.FileOutputStream(f);
            os.write(data);
            os.close();
            HonestLog.log("dump", tag + " " + internal + " -> " + f + " (" + data.length + "B)");
        } catch (Throwable t) {
            HonestLog.log("dump", "FAIL " + internal + ": " + t);
        }
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null) {
            return null;
        }
        String internal = className.replace('.', '/');
        try {
            if (armed && internal.equals(Fingerprint.DISPLAY)) {
                dump("in", internal, classfileBuffer);
                if (!enabled(ID_DISPLAY_CREATE)) {
                    HonestLog.log(ID_DISPLAY_CREATE, "DISABLED (windowfix.disable)");
                    return null;
                }
                byte[] out = DisplayCreateCut.apply(classfileBuffer);
                if (out != null) {
                    HonestLog.log(ID_DISPLAY_CREATE, "applied (setResizable + center)");
                    dump("out", internal, out);
                    verifyAndLog(internal, out);
                    return out;
                }
                HonestLog.log(ID_DISPLAY_CREATE, "SKIPPED (create()V/RETURN not found)");
                return null;
            }
            if (!armed && Fingerprint.looksLikeCandidate(classfileBuffer)) {
                dump("in", internal, classfileBuffer);
                ClassNode cn = Fingerprint.read(classfileBuffer, false);
                java.util.List<String> verdict = new java.util.ArrayList<String>();
                Fingerprint.MainClassInfo info = Fingerprint.analyze(cn, verdict);
                if (info == null) {
                    HonestLog.log("fingerprint", "miss on candidate " + internal + " — pass-through");
                    for (String v : verdict) {
                        HonestLog.log("verdict", internal + " " + v);
                    }
                    return null;
                }
                HonestLog.log("fingerprint", "HIT " + internal + " (form="
                        + (info.rawForm ? "raw-direct-reads" : "post-mousepatch") + ")");

                int n2b;
                if (enabled(ID_SIZE_SYNC)) {
                    java.util.List<String> syncTrace = new java.util.ArrayList<String>();
                    n2b = SizeSyncCut.apply(cn, info.canvasField, syncTrace);
                    HonestLog.log(ID_SIZE_SYNC, n2b > 0
                            ? "applied (" + n2b + " reads -> Display via POP+STATIC; "
                                    + "gate kept; standalone block at target)"
                            : "SKIPPED (see detail lines)");
                    // §3d 迭代要求 2：size-sync 每站点栈收支明细随判词行出
                    for (String t : syncTrace) {
                        HonestLog.log(ID_SIZE_SYNC, t);
                    }
                } else {
                    n2b = 0;
                    HonestLog.log(ID_SIZE_SYNC, "DISABLED (windowfix.disable)");
                }

                // §3g：grab 重做换绑点（Display.update 后每帧一次）。必须先于 close-exit
                // 注入，close-exit 的轮询才会紧贴 update（update→poll→grab 帧尾序）。
                int n4;
                if (enabled(ID_GRAB_BIND)) {
                    if (info.worldField == null || info.screenInstField == null) {
                        n4 = 0;
                        HonestLog.log(ID_GRAB_BIND,
                                "SKIPPED (§3m gate fields not detected: world=" + info.worldField
                                        + " screen=" + info.screenInstField + " — 宁可不抓，不锁菜单)");
                    } else {
                        n4 = GrabBindScreenCut.apply(cn, info.screenInstField, info.screenInstDesc,
                                info.worldField, info.worldFieldDesc);
                        HonestLog.log(ID_GRAB_BIND, n4 > 0
                                ? "applied (" + n4 + " sites @ Display.update -> GrabHelper.tick"
                                        + "(desired), gate in bytecode: " + info.screenInstField
                                        + "==null && " + info.worldField + "!=null (edge-triggered)"
                                : "SKIPPED (no Display.update()V site)");
                    }
                } else {
                    n4 = 0;
                    HonestLog.log(ID_GRAB_BIND, "DISABLED (windowfix.disable)");
                }

                int[] n3;
                if (enabled(ID_CLOSE_EXIT)) {
                    n3 = CloseExitCut.apply(cn);
                    HonestLog.log(ID_CLOSE_EXIT, (n3[0] > 0 && n3[1] > 0)
                            ? "applied (polls=" + n3[0] + ", guards=" + n3[1] + ")"
                            : "PARTIAL (polls=" + n3[0] + ", guards=" + n3[1] + ")");
                } else {
                    n3 = new int[]{0, 0};
                    HonestLog.log(ID_CLOSE_EXIT, "DISABLED (windowfix.disable)");
                }

                armed = true;
                // §3f 要求 3：grab-bind 每站点插探针 label，写出后取真实 bci——
                // 判词行带 方法+偏移+下游调用，A/B 局 javap diff 即定位。
                java.util.List<String> grabProbeMeta = new java.util.ArrayList<String>();
                java.util.List<org.objectweb.asm.Label> grabProbes =
                        new java.util.ArrayList<org.objectweb.asm.Label>();
                if (n4 > 0) {
                    for (MethodNode m : cn.methods) {
                        for (AbstractInsnNode in = m.instructions.getFirst();
                             in != null; in = in.getNext()) {
                            if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKESTATIC) {
                                MethodInsnNode mi = (MethodInsnNode) in;
                                if ("com/atticcorner/windowfix/GrabHelper".equals(mi.owner)
                                        && "tick".equals(mi.name)) {
                                    AbstractInsnNode nx = in.getNext();
                                    while (nx != null && (nx instanceof LabelNode
                                            || nx instanceof org.objectweb.asm.tree.FrameNode
                                            || nx instanceof org.objectweb.asm.tree.LineNumberNode)) {
                                        nx = nx.getNext();
                                    }
                                    String down = nx instanceof MethodInsnNode
                                            ? ((MethodInsnNode) nx).owner + "." + ((MethodInsnNode) nx).name
                                                    + ((MethodInsnNode) nx).desc
                                            : (nx != null ? String.valueOf(nx.getOpcode()) : "END");
                                    org.objectweb.asm.Label lb = new org.objectweb.asm.Label();
                                    LabelNode probe = new LabelNode(lb);
                                    m.instructions.insertBefore(mi, probe);
                                    grabProbes.add(lb);
                                    grabProbeMeta.add(m.name + m.desc + " (before " + down + ")");
                                }
                            }
                        }
                    }
                }
                ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                cn.accept(w);
                byte[] out = w.toByteArray();
                for (int k = 0; k < grabProbes.size(); k++) {
                    try {
                        HonestLog.log(ID_GRAB_BIND, "site " + (k + 1) + " @"
                                + grabProbes.get(k).getOffset() + " in " + grabProbeMeta.get(k));
                    } catch (Throwable traceFail) {
                        HonestLog.log(ID_GRAB_BIND, "site " + (k + 1)
                                + " offset trace failed: " + traceFail);
                    }
                }
                dump("out", internal, out);
                verifyAndLog(internal, out);
                return out;
            }
        } catch (Throwable t) {
            HonestLog.log("error", internal + ": " + t);
        }
        return null;
    }
}
