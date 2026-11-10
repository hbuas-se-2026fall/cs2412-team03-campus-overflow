package com.campusoverflow.reputation.application;

import com.campusoverflow.reputation.api.event.BadgeAwardedEvent;
import com.campusoverflow.reputation.domain.BadgeAward;
import com.campusoverflow.reputation.domain.BadgeRepository;
import com.campusoverflow.reputation.domain.BadgeType;
import com.campusoverflow.shared.event.EventIds;
import com.campusoverflow.shared.event.IntegrationEventPublisher;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 徽章授予（幂等：已拥有则忽略）。 */
@Service
public class BadgeService {

    private final BadgeRepository badges;
    private final IntegrationEventPublisher events;
    private final Clock clock;

    public BadgeService(BadgeRepository badges, IntegrationEventPublisher events, Clock clock) {
        this.badges = badges;
        this.events = events;
        this.clock = clock;
    }

    /**
     * 授予徽章。上游事件是<b>至少一次</b>投递的，所以这里用“已拥有即忽略”实现幂等：
     * 重复调用不会再写一行徽章，也不会重复发事件。
     *
     * @param userId 获得者
     * @param type   徽章类型
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void grant(long userId, BadgeType type) {
        if (type == null) {
            throw new IllegalArgumentException("徽章类型不能为空");
        }
        if (badges.has(userId, type)) {
            return;
        }
        Instant now = clock.instant();
        badges.award(new BadgeAward(userId, type, now));
        events.publish(new BadgeAwardedEvent(EventIds.next(), now, userId, type.name(), type.displayName()));
    }
}
