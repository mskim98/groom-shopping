-- ============================================
-- V2: Add Test Users
-- k6 로드 테스트용 사용자 데이터 (20명)
-- password: "password123" (bcrypt hashed)
-- ============================================

INSERT INTO users (email, password, name, role, grade, created_at, updated_at) VALUES
-- Admin user
('admin@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Admin User', 'ROLE_ADMIN', 'BRONZE', NOW(), NOW()),

-- Regular test users (1-20)
('user_1@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 1', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_2@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 2', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_3@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 3', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_4@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 4', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_5@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 5', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_6@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 6', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_7@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 7', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_8@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 8', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_9@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 9', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_10@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 10', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_11@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 11', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_12@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 12', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_13@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 13', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_14@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 14', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_15@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 15', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_16@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 16', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_17@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 17', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_18@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 18', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_19@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 19', 'ROLE_USER', 'BRONZE', NOW(), NOW()),
('user_20@test.com', '$2a$10$slYQmyNdGzin7olVN3p5OPST9/PgBkqquzi8Ay0IQi7dVK3W7ggvW', 'Test User 20', 'ROLE_USER', 'BRONZE', NOW(), NOW());

-- 모든 사용자에 대해 Cart 생성
INSERT INTO cart (user_id, created_at, updated_at)
SELECT id, NOW(), NOW() FROM users;

-- ============================================
-- V2 마이그레이션 완료
-- 총 21명 사용자 생성 (Admin + 20명의 테스트 사용자)
-- 각 사용자마다 장바구니 1개씩 생성
-- ============================================
