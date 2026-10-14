"use client";

import React, { useState } from "react";
import Link from "next/link";
import { DispatcherNavbar } from "@/components/layout/DispatcherNavbar";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import { OrderItem } from "@/lib/types";
import { useDemoStore, updateDemoState } from "@/lib/demo-store";
import { formatVND } from "@/lib/utils";
import { useToast } from "@/components/ui/Toast";

export default function DispatcherLiveBoardPage() {
  const { showToast } = useToast();
  const { state: demo } = useDemoStore();
  const { orders, drivers, incidents } = demo;

  // Modal phân công tài xế thông minh (UC-12)
  const [assigningOrder, setAssigningOrder] = useState<OrderItem | null>(null);
  const [selectedDriverId, setSelectedDriverId] = useState<string>("");

  // Thống kê tác chiến
  const pendingOrders = orders.filter((o) => o.status === "CHO_GAN");
  const deliveringOrders = orders.filter(
    (o) => o.status === "DA_GAN" || o.status === "DA_LAY_HANG" || o.status === "DANG_GIAO"
  );
  const onlineIdleDrivers = drivers.filter((d) => d.status === "ONLINE_IDLE");

  // Mở modal gán tài xế
  const handleOpenAssignModal = (order: OrderItem) => {
    setAssigningOrder(order);
    const bestDriver = onlineIdleDrivers[0] || drivers[0];
    if (bestDriver) {
      setSelectedDriverId(bestDriver.id);
    }
  };

  // Xác nhận gán tài xế (UC-12)
  const handleConfirmAssignment = () => {
    if (!assigningOrder || !selectedDriverId) return;

    const assignedDriver = drivers.find((d) => d.id === selectedDriverId);
    if (!assignedDriver) return;

    updateDemoState((previous) => ({ ...previous,
      orders: previous.orders.map((o) =>
        o.id === assigningOrder.id
          ? {
              ...o,
              status: "DA_GAN",
              statusText: "Đã gán tài xế",
              driver: {
                id: assignedDriver.id,
                name: assignedDriver.name,
                phone: assignedDriver.phone,
                vehiclePlate: assignedDriver.vehiclePlate,
                vehicleType: assignedDriver.vehicleType,
              },
            }
          : o
      ),
      drivers: previous.drivers.map((d) =>
        d.id === selectedDriverId ? { ...d, status: "ONLINE_BUSY", idleSinceMinutes: 0 } : d
      ),
    }));

    showToast(
      `Đã phân công tài xế ${assignedDriver.name} (${assignedDriver.vehiclePlate}) cho đơn ${assigningOrder.id}!`,
      "success"
    );
    setAssigningOrder(null);
  };

  // Xử lý sự cố: Đổi tài xế khác (UC-13)
  const handleReassignIncidentOrder = (orderId: string) => {
    const priorDriverId = orders.find((order) => order.id === orderId)?.driver?.id;
    updateDemoState((previous) => ({ ...previous,
      orders: previous.orders.map((o) =>
        o.id === orderId
          ? {
              ...o,
              status: "CHO_GAN",
              statusText: "Chờ gán tài xế (Điều phối lại)",
              incident: undefined,
              driver: undefined,
            }
          : o
      ),
      incidents: previous.incidents.map((incident) => incident.orderId === orderId ? { ...incident, status: "DA_GIAI_QUYET" } : incident),
      drivers: previous.drivers.map((driver) => driver.id === priorDriverId ? { ...driver, status: "ONLINE_IDLE" } : driver),
    }));
    showToast(`Đã thu hồi đơn ${orderId} về hàng đợi chờ gán để phân công tài xế mới!`, "info");
  };

  // Hủy đơn hàng từ điều phối (UC-14)
  const handleCancelOrder = (orderId: string, reason: string) => {
    const priorDriverId = orders.find((order) => order.id === orderId)?.driver?.id;
    updateDemoState((previous) => ({ ...previous,
      orders: previous.orders.map((o) =>
        o.id === orderId
          ? {
              ...o,
              status: "DA_HUY",
              statusText: `Đã hủy: ${reason}`,
            }
          : o
      ),
      incidents: previous.incidents.map((incident) => incident.orderId === orderId ? { ...incident, status: "DA_GIAI_QUYET" } : incident),
      drivers: previous.drivers.map((driver) => driver.id === priorDriverId ? { ...driver, status: "ONLINE_IDLE" } : driver),
    }));
    showToast(`Đã hủy đơn ${orderId} thành công theo yêu cầu nghiệp vụ!`, "error");
  };

  return (
    <div className="min-h-screen flex flex-col bg-[#FAFAF9] text-[#1C1917] font-sans">
      <DispatcherNavbar
        pendingCount={pendingOrders.length}
        incidentCount={incidents.filter((i) => i.status === "CHO_XU_LY").length}
      />

      <div className="bg-amber-50 border-b border-amber-200 px-4 py-2 text-sm"><div className="max-w-[1600px] mx-auto flex flex-wrap justify-between gap-2"><span>Bản giao diện mẫu · dữ liệu mô phỏng trong trình duyệt</span><Link href="/dispatcher/live" className="font-bold text-amber-900 underline">Điều phối đơn SQL Server · TX4 →</Link></div></div>

      {/* OPERATIONS TELEMETRY STRIP */}
      <section className="bg-white border-b border-stone-200 px-4 sm:px-6 py-2.5 shadow-2xs">
        <div className="max-w-[1600px] mx-auto flex flex-wrap items-center justify-between gap-3 sm:gap-4">
          {/* Telemetry Metrics */}
          <div className="flex flex-wrap items-center gap-2 sm:gap-3 text-xs sm:text-sm">
            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-stone-50 border border-stone-200 whitespace-nowrap">
              <span className="text-stone-500 font-semibold text-xs">Hàng đợi chờ gán:</span>
              <span className="text-base font-extrabold text-amber-600">{pendingOrders.length}</span>
            </div>

            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-stone-50 border border-stone-200 whitespace-nowrap">
              <span className="text-stone-500 font-semibold text-xs">Đang giao hàng:</span>
              <span className="text-base font-extrabold text-stone-900">{deliveringOrders.length}</span>
            </div>

            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-stone-50 border border-stone-200 whitespace-nowrap">
              <span className="text-stone-500 font-semibold text-xs">Tài xế sẵn sàng:</span>
              <span className="text-base font-extrabold text-emerald-600">
                {onlineIdleDrivers.length}
                <span className="text-xs text-stone-400 font-normal">/{drivers.length}</span>
              </span>
            </div>

            <div className="flex items-center gap-1.5 px-3 py-1.5 rounded-xl bg-stone-50 border border-stone-200 whitespace-nowrap">
              <span className="text-stone-500 font-semibold text-xs">Sự cố can thiệp:</span>
              <span className={`text-base font-extrabold ${incidents.some((incident) => incident.status === "CHO_XU_LY") ? "text-rose-600" : "text-stone-400"}`}>
                {incidents.filter((i) => i.status === "CHO_XU_LY").length}
              </span>
            </div>
          </div>

          {/* Quick Simulation Triggers */}
          <div className="flex items-center gap-2 shrink-0">
            <Link
              href="/dispatcher/create-order"
              className="px-3.5 py-1.5 rounded-xl bg-amber-500 hover:bg-amber-600 text-stone-900 text-xs font-bold shadow-xs transition-all active:scale-[0.98] whitespace-nowrap"
            >
              + Tiếp nhận cuộc gọi
            </Link>
          </div>
        </div>
      </section>

      {/* 3-COLUMN OPERATIONS COCKPIT */}
      <main className="flex-1 max-w-[1600px] w-full mx-auto p-4 sm:p-6 grid grid-cols-1 lg:grid-cols-12 gap-6">
        {/* ================= COLUMN 1: PENDING QUEUE (4 COLS) ================= */}
        <section className="lg:col-span-4 flex flex-col bg-white border border-stone-200 rounded-2xl overflow-hidden shadow-2xs">
          <div className="p-3.5 sm:p-4 border-b border-stone-200 bg-stone-50/70 flex items-center justify-between gap-2">
            <div className="flex items-center gap-2 shrink-0">
              <span className="text-sm font-extrabold text-stone-900 tracking-tight whitespace-nowrap">
                Hàng Đợi Chờ Gán
              </span>
              <span className="px-2 py-0.5 rounded-full text-xs font-bold bg-amber-100 text-amber-900 border border-amber-300">
                {pendingOrders.length}
              </span>
            </div>
            <span className="text-xs font-semibold text-stone-400 whitespace-nowrap">Hàng đợi</span>
          </div>

          <div className="flex-1 p-3.5 space-y-3 overflow-y-auto max-h-[calc(100vh-220px)]">
            {pendingOrders.length === 0 ? (
              <div className="py-12 text-center text-xs sm:text-sm text-stone-400 font-medium">
                Không có đơn hàng tồn đọng trong hàng đợi
              </div>
            ) : (
              pendingOrders.map((order) => (
                <div
                  key={order.id}
                  className="p-4 rounded-xl bg-white border border-stone-200 hover:border-amber-400 hover:shadow-xs transition-all space-y-3"
                >
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-extrabold text-amber-700">{order.id}</span>
                    <span className="text-xs font-medium text-stone-400">{order.createdAt}</span>
                  </div>

                  <div className="text-xs space-y-1.5">
                    <div className="flex items-start gap-2">
                      <span className="text-emerald-700 font-bold text-xs shrink-0">Lấy:</span>
                      <span className="text-stone-800 line-clamp-1 font-medium">
                        {order.sender.street}, {order.sender.district}
                      </span>
                    </div>
                    <div className="flex items-start gap-2">
                      <span className="text-amber-700 font-bold text-xs shrink-0">Giao:</span>
                      <span className="text-stone-800 line-clamp-1 font-medium">
                        {order.receiver.street}, {order.receiver.district}
                      </span>
                    </div>
                  </div>

                  <div className="flex items-center justify-between pt-2 border-t border-stone-100 text-xs">
                    <div className="text-stone-500 font-medium">
                      <span>{order.distanceKm} km</span> · <span>{order.packageWeightKg} kg</span>
                    </div>
                    <span className="text-sm font-extrabold text-stone-900">{formatVND(order.totalFee)}</span>
                  </div>

                  <div className="pt-1 flex items-center gap-2">
                    <Button
                      variant="primary"
                      size="sm"
                      fullWidth
                      onClick={() => handleOpenAssignModal(order)}
                      className="text-xs font-bold"
                    >
                      Phân công tài xế
                    </Button>
                    <Link
                      href={`/dispatcher/orders/${order.id}`}
                      className="px-3 py-2 rounded-xl border border-stone-200 hover:bg-stone-50 text-stone-700 text-xs font-semibold shrink-0 transition-colors"
                    >
                      Chi tiết
                    </Link>
                  </div>
                </div>
              ))
            )}
          </div>
        </section>

        {/* ================= COLUMN 2: IN-FLIGHT OPS & INCIDENTS (5 COLS) ================= */}
        <section className="lg:col-span-5 flex flex-col bg-white border border-stone-200 rounded-2xl overflow-hidden shadow-2xs">
          <div className="p-3.5 sm:p-4 border-b border-stone-200 bg-stone-50/70 flex items-center justify-between gap-2">
            <div className="flex items-center gap-2 shrink-0">
              <span className="text-sm font-extrabold text-stone-900 tracking-tight whitespace-nowrap">
                Đơn Đang Thực Hiện & Sự Cố
              </span>
              <span className="px-2 py-0.5 rounded-full text-xs font-bold bg-stone-100 text-stone-800 border border-stone-300">
                {deliveringOrders.length}
              </span>
            </div>
            <span className="text-xs font-semibold text-stone-400 whitespace-nowrap">Theo dõi trực tiếp</span>
          </div>

          <div className="flex-1 p-3.5 space-y-3 overflow-y-auto max-h-[calc(100vh-220px)]">
            {/* INCIDENT ALERTS */}
            {incidents.filter((i) => i.status === "CHO_XU_LY").length > 0 && (
              <div className="p-3.5 rounded-xl bg-rose-50 border border-rose-200 space-y-2.5">
                <div className="flex items-center justify-between text-xs font-bold text-rose-900">
                  <div className="flex items-center gap-1.5">
                    <span className="w-2 h-2 rounded-full bg-rose-600" />
                    <span>Cảnh báo sự cố cần can thiệp ({incidents.length})</span>
                  </div>
                  <span className="text-[11px] text-rose-700 font-semibold">Cần xử lý</span>
                </div>
                {incidents.map((inc) => (
                  <div key={inc.id} className="p-3 rounded-lg bg-white border border-rose-200 text-xs space-y-2">
                    <div className="flex items-center justify-between">
                      <span className="font-extrabold text-rose-800">{inc.orderId}</span>
                      <span className="text-[11px] text-stone-400 font-medium">{inc.reportedAt}</span>
                    </div>
                    <p className="text-stone-700 leading-relaxed font-medium">{inc.note}</p>
                    <div className="flex items-center gap-2 pt-1">
                      <button
                        type="button"
                        onClick={() => handleReassignIncidentOrder(inc.orderId)}
                        className="px-2.5 py-1.5 rounded-lg bg-amber-500 hover:bg-amber-600 text-stone-900 text-xs font-bold transition-colors cursor-pointer"
                      >
                        Đổi tài xế khác
                      </button>
                      <button
                        type="button"
                        onClick={() => handleCancelOrder(inc.orderId, inc.note)}
                        className="px-2.5 py-1.5 rounded-lg bg-stone-100 hover:bg-rose-100 text-rose-800 text-xs font-semibold border border-stone-200 transition-colors cursor-pointer"
                      >
                        Hủy đơn
                      </button>
                    </div>
                  </div>
                ))}
              </div>
            )}

            {/* LIVE DELIVERING ORDERS */}
            {deliveringOrders.map((order) => (
              <div
                key={order.id}
                className="p-4 rounded-xl bg-white border border-stone-200 hover:border-stone-300 transition-all space-y-2.5"
              >
                <div className="flex items-center justify-between">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-extrabold text-stone-900">{order.id}</span>
                    <Badge status={order.status} />
                  </div>
                  <span className="text-xs text-stone-400 font-medium">{order.createdAt}</span>
                </div>

                <div className="text-xs space-y-1.5 text-stone-600">
                  <div className="flex justify-between font-medium">
                    <span className="text-stone-400">Tuyến đường:</span>
                    <span className="text-stone-800 font-semibold">
                      {order.sender.district} → {order.receiver.district}
                    </span>
                  </div>
                  {order.driver && (
                    <div className="flex justify-between items-baseline text-xs font-medium">
                      <span className="text-stone-400">Tài xế giao:</span>
                      <span className="text-amber-800 font-bold">
                        {order.driver.name} ({order.driver.vehiclePlate})
                      </span>
                    </div>
                  )}
                </div>

                <div className="flex items-center justify-between pt-2.5 border-t border-stone-100 text-xs">
                  <span className="text-sm font-extrabold text-stone-900">{formatVND(order.totalFee)}</span>
                  <Link
                    href={`/dispatcher/orders/${order.id}`}
                    className="text-xs text-amber-700 hover:text-amber-800 font-bold"
                  >
                    Xem chi tiết & Can thiệp →
                  </Link>
                </div>
              </div>
            ))}
          </div>
        </section>

        {/* ================= COLUMN 3: FLEET ROSTER (3 COLS) ================= */}
        <section className="lg:col-span-3 flex flex-col bg-white border border-stone-200 rounded-2xl overflow-hidden shadow-2xs">
          <div className="p-3.5 sm:p-4 border-b border-stone-200 bg-stone-50/70 flex items-center justify-between gap-2">
            <div className="flex items-center gap-2 shrink-0">
              <span className="text-sm font-extrabold text-stone-900 tracking-tight whitespace-nowrap">
                Đội Xe Trực Tuyến
              </span>
              <span className="px-2 py-0.5 rounded-full text-xs font-bold bg-emerald-100 text-emerald-800 border border-emerald-300">
                {onlineIdleDrivers.length} rảnh
              </span>
            </div>
            <Link
              href="/dispatcher/fleet"
              className="text-xs font-semibold text-stone-500 hover:text-amber-600 whitespace-nowrap"
            >
              Tất cả →
            </Link>
          </div>

          <div className="flex-1 p-3.5 space-y-2.5 overflow-y-auto max-h-[calc(100vh-220px)]">
            {drivers.map((driver) => {
              const isIdle = driver.status === "ONLINE_IDLE";
              const isBusy = driver.status === "ONLINE_BUSY";

              return (
                <div
                  key={driver.id}
                  className="p-3.5 rounded-xl bg-white border border-stone-200 hover:border-stone-300 transition-all space-y-2"
                >
                  <div className="flex items-center justify-between">
                    <div>
                      <span className="font-bold text-xs text-stone-900 block">{driver.name}</span>
                      <span className="text-xs font-semibold text-stone-500">{driver.vehiclePlate}</span>
                    </div>
                    <span
                      className={`px-2 py-0.5 rounded-full text-[11px] font-bold border ${
                        isIdle
                          ? "bg-emerald-50 text-emerald-800 border-emerald-200"
                          : isBusy
                          ? "bg-amber-50 text-amber-800 border-amber-200"
                          : "bg-stone-100 text-stone-500 border-stone-200"
                      }`}
                    >
                      {isIdle ? `Rảnh ${driver.idleSinceMinutes}m` : isBusy ? "Đang giao" : "Tạm nghỉ"}
                    </span>
                  </div>

                  <div className="flex items-center justify-between text-xs text-stone-500 font-medium pt-1.5 border-t border-stone-100">
                    <span>{driver.currentDistrict}</span>
                    <span className="font-semibold text-stone-700">COD: {formatVND(driver.codCashHolding)}</span>
                  </div>
                </div>
              );
            })}
          </div>
        </section>
      </main>

      {/* MODAL PHÂN CÔNG TÀI XẾ THÔNG MINH (UC-12) */}
      {assigningOrder && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-stone-900/40 backdrop-blur-xs">
          <div className="w-full max-w-lg bg-white border border-stone-200 rounded-2xl shadow-xl p-6 space-y-5 text-stone-800">
            <div className="flex items-start justify-between border-b border-stone-200 pb-3">
              <div>
                <span className="text-xs font-bold text-amber-800 uppercase tracking-wider block">
                  ĐIỀU PHỐI TÀI XẾ
                </span>
                <h3 className="text-lg font-extrabold text-stone-900 mt-1">
                  Phân công tài xế cho đơn {assigningOrder.id}
                </h3>
              </div>
              <button
                type="button"
                onClick={() => setAssigningOrder(null)}
                className="text-stone-400 hover:text-stone-600 text-base font-bold cursor-pointer"
              >
                ✕
              </button>
            </div>

            <div className="p-3.5 rounded-xl bg-stone-50 border border-stone-200 space-y-2 text-xs">
              <div className="flex justify-between font-medium">
                <span className="text-stone-500">Điểm lấy:</span>
                <span className="text-stone-900 font-semibold">
                  {assigningOrder.sender.street}, {assigningOrder.sender.district}
                </span>
              </div>
              <div className="flex justify-between font-medium">
                <span className="text-stone-500">Điểm giao:</span>
                <span className="text-stone-900 font-semibold">
                  {assigningOrder.receiver.street}, {assigningOrder.receiver.district}
                </span>
              </div>
              <div className="flex justify-between pt-1.5 border-t border-stone-200">
                <span className="text-stone-500 font-medium">Cự ly & Cước phí:</span>
                <span className="text-amber-800 font-extrabold text-sm">
                  {assigningOrder.distanceKm} km · {formatVND(assigningOrder.totalFee)}
                </span>
              </div>
            </div>

            <div className="space-y-2.5">
              <label className="block text-xs font-bold text-stone-800 uppercase tracking-wide">
                Gợi ý tài xế tối ưu (Đang trực tuyến rảnh):
              </label>

              <div className="space-y-2 max-h-56 overflow-y-auto">
                {drivers.map((d) => {
                  const isSelected = selectedDriverId === d.id;
                  const isIdle = d.status === "ONLINE_IDLE";

                  return (
                    <label
                      key={d.id}
                      className={`flex items-center justify-between p-3.5 rounded-xl border text-xs cursor-pointer transition-all ${
                        isSelected
                          ? "border-amber-500 bg-amber-50 shadow-2xs"
                          : "border-stone-200 bg-white hover:border-stone-300"
                      }`}
                    >
                      <div className="flex items-center gap-3">
                        <input
                          type="radio"
                          name="driverAssign"
                          checked={isSelected}
                          onChange={() => setSelectedDriverId(d.id)}
                          className="text-amber-500 focus:ring-0"
                        />
                        <div>
                          <div className="font-bold text-sm text-stone-900">{d.name}</div>
                          <div className="text-xs text-stone-500 font-medium">
                            {d.vehiclePlate} · {d.currentDistrict}
                          </div>
                        </div>
                      </div>

                      <div className="text-right text-xs">
                        <span
                          className={`font-bold block ${
                            isIdle ? "text-emerald-700" : "text-amber-700"
                          }`}
                        >
                          {isIdle ? `Rảnh ${d.idleSinceMinutes}m` : "Đang giao"}
                        </span>
                        <span className="text-stone-400 text-[11px] font-medium">Đánh giá: ★{d.rating}</span>
                      </div>
                    </label>
                  );
                })}
              </div>
            </div>

            <div className="flex items-center justify-end gap-3 pt-3 border-t border-stone-200">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setAssigningOrder(null)}
                className="text-xs font-semibold"
              >
                Hủy bỏ
              </Button>
              <Button
                variant="primary"
                size="sm"
                onClick={handleConfirmAssignment}
                className="text-xs font-bold shadow-sm"
              >
                Xác nhận phân công
              </Button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
