# 战斗页右侧立绘栏改造 — 设计

2026-09-13

## 问题

战斗页右侧立绘栏宽 250、高 776（跟随战场区高度）。立绘是 3:4 竖版全身像，等比完整显示后
卡片只有 250×333，下面空出约 443px。

根因不是留白本身，而是这一栏**只承担了「放一张图」**，没有承担任何信息职责。同样的角色信息
（驾驶员、装甲、共振能量、状态、脉冲技能）全挤在左侧栏里，右侧自然是空的。

## 目标

把右侧栏变成「我的角色」，左侧栏变成「战场局势」。两栏都有明确职责，信息不重复，留白消失。

## 分区

| 右栏 — 我的角色 | 左栏 — 战场局势 |
| --- | --- |
| 立绘（带框） | 模式标题 |
| 驾驶员 / P1 · 角色名 · 称号 | 剩余敌军（双人模式为 P2 状态） |
| 机体（坦克名 + 装甲值）与装甲条 | 炮塔库存 / 部署炮塔 |
| 共振能量与能量条 | 战地补给 |
| 附加状态（护盾 / 急速） | 操作提示 |
| 释放脉冲（Q） | SEED |

## 立绘框

采用「画框内嵌」：立绘四周垫 9px 深色衬边（`#1e2724`），外层 1px `#46524b` 边框，
内层再压 1px `#5f7163` 细线。立绘本身因此缩小约 8%（250 → 202 宽）。

框不能直接加在 `PortraitView` 上：它在 `pose()` 里每帧 `setStyle()` 写背景色，会覆盖同一份
内联样式。因此用两层 `StackPane` 包住——外层 `portrait-frame` 负责衬边与外框，内层
`portrait-inner` 负责细线，`PortraitView` 只管自己。

## 改动范围

- `TankTroubleApp.start()` — 组装 `portrait-frame` / `portrait-inner` / `driverPanel`；
  新建 `situation` 标签；`energyBar` 的样式类提到这里加一次（避免每次重建重复添加）。
- `TankTroubleApp.battleSidebar()` — 移除驾驶员整块，改放「战场局势」区。
- 新增 `TankTroubleApp.driverPanel(Snapshot)` — 每次 `refresh()` 重建右栏内容，
  与 `sidebar` 的清空重建方式一致。
- `TankTroubleApp.updateHud()` — `effects` 只留附加状态；新增 `situation` 承载剩余敌军 /
  P2 状态；`sidebar.lookup("#pulse-button")` 改为 `root.lookup(...)`（按钮已不在侧栏）。
- `mecha.css` — 新增 `.portrait-frame`、`.portrait-inner`、`#driver-panel` 及其文字样式。

`FxSmokeTest` 未引用被移动的控件（`#pulse-button`、`#deploy-button`、`health`、`energy`、
`effects`），无需改动。被移动的按钮 id 保持不变，外部引用不受影响。

## 不做的事

- 不改模型层、事件、AI、碰撞、存档。
- 不新增战斗日志（那是备选方案 B，本次不采用）。
- 不调整左侧栏的弹性占位行为（补给按钮仍被推到底部）。
