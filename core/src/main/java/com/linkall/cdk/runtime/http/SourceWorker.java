package com.linkall.cdk.runtime.http;

import com.linkall.cdk.config.SourceConfig;
import com.linkall.cdk.connector.Source;
import com.linkall.cdk.connector.Tuple;
import com.linkall.cdk.runtime.ConnectorWorker;
import com.linkall.cdk.util.EventUtil;
import io.cloudevents.http.vertx.VertxMessageFactory;
import io.cloudevents.jackson.JsonFormat;
import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientOptions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SourceWorker implements ConnectorWorker {
    private static final Logger LOGGER = LoggerFactory.getLogger(SourceWorker.class);
    private final WebClient webClient;
    private final CircuitBreaker breaker;

    private final Source source;
    private final SourceConfig config;
    private final ExecutorService executorService;
    private volatile boolean isRunning = true;
    private BlockingQueue<Tuple> queue;

    public SourceWorker(Source source, SourceConfig config) {
        this.source = source;
        this.config = config;
        Vertx vertx = Vertx.vertx();
        breaker = CircuitBreaker.create("my-circuit-breaker", vertx,
                new CircuitBreakerOptions()
                        .setMaxRetries(config.getSendEventAttempts() - 1)
                        .setTimeout(3000));
        webClient = WebClient.create(vertx, new WebClientOptions().setUserAgent("cdk-java-" + source.name()));
        executorService = Executors.newSingleThreadExecutor();
    }

    @Override
    public void start() {
        LOGGER.info("source worker starting");
        LOGGER.info("event target is {}", config.getTarget());
        try {
            new URL(config.getTarget());
        } catch (MalformedURLException e) {
            throw new RuntimeException(String.format("target is invalid %s", config.getTarget()), e);
        }
        queue = source.queue();
        executorService.execute(this::runLoop);
        LOGGER.info("source worker started");
    }

    @Override
    public void stop() {
        LOGGER.info("source worker stopping");
        isRunning = false;
        executorService.shutdown();
        try {
            source.destroy();
        } catch (Exception e) {
            LOGGER.error("source destroy error", e);
        }
        LOGGER.info("source worker stopped");
    }

    public void runLoop() {
        while (isRunning) {
            try {
                Tuple tuple = queue.poll(5, TimeUnit.SECONDS);
                if (tuple==null) {
                    continue;
                }
                LOGGER.info("new event:{}", tuple.getEvent().getId());
                breaker.execute(promise ->
                        VertxMessageFactory.createWriter(webClient.postAbs(config.getTarget()))
                                .writeStructured(tuple.getEvent(), JsonFormat.CONTENT_TYPE)
                                .onSuccess(r ->
                                        promise.complete()
                                ).onFailure(r ->
                                        LOGGER.info("send event error {}", tuple.getEvent().getId(), r.getCause())
                                )
                ).onSuccess(r -> {
                    LOGGER.debug("send event success {}", EventUtil.eventToJson(tuple.getEvent()));
                    if (tuple.getSuccess()!=null) {
                        tuple.getSuccess().call();
                    }
                }).onFailure(r -> {
                    LOGGER.warn("send event failed {}", EventUtil.eventToJson(tuple.getEvent()), r.getCause());
                    if (tuple.getFailed()!=null) {
                        tuple.getFailed().call(r.getMessage());
                    }
                });
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
}
