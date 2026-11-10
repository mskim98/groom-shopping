-- 상품 목록 · 검색 인덱스 두 가지

-- 1) 2글자 이름 검색. pg_trgm(idx_product_name_trgm)은 3글자 조각을 색인하므로 2글자 키워드(LIKE '%ab%')에서는 쓸 조각이 없다
--    그래서 이름을 겹치는 2글자 조각의 집합(중복 제거)으로 바꾸는 함수를 색인하고, 키워드가 정확히 2글자면 그 조각이 이름의 집합에 들어 있는지(@>)로 후보를 좁힌다
--    LIKE 는 그대로 남겨 "포함" 의미는 LIKE 가 확정한다. 문자 수는 바이트가 아니라 문자(코드 포인트) 기준이다
CREATE FUNCTION product_name_grams2(txt text) RETURNS text[]
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT COALESCE(array_agg(DISTINCT substr(txt, i, 2)), '{}'::text[])
      FROM generate_series(1, length(txt) - 1) AS i
$$;

-- 질의에서 부르는 얇은 함수. 본문이 식 하나뿐이라 계획 단계에서 풀려 @> 로 바뀌므로 위 함수의 GIN 인덱스가 걸린다(STRICT 로 만들면 풀리지 않는다)
CREATE FUNCTION product_name_has_gram2(txt text, gram text) RETURNS boolean
    LANGUAGE sql IMMUTABLE PARALLEL SAFE
AS $$
    SELECT product_name_grams2(txt) @> ARRAY[gram]
$$;

CREATE INDEX idx_product_name_grams2 ON product USING gin (product_name_grams2(lower(name)));

-- 2) 프런트 목록 정렬(ORDER BY category ASC, id DESC)을 인덱스 순서 그대로 내준다 - Sort 없이 OFFSET 만큼만 건너뛰고 읽는다
CREATE INDEX idx_product_category_id_desc ON product (category, id DESC);
