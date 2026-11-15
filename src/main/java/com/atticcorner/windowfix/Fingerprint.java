package com.atticcorner.windowfix;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 版本自门控（案卷 §2.2 指纹矩阵，0627/0629 独有）：
 *   主类 implements Runnable + 持 Ljava/awt/Canvas; 字段 + run() 有 Display.setParent
 *   嵌入路 + 唯一 new X(Ljava/awt/Component;)V 的 MouseHelper 型 + 其 ()V 回中调用在场。
 *
 * 直调形态两式（§2.2 判据）：
 *   raw   —— run() 有 Mouse.getDX/getDY 直调（原始字节码 / legacyfix 缺席时）；
 *   post  —— 类内出现 MousePatch 产物 Mouse.setCursorPosition 直调
 *            （真 attic.10 下 getDX/getDY 已被 CtTransformer 清零，回中调用站点仍在）。
 * 两式皆无 = 0625/0630 形态（零直调，§2.2）→ 直通。
 *
 * 挂载表（appliesFrom=appliesTo 仅 0627/0629）是第一道闸，本指纹是第二道保险。
 */
public final class Fingerprint {

    public static final String DISPLAY = "org/lwjgl/opengl/Display";
    public static final String MOUSE = "org/lwjgl/input/Mouse";
    public static final String CANVAS_DESC = "Ljava/awt/Canvas;";

    private Fingerprint() {
    }

    /** 回中调用站点：所在方法 + 调用指令。 */
    public static final class Site {
        public final MethodNode method;
        public final MethodInsnNode call;

        Site(MethodNode method, MethodInsnNode call) {
            this.method = method;
            this.call = call;
        }
    }

    /** 分析结果：四刀所需的全部挂点信息。 */
    public static final class MainClassInfo {
        public final String className;
        /** canvas 字段名（0629=B，0627=E，按类型探测，§28 修复 1）。 */
        public final String canvasField;
        /** MouseHelper 型（主类唯一以 new X(Ljava/awt/Component;)V 构造的类，0629=hn/0627=hl）。 */
        public final String helperClass;
        /** 回中调用站点（GETFIELD 本类字段:LhelperClass; → INVOKEVIRTUAL helperClass.m:()V）。 */
        public final List<Site> recenterSites;
        /** GuiScreen 静态字段所属类（0629/0627 均 = ae）；null = 探测失败，第四刀跳过并记账。 */
        public final String screenOwner;
        public final String screenField;
        /** 形态：true=原始直调，false=MousePatch 产物式。 */
        public final boolean rawForm;

        MainClassInfo(String className, String canvasField, String helperClass, List<Site> recenterSites,
                      String screenOwner, String screenField, boolean rawForm) {
            this.className = className;
            this.canvasField = canvasField;
            this.helperClass = helperClass;
            this.recenterSites = recenterSites;
            this.screenOwner = screenOwner;
            this.screenField = screenField;
            this.rawForm = rawForm;
        }
    }

    /** 廉价预筛：Runnable + Canvas 字段 + run()V 三条件（不全量解析代码）。 */
    public static boolean looksLikeCandidate(byte[] classData) {
        try {
            ClassNode cn = read(classData, true);
            if (!cn.interfaces.contains("java/lang/Runnable")) {
                return false;
            }
            if (findCanvasField(cn) == null) {
                return false;
            }
            return findRun(cn) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 全量指纹分析；不匹配返回 null（= 直通）。站点引用与入参 ClassNode 同一棵树，可直接交 cuts 变换。 */
    public static MainClassInfo analyze(ClassNode cn) {
        if (!cn.interfaces.contains("java/lang/Runnable")) {
            return null;
        }
        String canvas = findCanvasField(cn);
        if (canvas == null) {
            return null;
        }
        MethodNode run = findRun(cn);
        if (run == null) {
            return null;
        }
        // run() 有 Display.setParent 嵌入路（§2.2；DeAwtPatch 只改 Display 类本体，调用点存活）
        if (!hasStaticCall(run, DISPLAY, "setParent", "(Ljava/awt/Canvas;)V")) {
            return null;
        }
        // MouseHelper 型 = 主类唯一以 new X(Ljava/awt/Component;)V 构造的类（0629=hn/0627=hl，
        // MousePatch 不触碰构造点与调用点，两式形态下均存活）。
        String helper = findHelperType(cn);
        if (helper == null) {
            return null;
        }
        // 回中调用形态：GETFIELD 本类字段(LX;) → 紧邻 INVOKEVIRTUAL X.m:()V（X 必须 = helper）
        List<Site> sites = findRecenterSites(cn, helper);
        if (sites.isEmpty()) {
            return null;
        }
        // 直调两式（§2.2 判据，0627/0629 独有；0625/0630 零直调）
        boolean raw = hasStaticCall(run, MOUSE, "getDX", "()I")
                && hasStaticCall(run, MOUSE, "getDY", "()I");
        boolean patched = hasStaticCallInClass(cn, MOUSE, "setCursorPosition", "(II)V");
        if (!raw && !patched) {
            return null;
        }
        // GuiScreen 静态字段（§32：0629 型 = ae；主类对其同时有 ()V 与 (I)V 实例调用）
        String[] screen = findScreenStatic(cn);
        return new MainClassInfo(cn.name, canvas, helper, sites,
                screen == null ? null : screen[0],
                screen == null ? null : screen[1],
                raw);
    }

    // ---------- 内部工具 ----------

    static ClassNode read(byte[] classData, boolean skipCode) {
        ClassNode cn = new ClassNode();
        new ClassReader(classData).accept(cn, skipCode ? ClassReader.SKIP_CODE : 0);
        return cn;
    }

    public static MethodNode findRun(ClassNode cn) {
        for (MethodNode m : cn.methods) {
            if ("run".equals(m.name) && "()V".equals(m.desc)) {
                return m;
            }
        }
        return null;
    }

    static String findCanvasField(ClassNode cn) {
        for (FieldNode f : cn.fields) {
            if (CANVAS_DESC.equals(f.desc)) {
                return f.name;
            }
        }
        return null;
    }

    static boolean hasStaticCall(MethodNode m, String owner, String name, String desc) {
        return m != null && m.instructions != null
                && hasStaticCall(m.instructions, owner, name, desc);
    }

    static boolean hasStaticCall(InsnList insns, String owner, String name, String desc) {
        for (AbstractInsnNode in = insns.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) in;
                if (mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean hasStaticCallInClass(ClassNode cn, String owner, String name, String desc) {
        for (MethodNode m : cn.methods) {
            if (m.instructions != null && hasStaticCall(m.instructions, owner, name, desc)) {
                return true;
            }
        }
        return false;
    }

    /**
     * MouseHelper 型探测：主类内唯一以 new X(Ljava/awt/Component;)V 构造的非库类
     * （0629=hn @ctor581 / 0627=hl @ctor158，javap 实测两版本各恰好 1 处）。
     * 零个或多个不同 owner → null（歧义 = 直通，不猜）。
     */
    static String findHelperType(ClassNode cn) {
        String found = null;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode) {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    if (mi.getOpcode() == Opcodes.INVOKESPECIAL
                            && "<init>".equals(mi.name)
                            && "(Ljava/awt/Component;)V".equals(mi.desc)
                            && !isLib(mi.owner)) {
                        if (found == null) {
                            found = mi.owner;
                        } else if (!found.equals(mi.owner)) {
                            return null;
                        }
                    }
                }
            }
        }
        return found;
    }

    /**
     * 回中调用站点探测（第四刀挂点，§28/§32）：主类内全部 INVOKEVIRTUAL helper.m:()V。
     * javap 实测 0629 = hn.a 2 站点（run()@1541 GETFIELD 紧邻 + grab()@42 局部变量中转），
     * 0627 = hl.a 同构。helper 型已由 findHelperType 唯一锁定，其 ()V 即 flush 本体；
     * 不限定 helper 型会把任意混淆字段的 ()V 调用误计入（实测 17），第四刀污染无关调用点。
     */
    static List<Site> findRecenterSites(ClassNode cn, String helper) {
        List<Site> sites = new ArrayList<Site>();
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    if (mi.owner.equals(helper) && "()V".equals(mi.desc)) {
                        sites.add(new Site(m, mi));
                    }
                }
            }
        }
        return sites;
    }

    /**
     * GuiScreen 静态字段探测（§32）：主类引用的自类型 GETSTATIC（owner.f:Lowner;）中，
     * 唯一同时被主类以 ()V 与 (I)V 实调的 owner（0629/0627 实测 = ae.a；
     * bm 仅 ordinal()I、gi 无实调，天然排除）。
     * 恰好一个 → [owner, field]；零个或多个 → null（第四刀跳过并诚实记账）。
     */
    static String[] findScreenStatic(ClassNode cn) {
        Map<String, String> selfStatics = new LinkedHashMap<String, String>();
        Map<String, Set<String>> virtualCalls = new LinkedHashMap<String, Set<String>>();
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof FieldInsnNode && in.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode fi = (FieldInsnNode) in;
                    if (!isLib(fi.owner) && !fi.owner.equals(cn.name)
                            && fi.desc.equals("L" + fi.owner + ";")) {
                        if (!selfStatics.containsKey(fi.owner)) {
                            selfStatics.put(fi.owner, fi.name);
                        }
                    }
                } else if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    Set<String> descs = virtualCalls.get(mi.owner);
                    if (descs == null) {
                        descs = new HashSet<String>();
                        virtualCalls.put(mi.owner, descs);
                    }
                    descs.add(mi.desc);
                }
            }
        }
        List<String[]> hits = new ArrayList<String[]>();
        for (Map.Entry<String, String> e : selfStatics.entrySet()) {
            Set<String> descs = virtualCalls.get(e.getKey());
            if (descs != null && descs.contains("()V") && descs.contains("(I)V")) {
                hits.add(new String[]{e.getKey(), e.getValue()});
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    public static boolean isPseudo(AbstractInsnNode in) {
        return in instanceof org.objectweb.asm.tree.LabelNode
                || in instanceof org.objectweb.asm.tree.FrameNode
                || in instanceof org.objectweb.asm.tree.LineNumberNode;
    }

    static boolean isLib(String owner) {
        return owner.startsWith("java/") || owner.startsWith("javax/")
                || owner.startsWith("org/lwjgl") || owner.startsWith("sun/")
                || owner.startsWith("com/atticcorner");
    }
}
