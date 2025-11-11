# SSE 연결 프론트엔드 구현 가이드

## 📋 개요

SSE(Server-Sent Events) 연결은 **프론트엔드(웹페이지)에서 반드시 구현**해야 합니다.
백엔드는 SSE 엔드포인트만 제공하며, 실제 연결은 클라이언트(브라우저)에서 생성합니다.

---

## ⚠️ 중요: 웹페이지에서 구현 필요

**SSE 연결은 자동으로 생성되지 않습니다!**

- ❌ 백엔드에서 자동으로 연결 생성 ❌
- ✅ **프론트엔드에서 JavaScript로 연결 생성** ✅

---

## 🔧 구현 방법

### 1. 로그인 후 SSE 연결 생성

**필수 구현 단계:**

```javascript
// 1. 로그인 API 호출
async function login(email, password) {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json'
    },
    body: JSON.stringify({ email, password })
  });
  
  const data = await response.json();
  const token = data.accessToken;
  
  // 2. 로그인 성공 후 SSE 연결 생성 (중요!)
  connectSSE(token);
  
  return token;
}
```

### 2. SSE 연결 함수 구현

```javascript
let eventSource = null;

function connectSSE(token) {
  // 기존 연결이 있으면 종료
  if (eventSource) {
    eventSource.close();
  }
  
  // SSE 연결 생성
  // ⚠️ EventSource는 Authorization 헤더를 직접 지원하지 않으므로
  // URL에 토큰을 포함하거나, 별도 인증 방식 사용 필요
  
  // 방법 1: URL에 토큰 포함 (간단하지만 보안상 권장하지 않음)
  eventSource = new EventSource(
    `http://localhost:8080/api/v1/notification/stream?token=${token}`
  );
  
  // 방법 2: fetch API로 스트림 연결 (권장)
  connectSSEWithFetch(token);
}

// 방법 2: fetch API 사용 (권장)
async function connectSSEWithFetch(token) {
  try {
    const response = await fetch('/api/v1/notification/stream', {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'text/event-stream'
      }
    });
    
    if (!response.ok) {
      throw new Error('SSE 연결 실패');
    }
    
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    
    // 스트림 읽기
    while (true) {
      const { done, value } = await reader.read();
      
      if (done) {
        console.log('SSE 연결 종료');
        break;
      }
      
      // SSE 이벤트 파싱
      const chunk = decoder.decode(value);
      parseSSEEvent(chunk);
    }
  } catch (error) {
    console.error('SSE 연결 오류:', error);
    // 재연결 시도
    setTimeout(() => connectSSEWithFetch(token), 5000);
  }
}

// SSE 이벤트 파싱
function parseSSEEvent(chunk) {
  const lines = chunk.split('\n');
  let eventType = 'message';
  let data = '';
  
  for (const line of lines) {
    if (line.startsWith('event:')) {
      eventType = line.substring(6).trim();
    } else if (line.startsWith('data:')) {
      data = line.substring(5).trim();
    }
  }
  
  if (data && eventType === 'notification') {
    handleNotification(JSON.parse(data));
  }
}
```

### 3. 실시간 알림 처리

```javascript
function handleNotification(notification) {
  console.log('새 알림 수신:', notification);
  
  // 1. 알림 배지 업데이트
  updateNotificationBadge();
  
  // 2. 토스트 알림 표시
  showNotificationToast(notification);
  
  // 3. 알림 목록이 열려있으면 새 알림 추가
  if (isNotificationModalOpen()) {
    addNotificationToList(notification);
  }
}

function showNotificationToast(notification) {
  // 화면 우측 상단에 토스트 표시
  const toast = document.createElement('div');
  toast.className = 'notification-toast';
  toast.innerHTML = `
    <div class="toast-icon">🔔</div>
    <div class="toast-message">${notification.message}</div>
    <button onclick="this.parentElement.remove()">×</button>
  `;
  
  document.body.appendChild(toast);
  
  // 5초 후 자동 제거
  setTimeout(() => {
    toast.style.opacity = '0';
    setTimeout(() => toast.remove(), 300);
  }, 5000);
}
```

### 4. 연결 종료 처리

```javascript
// 페이지를 떠날 때 연결 종료
window.addEventListener('beforeunload', () => {
  if (eventSource) {
    eventSource.close();
  }
});

// 로그아웃 시 연결 종료
function logout() {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
  // 로그아웃 처리...
}
```

---

## 🎯 완전한 구현 예시

### HTML 구조

```html
<!DOCTYPE html>
<html>
<head>
  <title>알림 시스템</title>
  <style>
    .notification-icon {
      position: relative;
      cursor: pointer;
    }
    .notification-badge {
      position: absolute;
      top: -5px;
      right: -5px;
      background: red;
      color: white;
      border-radius: 50%;
      padding: 2px 6px;
      font-size: 12px;
    }
    .notification-toast {
      position: fixed;
      top: 20px;
      right: 20px;
      background: white;
      border: 1px solid #ccc;
      border-radius: 8px;
      padding: 15px;
      box-shadow: 0 2px 10px rgba(0,0,0,0.1);
      z-index: 1000;
    }
  </style>
</head>
<body>
  <!-- 알림 아이콘 -->
  <div class="notification-icon" onclick="showNotifications()">
    🔔
    <span id="notification-badge" class="notification-badge" style="display: none;">0</span>
  </div>
  
  <!-- 알림 목록 모달 -->
  <div id="notification-modal" style="display: none;">
    <!-- 알림 목록 -->
  </div>
  
  <script src="notification.js"></script>
</body>
</html>
```

### JavaScript 구현 (notification.js)

```javascript
// 전역 변수
let eventSource = null;
let token = null;

// 페이지 로드 시 실행
document.addEventListener('DOMContentLoaded', () => {
  // 로그인 상태 확인
  token = localStorage.getItem('accessToken');
  
  if (token) {
    // SSE 연결 생성
    connectSSE(token);
    // 읽지 않은 알림 개수 조회
    loadUnreadNotifications();
  }
});

// 로그인 함수
async function login(email, password) {
  try {
    const response = await fetch('/api/auth/login', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ email, password })
    });
    
    if (!response.ok) {
      throw new Error('로그인 실패');
    }
    
    const data = await response.json();
    token = data.accessToken;
    
    // 토큰 저장
    localStorage.setItem('accessToken', token);
    
    // SSE 연결 생성 (중요!)
    connectSSE(token);
    
    // 읽지 않은 알림 개수 조회
    loadUnreadNotifications();
    
    return token;
  } catch (error) {
    console.error('로그인 오류:', error);
    throw error;
  }
}

// SSE 연결 생성
function connectSSE(token) {
  // 기존 연결 종료
  if (eventSource) {
    eventSource.close();
  }
  
  // EventSource로 연결 (간단한 방법)
  // ⚠️ 주의: EventSource는 Authorization 헤더를 지원하지 않음
  // 백엔드에서 쿼리 파라미터로 토큰을 받도록 수정 필요
  
  // 또는 fetch API 사용 (권장)
  connectSSEWithFetch(token);
}

// fetch API로 SSE 연결 (권장)
async function connectSSEWithFetch(token) {
  try {
    const response = await fetch('/api/v1/notification/stream', {
      headers: {
        'Authorization': `Bearer ${token}`,
        'Accept': 'text/event-stream'
      }
    });
    
    if (!response.ok) {
      throw new Error('SSE 연결 실패');
    }
    
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    
    console.log('SSE 연결 성공');
    
    // 스트림 읽기
    while (true) {
      const { done, value } = await reader.read();
      
      if (done) {
        console.log('SSE 연결 종료');
        // 재연결 시도
        setTimeout(() => connectSSEWithFetch(token), 5000);
        break;
      }
      
      // 데이터 파싱
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\n');
      buffer = lines.pop(); // 마지막 불완전한 라인 보관
      
      let eventType = 'message';
      let data = '';
      
      for (const line of lines) {
        if (line.startsWith('event:')) {
          eventType = line.substring(6).trim();
        } else if (line.startsWith('data:')) {
          data = line.substring(5).trim();
        } else if (line === '') {
          // 빈 줄 = 이벤트 구분자
          if (data && eventType === 'notification') {
            handleNotification(data);
            data = '';
          }
        }
      }
    }
  } catch (error) {
    console.error('SSE 연결 오류:', error);
    // 재연결 시도
    setTimeout(() => connectSSEWithFetch(token), 5000);
  }
}

// 알림 처리
function handleNotification(message) {
  console.log('새 알림:', message);
  
  // 알림 배지 업데이트
  updateNotificationBadge();
  
  // 토스트 알림 표시
  showNotificationToast(message);
  
  // 알림 목록이 열려있으면 새 알림 추가
  if (document.getElementById('notification-modal').style.display === 'block') {
    loadNotifications();
  }
}

// 읽지 않은 알림 개수 조회
async function loadUnreadNotifications() {
  try {
    const response = await fetch('/api/notifications/unread', {
      headers: {
        'Authorization': `Bearer ${token}`
      }
    });
    
    if (!response.ok) {
      throw new Error('알림 조회 실패');
    }
    
    const notifications = await response.json();
    updateNotificationBadge(notifications.length);
  } catch (error) {
    console.error('알림 조회 오류:', error);
  }
}

// 알림 배지 업데이트
function updateNotificationBadge(count) {
  const badge = document.getElementById('notification-badge');
  if (count > 0) {
    badge.textContent = count;
    badge.style.display = 'block';
  } else {
    badge.style.display = 'none';
  }
}

// 토스트 알림 표시
function showNotificationToast(message) {
  const toast = document.createElement('div');
  toast.className = 'notification-toast';
  toast.innerHTML = `
    <div style="display: flex; align-items: center; gap: 10px;">
      <div>🔔</div>
      <div>${message}</div>
      <button onclick="this.parentElement.parentElement.remove()" style="margin-left: auto;">×</button>
    </div>
  `;
  
  document.body.appendChild(toast);
  
  // 5초 후 자동 제거
  setTimeout(() => {
    toast.style.opacity = '0';
    toast.style.transition = 'opacity 0.3s';
    setTimeout(() => toast.remove(), 300);
  }, 5000);
}

// 페이지 종료 시 연결 종료
window.addEventListener('beforeunload', () => {
  if (eventSource) {
    eventSource.close();
  }
});
```

---

## ⚠️ 주의사항

### 1. EventSource의 Authorization 헤더 제한

**문제:**
- `EventSource` API는 `Authorization` 헤더를 직접 지원하지 않음
- 기본적으로는 쿠키나 URL 파라미터로 인증해야 함

**해결 방법:**

**방법 1: URL에 토큰 포함 (보안상 권장하지 않음)**
```javascript
eventSource = new EventSource(
  `/api/v1/notification/stream?token=${token}`
);
```

**방법 2: fetch API 사용 (권장)**
```javascript
// 위의 connectSSEWithFetch 함수 참고
```

**방법 3: 백엔드에서 쿠키 사용**
```javascript
// 로그인 시 쿠키에 토큰 저장
// SSE 연결 시 쿠키 자동 전송
eventSource = new EventSource('/api/v1/notification/stream');
```

### 2. 재연결 처리

SSE 연결이 끊어질 수 있으므로 재연결 로직 필요:

```javascript
function connectSSEWithRetry(token, retryCount = 0) {
  const maxRetries = 5;
  const retryDelay = Math.min(1000 * Math.pow(2, retryCount), 30000);
  
  connectSSEWithFetch(token).catch(() => {
    if (retryCount < maxRetries) {
      setTimeout(() => {
        connectSSEWithRetry(token, retryCount + 1);
      }, retryDelay);
    }
  });
}
```

### 3. 메모리 누수 방지

연결 종료 시 리소스 정리:

```javascript
function disconnectSSE() {
  if (eventSource) {
    eventSource.close();
    eventSource = null;
  }
  if (reader) {
    reader.cancel();
    reader = null;
  }
}
```

---

## 📝 요약

**SSE 연결은 프론트엔드에서 반드시 구현해야 합니다:**

1. ✅ **로그인 후 SSE 연결 생성** (JavaScript로 구현)
2. ✅ **실시간 알림 수신 처리** (이벤트 리스너)
3. ✅ **연결 종료 처리** (페이지 종료 시)
4. ✅ **재연결 로직** (연결 끊김 시)

**핵심 메시지:**
> "SSE 연결은 백엔드에서 자동으로 생성되지 않습니다. 
> 프론트엔드에서 JavaScript로 EventSource 또는 fetch API를 사용하여 
> 명시적으로 연결을 생성하고 관리해야 합니다."


