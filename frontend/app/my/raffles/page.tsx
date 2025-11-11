'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { getAccessToken } from '@/lib/api';
import { Card, CardContent } from '@/components/ui/card';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Skeleton } from '@/components/ui/skeleton';
import { Ticket, Trophy } from 'lucide-react';

interface MyRaffleEntry {
  raffleId: string;
  raffleTitle: string;
  entryCount: number;
  entryDate: string;
  status: string;
  isWinner?: boolean;
}

export default function MyRafflesPage() {
  const router = useRouter();
  const [entries, setEntries] = useState<MyRaffleEntry[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!getAccessToken()) {
      router.push('/login');
      return;
    }
    loadEntries();
  }, [router]);

  const loadEntries = async () => {
    try {
      // Mock data - 실제 API 엔드포인트로 교체
      setEntries([
        {
          raffleId: '1',
          raffleTitle: '여름 맞이 경품 추첨',
          entryCount: 3,
          entryDate: '2024-11-01',
          status: 'DRAWN',
          isWinner: false,
        },
        {
          raffleId: '2',
          raffleTitle: '신규 회원 감사 이벤트',
          entryCount: 1,
          entryDate: '2024-11-05',
          status: 'ACTIVE',
          isWinner: false,
        },
      ]);
    } catch (error) {
      console.error('Failed to load entries:', error);
    } finally {
      setLoading(false);
    }
  };

  const getStatusBadge = (status: string, isWinner?: boolean) => {
    if (status === 'DRAWN') {
      if (isWinner) {
        return <Badge className="bg-yellow-500">당첨</Badge>;
      }
      return <Badge variant="secondary">미당첨</Badge>;
    }

    const variants: Record<string, any> = {
      ACTIVE: 'default',
      CLOSED: 'secondary',
      READY: 'outline',
    };

    const labels: Record<string, string> = {
      ACTIVE: '진행중',
      CLOSED: '마감',
      READY: '대기중',
    };

    return <Badge variant={variants[status] || 'default'}>{labels[status] || status}</Badge>;
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
      <h2 className="mb-6">내 응모 내역</h2>

      <div className="space-y-4">
        {entries.map((entry) => (
          <Card key={entry.raffleId}>
            <CardContent className="p-6">
              <div className="flex justify-between items-start mb-4">
                <div className="flex-1">
                  <h3 className="mb-2">{entry.raffleTitle}</h3>
                  <div className="flex items-center gap-4 text-muted-foreground">
                    <div className="flex items-center gap-1">
                      <Ticket className="w-4 h-4" />
                      <span>응모 {entry.entryCount}회</span>
                    </div>
                    <span>응모일: {new Date(entry.entryDate).toLocaleDateString()}</span>
                  </div>
                </div>
                <div className="flex flex-col gap-2 items-end">
                  {getStatusBadge(entry.status, entry.isWinner)}
                  {entry.isWinner && (
                    <div className="flex items-center gap-1 text-yellow-600">
                      <Trophy className="w-4 h-4" />
                      <span>축하합니다!</span>
                    </div>
                  )}
                </div>
              </div>

              <div className="flex gap-2">
                <Link href={`/raffles/${entry.raffleId}`}>
                  <Button variant="outline" size="sm">
                    상세 보기
                  </Button>
                </Link>
                {entry.status === 'DRAWN' && (
                  <Link href={`/raffles/${entry.raffleId}/result`}>
                    <Button variant="outline" size="sm">
                      결과 확인
                    </Button>
                  </Link>
                )}
              </div>
            </CardContent>
          </Card>
        ))}
      </div>

      {entries.length === 0 && (
        <Card>
          <CardContent className="p-12 text-center">
            <Ticket className="w-12 h-12 mx-auto mb-4 text-muted-foreground" />
            <p className="text-muted-foreground mb-4">응모한 추첨이 없습니다.</p>
            <Link href="/raffles">
              <Button>추첨 이벤트 보기</Button>
            </Link>
          </CardContent>
        </Card>
      )}
    </div>
  );
}
