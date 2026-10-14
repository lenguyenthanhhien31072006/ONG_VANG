import { DispatchOrderDetail } from "@/components/dispatch/DispatchOrderDetail";
export default async function DriverOrder({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  return <DispatchOrderDetail key={id} id={id} driver />;
}
