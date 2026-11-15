# infdev-windowfix-agent 案卷判词全录（0.0.1 → 0.0.13 案结）

> **来源与口径**：本文是上游交接案卷（启动器仓 `Attic-Minecraft-Launcher/handoff-infdev-windowfix-agent.md`，案卷本体已删除）的抢救存档——案结后从 IDE 长文本缓存找回全文，作为本路线唯一幸存的 raw 判词记录入库。
> 已按公开纪律将机器特定路径泛化（JDK 安装目录、fork 工作树、案卷绝对路径）；其余内容逐字保留，判词时间戳为 2026-11-15。
> 症状背景与挂载契约见 README；复盘摘要与逐版定罪链见 v0.0.12 归档位 Release notes。

---

# infdev-windowfix-agent 新仓交接简报（启动器侧小 javaagent + 实例设置必开功能项）

> 前案：`handoff-legacyfix-deawt.md`（§1-33，已案结）。fork 内 native-port 路线终止于十六轮（四件套 ✓ / 相机 ✗ / grab 过严 ✗）。
> 本方案 = 启动器侧自研小 agent 替代 fork 内修复，迭代环收回本仓。
> 已定决策：功能项**默认开、可关**（关 = 逃生门兼复现现状形态）；手术面**四项全做**；仅挂 `{inf-20100627, inf-20100629}`。

## 1. Agent 规格（新仓库要做的内容）

- **形态**：Java 8 目标，ASM 9.x shaded fat jar；manifest `Premain-Class`；premain 必须**容忍 `=gameDir` 参数**——启动器 javaagent 展开固定为 `-javaagent:<path>=<gameDir>`（launch.ts L265-279），agent 忽略即可。
- **版本自门控（防御性）**：仅当类形态匹配才变换——主类 implements Runnable + 持 `Ljava/awt/Canvas;` 字段 + run() 有 `Display.setParent` 嵌入路 + `Mouse.getDX/getDY` 直调回中形态（= 案卷 §2.2 指纹，0627/0629 独有）。不匹配 = 直通。
- **四刀**（全部有案卷出处，勿自创）：
  1. **ctor canvas 置空**（§28）：主类构造器 canvas 字段 PUTFIELD 之后再写 ACONST_NULL+PUTFIELD（embedded→standalone 转换）。0629 字段名 = `B`；0627 混淆名不同，按形态探测。
  2. **Display.create 后插**（§28 修复 2）：`setResizable(true)` + `Toolkit.getScreenSize()` 居中 `setLocation`。
  3. **关窗兜底**（§29 r13 骨架项，实机验证过）：原生循环自轮询 isCloseRequested，兜底路径 = wasCloseRequested → 进程自退。
  4. **grab 绑屏**（§32 正解）：探测主类 GuiScreen 型字段（0629 型 = `ae`），回中调用站点注入 `Mouse.setGrabbed(screenField == null)`——暂停菜单/界面开自动释放。**勿复刻十五轮无条件 grab**（那是 §32 定罪的过严形态）。
- **相机不动**：真 attic.10 的 MousePatch helper 供数链在 deawt 下已绿（r13 实证），agent 不做 `hn`/MouseHelper 手术。
- **诚实记账**（§13 教训：注入≠执行）：每刀应用时 stderr 打一行 `[windowfix] <site> applied`，运行期可见。
- **构建纪律**：JDK 8（本机任一 JDK 8 安装）；gradle shadowJar；产物名 `infdev-windowfix-<版本>.jar`（文件名钉版 = 显式升级通道，同 legacyfix 纪律）；迭代构建一律 `--rerun-tasks`（gradle 增量漏检两次教训 §12/§31；**哈希变化 ≠ 内容变化**）。
- **静态自证 harness**（移植 fork 侧形态，案卷 §28/§30 有全套断言样例）：生产路径 transform→toBytecode、单 loader `-Xverify:all`、javap 断言新体在场/原解引用清零。

## 2. 启动器侧集成（启动器仓文件路径）

| 面 | 文件 | 位置 | 动作 |
|---|---|---|---|
| 挂载表 | `packages/runtime/src/quirks.ts` | L40-62 | 加两条 AgentMount：form='javaagent'、path='infdev-windowfix/infdev-windowfix-<ver>.jar'、appliesFrom=appliesTo 分别钉 `inf-20100627`/`inf-20100629`（单版本区间）、quirk='infdev-windowfix' |
| 契约 | `packages/core-spec/src/version.ts` | L74-91 | AgentMount 已支持，**零改动** |
| 展开 | `packages/runtime/src/launch.ts` | L265-279 | javaagent 展开通用，**零改动**；挂载表顺序把新条目排在 legacyfix 之后（尾注入；attic.10 CtClass 头插已保第三方织入存活，MCWorldAgent 同链验证） |
| 下载 | `packages/runtime/src/betacraft.ts` | L529-578 同型 | 新 `ensureWindowfix`：新仓 GitHub Release 直链 + sha256/size meta 校验（照 ensureLegacyfix 形态）；`endpoints.ts` L15-24 加 release base |
| 开关字段 | `packages/core-spec/src/settings.ts` | L205-209 旁 | `infdevWindowfix?: boolean`（缺键 = true，intelFix 同语义） |
| 持久化 | `packages/runtime/src/storage.ts` | L194 / L266 / L364 | 键入档案回环清单（仅偏离缺省落键） |
| 装配 | `packages/runtime/src/runtime.ts` | L661（withAgentMounts 并入点）/ L1652-1653 / L1852-1853 | 开关 off → 滤掉 quirk='infdev-windowfix' 的 mount；缺键回落照 intelFix 形态 |
| UI | `packages/ui/src/components/InstanceList.vue` | L47-48 / L102-103 / L202 / L252-253 / L555-559 | checkbox 全同型复刻 |
| i18n | `packages/ui/src/i18n/locales/{zh-CN,zh-TW,en}.json` | instance.intelFix 旁 | name/desc 三份 |
| 测试 | `packages/runtime/tests/launch.test.js`（L337 同型）/ `storage.test.js`（L394 同型） | — | 开关 on/off 挂载滤除 + 档案回环断言 |

## 3. 验收标准

1. **0627/0629（不带任何 `-Dlf.*` 绕行参数）**：关窗自退 ✓ / 拉伸 resize ✓ / F11 ✓ / 皮肤 ✓ / 锁鼠光标不出窗 ✓ / **ESC 菜单鼠标释放可操作** ✓ / 相机正常（MousePatch 供数，应天然绿）✓。
2. 开关 off = 精确复现真 attic.10 现状形态（三症状在，作逃生门与取证）。
3. 回归：0625-1917 / 0630 / in-20100223 / c0.30 / a1.2.3 / b1.7.3 不劣化（挂载未钉它们 + agent 自门控双保险）。
4. `[windowfix]` stderr 行在 debug 局可见（运行证据纪律）。
5. 验收判词续写入 `handoff-legacyfix-deawt.md` §34+（或新案卷，用户定）。

## 3b. 试跑判词（0.0.1，2026-11-15 18:35，jar 298746B）

**局活**（pid 35608 窗 `Minecraft Infdev`，无异常栈），premain 记账行 ✓、`=gameDir` 容忍 ✓、命令行实证尾注入（legacyfix 在前 windowfix 在后）✓。

**定罪：自门控指纹失配 → 全刀 pass-through**（`[windowfix] fingerprint miss on candidate at`）。根因 = **transformer 链上 legacyfix 的类变换先于我们执行**——`at` 到达时 run() 内 getDX/getDY 直读已被 MousePatch 清零（这正是 MousePatch 的设计行为，attic.10 `Patched Mouse.getDX/getDY in Minecraft`）。指纹第 4 项「直调回中形态」在补丁后字节上永不成立。

**迭代要求**：
1. 指纹改按**补丁后形态**定义：主类 implements Runnable + 持 `Ljava/awt/Canvas;` 字段 + run() 含 `Display.setParent` 调用（DeAwtPatch 重写后形态仍可读）+ 存在 robot 回中调用站点（`Robot.mouseMove` 经 `hn.a(Point)` 形态，MousePatch 不清零它）——具体残存形态以本局 `at` 字节 javap 实证为准（素材 `%TEMP%\jpx\x` 为原始件，需另解包补丁后字节：agent harness `dump=` 或插桩落盘）。
2. 门控失配时 stderr 打**逐项判决**（哪项不中），别再只报一句 miss——下轮取证成本减半。
3. 基线对照：本局症状 = attic.10 现状三症状（**用户已确认形态依旧**，pass-through 语义成立、逃生门形态天然验证）。

## 3c. 0.0.2 试跑判词（2026-11-15 18:43，sha `3c58a289…`）

**记分**：fingerprint HIT（post-mousepatch 形态✓）· 四刀全 applied · 关窗 ✓（进程 exit 0 自退）· grab 绑屏+ESC 释放 ✓ · F11/皮肤 ✓ · **相机 ✗ · resize ✗**。

**主嫌 = 刀 1 置空时机（ctor 太早）**：fork r13 同款转换的绿配方是「run() 内 `Display.setParent` 调用**之后**置空」，而 0.0.2 在**构造器**就置空——游戏主循环从第一帧就走纯 standalone 分支，嵌入分支（robot 回中站点 + canvas 尺寸读点）整体熄火。相机供数链与 resize 检测都挂在这两条线上：
- §2.3 javap 事实：0629 主类 run() 有 **4 处 canvas 尺寸读点**——fork 的 resize 绿靠 DeAwtPatch 把这些读点改写/同步为 `Display.getWidth/getHeight`（resize 同步块）。0.0.2 刀 2 只做了 setResizable+居中，**缺尺寸同步半刀**。
- 相机：基线（0.0.1 局）绿 → 是本轮刀弄坏的。若回中/供数站点在 canvas!=null 分支内，ctor 置空即杀之。

**迭代要求（0.0.3）**：
1. 刀 1 置空时机从 ctor 挪到 **run() 内 setParent 调用后**（r13 实证形态），或给 agent 加逐刀 disable 开关（`-Dwindowfix.disable=ctor-canvas-null,...`）做运行时 A/B——一次构建分出毒刀。
2. 刀 2 补尺寸同步：把 run() 的 canvas 尺寸读点改写为 `Display.getWidth()/getHeight()`（fork resize 同步块语义，§2.3 读点位置有 javap 底）。
3. 取证升级：harness `dump=` 落盘补丁后 `at` 字节 + javap 对照 0.0.1 基线局（diff 即答案，别再推理）。

## 3d. 0.0.3 试跑判词（2026-11-15 19:13）——init 崩，毒刀钉死

`java.lang.VerifyError: (class: at, method: run) Inconsistent stack height 3 != 2`（MinecraftApplet.init 即炸，无窗）。

**定罪 = 新刀 size-sync**（其余三刀 0.0.2 已实证字节干净）：4 处 canvas 尺寸读点改写 + 「gate neutralized」判空中和的栈收支不平——典型毒形：判空站点 `ALOAD_0/GETFIELD B` 后跟 `IFNULL`，中和判空时把对象引用吃进 IFNULL 分支却没把等值比较支的栈形一起改；或读点替换成 `Display.getWidth()` 时旧序列残留 `POP` 记账错。**ASM 手改条件结构必须逐点重算栈收支，或改用 try-catch 无关的纯表达式替换（只换调用序列，不碰判空跳转）**。

**迭代要求（0.0.4）**：
1. size-sync 与 gate 中和重写：判空跳转结构保持原样（把 `IFNULL` 目标分支里的读点一并同步，而不是中和跳转）；每刀产出后过 `CheckClassAdapter`（asm-util 已在 shade 清单，运行时 `verify=true` 开关触发）——VerifyError 在 premain 当场报行，不进游戏 init 才发现。
2. 判词行加 size-sync 每站点栈收支明细（4 行），下轮 diff 即定位。
3. canvas-null/close/grab 三刀勿动（已绿）。

## 3e. 0.0.4 试跑判词（2026-11-15 19:56）——resize 绿，相机改判毒刀本身

记分：size-sync 重做生效（栈收支 net=+1 对齐、gate 保持、standalone 同步块）→ **拉伸 resize ✓**；✕ 关窗 exit 0 ✓；grab+ESC ✓；F11/皮肤 ✓；**相机仍 ✗**。

**改判**：§3c 的「置空时机太早」假说被 0.0.4 证伪（r13 同款 post-setParent 形态相机照样红）。对照全数据：相机绿的唯一形态 = 0.0.1 基线（canvas 不置空）；红 = 一切做了 embedded→standalone 转换的形态（fork r13-r15 + agent 0.0.2-0.0.4）。**毒 = canvas-null 刀本身**：置空后 run() 的 canvas!=null 判空把 robot 回中分支整体熄火，而 MousePatch 的 delta 供数（b/c 写点）就住在回中方法 `hn.a(Point)` 里——分支死 → 供数死 → 相机死。

**0.0.5 配方（删刀而非调刀）**：
1. 先 A/B 实证：若有逐刀开关，跑 `-Dwindowfix.disable=canvas-null`（无则先加）——预期相机绿且其余四刀照常生效。
2. 预期成立则 canvas-null 整刀移除（或降为默认关）。依据：0.0.1 基线实证「不置空」形态下窗（deawt setParent 重写自出独立窗）+ 相机都正常；基线三症状（抓不住/关窗/拉伸）恰由现存四刀（grab-bind/close-exit/size-sync/display-create）外部补齐——各刀均不依赖 canvas==null。
3. size-sync 注意：canvas 非空时 gate 走正常支，读点已换 Display 照常同步，预期无冲突；A/B 局顺带复核 resize。

## 3f. 0.0.5 试跑判词（2026-11-15 20:05）——§3e 证伪，毒收敛到现存四刀

记分：**相机仍 ✗**，其余全绿（resize ✓ 复核过、关窗 exit 0 ✓、grab+ESC ✓、F11/皮肤 ✓）。

**逻辑链**：0.0.1 基线（四刀全无）相机绿 → 0.0.5（canvas-null 已拆、其余四刀在）相机红 ⇒ 毒 ∈ {size-sync, grab-bind, close-exit, display-create}。**首嫌 grab-bind**：注入站点 = 2 处回中调用点——与相机供数链（MousePatch 住在 `hn.a(Point)` 里）同一线路；setGrabbed 注入若包裹/改写了回中调用序列或先于 hn 消耗 delta（getDX 消耗式读），供数即断。次嫌 size-sync 的 gate-target 同步块（若回中分支与 resize 检查同居 canvas!=null 判空结构内，块插入位置可能截了回中支的去路）。

**0.0.6 要求（停止整刀推理，跑运行时二分）**：
1. **逐刀 disable 开关必须落**（§3c.1 已提过，若未实现本轮补）：`-Dwindowfix.disable=grab-bind,size-sync,...`（逗号集，默认空）。启动器侧随即跑 A/B 矩阵：先 `disable=grab-bind`（相机预期翻绿即定罪），再按残余症状收敛。
2. grab-bind 若定罪：正解参考 LW MouseFix 语义——setGrabbed 绑点别打在回中调用序列内部，改挂 `Display.update` 后（每帧一次，读屏字段判态），与供数链零交集。
3. 判词行补 grab-bind 两站点的插入位置描述（方法+行号偏移），A/B 局 diff 即定位。

## 3g. A/B 定罪（2026-11-15 20:15，0.0.6 `disable=grab-bind`）

**相机翻绿 ⇒ grab-bind 定罪**（注入回中调用站点 = 掐断 MousePatch 供数链，机制与 §3f 首嫌一致）。size-sync/close-exit/display-create 三刀无罪（本局照常 applied 且 resize/关窗/F11 全绿）。

**0.0.7 重做要求**：
1. grab-bind 换绑点：不再碰回中调用站点，改挂游戏 update 路（`Display.update` 后每帧一次，LW MouseFix 语义 `setGrabbed(active && GuiScreen==null)`）——与供数链零交集。
2. **双判点验收**：相机绿 **且** 锁鼠+ESC 释放绿。本 A/B 局锁鼠已回退为红（刀禁用的预期代价），只补好相机不算过。
3. disable 开关保留（运行时二分已被证明一轮定案，比整刀推理省三轮）。

## 3h. 0.0.7 试跑判词（2026-11-15 20:23）——grab 换绑点引入新回归：标题菜单卡死

症状：左右键失效、卡标题菜单（相机/其余判点因此**未及测试，悬置**）。

**机制定罪（无需新实验）**：0629 标题菜单**不建 GuiScreen**（菜单绘制在主类、点击走 Mouse.getX/getY 绝对坐标），新绑点每帧判 `active && screen==null` → 菜单处 screen 恒 null → grab 误开 → 原生光标锁窗心 → 点击永不落按钮。老绑点（回中站点）只在游戏中走到，天然避开菜单——换绑点把「无屏=游戏中」这个不成立等式暴露了。

**0.0.8 要求**：
1. grab 判据补**在游戏中门**：`active && screen==null && inGame`。inGame 信号取主类 javap 实证的渲染器/玩家字段非空（与回中分支守卫同源字段优先——它恰好就是「游戏中才走到」的那个条件）。
2. Display.update 绑点本身保留（§3g 双判点里相机侧待验：0.0.7 因卡菜单未测，0.0.8 复跑时相机+锁鼠+菜单点击三判点一起收）。
3. 菜单回归列入验收：标题界面光标自由、Singleplayer/存档按钮可点。

## 3i. 0.0.8 试跑判词（2026-11-15 20:33）——`w` 门无效，javap 定正解门

菜单仍卡（相机因此未及判）。**javap 实证 `w:Z` 是 boolean**（回中分支守卫 `getfield w@1513 → getDX@1519`，即「第一人称」类标志）——菜单处恒真，门形同虚设。§3h 的「同源字段优先」指错了对象：同源守卫只保证「游戏中会走到」，不保证「菜单处为假」。

**0.0.9 正解门（javap 底已翻实）**：`active && screen==null && d!=null`——`d:Lez` = WorldRenderer：
- 全类**唯一写点** = setter `putfield #203`（偏移 20，存参），构造器不初始化 → 开机菜单天然 null；
- 游戏中非 null；ESC 暂停屏由 screen 门接管。
- 若「退出存档回菜单」后 d 未随 leaveWorld 置 null（javap 静态不可判，实机第二幕菜单即测），加双保险 `&& k!=null`（`k:Lhy` 玩家，同为世界加载期写入）。

**验收补一条**：进档→退档→**二次回菜单**光标自由（d 泄漏检测）。

## 3j. 0.0.9 A/B 定罪（2026-11-15 21:38）——毒形修正：不是 grab 误开，是每帧 Mouse 调用吃事件

A/B 矩阵收齐：`disable=grab-bind` 菜单**能点**；全刀局菜单**光标自由但点击无响应**。

**推翻 §3h/§3i 因果链**：光标自由 ⇒ 菜单处 grab 并未开启（`d!=null` 门本身是对的，或至少 setGrabbed(true) 没发生）。毒形 = **刀每帧调用的 Mouse API 路径在消耗输入事件**——attic.10 MousePatch 重写过 `org.lwjgl.input.Mouse`（含 grab/事件语义），外部注入每帧调 `Mouse.setGrabbed(...)`（即便传 false）会走补丁后的实现，顺带泵/清事件队列 → 游戏菜单永远收不到 getEventButton → 点击全灭。老绑点局（0.0.2-0.0.6）菜单无恙 = 站点只在游戏内走到，从不在菜单调用。

**0.0.10 终版刀形**：
1. **边沿触发**：注入代码缓存 last-apply 状态，仅在 `desired != last` 时调 setGrabbed——菜单稳态（恒 false）= 零调用零干扰；游戏内进出 ESC 各一次。
2. 门保持 `active && screen==null && d!=null`（A/B 证明门未开 grab，无需再改）。
3. 判点扩回四项：菜单点击 / 相机 / 锁鼠+ESC 释放 / **游戏内挖矿放置点击**（事件消耗若在局内同样发作，此前从未测到）。

## 3k. 0.0.10 试跑判词（2026-11-15 21:45）——菜单/相机双绿，游戏内 grab 不生效

记分：菜单点击 ✓ · 相机 ✓ · **游戏内左右键（挖矿/放置）✓**——事件消耗毒形随边沿触发根治 · **锁鼠 ✗（游戏内 grab 恒不触发，处 disable 态）**。0.0.11 只剩 grab 生效性一项要收。

**假说序（按嫌疑排）**：
1. **`Display.isActive()` 在 deawt 环境下恒假**——老绑点判据里没有 active（0.0.2-0.0.6 锁鼠绿 = 无 active 项也能工作）；LW MouseFix 带 active 是 betacraft 网页环境语义，照搬进 deawt 桌面窗可能永远拿不到 true → desired 恒假。
2. 站点落错循环段：run()@2213 若实际在菜单段而非游戏段，游戏内永不执行注入（0.0.7-0.0.9 菜单卡死 = 站点至少菜单段在跑；游戏段待证）。
3. 边沿缓存初始化错（last 初值=true → 首次 desired=true 不触发）。

**0.0.11 要求**：
1. **删 `active` 项**（假说 1 成本最低且老配方反证其非必要）：门 = `screen==null && d!=null`。
2. 加 `-Dwindowfix.debug=true` 遥测：desired 及各门项**变化时**打一行（进档/ESC/回菜单各一条），站点执行计数每 600 帧一条——三个假说一局分完。
3. 判点维持 §3j 四项 + 复核 ESC 释放与二次回菜单。

## 3l. 0.0.11 试跑判词（2026-11-15 21:58）——遥测一局现行：反射层级查找错

`[windowfix.grab] tick world error: java.lang.NoSuchFieldException: d`——GrabHelper 反射读 `d` 恒失败，grab 依旧不生效（其余判点本轮不重测，刀面未变）。

**根因（案卷旧事实直接钉死）**：§12 javap 实证主类 `at` 是 **abstract** 类，运行时实例是其具体子类——`obj.getClass().getDeclaredField("d")` **不查父类层级**，字段声明在 `at` 上 → 恒 NoSuchFieldException。

**0.0.12 修法（一行级）**：
1. 反射改**沿 superclass 链查找并缓存 Field**（一次性解析，tick 路径零反射查找）；或 transform 期就把 `at` 的 Class 句柄/字段偏移烧进注入点。
2. 同类错误**只报一次**（现在每 tick 刷行会淹日志）。
3. 修完本轮判点直接收 §3k 四项 + ESC 释放 + 二次回菜单（遥测行保留供终验存档）。

## 3m. 0.0.12 试跑判词（2026-11-15 22:0x）——锁鼠唯一红，遥测定位到 screen 项读取路径

记分：除锁鼠外全绿（菜单/相机/左右键/resize/关窗/F11/皮肤）。遥测铁证（本局 301 行 grab 状态）：

- 游戏内 `worldOk=true`（d 的父链缓存反射已修好，门的一半正确）
- 但 **`screenNull=false` 恒成立（含游戏内）** → `desired=false` 恒成立 → setGrabbed 从未被调用。

**根因**：screen 项仍走注入参数 `GrabHelper.tick(this, screen)`（站点 GETFIELD 传入），该读取路径绑错——读到一个恒非 null 的对象。与 §3l 的 d 是**同一 bug 类（字段解析错）的第二处**，非路线性死循环；d 的修法已被 worldOk=true 实证有效。

**0.0.13 配方（结构版——灭绝 bug 类，不是再修一处接线）**：
用户元质疑成立：0.0.11→0.0.13 连修三轮全是「helper 运行时反射读混淆字段」同一 bug 类。结构性根除 = **GrabHelper 零反射**：
1. 门的全部字段读取（d、screen）改在**注入点字节码**里算：transform 期 agent 已从指纹拿到 `at` 的字段名，直接生成 `ALOAD_0; GETFIELD at.d; IFNULL→false; GETFIELD at.screen; IFNONNULL→false; …` 汇成 boolean，调 `GrabHelper.tick(boolean desired)`。GETFIELD 由 JVM 校验期沿父链解析——层级/命名/参数绑错三类事故同时物理消失（§3l/§3m 都不会再有可能发生）。
2. helper 只剩：缓存 last、边沿触发、调 `Mouse.setGrabbed`、遥测行（desired/last 照打）。
3. 验收过渡判据不变：进档 `desired=true last=true` 行、ESC 回 `desired=false`、退档回菜单不再需要 worldOk 字样（项名可简化为 desired/last 两态）。

## 3n. 路线止损线 + 0.0.13 遥测判词（2026-11-15 22:12）

**用户止损令**：自 0.0.13 起至多再迭代 3 次，仍不成即终止 javaagent 路线。

0.0.13 结构版遥测（pid 17000）：门首次全程按设计运转——`frame=1 desired=false`（菜单）→ `frame=619 desired=true + EDGE -> setGrabbed(true)`（进档，**调用历史上首次真实发生**）→ `frame=1200 desired=true last=true`（稳态）→ `frame=1780 desired=false`（ESC/回菜单释放边沿）。接线类 bug 确认灭绝（零反射生效）。

**待判**：setGrabbed(true) 的物理效果（光标是否锁窗）。若绿 → 收全判点即案结；若红 → §3m 预告的「调用点环境暗礁」现身，进入止损额度内的下一版。

## 3o. 案结判词（2026-11-15 22:2x）——0.0.13 彻底修复，用户实机全绿

用户判词：「彻底修复，其他问题没有再复现，收口」。0629 全判点：菜单点击 ✓ 相机 ✓ **锁鼠 ✓（ESC 释放/恢复正常）** 游戏内左右键 ✓ resize ✓ 关窗自退 ✓ F11 ✓ 皮肤 ✓。

**终版刀形存档（0.0.13）**：指纹=补丁后形态（post-mousepatch）；四刀=size-sync（4 读点 POP+STATIC + gate-target 独立块）、grab-bind（2 站点 @Display.update 后，门 `m==null && d!=null` 全在注入字节码里算，GrabHelper 零反射、边沿触发、只调 setGrabbed）、close-exit（polls=1 guards=4）、display-create（setResizable+居中）。canvas-null 刀已永久移除。

**路线复盘（回应止损质疑）**：0.0.11→0.0.13 三轮「修逻辑」实为同一 bug 类（helper 运行时反射读混淆字段）的连环排雷；结构版（零反射+字节码门）一轮灭绝，随即案结。教训入卷：**注入代码永远不要在 helper 里反射读游戏字段，字段读取放注入点字节码，helper 只收原始类型**。

回归局（0625-1917 挂 0.0.13，验指纹直通）：见 §3p。

## 3p. 回归局判词（2026-11-15 22:14）——非受害版直通 ✓

0625-1917 挂 0.0.13：`fingerprint miss on candidate net/minecraft/client/f — pass-through`，且逐项判决行齐落（runnable/canvas-field/run()V/parented 各一条——§3c 要求的失配可解释性达成）。窗口正常起（pid 38760），游戏类未被触碰 = 现状零变化。**回归验收通过，全案收口。**

## 4. 素材索引（新仓开发时取用）

- 案卷：启动器仓 `Attic-Minecraft-Launcher/handoff-legacyfix-deawt.md`（§2.2 指纹矩阵、§28/§30/§32 手术规格、§33 案结备忘）。
- 反汇编：本机取证目录 `%TEMP%`（jp-at-0627/jp-at-0629/jp-f-0625/jp-aw-0630，javap -p -s -c；jpx/x = 0629 解包类，含 at/hn/ae/di 全量）——Mojang 专有字节，不入库。
- LW 参考：`MouseFix` 语义（grab 绑 active&&无屏、setDelta 重写）见 legacyfix LW 源。
- fork 工作树（十五轮 native-port 未提交改动，7 文件）可作反例参考：本机 `legacyfix-attic` 工作树。
