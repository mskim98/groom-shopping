'use client';

import Link from 'next/link';
import { usePathname, useRouter } from 'next/navigation';
import { ShoppingCart, User, LogOut, Package, Ticket, Gift, Percent } from 'lucide-react';
import { Button } from './ui/button';
import { getAccessToken, clearTokens, authApi } from '@/lib/api';
import { useEffect, useState } from 'react';

export function Navbar() {
  const pathname = usePathname();
  const router = useRouter();
  const [isLoggedIn, setIsLoggedIn] = useState(false);

  useEffect(() => {
    setIsLoggedIn(!!getAccessToken());
  }, [pathname]);

  const handleLogout = async () => {
    try {
      await authApi.logout();
    } catch (error) {
      console.error('Logout error:', error);
    } finally {
      clearTokens();
      setIsLoggedIn(false);
      router.push('/');
    }
  };

  return (
    <nav className="border-b bg-white sticky top-0 z-50">
      <div className="container mx-auto px-4 py-4 flex items-center justify-between">
        <Link href="/">
          <h1 className="cursor-pointer">쇼핑몰</h1>
        </Link>

        <div className="flex items-center gap-6">
          <Link href="/products" className={pathname === '/products' ? 'text-primary' : ''}>
            상품
          </Link>
          <Link href="/raffles" className={pathname === '/raffles' ? 'text-primary' : ''}>
            <Gift className="w-4 h-4 inline mr-1" />
            추첨 이벤트
          </Link>
          {isLoggedIn && (
            <>
              <Link href="/cart" className={pathname === '/cart' ? 'text-primary' : ''}>
                <ShoppingCart className="w-4 h-4 inline mr-1" />
                장바구니
              </Link>
              <Link href="/my/orders" className={pathname === '/my/orders' ? 'text-primary' : ''}>
                <Package className="w-4 h-4 inline mr-1" />
                주문내역
              </Link>
              <Link href="/my/raffles" className={pathname === '/my/raffles' ? 'text-primary' : ''}>
                <Ticket className="w-4 h-4 inline mr-1" />
                내 응모내역
              </Link>
              <Link href="/coupons" className={pathname === '/coupons' ? 'text-primary' : ''}>
                <Percent className="w-4 h-4 inline mr-1" />
                쿠폰
              </Link>
            </>
          )}
        </div>

        <div className="flex items-center gap-2">
          {isLoggedIn ? (
            <>
              <Link href="/admin">
                <Button variant="outline" size="sm">
                  <User className="w-4 h-4 mr-1" />
                  관리자
                </Button>
              </Link>
              <Button variant="outline" size="sm" onClick={handleLogout}>
                <LogOut className="w-4 h-4 mr-1" />
                로그아웃
              </Button>
            </>
          ) : (
            <>
              <Link href="/login">
                <Button variant="outline" size="sm">로그인</Button>
              </Link>
              <Link href="/signup">
                <Button size="sm">회원가입</Button>
              </Link>
            </>
          )}
        </div>
      </div>
    </nav>
  );
}
