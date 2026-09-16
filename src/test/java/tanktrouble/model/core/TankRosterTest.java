package tanktrouble.model.core;

import java.util.*;
import org.junit.jupiter.api.Test;
import tanktrouble.model.data.GameData.*;
import tanktrouble.model.data.Rules;
import tanktrouble.model.entity.Entities.*;
import tanktrouble.model.map.TestMaps;
import tanktrouble.model.rule.CombatSystem;
import static org.junit.jupiter.api.Assertions.*;
import static tanktrouble.model.core.ModelTest.*;

class TankRosterTest {
    private static TankGameModel game(TankType type) {
        TankGameModel game=new TankGameModel();
        game.selectMode(Mode.SINGLE);
        game.selectTank(0,type);
        game.setTerrainEnabled(false);
        game.startBattle(42);
        return game;
    }

    @Test void sixCampusVehiclesHaveDistinctGameplayProfiles() {
        assertEquals(6,TankType.values().length);
        Set<String> statSignatures=new HashSet<>();
        for(TankType type:TankType.values()) {
            assertTrue(type.hp>=7 && type.hp<=15,type.name());
            assertTrue(type.speed>=130 && type.speed<=240,type.name());
            assertTrue(type.cooldown>=390 && type.cooldown<=680,type.name());
            statSignatures.add(type.hp+":"+type.speed+":"+type.cooldown);
        }
        assertEquals(6,statSignatures.size(),"Every campus vehicle needs its own stat identity");
    }

    @Test void startingPassivesAreAppliedAtBattleStart() {
        Tank balanced=tanks(game(TankType.BALANCED)).get(0);
        assertEquals(25,balanced.energy);

        Tank research=tanks(game(TankType.RESEARCH)).get(0);
        assertEquals(20,research.energy);

        Tank medic=tanks(game(TankType.MEDIC)).get(0);
        assertEquals(2500,medic.shieldMs);
    }

    @Test void medicRepairBonusRestoresFourHealth() {
        TankGameModel game=game(TankType.MEDIC);
        quiet(game);
        Tank player=tanks(game).get(0);
        player.hp=5;
        List<PickupView> pickups=field(game,"pickups");
        pickups.clear();
        pickups.add(new PickupView(player.x,player.y,Item.REPAIR));

        game.tick(8);

        assertEquals(9,player.hp);
    }

    @Test void researchGainsExtraEnergyFromEachBounce() {
        Tank owner=new Tank(0,0,TankType.RESEARCH,100,100);
        Bullet bullet=new Bullet(owner.id,7.1,100,180);
        List<Bullet> bullets=new ArrayList<>(List.of(bullet));

        new CombatSystem().update(bullets,TestMaps.open(),List.of(owner),8,(tank,hit)->{},(player,x,y)->{});

        assertEquals(Rules.BOUNCE_ENERGY+TankType.RESEARCH.bounceBonus,owner.energy);
    }
}
