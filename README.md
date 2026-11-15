# infdev-windowfix-agent - Infdev 窗口修复 javaagent (Minecraft Infdev Window-Fix Java Agent)

`infdev-windowfix-agent` 是一个**启动器侧自研小 javaagent**：对 Minecraft Infdev `20100627` / `20100629` 两个版本做四项字节码手术，修复 [legacyfix](https://github.com/CreatorCSIE/legacyfix-attic) DeAWT 链路下的关窗不自退、窗口不可拉伸、鼠标抓取过严等症状——由 [Attic Corner Launcher](https://github.com/CreatorCSIE/Attic-Corner-Launcher) 下载挂载，不修改 legacyfix、不落盘改游戏 jar。

> **免责声明**：本项目是独立开发的第三方开源修复工具，并非 Minecraft 官方产品，未获 Microsoft、Mojang Studios 的赞助、认可或背书。
> *Independently developed third-party open-source fix agent. Not an official Minecraft product; not approved or endorsed by Microsoft or Mojang Studios.*

> **背景**：legacyfix `26.11-attic.10` 的 DeAWT + MousePatch 组合在 Infdev 0627/0629 两版上污染原生窗口链路（症状：鼠标抓不住、✕ 关窗进程不自退、拉伸不 resize）。该组合形态仅此两版命中——其 `run()` 内 `Mouse.getDX/getDY` 直调 robot 回中分支为 0627/0629 独有（javap 原始 jar 可复现取证），0625/0630 等同族版本无恙。本 agent 以启动器侧独立实现修复。已定设计：功能项**默认开、可关**（关 = 逃生门兼复现现状形态）；手术面**四项全做**；仅挂 `{inf-20100627, inf-20100629}`。

> **与 legacyfix-attic 的关系**：[legacyfix-attic](https://github.com/CreatorCSIE/legacyfix-attic)（GPL-3.0，与本仓及 Attic 启动器同一维护者）是 legacyfix 的 fork，其发布构件 `26.11-attic.10` 由启动器默认挂载，负责远古版本的 AWT 嵌入治理与鼠标/窗口补丁。本 agent **不是 legacyfix 的替代，而是它的伴生件**：所治症状恰好产生于 legacyfix 的 DeAWT+MousePatch 组合与 0627/0629 独有类形态的交叉点，故修复以外部类变换落在 legacyfix 产物**之后**，不修改、不分发 legacyfix 本体。
>
> **对症下药的环境前提**（脱离该链路不成立，勿在他处复用）：
> - **尾注入**：本 agent 必须排在 legacyfix javaagent 之后加载——变换建立在修复层最终字节之上（attic.10 已保证第三方 agent 织入存活）。
> - **启动器挂载契约**：`-javaagent:<path>=<gameDir>` 展开形态（premain 容忍该参数）、jar 由启动器按钉版 hash 下载落数据根。
> - **ASM relocate**：shade 重定位避开 legacyfix 自带 ASM，两份字节码库互不踩踏。
> - **鼠标 delta 供数在 legacyfix 手里**：MousePatch 已改写 MouseHelper 喂值（实机验证），本 agent 只补 grab 语义（刀 4），不碰供数链——两刀分属两层，缺一即回归症状。

---

## 🌟 核心机制

- **形态**：Java 8 目标，ASM 9.x shaded fat jar，manifest `Premain-Class`。premain **容忍 `=gameDir` 参数**——启动器 javaagent 展开固定为 `-javaagent:<path>=<gameDir>`，agent 忽略即可。
- **版本自门控（防御性）**：仅当类形态匹配才变换——主类 implements Runnable + 持 `Ljava/awt/Canvas;` 字段 + `run()` 有 `Display.setParent` 嵌入路 + `Mouse.getDX/getDY` 直调回中形态（0627/0629 独有，javap 原始 jar 可复现）。**不匹配 = 直通**。
- **四刀**（终版刀形 = 0.0.13，实机全绿；每刀取证依据 = 原始/补丁后 jar 反汇编，钉死在源码注释）：

| 刀 | 取证依据 | 手术内容 |
|---|---|---|
| 1 · size-sync | javap：`run()` 4 处 canvas 尺寸读点 + `IFNULL` 判空门 | 读点 `POP + Display.getWidth/getHeight` 纯表达式替换（判空跳转保持原样）；IFNULL 目标处插 standalone 同步块（isCreated → 尺寸比对 → 私有 resize + Canvas.setSize 守卫） |
| 2 · display-create | javap：`Display.create()` 调用站点 | create 尾插 `setResizable(true)` + `Toolkit.getScreenSize()` 居中 `setLocation` |
| 3 · close-exit | 原生循环已自轮询 `isCloseRequested` | 每个 `Display.update` 后补轮询；兜底路径 = `wasCloseRequested` → `System.exit` |
| 4 · grab-bind | javap：主类 `m:Lcz`（currentScreen）/ `d:Lez`（WorldRenderer）字段形态 | 每个 `Display.update` 后注入字节码算 `desired = (m==null && d!=null)`，调 `GrabHelper.tick(desired)`——边沿触发（仅翻转时 `Mouse.setGrabbed`，菜单稳态零调用零事件泵）。回中调用序列一字不碰 |

> **演进判词摘要**（详见案卷 §3c–§3m）：Phase 1 的 ctor-canvas-null 刀经真机定罪掐回中分支，**永久移除**；grab 刀历经绑点（掐供数链→换 update 后）、门（active 桌面恒假→删；`ae.a` 静态单例绑错→改实例字段 `m`）、毒形（每帧 setGrabbed 泵事件→边沿触发）、反射（`getDeclaredField` 不查父链→字段读取全移注入点字节码）四轮定罪后结构灭绝 bug 类。教训：**注入代码永远不要在 helper 里反射读游戏字段，字段读取放注入点字节码，helper 只收原始类型**。

- **相机不动**：legacyfix attic.10 的 MousePatch 已负责这两版的鼠标 delta 供数（实机验证），agent 不做 MouseHelper 手术。
- **诚实记账**：每刀应用/跳过/禁用均 stderr 打 `[windowfix] <site> ...` 行；grab 站点带 bci 记账行（判词对照锚点）。
- **运行期开关**：`-Dwindowfix.verify=true`（ASM BasicInterpreter 栈收支闸，每刀产出当场过验）· `-Dwindowfix.debug=true`（grab 遥测：desired 变化行 + EDGE 行 + 每 600 帧计数行）· `-Dwindowfix.disable=<刀id,...>`（逐刀关，id ∈ size-sync/display-create/close-exit/grab-bind）。
- **逃生门**：启动器侧功能项开关 off = 精确复现未挂本 agent 的 attic.10 现状形态（三症状在，作取证基线）。

---

## 🧭 开发状态

| 阶段 | 内容 | 状态 |
|---|---|---|
| Phase 0 | 仓库初始化 / Gradle 骨架 / README / MIT | ✅ 完成 |
| Phase 1 | 自门控指纹 + 刀实现 + 静态自证 harness | ✅ 完成（harness 65/65 全绿：0627/0629 结构断言 + disable 回归 + 0625 负向 + 真链路 post 取证 + BasicVerifier 栈闸） |
| Phase 2 | 实机验收（0627/0629 全套 + 回归矩阵） | ✅ 完成（**0.0.13 案结**：0629 判点菜单点击/相机/锁鼠+ESC 释放/游戏内左右键/resize/关窗自退/F11/皮肤全绿；0625-1917 回归局指纹直通、窗口正常起、游戏类零触碰） |
| Phase 2.5 | CI（GitHub Actions + gradlew LF 行尾修复） | ✅ 绿 |
| Phase 3 | GitHub Release（钉版 jar + sha256/size meta） | ✅ 完成（[v0.0.13](https://github.com/CreatorCSIE/infdev-0627-deawt-fix/releases/tag/v0.0.13)：jar 397156B / sha256 `25ef556b…8015`；启动器接线按决策**不在本仓范围**，直链即取即用） |

---

## 💻 运行环境要求

- **构建**：JDK 8（`C:\Program Files\Java\jdk1.8.0_202`）；Gradle wrapper 钉 **6.9.4** + shadow 插件 **6.1.0** + ASM **9.1**——与 legacyfix fork 同工具链，本地缓存可全离线构建。
- **运行**：游戏侧 Java 8；agent 为 shaded fat jar（ASM 已 relocate，不与 legacyfix 自带 ASM 冲突）。

---

## 🚀 构建

```bash
# 产物：build/libs/infdev-windowfix-<版本>.jar（文件名钉版 = 显式升级通道，同 legacyfix 纪律）
gradlew shadowJar --rerun-tasks

# 静态自证 harness（-Xverify:all；可加 -PpostFix=<agent dump 证件路径> 跑真链路 post 局）
gradlew harness --rerun-tasks
```

> **构建纪律**：迭代构建一律 `--rerun-tasks`（gradle 增量漏检发生过两次；**哈希变化 ≠ 内容变化**）。`JAVA_HOME` 须钉 JDK 8（Gradle 6.9.4 不认新 JDK）。

### 仓库结构 (Repository Layout)

```text
infdev-windowfix-agent
├── README.md / LICENSE (MIT) / .gitignore / .gitattributes（行尾钉版）
├── build.gradle            shadowJar + Premain-Class manifest + 钉版产物名 + ASM relocate
├── .github/workflows/      CI.yml + nightly.yml
├── settings.gradle / gradle.properties
├── gradle/wrapper/         Gradle 6.9.4 wrapper（钉版）
└── src/
    ├── main/java/com/atticcorner/windowfix/
    │   ├── WindowFixAgent.java       premain 入口（容忍 =gameDir）+ 自门控 + verify/disable/debug 解析
    │   ├── WindowFixTransformer.java 主类命中后四刀编排 + 逐刀记账日志 + 栈收支验证闸
    │   ├── Fingerprint.java          形态指纹（Runnable/Canvas/run()/回中站点/screen 与 world 字段探测）
    │   ├── GrabHelper.java           刀 4 运行体（边沿缓存 + setGrabbed + 遥测；零混淆字段反射）
    │   ├── VerifyGate.java           ASM BasicInterpreter 栈收支验证
    │   ├── HonestLog.java            [windowfix] stderr 记账
    │   └── cuts/                     四刀各成一文件（取证出处钉死在注释里）
    └── test/java/com/atticcorner/windowfix/harness/
        └── StaticHarness.java        静态自证：生产路径 transform→toBytecode、单 loader -Xverify:all、
                                      javap 断言新体在场 / 原解引用清零 / 门形 / disable 回归 / post 取证
```

---

## 🔌 启动器侧集成（不在本仓，按决策未接线）

挂载表 `quirks.ts`（两条 AgentMount 单版本钉 0627/0629，排 legacyfix 之后尾注入）· 下载 `betacraft.ts` 新 `ensureWindowfix`（Release 直链 + sha256/size 校验）· 开关 `settings.ts` `infdevWindowfix?`（缺键 = true，intelFix 同语义）· 装配 `runtime.ts` 滤除 · UI `InstanceList.vue` checkbox · i18n 三语 · launch/storage 测试。—— **本仓 Phase 3 以 Release 收口，以上为接线蓝图（候启动器侧另行立项）**；当前用法 = 手动 `-javaagent:<Release jar 路径>`。

---

## ✅ 验收标准

1. **0627/0629（不带任何 `-Dlf.*` 绕行参数）**：关窗自退 ✓ / 拉伸 resize ✓ / F11 ✓ / 皮肤 ✓ / 锁鼠光标不出窗 ✓ / **ESC 菜单鼠标释放可操作** ✓ / 相机正常（MousePatch 供数，应天然绿）✓ / **标题菜单点击可响应** ✓ / **游戏内左右键可响应** ✓。—— **0.0.13 实机全绿达成**。
2. 开关 off = 精确复现未挂本 agent 的 attic.10 现状形态（逃生门与取证基线）。
3. 回归：0625-1917 ✓（0.0.13 实机：指纹直通 + 逐项判决行 + 窗口正常起、游戏类零触碰）/ 0630 / in-20100223 / c0.30 / a1.2.3 / b1.7.3（挂载未钉它们 + agent 自门控双保险）。
4. `[windowfix]` stderr 行在 debug 局可见（运行证据纪律）——已扩展为逐刀记账 + grab bci 站点行 + `-Dwindowfix.debug=true` 遥测行。
5. 验收结论落本仓（README 开发状态表 / release notes）。—— Phase 2 结论已落上表。

---

## 📄 许可证与版权声明 (License & Copyright)

- 本项目仅供 Minecraft 历史版本研究、怀旧与技术交流使用。
- 源码采用 **[MIT License](LICENSE)** 开源。**Copyright (c) 2026 CreatorCSIE.**
- **第三方资产与商标免责声明**：
  - 本仓库**不包含、不分发任何官方 Minecraft 游戏 `.jar` 客户端包**；agent 在运行期对本地已下载的版本 jar 做类变换，不落盘改 jar。
  - 本项目所涉及的 Minecraft 游戏资产、商标与品牌版权均归 **Mojang Studios / Microsoft** 所有。
