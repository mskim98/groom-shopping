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
  type: string;
  amount: number;
}

export default function CartPage() {
  const router = useRouter();
  const [cartItems, setCartItems] = useState<CartItem[]>([]);
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [selectedCoupon, setSelectedCoupon] = useState<string>('');
  const [loading, setLoading] = useState(true);
  const [selectedItems, setSelectedItems] = useState<Set<string>>(new Set());

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
      // 사용자가 발급받은 쿠폰만 로드
      try {
        const myCouponsResponse = await couponApi.getMyCoupons();
        console.log('My coupons response:', myCouponsResponse);

        const myCoupons = Array.isArray(myCouponsResponse) ? myCouponsResponse : [];
        console.log('Loaded my coupons:', myCoupons);

        // CouponIssueResponse를 Coupon 형식으로 변환
        const couponsData = await Promise.all(
          myCoupons.map(async (issue: any) => {
            try {
              const couponDetail = await couponApi.getCoupon(String(issue.couponId));
              return {
                id: String(issue.couponId),
                couponIssueId: issue.couponIssueId,
                ...couponDetail,
              };
            } catch (error) {
              console.error('Failed to load coupon detail:', error);
              return null;
            }
          })
        );

        const validCoupons = couponsData.filter(c => c !== null);
        console.log('Valid coupons:', validCoupons);
        setCoupons(validCoupons);
      } catch (error) {
        console.error('Failed to load my coupons:', error);
        // getMyCoupons 실패시 모든 쿠폰 로드 (대체)
        const response = await couponApi.getCoupons(0, 100);
        setCoupons(response.content || []);
      }
    } catch (error) {
      console.error('Failed to load coupons:', error);
      setCoupons([]);
    }
  };

  const handleRemove = async (productId: string) => {
    try {
      const item = cartItems.find(i => i.productId === productId);
      if (!item) {
        toast.error('상품을 찾을 수 없습니다.');
        return;
      }
      // 전체 수량을 제거
      await cartApi.removeFromCart(productId, item.quantity);
      setCartItems(cartItems.filter(item => item.productId !== productId));
      setSelectedItems(prev => {
        const newSet = new Set(prev);
        newSet.delete(productId);
        return newSet;
      });
      toast.success('상품이 삭제되었습니다.');
    } catch (error) {
      console.error('Remove error:', error);
      toast.error('삭제에 실패했습니다.');
    }
  };

  const handleToggleSelect = (productId: string) => {
    setSelectedItems(prev => {
      const newSet = new Set(prev);
      if (newSet.has(productId)) {
        newSet.delete(productId);
      } else {
        newSet.add(productId);
      }
      return newSet;
    });
  };

  const handleSelectAll = () => {
    if (selectedItems.size === cartItems.length) {
      setSelectedItems(new Set());
    } else {
      setSelectedItems(new Set(cartItems.map(item => item.productId)));
    }
  };

  const handleRemoveSelected = async () => {
    if (selectedItems.size === 0) {
      toast.error('삭제할 상품을 선택해주세요.');
      return;
    }

    try {
      const itemsToRemove = cartItems
        .filter(item => selectedItems.has(item.productId))
        .map(item => ({
          productId: item.productId,
          quantity: item.quantity,
        }));

      await cartApi.removeMultipleFromCart(itemsToRemove);
      setCartItems(cartItems.filter(item => !selectedItems.has(item.productId)));
      setSelectedItems(new Set());
      toast.success(`${itemsToRemove.length}개의 상품이 삭제되었습니다.`);
    } catch (error) {
      console.error('Remove selected error:', error);
      toast.error('삭제에 실패했습니다.');
    }
  };

  const handleIncreaseQuantity = async (productId: string) => {
    try {
      await cartApi.increaseQuantity(productId);
      setCartItems(cartItems.map(item =>
        item.productId === productId
          ? { ...item, quantity: item.quantity + 1 }
          : item
      ));
      toast.success('수량이 증가했습니다.');
    } catch (error) {
      toast.error('수량 증가에 실패했습니다.');
    }
  };

  const handleDecreaseQuantity = async (productId: string) => {
    try {
      const item = cartItems.find(i => i.productId === productId);
      if (item && item.quantity <= 1) {
        toast.error('최소 1개 이상이어야 합니다.');
        return;
      }
      await cartApi.decreaseQuantity(productId);
      setCartItems(cartItems.map(item =>
        item.productId === productId
          ? { ...item, quantity: item.quantity - 1 }
          : item
      ));
      toast.success('수량이 감소했습니다.');
    } catch (error) {
      toast.error('수량 감소에 실패했습니다.');
    }
  };

  const calculateTotal = () => {
    const selectedCartItems = cartItems.filter(item => selectedItems.has(item.productId));
    const subtotal = selectedCartItems.length > 0
      ? selectedCartItems.reduce((sum, item) => sum + item.price * item.quantity, 0)
      : cartItems.reduce((sum, item) => sum + item.price * item.quantity, 0);
    let discount = 0;

    if (selectedCoupon) {
      console.log('Selected coupon ID:', selectedCoupon);
      console.log('Available coupons:', coupons);
      const coupon = coupons.find(c => {
        console.log('Comparing:', String(c.id), '===', selectedCoupon);
        return String(c.id) === String(selectedCoupon);
      });
      console.log('Found coupon:', coupon);

      if (coupon) {
        if (coupon.type === 'PERCENT') {
          discount = subtotal * (coupon.amount / 100);
        } else {
          discount = coupon.amount;
        }
      }
    }

    console.log('Total calculation:', { subtotal, discount, selectedCoupon, couponsCount: coupons.length });

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
    
    // 선택한 쿠폰 정보를 sessionStorage에 저장
    if (selectedCoupon) {
      const selectedCouponData = coupons.find(c => String(c.id) === String(selectedCoupon));
      if (selectedCouponData) {
        console.log(selectedCouponData)
        sessionStorage.setItem('selectedCoupon', JSON.stringify({
          couponId: selectedCoupon,
          couponIssueId: selectedCouponData.couponIssueId,
          couponName: selectedCouponData.name,
          couponType: selectedCouponData.type,
          couponAmount: selectedCouponData.amount,
        }));
      }
    } else {
      sessionStorage.removeItem('selectedCoupon');
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
          {cartItems.length > 0 && (
            <div className="flex items-center justify-between mb-4">
              <div className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={selectedItems.size === cartItems.length && cartItems.length > 0}
                  onChange={handleSelectAll}
                  className="w-4 h-4 cursor-pointer"
                />
                <label className="text-sm text-muted-foreground cursor-pointer" onClick={handleSelectAll}>
                  전체 선택 ({selectedItems.size}/{cartItems.length})
                </label>
              </div>
              {selectedItems.size > 0 && (
                <Button
                  variant="destructive"
                  size="sm"
                  onClick={handleRemoveSelected}
                >
                  선택 삭제 ({selectedItems.size})
                </Button>
              )}
            </div>
          )}
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
                    <div className="flex items-center">
                      <input
                        type="checkbox"
                        checked={selectedItems.has(item.productId)}
                        onChange={() => handleToggleSelect(item.productId)}
                        className="w-4 h-4 cursor-pointer"
                      />
                    </div>
                    <div className="w-24 h-24 flex-shrink-0">
                      <ImageWithFallback
                        src={item.productImage || '/placeholder-product.jpg'}
                        alt={item.productName}
                        className="w-full h-full object-cover rounded"
                      />
                    </div>
                    <div className="flex-1">
                      <h3 className="mb-2">{item.productName}</h3>
                      <div className="flex items-center gap-2 mb-2">
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => handleDecreaseQuantity(item.productId)}
                          disabled={item.quantity <= 1}
                        >
                          −
                        </Button>
                        <span className="w-8 text-center">{item.quantity}</span>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => handleIncreaseQuantity(item.productId)}
                        >
                          +
                        </Button>
                      </div>
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
                  <label htmlFor="coupon-select" className="text-muted-foreground mb-2 block">쿠폰 선택</label>
                  <select
                    id="coupon-select"
                    value={selectedCoupon}
                    onChange={(e) => setSelectedCoupon(e.target.value)}
                    className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm bg-white cursor-pointer"
                  >
                    <option value="">쿠폰 미사용</option>
                    {coupons.map((coupon) => (
                      <option key={coupon.id} value={coupon.id}>
                        {coupon.name} (
                        {coupon.type === 'PERCENT'
                          ? `${coupon.amount}%`
                          : `${coupon.amount.toLocaleString()}원`}
                        )
                      </option>
                    ))}
                  </select>
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
