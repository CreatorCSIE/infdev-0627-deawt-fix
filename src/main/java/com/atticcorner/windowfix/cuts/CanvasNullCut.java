package com.atticcorner.windowfix.cuts;

import com.atticcorner.windowfix.Fingerprint;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 第一刀 · ctor canvas 置空（案卷 §28 修复 1）。
 *
 * 主类构造器 canvas 字段 PUTFIELD 之后再写 ACONST_NULL + PUTFIELD
 * （embedded → standalone 转换；0629 字段名 = B @206，0627 = E @216，按类型探测）。
 * run() 头部 GETFIELD canvas; IFNULL 原生分支完全自给 → 置 null 即翻轨（§26 第 1 条）。
 */
public final class CanvasNullCut {

    private CanvasNullCut() {
    }

    /** @return 应用的 PUTFIELD 站点数（0629/0627 实测 = 1，B 唯一写点=构造器，§13）。 */
    public static int apply(ClassNode cn, String canvasField) {
        // 先收集再插入：注入体本身含 PUTFIELD canvas，迭代中插入会自匹配（死循环教训）
        int count = 0;
        for (MethodNode m : cn.methods) {
            if (!"<init>".equals(m.name) || m.instructions == null) {
                continue;
            }
            List<FieldInsnNode> hits = new ArrayList<FieldInsnNode>();
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof FieldInsnNode) {
                    FieldInsnNode fi = (FieldInsnNode) in;
                    if (fi.getOpcode() == Opcodes.PUTFIELD
                            && fi.owner.equals(cn.name)
                            && fi.name.equals(canvasField)
                            && Fingerprint.CANVAS_DESC.equals(fi.desc)) {
                        hits.add(fi);
                    }
                }
            }
            for (FieldInsnNode fi : hits) {
                InsnList after = new InsnList();
                after.add(new VarInsnNode(Opcodes.ALOAD, 0));
                after.add(new InsnNode(Opcodes.ACONST_NULL));
                after.add(new FieldInsnNode(Opcodes.PUTFIELD, fi.owner, fi.name, fi.desc));
                m.instructions.insert(fi, after);
                count++;
            }
        }
        return count;
    }
}
