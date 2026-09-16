# `model/rule/AiSystem.java` 详细说明

158 行。**敌方坦克的全部思考逻辑都在这一个文件里。**

---

## 一、它在系统里的位置

```java
// TankGameModel.java:49
private final AiSystem ai=new AiSystem();

// TankGameModel.java:279 —— 每个 tick，对每个敌人调一次
if(target!=null && ai.update(tank,target,map,tanks,bullets,ms,wave) && fire(tank))
    ai.onFired(tank,map,tanks);
```

**关键分工**：
- `ai.update(...)` 返回一个布尔值 —— **"我想开火"**
- 真正开火由 `TankGameModel.fire(tank)` 执行（:317），因为冷却、散射、事件广播是 model 的状态
- 开火成功后回调 `ai.onFired(...)`，让 AI 做"打了就跑"

**AI 不持有任何全局状态**，所有记忆都存在它操作的那辆 `Tank` 对象上（见下节）。

---

## 二、它读写的状态：`Tank` 上的 AI 字段

| 字段 | 类型 | 含义 |
|---|---|---|
| `angle` | double | 朝向（度）。AI 通过 `turn()` 改它 |
| `x, y` | double | 位置（世界坐标，不是格子） |
| `fireMs` | int | 开火冷却剩余 |
| `path` | `List<Cell>` | **A\* 算出来的路径**，格子序列 |
| `target` | `Cell` | 上次寻路的目标格，用来检测"目标变了" |
| `replanMs` | int | 距离下次允许重算路径还剩多久 |
| `dodgeMs` | int | 闪避剩余时长 |
| `dodgeAngle` | double | 闪避时朝哪个方向 |
| `reverseMs` | int | 倒车剩余时长 |
| `stuckMs` | int | 已经"挪不动"累计了多久 |
| `id` | int | 坦克编号。**用来打破对称**（见 `escapeHeading`） |
| `player` | int | 负数 = 敌人。AI 靠这个区分敌我 |

**这些字段只有 AI 用**，玩家坦克上永远是初值。

---

## 三、它依赖的三个工具

```java
private final Pathfinder      pathfinder = new Pathfinder();
private final CollisionSystem collision  = new CollisionSystem();
private final CombatSystem    combat     = new CombatSystem();
```

| 工具 | 提供什么 |
|---|---|
| `Pathfinder` | `findPath(map,start,goal)` → JGraphT 的 A\*，返回格子序列 |
| `CollisionSystem` | `canMove` / `move` / `sweep` / `clearShot` |
| `CombatSystem` | `fire`（**纯函数**，只构造子弹不发射）/ `canHit` |

**`combat.fire()` 是纯的** —— 它算出发射位置、检查枪口有没有被墙堵住，返回一个 `Bullet` 对象，
但**不加进世界的子弹列表**。子弹真正进入世界是在 `TankGameModel.fire()` 里的 `bullets.addAll(...)`。

所以 AI 可以**随便试算**"如果我开火会怎样"，不会真的打出去。

---

## 四、主入口 `update()` —— 优先级状态机

```java
public boolean update(Tank enemy,Tank player,GridMap map,List<Tank> tanks,
                      List<Bullet> bullets,int ms,int wave)
```

**每个 tick 对每个敌人调一次。按优先级从上往下走，走到哪一层就 return。**

### 第 0 层：活着吗

```java
if(!enemy.alive() || !player.alive()) return false;
```

### 第 1 层：在倒车（最高优先级）

```java
if(enemy.reverseMs>0) {
    enemy.reverseMs=Math.max(0,enemy.reverseMs-ms);
    moveAlong(enemy,enemy.angle,-speed(wave)*Rules.REVERSE*ms/1000.,map,tanks);
    enemy.replanMs=0;enemy.stuckMs=0;return false;
}
```

注意 `-speed(...)` —— 负号就是倒退。同时**把 `replanMs` 清零**，意思是"倒完车立刻重新规划路径"。

`enemy.angle` 不变，只把位置往回拉 —— 所以是**倒着走**，不是掉头。

### 第 2 层：有没有威胁

```java
if(enemy.dodgeMs<=0) {
    Bullet threat=findThreat(enemy,bullets,map,tanks);
    if(threat!=null) beginDodge(enemy,threat,map,tanks,Rules.AI_DODGE_MS);
}
```

**只在没在闪避的时候才找威胁** —— 闪避是不可打断的（持续 480ms）。

### 第 3 层：正在闪避

```java
if(enemy.dodgeMs>0) {
    enemy.dodgeMs=Math.max(0,enemy.dodgeMs-ms);
    double difference=turn(enemy,enemy.dodgeAngle,ms);
    if(Math.abs(difference)<=135) drive(enemy,enemy.angle,speed(wave)*ms/1000.,map,tanks,ms);
    else enemy.stuckMs=0;
    if(enemy.dodgeMs==0) enemy.replanMs=0;
    return false;
}
```

**`<=135` 这个数是关键**：转向到还差 135° 以内就开始往前开。
意思是**"哪怕还没转到，只要不是背对着，先动起来"** —— 躲子弹要紧，不能原地转圈。

`dodgeMs` 归零时清 `replanMs`，闪避结束立刻重新规划。

### 第 4 层：追击

```java
double[] destination=pursuitTarget(enemy,player,map,ms);
double dx=destination[0]-enemy.x, dy=destination[1]-enemy.y, distance=Math.hypot(dx,dy);
if(distance>1) {
    double difference=turn(enemy,Math.toDegrees(Math.atan2(dy,dx)),ms);
    if(Math.abs(difference)<Rules.AI_TURN_BAND && (distance>Rules.TANK_HALF*2 || enemy.path.size()>1))
        drive(enemy,Math.toDegrees(Math.atan2(dy,dx)),Math.min(distance,speed(wave)*ms/1000.),map,tanks,ms);
    else enemy.stuckMs=0;
} else enemy.stuckMs=0;
```

**两个条件都满足才前进：**
1. 朝向差 < `AI_TURN_BAND`（24°）—— 瞄得够准
2. **距离够远（> 2 个坦克半径）或者路径不止一格** —— 免得在原地抖

第 2 条是防"贴脸抽搐"：如果目标就在眼前而且已经到了，就不动。

迈的步子取 `min(距离, 本帧该走的距离)` —— **不会冲过头**。

### 第 5 层：能不能开火

```java
return enemy.reverseMs==0 && shouldFire(enemy,player,map,tanks);
```

倒车时不打（枪口朝后没用）。

---

## 五、方法逐个讲

### `pursuitTarget()` :45 —— 追击目标怎么定

这是**最有设计感**的一段。

```java
enemy.replanMs-=ms;
Cell goal=map.cell(player.x,player.y), here=map.cell(enemy.x,enemy.y);
if(enemy.replanMs<=0 || !goal.equals(enemy.target) || enemy.path.isEmpty()
   || !here.equals(enemy.path.get(0))) {
    enemy.path=pathfinder.findPath(map,here,goal);
    enemy.target=goal;enemy.replanMs=Rules.REPLAN_MS;
}
```

**什么时候重算 A\***（四个条件任一）：
1. 距上次超过 `REPLAN_MS`
2. 玩家换了格子
3. 路径空了
4. 自己已经不在路径的第一个格子上了（走偏了）

**这是性能上的关键取舍 —— 不是每帧算 A\*。**

```java
if(collision.clearShot(enemy.x,enemy.y,player.x,player.y,Rules.TANK_HALF,map))
    return new double[]{player.x,player.y};
```

**有直线视野就直接冲玩家，根本不用路径。**

后面是**路径跟随**：

```java
if(enemy.path.size()>1) {
    Cell current=enemy.path.get(0), next=enemy.path.get(1);
    if(current.x()!=next.x() && Math.abs(current.centerY()-enemy.y)>2)
        return new double[]{enemy.x,current.centerY()};
    if(current.y()!=next.y() && Math.abs(current.centerX()-enemy.x)>2)
        return new double[]{current.centerX(),enemy.y};
    return new double[]{next.centerX(),next.centerY()};
}
```

**这段在解决一个实际问题**：A\* 给的是格子序列，但坦克走的是连续坐标。
如果直接朝"下一个格子的中心"走，在走廊里会走成锯齿。

所以：**如果下一步是横向移动，先把 Y 对齐到走廊中线再走**；纵向同理。
而 `>2` 这个容差是防止对齐完还差一点点时反复修正。

注释里那句 "without returning to a cell center already passed" 说的就是：
**对齐目标用的是 `current`（当前格）的中心，不是 `next` 的** —— 否则会往后退到已经走过的位置。

### `findThreat()` :65 —— 找出"最快会打中我"的子弹

```java
for(Bullet bullet:bullets) {
    if(bullet.dead || bullet.lifeMs<=0 || !CombatSystem.canHit(bullet,enemy,tanks)) continue;
    double dx=enemy.x-bullet.x, dy=enemy.y-bullet.y, distance=Math.hypot(dx,dy);
    double speed=Math.hypot(bullet.vx,bullet.vy);
    if(distance>Rules.AI_DODGE_RANGE || distance<1e-6 || speed<1e-6) continue;
    if((bullet.vx*dx+bullet.vy*dy)/(speed*distance)<Rules.AI_DODGE_DOT) continue;
    ...
}
```

**四道过滤，从便宜到贵：**

| 检查 | 作用 |
|---|---|
| `canHit` | 这颗子弹有没有可能打到我（含穿透等规则） |
| `distance > 250` | **太远不管** —— 等它飞近了再说 |
| **点积 `< .85`** | **判断"朝我来"还是"路过"** |
| `sweep(...)` | 精确的线段-矩形相交，算最早接触时间 |

点积那一行是核心：`(vx·dx + vy·dy) / (|v|·|d|)` 是夹角余弦。
`.85` 约等于 **32°** —— 子弹只有朝我飞、夹角小于 32° 才算威胁。

```java
double horizon=Math.min(bullet.lifeMs/1000., Rules.AI_DODGE_RANGE/speed);
var contact=collision.sweep(bullet.x,bullet.y,bullet.vx*horizon,bullet.vy*horizon,
                            Rules.BULLET_HALF+3,enemy.bounds());
```

**`horizon` 是预测时长**，取"子弹剩余寿命"和"飞完 250px 需要多久"里的小者。
然后把子弹看作一条线段，和我自己**放大了 3px** 的矩形求交。

放大 3px 是**保险** —— 躲的时候留点余量。

最后：

```java
double time=contact.get().time()*horizon;
if(time>=soonest || !collision.clearShot(...)) continue;
```

`time` 是**预计被击中的时刻**（秒）。`soonest` 记录目前最早的。
中间那个 `clearShot` 检查是**排除被墙挡住的子弹** —— 打不到我的不用躲。

**返回的是"最快会打中我的那一颗"**，不是最近的一颗。

### `beginDodge()` :84 —— 决定往哪躲

```java
double speed=Math.hypot(threat.vx,threat.vy), nx=-threat.vy/speed, ny=threat.vx/speed;
double cross=nx*(enemy.x-threat.x)+ny*(enemy.y-threat.y);
double heading=escapeHeading(enemy,nx,ny,cross,map,tanks);
if(Double.isFinite(heading)) {enemy.dodgeAngle=heading;enemy.dodgeMs=duration;enemy.stuckMs=0;}
```

- `(nx,ny)` 是**子弹飞行方向的垂直方向** —— 闪避只能往两边躲，不能顺着弹道跑
- `cross` 是我在**弹道哪一侧**（有正负号）
- 算出角度是有限值才进入闪避；是 `NaN` 说明**没地方躲**，那就硬吃

### `escapeHeading()` :91 —— 往哪边躲（决策链）

```java
double a=room(enemy, nx, ny,...), b=room(enemy,-nx,-ny,...);   // 两侧各有多少空间
if(Math.max(a,b)<Rules.AI_PROBE_STEP) return Double.NaN;        // 两边都堵死，放弃

double required=Math.max(1,Rules.TANK_HALF+Rules.BULLET_HALF+.5-Math.abs(cross));
```

**`required` 是"必须让开多少"** —— 我离弹道越近（`|cross|` 越小），需要的空间越大。

然后是**一条优先级决策链**（从上往下，命中即止）：

| 条件 | 含义 | 决策 |
|---|---|---|
| `a<required && b>=required` | 右边不够、左边够 | 往左 |
| `b<required && a>=required` | 左边不够、右边够 | 往右 |
| `a < PROBE_STEP(1)` | 右边完全堵死 | 往左 |
| `b < PROBE_STEP(1)` | 左边完全堵死 | 往右 |
| `\|cross\| > .5` | 明显在某一侧 | 往**远离弹道**的那侧 |
| `\|a-b\| > .5` | 一边明显更宽 | 往宽的那边 |
| 否则 | **完全对称** | `enemy.id%2==0` |

**最后一条值得讲**：两边完全一样时按坦克编号的奇偶决定。
这是为了**让同屏多个敌人不会整齐划一地做同一个动作** —— 否则看起来像一堆复制品。

### `room()` :106 —— 探测某方向能走多远

```java
for(double distance=Rules.AI_PROBE_STEP;distance<=Rules.AI_PROBE;distance+=Rules.AI_PROBE_STEP)
    if(!collision.canMove(enemy,enemy.x+dx*distance,enemy.y+dy*distance,map,tanks))
        return distance-Rules.AI_PROBE_STEP;
return Rules.AI_PROBE;
```

**步进探测**：从 1px 开始，每次 +1px，试能不能站。撞墙就返回上一步的距离。
最远探 48px（`AI_PROBE`），一路畅通就返回 48。

### `shouldFire()` :112 —— 开火判定（四重检查）

```java
if(enemy.fireMs>0) return false;                                          // ① 冷却
double heading=Math.toDegrees(Math.atan2(player.y-enemy.y,player.x-enemy.x));
if(Math.abs(normalize(heading-enemy.angle))>Rules.AI_FIRE_BAND) return false;  // ② 瞄准（±8°）
Bullet shot=combat.fire(enemy,map);
if(shot==null) return false;                                              // ③ 枪口被堵
double travel=Math.hypot(player.x-shot.x,player.y-shot.y)+Rules.TANK_HALF*2;
double dx=Math.cos(Math.toRadians(enemy.angle))*travel,dy=Math.sin(...)*travel;
var target=collision.sweep(shot.x,shot.y,dx,dy,Rules.BULLET_HALF,player.bounds());
boolean overlapping=player.bounds().intersects(Rect.centered(shot.x,shot.y,Rules.BULLET_HALF));
if(target.isEmpty() && !overlapping) return false;                        // ③' 弹道打不到
double time=overlapping?0:target.orElseThrow().time();
if(!collision.clearShot(shot.x,shot.y,shot.x+dx*time,shot.y+dy*time,Rules.BULLET_HALF,map))
    return false;                                                         // ④ 中间有墙
for(Tank ally:tanks) if(ally!=enemy && ally.player<0 && ally.alive()) {
    if(ally.bounds().intersects(...)) return false;                       // ⑤ 枪口重叠友军
    var obstruction=collision.sweep(shot.x,shot.y,dx,dy,Rules.BULLET_HALF,ally.bounds());
    if(obstruction.isPresent() && obstruction.get().time()<=time) return false;  // ⑤' 会先打中友军
}
return true;
```

**五个检查**：冷却 → 瞄准 → 枪口没堵 → 弹道通（连玩家）→ **中间没墙** → **不会误伤友军**。

最后那个友军检查是**逐个遍历其他敌人**，比较"打到友军的时刻"和"打到玩家的时刻"：
友军更早就打死都不开火。

### `onFired()` :133 —— 打了就跑

```java
double angle=Math.toRadians(enemy.angle);
double heading=escapeHeading(enemy,-Math.sin(angle),Math.cos(angle),0,map,tanks);
if(Double.isFinite(heading)) {enemy.dodgeAngle=heading;enemy.dodgeMs=Rules.AI_SCOOT_MS;...}
```

开完枪立刻**借用闪避机制**做一次 400ms 的侧移。
`cross=0` 表示"假装弹道就从我身上过"，所以是纯按空间大小选方向。

### `drive()` :139 —— 移动 + 卡住检测

```java
double x=enemy.x,y=enemy.y;moveAlong(enemy,heading,step,map,tanks);
enemy.stuckMs=Math.hypot(enemy.x-x,enemy.y-y)<step*.1?enemy.stuckMs+ms:0;
if(enemy.stuckMs>=Rules.AI_STUCK_MS) {enemy.stuckMs=0;enemy.dodgeMs=0;enemy.reverseMs=Rules.AI_REVERSE_MS;}
```

**实际位移 < 意图位移的 10% 就算"挪不动"**，累计到 600ms 就触发倒车 350ms。

这是**防卡墙的兜底** —— 塔防游戏里 AI 卡住不动是最难看的 bug。

### `moveAlong()` :144 / `turn()` :148 / `normalize()` :153

```java
private static double normalize(double angle) {return ((angle%360)+540)%360-180;}
```

**把任意角度归一到 (-180, 180]**。所有角度比较都走它，否则 `350°` 和 `-10°` 会被判成差 360°。

```java
private static double turn(Tank enemy,double heading,int ms) {
    double difference=normalize(heading-enemy.angle),limit=Rules.TURN_SPEED*ms/1000.;
    enemy.angle=(enemy.angle+Math.max(-limit,Math.min(limit,difference))+360)%360;
    return normalize(heading-enemy.angle);
}
```

**每帧最多转 `TURN_SPEED × 时间`** —— 不能瞬间掉头。返回**转完之后还差多少**（调用方用它判断瞄准没）。

### `speed()` :147 —— 难度曲线

```java
private static double speed(int wave) {return 90+Math.min(55,Math.max(0,wave-1)*5);}
```

第 1 波 90，每波 +5，**第 12 波起封顶 145**。

### `reset()` :154 —— 复活时清状态

```java
enemy.path=List.of();enemy.target=null;enemy.replanMs=0;
enemy.dodgeMs=enemy.reverseMs=enemy.stuckMs=0;
```

换波或重开时调用，**否则新出生的敌人会带着上一条命的路径和闪避状态**。

---

## 六、参数表

全部在 `Rules.java` :14-16。

| 常量 | 值 | 作用 |
|---|---|---|
| `AI_DODGE_MS` | 480 | 一次闪避持续多久 |
| `AI_SCOOT_MS` | 400 | 开完枪侧移多久 |
| `AI_STUCK_MS` | 600 | 挪不动多久算卡住 |
| `AI_REVERSE_MS` | 350 | 倒车多久 |
| `AI_DODGE_RANGE` | 250 | 多远之外的子弹不管 |
| `AI_DODGE_DOT` | .85 | "朝我来"的夹角阈值（约 32°） |
| `AI_PROBE` | 48 | 闪避时最远探多远 |
| `AI_PROBE_STEP` | 1 | 探测步长 |
| `AI_FIRE_BAND` | 8 | 开火瞄准容差（度） |
| `AI_TURN_BAND` | 24 | 转向到位多少度内就边走边转 |

另外还用到（不在 AI_ 前缀里）：

| 常量 | 作用 |
|---|---|
| `Rules.REPLAN_MS` | 路径重算间隔 |
| `Rules.REVERSE` | 倒车速度倍率 |
| `Rules.TURN_SPEED` | 每秒最多转多少度 |
| `Rules.TANK_HALF` / `BULLET_HALF` | 半宽，碰撞用 |
| `Rules.ENEMY_FIRE_MS` | 敌人开火冷却（在 `TankGameModel.fire()` 里设） |

---

## 七、一张图

```
                        update(enemy, player, ...)
                                  │
              ┌───────────────────┴───────────────────┐
              │ 死的？ → return false                  │
              └───────────────────┬───────────────────┘
                                  ↓
                    ┌── 在倒车？ ──→ 倒退 + 清路徑 → return
                    │
                    ├── 有子弹要打中我？ → beginDodge（算躲哪边）
                    │
                    ├── 在闪避？ ──→ 转向 + 冲 → return
                    │
                    ├── 追击 ──→ pursuitTarget
                    │              ├─ 有直线视野 → 冲玩家
                    │              └─ 没有 → A* 路径跟随
                    │           → turn → drive（卡住就倒车）
                    │
                    └── shouldFire ──→ 冷却 / 瞄准 / 枪口 / 弹道 / 友军
                                          ↓
                                     true = "我想开火"
                                          ↓
                        TankGameModel 真的发射 → onFired（打了就跑）
```

---

## 八、答辩时值得讲的三个点

**1. 不是每帧算 A\***
只有「超过重规划间隔 / 目标换了格子 / 路径空了 / 自己走偏了」才重算。
而且**有直线视野时根本不走寻路，直接冲玩家**。这是服务端 CPU 占用低的原因之一。

**2. `combat.fire()` 是纯函数，AI 用它"试算"**
AI 能反复问"如果我现在开火会怎样"而不产生副作用 —— 子弹真正进入世界要靠 model 那边
`bullets.addAll(...)`。这个纯/不纯的边界是刻意设计的。

**3. 完全对称时按 `id` 奇偶决策**
`escapeHeading` 最后那个 `else first=enemy.id%2==0`。
不加这一条，同屏多个敌人躲子弹时会**整齐划一地做同一个动作**，一眼看出是程序。

---

## 九、可能被追问的

**Q：AI 会不会太难 / 太笨？怎么调？**
改 `Rules` 里的常量就行，不用动逻辑。
难度随波次靠 `speed()` 涨（90→145），想调难度曲线改这一个方法。

**Q：AI 会不会误伤自己人？**
不会。`shouldFire` 最后一段逐个检查其他敌人，如果会先打中友军就不开火。

**Q：AI 卡住了怎么办？**
`drive()` 里有兜底：实际位移不足意图的 10% 就累计，600ms 后倒车 350ms 重新找路。

**Q：为什么闪避时"还没转到位就往前开"？**
`Math.abs(difference)<=135` —— 只要不是背对着就动起来。
躲子弹要紧，原地转圈等于站桩挨打。

**Q：多个敌人会不会挤在一起？**
没有专门的避让逻辑，靠 `findThreat` 的距离阈值和各自的路径规划自然分开。
**这是个可以承认的不足** —— 高波次时敌人可能扎堆。
