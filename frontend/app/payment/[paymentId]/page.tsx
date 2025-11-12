'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { paymentApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { ArrowLeft, CreditCard } from 'lucide-react';

interface PaymentDetail {
  id: string;
  orderId: string;
  amount: number;
  status: string;
  paymentMethod: string;
  paymentDate: string;
  items?: Array<{
    productName: string;
    quantity: number;
    price: number;
  }>;
}

export default function PaymentDetailPage() {
  const params = useParams();
  const router = useRouter();
  const [payment, setPayment] = useState<PaymentDetail | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!getAccessToken()) {
      router.push('/login');
      return;
    }
    loadPayment();
  }, [params.paymentId, router]);

  const loadPayment = async () => {
    try {
      const data = await paymentApi.getPayment(params.paymentId as string);
      setPayment(data);
    } catch (error) {
      console.error('Failed to load payment:', error);
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
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  if (!payment) {
    return (
      <div className="container mx-auto px-4 py-8">
        <p>결제 정보를 찾을 수 없습니다.</p>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8 max-w-3xl">
      <Button variant="ghost" onClick={() => router.back()} className="mb-6">
        <ArrowLeft className="w-4 h-4 mr-2" />
        뒤로가기
      </Button>

      <Card>
        <CardHeader>
          <div className="flex justify-between items-start">
            <div className="flex items-center gap-3">
              <CreditCard className="w-8 h-8 text-primary" />
              <CardTitle>결제 상세</CardTitle>
            </div>
            {getStatusBadge(payment.status)}
          </div>
        </CardHeader>
        <CardContent className="space-y-6">
          <div className="grid md:grid-cols-2 gap-6">
            <div>
              <p className="text-muted-foreground mb-1">결제번호</p>
              <p>{payment.id}</p>
            </div>
            <div>
              <p className="text-muted-foreground mb-1">주문번호</p>
              <p>{payment.orderId}</p>
            </div>
            <div>
              <p className="text-muted-foreground mb-1">결제일시</p>
              <p>{new Date(payment.paymentDate).toLocaleString()}</p>
            </div>
            <div>
              <p className="text-muted-foreground mb-1">결제수단</p>
              <p>{payment.paymentMethod || 'Toss Payments'}</p>
            </div>
          </div>

          {payment.items && payment.items.length > 0 && (
            <div className="pt-4 border-t">
              <h3 className="mb-4">주문 상품</h3>
              <div className="space-y-3">
                {payment.items.map((item, index) => (
                  <div key={index} className="flex justify-between py-2 border-b">
                    <div>
                      <p>{item.productName}</p>
                      <p className="text-muted-foreground">{item.quantity}개</p>
                    </div>
                    <p>{(item.price * item.quantity).toLocaleString()}원</p>
                  </div>
                ))}
              </div>
            </div>
          )}

          <div className="flex justify-between items-center pt-4 border-t">
            <span>결제 금액</span>
            <span className="text-primary text-xl">{payment.amount.toLocaleString()}원</span>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
