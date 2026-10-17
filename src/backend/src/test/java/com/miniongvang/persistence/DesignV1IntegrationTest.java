package com.miniongvang.persistence;
import com.miniongvang.service.DispatchErrors;
import com.miniongvang.security.ActorContext;
import com.miniongvang.dto.order.OrderDtos.CreateOrder;
import com.miniongvang.dto.payment.PaymentDtos.Payment;
import com.miniongvang.dto.quote.QuoteDtos.Parcel;
import com.miniongvang.dto.report.ReportDtos.Report;
import com.miniongvang.config.PersistenceContext;
import com.miniongvang.entity.CuocDonHang;
import com.miniongvang.entity.ChinhSachThanhVien;
import com.miniongvang.entity.ChinhSachPhuThu;
import com.miniongvang.entity.BieuPhi;
import com.miniongvang.entity.DonHang;
import com.miniongvang.entity.KhachHang;
import com.miniongvang.entity.PhanCongDonHang;
import com.miniongvang.entity.enums.AccountRole;
import com.miniongvang.entity.enums.AssignmentOutcome;
import com.miniongvang.entity.enums.OrderEventType;
import com.miniongvang.entity.enums.OrderStatus;
import com.miniongvang.entity.enums.MembershipTier;
import com.miniongvang.entity.enums.PolicyStatus;
import com.miniongvang.integration.FakeRouteProvider;
import com.miniongvang.seed.DesignV1DemoSeeder;
import com.miniongvang.service.AuthService;
import com.miniongvang.service.CustomerService;
import com.miniongvang.service.DispatchService;
import com.miniongvang.service.OrderService;
import com.miniongvang.service.MembershipPolicyService;
import com.miniongvang.service.PolicyPublicationService;
import com.miniongvang.service.PaymentService;
import com.miniongvang.service.VnpayPaymentService;
import com.miniongvang.integration.VnpayGateway;
import com.miniongvang.entity.enums.PaymentEventResult;
import com.miniongvang.service.QuoteService;
import com.miniongvang.service.RouteService;
import com.miniongvang.service.TransactionRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.NoSuchElementException;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Checks the new schema through the real SQL Server, JPA mapping and current service flow. */
class DesignV1IntegrationTest {
    private static PersistenceContext db;
    private static TransactionRunner tx;
    private static QuoteService quotes;
    private static OrderService orders;
    private static DispatchService dispatch;
    private static PaymentService payments;
    private static String url;

    private static ActorContext customer(int n) {
        return new ActorContext("TK-DEMO-KH-" + n, "Customer", AccountRole.KHACH_HANG,
                "KH-DEMO-" + n, null);
    }
    private static ActorContext dispatcher() {
        return new ActorContext("TK-DEMO-NV", "Dispatcher", AccountRole.TONG_DAI,
                null, null);
    }
    private static ActorContext driver() {
        return new ActorContext("TK-DEMO-TX-1", "Driver", AccountRole.TAI_XE,
                null, "TX-DEMO-1");
    }
    private static ActorContext admin() {
        return new ActorContext("TK-DEMO-ADMIN", "Administrator", AccountRole.CHU_DOI_XE, null, null);
    }
    private static List<Parcel> parcels() {
        return List.of(new Parcel("Tài liệu", null, "1.00"));
    }
    private static String newOrder() {
        var parcel = parcels();
        var quote = quotes.create(customer(1), null, "Quận 1", "Quận 3", parcel);
        return orders.create(customer(1), UUID.randomUUID().toString(),
                new CreateOrder(quote.maBaoGia(), null, "Quận 1", "Quận 3",
                        "0901234567", null, parcel)).maDon();
    }

    @Test void t20DispatcherCanFindCustomersForOrderWithoutLeakingLockedAccounts() {
        var customers = new CustomerService(db.entityManagerFactory());
        var firstPage = customers.search(dispatcher(), 0, 1, null);
        assertEquals(3, firstPage.totalElements());
        assertEquals(3, firstPage.totalPages());
        assertEquals(1, firstPage.items().size());
        assertEquals(1, customers.search(dispatcher(), 1, 1, null).items().size());
        var selected = customers.search(dispatcher(), 0, 20, "0900000002");
        assertEquals(1, selected.totalElements());
        assertEquals("KH-DEMO-2", selected.items().getFirst().maKh());
        assertEquals("VANG", selected.items().getFirst().hangThanhVien().maHang());
        assertTrue(selected.items().getFirst().hangThanhVien().conHieuLuc());
        var expired = customers.search(dispatcher(), 0, 20, "0900000003");
        assertEquals(1, expired.totalElements());
        assertEquals("KH-DEMO-3", expired.items().getFirst().maKh());
        assertEquals("VANG", expired.items().getFirst().hangThanhVien().maHang());
        assertFalse(expired.items().getFirst().hangThanhVien().conHieuLuc());
        assertEquals(0, customers.search(dispatcher(), 0, 20, "not-found").totalElements());
        assertThrows(SecurityException.class, () -> customers.search(customer(1), 0, 20, null));
        assertThrows(IllegalArgumentException.class, () -> customers.search(dispatcher(), -1, 20, null));
        assertThrows(IllegalArgumentException.class, () -> customers.search(dispatcher(), 0, 101, null));
        assertThrows(IllegalArgumentException.class, () -> customers.search(dispatcher(), 0, 20, " "));
        tx.run(em -> {
            em.find(com.miniongvang.entity.TaiKhoan.class, "TK-DEMO-KH-2")
                    .setTrangThai(com.miniongvang.entity.enums.AccountStatus.KHOA);
            return null;
        });
        assertEquals(2, customers.search(dispatcher(), 0, 20, null).totalElements());
    }

    @Test void dt09IdempotencyExpiresAtExactlyTwentyFourHoursWithoutDeletingOldOrder() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var route = new RouteService(new FakeRouteProvider(), 30_000);
        var firstQuotes = new QuoteService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC));
        var firstOrders = new OrderService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC));
        var quote = firstQuotes.create(customer(1), null, "Quận 1", "Quận 3", parcels());
        var request = new CreateOrder(quote.maBaoGia(), null, "Quận 1", "Quận 3", "0901234567", null, parcels());
        String key = UUID.randomUUID().toString();
        var original = firstOrders.create(customer(1), key, request);
        Instant expiry = start.plusSeconds(86400);
        assertEquals(original, new OrderService(db.entityManagerFactory(), route,
                Clock.fixed(expiry.minusMillis(1), ZoneOffset.UTC)).create(customer(1), key, request));
        var expiredOrders = new OrderService(db.entityManagerFactory(), route, Clock.fixed(expiry, ZoneOffset.UTC));
        assertEquals("QUOTE_EXPIRED", assertThrows(OrderService.Conflict.class,
                () -> expiredOrders.create(customer(1), key, request)).getMessage());
        var freshQuote = new QuoteService(db.entityManagerFactory(), route, Clock.fixed(expiry, ZoneOffset.UTC))
                .create(customer(1), null, "Quận 1", "Quận 3", parcels());
        var fresh = expiredOrders.create(customer(1), key, new CreateOrder(freshQuote.maBaoGia(), null,
                "Quận 1", "Quận 3", "0901234567", null, parcels()));
        assertNotEquals(original.maDon(), fresh.maDon());
        assertEquals(original.maDon(), dispatch.order(customer(1), original.maDon()).maDon());
        assertArrayEquals(new long[]{2, 1}, tableCounts(new String[]{"don_hang", "yeu_cau_tao_don"}));
    }

    @Test void dt12SuggestionsAndAssignmentRejectEveryIneligibleDriver() throws Exception {
        for (String change : List.of(
                "DELETE FROM dbo.phuong_tien WHERE ma_tx='TX-DEMO-1'",
                "UPDATE dbo.phuong_tien SET dang_hoat_dong=0 WHERE ma_tx='TX-DEMO-1'",
                "UPDATE dbo.tai_xe SET ngay_het_han_gplx='2000-01-01' WHERE ma_tx='TX-DEMO-1'",
                "UPDATE dbo.tai_khoan SET trang_thai='KHOA' WHERE ma_tk='TK-DEMO-TX-1'")) {
            resetFixture();
            String id = newOrder();
            tx.run(em -> { em.createNativeQuery(change).executeUpdate(); return null; });
            assertTrue(dispatch.suggestions(dispatcher(), id, 0, 20).items().stream()
                    .noneMatch(d -> d.maTx().equals("TX-DEMO-1")), change);
            assertEquals("DRIVER_UNAVAILABLE", assertThrows(DispatchErrors.Conflict.class,
                    () -> dispatch.assign(dispatcher(), id, "TX-DEMO-1"), change).getMessage());
            assertEquals("CHO_GAN", dispatch.order(customer(1), id).trangThai());
            assertEquals(1, dispatch.events(customer(1), id, 0, 20).items().size());
            assertEquals(0, tableCounts(new String[]{"phan_cong_don_hang"})[0]);
        }
    }

    @Test void dt15CompletedReplayCannotCloseDriversNewAssignment() {
        String old = completedOrder();
        var before = dispatch.order(customer(1), old);
        int oldEvents = dispatch.events(customer(1), old, 0, 20).items().size();
        String current = newOrder();
        dispatch.assign(dispatcher(), current, "TX-DEMO-1");
        var replay = dispatch.transition(driver(), old, OrderStatus.HOAN_TAT);
        assertEquals(before.hoanTatLuc(), replay.hoanTatLuc());
        assertEquals(oldEvents, dispatch.events(customer(1), old, 0, 20).items().size());
        assertEquals("DA_GAN", dispatch.order(customer(1), current).trangThai());
        tx.run(em -> {
            var active = new com.miniongvang.dao.DispatchDAO(em).activeOrder(current);
            assertNotNull(active); assertNull(active.getKetThucLuc()); assertNull(active.getKetQuaKetThuc());
            assertNull(em.find(com.miniongvang.entity.TaiXe.class, "TX-DEMO-1").getRanhTu());
            return null;
        });
    }

    @Test void dt16CancellationRacingPickupLeavesOneConsistentOutcome() throws Exception {
        String id = newOrder(); dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var cancel = pool.submit(() -> { ready.countDown(); start.await();
                try { return dispatch.cancel(customer(1), id, "Race cancellation").trangThai(); }
                catch (DispatchErrors.Conflict e) { return e.getMessage(); } });
            var pickup = pool.submit(() -> { ready.countDown(); start.await();
                try { return dispatch.transition(driver(), id, OrderStatus.DA_LAY_HANG).trangThai(); }
                catch (DispatchErrors.Conflict e) { return e.getMessage(); }
                catch (NoSuchElementException e) { return "RESOURCE_NOT_FOUND"; } });
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            String a = cancel.get(20, TimeUnit.SECONDS), b = pickup.get(20, TimeUnit.SECONDS);
            assertTrue((a.equals("DA_HUY") && (b.equals("ORDER_STATE_CONFLICT") || b.equals("RESOURCE_NOT_FOUND")))
                    || (a.equals("ORDER_STATE_CONFLICT") && b.equals("DA_LAY_HANG")), a + "/" + b);
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
        tx.run(em -> {
            var order = em.find(DonHang.class, id);
            var assignment = em.createQuery("from PhanCongDonHang p where p.donHang.id=:id", PhanCongDonHang.class)
                    .setParameter("id", id).getSingleResult();
            var driver = em.find(com.miniongvang.entity.TaiXe.class, "TX-DEMO-1");
            if (order.getTrangThai() == OrderStatus.DA_HUY) {
                assertNotNull(assignment.getKetThucLuc()); assertEquals(AssignmentOutcome.HUY_DON, assignment.getKetQuaKetThuc());
                assertNotNull(driver.getRanhTu());
            } else {
                assertEquals(OrderStatus.DA_LAY_HANG, order.getTrangThai()); assertNull(assignment.getKetThucLuc());
                assertNull(assignment.getKetQuaKetThuc()); assertNull(driver.getRanhTu());
            }
            return null;
        });
        assertEquals(3, dispatch.events(customer(1), id, 0, 20).items().size());
    }

    @Test void dt26DeletingReferencedAccountPolicyOrOrderCannotEraseHistory() {
        String id = completedOrder();
        String[] tables = {"tai_khoan", "cau_hinh_cuoc", "don_hang", "cuoc_don_hang", "nhat_ky_don_hang", "phan_cong_don_hang"};
        long[] before = tableCounts(tables);
        for (String sql : List.of("DELETE FROM dbo.tai_khoan WHERE ma_tk='TK-DEMO-KH-1'",
                "DELETE FROM dbo.cau_hinh_cuoc WHERE ma_bieu_phi='CUOC-DEMO-1'",
                "DELETE FROM dbo.don_hang WHERE ma_don='" + id + "'")) {
            Throwable error = assertThrows(RuntimeException.class,
                    () -> tx.run(em -> { em.createNativeQuery(sql).executeUpdate(); return null; }));
            while (error.getCause() != null) error = error.getCause();
            assertInstanceOf(java.sql.SQLException.class, error);
            assertArrayEquals(before, tableCounts(tables));
        }
        assertEquals("HOAN_TAT", dispatch.order(customer(1), id).trangThai());
        assertEquals(6, dispatch.events(customer(1), id, 0, 20).items().size());
    }

    @Test void t19InvariantQueryAcceptsValidPaymentAndDetectsWrongConfirmedAmount() throws Exception {
        String id = completedOrder();
        var payment = payments.list(customer(1), id, 0, 20).items().getFirst();
        payments.confirmCash(driver(), payment.maGiaoDich(), new BigDecimal(payment.soTien()));
        var scriptPath = java.nio.file.Path.of("scripts/check-design-v1-invariants.sql");
        if (!java.nio.file.Files.exists(scriptPath)) scriptPath = java.nio.file.Path.of("../../scripts/check-design-v1-invariants.sql");
        String sql = java.nio.file.Files.readString(scriptPath);
        try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
            try { statement.execute(sql); }
            finally { statement.execute("SET NOCOUNT OFF"); }
        }
        tx.run(em -> { em.createNativeQuery("UPDATE dbo.thanh_toan SET so_tien_thuc_nhan=so_tien_thuc_nhan+1 WHERE ma_giao_dich=:id")
                .setParameter("id", payment.maGiaoDich()).executeUpdate(); return null; });
        try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
            try {
                var error = assertThrows(java.sql.SQLException.class, () -> statement.execute(sql));
                assertEquals(51010, error.getErrorCode());
            } finally {
                // Hikari returns this physical connection to Hibernate; restore
                // JDBC row counts even when the reconciliation batch throws.
                statement.execute("SET NOCOUNT OFF");
            }
        } finally {
            tx.run(em -> { em.createNativeQuery("UPDATE dbo.thanh_toan SET so_tien_thuc_nhan=:amount WHERE ma_giao_dich=:id")
                    .setParameter("amount", new BigDecimal(payment.soTien())).setParameter("id", payment.maGiaoDich())
                    .executeUpdate(); return null; });
        }
    }

    @Test void dt01WrongProfileRoleAndDuplicateProfileAreRejectedAtomically() {
        String[] tables = {"tai_khoan", "khach_hang", "tai_xe"};
        long[] before = tableCounts(tables);
        var statements = List.of(
                "UPDATE dbo.khach_hang SET ma_tk='TK-DEMO-ADMIN' WHERE ma_kh='KH-DEMO-1'",
                "UPDATE dbo.tai_xe SET ma_tk='TK-DEMO-NV' WHERE ma_tx='TX-DEMO-1'",
                "UPDATE dbo.tai_khoan SET vai_tro='TONG_DAI' WHERE ma_tk='TK-DEMO-KH-1'",
                "UPDATE dbo.khach_hang SET ma_tk='TK-DEMO-KH-1' WHERE ma_kh='KH-DEMO-2'");
        int[] expected = {51080, 51081, 51082, 2601};
        for (int i=0; i<statements.size(); i++) {
            String sql = statements.get(i);
            Throwable error = assertThrows(RuntimeException.class, () -> tx.run(em -> {
                em.createNativeQuery(sql).executeUpdate(); return null;
            }));
            while(error.getCause()!=null) error=error.getCause();
            assertInstanceOf(java.sql.SQLException.class, error);
            int code = ((java.sql.SQLException)error).getErrorCode();
            if (i==3) assertTrue(code==2601 || code==2627); else assertEquals(expected[i],code);
            assertArrayEquals(before, tableCounts(tables));
        }
        var auth = new AuthService(db.entityManagerFactory());
        assertEquals("KH-DEMO-1", auth.current("TK-DEMO-KH-1").maKh());
        assertEquals("TX-DEMO-1", auth.current("TK-DEMO-TX-1").maTx());
    }

    private static String publishFareSuccessor(Instant now, Instant boundary) {
        var publication = new PolicyPublicationService(db.entityManagerFactory(), Clock.fixed(now, ZoneOffset.UTC));
        publication.closeFare(admin(), "CUOC-DEMO-1", boundary);
        String id = UUID.randomUUID().toString();
        tx.run(em -> {
            var fare = new BieuPhi();
            fare.setId(id); fare.setTenBieuPhi("T19 successor");
            fare.setCuocCoBan(new BigDecimal("12345.00")); fare.setDonGiaKm(new BigDecimal("10000.00"));
            fare.setKmToiThieu(BigDecimal.ZERO); fare.setHieuLucTu(boundary);
            fare.setTrangThai(PolicyStatus.NHAP); em.persist(fare); return null;
        });
        publication.publishFare(admin(), id);
        return id;
    }

    @Test void dt04PublishedSuccessorNeverChangesCommittedOrderFare() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS), boundary = start.plusSeconds(120);
        var route = new RouteService(new FakeRouteProvider(), 30_000);
        var quote = new QuoteService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC))
                .create(customer(1), null, "Quận 1", "Quận 3", parcels());
        var original = new OrderService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC))
                .create(customer(1), UUID.randomUUID().toString(), new CreateOrder(quote.maBaoGia(), null,
                        "Quận 1", "Quận 3", "0901234567", null, parcels()));
        var before = dispatch.detail(customer(1), original.maDon());
        String successor = publishFareSuccessor(start, boundary);
        var next = new QuoteService(db.entityManagerFactory(), route, Clock.fixed(boundary, ZoneOffset.UTC))
                .create(customer(1), null, "Quận 1", "Quận 3", parcels());
        assertNotEquals(quote.cuoc().tongCuoc(), next.cuoc().tongCuoc());
        assertEquals(original.cuoc(), dispatch.order(customer(1), original.maDon()).cuoc());
        var after = dispatch.detail(customer(1), original.maDon());
        // Closing a published policy's future validity window is permitted;
        // its pricing content and the order's policy reference remain immutable.
        var oldPolicy = before.phienBanChinhSach().bieuPhi();
        var historicalPolicy = after.phienBanChinhSach().bieuPhi();
        assertEquals(oldPolicy.maBieuPhi(), historicalPolicy.maBieuPhi());
        assertEquals(oldPolicy.tenBieuPhi(), historicalPolicy.tenBieuPhi());
        assertEquals(oldPolicy.cuocCoBan(), historicalPolicy.cuocCoBan());
        assertEquals(oldPolicy.donGiaKm(), historicalPolicy.donGiaKm());
        assertEquals(oldPolicy.kmToiThieu(), historicalPolicy.kmToiThieu());
        assertEquals(oldPolicy.kmToiDa(), historicalPolicy.kmToiDa());
        assertEquals(oldPolicy.hieuLucTu(), historicalPolicy.hieuLucTu());
        assertNull(oldPolicy.hieuLucDen());
        assertEquals(boundary.toString(), historicalPolicy.hieuLucDen());
        assertEquals(before.phienBanChinhSach().giamGia(), after.phienBanChinhSach().giamGia());
        assertEquals(before.phienBanChinhSach().phuThu(), after.phienBanChinhSach().phuThu());
        assertEquals(before.cuoc(), after.cuoc());
        tx.run(em -> {
            assertEquals("CUOC-DEMO-1", em.find(CuocDonHang.class, original.maDon()).getBieuPhi().getId());
            assertEquals(successor, em.find(com.miniongvang.entity.BaoGia.class, next.maBaoGia()).getMaBieuPhi());
            return null;
        });
    }

    @Test void dt05PublishedMembershipPolicyExpiresAtExactBoundaryInEveryClockZone() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS), boundary = start.plusSeconds(120);
        new PolicyPublicationService(db.entityManagerFactory(), Clock.fixed(start, ZoneOffset.UTC))
                .closeMembership(admin(), "TV-DEMO-VANG-1", boundary);
        tx.run(em -> {
            var member = em.find(KhachHang.class, "KH-DEMO-2");
            member.setThanhVienTu(start.minusSeconds(60)); member.setThanhVienDen(boundary.plusSeconds(3600));
            for (String zone : List.of("UTC", "Asia/Ho_Chi_Minh", "America/New_York")) {
                var clock = Clock.fixed(boundary, java.time.ZoneId.of(zone));
                var service = new MembershipPolicyService(clock);
                var before = service.evaluate(em, member, new BigDecimal("100.00"), clock.instant().minusMillis(1));
                assertEquals("TV-DEMO-VANG-1", before.policy().getId()); assertEquals(new BigDecimal("10.00"), before.discount());
                for (Instant at : List.of(clock.instant(), clock.instant().plusMillis(1))) {
                    var expired = service.evaluate(em, member, new BigDecimal("100.00"), at);
                    assertNull(expired.policy(), zone); assertFalse(expired.active(), zone);
                    assertEquals(new BigDecimal("0.00"), expired.discount(), zone);
                }
            }
            return null;
        });
    }

    @Test void dt07ChangedPolicyOrParcelsRejectsQuoteWithoutPartialOrder() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS), boundary = start.plusSeconds(120);
        var route = new RouteService(new FakeRouteProvider(), 30_000);
        var quote = new QuoteService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC))
                .create(customer(1), null, "Quận 1", "Quận 3", parcels());
        var original = new CreateOrder(quote.maBaoGia(), null, "Quận 1", "Quận 3", "0901234567", null, parcels());
        String[] tables = {"don_hang", "cuoc_don_hang", "chi_tiet_kien_hang", "phu_thu_don_hang", "nhat_ky_don_hang", "yeu_cau_tao_don"};
        long[] before = tableCounts(tables);
        var changedParcels = new CreateOrder(quote.maBaoGia(), null, "Quận 1", "Quận 3", "0901234567", null,
                List.of(new Parcel("Tài liệu", null, "2.00")));
        var atStart = new OrderService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC));
        assertEquals("QUOTE_CHANGED", assertThrows(OrderService.Conflict.class,
                () -> atStart.create(customer(1), UUID.randomUUID().toString(), changedParcels)).getMessage());
        assertArrayEquals(before, tableCounts(tables));
        publishFareSuccessor(start, boundary);
        var atBoundary = new OrderService(db.entityManagerFactory(), route, Clock.fixed(boundary, ZoneOffset.UTC));
        assertEquals("QUOTE_CHANGED", assertThrows(OrderService.Conflict.class,
                () -> atBoundary.create(customer(1), UUID.randomUUID().toString(), original)).getMessage());
        assertArrayEquals(before, tableCounts(tables));
        // A fresh quote permits a deliberate retry; stale quote is never repriced silently.
        var fresh = new QuoteService(db.entityManagerFactory(), route, Clock.fixed(boundary, ZoneOffset.UTC))
                .create(customer(1), null, "Quận 1", "Quận 3", parcels());
        var accepted = atBoundary.create(customer(1), UUID.randomUUID().toString(), new CreateOrder(
                fresh.maBaoGia(), null, "Quận 1", "Quận 3", "0901234567", null, parcels()));
        assertEquals(fresh.cuoc(), accepted.cuoc());
        assertEquals(1, tableCounts(new String[]{"don_hang"})[0]);
    }

    @BeforeAll static void start() throws Exception {
        url = System.getenv("TEST_DB_URL");
        if (Boolean.parseBoolean(System.getenv("REQUIRE_TEST_DB"))) assertNotNull(url);
        assumeTrue(url != null && url.matches("jdbc:sqlserver://[^;]+;databaseName=mini_ong_vang_test;encrypt=true;trustServerCertificate=true"));
        try (var connection = DriverManager.getConnection(url, System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"));
             var statement = connection.createStatement(); var result = statement.executeQuery("SELECT DB_NAME()")) {
            assertTrue(result.next());
            assertEquals("mini_ong_vang_test", result.getString(1));
        }
        db = PersistenceContext.start(url, System.getenv("TEST_DB_USER"), System.getenv("TEST_DB_PASSWORD"));
        tx = new TransactionRunner(db.entityManagerFactory());
        var routes = new RouteService(new FakeRouteProvider(), 30_000);
        quotes = new QuoteService(db.entityManagerFactory(), routes);
        orders = new OrderService(db.entityManagerFactory(), routes);
        dispatch = new DispatchService(db.entityManagerFactory());
        payments = new PaymentService(db.entityManagerFactory());
    }

    @AfterAll static void stop() { if (db != null) db.close(); }

    @BeforeEach void resetFixture() throws Exception {
        try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute("DISABLE TRIGGER dbo.trg_membership_policy_immutable ON dbo.chinh_sach_thanh_vien");
            statement.execute("DISABLE TRIGGER dbo.trg_fare_policy_immutable ON dbo.cau_hinh_cuoc");
            statement.execute("DISABLE TRIGGER dbo.trg_surcharge_policy_immutable ON dbo.cau_hinh_phu_thu");
            for (String table : List.of("phan_chia_cuoc", "yeu_cau_tao_don", "bao_gia", "su_kien_thanh_toan",
                    "xac_nhan_thanh_toan_don_hang", "thanh_toan", "nhat_ky_don_hang",
                    "chi_tiet_kien_hang", "phu_thu_don_hang", "cuoc_don_hang",
                    "phan_cong_don_hang", "don_hang", "phuong_tien", "tai_xe",
                    "khach_hang", "nhat_ky_kiem_toan", "cau_hinh_phu_thu",
                    "cau_hinh_cuoc", "chinh_sach_thanh_vien", "tai_khoan",
                    "demo_seed_manifest")) statement.executeUpdate("DELETE FROM dbo.[" + table + "]");
            statement.execute("ENABLE TRIGGER dbo.trg_membership_policy_immutable ON dbo.chinh_sach_thanh_vien");
            statement.execute("ENABLE TRIGGER dbo.trg_fare_policy_immutable ON dbo.cau_hinh_cuoc");
            statement.execute("ENABLE TRIGGER dbo.trg_surcharge_policy_immutable ON dbo.cau_hinh_phu_thu");
            connection.commit();
        }
        new DesignV1DemoSeeder(db.entityManagerFactory()).seed("design-v1-test-password");
    }

    @Test void migratedSchemaHasTwentySixDomainTablesAndTrustedConstraints() throws Exception {
        try (var connection = db.dataSource().getConnection();
             var statement = connection.createStatement();
             var result = statement.executeQuery("""
                     SELECT (SELECT COUNT(*) FROM sys.tables WHERE schema_id=SCHEMA_ID('dbo')
                             AND name NOT IN ('flyway_schema_history','demo_seed_manifest')),
                            (SELECT COUNT(*) FROM sys.foreign_keys WHERE is_disabled=1 OR is_not_trusted=1),
                            (SELECT COUNT(*) FROM sys.check_constraints WHERE is_disabled=1 OR is_not_trusted=1)
                     """)) {
            assertTrue(result.next());
            assertEquals(26, result.getInt(1));
            assertEquals(0, result.getInt(2));
            assertEquals(0, result.getInt(3));
        }
    }

    @Test void quoteAndOrderKeepVersionedFareAndReplayResponse() {
        var parcel = parcels();
        var regularQuote = quotes.create(customer(1), null, "Quận 1", "Quận 3", parcel);
        var memberQuote = quotes.create(customer(2), null, "Quận 1", "Quận 3", parcel);
        assertEquals("0.00", regularQuote.cuoc().tienGiamGia());
        assertTrue(new BigDecimal(memberQuote.cuoc().tienGiamGia()).signum() > 0);
        assertNotNull(memberQuote.hangThanhVien());
        assertTrue(memberQuote.hangThanhVien().ngayHetHan().matches("\\d{4}-\\d{2}-\\d{2}"));
        String key = UUID.randomUUID().toString();
        var request = new CreateOrder(memberQuote.maBaoGia(), null,
                "Quận 1", "Quận 3", "0901234567", null, parcel);
        var created = orders.create(customer(2), key, request);
        assertEquals("TK-DEMO-KH-2", created.maTkTao());
        assertEquals(created, orders.create(customer(2), key, request));
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(OrderService.Conflict.class,
                () -> orders.create(customer(2), key, new CreateOrder(memberQuote.maBaoGia(), null,
                        "Quận 1", "Quận 3", "0909999999", null, parcel))).getMessage());
        tx.run(em -> {
            DonHang order = em.find(DonHang.class, created.maDon());
            CuocDonHang fare = em.find(CuocDonHang.class, created.maDon());
            assertEquals("TK-DEMO-KH-2", order.getTaiKhoanTao().getId());
            assertEquals("CUOC-DEMO-1", fare.getBieuPhi().getId());
            assertEquals("TV-DEMO-VANG-1", fare.getChinhSachGiam().getId());
            assertEquals(new BigDecimal(created.cuoc().tienGiamGia()), fare.getTienGiamGia());
            return null;
        });
    }

    @Test void membershipWindowAndZeroOrFullDiscountUseExactBoundaries() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        Instant end = start.plusSeconds(300);
        tx.run(em -> {
            KhachHang customer = em.find(KhachHang.class, "KH-DEMO-2");
            customer.setThanhVienTu(start); customer.setThanhVienDen(end);
            var membership = new MembershipPolicyService();
            BigDecimal subtotal = new BigDecimal("100.00");
            assertEquals(BigDecimal.ZERO.setScale(2),
                    membership.evaluate(em, customer, subtotal, start.minusMillis(1)).discount());
            assertEquals(new BigDecimal("10.00"),
                    membership.evaluate(em, customer, subtotal, start).discount());
            assertEquals(new BigDecimal("10.00"),
                    membership.evaluate(em, customer, subtotal, end.minusMillis(1)).discount());
            assertEquals(BigDecimal.ZERO.setScale(2),
                    membership.evaluate(em, customer, subtotal, end).discount());
            for (var tier : List.of(MembershipTier.BAC, MembershipTier.KIM_CUONG)) {
                var policy = new ChinhSachThanhVien();
                policy.setId(UUID.randomUUID().toString()); policy.setHang(tier);
                policy.setTyLeGiam(tier == MembershipTier.BAC ? BigDecimal.ZERO : new BigDecimal("100.00"));
                policy.setHieuLucTu(start.minusSeconds(60)); policy.setTrangThai(PolicyStatus.CONG_BO);
                em.persist(policy);
            }
            em.flush();
            customer.setMaHang(MembershipTier.BAC);
            assertEquals(BigDecimal.ZERO.setScale(2), membership.evaluate(em, customer, subtotal, start).discount());
            customer.setMaHang(MembershipTier.KIM_CUONG);
            assertEquals(new BigDecimal("100.00"), membership.evaluate(em, customer, subtotal, start).discount());
            return null;
        });
    }

    @Test void quoteExpiresAtExactlyThreeHundredSeconds() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var route = new RouteService(new FakeRouteProvider(), 30_000);
        var quote = new QuoteService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC))
                .create(customer(1), null, "Quận 1", "Quận 3", parcels());
        var request = new CreateOrder(quote.maBaoGia(), null, "Quận 1", "Quận 3",
                "0901234567", null, parcels());
        var justBefore = new OrderService(db.entityManagerFactory(), route,
                Clock.fixed(start.plusMillis(299_999), ZoneOffset.UTC));
        assertNotNull(justBefore.create(customer(1), UUID.randomUUID().toString(), request).maDon());
        var atExpiry = new OrderService(db.entityManagerFactory(), route,
                Clock.fixed(start.plusSeconds(300), ZoneOffset.UTC));
        assertEquals("QUOTE_EXPIRED", assertThrows(OrderService.Conflict.class,
                () -> atExpiry.create(customer(1), UUID.randomUUID().toString(), request)).getMessage());
    }

    @Test void committedOrderReplaysWithoutRouteProviderOrLiveQuote() {
        Instant start = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        var route = new RouteService(new FakeRouteProvider(), 30_000);
        var quote = new QuoteService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC))
                .create(customer(1), null, "Quận 1", "Quận 3", parcels());
        var request = new CreateOrder(quote.maBaoGia(), null, "Quận 1", "Quận 3",
                "0901234567", null, parcels());
        String key = UUID.randomUUID().toString();
        var created = new OrderService(db.entityManagerFactory(), route, Clock.fixed(start, ZoneOffset.UTC))
                .create(customer(1), key, request);
        var unavailableRoute = new RouteService(new FakeRouteProvider(FakeRouteProvider.Scenario.TIMEOUT), 30_000);
        var replayService = new OrderService(db.entityManagerFactory(), unavailableRoute,
                Clock.fixed(start.plusSeconds(301), ZoneOffset.UTC));
        assertEquals(created, replayService.create(customer(1), key, request));
        assertEquals("QUOTE_EXPIRED", assertThrows(OrderService.Conflict.class,
                () -> replayService.create(customer(1), UUID.randomUUID().toString(), request)).getMessage());
    }

    @Test void failedIdempotencyWriteRollsBackWholeOrderAggregate() throws Exception {
        var quote = quotes.create(customer(1), null, "Quận 1", "Quận 3", parcels());
        var request = new CreateOrder(quote.maBaoGia(), null, "Quận 1", "Quận 3",
                "0901234567", null, parcels());
        String[] tables = {"don_hang", "cuoc_don_hang", "chi_tiet_kien_hang",
                "phu_thu_don_hang", "nhat_ky_don_hang", "yeu_cau_tao_don"};
        long[] before = tableCounts(tables);
        try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER dbo.trg_test_order_rollback ON dbo.yeu_cau_tao_don "
                    + "AFTER INSERT AS BEGIN THROW 51070, 'Injected request failure', 1; END");
        }
        try {
            assertThrows(RuntimeException.class, () -> orders.create(customer(1), UUID.randomUUID().toString(), request));
        } finally {
            try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER dbo.trg_test_order_rollback");
            }
        }
        assertArrayEquals(before, tableCounts(tables));
    }

    private static long[] tableCounts(String[] tables) {
        return tx.run(em -> {
            long[] counts = new long[tables.length];
            for (int i = 0; i < tables.length; i++)
                counts[i] = ((Number) em.createNativeQuery("SELECT COUNT(*) FROM dbo.[" + tables[i] + "]")
                        .getSingleResult()).longValue();
            return counts;
        });
    }

    @Test void dispatchLifecycleDerivesDriverAndRecordsOrderedEvents() {
        String id = newOrder();
        assertEquals(OrderStatus.CHO_GAN.name(), dispatch.order(customer(1), id).trangThai());
        assertEquals("TX-DEMO-1", dispatch.assign(dispatcher(), id, "TX-DEMO-1").maTx());
        dispatch.transition(driver(), id, OrderStatus.DA_LAY_HANG);
        collectCash(driver(), id);
        dispatch.transition(driver(), id, OrderStatus.DANG_GIAO);
        assertEquals("ARRIVAL_REQUIRED", assertThrows(DispatchErrors.Conflict.class,
                () -> dispatch.transition(driver(), id, OrderStatus.HOAN_TAT)).getMessage());
        assertEquals(OrderEventType.DEN_DIEM_NHAN, dispatch.arrive(driver(), id).loaiSuKien());
        assertEquals(OrderStatus.HOAN_TAT.name(), dispatch.transition(driver(), id, OrderStatus.HOAN_TAT).trangThai());
        var events = dispatch.events(customer(1), id, 0, 20).items();
        assertEquals(6, events.size());
        assertEquals(1, events.get(0).thuTu());
        assertEquals(OrderEventType.TAO_DON, events.get(0).loaiSuKien());
        assertNull(events.get(0).trangThaiTruoc());
        assertEquals(OrderStatus.CHO_GAN, events.get(0).trangThaiSau());
        assertEquals("TK-DEMO-KH-1", events.get(0).maTkThucHien());
        assertEquals(6, events.get(5).thuTu());
        assertEquals(OrderStatus.HOAN_TAT, events.get(5).trangThaiSau());
        tx.run(em -> {
            PhanCongDonHang assignment = em.createQuery(
                    "select p from PhanCongDonHang p where p.donHang.id=:id", PhanCongDonHang.class)
                    .setParameter("id", id).getSingleResult();
            assertNotNull(assignment.getKetThucLuc());
            assertEquals(OrderStatus.HOAN_TAT, em.find(DonHang.class, id).getTrangThai());
            return null;
        });
    }

    @Test void customerCancellationRecordsReasonAndEventWithoutChangingFare() {
        String id = newOrder();
        var before = dispatch.order(customer(1), id);
        var cancelled = dispatch.cancel(customer(1), id, "  Đổi kế hoạch  ");
        assertEquals("DA_HUY", cancelled.trangThai());
        assertNull(cancelled.maTx());
        assertNotNull(cancelled.huyLuc());
        assertEquals(before.cuoc(), cancelled.cuoc());
        tx.run(em -> {
            var order = em.find(DonHang.class, id);
            assertEquals("Đổi kế hoạch", order.getLyDoHuy());
            assertNotNull(order.getThoiGianHuy());
            return null;
        });
        var events = dispatch.events(customer(1), id, 0, 20).items();
        assertEquals(2, events.size());
        assertEquals(OrderStatus.CHO_GAN, events.get(1).trangThaiTruoc());
        assertEquals(OrderStatus.DA_HUY, events.get(1).trangThaiSau());
        assertEquals("TK-DEMO-KH-1", events.get(1).maTkThucHien());
        assertEquals("Đổi kế hoạch", events.get(1).ghiChu());
        assertEquals("ORDER_STATE_CONFLICT", assertThrows(DispatchErrors.Conflict.class,
                () -> dispatch.cancel(customer(1), id, "again")).getMessage());
        assertEquals(2, dispatch.events(customer(1), id, 0, 20).items().size());
    }

    @Test void assignedCancellationReleasesDriverAndKeepsAssignmentHistory() {
        String id = newOrder();
        dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        var cancelled = dispatch.cancel(dispatcher(), id, "Khách đổi kế hoạch");
        assertEquals("DA_HUY", cancelled.trangThai());
        assertNull(cancelled.maTx());
        tx.run(em -> {
            var assignment = em.createQuery("from PhanCongDonHang p where p.donHang.id=:id", PhanCongDonHang.class)
                    .setParameter("id", id).getSingleResult();
            assertNotNull(assignment.getKetThucLuc());
            assertEquals(AssignmentOutcome.HUY_DON, assignment.getKetQuaKetThuc());
            assertEquals("Khách đổi kế hoạch", assignment.getLyDo());
            assertNotNull(em.find(com.miniongvang.entity.TaiXe.class, "TX-DEMO-1").getRanhTu());
            return null;
        });
        assertEquals(3, dispatch.events(customer(1), id, 0, 20).items().size());
    }

    @Test void cancellationChecksOwnerRoleReasonStateAndExactDeadline() {
        String id = newOrder();
        assertThrows(java.util.NoSuchElementException.class,
                () -> dispatch.cancel(customer(2), id, "not mine"));
        assertThrows(SecurityException.class, () -> dispatch.cancel(driver(), id, "driver"));
        for (String reason : java.util.Arrays.asList(null, "", "  ", "x".repeat(501)))
            assertThrows(IllegalArgumentException.class, () -> dispatch.cancel(customer(1), id, reason));
        Instant created = tx.run(em -> em.find(DonHang.class, id).getThoiGianTao());
        var justBefore = new DispatchService(db.entityManagerFactory(),
                Clock.fixed(created.plusMillis(299_999), ZoneOffset.UTC));
        assertEquals("DA_HUY", justBefore.cancel(customer(1), id, "within window").trangThai());
        for (int seconds : List.of(300, 301)) {
            String lateId = newOrder();
            Instant lateCreated = tx.run(em -> em.find(DonHang.class, lateId).getThoiGianTao());
            var late = new DispatchService(db.entityManagerFactory(),
                    Clock.fixed(lateCreated.plusSeconds(seconds), ZoneOffset.UTC));
            assertEquals("CANCELLATION_WINDOW_EXPIRED", assertThrows(DispatchErrors.Conflict.class,
                    () -> late.cancel(customer(1), lateId, "too late")).getMessage());
        }
        String inProgress = newOrder();
        dispatch.assign(dispatcher(), inProgress, "TX-DEMO-1");
        dispatch.transition(driver(), inProgress, OrderStatus.DA_LAY_HANG);
        assertEquals("ORDER_STATE_CONFLICT", assertThrows(DispatchErrors.Conflict.class,
                () -> dispatch.cancel(customer(1), inProgress, "too late in lifecycle")).getMessage());
    }

    @Test void concurrentAssignmentAndCancellationLeaveNoActiveAssignment() throws Exception {
        String id = newOrder();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var assign = executor.submit(() -> {
                try { dispatch.assign(dispatcher(), id, "TX-DEMO-1"); return true; }
                catch (DispatchErrors.Conflict conflict) {
                    assertEquals("ORDER_STATE_CONFLICT", conflict.getMessage()); return false;
                }
            });
            var cancel = executor.submit(() -> dispatch.cancel(customer(1), id, "Hủy trước khi lấy hàng"));
            assertEquals("DA_HUY", cancel.get(20, TimeUnit.SECONDS).trangThai());
            assign.get(20, TimeUnit.SECONDS);
        }
        tx.run(em -> {
            assertEquals(OrderStatus.DA_HUY, em.find(DonHang.class, id).getTrangThai());
            assertEquals(0L, em.createQuery("select count(p) from PhanCongDonHang p "
                    + "where p.donHang.id=:id and p.ketThucLuc is null", Long.class)
                    .setParameter("id", id).getSingleResult());
            return null;
        });
    }

    @Test void cancellationRollsBackOrderAndAssignmentWhenEventWriteFails() throws Exception {
        String id = newOrder();
        dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER dbo.trg_test_cancel_rollback ON dbo.nhat_ky_don_hang "
                    + "AFTER INSERT AS BEGIN THROW 51072, 'Injected event failure', 1; END");
        }
        try {
            assertThrows(RuntimeException.class, () -> dispatch.cancel(customer(1), id, "Hủy thử"));
        } finally {
            try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER dbo.trg_test_cancel_rollback");
            }
        }
        assertEquals("DA_GAN", dispatch.order(customer(1), id).trangThai());
        assertEquals("TX-DEMO-1", dispatch.order(customer(1), id).maTx());
        assertEquals(2, dispatch.events(customer(1), id, 0, 20).items().size());
        tx.run(em -> {
            assertNull(em.find(DonHang.class, id).getThoiGianHuy());
            var assignment = em.createQuery("from PhanCongDonHang p where p.donHang.id=:id", PhanCongDonHang.class)
                    .setParameter("id", id).getSingleResult();
            assertNull(assignment.getKetThucLuc());
            assertNull(assignment.getKetQuaKetThuc());
            return null;
        });
    }

    @Test void rejectedDriverCannotBeAssignedAgainToSameOrder() {
        String id = newOrder();
        dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        assertEquals(OrderStatus.CHO_GAN.name(), dispatch.reject(driver(), id, "Xe hỏng").trangThai());
        assertEquals("DRIVER_UNAVAILABLE", assertThrows(DispatchErrors.Conflict.class,
                () -> dispatch.assign(dispatcher(), id, "TX-DEMO-1")).getMessage());
        assertEquals("TX-DEMO-2", dispatch.assign(dispatcher(), id, "TX-DEMO-2").maTx());
    }

    @Test void concurrentAssignmentsLeaveOnlyOneActiveDriver() throws Exception {
        String id = newOrder();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> assignOrConflict(id, "TX-DEMO-1"));
            var second = executor.submit(() -> assignOrConflict(id, "TX-DEMO-2"));
            assertEquals(1, (first.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(20, TimeUnit.SECONDS) ? 1 : 0));
        }
        tx.run(em -> {
            assertEquals(1L, em.createQuery("select count(p) from PhanCongDonHang p "
                    + "where p.donHang.id=:id and p.ketThucLuc is null", Long.class)
                    .setParameter("id", id).getSingleResult());
            return null;
        });
    }

    @Test void assignmentRechecksAccountAndVehicleAfterConcurrentChange() throws Exception {
        assertAssignmentWaitsForChange(
                "UPDATE tai_khoan SET trang_thai='KHOA' WHERE ma_tk='TK-DEMO-TX-1'",
                "UPDATE tai_khoan SET trang_thai='HOAT_DONG' WHERE ma_tk='TK-DEMO-TX-1'");
        assertAssignmentWaitsForChange(
                "UPDATE phuong_tien SET dang_hoat_dong=0 WHERE ma_phuong_tien='XE-DEMO-1'",
                "UPDATE phuong_tien SET dang_hoat_dong=1 WHERE ma_phuong_tien='XE-DEMO-1'");
    }

    private static void assertAssignmentWaitsForChange(String change, String restore) throws Exception {
        String id = newOrder();
        try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement();
             var executor = Executors.newSingleThreadExecutor()) {
            connection.setAutoCommit(false);
            assertEquals(1, statement.executeUpdate(change));
            var future = executor.submit(() -> {
                try { dispatch.assign(dispatcher(), id, "TX-DEMO-1"); return "ASSIGNED"; }
                catch (DispatchErrors.Conflict conflict) { return conflict.getMessage(); }
            });
            assertThrows(java.util.concurrent.TimeoutException.class, () -> future.get(250, TimeUnit.MILLISECONDS));
            connection.commit();
            assertEquals("DRIVER_UNAVAILABLE", future.get(20, TimeUnit.SECONDS));
        }
        tx.run(em -> { em.createNativeQuery(restore).executeUpdate(); return null; });
        assertEquals(OrderStatus.CHO_GAN.name(), dispatch.order(customer(1), id).trangThai());
    }

    @Test void concurrentPolicyPublicationSerializesEmptyScopeAndAuditsWinner() throws Exception {
        Instant start = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS);
        String first = UUID.randomUUID().toString(), second = UUID.randomUUID().toString();
        tx.run(em -> {
            for (String id : List.of(first, second)) {
                var policy = new ChinhSachThanhVien();
                policy.setId(id); policy.setHang(MembershipTier.BAC);
                policy.setTyLeGiam(new BigDecimal("5.00"));
                Instant begins = id.equals(first) ? start : start.plusSeconds(1);
                policy.setHieuLucTu(begins); policy.setHieuLucDen(start.plusSeconds(86400));
                policy.setTrangThai(PolicyStatus.NHAP); em.persist(policy);
            }
            return null;
        });
        var publication = new PolicyPublicationService(db.entityManagerFactory());
        try (var executor = Executors.newFixedThreadPool(2)) {
            var a = executor.submit(() -> publishOrConflict(publication, first));
            var b = executor.submit(() -> publishOrConflict(publication, second));
            assertEquals(1, (a.get(20, TimeUnit.SECONDS) ? 1 : 0)
                    + (b.get(20, TimeUnit.SECONDS) ? 1 : 0));
        }
        tx.run(em -> {
            assertEquals(1L, em.createQuery("select count(p) from ChinhSachThanhVien p "
                    + "where p.hang=:tier and p.trangThai=:status", Long.class)
                    .setParameter("tier", MembershipTier.BAC)
                    .setParameter("status", PolicyStatus.CONG_BO).getSingleResult());
            assertEquals(1L, em.createQuery("select count(a) from NhatKyKiemToan a "
                    + "where a.loaiDoiTuong='chinh_sach_thanh_vien' and a.hanhDong=:action", Long.class)
                    .setParameter("action", com.miniongvang.entity.enums.AuditAction.CONG_BO).getSingleResult());
            return null;
        });
    }

    @Test void auditFailureRollsBackPolicyPublication() throws Exception {
        String id = UUID.randomUUID().toString();
        tx.run(em -> {
            var policy = new ChinhSachThanhVien();
            policy.setId(id); policy.setHang(MembershipTier.BAC);
            policy.setTyLeGiam(new BigDecimal("5.00"));
            policy.setHieuLucTu(Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.MILLIS));
            policy.setTrangThai(PolicyStatus.NHAP); em.persist(policy);
            return null;
        });
        try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER dbo.trg_test_policy_audit_rollback ON dbo.nhat_ky_kiem_toan "
                    + "AFTER INSERT AS BEGIN THROW 51071, 'Injected audit failure', 1; END");
        }
        try {
            var publication = new PolicyPublicationService(db.entityManagerFactory());
            assertThrows(RuntimeException.class, () -> publication.publishMembership(admin(), id));
        } finally {
            try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER dbo.trg_test_policy_audit_rollback");
            }
        }
        tx.run(em -> {
            assertEquals(PolicyStatus.NHAP, em.find(ChinhSachThanhVien.class, id).getTrangThai());
            assertEquals(0L, em.createQuery("select count(a) from NhatKyKiemToan a "
                    + "where a.maDoiTuong=:id", Long.class).setParameter("id", id).getSingleResult());
            return null;
        });
    }

    private static boolean publishOrConflict(PolicyPublicationService publication, String id) {
        try { publication.publishMembership(admin(), id); return true; }
        catch (IllegalStateException conflict) { return false; }
    }

    @Test void publishedPoliciesRejectDirectMutationAndControlledSuccessorsDoNotOverlap() {
        assertThrows(RuntimeException.class, () -> tx.run(em -> {
            em.createNativeQuery("UPDATE chinh_sach_thanh_vien SET ty_le_giam=20 WHERE ma_chinh_sach='TV-DEMO-VANG-1'")
                    .executeUpdate(); return null;
        }));
        assertThrows(RuntimeException.class, () -> tx.run(em -> {
            em.createNativeQuery("UPDATE cau_hinh_cuoc SET ten_bieu_phi=N'Changed' WHERE ma_bieu_phi='CUOC-DEMO-1'")
                    .executeUpdate(); return null;
        }));
        assertThrows(RuntimeException.class, () -> tx.run(em -> {
            em.createNativeQuery("UPDATE cau_hinh_phu_thu SET so_tien=9000 WHERE ma_phu_thu='PHU-DEMO-1'")
                    .executeUpdate(); return null;
        }));
        var publication = new PolicyPublicationService(db.entityManagerFactory());
        Instant boundary = Instant.now().plusSeconds(120).truncatedTo(ChronoUnit.MILLIS);
        publication.closeFare(admin(), "CUOC-DEMO-1", boundary);
        publication.closeSurcharge(admin(), "PHU-DEMO-1", boundary);
        String fareId = UUID.randomUUID().toString(), surchargeId = UUID.randomUUID().toString();
        tx.run(em -> {
            var fare = new BieuPhi();
            fare.setId(fareId); fare.setTenBieuPhi("Biểu phí kế tiếp");
            fare.setCuocCoBan(new BigDecimal("22000.00")); fare.setDonGiaKm(new BigDecimal("5000.00"));
            fare.setKmToiThieu(BigDecimal.ZERO); fare.setHieuLucTu(boundary);
            fare.setTrangThai(PolicyStatus.NHAP); em.persist(fare);
            var extra = new ChinhSachPhuThu();
            extra.setId(surchargeId); extra.setMaLoaiPhuThu("DICH_VU");
            extra.setTenPhuThu("Phụ thu kế tiếp"); extra.setSoTien(new BigDecimal("6000.00"));
            extra.setHieuLucTu(boundary); extra.setTrangThai(PolicyStatus.NHAP); em.persist(extra);
            return null;
        });
        publication.publishFare(admin(), fareId);
        publication.publishSurcharge(admin(), surchargeId);
        assertThrows(IllegalStateException.class, () -> publication.closeFare(admin(), "CUOC-DEMO-1", boundary.plusSeconds(1)));
        tx.run(em -> {
            assertEquals(4L, em.createQuery("select count(a) from NhatKyKiemToan a", Long.class).getSingleResult());
            return null;
        });
    }
    private static boolean assignOrConflict(String id, String driver) {
        try { dispatch.assign(dispatcher(), id, driver); return true; }
        catch (DispatchErrors.Conflict conflict) { return false; }
    }


    private static Payment collectCash(ActorContext actor, String id) {
        var payment = payments.createCash(actor, id, "fixture-" + id);
        payments.confirmCash(actor, payment.maGiaoDich(), new BigDecimal(payment.soTien()));
        return payment;
    }

    private static String collectionOrder(String method) {
        String id = newOrder();
        tx.run(em -> { em.find(DonHang.class,id).setPhuongThucThanhToan(method); return null; });
        dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        dispatch.transition(driver(), id, OrderStatus.DA_LAY_HANG);
        return id;
    }

    // Historical unallocated money is fixture data, not a permitted driver confirmation.
    private static Payment historicalExtraReceipt(String id, BigDecimal amount) {
        String paymentId = UUID.randomUUID().toString();
        tx.run(em -> {
            var p = new com.miniongvang.entity.LanThanhToan();
            p.setId(paymentId); p.setDonHang(em.find(DonHang.class,id)); p.setMaThamChieu(paymentId);
            p.setPhuongThuc(com.miniongvang.entity.enums.PaymentMethod.TIEN_MAT);
            p.setTrangThai(com.miniongvang.entity.enums.PaymentStatus.THANH_CONG);
            p.setTaoLuc(Instant.now()); p.setGiaiQuyetLuc(Instant.now()); p.setThuLuc(Instant.now());
            p.setSoTienThucNhan(amount); p.setTaiKhoanXacNhan(em.find(com.miniongvang.entity.TaiKhoan.class,driver().maNguoiDung()));
            em.persist(p); return null;
        });
        return payments.get(driver(),paymentId);
    }

    private static String completedOrder() {
        String id = newOrder();
        dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        dispatch.transition(driver(), id, OrderStatus.DA_LAY_HANG);
        collectCash(driver(), id);
        dispatch.transition(driver(), id, OrderStatus.DANG_GIAO);
        dispatch.arrive(driver(), id);
        dispatch.transition(driver(), id, OrderStatus.HOAN_TAT);
        return id;
    }

    @Test void cashPaymentRequiresMilestoneAndCorrectConfirmer() {
        String id = newOrder();
        dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        assertEquals("PAYMENT_MILESTONE_REQUIRED", assertThrows(PaymentService.Conflict.class,
                () -> payments.createCash(driver(), id, "early")).getMessage());
        dispatch.transition(driver(), id, OrderStatus.DA_LAY_HANG);
        var payment = payments.createCash(driver(), id, "cash-key");
        assertEquals(payment.maGiaoDich(), payments.createCash(driver(), id, "cash-key").maGiaoDich());
        String otherOrder = newOrder();
        assertEquals("IDEMPOTENCY_CONFLICT", assertThrows(PaymentService.Conflict.class,
                () -> payments.createCash(driver(), otherOrder, "cash-key")).getMessage());
        assertEquals("PAYMENT_PENDING", assertThrows(PaymentService.Conflict.class,
                () -> payments.createCash(driver(), id, "another-key")).getMessage());
        assertThrows(SecurityException.class, () -> payments.confirmCash(customer(1), payment.maGiaoDich(),
                new BigDecimal(payment.soTien())));
        var wrongDriver = new ActorContext("TK-DEMO-TX-2", "Other", AccountRole.TAI_XE, null, "TX-DEMO-2");
        assertThrows(SecurityException.class, () -> payments.confirmCash(wrongDriver, payment.maGiaoDich(),
                new BigDecimal(payment.soTien())));
        assertFalse(payments.confirmCash(driver(), payment.maGiaoDich(), new BigDecimal(payment.soTien())).canDoiSoat());
        assertEquals("DA_THANH_TOAN", dispatch.order(customer(1), id).thanhToan().trangThai());
        assertEquals("0.00", dispatch.order(customer(1), id).thanhToan().soTienConPhaiThu());
        assertEquals("PAYMENT_ALREADY_PAID", assertThrows(PaymentService.Conflict.class,
                () -> payments.createCash(driver(), id, "paid-again")).getMessage());
        assertFalse(payments.confirmCash(driver(), payment.maGiaoDich(), new BigDecimal(payment.soTien())).canDoiSoat());
    }

    @Test void wrongCashAmountIsRejectedWithoutRecordingCollection() {
        String id = collectionOrder("TIEN_MAT");
        var p = payments.createCash(driver(), id, "wrong-amount");
        assertThrows(PaymentService.Conflict.class, () -> payments.confirmCash(driver(), p.maGiaoDich(), new BigDecimal(p.soTien()).add(BigDecimal.ONE)));
        assertNull(payments.get(driver(), p.maGiaoDich()).soTienThucNhan());
        assertFalse(payments.get(driver(), p.maGiaoDich()).daXacNhanDu());
        assertThrows(SecurityException.class, () -> payments.confirmCash(dispatcher(), p.maGiaoDich(), new BigDecimal(p.soTien())));
    }

    @Test void freeOrderDoesNotCreatePayment() {
        String id = collectionOrder("TIEN_MAT");
        tx.run(em -> {
            var details = new com.miniongvang.dao.DonHangDAO(em).findDetailsV1(id);
            BigDecimal extras = details.surcharges().stream()
                    .map(p -> p.getChinhSachPhuThu().getSoTien()).reduce(BigDecimal.ZERO, BigDecimal::add);
            details.fare().setTienGiamGia(details.fare().getCuocGoc().add(extras));
            return null;
        });
        assertThrows(PaymentService.NotRequired.class,
                () -> payments.createCash(driver(), id, "free-order"));
        assertEquals(0, payments.list(customer(1), id, 0, 20).totalElements());
        assertEquals("MIEN_CUOC", dispatch.order(customer(1), id).thanhToan().trangThai());
        dispatch.transition(driver(),id,OrderStatus.DANG_GIAO);
        dispatch.arrive(driver(),id);
        dispatch.transition(driver(),id,OrderStatus.HOAN_TAT);
    }

    @Test void simultaneousCashRequestsLeaveOneUnresolvedAttempt() throws Exception {
        String id = collectionOrder("TIEN_MAT");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return createCashOrConflict(id, "parallel-1"); });
            var second = pool.submit(() -> { start.await(); return createCashOrConflict(id, "parallel-2"); });
            start.countDown();
            assertNotEquals(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(1, payments.list(customer(1), id, 0, 20).totalElements());
        } finally { pool.shutdownNow(); }
    }

    @Test void simultaneousCashConfirmationsSettleOnce() throws Exception {
        String id = collectionOrder("TIEN_MAT");
        var payment = payments.createCash(driver(), id, "confirm-parallel");
        BigDecimal due = new BigDecimal(payment.soTien());
        var pool = Executors.newFixedThreadPool(2);
        try {
            var start = new java.util.concurrent.CountDownLatch(1);
            var first = pool.submit(() -> { start.await(); return payments.confirmCash(driver(), payment.maGiaoDich(), due); });
            var second = pool.submit(() -> { start.await(); return payments.confirmCash(driver(), payment.maGiaoDich(), due); });
            start.countDown();
            assertFalse(first.get(15, TimeUnit.SECONDS).canDoiSoat());
            assertFalse(second.get(15, TimeUnit.SECONDS).canDoiSoat());
            assertEquals(1L, (long) tx.run(em -> em.createQuery(
                    "select count(c) from XacNhanThanhToanDonHang c where c.donHang.id=:id", Long.class)
                    .setParameter("id", id).getSingleResult()));
        } finally { pool.shutdownNow(); }
    }

    @Test void secondRealCollectionRemainsUnallocated() {
        String id = collectionOrder("TIEN_MAT");
        var first = payments.createCash(driver(), id, "first-collection");
        BigDecimal due = new BigDecimal(first.soTien());
        payments.confirmCash(driver(), first.maGiaoDich(), due);
        String extraId = UUID.randomUUID().toString();
        tx.run(em -> {
            em.createNativeQuery("""
                    INSERT INTO thanh_toan(ma_giao_dich,ma_don,ma_tham_chieu,phuong_thuc,trang_thai,
                      tao_luc,giai_quyet_luc,so_tien_thuc_nhan,thu_luc,ma_tk_xac_nhan)
                    VALUES (:id,:orderId,:reference,'TIEN_MAT','THANH_CONG',
                      SYSUTCDATETIME(),SYSUTCDATETIME(),:amount,SYSUTCDATETIME(),'TK-DEMO-NV')
                    """).setParameter("id", extraId).setParameter("orderId", id)
                    .setParameter("reference", "EXTRA-" + extraId).setParameter("amount", due).executeUpdate();
            return null;
        });
        assertTrue(payments.get(dispatcher(), extraId).canDoiSoat());
        assertTrue(dispatch.order(customer(1), id).thanhToan().canDoiSoat());
        assertEquals("DA_THANH_TOAN", dispatch.order(customer(1), id).thanhToan().trangThai());
    }

    @Test void failedConfirmationRollsBackCollection() throws Exception {
        String id = collectionOrder("TIEN_MAT");
        var payment = payments.createCash(driver(), id, "rollback-collection");
        try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER dbo.trg_test_payment_rollback ON dbo.xac_nhan_thanh_toan_don_hang "
                    + "AFTER INSERT AS BEGIN THROW 51072, 'Injected confirmation failure', 1; END");
        }
        try {
            assertThrows(RuntimeException.class, () -> payments.confirmCash(driver(), payment.maGiaoDich(),
                    new BigDecimal(payment.soTien())));
        } finally {
            try (var connection = db.dataSource().getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER dbo.trg_test_payment_rollback");
            }
        }
        assertEquals("CHO_XU_LY", payments.get(customer(1), payment.maGiaoDich()).trangThai().name());
        assertEquals("DANG_XU_LY", dispatch.order(customer(1), id).thanhToan().trangThai());
    }

    @Test void paymentCannotSettleAnotherOrder() {
        String firstOrder = collectionOrder("TIEN_MAT");
        String secondOrder = newOrder();
        var payment = payments.createCash(driver(), firstOrder, "wrong-order-link");
        assertThrows(RuntimeException.class, () -> tx.run(em -> {
            em.createNativeQuery("""
                    INSERT INTO xac_nhan_thanh_toan_don_hang(ma_don,ma_giao_dich)
                    VALUES (:orderId,:paymentId)
                    """).setParameter("orderId", secondOrder)
                    .setParameter("paymentId", payment.maGiaoDich()).executeUpdate();
            return null;
        }));
        payments.confirmCash(driver(), payment.maGiaoDich(), new BigDecimal(payment.soTien()));
        assertEquals("CHUA_THANH_TOAN", dispatch.order(customer(1), secondOrder).thanhToan().trangThai());
    }

    private static String createCashOrConflict(String id, String key) {
        try { payments.createCash(driver(), id, key); return "CREATED"; }
        catch (PaymentService.Conflict conflict) { return conflict.getMessage(); }
    }

    @Test void paymentIndexRejectsSecondPendingAttemptOfSameState() {
        String id = newOrder();
        tx.run(em -> {
            em.createNativeQuery("""
                    INSERT INTO thanh_toan(ma_giao_dich,ma_don,ma_tham_chieu,phuong_thuc,trang_thai,tao_luc)
                    VALUES (:id,:orderId,:reference,'TIEN_MAT','CHO_XU_LY',SYSUTCDATETIME())
                    """).setParameter("id", UUID.randomUUID().toString()).setParameter("orderId", id)
                    .setParameter("reference", UUID.randomUUID().toString()).executeUpdate();
            return null;
        });
        assertThrows(RuntimeException.class, () -> tx.run(em -> {
            em.createNativeQuery("""
                    INSERT INTO thanh_toan(ma_giao_dich,ma_don,ma_tham_chieu,phuong_thuc,trang_thai,tao_luc)
                    VALUES (:id,:orderId,:reference,'VNPAY_QR','CHO_XU_LY',SYSUTCDATETIME())
                    """).setParameter("id", UUID.randomUUID().toString()).setParameter("orderId", id)
                    .setParameter("reference", UUID.randomUUID().toString()).executeUpdate();
            return null;
        }));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"NGUOI_DAT,TIEN_MAT", "NGUOI_DAT,VNPAY_QR", "NGUOI_NHAN,TIEN_MAT", "NGUOI_NHAN,VNPAY_QR"})
    void collectionMatrixLocksNextStepUntilVerified(String payer, String method) {
        String id = newOrder();
        tx.run(em -> { var d=em.find(DonHang.class,id); d.setBenThanhToan(payer); d.setPhuongThucThanhToan(method); return null; });
        dispatch.assign(dispatcher(),id,"TX-DEMO-1");
        assertThrows(PaymentService.Conflict.class, () -> requestCollection(driver(),id,method,"early"));
        assertThrows(SecurityException.class, () -> requestCollection(customer(1),id,method,"customer"));
        dispatch.transition(driver(),id,OrderStatus.DA_LAY_HANG);
        boolean sender = payer.equals("NGUOI_DAT");
        if (!sender) {
            assertThrows(PaymentService.Conflict.class, () -> requestCollection(driver(),id,method,"before-arrival"));
            dispatch.transition(driver(),id,OrderStatus.DANG_GIAO);
            assertThrows(PaymentService.Conflict.class, () -> requestCollection(driver(),id,method,"before-arrival-2"));
            dispatch.arrive(driver(),id);
        }
        OrderStatus next = sender ? OrderStatus.DANG_GIAO : OrderStatus.HOAN_TAT;
        assertEquals("PAYMENT_REQUIRED", assertThrows(DispatchErrors.Conflict.class,
                () -> dispatch.transition(driver(),id,next)).getMessage());
        var wrong = new ActorContext("TK-DEMO-TX-2","Other",AccountRole.TAI_XE,null,"TX-DEMO-2");
        assertThrows(SecurityException.class, () -> requestCollection(wrong,id,method,"wrong"));
        var p=requestCollection(driver(),id,method,"matrix");
        assertEquals(p.maGiaoDich(),requestCollection(driver(),id,method,"matrix").maGiaoDich());
        assertThrows(PaymentService.Conflict.class, () -> requestCollection(driver(),id,method,"second"));
        if (method.equals("TIEN_MAT")) {
            assertThrows(SecurityException.class, () -> payments.confirmCash(wrong,p.maGiaoDich(),new BigDecimal(p.soTien())));
            assertThrows(SecurityException.class, () -> payments.confirmCash(dispatcher(),p.maGiaoDich(),new BigDecimal(p.soTien())));
            payments.confirmCash(driver(),p.maGiaoDich(),new BigDecimal(p.soTien()));
            var replay=payments.confirmCash(driver(),p.maGiaoDich(),new BigDecimal(p.soTien())).payment();
            assertEquals(driver().maNguoiDung(),replay.maTkThu());
            assertEquals(p.soTien(),replay.soTienGiuHo());
        } else {
            assertThrows(PaymentService.Conflict.class, () -> payments.confirmCash(driver(),p.maGiaoDich(),new BigDecimal(p.soTien())));
            var vnp = new VnpayPaymentService(db.entityManagerFactory(),vnpayGateway());
            var ipn = signedIpn(p,new BigDecimal(p.soTien()).movePointRight(2).toBigIntegerExact().toString(),"00","MATRIX");
            vnp.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_RETURN,ipn);
            assertTrue(payments.get(driver(),p.maGiaoDich()).daXacNhanDu());
            vnp.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,ipn);
            assertTrue(vnp.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,ipn).duplicate());
        }
        dispatch.transition(driver(),id,next);
        if (sender) {
            dispatch.arrive(driver(),id);
            dispatch.incident(driver(),id,com.miniongvang.entity.enums.IncidentType.TU_CHOI_NHAN,"Recipient refused");
            assertEquals("DANG_GIAO",dispatch.order(driver(),id).trangThai());
            assertTrue(payments.get(driver(),p.maGiaoDich()).daXacNhanDu());
            assertEquals(1,payments.list(driver(),id,0,20).totalElements());
        }
    }

    private static Payment requestCollection(ActorContext actor,String id,String method,String key) {
        return method.equals("TIEN_MAT") ? payments.createCash(actor,id,key)
                : payments.createOnline(actor,id,key,"127.0.0.1",vnpayGateway());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"24","02"})
    void failedOrCancelledVnpayCanRetryAndLateDuplicateNeedsReview(String status) {
        String id=collectionOrder("VNPAY_QR");
        var p=requestCollection(driver(),id,"VNPAY_QR","late");
        var vnp=new VnpayPaymentService(db.entityManagerFactory(),vnpayGateway());
        String amount=new BigDecimal(p.soTien()).movePointRight(2).toBigIntegerExact().toString();
        vnp.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,signedIpn(p,amount,status,"FAILED"));
        assertTrue(payments.get(driver(),p.maGiaoDich()).canDoiSoat());
        var retry=requestCollection(driver(),id,"VNPAY_QR","new");
        assertNotEquals(p.maGiaoDich(),retry.maGiaoDich());
        assertEquals("CHO_THU_LAI_DEMO",payments.get(driver(),p.maGiaoDich()).trangThai().name());
        assertThrows(DispatchErrors.Conflict.class, () -> dispatch.transition(driver(),id,OrderStatus.DANG_GIAO));
        assertEquals(PaymentEventResult.DA_AP_DUNG,vnp.apply(
                com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,
                signedIpn(retry,amount,"00","RETRY")).result());
        dispatch.transition(driver(),id,OrderStatus.DANG_GIAO);
        // A late success for the retired attempt must not be counted as a second fare.
        assertEquals(PaymentEventResult.CAN_DOI_SOAT,vnp.apply(
                com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,
                signedIpn(p,amount,"00","LATE")).result());
        dispatch.arrive(driver(),id);
        assertThrows(DispatchErrors.Conflict.class, () -> dispatch.transition(driver(),id,OrderStatus.HOAN_TAT));
        assertTrue(payments.get(driver(),p.maGiaoDich()).canDoiSoat());
    }

    private static final String VNPAY_TEST_SECRET = "test-only-secret";
    private static VnpayGateway vnpayGateway() {
        return new VnpayGateway("TEST1234", VNPAY_TEST_SECRET,
                "https://example.test/api/payments/vnpay/return",
                URI.create("https://sandbox.vnpayment.vn/paymentv2/vpcpay.html"),
                URI.create("http://127.0.0.1:1/query"), HttpClient.newHttpClient());
    }
    private static Map<String,String> signedIpn(Payment payment,String amount,String status,String transaction) {
        return signedIpn(payment,amount,status,transaction,VnpayGateway.orderInfo(payment.maDon()));
    }
    private static Map<String,String> signedIpn(Payment payment,String amount,String status,
                                                String transaction,String orderInfo) {
        Map<String,String> message=new TreeMap<>();
        String reference=vnpayGateway().parseQuery(URI.create(payment.paymentUrl()).getRawQuery()).get("vnp_TxnRef");
        message.put("vnp_TmnCode","TEST1234"); message.put("vnp_TxnRef",reference);
        message.put("vnp_OrderInfo",orderInfo);
        message.put("vnp_Amount",amount); message.put("vnp_ResponseCode",status);
        message.put("vnp_TransactionStatus",status); message.put("vnp_TransactionNo",transaction);
        message.put("vnp_PayDate","20261011150000");
        String canonical=message.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(),StandardCharsets.UTF_8)
                +"="+URLEncoder.encode(e.getValue(),StandardCharsets.UTF_8)).reduce((a,b)->a+"&"+b).orElse("");
        try {
            Mac mac=Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(VNPAY_TEST_SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA512"));
            message.put("vnp_SecureHash",java.util.HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        } catch (Exception failure) { throw new AssertionError(failure); }
        return message;
    }

    @Test void signedVnpayReturnSettlesSandboxCheckoutAndLaterIpnIsIdempotent() {
        String order=collectionOrder("VNPAY_QR");
        var gateway=vnpayGateway();
        var service=new VnpayPaymentService(db.entityManagerFactory(),gateway);
        var payment=payments.createOnline(driver(),order,"online-once","127.0.0.1",gateway);
        assertEquals(payment.paymentUrl(),payments.createOnline(driver(),order,"online-once","127.0.0.1",gateway).paymentUrl());
        var message=signedIpn(payment,new BigDecimal(payment.soTien()).movePointRight(2).toBigIntegerExact().toString(),"00","VNP-1");
        var browserReturn=service.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_RETURN,message);
        assertEquals(PaymentEventResult.DA_AP_DUNG,browserReturn.result());
        assertEquals(order,browserReturn.returnOrderId());
        assertEquals(order,service.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_RETURN,message).returnOrderId());
        assertTrue(payments.get(customer(1),payment.maGiaoDich()).daXacNhanDu());
        assertEquals(PaymentEventResult.KHONG_THAY_DOI,service.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,message).result());
        assertTrue(service.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,message).duplicate());
        assertTrue(payments.get(customer(1),payment.maGiaoDich()).daXacNhanDu());
        assertEquals("DA_THANH_TOAN",dispatch.order(customer(1),order).thanhToan().trangThai());
        assertEquals(2L,(long)tx.run(em -> em.createQuery("select count(e) from SuKienThanhToan e",Long.class).getSingleResult()));
    }

    @Test void expiredSandboxCheckoutGetsNewLinkWithoutQueryDr() {
        String order=collectionOrder("VNPAY_QR");
        var gateway=vnpayGateway();
        var first=payments.createOnline(driver(),order,"demo-expired-one","127.0.0.1",gateway);
        var later=new PaymentService(db.entityManagerFactory(),
                Clock.fixed(Instant.now().plus(16,ChronoUnit.MINUTES),ZoneOffset.UTC));
        var second=later.createOnline(driver(),order,"demo-expired-two","127.0.0.1",gateway);
        assertNotEquals(first.maGiaoDich(),second.maGiaoDich());
        assertEquals("CHO_THU_LAI_DEMO",payments.get(driver(),first.maGiaoDich()).trangThai().name());
        assertFalse(payments.get(driver(),first.maGiaoDich()).canDoiSoat());
        assertEquals(second.paymentUrl(),later.createOnline(
                driver(),order,"demo-expired-two","127.0.0.1",gateway).paymentUrl());
    }

    @Test void vnpayInvalidMismatchLateAndTimeoutAreRetained() {
        String order=collectionOrder("VNPAY_QR");
        var gateway=vnpayGateway();
        var service=new VnpayPaymentService(db.entityManagerFactory(),gateway);
        var first=payments.createOnline(driver(),order,"online-invalid","127.0.0.1",gateway);
        String cents=new BigDecimal(first.soTien()).movePointRight(2).toBigIntegerExact().toString();
        var forged=new TreeMap<>(signedIpn(first,cents,"00","VNP-FORGED"));
        forged.put("vnp_Amount","99900");
        var invalidReturn=service.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_RETURN,forged);
        assertEquals(PaymentEventResult.KHONG_HOP_LE,invalidReturn.result());
        assertNull(invalidReturn.returnOrderId());
        var mismatchedReturn=service.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_RETURN,
                signedIpn(first,cents,"00","VNP-1","other order"));
        assertEquals(PaymentEventResult.KHONG_KHOP,mismatchedReturn.result());
        assertNull(mismatchedReturn.returnOrderId());
        assertEquals("CHUA_RO_KET_QUA",service.reconcile(customer(1),first.maGiaoDich()).trangThai().name());
        assertThrows(NoSuchElementException.class,() -> service.reconcile(customer(2),first.maGiaoDich()));
        assertEquals(PaymentEventResult.CAN_DOI_SOAT,service.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,
                signedIpn(first,"99900","00","VNP-WRONG-AMOUNT")).result());
        assertTrue(payments.get(customer(1),first.maGiaoDich()).canDoiSoat());
        assertEquals("CHUA_THANH_TOAN",dispatch.order(customer(1),order).thanhToan().trangThai());
        assertThrows(PaymentService.Conflict.class, () -> payments.createOnline(driver(),order,"online-second","127.0.0.1",gateway));
        assertEquals(PaymentEventResult.CAN_DOI_SOAT,service.apply(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,
                signedIpn(first,cents,"00","VNP-LATE")).result());
        assertTrue(payments.get(customer(1),first.maGiaoDich()).canDoiSoat());
    }

    @Test void concurrentVnpayIpnsCreateOneConfirmation() throws Exception {
        String order=collectionOrder("VNPAY_QR");
        var gateway=vnpayGateway();
        var service=new VnpayPaymentService(db.entityManagerFactory(),gateway);
        var payment=payments.createOnline(driver(),order,"parallel-ipn","127.0.0.1",gateway);
        var ipn=signedIpn(payment,new BigDecimal(payment.soTien()).movePointRight(2)
                .toBigIntegerExact().toString(),"00","VNP-CONCURRENT");
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var one=pool.submit(() -> { start.await(); return service.apply(
                    com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,ipn); });
            var two=pool.submit(() -> { start.await(); return service.apply(
                    com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN,ipn); });
            start.countDown();
            assertNotEquals(one.get(15,TimeUnit.SECONDS).duplicate(),two.get(15,TimeUnit.SECONDS).duplicate());
            assertEquals(1L,(long)tx.run(em -> em.createQuery(
                    "select count(c) from XacNhanThanhToanDonHang c where c.donHang.id=:id",Long.class)
                    .setParameter("id",order).getSingleResult()));
            assertEquals(1L,(long)tx.run(em -> em.createQuery(
                    "select count(e) from SuKienThanhToan e",Long.class).getSingleResult()));
        } finally { pool.shutdownNow(); }
    }

    @Test void signedQueryResultCanSettleButPendingQueryCannot() throws Exception {
        String order=collectionOrder("VNPAY_QR");
        var gateway=vnpayGateway();
        var service=new VnpayPaymentService(db.entityManagerFactory(),gateway);
        var payment=payments.createOnline(driver(),order,"query-signed","127.0.0.1",gateway);
        String reference=gateway.parseQuery(URI.create(payment.paymentUrl()).getRawQuery()).get("vnp_TxnRef");
        String amount=new BigDecimal(payment.soTien()).movePointRight(2).toBigIntegerExact().toString();
        var pending=signedQuery(reference,amount,"01");
        assertEquals(PaymentEventResult.KHONG_THAY_DOI,service.apply(
                com.miniongvang.entity.enums.PaymentEventSource.VNPAY_QUERY,pending).result());
        assertFalse(payments.get(customer(1),payment.maGiaoDich()).daXacNhanDu());
        var success=signedQuery(reference,amount,"00");
        assertEquals(PaymentEventResult.DA_AP_DUNG,service.apply(
                com.miniongvang.entity.enums.PaymentEventSource.VNPAY_QUERY,success).result());
        assertTrue(payments.get(customer(1),payment.maGiaoDich()).daXacNhanDu());
    }

    @Test void expiredUnpaidVnpayAttemptCanBeRetriedOnlyAfterSignedNegativeQuery() throws Exception {
        String order=collectionOrder("VNPAY_QR");
        var gateway=vnpayGateway();
        var first=payments.createOnline(driver(),order,"expired-first","127.0.0.1",gateway);
        String reference=gateway.parseQuery(URI.create(first.paymentUrl()).getRawQuery()).get("vnp_TxnRef");
        var early=new VnpayPaymentService(db.entityManagerFactory(),gateway);
        assertEquals(PaymentEventResult.KHONG_THAY_DOI,early.apply(
                com.miniongvang.entity.enums.PaymentEventSource.VNPAY_QUERY,
                signedQuery(reference,null,null,"91")).result());
        assertThrows(PaymentService.Conflict.class,() -> payments.createOnline(
                driver(),order,"expired-second","127.0.0.1",gateway));

        var later=new VnpayPaymentService(db.entityManagerFactory(),gateway,
                Clock.fixed(Instant.now().plus(21,ChronoUnit.MINUTES),ZoneOffset.UTC));
        assertEquals(PaymentEventResult.DA_AP_DUNG,later.apply(
                com.miniongvang.entity.enums.PaymentEventSource.VNPAY_QUERY,
                signedQuery(reference,null,null,"91")).result());
        assertEquals("KHONG_THU_DA_XAC_MINH",payments.get(driver(),first.maGiaoDich()).trangThai().name());
        assertFalse(payments.get(driver(),first.maGiaoDich()).canDoiSoat());
        var second=payments.createOnline(driver(),order,"expired-second","127.0.0.1",gateway);
        assertNotEquals(first.maGiaoDich(),second.maGiaoDich());
        assertEquals(second.maGiaoDich(),payments.createOnline(
                driver(),order,"expired-second","127.0.0.1",gateway).maGiaoDich());
    }

    private static Map<String,String> signedQuery(String reference,String amount,String status) throws Exception {
        return signedQuery(reference,amount,status,"00");
    }

    private static Map<String,String> signedQuery(String reference,String amount,String status,String responseCode) throws Exception {
        Map<String,String> result=new TreeMap<>();
        result.put("vnp_ResponseId",UUID.randomUUID().toString()); result.put("vnp_Command","querydr");
        result.put("vnp_ResponseCode",responseCode); result.put("vnp_Message","success");
        result.put("vnp_TmnCode","TEST1234"); result.put("vnp_TxnRef",reference);
        if (amount!=null) result.put("vnp_Amount",amount);
        result.put("vnp_BankCode","NCB");
        result.put("vnp_PayDate","20261011150000"); result.put("vnp_TransactionNo","VNP-QUERY");
        result.put("vnp_TransactionType","01");
        if (status!=null) result.put("vnp_TransactionStatus",status);
        result.put("vnp_OrderInfo","Query "+reference);
        String[] names={"vnp_ResponseId","vnp_Command","vnp_ResponseCode","vnp_Message","vnp_TmnCode",
                "vnp_TxnRef","vnp_Amount","vnp_BankCode","vnp_PayDate","vnp_TransactionNo",
                "vnp_TransactionType","vnp_TransactionStatus","vnp_OrderInfo"};
        String canonical=java.util.Arrays.stream(names).map(name -> result.getOrDefault(name,""))
                .collect(java.util.stream.Collectors.joining("|"));
        Mac mac=Mac.getInstance("HmacSHA512");
        mac.init(new SecretKeySpec(VNPAY_TEST_SECRET.getBytes(StandardCharsets.UTF_8),"HmacSHA512"));
        result.put("vnp_SecureHash",java.util.HexFormat.of().formatHex(mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8))));
        return result;
    }

    @Test void databaseRejectsBrokenForeignKeyAndNegativeFare() {
        assertThrows(RuntimeException.class, () -> tx.run(em -> {
            em.createNativeQuery("UPDATE khach_hang SET ma_tk='MISSING' WHERE ma_kh='KH-DEMO-1'")
                    .executeUpdate(); return null;
        }));
        assertThrows(RuntimeException.class, () -> tx.run(em -> {
            em.createNativeQuery("UPDATE cau_hinh_cuoc SET cuoc_co_ban=-1 WHERE ma_bieu_phi='CUOC-DEMO-1'")
                    .executeUpdate(); return null;
        }));
    }

    private static com.miniongvang.service.ReportService reports() { return new com.miniongvang.service.ReportService(db.entityManagerFactory()); }
    private static Report report(String from,String to,com.miniongvang.dto.report.ReportDtos.Group group) {
        return reports().revenue(admin(),java.time.LocalDate.parse(from),java.time.LocalDate.parse(to),group);
    }
    private static void t17OrderTime(String id,String completed) {
        tx.run(em -> { var d=em.find(DonHang.class,id);var time=Instant.parse(completed);
            d.setThoiGianTao(time.minusSeconds(60));d.setThoiGianHoanTat(time);return null; });
    }
    private static void t17PaymentTime(String id,String received) {
        tx.run(em -> { var p=em.find(com.miniongvang.entity.LanThanhToan.class,id);var time=Instant.parse(received);
            p.setTaoLuc(time.minusSeconds(1));p.setGiaiQuyetLuc(time);p.setThuLuc(time);return null; });
    }
    @Test void removedCodTablesDoNotBreakCashCompletionRevenueAndDriverEarnings() {
        tx.run(em -> {
            for (String table : List.of("su_kien_nop_cod", "lan_nop_cod", "khoan_cod_trong_dot", "dot_nop_cod")) {
                assertNull(em.createNativeQuery("select object_id(:name, 'U')")
                        .setParameter("name", "dbo." + table).getSingleResult());
            }
            assertNotNull(em.createNativeQuery("select object_id('dbo.phan_chia_cuoc', 'U')").getSingleResult());
            return null;
        });
        String id = completedOrder();
        BigDecimal fare = new BigDecimal(dispatch.order(customer(1), id).cuoc().tongCuoc());
        BigDecimal company = fare.multiply(new BigDecimal("0.20"))
                .setScale(0, java.math.RoundingMode.HALF_UP).setScale(2);
        var from = tx.run(em -> em.find(DonHang.class, id).getThoiGianHoanTat()
                .atZone(java.time.ZoneId.of("Asia/Ho_Chi_Minh")).toLocalDate());
        var to = from.plusDays(1);
        var result = reports().revenue(admin(), from, to, com.miniongvang.dto.report.ReportDtos.Group.THANG);
        assertEquals(fare.toPlainString(), result.tong().tienThucThu());
        assertEquals(company.toPlainString(), result.tong().phanCongTy());
        assertEquals(fare.subtract(company).toPlainString(), result.tong().phanTaiXe());
        var earnings = new com.miniongvang.service.DriverEarningsService(db.entityManagerFactory());
        assertEquals(fare.subtract(company).toPlainString(), earnings.mine(driver(), from, to).phanTaiXePhatSinh());
        assertEquals(fare.toPlainString(), earnings.mine(driver(), from, to).tongCuocDaHoanTat());
        assertTrue(earnings.drivers(admin(), from, to).stream().anyMatch(d -> d.maTx().equals("TX-DEMO-1")));
    }

    @Test void t17ReportsRequireActiveAuthorizedAccountAndValidDates() {
        var from=java.time.LocalDate.of(2026,10,1); var to=from.plusDays(1);
        assertThrows(SecurityException.class,()->reports().revenue(customer(1),from,to,com.miniongvang.dto.report.ReportDtos.Group.NGAY));
        assertDoesNotThrow(()->reports().revenue(dispatcher(),from,to,com.miniongvang.dto.report.ReportDtos.Group.NGAY));
        assertThrows(SecurityException.class,()->reports().revenue(driver(),from,to,com.miniongvang.dto.report.ReportDtos.Group.NGAY));
        assertThrows(SecurityException.class,()->reports().revenue(null,from,to,com.miniongvang.dto.report.ReportDtos.Group.NGAY));
        assertThrows(com.miniongvang.service.ReportService.DateRangeInvalid.class,()->reports().revenue(admin(),to,from,com.miniongvang.dto.report.ReportDtos.Group.NGAY));
        assertThrows(com.miniongvang.service.ReportService.DateRangeInvalid.class,()->reports().revenue(admin(),from,from,com.miniongvang.dto.report.ReportDtos.Group.NGAY));
        assertThrows(com.miniongvang.service.ReportService.DateRangeInvalid.class,()->reports().revenue(admin(),null,to,com.miniongvang.dto.report.ReportDtos.Group.NGAY));
        tx.run(em->{em.find(com.miniongvang.entity.TaiKhoan.class,admin().maNguoiDung()).setTrangThai(com.miniongvang.entity.enums.AccountStatus.KHOA);return null;});
        assertThrows(SecurityException.class,()->reports().revenue(admin(),from,to,com.miniongvang.dto.report.ReportDtos.Group.NGAY));
    }
    @Test void t17EmptyPeriodIsZeroAndGroupingClipsCalendarBoundaries() {
        var day=report("2026-10-01","2026-10-03",com.miniongvang.dto.report.ReportDtos.Group.NGAY);
        assertEquals(2,day.items().size());assertEquals("2026-09-30T17:00:00Z",day.items().getFirst().batDau());
        assertEquals("0.00",day.tong().giaTriDonHoanTat());assertEquals("0.00",day.tong().tienThucThu());
        assertEquals("0.00",day.tong().tienThuChuaPhanBo());assertEquals("0.00",day.tong().cuocConPhaiThuCuoiKy());
        assertEquals(0,day.tong().soDonHoanTat());assertEquals(0,day.tong().soDonHuy());
        var week=report("2026-10-01","2026-10-13",com.miniongvang.dto.report.ReportDtos.Group.TUAN);
        assertEquals(3,week.items().size());assertEquals("2026-10-04T17:00:00Z",week.items().getFirst().ketThuc());
        assertEquals("2026-10-12T17:00:00Z",week.items().getLast().ketThuc());
        var month=report("2026-09-29","2026-11-02",com.miniongvang.dto.report.ReportDtos.Group.THANG);
        assertEquals(3,month.items().size());assertEquals("2026-09-30T17:00:00Z",month.items().getFirst().ketThuc());
    }
    @Test void t17LaterReceiptDoesNotEraseEarlierReceivable() {
        String id=completedOrder();String fare=dispatch.order(customer(1),id).cuoc().tongCuoc();
        var payment=payments.list(customer(1), id, 0, 20).items().getFirst();
        payments.confirmCash(driver(),payment.maGiaoDich(),new BigDecimal(fare));
        t17OrderTime(id,"2026-10-05T03:00:00Z");t17PaymentTime(payment.maGiaoDich(),"2026-10-07T03:00:00Z");
        var early=report("2026-10-05","2026-10-06",com.miniongvang.dto.report.ReportDtos.Group.NGAY);
        assertEquals(fare,early.tong().giaTriDonHoanTat());assertEquals("0.00",early.tong().tienThucThu());
        assertEquals(fare,early.tong().cuocConPhaiThuCuoiKy());
        var beforeReceipt=report("2026-10-06","2026-10-07",com.miniongvang.dto.report.ReportDtos.Group.NGAY);
        assertEquals("0.00",beforeReceipt.tong().giaTriDonHoanTat());assertEquals(fare,beforeReceipt.tong().cuocConPhaiThuCuoiKy());
        var late=report("2026-10-07","2026-10-08",com.miniongvang.dto.report.ReportDtos.Group.NGAY);
        assertEquals("0.00",late.tong().giaTriDonHoanTat());assertEquals(fare,late.tong().tienThucThu());
        assertEquals("0.00",late.tong().tienThuChuaPhanBo());assertEquals("0.00",late.tong().cuocConPhaiThuCuoiKy());
        var whole=report("2026-10-05","2026-10-08",com.miniongvang.dto.report.ReportDtos.Group.NGAY);
        assertEquals("0.00",whole.tong().cuocConPhaiThuCuoiKy());
        assertEquals(fare,whole.items().getFirst().chiTieu().cuocConPhaiThuCuoiKy());
    }
    @Test void t17UnallocatedReceiptAndManyChildrenDoNotMultiplyFare() {
        tx.run(em->{var p=new ChinhSachPhuThu();p.setId("PHU-T17-2");p.setMaLoaiPhuThu("BAO_QUAN");p.setTenPhuThu("Bảo quản");
            p.setSoTien(new BigDecimal("7000.00"));p.setHieuLucTu(Instant.now().minusSeconds(60));p.setTrangThai(PolicyStatus.CONG_BO);em.persist(p);return null;});
        var parcels=List.of(new Parcel("Hoa",null,"1.00"),new Parcel("Tài liệu",null,"2.00"));
        var q=quotes.create(customer(1),null,"Quận 1","Quận 3",parcels);
        String id=orders.create(customer(1),UUID.randomUUID().toString(),new CreateOrder(q.maBaoGia(),null,"Quận 1","Quận 3","0901234567",null,parcels)).maDon();
        dispatch.assign(dispatcher(),id,"TX-DEMO-1");dispatch.transition(driver(),id,OrderStatus.DA_LAY_HANG);
        var full=collectCash(driver(),id);dispatch.transition(driver(),id,OrderStatus.DANG_GIAO);dispatch.arrive(driver(),id);dispatch.transition(driver(),id,OrderStatus.HOAN_TAT);
        var partial=historicalExtraReceipt(id,new BigDecimal("5000.00"));
        t17OrderTime(id,"2026-10-05T03:00:00Z");t17PaymentTime(partial.maGiaoDich(),"2026-10-05T04:00:00Z");t17PaymentTime(full.maGiaoDich(),"2026-10-05T05:00:00Z");
        tx.run(em->{ for(int i=0;i<3;i++){var e=new com.miniongvang.entity.SuKienThanhToan();e.setId(UUID.randomUUID().toString());
            e.setLanThanhToan(em.find(com.miniongvang.entity.LanThanhToan.class,full.maGiaoDich()));e.setNguon(com.miniongvang.entity.enums.PaymentEventSource.VNPAY_IPN);
            e.setKhoaSuKien(UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", ""));e.setNhanLuc(Instant.now());e.setChuKyHopLe(true);e.setKetQuaXuLy(PaymentEventResult.DA_AP_DUNG);e.setNoiDungJson("{}");em.persist(e);}return null;});
        var result=report("2026-10-05","2026-10-06",com.miniongvang.dto.report.ReportDtos.Group.NGAY).tong();
        assertEquals(full.soTien(),result.giaTriDonHoanTat());assertEquals(1,result.soDonHoanTat());
        assertEquals(new BigDecimal(full.soTien()).add(new BigDecimal("5000.00")).toPlainString(),result.tienThucThu());
        assertEquals("5000.00",result.tienThuChuaPhanBo());assertEquals("0.00",result.cuocConPhaiThuCuoiKy());
    }
    @Test void t17EndBoundaryExcludesCompletionAndReceiptExactlyAtEnd() {
        String id=completedOrder();var p=payments.list(customer(1), id, 0, 20).items().getFirst();payments.confirmCash(driver(),p.maGiaoDich(),new BigDecimal(p.soTien()));
        t17OrderTime(id,"2026-10-05T17:00:00Z");t17PaymentTime(p.maGiaoDich(),"2026-10-05T17:00:00Z");
        var before=report("2026-10-05","2026-10-06",com.miniongvang.dto.report.ReportDtos.Group.NGAY).tong();
        assertEquals(0,before.soDonHoanTat());assertEquals("0.00",before.tienThucThu());assertEquals("0.00",before.cuocConPhaiThuCuoiKy());
        var after=report("2026-10-06","2026-10-07",com.miniongvang.dto.report.ReportDtos.Group.NGAY).tong();
        assertEquals(1,after.soDonHoanTat());assertEquals(p.soTien(),after.tienThucThu());
    }
    @Test void t17CancelledAndPendingOrdersDoNotCreateRevenueOrDebt() {
        String id=newOrder();dispatch.cancel(customer(1),id,"Hủy kiểm thử");newOrder();
        tx.run(em->{var d=em.find(DonHang.class,id);d.setThoiGianTao(Instant.parse("2026-10-05T02:00:00Z"));d.setThoiGianHuy(Instant.parse("2026-10-05T03:00:00Z"));return null;});
        var result=report("2026-10-05","2026-10-06",com.miniongvang.dto.report.ReportDtos.Group.NGAY).tong();
        assertEquals(1,result.soDonHuy());assertEquals(0,result.soDonHoanTat());assertEquals("0.00",result.giaTriDonHoanTat());
        assertEquals("0.00",result.cuocConPhaiThuCuoiKy());
    } 
    @Test void t16DetailKeepsPolicyVersionsAndProtectsOwnership() {
        String id = newOrder();
        var detail = dispatch.detail(customer(1), id);
        assertEquals(dispatch.order(customer(1), id).cuoc(), detail.cuoc());
        assertEquals("CUOC-DEMO-1", detail.phienBanChinhSach().bieuPhi().maBieuPhi());
        assertNull(detail.phienBanChinhSach().giamGia());
        assertNull(detail.xacNhanThanhToan());
        assertTrue(detail.phanCong().isEmpty());
        assertEquals(1, detail.nhatKy().getFirst().thuTu());
        assertThrows(NoSuchElementException.class, () -> dispatch.detail(customer(2), id));
        assertThrows(NoSuchElementException.class, () -> dispatch.events(customer(2), id, 0, 20));
        assertThrows(SecurityException.class, () -> dispatch.detail(admin(), id));
        assertThrows(SecurityException.class, () -> dispatch.detail(null, id));
        assertEquals(id, dispatch.detail(dispatcher(), id).maDon());
    }

    @Test void t16DetailIncludesRejectedAssignmentsAndConfirmedPayment() {
        String id = newOrder();
        dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        dispatch.reject(driver(), id, "Không thể nhận đơn");
        dispatch.assign(dispatcher(), id, "TX-DEMO-2");
        var second = new ActorContext("TK-DEMO-TX-2", "Driver", AccountRole.TAI_XE, null, "TX-DEMO-2");
        dispatch.transition(second, id, OrderStatus.DA_LAY_HANG);
        var payment = collectCash(second, id);
        dispatch.transition(second, id, OrderStatus.DANG_GIAO);
        dispatch.arrive(second, id);
        dispatch.transition(second, id, OrderStatus.HOAN_TAT);
        payments.confirmCash(second, payment.maGiaoDich(), new BigDecimal(payment.soTien()));
        var detail = dispatch.detail(customer(1), id);
        assertEquals(2, detail.phanCong().size());
        assertTrue(detail.phanCong().stream().anyMatch(a -> "TU_CHOI".equals(a.ketQuaKetThuc()) && "Không thể nhận đơn".equals(a.lyDo())));
        assertTrue(detail.phanCong().stream().anyMatch(a -> "HOAN_TAT".equals(a.ketQuaKetThuc())));
        assertEquals(payment.maGiaoDich(), detail.xacNhanThanhToan().maGiaoDich());
        assertEquals(payment.soTien(), detail.xacNhanThanhToan().soTienThucNhan());
        assertEquals("TK-DEMO-TX-2", detail.xacNhanThanhToan().maTkXacNhan());
        assertEquals("0.00", detail.thanhToan().soTienConPhaiThu());
        var summary = dispatch.orders(customer(1), 0, 100, null, null, OrderStatus.HOAN_TAT);
        assertEquals(1, summary.totalElements());
        assertEquals(payment.soTien(), summary.summary().tongCuoc());
        assertThrows(NoSuchElementException.class, () -> dispatch.detail(driver(), id));
    }

    @Test void t16EventsUseSequenceWhenTimestampsTie() {
        String id = newOrder();
        Instant same = tx.run(em -> em.find(DonHang.class, id).getThoiGianTao());
        var frozen = new DispatchService(db.entityManagerFactory(), Clock.fixed(same, ZoneOffset.UTC));
        frozen.assign(dispatcher(), id, "TX-DEMO-1");
        frozen.reject(driver(), id, "Lý do từ chối");
        var detail = dispatch.detail(customer(1), id);
        assertEquals(3, detail.nhatKy().size());
        for (int i = 0; i < 3; i++) {
            assertEquals(i + 1, detail.nhatKy().get(i).thuTu());
            assertEquals(same.toString(), detail.nhatKy().get(i).thoiGian());
        }
        assertEquals(2, dispatch.events(customer(1), id, 1, 1).items().getFirst().thuTu());
    }

    @Test void t16HistoryFiltersPagesAndSummarizesWithoutChildMultiplication() {
        tx.run(em -> {
            var extra = new ChinhSachPhuThu();
            extra.setId("PHU-T16-2"); extra.setMaLoaiPhuThu("BAO_QUAN");
            extra.setTenPhuThu("Phụ thu kiểm thử T16"); extra.setSoTien(new BigDecimal("7000.00"));
            extra.setHieuLucTu(Instant.now().minusSeconds(60)); extra.setTrangThai(PolicyStatus.CONG_BO);
            em.persist(extra); return null;
        });
        var parcels = List.of(new Parcel("Hoa", null, "1.00"), new Parcel("Tài liệu", null, "2.00"));
        var quote = quotes.create(customer(1), null, "Quận 1", "Quận 3", parcels);
        String id = orders.create(customer(1), UUID.randomUUID().toString(), new CreateOrder(
                quote.maBaoGia(), null, "Quận 1", "Quận 3", "0901234567", null, parcels)).maDon();
        String other = newOrder();
        dispatch.assign(dispatcher(), id, "TX-DEMO-1");
        dispatch.reject(driver(), id, "Từ chối");
        var page = dispatch.orders(customer(1), 0, 1, null, null, null);
        var all = dispatch.orders(customer(1), 0, 100, null, null, null);
        assertEquals(all.totalElements(), page.summary().tongSoDon());
        assertEquals(all.summary(), page.summary());
        BigDecimal sum = all.items().stream().map(o -> new BigDecimal(o.cuoc().tongCuoc())).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, sum.compareTo(new BigDecimal(page.summary().tongCuoc())));
        assertEquals(2, dispatch.detail(customer(1), id).kienHang().size());
        assertEquals(2, dispatch.detail(customer(1), id).phienBanChinhSach().phuThu().size());
        var status = dispatch.orders(customer(1), 0, 100, null, null, OrderStatus.CHO_GAN);
        assertTrue(status.items().stream().allMatch(o -> o.trangThai().equals("CHO_GAN")));
        assertTrue(dispatch.orders(customer(1), 10000, 20, null, null, null).items().isEmpty());
        var empty = dispatch.orders(customer(1), 0, 20, java.time.LocalDate.of(2099,1,1), java.time.LocalDate.of(2099,1,2), null);
        assertEquals(0, empty.totalElements()); assertEquals("0.00", empty.summary().tongCuoc());
        assertThrows(DispatchErrors.DateRangeInvalid.class, () -> dispatch.orders(customer(1),0,20,java.time.LocalDate.of(2026,1,2),java.time.LocalDate.of(2026,1,1),null));
        assertThrows(DispatchErrors.DateRangeInvalid.class, () -> dispatch.orders(customer(1),0,20,java.time.LocalDate.of(2026,1,1),java.time.LocalDate.of(2026,1,1),null));
        assertThrows(IllegalArgumentException.class, () -> dispatch.orders(customer(1),-1,20,null,null,null));
        assertThrows(IllegalArgumentException.class, () -> dispatch.orders(customer(1),0,101,null,null,null));
        assertFalse(dispatch.orders(customer(2),0,100,null,null,null).items().stream().anyMatch(o -> o.maDon().equals(id) || o.maDon().equals(other)));
    }

    @Test void t16DatesUseVietnamDayBoundariesAndTotalsCoverAllPages() {
        String first = newOrder(), second = newOrder(), excluded = newOrder();
        tx.run(em -> {
            em.find(DonHang.class, first).setThoiGianTao(Instant.parse("2026-10-10T17:00:00Z"));
            em.find(DonHang.class, second).setThoiGianTao(Instant.parse("2026-10-11T16:59:59Z"));
            em.find(DonHang.class, excluded).setThoiGianTao(Instant.parse("2026-10-11T17:00:00Z"));
            return null;
        });
        var from = java.time.LocalDate.of(2026,10,11);
        var to = from.plusDays(1);
        var page = dispatch.orders(customer(1),0,1,from,to,null);
        assertEquals(2, page.totalElements()); assertEquals(second,page.items().getFirst().maDon());
        assertEquals(first,dispatch.orders(customer(1),1,1,from,to,null).items().getFirst().maDon());
        assertEquals(0, new BigDecimal(page.summary().tongCuoc()).compareTo(
                new BigDecimal(dispatch.order(customer(1),first).cuoc().tongCuoc())
                        .add(new BigDecimal(dispatch.order(customer(1),second).cuoc().tongCuoc()))));
    }

    @Test void t16DispatcherCreatedOrderBelongsToCustomerAndUsesSavedDiscount() {
        var parcel = parcels();
        var quote = quotes.create(dispatcher(), "KH-DEMO-2", "Quận 1", "Quận 3", parcel);
        var order = orders.create(dispatcher(), UUID.randomUUID().toString(),
                new CreateOrder(quote.maBaoGia(), "KH-DEMO-2", "Quận 1", "Quận 3", "0901234567", null, parcel));
        var detail = dispatch.detail(customer(2),order.maDon());
        assertEquals("TK-DEMO-NV",detail.maTkTao());
        assertNotNull(detail.phienBanChinhSach().giamGia());
        String saved = tx.run(em -> em.find(CuocDonHang.class,order.maDon()).getChinhSachGiam().getId());
        assertEquals(saved,detail.phienBanChinhSach().giamGia().maChinhSach());
        var cutoff = Instant.now().plusSeconds(60).truncatedTo(ChronoUnit.MILLIS);
        new PolicyPublicationService(db.entityManagerFactory()).closeFare(admin(),"CUOC-DEMO-1",cutoff);
        assertEquals(detail.cuoc(),dispatch.detail(customer(2),order.maDon()).cuoc());
        assertEquals("CUOC-DEMO-1",dispatch.detail(customer(2),order.maDon()).phienBanChinhSach().bieuPhi().maBieuPhi());
        assertThrows(NoSuchElementException.class,()->dispatch.detail(customer(1),order.maDon()));
        assertTrue(dispatch.orders(customer(2),0,100,null,null,null).items().stream().anyMatch(o->o.maDon().equals(order.maDon())));

    }
}
