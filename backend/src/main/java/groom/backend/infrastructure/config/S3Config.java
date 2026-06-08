package groom.backend.infrastructure.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Slf4j
@Configuration
public class S3Config {

  @Value("${cloud.aws.credentials.access-key}")
  private String accessKey;

  @Value("${cloud.aws.credentials.secret-key}")
  private String secretKey;

  @Value("${cloud.aws.region}")
  private String region;

  @Bean
  public S3Client s3Client() {
    // AWS 키가 비어 있으면(로컬/부하테스트 환경) 더미 자격증명으로 클라이언트를 만들어
    // 앱이 정상 부팅되게 한다. 실제 S3 호출(업로드 등) 시에만 실패하며, 부팅은 막지 않는다.
    boolean blank = accessKey == null || accessKey.isBlank()
            || secretKey == null || secretKey.isBlank();
    AwsBasicCredentials credentials = blank
            ? AwsBasicCredentials.create("dummy-access-key", "dummy-secret-key")
            : AwsBasicCredentials.create(accessKey, secretKey);

    if (blank) {
      log.warn("[S3Config] AWS 자격증명이 비어 있어 더미 키로 S3Client 를 생성한다. "
              + "실제 S3 업로드는 동작하지 않는다(부팅/부하테스트 전용).");
    }

    return S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(() -> credentials)
            .build();
  }
}
