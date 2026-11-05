package com.campusoverflow.discovery.application;

import com.campusoverflow.discovery.domain.search.SearchDocument;
import com.campusoverflow.discovery.domain.search.SearchIndex;
import com.campusoverflow.qa.api.event.AnswerAcceptedEvent;
import com.campusoverflow.qa.api.event.AnswerSubmittedEvent;
import com.campusoverflow.qa.api.event.ContentDeletedEvent;
import com.campusoverflow.qa.api.event.QuestionEditedEvent;
import com.campusoverflow.qa.api.event.QuestionPostedEvent;
import com.campusoverflow.qa.api.event.VoteCastEvent;
import com.campusoverflow.reputation.api.event.BountyAwardedEvent;
import com.campusoverflow.reputation.api.event.BountyClosedEvent;
import com.campusoverflow.reputation.api.event.BountyOpenedEvent;
import com.campusoverflow.shared.event.IntegrationEvent;
import com.campusoverflow.shared.event.ProcessedEventStore;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 事件投影器：把上游事件折叠为搜索投影（dis_search_doc）。
 * 计数器类更新不是天然幂等的，因此每个事件先经 ProcessedEventStore 去重（与投影更新同一事务）。
 */
@Component
public class SearchIndexProjector {

    static final String CONSUMER = "discovery.search-index";

    private final SearchIndex index;
    private final ProcessedEventStore processed;

    public SearchIndexProjector(SearchIndex index, ProcessedEventStore processed) {
        this.index = index;
        this.processed = processed;
    }

    private boolean firstTime(IntegrationEvent e) {
        // TODO(S3)：等待后续实现 ProcessedEventStore 去重逻辑
        // 暂时返回 false，表示不是第一次（直接跳过后续处理）
        return false;
    }

    @EventListener
    @Transactional
    public void on(QuestionPostedEvent e) {
        // TODO(S3)：等待 Lab7 实现搜索投影
        return;
    }

    @EventListener
    @Transactional
    public void on(QuestionEditedEvent e) {
        // TODO(S3)：等待 Lab7 实现
        return;
    }

    @EventListener
    @Transactional
    public void on(AnswerSubmittedEvent e) {
        // TODO(S3)：等待 Lab7 实现
        return;
    }

    @EventListener
    @Transactional
    public void on(ContentDeletedEvent e) {
        // TODO(S3)：等待 Lab7 实现
        return;
    }

    @EventListener
    @Transactional
    public void on(AnswerAcceptedEvent e) {
        // TODO(S3)：等待 Lab7 实现
        return;
    }

    @EventListener
    @Transactional
    public void on(VoteCastEvent e) {
        // TODO(S3)：等待 Lab7 实现
        return;
    }

    @EventListener
    @Transactional
    public void on(BountyOpenedEvent e) {
        // TODO(S3)：等待 Lab7 实现
        return;
    }

    @EventListener
    @Transactional
    public void on(BountyAwardedEvent e) {
        // TODO(S3)：等待 Lab7 实现
        return;
    }

    @EventListener
    @Transactional
    public void on(BountyClosedEvent e) {
        // TODO(S3)：等待 Lab7 实现
        return;
    }
}