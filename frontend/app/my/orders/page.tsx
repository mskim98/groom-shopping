'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { paymentApi, getAccessToken } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Package } from 'lucide-react';

interface PaymentInfo {
  id: string;
  orderId: string;
  amount: number;
  status: string;
  paymentDate: string;
}

export default function MyOrdersPage() {
  const router = useRouter();
  const [orders, setOrders] = useState<PaymentInfo[]>([]);
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
      const payments = await paymentApi.getMyPayments();
      console.log('Payments loaded:', payments);
      setOrders(payments || []);
    } catch (error) {
      console.error('Failed to load payments:', error);
      setOrders([]);
    } finally {
      setLoading(false);
    }
  };

  const getStatusBadge = (status: string) => {
    const variants: Record<string, any> = {
      PENDING: 'outline',
      COMPLETED: 'default',
      FAILED: 'destructive',
      CANCELLED: 'secondary',
    };

    const labels: Record<string, string> = {
      PENDING: '대기중',
      COMPLETED: '완료',
      FAILED: '실패',
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
        {orders.map((payment) => (
          <Card key={payment.id}>
            <CardContent className="p-6">
              <div className="flex justify-between items-start mb-4">
                <div className="flex-1">
                  <h3 className="mb-2 font-semibold">주문번호: {payment.orderId}</h3>
                  <p className="text-sm text-muted-foreground">
                    결제일: {new Date(payment.paymentDate).toLocaleString()}
                  </p>
                </div>
                <div className="text-right">
                  {getStatusBadge(payment.status)}
                  <p className="mt-2 text-lg font-bold text-primary">
                    {payment.amount.toLocaleString()}원
                  </p>
                </div>
              </div>

              <div className="flex gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => router.push(`/order/${payment.orderId}`)}
                >
                  상세 보기
                </Button>
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
            <Button onClick={() => router.push('/products')}>
              상품 둘러보기
            </Button>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
