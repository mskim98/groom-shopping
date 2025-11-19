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
    npm config set fetch-retries 5 && \
    npm config set fetch-retry-mintimeout 20000 && \
    npm config set fetch-retry-maxtimeout 120000 && \
    npm config set network-timeout 300000

# npm 설치 (package-lock.json이 있으면 npm ci, 없으면 npm install)
# 네트워크 오류 시 자동 재시도
RUN if [ -f package-lock.json ]; then \
      npm ci --prefer-offline --no-audit || \
      (echo "npm ci 실패, npm install로 재시도..." && sleep 5 && npm install --no-audit); \
    else \
      npm install --no-audit; \
    fi

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