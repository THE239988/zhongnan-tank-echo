# 补给延迟重生 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 开局生成的 7 个补给在被拾取 10 秒后，于另一个随机空闲格子重生同类型补给，使整局内补给密度保持稳定。

**Architecture:** 全部改动落在模拟层 `TankGameModel` 内部。用一张 `EnumMap<Item,PickupView>` 按**对象身份**记住 7 个种子补给的实例，从而把种子补给与敌人掉落区分开；`Snapshot.pickups` 仍然合并发出，因此协议与客户端零改动。

**Tech Stack:** Java 17、Maven、JUnit 5（jupiter 5.12.2）、surefire。

## Global Constraints

- 规格：`docs/superpowers/specs/2026-09-15-supply-respawn-design.md`（已批准）
- 常量 `Rules.ITEM_RESPAWN_MS = 10000`，与 `Rules.TERRAIN_REFRESH_MS` 同频
- **`Protocol.VERSION` 保持 4 不变**；不改 `Protocol`、`RemoteGameModel`、`BattleRenderer`、`MultiplayerWindow`
- 改动只允许落在 `Rules.java` 和 `TankGameModel.java` 两个文件
- 模拟固定步长 `Rules.STEP_MS = 8`，`tick(deltaMs)` 传入非 8 会抛 `IllegalArgumentException`
- 模型层不得导入 JavaFX；`TankGameModel` 是纯模型，测试不需要图形环境
- **本项目不是 git 仓库**（有 `.gitignore` 但无 `.git`）。Task 0 是可选前置；若不执行 Task 0，跳过所有 `git commit` 步骤，其余照做
- 测试命令统一用 `mvn -q test -Dtest=<类名>`，在项目根目录执行

---

### Task 0（可选）: 初始化 git 仓库

**Files:**
- 已存在: `.gitignore`（内容无需改动）

**Interfaces:**
- Consumes: 无
- Produces: 可用的 git 仓库，使后续任务的 commit 步骤可执行

- [ ] **Step 1: 确认当前不是仓库**

Run: `git rev-parse --is-inside-work-tree`
Expected: `fatal: not a git repository`

- [ ] **Step 2: 初始化并首次提交**

```bash
git init
git add -A
git commit -m "chore: 导入 TankTrouble 3.0.2 源码"
```

- [ ] **Step 3: 确认仓库可用**

Run: `git log --oneline -1`
Expected: 输出一条 `chore: 导入 TankTrouble 3.0.2 源码`

---

### Task 1: 种子补给槽位与 10 秒重生

让开局 7 个补给各自成为可循环槽位：被拾取后 10 秒回到地图上。本任务实现最简放置（任意非原格的格子），放置规则与敌人掉落的区分在 Task 2、3 收紧。

**Files:**
- Modify: `src/main/java/tanktrouble/model/data/Rules.java:20`
- Modify: `src/main/java/tanktrouble/model/core/TankGameModel.java`（字段区 ~21、`initializeWave` 145-148、`tick` 198-204、`collect` 340-346）
- Test: `src/test/java/tanktrouble/model/core/SupplyRespawnTest.java`（新建）

**Interfaces:**
- Consumes: `ModelTest.game(Mode)`、`ModelTest.quiet(TankGameModel)`、`ModelTest.field(Object,String)`、`ModelTest.tanks(TankGameModel)`（均已在 `src/test/java/tanktrouble/model/core/ModelTest.java` 中存在，包级可见）
- Produces:
  - `Rules.ITEM_RESPAWN_MS`：`public static final int = 10000`
  - `TankGameModel` 私有字段 `Map<Item,PickupView> seeded`——按物品类型索引当前存活的那个**种子**补给，按对象身份识别
  - `TankGameModel` 私有字段 `List<Respawn> respawns`——等待回归的槽位
  - `TankGameModel` 私有方法 `private void updateSupplies()`——由 `tick` 每步调用
  - `TankGameModel` 私有静态内部类 `Respawn{Item item; double fromX,fromY; int remainingMs;}`
  - `TankGameModel` 私有方法 `private Cell freeSupplyCell(double avoidX,double avoidY)`——Task 2 完善其规则

- [ ] **Step 1: 写失败的测试**

新建 `src/test/java/tanktrouble/model/core/SupplyRespawnTest.java`：

```java
package tanktrouble.model.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.TestMaps;
import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.model.core.ModelTest.*;

class SupplyRespawnTest {
    /** A cell centre far from both DUEL spawns, so nothing collects it by accident. */
    private static final double SUPPLY_X=440,SUPPLY_Y=360;

    /**
     * DUEL has two stationary human players and no AI, so a long advance stays deterministic.
     * The seven generated supplies are replaced by a single tracked one at a known cell.
     */
    private static TankGameModel supplyGame(Item item) {
        TankGameModel game=game(Mode.DUEL);
        quiet(game);
        List<PickupView> pickups=field(game,"pickups");
        Map<Item,PickupView> seeded=field(game,"seeded");
        pickups.clear();
        seeded.clear();
        PickupView supply=new PickupView(SUPPLY_X,SUPPLY_Y,item);
        pickups.add(supply);
        seeded.put(item,supply);
        return game;
    }

    private static Map<Item,PickupView> slots(TankGameModel game) {return field(game,"seeded");}

    private static void advance(TankGameModel game,int ms) {
        for(int elapsed=0;elapsed<ms;elapsed+=8) game.tick(8);
    }

    /** Walks the player onto the tracked supply so the real pickup path runs. */
    private static void takeIt(TankGameModel game) {
        Tank player=tanks(game).get(0);
        player.x=SUPPLY_X;player.y=SUPPLY_Y;
        game.tick(8);
        assertTrue(game.snapshot().pickups().isEmpty(),"The supply should have been collected");
    }

    /** Frees the cell the supply was taken from, so a returning one could legally reuse it. */
    private static void stepAway(TankGameModel game) {
        Tank player=tanks(game).get(0);
        player.x=700;player.y=600;
    }

    @Test void aTakenSeedReturnsExactlyTenSecondsLater() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);

        advance(game,9992);
        assertTrue(game.snapshot().pickups().isEmpty(),"Still gone one tick short of ten seconds");

        game.tick(8);
        assertEquals(1,game.snapshot().pickups().size(),"The slot is back after ten seconds");
    }

    @Test void aReturningSeedKeepsItsItemAndAvoidsTheCellItWasTakenFrom() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);

        advance(game,10000);

        PickupView returned=game.snapshot().pickups().get(0);
        assertEquals(Item.SHIELD,returned.item(),"A slot returns as the item it was");
        assertFalse(returned.x()==SUPPLY_X && returned.y()==SUPPLY_Y,
                "A slot must not return to the cell it was taken from");
    }

    @Test void everyWaveSeedsExactlyOneTrackedSlotPerItem() {
        TankGameModel game=game(Mode.DUEL);
        Map<Item,PickupView> seeded=slots(game);

        assertEquals(EnumSet.allOf(Item.class),EnumSet.copyOf(seeded.keySet()));
        assertEquals(Item.values().length,game.snapshot().pickups().size());
        for(Item item:Item.values())
            assertTrue(game.snapshot().pickups().contains(seeded.get(item)),
                    "Every seeded slot must also be on the map: "+item);
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=SupplyRespawnTest`
Expected: 编译失败或测试失败。`field(game,"seeded")` 会抛 `AssertionError`（字段不存在），`Item.SHIELD` 的回归断言失败。**这是预期的**——`seeded` 尚未实现。

- [ ] **Step 3: 添加常量**

`src/main/java/tanktrouble/model/data/Rules.java:20` 改为：

```java
    public static final int TERRAIN_REFRESH_MS = 10000;
    /** How long a consumed seeded supply stays gone before returning at a different cell. */
    public static final int ITEM_RESPAWN_MS = 10000;
```

- [ ] **Step 4: 添加槽位字段与倒计时类型**

`src/main/java/tanktrouble/model/core/TankGameModel.java`，在 `private final List<PickupView> pickups=new ArrayList<>();`（第 22 行）之后插入：

```java
    /**
     * The live seeded supply of each item, so a pickup leaving the map can be recognised as one of
     * the cycle slots rather than an enemy drop.
     *
     * <p>Keyed by identity, not equality: two supplies carrying the same item are {@code equals},
     * and only the instance seeded here is allowed to come back.
     */
    private final Map<Item,PickupView> seeded=new EnumMap<>(Item.class);
    /** Seeded slots counting down to their next appearance, with where they were taken from. */
    private final List<Respawn> respawns=new ArrayList<>();

    /** One seeded slot on its way back to the map. */
    private static final class Respawn {
        final Item item;
        final double fromX,fromY;
        int remainingMs;
        Respawn(Item item,double fromX,double fromY,int remainingMs) {
            this.item=item;this.fromX=fromX;this.fromY=fromY;this.remainingMs=remainingMs;
        }
    }
```

- [ ] **Step 5: 在 initializeWave 中登记槽位**

`TankGameModel.java:145-148`，把原来的：

```java
        for(int i=0;i<Item.values().length;i++) {
            Cell cell=freeCell(occupied,false);occupied.add(cell);
            pickups.add(new PickupView(cell.centerX(),cell.centerY(),Item.values()[i]));
        }
```

替换为：

```java
        seeded.clear();respawns.clear();
        for(int i=0;i<Item.values().length;i++) {
            Cell cell=freeCell(occupied,false);occupied.add(cell);
            PickupView supply=new PickupView(cell.centerX(),cell.centerY(),Item.values()[i]);
            pickups.add(supply);seeded.put(supply.item(),supply);
        }
```

- [ ] **Step 6: 在 collect 中排入倒计时**

`TankGameModel.java:340-346` 的 `pickups.removeIf` 块内，在 `emit(...)` 之后、`return true;` 之前插入 `scheduleRespawn(p);`：

```java
            pickups.removeIf(p->{
                if(tank.bounds().intersects(Rect.centered(p.x(),p.y(),11)) && applyItem(tank,p.item())) {
                    emit(p.item().event,tank.player,p.x(),p.y());
                    scheduleRespawn(p);
                    return true;
                }
                return false;
            });
```

并在 `collect()` 方法之后新增：

```java
    /**
     * Starts the cycle for a seeded slot that was just taken.
     *
     * <p>Guarded by identity: an enemy drop of the same item is a different instance, so it is
     * removed from the map without ever entering the cycle.
     */
    private void scheduleRespawn(PickupView supply) {
        if(seeded.get(supply.item())!=supply) return;
        seeded.remove(supply.item());
        respawns.add(new Respawn(supply.item(),supply.x(),supply.y(),Rules.ITEM_RESPAWN_MS));
    }
```

- [ ] **Step 7: 在 tick 中推进倒计时**

`TankGameModel.java:198-204`，在 `if(terrainEnabled){...}` 块之后、`for(Tank tank:tanks)` 循环之前插入 `updateSupplies();`：

```java
        if(terrainEnabled) {
            terrainElapsedMs+=ms;
            if(terrainElapsedMs>=Rules.TERRAIN_REFRESH_MS) {
                terrainElapsedMs-=Rules.TERRAIN_REFRESH_MS;
                refreshTerrain();
            }
        }
        updateSupplies();
```

并在 `refreshTerrain()` 方法之后新增：

```java
    /** Counts down returning supplies and puts each one back at a cell nothing else is using. */
    private void updateSupplies() {
        if(respawns.isEmpty()) return;
        for(Iterator<Respawn> it=respawns.iterator();it.hasNext();) {
            Respawn slot=it.next();
            slot.remainingMs-=Rules.STEP_MS;
            if(slot.remainingMs>0) continue;
            Cell cell=freeSupplyCell(slot.fromX,slot.fromY);
            // No room this tick: hold at zero and try again next step, the way terrain keeps its
            // layout rather than dropping a zone somewhere invalid.
            if(cell==null) {slot.remainingMs=0;continue;}
            PickupView supply=new PickupView(cell.centerX(),cell.centerY(),slot.item);
            pickups.add(supply);seeded.put(slot.item,supply);
            it.remove();
        }
    }

    /**
     * A cell a returning supply may occupy, excluding the one it was taken from.
     *
     * <p>Cell centres are always clear of the map's wall lines, so the only things to avoid are
     * what occupies cells at runtime. Task 2 tightens this; for now any other cell will do.
     */
    private Cell freeSupplyCell(double avoidX,double avoidY) {
        List<Cell> candidates=new ArrayList<>();
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++) {
            Cell cell=new Cell(x,y);
            if(cell.centerX()==avoidX && cell.centerY()==avoidY) continue;
            candidates.add(cell);
        }
        return candidates.isEmpty()?null:candidates.get(random.nextInt(candidates.size()));
    }
```

- [ ] **Step 8: 运行测试确认通过**

Run: `mvn -q test -Dtest=SupplyRespawnTest`
Expected: 3 个测试全部 PASS。

- [ ] **Step 9: 确认没有破坏既有测试**

Run: `mvn -q test -Dtest=ModelTest,TerrainRefreshTest,MultiplayerModeTest`
Expected: 全部 PASS。`pickups` 字段名与语义未变，这 27 个既有用例应当原样通过。

- [ ] **Step 10: 提交**

```bash
git add src/main/java/tanktrouble/model/data/Rules.java \
        src/main/java/tanktrouble/model/core/TankGameModel.java \
        src/test/java/tanktrouble/model/core/SupplyRespawnTest.java
git commit -m "feat(model): 补给被拾取后 10 秒于新格子重生"
```

---

### Task 2: 重生放置避开占用格，无空位时重试

**Files:**
- Modify: `src/main/java/tanktrouble/model/core/TankGameModel.java`（`freeSupplyCell`）
- Test: `src/test/java/tanktrouble/model/core/SupplyRespawnTest.java`

**Interfaces:**
- Consumes: Task 1 的 `freeSupplyCell(double,double)`、`respawns`、`seeded`、`updateSupplies()`
- Produces: `freeSupplyCell` 的完整语义——避开地形区、存活坦克、存活炮塔、传送门、其它补给；无可用格子时返回 `null`

- [ ] **Step 1: 写失败的测试**

在 `SupplyRespawnTest` 中追加：

```java
    /**
     * Covers every cell but two, then blocks those two with a terrain zone and a tank.
     *
     * <p>Built so the outcome cannot depend on which cell a random draw happens to pick: a slot
     * that only dodges the cell it came from has 107 places to land and will take one of them,
     * while a slot that respects occupancy has none and must wait.
     */
    @Test void aReturningSeedNeverLandsOnAnOccupiedCell() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        // One player out of the grid and one holding cell (8,6); neither may collect anything.
        Tank parked=tanks(game).get(0);
        parked.x=-500;parked.y=-500;
        Tank other=tanks(game).get(1);
        other.x=(8+.5)*Rules.CELL;other.y=(6+.5)*Rules.CELL;
        List<PickupView> pickups=field(game,"pickups");
        pickups.clear();
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++) {
            if((x==6&&y==4)||(x==8&&y==6)) continue;
            pickups.add(new PickupView((x+.5)*Rules.CELL,(y+.5)*Rules.CELL,Item.REPAIR));
        }
        ModelTest.<List<Zone>>field(game,"zones").add(new Zone(
                new Rect(6*Rules.CELL+8,4*Rules.CELL+8,Rules.CELL-16,Rules.CELL-16),Terrain.LAVA));

        advance(game,10000);

        assertEquals(Rules.COLS*Rules.ROWS-2,game.snapshot().pickups().size(),
                "The slot must wait rather than land on the zone, the tank, or another supply");
    }

    @Test void aReturningSeedWaitsWhenEveryCellIsTaken() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        // Park both players outside the grid: every cell is about to be covered, and a tank
        // standing on one would simply collect the supply we are trying to hold down.
        for(Tank tank:tanks(game)) {tank.x=-500;tank.y=-500;}
        List<PickupView> pickups=field(game,"pickups");
        pickups.clear();
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++)
            pickups.add(new PickupView((x+.5)*Rules.CELL,(y+.5)*Rules.CELL,Item.REPAIR));

        advance(game,10000);
        assertEquals(Rules.COLS*Rules.ROWS,game.snapshot().pickups().size(),
                "A full map must not gain a supply");

        pickups.clear();
        game.tick(8);
        assertEquals(1,game.snapshot().pickups().size(),"It lands as soon as a cell frees up");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=SupplyRespawnTest`
Expected: 两个新用例 FAIL——`aReturningSeedAvoidsCellsThatAreAlreadyTaken` 会落在被占格子上；`aReturningSeedWaitsWhenEveryCellIsTaken` 会多出一个补给（`108+1` 而非 `108`）。

- [ ] **Step 3: 收紧 freeSupplyCell**

把 `freeSupplyCell` 整个方法替换为：

```java
    /**
     * A cell a returning supply may occupy, excluding the one it was taken from.
     *
     * <p>Cell centres are always clear of the map's wall lines, so the only things to avoid are
     * what occupies cells at runtime. Mirrors the checks {@link #refreshTerrain()} makes, at the
     * smaller radius a supply uses.
     *
     * @return a free cell, or null when every candidate is taken; the caller retries next tick
     */
    private Cell freeSupplyCell(double avoidX,double avoidY) {
        List<Cell> candidates=new ArrayList<>();
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++) {
            Cell cell=new Cell(x,y);
            double cx=cell.centerX(),cy=cell.centerY();
            if(Math.abs(cx-avoidX)<1e-9 && Math.abs(cy-avoidY)<1e-9) continue;
            Rect bounds=Rect.centered(cx,cy,11);
            if(zones.stream().anyMatch(z->bounds.intersects(z.bounds()))
                    || tanks.stream().anyMatch(t->t.alive() && bounds.intersects(t.bounds()))
                    || turrets.stream().anyMatch(t->t.lifeMs>0 && bounds.intersects(Rect.centered(t.x,t.y,Rules.TURRET_HALF)))
                    || portals.stream().anyMatch(p->bounds.intersects(Rect.centered(p.centerX(),p.centerY(),Rules.PORTAL_HALF)))
                    || pickups.stream().anyMatch(p->bounds.intersects(Rect.centered(p.x(),p.y(),11)))) continue;
            candidates.add(cell);
        }
        return candidates.isEmpty()?null:candidates.get(random.nextInt(candidates.size()));
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=SupplyRespawnTest`
Expected: 5 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/tanktrouble/model/core/TankGameModel.java \
        src/test/java/tanktrouble/model/core/SupplyRespawnTest.java
git commit -m "feat(model): 重生补给避开占用格并在无空位时重试"
```

---

### Task 3: 敌人掉落不循环；多人模式跳过炮塔槽位

**Files:**
- Modify: `src/main/java/tanktrouble/model/core/TankGameModel.java`（`scheduleRespawn`）
- Test: `src/test/java/tanktrouble/model/core/SupplyRespawnTest.java`

**Interfaces:**
- Consumes: Task 1 的 `scheduleRespawn(PickupView)`、`seeded`；Task 2 的 `freeSupplyCell`
- Produces: `scheduleRespawn` 完整语义——仅种子实例进入循环；`Mode.MULTI`/`Mode.COOP` 下 `Item.TURRET` 不进入循环

- [ ] **Step 1: 写失败的测试**

在 `SupplyRespawnTest` 中追加（注意新增 `multiGame` 助手）：

```java
    /** MULTI/COOP need explicit player slots; there is no AI in these modes. */
    private static TankGameModel multiGame(Mode mode,int players) {
        TankGameModel game=new TankGameModel();
        game.selectMode(mode);
        game.setTerrainEnabled(false);
        game.startBattle(42,players);
        ModelTest.set(game,"map",TestMaps.open());
        for(Tank tank:tanks(game)) {tank.fireMs=1_000_000;tank.shieldMs=1_000_000;}
        ModelTest.<List<Cell>>field(game,"portals").clear();
        return game;
    }

    /** Walks the player onto an arbitrary supply so a plain enemy drop can be collected. */
    private static void takeAt(TankGameModel game,double x,double y) {
        Tank player=tanks(game).get(0);
        player.x=x;player.y=y;
        game.tick(8);
    }

    @Test void anEnemyDropDoesNotReturnAfterBeingTaken() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);
        // A drop of a different item, on a cell nothing else can reach, so whichever supply does
        // come back is unambiguously the seeded one.
        List<PickupView> pickups=field(game,"pickups");
        pickups.add(new PickupView((2+.5)*Rules.CELL,(1+.5)*Rules.CELL,Item.REPAIR));
        takeAt(game,(2+.5)*Rules.CELL,(1+.5)*Rules.CELL);
        assertTrue(game.snapshot().pickups().isEmpty(),"Both the seed and the drop are gone");

        advance(game,30000);

        assertEquals(1,game.snapshot().pickups().size(),"Only the seeded slot comes back");
        assertEquals(Item.SHIELD,game.snapshot().pickups().get(0).item(),
                "A drop must never re-enter the map");
    }

    @Test void multiplayerLetsTheTurretSlotGoForGood() {
        TankGameModel game=multiGame(Mode.MULTI,2);
        List<PickupView> pickups=field(game,"pickups");
        pickups.clear();
        Map<Item,PickupView> seeded=slots(game);
        seeded.clear();
        PickupView turret=new PickupView(SUPPLY_X,SUPPLY_Y,Item.TURRET);
        pickups.add(turret);
        seeded.put(Item.TURRET,turret);

        takeAt(game,SUPPLY_X,SUPPLY_Y);
        assertTrue(game.snapshot().pickups().isEmpty(),"The turret supply was collected");

        advance(game,30000);

        assertTrue(game.snapshot().pickups().isEmpty(),
                "A networked client can never deploy a turret, so its slot must not come back");
    }

    @Test void nonNetworkedModesKeepCyclingTheTurretSlot() {
        TankGameModel game=supplyGame(Item.TURRET);
        takeIt(game);
        stepAway(game);

        advance(game,10000);

        assertEquals(Item.TURRET,game.snapshot().pickups().get(0).item(),
                "Deployment exists outside the networked modes, so the slot keeps cycling");
    }

    @Test void aFullTurretInventoryLeavesTheSlotOnTheMapAndOffTheClock() {
        TankGameModel game=supplyGame(Item.TURRET);
        Tank player=tanks(game).get(0);
        player.turretStock=0;
        List<Turret> turrets=ModelTest.field(game,"turrets");
        for(int i=0;i<Rules.TURRET_LIMIT;i++) {
            Turret turret=new Turret(player.id,200+i*40,600);
            turret.lifeMs=1_000_000;   // never expires, or capacity would free up mid-test
            turret.fireMs=1_000_000;   // never fires, so the duel cannot end mid-test
            turrets.add(turret);
        }

        takeAt(game,SUPPLY_X,SUPPLY_Y);

        assertEquals(1,game.snapshot().pickups().size(),"A refused pickup stays where it is");
        advance(game,30000);
        assertEquals(1,game.snapshot().pickups().size(),"And never starts a countdown");
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run: `mvn -q test -Dtest=SupplyRespawnTest`
Expected: `multiplayerLetsTheTurretSlotGoForGood` FAIL——炮塔槽位在 30 秒后仍然回来了。其余三个新用例应当已经 PASS（身份判定在 Task 1 已实现，容量判定是既有 `applyItem` 行为）。

- [ ] **Step 3: 在 scheduleRespawn 中跳过联机炮塔**

在 `scheduleRespawn` 的 `seeded.remove(supply.item());` 之后插入：

```java
        // A networked client can never reach DEPLOYING, so a turret supply there only ever adds
        // inventory that cannot be spent. Retiring the slot beats respawning something unusable.
        if(supply.item()==Item.TURRET && (mode==Mode.MULTI||mode==Mode.COOP)) return;
```

此时整个方法为：

```java
    private void scheduleRespawn(PickupView supply) {
        if(seeded.get(supply.item())!=supply) return;
        seeded.remove(supply.item());
        // A networked client can never reach DEPLOYING, so a turret supply there only ever adds
        // inventory that cannot be spent. Retiring the slot beats respawning something unusable.
        if(supply.item()==Item.TURRET && (mode==Mode.MULTI||mode==Mode.COOP)) return;
        respawns.add(new Respawn(supply.item(),supply.x(),supply.y(),Rules.ITEM_RESPAWN_MS));
    }
```

- [ ] **Step 4: 运行测试确认通过**

Run: `mvn -q test -Dtest=SupplyRespawnTest`
Expected: 9 个测试全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add src/main/java/tanktrouble/model/core/TankGameModel.java \
        src/test/java/tanktrouble/model/core/SupplyRespawnTest.java
git commit -m "feat(model): 敌人掉落不循环，联机模式炮塔槽位退出循环"
```

---

### Task 4: 新一波与重开重置槽位；暂停冻结倒计时

**Files:**
- Modify: `src/main/java/tanktrouble/model/core/TankGameModel.java`（仅在发现缺陷时）
- Test: `src/test/java/tanktrouble/model/core/SupplyRespawnTest.java`

**Interfaces:**
- Consumes: Task 1-3 的全部产出
- Produces: 无新接口；确认生命周期与冻结语义

- [ ] **Step 1: 写失败的测试**

在 `SupplyRespawnTest` 中追加：

```java
    @Test void startingAnotherBattleResetsEverySlot() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);
        advance(game,5000);

        game.home();
        game.selectMode(Mode.DUEL);
        game.startBattle(42);

        assertEquals(Item.values().length,game.snapshot().pickups().size(),
                "A fresh battle seeds a full set of slots");
        assertEquals(EnumSet.allOf(Item.class),EnumSet.copyOf(slots(game).keySet()));

        advance(game,9992);
        assertEquals(Item.values().length,game.snapshot().pickups().size(),
                "No stale countdown may survive into the new battle");
    }

    @Test void pauseFreezesTheReturnTimer() {
        TankGameModel game=supplyGame(Item.SHIELD);
        takeIt(game);
        stepAway(game);

        game.pause();
        advance(game,60000);
        assertTrue(game.snapshot().pickups().isEmpty(),"Paused time does not count");

        game.resume();
        advance(game,9992);
        assertTrue(game.snapshot().pickups().isEmpty(),"Still one tick short");
        game.tick(8);
        assertEquals(1,game.snapshot().pickups().size(),"The countdown resumes where it left off");
    }

    @Test void eachEndlessWaveStartsWithAFreshSetOfSlots() {
        TankGameModel game=new TankGameModel();
        game.selectMode(Mode.ENDLESS);
        game.setTerrainEnabled(false);
        game.startBattle(8);
        ModelTest.set(game,"map",TestMaps.open());
        for(Tank tank:tanks(game)) {tank.fireMs=1_000_000;tank.shieldMs=1_000_000;}
        ModelTest.<List<Cell>>field(game,"portals").clear();

        PickupView taken=slots(game).get(Item.SHIELD);
        Tank player=tanks(game).get(0);
        player.x=taken.x();player.y=taken.y();
        game.tick(8);
        advance(game,5000);

        tanks(game).stream().filter(tank->tank.player<0).forEach(tank->tank.hp=0);
        advance(game,6000);
        assertEquals(State.UPGRADE,game.snapshot().state());
        assertTrue(game.chooseUpgrade(Upgrade.REINFORCE));

        assertEquals(Item.values().length,game.snapshot().pickups().size(),
                "The new wave reseeds every slot");
        advance(game,9992);
        assertEquals(Item.values().length,game.snapshot().pickups().size(),
                "The old wave's countdown was discarded");
    }
```

- [ ] **Step 2: 运行测试确认失败或通过**

Run: `mvn -q test -Dtest=SupplyRespawnTest`
Expected: 理想情况下 12 个全部 PASS——`initializeWave()` 开头的 `seeded.clear();respawns.clear();` 已经覆盖了前两个用例，`tick()` 的 `state!=RUNNING` 早退覆盖了暂停用例。**若全部通过，本任务无需改生产代码**，直接进 Step 5 提交测试。若有 FAIL，进 Step 3。

- [ ] **Step 3: 仅在 Step 2 出现失败时修复**

按失败断言判断。可能的缺陷与修法：

- 「No stale countdown may survive」失败 → 确认 `initializeWave()` 中 `seeded.clear();respawns.clear();` 位于方法开头（`map=new RandomMapGenerator()...` 之后、`bullets.clear()` 附近），且 `startBattle(...)` 会调用 `initializeWave()`
- 「The countdown resumes where it left off」失败 → 说明倒计时在非 RUNNING 状态仍在推进，检查 `updateSupplies()` 的调用点是否位于 `tick()` 的 `if(state!=State.RUNNING) return;` 之后

- [ ] **Step 4: 运行全部模型层测试**

Run: `mvn -q test -Dtest=SupplyRespawnTest,ModelTest,TerrainRefreshTest,MultiplayerModeTest,ModelEventTest,DeploymentTest,EnemyBehaviorTest,TankRosterTest`
Expected: 全部 PASS。

- [ ] **Step 5: 提交**

```bash
git add src/test/java/tanktrouble/model/core/SupplyRespawnTest.java
git commit -m "test(model): 覆盖补给槽位在换波/重开/暂停下的生命周期"
```

---

### Task 5: 全量回归

**Files:** 无改动

**Interfaces:**
- Consumes: Task 1-4 的全部产出
- Produces: 通过验证的构建产物与部署包

- [ ] **Step 1: 跑完整测试套件**

Run: `mvn -q test`
Expected: BUILD SUCCESS，无失败。`FxSmokeTest` / `BattleRendererTest` 等图形用例需要可用的 Windows 桌面；若在无图形环境失败，记录失败的类名并在下一步说明，**不要**为通过而去改这些测试。

- [ ] **Step 2: 验证协议版本未被改动**

Run: `git diff HEAD~4 --stat -- src/main/java/tanktrouble/net/`
Expected: 输出为空。本计划不应触碰 `net/` 下任何文件。

- [ ] **Step 3: 复核验收标准**

对照规格 `docs/superpowers/specs/2026-09-15-supply-respawn-design.md` 逐条确认：

- 规则 1-6 每条都有对应测试且通过
- `Protocol.VERSION` 仍为 4
- 改动文件仅 `Rules.java`、`TankGameModel.java`、`SupplyRespawnTest.java`

Run: `git diff HEAD~4 --stat`
Expected: 只列出上述三个文件。

- [ ] **Step 4: 打包**

Run: `mvn -q package`
Expected: BUILD SUCCESS，`target/tanktrouble-javafx-3.0.2.jar` 生成。

- [ ] **Step 5: 提交（若有打包产物需要忽略则跳过）**

```bash
git status --porcelain
```
若只有 `target/` 被忽略、无其它改动，则无需提交。否则：

```bash
git add -A
git commit -m "chore: 补给重生功能回归通过"
```

---

## 部署说明（不属于本计划范围）

服务器上跑的是 3.0.2，本地这个 build 与它逐字节相同（sha256 `f8cb883b...`）。本次改动若要上线，需要：

1. `mvn -q package` 产出新 jar
2. 按 `deploy/server/DEPLOY.md` 打发布目录
3. 用 `deploy/server/install-release.sh <目录> 3.0.2` 安装（或升版本号到 3.0.3 以便回滚）

**注意**：本次改动**不涉及协议**，所以服务端和客户端可以各自独立更新，不需要强制同步升级。但联机时服务端决定补给刷新，客户端只是显示——所以只要服务端更新了，玩法改动就生效。

联机卡顿优化是**另一轮工作**，不在此计划内。
