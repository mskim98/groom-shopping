'use client';

import { useEffect, useState } from 'react';
import { useParams, useRouter } from 'next/navigation';
import { raffleApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Skeleton } from '@/components/ui/skeleton';
import { ArrowLeft, Trash2, Bell, Search } from 'lucide-react';
import { toast } from 'sonner';

interface RaffleDetail {
  raffleId: number;
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
  participantCount?: number;
}

interface Participant {
  userId: number;
  userName: string;
  userEmail: string;
  createdAt: string;
}

interface ResultData {
  drawAt: string;
  winnersCount: number;
  winners: Array<{
    userId: number;
    rank: number;
    userName: string;
    userEmail: string;
  }>;
}

export default function AdminRaffleDetailPage() {
  const params = useParams();
  const router = useRouter();
  const [raffle, setRaffle] = useState<RaffleDetail | null>(null);
  const [participants, setParticipants] = useState<Participant[]>([]);
  const [result, setResult] = useState<ResultData | null>(null);
  const [loading, setLoading] = useState(true);
  const [searchTerm, setSearchTerm] = useState('');
  const [showDelete, setShowDelete] = useState(false);

  useEffect(() => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }
    loadData();
  }, [params.id, router]);

  const loadData = async () => {
    try {
      const raffleData = await raffleApi.getRaffle(params.id as number);
      setRaffle(raffleData);

      const participantsData = await raffleApi.getParticipants(params.id as number, 0, 100);
      setParticipants(participantsData.content || []);

      if (raffleData.status === 'DRAWN') {
        try {
          const resultData = await raffleApi.getResult(params.id as number);
          setResult(resultData);
        } catch (error) {
          console.error('Failed to load result:', error);
        }
      }
    } catch (error) {
      toast.error('데이터를 불러오는데 실패했습니다.');
    } finally {
      setLoading(false);
    }
  };

  const handleDelete = async () => {
    try {
      await raffleApi.deleteRaffle(params.id as number);
      toast.success('추첨이 삭제되었습니다.');
      router.push('/admin/raffles');
    } catch (error) {
      toast.error('삭제에 실패했습니다.');
    }
  };

  const handleNotifyWinners = () => {
    toast.success('당첨자에게 알림이 전송되었습니다.');
  };

  const filteredParticipants = participants.filter(
    (p) =>
      p.userName.toLowerCase().includes(searchTerm.toLowerCase()) ||
      p.userEmail.toLowerCase().includes(searchTerm.toLowerCase())
  );

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

  return (
    <div className="container mx-auto px-4 py-8">
      <Button variant="ghost" onClick={() => router.push('/admin/raffles')} className="mb-6">
        <ArrowLeft className="w-4 h-4 mr-2" />
        목록으로 돌아가기
      </Button>

      <div className="grid md:grid-cols-3 gap-6 mb-6">
        <Card>
          <CardHeader>
            <CardTitle>이벤트 기본 정보</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <div>
              <p className="text-muted-foreground">추첨명</p>
              <p>{raffle.title}</p>
            </div>
            <div>
              <p className="text-muted-foreground">상태</p>
              <Badge>
                {raffle.status === 'DRAFT' && '초안'}
                {raffle.status === 'READY' && '준비완료'}
                {raffle.status === 'ACTIVE' && '활성'}
                {raffle.status === 'CLOSED' && '종료'}
                {raffle.status === 'DRAWN' && '추첨완료'}
                {raffle.status === 'CANCELLED' && '취소'}
              </Badge>
            </div>
            <div>
              <p className="text-muted-foreground">설명</p>
              <p>{raffle.description}</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>일정 정보</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <div>
              <p className="text-muted-foreground">응모 시작일</p>
              <p>{new Date(raffle.entryStartAt).toLocaleString()}</p>
            </div>
            <div>
              <p className="text-muted-foreground">응모 종료일</p>
              <p>{new Date(raffle.entryEndAt).toLocaleString()}</p>
            </div>
            <div>
              <p className="text-muted-foreground">추첨일</p>
              <p>{new Date(raffle.raffleDrawAt).toLocaleString()}</p>
            </div>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>통계</CardTitle>
          </CardHeader>
          <CardContent className="space-y-2">
            <div>
              <p className="text-muted-foreground">최대 당첨자 수</p>
              <p>{raffle.winnersCount}명</p>
            </div>
            <div>
              <p className="text-muted-foreground">1인당 최대 응모 수</p>
              <p>{raffle.maxEntriesPerUser}회</p>
            </div>
            <div>
              <p className="text-muted-foreground">총 참여자 수</p>
              <p>{participants.length}명</p>
            </div>
          </CardContent>
        </Card>
      </div>

      <Card className="mb-6">
        <CardHeader>
          <CardTitle>참여자 목록</CardTitle>
        </CardHeader>
        <CardContent>
          <div className="mb-4">
            <div className="relative">
              <Search className="absolute left-3 top-1/2 transform -translate-y-1/2 w-4 h-4 text-muted-foreground" />
              <Input
                placeholder="이름 또는 이메일로 검색..."
                value={searchTerm}
                onChange={(e) => setSearchTerm(e.target.value)}
                className="pl-10"
              />
            </div>
          </div>
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>번호</TableHead>
                <TableHead>이름</TableHead>
                <TableHead>이메일</TableHead>
                <TableHead>응모일</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {filteredParticipants.map((participant, index) => (
                <TableRow key={participant.userId}>
                  <TableCell>{index + 1}</TableCell>
                  <TableCell>{participant.userName}</TableCell>
                  <TableCell>{participant.userEmail}</TableCell>
                  <TableCell>{new Date(participant.createdAt).toLocaleString()}</TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
          {filteredParticipants.length === 0 && (
            <div className="text-center py-8 text-muted-foreground">
              참여자가 없습니다.
            </div>
          )}
        </CardContent>
      </Card>

      {result && raffle.status === 'DRAWN' && (
        <Card className="mb-6">
          <CardHeader>
            <CardTitle>당첨 결과</CardTitle>
          </CardHeader>
          <CardContent>
            <div className="mb-4">
              <p className="text-muted-foreground">
                추첨 일시: {new Date(result.drawAt).toLocaleString()}
              </p>
              <p className="text-muted-foreground">당첨자 수: {result.winnersCount}명</p>
            </div>
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
                    <TableCell>{winner.rank}</TableCell>
                    <TableCell>{winner.userName}</TableCell>
                    <TableCell>{winner.userEmail}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
           {/* <div className="mt-4">
              <Button onClick={handleNotifyWinners}>
                <Bell className="w-4 h-4 mr-2" />
                당첨자에게 알림 발송
              </Button>
            </div>*/}
          </CardContent>
        </Card>
      )}

      <div className="flex gap-2">
        <Button variant="destructive" onClick={() => setShowDelete(true)}>
          <Trash2 className="w-4 h-4 mr-2" />
          삭제
        </Button>
      </div>

      {/* Delete Confirmation */}
      <AlertDialog open={showDelete} onOpenChange={setShowDelete}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>추첨 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              정말로 이 추첨을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleDelete}>삭제</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
