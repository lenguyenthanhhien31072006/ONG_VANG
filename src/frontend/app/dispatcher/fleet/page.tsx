"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { DispatcherNavbar } from "@/components/layout/DispatcherNavbar";
import { api, type Driver } from "@/lib/live-orders";
import { driverForView, type DispatchDriver } from "@/lib/dispatch-view";
import type { DriverStatus } from "@/lib/types";
import { useToast } from "@/components/ui/Toast";

type FilterStatus = "ALL" | DriverStatus;

export default function DispatcherFleetPage() {
  const { showToast } = useToast();
  const [drivers, setDrivers] = useState<DispatchDriver[]>([]);
  const [filter, setFilter] = useState<FilterStatus>("ALL");
  const [searchDistrict, setSearchDistrict] = useState("ALL");
  const [editing, setEditing] = useState<DispatchDriver | null>(null);
  const [address, setAddress] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const refresh = useCallback(async () => {
    try {
      const page = await api<{ items: Driver[] }>("/drivers?size=100");
      setDrivers(page.items.map(driverForView));
      setError("");
    }
    catch (cause) { setError((cause as Error).message); }
  }, []);
  useEffect(() => {
    const initial = setTimeout(() => { void refresh(); }, 0);
    return () => clearTimeout(initial);
  }, [refresh]);

  const filteredDrivers = drivers.filter(driver =>
    (filter === "ALL" || driver.status === filter) &&
    (searchDistrict === "ALL" || driver.currentDistrict.includes(searchDistrict)));
  const onlineIdleCount = drivers.filter(driver => driver.status === "ONLINE_IDLE").length;
  const onlineBusyCount = drivers.filter(driver => driver.status === "ONLINE_BUSY").length;
  const offlineCount = drivers.filter(driver => driver.status === "OFFLINE").length;

  const handlePingDriver = (driver: DispatchDriver) => { setEditing(driver); setAddress(driver.currentDistrict === "Chưa cấu hình vị trí demo" ? "" : driver.currentDistrict); };
  const savePosition = async () => {
    if (!editing || !address.trim() || saving) return;
    setSaving(true);
    try {
      await api(`/drivers/${encodeURIComponent(editing.id)}/demo-position`, {
        method: "POST", body: JSON.stringify({ diaChi: address.trim() }),
      });
      setEditing(null); await refresh(); showToast("Đã lưu vị trí demo cố định từ Goong.", "success");
    } catch (cause) { setError((cause as Error).message); }
    finally { setSaving(false); }
  };

  return (
    <div className="min-h-screen flex flex-col bg-[#FAFAFA] text-[#1C1917] font-sans">
      <DispatcherNavbar />

      <main className="flex-1 max-w-[1440px] w-full mx-auto p-4 sm:p-6 lg:p-8 space-y-6">
        {error && <p role="alert" className="rounded-xl bg-rose-50 p-3 text-sm text-rose-800">{error}</p>}
        {/* Header */}
        <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-stone-200">
          <div>
            <div className="inline-flex items-center gap-2 px-2.5 py-0.5 rounded-full bg-amber-100 text-amber-900 text-xs font-bold mb-1.5">
              QUẢN TRỊ ĐỘI XE (DP-04)
            </div>
            <h1 className="text-xl sm:text-2xl font-extrabold text-stone-900 tracking-tight">
              Giám Sát Đội Xe & Tài Xế Tác Chiến
            </h1>
            <p className="text-xs sm:text-sm text-stone-500 mt-1">
              Theo dõi trạng thái trực tuyến, phương tiện và vị trí demo của đội ngũ tài xế
            </p>
          </div>

          <Link
            href="/dispatcher"
            className="text-xs sm:text-sm font-semibold text-stone-500 hover:text-amber-800 transition-colors"
          >
            ← Bàn điều phối
          </Link>
        </div>

        {/* FLEET TELEMETRY STRIP */}
        <section className="grid grid-cols-2 lg:grid-cols-4 divide-y lg:divide-y-0 lg:divide-x divide-stone-200 border border-stone-200 bg-white rounded-2xl overflow-hidden shadow-2xs">
          <div className="p-5 space-y-1">
            <span className="text-xs font-bold uppercase tracking-wider text-stone-400 block">
              Tổng số tài xế
            </span>
            <div className="text-2xl sm:text-3xl font-extrabold text-stone-900">{drivers.length}</div>
            <span className="text-xs text-stone-500 font-medium block">Đã đăng ký vận hành</span>
          </div>

          <div className="p-5 space-y-1">
            <span className="text-xs font-bold uppercase tracking-wider text-emerald-700 block">
              Đang trực tuyến (Rảnh)
            </span>
            <div className="text-2xl sm:text-3xl font-extrabold text-emerald-600">{onlineIdleCount}</div>
            <span className="text-xs text-stone-500 font-medium block">Sẵn sàng nhận cuốc ngay</span>
          </div>

          <div className="p-5 space-y-1">
            <span className="text-xs font-bold uppercase tracking-wider text-amber-700 block">
              Đang chạy chuyến
            </span>
            <div className="text-2xl sm:text-3xl font-extrabold text-amber-600">{onlineBusyCount}</div>
            <span className="text-xs text-stone-500 font-medium block">Đang lấy hoặc giao hàng</span>
          </div>

          <div className="p-5 space-y-1">
            <span className="text-xs font-bold uppercase tracking-wider text-stone-400 block">
              Tạm nghỉ (Offline)
            </span>
            <div className="text-2xl sm:text-3xl font-extrabold text-stone-500">{offlineCount}</div>
            <span className="text-xs text-stone-500 font-medium block">Ngoài ca trực tiếp nhận</span>
          </div>
        </section>

        {/* BỘ LỌC TÀI XẾ */}
        <div className="bg-white border border-stone-200 rounded-2xl p-4 flex flex-wrap items-center justify-between gap-3 text-xs shadow-2xs">
          <div className="flex items-center gap-2 overflow-x-auto">
            <span className="text-stone-500 font-bold mr-1">Trạng thái:</span>
            {[
              { key: "ALL", label: "Tất cả" },
              { key: "ONLINE_IDLE", label: "Đang rảnh" },
              { key: "ONLINE_BUSY", label: "Đang bận" },
              { key: "OFFLINE", label: "Tạm nghỉ" },
            ].map((st) => (
              <button
                key={st.key}
                type="button"
                onClick={() => setFilter(st.key as FilterStatus)}
                className={`px-3.5 py-2 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                  filter === st.key
                    ? "bg-amber-500 text-stone-900 shadow-sm"
                    : "bg-stone-100 text-stone-700 hover:bg-stone-200"
                }`}
              >
                {st.label}
              </button>
            ))}
          </div>

          <div className="flex items-center gap-2">
            <span className="text-stone-500 font-bold">Khu vực:</span>
            <select
              value={searchDistrict}
              onChange={(e) => setSearchDistrict(e.target.value)}
              className="h-10 px-3.5 rounded-xl border border-stone-300 bg-white text-stone-800 text-xs font-semibold outline-none focus:border-amber-500"
            >
              <option value="ALL">Tất cả quận</option>
              <option value="Quận 1">Quận 1</option>
              <option value="Quận 3">Quận 3</option>
              <option value="Quận 5">Quận 5</option>
              <option value="Quận 7">Quận 7</option>
            </select>
          </div>
        </div>

        {/* BẢNG DANH SÁCH TÀI XẾ */}
        <section className="bg-white border border-stone-200 rounded-2xl overflow-hidden shadow-2xs">
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs sm:text-sm">
              <thead className="bg-stone-50 text-stone-600 font-bold uppercase text-xs tracking-wider border-b border-stone-200">
                <tr>
                  <th className="p-4 pl-6 whitespace-nowrap">Tài xế & Mã</th>
                  <th className="p-4 whitespace-nowrap">Phương tiện</th>
                  <th className="p-4 whitespace-nowrap">Khu vực tác chiến</th>
                  <th className="p-4 whitespace-nowrap">Trạng thái</th>
                  <th className="p-4 whitespace-nowrap">Cuốc hoàn tất</th>
                  <th className="p-4 whitespace-nowrap">Đánh giá</th>
                  <th className="p-4 pr-6 text-right whitespace-nowrap">Thao tác</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-stone-200">
                {filteredDrivers.map((d) => {
                  const isIdle = d.status === "ONLINE_IDLE";
                  const isBusy = d.status === "ONLINE_BUSY";

                  return (
                    <tr key={d.id} className="hover:bg-stone-50/70 transition-colors">
                      <td className="p-4 pl-6">
                        <div className="font-extrabold text-stone-900 text-sm sm:text-base">{d.name}</div>
                        <div className="text-xs text-stone-500 font-medium">{d.id} · {d.phone}</div>
                      </td>
                      <td className="p-4">
                        <div className="font-bold text-amber-700">{d.vehiclePlate}</div>
                        <div className="text-xs text-stone-500 font-medium">{d.vehicleType}</div>
                      </td>
                      <td className="p-4 font-semibold text-stone-800">
                        {d.currentDistrict}
                      </td>
                      <td className="p-4">
                        <span
                          className={`inline-block px-2.5 py-0.5 rounded-full text-xs font-bold border ${
                            isIdle
                              ? "bg-emerald-50 text-emerald-800 border-emerald-200"
                              : isBusy
                              ? "bg-amber-50 text-amber-800 border-amber-200"
                              : "bg-stone-100 text-stone-600 border-stone-200"
                          }`}
                        >
                          {isIdle ? "Rảnh" : isBusy ? "Đang giao" : "Tạm nghỉ"}
                        </span>
                      </td>
                      <td className="p-4 font-semibold text-stone-800">
                        —
                      </td>
                      <td className="p-4">
                        <span className="font-bold text-amber-700">—</span>
                        <div className="text-xs text-stone-500 font-medium">Chưa có thống kê</div>
                      </td>
                      <td className="p-4 pr-6 text-right space-x-2">
                        <a
                          href={`tel:${d.phone}`}
                          className="px-3 py-1.5 rounded-xl bg-stone-100 hover:bg-stone-200 text-stone-800 text-xs font-bold inline-block transition-colors"
                        >
                          Gọi
                        </a>
                        <button
                          type="button"
                          onClick={() => handlePingDriver(d)}
                          className="px-3 py-1.5 rounded-xl bg-amber-50 hover:bg-amber-100 text-amber-900 text-xs font-bold border border-amber-200 transition-colors cursor-pointer"
                        >
                          Sửa vị trí demo
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      </main>
      {editing && <div className="fixed inset-0 z-50 flex items-center justify-center bg-stone-900/40 p-4">
        <div className="w-full max-w-lg space-y-4 rounded-2xl bg-white p-6 text-stone-800">
          <h2 className="text-lg font-extrabold">Vị trí demo cố định · {editing.name}</h2>
          <p className="text-xs text-stone-500">Địa chỉ được Goong geocode trước khi lưu vào DB; không phải GPS trực tiếp.</p>
          {error && <p role="alert" className="text-sm text-rose-700">{error}</p>}
          <input value={address} onChange={event => setAddress(event.target.value)} maxLength={255} className="w-full rounded-xl border px-3 py-2" />
          <div className="flex justify-end gap-2"><button onClick={() => setEditing(null)} className="rounded-xl border px-4 py-2">Hủy</button>
            <button onClick={() => void savePosition()} disabled={saving || !address.trim()} className="rounded-xl bg-amber-500 px-4 py-2 font-bold disabled:opacity-50">Lưu</button></div>
        </div>
      </div>}
    </div>
  );
}
