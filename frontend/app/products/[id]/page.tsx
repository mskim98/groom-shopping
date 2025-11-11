'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { productApi, cartApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Card, CardContent } from '@/components/ui/card';
import { Skeleton } from '@/components/ui/skeleton';
import { ShoppingCart, ArrowLeft } from 'lucide-react';
import { toast } from 'sonner';
import { ImageWithFallback } from '@/components/figma/ImageWithFallback';

interface Product {
  id: string;
  name: string;
  description: string;
  price: number;
  imageUrl?: string;
  stock: number;
}

export default function ProductDetailPage() {
  const params = useParams();
  const router = useRouter();
  const [product, setProduct] = useState<Product | null>(null);
  const [loading, setLoading] = useState(true);
  const [quantity, setQuantity] = useState(1);
  const [addingToCart, setAddingToCart] = useState(false);

  useEffect(() => {
    const loadProduct = async () => {
      try {
        const data = await productApi.getProduct(params.id as string);
        setProduct(data);
      } catch (error) {
        toast.error('상품을 불러오는데 실패했습니다.');
      } finally {
        setLoading(false);
      }
    };

    loadProduct();
  }, [params.id]);

  const handleAddToCart = async () => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }

    setAddingToCart(true);
    try {
      await cartApi.addToCart(params.id as string, quantity);
      toast.success('장바구니에 추가되었습니다.');
    } catch (error) {
      toast.error('장바구니 추가에 실패했습니다.');
    } finally {
      setAddingToCart(false);
    }
  };

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <Skeleton className="h-8 w-32 mb-6" />
        <div className="grid md:grid-cols-2 gap-8">
          <Skeleton className="aspect-square w-full" />
          <div className="space-y-4">
            <Skeleton className="h-8 w-3/4" />
            <Skeleton className="h-24 w-full" />
            <Skeleton className="h-6 w-1/2" />
          </div>
        </div>
      </div>
    );
  }

  if (!product) {
    return (
      <div className="container mx-auto px-4 py-8">
        <p>상품을 찾을 수 없습니다.</p>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <Button variant="ghost" onClick={() => router.back()} className="mb-6">
        <ArrowLeft className="w-4 h-4 mr-2" />
        뒤로가기
      </Button>

      <div className="grid md:grid-cols-2 gap-8">
        <div>
          <Card>
            <CardContent className="p-0">
              <div className="aspect-square relative overflow-hidden">
                <ImageWithFallback
                  src={product.imageUrl || '/placeholder-product.jpg'}
                  alt={product.name}
                  className="w-full h-full object-cover"
                />
              </div>
            </CardContent>
          </Card>
        </div>

        <div>
          <h2 className="mb-4">{product.name}</h2>
          <p className="text-muted-foreground mb-6">{product.description}</p>
          
          <div className="mb-6">
            <p className="text-primary mb-2">{product.price.toLocaleString()}원</p>
            <p className="text-muted-foreground">재고: {product.stock}개</p>
          </div>

          <div className="space-y-4">
            <div>
              <Label htmlFor="quantity">수량</Label>
              <Input
                id="quantity"
                type="number"
                min="1"
                max={product.stock}
                value={quantity}
                onChange={(e) => setQuantity(Math.max(1, parseInt(e.target.value) || 1))}
                className="w-24"
              />
            </div>

            <div className="flex gap-2">
              <Button
                onClick={handleAddToCart}
                disabled={addingToCart || product.stock === 0}
                className="flex-1"
              >
                <ShoppingCart className="w-4 h-4 mr-2" />
                {product.stock === 0 ? '품절' : '장바구니 담기'}
              </Button>
              <Button
                variant="outline"
                disabled={product.stock === 0}
                onClick={() => {
                  handleAddToCart();
                  setTimeout(() => router.push('/cart'), 500);
                }}
              >
                바로 구매
              </Button>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
