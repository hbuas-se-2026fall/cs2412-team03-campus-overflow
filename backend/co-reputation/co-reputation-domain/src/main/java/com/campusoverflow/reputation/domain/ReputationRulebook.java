package com.campusoverflow.reputation.domain;

import com.campusoverflow.shared.domain.BusinessRuleException;
import com.campusoverflow.shared.security.Role;

/**
 * 声誉规则手册——把“游戏规则”集中在一处，便于课堂讨论与参数调整。
 * <ul>
 *   <li>回答被采纳 +15（自问自答被采纳不加分）；</li>
 *   <li>被赞 +10，被踩 −2；改投、撤销按“新效果 − 旧效果”计算增量；</li>
 *   <li>反作弊：每人每个自然日（UTC）因被投票获得的正向声誉最多 200。</li>
 * </ul>
 */
public final class ReputationRulebook {

    public static final int ACCEPTED = 15;
    public static final int UPVOTE = 10;
    public static final int DOWNVOTE = -2;
    public static final int DAILY_VOTE_CAP = 200;

    private ReputationRulebook() {
    }

    /** 单张票的效果：赞成 +10，反对 −2，无票 0。 */
    public static int voteEffect(int value) {
        return switch (value) {
            case 1 -> UPVOTE;
            case -1 -> DOWNVOTE;
            case 0 -> 0;
            default -> throw new BusinessRuleException("REP-1001", "投票值只能是 +1、-1 或 0");
        };
    }

    /**
     * 改投/撤销的增量 = 新票效果 − 旧票效果。
     * 这样 +1 → −1 得到 −12（−2 减去 10），而不是单看新票的 −2。
     */
    public static int voteDelta(int oldValue, int newValue) {
        return voteEffect(newValue) - voteEffect(oldValue);
    }

    public static int acceptedDelta(long answerAuthorId, long questionAuthorId) {
        return answerAuthorId == questionAuthorId ? 0 : ACCEPTED;
    }

    /** 对正向投票增量应用每日上限；负向增量不受限。 */
    public static int applyDailyCap(int delta, int positiveVoteGainToday) {
        if (delta <= 0) {
            return delta;
        }
        int remaining = Math.max(0, DAILY_VOTE_CAP - Math.max(0, positiveVoteGainToday));
        return Math.min(delta, remaining);
    }

    /** 开户时发放的悬赏积分（与声誉是两种不同的“货币”）。 */
    public static int initialPoints(Role role) {
        return role == Role.STUDENT ? 0 : 1000;
    }
}
