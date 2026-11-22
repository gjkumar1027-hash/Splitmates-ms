package com.splitmates.notification;

import com.splitmates.auth.CurrentUser;
import com.splitmates.common.ApiException;
import com.splitmates.domain.Notification;
import com.splitmates.repo.NotificationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    public record NotificationDto(Long id, Long groupId, String type, String message, String link, boolean read, Instant createdAt) {
    }

    private final NotificationRepository repo;

    public NotificationController(NotificationRepository repo) {
        this.repo = repo;
    }

    @GetMapping
    public List<NotificationDto> list(@RequestParam(defaultValue = "50") int limit) {
        return repo.findByUserIdOrderByCreatedAtDesc(CurrentUser.id(), PageRequest.of(0, Math.min(limit, 200))).stream()
                .map(n -> new NotificationDto(n.getId(), n.getGroupId(), n.getType(), n.getMessage(), n.getLink(), n.isRead(), n.getCreatedAt()))
                .toList();
    }

    @GetMapping("/unread-count")
    public Map<String, Long> unread() {
        return Map.of("count", repo.countByUserIdAndReadFalse(CurrentUser.id()));
    }

    @PostMapping("/read-all")
    @Transactional
    public ResponseEntity<Void> readAll() {
        repo.markAllRead(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/read")
    @Transactional
    public ResponseEntity<Void> read(@PathVariable Long id) {
        Notification n = repo.findById(id).filter(x -> x.getUserId().equals(CurrentUser.id()))
                .orElseThrow(() -> ApiException.notFound("Notification"));
        n.setRead(true);
        return ResponseEntity.noContent().build();
    }
}
