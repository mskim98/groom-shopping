'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { raffleApi } from '@/lib/api';
import { Card, CardContent, CardFooter } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Gift, Calendar, Users } from 'lucide-react';

interface Raffle {
  id: string;
  title: string;
  description: string;
  status: string;
  winnersCount: number;
  maxEntriesPerUser: number;
  entryStartAt: string;
  entryEndAt: string;
  raffleDrawAt: string;
  userEntered?: boolean;
}

const statusLabels: Record<string, { label: string; variant: any }> = {
  DRAFT: { label: '초안', variant: 'secondary' },
  READY: { label: '준비중', variant: 'outline' },
  ACTIVE: { label: '진행중', variant: 'default' },
  CLOSED: { label: '마감', variant: 'secondary' },
  DRAWN: { label: '추첨완료', variant: 'destructive' },
  CANCELLED: { label: '취소됨', variant: 'destructive' },
};

export default function RafflesPage() {
  const router = useRouter();
  const [raffles, setRaffles] = useState<Raffle[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadRaffles();
  }, []);

  const loadRaffles = async () => {
    try {
      const response = await raffleApi.getRaffles(0, 20);
      console.log('Raffles response:', response);
      const raffleList = response.content || response || [];
      console.log('Filtered raffles:', raffleList);
      setRaffles(raffleList);
    } catch (error) {
      console.error('Failed to load raffles:', error);
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <Skeleton className="h-8 w-48 mb-6" />
        <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-6">
          {Array.from({ length: 6 }).map((_, i) => (
            <Skeleton key={i} className="h-64 w-full" />
          ))}
        </div>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="flex justify-between items-center mb-6">
        <h2>추첨 이벤트</h2>
      </div>

      <div className="grid md:grid-cols-2 lg:grid-cols-3 gap-6">
        {raffles.filter(r => r.status !== 'DRAFT').map((raffle) => {
          const statusInfo = statusLabels[raffle.status] || statusLabels.DRAFT;
          
          return (
            <Card key={raffle.id} className="hover:shadow-lg transition-shadow">
              <CardContent className="p-6">
                <div className="flex justify-between items-start mb-4">
                  <Gift className="w-8 h-8 text-primary" />
                  <Badge variant={statusInfo.variant}>{statusInfo.label}</Badge>
                </div>
                
                <h3 className="mb-2">{raffle.title}</h3>
                <p className="text-muted-foreground mb-4 line-clamp-2">
                  {raffle.description}
                </p>
                
                <div className="space-y-2 text-muted-foreground">
                  <div className="flex items-center gap-2">
                    <Users className="w-4 h-4" />
                    <span>당첨자 {raffle.winnersCount}명</span>
                  </div>
                  <div className="flex items-center gap-2">
                    <Calendar className="w-4 h-4" />
                    <span>
                      {new Date(raffle.entryStartAt).toLocaleDateString()} ~{' '}
                      {new Date(raffle.entryEndAt).toLocaleDateString()}
                    </span>
                  </div>
                </div>
              </CardContent>
              
              <CardFooter className="p-6 pt-0">
                {raffle.status === 'ACTIVE' && !raffle.userEntered && (
                  <Link href={`/raffles/${raffle.id}`} className="w-full">
                    <Button className="w-full">응모하기</Button>
                  </Link>
                )}
                {raffle.status === 'ACTIVE' && raffle.userEntered && (
                  <Button className="w-full" disabled>
                    응모 완료
                  </Button>
                )}
                {raffle.status === 'READY' && (
                  <Button className="w-full" variant="outline" disabled>
                    응모 대기중
                  </Button>
                )}
                {raffle.status === 'DRAWN' && (
                  <Link href={`/raffles/${raffle.id}/result`} className="w-full">
                    <Button className="w-full" variant="outline">
                      결과 보기
                    </Button>
                  </Link>
                )}
                {(raffle.status === 'CLOSED' || raffle.status === 'CANCELLED') && (
                  <Button className="w-full" variant="outline" disabled>
                    {raffle.status === 'CLOSED' ? '마감됨' : '취소됨'}
                  </Button>
                )}
              </CardFooter>
            </Card>
          );
        })}
      </div>

      {raffles.filter(r => r.status !== 'DRAFT').length === 0 && (
        <div className="text-center py-12">
          <Gift className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
          <p className="text-muted-foreground">진행 중인 추첨 이벤트가 없습니다.</p>
        </div>
      )}
    </div>
  );
}
