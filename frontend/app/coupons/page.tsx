'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { couponApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Gift, Check } from 'lucide-react';
import { toast } from 'sonner';

interface Coupon {
  id: string;
  name: string;
  description?: string;
  type: string;
  amount: number;
  expireDate?: string;
  isActive?: boolean;
  isIssued?: boolean; // 내가 발급받은 쿠폰인지 여부
}

interface CouponIssueResponse {
  couponIssueId: number;
  couponId: number;
  createdAt: string;
  deletedAt: string;
}

export default function CouponsPage() {
  const router = useRouter();
  const [allCoupons, setAllCoupons] = useState<Coupon[]>([]);
  const [myCoupons, setMyCoupons] = useState<Coupon[]>([]);
  const [loading, setLoading] = useState(true);
  const [activeTab, setActiveTab] = useState<'available' | 'my'>('available');

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
      // 모든 쿠폰 로드
      const allCouponsResponse = await couponApi.getCoupons(0, 100);
      const coupons = allCouponsResponse.content || [];
      console.log('All coupons:', coupons);

      // 내가 발급받은 쿠폰 로드
      try {
        const issuedCouponsResponse = await couponApi.getMyCoupons();
        console.log('Issued coupons response:', issuedCouponsResponse);
        console.log('Issued coupons response type:', typeof issuedCouponsResponse);

        const issuedCoupons = Array.isArray(issuedCouponsResponse)
          ? issuedCouponsResponse
          : issuedCouponsResponse?.content || [];

        const issuedCouponIds = new Set(issuedCoupons.map((c: CouponIssueResponse) => String(c.couponId)));
        console.log('Issued coupon IDs:', Array.from(issuedCouponIds));

        // 발급받은 쿠폰 정보 표시
        const enrichedCoupons = coupons.map((c: Coupon) => ({
          ...c,
          isIssued: issuedCouponIds.has(String(c.id)),
        }));

        setAllCoupons(enrichedCoupons);

        // 발급받은 쿠폰만 필터링
        const myCouponList = enrichedCoupons.filter((c: Coupon) => c.isIssued);
        console.log('My coupons:', myCouponList);
        setMyCoupons(myCouponList);
      } catch (issuedCouponsError) {
        console.error('Failed to load issued coupons:', issuedCouponsError);
        // 발급된 쿠폰 로딩 실패해도 모든 쿠폰은 표시
        setAllCoupons(coupons);
        setMyCoupons([]);
        toast.error('발급된 쿠폰 목록을 불러오는데 실패했습니다.');
      }
    } catch (error) {
      console.error('Failed to load coupons:', error);
      toast.error('쿠폰 목록을 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleIssueCoupon = async (couponId: string) => {
    try {
      // 발급받을 쿠폰 정보 찾기
      const couponToIssue = allCoupons.find(c => c.id === couponId);
      if (!couponToIssue) {
        toast.error('쿠폰을 찾을 수 없습니다.');
        return;
      }

      // 백엔드 issueCoupon이 인증된 사용자로 자동 인식하도록 변경됨
      await couponApi.issueCoupon(couponId, '');

      toast.success('쿠폰이 발급되었습니다.');

      // 쿠폰 목록 새로고침 (발급 상태 업데이트)
      await loadCoupons();
    } catch (error) {
      console.error('Coupon issue error:', error);
      toast.error('쿠폰 발급에 실패했습니다.');
    }
  };

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <Skeleton className="h-8 w-32 mb-6" />
        <div className="space-y-4">
          {Array.from({ length: 3 }).map((_, i) => (
            <Skeleton key={i} className="h-32 w-full" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <h2 className="mb-6">쿠폰</h2>

      <div className="space-y-4">
        <div className="flex gap-2 border-b">
          <Button
            variant={activeTab === 'available' ? 'default' : 'ghost'}
            className="rounded-none border-b-2"
            onClick={() => setActiveTab('available')}
          >
            발급 가능한 쿠폰
          </Button>
          <Button
            variant={activeTab === 'my' ? 'default' : 'ghost'}
            className="rounded-none border-b-2"
            onClick={() => setActiveTab('my')}
          >
            내 쿠폰 ({myCoupons.length})
          </Button>
        </div>

        {activeTab === 'available' && (
          allCoupons.length === 0 ? (
            <Card>
              <CardContent className="p-12 text-center">
                <Gift className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
                <p className="text-muted-foreground">발급 가능한 쿠폰이 없습니다.</p>
              </CardContent>
            </Card>
          ) : (
            <div className="grid gap-4 md:grid-cols-2">
              {allCoupons.map((coupon) => (
                <Card
                  key={coupon.id}
                  className={`${!coupon.isActive ? 'opacity-50' : ''}`}
                >
                  <CardHeader>
                    <div className="flex items-start justify-between">
                      <CardTitle className="text-lg">{coupon.name}</CardTitle>
                      {coupon.isActive ? (
                        <Badge variant="default">활성</Badge>
                      ) : (
                        <Badge variant="secondary">비활성</Badge>
                      )}
                    </div>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {coupon.description && (
                      <p className="text-sm text-muted-foreground">
                        {coupon.description}
                      </p>
                    )}

                    <div className="bg-primary/5 rounded p-3">
                      <p className="text-2xl font-bold text-primary">
                        {coupon.type === 'PERCENT'
                          ? `${coupon.amount}%`
                          : `${coupon.amount.toLocaleString()}원`}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {coupon.type === 'PERCENT' ? '할인율' : '할인액'}
                      </p>
                    </div>

                    {coupon.expireDate && (
                      <p className="text-xs text-muted-foreground">
                        만료일: {new Date(coupon.expireDate).toLocaleDateString()}
                      </p>
                    )}

                    {coupon.isIssued ? (
                      <Button variant="outline" disabled className="w-full">
                        <Check className="w-4 h-4 mr-2" />
                        발급됨
                      </Button>
                    ) : (
                      <Button
                        className="w-full"
                        disabled={!coupon.isActive}
                        onClick={() => handleIssueCoupon(coupon.id)}
                      >
                        발급받기
                      </Button>
                    )}
                  </CardContent>
                </Card>
              ))}
            </div>
          )
        )}

        {activeTab === 'my' && (
          myCoupons.length === 0 ? (
            <Card>
              <CardContent className="p-12 text-center">
                <Gift className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
                <p className="text-muted-foreground">발급받은 쿠폰이 없습니다.</p>
              </CardContent>
            </Card>
          ) : (
            <div className="grid gap-4 md:grid-cols-2">
              {myCoupons.map((coupon) => (
                <Card key={coupon.id}>
                  <CardHeader>
                    <CardTitle className="text-lg">{coupon.name}</CardTitle>
                  </CardHeader>
                  <CardContent className="space-y-4">
                    {coupon.description && (
                      <p className="text-sm text-muted-foreground">
                        {coupon.description}
                      </p>
                    )}

                    <div className="bg-primary/5 rounded p-3">
                      <p className="text-2xl font-bold text-primary">
                        {coupon.type === 'PERCENT'
                          ? `${coupon.amount}%`
                          : `${coupon.amount.toLocaleString()}원`}
                      </p>
                      <p className="text-xs text-muted-foreground">
                        {coupon.type === 'PERCENT' ? '할인율' : '할인액'}
                      </p>
                    </div>

                    {coupon.expireDate && (
                      <p className="text-xs text-muted-foreground">
                        만료일: {new Date(coupon.expireDate).toLocaleDateString()}
                      </p>
                    )}

                    <div className="text-xs text-green-600 font-medium">
                      ✓ 사용 가능한 쿠폰입니다.
                    </div>
                  </CardContent>
                </Card>
              ))}
            </div>
          )
        )}
      </div>
    </div>
  );
}
