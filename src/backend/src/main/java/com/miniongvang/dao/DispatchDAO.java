package com.miniongvang.dao;

import com.miniongvang.entity.DonHang;
import com.miniongvang.entity.PhanCongDonHang;
import com.miniongvang.entity.PhuongTien;
import com.miniongvang.entity.SuKienDonHang;
import com.miniongvang.entity.TaiXe;
import com.miniongvang.entity.enums.AccountRole;
import com.miniongvang.entity.enums.AccountStatus;
import com.miniongvang.entity.enums.AssignmentOutcome;
import com.miniongvang.entity.enums.DriverStatus;
import com.miniongvang.entity.enums.OrderStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.TypedQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads assignment history as the only source for a driver's relationship to an order. */
public final class DispatchDAO {
    private final EntityManager em;
    public DispatchDAO(EntityManager em) { this.em = em; }

    public boolean completedBy(String order, String driver) {
        return em.createQuery("""
                select count(p) from PhanCongDonHang p
                where p.donHang.id=:order and p.taiXe.id=:driver and p.ketQuaKetThuc=:completed
                """, Long.class)
                .setParameter("order", order).setParameter("driver", driver)
                .setParameter("completed", AssignmentOutcome.HOAN_TAT).getSingleResult() > 0;
    }

    public Events events(String order, int page, int size) {
        long total = em.createQuery("select count(e) from SuKienDonHang e where e.donHang.id=:id", Long.class)
                .setParameter("id", order).getSingleResult();
        var items = em.createQuery(
                "from SuKienDonHang e where e.donHang.id=:id order by e.thuTu", SuKienDonHang.class)
                .setParameter("id", order).setFirstResult(Math.multiplyExact(page, size))
                .setMaxResults(size).getResultList();
        return new Events(items, total);
    }

    public int nextEventSequence(String order) {
        Integer last = em.createQuery(
                "select max(e.thuTu) from SuKienDonHang e where e.donHang.id=:id", Integer.class)
                .setParameter("id", order).getSingleResult();
        return last == null ? 1 : Math.addExact(last, 1);
    }
    public record Events(List<SuKienDonHang> items, long total) {}

    public boolean relatedDriver(String order, String driver) {
        return em.createQuery("""
                select count(p) from PhanCongDonHang p
                where p.donHang.id=:order and p.taiXe.id=:driver
                  and (p.ketQuaKetThuc is null or p.ketQuaKetThuc<>:rejected)
                  and (p.donHang.trangThai<>:assigned or p.ketThucLuc is null)
                """, Long.class)
                .setParameter("order", order).setParameter("driver", driver)
                .setParameter("rejected", AssignmentOutcome.TU_CHOI).setParameter("assigned", OrderStatus.DA_GAN).getSingleResult() > 0;
    }

    public boolean rejected(String order, String driver) {
        return em.createQuery("""
                select count(p) from PhanCongDonHang p
                where p.donHang.id=:order and p.taiXe.id=:driver and p.ketQuaKetThuc=:rejected
                """, Long.class)
                .setParameter("order", order).setParameter("driver", driver)
                .setParameter("rejected", AssignmentOutcome.TU_CHOI).getSingleResult() > 0;
    }

    public PhanCongDonHang activeOrder(String order) {
        return em.createQuery("""
                from PhanCongDonHang p where p.donHang.id=:id and p.ketThucLuc is null
                """, PhanCongDonHang.class)
                .setParameter("id", order).getResultStream().findFirst().orElse(null);
    }

    public PhanCongDonHang completedOrder(String order) {
        return em.createQuery("""
                from PhanCongDonHang p where p.donHang.id=:id and p.ketQuaKetThuc=:completed
                """, PhanCongDonHang.class)
                .setParameter("id", order).setParameter("completed", AssignmentOutcome.HOAN_TAT)
                .getResultStream().findFirst().orElse(null);
    }

    private static final String BUSY =
            "exists (select p.id from PhanCongDonHang p where p.taiXe=t and p.ketThucLuc is null)";

    public boolean busy(String driver) {
        return em.createQuery("select count(t) from TaiXe t where t.id=:id and " + BUSY, Long.class)
                .setParameter("id", driver).getSingleResult() > 0;
    }

    public Drivers drivers(DriverStatus status, Boolean busy, String order, LocalDate today,
                           int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = " where t.taiKhoan.trangThai=:active and t.taiKhoan.vaiTro=:driverRole";
        params.put("active", AccountStatus.HOAT_DONG);
        params.put("driverRole", AccountRole.TAI_XE);
        if (status != null) { where += " and t.trangThai=:status"; params.put("status", status); }
        if (busy != null) where += " and " + (busy ? BUSY : "not " + BUSY);
        if (order != null) {
            where += " and t.trangThai=:online and t.ranhTu is not null"
                    + " and t.ngayHetHanGplx>=:today and not " + BUSY
                    + " and exists (select v.id from PhuongTien v where v.taiXe=t and v.dangHoatDong=true)"
                    + " and not exists (select r.id from PhanCongDonHang r where r.donHang.id=:order"
                    + " and r.taiXe=t and r.ketQuaKetThuc=:rejected)";
            params.put("online", DriverStatus.ONLINE);
            params.put("today", today);
            params.put("order", order);
            params.put("rejected", AssignmentOutcome.TU_CHOI);
        }
        long count = bind(em.createQuery("select count(t) from TaiXe t" + where, Long.class), params)
                .getSingleResult();
        String sort = order == null ? "t.taiKhoan.hoTen,t.id" : "t.ranhTu,t.id";
        var items = bind(em.createQuery("from TaiXe t" + where + " order by " + sort, TaiXe.class), params)
                .setFirstResult(Math.multiplyExact(page, size)).setMaxResults(size).getResultList();
        return new Drivers(items, count);
    }

    public PhuongTien vehicle(String driver) {
        return em.createQuery("from PhuongTien p where p.taiXe.id=:id", PhuongTien.class)
                .setParameter("id", driver).setLockMode(LockModeType.PESSIMISTIC_WRITE)
                .getResultStream().findFirst().orElse(null);
    }

    public Orders orders(AccountRole role, String profile, OrderStatus status,
                         Instant from, Instant to, int page, int size) {
        Map<String, Object> params = new HashMap<>();
        String where = " where 1=1";
        if (role == AccountRole.KHACH_HANG) {
            where += " and d.khachHang.id=:profile";
            params.put("profile", profile);
        } else if (role == AccountRole.TAI_XE) {
            where += " and exists (select p.id from PhanCongDonHang p"
                    + " where p.donHang=d and p.taiXe.id=:profile"
                    + " and (p.ketQuaKetThuc is null or p.ketQuaKetThuc<>:rejected)"
                    + " and (d.trangThai<>:assigned or p.ketThucLuc is null))";
            params.put("profile", profile);
            params.put("assigned", OrderStatus.DA_GAN);
            params.put("rejected", AssignmentOutcome.TU_CHOI);
        }
        if (status != null) { where += " and d.trangThai=:status"; params.put("status", status); }
        if (from != null) { where += " and d.thoiGianTao>=:from"; params.put("from", from); }
        if (to != null) { where += " and d.thoiGianTao<:to"; params.put("to", to); }
        long count = bind(em.createQuery("select count(d) from DonHang d" + where, Long.class), params)
                .getSingleResult();
        BigDecimal core = bind(em.createQuery(
                "select sum(c.cuocGoc-c.tienGiamGia) from CuocDonHang c join c.donHang d" + where,
                BigDecimal.class), params).getSingleResult();
        BigDecimal surcharge = bind(em.createQuery(
                "select sum(p.chinhSachPhuThu.soTien) from PhuThuDonHang p join p.donHang d" + where,
                BigDecimal.class), params).getSingleResult();
        var items = bind(em.createQuery(
                "from DonHang d" + where + " order by d.thoiGianTao desc,d.id", DonHang.class), params)
                .setFirstResult(Math.multiplyExact(page, size)).setMaxResults(size).getResultList();
        return new Orders(items, count,
                (core == null ? BigDecimal.ZERO : core).add(surcharge == null ? BigDecimal.ZERO : surcharge));
    }

    private static <T> TypedQuery<T> bind(TypedQuery<T> query, Map<String, Object> params) {
        params.forEach(query::setParameter);
        return query;
    }
    public record Drivers(List<TaiXe> items, long total) {}
    public record Orders(List<DonHang> items, long total, BigDecimal fare) {}
}
