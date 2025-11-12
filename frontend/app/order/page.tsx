'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { cartApi, orderApi, paymentApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { toast } from 'sonner';
import { CreditCard } from 'lucide-react';

interface CartItem {
  productId: string;
  productName: string;
  price: number;
  quantity: number;
}

interface OrderResponse {
  orderId: string; // UUID
  userId: number;
  subTotal: number;
  discountAmount: number;
  totalAmount: number;
  status: string;
  couponId: number | null;
  createdAt: string;
  orderItems: Array<{
    productId: string;
    productName: string;
    price: number;
    quantity: number;
  }>;
}

export default function OrderPage() {
  const router = useRouter();
  const [cartItems, setCartItems] = useState<CartItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [memo, setMemo] = useState('');
  const [createdOrder, setCreatedOrder] = useState<OrderResponse | null>(null);
  const [paymentInProgress, setPaymentInProgress] = useState(false);
  const [paymentCompleted, setPaymentCompleted] = useState(false);

  useEffect(() => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }

    loadCart();
  }, [router]);

  const loadCart = async () => {
    try {
      const response = await cartApi.getCart();
      setCartItems(response.items || []);
      if (response.items.length === 0) {
        toast.error('장바구니가 비어있습니다.');
        router.push('/cart');
      }
    } catch (error) {
      toast.error('장바구니를 불러오는데 실패했습니다.');
    }
  };

  const calculateTotal = () => {
    return cartItems.reduce((sum, item) => sum + item.price * item.quantity, 0);
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);

    try {
      // 검증
      if (!name || !phone || !address) {
        toast.error('배송 정보를 모두 입력해주세요.');
        setLoading(false);
        return;
      }

      console.log('Creating order with data:', {
        couponId: null,
      });

      // 백엔드는 couponId만 필요 (CreateOrderRequest)
      // couponId: null (쿠폰 미적용) 또는 숫자 쿠폰 ID
      const orderData = {
        couponId: null,
      };

      const order = await orderApi.createOrder(orderData) as OrderResponse;

      console.log('Order created successfully:', order);
      toast.success('주문이 생성되었습니다.');

      // 배송 정보는 세션에 임시 저장 (향후 별도 API로 저장 필요)
      if (typeof window !== 'undefined') {
        sessionStorage.setItem('shippingInfo', JSON.stringify({
          name,
          phone,
          address,
          memo,
        }));
      }

      // 결제 수단 선택 상태로 변경
      setCreatedOrder(order);
    } catch (error) {
      console.error('Order creation error:', error);
      console.error('Error details:', error);
      toast.error('주문 생성에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  // 테스트 결제
  const handleTestPayment = async () => {
    if (!createdOrder) return;

    setPaymentInProgress(true);
    try {
      const paymentData = {
        orderId: createdOrder.orderId,
        amount: createdOrder.totalAmount,
        paymentMethod: 'TEST',
      };

      console.log('Processing test payment:', paymentData);
      await paymentApi.confirmTestPayment(paymentData);

      toast.success('테스트 결제가 완료되었습니다.');
      setPaymentCompleted(true);
    } catch (error) {
      console.error('Test payment error:', error);
      toast.error(error instanceof Error ? error.message : '테스트 결제에 실패했습니다.');
    } finally {
      setPaymentInProgress(false);
    }
  };

  // 실제 토스 결제
  const handleTossPayment = async () => {
    if (!createdOrder) return;

    setPaymentInProgress(true);
    try {
      const paymentData = {
        orderId: createdOrder.orderId,
        amount: createdOrder.totalAmount,
        paymentMethod: 'TOSS',
      };

      console.log('Processing TOSS payment:', paymentData);
      await paymentApi.confirmPayment(paymentData);

      toast.success('결제가 완료되었습니다.');
      setPaymentCompleted(true);
    } catch (error) {
      console.error('Payment error:', error);
      toast.error(error instanceof Error ? error.message : '결제에 실패했습니다.');
    } finally {
      setPaymentInProgress(false);
    }
  };

  // 결제 완료 전 - 결제 수단 선택 화면
  if (createdOrder && !paymentCompleted) {
    return (
      <div className="container mx-auto px-4 py-8 max-w-3xl">
        <h2 className="mb-6 text-2xl font-bold">결제 방법 선택</h2>

        {/* 주문 정보 요약 */}
        <Card className="mb-6">
          <CardContent className="p-6">
            <h3 className="mb-4 font-semibold">주문 정보</h3>
            <div className="space-y-2 text-sm">
              <div className="flex justify-between">
                <span className="text-muted-foreground">주문번호</span>
                <span className="font-mono">{createdOrder.orderId}</span>
              </div>
              <div className="flex justify-between py-2 border-t border-b">
                <span>결제 금액</span>
                <span className="text-lg font-bold text-primary">
                  {createdOrder.totalAmount.toLocaleString()}원
                </span>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* 결제 수단 선택 */}
        <div className="grid grid-cols-2 gap-4">
          {/* 테스트 결제 버튼 */}
          <Card
            className="cursor-pointer hover:border-blue-500 transition-colors"
            onClick={handleTestPayment}
          >
            <CardContent className="p-6">
              <div className="text-center">
                <div className="w-12 h-12 mx-auto mb-3 bg-blue-100 rounded-full flex items-center justify-center">
                  <CreditCard className="w-6 h-6 text-blue-600" />
                </div>
                <h3 className="font-semibold mb-2">테스트 결제</h3>
                <p className="text-xs text-muted-foreground mb-4">
                  실제 결제 없이 테스트합니다
                </p>
                <Button
                  className="w-full"
                  disabled={paymentInProgress}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleTestPayment();
                  }}
                >
                  {paymentInProgress ? '처리 중...' : '테스트 결제'}
                </Button>
              </div>
            </CardContent>
          </Card>

          {/* 실제 결제 버튼 */}
          <Card
            className="cursor-pointer hover:border-amber-500 transition-colors"
            onClick={handleTossPayment}
          >
            <CardContent className="p-6">
              <div className="text-center">
                <div className="w-12 h-12 mx-auto mb-3 bg-amber-100 rounded-full flex items-center justify-center">
                  <CreditCard className="w-6 h-6 text-amber-600" />
                </div>
                <h3 className="font-semibold mb-2">토스 결제</h3>
                <p className="text-xs text-muted-foreground mb-4">
                  토스를 통한 실제 결제
                </p>
                <Button
                  className="w-full bg-amber-600 hover:bg-amber-700"
                  disabled={paymentInProgress}
                  onClick={(e) => {
                    e.stopPropagation();
                    handleTossPayment();
                  }}
                >
                  {paymentInProgress ? '처리 중...' : '토스 결제'}
                </Button>
              </div>
            </CardContent>
          </Card>
        </div>

        {/* 취소 버튼 */}
        <Button
          variant="outline"
          className="w-full mt-6"
          onClick={() => {
            setCreatedOrder(null);
            setPaymentCompleted(false);
          }}
        >
          결제 취소
        </Button>
      </div>
    );
  }

  // 결제 완료 상태 - 주문 확인 페이지 표시
  if (createdOrder && paymentCompleted) {
    return (
      <div className="container mx-auto px-4 py-8 max-w-3xl">
        <Card>
          <CardContent className="p-12 text-center">
            <div className="mb-6">
              <div className="w-16 h-16 mx-auto mb-4 bg-green-100 rounded-full flex items-center justify-center">
                <span className="text-3xl">✓</span>
              </div>
              <h2 className="text-2xl font-bold mb-2">주문이 완료되었습니다!</h2>
              <p className="text-muted-foreground">주문번호: {createdOrder.orderId}</p>
            </div>
          </CardContent>
        </Card>

        <Card className="mt-6">
          <CardContent className="p-6">
            <h3 className="mb-4 font-bold">주문 정보</h3>
            <div className="space-y-4">
              <div className="flex justify-between py-2 border-b">
                <span className="text-muted-foreground">주문번호</span>
                <span className="font-mono text-sm">{createdOrder.orderId}</span>
              </div>
              <div className="flex justify-between py-2 border-b">
                <span className="text-muted-foreground">주문일시</span>
                <span>{new Date(createdOrder.createdAt).toLocaleString()}</span>
              </div>
              <div className="flex justify-between py-2 border-b">
                <span className="text-muted-foreground">상태</span>
                <span className="font-semibold">{createdOrder.status}</span>
              </div>
            </div>
          </CardContent>
        </Card>

        <Card className="mt-6">
          <CardContent className="p-6">
            <h3 className="mb-4 font-bold">주문 상품</h3>
            <div className="space-y-3">
              {createdOrder.orderItems.map((item) => (
                <div key={item.productId} className="flex justify-between py-2 border-b">
                  <div>
                    <p>{item.productName}</p>
                    <p className="text-muted-foreground">{item.quantity}개</p>
                  </div>
                  <p>{(item.price * item.quantity).toLocaleString()}원</p>
                </div>
              ))}
            </div>
          </CardContent>
        </Card>

        <Card className="mt-6">
          <CardContent className="p-6">
            <h3 className="mb-4 font-bold">배송 정보</h3>
            <div className="space-y-2 text-sm">
              {(() => {
                const shippingInfo = typeof window !== 'undefined'
                  ? JSON.parse(sessionStorage.getItem('shippingInfo') || '{}')
                  : {};
                return (
                  <>
                    <p><span className="text-muted-foreground">받는 사람:</span> {shippingInfo.name}</p>
                    <p><span className="text-muted-foreground">연락처:</span> {shippingInfo.phone}</p>
                    <p><span className="text-muted-foreground">주소:</span> {shippingInfo.address}</p>
                    {shippingInfo.memo && (
                      <p><span className="text-muted-foreground">배송메모:</span> {shippingInfo.memo}</p>
                    )}
                  </>
                );
              })()}
            </div>
          </CardContent>
        </Card>

        <Card className="mt-6">
          <CardContent className="p-6">
            <div className="space-y-2 pt-4 border-t">
              <div className="flex justify-between">
                <span className="text-muted-foreground">소계</span>
                <span>{createdOrder.subTotal.toLocaleString()}원</span>
              </div>
              {createdOrder.discountAmount > 0 && (
                <div className="flex justify-between text-destructive">
                  <span>할인</span>
                  <span>-{createdOrder.discountAmount.toLocaleString()}원</span>
                </div>
              )}
              <div className="flex justify-between pt-2 border-t text-lg font-bold">
                <span>합계</span>
                <span className="text-primary">{createdOrder.totalAmount.toLocaleString()}원</span>
              </div>
            </div>
          </CardContent>
        </Card>

        <div className="flex gap-2 mt-6">
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

  return (
    <div className="container mx-auto px-4 py-8 max-w-3xl">
      <h2 className="mb-6">주문하기</h2>

      <form onSubmit={handleSubmit} className="space-y-6">
        <Card>
          <CardContent className="p-6">
            <h3 className="mb-4">배송 정보</h3>
            <div className="space-y-4">
              <div>
                <Label htmlFor="name">받는 사람</Label>
                <Input
                  id="name"
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  required
                />
              </div>
              <div>
                <Label htmlFor="phone">연락처</Label>
                <Input
                  id="phone"
                  type="tel"
                  value={phone}
                  onChange={(e) => setPhone(e.target.value)}
                  placeholder="010-0000-0000"
                  required
                />
              </div>
              <div>
                <Label htmlFor="address">배송 주소</Label>
                <Input
                  id="address"
                  value={address}
                  onChange={(e) => setAddress(e.target.value)}
                  required
                />
              </div>
              <div>
                <Label htmlFor="memo">배송 메모</Label>
                <Textarea
                  id="memo"
                  value={memo}
                  onChange={(e) => setMemo(e.target.value)}
                  placeholder="배송 시 요청사항을 입력해주세요"
                  rows={3}
                />
              </div>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardContent className="p-6">
            <h3 className="mb-4">주문 상품</h3>
            <div className="space-y-3">
              {cartItems.map((item) => (
                <div key={item.productId} className="flex justify-between py-2 border-b">
                  <div>
                    <p>{item.productName}</p>
                    <p className="text-muted-foreground">{item.quantity}개</p>
                  </div>
                  <p>{(item.price * item.quantity).toLocaleString()}원</p>
                </div>
              ))}
              <div className="flex justify-between pt-4">
                <span>총 결제 금액</span>
                <span className="text-primary">{calculateTotal().toLocaleString()}원</span>
              </div>
            </div>
          </CardContent>
        </Card>

        <div className="flex gap-2">
          <Button type="button" variant="outline" onClick={() => router.back()}>
            취소
          </Button>
          <Button type="submit" className="flex-1" disabled={loading}>
            {loading ? '처리 중...' : '결제하기'}
          </Button>
        </div>
      </form>
    </div>
  );
}
