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

 - 예외처리
<br>
  휴식 → CPU 과부하 방지
<br>
  예외 발생 시 인터럽트 상태 유지 

  
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
   - Prometheus(Node Exporter)에서 수집한 시스템 메트릭과 함께 대시보드 구성

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


