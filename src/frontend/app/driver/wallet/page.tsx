"use client";

import React, { useCallback, useEffect, useState } from "react";
import { DriverNavbar } from "@/components/layout/DriverNavbar";
import { DriverBottomNav } from "@/components/layout/DriverBottomNav";
import { formatVND } from "@/lib/utils";
import { api, type Driver, type Order } from "@/lib/live-orders";
import type { DriverEarnings } from "@/lib/finance";

export default function DriverWalletPage() {
  const [driver, setDriver] = useState<Driver | null>(null);
  const [earnings, setEarnings] = useState<DriverEarnings | null>(null);
  const [cashOrders, setCashOrders] = useState<Order[]>([]);
  const [error, setError] = useState("");
  const refresh = useCallback(async () => {
    try {
      const [self, money, orders] = await Promise.all([
        api<Driver>("/drivers/me"),
        api<DriverEarnings>("/drivers/me/earnings?tuNgay=2000-01-01&denNgay=2100-01-01"),
        api<{ items: Order[] }>("/orders?trangThai=HOAN_TAT&size=100"),
      ]);
      setDriver(self); setEarnings(money);
      setCashOrders(orders.items.filter((order) => order.phuongThucThanhToan === "TIEN_MAT" && order.thanhToan.soTienConPhaiThu === "0.00"));
      setError("");
    } catch (cause) { setError((cause as Error).message); }
  }, []);
  useEffect(() => { const timer = setTimeout(() => { void refresh(); }, 0); return () => clearTimeout(timer); }, [refresh]);
  const isOnline = driver?.trangThaiHoatDong === "ONLINE";
  const balance = Number(earnings?.phanTaiXePhatSinh ?? 0);

  return (
    <div className="min-h-screen flex flex-col bg-[#FAFAF9] text-[#1C1917] pb-24 md:pb-8">
      <DriverNavbar isOnline={isOnline} onToggleOnline={() => {}} />

      <main className="flex-1 max-w-[768px] w-full mx-auto px-4 sm:px-6 py-5 sm:py-8 space-y-5">
        {error && <p role="alert" className="rounded-xl bg-rose-50 p-3 text-sm text-rose-800">{error}</p>}
        {/* Header */}
        <div>
          <h1 className="text-xl sm:text-2xl font-extrabold text-stone-900 tracking-tight">
            Thu nhập tài xế
          </h1>
          <p className="text-xs text-stone-500 mt-0.5">
            Theo dõi phần cước tài xế được hưởng và lịch sử thu cước
          </p>
        </div>

        {/* THU NHẬP PHÁT SINH */}
        <div className="grid grid-cols-1 gap-4">
          {/* Phần cước tài xế được hưởng, không phải tiền đã chi trả */}
          <div className="bg-stone-900 text-white rounded-2xl p-5 shadow-sm space-y-3 relative overflow-hidden">
            <span className="text-[10px] font-bold uppercase tracking-wider text-amber-400 block">
              Thu nhập phát sinh
            </span>
            <div className="text-3xl font-black">
              {formatVND(balance)}
            </div>
            <p className="text-[11px] text-stone-400">
              Chưa ghi nhận chuyển khoản thực tế về ngân hàng tài xế.
            </p>
          </div>
        </div>

        {/* LỊCH SỬ BIẾN ĐỘNG SỐ DƯ */}
        <section className="bg-white border border-stone-200 rounded-2xl p-4 sm:p-5 shadow-2xs space-y-3">
          <h3 className="font-extrabold text-stone-900 text-sm uppercase tracking-wider">
            Đơn đã thu tiền mặt
          </h3>

          <div className="divide-y divide-stone-100">
            {cashOrders.length === 0 && <p className="py-6 text-center text-sm text-stone-500">Chưa có đơn đã thu tiền mặt.</p>}
            {cashOrders.map((order) => (
                <div key={order.maDon} className="py-3 flex items-center justify-between gap-3">
                  <div className="space-y-0.5">
                    <div className="font-bold text-xs sm:text-sm text-stone-900">
                      Thu tiền mặt đơn {order.maDon}
                    </div>
                    <div className="text-[11px] text-stone-400 font-mono">
                      {new Date(order.thoiGianTao).toLocaleString("vi-VN")}
                    </div>
                  </div>

                  <div className="text-right">
                    <span className="font-black text-sm sm:text-base text-emerald-600">{formatVND(Number(order.cuoc.tongCuoc))}</span>
                  </div>
                </div>
            ))}
          </div>
        </section>
      </main>

      <DriverBottomNav />
    </div>
  );
}
