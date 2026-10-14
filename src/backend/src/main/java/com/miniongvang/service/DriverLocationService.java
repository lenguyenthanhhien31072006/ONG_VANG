package com.miniongvang.service;

import com.miniongvang.dao.TaiXeDAO;
import com.miniongvang.dto.dispatch.DispatchDtos.Driver;
import com.miniongvang.entity.enums.AccountRole;
import com.miniongvang.security.ActorContext;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.util.NoSuchElementException;

public final class DriverLocationService {
    private final TransactionRunner transactions;
    private final DriverRankingService routing;
    private final Clock clock;
    public DriverLocationService(EntityManagerFactory factory, DriverRankingService routing, Clock clock) {
        transactions = new TransactionRunner(factory); this.routing = routing; this.clock = clock;
    }
    public Driver configure(ActorContext actor, String driverId, String address) {
        DispatchQueryService.id(driverId);
        transactions.run(em -> {
            DispatchAccess.actor(em,actor,AccountRole.TONG_DAI);
            if (new TaiXeDAO(em).find(driverId) == null) throw new NoSuchElementException("Driver not found");
            return null;
        });
        // The UI cannot supply coordinates. Only a successful provider lookup can configure them.
        var position = routing.verify(address);
        return transactions.run(em -> {
            DispatchAccess.actor(em,actor,AccountRole.TONG_DAI);
            var driver = new TaiXeDAO(em).findForUpdate(driverId);
            if (driver == null) throw new NoSuchElementException("Driver not found");
            driver.setDiaChiViTriDemo(position.address()); driver.setViDoDemo(position.latitude());
            driver.setKinhDoDemo(position.longitude()); driver.setNguonViTri("DEMO_CO_DINH");
            driver.setViTriCapNhatLuc(clock.instant()); em.flush();
            return DispatchQueryService.driverView(em,driver);
        });
    }
}
