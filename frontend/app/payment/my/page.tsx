'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { paymentApi, getAccessToken } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { CreditCard, Eye } from 'lucide-react';

interface Payment {
  id: string;
  orderId: string;
  amount: number;
  status: string;
  paymentMethod: string;
  paymentDate: string;
}

export default function MyPaymentsPage() {
  const router = useRouter();
  const [payments, setPayments] = useState<Payment[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!getAccessToken()) {
      router.push('/login');
      return;
    }
    loadPayments();
  }, [router]);

  const loadPayments = async () => {
    try {
      const data = await paymentApi.getMyPayments();
      setPayments(data);
    } catch (error) {
      console.error('Failed to load payments:', error);
    } finally {
      setLoading(false);
    }
  };

  const getStatusBadge = (status: string) => {
    const variants: Record<string, any> = {
      COMPLETED: 'default',
      PENDING: 'outline',
      FAILED: 'destructive',
      REFUNDED: 'secondary',
    };

    const labels: Record<string, string> = {
      COMPLETED: '완료',
      PENDING: '대기중',
      FAILED: '실패',
      REFUNDED: '환불',
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
      <h2 className="mb-6">결제 내역</h2>

      <div className="space-y-4">
        {payments.map((payment) => (
          <Card key={payment.id}>
            <CardContent className="p-6">
              <div className="flex justify-between items-start mb-4">
                <div>
                  <p className="text-muted-foreground">결제번호: {payment.id}</p>
                  <p className="text-muted-foreground">주문번호: {payment.orderId}</p>
                  <p className="text-muted-foreground">
                    결제일: {new Date(payment.paymentDate).toLocaleDateString()}
                  </p>
                  <p className="text-muted-foreground">
                    결제수단: {payment.paymentMethod || 'Toss Payments'}
                  </p>
                </div>
                {getStatusBadge(payment.status)}
              </div>

              <div className="flex justify-between items-center pt-4 border-t">
                <span>결제 금액</span>
                <span className="text-primary">{payment.amount.toLocaleString()}원</span>
              </div>

              <div className="mt-4">
                <Link href={`/payment/${payment.id}`}>
                  <Button variant="outline" size="sm">
                    <Eye className="w-4 h-4 mr-2" />
                    상세 보기
                  </Button>
                </Link>
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {payments.length === 0 && (
        <Card>
          <CardContent className="p-12 text-center">
            <CreditCard className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
            <p className="text-muted-foreground mb-4">결제 내역이 없습니다.</p>
            <Link href="/products" className="text-primary hover:underline">
              상품 둘러보기
            </Link>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
