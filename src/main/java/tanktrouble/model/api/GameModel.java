package tanktrouble.model.api;

import java.util.List;
import tanktrouble.model.data.GameData.*;

public interface GameModel {
    void selectMode(Mode mode);
    void selectTank(int player,TankType type);
    void setTerrainEnabled(boolean enabled);
    void startBattle(long seed);
    void startBattle(long seed,int players);
    void tick(long deltaMs);
    void handleInput(int player,Action action,boolean active);
    void clearInput();
    void pause();
    void resume();
    void openShop();
    boolean canPurchase(Item item);
    boolean purchase(Item item);
    boolean beginDeployment();
    boolean canDeployTurret(double x,double y);
    boolean deployTurret(double x,double y);
    void cancelDeployment();
    boolean chooseUpgrade(Upgrade upgrade);
    void restart();
    void home();
    void finishRun();
    void exit();
    Snapshot snapshot();
    /** Events recorded since the previous call, in the order they occurred. */
    List<GameEvent> drainEvents();
}
