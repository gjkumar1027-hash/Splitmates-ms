package com.splitmates.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "group_members")
@Getter
@Setter
public class GroupMember {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long groupId;
    /** Null for guests (FR-12) and for members whose account was deleted (FR-04). */
    private Long userId;
    private String displayName;
    private String avatarEmoji;
    private String avatarColor;
    @Enumerated(EnumType.STRING)
    private MemberRole role = MemberRole.MEMBER;
    @Column(name = "is_active")
    private boolean active = true;
    private Instant joinedAt = Instant.now();

    public boolean isGuest() {
        return userId == null;
    }

    public boolean isAdmin() {
        return role == MemberRole.ADMIN;
    }
}
