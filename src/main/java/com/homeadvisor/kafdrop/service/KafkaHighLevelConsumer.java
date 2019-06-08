package com.homeadvisor.kafdrop.service;

import com.fasterxml.jackson.databind.*;
import com.google.common.collect.*;
import com.homeadvisor.kafdrop.config.*;
import com.homeadvisor.kafdrop.model.*;
import com.homeadvisor.kafdrop.util.*;
import org.apache.kafka.clients.*;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.*;
import org.apache.kafka.common.config.*;
import org.apache.kafka.common.serialization.*;
import org.slf4j.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.*;

import javax.annotation.*;
import java.nio.*;
import java.time.*;
import java.util.*;
import java.util.stream.*;

@Service
public final class KafkaHighLevelConsumer {
  private static final int POLL_TIMEOUT_MS = 200;

  private final Logger LOG = LoggerFactory.getLogger(getClass());
  @Autowired
  private ObjectMapper objectMapper;
  private KafkaConsumer<String, byte[]> kafkaConsumer;

  @Autowired
  private KafkaConfiguration kafkaConfiguration;

  public KafkaHighLevelConsumer() {
  }

  @PostConstruct
  private void initializeClient() {
    if (kafkaConsumer == null) {
      final var properties = new Properties();
      properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
      properties.put(ConsumerConfig.GROUP_ID_CONFIG, "kafdrop-consumer-group");
      properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
      properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
      properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
      properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
      properties.put(ConsumerConfig.CLIENT_ID_CONFIG, "kafdrop-client");
      properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaConfiguration.getBrokerConnect());

      if (kafkaConfiguration.getIsSecured()) {
        properties.put(SaslConfigs.SASL_MECHANISM, kafkaConfiguration.getSaslMechanism());
        properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, kafkaConfiguration.getSecurityProtocol());
      }

      kafkaConsumer = new KafkaConsumer<>(properties);
    }
  }

  synchronized Map<Integer, TopicPartitionVO> getPartitionSize(String topic) {
    initializeClient();

    final var partitionInfoSet = kafkaConsumer.partitionsFor(topic);
    kafkaConsumer.assign(partitionInfoSet.stream()
                             .map(partitionInfo -> new TopicPartition(partitionInfo.topic(),
                                                                      partitionInfo.partition()))
                             .collect(Collectors.toList()));

    kafkaConsumer.poll(0);
    Set<TopicPartition> assignedPartitionList = kafkaConsumer.assignment();
    TopicVO topicVO = getTopicInfo(topic);
    Map<Integer, TopicPartitionVO> partitionsVo = topicVO.getPartitionMap();

    kafkaConsumer.seekToBeginning(assignedPartitionList);
    assignedPartitionList.stream().forEach(topicPartition -> {
      TopicPartitionVO topicPartitionVO = partitionsVo.get(topicPartition.partition());
      long startOffset = kafkaConsumer.position(topicPartition);
      LOG.debug("topic: {}, partition: {}, startOffset: {}", topicPartition.topic(), topicPartition.partition(), startOffset);
      topicPartitionVO.setFirstOffset(startOffset);
    });

    kafkaConsumer.seekToEnd(assignedPartitionList);
    assignedPartitionList.stream().forEach(topicPartition -> {
      long latestOffset = kafkaConsumer.position(topicPartition);
      LOG.debug("topic: {}, partition: {}, latestOffset: {}", topicPartition.topic(), topicPartition.partition(), latestOffset);
      TopicPartitionVO partitionVO = partitionsVo.get(topicPartition.partition());
      partitionVO.setSize(latestOffset);
    });
    return partitionsVo;
  }

  synchronized List<ConsumerRecord<String, String>> getLatestRecords(TopicPartition topicPartition, long offset, Long count,
                                                                     MessageDeserializer deserializer) {
    initializeClient();
    kafkaConsumer.assign(Collections.singletonList(topicPartition));
    kafkaConsumer.seek(topicPartition, offset);

    final var rawRecords = kafkaConsumer.poll(Duration.ofMillis(POLL_TIMEOUT_MS));
    final var numRecords = rawRecords.count();
    return rawRecords.records(topicPartition)
        .subList(0, Math.min(count.intValue(), numRecords))
        .stream()
        .map(rec -> new ConsumerRecord<>(rec.topic(),
                                         rec.partition(),
                                         rec.offset(),
                                         rec.timestamp(),
                                         rec.timestampType(),
                                         0L,
                                         rec.serializedKeySize(),
                                         rec.serializedValueSize(),
                                         rec.key(),
                                         deserializer.deserializeMessage(ByteBuffer.wrap(rec.value())),
                                         rec.headers(),
                                         rec.leaderEpoch()))
        .collect(Collectors.toList());
  }

  synchronized Map<String, TopicVO> getTopicsInfo(String[] topics) {
    initializeClient();
    if (topics.length == 0) {
      final var topicSet = kafkaConsumer.listTopics().keySet();
      topics = Arrays.copyOf(topicSet.toArray(), topicSet.size(), String[].class);
    }
    final var topicVos = new HashMap<String, TopicVO>();

    for (var topic : topics) {
      topicVos.put(topic, getTopicInfo(topic));
    }

    return topicVos;
  }

  private TopicVO getTopicInfo(String topic) {
    List<PartitionInfo> partitionInfoList = kafkaConsumer.partitionsFor(topic);
    TopicVO topicVO = new TopicVO(topic);
    Map<Integer, TopicPartitionVO> partitions = new TreeMap<>();

    for (PartitionInfo partitionInfo : partitionInfoList) {
      TopicPartitionVO topicPartitionVO = new TopicPartitionVO(partitionInfo.partition());

      final Node leader = partitionInfo.leader();
      if (leader != null) {
        topicPartitionVO.addReplica(new TopicPartitionVO.PartitionReplica(leader.id(), true, true));
      }

      for (Node node : partitionInfo.replicas()) {
        topicPartitionVO.addReplica(new TopicPartitionVO.PartitionReplica(node.id(), true, false));
      }
      partitions.put(partitionInfo.partition(), topicPartitionVO);
    }

    topicVO.setPartitions(partitions);
    return topicVO;
  }
}
