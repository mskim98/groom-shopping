'use client';

import { useEffect, useState } from 'react';
import { useRouter, useParams } from 'next/navigation';
import { orderApi, getAccessToken } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ArrowLeft } from 'lucide-react';

interface OrderItem {
  productId: string;
  productName: string;
  price: number;
  quantity: number;
}

interface OrderDetail {
  orderId: string;
  userId: number;
  subTotal: number;
  discountAmount: number;
  totalAmount: number;
  status: string;
  couponId: number | null;
  createdAt: string;
  orderItems: OrderItem[];
}

export default function OrderDetailPage() {
  const router = useRouter();
  const params = useParams();
  const orderId = params?.orderId as string;

  const [order, setOrder] = useState<OrderDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!getAccessToken()) {
      router.push('/login');
      return;
    }

    if (!orderId) {
      setError('주문번호가 없습니다.');
      setLoading(false);
      return;
    }

    loadOrderDetail();
  }, [orderId, router]);

  const loadOrderDetail = async () => {
    try {
      const data = await orderApi.getOrder(orderId);
      console.log('Order detail loaded:', data);
      setOrder(data);
      setError(null);
    } catch (err) {
      console.error('Failed to load order detail:', err);
      setError(err instanceof Error ? err.message : '주문을 불러오는데 실패했습니다.');
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
      <div className="container mx-auto px-4 py-8 max-w-3xl">
        <Skeleton className="h-8 w-32 mb-6" />
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-32 w-full" />
          ))}
        </div>
      </div>
    );
  }

  if (error || !order) {
    return (
      <div className="container mx-auto px-4 py-8 max-w-3xl">
        <Card>
          <CardContent className="p-12 text-center">
            <p className="text-destructive mb-4">{error || '주문을 찾을 수 없습니다.'}</p>
            <Button onClick={() => router.back()}>뒤로가기</Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8 max-w-3xl">
      <div className="flex items-center gap-4 mb-6">
        <Button
          variant="ghost"
          size="sm"
          onClick={() => router.back()}
          className="p-0 h-auto"
        >
          <ArrowLeft className="w-5 h-5" />
        </Button>
        <h2 className="text-2xl font-bold">주문 상세</h2>
      </div>

      {/* 주문 정보 */}
      <Card className="mb-6">
        <CardContent className="p-6">
          <div className="flex justify-between items-start mb-4">
            <div>
              <h3 className="font-semibold mb-2">주문 정보</h3>
              <p className="text-sm text-muted-foreground">
                주문번호: <span className="font-mono">{order.orderId}</span>
              </p>
              <p className="text-sm text-muted-foreground">
                주문일시: {new Date(order.createdAt).toLocaleString()}
              </p>
            </div>
            <div className="text-right">
              {getStatusBadge(order.status)}
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 주문 상품 */}
      <Card className="mb-6">
        <CardContent className="p-6">
          <h3 className="font-semibold mb-4">주문 상품</h3>
          <div className="space-y-3">
            {order.orderItems.map((item) => (
              <div key={item.productId} className="flex justify-between items-start py-3 border-b last:border-b-0">
                <div className="flex-1">
                  <p className="font-medium">{item.productName}</p>
                  <p className="text-sm text-muted-foreground">수량: {item.quantity}개</p>
                  <p className="text-sm text-muted-foreground">
                    단가: {item.price.toLocaleString()}원
                  </p>
                </div>
                <p className="font-semibold text-right">
                  {(item.price * item.quantity).toLocaleString()}원
                </p>
              </div>
            ))}
          </div>
        </CardContent>
      </Card>

      {/* 결제 정보 */}
      <Card className="mb-6">
        <CardContent className="p-6">
          <h3 className="font-semibold mb-4">결제 정보</h3>
          <div className="space-y-2 text-sm">
            <div className="flex justify-between">
              <span className="text-muted-foreground">소계</span>
              <span>{order.subTotal.toLocaleString()}원</span>
            </div>
            {order.discountAmount > 0 && (
              <div className="flex justify-between text-destructive">
                <span>할인</span>
                <span>-{order.discountAmount.toLocaleString()}원</span>
              </div>
            )}
            {order.couponId && (
              <div className="flex justify-between text-amber-600">
                <span>쿠폰</span>
                <span>적용됨</span>
              </div>
            )}
            <div className="flex justify-between pt-2 border-t text-lg font-bold">
              <span>합계</span>
              <span className="text-primary">{order.totalAmount.toLocaleString()}원</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* 액션 버튼 */}
      <div className="flex gap-2">
        <Button
          variant="outline"
          className="flex-1"
          onClick={() => router.push('/my/orders')}
        >
          주문 내역 보기
        </Button>
        <Button
          className="flex-1"
          onClick={() => router.push('/products')}
        >
          계속 쇼핑하기
        </Button>
      </div>
    </div>
  );
}
