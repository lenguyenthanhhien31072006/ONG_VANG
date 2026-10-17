package com.miniongvang.service;
import com.miniongvang.security.ActorContext;
import com.miniongvang.dto.report.ReportDtos.Bucket;
import com.miniongvang.dto.report.ReportDtos.Metrics;
import com.miniongvang.dto.report.ReportDtos.Report;
import com.miniongvang.dto.report.ReportDtos.Group;

import com.miniongvang.dao.ReportDAO;
import com.miniongvang.entity.TaiKhoan;
import com.miniongvang.entity.enums.AccountRole;
import com.miniongvang.entity.enums.AccountStatus;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;

public final class ReportService {
    private static final ZoneId ZONE=ZoneId.of("Asia/Ho_Chi_Minh");
    private final TransactionRunner transactions;
    public ReportService(EntityManagerFactory factory) { transactions=new TransactionRunner(factory); }
    public Report revenue(ActorContext actor, LocalDate from, LocalDate to, Group group) {
        if (actor==null || actor.maNguoiDung()==null
                || actor.vaiTro()!=AccountRole.CHU_DOI_XE && actor.vaiTro()!=AccountRole.TONG_DAI)
            throw new SecurityException("Reports role required");
        if (from==null || to==null || !from.isBefore(to)) throw new DateRangeInvalid();
        if (group==null) throw new IllegalArgumentException("Grouping required");
        return transactions.run(em -> {
            var account=em.find(TaiKhoan.class,actor.maNguoiDung());
            if (account==null || account.getVaiTro()!=actor.vaiTro() || account.getTrangThai()!=AccountStatus.HOAT_DONG)
                throw new SecurityException("Account unavailable");
            var dao=new ReportDAO(em);
            List<Bucket> items=new ArrayList<>();
            for (LocalDate start=from; start.isBefore(to);) {
                LocalDate end=next(start,to,group);
                var begin=start.atStartOfDay(ZONE).toInstant(); var finish=end.atStartOfDay(ZONE).toInstant();
                items.add(new Bucket(begin.toString(),finish.toString(),metrics(dao.totals(begin,finish))));
                start=end;
            }
            return new Report(from.toString(),to.toString(),ZONE.getId(),group,
                    metrics(dao.totals(from.atStartOfDay(ZONE).toInstant(),to.atStartOfDay(ZONE).toInstant())),List.copyOf(items));
        });
    }
    private static LocalDate next(LocalDate start, LocalDate to, Group group) {
        try {
            LocalDate end=switch(group) {
                case NGAY -> start.plusDays(1);
                case TUAN -> start.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
                case THANG -> start.withDayOfMonth(1).plusMonths(1);
            };
            return end.isAfter(to)?to:end;
        } catch (DateTimeException overflow) { return to; }
    }
    private static Metrics metrics(ReportDAO.Totals t) {
        return new Metrics(money(t.completed()),money(t.received()),money(t.unallocated()),money(t.receivable()),t.completedCount(),t.cancelledCount(),
                money(t.company()),money(t.driver()));
    }
    private static String money(BigDecimal value) { return value.setScale(2).toPlainString(); }

    public static final class DateRangeInvalid extends RuntimeException {}
}
