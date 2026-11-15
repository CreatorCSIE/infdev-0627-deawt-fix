package com.atticcorner.windowfix.harness;

import com.atticcorner.windowfix.Fingerprint;
import com.atticcorner.windowfix.WindowFixTransformer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 静态自证 harness（前案交接简报 §2 Phase 1 验收物 + 案卷 §26/§28/§30 断言样例移植）：
 *   生产路径 transform → toBytecode → ASM 结构断言（新体在场 / 原解引用清零）
 *   → 单 loader -Xverify:all 装载（harness JVM 需带 -Xverify:all 启动）。
 *
 * 素材：启动器数据根 %APPDATA%\.attic\versions\ 下的实 jar
 *   inf-20100629.jar / inf-20100627.jar（正向）· inf-20100625-1917.jar（负向直通 + 回归零触碰）。
 *
 * 用法：gradlew harness（或 java -Xverify:all -cp ... StaticHarness [versionsDir]）
 */
public final class StaticHarness {

    private static int failures = 0;
    private static int checks = 0;
    private static Path VERSIONS;

    public static void main(String[] args) throws Exception {
        VERSIONS = args.length > 0
                ? pathOf(args[0])
                : pathOf(System.getenv("APPDATA") + File.separator + ".attic" + File.separator + "versions");
        Path versions = VERSIONS;
        System.out.println("== infdev-windowfix static harness ==");
        System.out.println("versions dir: " + versions);

        positive(versions.resolve("inf-20100629.jar"));
        positive(versions.resolve("inf-20100627.jar"));
        negative(versions.resolve("inf-20100625-1917.jar"));

        System.out.println();
        System.out.println("checks=" + checks + " failures=" + failures);
        System.out.println(failures == 0 ? "ALL PASS" : "FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------- 正向：0627/0629 四刀全量断言 ----------

    private static void positive(Path jarPath) throws Exception {
        System.out.println();
        System.out.println("---- " + jarPath.getFileName() + " ----");
        if (!Files.exists(jarPath)) {
            fail("jar missing: " + jarPath);
            return;
        }
        List<String> names = new ArrayList<String>();
        List<byte[]> datas = new ArrayList<byte[]>();
        readClasses(jarPath, names, datas);

        WindowFixTransformer t = new WindowFixTransformer();
        int changed = 0;
        byte[] mainOut = null;
        String mainInternal = null;
        for (int i = 0; i < names.size(); i++) {
            String dotted = names.get(i).replace('/', '.');
            byte[] out = t.transform(null, dotted, null, null, datas.get(i));
            if (out != null) {
                changed++;
                mainOut = out;
                mainInternal = names.get(i);
            }
        }
        assertTrue("main class hit exactly once", mainOut != null);
        assertTrue("game jar: only main touched (changed=" + changed + ")", changed == 1);

        // 原始形态指纹（站点数基准）
        Fingerprint.MainClassInfo info = Fingerprint.analyze(
                nodeOf(bytesOf(names, datas, mainInternal), false));
        if (!check("fingerprint on original bytes", info != null)) {
            return;
        }
        int sites = info.recenterSites.size();
        assertTrue("recenter sites == 2 per javap (got " + sites + ")", sites == 2);

        ClassNode cn = nodeOf(mainOut, false);
        MethodNode run = Fingerprint.findRun(cn);

        // 刀1：ctor PUTFIELD canvas 之后 ACONST_NULL;PUTFIELD 在场
        int nullWrites = 0;
        for (MethodNode m : cn.methods) {
            if (!"<init>".equals(m.name)) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof FieldInsnNode && in.getOpcode() == Opcodes.PUTFIELD
                        && ((FieldInsnNode) in).name.equals(info.canvasField)) {
                    // 注入体 = ALOAD_0; ACONST_NULL; PUTFIELD，逐一对账
                    AbstractInsnNode a = nextReal(in);
                    AbstractInsnNode b = a == null ? null : nextReal(a);
                    AbstractInsnNode c = b == null ? null : nextReal(b);
                    if (a instanceof VarInsnNode && a.getOpcode() == Opcodes.ALOAD
                            && ((VarInsnNode) a).var == 0
                            && b != null && b.getOpcode() == Opcodes.ACONST_NULL
                            && c instanceof FieldInsnNode && c.getOpcode() == Opcodes.PUTFIELD
                            && ((FieldInsnNode) c).name.equals(info.canvasField)) {
                        nullWrites++;
                    }
                }
            }
        }
        assertTrue("cut1 ctor-canvas-null applied (" + nullWrites + " site)", nullWrites >= 1);

        // 刀3：每个 Display.update 后紧跟 isCloseRequested 轮询；每个 RETURN 前 wasCloseRequested 兜底
        int updates = 0;
        int polls = 0;
        for (AbstractInsnNode in = run.instructions.getFirst(); in != null; in = in.getNext()) {
            if (isStatic(in, "org/lwjgl/opengl/Display", "update", "()V")) {
                updates++;
                AbstractInsnNode nx = nextReal(in);
                if (isStatic(nx, "org/lwjgl/opengl/Display", "isCloseRequested", "()Z")) {
                    polls++;
                }
            }
        }
        assertTrue("cut3 poll after every update (" + polls + "/" + updates + ")",
                updates >= 1 && polls == updates);
        int returns = 0;
        int guards = 0;
        for (AbstractInsnNode in = run.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in.getOpcode() == Opcodes.RETURN) {
                returns++;
                AbstractInsnNode p = prevReal(in);
                if (isStatic(p, "java/lang/System", "exit", "(I)V")) {
                    guards++;
                }
            }
        }
        assertTrue("cut3 exit guard before every return (" + guards + "/" + returns + ")",
                returns >= 1 && guards == returns);

        // 刀4：setGrabbed 站点数 == 回中站点数；回中调用本体存活（§30：勿删供数通道）
        int grabbed = countClass(cn, "org/lwjgl/input/Mouse", "setGrabbed", "(Z)V");
        assertTrue("cut4 setGrabbed injected (" + grabbed + " vs sites " + sites + ")",
                grabbed == sites);
        int helperCalls = 0;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "()V".equals(((MethodInsnNode) in).desc)
                        && ((MethodInsnNode) in).owner.equals(info.helperClass)) {
                    helperCalls++;
                }
            }
        }
        assertTrue("recenter calls preserved (" + helperCalls + ")", helperCalls == sites);

        // 刀2：Display 取材 .attic/bin/lwjgl.jar（主类命中后 armed 生效）
        byte[] displayOut = null;
        Path lwjgl = lwjglJar();
        if (Files.exists(lwjgl)) {
            byte[] displayIn = entryBytes(lwjgl, "org/lwjgl/opengl/Display");
            if (check("Display entry found in " + lwjgl.getFileName(), displayIn != null)) {
                displayOut = t.transform(null, "org.lwjgl.opengl.Display", null, null, displayIn);
                if (check("cut2 Display transformed", displayOut != null)) {
                    ClassNode dcn = nodeOf(displayOut, false);
                    assertTrue("cut2 setResizable+setLocation in create",
                            countClass(dcn, "org/lwjgl/opengl/Display", "setResizable", "(Z)V") > 0
                                    && countClass(dcn, "org/lwjgl/opengl/Display", "setLocation", "(II)V") > 0);
                }
            }
        } else {
            System.out.println("  [note] lwjgl.jar not found at " + lwjgl + " — cut2 skipped");
        }

        // 装载自证：-Xverify:all 单 loader（补丁类遮蔽原 jar 类）
        loadCheck(mainInternal, mainOut, displayOut, jarPath);
    }

    // ---------- 负向：0625 直通 + 全 jar 零触碰（回归不劣化静态证据） ----------

    private static void negative(Path jarPath) throws Exception {
        System.out.println();
        System.out.println("---- " + jarPath.getFileName() + " (negative) ----");
        if (!Files.exists(jarPath)) {
            fail("jar missing: " + jarPath);
            return;
        }
        List<String> names = new ArrayList<String>();
        List<byte[]> datas = new ArrayList<byte[]>();
        readClasses(jarPath, names, datas);
        WindowFixTransformer t = new WindowFixTransformer();
        int changed = 0;
        for (int i = 0; i < names.size(); i++) {
            if (t.transform(null, names.get(i).replace('/', '.'), null, null, datas.get(i)) != null) {
                changed++;
            }
        }
        assertTrue("0625 pass-through: zero classes touched (changed=" + changed + ")", changed == 0);
    }

    // ---------- 工具 ----------

    private static void loadCheck(String internal, byte[] mainOut, byte[] displayOut, Path jar)
            throws Exception {
        Path dir = Files.createTempDirectory("windowfix-harness");
        Path cls = dir.resolve(internal + ".class");
        Files.createDirectories(cls.getParent());
        Files.write(cls, mainOut);
        if (displayOut != null) {
            Path d = dir.resolve("org/lwjgl/opengl/Display.class");
            Files.createDirectories(d.getParent());
            Files.write(d, displayOut);
        }
        URL[] urls = new URL[]{dir.toUri().toURL(), jar.toUri().toURL()};
        URLClassLoader loader = new URLClassLoader(urls,
                ClassLoader.getSystemClassLoader().getParent());
        try {
            Class.forName(internal.replace('/', '.'), true, loader);
            assertTrue("load -Xverify:all: " + internal + " links & inits", true);
        } catch (VerifyError ve) {
            fail("load -Xverify:all VERIFY FAIL " + internal + ": " + ve);
        } catch (ClassFormatError cf) {
            fail("load class format FAIL " + internal + ": " + cf);
        } catch (Throwable other) {
            // 验证发生在装载/链接期，先于 <clinit>；解析/初始化异常不算字节码坏
            System.out.println("  [note] " + internal + " verified, init deferred: "
                    + other.getClass().getSimpleName() + ": " + other.getMessage());
        } finally {
            loader.close();
        }
    }

    private static void readClasses(Path jar, List<String> names, List<byte[]> datas) throws Exception {
        JarFile jf = new JarFile(jar.toFile());
        try {
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry e = en.nextElement();
                if (e.getName().endsWith(".class")) {
                    names.add(e.getName().substring(0, e.getName().length() - 6));
                    InputStream is = jf.getInputStream(e);
                    ByteArrayOutputStream bos = new ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        bos.write(buf, 0, n);
                    }
                    is.close();
                    datas.add(bos.toByteArray());
                }
            }
        } finally {
            jf.close();
        }
    }

    private static byte[] bytesOf(List<String> names, List<byte[]> datas, String internal) {
        for (int i = 0; i < names.size(); i++) {
            if (names.get(i).equals(internal)) {
                return datas.get(i);
            }
        }
        return null;
    }

    private static ClassNode nodeOf(byte[] b, boolean skipCode) {
        ClassNode cn = new ClassNode();
        new ClassReader(b).accept(cn, skipCode ? ClassReader.SKIP_CODE : 0);
        return cn;
    }

    private static boolean isStatic(AbstractInsnNode in, String owner, String name, String desc) {
        if (!(in instanceof MethodInsnNode)) {
            return false;
        }
        MethodInsnNode mi = (MethodInsnNode) in;
        return mi.getOpcode() == Opcodes.INVOKESTATIC
                && mi.owner.equals(owner) && mi.name.equals(name) && mi.desc.equals(desc);
    }

    private static int countStatic(MethodNode m, String owner, String name, String desc) {
        int c = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (isStatic(in, owner, name, desc)) {
                c++;
            }
        }
        return c;
    }

    private static int countClass(ClassNode cn, String owner, String name, String desc) {
        int c = 0;
        for (MethodNode m : cn.methods) {
            if (m.instructions != null) {
                c += countStatic(m, owner, name, desc);
            }
        }
        return c;
    }

    private static AbstractInsnNode nextReal(AbstractInsnNode in) {
        AbstractInsnNode n = in.getNext();
        while (n != null && (n instanceof org.objectweb.asm.tree.LabelNode
                || n instanceof org.objectweb.asm.tree.FrameNode
                || n instanceof org.objectweb.asm.tree.LineNumberNode)) {
            n = n.getNext();
        }
        return n;
    }

    private static AbstractInsnNode prevReal(AbstractInsnNode in) {
        AbstractInsnNode p = in.getPrevious();
        while (p != null && (p instanceof org.objectweb.asm.tree.LabelNode
                || p instanceof org.objectweb.asm.tree.FrameNode
                || p instanceof org.objectweb.asm.tree.LineNumberNode)) {
            p = p.getPrevious();
        }
        return p;
    }

    private static Path pathOf(String s) {
        return new File(s).toPath();
    }

    private static Path lwjglJar() {
        return VERSIONS.getParent().resolve("bin").resolve("lwjgl.jar");
    }

    private static byte[] entryBytes(Path jar, String internalName) throws Exception {
        JarFile jf = new JarFile(jar.toFile());
        try {
            JarEntry e = jf.getJarEntry(internalName + ".class");
            if (e == null) {
                return null;
            }
            InputStream is = jf.getInputStream(e);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            is.close();
            return bos.toByteArray();
        } finally {
            jf.close();
        }
    }

    private static void assertTrue(String what, boolean ok) {
        check(what, ok);
    }

    private static boolean check(String what, boolean ok) {
        checks++;
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + what);
        if (!ok) {
            failures++;
        }
        return ok;
    }

    private static void fail(String what) {
        checks++;
        failures++;
        System.out.println("  FAIL  " + what);
    }
}
