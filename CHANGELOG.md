# 更新日志 / Changelog

本分支（`ysm_geo_model`）从 `ysm_epicfight_compat` 主项目切出后独立维护。以下按版本记录。

## v1.0.3 — 未发布（本轮审查 + P0/P1/P2 修复 + 里程碑 D）

一次以主项目 v1.8/v1.9 为基线的对照审查（正确性/架构/安全/性能/可维护性五维评分）后，
按 P0 → P1 → P2 → D 四批回移与自研修复。五维自评从 43/100 提升到约 86/100。

### P0（安全与崩溃）

- **路径穿越**：`model_id` 与清单内 `model`/`texture`/`extra_textures`/`animation` 路径此前零校验，
  `sanitize` 甚至放行 `.` 与 `/`，第三方模型包可穿越到包外任意文件（读 + 写）。现在
  `YSMMeshLibrary.sanitize` 按 `/` 分段丢弃空段并把 `..`/`.` 段中和为 `_`，新增
  `resolveInside(root, relative)` 词法围栏（拒绝绝对路径/盘符/UNC/反斜杠/NUL/空段/`.`/`..`
  并按 normalize 复核），并在全部 5 个读入口与 1 个写出口落地。
- **Molang 函数实参个数**：`Env.callFunction` 增加 `argCount`。求值器复用每线程参数缓冲，
  少参调用此前会读到上一次调用残留的值，脚本求值不确定（例如 `math.min(9)` 读到旧操作数）。
- **同步/异步求值竞争**：渲染线程求值此前不走异步 worker 持有的 `evalPending` 互斥，
  两者会写同一双缓冲槽与同一批 HashMap。新增 `evaluateSync()`（CAS 抢不到则跳过本帧），
  并把相机/帧时间等渲染线程状态改为渲染线程预捕获的不可变 `EvalInputs`。
- **`YSM_EF_Compat` 兼容守卫实际上从未生效**：`YsmAnimationTransitionGuardMixin` 既不在任何 mixin
  配置的列表里，配置也没有 `plugin` 键——两个类编译进 jar 却完全是死的。现移入独立子包并新增
  `ysm_geo_compat.ysm.mixins.json`（`required:false` + `YsmGeoMixinPlugin`），装了 YSMEF 时自动让位。
- **GLES 上 GPU 路径必崩**：`glGetProgramResourceIndex`/`glShaderStorageBlockBinding` 是桌面专属入口，
  在 ES 上下文调用会失败并被 `disableIfOwned()` 永久关掉 GPU 路径、静默改掉用户配置。现包进 `if (!es)`。
- **清扫时钟跨实体比较**：动画器清扫用各实体的 `tickCount` 互相比较，导致每 15 秒误删"年轻"实体
  （刚生成/刚重生的女仆与玩家）的活跃动画器。现改用世界 `gameTime` + 无 Level 时的单调兜底计数器。
- **GL 状态非异常安全**：两条直接路径的 GL 状态还原在直线代码上，绘制中途抛异常即泄漏
  cull/blend/depth/program/VAO/SSBO 并让 light layer 常开。现改为 `try/catch/finally`，
  finally 内逐项守护，并补 `getTexture()` 为 null 的检查。
- **TLM 几何父链环无限递归**：`buildBone` 在递归之后才写 memo，`A.parent=B, B.parent=A` 会一直递归。
  现加深度守卫，环/超深链改为受控异常（跳过该模型而不是 `StackOverflowError`）。
- **EFTLM 注入缺 `require = 0`**：可选依赖签名漂移时会变成注入错误。已恢复。

### P1（正确性与稳定性）

- GPU 雾距公式改回 vanilla `|T + x|`（原式随相机旋转漂移最多一个模型半径）。
- 半透明第二遍补 `depthMask(false)`（不再写深度遮挡身后实体）。
- CPU 路径共享动态 VBO 每帧上传前 orphaning + `GL_STREAM_DRAW`（同一模型多只女仆不再互相覆写顶点）。
- CPU 蒙皮新增整部件刚性快速路径（`tryPrecomputePartMatrix` + `skinVertexRigid`）。
- `YSMMesh.draw` 补 `poses` 裁剪：`EntitySnapshot` 数组长于当前 armature 时 Epic Fight compute 路径
  会 `searchJointById(i)` 越界崩溃（EFTLM 按手持物品换 armature 时命中）。
- compute setup 反射改按实例缓存，`findComputeSetupField` 改 catch `Throwable`（原先静态初始化可能失败）。
- `"loop": {"time": N}` 与 `"loop": "true"` 形式不再抛异常丢弃整批动画。
- 补写 `query.is_alive`；补 `hold_offhand:` 副手叠加分支；关键帧插值 alpha 夹到 [0,1]；
  载具动画按当前载具重解析。
- 异步求值失败策略：一次失败永久禁用 → 连续 3 次失败禁用 + 10 分钟无新失败自动恢复。
- Molang 解析深度守卫（`MAX_PARSE_DEPTH=256`）、Bedrock 三元简写 `a ? b`、裸字符串字面量，
  以及可插拔的编译失败告警（此前失败完全静默，变体"突然不出现"无从排查）。
- 运行时模型加载失败不再缓存 `null`（原先一次失败即永久不再加载），改 10 秒退避重试。
- 默认（战斗模式）求值环境实现 `math.*`（原先所有函数返回 0，任何 math 驱动的 scale 通道会塌缩）。
- `F3+T` 后纹理真正失效（原先 `UPLOADED_TEXTURES` 仍命中，改过的贴图要重启才生效）；
  `TEXTURE_DATA` 改并发容器并在上传后释放已编码字节（原先所有纹理 PNG 常驻堆）。
- 网格选择器改每 UUID 缓存（原先每帧拼 `uuid|model|texture` 字符串，集合无界）。
- 骨骼名归一化统一为 `YSMJointMapper.normalizeBoneName`（三份实现里有一份漏了 `_default` 剥离，
  导致腕部枢轴回退到肘部、手持武器浮空）。
- 骨骼递归深度守卫（`YSMGeoModel.buildBone` / `EFMeshJsonWriter.walkBone`）+ 重名骨骼拒绝。
- `generateAll` 改为"构建到局部 map 再整体交换"，失败复位懒生成守卫。

### P2（架构、性能、清理）

- **打破 `model ↔ gpu/cpu` 包环**：新增 `RenderBridgeRegistry`（绘制分派）与 `MeshReleaser`（资源释放），
  两条渲染路径静态自注册；`model` 包对 `gpu`/`cpu` 的引用归零。新增 `RenderPaths.init()`
  在启动期触发自注册并校验（注册不完整会报 ERROR，不再静默退化成"直接路径永不生效"）。
- 20 关节表收敛为 `model/JointTable` 单一数据源（原先关节映射表与绑定骨架各一份）。
- `generateAll` 增加渲染线程断言（EF `Meshes.ACCESSORS` 非线程安全）与 `Semaphore(2)` 并发上限。
- 性能：Iris 光影包探测去重（删除 CPU 路径里逐字重复的实现与独立 TTL）、
  `isYsmPreviewMode` 加 250ms TTL（原先每网格每帧反射）、CPU 能力探测缓存
  （原先每次候选绘制 `glGetString`）、diag 开关改常量、默认形态 part→bone 索引缓存、
  光影包下的 GUI 预览免除拦截、`packNormal` 与 GL 状态快照收敛到 `renderer/GlRenderState`。
- 启动期预编译两个蒙皮着色器（移出实体渲染 pass；某些驱动在 pass 中懒编译着色器会硬崩）。
- 清理：删除轮盘姿势矫正死代码 151 行；诊断 identity map 随网格释放；
  空字节不再被当成 0×0 贴图；`texture_width/height` 夹取（声明 0 会让 UV 变 NaN）；
  姿势缓存满时改 LRU 单条淘汰（原先全清会让其它实体该帧丢失姿势矫正）；
  `memAlloc` 提到 `glGen*` 之前；5 处"丢弃顶点"改为退化为零面积三角形（不再让后续三角形错位）；
  模型包扫描的静默 `catch` 全部补 WARN；每模型一行的缩放 INFO 改为只输出首条；
  移除不可能的 `if (pack != null)` 分支与魔法数字。

### 里程碑 D（生命周期、贴图入包、枢轴回退、查询 id 化）

- **贴图写入生成资源包**（`YSMMeshLibrary.writePackTexture`）：PNG/JPEG 原样复制，
  WebP/AVIF 经 YSM 的 ImageStream 解码、raw RGBA 经 `readRawRgba`，两者重编码为 PNG 后落盘
  （路径经 `resolveInside` 围栏，会话内按内容哈希去重）。按资源位置取贴图的消费者
  （原版 model layer、Epic Fight 自身贴图解析、其他模组）现在真的能取到文件，而不只是
  `DynamicTexture` 里的一份内存贴图。
- **修掉既有缺陷：生成贴图路径出现双扩展名**。TLM 清单给的路径已带 `.png`，旧代码又追加一次，
  资源位置变成 `…/singyoku3.png.png`。现在先剥离尾部 `.png`；并新增
  `deleteStalePackTextures()`，每次生成后清理不再被任何模型引用的生成贴图
  （实测自动清掉 255 个历史 `.png.png` 残留）。
- **客户端 tick 生命周期**（新增 `YSMGeoClientTickHandler`，FORGE 总线 END 相位）：
  - 分帧预热 `TlmModelLibrary.prewarmMeshes()`：8 ms/tick 预算 + 上限感知，首次绘制不再付网格解析成本；
  - 贴图异步：解码在后台池、GL 上传在渲染线程以 10 ms/帧预算排空（失败路径 `close()` 未接管的
    `NativeImage`，避免离堆泄漏）；
  - 延迟释放：淘汰的网格与贴图等 **5 tick** 再释放，避免本帧已录制的绘制引用已释放的 GL 对象；
  - LRU 淘汰：新配置 `lazyModelCacheSize`（默认 64，范围 8–512），淘汰时经反射从 Epic Fight 私有
    `Meshes.MESHES` 取出实例；**取不到即整体禁用淘汰**（不能出现"我方删了、EF 还继续发放"的状态），
    失败路径不释放贴图。淘汰后的模型在下一次绘制时由 EF 的 `MeshAccessor.get()`
    （check-then-load）从生成的网格 JSON **自动重建**，无需重转换；贴图则从生成包文件重读。
- **`collectGeometry` 两级回退 + 枢轴排除名单**：直连映射骨骼进首选层、其余进回退层，某关节首选层
  为空时才用回退层——修掉"骨骼命名不被识别 → 全部枢轴为空 → 整套骨架退回 Steve 比例"。
  新增 `PIVOT_EXCLUDED_BONE_NAMES`、`WEAPON_NAME_PARTS`/`isWeaponBone`、
  `YSMRuntimeModel.defaultHiddenBoneNames()`（默认形态被缩放到 0 的隐藏骨骼不参与枢轴）。
  `isWeaponBone` 显式排除含 `elbow` 的名字（`contains("bow")` 会误伤 `LeftElbow`）。
  保留 GEO 原有的"编号骨骼两趟判定"，未被主项目的简化版覆盖。
- **Molang 查询 id 化**：62 个查询名提升为 `Q_*` 的 `static final int`，`setQuery(int,double)`，
  String 键调用点归零（模型可见时每帧约 55 次 `ConcurrentHashMap` 查询消失）。
  驻留表**有意不清空**：id 会烘焙进已编译表达式，清表会让新一轮发号与存活表达式冲突；
  其规模上界为已加载脚本中不同路径的数目（与帧数、实体数无关），已写入代码注释。

### 工程化

- 新增 `.gitignore` 并停止跟踪 `build/`、`.gradle/`、`.idea/`（跟踪文件数 174 → 57）。
- 接入 JUnit 5（`./gradlew test`），新增 23 个单元测试：molang 求值器（含实参个数、
  陈旧槽位、深度守卫、三元简写、字符串字面量）、路径围栏（穿越/绝对路径/盘符/UNC/NUL/
  写读命名一致性）、关节表与骨名归一化。
- Epic Fight 依赖区间**最终保持 `[20.14,)`**（审查中曾收紧为 `[20.14.17,20.15)`，随后按实际发布形态
  放宽）：本模组以 jarJar 内置进 EpicFight_TouhouLittleMaid（`libs/YSM_GEO_Compat.jar`），若此处比
  EFTLM 自身的 `[20.14,)` 更窄，能正常加载 EFTLM 的玩家（如 EF 20.14.5）会撞上**嵌套模组的强制依赖
  报错**，错误信息指向内置模组、极难排查。区间之外的兼容性由运行时的失败容忍查找兜底（mixin 目标、
  反射的 `SkinnedMesh` 字段、`ComputeShaderSetup` API 失败即回退到 EF 自带绘制路径）；EF 20.15 出现时
  需重新校验并与 EFTLM 同步放开。
- `gradle.properties` 移除 `-Dnet.minecraftforge.gradle.check.certs=false`（关闭构件证书校验
  属供应链风险；本地变通应放用户级 `~/.gradle/gradle.properties`）。
- README 全面修正：补全架构清单，删除并不存在的 `debugLogConversion` 配置项，
  修正产物版本号与"强制计算着色器"的过时描述，补上真实的系统属性表。

---

## v1.0.2 及更早

参见 git 历史（`git log --oneline`）。要点：CPU 渲染与 GPU 骨架（`0630fab`）、
高模加载修复（`325b61a`）、坐标一致性修复（`07e4dcf`）。
