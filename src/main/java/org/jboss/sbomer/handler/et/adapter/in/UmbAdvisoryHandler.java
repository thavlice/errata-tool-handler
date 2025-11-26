package org.jboss.sbomer.handler.et.adapter.in;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;

import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.sbomer.handler.et.core.port.api.AdvisoryHandler;

import io.smallrye.reactive.messaging.amqp.IncomingAmqpMetadata;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.vertx.core.json.JsonObject;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

/**
 * WHAT IT DOES
 * 1. Listening to the UMB on its own
 * 2. Checks for the right topic todo(make into config)
 * 3. Checks the status, if it is from QE or SHIPPED_LIVE todo(do we need this?)
 * 4. Calls AdvisoryHandler method requestGenerations()
 * 5. Returns? todo
 */


/**
 * Handler for processing advisory updates messages received via UMB.
 * Acts as a Driving Adapter to trigger the domain logic.
 */
@ApplicationScoped
@Slf4j
public class UmbAdvisoryHandler {
    private static final String SUBJECT_ERRATA_ACTIVITY = "errata.activity.status";
    private static final Set<String> RELEVANT_STATUSES = Set.of("QE", "SHIPPED_LIVE");
    private final AdvisoryHandler advisoryHandler;

    @Inject
    UmbAdvisoryHandler(AdvisoryHandler advisoryHandler) {
        this.advisoryHandler = advisoryHandler;
    }

    /**
     * Listens to the UMB 'errata' channel for advisory phase triggers.
     * It extracts the ID and invokes the domain handler.
     */
    @Incoming("errata")
    @Blocking(ordered = false)
    public CompletionStage<Void> process(Message<byte[]> message) {
        log.debug("Received new Errata tool status change notification");

        // 1. Validate Subject via Metadata
        if (!isValidSubject(message)) {
            log.warn("Received UMB message with invalid or missing subject, skipping.");
            return message.ack();
        }

        // 2. Decode the message payload
        String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
        JsonObject json;
        try {
            json = new JsonObject(payload);
        } catch (Exception e) {
            log.error("Failed to parse UMB message payload. Raw payload: {}", payload, e);
            return message.ack();
        }

        // 3. Check for relevant status (QE or SHIPPED_LIVE)
        Long errataId = json.getLong("errata_id");
        if (errataId == null) {
            log.error("Errata id not found");
            return message.ack();
        }

        String status = json.getString("errata_status");

        if (status == null || !RELEVANT_STATUSES.contains(status)) {
            log.debug("Skipping message for errata {} with status {}", errataId, status);
            return message.ack();
        }

        // 4. Invoke Business Logic
        log.info("Triggering generation for advisory {} based on status change to {}", errataId, status);
        advisoryHandler.requestGenerations(String.valueOf(errataId));

        return message.ack();
    }

    private boolean isValidSubject(Message<byte[]> message) {
        Optional<IncomingAmqpMetadata> metadata = message.getMetadata(IncomingAmqpMetadata.class);

        if (metadata.isEmpty()) {
            return false;
        }

        JsonObject properties = metadata.get().getProperties();
        if (properties == null) {
            return false;
        }

        String subject = properties.getString("subject");
        return SUBJECT_ERRATA_ACTIVITY.equals(subject);
    }
}