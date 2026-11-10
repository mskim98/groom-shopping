-- V1 의 idx_product_category(category)는 V25 의 idx_product_category_id_desc(category, id DESC)가 같은 선행 열로 덮는다 - 남겨 두면 플래너가 정렬이 필요한 쪽을 골라 Sort 가 생기고 쓰기마다 인덱스 하나를 더 갱신한다
DROP INDEX IF EXISTS idx_product_category;
