'use client';

import { Suspense, useEffect, useState } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { CheckCircle2 } from 'lucide-react';
import { toast } from 'sonner';

function PaymentContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const orderId = searchParams.get('orderId');
  const success = searchParams.get('success');
  const [processing, setProcessing] = useState(false);

  useEffect(() => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }

    if (!orderId && !success) {
      router.push('/cart');
    }
  }, [orderId, success, router]);

  const handlePayment = async () => {
    setProcessing(true);
    
    // Toss Payments 연동 시뮬레이션
    // 실제 환경에서는 Toss Payments SDK를 사용합니다
    setTimeout(() => {
      toast.success('결제가 완료되었습니다!');
      router.push('/payment?success=true');
    }, 2000);
  };

  // 결제 완료 페이지
  if (success === 'true') {
    return (
      <div className="container mx-auto px-4 py-12 max-w-md">
        <Card>
          <CardContent className="p-12 text-center">
            <CheckCircle2 className="w-16 h-16 mx-auto mb-4 text-green-600" />
            <h2 className="mb-4">결제 완료</h2>
            <p className="text-muted-foreground mb-6">
              주문이 정상적으로 완료되었습니다.
            </p>
            <div className="space-y-2">
              <Button className="w-full" onClick={() => router.push('/my/orders')}>
                주문 내역 보기
              </Button>
              <Button variant="outline" className="w-full" onClick={() => router.push('/products')}>
                쇼핑 계속하기
              </Button>
            </div>
          </CardContent>
        </Card>
      </div>
    );
  }

  // 결제 진행 페이지
  return (
    <div className="container mx-auto px-4 py-12 max-w-md">
      <Card>
        <CardContent className="p-6">
          <h2 className="mb-6">결제하기</h2>
          
          <div className="mb-6 p-4 bg-muted rounded-lg">
            <p className="text-muted-foreground mb-2">주문번호</p>
            <p>{orderId}</p>
          </div>

          <div className="space-y-4">
            <p className="text-muted-foreground">
              Toss Payments를 통해 안전하게 결제됩니다.
            </p>
            
            <Button 
              className="w-full" 
              onClick={handlePayment}
              disabled={processing}
            >
              {processing ? '결제 처리 중...' : '결제하기'}
            </Button>
            
            <Button 
              variant="outline" 
              className="w-full" 
              onClick={() => router.back()}
              disabled={processing}
            >
              취소
            </Button>
          </div>

          <div className="mt-6 p-4 border rounded-lg">
            <p className="text-muted-foreground">
              <strong>참고:</strong> 실제 결제 연동을 위해서는 Toss Payments API 키가 필요합니다.
              현재는 시뮬레이션 모드로 동작합니다.
            </p>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}

export default function PaymentPage() {
  return (
    <Suspense fallback={<div className="container mx-auto px-4 py-12 text-center">Loading...</div>}>
      <PaymentContent />
    </Suspense>
  );
}
