package com.splitmates;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end API tests against the real PostgreSQL database configured in backend/.env
 * (the Neon database). Boots the full app on a random port, exercises the PRD's
 * acceptance criteria and business rules over HTTP, and deletes everything it created.
 *
 * Every run uses fresh random phone numbers, so it never touches real users' data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    @Value("${local.server.port}")
    int port;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newHttpClient();
    private final String runId = String.valueOf(100000 + new Random().nextInt(900000));
    private final List<String> phones = new ArrayList<>();
    private final List<Long> userIds = new ArrayList<>();
    private final List<Long> groupIds = new ArrayList<>();

    // Shared fixtures (tests run in order)
    private Session arun, divya, karthik, outsider;
    private long tripId;
    private long mArun, mDivya, mKarthik;
    private String inviteCode;

    record Res(int status, JsonNode body) {
        long id() {
            return body.get("id").asLong();
        }
    }

    record Session(String access, String refresh, long userId) {
    }

    // ---------------------------------------------------------------- helpers

    private Res call(String method, String path, String token, Object body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json");
        if (token != null) b.header("Authorization", "Bearer " + token);
        b.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        JsonNode node = r.body() == null || r.body().isBlank() || !r.body().trim().startsWith("{") && !r.body().trim().startsWith("[")
                ? json.createObjectNode() : json.readTree(r.body());
        return new Res(r.statusCode(), node);
    }

    private String phone(int n) {
        String p = "9" + runId + String.format("%03d", n);
        phones.add("+91" + p);
        return p;
    }

    private Session login(int n, String name) throws Exception {
        String phone = phone(n);
        Res otp = call("POST", "/auth/otp", null, Map.of("phone", phone));
        assertThat(otp.status()).as("OTP request").isEqualTo(200);
        String code = otp.body().get("devCode").asString();
        Res v = call("POST", "/auth/verify", null, Map.of("phone", phone, "code", code, "displayName", name, "consent", true));
        assertThat(v.status()).as("OTP verify " + v.body()).isEqualTo(200);
        long uid = v.body().get("user").get("id").asLong();
        userIds.add(uid);
        return new Session(v.body().get("accessToken").asString(), v.body().get("refreshToken").asString(), uid);
    }

    private static Map<String, Object> expense(String purpose, String amount, String category, String split,
                                               List<Map<String, Object>> payers, List<Map<String, Object>> shares) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("expenseDate", LocalDate.now().toString());
        m.put("purpose", purpose);
        m.put("amount", amount);
        m.put("category", category);
        m.put("splitType", split);
        m.put("payers", payers);
        m.put("shares", shares);
        return m;
    }

    private static Map<String, Object> who(long memberId) {
        return Map.of("memberId", memberId);
    }

    private static Map<String, Object> who(long memberId, String value) {
        return Map.of("memberId", memberId, "value", value);
    }

    private Map<Long, Long> nets(long groupId, Session s) throws Exception {
        Res b = call("GET", "/groups/" + groupId + "/balances", s.access(), null);
        assertThat(b.status()).isEqualTo(200);
        Map<Long, Long> out = new HashMap<>();
        b.body().get("members").forEach(m -> out.put(m.get("memberId").asLong(), m.get("net").asLong()));
        return out;
    }

    private long memberIdFor(JsonNode group, String name) {
        for (JsonNode m : group.get("members")) if (m.get("displayName").asString().equals(name)) return m.get("id").asLong();
        throw new AssertionError("No member " + name);
    }

    // ---------------------------------------------------------------- setup / teardown

    @BeforeAll
    void users() throws Exception {
        arun = login(1, "Arun");
        divya = login(2, "Divya");
        karthik = login(3, "Karthik");
        outsider = login(4, "Outsider");
    }

    @AfterAll
    void cleanup() {
        // Children first: payer/share/settlement rows reference group_members without cascade.
        for (Long g : groupIds) {
            jdbc.update("delete from expenses where group_id = ?", g); // cascades payers + shares
            jdbc.update("delete from settlements where group_id = ?", g);
            jdbc.update("delete from nudges where group_id = ?", g);
            jdbc.update("delete from activity_log where group_id = ?", g);
            jdbc.update("delete from expense_groups where id = ?", g); // cascades members + notifications
        }
        for (Long u : userIds) jdbc.update("delete from users where id = ?", u);
        for (String p : phones) jdbc.update("delete from otp_codes where phone = ?", p);
    }

    // ---------------------------------------------------------------- auth

    @Test
    @Order(1)
    void databaseIsReachableAndSchemaApplied() {
        Integer tables = jdbc.queryForObject("""
                select count(*) from information_schema.tables
                where table_schema = 'public' and table_name in
                ('users','expense_groups','group_members','expenses','expense_payers','expense_shares',
                 'settlements','activity_log','notifications','nudges','otp_codes','refresh_tokens')""", Integer.class);
        assertThat(tables).isEqualTo(12);
        Integer changesets = jdbc.queryForObject("select count(*) from databasechangelog", Integer.class);
        assertThat(changesets).isGreaterThanOrEqualTo(12);
    }

    @Test
    @Order(2)
    void wrongOtpIsRejectedAndOtpIsRateLimited() throws Exception {
        String p = phone(9);
        for (int i = 0; i < 5; i++) assertThat(call("POST", "/auth/otp", null, Map.of("phone", p)).status()).isEqualTo(200);
        Res sixth = call("POST", "/auth/otp", null, Map.of("phone", p));
        assertThat(sixth.status()).as("NFR-06: 5 OTPs per hour").isEqualTo(429);
        Res wrong = call("POST", "/auth/verify", null, Map.of("phone", p, "code", "000000", "consent", true));
        assertThat(wrong.status()).isEqualTo(400);
    }

    @Test
    @Order(3)
    void unauthenticatedCallsGet401() throws Exception {
        assertThat(call("GET", "/groups", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/me", "not-a-jwt", null).status()).isEqualTo(401);
    }

    // ---------------------------------------------------------------- groups

    @Test
    @Order(10)
    void createGroupInviteAndClaimGuest() throws Exception {
        Res g = call("POST", "/groups", arun.access(), Map.of("name", "Goa Trip " + runId, "type", "TRIP", "guestNames", List.of("Karthik")));
        assertThat(g.status()).isEqualTo(201);
        tripId = g.id();
        groupIds.add(tripId);
        inviteCode = g.body().get("inviteCode").asString();
        assertThat(g.body().get("myRole").asString()).isEqualTo("ADMIN");
        assertThat(g.body().get("coverEmoji").asString()).isEqualTo("✈️");

        // Public invite preview (no auth) lists the claimable guest
        Res preview = call("GET", "/invites/" + inviteCode, null, null);
        assertThat(preview.status()).isEqualTo(200);
        long guestId = preview.body().get("claimableGuests").get(0).get("memberId").asLong();

        assertThat(call("POST", "/groups/join", divya.access(), Map.of("code", inviteCode)).status()).isEqualTo(200);
        Map<String, Object> claim = new HashMap<>();
        claim.put("code", inviteCode);
        claim.put("claimMemberId", guestId);
        Res joined = call("POST", "/groups/join", karthik.access(), claim);
        assertThat(joined.status()).isEqualTo(200);
        assertThat(joined.body().get("myMemberId").asLong()).as("FR-13: guest profile claimed").isEqualTo(guestId);

        mArun = memberIdFor(joined.body(), "Arun");
        mDivya = memberIdFor(joined.body(), "Divya");
        mKarthik = guestId;
        assertThat(joined.body().get("members")).hasSize(3);
    }

    @Test
    @Order(11)
    void ac06_nonMemberGets403OnEveryGroupEndpoint() throws Exception {
        for (String p : List.of("", "/expenses", "/balances", "/insights", "/activity", "/settlements", "/report?format=csv")) {
            assertThat(call("GET", "/groups/" + tripId + p, outsider.access(), null).status()).as("GET /groups/{id}" + p).isEqualTo(403);
        }
        Res post = call("POST", "/groups/" + tripId + "/expenses", outsider.access(),
                expense("Sneaky", "10", "FOOD", "EQUAL", List.of(who(mArun)), List.of(who(mArun))));
        assertThat(post.status()).isEqualTo(403);
    }

    // ---------------------------------------------------------------- expenses & splits

    @Test
    @Order(20)
    void workedExample43_andAc04_twoPayments() throws Exception {
        List<Map<String, Object>> all = List.of(who(mArun), who(mDivya), who(mKarthik));
        assertThat(call("POST", "/groups/" + tripId + "/expenses", arun.access(),
                expense("Hotel", "6000", "STAY", "EQUAL", List.of(who(mArun)), all)).status()).isEqualTo(201);
        assertThat(call("POST", "/groups/" + tripId + "/expenses", divya.access(),
                expense("Fuel", "1500", "FUEL", "EQUAL", List.of(who(mDivya)), all)).status()).isEqualTo(201);

        Map<Long, Long> n = nets(tripId, divya);
        assertThat(n).containsEntry(mArun, 350_000L).containsEntry(mDivya, -100_000L).containsEntry(mKarthik, -250_000L);
        assertThat(n.values().stream().mapToLong(Long::longValue).sum()).as("BR-04: nets sum to zero").isZero();

        JsonNode plan = call("GET", "/groups/" + tripId + "/balances", divya.access(), null).body().get("plan");
        assertThat(plan).hasSize(2);
        assertThat(plan.get(0).get("fromMemberId").asLong()).isEqualTo(mKarthik);
        assertThat(plan.get(0).get("amount").asLong()).isEqualTo(250_000L);
        assertThat(plan.get(1).get("fromMemberId").asLong()).isEqualTo(mDivya);
        assertThat(plan.get(1).get("amount").asLong()).isEqualTo(100_000L);
    }

    @Test
    @Order(21)
    void ac01_equalSplitAmongFour() throws Exception {
        Res g = call("POST", "/groups", arun.access(), Map.of("name", "Office lunch " + runId, "type", "FOOD", "guestNames", List.of("B", "C", "D")));
        long gid = g.id();
        groupIds.add(gid);
        List<Long> ids = new ArrayList<>();
        g.body().get("members").forEach(m -> ids.add(m.get("id").asLong()));
        long me = g.body().get("myMemberId").asLong();

        Res e = call("POST", "/groups/" + gid + "/expenses", arun.access(),
                expense("Lunch", "1000", "FOOD", "EQUAL", List.of(who(me)), ids.stream().map(ApiIntegrationTest::who).toList()));
        assertThat(e.status()).isEqualTo(201);
        e.body().get("shares").forEach(s -> assertThat(s.get("amount").asLong()).isEqualTo(25_000L));

        Map<Long, Long> n = nets(gid, arun);
        assertThat(n.get(me)).isEqualTo(75_000L);
        ids.stream().filter(id -> id != me).forEach(id -> assertThat(n.get(id)).isEqualTo(-25_000L));
    }

    @Test
    @Order(22)
    void ac02_roundingGivesLeftoverPaiseToPayerFirst() throws Exception {
        Res e = call("POST", "/groups/" + tripId + "/expenses", divya.access(),
                expense("Chai", "100", "FOOD", "EQUAL", List.of(who(mDivya)), List.of(who(mArun), who(mDivya), who(mKarthik))));
        assertThat(e.status()).isEqualTo(201);
        Map<Long, Long> shares = new HashMap<>();
        e.body().get("shares").forEach(s -> shares.put(s.get("memberId").asLong(), s.get("amount").asLong()));
        assertThat(shares).containsEntry(mDivya, 3334L).containsEntry(mArun, 3333L).containsEntry(mKarthik, 3333L);
        assertThat(shares.values().stream().mapToLong(Long::longValue).sum()).isEqualTo(10_000L);
    }

    @Test
    @Order(23)
    void ac03_invalidSplitsAreBlocked() throws Exception {
        String base = "/groups/" + tripId + "/expenses";
        Res exact = call("POST", base, arun.access(),
                expense("Bad exact", "100", "FOOD", "EXACT", List.of(who(mArun)), List.of(who(mArun, "40"), who(mDivya, "50"))));
        assertThat(exact.status()).isEqualTo(400);
        assertThat(exact.body().get("message").asString()).contains("remaining");

        Res pct = call("POST", base, arun.access(),
                expense("Bad pct", "100", "FOOD", "PERCENT", List.of(who(mArun)), List.of(who(mArun, "50"), who(mDivya, "40"))));
        assertThat(pct.status()).isEqualTo(400);

        Res payers = call("POST", base, arun.access(), expense("Bad payers", "4800", "FOOD", "EQUAL",
                List.of(Map.of("memberId", mArun, "amount", "3000"), Map.of("memberId", mDivya, "amount", "1000")), List.of(who(mArun))));
        assertThat(payers.status()).as("BR-03: payers must sum to total").isEqualTo(400);

        Res tooLong = call("POST", base, arun.access(), expense("x".repeat(101), "1", "FOOD", "EQUAL", List.of(who(mArun)), List.of(who(mArun))));
        assertThat(tooLong.status()).as("FR-20: purpose max 100").isEqualTo(400);

        Res threeDecimals = call("POST", base, arun.access(), expense("Bad amount", "1.234", "FOOD", "EQUAL", List.of(who(mArun)), List.of(who(mArun))));
        assertThat(threeDecimals.status()).isEqualTo(400);
    }

    @Test
    @Order(24)
    void percentSharesAndMultiplePayers() throws Exception {
        String base = "/groups/" + tripId + "/expenses";
        Res shares = call("POST", base, arun.access(), expense("Villa", "900", "STAY", "SHARES",
                List.of(who(mArun)), List.of(who(mArun, "2"), who(mDivya, "1"))));
        assertThat(shares.status()).isEqualTo(201);
        assertThat(shares.body().get("shares").get(0).get("amount").asLong()).isEqualTo(60_000L);

        Res multi = call("POST", base, arun.access(), expense("Dinner at beach shack", "4800", "FOOD", "PERCENT",
                List.of(Map.of("memberId", mArun, "amount", "3000"), Map.of("memberId", mDivya, "amount", "1800")),
                List.of(who(mArun, "50"), who(mDivya, "25"), who(mKarthik, "25"))));
        assertThat(multi.status()).as("FR-24 multiple payers " + multi.body()).isEqualTo(201);
        assertThat(multi.body().get("payers")).hasSize(2);

        // Undo these two so the settlement tests below start from the worked example.
        assertThat(call("DELETE", "/expenses/" + shares.id(), arun.access(), null).status()).isEqualTo(204);
        assertThat(call("DELETE", "/expenses/" + multi.id(), arun.access(), null).status()).isEqualTo(204);
    }

    @Test
    @Order(25)
    void br07_optimisticLockingAndFr25_onlyCreatorOrAdminEdits() throws Exception {
        Res created = call("POST", "/groups/" + tripId + "/expenses", divya.access(),
                expense("Snacks", "300", "FOOD", "EQUAL", List.of(who(mDivya)), List.of(who(mArun), who(mDivya), who(mKarthik))));
        long id = created.id();
        int version = created.body().get("version").asInt();

        Map<String, Object> edit = expense("Snacks & chai", "300", "FOOD", "EQUAL", List.of(who(mDivya)), List.of(who(mArun), who(mDivya), who(mKarthik)));
        edit.put("version", version);
        assertThat(call("PUT", "/expenses/" + id, divya.access(), edit).status()).isEqualTo(200);

        Res stale = call("PUT", "/expenses/" + id, arun.access(), edit); // same old version
        assertThat(stale.status()).isEqualTo(409);
        assertThat(stale.body().get("message").asString()).isEqualTo("This expense was changed by Divya — review the latest version");

        Res forbidden = call("PUT", "/expenses/" + id, karthik.access(), edit);
        assertThat(forbidden.status()).as("Karthik is neither creator nor admin").isEqualTo(403);

        // BR-06 soft delete + admin restore
        assertThat(call("DELETE", "/expenses/" + id, divya.access(), null).status()).isEqualTo(204);
        JsonNode deleted = call("GET", "/groups/" + tripId + "/expenses/deleted", arun.access(), null).body();
        assertThat(deleted.findValues("id").stream().map(JsonNode::asLong)).contains(id);
        assertThat(call("POST", "/expenses/" + id + "/restore", divya.access(), null).status()).as("only admins restore").isEqualTo(403);
        assertThat(call("POST", "/expenses/" + id + "/restore", arun.access(), null).status()).isEqualTo(200);
        assertThat(call("DELETE", "/expenses/" + id, arun.access(), null).status()).isEqualTo(204);

        JsonNode activity = call("GET", "/groups/" + tripId + "/activity", arun.access(), null).body();
        List<String> actions = activity.findValues("action").stream().map(JsonNode::asString).toList();
        assertThat(actions).as("FR-52").contains("EXPENSE_ADDED", "EXPENSE_EDITED", "EXPENSE_DELETED", "EXPENSE_RESTORED");
    }

    @Test
    @Order(26)
    void fr51_searchAndFilters() throws Exception {
        String base = "/groups/" + tripId + "/expenses";
        assertThat(call("GET", base + "?q=HOT", arun.access(), null).body().get("total").asLong()).isEqualTo(1);
        assertThat(call("GET", base + "?category=FUEL", arun.access(), null).body().get("total").asLong()).isEqualTo(1);
        assertThat(call("GET", base + "?payerId=" + mDivya, arun.access(), null).body().get("total").asLong()).isEqualTo(2);
        assertThat(call("GET", base + "?from=2000-01-01&to=2000-12-31", arun.access(), null).body().get("total").asLong()).isZero();
        JsonNode list = call("GET", base, karthik.access(), null).body().get("items");
        assertThat(list.get(0).get("myShare").asLong()).as("FR-50: your share").isPositive();
    }

    // ---------------------------------------------------------------- settlements

    @Test
    @Order(30)
    void fr15_memberWithBalanceCannotBeRemoved() throws Exception {
        assertThat(call("DELETE", "/groups/" + tripId + "/members/" + mDivya, arun.access(), null).status()).isEqualTo(409);
    }

    @Test
    @Order(31)
    void ac07_settlementPendingThenConfirmedBringsBalanceToZero() throws Exception {
        long karthikOwes = -nets(tripId, karthik).get(mKarthik);
        Map<String, Object> pay = Map.of("fromMemberId", mKarthik, "toMemberId", mArun,
                "amount", String.format("%d.%02d", karthikOwes / 100, karthikOwes % 100), "method", "UPI");
        Res s = call("POST", "/groups/" + tripId + "/settlements", karthik.access(), pay);
        assertThat(s.status()).isEqualTo(201);
        assertThat(s.body().get("status").asString()).as("FR-45").isEqualTo("PENDING");
        assertThat(nets(tripId, karthik).get(mKarthik)).as("pending doesn't move balances").isEqualTo(-karthikOwes);

        JsonNode notes = call("GET", "/notifications", arun.access(), null).body();
        assertThat(notes.findValues("type").stream().map(JsonNode::asString)).as("FR-61").contains("SETTLEMENT_PENDING");

        assertThat(call("PATCH", "/settlements/" + s.id() + "/confirm", divya.access(), null).status()).as("only receiver confirms").isEqualTo(403);
        Res ok = call("PATCH", "/settlements/" + s.id() + "/confirm", arun.access(), null);
        assertThat(ok.body().get("status").asString()).isEqualTo("CONFIRMED");
        assertThat(call("GET", "/groups/" + tripId + "/balances", karthik.access(), null).body().get("myNet").asLong()).isZero();
        assertThat(call("PATCH", "/settlements/" + s.id() + "/confirm", arun.access(), null).status()).isEqualTo(409);
    }

    @Test
    @Order(32)
    void rejectedSettlementDoesNotCount() throws Exception {
        long before = nets(tripId, divya).get(mDivya);
        Res s = call("POST", "/groups/" + tripId + "/settlements", divya.access(),
                Map.of("fromMemberId", mDivya, "toMemberId", mArun, "amount", "10", "method", "CASH"));
        assertThat(call("PATCH", "/settlements/" + s.id() + "/reject", arun.access(), null).body().get("status").asString()).isEqualTo("REJECTED");
        assertThat(nets(tripId, divya).get(mDivya)).isEqualTo(before);
    }

    @Test
    @Order(33)
    void fr62_nudgeOncePer24Hours() throws Exception {
        Res first = call("POST", "/groups/" + tripId + "/nudges", arun.access(), Map.of("toMemberId", mDivya));
        assertThat(first.status()).isEqualTo(200);
        assertThat(first.body().get("message").asString()).startsWith("Hey Divya, Arun's wallet misses you");
        assertThat(call("POST", "/groups/" + tripId + "/nudges", arun.access(), Map.of("toMemberId", mDivya)).status()).isEqualTo(429);
        assertThat(call("POST", "/groups/" + tripId + "/nudges", divya.access(), Map.of("toMemberId", mArun)).status())
                .as("can't nudge someone who doesn't owe you").isEqualTo(400);
    }

    @Test
    @Order(34)
    void fr46_crossGroupSummary() throws Exception {
        JsonNode s = call("GET", "/me/summary", arun.access(), null).body();
        assertThat(s.get("totalGetBack").asLong()).isPositive();
        assertThat(s.get("net").asLong()).isEqualTo(s.get("totalGetBack").asLong() - s.get("totalOwe").asLong());
        assertThat(s.get("friends").findValues("displayName").stream().map(JsonNode::asString)).contains("Divya");
    }

    @Test
    @Order(35)
    void fr53_insightsAndFr54_reports() throws Exception {
        JsonNode ins = call("GET", "/groups/" + tripId + "/insights", arun.access(), null).body();
        assertThat(ins.get("totalSpend").asLong()).isEqualTo(760_000L);
        assertThat(ins.get("badges").findValues("key").stream().map(JsonNode::asString)).contains("BIGGEST_SPENDER", "FASTEST_SETTLER");

        HttpResponse<byte[]> pdf = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/groups/" + tripId + "/report?format=pdf"))
                .header("Authorization", "Bearer " + arun.access()).build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(new String(pdf.body(), 0, 5)).isEqualTo("%PDF-");

        HttpResponse<String> csv = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/groups/" + tripId + "/report?format=csv"))
                .header("Authorization", "Bearer " + arun.access()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(csv.body()).contains("\"Hotel\"", "Settle-up plan");
    }

    @Test
    @Order(36)
    void nfr05_storedMoneyReconciles() {
        Integer drift = jdbc.queryForObject("""
                select count(*) from expenses e where e.group_id = ? and e.is_deleted = false and (
                  e.amount <> (select coalesce(sum(amount_paid),0) from expense_payers p where p.expense_id = e.id) or
                  e.amount <> (select coalesce(sum(amount_owed),0) from expense_shares s where s.expense_id = e.id))""",
                Integer.class, tripId);
        assertThat(drift).isZero();
        Long paise = jdbc.queryForObject("select sum(amount) from expenses where group_id = ? and is_deleted = false", Long.class, tripId);
        assertThat(paise).as("BR-01 stored as integer paise").isEqualTo(760_000L);
    }

    // ---------------------------------------------------------------- archive, tokens, account

    @Test
    @Order(40)
    void br08_archivedGroupIsReadOnlyButSettlementsAllowed() throws Exception {
        assertThat(call("POST", "/groups/" + tripId + "/archive", divya.access(), null).status()).as("FR-14 admin only").isEqualTo(403);
        assertThat(call("POST", "/groups/" + tripId + "/archive", arun.access(), null).status()).isEqualTo(200);
        Res add = call("POST", "/groups/" + tripId + "/expenses", arun.access(),
                expense("Late", "1", "FOOD", "EQUAL", List.of(who(mArun)), List.of(who(mArun))));
        assertThat(add.status()).isEqualTo(409);
        Res settle = call("POST", "/groups/" + tripId + "/settlements", arun.access(),
                Map.of("fromMemberId", mDivya, "toMemberId", mArun, "amount", "1000", "method", "CASH"));
        assertThat(settle.status()).isEqualTo(201);
        assertThat(settle.body().get("status").asString()).as("receiver recorded it → auto-confirmed").isEqualTo("CONFIRMED");
        assertThat(call("POST", "/groups/" + tripId + "/reopen", arun.access(), null).status()).isEqualTo(200);
    }

    @Test
    @Order(41)
    void nfr06_refreshTokensRotateAndReuseIsDetected() throws Exception {
        Res first = call("POST", "/auth/refresh", null, Map.of("refreshToken", outsider.refresh()));
        assertThat(first.status()).isEqualTo(200);
        String next = first.body().get("refreshToken").asString();
        assertThat(next).isNotEqualTo(outsider.refresh());
        assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", outsider.refresh())).status()).isEqualTo(401);
        assertThat(call("POST", "/auth/refresh", null, Map.of("refreshToken", next)).status()).as("whole family revoked").isEqualTo(401);
    }

    @Test
    @Order(42)
    void fr04_deletedAccountShowsAsFormerMember() throws Exception {
        assertThat(call("DELETE", "/me", karthik.access(), null).status()).isEqualTo(204);
        JsonNode g = call("GET", "/groups/" + tripId, arun.access(), null).body();
        assertThat(g.get("members").findValues("displayName").stream().map(JsonNode::asString)).contains("Former member");
        Map<Long, Long> n = nets(tripId, arun);
        assertThat(n.get(mKarthik)).as("history kept").isZero();
        assertThat(n.values().stream().mapToLong(Long::longValue).sum()).isZero();
    }
}
