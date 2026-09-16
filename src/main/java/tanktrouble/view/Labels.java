package tanktrouble.view;

import tanktrouble.model.data.GameData.*;

final class Labels {
    private Labels() {}
    static String mode(Mode mode) {return switch(mode) {case SINGLE->"单人突围";case DUEL->"双人对决";case ENDLESS->"无尽回响";case MULTI->"多人混战";case COOP->"合作讨伐";};}
    static String tank(TankType type) {return switch(type) {
        case BALANCED->"潇湘 · 天元主战";
        case HEAVY->"岳麓山 · 磐石重装";
        case ENGINEER->"麓南 · 天工后勤";
        case SCOUT->"天心 · 追风突击";
        case MEDIC->"开福 · 白鸢医疗";
        case RESEARCH->"杏林 · 青囊实验";
    };}
    static String tankRole(TankType type) {return switch(type) {
        case BALANCED->"主角 / 均衡压制";
        case HEAVY->"装甲 / 正面推进";
        case ENGINEER->"后勤 / 维修部署";
        case SCOUT->"速度 / 侧翼突击";
        case MEDIC->"医疗 / 持续续航";
        case RESEARCH->"实验 / 反弹蓄能";
    };}
    static String tankTrait(TankType type) {return switch(type) {
        case BALANCED->"初始共振 25；机动、装甲与装填最均衡";
        case HEAVY->"15 点装甲与重炮节奏；转向和推进最慢";
        case ENGINEER->"初始能量 50；维修 +4；炮塔持续 18 秒";
        case SCOUT->"全车型最高速度与最快装填；装甲最低";
        case MEDIC->"开局护盾 2.5 秒；维修恢复 4 点生命";
        case RESEARCH->"初始能量 20；每次反弹额外获得 2 能量";
    };}
    static String item(Item item) {return switch(item) {
        case REPAIR->"维修组件";case SHIELD->"相位护盾";case RAPID->"急速弹匣";case TURRET->"自动炮塔";
        case SCATTER->"散射矩阵";case AIM->"预瞄模块";case PENETRATE->"穿透弹芯";
    };}
    static String effect(Item item) {return switch(item) {
        case REPAIR->"恢复 3 点生命";case SHIELD->"免疫伤害 · 9 秒";case RAPID->"射击间隔 -45% · 9 秒";case TURRET->"炮塔库存 +1 · 部署后 12 秒";
        case SCATTER->"每次发射 4 枚子弹 · 总张角 120° · 9 秒";case AIM->"显示含反弹的弹道预测线 · 9 秒";case PENETRATE->"子弹穿透所有墙体，触边消失 · 9 秒";
    };}
    static String upgrade(Upgrade upgrade) {return switch(upgrade) {case REINFORCE->"复合装甲";case OVERCLOCK->"超频火控";case RESONANCE->"共振核心";};}
    static String upgradeEffect(Upgrade upgrade) {return switch(upgrade) {case REINFORCE->"生命上限 +2 / 恢复 4 点";case OVERCLOCK->"永久射击间隔 -14% / 最低 35%";case RESONANCE->"脉冲充满 / 蓄能 +2 / 半径 +20";};}
}
