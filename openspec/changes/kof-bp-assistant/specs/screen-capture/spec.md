## ADDED Requirements

### Requirement: 用户手动触发截图
系统 SHALL 仅在用户点击"扫描"按钮时执行一次截图，不得持续轮询或自动截图。

#### Scenario: 点击扫描触发截图
- **WHEN** 用户在悬浮窗点击"扫描"按钮
- **THEN** 系统通过 MediaProjection 截取当前屏幕一帧，并进入识别流程

#### Scenario: 未授权时点击扫描
- **WHEN** 用户点击"扫描"但尚未授权 MediaProjection
- **THEN** 系统弹出系统级授权弹窗，授权成功后自动执行截图

---

### Requirement: 进入对局后停止录屏
系统 SHALL 在用户点击"进入游戏"按钮时立即停止 MediaProjection，释放 VirtualDisplay。

#### Scenario: 用户点击进入游戏
- **WHEN** 用户在悬浮窗点击"进入游戏 →"按钮
- **THEN** 系统调用 `mediaProjection.stop()` 并释放 VirtualDisplay，此后不再执行任何截图操作

#### Scenario: App 进入后台期间
- **WHEN** 用户未点击"进入游戏"但将 App 切换到后台
- **THEN** MediaProjection 保持待机状态（不录屏），仅在下次点击"扫描"时重新激活

---

### Requirement: ROI 裁剪减少处理量
系统 SHALL 仅对截图的对方 BP 区域（上半屏）进行处理，不处理完整截图。

#### Scenario: 裁剪对方 Ban/Pick 区域
- **WHEN** 截图完成后进入识别流程
- **THEN** 系统根据 `layout_config.json` 中的比例坐标裁剪出对方禁用槽位和选择槽位的 ROI，丢弃其余区域

#### Scenario: 坐标配置缺失当前分辨率
- **WHEN** 当前设备分辨率在 `layout_config.json` 中无对应配置
- **THEN** 系统使用最接近纵横比的配置作为回退，并在结果界面提示"坐标可能偏移，如识别有误请反馈"
