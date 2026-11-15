package com.atticcorner.windowfix.cuts;

import com.atticcorner.windowfix.Fingerprint;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 第二刀 · Display.create 后插（案卷 §28 修复 2）。
 *
 * org/lwjgl/opengl/Display.create() 每个 RETURN 前插：
 *   Display.setResizable(true);
 *   Display.setLocation((Toolkit.getScreenSize().width  - Display.getWidth())  / 2,
 *                       (Toolkit.getScreenSize().height - Display.getHeight()) / 2);
 * 拉伸 + 居中一次到位。create 只在开窗时执行一次，双取 getScreenSize 无成本顾虑。
 *
 * 仅当主类指纹命中（armed）后才变换 Display —— 挂载表本就只钉 0627/0629，
 * 此为第二道保险（§2.2 防御性自门控）。
 */
public final class DisplayCreateCut {

    private DisplayCreateCut() {
    }

    /** @return 变换后的字节码；create()V 不存在或无 RETURN 时返回 null（= 直通）。 */
    public static byte[] apply(byte[] classData) {
        ClassNode cn = new ClassNode();
        new ClassReader(classData).accept(cn, 0);
        MethodNode create = null;
        for (MethodNode m : cn.methods) {
            if ("create".equals(m.name) && "()V".equals(m.desc)) {
                create = m;
                break;
            }
        }
        if (create == null || create.instructions == null) {
            return null;
        }
        int count = 0;
        for (AbstractInsnNode in = create.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.RETURN) {
                create.instructions.insertBefore(in, buildSeq());
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(w);
        return w.toByteArray();
    }

    private static InsnList buildSeq() {
        InsnList seq = new InsnList();
        // setResizable(true)
        seq.add(new InsnNode(Opcodes.ICONST_1));
        seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY,
                "setResizable", "(Z)V", false));
        // x = (screen.width  - getWidth())  / 2
        seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/awt/Toolkit",
                "getDefaultToolkit", "()Ljava/awt/Toolkit;", false));
        seq.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/awt/Toolkit",
                "getScreenSize", "()Ljava/awt/Dimension;", false));
        seq.add(new FieldInsnNode(Opcodes.GETFIELD, "java/awt/Dimension", "width", "I"));
        seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY,
                "getWidth", "()I", false));
        seq.add(new InsnNode(Opcodes.ISUB));
        seq.add(new InsnNode(Opcodes.ICONST_2));
        seq.add(new InsnNode(Opcodes.IDIV));
        // y = (screen.height - getHeight()) / 2
        seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/awt/Toolkit",
                "getDefaultToolkit", "()Ljava/awt/Toolkit;", false));
        seq.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/awt/Toolkit",
                "getScreenSize", "()Ljava/awt/Dimension;", false));
        seq.add(new FieldInsnNode(Opcodes.GETFIELD, "java/awt/Dimension", "height", "I"));
        seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY,
                "getHeight", "()I", false));
        seq.add(new InsnNode(Opcodes.ISUB));
        seq.add(new InsnNode(Opcodes.ICONST_2));
        seq.add(new InsnNode(Opcodes.IDIV));
        // setLocation(x, y)
        seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY,
                "setLocation", "(II)V", false));
        return seq;
    }
}
