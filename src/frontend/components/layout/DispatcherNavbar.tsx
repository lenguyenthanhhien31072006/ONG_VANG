"use client";

import React, { useState, useEffect } from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";
import { LogoutButton } from "@/components/auth/LogoutButton";

interface DispatcherNavbarProps {
  pendingCount?: number;
  incidentCount?: number;
}

export function DispatcherNavbar({ pendingCount, incidentCount }: DispatcherNavbarProps) {
  const pathname = usePathname();
  const [currentTime, setCurrentTime] = useState<string>("");

  useEffect(() => {
    const updateClock = () => {
      const now = new Date();
      setCurrentTime(
        now.toLocaleTimeString("vi-VN", {
          hour: "2-digit",
          minute: "2-digit",
          second: "2-digit",
          hour12: false,
        })
      );
    };
    updateClock();
    const interval = setInterval(updateClock, 1000);
    return () => clearInterval(interval);
  }, []);

  const live = pathname?.startsWith("/dispatcher/live");
  const navLinks = [
    { href: "/dispatcher", label: "Bàn điều phối" },
    { href: "/dispatcher/live", label: "Điều phối thật · TX4" },
    { href: live ? "/dispatcher/live/create-order" : "/dispatcher/create-order", label: "Hotline tạo đơn" },
    { href: live ? "/dispatcher/live/fleet" : "/dispatcher/fleet", label: "Đội xe & Tài xế" },
    { href: "/dispatcher/reports", label: "Báo cáo & Đối soát" },
  ];

  return (
    <header className="sticky top-0 z-40 w-full bg-white/95 backdrop-blur-md border-b border-stone-200 shadow-2xs font-sans">
      <div className="max-w-[1600px] mx-auto px-3 sm:px-5 lg:px-6 h-16 flex items-center justify-between gap-2 sm:gap-4">
        {/* Brand identity */}
        <div className="flex items-center gap-2.5 shrink-0">
          <Link href="/dispatcher" className="flex items-center gap-2 group">
            <div className="w-9 h-9 rounded-xl bg-amber-500 flex items-center justify-center text-stone-900 font-extrabold text-sm shadow-sm transition-transform duration-200 group-hover:scale-105 shrink-0">
              OV
            </div>
            <div className="flex items-center gap-2">
              <span className="font-extrabold text-base sm:text-lg tracking-tight text-stone-900 whitespace-nowrap">
                Mini Ong Vàng
              </span>
              <span className="hidden sm:inline-block text-[10px] uppercase font-bold tracking-wide px-2 py-0.5 rounded-full bg-amber-100 text-amber-900 border border-amber-200 whitespace-nowrap">
                Điều Phối
              </span>
            </div>
          </Link>

          {/* Real-time Clock (only on large screens to prevent wrapping) */}
          {currentTime && (
            <div className="hidden 2xl:flex items-center gap-1.5 pl-3 border-l border-stone-200 text-xs font-semibold text-stone-600 whitespace-nowrap">
              <span className="text-stone-900 font-bold">{currentTime}</span>
              <span className="text-stone-400 font-normal">Ca 01</span>
            </div>
          )}
        </div>

        {/* Navigation Tabs - Strict whitespace-nowrap and shrink-0 to prevent 2-line wrap */}
        <nav className="hidden md:flex items-center gap-1 shrink-0">
          {navLinks.map((link) => {
            const isActive = pathname === link.href;
            return (
              <Link
                key={link.href}
                href={link.href}
                className={`px-3 py-1.5 rounded-xl text-xs sm:text-sm font-semibold transition-all relative whitespace-nowrap shrink-0 ${
                  isActive
                    ? "text-stone-900 bg-amber-50 font-bold"
                    : "text-stone-600 hover:text-stone-900 hover:bg-stone-100/70"
                }`}
              >
                <span>{link.label}</span>
                {isActive && (
                  <span className="absolute bottom-0 left-3 right-3 h-[2px] bg-amber-500 rounded-full" />
                )}
              </Link>
            );
          })}
        </nav>

        {/* Status Counters & Role Switchers - All whitespace-nowrap */}
        <div className="flex items-center gap-2 sm:gap-2.5 shrink-0">
          {/* Pending Queue Counter */}
          <Link
            href="/dispatcher"
            className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl bg-amber-50 border border-amber-200 hover:bg-amber-100 text-xs transition-colors whitespace-nowrap shrink-0"
            title="Đơn hàng chờ gán tài xế"
          >
            <span className="text-amber-800 font-medium">Hàng đợi:</span>
            <span className="font-extrabold text-amber-900">{pendingCount ?? "—"}</span>
          </Link>

          {/* Active Incident Counter */}
          {incidentCount !== undefined && incidentCount > 0 && (
            <Link
              href="/dispatcher"
              className="flex items-center gap-1.5 px-2.5 py-1.5 rounded-xl bg-rose-50 border border-rose-200 text-rose-800 text-xs font-bold hover:bg-rose-100 transition-colors whitespace-nowrap shrink-0"
              title="Sự cố đang chờ điều phối can thiệp"
            >
              <span className="w-1.5 h-1.5 rounded-full bg-rose-500 shrink-0" />
              <span>Sự cố:</span>
              <span className="text-rose-900 font-extrabold">{incidentCount}</span>
            </Link>
          )}



          {/* Operator Profile */}
          <Link
            href="/dispatcher/account"
            aria-label="Mở hồ sơ điều phối viên"
            aria-current={pathname === "/dispatcher/account" ? "page" : undefined}
            className={`flex items-center gap-2 rounded-xl border px-1.5 py-1 shrink-0 transition-colors focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-amber-600 ${
              pathname === "/dispatcher/account" ? "border-amber-300 bg-amber-50" : "border-transparent hover:border-stone-200 hover:bg-stone-50"
            }`}
          >
            <span className="w-8 h-8 rounded-full bg-amber-500/20 border border-amber-300 flex items-center justify-center text-xs font-extrabold text-amber-900 shrink-0">
              ĐP
            </span>
            <span className="hidden lg:block text-xs font-bold text-stone-700 2xl:hidden">Tài khoản</span>
            <span className="hidden 2xl:flex flex-col text-left text-xs leading-none whitespace-nowrap">
              <span className="font-bold text-stone-900">Điều phối viên</span>
              <span className="text-[10px] text-stone-500 mt-0.5">Tài khoản điều phối</span>
            </span>
          </Link>
          <LogoutButton className="rounded-lg border border-stone-300 px-3 py-2 text-xs font-bold text-stone-700 hover:bg-stone-50" />
        </div>
      </div>

      {/* Mobile Sub-Navigation */}
      <div className="md:hidden flex items-center justify-around px-2 py-2 bg-stone-50 border-t border-stone-200 text-xs overflow-x-auto">
        {[...navLinks, { href: "/dispatcher/account", label: "Tài khoản" }].map((link) => {
          const isActive = pathname === link.href;
          return (
            <Link
              key={link.href}
              href={link.href}
              className={`px-2.5 py-1 rounded-lg font-semibold transition-colors whitespace-nowrap ${
                isActive ? "text-amber-900 bg-amber-100 font-bold" : "text-stone-600"
              }`}
            >
              {link.label}
            </Link>
          );
        })}
      </div>
    </header>
  );
}
