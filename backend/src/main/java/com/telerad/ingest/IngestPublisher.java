package com.telerad.ingest;

import com.telerad.config.TeleradProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class IngestPublisher {
    private final RabbitTemplate rabbitTemplate;
    private final TeleradProperties properties;

    public void publish(UUID tenantId, String storageKey, String studyUidHint, String source, String callingAe) {
        StudyIngestMessage msg = new StudyIngestMessage(tenantId, storageKey, studyUidHint, source, callingAe);
        rabbitTemplate.convertAndSend(properties.getRabbit().getExchange(), "study.ingest", msg);
    }
}
