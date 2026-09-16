# net 模块定位表

答辩用。**左边是可能被问的问题，右边是答案在哪个文件的哪一行。**

行号对应 `src/main/java/tanktrouble/net/` 下的文件。搜索时用方法名比记行号稳。

---

## 一、每个文件负责什么

| 文件 | 行数 | 一句话职责 |
|---|---|---|
| `GameServer.java` | 400 | **大厅**。监听端口、校验口令、管理房间的创建与销毁、把玩家在房间之间搬来搬去 |
| `Room.java` | 464 | **一个对战房间**。跑 tick、收玩家输入、维护成员表、准备/开局、广播快照 |
| `Protocol.java` | 218 | **线路格式**。所有能在线路上传的消息类型、版本号、端口号 |
| `ClientConnection.java` | 145 | **一条连接**。发送队列、礼貌关闭、连接生命周期 |
| `NetworkQueues.java` | 64 | 入队策略 + **丢帧规则**（队列满了丢哪些） |
| `LanDiscovery.java` | 218 | **局域网发现**。UDP 广播自己的房间；监听别人的广播 |
| `GameClient.java` | 135 | **客户端侧连接**。连服务器、发消息、收消息队列 |
| `RemoteGameModel.java` | 527 | **客户端的"远端视图"**。把服务端快照变成能渲染的世界，含插值和外推 |
| `NetStats.java` | 167 | 性能插桩。**不设 `-Dtanktrouble.netStats` 就完全不工作** |

**前 6 个是服务端**（`GameServer` 的依赖闭包），后 3 个只在客户端。

---

## 二、问题 → 定位

### 连接与安全

| 问题 | 文件 | 定位 |
|---|---|---|
| 服务器怎么接受连接？ | `GameServer` | `acceptLoop()` :95 —— 每来一个客户端开一个专属读取线程 |
| **口令在哪校验的？** | `GameServer` | `readLoop()` :127，校验在 :138 |
| 不校验口令会怎样？ | `GameServer` | `main()` :375 末尾的警告打印 —— 没设口令会显式警告 |
| 版本不匹配怎么办？ | `GameServer` | `readLoop()` :134，直接拒绝并回传两边版本号 |
| 服务器上哪些消息能进来？ | `Protocol` | 所有 `implements ClientMessage` 的 record |
| 服务器上哪些消息能出去？ | `Protocol` | 所有 `implements ServerMessage` 的 record |

⚠️ **主动讲的一条**：口令校验发生在**反序列化之后**（`readLoop` :129 先 `readObject()`，:138 才比对口令）。
答法：RCE 需要类路径上有 gadget chain，我们这套依赖里没有；但 **DoS 是现实的**。
改法是把口令做成连接后的第一条纯文本握手。

### 大厅与房间路由

| 问题 | 文件 | 定位 |
|---|---|---|
| 消息怎么知道该给谁？ | `GameServer` | `dispatch()` :166 —— 先查 `inRoom`，在房间就转给房间，否则按建房/进房处理 |
| 玩家进出房间要重连吗？ | `GameServer` | `enter()` :274 / `returnToLobby()` :300 —— **都是改一下路由表，连接不动** |
| 房间列表怎么更新？ | `GameServer` | `listLoop()` :194（每秒推）+ `broadcastRoomList()` :208 |
| 为什么每秒都要推？ | `GameServer` | `broadcastRoomList()` 的注释 —— 推送丢了也能一秒后自我纠正 |
| 房间满了怎么办？ | `GameServer` | `enterDefaultRoom()` :228 / `createRoom()` :242 —— 拒绝，不给建 |
| 房间数上限是多少？为什么？ | `GameServer` | `MAX_ROOMS` :27 |
| 房间没人了怎么销毁？ | `GameServer` | `removeRoom()` :264 |
| **房主退出会怎样？** | `GameServer` | `disbandRoom()` :323 —— 整个房间解散，其他人回大厅（不掉线） |
| 为什么销毁房间不关连接？ | `GameServer` | `removeRoom()` 的注释 —— 早先关了，结果"离开房间"看起来像"掉线" |
| "直接开打"是怎么实现的？ | `GameServer` | `enterDefaultRoom()` :224 —— 房间号为负 = 别让我选 |

### 对战房间内部

| 问题 | 文件 | 定位 |
|---|---|---|
| 对战的循环在哪？ | `Room` | `broadcastLoop()` :396 —— 房间自己的线程 |
| 玩家操作怎么进来的？ | `Room` | `receive()` :208 |
| 快照怎么发出去的？ | `Room` | `broadcastFrame()` :445 |
| 准备状态怎么维护？ | `Room` | `members` :33 + `ready` :37 |
| 什么时候开局？ | `Room` | `startIfReady()` :325 |
| 成员表怎么下发？ | `Room` | `broadcastLobby()` :375 |
| 踢人怎么做的？ | `Room` | `kickPlayer()` :246 |
| 聊天怎么做的？ | `Room` | `relayChat()` :300（含 `chatCooldown` 防刷屏 :38） |
| 房间什么时候算「该销毁了」？ | `Room` | `closeIfEmpty()` :437 |

### 传输与队列

| 问题 | 文件 | 定位 |
|---|---|---|
| 为什么要发送队列？ | `ClientConnection` | `outgoing` :38 + `writeLoop()` :111 |
| **慢客户端会不会拖住别人？** | `ClientConnection` | 每个连接自己的 `outgoing` 队列 —— **不会，各发各的** |
| 队列满了丢什么？ | `NetworkQueues` | `trimEvents()` :60 —— 丢事件，保快照 |
| 关闭连接为什么这么绕？ | `ClientConnection` | `close()` :89/:95 用 `closeClaimed` :36 保证只关一次 |
| 服务器关机时怎么处理还没关完的连接？ | `GameServer` | `close()` :347 —— 每个连接独立线程关，整批只给 750ms |

### 局域网发现

| 问题 | 文件 | 定位 |
|---|---|---|
| 局域网怎么自动发现的？ | `LanDiscovery` | `Broadcaster` :48 / `Listener` :125 |
| 广播的是什么内容？ | `LanDiscovery` | `Found` :31 —— 房间号、主机、端口、房名、人数 |
| 广播地址怎么算的？ | `LanDiscovery` | `broadcastTargets()` :93 |
| 为什么互联网不能用广播？ | `LanDiscovery` | 只在局域网段有效，路由器不转发广播包 |
| 怎么避免连到自己的服务器？ | `LanDiscovery` | `Broadcaster.serverId` :50（UUID 标识自己） |

### 客户端侧

| 问题 | 文件 | 定位 |
|---|---|---|
| 客户端怎么连服务器？ | `GameClient` | `connect()` :33/:42 |
| 客户端怎么收消息？ | `GameClient` | `readLoop()` :90 + `poll()` :88 |
| 客户端怎么处理快照？ | `RemoteGameModel` | `pump()` :108 |
| **延迟是怎么来的？** | `RemoteGameModel` | 搜 `interpolate` —— 45ms 插值缓冲 + 物理往返 |
| **卡顿是怎么修的？** | `RemoteGameModel` | 搜 `extrapolate` —— 缓冲打空时按最后速度外推 |
| 外推会不会推飞？ | `RemoteGameModel` | `extrapolate` 内的三道保险：50ms 上限 / 300px/s 闸门 / 子弹用自带 vx,vy |
| 房间列表在客户端哪？ | `RemoteGameModel` | `roomList()` :176 |
| 自己是不是房主？ | `RemoteGameModel` | `isHost()` :83 |

### 协议与版本

| 问题 | 文件 | 定位 |
|---|---|---|
| 协议版本号在哪？现在是多少？ | `Protocol` | `VERSION = 4L` :21 |
| 改协议要做什么？ | `Protocol` | 升 `VERSION` —— 旧客户端会自动被 `GameServer.readLoop()` :134 拒绝 |
| 默认端口？ | `Protocol` | `DEFAULT_PORT = 7777` :26 |
| 一次对战在线上传的是什么？ | `Protocol` | `Frame` :182 —— 一个快照 + 一批事件 |
| 房间列表长什么样？ | `Protocol` | `RoomInfo` :70 / `RoomList` :90 |
| 玩家的输入是怎么表达的？ | `Protocol` | `Input` :159 —— 玩家号 + 动作 + 按下/松开 |

---

## 三、一句话记住的结构

```
玩家 → GameClient → [网络] → GameServer.readLoop（握手+口令）
                                    ↓
                              dispatch（按"你在哪"路由）
                                    ↓
                   大厅分支                     房间分支
              createRoom / enter            Room.receive
                    ↓                              ↓
              rooms 表（最多 5 个）          Room.broadcastLoop（tick）
                    ↓                              ↓
              broadcastRoomList            Room.broadcastFrame（30Hz 快照）
                    ↓                              ↓
                 ← ← ← ← ← 网络 ← ← ← ← ← ← ← ←
                    ↓
        RemoteGameModel.pump（插值 / 外推）
                    ↓
               BattleRenderer 画出来
```

**`GameServer` 只依赖 `TankGameModel` 和 `Protocol`，碰不到 `view/`** ——
所以改界面永远不用重新部署服务器。
