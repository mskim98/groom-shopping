import Link from 'next/link';
import { Button } from '@/components/ui/button';
import { ShoppingBag, Gift, Ticket, TrendingUp } from 'lucide-react';

export default function HomePage() {
  return (
    <div className="container mx-auto px-4 py-12">
      <section className="text-center mb-16">
        <h1 className="mb-4">온라인 쇼핑몰에 오신 것을 환영합니다</h1>
        <p className="text-muted-foreground mb-8">
          최고의 상품과 특별한 이벤트를 만나보세요
        </p>
        <div className="flex gap-4 justify-center">
          <Link href="/products">
            <Button size="lg">
              <ShoppingBag className="w-5 h-5 mr-2" />
              상품 둘러보기
            </Button>
          </Link>
          <Link href="/raffles">
            <Button size="lg" variant="outline">
              <Gift className="w-5 h-5 mr-2" />
              추첨 이벤트
            </Button>
          </Link>
        </div>
      </section>

      <section className="grid md:grid-cols-3 gap-6">
        <div className="border rounded-lg p-6 text-center">
          <ShoppingBag className="w-12 h-12 mx-auto mb-4 text-primary" />
          <h3 className="mb-2">다양한 상품</h3>
          <p className="text-muted-foreground">
            엄선된 상품들을 합리적인 가격에 만나보세요
          </p>
        </div>

        <div className="border rounded-lg p-6 text-center">
          <Ticket className="w-12 h-12 mx-auto mb-4 text-primary" />
          <h3 className="mb-2">특별 쿠폰</h3>
          <p className="text-muted-foreground">
            회원 전용 할인 쿠폰을 받아보세요
          </p>
        </div>

        <div className="border rounded-lg p-6 text-center">
          <Gift className="w-12 h-12 mx-auto mb-4 text-primary" />
          <h3 className="mb-2">추첨 이벤트</h3>
          <p className="text-muted-foreground">
            매주 진행되는 경품 추첨에 참여하세요
          </p>
        </div>
      </section>
    </div>
  );
}
