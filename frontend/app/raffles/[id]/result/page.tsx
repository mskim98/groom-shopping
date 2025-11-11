'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { raffleApi } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { Trophy, ArrowLeft, Calendar } from 'lucide-react';
import { toast } from 'sonner';

interface ResultData {
  raffleId: string;
  raffleTitle: string;
  drawAt: string;
  winnersCount: number;
  winners: Array<{
    userId: string;
    userName: string;
    userEmail: string;
  }>;
}

export default function RaffleResultPage() {
  const params = useParams();
  const router = useRouter();
  const [result, setResult] = useState<ResultData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    loadResult();
  }, [params.id]);

  const loadResult = async () => {
    try {
      const data = await raffleApi.getResult(params.id as string);
      setResult(data);
    } catch (error) {
      toast.error('추첨 결과를 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
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

  if (!result) {
    return (
      <div className="container mx-auto px-4 py-8">
        <p>추첨 결과를 찾을 수 없습니다.</p>
      </div>
    );
  }

  return (
    <div className="container mx-auto px-4 py-8 max-w-4xl">
      <Button variant="ghost" onClick={() => router.back()} className="mb-6">
        <ArrowLeft className="w-4 h-4 mr-2" />
        뒤로가기
      </Button>

      <Card>
        <CardContent className="p-8">
          <div className="text-center mb-8">
            <Trophy className="w-16 h-16 mx-auto mb-4 text-yellow-500" />
            <h2 className="mb-2">{result.raffleTitle}</h2>
            <Badge variant="outline" className="mb-4">추첨 완료</Badge>
            <div className="flex items-center justify-center gap-2 text-muted-foreground">
              <Calendar className="w-4 h-4" />
              <span>추첨일: {new Date(result.drawAt).toLocaleString()}</span>
            </div>
          </div>

          <div className="mb-6">
            <h3 className="mb-4">당첨자 명단 ({result.winnersCount}명)</h3>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>순번</TableHead>
                  <TableHead>이름</TableHead>
                  <TableHead>이메일</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {result.winners.map((winner, index) => (
                  <TableRow key={winner.userId}>
                    <TableCell>{index + 1}</TableCell>
                    <TableCell>{winner.userName}</TableCell>
                    <TableCell>{winner.userEmail}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>

          <div className="flex gap-2 justify-center">
            <Button variant="outline" onClick={() => router.push('/raffles')}>
              다른 이벤트 보기
            </Button>
            <Button onClick={() => router.push('/my/raffles')}>
              내 응모 내역
            </Button>
          </div>
        </CardContent>
      </Card>
    </div>
  );
}
