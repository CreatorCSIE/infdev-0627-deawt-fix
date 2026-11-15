package com.atticcorner.windowfix;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;

import java.util.ArrayList;
import java.util.List;

/**
 * 栈收支验证闸（§3d 迭代要求 1）。
 *
 * 0.0.3 教训：harness 的 -Xverify:all 加载被 NoClassDefFoundError 推迟——
 * 链接失败先于方法验证，VerifyError 根本没机会报（"loadCheck 只到 init 推迟"）。
 *
 * 实现说明：§3d 点名的 CheckClassAdapter.verify 内部 SimpleVerifier/BasicVerifier
 * 在 ASM 7+ 已从 asm-util 移除（9.1 实测 jar 无此类），改用 asm-analysis 的
 * Analyzer(BasicInterpreter)——它在分支汇合点直接复现 JVM 验证器的
 * "Inconsistent stack height X != Y" 检查（正是 0.0.3 定罪错误一类），
 * 且只做栈形分析、无类型解析噪声，混淆类无需类路径。
 *
 * 运行时：-Dwindowfix.verify=true → 每刀产出当场验证，错误行 premain 当场报。
 */
public final class VerifyGate {

    private VerifyGate() {
    }

    /** @return 每方法一条错误（空列表 = 干净）。异常也入账（不静默）。 */
    public static List<String> verify(byte[] classData) {
        List<String> errs = new ArrayList<String>();
        ClassNode cn = new ClassNode();
        try {
            new ClassReader(classData).accept(cn, 0);
        } catch (Throwable t) {
            errs.add("parse: " + t);
            return errs;
        }
        for (MethodNode m : cn.methods) {
            if (m.instructions == null || m.instructions.size() == 0) {
                continue;
            }
            try {
                new Analyzer<BasicValue>(new BasicInterpreter()).analyze(cn.name, m);
            } catch (AnalyzerException e) {
                errs.add(m.name + m.desc + ": " + e.getMessage());
            } catch (Throwable t) {
                Throwable root = t.getCause() != null ? t.getCause() : t;
                errs.add(m.name + m.desc + ": " + root);
            }
        }
        return errs;
    }
}
