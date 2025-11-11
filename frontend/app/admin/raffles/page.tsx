'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { raffleApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Plus, Play, Eye } from 'lucide-react';
import { toast } from 'sonner';

interface Raffle {
  id: string;
  title: string;
  description: string;
  status: string;
  raffleProductId?: string;
  winnerProductId?: string;
  winnersCount: number;
  maxEntriesPerUser: number;
  entryStartAt: string;
  entryEndAt: string;
  raffleDrawAt: string;
}

export default function AdminRafflesPage() {
  const router = useRouter();
  const [raffles, setRaffles] = useState<Raffle[]>([]);
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [executeRaffle, setExecuteRaffle] = useState<Raffle | null>(null);
  
  const [formData, setFormData] = useState({
    title: '',
    description: '',
    raffleProductId: '',
    winnerProductId: '',
    winnersCount: 1,
    maxEntriesPerUser: 1,
    entryStartAt: '',
    entryEndAt: '',
    raffleDrawAt: '',
  });

  useEffect(() => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }
    loadRaffles();
  }, [router]);

  const loadRaffles = async () => {
    try {
      const response = await raffleApi.getRaffles(0, 100);
      setRaffles(response.content);
    } catch (error) {
      toast.error('추첨 목록을 불러오는데 실패했습니다.');
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await raffleApi.createRaffle(formData);
      toast.success('추첨이 등록되었습니다.');
      setIsCreateOpen(false);
      resetForm();
      loadRaffles();
    } catch (error) {
      toast.error('추첨 등록에 실패했습니다.');
    }
  };

  const handleExecute = async () => {
    if (!executeRaffle) return;
    
    try {
      await raffleApi.executeRaffle(executeRaffle.id);
      toast.success('추첨이 완료되었습니다.');
      setExecuteRaffle(null);
      router.push(`/admin/raffles/${executeRaffle.id}`);
    } catch (error) {
      toast.error('추첨 실행에 실패했습니다.');
    }
  };

  const resetForm = () => {
    setFormData({
      title: '',
      description: '',
      raffleProductId: '',
      winnerProductId: '',
      winnersCount: 1,
      maxEntriesPerUser: 1,
      entryStartAt: '',
      entryEndAt: '',
      raffleDrawAt: '',
    });
  };

  const getStatusBadge = (status: string) => {
    const variants: Record<string, any> = {
      DRAFT: 'secondary',
      READY: 'outline',
      ACTIVE: 'default',
      CLOSED: 'secondary',
      DRAWN: 'destructive',
      CANCELLED: 'destructive',
    };

    const labels: Record<string, string> = {
      DRAFT: '초안',
      READY: '준비완료',
      ACTIVE: '활성',
      CLOSED: '종료',
      DRAWN: '추첨완료',
      CANCELLED: '취소',
    };

    return <Badge variant={variants[status] || 'default'}>{labels[status] || status}</Badge>;
  };

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="flex justify-between items-center mb-6">
        <h2>추첨 관리</h2>
        <Dialog open={isCreateOpen} onOpenChange={setIsCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="w-4 h-4 mr-2" />
              추첨 등록
            </Button>
          </DialogTrigger>
          <DialogContent className="max-w-2xl max-h-[90vh] overflow-y-auto">
            <DialogHeader>
              <DialogTitle>새 추첨 등록</DialogTitle>
            </DialogHeader>
            <form onSubmit={handleCreate} className="space-y-4">
              <div>
                <Label htmlFor="title">추첨명</Label>
                <Input
                  id="title"
                  value={formData.title}
                  onChange={(e) => setFormData({ ...formData, title: e.target.value })}
                  required
                />
              </div>
              <div>
                <Label htmlFor="description">추첨 설명</Label>
                <Textarea
                  id="description"
                  value={formData.description}
                  onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                  required
                />
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <Label htmlFor="raffleProductId">추첨 티켓 상품 ID</Label>
                  <Input
                    id="raffleProductId"
                    value={formData.raffleProductId}
                    onChange={(e) => setFormData({ ...formData, raffleProductId: e.target.value })}
                  />
                </div>
                <div>
                  <Label htmlFor="winnerProductId">증정 상품 ID</Label>
                  <Input
                    id="winnerProductId"
                    value={formData.winnerProductId}
                    onChange={(e) => setFormData({ ...formData, winnerProductId: e.target.value })}
                  />
                </div>
              </div>
              <div className="grid grid-cols-2 gap-4">
                <div>
                  <Label htmlFor="winnersCount">최대 당첨자 수</Label>
                  <Input
                    id="winnersCount"
                    type="number"
                    min="1"
                    value={formData.winnersCount}
                    onChange={(e) => setFormData({ ...formData, winnersCount: parseInt(e.target.value) })}
                    required
                  />
                </div>
                <div>
                  <Label htmlFor="maxEntriesPerUser">명당 최대 응모 수</Label>
                  <Input
                    id="maxEntriesPerUser"
                    type="number"
                    min="1"
                    value={formData.maxEntriesPerUser}
                    onChange={(e) => setFormData({ ...formData, maxEntriesPerUser: parseInt(e.target.value) })}
                    required
                  />
                </div>
              </div>
              <div>
                <Label htmlFor="entryStartAt">응모 시작일</Label>
                <Input
                  id="entryStartAt"
                  type="datetime-local"
                  value={formData.entryStartAt}
                  onChange={(e) => setFormData({ ...formData, entryStartAt: e.target.value })}
                  required
                />
              </div>
              <div>
                <Label htmlFor="entryEndAt">응모 종료일</Label>
                <Input
                  id="entryEndAt"
                  type="datetime-local"
                  value={formData.entryEndAt}
                  onChange={(e) => setFormData({ ...formData, entryEndAt: e.target.value })}
                  required
                />
              </div>
              <div>
                <Label htmlFor="raffleDrawAt">추첨일</Label>
                <Input
                  id="raffleDrawAt"
                  type="datetime-local"
                  value={formData.raffleDrawAt}
                  onChange={(e) => setFormData({ ...formData, raffleDrawAt: e.target.value })}
                  required
                />
              </div>
              <Button type="submit" className="w-full">등록</Button>
            </form>
          </DialogContent>
        </Dialog>
      </div>

      <Card>
        <CardContent className="p-0">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>추첨명</TableHead>
                <TableHead>상태</TableHead>
                <TableHead>당첨자 수</TableHead>
                <TableHead>응모 기간</TableHead>
                <TableHead>추첨일</TableHead>
                <TableHead>작업</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {raffles.map((raffle) => (
                <TableRow key={raffle.id}>
                  <TableCell>{raffle.title}</TableCell>
                  <TableCell>{getStatusBadge(raffle.status)}</TableCell>
                  <TableCell>{raffle.winnersCount}명</TableCell>
                  <TableCell>
                    {new Date(raffle.entryStartAt).toLocaleDateString()} ~{' '}
                    {new Date(raffle.entryEndAt).toLocaleDateString()}
                  </TableCell>
                  <TableCell>{new Date(raffle.raffleDrawAt).toLocaleDateString()}</TableCell>
                  <TableCell>
                    <div className="flex gap-2">
                      <Link href={`/admin/raffles/${raffle.id}`}>
                        <Button size="sm" variant="outline">
                          <Eye className="w-4 h-4" />
                        </Button>
                      </Link>
                      {raffle.status === 'CLOSED' && (
                        <Button
                          size="sm"
                          onClick={() => setExecuteRaffle(raffle)}
                        >
                          <Play className="w-4 h-4" />
                        </Button>
                      )}
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Execute Confirmation */}
      <AlertDialog open={!!executeRaffle} onOpenChange={(open) => !open && setExecuteRaffle(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>추첨 실행</AlertDialogTitle>
            <AlertDialogDescription>
              정말로 추첨을 실행하시겠습니까? 실행 후에는 취소할 수 없습니다.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>취소</AlertDialogCancel>
            <AlertDialogAction onClick={handleExecute}>확인</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
