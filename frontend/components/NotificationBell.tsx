'use client';

import { useEffect, useState, useRef, useCallback } from 'react';
import { Bell } from 'lucide-react';
import { Button } from './ui/button';
import { notificationApi, getAccessToken } from '@/lib/api';
import { NotificationList } from './NotificationList';
import { toast } from 'sonner';

interface Notification {
  id: string;
  title: string;
  message: string;
  read: boolean;
  createdAt: string;
  type?: string;
}

export function NotificationBell() {
  const [unreadCount, setUnreadCount] = useState(0);
  const [notifications, setNotifications] = useState<Notification[]>([]);
  const [isOpen, setIsOpen] = useState(false);
  const [loading, setLoading] = useState(false);
  const eventSourceRef = useRef<EventSource | null>(null);
  const isOpenRef = useRef(false);

  // isOpen 상태를 ref로도 추적하여 최신 값을 참조할 수 있도록 함
  useEffect(() => {
    isOpenRef.current = isOpen;
  }, [isOpen]);

  const loadUnreadCount = useCallback(async () => {
    try {
      const unread = await notificationApi.getUnreadNotifications();
      setUnreadCount(unread?.length || 0);
    } catch (error: any) {
      // 로그인 페이지에서는 에러를 무시 (무한루프 방지)
      if (typeof window !== 'undefined' && window.location.pathname === '/login') {
        return;
      }
      console.error('Failed to load unread count:', error);
    }
  }, []);

  const loadNotifications = useCallback(async () => {
    setLoading(true);
    try {
      const unread = await notificationApi.getUnreadNotifications();
      setNotifications(unread || []);
      // 알림 목록을 로드할 때 배지 카운트도 함께 업데이트
      setUnreadCount(unread?.length || 0);
    } catch (error) {
      console.error('Failed to load notifications:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    if (!getAccessToken()) return;

    // 초기 읽지 않은 알림 개수 로드
    loadUnreadCount();

    // SSE 연결
    const eventSource = notificationApi.connectSSE(
      (data) => {
        // 새 알림 수신
        console.log('New notification received:', data);
        
        // 주문 관련 알림인지 확인 (주문한 사용자는 알림을 받지 않음)
        // 백엔드에서 이미 필터링되어야 하지만, 추가 안전장치
        const notificationType = data?.type || '';
        const notificationTitle = data?.title || '';
        
        // 주문 관련 알림이면 무시 (백엔드에서 처리되어야 함)
        // 프론트엔드에서는 추가 안전장치로만 사용
        if (notificationType === 'ORDER' || notificationTitle.includes('주문')) {
          console.log('Order notification ignored for order creator');
          return; // 주문한 사용자는 알림을 받지 않음
        }
        
        // 실시간 알림을 화면에 토스트로 표시
        const notificationMessage = data?.message || '';
        
        toast.info(notificationTitle, {
          description: notificationMessage,
          duration: 5000,
          action: {
            label: '확인',
            onClick: () => {
              if (!isOpenRef.current) {
                setIsOpen(true);
                loadNotifications();
              }
            },
          },
        });
        
        // 서버에서 최신 읽지 않은 알림 개수를 가져와서 배지 업데이트
        // 모달이 열려있으면 목록도 함께 업데이트
        if (isOpenRef.current) {
          loadNotifications(); // 이 함수가 배지 카운트도 업데이트함
        } else {
          // 모달이 닫혀있어도 배지를 즉시 업데이트
          loadUnreadCount();
        }
      },
      (error) => {
        console.error('SSE error:', error);
        // 재연결 시도
        setTimeout(() => {
          if (getAccessToken()) {
            loadUnreadCount();
          }
        }, 5000);
      }
    );

    eventSourceRef.current = eventSource;

    // 주기적으로 읽지 않은 알림 개수 확인 (30초마다)
    // SSE가 제대로 작동하지 않을 경우를 대비한 폴링
    const pollInterval = setInterval(() => {
      if (getAccessToken()) {
        loadUnreadCount();
      }
    }, 30000); // 30초마다 확인

    return () => {
      if (eventSourceRef.current) {
        eventSourceRef.current.close();
        eventSourceRef.current = null;
      }
      clearInterval(pollInterval);
    };
  }, [loadUnreadCount, loadNotifications]);

  const handleBellClick = () => {
    if (!isOpen) {
      loadNotifications(); // 읽지 않은 알림 로드
    }
    setIsOpen(!isOpen);
  };

  const handleNotificationRead = async (id: string) => {
    try {
      await notificationApi.markAsRead(id);
      // 읽음 처리된 알림을 목록에서 제거 (읽지 않은 알림만 표시하므로)
      setNotifications(prev => prev.filter(n => n.id !== id));
      setUnreadCount(prev => Math.max(0, prev - 1));
    } catch (error) {
      console.error('Failed to mark as read:', error);
    }
  };

  const handleMarkAllAsRead = async () => {
    try {
      await notificationApi.markAllAsRead();
      // 모든 알림을 목록에서 제거 (읽지 않은 알림만 표시하므로)
      setNotifications([]);
      setUnreadCount(0);
    } catch (error) {
      console.error('Failed to mark all as read:', error);
    }
  };


  if (!getAccessToken()) {
    return null;
  }

  return (
    <div className="relative">
      <Button
        variant="ghost"
        size="sm"
        onClick={handleBellClick}
        className="relative"
      >
        <Bell className="w-5 h-5" />
        {unreadCount > 0 && (
          <span className="absolute -top-1 -right-1 bg-red-500 text-white text-xs rounded-full w-5 h-5 flex items-center justify-center">
            {unreadCount > 99 ? '99+' : unreadCount}
          </span>
        )}
      </Button>

      {isOpen && (
        <NotificationList
          notifications={notifications}
          loading={loading}
          onClose={() => setIsOpen(false)}
          onMarkAsRead={handleNotificationRead}
          onMarkAllAsRead={handleMarkAllAsRead}
        />
      )}
    </div>
  );
}

