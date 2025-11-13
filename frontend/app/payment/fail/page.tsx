'use client';

import { Suspense } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { AlertCircle } from 'lucide-react';

function FailContent() {
  const router = useRouter();
  const searchParams = useSearchParams();

  const code = searchParams.get('code');
  const message = searchParams.get('message');
  const orderId = searchParams.get('orderId');

  const getErrorMessage = (errorCode: string | null): string => {
    switch (errorCode) {
      case 'INSUFFICIENT_BALANCE':
        return '잔액이 부족합니다.';
      case 'CARD_DECLINED':
        return '카드가 거절되었습니다.';
      case 'INVALID_CARD_INFO':
        return '카드 정보가 올바르지 않습니다.';
      case 'PAYMENT_TIMEOUT':
        return '결제 시간이 초과되었습니다.';
      case 'USER_CANCEL':
        return '사용자가 결제를 취소했습니다.';
      default:
        return message || '결제 중 오류가 발생했습니다.';
    }
  };

  return (
    <div className="container mx-auto px-4 py-12 max-w-2xl">
      <Card className="border-red-200 bg-red-50">
        <CardContent className="p-12">
          <div className="text-center">
            <AlertCircle className="w-16 h-16 mx-auto mb-4 text-red-600" />
            <h1 className="text-3xl font-bold mb-2 text-red-900">결제 실패</h1>
            <p className="text-red-800 mb-8">
              {getErrorMessage(code)}
            </p>

            {/* 에러 상세 정보 */}
            {(code || orderId) && (
              <div className="mb-8 p-4 bg-white rounded-lg border border-red-200 text-left space-y-2">
                {code && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">에러 코드</span>
                    <span className="font-mono font-semibold text-red-600">{code}</span>
                  </div>
                )}
                {orderId && (
                  <div className="flex justify-between">
                    <span className="text-muted-foreground">주문번호</span>
                    <span className="font-mono font-semibold">{orderId}</span>
                  </div>
                )}
              </div>
            )}

            {/* 액션 버튼 */}
            <div className="space-y-3">
              <Button
                className="w-full h-12 text-base font-semibold bg-red-600 hover:bg-red-700"
                onClick={() => router.back()}
                size="lg"
              >
                결제 다시 시도
              </Button>

              <Button
                variant="outline"
                className="w-full"
                onClick={() => router.push('/cart')}
              >
                장바구니로 돌아가기
              </Button>

              <Button
                variant="ghost"
                className="w-full"
                onClick={() => router.push('/products')}
              >
                쇼핑 계속하기
              </Button>
            </div>

            {/* 도움말 */}
            <div className="mt-8 p-4 border rounded-lg border-red-200 bg-white">
              <p className="text-sm text-red-900 mb-2">
                <strong>🆘 도움말:</strong>
              </p>
              <ul className="text-sm text-red-800 space-y-1 text-left">
                <li>• 카드 정보를 다시 확인해주세요.</li>
                <li>• 다른 결제 수단으로 시도해보세요.</li>
                <li>• 카드사 고객센터에 문의해주세요.</li>
              </ul>
            </div>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

export default function PaymentFailPage() {
  return (
    <Suspense fallback={
      <div className="container mx-auto px-4 py-12 text-center">
        <div className="inline-block h-8 w-8 animate-spin rounded-full border-4 border-solid border-current border-r-transparent"></div>
      </div>
    }>
      <FailContent />
    </Suspense>
  );
}
