## 1. 项目初始化

- [x] 1.1 创建 Android 项目（Kotlin，minSdk 26 / Android 8.0）
- [x] 1.2 配置 build.gradle：添加 OkHttp、Gson 依赖（固定版本）
- [x] 1.3 配置 AndroidManifest：声明 SYSTEM_ALERT_WINDOW、FOREGROUND_SERVICE、INTERNET 权限
- [x] 1.4 创建项目包结构：capture / recognition / analysis / ui / data / service

## 2. 数据层

- [x] 2.1 定义数据模型：Hero、Counter、Combo、SeasonMeta、CounterStrategy、LayoutConfig（Kotlin data class）
- [x] 2.2 实现 DataRepository：从内部存储加载 heroes.json / combos.json / season_meta.json / counter_strategies.json / layout_config.json / hero_hashes.json
- [x] 2.3 编写 JSON 解析工具，处理文件不存在时的降级（返回空列表，记录日志）
- [x] 2.4 准备开发用 mock 数据包（10 个英雄 + 基础克制关系 + 3 条阵容/套路规则），用于本地调试

## 3. 数据同步模块（data-sync）

> ⚠️ 代码已实现，但因 `BuildConfig.VERSION_CHECK_URL` 仍为占位域名（`example.com` / `your-prod-server.com`），
> 联网路径尚未端到端验收。部署前必须在 `app/build.gradle` 替换为真实服务地址后，重新验证 3.1–3.4。
> 当前仅离线降级路径（3.5）可独立验证。

- [x] 3.1 实现版本检测：GET /api/version/check，解析 force_update 和 data_version（代码完成，待真实服务端联调）
- [x] 3.2 实现数据包下载：带进度回调，下载完成后 MD5 校验（代码完成，待真实服务端联调）
- [x] 3.3 实现解压覆盖：zip 解压到内部存储，替换旧数据文件（代码完成，原子替换已修复）
- [x] 3.4 实现启动检测流程：网络可用 → 检查版本 → 强制更新弹窗 or 直接进入（代码完成，待真实服务端联调）
- [x] 3.5 处理网络不可用场景：跳过检测，展示离线提示条，使用本地数据正常启动

## 4. 截图模块（screen-capture）

- [x] 4.1 实现 MediaProjection 权限申请流程（首次弹系统授权弹窗）
- [x] 4.2 实现单次截图：创建 VirtualDisplay → ImageReader → 获取一帧 Bitmap
- [x] 4.3 实现 ROI 裁剪：根据 layout_config.json 比例坐标裁剪对方 Ban/Pick 槽位
- [x] 4.4 实现"进入游戏"时停止：mediaProjection.stop() + VirtualDisplay.release()
- [x] 4.5 处理当前分辨率无配置时的回退逻辑（最近纵横比配置）

## 5. 英雄识别模块（hero-recognition）

- [x] 5.1 实现 pHash 算法：Bitmap → 32×32 灰度 → DCT → 64-bit hash
- [x] 5.2 实现汉明距离计算：两个 Long 异或后统计置位数（popcount）
- [x] 5.3 实现最近邻匹配：遍历 hero_hashes，返回汉明距离最小的英雄 ID
- [x] 5.4 实现空槽位检测：汉明距离全部 > 20 时返回"未选"
- [ ] 5.5 在 2～3 款小米机型上测试 pHash 一致性，确认阈值（目标：阈值 ≤ 10）

## 6. 克制分析模块（counter-analysis）

- [x] 6.1 实现 Ban 阶段分析：统计禁用角色分布，匹配意图规则，输出文字描述
- [x] 6.2 实现轻量级阵容/套路规则引擎：加载 combos.json，按 ban/pick 命中、核心英雄、赛季权重计算规则得分
- [x] 6.3 实现当前赛季强势数据加载：读取 season_meta.json，识别强势英雄和强势阵容标签
- [x] 6.4 实现阵容级反制建议：命中套路后从 counter_strategies.json 输出威胁点、反制思路、推荐英雄标签和避免选择类型
- [x] 6.5 实现 Pick 阶段对位克制：根据英雄 ID 查 hard_counters / soft_counters，返回 TOP3
- [x] 6.6 实现多英雄分析：对每个已识别英雄分别输出克制建议，并合并阵容级提示
- [x] 6.7 处理克制或规则数据为空的场景：展示"暂无克制数据"或回退到单英雄克制建议
- [x] 6.8 增加分析耗时埋点：规则数量 ≤ 200 时阵容/套路识别耗时目标 < 50ms

## 7. 悬浮窗 UI（floating-window）

- [x] 7.1 实现 FloatingWindowService（Foreground Service，带通知栏图标）
- [x] 7.2 实现悬浮窗布局：展开态（结果面板 + 扫描按钮 + 进入游戏按钮）
- [x] 7.3 实现折叠态：48dp 圆形图标，点击展开
- [x] 7.4 实现拖动：OnTouchListener 处理 ACTION_MOVE，更新 WindowManager LayoutParams
- [x] 7.5 实现位置记忆：拖动结束后写入 SharedPreferences，下次启动恢复
- [x] 7.6 实现扫描按钮防重复点击：处理中禁用按钮，完成后恢复
- [x] 7.7 实现扫描结果展示：每次覆盖面板内容（Ban 意图 + 阵容/套路提示 + 阵容级反制思路 + 对位克制列表）
- [x] 7.8 实现"进入游戏 →"按钮：点击后折叠悬浮窗 + 停止 MediaProjection

## 8. MIUI 权限引导

- [x] 8.1 实现悬浮窗权限检测：检查 Settings.canDrawOverlays()，未授权则跳转设置页
- [x] 8.2 实现 MIUI 电池白名单引导：检测厂商，引导用户进入"省电策略 → 无限制"
- [x] 8.3 实现权限引导页 UI：逐步说明需要开启的权限及原因

## 9. 端到端联调

- [ ] 9.1 完整流程测试：启动 → 版本检测 → 悬浮窗 → 进入 BP 界面 → 扫描 → 结果展示 → 进入游戏 → 悬浮窗隐藏
- [ ] 9.2 在小米 14 / Redmi Note13 上验证悬浮窗存活（MIUI 后台不被杀）
- [ ] 9.3 验证进入对局后 MediaProjection 已停止（无录屏权限标识）
- [ ] 9.4 打包 release APK，验证直接安装流程（允许未知来源提示处理）
- [ ] 9.5 验证性能目标：截图→识别→分析→展示总耗时 < 500ms，后台驻留内存 < 50MB
