package com.splitmates.report;

import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.splitmates.balance.BalanceDtos.GroupBalances;
import com.splitmates.balance.BalanceDtos.MemberBalance;
import com.splitmates.balance.BalanceDtos.TransferDto;
import com.splitmates.balance.BalanceService;
import com.splitmates.common.Money;
import com.splitmates.domain.*;
import com.splitmates.group.GroupAccess;
import com.splitmates.repo.ExpenseRepository;
import com.splitmates.repo.GroupMemberRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** FR-54: export a group report as CSV or PDF. */
@Service
public class ReportService {
    private static final Color VIOLET = new Color(0x7C, 0x3A, 0xED);

    private final ExpenseRepository expenses;
    private final GroupMemberRepository members;
    private final GroupAccess access;
    private final BalanceService balances;

    public ReportService(ExpenseRepository expenses, GroupMemberRepository members, GroupAccess access, BalanceService balances) {
        this.expenses = expenses;
        this.members = members;
        this.access = access;
        this.balances = balances;
    }

    private record Data(ExpenseGroup group, List<Expense> expenses, Map<Long, String> names, GroupBalances balances) {
    }

    private Data load(Long groupId, Long userId) {
        access.requireMember(groupId, userId);
        ExpenseGroup g = access.group(groupId);
        Map<Long, String> names = members.findByGroupIdOrderByIdAsc(groupId).stream()
                .collect(Collectors.toMap(GroupMember::getId, GroupMember::getDisplayName));
        return new Data(g, expenses.findByGroupIdAndDeletedFalseOrderByExpenseDateDescIdDesc(groupId), names,
                balances.groupBalances(groupId, userId));
    }

    public static String fileName(ExpenseGroup g, String ext) {
        return g.getName().replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "") + "-report-" + LocalDate.now() + "." + ext;
    }

    @Transactional(readOnly = true)
    public ExpenseGroup group(Long groupId, Long userId) {
        access.requireMember(groupId, userId);
        return access.group(groupId);
    }

    @Transactional(readOnly = true)
    public byte[] csv(Long groupId, Long userId) {
        Data d = load(groupId, userId);
        StringBuilder sb = new StringBuilder("﻿"); // BOM so Excel reads UTF-8
        sb.append("Date,Purpose,Category,Amount (INR),Paid by,Split,Shares\n");
        for (Expense e : d.expenses()) {
            String payers = e.getPayers().stream().map(p -> d.names().get(p.getMemberId()) + " " + Money.plain(p.getAmountPaid()))
                    .collect(Collectors.joining("; "));
            String shares = e.getShares().stream().map(s -> d.names().get(s.getMemberId()) + " " + Money.plain(s.getAmountOwed()))
                    .collect(Collectors.joining("; "));
            sb.append(e.getExpenseDate()).append(',').append(esc(e.getPurpose())).append(',').append(e.getCategory()).append(',')
                    .append(Money.plain(e.getAmount())).append(',').append(esc(payers)).append(',').append(e.getSplitType()).append(',')
                    .append(esc(shares)).append('\n');
        }
        sb.append("\nMember,Paid,Share,Settled sent,Settled received,Net\n");
        for (MemberBalance b : d.balances().members()) {
            sb.append(esc(d.names().get(b.memberId()))).append(',').append(Money.plain(b.paid())).append(',')
                    .append(Money.plain(b.owed())).append(',').append(Money.plain(b.settledSent())).append(',')
                    .append(Money.plain(b.settledReceived())).append(',').append(Money.plain(b.net())).append('\n');
        }
        sb.append("\nSettle-up plan\nFrom,To,Amount\n");
        for (TransferDto t : d.balances().plan()) {
            sb.append(esc(d.names().get(t.fromMemberId()))).append(',').append(esc(d.names().get(t.toMemberId()))).append(',')
                    .append(Money.plain(t.amount())).append('\n');
        }
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(Long groupId, Long userId) {
        Data d = load(groupId, userId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 42, 36);
        PdfWriter.getInstance(doc, out);
        doc.open();
        Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, VIOLET);
        Font h2 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
        Font body = FontFactory.getFont(FontFactory.HELVETICA, 9);
        Font bold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);

        doc.add(new Paragraph(ascii(d.group().getName()) + " - SplitMates report", title));
        long total = d.expenses().stream().mapToLong(Expense::getAmount).sum();
        doc.add(new Paragraph("Generated " + LocalDate.now() + "   |   " + d.expenses().size() + " expenses   |   Total spend " + rs(total)
                + "   |   Status " + d.group().getStatus(), body));
        doc.add(Chunk.NEWLINE);

        doc.add(new Paragraph("Balances", h2));
        PdfPTable bt = table(new float[]{3, 2, 2, 2, 2}, bold, "Member", "Paid", "Share", "Settlements (net)", "Net");
        for (MemberBalance b : d.balances().members()) {
            if (b.paid() == 0 && b.owed() == 0 && b.net() == 0) continue;
            row(bt, body, ascii(d.names().get(b.memberId())), rs(b.paid()), rs(b.owed()), rs(b.settledSent() - b.settledReceived()),
                    (b.net() > 0 ? "+" : "") + rs(b.net()));
        }
        doc.add(bt);
        doc.add(Chunk.NEWLINE);

        doc.add(new Paragraph("Settle-up plan", h2));
        if (d.balances().plan().isEmpty()) doc.add(new Paragraph("All squared up!", body));
        for (TransferDto t : d.balances().plan()) {
            doc.add(new Paragraph(ascii(d.names().get(t.fromMemberId())) + "  ->  " + ascii(d.names().get(t.toMemberId())) + "   " + rs(t.amount()), body));
        }
        doc.add(Chunk.NEWLINE);

        doc.add(new Paragraph("Expenses", h2));
        PdfPTable et = table(new float[]{2, 5, 2, 2, 3}, bold, "Date", "Purpose", "Category", "Amount", "Paid by");
        for (Expense e : d.expenses()) {
            String payers = e.getPayers().stream().map(p -> ascii(d.names().get(p.getMemberId()))).collect(Collectors.joining(", "));
            row(et, body, e.getExpenseDate().toString(), ascii(e.getPurpose()), e.getCategory().name(), rs(e.getAmount()), payers);
        }
        doc.add(et);
        doc.close();
        return out.toByteArray();
    }

    private static PdfPTable table(float[] widths, Font headFont, String... heads) {
        PdfPTable t = new PdfPTable(widths);
        t.setWidthPercentage(100);
        t.setSpacingBefore(4);
        for (String h : heads) {
            PdfPCell c = new PdfPCell(new Phrase(h, headFont));
            c.setBackgroundColor(VIOLET);
            c.setPadding(5);
            t.addCell(c);
        }
        t.setHeaderRows(1);
        return t;
    }

    private static void row(PdfPTable t, Font f, String... cells) {
        for (String s : cells) {
            PdfPCell c = new PdfPCell(new Phrase(s, f));
            c.setPadding(4);
            t.addCell(c);
        }
    }

    /** Built-in PDF fonts lack the rupee glyph and emoji, so use "Rs." and strip non-Latin-1 characters. */
    private static String rs(long paise) {
        return Money.format(paise).replace("₹", "Rs. ");
    }

    private static String ascii(String s) {
        return s == null ? "" : s.replaceAll("[^\\x20-\\xFF]", "").trim();
    }

    private static String esc(String s) {
        if (s == null) return "";
        String v = s.replace("\"", "\"\"");
        // Neutralise spreadsheet formula injection.
        if (!v.isEmpty() && "=+-@".indexOf(v.charAt(0)) >= 0) v = "'" + v;
        return "\"" + v + "\"";
    }
}
