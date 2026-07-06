# KOF BP Assistant

王者荣耀 BP（Ban/Pick）阶段的实时辅助工具。通过悬浮窗在 BP 阶段手动截图，识别对方禁用/选用英雄，给出轻量级阵容意图提示与对位克制建议。

> **合规声明**：本工具**不读取游戏进程/内存、不注入代码、不模拟操作**。仅在 BP 阶段（游戏大厅，非对局内）通过系统级 `MediaProjection` 手动截图分析；用户点击「进入游戏」后立即停止所有截图行为，进入对局期间零录屏、零驻留。

## 功能特性

- **手动截图识别** — BP 阶段点击「扫描」触发单次截图，基于 pHash 感知哈希识别对方英雄，无持续录屏
- **轻量级阵容分析** — 本地规则引擎识别对方阵容/套路意图，输出威胁点、反制思路和推荐英雄标签
- **对位克制建议** — 针对每个已识别英雄给出 TOP3 克制英雄
- **可折叠悬浮窗** — 展开/折叠、自由拖动、位置记忆，进入对局后一键收起
- **数据可切换热更新** — 当前为内置写死数据模式（不联网）；有服务器后一键开关即启用启动版本检测 + 强制更新拉取数据包
- **MIUI 适配** — 悬浮窗权限检测、电池白名单引导，针对小米机型优化后台存活

## 技术栈

- **平台**：Android 8.0+（minSdk 26 / targetSdk 34），Kotlin
- **网络**：OkHttp 4.12.0
- **序列化**：Gson 2.10.1
- **异步**：Kotlin Coroutines 1.7.3
- **识别算法**：自实现 pHash（感知哈希，64-bit），无 OpenCV 依赖

## 项目结构

```
kof-bp-assistant/                 # Android 应用
├── app/src/main/
│   ├── java/com/kof/bpassistant/
│   │   ├── capture/              # 截图模块（MediaProjection）
│   │   ├── recognition/          # 英雄识别（pHash）
│   │   ├── analysis/             # 克制分析引擎
│   │   ├── service/              # 悬浮窗前台服务
│   │   ├── ui/                   # 主界面 + 权限引导
│   │   └── data/                 # 数据模型 + 仓库 + 数据同步
│   ├── res/                      # 布局/资源
│   └── assets/kof_data/          # mock 数据包（heroes/combos/season_meta 等）
└── build.gradle

openspec/                         # 规格与设计文档（OpenSpec）
└── changes/kof-bp-assistant/
    ├── proposal.md               # 需求提案
    ├── design.md                 # 设计决策（含技术选型理由）
    ├── tasks.md                  # 实现任务清单
    └── specs/                    # 各能力的详细规格
```

## 快速开始

### 环境要求

- Android SDK（platform android-34、build-tools 34.0.0）
- JDK 17
- Android Studio 或命令行 Gradle

### 配置 SDK 路径

在 `kof-bp-assistant/` 下创建 `local.properties`（不入库）：

```properties
sdk.dir=/path/to/your/Android/sdk
```

### 构建

```bash
cd kof-bp-assistant
./gradlew :app:assembleDebug
```

APK 产物位于 `app/build/outputs/apk/debug/`。

## 数据维护

应用的英雄克制关系、阵容规则、头像哈希、ROI 坐标全部由 6 个 JSON 文件驱动：

| 文件 | 内容 |
|------|------|
| `heroes.json` | 英雄基础信息 + `hardCounters`/`softCounters`（对位克制关系） |
| `combos.json` | 阵容/套路规则（核心英雄、ban/pick 权重、触发阈值） |
| `counter_strategies.json` | 命中套路后的反制思路、推荐/避免英雄类型 |
| `season_meta.json` | 当前赛季强势英雄/阵容、强度梯度（每赛季更新） |
| `hero_hashes.json` | 英雄头像 pHash 库 `{hero_id: pHash_int64}`（不含图片，仅哈希值） |
| `layout_config.json` | BP 界面槽位比例坐标（按屏幕比例，游戏 UI 改版时更新） |

数据的加载优先级：`assets/kof_data/`（打包进 APK，初始/兜底）→ 复制到内部存储 `files/kof_data/`（运行时实际读取）→ 有服务器后由下载的数据包覆盖。

### 当前阶段：内置写死数据（无服务器）

`app/build.gradle` 中 `SYNC_ENABLED = false`，启动**不联网**，直接使用打包进 APK 的 `assets/kof_data/` 数据。更新数据的工作流：

```
1. 编辑 app/src/main/assets/kof_data/*.json
2. build.gradle 中 versionCode +1   ← 关键，否则老用户升级后不会刷新数据
3. 重新打包 APK → 安装
```

> **为什么必须升 versionCode**：内部存储的数据只在「文件不存在」或「versionCode 变化」时才用新 assets 覆盖。不升 versionCode，老用户装了新包也还是旧数据。

### 有服务器后：启用热更新

改 `app/build.gradle` 两处后重新打包，热更新代码无需改动：

```groovy
buildConfigField "boolean", "SYNC_ENABLED", "true"
buildConfigField "String", "VERSION_CHECK_URL", '"https://你的真实域名/api/version/check"'
buildConfigField "String", "DATA_DOWNLOAD_BASE_URL", '"https://你的真实域名/data/"'
```

服务端需提供：
- `GET /api/version/check` → 返回 `{ "forceUpdate": true, "dataVersion": "2026.07", "downloadUrl": "https://.../data_2026_07.zip", "md5": "..." }`
- 可下载数据包 zip 的文件服务（zip 内为上述 6 个 JSON，全量替换）

切换后 `assets/kof_data/` 降级为「首次安装兜底 + 断网降级」，下载的数据包不会被旧 assets 覆盖。

## 部署前必改

1. **数据同步开关与地址** — 有服务器后将 `SYNC_ENABLED` 改为 `true`，并替换 `VERSION_CHECK_URL` / `DATA_DOWNLOAD_BASE_URL` 为真实地址（详见上方「数据维护」）
2. **英雄哈希库** — `hero_hashes.json` 为 mock 数据，正式版需在真机截取所有英雄头像并提取 pHash 生成
3. **坐标配置** — `layout_config.json` 中的 ROI 比例坐标需按目标机型 BP 界面实测校准

## 核心设计决策

| 决策 | 选择 | 理由 |
|------|------|------|
| 识别算法 | 自实现 pHash，不引入 OpenCV | pHash 核心 < 200 行，OpenCV AAR ~20MB；固定头像图标无需复杂特征提取 |
| 截图方式 | MediaProjection 手动触发 | 事件驱动零轮询；进入对局后停止是规避反外挂检测的硬性要求 |
| 坐标配置 | 屏幕比例存储，随数据包热更新 | UI 改版无需重新发版，比例坐标自动适配同纵横比分辨率 |
| 后台存活 | Foreground Service + 电池白名单引导 | MIUI 激进杀后台，前台服务是官方存活率最高的方案 |
| 阵容识别 | 本地 JSON 规则引擎 | 快速响应、低内存驻留，可通过数据包热更新跟随赛季 |

详见 [`openspec/changes/kof-bp-assistant/design.md`](openspec/changes/kof-bp-assistant/design.md)。

## 性能目标

- 截图 → 识别 → 分析 → 展示总耗时 < 500ms
- 后台驻留内存 < 50MB
- 阵容/套路识别（≤200 条规则）耗时 < 50ms

## 开发状态

MVP 代码实现完成。以下需真实环境验证：

- [ ] pHash 跨机型一致性实测（目标阈值 ≤ 10）
- [ ] 真机端到端联调 + MIUI 后台存活验证
- [ ] 性能目标实测
- [ ] 服务端接口对接
