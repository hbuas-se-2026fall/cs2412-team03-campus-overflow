package com.campusoverflow.reputation.domain;

import java.util.EnumSet;
import java.util.Set;

/** 徽章授予规则（纯函数，易于测试与扩展）。 */
public final class BadgePolicy {

    private BadgePolicy() {
    }

    /** 回答被采纳数达标即授予：1 个给「初露锋芒」，累计 10 个给「解惑达人」。 */
    public static Set<BadgeType> forAcceptedCount(long acceptedCount) {
        Set<BadgeType> badges = EnumSet.noneOf(BadgeType.class);
        if (acceptedCount >= 1) {
            badges.add(BadgeType.FIRST_ACCEPTED);
        }
        if (acceptedCount >= 10) {
            badges.add(BadgeType.HELPER_10);
        }
        return badges;
    }

    /** 声誉跨过 100 / 1000 的门槛时授予。 */
    public static Set<BadgeType> forReputation(int reputation) {
        Set<BadgeType> badges = EnumSet.noneOf(BadgeType.class);
        if (reputation >= 100) {
            badges.add(BadgeType.REPUTATION_100);
        }
        if (reputation >= 1000) {
            badges.add(BadgeType.REPUTATION_1000);
        }
        return badges;
    }
}
