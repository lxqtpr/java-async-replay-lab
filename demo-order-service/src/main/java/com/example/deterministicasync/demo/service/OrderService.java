package com.example.deterministicasync.demo.service;

import com.example.deterministicasync.demo.model.OrderStatus;
import com.example.deterministicasync.demo.persistence.OrderRepository;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OrderService {
    private final OrderRepository orderRepository;
    private final ExecutorService executorService;
    private final Duration minDelay;
    private final Duration maxDelay;
    private final int workerCount;
    private final BlockingQueue<UUID> queuedOrders = new LinkedBlockingQueue<>();
    private final ConcurrentHashMap.KeySetView<UUID, Boolean> processingOrders = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final List<Future<?>> workerFutures = new ArrayList<>();

    public OrderService(
            OrderRepository orderRepository,
            ExecutorService executorService,
            Duration minDelay,
            Duration maxDelay
    ) {
        this(orderRepository, executorService, minDelay, maxDelay, 5);
    }

    public OrderService(
            OrderRepository orderRepository,
            ExecutorService executorService,
            Duration minDelay,
            Duration maxDelay,
            int workerCount
    ) {
        this.orderRepository = orderRepository;
        this.executorService = executorService;
        this.minDelay = minDelay;
        this.maxDelay = maxDelay;
        if (workerCount <= 0) {
            throw new IllegalArgumentException("workerCount must be positive");
        }
        this.workerCount = workerCount;
        startWorkers();
    }

    public UUID createOrder(String customerId, BigDecimal amount) {
        if (!running.get()) {
            throw new IllegalStateException("OrderService is already stopped");
        }

        UUID orderId = orderRepository.createOrder(customerId, amount);
        queuedOrders.add(orderId);
        return orderId;
    }

    public Optional<OrderStatus> findStatus(UUID orderId) {
        return orderRepository.findStatus(orderId);
    }

    public int inFlightCount() {
        return queuedOrders.size() + processingOrders.size();
    }

    public int queueDepth() {
        return queuedOrders.size();
    }

    public void resetInFlightQueue() {
        queuedOrders.clear();
        processingOrders.clear();
    }

    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }

        for (Future<?> workerFuture : workerFutures) {
            workerFuture.cancel(true);
        }
        workerFutures.clear();
        resetInFlightQueue();
    }

    private void startWorkers() {
        for (int i = 0; i < workerCount; i++) {
            workerFutures.add(executorService.submit(this::workerLoop));
        }
    }

    private void workerLoop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            UUID orderId;
            try {
                orderId = queuedOrders.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }

            if (orderId == null) {
                continue;
            }

            processOrder(orderId);
        }
    }

    private void processOrder(UUID orderId) {
        processingOrders.add(orderId);
        try {
            orderRepository.updateStatus(orderId, OrderStatus.PROCESSING);
            randomDelay();
            orderRepository.updateStatus(orderId, OrderStatus.COMPLETED);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            orderRepository.updateStatus(orderId, OrderStatus.FAILED);
        } catch (Exception ex) {
            orderRepository.updateStatus(orderId, OrderStatus.FAILED);
        } finally {
            processingOrders.remove(orderId);
        }
    }

    private void randomDelay() throws InterruptedException {
        long minMs = minDelay.toMillis();
        long maxMs = maxDelay.toMillis();
        long delayMs = ThreadLocalRandom.current().nextLong(minMs, maxMs + 1);
        TimeUnit.MILLISECONDS.sleep(delayMs);
    }
}
