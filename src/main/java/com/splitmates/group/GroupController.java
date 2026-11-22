package com.splitmates.group;

import com.splitmates.auth.CurrentUser;
import com.splitmates.group.GroupDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/groups")
public class GroupController {
    private final GroupService service;

    public GroupController(GroupService service) {
        this.service = service;
    }

    @GetMapping
    public List<GroupDto> list() {
        return service.list(CurrentUser.id());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public GroupDto create(@Valid @RequestBody CreateGroupRequest req) {
        return service.create(req, CurrentUser.id());
    }

    @GetMapping("/{id}")
    public GroupDto get(@PathVariable Long id) {
        return service.get(id, CurrentUser.id());
    }

    @PatchMapping("/{id}")
    public GroupDto update(@PathVariable Long id, @Valid @RequestBody UpdateGroupRequest req) {
        return service.update(id, req, CurrentUser.id());
    }

    @PostMapping("/{id}/archive")
    public GroupDto archive(@PathVariable Long id) {
        return service.setArchived(id, true, CurrentUser.id());
    }

    @PostMapping("/{id}/reopen")
    public GroupDto reopen(@PathVariable Long id) {
        return service.setArchived(id, false, CurrentUser.id());
    }

    @PostMapping("/{id}/members")
    @ResponseStatus(HttpStatus.CREATED)
    public MemberDto addMember(@PathVariable Long id, @Valid @RequestBody AddMemberRequest req) {
        return service.addGuest(id, req, CurrentUser.id());
    }

    @DeleteMapping("/{id}/members/{memberId}")
    public ResponseEntity<Void> removeMember(@PathVariable Long id, @PathVariable Long memberId) {
        service.removeMember(id, memberId, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/members/{memberId}/role")
    public MemberDto setRole(@PathVariable Long id, @PathVariable Long memberId, @Valid @RequestBody RoleRequest req) {
        return service.setRole(id, memberId, req.role(), CurrentUser.id());
    }

    @PostMapping("/join")
    public GroupDto join(@Valid @RequestBody JoinRequest req) {
        return service.join(req, CurrentUser.id());
    }
}
