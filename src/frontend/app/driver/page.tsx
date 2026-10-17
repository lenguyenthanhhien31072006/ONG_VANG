"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { DriverNavbar } from "@/components/layout/DriverNavbar";
import { DriverBottomNav } from "@/components/layout/DriverBottomNav";
import { Button } from "@/components/ui/Button";
import { Badge } from "@/components/ui/Badge";
import type { OrderItem } from "@/lib/types";
import { api, type Driver, type Order } from "@/lib/live-orders";
import type { DriverEarnings } from "@/lib/finance";
import { reportRange } from "@/lib/finance";
import { driverForView, orderForView, type DispatchDriver } from "@/lib/dispatch-view";
import { formatVND } from "@/lib/utils";
import { useToast } from "@/components/ui/Toast";

export default function DriverDashboardPage() {
  const { showToast } = useToast();
  const router = useRouter();
  const [driver, setDriver] = useState<DispatchDriver | null>(null);
  const [todayFare, setTodayFare] = useState("0.00");
  const [activeOrder, setActiveOrder] = useState<OrderItem | null>(null);
  const [todayTrips, setTodayTrips] = useState<OrderItem[]>([]);
  const [incomingOrder, setIncomingOrder] = useState<OrderItem | null>(null);
  const [isRejectModalOpen, setIsRejectModalOpen] = useState(false);
  const [rejectReason, setRejectReason] = useState("Đang ở quá xa điểm lấy hàng");
  const [error, setError] = useState("");
  const isOnline = driver?.status === "ONLINE_IDLE" || driver?.status === "ONLINE_BUSY";
  const ratedTrips: OrderItem[] = [];
  const averageRating = "—";

  const refresh = useCallback(async () => {
    try {
      const { from, to } = reportRange("TODAY");
      const [self, assigned, picked, delivering, completed, earnings] = await Promise.all([
        api<Driver>("/drivers/me"),
        api<{ items: Order[] }>("/orders?trangThai=DA_GAN&size=100"),
        api<{ items: Order[] }>("/orders?trangThai=DA_LAY_HANG&size=100"),
        api<{ items: Order[] }>("/orders?trangThai=DANG_GIAO&size=100"),
        api<{ items: Order[] }>("/orders?trangThai=HOAN_TAT&size=100"),
        api<DriverEarnings>(`/drivers/me/earnings?tuNgay=${from}&denNgay=${to}`),
      ]);
      setTodayFare(earnings.tongCuocDaHoanTat);
      const roster = [driverForView(self)];
      setDriver(roster[0]);
      const current = [...assigned.items, ...picked.items, ...delivering.items].map(order => orderForView(order, roster));
      setActiveOrder(current[0] ?? null);
      setIncomingOrder(previous => previous && current[0]?.id === previous.id && current[0].status === "DA_GAN"
        ? current[0] : null);
      if (current[0]?.status !== "DA_GAN") setIsRejectModalOpen(false);
      const today = new Date().toDateString();
      setTodayTrips(completed.items.map(order => orderForView(order, roster))
        .filter(order => new Date(order.createdTimestamp).toDateString() === today));
      setError("");
    } catch (cause) { setError((cause as Error).message); }
  }, []);
  useEffect(() => {
    const initial = setTimeout(() => { void refresh(); }, 0);
    const timer = setInterval(() => { void refresh(); }, 15000);
    return () => { clearTimeout(initial); clearInterval(timer); };
  }, [refresh]);

  const handleToggleOnline = () => showToast("Trạng thái trực tuyến hiện được quản lý ở backend.", "info");
  const handleTriggerIncomingOrder = () => {
    if (activeOrder?.status === "DA_GAN") setIncomingOrder(activeOrder);
    else showToast("Chưa có đơn mới được phân công cho bạn.", "info");
  };
  const handleOpenAssignedOrder = () => {
    if (!incomingOrder) return;
    const id = incomingOrder.id;
    setIncomingOrder(null);
    router.push(`/driver/orders/${encodeURIComponent(id)}`);
  };
  const handleRejectOrder = async () => {
    if (!incomingOrder) return;
    try {
      await api(`/orders/${encodeURIComponent(incomingOrder.id)}/reject`, {
        method: "POST", body: JSON.stringify({ lyDo: rejectReason }),
      });
      setIsRejectModalOpen(false); setIncomingOrder(null); await refresh();
      showToast("Đã từ chối đơn; đơn trở về hàng đợi điều phối.", "info");
    } catch (cause) { await refresh(); showToast((cause as Error).message, "error"); }
  };

  return (
    <div className="min-h-screen flex flex-col bg-[#FAFAF9] text-[#1C1917] pb-20 md:pb-8">
      <DriverNavbar isOnline={isOnline} onToggleOnline={handleToggleOnline} showStatus={false} />

      <main className="flex-1 max-w-[1024px] w-full mx-auto px-4 sm:px-6 py-5 sm:py-8 space-y-5">
        {error && <p role="alert" className="rounded-xl bg-rose-50 p-3 text-sm text-rose-800">{error}</p>}
        {/* BANNER THÔNG BÁO TRẠNG THÁI */}
        <section
          className={`p-4 rounded-2xl border transition-all flex items-center justify-between gap-3 ${
            isOnline
              ? "bg-emerald-500/10 border-emerald-500/30 text-emerald-950"
              : "bg-stone-100 border-stone-200 text-stone-600"
          }`}
        >
          <div className="flex items-center gap-3">
            <div
              className={`w-3.5 h-3.5 rounded-full ${
                isOnline ? "bg-emerald-500 animate-ping" : "bg-stone-400"
              }`}
            />
            <div>
              <h2 className="font-bold text-sm sm:text-base">
                {isOnline ? "Đang trực tuyến sẵn sàng nhận cuốc" : "Đang tạm nghỉ nhận chuyến"}
              </h2>
              <p className="text-xs text-stone-500">
                {isOnline
                  ? "Bạn đang sẵn sàng nhận phân công từ điều phối viên."
                  : "Bật trực tuyến ở góc trên để bắt đầu nhận phân công từ điều phối viên."}
              </p>
            </div>
          </div>

          {/* Test Trigger Button */}
          <button
            type="button"
            onClick={handleTriggerIncomingOrder}
            className="px-3 py-1.5 rounded-xl bg-amber-500 hover:bg-amber-600 text-stone-950 text-xs font-bold shrink-0 shadow-2xs"
            title="Xem đơn mới đã được phân công cho bạn"
          >
            Xem đơn đã gán
          </button>
        </section>

        {/* 3 THẺ KPI TRONG NGÀY */}
        <section className="grid grid-cols-3 gap-2.5 sm:gap-4">
          <div className="bg-white border border-stone-200 rounded-2xl p-3.5 sm:p-4 shadow-2xs text-center">
            <span className="text-[10px] sm:text-xs font-bold uppercase tracking-wider text-stone-400 block">
              Cuốc hôm nay
            </span>
            <div className="text-xl sm:text-3xl font-extrabold text-stone-900 mt-1">
              {todayTrips.length}
            </div>
            <span className="text-[10px] text-emerald-600 font-semibold hidden sm:inline">
              Đơn hoàn tất được tạo hôm nay
            </span>
          </div>

          <div className="bg-white border border-stone-200 rounded-2xl p-3.5 sm:p-4 shadow-2xs text-center">
            <span className="text-[10px] sm:text-xs font-bold uppercase tracking-wider text-stone-400 block">
              Tổng cước hôm nay
            </span>
            <div className="text-xl sm:text-3xl font-extrabold text-amber-600 mt-1">
              {formatVND(Number(todayFare))}
            </div>
            <span className="text-[10px] text-stone-500 font-medium hidden sm:inline">
              Theo thời điểm hoàn tất, chưa phải thu nhập thực nhận
            </span>
          </div>

          <div className="bg-white border border-stone-200 rounded-2xl p-3.5 sm:p-4 shadow-2xs text-center">
            <span className="text-[10px] sm:text-xs font-bold uppercase tracking-wider text-stone-400 block">
              Đánh giá sao
            </span>
            <div className="text-xl sm:text-3xl font-extrabold text-stone-900 mt-1 flex items-center justify-center gap-1">
              <span className="text-amber-500">★</span> {averageRating}
            </div>
            <span className="text-[10px] text-amber-700 font-semibold hidden sm:inline">
              {ratedTrips.length} lượt đánh giá
            </span>
          </div>
        </section>

        {/* THẺ ĐƠN HÀNG ĐANG THỰC HIỆN HOẶC TRẠNG THÁI TÌM ĐƠN */}
        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h3 className="font-extrabold text-stone-900 text-sm sm:text-base uppercase tracking-wider">
              Cuốc xe đang phụ trách
            </h3>
            {activeOrder && (
              <Badge status={activeOrder.status} />
            )}
          </div>

          {activeOrder ? (
            /* ACTIVE JOB CARD */
            <div className="bg-white border-2 border-amber-400/80 rounded-2xl p-4 sm:p-6 shadow-md space-y-4 relative overflow-hidden animate-fadeIn">
              <div className="flex items-center justify-between pb-3 border-b border-stone-100 flex-wrap gap-2">
                <div className="flex items-center gap-2">
                  <span className="w-2.5 h-2.5 rounded-full bg-amber-500 animate-pulse" />
                  <span className="font-mono font-black text-base sm:text-lg text-stone-900">
                    {activeOrder.id}
                  </span>
                  <span className="text-xs text-stone-400 font-medium">
                    · {activeOrder.packageType}
                  </span>
                </div>
                <div className="text-right">
                  <span className="text-[10px] font-bold text-stone-400 block uppercase">
                    Cước phí thu
                  </span>
                  <span className="text-base sm:text-lg font-black text-amber-600">
                    {formatVND(activeOrder.totalFee)}
                  </span>
                </div>
              </div>

              {/* Route */}
              <div className="space-y-3 text-xs sm:text-sm">
                <div className="flex items-start gap-3">
                  <div className="w-6 flex flex-col items-center shrink-0 mt-0.5">
                    <span className="w-3 h-3 rounded-full bg-stone-400" />
                    <span className="w-0.5 h-7 bg-stone-200" />
                  </div>
                  <div>
                    <span className="text-[10px] font-bold uppercase tracking-wider text-stone-400 block">
                      Điểm lấy hàng
                    </span>
                    <strong className="text-stone-900">{activeOrder.sender.street}</strong>,{" "}
                    <span className="text-stone-600">{activeOrder.sender.district}</span>
                    <div className="text-[11px] text-stone-500 mt-0.5">
                      {activeOrder.sender.name} ({activeOrder.sender.phone})
                    </div>
                  </div>
                </div>

                <div className="flex items-start gap-3">
                  <div className="w-6 flex flex-col items-center shrink-0 mt-0.5">
                    <span className="w-3 h-3 rounded-full bg-amber-500 ring-4 ring-amber-100" />
                  </div>
                  <div>
                    <span className="text-[10px] font-bold uppercase tracking-wider text-amber-700 block">
                      Điểm giao hàng
                    </span>
                    <strong className="text-stone-900">{activeOrder.receiver.street}</strong>,{" "}
                    <span className="text-stone-600">{activeOrder.receiver.district}</span>
                    <div className="text-[11px] text-stone-500 mt-0.5">
                      {activeOrder.receiver.name} ({activeOrder.receiver.phone})
                    </div>
                  </div>
                </div>
              </div>

              {/* Big Cockpit CTA Button */}
              <div className="pt-2">
                <Link href={`/driver/orders/${activeOrder.id}`} className="block w-full">
                  <Button variant="primary" size="lg" fullWidth className="h-13 text-base">
                    <span>VÀO BUỒNG LÁI THỰC HIỆN CUỐC GIAO</span>
                    <span className="text-xl">➔</span>
                  </Button>
                </Link>
              </div>
            </div>
          ) : (
            /* RADAR SCANNING STATE */
            <div className="bg-white border border-stone-200 rounded-2xl p-8 sm:p-12 text-center space-y-4 shadow-2xs">
              <div className="relative w-20 h-20 mx-auto flex items-center justify-center">
                <span className="absolute w-20 h-20 rounded-full bg-amber-400/20 animate-ping" />
                <span className="absolute w-14 h-14 rounded-full bg-amber-400/40 animate-pulse" />
                <div className="relative w-10 h-10 rounded-full bg-amber-500 text-stone-950 flex items-center justify-center text-xl font-bold shadow-sm">
                  🏍️
                </div>
              </div>

              <div className="space-y-1">
                <h4 className="font-extrabold text-stone-900 text-base">
                  {isOnline ? "Đang chờ cuốc xe mới..." : "Bạn đang ở chế độ Tạm nghỉ"}
                </h4>
                <p className="text-xs text-stone-500 max-w-sm mx-auto">
                  {isOnline
                    ? "Hệ thống sẽ phát chuông thông báo khi có đơn hàng trong khu vực phân công."
                    : "Bật trực tuyến để nhận đơn và gia tăng thu nhập ngay hôm nay."}
                </p>
              </div>
            </div>
          )}
        </section>

        {/* LỐI TẮT NHANH */}
        <section className="grid grid-cols-2 gap-3 pt-2">
          <Link
            href="/driver/history"
            className="p-4 rounded-xl bg-white border border-stone-200 hover:border-amber-300 transition-colors shadow-2xs space-y-1"
          >
            <span className="text-xl">📜</span>
            <h4 className="font-bold text-stone-900 text-xs sm:text-sm">Lịch sử chuyến</h4>
            <p className="text-[11px] text-stone-500">Xem lại các chuyến xe đã hoàn thành</p>
          </Link>

          <Link
            href="/driver/wallet"
            className="p-4 rounded-xl bg-white border border-stone-200 hover:border-amber-300 transition-colors shadow-2xs space-y-1"
          >
            <span className="text-xl">💳</span>
            <h4 className="font-bold text-stone-900 text-xs sm:text-sm">Thu nhập tài xế</h4>
            <p className="text-[11px] text-stone-500">Xem phần cước được hưởng và lịch sử thu cước</p>
          </Link>
        </section>
      </main>

      {/* ========================================================================= */}
      {/* MODAL / BOTTOM SHEET: ĐƠN PHÂN CÔNG MỚI ĐẾN (TX-02 / UC-12)               */}
      {/* ========================================================================= */}
      {incomingOrder && (
        <div className="fixed inset-0 z-50 flex items-end sm:items-center justify-center p-0 sm:p-4 bg-stone-900/60 backdrop-blur-xs animate-fadeIn">
          <div className="w-full sm:max-w-lg bg-white sm:rounded-3xl rounded-t-3xl shadow-2xl p-5 sm:p-7 space-y-5 animate-scaleUp">
            {/* Header with ring icon & 30s countdown */}
            <div className="flex items-center justify-between pb-3 border-b border-stone-100">
              <div className="flex items-center gap-2">
                <span className="w-3 h-3 rounded-full bg-emerald-500 animate-ping" />
                <span className="text-xs font-bold uppercase tracking-wider text-emerald-700 bg-emerald-50 px-2.5 py-1 rounded-full border border-emerald-200">
                  CUỐC XE MỚI ĐƯỢC GÁN
                </span>
              </div>
              <div className="font-mono text-sm font-extrabold text-amber-600 bg-amber-50 px-2.5 py-1 rounded-full border border-amber-200">
                Đã phân công
              </div>
            </div>

            {/* Price & Distance */}
            <div className="flex items-center justify-between bg-amber-50/60 p-4 rounded-2xl border border-amber-200/80">
              <div>
                <span className="text-[10px] font-bold text-amber-800 block uppercase tracking-wider">
                  Cước chuyến nhận
                </span>
                <span className="text-2xl font-black text-amber-950">
                  {formatVND(incomingOrder.totalFee)}
                </span>
              </div>
              <div className="text-right">
                <span className="text-[10px] font-bold text-amber-800 block uppercase tracking-wider">
                  Cự ly lộ trình
                </span>
                <span className="text-lg font-bold text-amber-950">
                  {incomingOrder.distanceKm} km
                </span>
              </div>
            </div>

            {/* Route preview */}
            <div className="space-y-2.5 text-xs">
              <div className="flex items-start gap-2.5">
                <span className="w-2.5 h-2.5 rounded-full bg-stone-400 mt-1 shrink-0" />
                <div>
                  <span className="text-[10px] text-stone-400 font-bold block">LẤY HÀNG:</span>
                  <strong className="text-stone-900">{incomingOrder.sender.street}, {incomingOrder.sender.district}</strong>
                </div>
              </div>
              <div className="flex items-start gap-2.5">
                <span className="w-2.5 h-2.5 rounded-full bg-amber-500 ring-2 ring-amber-200 mt-1 shrink-0" />
                <div>
                  <span className="text-[10px] text-amber-700 font-bold block">GIAO ĐẾN:</span>
                  <strong className="text-stone-900">{incomingOrder.receiver.street}, {incomingOrder.receiver.district}</strong>
                </div>
              </div>
            </div>

            {/* Action buttons (Big touch targets) */}
            <div className="grid grid-cols-2 gap-3 pt-2">
              <Button
                variant="outline"
                size="lg"
                onClick={() => setIsRejectModalOpen(true)}
                className="h-13 text-red-600 border-red-200 hover:bg-red-50 text-sm font-bold"
              >
                Từ chối cuốc
              </Button>
              <Button
                variant="primary"
                size="lg"
                onClick={handleOpenAssignedOrder}
                className="h-13 text-sm font-bold shadow-md"
              >
                XEM CHI TIẾT ĐƠN
              </Button>
            </div>
          </div>
        </div>
      )}

      {/* ========================================================================= */}
      {/* MODAL: CHỌN LÝ DO TỪ CHỐI ĐƠN GÁN (UC-12)                                 */}
      {/* ========================================================================= */}
      {isRejectModalOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-stone-900/60 backdrop-blur-xs animate-fadeIn">
          <div className="w-full max-w-md bg-white rounded-2xl shadow-xl p-5 sm:p-6 space-y-4 animate-scaleUp">
            <h3 className="text-base font-extrabold text-stone-900">
              Lý do từ chối cuốc xe
            </h3>
            <p className="text-xs text-stone-500">
              Đơn hàng sẽ được chuyển lại trạng thái <strong>Chờ gán</strong> để điều phối viên phân công tài xế khác.
            </p>

            <div className="space-y-2">
              {[
                "Đang ở quá xa điểm lấy hàng (> 5km)",
                "Xe bị sự cố (hỏng hóc, thủng lốp)",
                "Hàng hóa cồng kềnh quá tải trọng xe máy",
                "Đang có việc gấp cá nhân đột xuất",
              ].map((reason) => (
                <label
                  key={reason}
                  className={`flex items-center gap-3 p-3 rounded-xl border text-xs cursor-pointer transition-colors ${
                    rejectReason === reason
                      ? "border-red-400 bg-red-50/50 text-red-950 font-semibold"
                      : "border-stone-200 hover:bg-stone-50 text-stone-700"
                  }`}
                >
                  <input
                    type="radio"
                    name="rejectReason"
                    checked={rejectReason === reason}
                    onChange={() => setRejectReason(reason)}
                    className="text-red-600 focus:ring-red-500"
                  />
                  <span>{reason}</span>
                </label>
              ))}
            </div>

            <div className="flex items-center justify-end gap-2.5 pt-2">
              <Button
                variant="outline"
                size="sm"
                onClick={() => setIsRejectModalOpen(false)}
              >
                Quay lại
              </Button>
              <Button
                variant="danger"
                size="sm"
                onClick={handleRejectOrder}
              >
                Xác nhận từ chối
              </Button>
            </div>
          </div>
        </div>
      )}

      <DriverBottomNav />
    </div>
  );
}
