package com.inmobi.messaging.consumer;

import java.io.IOException;
import java.util.Date;

import com.inmobi.instrumentation.MessagingClientMetrics;
import com.inmobi.instrumentation.MessagingClientStats;
import com.inmobi.messaging.ClientConfig;
import com.inmobi.messaging.Message;

/**
 * Abstract class implementing {@link MessageConsumer} interface.
 * 
 * It provides the access to configuration parameters({@link ClientConfig}) for
 * consumer interface
 * 
 * It initializes topic name, consumer name and startTime. 
 * startTime is the time from which messages should be consumed. 
 * <ul>
 * <li>if no
 * startTime is passed, messages will be consumed from last marked position.
 * <li>
 * If there is no last marked position, messages will be consumed from the
 * starting of the available stream i.e. all messages that are not purged.
 * <li>If startTime or last marked position is beyond the retention
 * period of the stream, messages will be consumed from starting of the
 * available stream.
 *</ul>
 */
public abstract class AbstractMessageConsumer implements MessageConsumer {

  private ClientConfig config;
  protected String topicName;
  protected String consumerName;
  protected Date startTime;
  private MessageConsumerMetricsBase metrics;
  private MessagingClientStats statsEmitter = new MessagingClientStats();

  /**
   * Initialize the consumer with passed configuration object
   * 
   * @param config {@link ClientConfig} for the consumer
   * @throws IOException 
   */
  protected void init(ClientConfig config) throws IOException {
    this.config = config;
  }

  protected abstract MessagingClientMetrics getMetricsImpl();

  protected abstract void doMark() throws IOException;

  protected abstract void doReset() throws IOException;

  protected abstract Message getNext() throws InterruptedException;

  public synchronized Message next() throws InterruptedException {
    Message msg = getNext();
    metrics.addMessagesConsumed();
    return msg;
  }

  public synchronized void mark() throws IOException {
    if (isMarkSupported()) {
      doMark();
      metrics.addMarkCalls();
    }
  }

  public synchronized void reset() throws IOException {
    if (isMarkSupported()) {
      doReset();
      metrics.addResetCalls();
    }
  }

  /**
   * Initialize the consumer with passed configuration object, streamName and 
   * consumerName and startTime.
   * 
   * @param topicName Name of the topic being consumed
   * @param consumerName Name of the consumer
   * @param startTime Starting time from which messages should be consumed
   * @param config {@link ClientConfig} for the consumer
   * @throws IOException 
   */
  public void init(String topicName, String consumerName, Date startTimestamp,
      ClientConfig config) throws IOException {
    this.topicName = topicName;
    this.consumerName = consumerName;
    this.startTime = startTimestamp;
    // do not accept start time in future
    if (startTime != null && 
        startTime.after(new Date(System.currentTimeMillis()))) {
      throw new IllegalArgumentException("Future start time is not accepted");
    }
    metrics = (MessageConsumerMetricsBase) getMetricsImpl();
    String emitterConfig = config
        .getString(MessageConsumerFactory.EMITTER_CONF_FILE_KEY);
    if (emitterConfig != null) {
      statsEmitter.init(emitterConfig, metrics.getStatsMap(),
          metrics.getContexts());
    }
    init(config);
  }

  /**
   * Get the configuration of the consumer.
   * 
   * @return {@link ClientConfig} object
   */
  public ClientConfig getConfig() {
    return this.config;
  }

  /**
   * Get the topic name being consumed.
   * 
   * @return String topicName
   */
  public String getTopicName() {
    return topicName;
  }

  /**
   * Get the consumer name
   * 
   * @return String consumerName
   */
  public String getConsumerName() {
    return consumerName;
  }

  /**
   * Get the starting time of the consumption.
   * 
   * @return Date object
   */
  public Date getStartTime() {
    return startTime;
  }

  /**
   * Get the consumer metrics object
   * 
   * @return MessageConsumerMetrics object
   */
  public MessagingClientMetrics getMetrics() {
    return metrics;
  }

  /**
   * Get the client stats object
   * 
   * @return MessagingClientStats object
   */
  MessagingClientStats getStats() {
    return statsEmitter;
  }

}
