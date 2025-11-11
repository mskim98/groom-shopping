'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { orderApi, getAccessToken } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Package } from 'lucide-react';

interface Order {
  id: string;
  orderDate: string;
  totalAmount: number;
  status: string;
  items: Array<{
    productName: string;
    quantity: number;
    price: number;
  }>;
}

export default function MyOrdersPage() {
  const router = useRouter();
  const [orders, setOrders] = useState<Order[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!getAccessToken()) {
      router.push('/login');
      return;
    }
    loadOrders();
  }, [router]);

  const loadOrders = async () => {
    try {
      const data = await orderApi.getOrders();
      setOrders(data);
    } catch (error) {
      console.error('Failed to load orders:', error);
    } finally {
      setLoading(false);
    }
  };

  const getStatusBadge = (status: string) => {
    const variants: Record<string, any> = {
      PENDING: 'outline',
      CONFIRMED: 'default',
      SHIPPING: 'default',
      DELIVERED: 'secondary',
      CANCELLED: 'destructive',
    };

    const labels: Record<string, string> = {
      PENDING: '대기중',
      CONFIRMED: '확인됨',
      SHIPPING: '배송중',
      DELIVERED: '배송완료',
      CANCELLED: '취소됨',
    };

    return <Badge variant={variants[status] || 'default'}>{labels[status] || status}</Badge>;
  };

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <Skeleton className="h-8 w-32 mb-6" />
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-32 w-full" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <h2 className="mb-6">주문 내역</h2>

      <div className="space-y-4">
        {orders.map((order) => (
          <Card key={order.id}>
            <CardContent className="p-6">
              <div className="flex justify-between items-start mb-4">
                <div>
                  <p className="text-muted-foreground">주문번호: {order.id}</p>
                  <p className="text-muted-foreground">
                    주문일: {new Date(order.orderDate).toLocaleDateString()}
                  </p>
                </div>
                {getStatusBadge(order.status)}
              </div>

              <div className="space-y-2 mb-4">
                {order.items.map((item, index) => (
                  <div key={index} className="flex justify-between">
                    <span>
                      {item.productName} x {item.quantity}
                    </span>
                    <span>{(item.price * item.quantity).toLocaleString()}원</span>
                  </div>
                ))}
              </div>

              <div className="flex justify-between items-center pt-4 border-t">
                <span>총 결제 금액</span>
                <span className="text-primary">{order.totalAmount.toLocaleString()}원</span>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {orders.length === 0 && (
        <Card>
          <CardContent className="p-12 text-center">
            <Package className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
            <p className="text-muted-foreground mb-4">주문 내역이 없습니다.</p>
            <Link href="/products" className="text-primary hover:underline">
              상품 둘러보기
            </Link>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
