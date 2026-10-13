import java.time.Instant;
import java.io.BufferedReader;
import java.io.InputStreamReader;

// Test harness only. Persistent JVM avoids attributing JVM startup latency to clock skew.
class PhaseAClockProbe {
    public static void main(String[] args) throws Exception {
        System.out.println("READY");
        var input = new BufferedReader(new InputStreamReader(System.in));
        while (input.readLine() != null) {
            var now = Instant.now();
            System.out.println(now.getEpochSecond() + now.getNano() / 1_000_000_000.0);
        }
    }
}
