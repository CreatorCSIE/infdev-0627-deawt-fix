package com.atticcorner.windowfix.cuts;

import com.atticcorner.windowfix.Fingerprint;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * 第三刀 · 关窗兜底（案卷 §29 r13 骨架项，实机验证过）。
 *
 * 原生循环自轮询 isCloseRequested：run() 每个 Display.update() 之后插
 *   if (Display.isCloseRequested()) System.exit(0);   （纯查询，不消耗标志，游戏自身检查不受扰）
 * 兜底路径 = wasCloseRequested → 进程自退：run() 每个 RETURN 之前插
 *   if (Display.wasCloseRequested()) System.exit(0);  （循环经 u=false 正常退出时的自退）
 *
 * exit 从游戏线程调用（非 shutdown-hook 线程），无 §26 第 4 条 exit-in-shutdown 死锁面。
 */
public final class CloseExitCut {

    private CloseExitCut() {
    }

    /** @return [update 轮询点数, return 兜底点数] */
    public static int[] apply(ClassNode cn) {
        MethodNode run = Fingerprint.findRun(cn);
        int polls = 0;
        int guards = 0;
        if (run == null || run.instructions == null) {
            return new int[]{0, 0};
        }
        // 1) Display.update() 后轮询 isCloseRequested
        for (AbstractInsnNode in = run.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof MethodInsnNode) {
                MethodInsnNode mi = (MethodInsnNode) in;
                if (mi.getOpcode() == Opcodes.INVOKESTATIC
                        && mi.owner.equals(Fingerprint.DISPLAY)
                        && mi.name.equals("update") && mi.desc.equals("()V")) {
                    LabelNode skip = new LabelNode();
                    InsnList seq = new InsnList();
                    seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY,
                            "isCloseRequested", "()Z", false));
                    seq.add(new JumpInsnNode(Opcodes.IFEQ, skip));
                    seq.add(new InsnNode(Opcodes.ICONST_0));
                    seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System",
                            "exit", "(I)V", false));
                    seq.add(skip);
                    run.instructions.insert(in, seq);
                    polls++;
                }
            }
        }
        // 2) run() 尾 RETURN 前兜底 wasCloseRequested → 自退
        for (AbstractInsnNode in = run.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.RETURN) {
                LabelNode skip = new LabelNode();
                InsnList seq = new InsnList();
                seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Fingerprint.DISPLAY,
                        "wasCloseRequested", "()Z", false));
                seq.add(new JumpInsnNode(Opcodes.IFEQ, skip));
                seq.add(new InsnNode(Opcodes.ICONST_0));
                seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System",
                        "exit", "(I)V", false));
                seq.add(skip);
                run.instructions.insertBefore(in, seq);
                guards++;
            }
        }
        return new int[]{polls, guards};
    }
}
