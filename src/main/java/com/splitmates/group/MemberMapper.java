package com.splitmates.group;

import com.splitmates.domain.GroupMember;
import com.splitmates.domain.User;
import com.splitmates.group.GroupDtos.MemberDto;
import com.splitmates.repo.UserRepository;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class MemberMapper {
    private final UserRepository users;

    public MemberMapper(UserRepository users) {
        this.users = users;
    }

    public List<MemberDto> toDtos(List<GroupMember> list) {
        Set<Long> ids = list.stream().map(GroupMember::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, User> byId = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return list.stream().map(m -> toDto(m, byId.get(m.getUserId()))).toList();
    }

    public MemberDto toDto(GroupMember m, User u) {
        String phone = null;
        String upi = null;
        if (u != null) {
            upi = u.getUpiId(); // NFR-10: UPI shared only within the group
            phone = u.getPhone() == null ? null : (u.isShowPhone() ? u.getPhone() : mask(u.getPhone()));
        }
        return new MemberDto(m.getId(), m.getUserId(), m.getDisplayName(), m.getAvatarEmoji(), m.getAvatarColor(),
                m.getRole(), m.isActive(), m.isGuest(), upi, phone);
    }

    static String mask(String phone) {
        if (phone.length() <= 4) return "••••";
        return phone.substring(0, Math.min(3, phone.length() - 4)) + "•••••" + phone.substring(phone.length() - 4);
    }
}
