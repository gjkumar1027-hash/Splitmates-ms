package com.splitmates.report;

import com.splitmates.auth.CurrentUser;
import com.splitmates.common.ApiException;
import com.splitmates.domain.ExpenseGroup;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/groups/{id}")
public class ReportController {
    private final ReportService reports;
    private final InsightsService insights;

    public ReportController(ReportService reports, InsightsService insights) {
        this.reports = reports;
        this.insights = insights;
    }

    @GetMapping("/insights")
    public InsightsService.Insights insights(@PathVariable Long id) {
        return insights.forGroup(id, CurrentUser.id());
    }

    @GetMapping("/report")
    public ResponseEntity<byte[]> report(@PathVariable Long id, @RequestParam(defaultValue = "pdf") String format) {
        Long uid = CurrentUser.id();
        ExpenseGroup g = reports.group(id, uid);
        boolean pdf = switch (format.toLowerCase()) {
            case "pdf" -> true;
            case "csv" -> false;
            default -> throw ApiException.badRequest("format must be pdf or csv");
        };
        byte[] body = pdf ? reports.pdf(id, uid) : reports.csv(id, uid);
        return ResponseEntity.ok()
                .contentType(pdf ? MediaType.APPLICATION_PDF : new MediaType("text", "csv"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(ReportService.fileName(g, pdf ? "pdf" : "csv")).build().toString())
                .body(body);
    }
}
