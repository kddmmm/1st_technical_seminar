# Java GC 비교 연구 (JDK 11 & 17 · Serial/Parallel/CMS/G1/ZGC)

> **핵심 요약**
> - **대상 JDK**: 11, 17
> - **GC 알고리즘**: Serial, Parallel, CMS(JDK 11), G1, ZGC
> - **목적**: **GC의 특성** 비교
> - **실행 환경**: vCPU 8, RAM 8GB, Linux VM
> - **공통 힙 크기**: 4GB (`-Xms4g -Xmx4g`)
> - **수집 방식**
>   - GC 로그 → **생성형 AI로 CSV 변환 → 검증 → Grafana Import**
>   - CPU/MEM → **Node Exporter → Prometheus → Grafana**

---

## 1. 연구 목적
- GC 알고리즘 분석 및 JDK와 GC를 동일 조건(힙 4GB)에서 실행해 **GC별 특성** 비교
- 특히 **서로 다른 GC의 상대적 특성** 을 비교하여 GC별 **성능 차이** (지연 시간 및 리소스 사용량)을 확인

---

## 2. 실험 환경
- **하드웨어(VM)**: 8 vCPU, 8GB RAM
- **운영체제**: Linux (Ubuntu 24.04)
- **JDK**: 11, 17 (OpenJDK)
- **관측 도구**
  - Node Exporter → Prometheus → Grafana (CPU, 메모리 리소스)
  - GC 로그 텍스트 → CSV 변환(GPT 이용) → Grafana Import
- **주의사항**
  - `sh` 스크립트나 자동 파서 대신 **수동 실행 + 로그 변환** 방식 사용
  - CMS GC는 JDK 14에서 Deprecated(사용 중단 권고), JDK 15부터 지원하지 않기 때문에 비교실험 중 Parallel ↔ CMS, CMS ↔ G1을 진행할 경우 JDK 11에서 진행
  - Loki, Docker **미사용**
- **테스트 코드 : GCTest.java**
  - 테스트 코드는 성능 비교를 위해 모두 같은 코드를 사용
 
    <br>
- **테스트 코드 설명**
```java
static final int OBJECT_COUNT = 100_000;
static final int OBJECT_SIZE_BYTES = 1024;
static final List<Object> memoryHog = new ArrayList<>();
```
 - OBJECT_COUNT : 한번에 생성할 객체 수
 - OBJECT_SIZE_BYTES : 객체 메모리 사이즈 지정 (1KB)
 - memoryHog: 메모리에 계속 유지하는 전역 리스트, Full GC 유발용

```java
byte[] data = new byte[OBJECT_SIZE_BYTES];
List<byte[]> nested = new ArrayList<>();
```
 - byte[] data : 배열 객체 하나당 힙 1KB 사용
 - nested : 배열을 리스트 안에 선언하여 CPU의 부하 높임
    - 힙 점유량 증가 → GC 자주 발생
    - 객체 생성 시 CPU 사용 → CPU 부하 증가

```java
HeavyObject() {
            for (int i = 0; i < 5; i++) {
                nested.add(new byte[OBJECT_SIZE_BYTES / 2]);
            }
```
   - 5개의 배열(512Byte)을 리스트에 추가
   - 총 3.5KB의 객체 생성

```java
    ExecutorService executor = Executors.newFixedThreadPool(4);
```
   - GC 테스트용 4개의 쓰레드 풀 생성 → 병렬로 객체 생성

```java
for (int t = 0; t < 4; t++) {
    executor.submit(() -> {
    List<Object> localList = new ArrayList<>();
```
   - localList : Young Region(Eden)에 쌓임 (minor GC 대상)

```java
while (true) {
  for (int i = 0; i < OBJECT_COUNT / 4; i++) {
       localList.add(new HeavyObject());
  }

  if (localList.size() > 100_000) {
      localList.clear();
  }
```
   - 반복문 안에서 객체 생성
   - 리스트가 커지면 참조 제거 → 가비지 발생, GC 대상

```java
 long duration = 5 * 60 * 1000;
```
   - 메인 스레드에서 5분간 테스트 실행

```java
 if (memoryHog.size() > 500_000 + expansionRate) {
                memoryHog.clear();
                System.gc();    //명시적인 GC 호출
                System.out.println("== 강제 Full GC 요청 ==");
                expansionRate += 50_000; 
  }
```
   - 일정 크기 이상 쌓이면 리스트 clear → 참조 제거
   - System.gc() : Full GC 강제 요청
   - expansionRate : CPU 부하 증가를 위한 단순 연산
<br>
 - 예외처리
<br>
  휴식 → CPU 과부하 방지
<br>
  예외 발생 시 인터럽트 상태 유지 

  
---

# 3. 비교한 GC 알고리즘 개념

## JVM 메모리 & GC 공통 개념

### Generational Hypothesis
- 대부분의 객체는 금방 죽음.
- 따라서 Young (Eden / Survivor) 와 Old (Tenured) 영역으로 나누어 수집 비용을 줄임.

### 카드 테이블 / Remembered Set (RSet)
- 다른 영역(Region)에서 이 Region을 참조하는 포인터를 추적.
- 전 힙 스캔을 피하고, 지역 단위 수집을 가능하게 함.

### Barrier (장벽)
- **Pre/Write barrier (SATB, G1 등)**: 쓰기 전에 이전 값을 기록 → 스냅샷 일관성 보장.  
- **Post/Write barrier (CMS incremental-update 등)**: 쓰기 후 더티 카드를 표시 → 변경 추적.  
- **Load/Read barrier (ZGC)**: 참조를 읽을 때 포인터 색상/매핑을 확인·재매핑.  

### 수집 방식
- **복사(Copying / Evacuation)**: 단편화 적음, Young에 유리.  
- **마크-스윕(-컴팩트) (Mark-Sweep / Mark-Compact)**: 빠르지만 단편화가 누적되면 Full GC(압축) 비용이 큼.  

### STW (Stop-The-World)
- 루트 스캔, 리마크, 객체 이동 같은 정합성 확보를 위한 핵심 구간.  
- STW를 줄이는 것이 목표지만, 완전히 없애긴 어려움.  

---

## 3.1 Serial GC / Parallel GC

### Serial GC

**핵심 키워드**
- 단일 스레드  
- 구조 단순  
- 소규모 힙 / 저사양 환경  

**개념**
- 가장 기본적인 GC  
- 모든 수집 작업을 하나의 GC 스레드가 직렬로 수행  
- GC 동안 애플리케이션은 완전 중단(STW)  
- 작은 힙과 싱글코어 환경에서 효율적  
- 구조가 단순하여 이해와 디버깅 용이  

**메모리 & 데이터 구조**
- Generational 구조: Young (Eden + 2×Survivor) / Old (Tenured)  
- 객체 헤더: age, 해시, 락 상태 등 정보 포함  
- TLAB(Thread-Local Allocation Buffer) 활용 가능 → 객체 할당 속도 향상  

**동작 방식**
- Minor GC (Young, Copying)  
  1. Eden과 Survivor 영역의 살아있는 객체를 다른 Survivor 또는 Old 영역으로 복사  
  2. 참조 갱신  
- Major/Full GC (Old, Mark-Compact)  
  1. Old 영역 객체 마킹  
  2. 살아있는 객체를 연속 공간으로 압축  
  3. 참조 갱신  

---

### Parallel GC

**핵심 키워드**
- 멀티스레드 STW 수집  
- 처리량(Throughput) 최적화  

**개념**
- Serial GC를 멀티스레드 환경에 맞게 확장한 버전  
- 여러 GC 스레드(worker)가 동시에 수집 작업 수행 → STW 시간 단축  
- 전체 처리량 극대화  

**메모리 & 데이터 구조**
- Generational 구조 유지  
- Young 영역: Parallel Scavenge (병렬 복사)  
- Old 영역: Parallel Old (병렬 Mark-Compact)  
- GC 스레드 수: `-XX:ParallelGCThreads` 옵션으로 조절 가능  

**동작 방식**
- Young GC (병렬 Minor)  
  1. Eden을 여러 블록으로 나눔  
  2. 각 스레드가 객체 복사 수행  
  3. 참조 갱신  
- Old GC (병렬 Full)  
  1. 여러 스레드가 동시에 Mark 단계 수행  
  2. 객체 이동과 Compact 단계 병렬 처리  

---

### Serial / Parallel GC 내부 보조기법

#### 1.1 TLAB (Thread-Local Allocation Buffer)

**개념**
- 각 스레드가 힙에서 객체를 빠르게 할당하기 위해 사용하는 스레드 전용 메모리 버퍼  
- 멀티스레드 환경에서 힙 접근 경쟁(lock contention) 최소화  

**설명 / 동작**
1. 힙에서 일정 크기의 블록을 TLAB로 할당  
2. 스레드는 TLAB에서 직접 객체 할당 → 동시성 확보 및 속도 향상  
3. TLAB 가득 차면 새로운 블록 힙에서 요청  
4. Minor GC 발생 시, 살아남은 객체를 Survivor 또는 Old 영역으로 이동  

- 목적: 스레드 로컬 할당 성능 최적화, 힙 경쟁 최소화  

#### 1.2 Work-stealing / Work-queue

**개념**
- Parallel GC에서 스레드 간 작업 분산 구조  
- 작업 부하 불균형 시, 남은 작업을 다른 스레드가 훔쳐 처리 (work-stealing)  

**설명 / 동작**
1. Eden과 Old 영역을 블록 단위로 나눠 스레드별 초기 작업 분배  
2. 각 스레드는 할당 블록에서 객체 복사 또는 Mark/Compact 수행  
3. 작업 부족 스레드는 다른 스레드의 남은 작업 훔쳐 처리 → CPU 활용 극대화  

- 목적: GC 처리 시간 최적화, 멀티코어 활용 극대화, 동적 부하 균형 실현  

---

## 3.2 CMS (Concurrent Mark-Sweep)

**핵심 키워드**
- 동시 마크/스윕  
- STW 시간 최소화  
- 단편화 발생 가능  
- JDK 14 이후 제거  

**개념**
- Old 영역 GC를 애플리케이션과 병행(concurrent) 수행  
- 평균 지연(latency)을 줄이는 Low Pause GC  
- 압축(compaction) 없이 동작 → 단편화 가능  

**메모리 & 데이터 구조**
- Young 영역: Parallel Scavenge 사용  
- Old 영역: CMS (Free List 기반)  
- Card Table + Post-write barrier로 cross-generation 참조 추적  

**동작 방식**
1. Initial Mark (STW): 루트에 연결된 객체를 빠르게 마크  
2. Concurrent Mark: 애플리케이션과 동시에 객체 그래프 탐색  
3. Remark (STW): concurrent 중 변경된 참조 최종 보정  
4. Concurrent Sweep: 도달 불가 객체 해제  
5. Fallback Compaction: 단편화 심하면 Full GC(Serial Old) 수행  

---

### CMS 내부 보조기법

#### 2.1 Write Barrier (Post-write barrier)

**개념**
- 애플리케이션이 Old 영역 객체 참조를 변경할 때, GC가 누락 없이 마크하도록 참조 변경 기록  

**설명 / 동작**
1. 객체 A가 B를 참조 → 참조를 C로 변경  
2. Post-write barrier가 “B 참조 제거” 기록  
3. Concurrent Mark 단계에서 기록된 참조를 재검사 → 누락 방지  

- 목적: STW 시간을 줄이며 동시 마킹 안정화  

#### 2.2 Remark 단계

**개념**
- Concurrent Mark 단계에서 누락된 참조를 최종 점검하는 짧은 STW 단계  

**설명 / 동작**
1. 애플리케이션 잠시 정지(STW)  
2. Concurrent Mark 중 변경된 참조를 재검사  
3. 참조 상태를 최신으로 보정  

- 목적: 메모리 정합성 확보, GC 안정성 보장  

#### 2.3 Free List 관리

**개념**
- Old 영역 객체를 가변 크기 블록 단위로 관리하는 빈 공간 연결 리스트  

**설명 / 동작**
1. 객체 해제 시 해당 블록을 Free List에 연결  
2. 새 객체 할당 시 적절한 블록을 찾아 사용  
3. 단편화 심하면 Full GC로 압축  

- 목적: Old 영역 가변 크기 객체 할당/해제 효율화, 단편화 관리  

---

## 3.3 G1 GC (Garbage-First)

**핵심 키워드**
- Region 기반  
- SATB (pre-write) barrier  
- 예측 가능한 Pause Time  
- JDK 9+ 기본 GC  

**개념**
- CMS 단점을 개선한 현대적 GC  
- 힙을 균일 크기 Region (1~32MB) 단위로 분할  
- Garbage-First 전략: 회수 가치 높은 Region 우선 수집  
- `-XX:MaxGCPauseMillis`로 목표 pause 시간 설정 가능  

**메모리 & 데이터 구조**
- Region: Young / Old / Humongous  
- RSet (Remembered Set): cross-region 참조 추적  
- Card Table: 참조 변경 시 Dirty 상태 표시 → RSet 갱신  

**동작 방식**
1. Initial Mark (STW): 루트 연결 객체 마크  
2. Concurrent Mark: 여러 스레드가 SATB 기반으로 마킹  
3. Remark (STW): 변경된 참조 보정  
4. Cleanup: Collection Set(CSet) 선정  
5. Evacuation (STW, 병렬): CSet 객체를 다른 Region으로 복사  
6. Mixed GC: Young + Old 일부 Region 동시에 수거  

---

### G1 내부 보조기법

#### 3.1 RSet (Remembered Set)

**개념**
- 각 Region별로 다른 Region에서 참조하는 객체를 기록하는 메타데이터  

**설명 / 동작**
1. 애플리케이션이 객체 참조 변경 → Dirty Card 표시  
2. Dirty Card 기반으로 RSet 업데이트  
3. Collection Set 선정 시 RSet 참조 → cross-region 안전 수거  

- 목적: 전 힙 스캔 없이 partial GC 가능  

#### 3.2 Card Table

**개념**
- 작은 메모리 블록(카드) 단위로 Dirty 상태 표시  

**설명 / 동작**
1. 객체 참조 변경 시 해당 카드 Dirty 표시  
2. GC가 Dirty 카드만 확인 → RSet 갱신  

- 목적: 힙 전체 스캔 없이 cross-region 참조 추적  

#### 3.3 SATB (Snapshot-At-The-Beginning) Barrier

**개념**
- Pre-write barrier 방식으로 객체가 GC 시작 시점에 살아있었음을 스냅샷 기록  

**설명 / 동작**
1. 애플리케이션이 참조를 변경하기 전 기존 참조를 기록  
2. GC가 기록된 참조를 기반으로 concurrent 마킹 수행  

- 목적: concurrent GC 시 참조 누락 방지, pause 시간 최소화  

---

## ZGC (Z Garbage Collector)

### 1. 핵심 목표
- Ultra-low latency: Stop-The-World(STW) 시간을 수 밀리초 수준으로 제한  
- Concurrent GC: 대부분의 GC 작업을 애플리케이션과 동시에 수행  
- 대형 힙 지원: 수백 GB ~ TB 규모 힙에서도 효율적  
- 단편화 최소화: 객체 이동과 재배치로 힙 단편화 방지  

---

### 2. 주요 개념

#### 2.1 Non-generational vs Generational
- Non-generational (JDK 17까지): 세대 구분 없이 모든 객체를 동일하게 관리  
- Generational ZGC (JDK 21+): Young / Old 세대 구분, Hot/Cold 객체 관리를 통해 효율 향상  

#### 2.2 ZPage
- 힙을 균일한 크기의 페이지 단위로 관리  
- 각 ZPage는 Young, Old, Humongous Object 용도로 나뉨  
- 페이지 단위 이동과 수거 가능 → GC의 부분 동작 가능  

#### 2.3 Colored Pointer
- 64bit 포인터 상위 비트에 상태 정보 내장  
- 상태 정보 예시:  
  - 객체가 이동 중인지  
  - 객체가 참조 중인지  
  - 객체가 Old/Young/Humongous인지  
- Load/Read Barrier와 결합하여 객체 참조를 안전하게 재매핑  

#### 2.4 Load/Read Barrier
- 애플리케이션이 참조를 읽을 때, 참조가 올바른 위치를 가리키도록 보장  
- Barrier 동작 시점:  
  1. 참조 읽기  
  2. Colored Pointer 검사  
  3. 필요 시 새 주소로 리다이렉션  
- 목적: 거의 STW 없이 concurrent relocate 가능  

#### 2.5 Remap / Relocate Queue
- 이동 중인 객체의 새 위치 정보를 비동기로 기록  
- GC 스레드와 애플리케이션 스레드가 충돌하지 않고 이동/복사 수행  
- 동시성 유지와 ultra-low latency 달성에 핵심 역할  

---

### 3. 동작 단계 (Runtime Perspective)
1. **Concurrent Marking**  
   - 루트 객체에서 시작하여 접근 가능한 객체를 마킹  
   - 대부분 애플리케이션과 동시에 수행  
   - 객체 이동 여부를 Colored Pointer로 표시  
2. **Concurrent Relocate**  
   - 살아있는 객체를 새 위치로 복사  
   - Remap/Relocate Queue에 새 위치 기록  
   - 참조 갱신은 Load/Read Barrier를 통해 실시간 처리  
3. **Commit / Reclamation**  
   - 이전 위치를 힙에서 회수  
   - GC가 완료되면 모든 참조가 새 위치로 안정화  
4. **Humongous Object 처리**  
   - 큰 객체는 여러 ZPage를 연속으로 할당  
   - 이동 시 페이지 단위로 처리  
   - GC 효율과 pause time 제어 가능  

---

### 4. 내부 최적화 기법

#### 4.1 Concurrent Class Unloading
- 사용되지 않는 클래스와 메타데이터도 concurrent로 제거  
- 대형 애플리케이션에서 PermGen/Metaspace 압축 역할  

#### 4.2 Biased/Forwarding Pointer
- 객체가 이동 중일 때 임시 포인터를 설정  
- Load/Read Barrier가 이 포인터를 참조하여 애플리케이션에 올바른 주소 제공  

#### 4.3 Memory Coloring & Quiescence
- 힙 페이지 상태를 색상(bit)으로 표시  
- 페이지 quiescence 확인 후 이동 → 안정성 보장  

#### 4.4 Thread-Local Allocation Buffer (TLAB) 활용
- 애플리케이션 스레드가 빠르게 객체 할당  
- GC concurrent 진행 중에도 스레드 로컬 할당 충돌 최소화  

---

### 5. 장점
- Ultra-low latency: 수 ms 수준의 STW  
- 대형 힙 지원: TB 단위 힙에서도 안정적  
- 부분적인 GC 가능: 전체 힙 스캔 필요 없음  
- 세대 구분 가능(JDK 21+): Young/Old 분리로 효율 향상  

### 6. 고려 사항 / 단점
- 모든 참조 읽기에 Load/Read Barrier 오버헤드 존재  
- CPU 부담 증가  
- 매우 큰 Humongous Object 집중 시 GC 효율 저하 가능  
- 복잡한 구현으로 디버깅 난이도 상승  


---

## 4. 데이터 처리 파이프라인
1. **실행 & 로그 수집**
   - 각 GC 조합으로 벤치마크 실행
     
   실행 예시(G1 GC)
   ```java
   java -XX:+UseG1GC -Xms4g -Xmx4g -Xlog:gc*:file=gc-g1.log:time GCTest.java
   ```
   - 로그 수집 커맨드 설명
     -  +UseG1GC : 사용하는 GC 종류 설정 (JVM option)
     - -Xms4g -Xmx4g : JVM Heap size setting
       -  -Xms4g : JVM 시작 시 힙 크기 (4GB)
       -  -Xmx4g : JVM 최대 힙 크기 (4GB)
       -  초기와 최대 힙 크기를 같게 설정하면 GC 시 힙 크기 변화로 인한 변동이 없어서 GC 특성 측정이 일정해짐
     - -Xlog:gc*:file=gc-g1.log:time : GC 로그 기록 옵션
       - gc*: 모든 GC 이벤트(log level) 기록
       - file=gc-g1.log: 로그를 gc-g1.log 파일로 저장
       - time: 로그에 타임스탬프를 기록(이벤트 체크) 

3. **CSV 변환**
   - 생성형 AI를 이용해 GC 로그 → CSV 변환
   - 스키마: `timestamp, gc_id, phase, pause_ms, heap_before_mb, heap_after_mb, reclaimed_mb`

  - CSV 스키마 설명

| 컬럼명            | 설명                                                                 |
|-------------------|----------------------------------------------------------------------|
| `timestamp`       | GC 이벤트가 발생한 시각         |
| `gc_id`           | GC 이벤트를 구분하기 위한 고유 식별자                                |
| `phase`           | GC 수행 단계 (예: Young GC, Major GC, Remark, Cleanup 등)            |
| `phase_ms`        | 해당 단계(phase)에서 소요된 시간                       |
| `heap_before_mb`  | GC 직전의 힙 메모리 사용량                                 |
| `heap_after_mb`   | GC 직후의 힙 메모리 사용량                                  |
| `reclaimed_mb`    | GC로 인해 회수된 메모리 크기 (heap_before - heap_after 값, MB 단위) |

   - 변환 후 검증 필요: 이벤트 시퀀스/합계/타임스탬프 확인

3. **시각화**

| 구성요소           | 역할                                                                 |
|-------------------|----------------------------------------------------------------------|
| Node Exporter           | 시스템 자원 모니터링                                                                 |
| Prometheus           | 메트릭 수집 및 저장                                                                |
| Grafana           | 시각화 대시보드 생성                                                                |

   - Grafana에서 CSV Import
   - Prometheus(Node Exporter)에서 수집한 시스템 메트릭(**CPU, 메모리 리소스**)과 함께 대시보드 구성

---

## 5. 결과 및 분석 (Results & Analysis)

### 5.1 Serial ↔ Parallel (JDK 17)

#### STW 비교

<table>
  <tr>
    <td><img width="1200" height="600" alt="Image" src="https://github.com/user-attachments/assets/5857f948-5cab-420e-b1d3-3d5782fae181" /></td>
    <td><img width="534" height="336" alt="Image" src="https://github.com/user-attachments/assets/02b0656c-a993-4be0-86ef-e5fa89bc2ef9" /></td>
  </tr>
  <tr>
    <td>Serial GC 및 Parallel GC 평균 STW 그래프</td>
    <td>Serial GC 및 Parallel GC 평균 STW</td>
  </tr>
</table>

- **분석**
  - **Serial GC**는 단일 스레드로 GC를 수행하기 때문에 STW 구간이 평균 **985ms**로 측정되었으며, 전체적으로 Pause 시간이 길고 변동 폭이 컸다.  
  - **Parallel GC**는 멀티스레드로 수집을 병렬화하여 평균 **89.5ms** 수준으로 Pause를 크게 줄였다.  
  - STW 시간 추이를 보면 Serial은 초반부터 일관적으로 수백 ms 이상의 긴 Pause가 지속된 반면, Parallel은 100ms 이하에서 안정적으로 유지되었다.  
  - 따라서 **JDK 17 환경에서 처리량 중심 워크로드**에는 Parallel GC가 Serial GC보다 훨씬 유리함을 확인할 수 있었다.  

#### CPU 연산 시간 비교

<table>
  <tr>
    <td><img width="1126" height="576" alt="Image" src="https://github.com/user-attachments/assets/a4cc013c-40a1-43ec-b629-e5a53bab49e8" /></td>
    <td><img width="1152" height="586" alt="Image" src="https://github.com/user-attachments/assets/33ac9c51-8421-4459-8812-b44d8f4fb77f" /></td>
  </tr>
  <tr>
    <td>Serial GC CPU 연산 시간</td>
    <td>Parallel GC CPU 연산 시간</td>
  </tr>
</table>

- **분석**
  - **Serial GC**: 단일 스레드 기반으로 동작하기 때문에 `real_time`과 `sys+user`의 차이가 거의 없었음. 이는 곧 **직렬적으로 한 코어만 활용**했음을 보여준다.  
  - **Parallel GC**: `sys_time + user_time`이 `real_time`을 크게 초과하는 양상이 나타났는데, 이는 **실제 경과 시간보다 더 많은 CPU 시간을 여러 스레드에서 동시에 소비**했음을 의미한다.  
  - 즉, Serial은 **낮은 CPU 활용률**로 긴 STW 시간을 만들었고, Parallel은 **멀티스레드 활용**으로 Pause는 크게 줄였으나, **CPU 전체 사용률은 더 높아지는 트레이드오프**가 발생했다.  
  - 종합하면, **작은 코어 환경에서는 Serial이 CPU 효율적일 수 있으나 지연이 길고**, **멀티코어 환경에서는 Parallel이 Pause를 줄이는 데 훨씬 효과적**이라는 결론을 확인할 수 있다.

#### 리소스 사용량(CPU, RAM) 비교

<table>
  <tr>
    <td><img width="809" height="517" alt="Image" src="https://github.com/user-attachments/assets/06639d36-aacb-4979-892f-0240230cda54" /></td>
    <td><img width="825" height="521" alt="Image" src="https://github.com/user-attachments/assets/7cbdb77f-32fa-48e8-b129-a98516447e75" /></td>
  </tr>
  <tr>
    <td>Serial GC 리소스 사용량</td>
    <td>Parallel GC 리소스 사용량</td>
  </tr>
</table>

- **분석**
  - **Serial GC**  
    - CPU 사용률: 전체적으로 낮게 유지되며, Idle 상태가 대부분을 차지함. 단일 스레드 기반이므로 CPU 자원 활용이 제한적임.  
  - **Parallel GC**  
    - CPU 사용률: GC 수행 구간 동안 **멀티스레드 병렬 처리로 CPU 사용률이 급격히 상승**. Idle 비율이 크게 줄고 User 영역 사용률이 꾸준히 높게 나타남.  
  - **결론**  
    - Serial은 **CPU 자원 소모가 적지만 Pause가 길다**는 단점이 있고, Parallel은 **CPU를 적극 활용하여 Pause 시간을 줄였지만 전체 CPU 점유율이 높아지는 트레이드오프**가 존재한다.  

---

### 5.2 Parallel ↔ CMS (JDK 11)

#### STW 비교

<img width="698" height="326" alt="Image" src="https://github.com/user-attachments/assets/c3c69fa8-68fd-4e21-a86a-6033c5fb8e0a" />


#### CMS GC별 타입 비교

<img width="663" height="340" alt="Image" src="https://github.com/user-attachments/assets/393d4975-3fef-40f0-8c11-d09c94a6dd30" />


- **분석**
  - **Parallel GC**는 멀티스레드 수집으로 인해 평균 Pause가 상대적으로 낮게 유지되었음(수백 ms 수준). STW 구간이 명확히 존재하나, 병렬 처리를 통해 짧은 시간 안에 작업을 끝내는 패턴이 나타남.  
  - **CMS GC**는 stop-the-world를 줄이기 위해 concurrent 단계(동시 마킹, 스윕 등)를 포함하지만, **Full GC 상황에서는 Pause가 매우 길게 발생**했음.  
    - CMS 이벤트 타입별 평균 Pause:
      - Young GC: 약 **325 ms**  
      - Initial Mark: 약 **3.6 ms**  
      - Remark: 약 **21.9 ms**  
      - Full GC: 약 **1257 ms**  
  - 특히 Full GC 구간이 **1000ms 이상으로 압도적으로 길어**, Parallel보다 CMS 전체 평균 Pause가 더 길게 측정됨. 이는 CMS가 **메모리 단편화(Fragmentation)** 문제로 Full GC를 발생시키는 경우가 있기 때문.  
  - **결론**: CMS는 일반적인 Young/Remark 단계에서는 Pause가 짧지만, Full GC 발생 시 Parallel보다 훨씬 긴 지연을 만들어낸다. 따라서 CMS는 **지연 분포의 안정성이 낮고 tail latency가 크다**는 한계가 명확히 드러남.  

---

### 5.3 CMS ↔ G1 (JDK 11)

#### STW 비교

<img width="676" height="343" alt="Image" src="https://github.com/user-attachments/assets/e1ba80d1-841d-4169-8026-1dc3a2531cae" />

- **분석**
  - **CMS GC(파랑)**: Pause 시간이 평균 수백 ms 수준으로 더 길게 나타났고, 실행 전체 구간에서 일관적으로 높은 값을 유지했다. 이는 concurrent 단계로 평균 Pause를 줄이려 하지만, **단편화 및 Full GC 발생 시 매우 긴 STW**를 유발하기 때문이다.  
  - **G1 GC(주황)**: Pause 시간이 평균 수십 ms 수준으로 낮게 유지되었고, CMS 대비 변동성이 적으며 안정적인 지연 특성을 보였다. Region 단위 수집 구조 덕분에 **예측 가능한 Pause 관리**가 가능했다.  
  - **결론**: JDK 11 환경에서 **CMS는 평균 Pause가 길고 tail latency가 크며**, **G1은 Pause가 짧고 안정적**이었다. 따라서 운영 환경에서는 CMS보다 **G1이 안정성과 예측 가능성 측면에서 더 유리**하다.

#### CPU 연산 시간 비교

<img width="672" height="334" alt="Image" src="https://github.com/user-attachments/assets/e59d5729-165b-461a-9867-eed805fd451a" />

- **분석**
  - **CMS GC**: CPU 사용량이 전반적으로 G1보다 높게 유지되었다. concurrent 단계에서 애플리케이션 스레드와 동시에 동작하기 때문에 **추가적인 CPU 오버헤드**가 발생한 것으로 보인다.  
  - **G1 GC**: 상대적으로 낮은 CPU 사용률을 보였으며, Region 단위 수집을 통해 Pause 안정성을 유지하면서도 CMS보다 CPU 효율적으로 동작했다.  
  - **결론**: CMS는 Pause를 줄이려는 목적에서 concurrent 단계를 많이 수행하다 보니 **CPU 소모가 크다**는 단점이 드러났다. 반면, G1은 CMS 대비 **CPU 효율성**이 더 높고, Pause 안정성까지 제공하여 운영 환경에서 유리하다.

#### 리소스 사용량 비교

<img width="811" height="515" alt="Image" src="https://github.com/user-attachments/assets/7678e0f2-2f39-4cb5-b691-cb83337a1eb1" />




- **분석**
  - **CMS**: 동시 단계로 평균 Pause는 낮추지만, **단편화로 인한 예기치 않은 긴 Pause(Full GC)** 가능성.
  - **G1**: Region 기반 수집으로 **Pause 예측 가능성**이 높고, **지연 안정성**(특히 p95/p99)이 CMS 대비 우수.
  - **결론**: **운영 안정성·예측 가능성**을 중시할 때 G1이 CMS 대비 유리. CMS를 써야 하는 레거시 제약이 없다면 **G1 권장**.

---

### 5.4 G1 ↔ ZGC (JDK 17)

#### STW 비교

<table>
  <tr>
    <td><img width="1099" height="280" alt="Image" src="https://github.com/user-attachments/assets/e658af8e-2806-435f-9bb4-2a75b4b59543" /></td>
    <td><img width="684" height="303" alt="Image" src="https://github.com/user-attachments/assets/e2960945-519d-4a58-b407-bc83c17de536" /></td>
  </tr>
  <tr>
    <td>G1GC 및 ZGC 평균 STW 그래프</td>
    <td>G1GC 및 ZGC 평균 STW</td>
  </tr>
</table>

<img width="2224" height="1125" alt="Image" src="https://github.com/user-attachments/assets/00333f14-e1d2-4d3f-8735-fd3b5308a069" />

- **분석**
  - **G1 GC**: 평균 Pause가 약 **148 ms**로 측정되었으며, 실행 내내 수십~백 ms 단위의 STW가 꾸준히 발생했다. tail latency(p99)도 수백 ms 이상으로 늘어날 수 있어 지연에 민감한 워크로드에서는 한계가 보였다.  
  - **ZGC**: 평균 Pause가 **0.018 ms** 수준으로, 사실상 Pause가 없는 것처럼 동작했다. 아래 그래프에서도 STW가 대부분 **0.015~0.03 ms 범위**에서 안정적으로 유지되며 극도로 낮은 지연을 확인할 수 있었다.  
  - **비교**:  
    - G1은 Throughput 위주의 설계로 안정적이지만, 저지연 요구에는 한계가 있다.  
    - ZGC는 STW를 밀리초 단위가 아닌 **마이크로초 수준**까지 줄여 지연 민감한 시스템(실시간 서비스, 금융, 게임 서버 등)에 특히 적합하다.  
  - **결론**: JDK 17 환경에서 ZGC는 G1 대비 **압도적으로 짧은 Pause**를 제공하며, 저지연 성능 측면에서 명확한 우위를 보였다. 다만, ZGC는 동시 실행 단계가 많아 **CPU 오버헤드가 증가할 수 있다는 점**을 고려해야 한다.

#### GC 동작 후 Heap 사용량 비교 

<img width="1116" height="570" alt="Image" src="https://github.com/user-attachments/assets/a4235edf-9927-4f93-ada1-0128433bbedb" />

- **분석**
  - **G1 GC**: Region 단위로 힙을 관리하기 때문에 GC 후 메모리 사용량이 크게 출렁이는 패턴을 보였다. 최대 사용량은 3~4GB에 이르렀으며, 메모리 압박이 클 때는 힙 해제가 충분히 이뤄지지 않아 높은 값이 유지되는 구간도 있었다.  
  - **ZGC**: GC 후 힙 사용량이 약 **1~1.5GB 수준**에서 안정적으로 유지되었으며, 큰 변동 없이 부드러운 곡선을 형성했다. 이는 ZGC가 동시(compacting/concurrent) 수집을 통해 메모리 단편화를 줄이고, **힙 공간을 더 효율적으로 관리**했음을 보여준다.  
  - **결론**: G1은 힙 관리에서 변동성이 크고 순간적인 고사용량 구간이 많지만, ZGC는 훨씬 **일정하고 안정적인 메모리 사용량**을 보였다. 따라서 ZGC는 **메모리 예측 가능성**과 **안정성** 측면에서 G1보다 유리하다.

#### 리소스 사용량 비교

<table>
  <tr>
    <td><img width="811" height="515" alt="Image" src="https://github.com/user-attachments/assets/7678e0f2-2f39-4cb5-b691-cb83337a1eb1" /></td>
    <td><img width="809" height="497" alt="Image" src="https://github.com/user-attachments/assets/8c509a85-95ae-45d1-9dcf-de0c35350dc2" /></td>
  </tr>
  <tr>
    <td>G1GC 리소스 사용량</td>
    <td>ZGC 리소스 사용량</td>
  </tr>
</table>

- **분석**
  - **CPU 사용률**
    - **G1GC**: GC 수행 구간에서 CPU 사용률이 약 **60% 이상**까지 꾸준히 유지되었다. 멀티스레드 수집이지만 STW 비중이 크기 때문에 CPU 리소스가 집중적으로 소모되는 구간이 뚜렷했다.  
    - **ZGC**: CPU 사용률은 **20~30% 수준**에서 안정적으로 유지되었다. 동시 수집(concurrent) 단계가 많아 전체 CPU 사용 구간이 길게 분포하지만, G1처럼 급격히 치솟는 구간은 없었다.  

  - **메모리 사용량**
    - **G1GC**: GC 직후 메모리 사용량이 크게 줄었다가 다시 빠르게 증가하는 패턴을 반복했다. 메모리 변동성이 크며, 순간적으로 최대 **7~8GB** 가까이 사용되는 경우도 나타났다.  
    - **ZGC**: 메모리 사용량이 비교적 일정하게 유지되며, 약 **3~5GB 수준**에서 안정적으로 동작했다.  
      - 단, ZGC는 내부적으로 **가상 메모리를 mmap으로 매핑**하여 힙을 관리하기 때문에, OS 레벨에서는 실제 “사용 중(Used)” 메모리 대신 **캐시/버퍼 영역**으로 표시되는 경향이 있다.  
      - 따라서 Grafana 그래프에서 노란색(`Used`) 영역이 작게 나타나지만, 이는 메모리를 덜 쓰는 게 아니라 **OS 관점에서 캐시로 분류**되었기 때문이다.  

  - **결론**
    - **G1GC**: CPU와 메모리 모두에서 사용량의 변동성이 크고 리소스 소모가 집중적으로 발생한다.  
    - **ZGC**: CPU 사용률이 낮고, 메모리 사용량도 안정적으로 유지되며, OS 레벨에서 mmap 기반 관리 특성 때문에 캐시로 인식되지만 실제로는 효율적으로 힙을 관리한다.  
      → **리소스 효율성과 안정성 측면에서 G1보다 우위**를 보였다.


---

## 6. 실습 간 한계점 및 개선 방향
- 동일 테스트 코드 사용으로 인해 예상치 못한 결과 발생
- 워크로드와 힙 4GB라는 고정 조건에서 나온 결과 → 대형 힙/다코어 환경에서는 차이 확대 예상
- CMS는 JDK 14 이상에서는 사용할 수 없음 → JDK 11에서만 비교
- CSV 변환은 GPT를 통해 이루어졌으므로 **검증 필수**
- GC 로그 구조가 GC/버전별로 달라 동일 파서 적용 불가 → 수동 검증 절차 필요

---

## 7. 회고

김동민  </br>
이노운  </br>
전수민  </br>


