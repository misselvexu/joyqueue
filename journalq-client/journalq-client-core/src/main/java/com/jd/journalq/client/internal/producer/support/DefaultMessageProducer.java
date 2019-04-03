package com.jd.journalq.client.internal.producer.support;

import com.jd.journalq.client.internal.cluster.ClusterManager;
import com.jd.journalq.client.internal.metadata.domain.TopicMetadata;
import com.jd.journalq.client.internal.nameserver.NameServerConfig;
import com.jd.journalq.client.internal.producer.MessageProducer;
import com.jd.journalq.client.internal.producer.MessageSender;
import com.jd.journalq.client.internal.producer.TransactionMessageProducer;
import com.jd.journalq.client.internal.producer.callback.AsyncBatchProduceCallback;
import com.jd.journalq.client.internal.producer.callback.AsyncProduceCallback;
import com.jd.journalq.client.internal.producer.config.ProducerConfig;
import com.jd.journalq.client.internal.producer.config.SenderConfig;
import com.jd.journalq.client.internal.producer.domain.ProduceMessage;
import com.jd.journalq.client.internal.producer.domain.SendResult;
import com.jd.journalq.client.internal.producer.exception.ProducerException;
import com.jd.journalq.client.internal.producer.interceptor.ProducerInterceptor;
import com.jd.journalq.client.internal.producer.interceptor.ProducerInterceptorManager;
import com.jd.journalq.client.internal.producer.transport.ProducerClientManager;
import com.jd.journalq.exception.JMQCode;
import com.jd.journalq.toolkit.concurrent.SimpleFuture;
import com.jd.journalq.toolkit.lang.Preconditions;
import com.jd.journalq.toolkit.service.Service;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DefaultMessageProducer
 * author: gaohaoxiang
 * email: gaohaoxiang@jd.com
 * date: 2018/12/10
 */
public class DefaultMessageProducer extends Service implements MessageProducer {

    protected static final Logger logger = LoggerFactory.getLogger(DefaultMessageProducer.class);

    private ProducerConfig config;
    private NameServerConfig nameServerConfig;
    private ClusterManager clusterManager;
    private ProducerClientManager producerClientManager;

    private SenderConfig senderConfig;
    private MessageSender messageSender;
    private AtomicLong transactionSequence;
    private MessageProducerInner messageProducerInner;
    private ProducerInterceptorManager producerInterceptorManager = new ProducerInterceptorManager();

    public DefaultMessageProducer(ProducerConfig config, NameServerConfig nameServerConfig, ClusterManager clusterManager, ProducerClientManager producerClientManager) {
        Preconditions.checkArgument(config != null, "producer not null");
        Preconditions.checkArgument(nameServerConfig != null, "nameServer not null");
        Preconditions.checkArgument(clusterManager != null, "clusterManager not null");
        Preconditions.checkArgument(producerClientManager != null, "producerClientManager not null");
        Preconditions.checkArgument(StringUtils.isNotBlank(config.getApp()), "producer.app not blank");
        Preconditions.checkArgument(config.getRetryPolicy() != null, "producer.retryPolicy not null");
        Preconditions.checkArgument(config.getQosLevel() != null, "producer.qosLevel not null");

        this.config = config;
        this.nameServerConfig = nameServerConfig;
        this.clusterManager = clusterManager;
        this.producerClientManager = producerClientManager;
    }

    @Override
    protected void validate() throws Exception {
        transactionSequence = new AtomicLong();
        senderConfig = new SenderConfig(config.isCompress(), config.getCompressThreshold(), config.getCompressType(), config.isBatch());
        messageSender = new DefaultMessageSender(producerClientManager, senderConfig);
        messageProducerInner = new MessageProducerInner(config, nameServerConfig, messageSender, clusterManager, producerClientManager, producerInterceptorManager);
    }

    @Override
    protected void doStart() throws Exception {
        messageSender.start();
        messageProducerInner.start();
    }

    @Override
    protected void doStop() {
        if (messageProducerInner != null) {
            messageProducerInner.stop();
        }
        if (messageSender != null) {
            messageSender.stop();
        }
    }

    @Override
    public SendResult send(ProduceMessage message) {
        return send(message, config.getTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public SendResult send(ProduceMessage message, long timeout, TimeUnit timeoutUnit) {
        return doSend(message, timeout, timeoutUnit, false, null);
    }

    @Override
    public List<SendResult> batchSend(List<ProduceMessage> messages) {
        return batchSend(messages, config.getTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public List<SendResult> batchSend(List<ProduceMessage> messages, long timeout, TimeUnit timeoutUnit) {
        return doBatchSend(messages, timeout, timeoutUnit, false, null);
    }

    @Override
    public void sendOneway(ProduceMessage message) {
        sendOneway(message, config.getTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void sendOneway(ProduceMessage message, long timeout, TimeUnit timeoutUnit) {
        doSend(message, timeout, timeoutUnit, true, null);
    }

    @Override
    public void batchSendOneway(List<ProduceMessage> messages) {
        batchSendOneway(messages, config.getTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void batchSendOneway(List<ProduceMessage> messages, long timeout, TimeUnit timeoutUnit) {
        doBatchSend(messages, timeout, timeoutUnit, true, null);
    }

    @Override
    public void sendAsync(ProduceMessage message, AsyncProduceCallback callback) {
        Preconditions.checkArgument(callback != null, "callback not null");
        sendAsync(message, config.getTimeout(), TimeUnit.MILLISECONDS, callback);
    }

    @Override
    public void sendAsync(ProduceMessage message, long timeout, TimeUnit timeoutUnit, AsyncProduceCallback callback) {
        Preconditions.checkArgument(callback != null, "callback not null");
        doSend(message, timeout, timeoutUnit, false, callback);
    }

    @Override
    public void batchSendAsync(List<ProduceMessage> messages, AsyncBatchProduceCallback callback) {
        Preconditions.checkArgument(callback != null, "callback not null");
        batchSendAsync(messages, config.getTimeout(), TimeUnit.MILLISECONDS, callback);
    }

    @Override
    public void batchSendAsync(List<ProduceMessage> messages, long timeout, TimeUnit timeoutUnit, AsyncBatchProduceCallback callback) {
        Preconditions.checkArgument(callback != null, "callback not null");
        doBatchSend(messages, timeout, timeoutUnit, false, callback);
    }

    @Override
    public Future<SendResult> sendAsync(ProduceMessage message) {
        return sendAsync(message, config.getTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Future<SendResult> sendAsync(ProduceMessage message, long timeout, TimeUnit timeoutUnit) {
        final SimpleFuture<SendResult> future = new SimpleFuture<SendResult>();
        doSend(message, timeout, timeoutUnit, false, new AsyncProduceCallback() {
            @Override
            public void onSuccess(ProduceMessage message, SendResult result) {
                future.setResponse(result);
            }

            @Override
            public void onException(ProduceMessage message, Throwable cause) {
                future.setThrowable(cause);
            }
        });
        return future;
    }

    @Override
    public Future<List<SendResult>> batchSendAsync(List<ProduceMessage> messages) {
        return batchSendAsync(messages, config.getTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public Future<List<SendResult>> batchSendAsync(List<ProduceMessage> messages, long timeout, TimeUnit timeoutUnit) {
        final SimpleFuture<List<SendResult>> future = new SimpleFuture<List<SendResult>>();
        doBatchSend(messages, timeout, timeoutUnit, false, new AsyncBatchProduceCallback() {
            @Override
            public void onSuccess(List<ProduceMessage> messages, List<SendResult> result) {
                future.setResponse(result);
            }

            @Override
            public void onException(List<ProduceMessage> messages, Throwable cause) {
                future.setThrowable(cause);
            }
        });
        return future;
    }

    protected SendResult doSend(ProduceMessage message, long timeout, TimeUnit timeoutUnit, boolean isOneway, AsyncProduceCallback callback) {
        checkState();
        return messageProducerInner.send(message, null, timeout, timeoutUnit, isOneway, config.isFailover(), callback);
    }

    protected List<SendResult> doBatchSend(List<ProduceMessage> messages, long timeout, TimeUnit timeoutUnit, boolean isOneway, AsyncBatchProduceCallback callback) {
        checkState();
        return messageProducerInner.batchSend(messages, null, timeout, timeoutUnit, isOneway, config.isFailover(), callback);
    }

    @Override
    public TransactionMessageProducer beginTransaction() {
        return beginTransaction(config.getTransactionTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public TransactionMessageProducer beginTransaction(long timeout, TimeUnit timeoutUnit) {
        return new DefaultTransactionMessageProducer(null, timeout, timeoutUnit, transactionSequence.getAndIncrement(), config, nameServerConfig, clusterManager, messageSender, messageProducerInner);
    }

    @Override
    public TransactionMessageProducer beginTransaction(String transactionId) {
        return beginTransaction(transactionId, config.getTransactionTimeout(), TimeUnit.MILLISECONDS);
    }

    @Override
    public TransactionMessageProducer beginTransaction(String transactionId, long timeout, TimeUnit timeoutUnit) {
        checkState();
        Preconditions.checkArgument(StringUtils.isNotBlank(transactionId), "transactionId not blank");
        return new DefaultTransactionMessageProducer(transactionId, timeout, timeoutUnit, transactionSequence.getAndIncrement(), config, nameServerConfig, clusterManager, messageSender, messageProducerInner);
    }

    @Override
    public TopicMetadata getTopicMetadata(String topic) {
        checkState();
        Preconditions.checkArgument(StringUtils.isNotBlank(topic), "topic not blank");

        String topicFullName = messageProducerInner.getTopicFullName(topic);
        return clusterManager.fetchTopicMetadata(topicFullName, config.getApp());
    }

    @Override
    public synchronized void addInterceptor(ProducerInterceptor interceptor) {
        Preconditions.checkArgument(interceptor != null, "interceptor can not be null");

        producerInterceptorManager.addInterceptor(interceptor);
    }

    @Override
    public synchronized void removeInterceptor(ProducerInterceptor interceptor) {
        Preconditions.checkArgument(interceptor != null, "interceptor can not be null");

        producerInterceptorManager.removeInterceptor(interceptor);
    }

    protected void checkState() {
        if (!isStarted()) {
            throw new ProducerException("producer is not started", JMQCode.CN_SERVICE_NOT_AVAILABLE.getCode());
        }
    }
}