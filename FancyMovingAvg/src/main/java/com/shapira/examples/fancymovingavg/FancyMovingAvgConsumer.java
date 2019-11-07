package com.shapira.examples.fancymovingavg;

import org.apache.commons.collections.buffer.CircularFifoBuffer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Like SimpleMovingAvg, it calculates moving average on integers from input topic.
 * Added some code to handle rebalances and such
 * This is just an example of the rebalance handling code
 * If you need all this fancy-schmancy stuff, you may just want to use KafkaStreams that handles it for you
 */
public class FancyMovingAvgConsumer {
    private Properties consumerProps = new Properties();
    private Properties producerProps = new Properties();
    private String waitTime;
    private KafkaConsumer<String, String> consumer;
    private KafkaProducer<String, String> producer;
    private Map<TopicPartition, OffsetAndMetadata> currentOffsets = new HashMap<>();

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("SimpleMovingAvgZkConsumer {brokers} {group.id} {topic} {window-size}");
            return;
        }

        final FancyMovingAvgConsumer movingAvg = new FancyMovingAvgConsumer();
        String brokers = args[0];
        String groupId = args[1];
        String topic = args[2];
        int window = Integer.parseInt(args[3]);

        CircularFifoBuffer buffer = new CircularFifoBuffer(window);
        movingAvg.configure(brokers, groupId);

        final Thread mainThread = Thread.currentThread();

        // Registering a shutdown hook so we can exit cleanly
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                System.out.println("Starting exit...");
                // Note that shutdownhook runs in a separate thread, so the only thing we can safely do to a consumer is wake it up
                movingAvg.consumer.wakeup();
                try {
                    mainThread.join();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });

        try {
            movingAvg.subscribe(Collections.singletonList(topic));

            // looping until ctrl-c, the shutdown hook will cleanup on exit
            while (true) {
                ConsumerRecords<String, String> records = movingAvg.consumer.poll(Duration.ofSeconds(1));
                movingAvg.producer.beginTransaction();
                System.out.println(System.currentTimeMillis() + "  --  waiting for data...");
                for (ConsumerRecord<String, String> record : records) {
                    System.out.printf("offset = %d, key = %s, value = %s\n", record.offset(), record.key(),
                            record.value());

                    int sum = 0;

                    try {
                        int num = Integer.parseInt(record.value());
                        buffer.add(num);
                    } catch (NumberFormatException e) {
                        // just ignore strings
                    }

                    for (Object o : buffer) {
                        sum += (Integer) o;
                    }

                    if (buffer.size() > 0) {
                        int calcAvg = (sum / buffer.size());
                        System.out.println("Moving avg is: " + calcAvg);
                        ProducerRecord<String, String> avg = new ProducerRecord<>(topic+"-avg", null, Integer.toString(calcAvg));
                        movingAvg.producer.send(avg);
                    }
                    movingAvg.currentOffsets.put(new TopicPartition(record.topic(), record.partition()), new OffsetAndMetadata(record.offset()+1, "no metadata"));
                }
                movingAvg.producer.sendOffsetsToTransaction(movingAvg.currentOffsets, groupId);
                movingAvg.producer.commitTransaction();  
            }
        } catch (WakeupException e) {
            // ignore for shutdown
        } finally {
            try {
                movingAvg.consumer.commitSync(movingAvg.currentOffsets);
            } finally {
                movingAvg.consumer.close();
                System.out.println("Closed consumer and we are done");
            }

        }
    }

    private void configure(String servers, String groupId) {
        consumerProps.put("group.id",groupId);
        consumerProps.put("bootstrap.servers",servers);
        consumerProps.put("auto.offset.reset","earliest");         // when in doubt, read everything
        consumerProps.put("key.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put("value.deserializer","org.apache.kafka.common.serialization.StringDeserializer");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        consumer = new KafkaConsumer<String, String>(consumerProps);

        producerProps.put("bootstrap.servers", servers);
        producerProps.put("transactional.id", "my-transactional-id");
        producer = new KafkaProducer<String,String>(producerProps, new StringSerializer(), new StringSerializer());
        producer.initTransactions();
    }

    private void subscribe(List<String> topics) {
        consumer.subscribe(topics);
    }

}
