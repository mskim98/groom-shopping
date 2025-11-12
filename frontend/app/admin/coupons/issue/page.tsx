'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { couponApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { ArrowLeft } from 'lucide-react';
import { toast } from 'sonner';

interface Coupon {
  id: string;
  name: string;
  description?: string;
  quantity?: number;
  amount: number;
  type: string;
  expireDate?: string;
  isActive?: boolean;
}

export default function CouponIssuePage() {
  const router = useRouter();
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [selectedCouponId, setSelectedCouponId] = useState('');
  const [userId, setUserId] = useState('');
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }
    loadCoupons();
  }, [router]);

  const loadCoupons = async () => {
    try {
      const response = await couponApi.getCoupons(0, 100);
      setCoupons(response.content || response);
    } catch (error) {
      toast.error('쿠폰 목록을 불러오는데 실패했습니다.');
    }
  };

  const handleIssueCoupon = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!selectedCouponId || !userId) {
      toast.error('쿠폰과 사용자 ID를 모두 입력해주세요.');
      return;
    }

    setLoading(true);
    try {
      await couponApi.issueCoupon(selectedCouponId, userId);
      toast.success('쿠폰이 발급되었습니다.');
      setUserId('');
    } catch (error: any) {
      toast.error(error.message || '쿠폰 발급에 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const selectedCoupon = coupons.find((c) => c.id === selectedCouponId);

  return (
    <div className="container mx-auto px-4 py-8">
      <Button variant="ghost" onClick={() => router.back()} className="mb-6">
        <ArrowLeft className="w-4 h-4 mr-2" />
        뒤로가기
      </Button>

      <h2 className="mb-6">쿠폰 발급</h2>

      <div className="grid md:grid-cols-2 gap-6">
        <Card>
          <CardHeader>
            <CardTitle>쿠폰 발급 정보</CardTitle>
          </CardHeader>
          <CardContent>
            <form onSubmit={handleIssueCoupon} className="space-y-4">
              <div>
                <Label htmlFor="coupon">발급할 쿠폰</Label>
                <Select value={selectedCouponId} onValueChange={setSelectedCouponId}>
                  <SelectTrigger>
                    <SelectValue placeholder="쿠폰을 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {coupons.map((coupon) => (
                      <SelectItem key={coupon.id} value={coupon.id}>
                        {coupon.name} (
                        {coupon.type === 'PERCENT' ? `${coupon.amount}%` : `${coupon.amount.toLocaleString()}원`} 할인)
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>

              <div>
                <Label htmlFor="userId">사용자 ID</Label>
                <Input
                  id="userId"
                  value={userId}
                  onChange={(e) => setUserId(e.target.value)}
                  placeholder="발급받을 사용자 ID를 입력하세요"
                  required
                />
              </div>

              <Button type="submit" className="w-full" disabled={loading}>
                {loading ? '발급 중...' : '쿠폰 발급'}
              </Button>
            </form>
          </CardContent>
        </Card>

        {selectedCoupon && (
          <Card>
            <CardHeader>
              <CardTitle>선택된 쿠폰 정보</CardTitle>
            </CardHeader>
            <CardContent className="space-y-3">
              <div>
                <p className="text-sm text-muted-foreground">쿠폰명</p>
                <p className="font-medium">{selectedCoupon.name}</p>
              </div>
              {selectedCoupon.description && (
                <div>
                  <p className="text-sm text-muted-foreground">설명</p>
                  <p className="text-sm">{selectedCoupon.description}</p>
                </div>
              )}
              <div>
                <p className="text-sm text-muted-foreground">할인 타입</p>
                <p className="font-medium">
                  {selectedCoupon.type === 'PERCENT' ? '퍼센트 할인' : '정액 할인'}
                </p>
              </div>
              <div>
                <p className="text-sm text-muted-foreground">할인 값</p>
                <p className="font-medium">
                  {selectedCoupon.type === 'PERCENT'
                    ? `${selectedCoupon.amount}%`
                    : `${selectedCoupon.amount.toLocaleString()}원`}
                </p>
              </div>
              {selectedCoupon.quantity && (
                <div>
                  <p className="text-sm text-muted-foreground">발급 수량</p>
                  <p className="font-medium">{selectedCoupon.quantity}개</p>
                </div>
              )}
              {selectedCoupon.expireDate && (
                <div>
                  <p className="text-sm text-muted-foreground">만료일</p>
                  <p className="font-medium">
                    {new Date(selectedCoupon.expireDate).toLocaleDateString()}
                  </p>
                </div>
              )}
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  );
}
