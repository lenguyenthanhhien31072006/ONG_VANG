package com.miniongvang.dao;

import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;

/** Aggregate each one-to-many relation separately; never multiply fare by parcels or payment events. */
public final class ReportDAO {
    private final EntityManager em;
    public ReportDAO(EntityManager em) { this.em = em; }
    public Totals totals(Instant from, Instant to) {
        String completed = " where d.thoiGianHoanTat>=:from and d.thoiGianHoanTat<:to";
        String debt = " where d.thoiGianHoanTat<:to and not exists "
                + "(select x.maDon from XacNhanThanhToanDonHang x where x.donHang=d and x.lanThanhToan.thuLuc<:to)";
        return new Totals(fare(completed,from,to),
                sum("select sum(p.soTienThucNhan) from LanThanhToan p where p.thuLuc>=:from and p.thuLuc<:to",from,to),
                sum("select sum(p.soTienThucNhan) from LanThanhToan p where p.thuLuc>=:from and p.thuLuc<:to "
                        + "and not exists (select x.maDon from XacNhanThanhToanDonHang x where x.lanThanhToan=p)",from,to),
                fare(debt,null,to), count("select count(d) from DonHang d"+completed,from,to),
                count("select count(d) from DonHang d where d.thoiGianHuy>=:from and d.thoiGianHuy<:to",from,to),
                nativeSum("select sum(tien_cong_ty) from dbo.phan_chia_cuoc where ghi_nhan_luc>=:from and ghi_nhan_luc<:to",from,to),
                nativeSum("select sum(tien_tai_xe) from dbo.phan_chia_cuoc where ghi_nhan_luc>=:from and ghi_nhan_luc<:to",from,to));
    }
    private BigDecimal fare(String where, Instant from, Instant to) {
        return sum("select sum(c.cuocGoc-c.tienGiamGia) from CuocDonHang c join c.donHang d"+where,from,to)
                .add(sum("select sum(p.chinhSachPhuThu.soTien) from PhuThuDonHang p join p.donHang d"+where,from,to));
    }
    private BigDecimal sum(String hql, Instant from, Instant to) {
        var query=em.createQuery(hql,BigDecimal.class).setParameter("to",to);
        if (from!=null) query.setParameter("from",from);
        BigDecimal value=query.getSingleResult(); return value==null?BigDecimal.ZERO:value;
    }
    private long count(String hql, Instant from, Instant to) {
        return em.createQuery(hql,Long.class).setParameter("from",from).setParameter("to",to).getSingleResult();
    }
    private BigDecimal nativeSum(String sql, Instant from, Instant to) {
        var query=em.createNativeQuery(sql).setParameter("to",java.sql.Timestamp.from(to));
        if(from!=null) query.setParameter("from",java.sql.Timestamp.from(from));
        Object value=query.getSingleResult(); return value==null?BigDecimal.ZERO:(BigDecimal)value;
    }
    public record Totals(BigDecimal completed, BigDecimal received, BigDecimal unallocated,
                         BigDecimal receivable, long completedCount, long cancelledCount,
                         BigDecimal company, BigDecimal driver) {}
}
