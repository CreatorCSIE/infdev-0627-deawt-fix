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

/**
 * 第四刀 · grab 绑屏（0.0.13 §3m 结构版：desired 在注入点字节码算，helper 零混淆字段反射）。
 *
 * 演进账：§3g 毒形=掐供数链→换绑点 update 后；§3i 门=screen==null&&d!=null；
 * §3j 毒形=每帧调 setGrabbed 泵事件→边沿触发；§3k 假说1=active 桌面恒假→删；
 * §3l 毒=反射 getDeclaredField 不查父链（at abstract）；§3m 毒=screen 项走 ae.a
 * 静态单例恒非 null（真 screen = 主类实例字段 m:Lcz / n:Lcz）。
 *
 * 0.0.13 注入形（每站点，栈收支 0）：
 *   ALOAD_0; GETFIELD at.<screen>; IFNONNULL no
 *   ALOAD_0; GETFIELD at.<d>;     IFNULL   no
 *   ICONST_1; GOTO tk
 *   no: ICONST_0
 *   tk: INVOKESTATIC GrabHelper.tick(Z)V
 * GETFIELD 由 JVM 校验期沿父链解析——层级/命名/参数绑错三类事故同时物理消失。
 */
public final class GrabBindScreenCut {

    private GrabBindScreenCut() {
    }

    /** @return 注入的站点数（= 主类 Display.update()V 调用数） */
    public static int apply(ClassNode cn, String screenField, String screenFieldDesc,
                            String worldField, String worldFieldDesc) {
        int count = 0;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKESTATIC) {
                    MethodInsnNode mi = (MethodInsnNode) in;
                    if (Fingerprint.DISPLAY.equals(mi.owner) && "update".equals(mi.name)
                            && "()V".equals(mi.desc)) {
                        LabelNode no = new LabelNode();
                        LabelNode tk = new LabelNode();
                        InsnList seq = new InsnList();
                        seq.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        seq.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, screenField,
                                screenFieldDesc));
                        seq.add(new JumpInsnNode(Opcodes.IFNONNULL, no));
                        seq.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        seq.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, worldField,
                                worldFieldDesc));
                        seq.add(new JumpInsnNode(Opcodes.IFNULL, no));
                        seq.add(new InsnNode(Opcodes.ICONST_1));
                        seq.add(new JumpInsnNode(Opcodes.GOTO, tk));
                        seq.add(no);
                        seq.add(new InsnNode(Opcodes.ICONST_0));
                        seq.add(tk);
                        seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "com/atticcorner/windowfix/GrabHelper", "tick", "(Z)V", false));
                        m.instructions.insert(in, seq);
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
