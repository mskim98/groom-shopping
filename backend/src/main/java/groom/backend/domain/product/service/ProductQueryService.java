package groom.backend.domain.product.service;

import groom.backend.domain.product.model.Product;
import groom.backend.infrastructure.cache.ProductDetailCache;
import groom.backend.domain.product.repository.ProductQueryRepository;
import groom.backend.interfaces.product.dto.request.ProductSearchRequest;
import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class ProductQueryService {

    private final ProductQueryRepository productQueryRepository;
    private final ProductDetailCache detailCache;
    private final int countCap;

    public ProductQueryService(ProductQueryRepository productQueryRepository, ProductDetailCache detailCache,
                               @Value("${product.list.count-cap:10000}") int countCap) {
        this.productQueryRepository = productQueryRepository;
        this.detailCache = detailCache;
        this.countCap = countCap;
    }

    // 시작 위치가 개수 상한 이상인 쪽은 저장소 호출 전에 막는다 - 깊은 OFFSET 은 건너뛴 행만큼 읽는다
    private void requireWithinCap(Pageable pageable) {
        if (pageable.isPaged() && pageable.getOffset() >= countCap) {
            throw new BusinessException(ErrorCode.PRODUCT_LIST_PAGE_TOO_DEEP);
        }
    }

    public Page<Product> findAllProducts(Pageable pageable) {
        requireWithinCap(pageable);
        return productQueryRepository.findAll(pageable);
    }

    // 트랜잭션 없이 읽는다 - L1/L2 적중이면 DB 연결을 잡지 않는다. DB 로 가는 읽기는 저장소가 자기 읽기 전용 트랜잭션을 연다
    // shortcut: 도메인 서비스가 infrastructure.cache 를 직접 쓴다, 캐시 구현이 둘 이상이 되면 도메인 쪽 인터페이스로 뺀다
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public ProductDetailCache.ProductDetail findDetail(UUID id) {
        return detailCache.read(id, productQueryRepository::findById)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "존재하지 않는 상품입니다."));
    }

    public Page<Product> findByCondition(ProductSearchRequest request, Pageable pageable) {
        requireWithinCap(pageable);
        return productQueryRepository.findByCondition(request.toCreteria(), pageable);
    }
}
