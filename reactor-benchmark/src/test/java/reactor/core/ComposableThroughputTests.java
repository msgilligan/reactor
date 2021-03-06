/*
 * Copyright (c) 2011-2013 GoPivotal, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.core;

import com.lmax.disruptor.YieldingWaitStrategy;
import com.lmax.disruptor.dsl.ProducerType;
import org.junit.Before;
import org.junit.Test;
import reactor.AbstractReactorTest;
import reactor.core.composable.Composable;
import reactor.core.composable.Deferred;
import reactor.core.composable.Promise;
import reactor.core.composable.Stream;
import reactor.core.composable.spec.Promises;
import reactor.core.composable.spec.Streams;
import reactor.event.dispatch.ActorDispatcher;
import reactor.event.dispatch.Dispatcher;
import reactor.event.dispatch.RingBufferDispatcher;
import reactor.event.dispatch.WorkQueueDispatcher;
import reactor.function.Consumer;
import reactor.function.Function;
import reactor.tuple.Tuple2;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;

import static junit.framework.Assert.assertEquals;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public class ComposableThroughputTests extends AbstractReactorTest {

	static int  length        = 500;
	static int  runs          = 1000;
	static int  samples       = 3;
	static long expectedTotal = sumSample();

	public static long sumSample() {
		long sum = 1;
		for(int x = 0; x < samples; x++) {
			for(int i = 0; i < runs; i++) {
				for(int j = 0; j < length; j++) {
					sum += j;
				}
			}
		}
		return sum;
	}

	private          CountDownLatch latch;
	private volatile long           total;


	private static final AtomicLongFieldUpdater<ComposableThroughputTests> totalUpdated = AtomicLongFieldUpdater
			.newUpdater(ComposableThroughputTests.class, "total");

	@Before
	public void init() {
		total = 0l;
	}

	private Deferred<Integer, Stream<Integer>> createDeferred(Dispatcher dispatcher) {
		latch = new CountDownLatch(1);
		Deferred<Integer, Stream<Integer>> dInt = Streams.<Integer>defer()
				.env(env)
				.dispatcher(dispatcher)
				.get();

		dInt.compose()
				.map(new Function<Integer, Integer>() {
					@Override
					public Integer apply(Integer number) {
						return number;
					}
				})
				.reduce(new Function<Tuple2<Integer, Long>, Long>() {
					@Override
					public Long apply(Tuple2<Integer, Long> r) {
						long last = (null != r.getT2() ? r.getT2() : 1);
						return last + r.getT1();
					}
				}, null, length*runs*samples)
				.consume(new Consumer<Long>() {
					@Override
					public void accept(Long number) {
						totalUpdated.set(ComposableThroughputTests.this, number);
						latch.countDown();
					}
				});
		return dInt;
	}

	private Stream<Integer> compose(Stream<Integer> stream, final Dispatcher dispatcher) {
		return stream.mapMany(new Function<Integer, Composable<Integer>>() {
			@Override
			public Composable<Integer> apply(Integer integer) {
				Deferred<Integer, Promise<Integer>> deferred = Promises.defer(env, dispatcher);
				try {
					return deferred.compose();
				} finally {
					deferred.accept(integer);
				}
			}
		})
				.consume(new Consumer<Integer>() {
					@Override
					public void accept(Integer integer) {
						totalUpdated.getAndIncrement(ComposableThroughputTests.this);
						latch.countDown();
					}
				});
	}

	private Deferred<Integer, Stream<Integer>> createMapManyDeferred() {
		latch = new CountDownLatch(length * runs * samples);
		final Dispatcher dispatcher = env.getDefaultDispatcher();
		final Deferred<Integer, Stream<Integer>> dInt = Streams.defer(env, dispatcher);
		compose(dInt.compose(), dispatcher);
		return dInt;
	}


	private Deferred<Integer, Stream<Integer>> createMapManyBatchedDeferred(int batchSize) {
		latch = new CountDownLatch((length * runs * samples) / batchSize);
		final Dispatcher dispatcher = env.getDefaultDispatcher();
		final Deferred<Integer, Stream<Integer>> dInt = Streams.<Integer>defer()
				.env(env)
				.dispatcher(dispatcher)
				.batchSize(batchSize)
				.get();
		compose(dInt.compose(), dispatcher);
		return dInt;
	}

	private void doTestMapMany(String name) throws InterruptedException {
		doTest(env.getDefaultDispatcher(), name, createMapManyDeferred());
		assertThat("Totals matched expected", total, is((long)length*runs*samples));
	}


	private void doTestMapManyBatched(String name) throws InterruptedException {
		int batchSize = 150;
		doTest(env.getDefaultDispatcher(), name, createMapManyBatchedDeferred(batchSize));
		assertThat("Totals matched expected", total, is((long)length*runs*samples));
	}

	private void doTest(Dispatcher dispatcher, String name) throws InterruptedException {
		doTest(dispatcher, name, createDeferred(dispatcher));
		assertThat("Totals matched expected", total, is(expectedTotal));
	}

	private void doTest(Dispatcher dispatcher,
	                    String name,
	                    Deferred<Integer, Stream<Integer>> d) throws InterruptedException {
		long start = System.currentTimeMillis();
		for (int x = 0; x < samples; x++) {
			for (int i = 0; i < runs; i++) {
				for (int j = 0; j < length; j++) {
					d.accept(j);
				}
			}
		}

		latch.await();
		assertEquals("Missing accepted events, possibly due to a backlog/batch issue", 0, latch.getCount());

		long end = System.currentTimeMillis();
		long elapsed = end - start;

		System.out.println(String.format("%s throughput (%sms): %s",
				name,
				elapsed,
				Math.round((length * runs * samples) / (elapsed * 1.0 / 1000)) + "/sec"));

		if (dispatcher != null) {
			dispatcher.shutdown();
		}
	}

	@Test
	public void testThreadPoolDispatcherComposableThroughput() throws InterruptedException {
		doTest(env.getDispatcher("threadPoolExecutor"), "thread pool");
	}

	@Test
	public void testWorkQueueDispatcherComposableThroughput() throws InterruptedException {
		doTest(new WorkQueueDispatcher("workQueue", 8, 2048, null), "work queue");
	}

	@Test
	public void testRingBufferDispatcherComposableThroughput() throws InterruptedException {
		doTest(env.getDispatcher("ringBuffer"), "ring buffer");
	}

	@Test
	public void testActorDispatcherComposableThroughput() throws InterruptedException {
		doTest(new ActorDispatcher(new Function<Object, Dispatcher>() {
			@Override
			public Dispatcher apply(Object o) {
				return env.getDispatcher("eventLoop");
			}
		}), "actor system");
	}

	@Test
	public void testSingleProducerRingBufferDispatcherComposableThroughput() throws InterruptedException {
		doTest(new RingBufferDispatcher(
				"test",
				2048,
				null,
				ProducerType.SINGLE,
				new YieldingWaitStrategy()
		), "single-producer ring buffer");
	}

	@Test
	public void testRingBufferDispatcherMapManyComposableThroughput() throws InterruptedException {
		doTestMapMany("single-producer ring buffer map many");
	}

	@Test
	public void testRingBufferDispatcherMapManyBatchedComposableThroughput() throws InterruptedException {
		doTestMapManyBatched("single-producer ring buffer batched map many");
	}

}
