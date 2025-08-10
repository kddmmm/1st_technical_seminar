
# Java GC 성능 연구 (JDK 8/11/17/21/24 · Serial/Parallel/CMS/G1/ZGC)

> **핵심 요약**
> - **로그 생성은 JDK 8/11/17/21/24 전부 진행**했지만, **시각화(그래프)는 JDK 17 데이터만 사용**했습니다.
> - **시스템 메트릭**: Node Exporter → Prometheus → Grafana
> - **GC 로그**: 텍스트 로그를 **CSV로 변환**하여 **Grafana에 직접 Import** (데이터 소스 대신 파일 업로드 사용)
> - **변환은 GPT를 통해 수행**했고, **별도의 검증 절차**로 결과를 확인했습니다.
> - **Loki/Docker 미사용.** 순수 바이너리/서비스로 구성.
> - **실험 환경**: 8 vCPU, 8GB RAM의 Linux VM

---

## 1. 목적
JDK 8/11/17/21/24에서 **Serial, Parallel, CMS, G1, ZGC**를 동일/유사 워크로드로 실행하여 **GC 동작 특성**을 비교하고,  
특히 **JDK 17 데이터를 시각화**하여 **오버헤드, 지연, 처리량** 관점에서의 의사결정을 돕습니다.

---

## 2. 실험 환경

- **하드웨어(가상화)**: vCPU 8코어, RAM 8GB
- **OS**: Linux (배포판/커널 버전은 아래 metadata에 기록)
- **JDK**: 8, 11, 17, 21, 24
- **GC**: Serial, Parallel, CMS(JDK8), G1, ZGC
- **관측**
  - **시스템 메트릭**: Node Exporter → Prometheus → Grafana
  - **GC 로그**: JVM `-Xlog`/`-XX:+PrintGCDetails` → **CSV 변환(GPT)** → Grafana **직접 Import**
- **네트워킹/스토리지 설정**: (필요 시 실험별 `metadata`에 기록)

> 참고: 이 저장소는 **Loki 및 Docker를 사용하지 않습니다**.

---

## 3. 데이터 파이프라인(실제 운용 흐름)

1) **벤치마크 실행 & 로그 수집**  
   - JDK/GC별 실행 (예시는 아래 6장)  
   - 로그 저장: `data/raw/jdk{8|11|17|21|24}/{GC}/.../gc.log`

2) **시스템 메트릭 수집(실시간)**  
   - Node Exporter → Prometheus 스크랩  
   - Grafana에서 Prometheus 데이터 소스로 대시보드 구성

3) **GC 로그 → CSV 변환**  
   - **GPT를 사용해 로그를 CSV로 변환**
   - 변환 스키마(예):  
     `timestamp, gc_id, phase, pause_ms, heap_before_mb, heap_after_mb, reclaimed_mb, ...`

4) **검증(Validation)**  
   - 로그의 **GC(n)** 시퀀스 연속성 확인  
   - `timestamp` 단조 증가, 누락 여부, 합계/평균값 샘플 계산  
   - 원본 로그의 pause 합계 vs CSV 합계 대조

5) **Grafana 시각화**  
   - **JDK 17 CSV만** 패널로 **직접 Import**하여 그래프 구성  
   - 시스템 메트릭은 Prometheus 쿼리로 동일 대시보드에서 함께 표시

---

## 4. 디렉토리 & 파일 규칙

- **원본 로그**:  
  `data/raw/jdk{8|11|17|21|24}/{GC}/{YYYYMMDD_HHMM}/{run_id}/gc.log`
- **CSV (시각화용, JDK 17)**:  
  `data/processed/jdk17/{GC}_{YYYYMMDD}_{run_id}.csv`
- **그래프**:  
  `results/charts/{figure_name}.png`
- 각 실험 폴더에 `metadata.json`(권장):  
  - JDK/빌드, 커널/배포판, GC 옵션, 힙 크기, 스레드 수, 워크로드 파라미터, THP/shmem 설정 등

---

## 5. 주요 지표 정의

- **GC 오버헤드** = (총 GC 중단시간) / (총 테스트 시간)
- **Pause 분포** = p50 / p90 / p99 (tail latency 관찰)
- **처리량(Throughput)** = 워크로드 단위 처리량(ops/s) 또는 애플리케이션 처리량
- **메모리** = GC 전/후 힙, Reclaimed(MB), RSS/VSZ 추이
- **CPU** = 유저/시스템/아이들, 프로세스별 CPU 사용률

---

## 6. 실행 예시 (로그 수집)

> 공통: 힙, 스레드, 워크로드 파라미터는 metadata에 기록

```bash
# Serial
java -XX:+UseSerialGC -Xms4g -Xmx4g -Xlog:gc*=debug:file=gc.log:time,uptime,level,tags \
     -jar benchmark/java/target/app.jar

# Parallel
java -XX:+UseParallelGC -Xms4g -Xmx4g -Xlog:gc*=debug:file=gc.log:time,uptime,level,tags \
     -jar ...

# CMS (JDK 8)
java -XX:+UseConcMarkSweepGC -Xms4g -Xmx4g -XX:+PrintGCDetails -XX:+PrintGCDateStamps \
     -Xloggc:gc.log -jar ...

# G1
java -XX:+UseG1GC -Xms4g -Xmx4g -Xlog:gc*=debug:file=gc.log:time,uptime,level,tags \
     -jar ...

# ZGC (JDK 11+ / 권장 17+)
java -XX:+UseZGC -Xms4g -Xmx4g -Xlog:gc*=debug:file=gc.log:time,uptime,level,tags \
     -jar ...
