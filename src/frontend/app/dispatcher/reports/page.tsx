"use client";

import React, { useCallback, useEffect, useState } from "react";
import Link from "next/link";
import { DispatcherNavbar } from "@/components/layout/DispatcherNavbar";
import { Button } from "@/components/ui/Button";
import { revenue, type RevenueReport } from "@/lib/finance";
import { formatVND } from "@/lib/utils";
import { useToast } from "@/components/ui/Toast";

export default function DispatcherReportsPage() {
  const { showToast } = useToast();
  const [report, setReport] = useState<RevenueReport | null>(null);
  const [error, setError] = useState("");
  const [dateRange, setDateRange] = useState<"TODAY" | "WEEK" | "MONTH">("TODAY");
  const refresh = useCallback(async () => {
    try {
      const current = await revenue(dateRange);
      setReport(current); setError("");
    } catch (cause) { setError((cause as Error).message); }
  }, [dateRange]);
  useEffect(() => {
    const initial = setTimeout(() => { void refresh(); }, 0);
    const timer = setInterval(() => { void refresh(); }, 15000);
    return () => { clearTimeout(initial); clearInterval(timer); };
  }, [refresh]);
  const metrics = report?.tong;
  const completedCount = metrics?.soDonHoanTat ?? 0;
  const exceptionCount = metrics?.soDonHuy ?? 0;
  const observed = completedCount + exceptionCount;

  const handleExportCsv = () => {
    const lines = [["Bắt đầu", "Kết thúc", "Cước hoàn tất", "Đã thu", "Còn phải thu", "Công ty 20%", "Tài xế 80%"],
      ...(report?.items ?? []).map((row) => [row.batDau, row.ketThuc, row.chiTieu.giaTriDonHoanTat,
        row.chiTieu.tienThucThu, row.chiTieu.cuocConPhaiThuCuoiKy,
        row.chiTieu.phanCongTy, row.chiTieu.phanTaiXe])];
    const csv = "\uFEFF" + lines.map((line) => line.map((cell) => '"' + cell.replaceAll('"', '""') + '"').join(",")).join("\r\n");
    const url = URL.createObjectURL(new Blob([csv], { type: "text/csv;charset=utf-8" }));
    const link = document.createElement("a");
    link.href = url;
    link.download = "bao-cao-dieu-phoi.csv";
    link.click();
    URL.revokeObjectURL(url);
    showToast("Đã tải báo cáo CSV theo kỳ đang xem.", "success");
  };

  return (
    <div className="min-h-screen flex flex-col bg-[#FAFAF9] text-[#1C1917] font-sans">
      <DispatcherNavbar />

      <main className="flex-1 max-w-[1440px] w-full mx-auto p-4 sm:p-6 lg:p-8 space-y-6">
        {/* Header */}
        <div className="flex flex-wrap items-center justify-between gap-4 pb-4 border-b border-stone-200">
          <div>
            <div className="inline-flex items-center gap-2 px-2.5 py-0.5 rounded-full bg-amber-100 text-amber-900 text-xs font-bold mb-1.5 whitespace-nowrap">
              ĐỐI SOÁT VẬN HÀNH
            </div>
            <h1 className="text-xl sm:text-2xl font-extrabold text-stone-900 tracking-tight">
              Báo Cáo Ca Trực
            </h1>
            <p className="text-xs sm:text-sm text-stone-500 mt-1">
              Tổng hợp chỉ số hoàn tất đơn hàng, cước và phần chia 20/80 theo kỳ
            </p>
          </div>

          <div className="flex items-center gap-3 shrink-0">
            <Button
              variant="outline"
              size="sm"
              onClick={handleExportCsv}
              className="text-xs font-bold whitespace-nowrap"
            >
              Tải báo cáo CSV
            </Button>
            <Link
              href="/dispatcher"
              className="text-xs sm:text-sm font-semibold text-stone-500 hover:text-amber-800 transition-colors whitespace-nowrap shrink-0"
            >
              ← Bàn điều phối
            </Link>
          </div>
        </div>

        {error && <p role="alert" className="rounded-xl border border-rose-200 bg-rose-50 p-3 text-sm text-rose-800">{error}</p>}

        {/* BỘ LỌC KỲ BÁO CÁO */}
        <div className="flex items-center gap-2 text-xs">
          <span className="text-stone-500 font-bold">Kỳ báo cáo:</span>
          {[
            { key: "TODAY", label: "Ca hôm nay" },
            { key: "WEEK", label: "7 ngày qua" },
            { key: "MONTH", label: "Tháng này" },
          ].map((item) => (
            <button
              key={item.key}
              type="button"
              onClick={() => setDateRange(item.key as "TODAY" | "WEEK" | "MONTH")}
              className={`px-3.5 py-2 rounded-xl text-xs font-bold transition-all cursor-pointer ${
                dateRange === item.key
                  ? "bg-amber-500 text-stone-900 shadow-sm"
                  : "bg-white border border-stone-200 text-stone-600 hover:bg-stone-100"
              }`}
            >
              {item.label}
            </button>
          ))}
        </div>

        {/* SHIFT TELEMETRY STRIP */}
        <section className="grid grid-cols-1 lg:grid-cols-3 divide-y lg:divide-y-0 lg:divide-x divide-stone-200 border border-stone-200 bg-white rounded-2xl overflow-hidden shadow-2xs">
          <div className="p-5 space-y-1">
            <span className="text-xs font-bold uppercase tracking-wider text-stone-400 block">
              Tổng cước snapshot
            </span>
            <div className="text-2xl sm:text-3xl font-extrabold text-amber-700">
              {formatVND(Number(metrics?.giaTriDonHoanTat ?? 0))}
            </div>
            <span className="text-xs text-emerald-700 font-medium block">
              Theo thời điểm hoàn tất trong kỳ
            </span>
          </div>

          <div className="p-5 space-y-1">
            <span className="text-xs font-bold uppercase tracking-wider text-emerald-700 block">
              Tỷ lệ hoàn tất đơn
            </span>
            <div className="text-2xl sm:text-3xl font-extrabold text-emerald-600">
              {observed ? (completedCount / observed * 100).toFixed(1) : "0.0"}%
            </div>
            <span className="text-xs text-stone-500 font-medium block">
              {completedCount} đơn hoàn tất
            </span>
          </div>

          <div className="p-5 space-y-1">
            <span className="text-xs font-bold uppercase tracking-wider text-rose-700 block">
              Tỷ lệ sự cố / Hủy đơn
            </span>
            <div className="text-2xl sm:text-3xl font-extrabold text-rose-600">
              {observed ? (exceptionCount / observed * 100).toFixed(1) : "0.0"}%
            </div>
            <span className="text-xs text-stone-500 font-medium block">
              {exceptionCount} đơn hủy trong kỳ
            </span>
          </div>


        </section>

        <section className="grid grid-cols-2 lg:grid-cols-4 gap-3">
          {[["Cước đã thu", metrics?.tienThucThu], ["Còn phải thu", metrics?.cuocConPhaiThuCuoiKy],
            ["Công ty 20%", metrics?.phanCongTy], ["Tài xế 80% phát sinh", metrics?.phanTaiXe]].map(([label, value]) =>
            <div key={label} className="rounded-2xl border border-stone-200 bg-white p-4"><span className="text-xs text-stone-500">{label}</span><strong className="mt-2 block text-lg">{formatVND(Number(value ?? 0))}</strong></div>)}
        </section>

      </main>
    </div>
  );
}
