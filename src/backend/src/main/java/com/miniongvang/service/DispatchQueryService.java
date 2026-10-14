package com.miniongvang.service;

import com.miniongvang.security.ActorContext;
import com.miniongvang.dto.dispatch.DispatchDtos.*;
import com.miniongvang.dto.order.OrderDtos.Order;
import com.miniongvang.dto.order.OrderDtos.PaymentSummary;
import com.miniongvang.dto.order.OrderHistoryDtos.Detail;
import com.miniongvang.dto.quote.FareDtos.Fare;
import com.miniongvang.dto.quote.FareDtos.Surcharge;
import com.miniongvang.dto.quote.QuoteDtos.Parcel;
import com.miniongvang.dao.*;
import com.miniongvang.entity.*;
import com.miniongvang.entity.enums.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Read side of dispatch. All visibility checks and projections stay inside one transaction. */
public final class DispatchQueryService {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final TransactionRunner transactions;
    private final Clock clock;
    private final DriverRankingService routing;
    public DispatchQueryService(EntityManagerFactory factory, Clock clock) {
        this(factory,clock,new DriverRankingService(null));
    }
    public DispatchQueryService(EntityManagerFactory factory, Clock clock, DriverRankingService routing) {
        transactions = new TransactionRunner(factory);
        this.clock = clock;
        this.routing = routing;
    }

    public OrderPage orders(ActorContext actor, int page, int size, LocalDate from, LocalDate to, OrderStatus status) {
        page(page,size);
        if (from != null && to != null && !from.isBefore(to)) throw new DispatchErrors.DateRangeInvalid();
        return transactions.run(em -> {
            DispatchAccess.actor(em,actor,AccountRole.KHACH_HANG,AccountRole.TONG_DAI,AccountRole.TAI_XE);
            String profile = actor.vaiTro()==AccountRole.TAI_XE ? actor.maTx() : actor.maKh();
            var result = new DispatchDAO(em).orders(actor.vaiTro(),profile,status,
                    from==null?null:from.atStartOfDay(VIETNAM).toInstant(),
                    to==null?null:to.atStartOfDay(VIETNAM).toInstant(),page,size);
            return new OrderPage(result.items().stream().map(d->view(em,d.getId())).toList(),page,size,
                    result.total(),pages(result.total(),size),new Summary(result.total(),money(result.fare())));
        });
    }

    public Order order(ActorContext actor,String id) {
        id(id);
        return transactions.run(em -> {
            DispatchAccess.actor(em,actor,AccountRole.KHACH_HANG,AccountRole.TONG_DAI,AccountRole.TAI_XE);
            DonHang order=new DonHangDAO(em).find(id);
            if (order==null || !visible(em,actor,order)) throw new NoSuchElementException("Order not found");
            return view(em,id);
        });
    }

    public Detail detail(ActorContext actor, String id) {
        id(id);
        return transactions.run(em -> {
            DispatchAccess.actor(em,actor,AccountRole.KHACH_HANG,AccountRole.TONG_DAI,AccountRole.TAI_XE);
            DonHang order = new DonHangDAO(em).find(id);
            if (order == null || !visible(em,actor,order)) throw new NoSuchElementException("Order not found");
            return OrderHistoryService.read(em, view(em,id));
        });
    }

    public DriverPage drivers(ActorContext actor,int page,int size,DriverStatus status,Boolean busy) {
        page(page,size);
        return transactions.run(em -> {
            DispatchAccess.actor(em,actor,AccountRole.TONG_DAI);
            return driverPage(em,new DispatchDAO(em).drivers(status,busy,null,
                    LocalDate.ofInstant(clock.instant(),VIETNAM),page,size),page,size);
        });
    }

    public DriverPage suggestions(ActorContext actor,String id,int page,int size) {
        id(id); page(page,size);
        Snapshot before = snapshot(actor,id);
        var ranked = routing.rank(before.candidates(),before.pickup());
        // Re-check eligibility/location/order after network calls; an assignment or cancellation may have won.
        Snapshot after = snapshot(actor,id);
        if (!before.equals(after)) ranked = DriverRankingService.fallback(after.candidates(),"DRIVER_SNAPSHOT_CHANGED");
        int start = (int)Math.min((long)page*size,ranked.items().size());
        int end = Math.min(start+size,ranked.items().size());
        return new DriverPage(ranked.items().subList(start,end),page,size,ranked.items().size(),
                pages(ranked.items().size(),size),ranked.method(),ranked.fallbackReason());
    }

    private record Snapshot(String pickup, List<DriverRankingService.Candidate> candidates) {}
    private Snapshot snapshot(ActorContext actor,String id) {
        return transactions.run(em -> {
            DispatchAccess.actor(em,actor,AccountRole.TONG_DAI);
            DonHang order=new DonHangDAO(em).find(id);
            if (order==null) throw new NoSuchElementException("Order not found");
            if (order.getTrangThai()!=OrderStatus.CHO_GAN) throw new DispatchErrors.Conflict("ORDER_STATE_CONFLICT");
            var result = new DispatchDAO(em).drivers(DriverStatus.ONLINE,false,id,
                    LocalDate.ofInstant(clock.instant(),VIETNAM),0,Integer.MAX_VALUE);
            return new Snapshot(order.getDiemLayHang(),result.items().stream()
                    .map(d -> new DriverRankingService.Candidate(driverView(em,d),d.getRanhTu())).toList());
        });
    }

    public EventPage events(ActorContext actor,String orderId,int page,int size) {
        id(orderId); page(page,size);
        return transactions.run(em -> {
            DispatchAccess.actor(em,actor,AccountRole.KHACH_HANG,AccountRole.TONG_DAI,AccountRole.TAI_XE);
            DonHang order=new DonHangDAO(em).find(orderId);
            if (order==null || !visible(em,actor,order)) throw new NoSuchElementException("Order not found");
            var result=new DispatchDAO(em).events(orderId,page,size);
            return new EventPage(result.items().stream().map(DispatchQueryService::eventView).toList(),
                    page,size,result.total(),pages(result.total(),size));
        });
    }

    static Event eventView(SuKienDonHang event) {
        return new Event(event.getId(), event.getDonHang().getId(), event.getThuTu(),
                event.getLoaiSuKien(), event.getTrangThaiTruoc(), event.getTrangThaiSau(),
                event.getThoiGian().toString(), event.getTaiKhoanThucHien().getId(),
                event.getTaiKhoanThucHien().getHoTen(), event.getGhiChu(), event.getLoaiSuCo());
    }

    static boolean visible(EntityManager em,ActorContext actor,DonHang order) {
        return switch(actor.vaiTro()) {
            case TONG_DAI -> true;
            case KHACH_HANG -> order.getKhachHang().getId().equals(actor.maKh());
            case TAI_XE -> new DispatchDAO(em).relatedDriver(order.getId(),actor.maTx());
            default -> false;
        };
    }

    static Order view(EntityManager em,String id) {
        var details=new DonHangDAO(em).findDetailsV1(id); var order=details.order(); var priced=details.fare();
        if (priced==null) throw new IllegalStateException("Order fare missing");
        var extras=details.surcharges().stream().map(p->new Surcharge(
                p.getChinhSachPhuThu().getId(),p.getChinhSachPhuThu().getTenPhuThu(),
                money(p.getChinhSachPhuThu().getSoTien()))).toList();
        BigDecimal extraTotal=details.surcharges().stream().map(p->p.getChinhSachPhuThu().getSoTien())
                .reduce(BigDecimal.ZERO,BigDecimal::add);
        BigDecimal total=priced.getCuocGoc().add(extraTotal).subtract(priced.getTienGiamGia());
        var fare=new Fare(money(priced.getCuocGoc()),money(extraTotal),
                money(priced.getTienGiamGia()),money(total),"VND",extras,
                money(priced.getCuocKm()), money(priced.getCuocKhoiLuong()),
                money(priced.getTongKhoiLuongKg()), priced.getBieuPhi().getId(),
                priced.getPhienBanBieuPhi());
        var parcels=details.parcels().stream().map(p->new Parcel(p.getLoaiHangHoa(),p.getGhiChuBaoQuan(),money(p.getKhoiLuongKg()))).toList();
        var payments=new LanThanhToanDAO(em);
        var attempts=payments.findAttempts(id);
        boolean paid=payments.confirmed(id);
        boolean pending=attempts.stream().anyMatch(p->p.getTrangThai()==PaymentStatus.CHO_XU_LY
                || p.getTrangThai()==PaymentStatus.CHUA_RO_KET_QUA);
        boolean reconcile=attempts.stream().anyMatch(p->p.getTrangThai()==PaymentStatus.THANH_CONG
                && !payments.confirmedBy(id,p.getId()));
        String state=total.signum()==0?"MIEN_CUOC":paid?"DA_THANH_TOAN":pending?"DANG_XU_LY":"CHUA_THANH_TOAN";
        var dispatch=new DispatchDAO(em);
        PhanCongDonHang assignment=dispatch.activeOrder(id);
        if (assignment==null && order.getTrangThai()==OrderStatus.HOAN_TAT)
            assignment=dispatch.completedOrder(id);
        return new Order(order.getId(),order.getThoiGianTao().toString(),order.getDiemLayHang(),
                order.getDiemGiaoHang(),order.getSdtNguoiNhan(),money(order.getQuangDuongKm()),order.getGhiChuGiaoHang(),
                order.getTrangThai().name(),order.getKhachHang().getId(),assignment==null?null:assignment.getTaiXe().getId(),
                order.getTaiKhoanTao().getId(),fare,parcels,
                new PaymentSummary(state,total.signum()==0||paid?"0.00":fare.tongCuoc(),reconcile),
                order.getThoiGianHoanTat()==null?null:order.getThoiGianHoanTat().toString(),order.getThoiGianHuy()==null?null:order.getThoiGianHuy().toString(),
                order.getTenNguoiNhan(), order.getBenThanhToan(), order.getPhuongThucThanhToan(), order.getNguonLoTrinh().name());
    }

    static DriverPage driverPage(EntityManager em,DispatchDAO.Drivers result,int page,int size) {
        var items=result.items().stream().map(t -> driverView(em,t)).toList();
        return new DriverPage(items,page,size,result.total(),pages(result.total(),size),"TEN_TAI_XE",null);
    }

    static Driver driverView(EntityManager em,TaiXe t) {
        var dao=new DispatchDAO(em); var v=dao.vehicle(t.getId());
        Vehicle vehicle=v==null?null:new Vehicle(v.getId(),v.getBienSoXe(),v.getLoaiXe(),v.getMauXe());
        return new Driver(t.getId(),t.getHoTen(),t.getSoDienThoai(),t.getTrangThai().name(),dao.busy(t.getId()),vehicle,
                t.getDiaChiViTriDemo(),t.getViDoDemo()==null?null:t.getViDoDemo().toPlainString(),
                t.getKinhDoDemo()==null?null:t.getKinhDoDemo().toPlainString(),t.getNguonViTri(),
                t.getViTriCapNhatLuc()==null?null:t.getViTriCapNhatLuc().toString(),null,null,null);
    }

    static String money(BigDecimal value) { return value.setScale(2).toPlainString(); }

    static long pages(long total,int size) { return (total+size-1)/size; }

    static void page(int page,int size) {
        if (page<0 || size<1 || size>100 || (long)page*size>Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid pagination");
    }

    static void id(String id) {
        if (id==null || id.isBlank() || id.length()>36 || !id.equals(id.strip())) throw new IllegalArgumentException("Invalid id");
    }
}
