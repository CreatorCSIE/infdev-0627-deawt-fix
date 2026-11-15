package com.atticcorner.windowfix.cuts;

import com.atticcorner.windowfix.Fingerprint;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.util.List;


/**
 * 第四刀 · grab 绑屏（案卷 §32 正解）。
 *
 * 每个回中调用站点（INVOKEVIRTUAL helper.m:()V）紧贴调用之前注入：
 *   Mouse.setGrabbed(<GuiScreen 静态字段> == null)
 * ——暂停菜单/界面开（screen != null）自动释放；游戏态（screen == null）抓取。
 * 勿复刻十五轮无条件 grab（§32 定罪的过严形态）。
 *
 * 站点本身不删不动（§30 教训：回中调用是 b/c 唯一供数通道，相机靠它）。
 */
public final class GrabBindScreenCut {

    private GrabBindScreenCut() {
    }

    /** @return 注入的站点数 */
    public static int apply(String screenOwner, String screenField,
                            List<Fingerprint.Site> recenterSites) {
        int count = 0;
        for (Fingerprint.Site site : recenterSites) {
            // 紧贴调用前注入：注入体栈中性（压 ICONST 即被 setGrabbed 消费），
            // 不依赖接收者如何上栈——run()@1541 是 GETFIELD 紧邻形态，grab()@42 是
            // "getfield t; astore_1; …; aload_1" 局部变量形态（javap 实测），两者通吃。
            LabelNode free = new LabelNode();
            LabelNode set = new LabelNode();
            InsnList seq = new InsnList();
            seq.add(new FieldInsnNode(Opcodes.GETSTATIC, screenOwner, screenField,
                    "L" + screenOwner + ";"));
            seq.add(new JumpInsnNode(Opcodes.IFNONNULL, free));
            seq.add(new InsnNode(Opcodes.ICONST_1));
            seq.add(new JumpInsnNode(Opcodes.GOTO, set));
            seq.add(free);
            seq.add(new InsnNode(Opcodes.ICONST_0));
            seq.add(set);
            seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, Fingerprint.MOUSE,
                    "setGrabbed", "(Z)V", false));
            site.method.instructions.insertBefore(site.call, seq);
            count++;
        }
        return count;
    }
}
