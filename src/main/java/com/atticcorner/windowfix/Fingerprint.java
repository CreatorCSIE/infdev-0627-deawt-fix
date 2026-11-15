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
import org.objectweb.asm.tree.VarInsnNode;

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
        /** §3m 真 screen 门：主类 currentScreen 实例字段（0629=m:Lcz / 0627=n:Lcz）。
         *  ae.a 是恒非 null 单例（§3m 定罪"读到恒非 null 对象"）；null = 探测失败，第四刀拒入。 */
        public final String screenInstField;
        public final String screenInstDesc;
        /** 形态：true=原始直调，false=MousePatch 产物式。 */
        public final boolean rawForm;
        /**
         * §3i 正解门：WorldRenderer 型字段（0629=d:Lez / 0627=d:Ley）——全类唯一写点
         * = setter 存参（ALOAD_0;ALOAD_1;PUTFIELD，setter 自带 null 支），构造器不初始化
         * → 开机菜单天然 null、游戏中非 null。null = 探测失败（第四刀拒入）。
         */
        public final String worldField;
        public final String worldFieldDesc;

        MainClassInfo(String className, String canvasField, String helperClass, List<Site> recenterSites,
                      String screenOwner, String screenField, boolean rawForm, String[] worldGuard,
                      String[] screenGuard) {
            this.className = className;
            this.canvasField = canvasField;
            this.helperClass = helperClass;
            this.recenterSites = recenterSites;
            this.screenOwner = screenOwner;
            this.screenField = screenField;
            this.rawForm = rawForm;
            this.worldField = worldGuard == null ? null : worldGuard[0];
            this.worldFieldDesc = worldGuard == null ? null : worldGuard[1];
            this.screenInstField = screenGuard == null ? null : screenGuard[0];
            this.screenInstDesc = screenGuard == null ? null : screenGuard[1];
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
        return analyze(cn, null);
    }

    /**
     * verdict 非空时逐项判决入列（试跑判词 §3b 迭代要求 2：miss 不许只报一句，
     * 哪项不中、现场形态长什么样，全打出来——下轮取证成本减半）。
     */
    public static MainClassInfo analyze(ClassNode cn, List<String> verdict) {
        verdict(cn, verdict, "runnable", cn.interfaces.contains("java/lang/Runnable"), null);
        if (!cn.interfaces.contains("java/lang/Runnable")) {
            return null;
        }
        String canvas = findCanvasField(cn);
        verdict(cn, verdict, "canvas-field", canvas != null, "fields=" + fieldTypes(cn));
        if (canvas == null) {
            return null;
        }
        MethodNode run = findRun(cn);
        verdict(cn, verdict, "run()V", run != null, null);
        if (run == null) {
            return null;
        }
        // run() 有 Display.setParent 嵌入路（§2.2；DeAwtPatch 只改 Display 类本体，调用点存活）
        boolean parented = hasStaticCall(run, DISPLAY, "setParent", "(Ljava/awt/Canvas;)V");
        verdict(cn, verdict, "parented", parented, null);
        if (!parented) {
            return null;
        }
        // MouseHelper 型 = 主类唯一以 new X(Ljava/awt/Component;)V 构造的类（0629=hn/0627=hl）。
        String helper = findHelperType(cn);
        verdict(cn, verdict, "helper-type", helper != null, "found=" + helper);
        if (helper == null) {
            return null;
        }
        List<Site> sites = findRecenterSites(cn, helper);
        verdict(cn, verdict, "recenter-sites", !sites.isEmpty(), "count=" + sites.size());
        if (sites.isEmpty()) {
            return null;
        }
        // 直调两式（§2.2 判据，0627/0629 独有；0625/0630 零直调）：
        //   raw = run() 有 getDX/getDY 直读；post = MousePatch 产物形态（真机定罪：
        //   补丁后直读被清零，产物残存形态以 dump 实证为准，两式任一即过闸）。
        boolean raw = hasStaticCall(run, MOUSE, "getDX", "()I")
                && hasStaticCall(run, MOUSE, "getDY", "()I");
        boolean patched = postPatchForm(cn);
        verdict(cn, verdict, "form", raw || patched,
                "raw=" + raw + " post=" + patched + " mouseRefs=" + mouseRefs(cn));
        if (!raw && !patched) {
            return null;
        }
        // GuiScreen 静态字段（§32：0629 型 = ae；主类对其同时有 ()V 与 (I)V 实例调用）
        String[] screen = findScreenStatic(cn);
        verdict(cn, verdict, "screen-static", screen != null,
                screen == null ? "candidates=" + screenCandidates(cn) : "hit=" + screen[0] + "." + screen[1]);
        // §3i：grab 正解门 = WorldRenderer 字段非空（w 门已被 0.0.8 真机定罪无效——
        // w 是"第一人称"类标志，菜单处恒真；同源守卫只保证"游戏中会走到"不保证"菜单处为假"）
        String[] world = findWorldField(cn);
        verdict(cn, verdict, "world-field", world != null,
                world == null ? "no unique single-write store-param non-lib reference field"
                        : "field=" + world[0] + ":" + world[1]);
        // §3m：screen 门 = 主类 currentScreen 实例字段（ae.a 静态单例恒非 null，定罪绑错）
        String[] screenInst = findScreenField(cn);
        verdict(cn, verdict, "screen-field", screenInst != null,
                screenInst == null ? "no unique null+obj-written field with dominant GETFIELD;IFNULL count"
                        : "field=" + screenInst[0] + ":" + screenInst[1]);
        return new MainClassInfo(cn.name, canvas, helper, sites,
                screen == null ? null : screen[0],
                screen == null ? null : screen[1],
                raw, world, screenInst);
    }

    /**
     * §3m currentScreen 实例字段探测：主类非库引用字段中，写入含 null 支与非 null 支
     * （开/关屏两路）且 `GETFIELD;IFNULL` 判空对数为严格最大（≥2）。javap 实测
     * 0629=m:Lcz(12 对) / 0627=n:Lcz(12 对)，次位 f:Lda 仅 3 对。歧义/零中 = null（第四刀拒入）。
     */
    static String[] findScreenField(ClassNode cn) {
        Map<String, int[]> stats = new LinkedHashMap<String, int[]>();
        Map<String, String> descOf = new LinkedHashMap<String, String>();
        for (FieldNode f : cn.fields) {
            if (!f.desc.startsWith("L") || !f.desc.endsWith(";")) {
                continue;
            }
            String type = f.desc.substring(1, f.desc.length() - 1);
            if (isLib(type) || type.equals(cn.name)) {
                continue;
            }
            descOf.put(f.name, f.desc);
            stats.put(f.name, new int[]{0, 0, 0}); // {ifnullPairs, nullWrites, objWrites}
        }
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (!(in instanceof FieldInsnNode)) {
                    continue;
                }
                FieldInsnNode fi = (FieldInsnNode) in;
                if (!fi.owner.equals(cn.name)) {
                    continue;
                }
                int[] st = stats.get(fi.name);
                if (st == null) {
                    continue;
                }
                if (fi.getOpcode() == Opcodes.PUTFIELD) {
                    AbstractInsnNode v = fi.getPrevious();
                    while (v != null && isPseudo(v)) {
                        v = v.getPrevious();
                    }
                    if (v != null && v.getOpcode() == Opcodes.ACONST_NULL) {
                        st[1]++;
                    } else {
                        st[2]++;
                    }
                } else if (fi.getOpcode() == Opcodes.GETFIELD) {
                    AbstractInsnNode nx = in.getNext();
                    while (nx != null && isPseudo(nx)) {
                        nx = nx.getNext();
                    }
                    if (nx != null && nx.getOpcode() == Opcodes.IFNULL) {
                        st[0]++;
                    }
                }
            }
        }
        String best = null;
        int bestPairs = 1;
        boolean tie = false;
        for (Map.Entry<String, int[]> e : stats.entrySet()) {
            int[] st = e.getValue();
            if (st[1] >= 1 && st[2] >= 1 && st[0] >= 2) {
                if (st[0] > bestPairs) {
                    bestPairs = st[0];
                    best = e.getKey();
                    tie = false;
                } else if (st[0] == bestPairs && best != null) {
                    tie = true;
                }
            }
        }
        if (best == null || tie) {
            return null;
        }
        return new String[]{best, descOf.get(best)};
    }

    /**
     * §3i WorldRenderer 字段探测：主类非库引用字段（desc=L T; ，T 非库且非自型）中，
     * 全类 PUTFIELD 恰好一次、且该写点形如 ALOAD_0;ALOAD_1;PUTFIELD（setter 存参、
     * 天然带 null 支）、且不在 <init>。javap 实测两版本各恰好一个
     * （0629=d:Lez / 0627=d:Ley；同为单写存参的 B/E:Ljava/awt/Canvas 属库型已排除）。
     * 歧义/零中 = null（第四刀拒入，宁可不抓不锁菜单）。
     */
    static String[] findWorldField(ClassNode cn) {
        List<String[]> hits = new ArrayList<String[]>();
        for (FieldNode f : cn.fields) {
            if (!f.desc.startsWith("L") || !f.desc.endsWith(";")) {
                continue;
            }
            String type = f.desc.substring(1, f.desc.length() - 1);
            if (isLib(type) || type.equals(cn.name)) {
                continue;
            }
            int writes = 0;
            boolean storeParam = false;
            for (MethodNode m : cn.methods) {
                if (m.instructions == null) {
                    continue;
                }
                for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                    if (in instanceof FieldInsnNode && in.getOpcode() == Opcodes.PUTFIELD) {
                        FieldInsnNode fi = (FieldInsnNode) in;
                        if (fi.owner.equals(cn.name) && fi.name.equals(f.name)) {
                            writes++;
                            if (!"<init>".equals(m.name)) {
                                AbstractInsnNode a1 = fi.getPrevious();
                                while (a1 != null && isPseudo(a1)) {
                                    a1 = a1.getPrevious();
                                }
                                AbstractInsnNode a0 = a1;
                                if (a1 instanceof VarInsnNode && a1.getOpcode() == Opcodes.ALOAD
                                        && ((VarInsnNode) a1).var == 1) {
                                    a0 = a0.getPrevious();
                                    while (a0 != null && isPseudo(a0)) {
                                        a0 = a0.getPrevious();
                                    }
                                    if (a0 instanceof VarInsnNode && a0.getOpcode() == Opcodes.ALOAD
                                            && ((VarInsnNode) a0).var == 0) {
                                        storeParam = true;
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (writes == 1 && storeParam) {
                hits.add(new String[]{f.name, f.desc});
            }
        }
        return hits.size() == 1 ? hits.get(0) : null;
    }

    /**
     * MousePatch 补丁后形态判据（试跑判词 §3b 定罪后按 dump 实证修订）：
     * 真链路（attic.10 mouse+deawt 全应用）补丁后 `at` 的 Mouse 残存引用实测 =
     *   destroy/create/getX/getY/setNativeCursor/next/getEvent* + isButtonDown ——
     *   getDX/getDY 被清零消失，回中路改写为读绝对坐标 getX()+getY()（原字节 @1913/@1932 本有，存活）。
     * 旧判据 setCursorPosition 实测不在主类（那是 helper 类改写体，非本类引用）→ 首跑 miss 定罪。
     * 负向排除：0625 主类 mouseRefs 无 getX/getY（verdict 实测）、0630 零 Mouse 读（javap 实测）→ 两式皆不过。
     */
    static boolean postPatchForm(ClassNode cn) {
        return hasStaticCallInClass(cn, MOUSE, "getX", "()I")
                && hasStaticCallInClass(cn, MOUSE, "getY", "()I");
    }

    private static void verdict(ClassNode cn, List<String> sink, String item, boolean ok, String detail) {
        if (sink == null) {
            return;
        }
        sink.add((ok ? "+" : "-") + " " + item + (detail != null ? " [" + detail + "]" : ""));
    }

    /** 现场取证：主类全部 Ljava/awt/Canvas; 字段与混淆字段名（miss 判决行用）。 */
    static String fieldTypes(ClassNode cn) {
        StringBuilder sb = new StringBuilder();
        for (FieldNode f : cn.fields) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(f.name).append(':').append(f.desc);
        }
        return sb.toString();
    }

    /** 现场取证：主类引用的全部 Mouse 静态方法（post 式判据修订的原始证据）。 */
    static String mouseRefs(ClassNode cn) {
        StringBuilder sb = new StringBuilder();
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode && MOUSE.equals(((MethodInsnNode) in).owner)) {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    String s = mi.name + mi.desc;
                    if (sb.indexOf(s) < 0) {
                        if (sb.length() > 0) {
                            sb.append(',');
                        }
                        sb.append(s);
                    }
                }
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    /** 现场取证：screenCandidates（自类型 GETSTATIC owner 及其被实调描述符集）。 */
    static String screenCandidates(ClassNode cn) {
        Map<String, String> selfStatics = new LinkedHashMap<String, String>();
        Map<String, Set<String>> calls = new LinkedHashMap<String, Set<String>>();
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof FieldInsnNode && in.getOpcode() == Opcodes.GETSTATIC) {
                    FieldInsnNode fi = (FieldInsnNode) in;
                    if (!isLib(fi.owner) && !fi.owner.equals(cn.name)
                            && fi.desc.equals("L" + fi.owner + ";")
                            && !selfStatics.containsKey(fi.owner)) {
                        selfStatics.put(fi.owner, fi.name);
                    }
                } else if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    Set<String> descs = calls.get(mi.owner);
                    if (descs == null) {
                        descs = new HashSet<String>();
                        calls.put(mi.owner, descs);
                    }
                    descs.add(mi.desc);
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : selfStatics.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(e.getKey()).append('.').append(e.getValue())
                    .append(calls.get(e.getKey()));
        }
        return sb.length() == 0 ? "none" : sb.toString();
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
