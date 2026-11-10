package com.coremaker.ledger.integration.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.IntConsumer;

public final class ConcurrentAttempts {

    private ConcurrentAttempts() {
    }

    public static List<Throwable> runAtOnce(int attempts, IntConsumer action) throws Exception {

        var start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(attempts);
        try {
            var outcomes = new ArrayList<Future<?>>();
            for (int attempt = 0; attempt < attempts; attempt++) {

                var current = attempt;
                outcomes.add(executor.submit(() -> {

                    start.await();
                    action.accept(current);
                    return null;
                }));
            }

            start.countDown();
            var failures = new ArrayList<Throwable>();
            for (var outcome : outcomes) {

                try {
                    outcome.get();
                } catch (ExecutionException failure) {
                    failures.add(failure.getCause());
                }
            }

            return failures;
        } finally {
            executor.shutdown();
        }
    }
}
