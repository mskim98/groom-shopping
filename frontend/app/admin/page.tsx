'use client';

import Link from 'next/link';
import { Card, CardContent } from '@/components/ui/card';
import { Package, Ticket, Gift, BarChart } from 'lucide-react';

export default function AdminPage() {
  return (
    <div className="container mx-auto px-4 py-8">
      <h2 className="mb-6">관리자 대시보드</h2>

      <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-6">
        <Link href="/admin/products">
          <Card className="hover:shadow-lg transition-shadow cursor-pointer">
            <CardContent className="p-6">
              <Package className="w-12 h-12 mb-4 text-primary" />
              <h3 className="mb-2">상품 관리</h3>
              <p className="text-muted-foreground">
                상품 등록, 수정, 삭제
              </p>
            </CardContent>
          </Card>
        </Link>

        <Link href="/admin/coupons">
          <Card className="hover:shadow-lg transition-shadow cursor-pointer">
            <CardContent className="p-6">
              <Ticket className="w-12 h-12 mb-4 text-primary" />
              <h3 className="mb-2">쿠폰 관리</h3>
              <p className="text-muted-foreground">
                쿠폰 등록, 수정, 발급
              </p>
            </CardContent>
          </Card>
        </Link>

        <Link href="/admin/raffles">
          <Card className="hover:shadow-lg transition-shadow cursor-pointer">
            <CardContent className="p-6">
              <Gift className="w-12 h-12 mb-4 text-primary" />
              <h3 className="mb-2">추첨 관리</h3>
              <p className="text-muted-foreground">
                추첨 이벤트 생성 및 실행
              </p>
            </CardContent>
          </Card>
        </Link>
      </div>
    </div>
  );
}
