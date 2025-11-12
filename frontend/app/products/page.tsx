'use client';

import { useEffect, useState } from 'react';
import Link from 'next/link';
import { productApi } from '@/lib/api';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { ChevronLeft, ChevronRight } from 'lucide-react';
import { ImageWithFallback } from '@/components/figma/ImageWithFallback';

interface Product {
  productId: string;
  name: string;
  description: string;
  price: number;
  imageUrl?: string;
  stock: number;
}

export default function ProductsPage() {
  const [products, setProducts] = useState<Product[]>([]);
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);

  const loadProducts = async (currentPage: number) => {
    setLoading(true);
    try {
      const response = await productApi.getProducts(currentPage, 20, 'id,desc');
      setProducts(response.content);
      setTotalPages(response.totalPages);
    } catch (error) {
      console.error('Failed to load products:', error);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadProducts(page);
  }, [page]);

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <h2 className="mb-6">전체 상품</h2>
        <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-4 gap-6">
          {Array.from({ length: 8 }).map((_, i) => (
            <Card key={i}>
              <Skeleton className="h-48 w-full" />
              <CardContent className="p-4">
                <Skeleton className="h-4 w-3/4 mb-2" />
                <Skeleton className="h-4 w-1/2" />
              </CardContent>
            </Card>
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <h2 className="mb-6">전체 상품</h2>
      
      <div className="grid grid-cols-1 md:grid-cols-3 lg:grid-cols-4 gap-6">
        {products.map((product) => (
          <Link key={product.productId} href={`/products/${product.productId}`}>
            <Card className="h-full hover:shadow-lg transition-shadow cursor-pointer">
              <div className="aspect-square relative overflow-hidden">
                <ImageWithFallback
                  src={product.imageUrl || '/placeholder-product.jpg'}
                  alt={product.name}
                  className="w-full h-full object-cover"
                />
              </div>
              <CardContent className="p-4">
                <h3 className="mb-2 line-clamp-2">{product.name}</h3>
                <p className="text-muted-foreground mb-2 line-clamp-2">
                  {product.description}
                </p>
                <p className="text-primary">{product.price.toLocaleString()}원</p>
              </CardContent>
              <CardFooter className="p-4 pt-0">
                <p className="text-muted-foreground">
                  재고: {product.stock}개
                </p>
              </CardFooter>
            </Card>
          </Link>
        ))}
      </div>

      {products.length === 0 && (
        <div className="text-center py-12">
          <p className="text-muted-foreground">등록된 상품이 없습니다.</p>
        </div>
      )}

      {totalPages > 1 && (
        <div className="flex justify-center items-center gap-2 mt-8">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            disabled={page === 0}
          >
            <ChevronLeft className="w-4 h-4" />
            이전
          </Button>
          <span className="text-muted-foreground">
            {page + 1} / {totalPages}
          </span>
          <Button
            variant="outline"
            size="sm"
            onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
            disabled={page >= totalPages - 1}
          >
            다음
            <ChevronRight className="w-4 h-4" />
          </Button>
        </div>
      )}
    </div>
  );
}
