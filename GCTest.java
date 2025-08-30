import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class GCTest {
    static final int OBJECT_COUNT = 100_000;
    static final int OBJECT_SIZE_BYTES = 1024;
    static final List<Object> memoryHog = new ArrayList<>();

    static class HeavyObject {
        byte[] data = new byte[OBJECT_SIZE_BYTES];
        List<byte[]> nested = new ArrayList<>();

        HeavyObject() {
            for (int i = 0; i < 5; i++) {
                nested.add(new byte[OBJECT_SIZE_BYTES / 2]);
            }
        }
    }

    public static void main(String[] args) {
        ExecutorService executor = Executors.newFixedThreadPool(4);

        for (int t = 0; t < 4; t++) {
            executor.submit(() -> {
                List<Object> localList = new ArrayList<>();
                while (true) {
                    for (int i = 0; i < OBJECT_COUNT / 4; i++) {
                        localList.add(new HeavyObject());
                    }

                    if (localList.size() > 100_000) {
                        localList.clear();
                    }

                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
        }

        // 5분 타이머 시작
        long startTime = System.currentTimeMillis();
        long duration = 5 * 60 * 1000; // 5분 = 300,000ms

        int expansionRate = 0;
        while (System.currentTimeMillis() - startTime < duration) {
            memoryHog.add(new HeavyObject());

            if (memoryHog.size() > 500_000 + expansionRate) {
                memoryHog.clear();
                System.gc();
                System.out.println("== 강제 Full GC 요청 ==");
                expansionRate += 50_000;
            }

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        System.out.println("✅ 5분간 테스트 종료");
        System.exit(0);
    }
}
