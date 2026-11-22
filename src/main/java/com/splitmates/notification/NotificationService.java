package com.splitmates.notification;

import com.splitmates.domain.GroupMember;
import com.splitmates.domain.Notification;
import com.splitmates.repo.NotificationRepository;
import com.splitmates.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app notifications (FR-60/61/62). Web push via FCM can be added by publishing
 * from {@link #notifyUser} once a Firebase project is configured.
 */
@Service
public class NotificationService {
    private final NotificationRepository repo;
    private final UserRepository users;

    public NotificationService(NotificationRepository repo, UserRepository users) {
        this.repo = repo;
        this.users = users;
    }

    @Transactional
    public void notifyUser(Long userId, Long groupId, String type, String message, String link) {
        if (userId == null) return;
        boolean enabled = users.findById(userId).map(u -> !u.isDeleted() && u.isNotifyInApp()).orElse(false);
        if (!enabled) return;
        Notification n = new Notification();
        n.setUserId(userId);
        n.setGroupId(groupId);
        n.setType(type);
        n.setMessage(message.length() > 300 ? message.substring(0, 297) + "..." : message);
        n.setLink(link);
        repo.save(n);
    }

    @Transactional
    public void notifyMember(GroupMember m, Long groupId, String type, String message, String link) {
        if (m != null && m.isActive() && m.getUserId() != null) notifyUser(m.getUserId(), groupId, type, message, link);
    }
}
