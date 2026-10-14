"use client";
import { useParams } from "next/navigation";
import { DispatchOrderDetail } from "@/components/dispatch/DispatchOrderDetail";

export default function DispatcherOrderDetail() {
  const { id } = useParams<{ id: string }>();
  return <DispatchOrderDetail key={id} id={id} dispatcher />;
}
