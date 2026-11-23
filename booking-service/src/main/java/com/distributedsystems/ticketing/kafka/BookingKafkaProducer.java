package com.distributedsystems.ticketing.kafka;

import com.distributedsystems.ticketing.model.BookingEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class BookingKafkaProducer {

    private static final Logger log = LoggerFactory.getLogger(BookingKafkaProducer.class);
    public static final String TOPIC = "booking-confirmations";

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public BookingKafkaProducer(KafkaTemplate<String, Object> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishBookingConfirmation(BookingEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.getUserId(), event)
                .whenComplete((result, ex) -> {
                    if (ex == null) {
                        log.info("[Kafka Producer] Published confirmation for bookingId={} to topic={} [partition={}, offset={}]",
                                event.getBookingId(), TOPIC,
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    } else {
                        log.error("[Kafka Producer] Failed to publish confirmation for bookingId={}: {}",
                                event.getBookingId(), ex.getMessage(), ex);
                    }
                });
        } catch (Exception e) {
            log.error("[Kafka Producer] Exception during Kafka send: {}", e.getMessage(), e);
        }
    }
}
