package chat.fold.service;

import chat.fold.db.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;

@ApplicationScoped
public class AuditLogService {
    private static final Logger LOG = Logger.getLogger(AuditLogService.class);

    @Inject
    AuditLogRepository auditLogRepository;

    @Inject
    ObjectMapper objectMapper;

    public void log(String actorId, String action, String targetType, String targetId, Map<String, Object> details) {
        String detailsJson = null;
        if (details != null) {
            try {
                detailsJson = objectMapper.writeValueAsString(details);
            } catch (JsonProcessingException e) {
                LOG.warnf("Failed to serialize audit log details: %s", e.getMessage());
            }
        }
        auditLogRepository.create(actorId, action, targetType, targetId, detailsJson);
    }

    public void log(String actorId, String action, String targetType, String targetId) {
        log(actorId, action, targetType, targetId, null);
    }
}
