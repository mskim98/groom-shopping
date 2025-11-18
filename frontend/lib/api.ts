// Use /api/v1 for Next.js rewrites (development)
// This avoids CORS issues by proxying through Next.js server
// For Docker production, set NEXT_PUBLIC_API_URL to http://backend:8080/api/v1
const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || '/api/v1';

interface RequestOptions extends RequestInit {
  requireAuth?: boolean;
}

interface ApiResponse<T> {
  success: boolean;
  data: T;
  message?: string;
  error?: {
    code: string;
    message: string;
  };
}

export async function apiRequest<T>(
  endpoint: string,
  options: RequestOptions = {}
): Promise<T | null> {
  const { requireAuth = false, headers = {}, ...restOptions } = options;

  const token = getAccessToken();

    const requestHeaders: Record<string, string> = {
        ...(restOptions.body instanceof FormData ? {} : { 'Content-Type': 'application/json' }),
        ...headers as Record<string, string>,
    };

  if (requireAuth && token) {
    requestHeaders['Authorization'] = `Bearer ${token}`;
  } else if (requireAuth && !token) {
    console.warn('Auth required but no token found');
  }

  // /api/로 시작하는 경로는 API_BASE_URL을 사용하지 않음
  const url = endpoint.startsWith('/api/') ? endpoint : `${API_BASE_URL}${endpoint}`;

  console.log('API Request:', {
    url,
    method: restOptions.method || 'GET',
    requireAuth,
    hasToken: !!token,
    hasAuthHeader: !!requestHeaders['Authorization'],
    body: restOptions.body,
  });

  const response = await fetch(url, {
    ...restOptions,
    headers: requestHeaders,
  });

  if (response.status === 401 && requireAuth) {
    // Try to refresh token
    const refreshed = await refreshAccessToken();
    if (refreshed) {
      // Retry the request
      const newToken = getAccessToken();
      if (newToken) {
        requestHeaders['Authorization'] = `Bearer ${newToken}`;
      }
      const retryResponse = await fetch(`${API_BASE_URL}${endpoint}`, {
        ...restOptions,
        headers: requestHeaders,
      });

      if (!retryResponse.ok) {
        const errorData = await retryResponse.json().catch(() => ({}));
        const errorMessage = errorData?.message || errorData?.error?.message || `API Error: ${retryResponse.status}`;
        throw new Error(errorMessage);
      }

      const retryResult: ApiResponse<T> = await retryResponse.json();
      return retryResult.data;
    } else {
      // Redirect to login (단, 이미 로그인 페이지에 있으면 리다이렉트하지 않음)
      if (typeof window !== 'undefined' && window.location.pathname !== '/login') {
        window.location.href = '/login';
      }
      throw new Error('인증이 필요합니다. 다시 로그인해주세요.');
    }
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}));
    console.error('API Error Response:', {
      status: response.status,
      statusText: response.statusText,
      errorData,
      url,
    });
    const errorMessage = errorData?.message || errorData?.error?.message || `요청 실패: ${response.status}`;
    throw new Error(errorMessage);
  }

    // 204 / 205 No Content 처리: JSON 파싱하지 않음
    if (response.status === 204 || response.status === 205) {
        return null;
    }

  const result = await response.json();

  // ApiResponse 형식 (success 필드가 있는 경우)
  if (typeof result === 'object' && result !== null && 'success' in result) {
    const apiResult: ApiResponse<T> = result;
    return apiResult.data;
  }

  // ApiResponse 래퍼 없이 직접 데이터를 반환하는 경우
  return result as T;
}

export function getAccessToken(): string | null {
  if (typeof window === 'undefined') return null;
  return localStorage.getItem('accessToken');
}

export function setAccessToken(token: string): void {
  localStorage.setItem('accessToken', token);
}

export function getRole(): string | null {
    if (typeof window === 'undefined') return null;
    const payload = parseJwt(getAccessToken() || '');
    return payload ? payload.role : null;
}



export function clearTokens(): void {
  localStorage.removeItem('accessToken');
}

function parseJwt(token: string) {
    try {
        const payload = token.split('.')[1];
        const base64 = payload.replace(/-/g, '+').replace(/_/g, '/');
        const jsonPayload = decodeURIComponent(
            atob(base64)
                .split('')
                .map((c) => '%' + ('00' + c.charCodeAt(0).toString(16)).slice(-2))
                .join('')
        );
        return JSON.parse(jsonPayload);
    } catch {
        return null;
    }
}

export async function refreshAccessToken(): Promise<boolean> {
  try {
    const response = await fetch(`${API_BASE_URL}/auth/refresh`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
      },
    });

    if (response.ok) {
      const data = await response.json();
      setAccessToken(data.accessToken);
      return true;
    }
    return false;
  } catch (error) {
    console.error('Token refresh failed:', error);
    return false;
  }
}

// Auth API
export const authApi = {
  login: (email: string, password: string) =>
    apiRequest<{ accessToken: string; refreshToken: string }>('/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    }),
  
  signup: (email: string, password: string, name: string) =>
    apiRequest<{ email: string; name: string }>('/auth/signup', {
      method: 'POST',
      body: JSON.stringify({ email, password, name }),
    }),
  
  logout: () =>
    apiRequest('/auth/logout', {
      method: 'POST',
      requireAuth: true,
    }),
};

// Product API
export const productApi = {
  getProducts: (page = 0, size = 20, sort = 'id,desc') =>
    apiRequest<{
      content: any[];
      totalElements: number;
      totalPages: number;
      number: number;
    }>(`/product?page=${page}&size=${size}&sort=${sort}`),
  
  getProduct: (id: string) =>
    apiRequest<any>(`/product/${id}`, {
      requireAuth: true,
    }),

    createProduct: (data: any) => {
        // 파일 포함 여부 확인
        const hasFile = data.imageFile instanceof File;

        let body: any;

        if (hasFile) {
            // multipart/form-data 생성
            const form = new FormData();
            form.append(
                "product",
                new Blob([JSON.stringify({
                    name: data.name,
                    description: data.description,
                    price: data.price,
                    stock: data.stock,
                    category: data.category
                })], { type: "application/json" })
            );
            form.append('image', data.imageFile); // 서버에서 MultipartFile image 로 받으면 됨

            body = form;
        } else {
            // 기존 JSON 그대로
            body = JSON.stringify(data);
        }

        return apiRequest('/product', {
            method: 'POST',
            body,
            requireAuth: true,
        });
    },

  
  updateProduct: (id: string, data: any) =>
    apiRequest(`/product/${id}`, {
      method: 'PATCH',
      body: JSON.stringify(data),
      requireAuth: true,
    }),
  
  deleteProduct: (id: string) =>
    apiRequest(`/product/${id}`, {
      method: 'DELETE',
      requireAuth: true,
    }),
};

// Cart API
export const cartApi = {
  getCart: () =>
    apiRequest<{ items: any[] }>('/cart', {
      requireAuth: true,
    }),
  
  addToCart: (productId: string, quantity: number) =>
    apiRequest('/cart/add', {
      method: 'POST',
      body: JSON.stringify({ productId, quantity }),
      requireAuth: true,
    }),
  
  removeFromCart: (productId: string, quantity: number) =>
    apiRequest('/cart/remove', {
      method: 'DELETE',
      body: JSON.stringify({ items: [{ productId, quantity }] }),
      requireAuth: true,
    }),

  removeMultipleFromCart: (items: Array<{ productId: string; quantity: number }>) =>
    apiRequest('/cart/remove', {
      method: 'DELETE',
      body: JSON.stringify({ items }),
      requireAuth: true,
    }),

  increaseQuantity: (productId: string) =>
    apiRequest('/cart/add', {
      method: 'POST',
      body: JSON.stringify({ productId, quantity: 1 }),
      requireAuth: true,
    }),

  decreaseQuantity: (productId: string) =>
    apiRequest('/cart/remove', {
      method: 'DELETE',
      body: JSON.stringify({ items: [{ productId, quantity: 1 }] }),
      requireAuth: true,
    }),
};

// Coupon API
export const couponApi = {
  getCoupons: (page = 0, size = 20) =>
    apiRequest<{
      content: any[];
      totalElements: number;
      totalPages: number;
    }>(`/coupon?page=${page}&size=${size}`, {
      requireAuth: true,
    }),
  
  getCoupon: (id: string) =>
    apiRequest<any>(`/coupon/${id}`, {
      requireAuth: true,
    }),
  
  createCoupon: (data: any) => {
    const { inactiveDate, ...body } = data;
  
    const query = inactiveDate
      ? `?date=${encodeURIComponent(inactiveDate)}`
      : '';
  
    return apiRequest(`/coupon${query}`, {
      method: 'POST',
      body: JSON.stringify(data),
      requireAuth: true,
    });
  },
  
  updateCoupon: (id: string, data: any) =>
    apiRequest(`/coupon/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
      requireAuth: true,
    }),
  
  deleteCoupon: (id: string) =>
    apiRequest(`/coupon/${id}`, {
      method: 'DELETE',
      requireAuth: true,
    }),
  
  issueCoupon: (couponId: string, userId: string) =>
    apiRequest(`/coupon/issue/${couponId}`, {
      method: 'POST',
      body: JSON.stringify({ userId }),
      requireAuth: true,
      headers: {
        'Request-Date': new Date().toISOString(),
      },
    }),

  getMyCoupons: () =>
    apiRequest<any[]>('/coupon/me', {
      requireAuth: true,
    }),
};

// Raffle API
export const raffleApi = {
  getRaffles: (page = 0, size = 20) =>
    apiRequest<{
      content: any[];
      totalElements: number;
      totalPages: number;
    }>(`/raffles?page=${page}&size=${size}`, {
      requireAuth: true,
    }),
  
  getRaffle: (id: string) =>
    apiRequest<any>(`/raffles/${id}`, {
      requireAuth: true,
    }),

  createRaffle: (data: any) =>
    apiRequest('/raffles', {
      method: 'POST',
      body: JSON.stringify(body),
      requireAuth: true,
    }),

  deleteRaffle: (id: string) =>
    apiRequest(`/raffles/${id}`, {
      method: 'DELETE',
      requireAuth: true,
    }),

  updateRaffle: (id: string, data: any) =>
    apiRequest(`/raffles/${id}`, {
      method: 'PUT',
      body: JSON.stringify(data),
      requireAuth: true,
    }),

  updateRaffleStatus: (id: string, status: string) =>
    apiRequest(`/raffles/${id}/status`, {
      method: 'PATCH',
      body: JSON.stringify({ status }),
      requireAuth: true,
    }),

  executeRaffle: (id: string) =>
    apiRequest(`/raffles/${id}/draws`, {
      method: 'POST',
      requireAuth: true,
    }),

  getParticipants: (id: string, page = 0, size = 20) =>
    apiRequest<{
      content: any[];
      totalElements: number;
    }>(`/raffles/${id}/participants?page=${page}&size=${size}`, {
      requireAuth: true,
    }),

  getResult: (id: string) =>
    apiRequest<any>(`/raffles/${id}/result`, {
      requireAuth: true,
    }),

  enterRaffle: (id: string, entries: number) =>
    apiRequest(`/raffles/${id}/enter`, {
      method: 'POST',
      body: JSON.stringify({ count: entries }),
      requireAuth: true,
    }),
};

// Order API
export const orderApi = {
  createOrder: (data: any) =>
    apiRequest('/order', {
      method: 'POST',
      body: JSON.stringify(data),
      requireAuth: true,
    }),

  getOrders: () =>
    apiRequest<any[]>('/order', {
      requireAuth: true,
    }),

  getOrder: (orderId: string) =>
    apiRequest<any>(`/order/${orderId}`, {
      requireAuth: true,
    }),
};

// Payment API
export const paymentApi = {
  getMyPayments: () =>
    apiRequest<any[]>('/payment/my', {
      requireAuth: true,
    }),

  getPayment: (id: string) =>
    apiRequest<any>(`/payment/${id}`, {
      requireAuth: true,
    }),

  // 테스트 결제 (Toss Payments 테스트 API)
  confirmTestPayment: (data: any) =>
    apiRequest('/payment/confirm/test', {
      method: 'POST',
      body: JSON.stringify(data),
      requireAuth: true,
    }),

  // 실제 결제 (Toss Payments 실제 API)
  confirmPayment: (data: any) =>
    apiRequest('/payment/confirm', {
      method: 'POST',
      body: JSON.stringify(data),
      requireAuth: true,
    }),
};

// Notification API
export const notificationApi = {
  // SSE 스트림 연결
  connectSSE: (onMessage: (data: any) => void, onError?: (error: Event) => void): EventSource | null => {
    if (typeof window === 'undefined') return null;
    
    const token = getAccessToken();
    if (!token) return null;

    const API_BASE_URL = process.env.NEXT_PUBLIC_API_URL || '/api/v1';
    // EventSource는 헤더를 직접 설정할 수 없으므로 쿼리 파라미터로 토큰 전달
    const url = `${API_BASE_URL}/notification/stream?token=${encodeURIComponent(token)}`;
    
    const eventSource = new EventSource(url, {
      withCredentials: true,
    });

    eventSource.onmessage = (event) => {
      try {
        const data = JSON.parse(event.data);
        onMessage(data);
      } catch (error) {
        console.error('Failed to parse SSE message:', error);
      }
    };

    eventSource.onerror = (error) => {
      console.error('SSE connection error:', error);
      if (onError) {
        onError(error);
      }
    };

    return eventSource;
  },

  // 읽지 않은 알림 가져오기
  getUnreadNotifications: () =>
    apiRequest<any[]>('/notification/unread', {
      requireAuth: true,
    }),

  // 알림 읽음 처리
  markAsRead: (id: string) =>
    apiRequest(`/notification/${id}/read`, {
      method: 'PATCH',
      requireAuth: true,
    }),

  // 전체 읽음 처리
  markAllAsRead: () =>
    apiRequest('/notification/read-all', {
      method: 'PATCH',
      requireAuth: true,
    }),
};
