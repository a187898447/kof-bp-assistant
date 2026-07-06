## ADDED Requirements

### Requirement: 启动时检测版本并强制更新
系统 SHALL 在每次 App 启动时请求服务端版本接口，若服务端标记 `force_update: true` 则阻止进入主界面，直到更新完成。

#### Scenario: 有强制更新
- **WHEN** App 启动时服务端返回 `{ "force_update": true, "data_version": "x.x" }`
- **THEN** 系统展示强制更新弹窗，用户无法关闭，点击"立即更新"后下载并解压新数据包，完成后进入主界面

#### Scenario: 无需更新
- **WHEN** App 启动时服务端返回 `{ "force_update": false }` 或本地版本与服务端一致
- **THEN** 系统直接进入主界面，不弹更新提示

#### Scenario: 网络不可用时启动
- **WHEN** App 启动时无网络连接，无法请求版本接口
- **THEN** 系统使用本地已有数据包正常启动，不阻断流程，顶部展示"离线模式，数据可能不是最新"提示条

---

### Requirement: 数据包全量下载替换
系统 SHALL 下载完整数据包 zip，解压后覆盖本地数据目录，不做增量 diff。

#### Scenario: 下载并解压成功
- **WHEN** 数据包下载完成且 MD5 校验通过
- **THEN** 系统将 zip 解压到应用内部存储，覆盖旧版 `heroes.json`、`combos.json`、`season_meta.json`、`counter_strategies.json`、`layout_config.json`、`hero_hashes.json`

#### Scenario: 下载中断或校验失败
- **WHEN** 下载过程中网络中断或 MD5 不匹配
- **THEN** 系统删除不完整的临时文件，保留旧版数据包，提示用户"更新失败，请重试"

---

### Requirement: 坐标配置热更新无需重新发版
系统 SHALL 将 `layout_config.json` 纳入数据包，更新坐标配置时只需服务端发布新数据包，无需重新发布 APK。

#### Scenario: 坐标配置随数据包更新
- **WHEN** 新数据包中 `layout_config.json` 包含新增机型坐标
- **THEN** App 更新后立即使用新坐标，无需用户手动操作
