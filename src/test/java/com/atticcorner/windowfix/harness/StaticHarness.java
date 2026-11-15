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
        String versionsArg = null;
        for (String a : args) {
            if (!a.startsWith("post=")) {
                versionsArg = a;
                break;
            }
        }
        VERSIONS = versionsArg != null
                ? pathOf(versionsArg)
                : pathOf(System.getenv("APPDATA") + File.separator + ".attic" + File.separator + "versions");
        Path versions = VERSIONS;
        System.out.println("== infdev-windowfix static harness ==");
        System.out.println("versions dir: " + versions);

        positive(versions.resolve("inf-20100629.jar"));
        positive(versions.resolve("inf-20100627.jar"));
        disableSwitch(versions.resolve("inf-20100629.jar"));
        negative(versions.resolve("inf-20100625-1917.jar"));

        // 取证件回归（可选参 post=<path>）：agent dump= 落盘的补丁后字节（真链路 legacyfix 输出），
        // 验证 post 式门控 + 四刀在真机形态上同样全绿。素材 = Mojang 专有字节，不入库，仅本地跑。
        for (String a : args) {
            if (a.startsWith("post=")) {
                Path fixture = pathOf(a.substring(5));
                if (Files.exists(fixture)) {
                    postPatch(fixture);
                } else {
                    System.out.println("  [note] post fixture missing: " + fixture);
                }
            }
        }

        System.out.println();
        System.out.println("checks=" + checks + " failures=" + failures);
        System.out.println(failures == 0 ? "ALL PASS" : "FAIL");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------- 正向：0627/0629 全刀面断言（0.0.5：三刀主类 + Display 半刀，canvas-null 已删） ----------

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

        // 0.0.5 §3e：canvas-null 整刀移除——setParent 后不得再有置空（毒刀不在场实证）
        int nullWrites = countNullAfterSetParent(run, info.canvasField);
        assertTrue("canvas-null cut removed: no null-write after setParent (" + nullWrites + ")",
                nullWrites == 0);

        // 刀2·尺寸同步（0.0.4 §3d 重写）：读点 POP+STATIC 替换（清零 Canvas 读）、
        // 判空跳转保持原样（门数 = 2：头部 + resize 块门）、standalone 同步块在场
        int canvasReads = countCanvasSizeReads(run);
        int displayW = countStatic(run, "org/lwjgl/opengl/Display", "getWidth", "()I");
        int displayH = countStatic(run, "org/lwjgl/opengl/Display", "getHeight", "()I");
        int created = countStatic(run, "org/lwjgl/opengl/Display", "isCreated", "()Z");
        int setSize = countVirtual(run, "java/awt/Canvas", "setSize", "(II)V");
        assertTrue("cut2b canvas size reads cleared in run() (" + canvasReads + ")",
                canvasReads == 0);
        assertTrue("cut2b Display.getWidth/getHeight present (" + displayW + "/" + displayH + ")",
                displayW >= 4 && displayH >= 4);
        assertTrue("cut2b standalone block present (isCreated=" + created + ", setSize=" + setSize + ")",
                created >= 1 && setSize == 1);
        // 门数 = 3：头部嵌入分支门 + resize 块门（均原样保留）+ 同步块 canvas!=null 守卫
        assertTrue("gates kept intact + block guard (" + countCanvasIfnullGates(run) + ")",
                countCanvasIfnullGates(run) == 3);
        // §3d 迭代要求 1：BasicInterpreter 栈收支当场过闸（0.0.3 的 3!=2 类错误无处遁形）
        java.util.List<String> verr = com.atticcorner.windowfix.VerifyGate.verify(mainOut);
        for (String v : verr) {
            System.out.println("    [verify] " + v);
        }
        assertTrue("BasicVerifier clean on main out (" + verr.size() + " errs)", verr.isEmpty());

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

        // 刀4（0.0.13 §3m）：注入点字节码算 desired（GETFIELD m / GETFIELD d），
        // 只传 boolean 给 GrabHelper.tick(Z)V；helper 零混淆字段反射；主类零直接 Mouse 引用。
        int upd = countClass(cn, "org/lwjgl/opengl/Display", "update", "()V");
        int ticks = countClass(cn, "com/atticcorner/windowfix/GrabHelper", "tick", "(Z)V");
        int directGrab = countClass(cn, "org/lwjgl/input/Mouse", "setGrabbed", "(Z)V");
        assertTrue("cut4 tick @update sites (" + ticks + " vs updates " + upd + ")",
                upd >= 1 && ticks == upd);
        assertTrue("cut4 main class zero direct Mouse calls (" + directGrab + ")",
                directGrab == 0);
        // 门形：每个 tick 前回走须见 GETFIELD screen(m:Lcz/n:Lcz) 与 GETFIELD world(d) 各一
        int fed = 0;
        for (MethodNode m : cn.methods) {
            if (m.instructions == null) {
                continue;
            }
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (isStatic(in, "com/atticcorner/windowfix/GrabHelper", "tick", "(Z)V")) {
                    boolean sawScreen = false;
                    boolean sawWorld = false;
                    AbstractInsnNode q = in;
                    for (int hop = 0; hop < 14 && q != null; hop++) {
                        q = q.getPrevious();
                        if (q instanceof FieldInsnNode && q.getOpcode() == Opcodes.GETFIELD
                                && ((FieldInsnNode) q).owner.equals(cn.name)) {
                            String dsc = ((FieldInsnNode) q).desc;
                            if (dsc.equals(info.screenInstDesc)) {
                                sawScreen = true;
                            }
                            if (dsc.equals(info.worldFieldDesc)) {
                                sawWorld = true;
                            }
                        }
                    }
                    if (sawScreen && sawWorld) {
                        fed++;
                    }
                }
            }
        }
        assertTrue("cut4 gate computed in bytecode (" + fed + "/" + ticks + ", screen="
                + info.screenInstField + ":" + info.screenInstDesc + ", world="
                + info.worldField + ":" + info.worldFieldDesc + ")",
                fed == ticks && info.screenInstField != null && info.worldField != null);
        int helperCalls = 0;
        int wedged = 0;
        for (MethodNode m : cn.methods) {
            for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
                if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKEVIRTUAL
                        && "()V".equals(((MethodInsnNode) in).desc)
                        && ((MethodInsnNode) in).owner.equals(info.helperClass)) {
                    helperCalls++;
                    AbstractInsnNode p = prevReal(in);
                    if (p instanceof MethodInsnNode
                            && "setGrabbed".equals(((MethodInsnNode) p).name)) {
                        wedged++;
                    }
                }
            }
        }
        assertTrue("recenter calls preserved (" + helperCalls + ")", helperCalls == sites);
        assertTrue("zero-intersection: no grab call before recenter (" + wedged + ")",
                wedged == 0);

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

    // ---------- 取证件：真链路补丁后字节（agent dump= 落盘）post 式全绿 ----------

    private static void postPatch(Path fixture) throws Exception {
        System.out.println();
        System.out.println("---- post-patch fixture: " + fixture + " ----");
        byte[] in = Files.readAllBytes(fixture);
        // 类名从字节自身读（默认包 at / 混淆名随版本）
        String internal = nodeOf(in, true).name;

        Fingerprint.MainClassInfo info = Fingerprint.analyze(nodeOf(in, false));
        if (!check("post-form fingerprint HIT on patched bytes", info != null)) {
            return;
        }
        assertTrue("form = post-mousepatch (raw=false)", !info.rawForm);
        assertTrue("recenter sites == 2 on patched bytes (got "
                + info.recenterSites.size() + ")", info.recenterSites.size() == 2);

        byte[] out = new WindowFixTransformer().transform(
                null, internal.replace('/', '.'), null, null, in);
        if (!check("transformer applies on patched bytes", out != null)) {
            return;
        }
        // §3c 迭代要求 3：补丁后字节落盘，javap 对照原始 in 即答案
        Path evidence = fixture.resolveSibling(fixture.getFileName() + ".013.out.class");
        Files.write(evidence, out);
        System.out.println("  [dump] 0.0.13 output -> " + evidence);

        ClassNode cn = nodeOf(out, false);
        MethodNode run = Fingerprint.findRun(cn);
        assertTrue("§3i world field detected on patched (" + info.worldField + ":"
                + info.worldFieldDesc + ")", info.worldField != null);
        int ticks = countClass(cn, "com/atticcorner/windowfix/GrabHelper", "tick", "(Z)V");
        assertTrue("cut4 tick @update on patched (" + ticks + " vs updates "
                + countClass(cn, "org/lwjgl/opengl/Display", "update", "()V")
                + ", direct setGrabbed=" + countClass(cn, "org/lwjgl/input/Mouse",
                "setGrabbed", "(Z)V") + ")",
                ticks == countClass(cn, "org/lwjgl/opengl/Display", "update", "()V")
                        && ticks >= 1
                        && countClass(cn, "org/lwjgl/input/Mouse", "setGrabbed", "(Z)V") == 0);
        assertTrue("canvas-null cut removed on patched ("
                + countNullAfterSetParent(run, info.canvasField) + ")",
                countNullAfterSetParent(run, info.canvasField) == 0);
        assertTrue("cut2b canvas size reads cleared on patched ("
                + countCanvasSizeReads(run) + ")", countCanvasSizeReads(run) == 0);
        assertTrue("gates kept on patched (2 原门 + 块守卫) (" + countCanvasIfnullGates(run) + ")",
                countCanvasIfnullGates(run) == 3);
        assertTrue("standalone block on patched (isCreated="
                + countStatic(run, "org/lwjgl/opengl/Display", "isCreated", "()Z")
                + ", setSize=" + countVirtual(run, "java/awt/Canvas", "setSize", "(II)V") + ")",
                countStatic(run, "org/lwjgl/opengl/Display", "isCreated", "()Z") >= 1
                        && countVirtual(run, "java/awt/Canvas", "setSize", "(II)V") == 1);
        java.util.List<String> verr = com.atticcorner.windowfix.VerifyGate.verify(out);
        for (String v : verr) {
            System.out.println("    [verify] " + v);
        }
        assertTrue("BasicVerifier clean on patched out (" + verr.size() + " errs)",
                verr.isEmpty());
        assertTrue("cut3 present on patched (update-poll "
                + countClass(cn, "org/lwjgl/opengl/Display", "isCloseRequested", "()Z")
                + ", exit " + countClass(cn, "java/lang/System", "exit", "(I)V") + ")",
                countClass(cn, "org/lwjgl/opengl/Display", "isCloseRequested", "()Z") >= 2
                        && countClass(cn, "java/lang/System", "exit", "(I)V") >= 1);
    }

    // ---------- disable 开关回归（§3c 迭代要求 1：逐刀 A/B） ----------

    private static void disableSwitch(Path jarPath) throws Exception {
        System.out.println();
        System.out.println("---- disable switch: " + jarPath.getFileName() + " ----");
        if (!Files.exists(jarPath)) {
            fail("jar missing: " + jarPath);
            return;
        }
        List<String> names = new ArrayList<String>();
        List<byte[]> datas = new ArrayList<byte[]>();
        readClasses(jarPath, names, datas);
        String mainName = null;
        byte[] mainData = null;
        for (int i = 0; i < names.size(); i++) {
            if (Fingerprint.looksLikeCandidate(datas.get(i))
                    && Fingerprint.analyze(nodeOf(datas.get(i), false)) != null) {
                mainName = names.get(i);
                mainData = datas.get(i);
                break;
            }
        }
        if (!check("main class located", mainData != null)) {
            return;
        }
        String dotted = mainName.replace('/', '.');

        // 全关 = 零注入（门控仍过、armed 记账不变——逃生门 = disable 全列）
        WindowFixTransformer off = new WindowFixTransformer(null,
                new java.util.HashSet<String>(java.util.Arrays.asList(
                        "size-sync", "display-create", "close-exit", "grab-bind")));
        byte[] out = off.transform(null, dotted, null, null, mainData);
        if (!check("all-disabled still transforms main (armed)", out != null)) {
            return;
        }
        ClassNode cn = nodeOf(out, false);
        MethodNode run = Fingerprint.findRun(cn);
        assertTrue("all-disabled: no GrabHelper.tick injection",
                countClass(cn, "com/atticcorner/windowfix/GrabHelper", "tick", "(Z)V") == 0);
        assertTrue("all-disabled: no wasCloseRequested",
                countClass(cn, "org/lwjgl/opengl/Display", "wasCloseRequested", "()Z") == 0);
        assertTrue("all-disabled: canvas size reads intact (" + countCanvasSizeReads(run) + ")",
                countCanvasSizeReads(run) >= 2);
        assertTrue("all-disabled: gates intact (" + countCanvasIfnullGates(run) + ")",
                countCanvasIfnullGates(run) == 2);
        assertTrue("all-disabled: no standalone block",
                countStatic(run, "org/lwjgl/opengl/Display", "isCreated", "()Z") == 0
                        && countVirtual(run, "java/awt/Canvas", "setSize", "(II)V") == 0);
        // 字段无关核对：setParent 后第二实指令不是 ACONST_NULL
        int nullAfterParent = 0;
        for (AbstractInsnNode in = run.instructions.getFirst(); in != null; in = in.getNext()) {
            if (isStatic(in, "org/lwjgl/opengl/Display", "setParent", "(Ljava/awt/Canvas;)V")) {
                AbstractInsnNode b = nextReal(nextReal(in));
                if (b != null && b.getOpcode() == Opcodes.ACONST_NULL) {
                    nullAfterParent++;
                }
            }
        }
        assertTrue("all-disabled: setParent not followed by ACONST_NULL", nullAfterParent == 0);

        // 单刀关（0.0.5）：size-sync off、其余 on -> 读点/门/块原样，grab 绑屏照常（刀间独立）
        WindowFixTransformer part = new WindowFixTransformer(null,
                new java.util.HashSet<String>(java.util.Arrays.asList("size-sync")));
        byte[] out2 = part.transform(null, dotted, null, null, mainData);
        ClassNode cn2 = nodeOf(out2, false);
        MethodNode run2 = Fingerprint.findRun(cn2);
        assertTrue("disable=size-sync only: canvas reads intact ("
                + countCanvasSizeReads(run2) + ")", countCanvasSizeReads(run2) >= 2);
        assertTrue("disable=size-sync only: gates intact ("
                + countCanvasIfnullGates(run2) + ")", countCanvasIfnullGates(run2) == 2);
        assertTrue("disable=size-sync only: no standalone block",
                countStatic(run2, "org/lwjgl/opengl/Display", "isCreated", "()Z") == 0
                        && countVirtual(run2, "java/awt/Canvas", "setSize", "(II)V") == 0);
        assertTrue("disable=size-sync only: grab-bind still applied ("
                + countClass(cn2, "com/atticcorner/windowfix/GrabHelper", "tick", "(Z)V") + ")",
                countClass(cn2, "com/atticcorner/windowfix/GrabHelper", "tick", "(Z)V") == 2);
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

    // ---------- 0.0.3 刀位断言 helper ----------

    /** run() 内 INVOKESTATIC Display.setParent 后紧跟 ALOAD_0;ACONST_NULL;PUTFIELD canvas 的站点数。 */
    private static int countNullAfterSetParent(MethodNode run, String canvasField) {
        int n = 0;
        for (AbstractInsnNode in = run.instructions.getFirst(); in != null; in = in.getNext()) {
            if (isStatic(in, "org/lwjgl/opengl/Display", "setParent", "(Ljava/awt/Canvas;)V")) {
                AbstractInsnNode a = nextReal(in);
                AbstractInsnNode b = a == null ? null : nextReal(a);
                AbstractInsnNode c = b == null ? null : nextReal(b);
                if (a instanceof VarInsnNode && a.getOpcode() == Opcodes.ALOAD
                        && ((VarInsnNode) a).var == 0
                        && b != null && b.getOpcode() == Opcodes.ACONST_NULL
                        && c instanceof FieldInsnNode && c.getOpcode() == Opcodes.PUTFIELD
                        && ((FieldInsnNode) c).name.equals(canvasField)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** run() 内 INVOKEVIRTUAL java/awt/Canvas.getWidth()/getHeight() 残留数。 */
    private static int countCanvasSizeReads(MethodNode run) {
        int n = 0;
        for (AbstractInsnNode in = run.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode mi = (MethodInsnNode) in;
                if ("java/awt/Canvas".equals(mi.owner) && "()I".equals(mi.desc)
                        && ("getWidth".equals(mi.name) || "getHeight".equals(mi.name))) {
                    n++;
                }
            }
        }
        return n;
    }

    /** run() 内 INVOKEVIRTUAL owner.name:desc 计数。 */
    private static int countVirtual(MethodNode m, String owner, String name, String desc) {
        int n = 0;
        for (AbstractInsnNode in = m.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof MethodInsnNode && in.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                MethodInsnNode mi = (MethodInsnNode) in;
                if (owner.equals(mi.owner) && name.equals(mi.name) && desc.equals(mi.desc)) {
                    n++;
                }
            }
        }
        return n;
    }

    /** run() 内 GETFIELD canvas; IFNULL 门数（0.0.4 §3d：判空跳转保持原样，应 = 2）。 */
    private static int countCanvasIfnullGates(MethodNode run) {
        int n = 0;
        for (AbstractInsnNode in = run.instructions.getFirst(); in != null; in = in.getNext()) {
            if (in instanceof FieldInsnNode && in.getOpcode() == Opcodes.GETFIELD
                    && Fingerprint.CANVAS_DESC.equals(((FieldInsnNode) in).desc)) {
                AbstractInsnNode nx = nextReal(in);
                if (nx != null && nx.getOpcode() == Opcodes.IFNULL) {
                    n++;
                }
            }
        }
        return n;
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
