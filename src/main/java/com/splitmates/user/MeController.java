package com.splitmates.user;

import com.splitmates.auth.CurrentUser;
import com.splitmates.balance.BalanceDtos.MeSummary;
import com.splitmates.balance.BalanceService;
import com.splitmates.user.UserDtos.MeDto;
import com.splitmates.user.UserDtos.UpdateProfileRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final UserService users;
    private final BalanceService balances;

    public MeController(UserService users, BalanceService balances) {
        this.users = users;
        this.balances = balances;
    }

    @GetMapping
    public MeDto me() {
        return UserDtos.me(users.get(CurrentUser.id()));
    }

    @PatchMapping
    public MeDto update(@Valid @RequestBody UpdateProfileRequest req) {
        return UserDtos.me(users.update(CurrentUser.id(), req));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete() {
        users.delete(CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/summary")
    public MeSummary summary() {
        return balances.summaryForUser(CurrentUser.id());
    }

    @GetMapping("/export")
    public ResponseEntity<Map<String, Object>> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"splitmates-my-data.json\"")
                .body(users.export(CurrentUser.id()));
    }
}
