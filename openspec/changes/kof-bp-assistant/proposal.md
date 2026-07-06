## Why

王者荣耀排位/巅峰赛 BP 阶段，玩家需要快速判断对方意图并选出克制英雄，但大多数玩家缺乏系统性的克制知识和阵容识别能力。当前没有一款轻量、非侵入式的实时辅助工具能在 BP 阶段给出准确的对位克制建议。

## What Changes

- 新增 Android 悬浮窗应用，在 BP 阶段提供实时辅助
- 截图识别对方禁用/选用英雄（pHash 算法，手动触发）
- 根据识别结果给出轻量级阵容/套路意图提示与对位克制英雄建议
- 后台服务端维护英雄克制数据，支持强制版本更新
- BP 结束后一键收起，进入对局期间零录屏零驻留

## Capabilities

### New Capabilities

- `screen-capture`: 基于 MediaProjection 的手动截图模块，仅在 BP 阶段激活，进入对局后立即停止
- `hero-recognition`: 基于 pHash 的英雄头像识别，将截图中的英雄槽位图像匹配到英雄 ID
- `counter-analysis`: 轻量级 BP 分析引擎，根据对方已选/禁用英雄输出阵容/套路意图提示、阵容级反制思路和对位克制建议
- `floating-window`: 可折叠拖动的悬浮窗 UI，展示扫描结果，支持收起隐藏
- `data-sync`: 启动时版本检测 + 强制更新机制，从服务端拉取英雄克制数据包

### Modified Capabilities

（无，全新项目）

## Impact

- **平台**：Android 8.0+，优先适配小米 MIUI
- **权限**：`SYSTEM_ALERT_WINDOW`（悬浮窗）、`MediaProjection`（截图，用户手动授权）、前台服务通知权限
- **外部依赖**：服务端版本检测 API + 数据包 CDN（自维护）
- **反外挂合规**：不读取游戏进程/内存，不注入代码，不模拟操作；进入对局后停止所有截图行为
- **数据维护**：开发者手动维护 `heroes.json` / `combos.json` / `season_meta.json` / `counter_strategies.json` / `layout_config.json`，通过强制更新下发
