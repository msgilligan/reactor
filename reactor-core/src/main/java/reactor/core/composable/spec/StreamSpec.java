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
package reactor.core.composable.spec;

import reactor.core.Environment;
import reactor.core.Observable;

import reactor.core.composable.Composable;
import reactor.core.composable.Stream;
import reactor.event.selector.Selector;
import reactor.function.Supplier;
import reactor.tuple.Tuple2;

/**
 * A helper class for specifying a bounded {@link Stream}. {@link #each} must be called to
 * provide the stream with its values.
 *
 * @param <T> The type of values that the stream contains.
 * @author Jon Brisbin
 * @author Stephane Maldini
 */
public final class StreamSpec<T> extends ComposableSpec<StreamSpec<T>, Stream<T>> {

	private int batchSize = -1;
	private Iterable<T> values;
	private Supplier<T> valuesSupplier;

	/**
	 * Configures the stream to have the given {@code batchSize}. A value of {@code -1}, which
	 * is the default configuration, configures the stream to not be batched.
	 *
	 * @param batchSize The batch size of the stream
	 * @return {@code this}
	 */
	public StreamSpec<T> batchSize(int batchSize) {
		this.batchSize = batchSize;
		return this;
	}

	/**
	 * Configures the stream to contain the given {@code values}.
	 *
	 * @param values The stream's values
	 * @return {@code this}
	 */
	public StreamSpec<T> each(Iterable<T> values) {
		this.values = values;
		return this;
	}


	/**
	 * Configures the stream to pass value from a {@link Supplier} on flush.
	 *
	 * @param supplier The stream's value generator
	 * @return {@code this}
	 */
	public StreamSpec<T> generate(Supplier<T> supplier) {
		this.valuesSupplier = supplier;
		return this;
	}

	@Override
	protected Stream<T> createComposable(Environment env, Observable observable,
	                                     Tuple2<Selector, Object> accept) {

		if (accept == null && values == null &&  valuesSupplier == null) {
			throw new IllegalStateException("A bounded stream must be configured with some values source. Use " +
					DeferredStreamSpec.class.getSimpleName() + " to create a stream with no initial values or supplier");
		}

		Stream<T> stream = new Stream<T>(observable, batchSize, null, accept, env);
		if(values == null){
			return stream.propagate(valuesSupplier);
		}else{
			return stream.propagate(values);
		}
	}

}
