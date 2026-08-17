# YSM GEO Compat

将 Touhou Little Maid（车万女仆，TLM）模型包中的 GEO 模型在运行时转换为 Epic Fight 的 SkinnedMesh，使自定义女仆模型可以直接使用史诗战斗（Epic Fight）的战斗动画系统。

**本模组不包含任何 YSM 模型支持**（不解析 `config/yes_steve_model`、不解密 `.ysm` 包）。玩家 YSM 模型与"使用 YSM 模型的女仆"由 **YSM_EpicFight_Compat** 模组负责；两者共存时，本模组在女仆渲染钩子上对 YSM 女仆主动让位。

| 项目 | 值 |
|---|---|
| Mod ID | `ysm_geo_compat` |
| Minecraft | 1.20.1（Forge `[47,)`） |
| 必装依赖 | Epic Fight `[20.14,)` |
| 可选依赖 | Touhou Little Maid `[1.5,)`、EpicFight_TouhouLittleMaid `[1.1,)`、YSM_EpicFight_Compat（YSM 女仆支持） |

---

## 功能特性

- **GEO 模型 → Epic Fight 网格**：将 TLM 模型包（`tlm_custom_pack` 目录包与 jar 内置 `maid_model.json` 条目，含 GeckoLib 模型）转换为 Epic Fight `animmodels` 网格 JSON。
- **女仆支持**：通过可选 mixin 钩住 EpicFight_TouhouLittleMaid 的 `PatchedLivingMaidRenderer`，女仆的 TLM 模型替换为转换网格；支持 `extra_textures` 变体（hash 变体 id）与 `show_backpack` 配置。
- **运行时变体控制**：解析模型包的 bedrock molang 动画脚本（平行脚本/状态动画/条件动画），在战斗中只显示默认形态（低血狐狸、魔法阵、枪械、背包等变体正确隐藏），空闲时按真实实体状态（血量/手持物品）动态切换。
- **外部模组联动动画过滤**：`iss:*`（Iron's Spells）、`tac:*`（TAC 枪械）等与第三方模组联动的动画被自动排除，其变体骨骼按平行脚本保持默认隐藏。
- **计算着色器渲染**：转换网格强制走 Epic Fight 计算着色器蒙皮路径（CPU 蒙皮路径会缺面），GPU 不支持时回退 CPU 路径并输出一次性告警。
- **性能优化**（移植自 YSM_EpicFight_Compat）：molang 整数 ID 内联求值、运行时模型后台预编译、非本地实体异步脚本求值（双缓冲）+ LOD 距离降频（40/64 格 → 30/10Hz）、逐实体动画器清扫（15s/60s）、关键帧增量游标、零分配矩阵合成。

---

## 架构

```
com.ysmef.geomodel
├── YSMGeoCompat                # 模组主类（@Mod, modId=ysm_geo_compat）
├── YSMGeoModel                 # Bedrock 几何解析（TLM 模型包 gecko/非 gecko 共用）
├── config/
│   └── YSMCompatConfig         # 客户端配置（debugLogConversion、scriptAsyncEval）
├── ysm/script/                 # 迷你 molang 解释器 + bedrock 动画 JSON 互转
│   ├── Molang                  # 编译缓存 + 路径整数 ID 内联 + 常量折叠
│   ├── ScriptAnim              # 动画数据结构
│   └── ScriptJson              # bedrock 动画 JSON ↔ 运行时 JSON 互转 + 相关性过滤
├── model/
│   ├── EFMeshJsonWriter        # 生成 EF animmodels JSON（quad 扇形三角化、关节绑定、运行时 JSON）
│   ├── TlmGeoModelParser       # TLM 非 gecko 女仆模型的几何解析
│   ├── TlmModelLibrary         # TLM 模型包扫描/转换、动画文件→运行时、extra_textures 变体、show_backpack
│   ├── YSMJointMapper          # YSM 骨骼名 → EF biped 20 关节 id
│   ├── YSMMesh                 # 网格子类：贴图覆盖、逐帧运行时变换、强制计算着色器路径
│   ├── YSMMeshLibrary          # 生成包路径/贴图注册/纹理上传/后台预编译池（无 YSM 模型扫描）
│   └── runtime/
│       ├── YSMRuntimeModel     # 运行时模型编译（骨骼表/绑定矩阵/动画编译 + 默认可见性 + 动画器清扫）
│       ├── YSMPlayerAnimator   # 逐实体 molang 求值器（状态机/时间线/插值/异步双缓冲/LOD）
│       └── YSMRuntimeBridge    # 渲染管线 ↔ 脚本求值器桥（ThreadLocal 当前实体）
├── renderer/
│   ├── YSMMeshSelector         # 女仆网格选择（运行时模型 ID + 贴图覆盖）
│   └── YSMBattleMode           # 战斗模式判定（反射 isFightMode，女仆）
├── eftlm/
│   └── YsmMaidMeshSupport      # 女仆 TLM 模型网格选择桥（YSM 女仆让位给 YSMEF）
└── mixin/
    └── eftlm/YsmMaidRendererMixin   # 劫持 EFTLM 女仆网格选择（可选配置）
```

### 数据流

```
启动
 ├─ FMLClientSetupEvent ── TlmModelLibrary.generateAll()
 │     └─ 扫描 tlm_custom_pack + jar 内置 maid_model.json
 │           └─ 每条目：几何 → 网格 JSON；动画文件 → 过滤 → 运行时 JSON；extra_textures → hash 变体
 └─ AddPackFindersEvent ── 注册 config/ysm_geo_compat/resourcepack 为客户端资源包

渲染（每帧）
 getMeshProvider（女仆 mixin）
   └─ YsmMaidMeshSupport.selectMaidMesh → 找到转换网格 + 设置 runtimeModelId + 当前实体
 YSMMesh.draw()
   ├─ YSMRuntimeBridge.apply()
   │    ├─ 战斗模式（EF 接管）→ applyDefaultVisibility（平行脚本 + 中性环境 → 默认形态）
   │    └─ 空闲模式 → YSMPlayerAnimator（真实血量/物品 → 变体动态切换，非本地实体异步求值）
   ├─ 贴图覆盖（模型贴图替换玩家皮肤）
   └─ 强制计算着色器路径 drawWithShader（无 GPU 支持 → CPU + 告警）
```

### 生成文件（运行时）

```
config/ysm_geo_compat/
└── resourcepack/                # 注册为客户端资源包的生成包
    ├── pack.mcmeta
    └── assets/ysm_geo_compat/
        ├── animmodels/entity/tlm/   # EF 网格 JSON（namespace__path 命名）
        └── ysm_runtime/entity/tlm/  # 运行时脚本 JSON（骨骼表 + molang 动画）
```

### 关键设计

- **YSM 让位**：`YsmMaidRendererMixin` 对 `EntityMaid#isYsmModel()` 的女仆直接返回、不设置返回值——YSM 女仆完整交给 YSM_EpicFight_Compat 的同名注入器，避免两个模组对同一 `CallbackInfoReturnable` 双重 `setReturnValue`（Mixin 0.8.5 下第二次调用抛 CancellationException）。
- **默认形态**（`YSMRuntimeModel.computeDefaultHidden`）：用中性 molang 环境（满血 20/20、站立、空闲）静态求值平行脚本，得到默认形态骨骼可见性；战斗模式只渲染默认形态。
- **运行时查找**（`YSMRuntimeModel.runtimeFileOf`）：候选路径依次尝试 YSM 风格 → TLM 风格（`tlm/namespace__path`）→ 剥离 `_<32hex>` 变体后缀回退基础模型。
- **计算着色器强制**（`YSMMesh.tryDrawWithComputeShader`）：反射读取 EF `SkinnedMesh.computerShaderSetup` 私有字段（EF 构造时即创建，与配置无关），直接调用 `drawWithShader` 绕过 `ClientConfig.activateComputeShader`。
- **逐动画容错**：单个动画 molang 编译失败只跳过该动画并告警，不会让整个模型退回"全变体可见"。
- **变体 id 算法**：与 TLM 完全一致——`MD5(extraTexture.getPath()).toLowerCase()`，`模型id_<32hex>`。

---

## 构建

要求：JDK 17+（Gradle 运行使用 JDK 21，见 `gradle.properties`）。

```bash
./gradlew build
```

产物：`build/libs/YSM_GEO_Compat-1.20.1-1.0.0.jar`（无内嵌库，无 -all 变体）。

依赖来源：Epic Fight（Modrinth Maven）、TLM / EFTLM（本地 `libs/` flatDir）。

> 若构建报 `maven.minecraftforge.net` 证书校验失败，`gradle.properties` 已内置 `-Dnet.minecraftforge.gradle.check.certs=false`。

## 安装

1. 将 `YSM_GEO_Compat-1.20.1-1.0.2.jar` 放入 `mods/`。
2. 必装 Epic Fight；女仆支持需 Touhou Little Maid + EpicFight_TouhouLittleMaid。
3. 需要玩家 YSM 模型或 YSM 女仆时，同时安装 YSM_EpicFight_Compat（本模组与其共存时仅处理 TLM 模型）。

## 使用

- 女仆：选用 TLM 模型包的模型即自动生效；`extra_textures` 变体纹理也会按 TLM 的 hash 变体 id 匹配。使用 YSM 模型的女仆由 YSM_EpicFight_Compat 渲染。
- 战斗中（EF 接管渲染）显示默认形态；空闲时 molang 驱动的变体（低血狐狸、手持御币魔法阵等）正常切换。
- 模型文件变更后按 `F3+T` 触发重新转换。

## 配置（`config/ysm_geo_compat-client.toml`）

- `debugLogConversion`：转换过程输出详细日志。
- `scriptAsyncEval`：非本地实体的脚本求值在后台线程执行（双缓冲），默认开。

## 开发注意事项

- mixin 配置 `ysm_geo_compat.eftlm.mixins.json`（required:false，缺 EFTLM 时忽略）。
- 转换算法变更时无需清单版本号（TLM 网格每次启动全量重转，无 manifest 缓存）。
- `libs/` 下的 `ysm-2.6.5.jar` 仅作参考，构建未引用（本模组无 YSM 依赖）。
