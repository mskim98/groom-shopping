'use client';

import { useEffect, useState } from 'react';
import { useRouter } from 'next/navigation';
import { couponApi, getAccessToken } from '@/lib/api';
import { Button } from '@/components/ui/button';
import { Card, CardContent } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { Label } from '@/components/ui/label';
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from '@/components/ui/select';
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/components/ui/table';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogTrigger } from '@/components/ui/dialog';
import { AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription, AlertDialogFooter, AlertDialogHeader, AlertDialogTitle } from '@/components/ui/alert-dialog';
import { Plus, Pencil, Trash2, Send } from 'lucide-react';
import { toast } from 'sonner';

interface Coupon {
  id: string;
  name: string;
  discountType: string;
  discountValue: number;
  expiresAt?: string;
}

export default function AdminCouponsPage() {
  const router = useRouter();
  const [coupons, setCoupons] = useState<Coupon[]>([]);
  const [isCreateOpen, setIsCreateOpen] = useState(false);
  const [editingCoupon, setEditingCoupon] = useState<Coupon | null>(null);
  const [deleteCoupon, setDeleteCoupon] = useState<Coupon | null>(null);
  const [issueDialogOpen, setIssueDialogOpen] = useState(false);
  const [issueCouponId, setIssueCouponId] = useState<string>('');
  const [issueUserId, setIssueUserId] = useState('');
  
  const [formData, setFormData] = useState({
    name: '',
    discountType: 'PERCENTAGE',
    discountValue: 0,
    expiresAt: '',
  });

  useEffect(() => {
    if (!getAccessToken()) {
      toast.error('로그인이 필요합니다.');
      router.push('/login');
      return;
    }
    loadCoupons();
  }, [router]);

  const loadCoupons = async () => {
    try {
      const response = await couponApi.getCoupons(0, 100);
      setCoupons(response.content);
    } catch (error) {
      toast.error('쿠폰 목록을 불러오는데 실패했습니다.');
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await couponApi.createCoupon(formData);
      toast.success('쿠폰이 등록되었습니다.');
      setIsCreateOpen(false);
      resetForm();
      loadCoupons();
    } catch (error) {
      toast.error('쿠폰 등록에 실패했습니다.');
    }
  };

  const handleUpdate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!editingCoupon) return;
    
    try {
      await couponApi.updateCoupon(editingCoupon.id, formData);
      toast.success('쿠폰이 수정되었습니다.');
      setEditingCoupon(null);
      resetForm();
      loadCoupons();
    } catch (error) {
      toast.error('쿠폰 수정에 실패했습니다.');
    }
  };

  const handleDelete = async () => {
    if (!deleteCoupon) return;
    
    try {
      await couponApi.deleteCoupon(deleteCoupon.id);
      toast.success('쿠폰이 삭제되었습니다.');
      setDeleteCoupon(null);
      loadCoupons();
    } catch (error) {
      toast.error('쿠폰 삭제에 실패했습니다.');
    }
  };

  const handleIssueCoupon = async (e: React.FormEvent) => {
    e.preventDefault();
    try {
      await couponApi.issueCoupon(issueCouponId, issueUserId);
      toast.success('쿠폰이 발급되었습니다.');
      setIssueDialogOpen(false);
      setIssueUserId('');
    } catch (error) {
      toast.error('쿠폰 발급에 실패했습니다.');
    }
  };

  const openEdit = (coupon: Coupon) => {
    setEditingCoupon(coupon);
    setFormData({
      name: coupon.name,
      discountType: coupon.discountType,
      discountValue: coupon.discountValue,
      expiresAt: coupon.expiresAt || '',
    });
  };

  const openIssue = (couponId: string) => {
    setIssueCouponId(couponId);
    setIssueDialogOpen(true);
  };

  const resetForm = () => {
    setFormData({
      name: '',
      discountType: 'PERCENTAGE',
      discountValue: 0,
      expiresAt: '',
    });
  };

  return (
    <div className="container mx-auto px-4 py-8">
      <div className="flex justify-between items-center mb-6">
        <h2>쿠폰 관리</h2>
        <Dialog open={isCreateOpen} onOpenChange={setIsCreateOpen}>
          <DialogTrigger asChild>
            <Button>
              <Plus className="w-4 h-4 mr-2" />
              쿠폰 등록
            </Button>
          </DialogTrigger>
          <DialogContent>
            <DialogHeader>
              <DialogTitle>새 쿠폰 등록</DialogTitle>
            </DialogHeader>
            <form onSubmit={handleCreate} className="space-y-4">
              <div>
                <Label htmlFor="name">쿠폰명</Label>
                <Input
                  id="name"
                  value={formData.name}
                  onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                  required
                />
              </div>
              <div>
                <Label htmlFor="discountType">할인 타입</Label>
                <Select
                  value={formData.discountType}
                  onValueChange={(value) => setFormData({ ...formData, discountType: value })}
                >
                  <SelectTrigger>
                    <SelectValue />
                  </SelectTrigger>
                  <SelectContent>
                    <SelectItem value="PERCENTAGE">퍼센트 할인</SelectItem>
                    <SelectItem value="FIXED">정액 할인</SelectItem>
                  </SelectContent>
                </Select>
              </div>
              <div>
                <Label htmlFor="discountValue">
                  할인 값 {formData.discountType === 'PERCENTAGE' ? '(%)' : '(원)'}
                </Label>
                <Input
                  id="discountValue"
                  type="number"
                  value={formData.discountValue}
                  onChange={(e) => setFormData({ ...formData, discountValue: parseInt(e.target.value) })}
                  required
                />
              </div>
              <div>
                <Label htmlFor="expiresAt">만료일</Label>
                <Input
                  id="expiresAt"
                  type="datetime-local"
                  value={formData.expiresAt}
                  onChange={(e) => setFormData({ ...formData, expiresAt: e.target.value })}
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
                <TableHead>쿠폰명</TableHead>
                <TableHead>할인 타입</TableHead>
                <TableHead>할인 값</TableHead>
                <TableHead>만료일</TableHead>
                <TableHead>작업</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {coupons.map((coupon) => (
                <TableRow key={coupon.id}>
                  <TableCell>{coupon.name}</TableCell>
                  <TableCell>
                    {coupon.discountType === 'PERCENTAGE' ? '퍼센트 할인' : '정액 할인'}
                  </TableCell>
                  <TableCell>
                    {coupon.discountType === 'PERCENTAGE'
                      ? `${coupon.discountValue}%`
                      : `${coupon.discountValue.toLocaleString()}원`}
                  </TableCell>
                  <TableCell>
                    {coupon.expiresAt ? new Date(coupon.expiresAt).toLocaleDateString() : '-'}
                  </TableCell>
                  <TableCell>
                    <div className="flex gap-2">
                      <Button size="sm" variant="outline" onClick={() => openIssue(coupon.id)}>
                        <Send className="w-4 h-4" />
                      </Button>
                      <Button size="sm" variant="outline" onClick={() => openEdit(coupon)}>
                        <Pencil className="w-4 h-4" />
                      </Button>
                      <Button size="sm" variant="destructive" onClick={() => setDeleteCoupon(coupon)}>
                        <Trash2 className="w-4 h-4" />
                      </Button>
                    </div>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </CardContent>
      </Card>

      {/* Edit Dialog */}
      <Dialog open={!!editingCoupon} onOpenChange={(open) => !open && setEditingCoupon(null)}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>쿠폰 수정</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleUpdate} className="space-y-4">
            <div>
              <Label htmlFor="edit-name">쿠폰명</Label>
              <Input
                id="edit-name"
                value={formData.name}
                onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                required
              />
            </div>
            <div>
              <Label htmlFor="edit-discountType">할인 타입</Label>
              <Select
                value={formData.discountType}
                onValueChange={(value) => setFormData({ ...formData, discountType: value })}
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="PERCENTAGE">퍼센트 할인</SelectItem>
                  <SelectItem value="FIXED">정액 할인</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div>
              <Label htmlFor="edit-discountValue">
                할인 값 {formData.discountType === 'PERCENTAGE' ? '(%)' : '(원)'}
              </Label>
              <Input
                id="edit-discountValue"
                type="number"
                value={formData.discountValue}
                onChange={(e) => setFormData({ ...formData, discountValue: parseInt(e.target.value) })}
                required
              />
            </div>
            <div>
              <Label htmlFor="edit-expiresAt">만료일</Label>
              <Input
                id="edit-expiresAt"
                type="datetime-local"
                value={formData.expiresAt}
                onChange={(e) => setFormData({ ...formData, expiresAt: e.target.value })}
              />
            </div>
            <Button type="submit" className="w-full">수정</Button>
          </form>
        </DialogContent>
      </Dialog>

      {/* Issue Dialog */}
      <Dialog open={issueDialogOpen} onOpenChange={setIssueDialogOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>쿠폰 발급</DialogTitle>
          </DialogHeader>
          <form onSubmit={handleIssueCoupon} className="space-y-4">
            <div>
              <Label htmlFor="userId">사용자 ID</Label>
              <Input
                id="userId"
                value={issueUserId}
                onChange={(e) => setIssueUserId(e.target.value)}
                placeholder="사용자 ID를 입력하세요"
                required
              />
            </div>
            <Button type="submit" className="w-full">발급</Button>
          </form>
        </DialogContent>
      </Dialog>

      {/* Delete Confirmation */}
      <AlertDialog open={!!deleteCoupon} onOpenChange={(open) => !open && setDeleteCoupon(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>쿠폰 삭제</AlertDialogTitle>
            <AlertDialogDescription>
              정말로 이 쿠폰을 삭제하시겠습니까? 이 작업은 되돌릴 수 없습니다.
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
