package com.mgg.exp.store.infrastructure.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DomainEventPublisherImpl {

    private final ApplicationEventPublisher eventPublisher;

    public void publish(Object event) {
        try {
            eventPublisher.publishEvent(event);
        } catch (Exception e) {
            log.error("publish event failed, event: {}", event.getClass().getSimpleName(), e);
        }
    }
}
