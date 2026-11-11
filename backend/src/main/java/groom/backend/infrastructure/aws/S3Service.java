package groom.backend.infrastructure.aws;

import groom.backend.common.exception.BusinessException;
import groom.backend.common.exception.ErrorCode;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class S3Service {

  private final S3Client s3Client;

  @Value("${cloud.aws.s3.bucket}")
  private String bucket;

  // 허용 형식과 서버가 정하는 확장자 - 클라이언트 파일명은 키에 쓰지 않는다
  private static final Map<String, String> ALLOWED_EXTENSIONS = Map.of(
          "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp");

  public String upload(MultipartFile file) {
    // HTML · SVG 같은 스크립트 파일이 PUBLIC_READ 로 올라가 저장형 XSS 가 되는 것을 막으려고 S3 호출 전에 거절한다
    String contentType = file.getContentType();
    String extension = contentType == null ? null : ALLOWED_EXTENSIONS.get(contentType);
    if (extension == null) {
      throw new BusinessException(ErrorCode.INVALID_PARAMETER, "허용하지 않는 이미지 형식입니다.");
    }
    // 파일명의 ../ · 공백 · 태그가 키로 들어가는 것을 막으려고 키는 UUID 와 확장자로만 만든다
    String key = "products/" + UUID.randomUUID() + "." + extension;

    PutObjectRequest request = PutObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .acl(ObjectCannedACL.PUBLIC_READ)
            .contentType(file.getContentType())
            .build();

    try {
      s3Client.putObject(request, RequestBody.fromBytes(file.getBytes()));
    } catch (IOException e) {
      throw new RuntimeException("S3 업로드 실패", e);
    }

    return "https://" + bucket + ".s3.ap-northeast-2.amazonaws.com/" + key;
  }
}
