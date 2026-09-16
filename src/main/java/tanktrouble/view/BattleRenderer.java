package tanktrouble.view;

import java.util.ArrayList;
import java.util.List;
import javafx.scene.canvas.*;
import javafx.scene.paint.*;
import javafx.scene.effect.BlendMode;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.*;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;

public final class BattleRenderer {
    private final Canvas canvas;
    private final GraphicsContext g;
    private static final Color INK=Color.web("#172b22"),TEAL=Color.web("#c8ef64"),PINK=Color.web("#f0836b");
    private static final Color BOSS=Color.web("#ffb347");
    private static final Color[] PLAYER_COLORS={TEAL,Color.web("#f3b44f"),Color.web("#5fd4cf"),
            Color.web("#ef718c"),Color.web("#a7b6ff")};
    private int localPlayer=-1;
    /** A bounce flash lives this long, in seconds of the clock draw() is handed. */
    private static final double SPARK_LIFETIME_S=.45;
    /**
     * A bounce flash, raised by the BOUNCE event and aged against the frame clock.
     *
     * <p>Stamped on the first draw rather than at the call site: the event is drained outside the
     * frame, so reactTo has no clock to hand over - and its signature cannot grow one, because
     * FxSmokeTest reflects on it as (List). A flash one frame old is a flash nobody can see the
     * difference in.
     */
    private static final class Spark {
        final int player;final double x,y;
        double startClock=-1;
        Spark(int player,double x,double y) {this.player=player;this.x=x;this.y=y;}
    }
    private final List<Spark> sparks=new ArrayList<>();
    /** How long any one battlefield effect lives. Each kind scales this by its own factor. */
    static final double EFFECT_LIFETIME_S=1.2;

    /**
     * One battlefield effect raised by an event.
     *
     * <p>Same stamping rule as {@link Spark}: the event is drained outside the frame, so reactTo
     * has no clock to hand over (its signature is frozen by FxSmokeTest). Positions are re-resolved
     * against the snapshot at draw time rather than trusted from the event, so a muzzle flash
     * follows the tank instead of lagging it by a frame.
     */
    static final class Fx {
        final GameEventType kind;final int player,sourceSeat;
        final double x,y,incomingAngle;
        double startClock=-1;
        Fx(GameEventType kind,int player,double x,double y,int sourceSeat,double incomingAngle) {
            this.kind=kind;this.player=player;this.x=x;this.y=y;
            this.sourceSeat=sourceSeat;this.incomingAngle=incomingAngle;
        }
    }
    private final List<Fx> effects=new ArrayList<>();

    public void addEffect(GameEventType kind,int player,double x,double y,int sourceSeat,double incomingAngle) {
        // BATTLE_START and the WAVE_START that start() fires right behind it are one moment, not
        // two. Suppressing here rather than at draw time keeps both events' own arms intact.
        if(kind==GameEventType.WAVE_START) for(Fx fx:effects)
            if(fx.kind==GameEventType.BATTLE_START && (fx.startClock<0 || fx.startClock+BOOT_WINDOW_S>currentClock)) return;
        effects.add(new Fx(kind,player,x,y,sourceSeat,incomingAngle));
    }
    /** A WAVE_START landing this soon after a BATTLE_START is the same moment, not a new wave. */
    private static final double BOOT_WINDOW_S=.5;
    private double currentClock;

    /** Test seam: the aging pass without needing a live canvas. */
    void ageEffectsForTesting(double clock) {ageEffects(clock);}
    int effectCount() {return effects.size();}
    /**
     * Test seam: the paint order of the two layers in the last frame. The larger mark went down
     * later, so a caller can tell whether the effects were painted over the tanks without reading
     * pixels back.
     *
     * <p>Two running counters rather than a list of layer names: the only question ever asked is
     * which of the two went down first, and two ints answer it without allocating a string every
     * frame.
     *
     * <p>Each mark is written by the paint itself - inside the tank loop and at the head of
     * {@link #drawEffect} - never on the layer boundary around it. A mark on the boundary outlives
     * a layer that painted nothing, so "the effects went down after the tanks" would still hold for
     * a frame that drew no effect at all, which is the one thing this is meant to catch.
     */
    int lastTankMark() {return tankMark;}
    int lastEffectMark() {return fxMark;}
    private int paintSeq,tankMark,fxMark;
    static double clamp01(double v) {return v<0?0:v>1?1:v;}

    /**
     * Stamps the effects raised since the last frame and drops the expired ones.
     *
     * <p>{@code currentClock} is stored here, not at the call site, because addEffect has no clock
     * to compare against: the WAVE_START suppression needs to know how old the BATTLE_START is,
     * and the only moment that is known is the frame.
     */
    private void ageEffects(double clock) {
        currentClock=clock;
        for(Fx fx:effects) if(fx.startClock<0) fx.startClock=clock;
        effects.removeIf(fx->clock-fx.startClock>hardExpirySeconds(fx.kind));
    }
    /**
     * When a kind's effect is dropped, in seconds of the frame clock.
     *
     * <p>Split out of {@link #ageEffects} so the frame loop and a test ask the same question: every
     * kind's own drawing has to be finished before this lands.
     */
    static double hardExpirySeconds(GameEventType kind) {
        return EFFECT_LIFETIME_S*lifetimeFactor(kind);
    }
    /**
     * A kind's life as a slice of {@link #EFFECT_LIFETIME_S}, written as a quotient wherever the
     * kind has a span of its own.
     *
     * <p>The quotient is the whole point: the constant that decides how long the drawing lasts is
     * then the same one the hard expiry reads, so turning the dial moves both. Written as a bare
     * slice of EFFECT_LIFETIME_S the two drift apart - the dial turns, the drawing follows it, and
     * the expiry stays where it was and deletes the effect mid-fade. The hit group was the worst of
     * them (its inner ring runs to 0.38s and a bare .3 dropped it at 0.36s); the item, coin, muzzle
     * and transition kinds were each caught the same way before this. Only the kinds that have no
     * span of their own - nothing in this file draws them - fall through to default.
     */
    private static double lifetimeFactor(GameEventType kind) {
        return switch(kind) {
            case HIT_ENEMY,HIT_PLAYER -> HIT_TTL_S/EFFECT_LIFETIME_S;
            case KILL -> KILL_TTL_S/EFFECT_LIFETIME_S;
            case SHIELD_BLOCK -> BLOCK_TTL_S/EFFECT_LIFETIME_S;
            case PULSE -> PULSE_TTL_S/EFFECT_LIFETIME_S;
            case TELEPORT -> TELEPORT_TTL_S/EFFECT_LIFETIME_S;
            case LAVA -> LAVA_TTL_S/EFFECT_LIFETIME_S;
            // The transition kinds are quotients for the same reason the coin, the item and the
            // muzzle kinds are: TRANSITION_TTL_S / TRANSITION_SHORT_S are the dials for how long a
            // moment plays, so they have to be the hard expiry too. Written as a bare factor they
            // left only 0.04s / 0.06s of headroom, and raising either dial would have deleted the
            // effect at the old 1.14s / 0.96s - mid-fade, with k never reaching 1.
            case BATTLE_START,BATTLE_END -> TRANSITION_TTL_S/EFFECT_LIFETIME_S;
            case WAVE_START -> TRANSITION_SHORT_S/EFFECT_LIFETIME_S;
            case COIN -> COIN_TTL_S/EFFECT_LIFETIME_S;
            // The item kinds do not take a slice of EFFECT_LIFETIME_S at all: their life is the
            // animation's own length (flight + preview), so the dial is PICKUP_TTL_S. Sharing the
            // coin's old .6 used to cut them off at 0.72s - 0.03s short of the preview the design
            // asks for - and made every later tuning of the preview silently depend on the coin's
            // number.
            case ITEM_REPAIR,ITEM_SHIELD,ITEM_RAPID,ITEM_TURRET,ITEM_SCATTER,ITEM_AIM,ITEM_PENETRATE ->
                PICKUP_TTL_S/EFFECT_LIFETIME_S;
            // The muzzle flash is the one kind whose life is its own constant rather than a slice of
            // EFFECT_LIFETIME_S: it has to stay well clear of a rapid scout's 215ms repeat, so
            // MUZZLE_TTL_S is the dial, and this entry makes it the hard expiry as well as the
            // draw-time one - otherwise turning the dial would show nothing at all.
            case PLAYER_FIRE,ENEMY_FIRE,TURRET_FIRE -> MUZZLE_TTL_S/EFFECT_LIFETIME_S;
            default -> .3;
        };
    }
    public BattleRenderer(Canvas canvas) {this.canvas=canvas;g=canvas.getGraphicsContext2D();}
    public void setLocalPlayer(int player) {localPlayer=player>=0?player:-1;}
    /** P1 teal, P2 amber, the AI side pink. One place, so a fourth use cannot miss P2 again. */
    static Color tankColor(int player) {
        return player<0?PINK:PLAYER_COLORS[Math.floorMod(player,PLAYER_COLORS.length)];
    }
    static Color tankColor(Mode mode,int player) {
        if(player<0) return PINK;
        if(mode==Mode.COOP) return TEAL;
        return tankColor(player);
    }

    /**
     * Whose colour a hit is painted in: the shooter's, always.
     *
     * <p>{@code sourceSeat} carries {@code -1} both for an AI shot and for terrain, because AI
     * tanks carry seat {@code -1} too. That ambiguity resolve itself here rather than hurting:
     * {@link #tankColor} already maps {@code -1} to the AI side, which is the right colour for an
     * AI shot - the common case - and merely imprecise for terrain.
     *
     * <p>Terrain no longer reaches this at all, so the ambiguity only ever means an AI shot: the
     * model reports a burn and a pulse's splash as damage with no bearing, and reactTo drops the
     * three hit kinds when they carry no bearing, before they can raise an effect (see
     * TankTroubleApp.reactTo).
     * The shooter-coloured ring, its sparks and the white hull flash are therefore only ever painted
     * on a tank a bullet actually reached - never on one that was standing in fire.
     *
     * <p>Falling back to the victim's colour instead would be the rejected option: it would make
     * HIT_PLAYER read as "I hurt myself" while HIT_ENEMY and KILL read as "I hurt them".
     */
    static Color hitColor(Fx fx) {
        return tankColor(fx.sourceSeat);
    }
    /**
     * Whether a point sits on a lava patch, tested against the zone's own rectangle.
     *
     * <p>The centre point, not the hull's footprint: a tank is damaged by the tile it stands on,
     * and a corner overlapping the edge is not yet "in the lava" as the model counts it.
     */
    static boolean onLava(List<Zone> zones,double x,double y) {
        for(Zone zone:zones) if(zone.terrain()==Terrain.LAVA) {
            Rect r=zone.bounds();
            if(x>=r.x()&&x<=r.x()+r.width()&&y>=r.y()&&y<=r.y()+r.height()) return true;
        }
        return false;
    }
    /**
     * The colour of a tank's shield ring: the shared cyan everywhere, the lava orange while the
     * shield is the only thing holding a burn off.
     *
     * <p>A shield over lava is the one state nothing else reports: the model damages, and therefore
     * emits, only while {@code shieldMs<=0} (TankGameModel), so a shielded tank standing in fire is
     * silent. The ring is the only thing on screen that can say the shield is the reason the player
     * is safe - and it has to say it in a different colour, because a normal cyan ring reads as
     * "safe" the instant before the shield lapses and the burn lands. Off lava it stays that cyan,
     * which is the state every other shield on the field is in.
     *
     * <p>{@code tank} takes no part in the choice: both colours are states of the shield, not of
     * whoever wears it. It stays in the signature anyway, so a call site reads as describing one
     * particular tank - which is what the caller is doing.
     */
    static Color shieldRingColor(TankView tank,boolean onLava) {
        return onLava?SHIELD_ON_LAVA:SHIELD_CYAN;
    }
    public void draw(Snapshot s,double clock) {
        ageEffects(clock);
        g.save();g.scale(canvas.getWidth()/Rules.WIDTH,canvas.getHeight()/Rules.HEIGHT);
        for(Spark spark:sparks) if(spark.startClock<0) spark.startClock=clock;
        sparks.removeIf(spark->clock-spark.startClock>SPARK_LIFETIME_S);
        g.setFill(Color.web("#111d24"));g.fillRect(0,0,Rules.WIDTH,Rules.HEIGHT);
        g.setFill(Color.web("#172a2e"));
        for(int x=0;x<Rules.COLS;x+=2) g.fillRect(x*Rules.CELL,0,Rules.CELL,Rules.HEIGHT);
        g.setStroke(Color.web("#28484b",.7));g.setLineWidth(1);
        for(int y=0;y<=Rules.HEIGHT;y+=Rules.CELL) g.strokeLine(0,y,Rules.WIDTH,y);
        for(int x=0;x<=Rules.WIDTH;x+=Rules.CELL) g.strokeLine(x,0,x,Rules.HEIGHT);
        g.setStroke(Color.web("#3c6661",.32));
        for(int y=Rules.CELL/2;y<Rules.HEIGHT;y+=Rules.CELL) for(int x=Rules.CELL/2;x<Rules.WIDTH;x+=Rules.CELL) g.strokeOval(x-3,y-3,6,6);
        g.setStroke(Color.web("#6a8562",.48));g.setLineWidth(2);
        g.strokeRect(20,20,Rules.WIDTH-40,Rules.HEIGHT-40);
        g.strokeOval(Rules.WIDTH/2.-58,Rules.HEIGHT/2.-58,116,116);
        g.strokeLine(Rules.WIDTH/2.,20,Rules.WIDTH/2.,Rules.HEIGHT-20);
        for(Zone zone:s.zones()) drawZone(zone,clock);
        for(int i=0;i<s.portals().size();i++) {
            Cell p=s.portals().get(i);double r=23+Math.sin(clock*3)*2;
            g.setStroke(i==0?Color.web("#10a9bf"):PINK);g.setLineWidth(4);
            g.strokeOval(p.centerX()-r,p.centerY()-r,r*2,r*2);
            g.setLineWidth(1.5);g.strokeOval(p.centerX()-15,p.centerY()-15,30,30);
            g.setFill(Color.web("#e4f1d9"));g.setFont(Font.font("Consolas",FontWeight.BOLD,12));g.fillText(i==0?"A":"B",p.centerX()-4,p.centerY()+4);
        }
        for(Rect w:s.walls()) {
            g.setFill(Color.web("#050d12",.8));g.fillRoundRect(w.x()+4,w.y()+5,w.width(),w.height(),5,5);
            g.setFill(Color.web("#344b50"));g.fillRoundRect(w.x(),w.y(),w.width(),w.height(),5,5);
            g.setFill(Color.web("#6e8782"));g.fillRoundRect(w.x()+2,w.y()+2,Math.max(0,w.width()-4),Math.max(0,w.height()-4),3,3);
            g.setStroke(Color.web("#a9d0b3",.65));g.setLineWidth(1);
            if(w.width()>w.height()) {
                g.strokeLine(w.x()+1,w.y()+2,w.x()+w.width()-1,w.y()+2);
                for(double x=w.x()+14;x<w.x()+w.width()-1;x+=16) g.strokeLine(x,w.y()+1,x,w.y()+w.height()-1);
            } else {
                g.strokeLine(w.x()+2,w.y()+1,w.x()+2,w.y()+w.height()-1);
                for(double y=w.y()+14;y<w.y()+w.height()-1;y+=16) g.strokeLine(w.x()+1,y,w.x()+w.width()-1,y);
            }
        }
        drawAimAssist(s,clock);
        for(CoinView coin:s.drops()) {
            g.setFill(Color.web("#d88e08"));g.fillOval(coin.x()-8,coin.y()-7,16,16);
            g.setFill(Color.web("#ffce50"));g.fillOval(coin.x()-7,coin.y()-9,14,14);
            g.setStroke(Color.web("#fff2b8"));g.strokeLine(coin.x(),coin.y()-6,coin.x(),coin.y()+2);
        }
        for(PickupView p:s.pickups()) drawPickup(p,clock);
        for(TurretView t:s.turrets()) drawTurret(t,clock);
        for(BulletView b:s.bullets()) drawBolt(b,tankColor(ownerSeat(b.owner(),s.tanks())));
        for(Spark spark:sparks) drawSpark(spark,clock);
        for(TankView t:s.tanks()) {
            tankMark=++paintSeq;
            if(t.hp()<=0) {g.setStroke(Color.web("#9aa8a1"));g.setLineWidth(3);g.strokeLine(t.x()-8,t.y()-8,t.x()+8,t.y()+8);g.strokeLine(t.x()+8,t.y()-8,t.x()-8,t.y()+8);continue;}
            // Only the hull recoils; the shield ring, pulse, health bar and seat label stay on the
            // true position, because those are read as status and a status that slides is a lie.
            double recoil=recoilFor(t,clock);
            Color body=t.boss()?BOSS:tankColor(t.player());
            tank(g,t.x()-Math.cos(Math.toRadians(t.angle()))*recoil,
                    t.y()-Math.sin(Math.toRadians(t.angle()))*recoil,
                    t.angle(),t.type(),body,t.boss()?1.28:1);
            if(t.boss()) {g.setStroke(Color.web("#ffb347",.8));g.setLineWidth(3);g.strokeOval(t.x()-36,t.y()-36,72,72);}
            if(t.player()==localPlayer) {g.setStroke(Color.web("#f4f8ee",.9));g.setLineWidth(1.5);g.strokeOval(t.x()-28,t.y()-28,56,56);}
            // The ring is drawn at SHIELD_R, the same radius the block's arc and impact beat stand
            // on: written as a literal 25 it stayed put while the arc moved, so turning SHIELD_R
            // measured one thing and drew another.
            if(t.shieldMs()>0) {g.setStroke(shieldRingColor(t,onLava(s.zones(),t.x(),t.y())));g.setLineWidth(2.5);g.strokeOval(t.x()-SHIELD_R,t.y()-SHIELD_R,SHIELD_R*2,SHIELD_R*2);}
            if(t.pulseMs()>0) {
                double radius=(Rules.PULSE_RADIUS+t.resonance()*20)*(1-t.pulseMs()/450.);
                g.setGlobalAlpha(t.pulseMs()/450.);g.setStroke(tankColor(t.player()));g.setLineWidth(7);g.strokeOval(t.x()-radius,t.y()-radius,radius*2,radius*2);g.setGlobalAlpha(1);
            }
            g.setFill(INK);g.fillRoundRect(t.x()-18,t.y()-30,36,4,3,3);
            g.setFill(body);g.fillRoundRect(t.x()-18,t.y()-30,36.0*t.hp()/t.maxHp(),4,3,3);
            if(t.player()>=0) {g.setFill(Color.web("#e8f4d5"));g.setFont(Font.font("Consolas",FontWeight.BOLD,10));g.fillText("P"+(t.player()+1),t.x()-6,t.y()+33);}
        }
        // The whole effect layer rides above the tanks: the hit flash is an opaque plate sized to
        // the hull, so drawing it under the tank leaves nothing but its antialiased rim. Every
        // other kind - shield arc, muzzle, pickup, coin, pulse, portal, wave scan - wants the same
        // side of the hull, so they move together. Below the banner, which is the very top.
        for(Fx fx:effects) drawEffect(s,fx,clock);
        if(s.intermissionMs()>0) {
            g.setFill(INK);g.fillRoundRect(Rules.WIDTH/2.-110,12,220,35,6,6);
            g.setFill(Color.WHITE);g.setFont(Font.font("Microsoft YaHei",FontWeight.BOLD,15));
            g.fillText("战场回收  "+(int)Math.ceil(s.intermissionMs()/1000.)+"s",Rules.WIDTH/2.-67,35);
        }
        g.restore();
    }
    /** The one place a kind turns into paint. Kinds with no arm of their own fall through silently. */
    private void drawEffect(Snapshot s,Fx fx,double clock) {
        fxMark=++paintSeq;
        double age=clock-fx.startClock;
        switch(fx.kind) {
            case HIT_ENEMY,HIT_PLAYER -> drawHit(s,fx,age);
            case KILL -> drawKill(s,fx,age);
            case PLAYER_FIRE,ENEMY_FIRE,TURRET_FIRE -> drawMuzzle(s,fx,age);
            case ITEM_REPAIR,ITEM_SHIELD,ITEM_RAPID,ITEM_TURRET,ITEM_SCATTER,ITEM_AIM,ITEM_PENETRATE ->
                drawPickupFlight(s,fx,age);
            case SHIELD_BLOCK -> drawShieldBlock(s,fx,age);
            case PULSE -> drawPulse(s,fx,age);
            case TELEPORT -> drawTeleport(s,fx,age);
            case COIN -> drawCoin(s,fx,age);
            case LAVA -> drawLava(s,fx,age);
            case BATTLE_START,WAVE_START,BATTLE_END -> drawTransition(s,fx,age);
            // TURRET_DEPLOY and BOUNCE land here: the first has no effect layer of its own and the
            // second is carried by addBounce's sparks, so both are silent by design rather than
            // waiting on a task that has already run.
            default -> { }
        }
    }
    private static final Color WAVE_WARM=Color.web("#c8ef64"),WAVE_CYAN=Color.web("#20cde0"),WAVE_COOL=Color.web("#78bed2");
    /** The two spans a moment plays for. These are the dials: lifetimeFactor reads them as quotients. */
    private static final double TRANSITION_TTL_S=1.1,TRANSITION_SHORT_S=.9;
    /**
     * The opening's white flash, kept small on purpose - it covers the whole field exactly when the
     * player is trying to find the enemies. TTL and alpha are one pair so they can be turned down
     * together; the TTL is shared by the gate and the ramp so the flash cannot end on a hard cut.
     */
    private static final double TRANSITION_FLASH_TTL_S=.25,TRANSITION_FLASH_ALPHA=.3;
    /** How far the ring reaches, in HEIGHTs: an opening takes the field, a wave only half of it. */
    private static final double TRANSITION_RING_REACH_FULL=1.02,TRANSITION_RING_REACH_HALF=.42;
    /** The banner's font size. The width model below is calibrated to it, so the two move together. */
    private static final double TRANSITION_FONT_PX=15;

    static boolean sweepIsInward(GameEventType kind) {return kind==GameEventType.BATTLE_END;}
    static Color transitionColor(GameEventType kind) {
        return switch(kind) {
            case BATTLE_END -> WAVE_COOL;
            case WAVE_START -> WAVE_CYAN;
            default -> WAVE_WARM;
        };
    }
    static String transitionText(GameEventType kind,int wave) {
        return switch(kind) {
            case BATTLE_END -> "战斗结束";
            case WAVE_START -> "第 "+wave+" 波";
            default -> "战斗开始";
        };
    }
    /**
     * Rough advance width of a banner string, by class of character.
     *
     * <p>A flat count*7.5 was exact for an all-CJK banner - 7.5 is half a 15px em, which is the
     * CJK advance - but it measured the halfwidth digits and the spaces in "第 10 波" as full ems
     * too, and the banner then sat up to 17px left of centre on a 168px-wide plate. YaHei Bold at
     * TRANSITION_FONT_PX advances a full em for CJK, about 0.6em for ASCII, about 0.27em for a space.
     */
    static double transitionTextWidth(String text) {
        double w=0;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            w+=c==' '?TRANSITION_FONT_PX*.27:c<128?TRANSITION_FONT_PX*.6:TRANSITION_FONT_PX;
        }
        return w;
    }

    private void drawTransition(Snapshot s,Fx fx,double age) {
        double span=fx.kind==GameEventType.WAVE_START?TRANSITION_SHORT_S:TRANSITION_TTL_S;
        double k=clamp01(age/span);
        if(k>=1) return;
        Color color=transitionColor(fx.kind);
        boolean inward=sweepIsInward(fx.kind);
        g.save();
        g.setGlobalBlendMode(BlendMode.ADD);
        // The scan sweeps down on an opening and back up on a close, so direction alone carries
        // which of the two moments this is. It eases out rather than running at a constant speed:
        // the approved preview drives it with 1-(1-k)^2, reversed on a close.
        double easedScan=1-(1-k)*(1-k);
        double scanY=(inward?(1-k)*(1-k):easedScan)*Rules.HEIGHT,edge=inward?-1:1;
        for(int i=0;i<5;i++) {
            double y=scanY-edge*i*5;
            if(y<0||y>Rules.HEIGHT) continue;
            g.setStroke((i==0?Color.web("#ffffff"):color).deriveColor(0,1,1,(.85-i*.16)*(1-k)));
            g.setLineWidth(i==0?3:1.6);
            g.strokeLine(0,y,Rules.WIDTH,y);
        }
        double ringK=clamp01(age/(inward?.6:.8));
        if(ringK<1) {
            // A wave's ring stops at mid-field: design 6 makes the wave the smaller "expand" between
            // the opening and the closing, and the approved preview splits them the same way
            // (h*.42 against h*1.02). Keyed on the kind, not on inward - inward is the ending's
            // direction and says nothing about how far an opening or a wave reaches.
            double reach=Rules.HEIGHT*(fx.kind==GameEventType.WAVE_START?TRANSITION_RING_REACH_HALF:TRANSITION_RING_REACH_FULL);
            double r=inward?(Rules.HEIGHT*.95)*(1-ringK*ringK):4+(reach-4)*(1-(1-ringK)*(1-ringK));
            // alpha .5 / 2.5px, straight from the preview: this full-screen overlay is the part of
            // the set the design is most wary of, so this is one place not to drift brighter.
            g.setStroke(color.deriveColor(0,1,1,.5*(1-ringK)));
            g.setLineWidth(2.5*(1-ringK)+.5);
            g.strokeOval(Rules.WIDTH/2.-Math.max(.1,r),Rules.HEIGHT/2.-Math.max(.1,r),Math.max(.1,r)*2,Math.max(.1,r)*2);
        }
        g.restore();
        // The white flash is the most expensive thing in this whole plan visually, so it is the
        // shortest and the dimmest, and it only opens a battle.
        if(fx.kind==GameEventType.BATTLE_START&&age<TRANSITION_FLASH_TTL_S) {
            g.setGlobalAlpha(TRANSITION_FLASH_ALPHA*(1-age/TRANSITION_FLASH_TTL_S));g.setFill(Color.WHITE);
            g.fillRect(0,0,Rules.WIDTH,Rules.HEIGHT);g.setGlobalAlpha(1);
        }
        double bannerFade=Math.sin(Math.PI*clamp01(k));
        if(bannerFade<=0) return;
        double width=168*(.4+.6*(1-(1-clamp01(k*3))*(1-clamp01(k*3))));
        g.setGlobalAlpha(bannerFade);
        g.setFill(INK);g.fillRoundRect(Rules.WIDTH/2.-width/2,12,width,30,6,6);
        g.setStroke(color.deriveColor(0,1,1,.9));g.setLineWidth(1.5);
        g.strokeRoundRect(Rules.WIDTH/2.-width/2,12,width,30,6,6);
        g.setFill(Color.WHITE);g.setFont(Font.font("Microsoft YaHei",FontWeight.BOLD,TRANSITION_FONT_PX));
        String text=transitionText(fx.kind,s.wave());
        g.fillText(text,Rules.WIDTH/2.-transitionTextWidth(text)/2,33);
        g.setGlobalAlpha(1);
    }
    /**
     * How close a point has to sit to a hull to count as landing on it.
     *
     * <p>An event carries a position, not a seat, so the hull it belongs to is found back by
     * proximity: drawHit finds the tank a shot landed on this way, and drawTeleport the tank that
     * owns the arrival veil. One number for both, so the two matches cannot drift apart.
     */
    private static final double TANK_MATCH_RADIUS=24;
    /** Peak of the white flash on a tank that just took a hit. One number to dial if it reads heavy. */
    static final double HIT_FLASH_ALPHA=.9;
    /**
     * The hit group's own span, and the delay the inner ring starts after.
     *
     * <p>.38, not the .34 the inner ring itself runs for: the ring opens HIT_INNER_DELAY_S late and
     * is the last thing the group puts down, so this is where its life really ends. HIT_TTL_S is the
     * dial the hard expiry reads (lifetimeFactor), so a .34 here would have gone on cutting the tail
     * of the ring off - which is exactly what the old bare .3 factor did at 0.36s.
     */
    private static final double HIT_TTL_S=.38,HIT_INNER_DELAY_S=.04,HIT_RING_R=22,HIT_CORE_R=5.5;
    /** The spark cone is the impact beat on its own, and is gone long before the ring around it. */
    private static final double HIT_SPARK_TTL_S=.18;
    private static final double KILL_TTL_S=.8,KILL_RING_R=70,KILL_INNER_R=46,KILL_DUST_R=52;
    /** The kill flash's discs, narrowing and brightening towards the middle, over its own .2 s. */
    private static final double KILL_FLASH_R=44,KILL_FLASH_MID_R=29,KILL_FLASH_INNER_R=15,KILL_FLASH_TTL_S=.2;

    private void drawHit(Snapshot s,Fx fx,double age) {
        Color color=hitColor(fx);
        g.save();
        g.setGlobalBlendMode(BlendMode.ADD);
        ring(fx.x,fx.y,clamp01(age/.3),3,HIT_RING_R,color,.8,2.4);
        // The inner ring opens HIT_INNER_DELAY_S in and runs the rest of the group's span, not
        // HIT_TTL_S on top of the delay - that would put its last frame at 0.42s, past the expiry.
        ring(fx.x,fx.y,clamp01((age-HIT_INNER_DELAY_S)/(HIT_TTL_S-HIT_INNER_DELAY_S)),2,15,Color.web("#fffefa"),.7,1.6);
        // The white core is the "it landed" beat and it is deliberately the shortest part.
        double core=clamp01(1-age/.09);
        if(core>0) {g.setFill(Color.web("#fffefa",.85*core));g.fillOval(fx.x-HIT_CORE_R,fx.y-HIT_CORE_R,HIT_CORE_R*2,HIT_CORE_R*2);}
        // The sparks age by their own shorter life: stretched over the effect's full .38 they would
        // still be glittering after the hit had stopped reading as an impact.
        if(age<HIT_SPARK_TTL_S) {
            double sk=clamp01(age/HIT_SPARK_TTL_S),eased=1-(1-sk)*(1-sk),fade=1-sk;
            // A hit has a side, so the sparks fan down the flight path instead of around the whole
            // circle. incomingAngle is the bearing the shot came from, so it travelled the other
            // way; an unknown bearing opens out to a plain fan rather than painting NaN.
            double fan=Double.isNaN(fx.incomingAngle)?0:fx.incomingAngle+Math.PI;
            for(int i=0;i<5;i++) {
                double spread=fan+(i/4.0-.5)*2.1,distance=eased*16;
                double px=fx.x+Math.cos(spread)*distance,py=fx.y+Math.sin(spread)*distance;
                // The streak trails back towards the centre so the particle reads as the head of a
                // comet and the segment as its wake, matching the approved preview.
                g.setStroke(color.deriveColor(0,1,1,.5*fade));g.setLineWidth(1.2*fade+.3);
                g.strokeLine(px,py,px-Math.cos(spread)*8*fade,py-Math.sin(spread)*8*fade);
                g.setFill(color.deriveColor(0,1,1.15,.9*fade*fade));
                g.fillOval(px-1.5,py-1.5,3,3);
            }
        }
        g.restore();
        // The tank that was hit is the one sitting on the impact point. Matching by seat would
        // pick the shooter on a HIT_ENEMY (its player field names the one who dealt it), which is
        // how this flash went missing on the single-player path altogether.
        for(TankView t:s.tanks()) if(Math.hypot(t.x()-fx.x,t.y()-fx.y)<TANK_MATCH_RADIUS) {
            double flash=clamp01(1-age/.09)*HIT_FLASH_ALPHA;
            if(flash<=0) continue;
            g.save();g.setGlobalBlendMode(BlendMode.ADD);
            g.setFill(Color.web("#fffefa",flash));
            g.fillRoundRect(t.x()-18,t.y()-17,36,34,3,3);
            g.restore();
        }
    }

    private void drawKill(Snapshot s,Fx fx,double age) {
        if(age>KILL_TTL_S) return;
        Color color=hitColor(fx);
        double k=clamp01(age/KILL_TTL_S);
        g.save();g.setGlobalBlendMode(BlendMode.ADD);
        ring(fx.x,fx.y,k,8,KILL_RING_R,color,.85,4);
        ring(fx.x,fx.y,clamp01(age/.55),4,KILL_INNER_R,Color.web("#fffefa"),.8,2.4);
        // The debris flies outward on the same ease the sparks use. Driven inward it read as the
        // blast sucking its own pieces back in, which is the opposite of what a kill looks like.
        double frag=clamp01(age/.7),eased=1-(1-frag)*(1-frag);
        for(int i=0;i<12;i++) {
            double spread=(fx.y*.11+fx.x*.05)%(Math.PI*2)+i*.5236,distance=eased*46;
            g.setStroke(color.deriveColor(0,1,1,.5*(1-k)));
            g.setLineWidth(1.4*(1-k)+.3);
            double px=fx.x+Math.cos(spread)*distance,py=fx.y+Math.sin(spread)*distance;
            // Same comet read as the hit sparks: the debris head sits out front and the streak
            // trails back towards the centre.
            g.strokeLine(px,py,px-Math.cos(spread)*8*(1-k),py-Math.sin(spread)*8*(1-k));
            g.setFill(color.deriveColor(0,1,1.2,.9*(1-k)*(1-k)));
            g.fillOval(px-1.8,py-1.8,3.6,3.6);
        }
        // The centre flash, as three stacked discs rather than the RadialGradient the design calls
        // for: a gradient's coordinates do not survive the scaled context draw() sets up, so this
        // project keeps clear of them. Additive, the discs wrap a bright middle in the falloff.
        double flash=clamp01(1-age/KILL_FLASH_TTL_S)*.8;
        if(flash>0) {
            g.setFill(Color.web("#fffefa",flash*.2));g.fillOval(fx.x-KILL_FLASH_R,fx.y-KILL_FLASH_R,KILL_FLASH_R*2,KILL_FLASH_R*2);
            g.setFill(Color.web("#fffefa",flash*.3));g.fillOval(fx.x-KILL_FLASH_MID_R,fx.y-KILL_FLASH_MID_R,KILL_FLASH_MID_R*2,KILL_FLASH_MID_R*2);
            g.setFill(Color.web("#fffefa",flash*.5));g.fillOval(fx.x-KILL_FLASH_INNER_R,fx.y-KILL_FLASH_INNER_R,KILL_FLASH_INNER_R*2,KILL_FLASH_INNER_R*2);
        }
        g.restore();
        // Ground dust is painted after the additive pass has closed, so it is not stacked brighter -
        // it is meant to read as settling, not as glowing. It opens on the same ease as the rest.
        double dust=clamp01(age/.7);
        if(dust<1) {double dustEased=1-(1-dust)*(1-dust);
            g.setGlobalAlpha(.25*(1-dust));g.setFill(Color.web("#96b4aa"));
            g.fillOval(fx.x-KILL_DUST_R*dustEased,fx.y-KILL_DUST_R*dustEased,KILL_DUST_R*2*dustEased,KILL_DUST_R*2*dustEased);
            g.setGlobalAlpha(1);}
    }

    /**
     * Barrel length from the hull's centre to the muzzle. The drawn hull barrel runs 0 -> 26
     * ({@link #tank}), so the flash lands on its tip. The turret's barrel reaches 27-29, not this -
     * see the turret arm of {@link #muzzleOriginFor}.
     */
    static final double MUZZLE_OFFSET=26;
    /** The one number to dial for the muzzle flash's life. It is not a factor of EFFECT_LIFETIME_S. */
    private static final double MUZZLE_TTL_S=.2,MUZZLE_RECOIL=4,MUZZLE_CORE_R=5.5,MUZZLE_RING_R=14;
    /** How long the barrel takes to slide back out. The flash is visible for the whole of it. */
    private static final double MUZZLE_RECOIL_WINDOW_S=.14;

    /**
     * Where a shot left from, as {x, y, angleInRadians}.
     *
     * <p>PLAYER_FIRE and ENEMY_FIRE carry the tank's centre; TURRET_FIRE carries the turret's. The
     * angle is never in the event, so it is read back from whichever body is still on the field -
     * and a body that has since died leaves the angle NaN rather than silently 0, because a muzzle
     * flash pointing due east is a lie while a missing one is merely missing.
     *
     * <p>The bodies carry degrees, not radians: TankGameModel turns a hull at TURN_SPEED degrees a
     * second and builds turret.angle with toDegrees. Converting here, once, keeps the declared
     * contract honest - every caller (drawMuzzle first) may take cos/sin of [2] directly.
     */
    static double[] muzzleOriginFor(List<TankView> tanks,List<TurretView> turrets,
                                    double x,double y,GameEventType kind,int seat) {
        // The two arms are exclusive, and the turret arm always returns. Falling through from a
        // turret that is already gone would hand the shot to whatever tank happens to stand within
        // 40px - and turrets do leave the snapshot on the very frame of their last shot
        // (turrets.removeIf(t->t.lifeMs<=0)), while the flash outlives it by MUZZLE_TTL_S.
        if(kind==GameEventType.TURRET_FIRE) {
            for(TurretView t:turrets) if(Math.hypot(t.x()-x,t.y()-y)<24) {
                // The flash sits 26 from the turret's centre, not on the tip of its 27-29 barrel.
                // The 1-3 px of daylight is invisible at flash scale, and not worth a constant.
                double angle=Math.toRadians(t.angle());
                return new double[]{t.x()+Math.cos(angle)*MUZZLE_OFFSET,
                                    t.y()+Math.sin(angle)*MUZZLE_OFFSET,angle};
            }
            return new double[]{x,y,Double.NaN};
        }
        TankView best=null;double bestDistance=Double.MAX_VALUE;
        for(TankView t:tanks) {
            if(kind==GameEventType.PLAYER_FIRE&&t.player()!=seat) continue;
            double d=Math.hypot(t.x()-x,t.y()-y);
            if(d<bestDistance) {bestDistance=d;best=t;}
        }
        if(best!=null&&bestDistance<40) {
            double angle=Math.toRadians(best.angle());
            return new double[]{best.x()+Math.cos(angle)*MUZZLE_OFFSET,
                                best.y()+Math.sin(angle)*MUZZLE_OFFSET,angle};
        }
        return new double[]{x,y,Double.NaN};
    }

    /**
     * How far the hull is pushed back this frame, along the reverse of its own heading.
     *
     * <p>Matched to a live FIRE effect sitting on the tank, the same way drawHit finds the tank that
     * took a hit - not to the effect's seat, which on a TURRET_FIRE belongs to a turret standing
     * elsewhere and would tug the wrong hull. A tank with no effect on it gets 0, so a frame without
     * firing draws exactly as it did before.
     *
     * <p>Nothing is written back to the TankView: the shot left from where the tank really is, and a
     * recoil stored in the snapshot would drag the next frame's muzzle with it.
     */
    private double recoilFor(TankView tank,double clock) {
        for(Fx fx:effects) {
            // Only a hull's own shot shoves it. A turret firing must not tug a tank that happens to
            // be parked beside it - recoil is the feel of your own gun, not a shockwave.
            if(fx.kind!=GameEventType.PLAYER_FIRE&&fx.kind!=GameEventType.ENEMY_FIRE) continue;
            if(Math.hypot(tank.x()-fx.x,tank.y()-fx.y)>=24) continue;
            double age=clock-fx.startClock;
            if(age<0||age>MUZZLE_RECOIL_WINDOW_S) continue;
            return MUZZLE_RECOIL*(1-age/MUZZLE_RECOIL_WINDOW_S);
        }
        return 0;
    }

    private void drawMuzzle(Snapshot s,Fx fx,double age) {
        // The hard expiry paths through lifetimeFactor, so this is the draw-time half of the same
        // number: without it a FIRE kind would keep painting for whatever the default factor gave it.
        if(age>MUZZLE_TTL_S) return;
        double[] origin=muzzleOriginFor(s.tanks(),s.turrets(),fx.x,fx.y,fx.kind,fx.player);
        Color color=fx.player<0?tankColor(fx.sourceSeat>=0?fx.sourceSeat:-1):tankColor(fx.player);
        if(Double.isNaN(origin[2])) return;
        double ux=Math.cos(origin[2]),uy=Math.sin(origin[2]);
        g.save();g.setGlobalBlendMode(BlendMode.ADD);
        double core=clamp01(1-age/.07);
        if(core>0) {g.setFill(Color.web("#fffefa",.85*core));
            g.fillOval(origin[0]-MUZZLE_CORE_R,origin[1]-MUZZLE_CORE_R,MUZZLE_CORE_R*2,MUZZLE_CORE_R*2);}
        ring(origin[0],origin[1],clamp01(age/.15),3,MUZZLE_RING_R,color,.8,2.4);
        // The initial-velocity line ties the muzzle to the bolt that just left it. Capped at
        // 0.12s on purpose: at scout-plus-rapid the next shot is 215ms away.
        double line=clamp01(1-age/.12);
        if(line>0) {
            g.setStroke(color.deriveColor(0,1,1,.75*line));g.setLineWidth(3*line+.5);
            g.strokeLine(origin[0],origin[1],origin[0]+ux*18*line,origin[1]+uy*18*line);
        }
        g.restore();
    }

    /**
     * The colour a shield block is painted in, taken from the ring the tank already wears (the
     * {@code t.shieldMs()>0} oval in {@link #draw}) rather than from {@link #tankColor}.
     *
     * <p>A per-seat colour would make the arc read as a decal stuck on the hull - one that changes
     * with whoever is in the driver's seat, and that says "this tank" rather than "the shield". The
     * shared cyan says the shield itself flared for a moment, which is what a block is.
     */
    static final Color SHIELD_CYAN=Color.web("#23acc6");
    /**
     * The ring a shield wears while it is the only thing holding lava off the hull.
     *
     * <p>A warm orange, deliberately close to the lava it is standing in: the ring is meant to read
     * as "the fire is here and the shield is what is between you and it", not as a second, unrelated
     * status light. What it must not do is stay cyan, which is the colour of a shield with nothing
     * to say.
     */
    static final Color SHIELD_ON_LAVA=Color.web("#e08a4a");
    /** The shield ring's radius, which the block's arc and its impact beat both stand on. */
    static final double SHIELD_R=25;
    private static final double BLOCK_TTL_S=.3,BLOCK_ARC_DEG=50;
    private static final double PULSE_TTL_S=.5,TELEPORT_TTL_S=.35;
    /** The arrival ring's reach, and the five-line cone the approved preview fans out with it. */
    private static final double TELEPORT_RING_R=26,TELEPORT_JET_REACH=30,TELEPORT_JET_NEAR=7,TELEPORT_JET_FAR=13;
    /**
     * The arrival's fade: what comes out of a portal is still leaving the light behind it.
     *
     * <p>A plate in the arriving player's colour sits on the hull and dissolves over the effect's
     * whole life, so the tank reads as materialising instead of as having been teleported in fully
     * formed. Not the hit group's white flash: same plate, but the shooter's colour rather than
     * white, and it lands at .45 rather than .9, which is the difference between appearing and
     * being hit.
     */
    private static final double TELEPORT_EMERGE_ALPHA=.45;
    /** World space is y-down; JavaFX arcs are y-up and counter-clockwise. */
    static double javafxAngle(double radians) {return -Math.toDegrees(radians);}
    /**
     * Degrees for the centre of a block arc.
     *
     * <p>Incoming angle is the come-from direction already, so this is the flip and nothing more.
     * A second half-turn here would move the arc to the untouched flank - see the global
     * constraints' warning about the mirror bug, which still "runs" when you make it.
     * No direction (NaN) is reported as NaN so the caller takes the full-ring fallback.
     */
    static double blockArcCentre(Fx fx) {
        return Double.isNaN(fx.incomingAngle)?Double.NaN:javafxAngle(fx.incomingAngle);
    }
    /**
     * The point of the shield a block lands on: the struck face, at the ring's own radius.
     *
     * <p>{@code incomingAngle} is the come-from bearing in screen-space radians already, which is
     * what {@code cos}/{@code sin} eat. Routing it through {@link #javafxAngle} on the way here
     * would be a degrees/negation round trip with a mirrored flank waiting at the end of it.
     * A NaN bearing yields NaN coordinates so the caller can leave the beat out altogether -
     * fillOval throws on NaN rather than skipping.
     */
    static double[] blockImpactPoint(Fx fx) {
        return new double[]{fx.x+Math.cos(fx.incomingAngle)*(SHIELD_R+2),fx.y+Math.sin(fx.incomingAngle)*(SHIELD_R+2)};
    }

    private void drawShieldBlock(Snapshot s,Fx fx,double age) {
        Color color=SHIELD_CYAN;
        g.save();g.setGlobalBlendMode(BlendMode.ADD);
        if(!Double.isNaN(fx.incomingAngle)) {
            // The impact beat belongs on the struck flank, not pinned due west. A fixed west point
            // sits on the lit arc for only .28 of the possible bearings - the arc is 100 degrees
            // wide - so for every other block the same frame would light one flank and score the
            // other, and the core is the brightest thing the additive layer lays down, which is the
            // part the eye lands on first.
            double[] impact=blockImpactPoint(fx);
            double ix=impact[0],iy=impact[1];
            double core=clamp01(1-age/.08);
            if(core>0) {g.setFill(Color.web("#8ce8f5",.85*core));g.fillOval(ix-6,iy-6,12,12);}
            // Only the struck flank lights up. A full ring would say "something happened"; the arc
            // plus the sparks say "from over there", which is the part that changes what you do next.
            double k=clamp01(age/BLOCK_TTL_S),expand=6*(1-(1-k)*(1-k)),fade=1-k;
            g.setStroke(color.deriveColor(0,1,1,.95*fade));g.setLineWidth(3.2*fade+.8);
            g.strokeArc(fx.x-(SHIELD_R+2+expand),fx.y-(SHIELD_R+2+expand),(SHIELD_R+2+expand)*2,(SHIELD_R+2+expand)*2,
                blockArcCentre(fx)-BLOCK_ARC_DEG,BLOCK_ARC_DEG*2,ArcType.OPEN);
            // The sparks leave that same struck face and travel out along the bearing, on the
            // outward ease the hit and kill debris use. Started on the far side and driven inward
            // they read as the block hoovering up its own sparks - the one arm in this file that
            // flew the wrong way.
            double eased=1-(1-k)*(1-k);
            for(int i=0;i<6;i++) {
                double a=fx.incomingAngle+(i/5.0-.5)*1.5;
                g.setFill(color.deriveColor(0,1,1.2,.9*fade*fade));
                g.fillOval(ix+Math.cos(a)*eased*20-1.5,iy+Math.sin(a)*eased*20-1.5,3,3);
            }
        } else {
            // Defensive only, and unreachable today: the model's single SHIELD_BLOCK emit always
            // carries a bearing, the pulse that wounds through a shield reports PULSE instead, and
            // lava damage cannot reach a tank whose shield is up. Kept because a full ring still
            // says "you were hit" while staying silent on where from, and because this is the one
            // place that keeps a lost bearing out of fillOval.
            ring(fx.x,fx.y,clamp01(age/BLOCK_TTL_S),SHIELD_R,SHIELD_R+14,color,.7,2.4);
        }
        g.restore();
    }

    private void drawPulse(Snapshot s,Fx fx,double age) {
        double k=clamp01(age/PULSE_TTL_S);
        g.save();g.setGlobalBlendMode(BlendMode.ADD);
        // Drawn at the base Rules.PULSE_RADIUS, which is the reach only for a tank with no
        // resonance: the damage really adds tank.resonance*20, and that bonus is not in the event
        // payload, so a high-resonance pulse draws a ring smaller than the circle it cleared.
        ring(fx.x,fx.y,clamp01(age/.45),0,Rules.PULSE_RADIUS,TEAL,.85,7);
        for(int i=0;i<16;i++) {
            double a=i/16.0*6.284+.2,distance=Rules.PULSE_RADIUS*(1-(1-k)*(1-k));
            g.setFill(TEAL.deriveColor(0,1,1.1,.8*(1-k)*(1-k)));
            g.fillOval(fx.x+Math.cos(a)*distance-2.4,fx.y+Math.sin(a)*distance-2.4,4.8,4.8);
        }
        g.restore();
    }

    private void drawTeleport(Snapshot s,Fx fx,double age) {
        if(age>TELEPORT_TTL_S) return;
        Color color=tankColor(fx.player);
        double k=clamp01(age/TELEPORT_TTL_S),fade=1-k;
        g.save();g.setGlobalBlendMode(BlendMode.ADD);
        // Arrival, and only arrival. TankGameModel.teleport sets the hull's position and only then
        // emits, so the event's coordinates are where the tank came OUT - the contracting ring the
        // design reserves for a departure was being drawn on the destination, which read as the tank
        // being sucked back in the instant it appeared. The destination expands (0 -> 26) instead.
        ring(fx.x,fx.y,k,0,TELEPORT_RING_R,color,.8,2.4);
        // The cone blows outwards with it: five lines, the outer ones shorter and dimmer, opening on
        // the same outward ease as the ring. Its bearing is fixed rather than read from the tank,
        // and the departure end is not drawn at all, for one reason - the event carries the arrival
        // point and nothing else, so there is no source position to pair it with and no heading to
        // aim either end down.
        double eased=1-(1-k)*(1-k);
        for(int i=-2;i<=2;i++) {
            double reach=TELEPORT_JET_REACH*eased*(1-Math.abs(i)*.22);
            g.setStroke(color.deriveColor(0,1,1,(.8-Math.abs(i)*.18)*fade));
            g.setLineWidth(2.6-Math.abs(i)*.5);
            g.strokeLine(fx.x,fx.y+i*TELEPORT_JET_NEAR,fx.x+reach,fx.y+i*TELEPORT_JET_FAR);
        }
        g.restore();
        // The fade, painted after the additive pass has closed so the hull is tinted rather than lit
        // up. The tank is found by the arrival point the way drawHit finds the tank a shot landed on
        // - by proximity, not by seat, which on a duel would also veil the other player's hull.
        double emerge=clamp01(1-age/TELEPORT_TTL_S);
        if(emerge>0) for(TankView t:s.tanks()) if(Math.hypot(t.x()-fx.x,t.y()-fx.y)<TANK_MATCH_RADIUS) {
            g.save();g.setGlobalAlpha(TELEPORT_EMERGE_ALPHA*emerge);
            g.setFill(color.deriveColor(0,1,1,1.4));
            g.fillRoundRect(t.x()-18,t.y()-17,36,34,3,3);
            g.restore();
        }
    }

    /** An expanding stroke. Radius is clamped non-negative: a negative one throws. */
    private void ring(double x,double y,double k,double r0,double r1,Color color,double alpha,double width) {
        if(k<0||k>=1) return;
        double eased=1-(1-k)*(1-k),r=Math.max(.1,r0+(r1-r0)*eased),fade=1-k;
        g.setStroke(color.deriveColor(0,1,1,alpha*fade));
        g.setLineWidth(width*fade+.5);
        g.strokeOval(x-r,y-r,r*2,r*2);
    }
    /**
     * One shot: a needle with a comet tail, in its owner's colour.
     *
     * <p>Colour used to encode the shot's state - white, yellow-green for a bounce, cyan for
     * penetration. But whose shot is in the air is the information that was actually missing
     * (nobody can hit themselves, and every bounce pays its owner resonance), while a bounce and a
     * penetration both have a non-colour carrier: brightness and length. So the hue goes to the
     * owner and the state moves into the numbers.
     */
    private void drawBolt(BulletView b,Color color) {
        double stretch=b.penetrate()?BOLT_PENETRATE_SCALE:1;
        double alpha=bulletAlpha(b.bounces());
        double angle=Math.toDegrees(Math.atan2(b.vy(),b.vx()));
        double tail=Math.hypot(b.vx(),b.vy())*bulletTailSeconds(b.bounces())
                *(b.penetrate()?BOLT_PENETRATE_TAIL_SCALE:1);
        double backX=-Math.cos(Math.toRadians(angle))*tail,backY=-Math.sin(Math.toRadians(angle))*tail;
        g.save();
        g.translate(b.x(),b.y());
        g.setGlobalBlendMode(BlendMode.ADD);
        // One gradient stroke, not a chain of segments: a chain bands where the segments meet.
        g.setStroke(new LinearGradient(backX,backY,0,0,false,CycleMethod.NO_CYCLE,
                new Stop(0,color.deriveColor(0,1,1,0)),
                new Stop(.6,color.deriveColor(0,1,1,.35*alpha)),
                new Stop(1,color.deriveColor(0,1,.85,alpha))));
        g.setLineWidth(BOLT_CORE_WIDTH+.5);
        g.strokeLine(backX,backY,0,0);
        // A glow stretched along the flight path, so the shot reads as moving, not hovering.
        g.save();
        g.rotate(angle);
        g.scale(BOLT_GLOW_STRETCH,1);
        g.setFill(new RadialGradient(0,0,0,0,BOLT_GLOW_RADIUS,false,CycleMethod.NO_CYCLE,
                new Stop(0,color.deriveColor(0,1,1,.55*alpha)),
                new Stop(1,color.deriveColor(0,1,1,0))));
        g.fillOval(-BOLT_GLOW_RADIUS,-BOLT_GLOW_RADIUS,BOLT_GLOW_RADIUS*2,BOLT_GLOW_RADIUS*2);
        g.restore();
        g.setGlobalBlendMode(BlendMode.SRC_OVER);
        g.rotate(angle);
        double half=BOLT_WIDTH/2*stretch,lead=BOLT_LENGTH*.6*stretch,rear=BOLT_LENGTH*.4*stretch;
        double[] xs={lead,0,-rear,0},ys={0,half,0,-half};
        g.setFill(color.deriveColor(0,1,1,Math.min(1,alpha*1.12)));
        g.fillPolygon(xs,ys,4);
        if(b.penetrate()) {
            g.setStroke(Color.web("#ffffff",.85));g.setLineWidth(1.2);g.strokePolygon(xs,ys,4);
        }
        g.setStroke(color.deriveColor(0,1,1,.75));g.setLineWidth(1.2);
        g.strokeArc(-BOLT_ARC_RADIUS,-BOLT_ARC_RADIUS,BOLT_ARC_RADIUS*2,BOLT_ARC_RADIUS*2,-51,102,ArcType.OPEN);
        g.strokeArc(-BOLT_ARC_RADIUS,-BOLT_ARC_RADIUS,BOLT_ARC_RADIUS*2,BOLT_ARC_RADIUS*2,129,102,ArcType.OPEN);
        g.restore();
    }

    private void drawZone(Zone zone,double time) {
        Rect r=zone.bounds();
        Color color=switch(zone.terrain()) {case ICE->Color.web("#81bfc1");case LAVA->Color.web("#d28c70");case GRAVITY->Color.web("#aba4c4");};
        g.setFill(color);g.fillRoundRect(r.x(),r.y(),r.width(),r.height(),6,6);
        g.setStroke(color.darker());g.setLineWidth(2);
        if(zone.terrain()==Terrain.ICE) for(int i=0;i<3;i++) g.strokeLine(r.x()+10+i*16,r.y()+12,r.x()+18+i*16,r.y()+r.height()-12);
        else if(zone.terrain()==Terrain.LAVA) {
            for(int i=0;i<3;i++) g.strokePolyline(new double[]{r.x()+9,r.x()+25,r.x()+40,r.x()+55},new double[]{r.y()+15+i*16,r.y()+9+i*16,r.y()+19+i*16,r.y()+13+i*16},4);
        } else {
            double cx=r.x()+r.width()/2,cy=r.y()+r.height()/2;
            for(int i=0;i<3;i++) {double a=8+i*8+Math.sin(time*2)*2;g.strokeOval(cx-a,cy-a,a*2,a*2);}
        }
    }
    /** Fed from the BOUNCE event, whose player field is already a seat (CombatSystem.java:81). */
    public void addBounce(int player,double x,double y) {
        sparks.add(new Spark(player,x,y));
    }
    /**
     * A ring and five sparks in the owner's colour.
     *
     * <p>The spread comes from a deterministic pseudo-seed rather than Random, so the same battle
     * screenshots identically twice - which is what makes the probe images comparable.
     */
    private void drawSpark(Spark spark,double clock) {
        double fade=1-Math.max(0,Math.min(1,(clock-spark.startClock)/SPARK_LIFETIME_S));
        Color color=tankColor(spark.player);
        double seed=(spark.x*.13+spark.y*.07)%(Math.PI*2);
        g.save();
        g.setGlobalBlendMode(BlendMode.ADD);
        g.setStroke(color.deriveColor(0,1,1,.7*fade));
        g.setLineWidth(1.8*fade+.4);
        double radius=4+(1-fade)*34;
        g.strokeOval(spark.x-radius,spark.y-radius,radius*2,radius*2);
        g.setFill(color.deriveColor(0,1,1.15,.8*fade*fade));
        for(int i=0;i<5;i++) {
            double spread=seed+i*1.2566,distance=(1-fade)*26;
            g.fillOval(spark.x+Math.cos(spread)*distance-1.6,spark.y+Math.sin(spread)*distance-1.6,3.2,3.2);
        }
        g.restore();
    }
    public void drawDeployment(double x,double y,boolean valid) {
        g.save();g.scale(canvas.getWidth()/Rules.WIDTH,canvas.getHeight()/Rules.HEIGHT);
        Color color=valid?TEAL:PINK;
        g.setGlobalAlpha(.25);g.setFill(color);g.fillRect(x-16,y-16,32,32);
        g.setGlobalAlpha(1);g.setStroke(color);g.setLineWidth(2);g.strokeRect(x-16,y-16,32,32);
        g.strokeLine(x-24,y,x+24,y);g.strokeLine(x,y-24,x,y+24);
        g.setFill(color);g.fillOval(x-7,y-7,14,14);
        g.restore();
    }
    private void drawPickup(PickupView p,double time) {
        double y=p.y()+Math.sin(time*3)*2;
        double size=38,left=p.x()-size/2,top=y-size/2;
        // Card treatment follows the supplied skill sheet: dark outer stroke, pale inset plate,
        // saturated accent and a soft offset shadow that keeps the icon readable on the arena.
        g.setFill(Color.web("#071117",.5));g.fillRoundRect(left+3,top+4,size,size,8,8);
        g.setFill(Color.web("#f4f7ed"));g.fillRoundRect(left,top,size,size,8,8);
        g.setStroke(INK);g.setLineWidth(2.5);g.strokeRoundRect(left,top,size,size,8,8);
        g.setStroke(Color.web("#d6e4d6"));g.setLineWidth(1);g.strokeRoundRect(left+3,top+3,size-6,size-6,6,6);
        drawSkillGlyph(p.item(),p.x(),y,1);
    }

    /**
     * The item's icon, at {@code scale} about {@code (x,y)}.
     *
     * <p>Every coordinate in the body is relative to that point: the glyph is a fistful of literal
     * offsets, and translating once at the top is what lets the pickup-flight reuse it at 62%
     * without a second copy of the artwork.
     */
    private void drawSkillGlyph(Item item,double x,double y,double scale) {
        g.save();g.translate(x,y);g.scale(scale,scale);
        Color green=Color.web("#76ed91"),cyan=Color.web("#20cde0"),pink=Color.web("#f34fa5"),gold=Color.web("#ffc94e");
        switch(item) {
            case REPAIR -> {
                g.setFill(green);g.fillRoundRect(-6,-15,12,30,5,5);g.fillRoundRect(-15,-6,30,12,5,5);
                g.setStroke(Color.web("#1b6136"));g.setLineWidth(2.5);g.strokeRoundRect(-6,-15,12,30,5,5);g.strokeRoundRect(-15,-6,30,12,5,5);
            }
            case SHIELD -> {
                g.setFill(Color.web("#8ce8f5"));g.setStroke(Color.web("#08728e"));g.setLineWidth(3);
                g.beginPath();g.moveTo(0,-15);g.lineTo(13,-9);g.lineTo(10,8);g.lineTo(0,16);g.lineTo(-10,8);g.lineTo(-13,-9);g.closePath();g.fill();g.stroke();
                g.setStroke(Color.web("#d9fbff"));g.setLineWidth(1.5);g.strokeLine(0,-11,0,11);g.strokeLine(-9,-5,9,-5);
            }
            case RAPID -> {
                g.setFill(Color.web("#fff0a8"));g.setStroke(Color.web("#8b5312"));g.setLineWidth(3);
                g.beginPath();g.moveTo(4,-16);g.lineTo(-10,2);g.lineTo(-1,2);g.lineTo(-5,16);g.lineTo(11,-4);g.lineTo(2,-4);g.closePath();g.fill();g.stroke();
                g.setStroke(Color.web("#d88718"));g.setLineWidth(2);g.strokeLine(13,-10,17,-6);g.strokeLine(13,0,17,4);g.strokeLine(13,10,17,14);
            }
            case TURRET -> {
                g.setFill(Color.web("#e8eee8"));g.fillOval(-12,-12,24,24);g.setStroke(Color.web("#26302a"));g.setLineWidth(3);g.strokeOval(-12,-12,24,24);
                g.setFill(gold);g.fillOval(-7,-7,14,14);g.setStroke(Color.web("#26302a"));g.setLineWidth(2);g.strokeOval(-7,-7,14,14);
                g.setStroke(Color.web("#26302a"));g.setLineWidth(4);g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);g.strokeLine(0,0,13,0);g.strokeLine(0,0,0,-13);
            }
            case SCATTER -> {
                g.setStroke(pink);g.setLineWidth(2.5);g.strokeLine(0,0,-12,-12);g.strokeLine(0,0,12,-12);g.strokeLine(0,0,-12,12);g.strokeLine(0,0,12,12);
                g.setFill(Color.web("#fff2fb"));for(int dx:new int[]{-12,12}) for(int dy:new int[]{-12,12}) {g.fillOval(dx-4,dy-4,8,8);g.setStroke(pink);g.strokeOval(dx-4,dy-4,8,8);}
                g.setFill(pink);g.fillOval(-4,-4,8,8);
            }
            case AIM -> {
                g.setStroke(cyan);g.setLineWidth(2);g.strokeOval(-13,-13,26,26);g.strokeOval(-7,-7,14,14);g.strokeLine(-16,0,16,0);g.strokeLine(0,-16,0,16);
                g.setFill(cyan);g.fillOval(-4,-4,8,8);
            }
            case PENETRATE -> {
                g.setFill(cyan);g.setStroke(Color.web("#08728e"));g.setLineWidth(2.5);g.fillOval(-12,-7,14,14);g.strokeOval(-12,-7,14,14);g.strokeLine(2,0,16,0);g.strokeLine(9,-6,16,0);g.strokeLine(9,6,16,0);
            }
        }
        g.restore();
    }

    private static final double PICKUP_FLIGHT_S=.35,PICKUP_ARC=30;
    /** How long the mime of what a previewed item will do runs, after the glyph has landed. */
    private static final double PICKUP_PREVIEW_S=.4;
    /**
     * A pickup effect's whole life: the flight plus the preview that follows it. One dial for both
     * halves of the animation - it is the hard expiry (through lifetimeFactor) and the window
     * previewItem draws in - and written as the sum so that lengthening either part moves the
     * expiry with it instead of leaving the effect to vanish mid-mime.
     */
    static final double PICKUP_TTL_S=PICKUP_FLIGHT_S+PICKUP_PREVIEW_S;
    /** Only these three need a preview: their effect is not visible at the moment of pickup. */
    private static final java.util.Set<GameEventType> PREVIEWED=java.util.EnumSet.of(
        GameEventType.ITEM_RAPID,GameEventType.ITEM_SCATTER,GameEventType.ITEM_PENETRATE);

    /** The item whose pickup this event reports, or null when the event is not a pickup. */
    static Item itemForEvent(GameEventType kind) {
        for(Item item:Item.values()) if(item.event==kind) return item;
        return null;
    }

    private void drawPickupFlight(Snapshot s,Fx fx,double age) {
        Item item=itemForEvent(fx.kind);
        if(item==null) return;
        // Target re-resolved every frame so the glyph homes in on a moving collector.
        TankView collector=null;
        for(TankView t:s.tanks()) if(t.player()==fx.player) {collector=t;break;}
        double tx=collector!=null?collector.x():fx.x,ty=collector!=null?collector.y():fx.y;
        double k=clamp01(age/PICKUP_FLIGHT_S),eased=1-(1-k)*(1-k);
        Color color=tankColor(fx.player);
        if(age<.22) {
            g.save();g.setGlobalBlendMode(BlendMode.ADD);
            for(int i=0;i<5;i++) {
                double spread=-Math.PI/2+(i/4.0-.5)*2.2,distance=(1-clamp01(age/.22))*16;
                g.setFill(color.deriveColor(0,1,1.2,.9*(1-clamp01(age/.22))*(1-clamp01(age/.22))));
                g.fillOval(fx.x+Math.cos(spread)*distance-1.4,fx.y+Math.sin(spread)*distance-1.4,2.8,2.8);
            }
            g.restore();
        }
        if(k<1) {
            double gx=fx.x+(tx-fx.x)*eased,gy=fx.y+(ty-fx.y)*eased-PICKUP_ARC*Math.sin(Math.PI*eased);
            g.save();g.setGlobalAlpha(k>.82?(1-k)/.18:1);
            drawSkillGlyph(item,gx,gy,(1-.38*k));
            g.restore();
        } else {
            // Its own save/restore, like every other ring in this file: ring sets stroke, width and
            // alpha, and the frame after this one must not inherit them.
            g.save();
            ring(tx,ty,clamp01((age-PICKUP_FLIGHT_S)/.25),6,20,color,.8,2.2);
            g.restore();
            // Falls back to due east only when the collector has already left the field, where there
            // is no heading to honour - 0 is a heading, NaN would paint nothing at all.
            if(PREVIEWED.contains(fx.kind))
                previewItem(item,tx,ty,collector!=null?collector.angle():0,age-PICKUP_FLIGHT_S);
        }
    }

    /**
     * A {@link #PICKUP_PREVIEW_S}-long mime of what the item is about to do, for the three whose
     * effect lands later.
     *
     * <p>Everything grows from the muzzle, so it needs the collector's heading: the barrel is where
     * "rapid fires twice" and "scatter opens into four" have to start from. Pinned due east, the
     * mime of a tank facing west plays out of the back of its hull and reads as a stray shot.
     *
     * <p>{@code angleDegrees} is degrees - everything the field carries is (TankView.angle
     * included) - so it is converted once, here, before any cos/sin sees it.
     */
    private void previewItem(Item item,double x,double y,double angleDegrees,double t) {
        if(t<0||t>PICKUP_PREVIEW_S) return;
        double radians=Math.toRadians(angleDegrees);
        double mx=x+Math.cos(radians)*MUZZLE_OFFSET,my=y+Math.sin(radians)*MUZZLE_OFFSET;
        g.save();g.setGlobalBlendMode(BlendMode.ADD);
        switch(item) {
            case RAPID -> {for(int i=0;i<2;i++) {
                double a=t-i*.14,f=clamp01(1-a/.1);
                if(a>0&&f>0) {g.setStroke(Color.web("#ffd24e",.85*f));g.setLineWidth(2.2);
                    for(int p=0;p<3;p++) {double an=radians+p*2.1-.5;
                        g.strokeLine(mx,my,mx+Math.cos(an)*11,my+Math.sin(an)*11);}}}}
            case SCATTER -> {double f=clamp01(1-t/.3);
                if(f>0) {g.setStroke(Color.web("#f34fa5",.8*f));g.setLineWidth(2);
                    for(double deg:new double[]{-60,-20,20,60}) {
                        double an=radians+Math.toRadians(deg),len=26*(1-(1-clamp01(t/.3))*(1-clamp01(t/.3)));
                        g.strokeLine(mx,my,mx+Math.cos(an)*len,my+Math.sin(an)*len);}}}
            // A through-line, not a flash: the shot enters behind the hull and leaves in front of
            // it, so it stays anchored on the hull's centre and runs the heading - 40px behind to
            // 46px ahead, the far end stretching as the mime plays out. Anchoring it on the muzzle
            // instead would push the "it passes through you" read off the tank entirely.
            case PENETRATE -> {double f=clamp01(1-t/.3);
                if(f>0) {g.setStroke(Color.web("#20cde0",.9*f));g.setLineWidth(3);
                    double reach=46+30*(1-(1-clamp01(t/.3))*(1-clamp01(t/.3)));
                    g.strokeLine(x-Math.cos(radians)*40,y-Math.sin(radians)*40,
                                 x+Math.cos(radians)*reach,y+Math.sin(radians)*reach);}}
            default -> { }
        }
        g.restore();
    }

    /**
     * How long a coin lives, measured from the frame the pickup raised it.
     *
     * <p>Its own dial, not a slice of EFFECT_LIFETIME_S: the coin's life is the flight plus the
     * "+1" that follows it, and lifetimeFactor's entry is written as that same quotient. Under the
     * shared .6 factor it expired at 0.72s while the text it raises runs to 0.9s - so the "+1" was
     * thrown away mid-fade, still at alpha .3, instead of fading out. One dial, so a longer "+1"
     * moves the expiry with it rather than being decapitated by a number set elsewhere.
     */
    static final double COIN_TTL_S=.9;
    /** A coin's flight, its arc height, and how long the burn's own patch stays lit. */
    private static final double COIN_FLIGHT_S=.3,COIN_ARC=22,LAVA_TTL_S=.35;
    /** The "+1" bubble's own length: the tail of a coin's life, not a second dial beside it. */
    private static final double COIN_TEXT_S=COIN_TTL_S-COIN_FLIGHT_S;

    /**
     * The coin leaving a dead tank for whoever picked it up.
     *
     * <p>The collector is re-resolved from the snapshot every frame rather than remembered from the
     * pickup event, so the coin homes in on a target that is still driving. A collector already gone
     * leaves the event's own coordinates, which is where the coin has to land rather than nowhere.
     */
    private void drawCoin(Snapshot s,Fx fx,double age) {
        TankView collector=null;
        for(TankView t:s.tanks()) if(t.player()==fx.player) {collector=t;break;}
        double tx=collector!=null?collector.x():fx.x,ty=collector!=null?collector.y():fx.y;
        double k=clamp01(age/COIN_FLIGHT_S),eased=1-(1-k)*(1-k);
        if(k<1) {
            double cx=fx.x+(tx-fx.x)*eased,cy=fx.y+(ty-fx.y)*eased-COIN_ARC*Math.sin(Math.PI*eased);
            g.save();g.setGlobalBlendMode(BlendMode.ADD);
            for(int i=1;i<=3;i++) {   // a short trail, so a fast coin still reads as moving
                double kk=clamp01(k-i*.07),px=fx.x+(tx-fx.x)*(1-(1-kk)*(1-kk));
                double py=fx.y+(ty-fx.y)*(1-(1-kk)*(1-kk))-COIN_ARC*Math.sin(Math.PI*kk);
                g.setFill(Color.web("#ffce50",.3*(1-k)/i));
                g.fillOval(px-6*(1-i*.16),py-6*(1-i*.16),12*(1-i*.16),12*(1-i*.16));
            }
            g.restore();
            drawCoinGlyph(cx,cy,1-.4*k);
        } else {
            // Its own save/restore, like every other ring in this file: ring sets stroke, width and
            // alpha, and the frame after this one must not inherit them.
            g.save();
            ring(tx,ty,clamp01((age-COIN_FLIGHT_S)/.2),4,16,Color.web("#ffce50"),.8,2);
            g.restore();
            double textFade=clamp01((age-COIN_FLIGHT_S)/COIN_TEXT_S);
            if(textFade<1) {
                g.setGlobalAlpha(1-textFade);
                g.setFill(Color.web("#ffce50"));
                g.setFont(Font.font("Microsoft YaHei",FontWeight.BOLD,13));
                g.fillText("+1",tx-8,ty-28-14*(1-(1-textFade)*(1-textFade)));
                g.setGlobalAlpha(1);
            }
        }
    }

    /** The coin itself, at {@code scale} about {@code (x,y)} - the same glyph the drop on the floor wears. */
    private void drawCoinGlyph(double x,double y,double scale) {
        g.setFill(Color.web("#d88e08"));g.fillOval(x-8*scale,y-7*scale,16*scale,16*scale);
        g.setFill(Color.web("#ffce50"));g.fillOval(x-7*scale,y-8*scale,14*scale,14*scale);
        g.setStroke(Color.web("#fff2b8"));g.setLineWidth(1.6*scale);
        g.strokeLine(x,y-6*scale,x,y+2*scale);
    }

    private void drawLava(Snapshot s,Fx fx,double age) {
        if(age>LAVA_TTL_S) return;
        double fade=1-clamp01(age/LAVA_TTL_S);
        g.save();g.setGlobalBlendMode(BlendMode.ADD);
        // The patch under the tank brightens, so "I am standing in it" is distinguishable from
        // "there is fire nearby" - which a few flying sparks on their own cannot say.
        g.setGlobalAlpha(.55*fade);g.setFill(Color.web("#ff9646",.8));
        // The patch's own ramp reads LAVA_TTL_S rather than a second .35: the same dial then moves
        // the patch, the tint and the hard expiry together.
        g.fillOval(fx.x-26-8*(1-(1-clamp01(age/LAVA_TTL_S))*(1-clamp01(age/LAVA_TTL_S))),fx.y+5,60,18);
        g.setGlobalAlpha(1);
        for(int i=0;i<6;i++) {
            double a=-Math.PI/2+(i/5.0-.5)*1.3,distance=fade*18;
            g.setFill(Color.web("#ff9646",.9*fade*fade));
            g.fillOval(fx.x+Math.cos(a)*distance-1.5,fx.y+12+Math.sin(a)*distance-1.5,3,3);
        }
        g.restore();
        // The body tint goes on last so it sits over the tank, not under it.
        double tint=clamp01(1-age/.16)*.7;
        if(tint>0) {
            g.save();g.setGlobalBlendMode(BlendMode.ADD);
            g.setFill(Color.web("#ff6a3c",tint));
            g.fillRoundRect(fx.x-18,fx.y-17,36,34,3,3);
            g.restore();
        }
    }

    private void drawTurret(TurretView turret,double clock) {
        Color accent=turret.owner()==0?TEAL:Color.web("#d99816");
        drawTurretBody(turret.x(),turret.y(),turret.angle(),accent,clock);
        double life=Math.max(0,Math.min(1,turret.remainingMs()/12000.));
        g.setStroke(Color.web("#91a49a",.7));g.setLineWidth(2);g.strokeArc(turret.x()-20,turret.y()-20,40,40,90,-360*life,javafx.scene.shape.ArcType.OPEN);
    }
    private void drawTurretBody(double x,double y,double angle,Color accent,double clock) {
        g.save();g.translate(x,y);
        g.setFill(Color.web("#071117",.55));g.fillOval(-17,-12,38,32);
        g.setStroke(Color.web("#92aaa0"));g.setLineWidth(3);
        for(int i=0;i<3;i++) {double a=Math.toRadians(i*120+90);g.strokeLine(Math.cos(a)*8,Math.sin(a)*8,Math.cos(a)*20,Math.sin(a)*20);}
        g.setFill(Color.web("#324a48"));g.fillPolygon(new double[]{-15,0,15,11,-11},new double[]{0,-14,0,13,13},5);
        g.setStroke(INK);g.setLineWidth(2);g.strokePolygon(new double[]{-15,0,15,11,-11},new double[]{0,-14,0,13,13},5);
        g.rotate(angle);
        g.setStroke(INK);g.setLineWidth(9);g.setLineCap(javafx.scene.shape.StrokeLineCap.ROUND);g.strokeLine(-2,0,27,0);
        g.setStroke(Color.web("#dce9df"));g.setLineWidth(4);g.strokeLine(1,-1,27,-1);
        g.setStroke(accent);g.setLineWidth(3);g.strokeLine(19,-1,29,-1);
        g.setFill(Color.web("#708b80"));g.fillRoundRect(-10,-9,20,18,6,6);
        g.setStroke(INK);g.setLineWidth(2);g.strokeRoundRect(-10,-9,20,18,6,6);
        g.setFill(accent);g.fillOval(-4,-4,8,8);
        g.setGlobalAlpha(.45+.25*Math.sin(clock*5));g.setStroke(accent);g.setLineWidth(1.5);g.strokeOval(-13,-13,26,26);
        g.restore();
    }

    private record TraceHit(double time,int nx,int ny) {}
    /** The two passes a laser is drawn in: a wide translucent halo, then a bright core. */
    private static final double AIM_GLOW_WIDTH=7.5,AIM_CORE_WIDTH=1.7,AIM_GLOW_ALPHA=.22;
    /** How far the far end is allowed to fall off, and the solid stub at the barrel. */
    private static final double AIM_GLOW_TAPER=.45,AIM_MUZZLE_LENGTH=28,AIM_MUZZLE_WIDTH=2.4;
    /** A shot's streak: seconds of its own travel, so the bolt is the same shape at any speed. */
    private static final double BOLT_LENGTH=19,BOLT_WIDTH=6.2,BOLT_GLOW_STRETCH=2.6,BOLT_ARC_RADIUS=7.5;
    private static final double BOLT_CORE_WIDTH=2.2,BOLT_GLOW_RADIUS=7.5;
    /** A penetrating shot has no colour of its own any more, so it has to be longer instead. */
    private static final double BOLT_PENETRATE_SCALE=1.8,BOLT_PENETRATE_TAIL_SCALE=1.7;
    /** How a shot grows with every bounce. Bouncing is both more dangerous - it can come around
     *  cover - and worth more to its owner, so "brighter" is the honest direction for the cue. */
    private static final double BOLT_ALPHA_BASE=.85,BOLT_ALPHA_PER_BOUNCE=.03;
    private static final double BOLT_TAIL_SECONDS=.105,BOLT_TAIL_PER_BOUNCE=.006;
    /** How the beam gives way along its own length. f=0 is the muzzle, f=1 the far end. */
    private static final double AIM_ALPHA_FALLOFF=.92;
    private void drawAimAssist(Snapshot snapshot,double clock) {
        // Every human seat, not just P1. Both duel players pick up the same supplies, and this beam
        // is the pickup's entire visible effect - to P2 it looked like the item had done nothing.
        for(TankView player:snapshot.tanks()) {
            if(player.player()<0||player.hp()<=0||player.aimMs()<=0) continue;
            double[] offsets=player.scatterMs()>0?new double[]{-60,-20,20,60}:new double[]{0};
            for(double offset:offsets)
                laser(player.x(),player.y(),player.angle()+offset,player.penetrateMs()>0,
                        tankColor(player.player()),snapshot.walls(),clock);
        }
    }
    static TankView localAimTank(Snapshot snapshot,int localPlayer) {
        return snapshot.tanks().stream()
                .filter(t->t.player()==localPlayer&&t.hp()>0&&t.aimMs()>0)
                .findFirst().orElse(null);
    }
    /**
     * A beam along the shot's path that gives way as it goes.
     *
     * <p>Full strength at the muzzle, 8% at the far end, so a long lane stops covering whatever is
     * standing in it. Struck segment by segment rather than as one path: the alpha has to follow arc
     * length, and a single gradient only follows a straight line. The joins are seamless because
     * each segment's ends carry the same two values, and there are never more than six of them.
     */
    private void laser(double tankX,double tankY,double angle,boolean penetrate,Color beam,
                       List<Rect> walls,double clock) {
        List<double[]> path=tracePath(tankX,tankY,angle,penetrate,walls);
        if(path.size()<2) return;
        double total=0;
        for(int i=1;i<path.size();i++)
            total+=Math.hypot(path.get(i)[0]-path.get(i-1)[0],path.get(i)[1]-path.get(i-1)[1]);
        if(total<1) return;
        g.save();
        g.setLineDashes();
        g.setGlobalBlendMode(BlendMode.ADD);
        // BUTT, not the default SQUARE. Every segment is stroked on its own so it can carry its own
        // gradient, and square caps would overlap at each bounce, doubling the alpha right there and
        // reading as a string of beads rather than one beam. An earlier iteration of this laser hit
        // exactly that, and the fix then was to stop stroking per segment - which is not open to us
        // now, because per-segment is what carries the fade. So the cap does the work instead.
        g.setLineCap(StrokeLineCap.BUTT);
        double along=0;
        for(int i=1;i<path.size();i++) {
            double[] a=path.get(i-1),b=path.get(i);
            double length=Math.hypot(b[0]-a[0],b[1]-a[1]);
            if(length<1e-6) continue;
            beamSegment(a[0],a[1],b[0],b[1],beam,fraction(along,total),fraction(along+length,total));
            along+=length;
        }
        muzzle(path,beam);
        marker(path.get(path.size()-1),walls,clock,beam);
        g.restore();
    }
    /**
     * One aim segment: halo under core, each fading between its own two fractions of the path.
     *
     * <p>The halo's width is taken from the segment's near end rather than interpolated, because a
     * stroked line has one width. At six segments the steps are a fraction of a pixel, which is why
     * this is allowed to be a step at all.
     */
    private void beamSegment(double x0,double y0,double x1,double y1,Color beam,double f0,double f1) {
        double halo0=AIM_GLOW_ALPHA*aimAlpha(f0)*aimAlpha(f0);
        double halo1=AIM_GLOW_ALPHA*aimAlpha(f1)*aimAlpha(f1);
        g.setStroke(new LinearGradient(x0,y0,x1,y1,false,CycleMethod.NO_CYCLE,
                new Stop(0,beam.deriveColor(0,1,1,halo0)),new Stop(1,beam.deriveColor(0,1,1,halo1))));
        g.setLineWidth(AIM_GLOW_WIDTH*(1-AIM_GLOW_TAPER*f0));
        g.strokeLine(x0,y0,x1,y1);
        g.setStroke(new LinearGradient(x0,y0,x1,y1,false,CycleMethod.NO_CYCLE,
                new Stop(0,beam.deriveColor(0,1,1,aimAlpha(f0))),
                new Stop(1,beam.deriveColor(0,1,1,aimAlpha(f1)))));
        g.setLineWidth(AIM_CORE_WIDTH);
        g.strokeLine(x0,y0,x1,y1);
    }
    /** The energy leaving the barrel: a solid stub, so the beam has an unambiguous origin. */
    private void muzzle(List<double[]> path,Color beam) {
        double[] a=path.get(0),b=path.get(1);
        double dx=b[0]-a[0],dy=b[1]-a[1],length=Math.hypot(dx,dy);
        if(length<1e-6) return;
        double reach=Math.min(AIM_MUZZLE_LENGTH,length);
        g.setStroke(Color.web("#ffffeb",.85));
        g.setLineWidth(AIM_MUZZLE_WIDTH);
        g.strokeLine(a[0],a[1],a[0]+dx/length*reach,a[1]+dy/length*reach);
    }
    /**
     * Where the shot lands.
     *
     * <p>Deliberately not faded along with the beam: the impact point is the most useful thing the
     * beam carries, and it sits at the end that faded hardest. Dimmer when the path left the arena
     * instead of meeting a wall, because then it only says "this one missed".
     */
    private void marker(double[] end,List<Rect> walls,double clock,Color beam) {
        double alpha=landsOnWall(end,walls)?.9:.55;
        double breathe=.5+.5*Math.sin(clock*4);
        g.setStroke(beam.deriveColor(0,1,1.15,alpha));
        g.setLineWidth(1.5);
        g.strokePolygon(new double[]{end[0],end[0]+6,end[0],end[0]-6},
                new double[]{end[1]-6,end[1],end[1]+6,end[1]},4);
        g.setFill(beam.deriveColor(0,1,1.2,alpha*(.35+.5*breathe)));
        g.fillOval(end[0]-2,end[1]-2,4,4);
    }
    /**
     * Where a point sits along the path, as a fraction in [0,1].
     *
     * <p>aimAlpha clamps too, so this is belt and braces - kept because a fraction outside [0,1]
     * is a bug in the loop above, and silently letting aimAlpha absorb it would hide that.
     */
    private static double fraction(double along,double total) {
        return Math.max(0,Math.min(1,along/total));
    }
    /** Whether an endpoint is sitting against a wall, within the same clearance tracePath uses. */
    private static boolean landsOnWall(double[] point,List<Rect> walls) {
        for(Rect wall:walls)
            if(point[0]>=wall.x()-Rules.BULLET_HALF-.5&&point[0]<=wall.x()+wall.width()+Rules.BULLET_HALF+.5
                    &&point[1]>=wall.y()-Rules.BULLET_HALF-.5&&point[1]<=wall.y()+wall.height()+Rules.BULLET_HALF+.5)
                return true;
        return false;
    }
    /** The polyline the shot would follow, muzzle to its last wall or the arena edge. */
    private List<double[]> tracePath(double tankX,double tankY,double angle,boolean penetrate,List<Rect> walls) {
        List<double[]> points=new ArrayList<>();
        double radians=Math.toRadians(angle),vx=Math.cos(radians),vy=Math.sin(radians);
        double muzzle=(Rules.TANK_HALF+Rules.BULLET_HALF+2)/Math.max(Math.abs(vx),Math.abs(vy));
        double x=tankX+vx*muzzle,y=tankY+vy*muzzle,remaining=900;
        points.add(new double[]{x,y});
        for(int bounce=0;bounce<(penetrate?1:5)&&remaining>1;bounce++) {
            TraceHit best=null;
            if(!penetrate) for(Rect wall:walls) {
                TraceHit hit=rayHit(x,y,vx*remaining,vy*remaining,wall);
                if(hit!=null&&(best==null||hit.time()<best.time())) best=hit;
            }
            if(best==null) {
                double edge=Math.min(vx>0?(Rules.WIDTH-Rules.BULLET_HALF-x)/vx: vx<0?(Rules.BULLET_HALF-x)/vx:Double.POSITIVE_INFINITY,
                        vy>0?(Rules.HEIGHT-Rules.BULLET_HALF-y)/vy:vy<0?(Rules.BULLET_HALF-y)/vy:Double.POSITIVE_INFINITY);
                double distance=Math.max(0,Math.min(remaining,edge));
                points.add(new double[]{x+vx*distance,y+vy*distance});
                break;
            }
            double distance=remaining*best.time(),endX=x+vx*distance,endY=y+vy*distance;
            points.add(new double[]{endX,endY});remaining-=distance;
            if(best.nx()!=0) vx=-vx;if(best.ny()!=0) vy=-vy;
            x=endX+vx*.05;y=endY+vy*.05;
        }
        return points;
    }
    private TraceHit rayHit(double x,double y,double dx,double dy,Rect wall) {
        double left=wall.x()-Rules.BULLET_HALF,right=wall.x()+wall.width()+Rules.BULLET_HALF;
        double top=wall.y()-Rules.BULLET_HALF,bottom=wall.y()+wall.height()+Rules.BULLET_HALF;
        if(Math.abs(dx)<1e-12&&(x<=left||x>=right)||Math.abs(dy)<1e-12&&(y<=top||y>=bottom)) return null;
        double tx0=dx==0?Double.NEGATIVE_INFINITY:Math.min((left-x)/dx,(right-x)/dx);
        double tx1=dx==0?Double.POSITIVE_INFINITY:Math.max((left-x)/dx,(right-x)/dx);
        double ty0=dy==0?Double.NEGATIVE_INFINITY:Math.min((top-y)/dy,(bottom-y)/dy);
        double ty1=dy==0?Double.POSITIVE_INFINITY:Math.max((top-y)/dy,(bottom-y)/dy);
        double entry=Math.max(tx0,ty0),exit=Math.min(tx1,ty1);
        if(entry>exit||exit<0||entry>1||entry<1e-7) return null;
        int nx=tx0>=ty0-1e-8?(dx>0?-1:1):0,ny=ty0>=tx0-1e-8?(dy>0?-1:1):0;
        return new TraceHit(entry,nx,ny);
    }
    /** 0.85 at a fresh shot up to 1.0 at five bounces (the sixth kills it, see Rules.MAX_BOUNCES). */
    static double bulletAlpha(int bounces) {
        return Math.min(1,BOLT_ALPHA_BASE+bounces*BOLT_ALPHA_PER_BOUNCE);
    }
    /** Tail length in seconds of the shot's own travel, so it is the same at any bullet speed. */
    static double bulletTailSeconds(int bounces) {
        return BOLT_TAIL_SECONDS+bounces*BOLT_TAIL_PER_BOUNCE;
    }
    /**
     * The beam's brightness at arc-length fraction f: 1 at the muzzle, .08 at the far end.
     *
     * <p>Total on purpose: the end of the last segment can land a hair past 1 in floating point,
     * and a negative alpha would punch a hole in the end of the beam. Clamping here means no caller
     * has to remember to.
     */
    static double aimAlpha(double fraction) {
        return Math.max(0,Math.min(1,1-AIM_ALPHA_FALLOFF*fraction));
    }
    /**
     * A bullet names a tank, not a seat, and the two only part company once the enemy joins the
     * same id counter. Same reduction the model itself does at CombatSystem.java:81, including the
     * -1 fallback: an owner that is gone was an enemy, because player tanks never leave the list
     * (initializeWave only removes the AI, TankGameModel.java:86).
     */
    static int ownerSeat(int ownerId,List<TankView> tanks) {
        return tanks.stream().filter(t->t.id()==ownerId).mapToInt(TankView::player).findFirst().orElse(-1);
    }
    public static void tank(GraphicsContext g,double x,double y,double angle,TankType type,Color color,double scale) {
        g.save();g.translate(x,y);g.rotate(angle);g.scale(scale,scale);
        double body=switch(type) {
            case HEAVY -> 32;
            case MEDIC -> 30;
            case RESEARCH -> 28;
            case SCOUT -> 25;
            default -> 29;
        };
        g.setFill(Color.web("#10251b",.5));g.fillRoundRect(-body/2+3,-14+4,body,28,2,2);
        g.setFill(INK);g.fillRoundRect(-18,-17,36,8,2,2);g.fillRoundRect(-18,9,36,8,2,2);
        g.setStroke(Color.web("#a5b5a1"));g.setLineWidth(1.5);
        for(int i=-14;i<18;i+=6) {g.strokeLine(i,-16,i,-10);g.strokeLine(i,10,i,16);}
        g.setFill(Color.web("#dce7d9"));
        g.fillPolygon(new double[]{-body/2+3,body/2-4,body/2,-body/2},new double[]{-11,-11,7,11},4);
        g.setStroke(INK);g.setLineWidth(1.5);g.strokePolygon(new double[]{-body/2+3,body/2-4,body/2,-body/2},new double[]{-11,-11,7,11},4);
        g.setFill(color);g.fillRect(-body/2+1,-10,5,20);g.fillRect(-body/2+3,-9,body-6,3);
        g.setStroke(INK);g.setLineWidth(8);g.strokeLine(0,0,26,0);
        g.setStroke(Color.web("#e1eddf"));g.setLineWidth(4);g.strokeLine(0,-1,26,-1);
        g.setStroke(color);g.strokeLine(20,-1,26,-1);
        g.setFill(Color.web("#779681"));g.fillRoundRect(-10,-9,20,18,4,4);
        g.setStroke(Color.web("#172b22"));g.setLineWidth(1);g.strokeRoundRect(-10,-9,20,18,4,4);
        g.setFill(Color.web("#eff7e8"));g.fillRoundRect(-7,-7,14,9,2,2);
        g.setFill(color);g.fillRect(-3,-4,6,5);
        if(type==TankType.ENGINEER) {
            g.setStroke(Color.WHITE);g.setLineWidth(2);g.strokeLine(-11,-3,-11,3);g.strokeLine(-14,0,-8,0);
        } else if(type==TankType.MEDIC) {
            g.setStroke(Color.web("#c94850"));g.setLineWidth(2.5);g.strokeLine(-12,-3,-12,3);g.strokeLine(-15,0,-9,0);
        } else if(type==TankType.RESEARCH) {
            g.setFill(Color.web("#78cbb5"));
            g.fillOval(-13,-3,4,4);g.fillOval(-8,-3,4,4);g.fillOval(-3,-3,4,4);
        }
        g.restore();
    }
}
