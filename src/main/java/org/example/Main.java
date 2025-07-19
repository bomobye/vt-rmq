package org.example;

import com.rabbitmq.client.*;
import java.io.*;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

public class Main {

    public static void main(String[] args) throws Exception {
        // 🔧 Parametreleri al
        Map<String, String> argMap = parseArgs(args);
        String host = argMap.getOrDefault("RABBIT_HOST", "localhost");
        String queueName = argMap.getOrDefault("QUEUE_NAME", "perf.queue");
        int duration = Integer.parseInt(argMap.getOrDefault("TEST_DURATION_SECONDS", "10"));
        int[] connOptions = parseIntList(argMap.getOrDefault("connOptions", "4,8,16"));
        int[] channelsPerConnOptions = parseIntList(argMap.getOrDefault("channelPerConnOptions", "32,64"));
        String filePath = argMap.getOrDefault("filepath", "sample.jpg");
        String replyTo_ = argMap.getOrDefault("REPLY_TO", "perf_reply.queue");

        byte[] message = Files.readAllBytes(new File(filePath).toPath());

        try (PrintWriter csv = new PrintWriter(new FileWriter("output.csv"))) {
            csv.println("connections,channels_per_conn,total_channels,total_threads,tps_avg,latency_avg_ms");

            for (int connCount : connOptions) {
                for (int channelPerConn : channelsPerConnOptions) {
                    int totalChannels = connCount * channelPerConn;

                    System.out.printf("▶️ Testing: %d connections x %d channels = %d channels\n",
                            connCount, channelPerConn, totalChannels);

                    Result result = runTest(host, queueName, connCount, channelPerConn, totalChannels, duration, message, replyTo_);
                    csv.printf("%d,%d,%d,%d,%d,%.2f\n",
                            connCount, channelPerConn, totalChannels, totalChannels,
                            result.totalMessages, result.avgLatencyMs);
                }
            }
        }

        System.out.println("✅ Test tamamlandı. Sonuçlar: output.csv");
    }

    private static Result runTest(String host, String queueName, int connCount, int channelsPerConn, int threadCount,
                                  int durationSeconds, byte[] message, String replyTo_) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        AtomicLong counter = new AtomicLong();
        LongAdder totalLatency = new LongAdder();

        // 🔌 Bağlantı kur
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(host);
        factory.setUsername("guest");
        factory.setPassword("guest");
        factory.setAutomaticRecoveryEnabled(true);

        // 🧹 Temizle
        try (Connection purgeConn = factory.newConnection();
             Channel purgeCh = purgeConn.createChannel()) {
            purgeCh.queueDeclare(queueName, false, false, false, null);
            purgeCh.queuePurge(queueName);
            System.out.println("🧹 Queue temizlendi: " + queueName);
        }

//        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
//                .deliveryMode(1)
//                .replyTo(replyTo_.isEmpty() ? null : replyTo_)
//                .build();

        Connection[] connections = new Connection[connCount];
        for (int i = 0; i < connCount; i++) {
            connections[i] = factory.newConnection();
        }

        Channel[] channels = new Channel[threadCount];
        for (int i = 0; i < threadCount; i++) {
            int connIndex = i % connCount;
            channels[i] = connections[connIndex].createChannel();
            channels[i].queueDeclare(queueName, false, false, false, null);
        }

        Instant endTime = Instant.now().plusSeconds(durationSeconds);
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            executor.submit(() -> {
                Channel channel = channels[index];
                try {
                    while (Instant.now().isBefore(endTime)) {
                        Instant start = Instant.now();
                        String correlationId = UUID.randomUUID().toString();
                        AMQP.BasicProperties props = new AMQP.BasicProperties.Builder()
                                .deliveryMode(1)
                                .replyTo(replyTo_)
                                .correlationId(correlationId)
                                .build();
                        channel.basicPublish("", queueName, props, message);
                        long latency = Duration.between(start, Instant.now()).toNanos();
                        totalLatency.add(latency);
                        counter.incrementAndGet();
                    }
                } catch (IOException | ShutdownSignalException ex) {
                    System.err.println("⚠️ Kanal kopmuş! Recreate ediliyor...");
                    try {
                        int connIndex = index % connections.length;
                        channel = connections[connIndex].createChannel();
                        channel.queueDeclare(queueName, false, false, false, null);
                        channels[index] = channel;  // replace with new one
                    } catch (Exception e) {
                        System.err.println("❌ Kanal yeniden oluşturulamadı. Bekleniyor...");
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        }

        Thread.sleep(durationSeconds * 1000L + 2000);

        for (Channel channel : channels) channel.close();
        for (Connection conn : connections) conn.close();
        executor.shutdownNow();

        long totalMessages = counter.get();
        double avgLatencyMs = totalLatency.sum() / 1_000_000.0 / totalMessages;
        return new Result(totalMessages / durationSeconds, avgLatencyMs);
    }

    private static int[] parseIntList(String csv) {
        return Arrays.stream(csv.split(",")).mapToInt(Integer::parseInt).toArray();
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> map = new HashMap<>();
        for (String arg : args) {
            String[] kv = arg.split("=", 2);
            if (kv.length == 2) map.put(kv[0], kv[1]);
        }
        return map;
    }

    private record Result(long totalMessages, double avgLatencyMs) {}
}
