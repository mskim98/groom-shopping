'use client';

import { Suspense, useEffect, useRef, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { CreditCard, AlertCircle } from 'lucide-react';
import { toast } from 'sonner';
import type { loadTossPayments } from '@tosspayments/tosspayments-sdk';

interface OrderInfo {
  orderId: string;
  orderName: string;
  amount: number;
  customerName: string;
  customerEmail: string;
}

function PaymentContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const orderId = searchParams.get('orderId');
  const [loading, setLoading] = useState(true);
  const [orderInfo, setOrderInfo] = useState<OrderInfo | null>(null);
  const [processing, setProcessing] = useState(false);
  const widgetsRef = useRef<any>(null);
  const clientKey = process.env.NEXT_PUBLIC_TOSS_CLIENT_KEY;

  // 1단계: 주문 정보 조회
  useEffect(() => {
    const initPayment = async () => {
      try {
        // 로그인 확인
        const accessToken = getAccessToken();
        if (!accessToken) {
          toast.error('로그인이 필요합니다.');
          router.push('/login');
          return;
        }

        // orderId 없으면 cart로 이동
        if (!orderId) {
          router.push('/cart');
          return;
        }

        // 주문 정보 조회
        const response = await fetch(`/api/v1/order/${orderId}`, {
          headers: {
            'Authorization': `Bearer ${accessToken}`,
          },
        });

        if (!response.ok) {
          throw new Error('주문 정보를 조회할 수 없습니다.');
        }

        const order = await response.json();
        // 주문 정보에서 customerName과 customerEmail은 가져올 수 없으므로 기본값 사용
        setOrderInfo({
          orderId: order.orderId,
          orderName: order.orderItems?.length > 0
            ? (order.orderItems[0].productName + (order.orderItems.length > 1 ? ` 외 ${order.orderItems.length - 1}건` : ''))
            : '상품',
          amount: order.totalAmount,
          customerName: '고객',
          customerEmail: '',
        });

        // 로딩 완료 - DOM이 렌더링될 시간을 제공
        setLoading(false);
      } catch (error) {
        console.error('결제 초기화 실패:', error);
        toast.error('결제 준비 중 오류가 발생했습니다.');
        setLoading(false);
      }
    };

    initPayment();
  }, [orderId, router]);

  // 2단계: orderInfo와 loading이 준비되면 토스 위젯 초기화
  useEffect(() => {
    if (orderInfo && !loading) {
      // DOM이 완전히 렌더링된 후에 토스 위젯 초기화
      const timer = requestAnimationFrame(() => {
        initTossPayments();
      });

      return () => cancelAnimationFrame(timer);
    }
  }, [orderInfo, loading]);

  const initTossPayments = async () => {
    if (!orderInfo) return;

    try {
      // SDK 동적 로드
      const { loadTossPayments } = await import('@tosspayments/tosspayments-sdk');

      const tossPayments = await loadTossPayments(clientKey!);
      const widgets = tossPayments.widgets({
        customerKey: localStorage.getItem('userId') || 'guest',
      });

      // 금액 설정
      await widgets.setAmount({
        currency: 'KRW',
        value: orderInfo.amount,
      });

      // 결제 UI 렌더링
      await widgets.renderPaymentMethods({
        selector: '#payment-method',
        variantKey: 'DEFAULT',
      });

      // 이용약관 UI 렌더링
      await widgets.renderAgreement({
        selector: '#agreement',
        variantKey: 'AGREEMENT',
      });

      widgetsRef.current = widgets;
      console.log('[TOSS_WIDGET_INITIALIZED] Toss payment widget initialized successfully');
    } catch (error) {
      console.error('토스 페이먼츠 초기화 실패:', error);
      toast.error('결제 위젯을 로드할 수 없습니다.');
    }
  };

  const handlePayment = async () => {
    if (!widgetsRef.current || !orderInfo) {
      toast.error('결제 준비가 완료되지 않았습니다.');
      return;
    }

    setProcessing(true);

    try {
      // 결제 요청
      await widgetsRef.current.requestPayment({
        orderId: orderInfo.orderId,
        orderName: orderInfo.orderName,
        customerEmail: orderInfo.customerEmail,
        customerName: orderInfo.customerName,
        successUrl: `${window.location.origin}/payment/success`,
        failUrl: `${window.location.origin}/payment/fail`,
      });
    } catch (error: any) {
      console.error('결제 요청 실패:', error);
      // 사용자가 결제를 취소한 경우
      if (error.code !== 'USER_CANCEL') {
        toast.error(error.message || '결제 요청에 실패했습니다.');
      }
    } finally {
      setProcessing(false);
    }
  };

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-12 max-w-2xl">
        <div className="text-center">
          <div className="inline-block h-8 w-8 animate-spin rounded-full border-4 border-solid border-current border-r-transparent"></div>
          <p className="mt-4 text-muted-foreground">결제 준비 중...</p>
        </div>
      </div>
    );
  }

  if (!orderInfo) {
    return (
      <div className="container mx-auto px-4 py-12 max-w-2xl">
        <Card className="border-red-200 bg-red-50">
          <CardContent className="p-6">
            <div className="flex items-start gap-4">
              <AlertCircle className="h-6 w-6 text-red-600 flex-shrink-0 mt-0.5" />
              <div>
                <h3 className="font-semibold text-red-900 mb-2">주문 정보를 불러올 수 없습니다</h3>
                <p className="text-red-800 mb-4">
                  주문 정보 조회 중 오류가 발생했습니다. 다시 시도해주세요.
                </p>
                <Button
                  variant="outline"
                  onClick={() => router.push('/cart')}
                >
                  장바구니로 돌아가기
                </Button>
              </div>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-12 max-w-2xl">
      <Card>
        <CardContent className="p-6">
          <div className="flex items-center gap-2 mb-6">
            <CreditCard className="h-6 w-6" />
            <h1 className="text-2xl font-bold">결제하기</h1>
          </div>

          {/* 주문 정보 */}
          <div className="mb-8 p-4 bg-muted rounded-lg">
            <h3 className="font-semibold mb-4">주문 정보</h3>
            <div className="space-y-2">
              <div className="flex justify-between">
                <span className="text-muted-foreground">주문번호</span>
                <span className="font-medium">{orderInfo.orderId}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-muted-foreground">상품명</span>
                <span className="font-medium">{orderInfo.orderName}</span>
              </div>
              <div className="flex justify-between pt-2 border-t">
                <span className="text-muted-foreground">결제 금액</span>
                <span className="font-bold text-lg">
                  {orderInfo.amount.toLocaleString()}원
                </span>
              </div>
            </div>
          </div>

          {/* 결제 위젯 영역 */}
          <div className="mb-6">
            <div id="payment-method" className="mb-6"></div>
            <div id="agreement"></div>
          </div>

          {/* 결제 버튼 */}
          <div className="space-y-3">
            <Button
              className="w-full h-12 text-base font-semibold"
              onClick={handlePayment}
              disabled={processing}
              size="lg"
            >
              {processing ? '결제 처리 중...' : `${orderInfo.amount.toLocaleString()}원 결제하기`}
            </Button>

            <Button
              variant="outline"
              className="w-full"
              onClick={() => router.back()}
              disabled={processing}
            >
              돌아가기
            </Button>
          </div>

          {/* 안내 메시지 */}
          <div className="mt-6 p-4 border rounded-lg bg-blue-50 border-blue-200">
            <p className="text-sm text-blue-900">
              <strong>💡 팁:</strong> 테스트 카드로 결제를 시도할 수 있습니다.
              실제 결제는 진행되지 않습니다.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

export default function PaymentPage() {
  return (
    <Suspense fallback={
      <div className="container mx-auto px-4 py-12 text-center">
        <div className="inline-block h-8 w-8 animate-spin rounded-full border-4 border-solid border-current border-r-transparent"></div>
      </div>
    }>
      <PaymentContent />
    </Suspense>
  );
}
