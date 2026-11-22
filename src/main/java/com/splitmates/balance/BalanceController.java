package com.splitmates.balance;

import com.splitmates.auth.CurrentUser;
import com.splitmates.balance.BalanceDtos.*;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class BalanceController {
    private final BalanceService balances;
    private final SettlementService settlements;

    public BalanceController(BalanceService balances, SettlementService settlements) {
        this.balances = balances;
        this.settlements = settlements;
    }

    @GetMapping("/groups/{id}/balances")
    public GroupBalances balances(@PathVariable Long id) {
        return balances.groupBalances(id, CurrentUser.id());
    }

    @GetMapping("/groups/{id}/settlements")
    public List<SettlementDto> list(@PathVariable Long id) {
        return settlements.list(id, CurrentUser.id());
    }

    @PostMapping("/groups/{id}/settlements")
    @ResponseStatus(HttpStatus.CREATED)
    public SettlementDto create(@PathVariable Long id, @Valid @RequestBody SettlementRequest req) {
        return settlements.create(id, req, CurrentUser.id());
    }

    @PatchMapping("/settlements/{id}/confirm")
    public SettlementDto confirm(@PathVariable Long id) {
        return settlements.confirm(id, CurrentUser.id(), true);
    }

    @PatchMapping("/settlements/{id}/reject")
    public SettlementDto reject(@PathVariable Long id) {
        return settlements.confirm(id, CurrentUser.id(), false);
    }

    @PostMapping("/groups/{id}/nudges")
    public NudgeResponse nudge(@PathVariable Long id, @Valid @RequestBody NudgeRequest req) {
        return settlements.nudge(id, req, CurrentUser.id());
    }
}
