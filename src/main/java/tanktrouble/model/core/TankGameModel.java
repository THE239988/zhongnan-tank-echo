package tanktrouble.model.core;

import java.util.*;
import tanktrouble.model.api.GameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.*;
import tanktrouble.model.rule.*;

public final class TankGameModel implements GameModel {
    public static final int MAX_PLAYERS=5;
    private State state=State.HOME;
    private State deploymentOrigin;
    private Mode mode=Mode.SINGLE;
    private Result result=Result.NONE;
    private final TankType[] selected=new TankType[MAX_PLAYERS];
    private final Map<Integer,EnumSet<Action>> input=new HashMap<>();
    private final List<Tank> tanks=new ArrayList<>();
    private final List<Bullet> bullets=new ArrayList<>();
    private final List<CoinView> coins=new ArrayList<>();
    private final List<PickupView> pickups=new ArrayList<>();
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

    private final List<Turret> turrets=new ArrayList<>();
    private final List<Cell> portals=new ArrayList<>();
    private final List<Zone> zones=new ArrayList<>();
    private final CollisionSystem collision=new CollisionSystem();
    private final CombatSystem combat=new CombatSystem();
    private final AiSystem ai=new AiSystem();
    private final List<GameEvent> events=new ArrayList<>();
    private GridMap map=new RandomMapGenerator().generate(1);
    private Random random=new Random(1);
    private long seed,elapsedMs,intermissionMs;
    private int terrainElapsedMs;
    private int wave=1,score,wallet,kills,shields,nextId,initialEnemyCount;
    private boolean bossSpawned;
    private boolean terrainEnabled=true;
    /**
     * Safety valve on the event buffer. A single tick can legitimately emit a handful of events;
     * hundreds would mean a bug, and silently growing the list would turn that into a leak.
     */
    private static final int MAX_EVENTS=256;

    public TankGameModel() {Arrays.fill(selected,TankType.BALANCED);}

    private void emit(GameEventType type,int player,double x,double y) {
        emit(type,player,x,y,-1,Double.NaN);
    }
    private void emit(GameEventType type,int player,double x,double y,int sourceSeat,double incomingAngle) {
        if(events.size()<MAX_EVENTS)
            events.add(new GameEvent(type,player,x,y,sourceSeat,incomingAngle));
    }
    private void emit(GameEventType type,int player) {emit(type,player,0,0);}
    /**
     * Returns every event recorded since the last call, then clears the buffer.
     *
     * <p>Draining rather than reading keeps each occurrence delivered exactly once, so a view can
     * call this once per frame without repeating sounds. Events produced outside {@link #tick}
     * (starting a battle, choosing an upgrade) accumulate until the next drain rather than being
     * dropped.
     */
    @Override public List<GameEvent> drainEvents() {
        if(events.isEmpty()) return List.of();
        List<GameEvent> drained=List.copyOf(events);
        events.clear();
        return drained;
    }

    @Override public void selectMode(Mode mode) {
        if(mode!=null && (state==State.HOME || state==State.READY)) {this.mode=mode;state=State.READY;}
    }
    @Override public void selectTank(int player,TankType type) {
        if(state==State.READY && player>=0 && player<MAX_PLAYERS && type!=null) selected[player]=type;
    }
    @Override public void setTerrainEnabled(boolean enabled) { if(state==State.READY) terrainEnabled=enabled; }
    @Override public void startBattle(long seed) {
        if(state!=State.READY) return;
        startBattle(seed,mode==Mode.DUEL?2:1);
    }
    @Override public void startBattle(long seed,int players) {
        int count=(mode==Mode.MULTI||mode==Mode.COOP)?Math.max(2,Math.min(players,MAX_PLAYERS)):mode==Mode.DUEL?2:1;
        List<Integer> slots=new ArrayList<>(count);
        for(int player=0;player<count;player++) slots.add(player);
        startBattle(seed,slots);
    }
    public void startBattle(long seed,List<Integer> playerSlots) {
        if(state!=State.READY) return;
        List<Integer> slots;
        if(mode==Mode.MULTI||mode==Mode.COOP) {
            if(playerSlots==null) return;
            slots=playerSlots.stream().filter(Objects::nonNull).filter(player->player>=0&&player<MAX_PLAYERS)
                    .distinct().sorted().limit(MAX_PLAYERS).toList();
            if(slots.size()<2) return;
        } else slots=mode==Mode.DUEL?List.of(0,1):List.of(0);
        this.seed=seed;random=new Random(seed);wave=1;score=wallet=kills=nextId=0;elapsedMs=0;
        tanks.clear();input.clear();result=Result.NONE;deploymentOrigin=null;bossSpawned=false;initialEnemyCount=0;
        for(int player:slots) {
            Tank tank=new Tank(nextId++,player,selected[player],0,0);
            if(mode==Mode.COOP) tank.team=0;
            tanks.add(tank);
        }
        for(Tank tank:tanks) {
            tank.energy=tank.type.startingEnergy;
            tank.shieldMs=tank.type.startingShieldMs;
        }
        emit(GameEventType.BATTLE_START,0);
        initializeWave();state=State.RUNNING;
    }
    public TankType selectedTankType(int player) {return player>=0&&player<MAX_PLAYERS?selected[player]:TankType.BALANCED;}
    public int humanPlayers() {
        if(mode==Mode.MULTI||mode==Mode.COOP) {
            long active=tanks.stream().filter(tank->tank.player>=0).count();
            return active==0?MAX_PLAYERS:(int)active;
        }
        return mode==Mode.DUEL?2:1;
    }
    private static final Cell[] SPAWNS={new Cell(1,1),new Cell(Rules.COLS-2,Rules.ROWS-2),
            new Cell(Rules.COLS-2,1),new Cell(1,Rules.ROWS-2),new Cell(Rules.COLS/2,Rules.ROWS/2)};
    private void initializeWave() {
        map=new RandomMapGenerator().generate(random.nextLong());
        bullets.clear();coins.clear();pickups.clear();turrets.clear();portals.clear();zones.clear();seeded.clear();respawns.clear();
        clearInput();intermissionMs=0;terrainElapsedMs=0;
        tanks.removeIf(t->t.player<0);
        Set<Cell> occupied=new HashSet<>();
        for(Tank tank:tanks) {
            Cell spawn=(mode==Mode.MULTI||mode==Mode.COOP)?spawnFor(tank.player,occupied):
                    tank.player==0?new Cell(0,0):new Cell(Rules.COLS-1,Rules.ROWS-1);
            occupied.add(spawn);tank.x=spawn.centerX();tank.y=spawn.centerY();tank.angle=tank.player==0?0:180;
            tank.vx=tank.vy=0;tank.fireMs=0;tank.portalMs=0;tank.heatMs=0;
        }
        if(mode!=Mode.DUEL && mode!=Mode.MULTI && mode!=Mode.COOP) {
            int count=mode==Mode.SINGLE?3:Math.min(10,2+wave);
            initialEnemyCount=count;
            for(int i=0;i<count;i++) {
                Cell spawn=freeCell(occupied,true);occupied.add(spawn);
                Tank enemy=new Tank(nextId++,-1,TankType.BALANCED,spawn.centerX(),spawn.centerY());
                enemy.angle=180;enemy.fireMs=Rules.ENEMY_FIRE_MS+i*160;
                enemy.hp=enemy.maxHp=mode==Mode.ENDLESS?1+(wave-1)/4:1;
                tanks.add(enemy);
            }
        }
        if(mode==Mode.COOP) spawnCoopBoss(occupied);
        for(int i=0;i<2;i++) {Cell cell=freeCell(occupied,false);occupied.add(cell);portals.add(cell);}
        // Seed one of every supply type so the new tactical modules are discoverable in a wave;
        // defeated enemies can still drop additional random copies later.
        for(int i=0;i<Item.values().length;i++) {
            Cell cell=freeCell(occupied,false);occupied.add(cell);
            PickupView supply=new PickupView(cell.centerX(),cell.centerY(),Item.values()[i]);
            pickups.add(supply);seeded.put(supply.item(),supply);
        }
        if(terrainEnabled) for(Terrain terrain:Terrain.values()) {
            Cell cell=freeCell(occupied,false);occupied.add(cell);
            zones.add(new Zone(new Rect(cell.x()*Rules.CELL+8,cell.y()*Rules.CELL+8,Rules.CELL-16,Rules.CELL-16),terrain));
        }
        emit(GameEventType.WAVE_START,0);
    }
    private Cell spawnFor(int player,Set<Cell> occupied) {
        Cell anchor=SPAWNS[Math.floorMod(player,SPAWNS.length)];
        if(!occupied.contains(anchor)&&!map.blocked(Rect.centered(anchor.centerX(),anchor.centerY(),Rules.TANK_HALF))) return anchor;
        return freeCell(occupied,false);
    }
    private void spawnCoopBoss(Set<Cell> occupied) {
        Cell spawn=freeCell(occupied,true);
        Tank boss=new Tank(nextId++,-1,TankType.HEAVY,spawn.centerX(),spawn.centerY());
        boss.boss=true;boss.hp=boss.maxHp=40+10*tanks.size();boss.fireMs=700;
        boss.skillMs=1200+random.nextInt(1401);tanks.add(boss);bossSpawned=true;
        emit(GameEventType.BOSS_SPAWN,-1,boss.x,boss.y);
    }
    private Cell freeCell(Set<Cell> used,boolean distant) {
        List<Cell> candidates=new ArrayList<>();
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++) {
            Cell cell=new Cell(x,y);
            if(!used.contains(cell) && (!distant || x+y>=7)) candidates.add(cell);
        }
        return candidates.get(random.nextInt(candidates.size()));
    }
    private void refreshTerrain() {
        List<Rect> candidates=new ArrayList<>();
        for(int x=0;x<Rules.COLS;x++) for(int y=0;y<Rules.ROWS;y++) {
            Rect bounds=new Rect(x*Rules.CELL+8,y*Rules.CELL+8,Rules.CELL-16,Rules.CELL-16);
            if(map.blocked(bounds)
                    || zones.stream().anyMatch(z->bounds.intersects(z.bounds()))
                    || tanks.stream().anyMatch(t->t.alive() && bounds.intersects(t.bounds()))
                    || turrets.stream().anyMatch(t->t.lifeMs>0 && bounds.intersects(Rect.centered(t.x,t.y,Rules.TURRET_HALF)))
                    || portals.stream().anyMatch(p->bounds.intersects(Rect.centered(p.centerX(),p.centerY(),Rules.PORTAL_HALF)))
                    || pickups.stream().anyMatch(p->bounds.intersects(Rect.centered(p.x(),p.y(),11)))) continue;
            candidates.add(bounds);
        }
        Terrain[] terrains=Terrain.values();
        // Preserve the current layout if a crowded map cannot fit all three safely.
        if(candidates.size()<terrains.length) return;
        Collections.shuffle(candidates,random);
        zones.clear();
        for(int i=0;i<terrains.length;i++) zones.add(new Zone(candidates.get(i),terrains[i]));
    }
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
    @Override public void tick(long deltaMs) {
        if(state!=State.RUNNING) return;
        if(deltaMs!=Rules.STEP_MS) throw new IllegalArgumentException("Simulation requires tick(8)");
        int ms=Rules.STEP_MS;elapsedMs+=ms;
        if(terrainEnabled) {
            terrainElapsedMs+=ms;
            if(terrainElapsedMs>=Rules.TERRAIN_REFRESH_MS) {
                terrainElapsedMs-=Rules.TERRAIN_REFRESH_MS;
                refreshTerrain();
            }
        }
        updateSupplies();
        for(Tank tank:tanks) if(tank.alive()) {
            tank.fireMs-=ms;tank.portalMs=Math.max(0,tank.portalMs-ms);
            tank.shieldMs=Math.max(0,tank.shieldMs-ms);tank.rapidMs=Math.max(0,tank.rapidMs-ms);
            tank.pulseMs=Math.max(0,tank.pulseMs-ms);tank.scatterMs=Math.max(0,tank.scatterMs-ms);
            tank.aimMs=Math.max(0,tank.aimMs-ms);tank.penetrateMs=Math.max(0,tank.penetrateMs-ms);
            if(tank.boss) tank.skillMs=Math.max(0,tank.skillMs-ms);
            if(tank.player>=0) updatePlayer(tank,ms);
            else {
                Tank target=tanks.stream().filter(t->t.player>=0 && t.alive()).findFirst().orElse(null);
                if(target!=null && ai.update(tank,target,map,tanks,bullets,ms,wave) && fire(tank))
                    ai.onFired(tank,map,tanks);
                if(tank.boss && tank.skillMs<=0) bossSkill(tank);
            }
            teleport(tank);
        }
        updateTurrets(ms);
        combat.update(bullets,map,tanks,ms,this::hit,
                (player,x,y)->emit(GameEventType.BOUNCE,player,x,y));
        updateTerrain(ms);
        collect();
        resolve();
        bullets.removeIf(b->b.dead);
    }
    private void updatePlayer(Tank tank,int ms) {
        var commands=input.computeIfAbsent(tank.player,k->EnumSet.noneOf(Action.class));
        double turn=(commands.contains(Action.RIGHT)?1:0)-(commands.contains(Action.LEFT)?1:0);
        tank.angle=(tank.angle+turn*Rules.TURN_SPEED*ms/1000.+360)%360;
        double drive=(commands.contains(Action.FORWARD)?1:0)-(commands.contains(Action.BACKWARD)?Rules.REVERSE:0);
        double speed=tank.type.speed*(inZone(tank,Terrain.GRAVITY)?.55:1);
        double vx=Math.cos(Math.toRadians(tank.angle))*speed*drive;
        double vy=Math.sin(Math.toRadians(tank.angle))*speed*drive;
        if(inZone(tank,Terrain.ICE)) {tank.vx+=(vx-tank.vx)*.025;tank.vy+=(vy-tank.vy)*.025;}
        else {tank.vx=vx;tank.vy=vy;}
        collision.move(tank,tank.vx*ms/1000.,tank.vy*ms/1000.,map,tanks);
        if(commands.contains(Action.FIRE) && tank.fireMs<=0) fire(tank);
        if(commands.remove(Action.PULSE) && tank.energy>=Rules.PULSE_COST) pulse(tank);
    }
    /** Boss launches a radial burst that pressures the player even when line-of-sight is blocked. */
    private void bossSkill(Tank boss) {
        for(int i=0;i<8;i++) {
            Bullet shell=combat.fire(boss,map,i*45,false);
            if(shell!=null) bullets.add(shell);
        }
        boss.skillMs=2800+random.nextInt(3201);
        emit(GameEventType.BOSS_SKILL,-1,boss.x,boss.y);
    }

    private boolean fire(Tank tank) {
        double[] offsets=tank.scatterMs>0?new double[]{-60,-20,20,60}:new double[]{0};
        List<Bullet> fired=new ArrayList<>(offsets.length);
        for(double offset:offsets) {
            Bullet bullet=combat.fire(tank,map,tank.angle+offset,tank.penetrateMs>0);
            if(bullet!=null) fired.add(bullet);
        }
        if(fired.isEmpty()) return false;
        bullets.addAll(fired);
        tank.fireMs=tank.player<0?Rules.ENEMY_FIRE_MS:
                (int)(tank.type.cooldown*tank.reloadMultiplier*(tank.rapidMs>0?.55:1));
        emit(tank.player<0?GameEventType.ENEMY_FIRE:GameEventType.PLAYER_FIRE,tank.player,tank.x,tank.y);
        return true;
    }
    private void pulse(Tank tank) {
        tank.energy=0;tank.pulseMs=450;
        double radius=Rules.PULSE_RADIUS+tank.resonance*20;
        for(Bullet b:bullets) if(Math.hypot(b.x-tank.x,b.y-tank.y)<=radius) b.dead=true;
        for(Tank target:tanks) if(target!=tank && target.team!=tank.team && target.alive() && target.shieldMs<=0 && Math.hypot(target.x-tank.x,target.y-tank.y)<=radius
                && collision.clearShot(tank.x,tank.y,target.x,target.y,0,map))
            damage(target,1,tank.player,Double.NaN);
        tank.shieldMs=Math.max(tank.shieldMs,800);
        emit(GameEventType.PULSE,tank.player,tank.x,tank.y);
    }
    private void hit(Tank target,Bullet bullet) {
        int attacker=tanks.stream().filter(t->t.id==bullet.owner).mapToInt(t->t.player).findFirst().orElse(-1);
        double incoming=Math.atan2(bullet.vy,bullet.vx)+Math.PI;
        if(target.shieldMs>0) {
            if(target.player>=0) shields++;
            // Reported separately from damage: absorbing a hit still needs feedback, or the
            // player cannot tell a working shield from a missed shot.
            emit(GameEventType.SHIELD_BLOCK,target.player,target.x,target.y,attacker,incoming);
            return;
        }
        damage(target,1,attacker,incoming);
    }
    private void damage(Tank target,int amount,int causedByPlayer,double incomingAngle) {
        if(!target.alive()) return;
        target.hp=Math.max(0,target.hp-amount);
        emit(target.player<0?GameEventType.HIT_ENEMY:GameEventType.HIT_PLAYER,
                target.player<0?causedByPlayer:target.player,target.x,target.y,
                causedByPlayer,incomingAngle);
        if(!target.alive()) {
            emit(GameEventType.KILL,causedByPlayer,target.x,target.y,causedByPlayer,incomingAngle);
            emit(GameEventType.EXPLOSION,target.player,target.x,target.y);
            if(target.player<0) {
                kills++;coins.add(new CoinView(target.x,target.y));
                if(random.nextDouble()<.28) pickups.add(new PickupView(target.x,target.y,Item.values()[random.nextInt(Item.values().length)]));
            }
        }
    }
    private void teleport(Tank tank) {
        if(tank.portalMs>0 || portals.size()!=2) return;
        for(int i=0;i<2;i++) {
            Cell source=portals.get(i),dest=portals.get(1-i);
            if(Math.hypot(tank.x-source.centerX(),tank.y-source.centerY())<18
                    && collision.canMove(tank,dest.centerX(),dest.centerY(),map,tanks)) {
                tank.x=dest.centerX();tank.y=dest.centerY();tank.portalMs=Rules.PORTAL_COOLDOWN_MS;
                ai.reset(tank);
                emit(GameEventType.TELEPORT,tank.player,tank.x,tank.y);
                return;
            }
        }
    }
    private boolean inZone(Tank tank,Terrain terrain) {
        return zones.stream().anyMatch(z->z.terrain()==terrain && z.bounds().intersects(Rect.centered(tank.x,tank.y,1)));
    }
    private void updateTerrain(int ms) {
        for(Tank tank:tanks) if(tank.alive()) {
            if(inZone(tank,Terrain.LAVA)) {
                tank.heatMs+=ms;
                if(tank.heatMs>=1000) {
                    tank.heatMs-=1000;
                    if(tank.shieldMs<=0) {damage(tank,1,-1,Double.NaN);emit(GameEventType.LAVA,tank.player,tank.x,tank.y);}
                }
            } else tank.heatMs=0;
        }
    }
    private void collect() {
        for(Tank tank:tanks) if(tank.player>=0 && tank.alive()) {
            coins.removeIf(c->{
                if(tank.bounds().intersects(Rect.centered(c.x(),c.y(),9))) {
                    wallet++;score+=Rules.COIN_SCORE;
                    emit(GameEventType.COIN,tank.player,c.x(),c.y());
                    return true;
                }
                return false;
            });
            pickups.removeIf(p->{
                if(tank.bounds().intersects(Rect.centered(p.x(),p.y(),11)) && applyItem(tank,p.item())) {
                    emit(p.item().event,tank.player,p.x(),p.y());
                    scheduleRespawn(p);
                    return true;
                }
                return false;
            });
        }
    }
    /**
     * Starts the cycle for a seeded slot that was just taken.
     *
     * <p>Guarded by identity: an enemy drop of the same item is a different instance, so it is
     * removed from the map without ever entering the cycle.
     */
    private void scheduleRespawn(PickupView supply) {
        if(seeded.get(supply.item())!=supply) return;
        seeded.remove(supply.item());
        // A networked client can never reach DEPLOYING, so a turret supply there only ever adds
        // inventory that cannot be spent. Retiring the slot beats respawning something unusable.
        if(supply.item()==Item.TURRET && (mode==Mode.MULTI||mode==Mode.COOP)) return;
        respawns.add(new Respawn(supply.item(),supply.x(),supply.y(),Rules.ITEM_RESPAWN_MS));
    }
    private boolean hasTurretCapacity(Tank tank) {
        return tank.turretStock+turrets.stream().filter(t->t.owner==tank.id && t.lifeMs>0).count()<Rules.TURRET_LIMIT;
    }
    private boolean applyItem(Tank tank,Item item) {
        if(item==Item.TURRET && !hasTurretCapacity(tank)) return false;
        switch(item) {
            case REPAIR -> tank.hp=Math.min(tank.maxHp,tank.hp+3+tank.type.repairBonus);
            case SHIELD -> tank.shieldMs=Rules.EFFECT_MS;
            case RAPID -> tank.rapidMs=Rules.EFFECT_MS;
            case TURRET -> tank.turretStock++;
            case SCATTER -> tank.scatterMs=Rules.EFFECT_MS;
            case AIM -> tank.aimMs=Rules.EFFECT_MS;
            case PENETRATE -> tank.penetrateMs=Rules.EFFECT_MS;
        }
        return true;
    }
    private void updateTurrets(int ms) {
        for(Turret turret:turrets) {
            turret.lifeMs-=ms;turret.fireMs-=ms;
            Tank owner=tanks.stream().filter(t->t.id==turret.owner).findFirst().orElse(null);
            if(owner==null || !owner.alive()) {turret.lifeMs=0;continue;}
            Tank target=tanks.stream().filter(t->t.alive() && t.id!=turret.owner && (mode==Mode.DUEL || t.player<0))
                    .filter(t->collision.clearShot(turret.x,turret.y,t.x,t.y,Rules.BULLET_HALF,map))
                    .min(Comparator.comparingDouble(t->Math.hypot(t.x-turret.x,t.y-turret.y))).orElse(null);
            if(target!=null) {
                turret.angle=Math.toDegrees(Math.atan2(target.y-turret.y,target.x-turret.x));
                if(turret.fireMs<=0) {
                    double dx=Math.cos(Math.toRadians(turret.angle))*24,dy=Math.sin(Math.toRadians(turret.angle))*24;
                    if(collision.clearShot(turret.x,turret.y,turret.x+dx,turret.y+dy,Rules.BULLET_HALF,map)
                            && tanks.stream().noneMatch(t->t.alive() && t.id!=turret.owner && t.bounds().intersects(Rect.centered(turret.x+dx,turret.y+dy,Rules.BULLET_HALF)))) {
                        bullets.add(new Bullet(turret.owner,turret.x+dx,turret.y+dy,turret.angle));
                        emit(GameEventType.TURRET_FIRE,owner.player,turret.x,turret.y);
                    }
                    turret.fireMs=750;
                }
            }
        }
        turrets.removeIf(t->t.lifeMs<=0);
    }
    private void resolve() {
        List<Tank> players=tanks.stream().filter(t->t.player>=0 && t.alive()).toList();
        if(mode==Mode.DUEL) {
            if(players.size()<2) end(players.isEmpty()?Result.DRAW:players.get(0).player==0?Result.P1_WIN:Result.P2_WIN);
            return;
        }
        if(mode==Mode.MULTI) {
            if(players.size()<=1) end(players.isEmpty()?Result.DRAW:Result.WIN);
            return;
        }
        if(mode==Mode.COOP) {
            if(players.isEmpty()) end(Result.DEFEAT);
            else if(tanks.stream().noneMatch(t->t.player<0&&t.alive())) end(Result.VICTORY);
            return;
        }
        if(players.isEmpty()) {end(Result.DEFEAT);return;}
        if(tanks.stream().noneMatch(t->t.player<0 && t.alive())) {
            if(mode==Mode.SINGLE) {
                if(!bossSpawned && initialEnemyCount>0) {spawnBoss();return;}
                if(bossSpawned) {end(Result.VICTORY);return;}
                end(Result.VICTORY);return;
            }
            if(intermissionMs==0) {intermissionMs=5000;bullets.clear();turrets.clear();}
            else {
                intermissionMs=Math.max(1,intermissionMs-Rules.STEP_MS);
                if(intermissionMs<=Rules.STEP_MS) {state=State.UPGRADE;clearInput();}
            }
        }
    }
    private void spawnBoss() {
        bossSpawned=true;
        Set<Cell> occupied=new HashSet<>();
        tanks.stream().filter(Tank::alive).forEach(t->occupied.add(map.cell(t.x,t.y)));
        Cell spawn=freeCell(occupied,true);
        Tank boss=new Tank(nextId++,-1,TankType.HEAVY,spawn.centerX(),spawn.centerY());
        boss.boss=true;boss.hp=boss.maxHp=20;boss.angle=180;boss.fireMs=900;
        boss.skillMs=1600+random.nextInt(1801);
        tanks.add(boss);
        emit(GameEventType.BOSS_SPAWN,-1,boss.x,boss.y);
    }
    private void end(Result result) {
        this.result=result;state=State.RESULT;clearInput();
        emit(GameEventType.BATTLE_END,0);
    }
    @Override public void handleInput(int player,Action action,boolean active) {
        if(player<0 || player>=MAX_PLAYERS || action==null) return;
        if((mode==Mode.MULTI||mode==Mode.COOP) && tanks.stream().noneMatch(tank->tank.player==player)) return;
        if(mode!=Mode.MULTI&&mode!=Mode.COOP&&player>=humanPlayers()) return;
        var commands=input.computeIfAbsent(player,k->EnumSet.noneOf(Action.class));
        if(!active) commands.remove(action);
        else if(state==State.RUNNING) commands.add(action);
    }
    @Override public void clearInput() {input.values().forEach(Set::clear);}
    public void disconnectPlayer(int player) {
        if(state!=State.RUNNING || (mode!=Mode.MULTI&&mode!=Mode.COOP)) return;
        input.remove(player);
        tanks.stream().filter(tank->tank.player==player&&tank.alive()).findFirst()
                .ifPresent(tank->damage(tank,tank.hp,-1,Double.NaN));
    }
    @Override public void pause() {if(state==State.RUNNING) {state=State.PAUSED;clearInput();}}
    @Override public void resume() {if(state==State.PAUSED || state==State.SHOP) {state=State.RUNNING;clearInput();}}
    @Override public void openShop() {if((state==State.RUNNING || state==State.PAUSED) && mode!=Mode.DUEL && mode!=Mode.MULTI && mode!=Mode.COOP) {state=State.SHOP;clearInput();}}
    private Tank livingPlayer() {
        return tanks.stream().filter(t->t.player==0 && t.alive()).findFirst().orElse(null);
    }
    @Override public boolean canPurchase(Item item) {
        if(state!=State.SHOP || mode==Mode.DUEL || item==null || wallet<item.price) return false;
        Tank player=livingPlayer();
        return player!=null && (item!=Item.REPAIR || player.hp<player.maxHp)
                && (item!=Item.TURRET || hasTurretCapacity(player));
    }
    @Override public boolean purchase(Item item) {
        if(!canPurchase(item)) return false;
        if(!applyItem(livingPlayer(),item)) return false;
        wallet-=item.price;return true;
    }
    @Override public boolean beginDeployment() {
        if(state!=State.RUNNING && state!=State.PAUSED && state!=State.SHOP) return false;
        Tank player=livingPlayer();
        if(player==null || player.turretStock<=0) return false;
        deploymentOrigin=state;state=State.DEPLOYING;clearInput();return true;
    }
    @Override public boolean canDeployTurret(double x,double y) {
        if(state!=State.DEPLOYING || !Double.isFinite(x) || !Double.isFinite(y)) return false;
        Tank player=livingPlayer();
        if(player==null || player.turretStock<=0) return false;
        Rect candidate=Rect.centered(x,y,Rules.TURRET_HALF);
        return !map.blocked(candidate)
                && tanks.stream().noneMatch(t->t.alive() && candidate.intersects(t.bounds()))
                && turrets.stream().noneMatch(t->t.lifeMs>0 && candidate.intersects(Rect.centered(t.x,t.y,Rules.TURRET_HALF)))
                && portals.stream().noneMatch(p->candidate.intersects(Rect.centered(p.centerX(),p.centerY(),Rules.PORTAL_HALF)));
    }
    @Override public boolean deployTurret(double x,double y) {
        if(!canDeployTurret(x,y)) return false;
        Tank player=livingPlayer();Turret turret=new Turret(player.id,x,y);
        turret.lifeMs=player.type.turretLifeMs;
        turrets.add(turret);player.turretStock--;
        emit(GameEventType.TURRET_DEPLOY,player.player,x,y);
        deploymentOrigin=null;state=State.PAUSED;clearInput();return true;
    }
    @Override public void cancelDeployment() {
        if(state!=State.DEPLOYING) return;
        state=deploymentOrigin;deploymentOrigin=null;clearInput();
    }
    @Override public boolean chooseUpgrade(Upgrade upgrade) {
        if(state!=State.UPGRADE || upgrade==null) return false;
        Tank player=tanks.stream().filter(t->t.player==0).findFirst().orElseThrow();
        switch(upgrade) {
            case REINFORCE -> {player.maxHp+=2;player.hp=Math.min(player.maxHp,player.hp+4);}
            case OVERCLOCK -> player.reloadMultiplier=Math.max(.35,player.reloadMultiplier*.86);
            case RESONANCE -> {player.resonance=Math.min(6,player.resonance+1);player.energy=100;}
        }
        wave++;initializeWave();state=State.RUNNING;return true;
    }
    @Override public void restart() {if(state==State.RESULT) {state=State.READY;clearInput();}}
    @Override public void home() {if(state!=State.EXITED) {state=State.HOME;deploymentOrigin=null;clearInput();bullets.clear();}}
    @Override public void finishRun() {
        if(mode==Mode.ENDLESS && (state==State.PAUSED || state==State.UPGRADE || state==State.SHOP)) end(Result.VICTORY);
    }
    @Override public void exit() {state=State.EXITED;deploymentOrigin=null;clearInput();bullets.clear();tanks.clear();}
    @Override public Snapshot snapshot() {
        return new Snapshot(state,mode,result,wave,score,wallet,kills,shields,elapsedMs,seed,intermissionMs,
                tanks.stream().map(Tank::view).toList(),
                bullets.stream().filter(b->!b.dead).map(b->new BulletView(b.x,b.y,b.vx,b.vy,b.owner,b.bounces,b.penetrate)).toList(),
                map.walls(),coins,pickups,portals,zones,
                turrets.stream().map(t->new TurretView(t.x,t.y,t.angle,t.lifeMs,t.owner)).toList(),
                state==State.UPGRADE?List.of(Upgrade.values()):List.of());
    }
}
