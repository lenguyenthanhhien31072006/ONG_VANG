package com.miniongvang.service;

import com.miniongvang.entity.TaiXe;
import com.miniongvang.dao.DriverEarningsDAO;
import com.miniongvang.entity.enums.AccountRole;
import com.miniongvang.security.ActorContext;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/** Revenue-share earnings only; no bank payout is recorded here. */
public final class DriverEarningsService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final TransactionRunner tx;
    public DriverEarningsService(EntityManagerFactory factory) { tx = new TransactionRunner(factory); }

    public record Earnings(String maTx, String hoTen, String tongCuocDaHoanTat,
                           String phanTaiXePhatSinh) {}

    public List<Earnings> drivers(ActorContext actor, LocalDate from, LocalDate to) {
        validate(from,to);
        return tx.run(em -> {
            PaymentService.account(em,actor,AccountRole.CHU_DOI_XE);
            List<Earnings> rows = new ArrayList<>();
            for (TaiXe d : new DriverEarningsDAO(em).drivers())
                rows.add(row(em,d,from,to));
            return List.copyOf(rows);
        });
    }
    public Earnings mine(ActorContext actor, LocalDate from, LocalDate to) {
        validate(from,to);
        return tx.run(em -> {
            PaymentService.account(em,actor,AccountRole.TAI_XE);
            TaiXe d = em.find(TaiXe.class,actor.maTx());
            return row(em,d,from,to);
        });
    }
    private static void validate(LocalDate from, LocalDate to) {
        if(from==null || to==null || !from.isBefore(to)) throw new ReportService.DateRangeInvalid();
    }
    private static Earnings row(EntityManager em, TaiXe d, LocalDate from, LocalDate to) {
        Timestamp begin=Timestamp.from(from.atStartOfDay(ZONE).toInstant());
        Timestamp end=Timestamp.from(to.atStartOfDay(ZONE).toInstant());
        String id=d.getId();
        var totals=new DriverEarningsDAO(em).totals(id,begin,end);
        return new Earnings(id,d.getTaiKhoan().getHoTen(),money(totals.completed()),money(totals.earned()));
    }
    private static String money(java.math.BigDecimal amount) { return amount.setScale(2).toPlainString(); }
}
