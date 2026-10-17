package com.miniongvang.dao;

import com.miniongvang.entity.TaiXe;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;

public final class DriverEarningsDAO {
    private final EntityManager em;
    public DriverEarningsDAO(EntityManager em) { this.em = em; }
    public List<TaiXe> drivers() {
        return em.createQuery("select d from TaiXe d order by d.taiKhoan.hoTen", TaiXe.class).getResultList();
    }
    public record Totals(BigDecimal completed, BigDecimal earned) {}

    public Totals totals(String driver, Timestamp begin, Timestamp end) {
        BigDecimal completed=sum("select sum(s.tong_cuoc) from dbo.phan_chia_cuoc s join dbo.phan_cong_don_hang a on a.ma_phan_cong=s.ma_phan_cong join dbo.don_hang o on o.ma_don=s.ma_don where a.ma_tx=:driver and o.thoi_gian_hoan_tat>=:begin and o.thoi_gian_hoan_tat<:end",driver,begin,end);
        BigDecimal earned=sum("select sum(s.tien_tai_xe) from dbo.phan_chia_cuoc s join dbo.phan_cong_don_hang a on a.ma_phan_cong=s.ma_phan_cong where a.ma_tx=:driver and s.ghi_nhan_luc>=:begin and s.ghi_nhan_luc<:end",driver,begin,end);
        return new Totals(completed,earned);
    }
    private BigDecimal sum(String sql,String driver,Timestamp from,Timestamp to) {
        var query=em.createNativeQuery(sql).setParameter("driver",driver);
        if(from!=null) query.setParameter("begin",from).setParameter("end",to);
        Object value=query.getSingleResult(); return value==null?BigDecimal.ZERO:(BigDecimal)value;
    }
}
