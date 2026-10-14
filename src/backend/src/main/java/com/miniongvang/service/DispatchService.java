package com.miniongvang.service;
import com.miniongvang.security.ActorContext;
import com.miniongvang.dto.dispatch.DispatchDtos.DriverPage;
import com.miniongvang.dto.dispatch.DispatchDtos.Event;
import com.miniongvang.dto.dispatch.DispatchDtos.EventPage;
import com.miniongvang.dto.dispatch.DispatchDtos.OrderPage;
import com.miniongvang.dto.order.OrderDtos.Order;
import com.miniongvang.dto.order.OrderHistoryDtos.Detail;

import static com.miniongvang.service.DispatchQueryService.*;
import static com.miniongvang.service.DispatchAccess.actor;
import com.miniongvang.service.DispatchErrors.Conflict;
import com.miniongvang.dao.*;
import com.miniongvang.entity.*;
import com.miniongvang.entity.enums.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.LockModeType;
import java.time.*;
import java.util.*;

public final class DispatchService {
    private static final ZoneId VIETNAM = ZoneId.of("Asia/Ho_Chi_Minh");
    private final TransactionRunner transactions;
    private final Clock clock;
    private final DispatchQueryService queries;
    private final DriverLocationService locations;
    public DispatchService(EntityManagerFactory factory) { this(factory,Clock.systemUTC()); }
    public DispatchService(EntityManagerFactory factory, Clock clock) {
        this(factory,clock,new DriverRankingService(null));
    }
    public DispatchService(EntityManagerFactory factory, Clock clock, DriverRankingService routing) {
        transactions = new TransactionRunner(factory); this.clock=clock;
        queries = new DispatchQueryService(factory, clock, routing);
        locations = new DriverLocationService(factory,routing,clock);
    }

    public com.miniongvang.dto.dispatch.DispatchDtos.Driver configureLocation(ActorContext actor,String id,String address) {
        return locations.configure(actor,id,address);
    }

    public OrderPage orders(ActorContext actor, int page, int size, LocalDate from, LocalDate to, OrderStatus status) {
        return queries.orders(actor, page, size, from, to, status);
    }
    public Order order(ActorContext actor, String id) { return queries.order(actor, id); }
    public Detail detail(ActorContext actor, String id) { return queries.detail(actor, id); }
    public DriverPage drivers(ActorContext actor, int page, int size, DriverStatus status, Boolean busy) {
        return queries.drivers(actor, page, size, status, busy);
    }
    public DriverPage suggestions(ActorContext actor, String id, int page, int size) {
        return queries.suggestions(actor, id, page, size);
    }
    public EventPage events(ActorContext actor, String id, int page, int size) {
        return queries.events(actor, id, page, size);
    }

    public Order assign(ActorContext actor,String orderId,String driverId) {
        id(orderId); id(driverId);
        return transactions.run(em -> {
            TaiKhoan dispatcher=actor(em,actor,AccountRole.TONG_DAI);
            // Global lock order for all dispatch writes: order, driver, driver's account.
            DonHang order=new DonHangDAO(em).findForUpdate(orderId);
            if (order==null) throw new NoSuchElementException("Order not found");
            var dao=new DispatchDAO(em);
            if (order.getTrangThai()!=OrderStatus.CHO_GAN || dao.activeOrder(orderId)!=null)
                throw new Conflict("ORDER_STATE_CONFLICT");
            TaiXe driver=new TaiXeDAO(em).findForUpdate(driverId);
            if (driver==null) throw new NoSuchElementException("Driver not found");
            TaiKhoan account=em.find(TaiKhoan.class,driver.getTaiKhoan().getId(),LockModeType.PESSIMISTIC_WRITE);
            PhuongTien vehicle=dao.vehicle(driverId);
            if (driver.getTrangThai()!=DriverStatus.ONLINE || driver.getRanhTu()==null
                    || account.getTrangThai()!=AccountStatus.HOAT_DONG || account.getVaiTro()!=AccountRole.TAI_XE
                    || driver.getNgayHetHanGplx().isBefore(LocalDate.ofInstant(clock.instant(),VIETNAM))
                    || vehicle==null || !vehicle.isDangHoatDong()
                    || dao.busy(driverId) || dao.rejected(orderId,driverId)) throw new Conflict("DRIVER_UNAVAILABLE");
            Instant now=clock.instant();
            PhanCongDonHang assignment=new PhanCongDonHang(); assignment.setId(UUID.randomUUID().toString());
            assignment.setDonHang(order); assignment.setTaiXe(driver); assignment.setTaiKhoanDieuPhoi(dispatcher);
            assignment.setBatDauLuc(now); em.persist(assignment);
            order.setTrangThai(OrderStatus.DA_GAN);
            driver.setRanhTu(null);
            event(em,order,dispatcher,now,OrderStatus.CHO_GAN,null);
            // Flush while still holding locks; DB partial unique indexes are a second guard.
            em.flush(); return view(em,orderId);
        });
    }
    public Order reject(ActorContext actor,String orderId,String reason) {
        id(orderId);
        if (reason==null || reason.isBlank() || reason.strip().length()>500)
            throw new IllegalArgumentException("Reason must contain 1-500 characters");
        String normalized=reason.strip();
        return transactions.run(em -> {
            TaiKhoan account=actor(em,actor,AccountRole.TAI_XE);
            DonHang order=new DonHangDAO(em).findForUpdate(orderId);
            if (order==null) throw new NoSuchElementException("Order not found");
            PhanCongDonHang assignment=new DispatchDAO(em).activeOrder(orderId);
            if (assignment==null || !assignment.getTaiXe().getId().equals(actor.maTx()))
                throw new NoSuchElementException("Order not found");
            if (order.getTrangThai()!=OrderStatus.DA_GAN) throw new Conflict("ORDER_STATE_CONFLICT");
            TaiXe driver=new TaiXeDAO(em).findForUpdate(actor.maTx());
            // actor validation loaded this driver before waiting for the order lock.
            // Refresh under lock to avoid overwriting a concurrent working-status change.
            em.refresh(driver,LockModeType.PESSIMISTIC_WRITE);
            em.refresh(account,LockModeType.PESSIMISTIC_WRITE);
            if (account.getTrangThai()!=AccountStatus.HOAT_DONG || account.getVaiTro()!=AccountRole.TAI_XE)
                throw new SecurityException("Account unavailable");
            assignment=new DispatchDAO(em).activeOrder(orderId);
            if (assignment==null || !assignment.getTaiXe().getId().equals(driver.getId()))
                throw new Conflict("ORDER_STATE_CONFLICT");
            Instant now=clock.instant();
            assignment.setKetThucLuc(now); assignment.setLyDo(normalized);
            assignment.setKetQuaKetThuc(AssignmentOutcome.TU_CHOI);
            order.setTrangThai(OrderStatus.CHO_GAN); driver.setRanhTu(now);
            event(em,order,account,now,OrderStatus.DA_GAN,normalized);
            em.flush(); return view(em,orderId);
        });
    }
    public Order cancel(ActorContext actor,String orderId,String reason) {
        id(orderId);
        if (reason==null || reason.isBlank() || reason.strip().length()>500)
            throw new IllegalArgumentException("Reason must contain 1-500 characters");
        String normalized=reason.strip();
        return transactions.run(em -> {
            TaiKhoan account=actor(em,actor,AccountRole.KHACH_HANG,AccountRole.TONG_DAI);
            DonHang order=new DonHangDAO(em).findForUpdate(orderId);
            if (order==null || actor.vaiTro()==AccountRole.KHACH_HANG
                    && !order.getKhachHang().getId().equals(actor.maKh()))
                throw new NoSuchElementException("Order not found");
            if (order.getTrangThai()!=OrderStatus.CHO_GAN && order.getTrangThai()!=OrderStatus.DA_GAN)
                throw new Conflict("ORDER_STATE_CONFLICT");
            Instant now=clock.instant();
            Duration age=Duration.between(order.getThoiGianTao(),now);
            if (age.isNegative() || age.compareTo(Duration.ofSeconds(300))>=0)
                throw new Conflict("CANCELLATION_WINDOW_EXPIRED");
            var dao=new DispatchDAO(em);
            OrderStatus previous=order.getTrangThai();
            if (order.getTrangThai()==OrderStatus.DA_GAN) {
                PhanCongDonHang assignment=dao.activeOrder(orderId);
                if (assignment==null) throw new Conflict("ORDER_STATE_CONFLICT");
                TaiXe driver=new TaiXeDAO(em).findForUpdate(assignment.getTaiXe().getId());
                em.refresh(driver,LockModeType.PESSIMISTIC_WRITE);
                TaiKhoan driverAccount=em.find(TaiKhoan.class,driver.getTaiKhoan().getId(),LockModeType.PESSIMISTIC_WRITE);
                em.refresh(driverAccount,LockModeType.PESSIMISTIC_WRITE);
                assignment=dao.activeOrder(orderId);
                if (assignment==null || !assignment.getTaiXe().getId().equals(driver.getId()))
                    throw new Conflict("ORDER_STATE_CONFLICT");
                assignment.setKetThucLuc(now); assignment.setLyDo(normalized);
                assignment.setKetQuaKetThuc(AssignmentOutcome.HUY_DON);
                driver.setRanhTu(now);
            } else if (dao.activeOrder(orderId)!=null) {
                throw new Conflict("ORDER_STATE_CONFLICT");
            }
            order.setTrangThai(OrderStatus.DA_HUY);
            order.setThoiGianHuy(now); order.setLyDoHuy(normalized);
            event(em,order,account,now,previous,normalized);
            em.flush(); return view(em,orderId);
        });
    }

    public Order transition(ActorContext actor,String orderId,OrderStatus target) {
        id(orderId);
        if (target==null || !EnumSet.of(OrderStatus.DA_LAY_HANG,OrderStatus.DANG_GIAO,OrderStatus.HOAN_TAT).contains(target))
            throw new IllegalArgumentException("Invalid transition target");
        return transactions.run(em -> {
            TaiKhoan account=actor(em,actor,AccountRole.TAI_XE);
            DonHang order=ownedOrder(em,actor,orderId);
            // A completed replay must not release a driver now working on another order.
            if (order.getTrangThai()==OrderStatus.HOAN_TAT && target==OrderStatus.HOAN_TAT) {
                em.refresh(account,LockModeType.PESSIMISTIC_WRITE);
                activeDriverAccount(account);
                return view(em,orderId);
            }
            TaiXe driver=lockDriver(em,actor,account,orderId);
            if (order.getTrangThai()==target) return view(em,orderId);
            OrderStatus required=switch(target) {
                case DA_LAY_HANG -> OrderStatus.DA_GAN;
                case DANG_GIAO -> OrderStatus.DA_LAY_HANG;
                case HOAN_TAT -> OrderStatus.DANG_GIAO;
                default -> throw new IllegalArgumentException("Invalid transition target");
            };
            if (order.getTrangThai()!=required) throw new Conflict("ORDER_STATE_CONFLICT");
            Instant now=clock.instant();
            OrderStatus previous=order.getTrangThai();
            order.setTrangThai(target);
            if (target==OrderStatus.HOAN_TAT) {
                var assignment=new DispatchDAO(em).activeOrder(orderId);
                assignment.setKetThucLuc(now); assignment.setKetQuaKetThuc(AssignmentOutcome.HOAN_TAT);
                order.setThoiGianHoanTat(now); driver.setRanhTu(now);
            }
            event(em,order,account,now,previous,null);
            em.flush(); return view(em,orderId);
        });
    }
    public Event incident(ActorContext actor,String orderId,IncidentType type,String reason) {
        id(orderId);
        if (type==null || reason==null || reason.isBlank() || reason.strip().length()>500)
            throw new IllegalArgumentException("Incident requires type and 1-500 character reason");
        return transactions.run(em -> {
            TaiKhoan account=actor(em,actor,AccountRole.TAI_XE);
            DonHang order=ownedOrder(em,actor,orderId);
            if (order.getTrangThai()!=OrderStatus.DANG_GIAO) throw new Conflict("ORDER_STATE_CONFLICT");
            lockDriver(em,actor,account,orderId);
            SuKienDonHang event=incidentEvent(em,order,account,clock.instant(),type,reason.strip());
            em.flush(); return eventView(event);
        });
    }
    private static DonHang ownedOrder(EntityManager em,ActorContext actor,String id) {
        DonHang order=new DonHangDAO(em).findForUpdate(id);
        if (order==null) throw new NoSuchElementException("Order not found");
        var assignment=new DispatchDAO(em).activeOrder(id);
        if ((assignment==null || !assignment.getTaiXe().getId().equals(actor.maTx()))
                && !(order.getTrangThai()==OrderStatus.HOAN_TAT
                && new DispatchDAO(em).completedBy(id,actor.maTx())))
            throw new NoSuchElementException("Order not found");
        return order;
    }
    private static TaiXe lockDriver(EntityManager em,ActorContext actor,TaiKhoan account,String orderId) {
        TaiXe driver=new TaiXeDAO(em).findForUpdate(actor.maTx());
        em.refresh(driver,LockModeType.PESSIMISTIC_WRITE);
        em.refresh(account,LockModeType.PESSIMISTIC_WRITE);
        activeDriverAccount(account);
        PhanCongDonHang assignment=new DispatchDAO(em).activeOrder(orderId);
        if (assignment==null || !assignment.getTaiXe().getId().equals(driver.getId()))
            throw new Conflict("ORDER_STATE_CONFLICT");
        return driver;
    }
    private static void activeDriverAccount(TaiKhoan account) {
        if (account.getTrangThai()!=AccountStatus.HOAT_DONG || account.getVaiTro()!=AccountRole.TAI_XE)
            throw new SecurityException("Account unavailable");
    }

    private static int nextSequence(EntityManager em, String orderId) {
        return new DispatchDAO(em).nextEventSequence(orderId);
    }
    private static SuKienDonHang event(EntityManager em,DonHang order,TaiKhoan actor,Instant now,
                                       OrderStatus before,String reason) {
        SuKienDonHang event=new SuKienDonHang(); event.setId(UUID.randomUUID().toString());
        event.setDonHang(order); event.setThuTu(nextSequence(em,order.getId()));
        event.setLoaiSuKien(OrderEventType.CHUYEN_TRANG_THAI);
        event.setTrangThaiTruoc(before); event.setTrangThaiSau(order.getTrangThai());
        event.setThoiGian(now); event.setTaiKhoanThucHien(actor); event.setGhiChu(reason);
        em.persist(event);
        return event;
    }
    private static SuKienDonHang incidentEvent(EntityManager em,DonHang order,TaiKhoan actor,
                                                Instant now,IncidentType type,String reason) {
        SuKienDonHang event=new SuKienDonHang(); event.setId(UUID.randomUUID().toString());
        event.setDonHang(order); event.setThuTu(nextSequence(em,order.getId()));
        event.setLoaiSuKien(OrderEventType.SU_CO);
        event.setTrangThaiTruoc(OrderStatus.DANG_GIAO); event.setTrangThaiSau(OrderStatus.DANG_GIAO);
        event.setThoiGian(now); event.setTaiKhoanThucHien(actor);
        event.setLoaiSuCo(type); event.setGhiChu(reason); em.persist(event);
        return event;
    }

}
