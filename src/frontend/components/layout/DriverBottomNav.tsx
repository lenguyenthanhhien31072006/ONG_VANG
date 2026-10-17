"use client";

import React from "react";
import Link from "next/link";
import { usePathname } from "next/navigation";

export function DriverBottomNav() {
  const pathname = usePathname();

  const tabs = [
    {
      href: "/driver",
      label: "Nhận đơn",
      icon: (
        <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M13 10V3L4 14h7v7l9-11h-7z" />
        </svg>
      ),
    },
    {
      href: "/driver/history",
      label: "Lịch sử",
      icon: (
        <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2" />
        </svg>
      ),
    },
    {
      href: "/driver/wallet",
      label: "Thu nhập",
      icon: (
        <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M3 10h18M7 15h1m4 0h1m-7 4h12a3 3 0 003-3V8a3 3 0 00-3-3H6a3 3 0 00-3 3v8a3 3 0 003 3z" />
        </svg>
      ),
    },
    {
      href: "/driver/account",
      label: "Tài khoản",
      icon: (
        <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M16 7a4 4 0 11-8 0 4 4 0 018 0zM12 14a7 7 0 00-7 7h14a7 7 0 00-7-7z" />
        </svg>
      ),
    },
  ];

  return (
    <nav
      aria-label="Thanh điều hướng tài xế"
      className="fixed bottom-0 left-0 right-0 z-40 bg-white border-t border-stone-200 shadow-lg safe-area-bottom md:hidden"
    >
      <div className="grid grid-cols-4 h-16 max-w-md mx-auto">
        {tabs.map((tab) => {
          const isActive = pathname === tab.href;
          return (
            <Link
              key={tab.href}
              href={tab.href}
              className={`flex flex-col items-center justify-center gap-1 transition-colors ${
                isActive
                  ? "text-amber-600 font-bold"
                  : "text-stone-400 hover:text-stone-700 font-medium"
              }`}
            >
              <div className={isActive ? "scale-110 transition-transform" : ""}>
                {tab.icon}
              </div>
              <span className="text-[11px] leading-tight">{tab.label}</span>
            </Link>
          );
        })}
      </div>
    </nav>
  );
}
