'use client';

import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { CheckCircle2, Loader2 } from 'lucide-react';
import { toast } from 'sonner';

interface PaymentResult {
  orderId: string;
  paymentKey: string;
  amount: number;
  status: string;
}

function SuccessContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const [loading, setLoading] = useState(true);
  const [paymentResult, setPaymentResult] = useState<PaymentResult | null>(null);

  useEffect(() => {
    const confirmPayment = async () => {
      try {
        const accessToken = getAccessToken();
        if (!accessToken) {
          toast.error('로그인이 필요합니다.');
          router.push('/login');
          return;
        }

        // 쿼리 파라미터에서 결제 정보 추출
        const paymentKey = searchParams.get('paymentKey');
        const orderId = searchParams.get('orderId');
        const amount = searchParams.get('amount');

        // 디버깅: 실제 받은 쿼리 파라미터 로깅
        console.log('[PAYMENT_SUCCESS] Query params:', {
          paymentKey,
          orderId,
          amount,
          allParams: Object.fromEntries(searchParams.entries()),
        });

        if (!paymentKey || !orderId || !amount) {
          throw new Error(`필수 결제 정보가 누락되었습니다. paymentKey: ${paymentKey}, orderId: ${orderId}, amount: ${amount}`);
        }

        // 백엔드에 결제 승인 요청
        const response = await fetch('/api/v1/payment/confirm', {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${accessToken}`,
          },
          body: JSON.stringify({
            paymentKey,
            orderId,
            amount: parseInt(amount),
          }),
        });

        if (!response.ok) {
          const error = await response.json();
          throw new Error(error.message || '결제 승인에 실패했습니다.');
        }

        const result = await response.json();
        setPaymentResult({
          orderId: result.orderId,
          paymentKey: result.paymentKey,
          amount: result.amount,
          status: result.status,
        });

        toast.success('결제가 완료되었습니다!');
      } catch (error) {
        console.error('결제 승인 실패:', error);
        toast.error(error instanceof Error ? error.message : '결제 승인 중 오류가 발생했습니다.');
        // 실패 시 3초 후 결제 페이지로 이동
        setTimeout(() => {
          router.push('/cart');
        }, 3000);
      } finally {
        setLoading(false);
      }
    };

    confirmPayment();
  }, [router, searchParams]);

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-12 max-w-2xl">
        <Card>
          <CardContent className="p-12 text-center">
            <Loader2 className="w-12 h-12 mx-auto mb-4 text-blue-600 animate-spin" />
            <h2 className="text-xl font-semibold mb-2">결제 처리 중</h2>
            <p className="text-muted-foreground">
              결제를 최종 승인하고 있습니다. 잠시만 기다려주세요...
            </p>
          </CardContent>
        </Card>
      </div>
    );
  }

  if (!paymentResult) {
    return (
      <div className="container mx-auto px-4 py-12 max-w-2xl">
        <Card className="border-red-200">
          <CardContent className="p-12 text-center">
            <CheckCircle2 className="w-12 h-12 mx-auto mb-4 text-red-600" />
            <h2 className="text-xl font-semibold mb-2 text-red-900">결제 실패</h2>
            <p className="text-muted-foreground mb-6">
              결제 승인 중 오류가 발생했습니다.
            </p>
            <Button
              className="w-full"
              onClick={() => router.push('/cart')}
            >
              장바구니로 돌아가기
            </Button>
          </CardContent>
        </Card>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-12 max-w-2xl">
      <Card>
        <CardContent className="p-12 text-center">
          <CheckCircle2 className="w-16 h-16 mx-auto mb-4 text-green-600" />
          <h1 className="text-3xl font-bold mb-2">결제 완료</h1>
          <p className="text-muted-foreground mb-8">
            주문이 정상적으로 완료되었습니다.
          </p>

          {/* 결제 상세 정보 */}
          <div className="mb-8 p-6 bg-muted rounded-lg text-left space-y-4">
            <div className="flex justify-between">
              <span className="text-muted-foreground">주문번호</span>
              <span className="font-mono font-semibold">{paymentResult.orderId}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted-foreground">결제 금액</span>
              <span className="font-semibold">
                {paymentResult.amount.toLocaleString()}원
              </span>
            </div>
            <div className="flex justify-between pt-4 border-t">
              <span className="text-muted-foreground">결제 상태</span>
              <span className="font-semibold text-green-600">
                {paymentResult.status === 'DONE' ? '완료' : paymentResult.status}
              </span>
            </div>
            <div className="flex justify-between">
              <span className="text-muted-foreground">결제 키</span>
              <span className="font-mono text-sm text-muted-foreground">
                {paymentResult.paymentKey.slice(0, 20)}...
              </span>
            </div>
          </div>

          {/* 액션 버튼 */}
          <div className="space-y-3">
            <Button
              className="w-full h-12 text-base font-semibold"
              onClick={() => router.push('/my/orders')}
              size="lg"
            >
              주문 내역 보기
            </Button>
            <Button
              variant="outline"
              className="w-full"
              onClick={() => router.push('/products')}
            >
              쇼핑 계속하기
            </Button>
          </div>

          {/* 추가 정보 */}
          <div className="mt-8 p-4 border rounded-lg bg-blue-50">
            <p className="text-sm text-blue-900">
              <strong>📧:</strong> 주문 확인 메일이 이메일로 발송될 예정입니다.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

export default function PaymentSuccessPage() {
  return (
    <Suspense fallback={
      <div className="container mx-auto px-4 py-12 text-center">
        <div className="inline-block h-8 w-8 animate-spin rounded-full border-4 border-solid border-current border-r-transparent"></div>
      </div>
    }>
      <SuccessContent />
    </Suspense>
  );
}
