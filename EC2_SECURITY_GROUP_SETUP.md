# EC2 보안 그룹 설정 가이드

## 아웃바운드 규칙 설정

EC2 인스턴스에서 npm 패키지를 다운로드하고 외부 서비스에 접근하려면 아웃바운드 트래픽이 허용되어야 합니다.

## AWS 콘솔에서 설정 방법

### 1. EC2 콘솔 접속
1. AWS 콘솔 → EC2 서비스
2. 좌측 메뉴에서 "보안 그룹" 클릭
3. 사용 중인 EC2 인스턴스의 보안 그룹 선택

### 2. 아웃바운드 규칙 편집
1. 선택한 보안 그룹의 "아웃바운드 규칙" 탭 클릭
2. "아웃바운드 규칙 편집" 버튼 클릭

### 3. 규칙 추가

#### 옵션 1: 모든 아웃바운드 트래픽 허용 (권장 - 개발/테스트 환경)
```
유형: 모든 트래픽
프로토콜: 모두
포트 범위: 모두
대상: 0.0.0.0/0
설명: Allow all outbound traffic
```

#### 옵션 2: 필요한 포트만 허용 (프로덕션 환경 권장)
다음 규칙들을 추가:

**HTTPS (npm, Docker Hub 등)**
```
유형: HTTPS
프로토콜: TCP
포트 범위: 443
대상: 0.0.0.0/0
설명: Allow HTTPS for npm and Docker Hub
```

**HTTP (선택사항 - 일부 레거시 서비스용)**
```
유형: HTTP
프로토콜: TCP
포트 범위: 80
대상: 0.0.0.0/0
설명: Allow HTTP
```

**DNS (도메인 이름 해석)**
```
유형: 사용자 지정 TCP
프로토콜: TCP
포트 범위: 53
대상: 0.0.0.0/0
설명: Allow DNS queries
```

**또는 UDP DNS**
```
유형: 사용자 지정 UDP
프로토콜: UDP
포트 범위: 53
대상: 0.0.0.0/0
설명: Allow DNS queries (UDP)
```

**Docker 레지스트리 (선택사항)**
```
유형: 사용자 지정 TCP
프로토콜: TCP
포트 범위: 443
대상: 0.0.0.0/0
설명: Allow Docker registry access
```

4. "규칙 저장" 클릭

## AWS CLI로 설정 (선택사항)

```bash
# 보안 그룹 ID 확인
aws ec2 describe-instances --instance-ids i-xxxxxxxxxxxxx --query 'Reservations[0].Instances[0].SecurityGroups[*].GroupId' --output text

# 아웃바운드 규칙 추가 (HTTPS)
aws ec2 authorize-security-group-egress \
    --group-id sg-xxxxxxxxxxxxx \
    --ip-permissions IpProtocol=tcp,FromPort=443,ToPort=443,IpRanges=[{CidrIp=0.0.0.0/0,Description="Allow HTTPS"}]

# 아웃바운드 규칙 추가 (HTTP)
aws ec2 authorize-security-group-egress \
    --group-id sg-xxxxxxxxxxxxx \
    --ip-permissions IpProtocol=tcp,FromPort=80,ToPort=80,IpRanges=[{CidrIp=0.0.0.0/0,Description="Allow HTTP"}]

# 모든 아웃바운드 트래픽 허용
aws ec2 authorize-security-group-egress \
    --group-id sg-xxxxxxxxxxxxx \
    --ip-permissions IpProtocol=-1,IpRanges=[{CidrIp=0.0.0.0/0,Description="Allow all outbound"}]
```

## 확인 방법

### 1. EC2에서 네트워크 연결 테스트
```bash
# HTTPS 연결 테스트 (npm 레지스트리)
curl -I https://registry.npmjs.org/

# HTTP 연결 테스트
curl -I http://www.google.com

# DNS 확인
nslookup registry.npmjs.org
```

### 2. 보안 그룹 규칙 확인
```bash
# 현재 보안 그룹의 아웃바운드 규칙 확인
aws ec2 describe-security-groups \
    --group-ids sg-xxxxxxxxxxxxx \
    --query 'SecurityGroups[0].IpPermissionsEgress'
```

## 기본 설정 확인

기본적으로 AWS EC2 보안 그룹은 **모든 아웃바운드 트래픽을 허용**합니다. 
하지만 다음 경우에는 제한이 있을 수 있습니다:

1. **사용자 정의 보안 그룹**: 아웃바운드 규칙을 명시적으로 설정한 경우
2. **네트워크 ACL**: VPC 레벨에서 차단된 경우
3. **회사/조직 정책**: 아웃바운드 트래픽이 제한된 경우

## 문제 해결

### 아웃바운드 규칙이 있는데도 연결이 안 되는 경우

1. **네트워크 ACL 확인**
   ```bash
   # VPC의 네트워크 ACL 확인
   aws ec2 describe-network-acls --filters "Name=vpc-id,Values=vpc-xxxxxxxxxxxxx"
   ```

2. **라우팅 테이블 확인**
   ```bash
   # 라우팅 테이블 확인
   aws ec2 describe-route-tables --filters "Name=vpc-id,Values=vpc-xxxxxxxxxxxxx"
   ```

3. **인터넷 게이트웨이 확인**
   - VPC에 인터넷 게이트웨이가 연결되어 있는지 확인
   - 서브넷이 인터넷 게이트웨이로 라우팅되는지 확인

## 권장 설정 (프로덕션)

프로덕션 환경에서는 최소 권한 원칙을 따라 필요한 포트만 열어두는 것을 권장합니다:

```
✅ HTTPS (443) - npm, Docker Hub, AWS API 등
✅ HTTP (80) - 일부 레거시 서비스
✅ DNS (53) - 도메인 이름 해석
✅ SSH (22) - 관리용 (인바운드만)
✅ 애플리케이션 포트 (80, 443) - 인바운드만
```

## 빠른 확인

EC2 인스턴스에서 다음 명령어로 즉시 확인:

```bash
# npm 레지스트리 접근 테스트
curl -v https://registry.npmjs.org/

# 성공하면 "HTTP/2 200" 또는 "HTTP/1.1 200 OK" 응답
# 실패하면 타임아웃 또는 연결 거부 오류
```

## 요약

**가장 간단한 설정 (개발/테스트):**
- 아웃바운드: 모든 트래픽 허용 (0.0.0.0/0)
- 인바운드: SSH (22), HTTP (80), HTTPS (443)

**프로덕션 권장 설정:**
- 아웃바운드: HTTPS (443), HTTP (80), DNS (53)
- 인바운드: SSH (22), HTTP (80), HTTPS (443)

