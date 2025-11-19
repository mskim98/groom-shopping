# Multi-stage build for Next.js application
FROM node:20 AS builder
WORKDIR /app

# Build arguments for Next.js public environment variables
ARG NEXT_PUBLIC_API_URL=/api/v1
ARG NEXT_PUBLIC_TOSS_CLIENT_KEY

# Set environment variables for build
ENV NEXT_PUBLIC_API_URL=${NEXT_PUBLIC_API_URL}
ENV NEXT_PUBLIC_TOSS_CLIENT_KEY=${NEXT_PUBLIC_TOSS_CLIENT_KEY}

# Copy package files
COPY package*.json ./

# npm 설정 (네트워크 오류 대응)
RUN npm config set registry https://registry.npmjs.org/ && \
    npm config set fetch-retries 10 && \
    npm config set fetch-retry-mintimeout 30000 && \
    npm config set fetch-retry-maxtimeout 300000

# npm 설치 (네트워크 안정성을 위해 npm install 사용)
# npm ci는 네트워크가 불안정할 때 타임아웃이 발생할 수 있어 npm install 사용
RUN echo "npm 패키지 설치 시작..." && \
    npm install --no-audit --legacy-peer-deps || \
    (echo "첫 번째 시도 실패, 10초 대기 후 재시도..." && \
     sleep 10 && \
     npm install --no-audit --legacy-peer-deps) || \
    (echo "두 번째 시도 실패, 20초 대기 후 최종 재시도..." && \
     sleep 20 && \
     npm install --no-audit --legacy-peer-deps) && \
    echo "npm 패키지 설치 완료"

# Copy source code
COPY . .

# Build the application
RUN npm run build

# Production stage
FROM node:20-alpine AS runner
WORKDIR /app

ENV NODE_ENV production

# Copy necessary files from builder
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static

EXPOSE 3000

ENV PORT 3000
ENV HOSTNAME "0.0.0.0"

CMD ["node", "server.js"]