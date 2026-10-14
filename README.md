# YSM GEO Compat

将 Touhou Little Maid（车万女仆，TLM）模型包中的 GEO 模型在运行时转换为 Epic Fight 的 SkinnedMesh，使自定义女仆模型可以直接使用史诗战斗（Epic Fight）的战斗动画系统。

**本模组不包含任何 YSM 模型支持**（不解析 `config/yes_steve_model`、不解密 `.ysm` 包）。玩家 YSM 模型与"使用 YSM 模型的女仆"由 **YSM_EpicFight_Compat** 模组负责；两者共存时，本模组在女仆渲染钩子上对 YSM 女仆主动让位。

| 项目 | 值 |
|---|---|
| Mod ID | `ysm_geo_compat` |
| Minecraft | 1.20.1（Forge `[47,)`） |
| 必装依赖 | Epic Fight `[20.14,)`（与 EpicFight_TouhouLittleMaid 自身区间保持一致，原因见下） |
| 可选依赖 | Touhou Little Maid `[1.5,)`、EpicFight_TouhouLittleMaid `[1.1,)`、YSM_EpicFight_Compat（YSM 女仆支持） |

依赖区间说明：本模组会被 **jarJar 内置**进 EpicFight_TouhouLittleMaid（EFTLM 的 `libs/YSM_GEO_Compat.jar`），
因此这里的 Epic Fight 区间必须与 EFTLM 自身声明的 `[20.14,)` **一致**——一旦更窄，能正常加载 EFTLM 的玩家
（例如 EF 20.14.5）会撞上**嵌套模组的强制依赖报错**，错误信息指向内置模组，极难排查。
超出已验证版本（20.14.17）的部分由运行时的失败容忍查找兜底：mixin 目标、反射的 `SkinnedMesh` 字段与
`ComputeShaderSetup` API 都在失败时回退到 Epic Fight 自带绘制路径。EF 20.15 出现时需重新校验，
并与 EFTLM 同步放宽区间。

---

## 功能特性

- **GEO 模型 → Epic Fight 网格**：将 TLM 模型包（`tlm_custom_pack` 目录包与 jar 内置 `maid_model.json` 条目，含 GeckoLib 模型）转换为 Epic Fight `animmodels` 网格 JSON。
- **女仆支持**：通过可选 mixin 钩住 EpicFight_TouhouLittleMaid 的 `PatchedLivingMaidRenderer`，女仆的 TLM 模型替换为转换网格；支持 `extra_textures` 变体（hash 变体 id）与 `show_backpack` 配置。
- **运行时变体控制**：解析模型包的 bedrock molang 动画脚本（平行脚本/状态动画/条件动画），在战斗中只显示默认形态（低血狐狸、魔法阵、枪械、背包等变体正确隐藏），空闲时按真实实体状态（血量/手持物品）动态切换。
- **外部模组联动动画过滤**：`iss:*`（Iron's Spells）、`tac:*`（TAC 枪械）等与第三方模组联动的动画被自动排除，其变体骨骼按平行脚本保持默认隐藏。
- **两条直接蒙皮渲染路径**：
  - **GPU 路径**：骨骼 SSBO + 蒙皮着色器，整模型一次 `glDrawArrays`（桌面 OpenGL 4.3+ / OpenGL ES 3.1+）。
  - **CPU 路径**：逐顶点 CPU 蒙皮 + 动态 VBO + `cpu_skin` 着色器，只需桌面 OpenGL 3.3 / OpenGL ES 3.0，适配老旧显卡、核显与低内存设备（整部件刚性单关节时走预计算矩阵快速路径）。
  - 两者都不可用或按设计让位时（GUI 预览、轮廓 pass、光影包等）回退 Epic Fight 自带路径。
- **性能优化**：molang 整数 ID 内联求值、运行时模型后台预编译、非本地实体异步脚本求值（双缓冲）+ LOD 距离降频（40/64 格 → 30/10Hz）、逐实体动画器清扫（15s/60s）、关键帧增量游标、零分配矩阵合成、着色器启动期预编译、热路径探针缓存。

---

## 架构

```
com.ysmef.geomodel
├── YSMGeoCompat                # 模组主类（@Mod, modId=ysm_geo_compat）；安装 molang 失败告警
├── YSMGeoModel                 # Bedrock 几何解析（TLM 模型包 gecko/非 gecko 共用）
│                               #   + MAX_BONE_DEPTH 深度守卫 + texture_width/height 夹取
├── config/
│   └── YSMCompatConfig         # 客户端配置（enableGpuRender、scriptAsyncEval、lazyModelCacheSize）+ DIAG 开关常量
├── ysm/script/                 # 迷你 molang 解释器 + bedrock 动画 JSON 互转
│   ├── Molang                  # 编译缓存 + 路径整数 ID 内联 + 常量折叠 + 解析深度守卫
│   │                           #   + 三元简写 a?b + 裸字符串字面量 + ErrorReporter 接口
│   ├── ScriptAnim              # 动画数据结构
│   └── ScriptJson              # bedrock 动画 JSON ↔ 运行时 JSON 互转 + 相关性过滤
├── model/
│   ├── EFMeshJsonWriter        # 生成 EF animmodels JSON（quad 扇形三角化、关节绑定、运行时 JSON）
│   ├── JointTable              # EF 参考双足骨架 20 关节表（单一数据源）
│   ├── MeshReleaser            # 资源释放接口（渲染路径自注册）
│   ├── RenderBridgeRegistry    # 绘制分派接口（GpuSkinRender / CpuSkinRender）
│   ├── TlmGeoModelParser       # TLM 非 gecko 女仆模型的几何解析
│   ├── TlmModelLibrary         # TLM 模型包扫描/转换、动画文件→运行时、变体、show_backpack
│   ├── YSMJointMapper          # 骨骼名 → EF biped 关节 id（含唯一 normalizeBoneName）
│   ├── YSMMesh                 # 网格子类：贴图覆盖、逐帧运行时变换、姿势裁剪、路径分派
│   ├── YSMMeshLibrary          # 生成包路径/sanitize+resolveInside 围栏/贴图注册与上传/预编译池
│   └── runtime/
│       ├── YSMRuntimeModel     # 运行时模型编译（骨骼表/绑定矩阵/动画编译 + 默认可见性 + 失败退避）
│       ├── YSMPlayerAnimator   # 逐实体 molang 求值器（状态机/时间线/插值/异步双缓冲/LOD）
│       ├── YsmBindArmature     # 按模型自身骨骼枢轴重建绑定骨架（战斗旋转轴心矫正）
│       └── YSMRuntimeBridge    # 渲染管线 ↔ 脚本求值器桥（ThreadLocal 当前实体）
├── renderer/
│   ├── GlRenderState           # GL 状态快照/还原 + 唯一 packNormal 实现
│   ├── RenderPaths             # 启动期触发两条路径自注册 + 着色器预编译
│   ├── YSMMeshSelector         # 女仆网格选择（运行时模型 ID + 贴图覆盖）
│   └── YSMBattleMode           # 战斗模式判定（反射 isFightMode，女仆）
├── cpu/                        # CPU 蒙皮路径：YsmCpuMesh / YsmCpuRenderPath / YsmCpuSkinShader
├── gpu/                        # GPU 蒙皮路径：YsmGpuMesh / YsmGpuRenderPath / YsmBoneSkinShader
│                               #   / YsmGpuCapability / YsmGpuRenderEnable / GpuShaderUtil
├── eftlm/
│   └── YsmMaidMeshSupport      # 女仆 TLM 模型网格选择桥（YSM 女仆让位给 YSMEF）
└── mixin/
    ├── RenderSystemAccessorMixin / SkinnedMeshCpuRenderMixin / YsmArmaturePoseMixin
    ├── YsmGeoMixinPlugin       # 可选 YSM 守卫的开关插件（装了 YSMEF 时禁用）
    ├── eftlm/YsmMaidRendererMixin   # 劫持 EFTLM 女仆网格选择（可选配置）
    └── ysm/YsmAnimationTransitionGuardMixin  # YSM 空 transition 守卫（可选配置）
```

事件入口两个（总线不同，勿混）：

- `event/YSMCompatClientEvents`（**MOD 总线**）：资源包注册、客户端启动生成、重载监听。
- `event/YSMGeoClientTickHandler`（**FORGE 总线**，END 相位）：贴图上传排空、延迟释放、LRU 淘汰、
  分帧预热——所有"不能在绘制中间做"的 GL 工作都收在这里。

### 数据流

```
启动
 ├─ FMLClientSetupEvent ── RenderPaths.init()（触发两条路径自注册 + 着色器预编译）
 │                       └─ TlmModelLibrary.generateAll()（渲染线程 + Semaphore(2)）
 │                             └─ 扫描 tlm_custom_pack + jar 内置 maid_model.json
 │                                   └─ 每条目：几何 → 网格 JSON；动画文件 → 过滤 → 运行时 JSON
 └─ AddPackFindersEvent ── 注册 config/ysm_geo_compat/resourcepack 为客户端资源包

客户端 tick（每 tick END，YSMGeoClientTickHandler）
  ├─ processPendingTextureUploads()        # 后台解码完成的贴图，10 ms/帧预算上传
  ├─ processPendingTexture/MeshReleases()  # 5 tick 前淘汰的网格/贴图，此处释放
  ├─ trimIfNeeded()                        # 超过 lazyModelCacheSize 时按 LRU 淘汰
  └─ TlmModelLibrary.prewarmMeshes()       # 8 ms/tick 实例化新生成的网格（达到上限即停）

渲染（每帧）
  getMeshProvider（女仆 mixin）
    └─ YsmMaidMeshSupport.selectMaidMesh → 找到转换网格 + 设置 runtimeModelId + 当前实体
  YSMMesh.draw()
    ├─ 姿势裁剪（poses 超出当前 armature 关节数时截断）→ YsmBindArmature 轴心矫正
    ├─ YSMRuntimeBridge.apply()
    │    ├─ 战斗模式（EF 接管）→ applyDefaultVisibility（平行脚本 + 中性环境 → 默认形态）
    │    └─ 空闲模式 → YSMPlayerAnimator（真实血量/物品 → 变体动态切换，非本地实体异步求值）
    ├─ GPU 路径（经 RenderBridgeRegistry 分派）→ 不可用/让位时 ↓
    ├─ CPU 路径（经 RenderBridgeRegistry 分派）→ 不可用/让位时 ↓
    └─ Epic Fight 自带路径（compute / drawPosed）
```

### 生成文件（运行时）

```
config/ysm_geo_compat/
└── resourcepack/                # 注册为客户端资源包的生成包
    ├── pack.mcmeta
    └── assets/ysm_geo_compat/
        ├── animmodels/entity/tlm/   # EF 网格 JSON（namespace__path 命名）
        ├── ysm_runtime/entity/tlm/  # 运行时脚本 JSON（骨骼表 + molang 动画）
        └── textures/tlm/            # 贴图副本（按资源位置可被资源管理器直接取到）
```

### 关键设计

- **YSM 让位**：`YsmMaidRendererMixin` 对 `EntityMaid#isYsmModel()` 的女仆直接返回、不设置返回值——YSM 女仆完整交给 YSM_EpicFight_Compat 的同名注入器，避免两个模组对同一 `CallbackInfoReturnable` 双重 `setReturnValue`（Mixin 0.8.5 下第二次调用抛 CancellationException）。
- **默认形态**（`YSMRuntimeModel.computeDefaultHidden`）：用中性 molang 环境（满血 20/20、站立、空闲）静态求值平行脚本，得到默认形态骨骼可见性；战斗模式只渲染默认形态。
- **依赖方向**：`model` 包不引用 `gpu`/`cpu` 包——绘制经 `RenderBridgeRegistry`、释放经 `MeshReleaser`，两条路径在静态块中自注册，`RenderPaths.init()` 负责在启动期触发并校验（注册不完整会 ERROR，不会静默退化）。
- **路径安全**：模型包是第三方文件，所有 `model_id` 与清单内 `model`/`texture`/`animation`/`extra_textures` 路径都经 `sanitize`（中和 `..`/`.` 与空段）与 `resolveInside` 词法围栏后才落地文件系统；磁盘读取另做真实路径检查，生成文件拒绝链接目录，单文件默认限制为 128 MiB。
- **部件与关节匹配**：`y/<骨骼名>` 网格部件精确对应运行时骨骼。Epic Fight 关节先由骨骼语义确定类型，再依据骨骼层级、模型空间顶点位置、髋部高度和大腿长度校正左右肢体及腰部衣物的归属；网格顶点与运行时骨骼表共用这一决策。
- **逐动画容错**：单个动画 molang 编译失败只跳过该动画并告警（`Molang.ErrorReporter`），不会让整个模型退回"全变体可见"。
- **变体 id 算法**：与 TLM 完全一致——`MD5(extraTexture.getPath()).toLowerCase()`，`模型id_<32hex>`。
- **GL 状态自恢复**：两条直接路径都用 `GlRenderState` 快照/还原，且还原在 `finally` 中逐项守护，异常不会污染后续渲染。

---

## 构建

要求：JDK 17+（Gradle 运行使用 JDK 21，见 `gradle.properties`）。

```bash
./gradlew build     # 产物：build/libs/YSM_GEO_Compat-1.20.1-1.1.0.jar（无内嵌库，无 -all 变体）
./gradlew test      # 单元测试（molang 求值/路径围栏/关节表与骨名归一化）
```

依赖来源：Epic Fight（Modrinth Maven）、TLM / EFTLM（本地 `libs/` flatDir）。

> 若构建报 `maven.minecraftforge.net` 证书校验失败，请把变通开关放在**用户级** `~/.gradle/gradle.properties`（例如 `-Dnet.minecraftforge.gradle.check.certs=false`），不要提交到项目里——那会关闭所有人的构件证书校验。

## 安装

1. 将 `YSM_GEO_Compat-1.20.1-1.1.0.jar` 放入 `mods/`。
2. 必装 Epic Fight；女仆支持需 Touhou Little Maid + EpicFight_TouhouLittleMaid。
3. 需要玩家 YSM 模型或 YSM 女仆时，同时安装 YSM_EpicFight_Compat（本模组与其共存时仅处理 TLM 模型）。

## 使用

- 女仆：选用 TLM 模型包的模型即自动生效；`extra_textures` 变体纹理也会按 TLM 的 hash 变体 id 匹配。使用 YSM 模型的女仆由 YSM_EpicFight_Compat 渲染。
- 战斗中（EF 接管渲染）显示默认形态；空闲时 molang 驱动的变体（低血狐狸、手持御币魔法阵等）正常切换。
- 模型文件变更后按 `F3+T` 触发重新转换（贴图与运行时脚本一并失效重载）。

## 配置

`config/ysm_geo_compat-client.toml`：

- `enableGpuRender`：使用 GPU 蒙皮路径。运行时不可用会自动关闭（与 ModernYSM 的 `UseGpuRenderer` 行为一致）。
- `scriptAsyncEval`：非本地实体的脚本求值在后台线程执行（双缓冲），默认开。
- `lazyModelCacheSize`：常驻女仆网格数上限（默认 64，范围 8–512；共享网格的额外贴图变体只计一次）。超出时按 LRU 释放网格与贴图，
  该模型下次被绘制时由 Epic Fight 从生成的网格 JSON 重新加载（不重转换、不会永久失效；
  贴图从生成资源包文件重新读取）。

转换与生命周期说明：

- 网格 JSON 与贴图在启动/重载时全量写入 `config/ysm_geo_compat/resourcepack`；网格**首次被请求时**
  才实例化（含解析、`SkinnedMesh` 构建、compute setup）。
- 实例化由客户端 tick 分帧预热（8 ms/tick，达到 `lazyModelCacheSize` 即停），避免女仆首次入画时
  在加载帧里付解析成本。
- 贴图解码在后台线程，GL 上传在渲染线程按 10 ms/帧预算排空；淘汰的网格与贴图延迟 5 tick 再释放。
- 若 Epic Fight 的私有网格缓存不可达（字段改名等），LRU 淘汰会**整体禁用并告警一次**，而不是
  只删自己表里的条目（后者会让 EF 继续发放已销毁的实例）。

系统属性（诊断/验证用）：

| 属性 | 作用 |
|---|---|
| `-Dysm_geo_compat.diag=true` | 输出逐次绘制被哪条路径拒绝及原因、GPU 输入诊断等 |
| `-Dysm_geo_compat.disable_gpu` | 强制停用 GPU 路径 |
| `-Dysm_geo_compat.force_cpu_render` | 跳过 EF 计算着色器，强制走 CPU 蒙皮路径（验证回退链） |
| `-Dysm_geo_compat.max_pack_file_bytes=134217728` | 模型包单文件读取上限，默认 128 MiB |

## 开发注意事项

- mixin 配置共三个：`ysm_geo_compat.mixins.json`（required）、`ysm_geo_compat.eftlm.mixins.json`（可选 EFTLM）、`ysm_geo_compat.ysm.mixins.json`（可选 YSM 守卫，带 `YsmGeoMixinPlugin`）。**不要**把 YSM 守卫并入 required 配置——没装 YSM 的玩家会启动失败。
- 转换算法变更时无需清单版本号（TLM 网格每次启动全量重转，无 manifest 缓存）。
- `libs/` 下的 `ysm-2.6.5.jar`（若存在）仅作参考，构建未引用（本模组无 YSM 依赖）。
- 单元测试只覆盖纯 Java 部分（不需要启动 Minecraft）：`./gradlew test`。
