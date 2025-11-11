'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { cartApi, orderApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { toast } from 'sonner';

interface CartItem {
  productId: string;
  productName: string;
  price: number;
  quantity: number;
}

export default function OrderPage() {
  const router = useRouter();
  const [cartItems, setCartItems] = useState<CartItem[]>([]);
  const [loading, setLoading] = useState(false);
  const [name, setName] = useState('');
  const [phone, setPhone] = useState('');
  const [address, setAddress] = useState('');
  const [memo, setMemo] = useState('');

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
      const orderData = {
        items: cartItems,
        shippingInfo: {
          name,
          phone,
          address,
          memo,
        },
        totalAmount: calculateTotal(),
      };

      const order = await orderApi.createOrder(orderData) as { id: number };
      toast.success('주문이 생성되었습니다.');
      router.push(`/payment?orderId=${order.id}`);
    } catch (error) {
      toast.error('주문 생성에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

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
