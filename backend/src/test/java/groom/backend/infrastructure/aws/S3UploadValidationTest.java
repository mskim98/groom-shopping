package groom.backend.infrastructure.aws;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

// 상품 이미지 업로드가 허용한 이미지 형식만 받고, 저장 키에 클라이언트 파일명이 섞이지 않는지 본다 (S3Client 는 mock, 받은 요청을 확인한다)
@DisplayName("상품 이미지 업로드 검증")
class S3UploadValidationTest {

    private S3Client s3Client;
    private S3Service s3Service;

    @BeforeEach
    void setUp() {
        s3Client = mock(S3Client.class);
        s3Service = new S3Service(s3Client);
        ReflectionTestUtils.setField(s3Service, "bucket", "test-bucket");
    }

    @ParameterizedTest
    @ValueSource(strings = {"text/html", "image/svg+xml", "application/octet-stream", "image/gif", "application/pdf"})
    @DisplayName("허용하지 않은 content type 은 S3 호출 전에 400 INVALID_PARAMETER 로 거절한다")
    void upload_허용하지_않은_타입_S3호출없이_400으로_거절한다(String contentType) {
        MockMultipartFile file = new MockMultipartFile("image", "evil.html", contentType, "<script>1</script>".getBytes());

        assertThatThrownBy(() -> s3Service.upload(file))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.INVALID_PARAMETER));
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @Test
    @DisplayName("content type 이 없는 파일도 S3 호출 전에 거절한다")
    void upload_타입이_없으면_S3호출없이_거절한다() {
        MockMultipartFile file = new MockMultipartFile("image", "a.png", null, new byte[] {1});

        assertThatThrownBy(() -> s3Service.upload(file)).isInstanceOf(BusinessException.class);
        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "../../x.html|image/png|png",
            "a b<script>.png|image/png|png",
            "photo.JPG|image/jpeg|jpg",
            "evil.exe|image/webp|webp"})
    @DisplayName("허용한 타입의 저장 키는 products/ 아래 서버가 만든 UUID 와 허용 확장자뿐이고 파일명은 들어가지 않는다")
    void upload_허용한_타입_키에_클라이언트_파일명이_들어가지_않는다(String originalName, String contentType, String extension) {
        MockMultipartFile file = new MockMultipartFile("image", originalName, contentType, new byte[] {1, 2, 3});

        String url = s3Service.upload(file);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest sent = captor.getValue();
        assertThat(sent.key()).matches("products/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\." + extension);
        assertThat(sent.contentType()).isEqualTo(contentType);
        assertThat(url).endsWith("/" + sent.key());
    }
}
