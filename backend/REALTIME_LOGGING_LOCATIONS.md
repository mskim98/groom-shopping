# 실시간 로그 남기는 부분 정리

## 📍 주요 실시간 로그 위치

### 1. NotificationApplicationService.java

#### 알림 생성 및 전송 관련
- **54번 라인**: `[NOTIFICATION_SERVICE_START]` - 알림 서비스 시작
- **68번 라인**: `[NOTIFICATION_QUERY_USERS]` - 사용자 조회 (쿼리 시간 포함)
- **93번 라인**: `[NOTIFICATION_SERVICE_COMPLETE]` - 알림 서비스 완료 (총 처리 시간)
- **98번 라인**: `[NOTIFICATION_SERVICE_FAILED]` - 알림 서비스 실패

#### 배치 저장 관련
- **292번 라인**: `[NOTIFICATION_BATCH_START]` - 배치 저장 시작
- **303번 라인**: `[NOTIFICATION_BATCH_SAVED]` - 배치 저장 완료
- **311번 라인**: `[NOTIFICATION_SSE_SENT]` - SSE 전송 성공 (DEBUG 레벨)
- **314번 라인**: `[NOTIFICATION_SSE_FAILED]` - SSE 전송 실패
- **320번 라인**: `[NOTIFICATION_BATCH_COMPLETE]` - 배치 저장 완료

#### 개별 저장 관련
- **337번 라인**: `[NOTIFICATION_INDIVIDUAL_START]` - 개별 저장 시작
- **348번 라인**: `[NOTIFICATION_INDIVIDUAL_FAILED]` - 개별 저장 실패
- **359번 라인**: `[NOTIFICATION_INDIVIDUAL_COMPLETE]` - 개별 저장 완료
- **378번 라인**: `[NOTIFICATION_SSE_SENT]` - SSE 전송 성공 (DEBUG 레벨)
- **380번 라인**: `[NOTIFICATION_SSE_FAILED]` - SSE 전송 실패

#### 실시간 알림 관련
- **263번 라인**: `[REALTIME_NOTIFICATION_START]` - 실시간 알림 시작
- **275번 라인**: `[REALTIME_NOTIFICATION_SUCCESS]` - 실시간 알림 성공
- **278번 라인**: `[REALTIME_NOTIFICATION_FAILED]` - 실시간 알림 실패

#### 배치 알림 관련
- **188번 라인**: `[BATCH_NOTIFICATION_START]` - 배치 알림 시작
- **218번 라인**: `[BATCH_NOTIFICATION_CHECK]` - 제품 재고 확인
- **224번 라인**: `[BATCH_NOTIFICATION_THRESHOLD_REACHED]` - 임계값 도달
- **230번 라인**: `[BATCH_NOTIFICATION_THRESHOLD_NOT_REACHED]` - 임계값 미도달
- **237번 라인**: `[BATCH_NOTIFICATION_PRODUCT_PROCESSED]` - 제품 처리 완료
- **250번 라인**: `[BATCH_NOTIFICATION_COMPLETE]` - 배치 알림 완료

### 2. SseService.java

#### SSE 연결 관련
- **32번 라인**: `[SSE_CONNECTION_CLOSED]` - SSE 연결 종료
- **36번 라인**: `[SSE_CONNECTION_TIMEOUT]` - SSE 연결 타임아웃
- **40번 라인**: `[SSE_CONNECTION_ERROR]` - SSE 연결 에러
- **45번 라인**: `[SSE_CONNECTION_CREATED]` - SSE 연결 생성 (총 연결 수 포함)

#### SSE 전송 관련
- **60번 라인**: `[SSE_EMITTER_NOT_FOUND]` - SSE Emitter 없음 (WARN)
- **72번 라인**: `[SSE_SEND_SUCCESS]` - SSE 전송 성공 (전송 시간 포함)
- **76번 라인**: `[SSE_SEND_FAILED]` - SSE 전송 실패
- **92번 라인**: `[SSE_CONNECTION_CLOSED_MANUALLY]` - 수동 연결 종료

## 📊 로그 레벨별 분류

### INFO 레벨 (일반 정보)
- 알림 서비스 시작/완료
- 배치 저장 시작/완료
- SSE 연결 생성/종료
- SSE 전송 성공

### DEBUG 레벨 (상세 정보)
- `[NOTIFICATION_SSE_SENT]` - SSE 전송 성공 (배치/개별 저장 시)

### WARN 레벨 (경고)
- `[SSE_EMITTER_NOT_FOUND]` - SSE Emitter 없음
- `[BATCH_NOTIFICATION_PRODUCT_NOT_FOUND]` - 제품 없음

### ERROR 레벨 (에러)
- 알림 서비스 실패
- SSE 전송 실패
- 배치 알림 실패
- SSE 연결 에러

## 🔍 성능 측정 로그

다음 로그들은 성능 측정을 위해 시간 정보를 포함합니다:
- `[NOTIFICATION_QUERY_USERS]` - queryDuration={}ms
- `[NOTIFICATION_SERVICE_COMPLETE]` - totalDuration={}ms, parallelDuration={}ms
- `[SSE_SEND_SUCCESS]` - duration={}ms
- `[BATCH_NOTIFICATION_PRODUCT_PROCESSED]` - duration={}ms
- `[BATCH_NOTIFICATION_COMPLETE]` - totalDuration={}ms
