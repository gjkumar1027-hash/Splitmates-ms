package com.splitmates.expense;

import com.splitmates.auth.CurrentUser;
import com.splitmates.domain.Category;
import com.splitmates.expense.ExpenseDtos.*;
import jakarta.validation.Valid;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/v1")
public class ExpenseController {
    private final ExpenseService service;

    public ExpenseController(ExpenseService service) {
        this.service = service;
    }

    @GetMapping("/groups/{groupId}/expenses")
    public ExpensePage list(@PathVariable Long groupId,
                            @RequestParam(required = false) String q,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                            @RequestParam(required = false) Category category,
                            @RequestParam(required = false) Long payerId,
                            @RequestParam(required = false) Long participantId,
                            @RequestParam(defaultValue = "0") int page,
                            @RequestParam(defaultValue = "50") int size) {
        return service.list(groupId, CurrentUser.id(), new ExpenseFilter(q, from, to, category, payerId, participantId), page, size);
    }

    @PostMapping("/groups/{groupId}/expenses")
    @ResponseStatus(HttpStatus.CREATED)
    public ExpenseDto create(@PathVariable Long groupId, @Valid @RequestBody ExpenseRequest req) {
        return service.create(groupId, req, CurrentUser.id());
    }

    @GetMapping("/groups/{groupId}/expenses/deleted")
    public List<ExpenseDto> deleted(@PathVariable Long groupId) {
        return service.recentlyDeleted(groupId, CurrentUser.id());
    }

    @GetMapping("/expenses/{id}")
    public ExpenseDto get(@PathVariable Long id) {
        return service.get(id, CurrentUser.id());
    }

    @PutMapping("/expenses/{id}")
    public ExpenseDto update(@PathVariable Long id, @Valid @RequestBody ExpenseRequest req) {
        return service.update(id, req, CurrentUser.id());
    }

    @DeleteMapping("/expenses/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id, CurrentUser.id());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/expenses/{id}/restore")
    public ExpenseDto restore(@PathVariable Long id) {
        return service.restore(id, CurrentUser.id());
    }

    @PostMapping(value = "/expenses/{id}/receipt", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ExpenseDto uploadReceipt(@PathVariable Long id, @RequestParam("file") MultipartFile file) {
        return service.attachReceipt(id, file, CurrentUser.id());
    }

    @GetMapping("/expenses/{id}/receipt")
    public ResponseEntity<Resource> receipt(@PathVariable Long id) throws IOException {
        Path p = service.receipt(id, CurrentUser.id());
        String type = Files.probeContentType(p);
        return ResponseEntity.ok()
                .contentType(type == null ? MediaType.APPLICATION_OCTET_STREAM : MediaType.parseMediaType(type))
                .cacheControl(CacheControl.maxAge(10, TimeUnit.MINUTES).cachePrivate())
                .body(new FileSystemResource(p));
    }
}
