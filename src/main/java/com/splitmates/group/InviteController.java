package com.splitmates.group;

import com.splitmates.group.GroupDtos.InvitePreview;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Public invite preview so the join page can show the group before login. */
@RestController
@RequestMapping("/api/v1/invites")
public class InviteController {
    private final GroupService service;

    public InviteController(GroupService service) {
        this.service = service;
    }

    @GetMapping("/{code}")
    public InvitePreview preview(@PathVariable String code) {
        return service.preview(code);
    }
}
