export type OrderStatus =
  | "CHO_GAN"
  | "DA_GAN"
  | "DA_LAY_HANG"
  | "DANG_GIAO"
  | "HOAN_TAT"
  | "DA_HUY"
  | "CHUA_THANH_TOAN";

export interface AddressInfo {
  name: string;
  phone: string;
  street: string;
  district: string;
  city: string;
  notes?: string;
}

export interface OrderItem {
  id: string;
  sender: AddressInfo;
  receiver: AddressInfo;
  packageType: string;
  packageWeightKg?: number;
  distanceKm: number;
  baseFee: number;
  surchargeFee: number;
  vipDiscountFee: number;
  totalFee: number;
  status: OrderStatus;
  statusText: string;
  createdAt: string;
  createdTimestamp: number;
  paid: boolean;
  paymentStatus?: string;
  rating?: { stars: number; tags: string[]; comment: string };
  paymentMethod?: "TIEN_MAT" | "VNPAY";
  driver?: {
    id: string;
    name: string;
    phone: string;
    vehiclePlate: string;
    vehicleType: string;
  };
  incident?: {
    type: string;
    note: string;
    reportedAt: string;
  };
}

export interface QuoteBreakdown {
  distanceKm: number;
  baseFee: number;
  surchargeFee: number;
  vipDiscountFee: number;
  totalFee: number;
  estimatedMinutes: number;
}

export type DriverStatus = "ONLINE_IDLE" | "ONLINE_BUSY" | "OFFLINE";

export interface DriverItem {
  id: string;
  name: string;
  phone: string;
  vehiclePlate: string;
  vehicleType: string;
  currentDistrict: string;
  status: DriverStatus;
  idleSinceMinutes: number;
  rating: number;
  completedOrdersToday: number;
  walletBalance?: number;
  acceptanceRate: number;
}

export interface IncidentItem {
  id: string;
  orderId: string;
  driverId: string;
  driverName: string;
  driverPhone: string;
  type: string;
  note: string;
  reportedAt: string;
  status: "CHO_XU_LY" | "DANG_XU_LY" | "DA_GIAI_QUYET";
}
