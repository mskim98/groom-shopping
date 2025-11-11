'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { cartApi, getAccessToken, couponApi } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Skeleton } from '@/components/ui/skeleton';
import { Trash2, ShoppingBag } from 'lucide-react';
import { toast } from 'sonner';
import { ImageWithFallback } from '@/components/figma/ImageWithFallback';

interface CartItem {
  productId: string;
  productName: string;
  productImage?: string;
  price: number;
  quantity: number;
}

interface Coupon {
  id: string;
  name: string;
  discountType: string;
  discountValue: number;
}

export default function CartPage() {
  const router = useRouter();
  const [cartItems, setCartItems] = useState<CartItem[]>([]);
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [selectedCoupon, setSelectedCoupon] = useState<string>('');
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }

    loadCart();
    loadCoupons();
  }, [router]);

  const loadCart = async () => {
    try {
      const response = await cartApi.getCart();
      setCartItems(response.items || []);
    } catch (error) {
      toast.error('장바구니를 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const loadCoupons = async () => {
    try {
      const response = await couponApi.getCoupons(0, 100);
      setCoupons(response.content || []);
    } catch (error) {
      console.error('Failed to load coupons:', error);
    }
  };

  const handleRemove = async (productId: string) => {
    try {
      await cartApi.removeFromCart(productId);
      setCartItems(cartItems.filter(item => item.productId !== productId));
      toast.success('상품이 삭제되었습니다.');
    } catch (error) {
      toast.error('삭제에 실패했습니다.');
    }
  };

  const calculateTotal = () => {
    const subtotal = cartItems.reduce((sum, item) => sum + item.price * item.quantity, 0);
    let discount = 0;

    if (selectedCoupon) {
      const coupon = coupons.find(c => c.id === selectedCoupon);
      if (coupon) {
        if (coupon.discountType === 'PERCENTAGE') {
          discount = subtotal * (coupon.discountValue / 100);
        } else {
          discount = coupon.discountValue;
        }
      }
    }

    return {
      subtotal,
      discount,
      total: subtotal - discount,
    };
  };

  const handleCheckout = () => {
    if (cartItems.length === 0) {
      toast.error('장바구니가 비어있습니다.');
      return;
    }
    router.push('/order');
  };

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <Skeleton className="h-8 w-32 mb-6" />
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-24 w-full" />
          ))}
        </div>
      </div>
    );
  }

  const totals = calculateTotal();

  return (
    <div className="container mx-auto px-4 py-8">
      <h2 className="mb-6">장바구니</h2>

      <div className="grid lg:grid-cols-3 gap-6">
        <div className="lg:col-span-2 space-y-4">
          {cartItems.length === 0 ? (
            <Card>
              <CardContent className="p-12 text-center">
                <ShoppingBag className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
                <p className="text-muted-foreground mb-4">장바구니가 비어있습니다.</p>
                <Button onClick={() => router.push('/products')}>
                  상품 둘러보기
                </Button>
              </CardContent>
            </Card>
          ) : (
            cartItems.map((item) => (
              <Card key={item.productId}>
                <CardContent className="p-4">
                  <div className="flex gap-4">
                    <div className="w-24 h-24 flex-shrink-0">
                      <ImageWithFallback
                        src={item.productImage || '/placeholder-product.jpg'}
                        alt={item.productName}
                        className="w-full h-full object-cover rounded"
                      />
                    </div>
                    <div className="flex-1">
                      <h3 className="mb-2">{item.productName}</h3>
                      <p className="text-muted-foreground mb-2">
                        수량: {item.quantity}개
                      </p>
                      <p className="text-primary">
                        {(item.price * item.quantity).toLocaleString()}원
                      </p>
                    </div>
                    <Button
                      variant="ghost"
                      size="sm"
                      onClick={() => handleRemove(item.productId)}
                    >
                      <Trash2 className="w-4 h-4" />
                    </Button>
                  </div>
                </CardContent>
              </Card>
            ))
          )}
        </div>

        <div>
          <Card>
            <CardContent className="p-6 space-y-4">
              <h3>주문 요약</h3>

              {coupons.length > 0 && (
                <div>
                  <label className="text-muted-foreground mb-2 block">쿠폰 선택</label>
                  <Select value={selectedCoupon} onValueChange={setSelectedCoupon}>
                    <SelectTrigger>
                      <SelectValue placeholder="쿠폰을 선택하세요" />
                    </SelectTrigger>
                    <SelectContent>
                      <SelectItem value="">쿠폰 미사용</SelectItem>
                      {coupons.map((coupon) => (
                        <SelectItem key={coupon.id} value={coupon.id}>
                          {coupon.name} (
                          {coupon.discountType === 'PERCENTAGE'
                            ? `${coupon.discountValue}%`
                            : `${coupon.discountValue.toLocaleString()}원`}
                          )
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </div>
              )}

              <div className="space-y-2 pt-4 border-t">
                <div className="flex justify-between">
                  <span className="text-muted-foreground">소계</span>
                  <span>{totals.subtotal.toLocaleString()}원</span>
                </div>
                {totals.discount > 0 && (
                  <div className="flex justify-between text-destructive">
                    <span>할인</span>
                    <span>-{totals.discount.toLocaleString()}원</span>
                  </div>
                )}
                <div className="flex justify-between pt-2 border-t">
                  <span>합계</span>
                  <span className="text-primary">{totals.total.toLocaleString()}원</span>
                </div>
              </div>

              <Button className="w-full" onClick={handleCheckout}>
                주문하기
              </Button>
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}
