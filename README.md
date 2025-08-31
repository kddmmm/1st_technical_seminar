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
  - **Serial**: 단일 스레드 수집으로 **Pause가 길고** 변동 폭이 큼. 작은 코어/작은 힙에서 단순성은 장점이나 **처리량(Throughput)**은 제한적.
  - **Parallel**: 멀티스레드 수집으로 **총 실행 시간 대비 오버헤드가 낮아지고 처리량이 향상**. 다만 STW 자체는 확실히 존재하여 **tail(p99)**이 길어질 수 있음.
  - **CPU 관점**: Parallel에서 **CPU 사용률이 높게 유지**되며, 짧은 시간에 GC를 몰아 처리하는 패턴이 관찰됨.

---

### 5.2 Parallel ↔ CMS (JDK 11)

#### STW 비교

<img width="698" height="326" alt="Image" src="https://github.com/user-attachments/assets/c3c69fa8-68fd-4e21-a86a-6033c5fb8e0a" />

#### 리소스 사용량 비교

CMS 미진행


- **분석**
  - **Parallel**: **Throughput 최적화** 성향이 뚜렷. STW 구간이 길 수 있으나 전체 처리량 관점에서 유리.
  - **CMS**: Remark/cleanup 등 **짧은 Pause를 여러 번** 발생시키며 평균 Pause는 줄 수 있으나, **tail(p99) 불안정** 및 **프래그멘테이션 위험**이 존재.
  - **결론**: **지연보다 처리량을 우선**하는 워크로드라면 Parallel, **사용자 체감 지연**을 낮추고 싶다면 CMS가 유효. 단, CMS는 운영 리스크(단편화/Full GC)가 있어 튜닝과 모니터링이 필수.

---

### 5.3 CMS ↔ G1 (JDK 11)

#### STW 비교

<img width="676" height="343" alt="Image" src="https://github.com/user-attachments/assets/e1ba80d1-841d-4169-8026-1dc3a2531cae" />

#### CPU 연산 시간 비교

<img width="672" height="334" alt="Image" src="https://github.com/user-attachments/assets/e59d5729-165b-461a-9867-eed805fd451a" />

#### 리소스 사용량 비교

<img width="811" height="515" alt="Image" src="https://github.com/user-attachments/assets/7678e0f2-2f39-4cb5-b691-cb83337a1eb1" />


#### CMS GC별 타비 비교

<img width="663" height="340" alt="Image" src="https://github.com/user-attachments/assets/393d4975-3fef-40f0-8c11-d09c94a6dd30" />


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



#### GC 동작 후 Heap 사용량 비교 

<img width="1116" height="570" alt="Image" src="https://github.com/user-attachments/assets/a4235edf-9927-4f93-ada1-0128433bbedb" />

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
  - **ZGC**: 대부분의 STW가 **수 ms 이하**로 나타나 **저지연**이 탁월. 작은 힙(4GB)에서도 p99가 매우 낮게 유지되는 경향.
  - **G1**: 평균 Pause는 짧지만 **tail(p99)가 수십 ms**로 치솟는 케이스가 관찰될 수 있음. 대신 **Throughput은 ZGC 대비 다소 유리**한 경향.
  - **CPU 관점**: ZGC는 동시 단계가 많아 **CPU 오버헤드가 상대적으로 높게** 관측될 수 있음. G1은 **리소스 효율** 측면에서 균형적.
  - **결론**: **지연(레イ턴시) 최우선**이면 ZGC, **처리량·효율** 균형이면 G1. 워크로드 특성(객체 생존/할당률)에 따라 선택.


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


