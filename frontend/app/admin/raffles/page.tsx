'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import Link from 'next/link';
import { raffleApi, productApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Textarea } from '@/components/ui/textarea';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { Badge } from '@/components/ui/badge';
import { Plus, Play, Eye, Check } from 'lucide-react';
import { toast } from 'sonner';

interface Raffle {
  raffleId: string | number;
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

interface Product {
  productId: string;
  name: string;
  description: string;
  price: number;
  stock: number;
  category: 'GENERAL' | 'TICKET' | 'RAFFLE';
}

export default function AdminRafflesPage() {
  const router = useRouter();
  const [raffles, setRaffles] = useState<Raffle[]>([]);
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [executeRaffle, setExecuteRaffle] = useState<Raffle | null>(null);
  const [products, setProducts] = useState<Product[]>([]);
  const [ticketProducts, setTicketProducts] = useState<Product[]>([]);
  const [raffleProducts, setRaffleProducts] = useState<Product[]>([]);

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
    const token = getAccessToken();
    if (!token) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }
    console.log('Access token exists:', !!token);
    loadRaffles();
    loadProducts();
  }, [router]);

  const loadRaffles = async () => {
    try {
      const response = await raffleApi.getRaffles(0, 100);
      console.log('Raffles response:', response);
      console.log('Response type:', typeof response);
      console.log('Response.content:', response.content);
      const raffleList = response.content || [];
      console.log('Final raffle list:', raffleList);
      setRaffles(raffleList);
    } catch (error) {
      console.error('Failed to load raffles:', error);
      toast.error('추첨 목록을 불러오는데 실패했습니다.');
    }
  };

  // 이미 등록된 추첨 상품 ID 목록
  const registeredRaffleProductIds = raffles.map(r => String(r.raffleProductId)).filter(Boolean);

  const loadProducts = async () => {
    try {
      const response = await productApi.getProducts(0, 100);
      setProducts(response.content);
      setTicketProducts(response.content.filter((p: Product) => p.category === 'TICKET'));
      setRaffleProducts(response.content.filter((p: Product) => p.category === 'RAFFLE'));
    } catch (error) {
      toast.error('상품 목록을 불러오는데 실패했습니다.');
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      // datetime-local 입력값을 ISO 8601 형식으로 변환 (초 단위 포함)
      const formatDateTime = (dateTimeStr: string) => {
        if (!dateTimeStr) return dateTimeStr;
        // 2025-11-07T21:05 -> 2025-11-07T21:05:00
          if (/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}$/.test(dateTimeStr)) {
              return `${dateTimeStr}:00`;
          }

          return dateTimeStr;
      };

      const payload = {
        ...formData,
        entryStartAt: formatDateTime(formData.entryStartAt),
        entryEndAt: formatDateTime(formData.entryEndAt),
        raffleDrawAt: formatDateTime(formData.raffleDrawAt),
      };

      await raffleApi.createRaffle(payload);
      toast.success('추첨이 등록되었습니다.');
      setIsCreateOpen(false);
      resetForm();
      loadRaffles();
    } catch (error) {
        toast.error(error.message || '추첨 등록에 실패했습니다.');
    }
  };

  const handleExecute = async () => {
    if (!executeRaffle) return;
    
    try {
      await raffleApi.executeRaffle (executeRaffle.raffleId);
      toast.success('추첨이 완료되었습니다.');
      setExecuteRaffle(null);
      router.push(`/admin/raffles/${executeRaffle.raffleId}`);
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


  const statusOptions = [
    { value: 'DRAFT', label: '초안' },
    { value: 'READY', label: '준비중' },
    { value: 'ACTIVE', label: '활성' },
    { value: 'CLOSED', label: '종료' },
    { value: 'DRAWN', label: '추첨완료' },
    { value: 'CANCELLED', label: '취소' },
  ];

  const getStatusBadge = (status: string) => {
    const variants: Record<string, any> = {
      DRAFT: 'secondary',
      READY: 'secondary',
      ACTIVE: 'default',
      CLOSED: 'secondary',
      DRAWN: 'destructive',
      CANCELLED: 'destructive',
    };

    const labels: Record<string, string> = {
      DRAFT: '초안',
      READY: '준비중',
      ACTIVE: '활성',
      CLOSED: '종료',
      DRAWN: '추첨완료',
      CANCELLED: '취소',
    };

    return <Badge variant={variants[status] || 'default'}>{labels[status] || status}</Badge>;
  };

  const getAvailableStatusOptions = (currentStatus: string) => {
    const transitions: Record<string, string[]> = {
      DRAFT: ['READY', 'CANCELLED'],
      READY: ['DRAFT', 'ACTIVE', 'CANCELLED'],
      ACTIVE: ['CLOSED', 'CANCELLED'],
      CLOSED: ['CANCELLED'],
      DRAWN: [],
      CANCELLED: [],
    };

    const availableStatuses = transitions[currentStatus] || [];
    return statusOptions.filter(opt => availableStatuses.includes(opt.value));
  };

  const handleStatusChange = async (raffleId: string | number, newStatus: string) => {
    try {
      const raffleIdStr = String(raffleId);
      console.log('Updating raffle status:', raffleIdStr, newStatus);

      await raffleApi.updateRaffleStatus(raffleIdStr, newStatus);
      toast.success('추첨 상태가 변경되었습니다.');
      loadRaffles();
    } catch (error) {
      console.error('Status change error:', error);
      toast.error('상태 변경에 실패했습니다.');
    }
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
              <div className="space-y-4">
                <div>
                  <Label className="mb-2 block">추첨 티켓 상품 (TICKET 카테고리)</Label>
                  <div className="grid grid-cols-2 gap-3 max-h-48 overflow-y-auto border rounded-md p-3">
                    {ticketProducts.map((product) => {
                      const isRegistered = registeredRaffleProductIds.includes(product.productId);
                      return (
                        <Card
                          key={product.productId}
                          className={`transition-all ${
                            isRegistered
                              ? 'cursor-not-allowed opacity-50 border-destructive/50'
                              : 'cursor-pointer hover:border-primary/50'
                          } ${
                            formData.raffleProductId === product.productId
                              ? 'border-primary border-2 bg-primary/5'
                              : ''
                          }`}
                          onClick={() => {
                            if (!isRegistered) {
                              setFormData({ ...formData, raffleProductId: product.productId });
                            } else {
                              toast.error('이미 등록된 추첨 상품입니다.');
                            }
                          }}
                        >
                          <CardHeader className="p-3">
                            <div className="flex items-start justify-between">
                              <CardTitle className="text-sm">{product.name}</CardTitle>
                              {formData.raffleProductId === product.productId && (
                                <Check className="w-4 h-4 text-primary" />
                              )}
                              {isRegistered && (
                                <span className="text-xs bg-destructive/20 text-destructive px-2 py-1 rounded">
                                  사용중
                                </span>
                              )}
                            </div>
                          </CardHeader>
                          <CardContent className="p-3 pt-0">
                            <p className="text-xs text-muted-foreground line-clamp-2">
                              {product.description}
                            </p>
                            <p className="text-xs mt-2">가격: {product.price.toLocaleString()}원</p>
                          </CardContent>
                        </Card>
                      );
                    })}
                    {ticketProducts.length === 0 && (
                      <p className="text-sm text-muted-foreground col-span-2 text-center py-4">
                        TICKET 카테고리 상품이 없습니다.
                      </p>
                    )}
                  </div>
                </div>

                <div>
                  <Label className="mb-2 block">증정 상품 (RAFFLE 카테고리)</Label>
                  <div className="grid grid-cols-2 gap-3 max-h-48 overflow-y-auto border rounded-md p-3">
                    {raffleProducts.map((product) => (
                      <Card
                        key={product.productId}
                        className={`cursor-pointer transition-all ${
                          formData.winnerProductId === product.productId
                            ? 'border-primary border-2 bg-primary/5'
                            : 'hover:border-primary/50'
                        }`}
                        onClick={() => setFormData({ ...formData, winnerProductId: product.productId })}
                      >
                        <CardHeader className="p-3">
                          <div className="flex items-start justify-between">
                            <CardTitle className="text-sm">{product.name}</CardTitle>
                            {formData.winnerProductId === product.productId && (
                              <Check className="w-4 h-4 text-primary" />
                            )}
                          </div>
                        </CardHeader>
                        <CardContent className="p-3 pt-0">
                          <p className="text-xs text-muted-foreground line-clamp-2">
                            {product.description}
                          </p>
                          <p className="text-xs mt-2">가격: {product.price.toLocaleString()}원</p>
                        </CardContent>
                      </Card>
                    ))}
                    {raffleProducts.length === 0 && (
                      <p className="text-sm text-muted-foreground col-span-2 text-center py-4">
                        RAFFLE 카테고리 상품이 없습니다.
                      </p>
                    )}
                  </div>
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
                <TableRow key={raffle.raffleId}>
                  <TableCell>{raffle.title}</TableCell>
                  <TableCell>
                    {getAvailableStatusOptions(raffle.status).length > 0 ? (
                      <select
                        value={raffle.status}
                        onChange={(e) => handleStatusChange(raffle.raffleId, e.target.value)}
                        className="px-2 py-1 border rounded text-sm bg-white cursor-pointer"
                      >
                        {statusOptions.map((option) => (
                          <option key={option.value} value={option.value} disabled={!getAvailableStatusOptions(raffle.status).map(o => o.value).includes(option.value) && option.value !== raffle.status}>
                            {option.label}
                          </option>
                        ))}
                      </select>
                    ) : (
                      getStatusBadge(raffle.status)
                    )}
                  </TableCell>
                  <TableCell>{raffle.winnersCount}명</TableCell>
                  <TableCell>
                    {new Date(raffle.entryStartAt).toLocaleDateString()} ~{' '}
                    {new Date(raffle.entryEndAt).toLocaleDateString()}
                  </TableCell>
                  <TableCell>{new Date(raffle.raffleDrawAt).toLocaleDateString()}</TableCell>
                  <TableCell>
                    <div className="flex gap-2">
                      <Link href={`/admin/raffles/${raffle.raffleId}`}>
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
