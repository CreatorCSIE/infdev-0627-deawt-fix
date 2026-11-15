# infdev-windowfix-agent - Infdev 窗口修复 javaagent (Minecraft Infdev Window-Fix Java Agent)

`infdev-windowfix-agent` 是一个**启动器侧自研小 javaagent**：对 Minecraft Infdev `20100627` / `20100629` 两个版本做四项字节码手术，修复 deawt 链路下的关窗不自退、窗口不可拉伸、鼠标抓取过严等症状——替代 legacyfix fork 内的 native-port 修复路线，迭代环收回本仓，由 [Attic Corner Launcher](https://github.com/CreatorCSIE/Attic-Corner-Launcher) 下载挂载。

> **免责声明**：本项目是独立开发的第三方开源修复工具，并非 Minecraft 官方产品，未获 Microsoft、Mojang Studios 的赞助、认可或背书。
> *Independently developed third-party open-source fix agent. Not an official Minecraft product; not approved or endorsed by Microsoft or Mojang Studios.*

> **前案**：`handoff-legacyfix-deawt.md`（§1-33，已案结）。fork 内 native-port 路线终止于十六轮（四件套 ✓ / 相机 ✗ / grab 过严 ✗）。本方案 = 启动器侧小 agent 替代 fork 内修复。已定决策：功能项**默认开、可关**（关 = 逃生门兼复现现状形态）；手术面**四项全做**；仅挂 `{inf-20100627, inf-20100629}`。

---

## 🌟 核心机制

- **形态**：Java 8 目标，ASM 9.x shaded fat jar，manifest `Premain-Class`。premain **容忍 `=gameDir` 参数**——启动器 javaagent 展开固定为 `-javaagent:<path>=<gameDir>`，agent 忽略即可。
- **版本自门控（防御性）**：仅当类形态匹配才变换——主类 implements Runnable + 持 `Ljava/awt/Canvas;` 字段 + `run()` 有 `Display.setParent` 嵌入路 + `Mouse.getDX/getDY` 直调回中形态（= 案卷 §2.2 指纹，0627/0629 独有）。**不匹配 = 直通**。
- **四刀**（全部有案卷出处，勿自创）：

| 刀 | 案卷出处 | 手术内容 |
|---|---|---|
| 1 · ctor canvas 置空 | §28 | 主类构造器 canvas 字段 PUTFIELD 之后再写 `ACONST_NULL + PUTFIELD`（embedded → standalone 转换）。0629 字段名 = `B`；0627 混淆名不同，按形态探测 |
| 2 · Display.create 后插 | §28 修复 2 | `setResizable(true)` + `Toolkit.getScreenSize()` 居中 `setLocation` |
| 3 · 关窗兜底 | §29 r13（实机验证过） | 原生循环自轮询 `isCloseRequested`，兜底路径 = `wasCloseRequested` → 进程自退 |
| 4 · grab 绑屏 | §32 正解 | 探测主类 GuiScreen 型字段（0629 型 = `ae`），回中调用站点注入 `Mouse.setGrabbed(screenField == null)`——暂停菜单/界面开自动释放。**勿复刻十五轮无条件 grab**（那是 §32 定罪的过严形态） |

- **相机不动**：真 attic.10 的 MousePatch helper 供数链在 deawt 下已绿（r13 实证），agent 不做 `hn` / MouseHelper 手术。
- **诚实记账**（§13 教训：注入 ≠ 执行）：每刀应用时 stderr 打一行 `[windowfix] <site> applied`，运行期可见。
- **逃生门**：启动器侧功能项开关 off = 精确复现真 attic.10 现状形态（三症状在，作取证基线）。

---

## 🧭 开发状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 仓库初始化 / Gradle 骨架 / README / MIT | ✅ 完成（本次） |
| Phase 1 | 自门控指纹 + 四刀实现 + 静态自证 harness | 📋 待开工 |
| Phase 2 | 实机验收（0627/0629 全套 + 回归矩阵） | 📋 |
| Phase 3 | GitHub Release（钉版 jar + sha256/size meta）+ 启动器侧接线 | 📋 |

---

## 💻 运行环境要求

- **构建**：JDK 8（`C:\Program Files\Java\jdk1.8.0_202`）；Gradle wrapper 钉 **6.9.4** + shadow 插件 **6.1.0** + ASM **9.1**——与 legacyfix fork 同工具链，本地缓存可全离线构建。
- **运行**：游戏侧 Java 8；agent 为 shaded fat jar（ASM 已 relocate，不与 legacyfix 自带 ASM 冲突）。

---

## 🚀 构建

```bash
# 产物：build/libs/infdev-windowfix-<版本>.jar（文件名钉版 = 显式升级通道，同 legacyfix 纪律）
gradlew shadowJar --rerun-tasks
```

> **构建纪律**：迭代构建一律 `--rerun-tasks`（gradle 增量漏检两次教训 §12/§31；**哈希变化 ≠ 内容变化**）。

### 仓库结构 (Repository Layout)

```text
infdev-windowfix-agent
├── README.md / LICENSE (MIT) / .gitignore
├── build.gradle            shadowJar + Premain-Class manifest + 钉版产物名 + ASM relocate
├── settings.gradle / gradle.properties
├── gradle/wrapper/         Gradle 6.9.4 wrapper（钉版）
└── src/
    ├── main/java/com/atticcorner/windowfix/
    │   ├── WindowFixAgent.java   premain 入口（容忍 =gameDir）+ 自门控 + transformer 注册
    │   └── cuts/                 四刀各成一文件（§28/§29/§32 出处钉死在注释里）
    └── test/java/com/atticcorner/windowfix/harness/
                                  静态自证：生产路径 transform→toBytecode、单 loader -Xverify:all、
                                  javap 断言新体在场 / 原解引用清零（案卷 §28/§30 断言样例移植）
```

---

## 🔌 启动器侧集成（不在本仓，在 Attic Corner Launcher）

挂载表 `quirks.ts`（两条 AgentMount 单版本钉 0627/0629，排 legacyfix 之后尾注入）· 下载 `betacraft.ts` 新 `ensureWindowfix`（Release 直链 + sha256/size 校验）· 开关 `settings.ts` `infdevWindowfix?`（缺键 = true，intelFix 同语义）· 装配 `runtime.ts` 滤除 · UI `InstanceList.vue` checkbox · i18n 三语 · launch/storage 测试。细则见前案交接简报 §2。

---

## ✅ 验收标准

1. **0627/0629（不带任何 `-Dlf.*` 绕行参数）**：关窗自退 ✓ / 拉伸 resize ✓ / F11 ✓ / 皮肤 ✓ / 锁鼠光标不出窗 ✓ / **ESC 菜单鼠标释放可操作** ✓ / 相机正常（MousePatch 供数，应天然绿）✓。
2. 开关 off = 精确复现真 attic.10 现状形态（逃生门与取证）。
3. 回归：0625-1917 / 0630 / in-20100223 / c0.30 / a1.2.3 / b1.7.3 不劣化（挂载未钉它们 + agent 自门控双保险）。
4. `[windowfix]` stderr 行在 debug 局可见（运行证据纪律）。
5. 验收判词续写入案卷（`handoff-legacyfix-deawt.md` §34+ 或新案卷）。

---

## 📄 许可证与版权声明 (License & Copyright)

- 本项目仅供 Minecraft 历史版本研究、怀旧与技术交流使用。
- 源码采用 **[MIT License](LICENSE)** 开源。**Copyright (c) 2026 CreatorCSIE.**
- **第三方资产与商标免责声明**：
  - 本仓库**不包含、不分发任何官方 Minecraft 游戏 `.jar` 客户端包**；agent 在运行期对本地已下载的版本 jar 做类变换，不落盘改 jar。
  - 本项目所涉及的 Minecraft 游戏资产、商标与品牌版权均归 **Mojang Studios / Microsoft** 所有。
