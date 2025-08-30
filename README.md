# Java GC 비교 연구 (JDK 11 & 17 · Serial/Parallel/CMS/G1/ZGC)

> **핵심 요약**
> - **대상 JDK**: 11, 17
> - **GC 알고리즘**: Serial, Parallel, CMS(JDK 11 전용), G1, ZGC
> - **목적**: CMS의 위치를 보기 위해 JDK 11에서 Parallel ↔ CMS, CMS ↔ G1 비교 / JDK 17에서 G1 ↔ ZGC 비교
> - **실행 환경**: vCPU 8, RAM 8GB, Linux VM
> - **공통 힙 크기**: 4GB (`-Xms4g -Xmx4g`)
> - **수집 방식**
>   - GC 로그 → **생성형 AI로 CSV 변환 → 검증 → Grafana Import**
>   - CPU/MEM → **Node Exporter → Prometheus → Grafana**

---

## 1. 연구 목적
- 서로 다른 JDK와 GC 알고리즘을 동일 조건(힙 4GB)에서 실행해 **GC 특성** 비교
- 특히 **CMS의 상대적 특성**을 Parallel/G1과 비교하고, **G1 vs ZGC**의 저지연 성능 차이를 확인

---

## 2. 실험 환경
- **하드웨어(가상)**: 8 vCPU, 8GB RAM
- **운영체제**: Linux (배포판/커널은 metadata에 기록)
- **JDK**: 11, 17 (Temurin)
- **관측 도구**
  - Node Exporter → Prometheus → Grafana (CPU, 메모리 리소스)
  - GC 로그 텍스트 → CSV 변환(GPT 이용) → Grafana Import
- **주의사항**
  - `sh` 스크립트나 자동 파서 대신 **수동 실행 + 로그 변환** 방식 사용
  - Loki, Docker **미사용**
- **테스트 코드**
  - 테스트 코드는 성능 비교를 위해 모두 같은 코드를 사용

---

## 3. 비교한 GC 알고리즘 개념
### 3.1 Serial GC
- 단일 스레드 GC, 소규모 힙/싱글코어 환경에 적합
- Stop-the-world 시간이 길지만 구조 단순

### 3.2 Parallel GC
- 멀티스레드 GC, 처리량 극대화 목표
- stop-the-world는 길 수 있으나 CPU 자원이 많을 때 효과적

### 3.3 CMS (Concurrent Mark-Sweep)
- 마크/스윕을 애플리케이션 실행과 병렬로 수행
- stop-the-world를 줄이지만 **프래그멘테이션 문제** 존재
- JDK 14 이후 제거, JDK 11에서만 비교 가능

### 3.4 G1 GC
- 힙을 Region 단위로 분할, 필요한 Region만 수집
- stop-the-world 시간 예측 가능 (Pause Time 목표 설정)
- CMS의 대체자

### 3.5 ZGC
- 저지연 목표 GC (STW 수 밀리초 이내)
- Colored Pointer, Load Barrier 기반
- 대형 힙(테라바이트 단위)에서도 저지연 유지

---

## 4. 데이터 처리 파이프라인
1. **실행 & 로그 수집**
   - 각 GC 조합으로 벤치마크 실행
   - `data/raw/.../gc.log` 저장
2. **CSV 변환**
   - 생성형 AI를 이용해 GC 로그 → CSV 변환
   - 스키마: `timestamp, gc_id, phase, pause_ms, heap_before_mb, heap_after_mb, reclaimed_mb`
   - 변환 후 검증: 이벤트 시퀀스/합계/타임스탬프 확인
3. **시각화**
   - Grafana에서 CSV Import
   - Prometheus(Node Exporter) 시스템 메트릭과 함께 대시보드 구성

---

## 5. 시각화 (Visualization)
### 5.1 구성 요소
- **시스템 메트릭 (Prometheus)**
  - CPU 사용률
  - 메모리 사용량
- **GC 이벤트 (CSV Import)**
  - Pause 시간 분포 (p50/p90/p99)
  - 오버헤드(총 GC 시간/실행 시간)
  - Heap Before/After
  - Reclaimed 메모리 vs Pause 상관관계

### 5.2 대시보드 예시
- CPU/MEM 패널: 실시간 자원 사용률
- GC 이벤트 패널: 시간축 기반 Pause 라인 그래프
- 비교 패널:
  - JDK 11: **Parallel ↔ CMS, CMS ↔ G1**
  - JDK 17: **G1 ↔ ZGC**

---

## 6. 분석 (Analysis)
### 6.1 JDK 11 결과
- **Parallel ↔ CMS**
  - Parallel: 처리량 유리, Pause 길다
  - CMS: Pause 줄지만 메모리 단편화 및 remark 단계 영향
- **CMS ↔ G1**
  - G1: CMS 대비 안정적이고 Pause 예측 가능
  - CMS: 일부 workload에서 Throughput은 낫지만 tail latency 취약

### 6.2 JDK 17 결과
- **G1 ↔ ZGC**
  - ZGC: 평균/최대 Pause가 짧고 tail latency 우수
  - G1: throughput 유리, pause는 수 밀리초~수십 밀리초 수준
- **추세**
  - ZGC는 확실히 저지연 특성을 보였음
  - 그러나 작은 힙(4GB)에서는 CPU 오버헤드가 상대적으로 큼

---

## 7. 한계와 주의사항
- 워크로드와 힙 4GB라는 고정 조건에서 나온 결과 → 대형 힙/다코어 환경에서는 차이 확대 예상
- CMS는 JDK 14 이상에서는 사용할 수 없음 → JDK 11에서만 비교
- CSV 변환은 GPT를 통해 이루어졌으므로 **검증 필수**
- GC 로그 구조가 GC/버전별로 달라 동일 파서 적용 불가 → 수동 검증 절차 필요

---

## 8. 재현 가이드
```bash
# GC 로그 실행 예시 (JDK 17 G1)
java -XX:+UseG1GC -Xms4g -Xmx4g \
     -Xlog:gc*:file=gc.log:time,uptime,level,tags \
     -jar app.jar

# CSV 변환: gc.log → gc.csv (생성형 AI 활용)
# Grafana → CSV Import → 대시보드 생성
