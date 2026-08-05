# YSM GEO Compat

将 Yes Steve Model（YSM）格式模型包与 Touhou Little Maid（车万女仆，TLM）模型包中的 GEO 模型，在运行时转换为 Epic Fight 的 SkinnedMesh，使自定义模型可以直接使用史诗战斗（Epic Fight）的战斗动画系统——**且完全不依赖 YSM 模组本体**。

| 项目 | 值 |
|---|---|
| Mod ID | `ysm_geo_compat` |
| Minecraft | 1.20.1（Forge `[47,)`） |
| 必装依赖 | Epic Fight `[20.14,)` |
| 可选依赖 | Touhou Little Maid `[1.5,)`、EpicFight_TouhouLittleMaid `[1.1,)` |

---

## 功能特性

- **GEO 模型 → Epic Fight 网格**：将 YSM 模型包（`config/yes_steve_model` 下的目录包与 `.ysm` 二进制加密包）和 TLM 模型包（`tlm_custom_pack`，含 GeckoLib 模型）批量转换为 Epic Fight `animmodels` 网格 JSON。
- **不依赖 YSM 模组**：直接读取模型文件（含 `.ysm` 加密包的解密），玩家模型选择通过 ForgeCaps NBT（`yes_steve_model:model_id`）读取，全程无 YSM 类引用。
- **女仆支持**：通过可选 mixin 钩住 EpicFight_TouhouLittleMaid 的 `PatchedLivingMaidRenderer`，女仆的 YSM 模型与 TLM 模型均替换为转换网格；支持 `extra_textures` 变体（hash 变体 id）与 `show_backpack` 配置。
- **运行时变体控制**：解析模型的 bedrock molang 动画脚本（平行脚本/状态动画/条件动画），在战斗中只显示默认形态（低血狐狸、魔法阵、枪械、背包等变体正确隐藏），空闲时按真实实体状态（血量/手持物品）动态切换。
- **外部模组联动动画过滤**：`iss:*`（Iron's Spells）、`tac:*`（TAC 枪械）等与第三方模组联动的动画被自动排除，其变体骨骼按平行脚本保持默认隐藏。
- **计算着色器渲染**：转换网格强制走 Epic Fight 计算着色器蒙皮路径（CPU 蒙皮路径会缺面），GPU 不支持时回退 CPU 路径并输出一次性告警。
- **增量生成**：双层指纹（元数据签名 + 内容签名）门控，模型文件未变化时直接从缓存恢复，启动秒进；`F3+T` 或 `/ysm model reload` 后自动重新转换。

---

## 架构

```
com.ysmef.geomodel
├── YSMGeoCompat                # 模组主类（@Mod, modId=ysm_geo_compat）
├── YSMGeoModel                 # Bedrock 几何解析（JSON + .ysm 二进制 → Bone/Quad）
├── TlmGeoModelParser           # TLM 非 gecko 女仆模型的几何解析
├── config/
│   └── YSMCompatConfig         # 客户端配置（debugLogConversion）
├── ysm/                        # .ysm 二进制包解密/解析（纯 Java，无 MC 依赖）
│   ├── YsmFileCrypto           # .ysm crypto v3：XChaCha20 滚动解密 + MT19937 + zstd
│   ├── YsmBinaryReader         # format<4 / ≤15 / ≥16 三代二进制格式读取
│   ├── YsmModelPackage         # 模型包扫描/加载 + 双层指纹（fingerprint/contentFingerprint）
│   ├── CityHash / MT19937 / XChaCha20 / ChaCha20Base   # 加密原语
│   └── script/
│       ├── Molang              # 迷你 molang 解释器（词法/递归下降/编译缓存）
│       ├── ScriptAnim          # 动画数据结构
│       └── ScriptJson          # bedrock 动画 JSON ↔ 运行时 JSON 互转 + 相关性过滤
├── model/
│   ├── EFMeshJsonWriter        # 生成 EF animmodels JSON（quad 扇形三角化、关节绑定、运行时 JSON）
│   ├── YSMMeshLibrary          # YSM 模型转换核心：manifest 门控、并行转换、贴图缓存、网格注册表
│   ├── TlmModelLibrary         # TLM 模型包扫描/转换、动画文件→运行时、extra_textures 变体、show_backpack
│   ├── YSMJointMapper          # YSM 骨骼名 → EF biped 20 关节 id
│   ├── YSMMesh                 # 网格子类：贴图覆盖、逐帧运行时变换、强制计算着色器路径
│   └── runtime/
│       ├── YSMRuntimeModel     # 运行时模型编译（骨骼表/绑定矩阵/动画编译 + 默认可见性）
│       ├── YSMPlayerAnimator   # 逐实体 molang 求值器（状态机/时间线/插值/物理弹簧）
│       └── YSMRuntimeBridge    # 渲染管线 ↔ 脚本求值器桥（ThreadLocal 当前实体）
├── renderer/
│   ├── YSMPlayerRenderer       # 玩家 patched 渲染器（LOWEST 优先级注册）
│   ├── YSMMeshSelector         # 网格选择共享逻辑（玩家/女仆共用）
│   ├── YSMModelAccess          # 从 ForgeCaps NBT 读取玩家模型选择（20 tick 缓存）
│   ├── YSMBattleMode           # 战斗模式判定（玩家 isEpicFightMode / 女仆反射 isFightMode）
│   └── layer/YsmConditionalArmorLayer   # 有转换网格时隐藏不贴合的护甲
├── mixin/
│   ├── PPlayerRendererMixin    # 劫持 EF PPlayerRenderer#getMeshProvider（主配置）
│   └── eftlm/YsmMaidRendererMixin       # 劫持 EFTLM 女仆网格选择（可选配置）
└── event/
    ├── YSMCompatClientEvents   # 客户端注册：渲染器/资源包/生成/重载监听
    ├── YSMRenderHook           # HIGHEST 优先级渲染接管（配合其他渲染替换模组）
    └── YSMReloadTrigger        # 监听 "ysm ... reload" 命令触发网格重建
```

### 数据流

```
启动
 ├─ FMLClientSetupEvent ── YSMMeshLibrary.ensureGeneratedBlocking()
 │     └─ manifest 缺失/过期？ → 并行转换全部 YSM 模型（解密→几何→网格 JSON→运行时 JSON）
 │     └─ 否则 → 从 manifest + 贴图缓存恢复
 ├─ TlmModelLibrary.generateAll()  → 扫描 tlm_custom_pack + jar 内置 maid_model.json
 │     └─ 每条目：几何 → 网格 JSON；动画文件 → 过滤 → 运行时 JSON；extra_textures → hash 变体
 └─ AddPackFindersEvent ── 注册 config/ysm_geo_compat/resourcepack 为客户端资源包

渲染（每帧）
 getMeshProvider（玩家 mixin / 女仆 mixin）
   └─ YSMMeshSelector.selectMesh → 找到转换网格 + 设置 runtimeModelId + 当前实体
 YSMMesh.draw()
   ├─ YSMRuntimeBridge.apply()
   │    ├─ 战斗模式（EF 接管）→ applyDefaultVisibility（平行脚本 + 中性环境 → 默认形态）
   │    └─ 空闲模式 → YSMPlayerAnimator（真实血量/物品 → 变体动态切换）
   ├─ 贴图覆盖（模型贴图替换玩家皮肤）
   └─ 强制计算着色器路径 drawWithShader（无 GPU 支持 → CPU + 告警）
```

### 生成文件（运行时）

```
config/ysm_geo_compat/
├── manifest.json                # 生成清单（generator 版本 + 每模型双层指纹）
├── texturecache/                # 贴图字节缓存
└── resourcepack/                # 注册为客户端资源包的生成包
    ├── pack.mcmeta
    └── assets/ysm_geo_compat/
        ├── animmodels/entity/   # EF 网格 JSON（YSM 模型 + tlm/ 子目录女仆模型）
        └── ysm_runtime/entity/  # 运行时脚本 JSON（骨骼表 + molang 动画）
```

### 关键设计

- **双层指纹门控**（`YsmModelPackage.fingerprint` / `contentFingerprint`）：元数据签名（路径/大小/mtime）快速判定，变化后再用内容签名（解密后的 `.ysm` 载荷）确认；仅元数据变化（YSM 启动时重写/重加密文件）则原地刷新清单，不触发重建。
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

产物：
- `build/libs/YSM_GEO_Compat-1.20.1-1.0.0.jar`（普通 jar）
- `build/libs/YSM_GEO_Compat-1.20.1-1.0.0-all.jar`（**jarJar 嵌套 zstd-jni，安装用这个**）

依赖来源：Epic Fight（Modrinth Maven）、TLM / EFTLM（本地 `libs/` flatDir）、zstd-jni（Maven Central）。

> 若构建报 `maven.minecraftforge.net` 证书校验失败，`gradle.properties` 已内置 `-Dnet.minecraftforge.gradle.check.certs=false`。

## 安装

1. 将 `YSM_GEO_Compat-1.20.1-1.0.0-all.jar` 放入 `mods/`。
2. 必装 Epic Fight；女仆支持需 Touhou Little Maid + EpicFight_TouhouLittleMaid。
3. 首次启动会自动生成并转换全部模型（阻塞至完成）；不要与旧参考模组 `YSM_EpicFight_Compat` 同时安装。

## 使用

- 玩家：装有 YSM（或同类模组）时自动读取模型选择；无 YSM 时玩家回退 EF 默认 biped。
- 女仆：选用 YSM 模型或 TLM 模型包的模型即自动生效；`extra_textures` 变体纹理也会按 TLM 的 hash 变体 id 匹配。
- 战斗中（EF 接管渲染）显示默认形态；空闲时 molang 驱动的变体（低血狐狸、手持御币魔法阵等）正常切换。
- 模型文件变更后按 `F3+T`，或（装有 YSM 时）执行 `/ysm model reload` 触发重新转换。

## 配置（`config/ysm_geo_compat-client.toml`）

- `debugLogConversion`：转换过程输出详细日志。

## 开发注意事项

- 主 mixin 配置 `ysm_geo_compat.mixins.json`（required），女仆配置 `ysm_geo_compat.eftlm.mixins.json`（required:false，缺 EFTLM 时忽略）。
- 转换算法或清单格式变更时需递增 `YSMMeshLibrary.GENERATOR_VERSION` 强制全量重建。
- `libs/` 下的 `ysm-2.6.5.jar` 仅作参考，构建未引用（本模组无 YSM 依赖）。
