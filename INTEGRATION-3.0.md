# TankTrouble 3.0 整合说明

整合完成时间：2026-09-15

## 整合范围

- 主线：本项目原有权威服务端、六坦克、本地双人、多人混战、合作 Boss。
- 子弹与特效：并入工作目录版本的晶针、拖尾、发射者配色、预瞄终点、光束衰减、弹墙扩散环和火星。
- 服务器联机：并入 `坦克大战(5)` 的房间列表、准备、聊天、房主踢人、局域网广播和自动发现。
- 部署：新增版本化 server 目录、systemd 单元、环境文件口令和回滚说明。
- 联机入口：局域网联机和公网服务器联机拆成两个独立模块，各自保存连接参数和界面状态。

## 协议

- 协议版本：`4`
- 游戏端口：`7777/TCP`
- 局域网发现：`7778/UDP`
- 旧协议（3.0.1）与 3.0.2 客户端不能混连。

## 验证

- 主源码 `mvn package`：通过。
- JavaFX 实机探针：980×700 双人页可绘制大型场景立绘、P1/P2 独立战斗栏、两份雷达和两份脉冲按钮。
- 同机自动联机：启动本地服务器后，客户端通过 `tanktrouble.autoJoin` 成功连接并进入大厅。
- 远程联机：使用正确口令连接服务器成功，服务端日志确认客户端已入站。
- 聊天链路：双客户端真实房间测试通过，JavaFX 焦点与 Enter 发送探针通过。
- 对局结束：房间保留成员和 `FINISHED` 状态，房主可“再来一局”，只有主动离开才会解散房间。
- 测试源码：原回归套件保留，并补入 `BattleFeedbackProbeTest`、`BulletAimProbeTest`、双人栏/大型立绘断言。当前 Codex 沙箱的 JDK 在读取 Maven 依赖 JAR 时返回 `AccessDeniedException`，因此这里只完成主源码构建；普通 Windows 环境执行 `test.ps1`，需要 GUI 冒烟时执行 `test.ps1 -Gui`。
- 最终 JAR：`target/tanktrouble-javafx-3.0.2.jar`
- JAR SHA-256：`F8CB883B6C2A51AF7E63C4AD73BC0B303FFF15F3D10E2C28CF063006641A213F`
- 已部署到云服务器（地址与口令不写进仓库），`/opt/tank-trouble/current -> releases/3.0.2`
- 服务端校验：systemd `active`，TCP 7777 监听正常，远端 JAR 哈希与本地一致。
- 服务端启动验证：`LAN discovery enabled`，TCP 监听正常。
- 慢客户端隔离：每个客户端拥有独立异步发送队列，旧快照自动合并，慢连接不再阻塞房间模拟。

## 联机范围

网络模式保留多人混战和合作 Boss，暂不开放商店、炮塔部署和局间强化。单机、本地双人和无尽模式继续保留完整系统。

## 入口

- 游戏：`run.bat`
- 同机多人：`play-multiplayer.bat`
- 独立服务器：`deploy/server/start-server.sh`
- 构建：`build.ps1`
- 测试：`test.ps1`
