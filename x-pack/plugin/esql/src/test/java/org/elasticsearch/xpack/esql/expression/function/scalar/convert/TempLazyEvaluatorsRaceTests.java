/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the Elastic License
 * 2.0; you may not use this file except in compliance with the Elastic License
 * 2.0.
 */

package org.elasticsearch.xpack.esql.expression.function.scalar.convert;

import org.elasticsearch.test.ESTestCase;
import org.elasticsearch.xpack.esql.EsqlTestUtils;
import org.elasticsearch.xpack.esql.core.expression.Literal;
import org.elasticsearch.xpack.esql.core.tree.Source;
import org.elasticsearch.xpack.esql.core.type.DataType;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * EVIDENCE for #164188 - not for merge. Hammers {@link ToDateNanos#factories()} on fresh instances from several
 * threads moving in lockstep, counting how often a thread gets the {@code null}-returning placeholder.
 */
public class TempLazyEvaluatorsRaceTests extends ESTestCase {
    public void testRace() throws Exception {
        int threads = Integer.getInteger("tests.race.threads", 4);
        int batch = 2048;
        long seconds = Long.getLong("tests.race.seconds", 120);
        ToDateNanos[] instances = new ToDateNanos[batch];
        AtomicBoolean stop = new AtomicBoolean();
        AtomicLong rounds = new AtomicLong();
        AtomicLong placeholders = new AtomicLong();
        AtomicLong missing = new AtomicLong();
        AtomicLong exceptions = new AtomicLong();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        Literal field = Literal.keyword(Source.EMPTY, "2023-10-23T12:15:03.360123456Z");
        CyclicBarrier barrier = new CyclicBarrier(threads, () -> {
            if (System.nanoTime() > deadline) {
                stop.set(true);
                return;
            }
            for (int i = 0; i < batch; i++) {
                instances[i] = new ToDateNanos(Source.EMPTY, field, EsqlTestUtils.TEST_CFG);
            }
            rounds.incrementAndGet();
        });
        Thread[] workers = new Thread[threads];
        for (int t = 0; t < threads; t++) {
            workers[t] = new Thread(() -> {
                try {
                    while (true) {
                        barrier.await();
                        if (stop.get()) {
                            return;
                        }
                        for (int i = 0; i < batch; i++) {
                            try {
                                AbstractConvertFunction.BuildFactory f = instances[i].factories().get(DataType.KEYWORD);
                                if (f == null) {
                                    missing.incrementAndGet();
                                } else if (f.build(Source.EMPTY, null) == null) {
                                    placeholders.incrementAndGet();
                                }
                            } catch (RuntimeException e) {
                                exceptions.incrementAndGet();
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }, "race-" + t);
            workers[t].start();
        }
        for (Thread worker : workers) {
            worker.join();
        }
        String summary = "RACE-RESULT instances="
            + rounds.get() * batch
            + " threads="
            + threads
            + " placeholderSeen="
            + placeholders.get()
            + " missing="
            + missing.get()
            + " exceptions="
            + exceptions.get();
        logger.info(summary);
        assertEquals(summary, 0, placeholders.get() + missing.get() + exceptions.get());
    }
}
