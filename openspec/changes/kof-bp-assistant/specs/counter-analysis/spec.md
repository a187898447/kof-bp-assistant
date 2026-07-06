## ADDED Requirements

### Requirement: Ban 阶段输出阵容意图分析
系统 SHALL 在识别到对方禁用英雄后，根据禁用角色分布和当前赛季数据，输出对方可能的阵容意图提示。

#### Scenario: 对方禁用以刺客为主
- **WHEN** 对方4个禁用英雄中有3个以上属于刺客/战士
- **THEN** 系统输出"对方可能害怕高机动刺客，倾向走后排输出阵容"并建议选择坦克/前排

#### Scenario: 禁用分布均匀，意图不明
- **WHEN** 对方禁用英雄角色分布较均匀，无明显倾向
- **THEN** 系统输出"禁用意图不明显，关注对方选人阶段"

---

### Requirement: Pick 阶段输出对位克制建议
系统 SHALL 在识别到对方每次选人后，针对每个已选英雄输出 TOP3 克制英雄建议。

#### Scenario: 识别到对方1个英雄
- **WHEN** 对方 pick 区出现1个已识别英雄
- **THEN** 系统从 `heroes.json` 中读取该英雄的 `hard_counters` 和 `soft_counters`，展示 TOP3 克制英雄名称

#### Scenario: 识别到对方多个英雄
- **WHEN** 对方 pick 区出现2个及以上已识别英雄
- **THEN** 系统对每个英雄分别输出克制建议，并在底部附加整体策略文字（来自轻量级阵容意图识别结果）

#### Scenario: 英雄克制数据为空
- **WHEN** 某英雄在 `heroes.json` 中 `hard_counters` 和 `soft_counters` 均为空数组
- **THEN** 系统展示"暂无克制数据"，不崩溃不报错

---

### Requirement: 轻量级阵容/套路意图识别
系统 SHALL 使用本地规则数据对敌方 ban/pick 结果进行轻量级阵容/套路意图识别，输出可能意图、置信度等级和阵容级反制思路。

#### Scenario: 命中强势阵容核心英雄
- **WHEN** 对方 pick 区已识别英雄命中 `combos.json` 中某个阵容规则的核心英雄集合，且得分达到触发阈值
- **THEN** 系统展示该阵容/套路名称、"高"或"中"置信度、主要威胁点和对应反制思路

#### Scenario: 命中娱乐套路或特殊体系
- **WHEN** 对方 ban/pick 组合命中 `combos.json` 中标记为 `fun` 或 `special` 的套路规则
- **THEN** 系统展示"对方可能尝试特殊套路"提示，并展示来自 `counter_strategies.json` 的针对性拆解建议

#### Scenario: 仅命中当前赛季强势英雄
- **WHEN** 对方已选英雄未组成明确阵容，但包含 `season_meta.json` 标记的当前赛季强势英雄
- **THEN** 系统展示"对方已拿当前赛季强势英雄"提示，并优先输出该英雄的对位克制建议

#### Scenario: 意图置信度不足
- **WHEN** 所有阵容/套路规则得分均低于触发阈值
- **THEN** 系统展示"阵容意图暂不明显，优先参考单英雄克制"，并继续输出对位克制建议

#### Scenario: 规则数据缺失
- **WHEN** `combos.json`、`season_meta.json` 或 `counter_strategies.json` 缺失或解析失败
- **THEN** 系统跳过阵容意图识别，不崩溃，并继续输出已有的单英雄克制建议

### Requirement: 本地规则分析性能受控
系统 SHALL 在本地完成阵容/套路意图识别，不依赖实时网络请求，且分析阶段不得明显拖慢扫描结果展示。

#### Scenario: 规则数量在 MVP 上限内
- **WHEN** 本地规则数量不超过200条且对方已识别英雄数量不超过9个
- **THEN** 系统在50ms内完成阵容/套路意图识别并返回结果

#### Scenario: 数据包规则过多
- **WHEN** `combos.json` 中规则数量超过200条
- **THEN** 系统仅加载前200条有效规则，并记录日志，避免内存和耗时失控
