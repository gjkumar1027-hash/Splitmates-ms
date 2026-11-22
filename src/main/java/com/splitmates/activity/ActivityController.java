package com.splitmates.activity;

import com.splitmates.activity.ActivityService.ActivityDto;
import com.splitmates.auth.CurrentUser;
import com.splitmates.group.GroupAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ActivityController {
    private final ActivityService activity;
    private final GroupAccess access;

    public ActivityController(ActivityService activity, GroupAccess access) {
        this.activity = activity;
        this.access = access;
    }

    @GetMapping("/groups/{id}/activity")
    public List<ActivityDto> group(@PathVariable Long id, @RequestParam(defaultValue = "100") int limit) {
        access.requireMember(id, CurrentUser.id());
        return activity.forGroup(id, Math.min(limit, 500));
    }

    @GetMapping("/activity")
    public List<ActivityDto> mine(@RequestParam(defaultValue = "100") int limit) {
        return activity.forUser(CurrentUser.id(), Math.min(limit, 500));
    }
}
