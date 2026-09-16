package tanktrouble.controller;

import java.util.*;
import java.util.function.BooleanSupplier;
import javafx.scene.Scene;
import javafx.scene.input.*;
import tanktrouble.model.api.GameModel;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;

public final class GameController {
    private GameModel model;
    private final Set<KeyCode> pressed=EnumSet.noneOf(KeyCode.class);
    private long previous,accumulator;
    private int localPlayer;
    public GameController(GameModel model) {this.model=model;}
    public void useModel(GameModel model,int localPlayer) {clearHeldActions();this.model=model;this.localPlayer=localPlayer;pressed.clear();resetClock();}
    public void clearHeldActions() {for(Action action:Action.values()) model.handleInput(localPlayer,action,false);}
    public void attach(Scene scene,Runnable onStateChanged) {
        attach(scene,onStateChanged,()->true);
    }
    public void attach(Scene scene,Runnable onStateChanged,BooleanSupplier inputEnabled) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED,event->{
            boolean first=pressed.add(event.getCode());
            if(event.isConsumed() || !inputEnabled.getAsBoolean()) return;
            State state=model.snapshot().state();
            if(first && event.getCode()==KeyCode.ESCAPE) {
                if(state==State.RUNNING) model.pause();
                else if(state==State.PAUSED || state==State.SHOP) model.resume();
                else if(state==State.DEPLOYING) model.cancelDeployment();
                onStateChanged.run();event.consume();return;
            }
            if(first && event.getCode()==KeyCode.B && state==State.RUNNING) {model.openShop();onStateChanged.run();event.consume();return;}
            if(first && event.getCode()==KeyCode.T && state==State.RUNNING) {model.beginDeployment();onStateChanged.run();event.consume();return;}
            if(state==State.RUNNING && mapKey(event.getCode(),true,first)) event.consume();
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED,event->{
            pressed.remove(event.getCode());
            if(model.snapshot().state()==State.RUNNING && mapKey(event.getCode(),false,true)) event.consume();
            else mapKey(event.getCode(),false,true);
        });
    }
    private boolean mapKey(KeyCode key,boolean active,boolean first) {
        int player=switch(key) {case UP,DOWN,LEFT,RIGHT,ENTER,SHIFT -> 1;default -> 0;};
        Action action=switch(key) {
            case W,UP -> Action.FORWARD;case S,DOWN -> Action.BACKWARD;
            case A,LEFT -> Action.LEFT;case D,RIGHT -> Action.RIGHT;
            case SPACE,ENTER -> Action.FIRE;case Q,SHIFT -> Action.PULSE;default -> null;
        };
        if(action==null) return false;
        Mode mode=model.snapshot().mode();
        if(mode==Mode.MULTI||mode==Mode.COOP) player=localPlayer;
        else if(player==1 && mode!=Mode.DUEL) player=0;
        if(action!=Action.PULSE || first) model.handleInput(player,action,active);
        return true;
    }
    public void frame(long nanos) {
        if(previous==0) previous=nanos;
        long delta=Math.min((long)Rules.MAX_FRAME_MS*1_000_000,Math.max(0,nanos-previous));previous=nanos;
        if(model.snapshot().state()!=State.RUNNING) {accumulator=0;return;}
        accumulator+=delta;
        while(accumulator>=Rules.STEP_MS*1_000_000L) {model.tick(Rules.STEP_MS);accumulator-=Rules.STEP_MS*1_000_000L;}
    }
    public void resetClock() {previous=0;accumulator=0;model.clearInput();}
    public void loseFocus() {pressed.clear();resetClock();model.pause();}
}
