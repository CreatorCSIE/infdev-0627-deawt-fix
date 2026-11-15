package com.atticcorner.windowfix.cuts;

import com.atticcorner.windowfix.Fingerprint;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 第二刀·尺寸同步半刀（案卷 §3c 迭代要求 2；0.0.4 按 §3d 判词重写）。
 *
 * 0.0.3 真机判词定罪：删 `GETFIELD B; IFNULL` 整对时，其前置 ALOAD_0 接受者压栈
 * 悬空 → run() "Inconsistent stack height 3 != 2" VerifyError。读点替换同理
 * （GETFIELD 前的 ALOAD_0/中转链未随删）。
 *
 * §3d 处方（本实现）：
 *   1. 判空跳转结构保持原样——门 `GETFIELD canvas; IFNULL L` 一根指头不碰。
 *   2. 读点改写 = 纯表达式替换：`GETFIELD canvas; INVOKEVIRTUAL Canvas.getWidth`
 *      → `GETFIELD canvas; POP; INVOKESTATIC Display.getWidth`
 *      （栈收支：+1-1+1 = 原 +1，逐点记账进 trace；GETFIELD 读 null 字段无 NPE——
 *      对象引用是 this，恒非空）。
 *   3. 在 IFNULL 目标 L 处插入 standalone 同步块（fork DeAwtPatch L233-245 语义）：
 *      if (!fullscreen && Display.isCreated()
 *          && (Display.getWidth()!=b || Display.getHeight()!=c)) {
 *          a(dW, dH); b=dW; c=dH; if (canvas!=null) canvas.setSize(dW, dH); }
 *      块自身栈中性、自带新标签，不触碰任何既有跳转——嵌入路（canvas!=null）
 *      走原块（读点已同步为 Display 值，deawt 下与 canvas 尺寸等价），
 *      standalone 路（canvas==null）跳 L 后走新块。两路都绿，无需局部变量。
 *
 * 探测失败（门/全屏字段/尺寸字段/resize 应用法任一不唯一）= 整刀跳过并记账，
 * 宁缺毋滥。
 */
public final class SizeSyncCut {

    private SizeSyncCut() {
    }

    /** @return 改写的尺寸读点数（0629/0627 实测 = 4；0 = 跳过）。trace 收逐站点栈收支行。 */
    public static int apply(ClassNode cn, String canvasField, List<String> trace) {
        MethodNode run = Fingerprint.findRun(cn);
        if (run == null || run.instructions == null) {
            return 0;
        }
        InsnList il = run.instructions;

        // 1) 收集读点对（先收集再动刀，§28 自匹配教训）
        List<AbstractInsnNode[]> pairs = new ArrayList<AbstractInsnNode[]>();
        for (AbstractInsnNode in = il.getFirst(); in != null; in = in.getNext()) {
            if (isCanvasGetField(in, cn.name, canvasField) && isCanvasSizeRead(nextReal(in))) {
                pairs.add(new AbstractInsnNode[]{in, nextReal(in)});
            }
        }
        if (pairs.isEmpty()) {
            trace.add("no canvas size read pair in run() -> SKIP");
            return 0;
        }
        int firstRead = il.indexOf(pairs.get(0)[0]);

        // 2) 原门定位（保持原样，只借它的跳转目标当插入点）：GETFIELD canvas; IFNULL L，L 在首个读点之后
        JumpInsnNode gate = null;
        for (AbstractInsnNode in = il.getFirst(); in != null; in = in.getNext()) {
            if (isCanvasGetField(in, cn.name, canvasField)) {
                AbstractInsnNode nx = nextReal(in);
                if (nx instanceof JumpInsnNode && nx.getOpcode() == Opcodes.IFNULL
                        && il.indexOf(((JumpInsnNode) nx).label) > firstRead) {
                    gate = (JumpInsnNode) nx;
                    break;
                }
            }
        }
        if (gate == null) {
            trace.add("resize gate (GETFIELD canvas; IFNULL past reads) not found -> SKIP");
            return 0;
        }
        LabelNode target = gate.label;
        int gi = il.indexOf(gate);
        int li = il.indexOf(target);

        // 3) 块内探测：全屏字段(Z+IFNE) / 宽字段 b / 高字段 c / resize 应用法 (II)V
        String ff = null;
        String wF = null;
        String hF = null;
        MethodInsnNode apply = null;
        Set<String> wCand = new HashSet<String>();
        Set<String> hCand = new HashSet<String>();
        Set<String> aCand = new HashSet<String>();
        for (int i = gi; i <= li && i < il.size(); i++) {
            AbstractInsnNode n = il.get(i);
            if (n instanceof FieldInsnNode && n.getOpcode() == Opcodes.GETFIELD
                    && ((FieldInsnNode) n).owner.equals(cn.name)
                    && "Z".equals(((FieldInsnNode) n).desc)) {
                AbstractInsnNode nx = nextReal(n);
                if (nx != null && nx.getOpcode() == Opcodes.IFNE) {
                    ff = ((FieldInsnNode) n).name;
                }
            }
            if (n instanceof MethodInsnNode
                    && ((MethodInsnNode) n).getOpcode() == Opcodes.INVOKEVIRTUAL
                    || n instanceof MethodInsnNode
                    && ((MethodInsnNode) n).getOpcode() == Opcodes.INVOKESPECIAL) {
                MethodInsnNode mi = (MethodInsnNode) n;
                // 0629 实测：resize 应用法 = INVOKESPECIAL a:(II)V（私有方法，javap @2219）
                if ("(II)V".equals(mi.desc) && mi.owner.equals(cn.name)
                        && !"<init>".equals(mi.name)) {
                    apply = mi;
                    aCand.add(mi.name);
                }
            }
        }
        for (AbstractInsnNode[] p : pairs) {
            MethodInsnNode call = (MethodInsnNode) p[1];
            AbstractInsnNode nx = nextReal(call);
            if (nx instanceof FieldInsnNode && ((FieldInsnNode) nx).owner.equals(cn.name)
                    && "I".equals(((FieldInsnNode) nx).desc)
                    && (((FieldInsnNode) nx).getOpcode() == Opcodes.GETFIELD
                            || ((FieldInsnNode) nx).getOpcode() == Opcodes.PUTFIELD)) {
                (("getWidth".equals(call.name) ? wCand : hCand)).add(((FieldInsnNode) nx).name);
            }
        }
        if (ff == null || wCand.size() != 1 || hCand.size() != 1 || apply == null
                || aCand.size() != 1 || wCand.equals(hCand)) {
            trace.add("probe failed: fullscreen=" + ff + " w=" + wCand + " h=" + hCand
                    + " apply=" + aCand + " -> SKIP");
            return 0;
        }
        wF = wCand.iterator().next();
        hF = hCand.iterator().next();

        // 4) 读点纯表达式替换：GETFIELD 后插 POP，调用换 INVOKESTATIC（栈收支逐点记账）
        for (AbstractInsnNode[] p : pairs) {
            MethodInsnNode call = (MethodInsnNode) p[1];
            boolean width = "getWidth".equals(call.name);
            InsnList pop = new InsnList();
            pop.add(new InsnNode(Opcodes.POP));
            il.insert(p[0], pop);
            il.set(call, new MethodInsnNode(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY,
                    width ? "getWidth" : "getHeight", "()I", false));
            trace.add("site " + (width ? "W" : "H") + ": GETFIELD+POP+Display."
                    + (width ? "getWidth" : "getHeight") + " net=+1 (was +1)");
        }

        // 5) L 后插 standalone 同步块（fork 语义；块栈中性，不碰既有跳转）
        LabelNode end = new LabelNode();
        LabelNode doResize = new LabelNode();
        InsnList blk = new InsnList();
        blk.add(new VarInsnNode(Opcodes.ALOAD, 0));
        blk.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, ff, "Z"));
        blk.add(new JumpInsnNode(Opcodes.IFNE, end));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "isCreated", "()Z"));
        blk.add(new JumpInsnNode(Opcodes.IFEQ, end));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "getWidth", "()I"));
        blk.add(new VarInsnNode(Opcodes.ALOAD, 0));
        blk.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, wF, "I"));
        blk.add(new JumpInsnNode(Opcodes.IF_ICMPNE, doResize));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "getHeight", "()I"));
        blk.add(new VarInsnNode(Opcodes.ALOAD, 0));
        blk.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, hF, "I"));
        blk.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, end));
        blk.add(doResize);
        blk.add(new VarInsnNode(Opcodes.ALOAD, 0));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "getWidth", "()I"));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "getHeight", "()I"));
        blk.add(new MethodInsnNode(apply.getOpcode(), cn.name, apply.name, "(II)V", false));
        blk.add(new VarInsnNode(Opcodes.ALOAD, 0));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "getWidth", "()I"));
        blk.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, wF, "I"));
        blk.add(new VarInsnNode(Opcodes.ALOAD, 0));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "getHeight", "()I"));
        blk.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, hF, "I"));
        blk.add(new VarInsnNode(Opcodes.ALOAD, 0));
        blk.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, canvasField, Fingerprint.CANVAS_DESC));
        blk.add(new JumpInsnNode(Opcodes.IFNULL, end));
        blk.add(new VarInsnNode(Opcodes.ALOAD, 0));
        blk.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, canvasField, Fingerprint.CANVAS_DESC));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "getWidth", "()I"));
        blk.add(insn(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY, "getHeight", "()I"));
        blk.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/awt/Canvas",
                "setSize", "(II)V", false));
        blk.add(end);
        il.insert(target, blk);
        trace.add("block: standalone sync inserted at gate target (ff=" + ff + ", dims="
                + wF + "/" + hF + ", apply=" + apply.name + "(II)V)");
        return pairs.size();
    }

    private static MethodInsnNode insn(int op, String owner, String name, String desc) {
        return new MethodInsnNode(op, owner, name, desc, false);
    }

    private static boolean isCanvasGetField(AbstractInsnNode in, String owner, String field) {
        if (!(in instanceof FieldInsnNode)) {
            return false;
        }
        FieldInsnNode fi = (FieldInsnNode) in;
        return fi.getOpcode() == Opcodes.GETFIELD && fi.owner.equals(owner)
                && fi.name.equals(field) && Fingerprint.CANVAS_DESC.equals(fi.desc);
    }

    private static boolean isCanvasSizeRead(AbstractInsnNode in) {
        if (!(in instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode mi = (MethodInsnNode) in;
        return mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/awt/Canvas".equals(mi.owner) && "()I".equals(mi.desc)
                && ("getWidth".equals(mi.name) || "getHeight".equals(mi.name));
    }

    static AbstractInsnNode nextReal(AbstractInsnNode in) {
        AbstractInsnNode n = in == null ? null : in.getNext();
        while (n != null && Fingerprint.isPseudo(n)) {
            n = n.getNext();
        }
        return n;
    }
}
