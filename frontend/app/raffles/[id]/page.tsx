'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { raffleApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Gift, Calendar, Users, Ticket, ArrowLeft } from 'lucide-react';
import { toast } from 'sonner';

interface RaffleDetail {
  id: string;
  title: string;
  description: string;
  status: string;
  raffleProductName?: string;
  winnerProductName?: string;
  winnersCount: number;
  maxEntriesPerUser: number;
  entryStartAt: string;
  entryEndAt: string;
  raffleDrawAt: string;
}

export default function RaffleDetailPage() {
  const params = useParams();
  const router = useRouter();
  const [raffle, setRaffle] = useState<RaffleDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [entries, setEntries] = useState(1);
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }

    loadRaffle();
  }, [params.id, router]);

  const loadRaffle = async () => {
    try {
      const data = await raffleApi.getRaffle(params.id as string);
      setRaffle(data);
    } catch (error) {
      toast.error('추첨 정보를 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    
    if (!raffle || raffle.status !== 'ACTIVE') {
      toast.error('현재 응모할 수 없는 상태입니다.');
      return;
    }

    setSubmitting(true);
    try {
      await raffleApi.enterRaffle(params.id as string, entries);
      toast.success('응모가 완료되었습니다!');
      router.push('/raffles');
    } catch (error) {
      toast.error('응모에 실패했습니다.');
    } finally {
      setSubmitting(false);
    }
  };

  if (loading) {
    return (
      <div className="container mx-auto px-4 py-8">
        <Skeleton className="h-8 w-32 mb-6" />
        <Skeleton className="h-96 w-full" />
      </div>
    );
  }

  if (!raffle) {
    return (
      <div className="container mx-auto px-4 py-8">
        <p>추첨 정보를 찾을 수 없습니다.</p>
      </div>
    );
  }

  const isActive = raffle.status === 'ACTIVE';
  const isReady = raffle.status === 'READY';
  const isClosed = raffle.status === 'CLOSED';
  const isDrawn = raffle.status === 'DRAWN';

  return (
    <div className="container mx-auto px-4 py-8 max-w-3xl">
      <Button variant="ghost" onClick={() => router.back()} className="mb-6">
        <ArrowLeft className="w-4 h-4 mr-2" />
        뒤로가기
      </Button>

      <Card>
        <CardContent className="p-8">
          <div className="flex justify-between items-start mb-6">
            <div className="flex items-center gap-3">
              <Gift className="w-10 h-10 text-primary" />
              <div>
                <h2 className="mb-1">{raffle.title}</h2>
                <Badge>
                  {raffle.status === 'ACTIVE' && '진행중'}
                  {raffle.status === 'READY' && '준비중'}
                  {raffle.status === 'CLOSED' && '마감'}
                  {raffle.status === 'DRAWN' && '추첨완료'}
                </Badge>
              </div>
            </div>
          </div>

          <p className="text-muted-foreground mb-6">{raffle.description}</p>

          <div className="grid md:grid-cols-2 gap-6 mb-6">
            <div className="space-y-4">
              <div className="flex items-start gap-3">
                <Ticket className="w-5 h-5 text-primary mt-0.5" />
                <div>
                  <p className="text-muted-foreground">응모 티켓</p>
                  <p>{raffle.raffleProductName || '미지정'}</p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <Gift className="w-5 h-5 text-primary mt-0.5" />
                <div>
                  <p className="text-muted-foreground">경품</p>
                  <p>{raffle.winnerProductName || '미지정'}</p>
                </div>
              </div>
            </div>

            <div className="space-y-4">
              <div className="flex items-start gap-3">
                <Users className="w-5 h-5 text-primary mt-0.5" />
                <div>
                  <p className="text-muted-foreground">당첨자 수</p>
                  <p>{raffle.winnersCount}명</p>
                </div>
              </div>
              <div className="flex items-start gap-3">
                <Ticket className="w-5 h-5 text-primary mt-0.5" />
                <div>
                  <p className="text-muted-foreground">1인당 최대 응모 수</p>
                  <p>{raffle.maxEntriesPerUser}회</p>
                </div>
              </div>
            </div>
          </div>

          <div className="p-4 bg-muted rounded-lg mb-6">
            <div className="flex items-center gap-2 mb-2">
              <Calendar className="w-4 h-4" />
              <span className="text-muted-foreground">일정</span>
            </div>
            <div className="space-y-1">
              <p>응모 기간: {new Date(raffle.entryStartAt).toLocaleString()} ~ {new Date(raffle.entryEndAt).toLocaleString()}</p>
              <p>추첨일: {new Date(raffle.raffleDrawAt).toLocaleString()}</p>
            </div>
          </div>

          {isReady && (
            <div className="text-center py-8">
              <p className="text-muted-foreground">응모 시작까지 대기 중입니다.</p>
            </div>
          )}

          {isClosed && (
            <div className="text-center py-8">
              <p className="text-muted-foreground">추첨이 마감되었습니다.</p>
            </div>
          )}

          {isDrawn && (
            <div className="text-center py-8">
              <p className="text-muted-foreground mb-4">추첨이 완료되었습니다.</p>
              <Button onClick={() => router.push(`/raffles/${raffle.id}/result`)}>
                결과 보기
              </Button>
            </div>
          )}

          {isActive && (
            <form onSubmit={handleSubmit}>
              <div className="mb-4">
                <Label htmlFor="entries">응모 횟수</Label>
                <Input
                  id="entries"
                  type="number"
                  min="1"
                  max={raffle.maxEntriesPerUser}
                  value={entries}
                  onChange={(e) => setEntries(Math.max(1, Math.min(raffle.maxEntriesPerUser, parseInt(e.target.value) || 1)))}
                  className="w-32"
                />
                <p className="text-muted-foreground mt-1">
                  최대 {raffle.maxEntriesPerUser}회까지 응모 가능
                </p>
              </div>
              <Button type="submit" className="w-full" disabled={submitting}>
                {submitting ? '응모 중...' : '응모하기'}
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
