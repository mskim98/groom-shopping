package groom.backend.infrastructure.kafka.stream;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;


/**
 * <h3>StateStore 저장용 래퍼(Wrapper) 클래스</h3>
 *
 * Kafka StateStore에 {@code List<CouponDelayEvent>}를 직접 저장할 때 발생하는
 * Java '타입 소거(Type Erasure)' 문제를 피하기 위해 사용하는 '포장' 클래스입니다.
 * (이유: Java 제네릭의 타입 소거 문제로 인한 Serde(직렬화) 오류 방지)
 * (Lombok @Data, @NoArgsConstructor, @AllArgsConstructor가
 * Getter/Setter/기본생성자를 자동으로 만들어주어 Serde가 정상 동작합니다.)
 *
 * <ol>
 * <li><b>기술적 이유 (직렬화):</b>
 * Java의 제네릭(List<T>)은 런타임에 타입 정보(T)가 사라집니다.
 * JsonDeserializer가 JSON 데이터를 {@code List<CouponDelayEvent>}로
 * 정확히 변환하지 못할 수 있습니다.
 * 이처럼 구체적인 클래스(Wrapper)로 감싸면, Deserializer가
 * 'CouponDelayEventListWrapper' 클래스를 명확히 인지하고,
 * 내부의 'events' 필드에 List 데이터를 안전하게 채워 넣을 수 있습니다.
 * </li>
 * <li><b>논리적 이유 (데이터 구조):</b>
 * StateStore의 Key는 '실행 시간(초)'입니다.
 * 만약 '같은 1초' 안에 여러 개의 이벤트가 실행되어야 한다면,
 * Key 하나(예: 1678888800)에 여러 이벤트(Value)를 '목록(List)'으로
 * 저장해야 합니다. 이 클래스는 그 목록을 담는 '그릇' 역할을 합니다.
 * </li>
 * </ol>
 *
 * <b>[주의]</b> 이 클래스를 JsonSerializer/JsonDeserializer가 올바르게 처리하려면
 * <b>기본 생성자(No-args constructor)</b>와 <b>Getter/Setter</b>가 반드시 필요합니다.
 * (예: Lombok의 {@literal @Data}, {@literal @NoArgsConstructor} 사용)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponDelayEventListWrapper {
    private List<CouponDelayEvent> events;
}
