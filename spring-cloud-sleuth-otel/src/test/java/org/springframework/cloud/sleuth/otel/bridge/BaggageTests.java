/*
 * Copyright 2013-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.sleuth.otel.bridge;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.extension.trace.propagation.B3Propagator;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.Test;

import org.springframework.cloud.sleuth.BaggageInScope;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.cloud.sleuth.otel.propagation.BaggageTextMapPropagator;

import static org.assertj.core.api.BDDAssertions.then;

class BaggageTests {

	public static final String KEY_1 = "key1";

	public static final String VALUE_1 = "value1";

	OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();

	OtelBaggageManager otelBaggageManager = new OtelBaggageManager(otelCurrentTraceContext,
			Collections.singletonList(KEY_1), Collections.emptyList(), Function.identity()::apply);

	ContextPropagators contextPropagators = ContextPropagators.create(
			TextMapPropagator.composite(W3CBaggagePropagator.getInstance(), B3Propagator.injectingSingleHeader(),
					new BaggageTextMapPropagator(Collections.singletonList(KEY_1), otelBaggageManager)));

	SdkTracerProvider sdkTracerProvider = SdkTracerProvider.builder()
			.setSampler(io.opentelemetry.sdk.trace.samplers.Sampler.alwaysOn()).build();

	OpenTelemetrySdk openTelemetrySdk = OpenTelemetrySdk.builder().setTracerProvider(sdkTracerProvider)
			.setPropagators(contextPropagators).build();

	io.opentelemetry.api.trace.Tracer otelTracer = openTelemetrySdk.getTracer("io.micrometer.micrometer-tracing");

	OtelPropagator propagator = new OtelPropagator(contextPropagators, otelTracer);

	Tracer tracer = new OtelTracer(otelTracer, Function.identity()::apply, otelBaggageManager);

	@Test
	void canSetAndGetBaggage() {
		// GIVEN
		Span span = tracer.nextSpan().start();
		then(Baggage.current()).isSameAs(Baggage.empty());

		try (Tracer.SpanInScope spanInScope = tracer.withSpan(span)) {
			// WHEN
			try (BaggageInScope baggage = this.tracer.getBaggage(KEY_1)) {
				baggage.set(VALUE_1);
				// THEN
				then(tracer.getBaggage(KEY_1).get()).isEqualTo(VALUE_1);
			}
		}

		then(Baggage.current()).isSameAs(Baggage.empty());
	}

	@Test
	void injectAndExtractKeepsTheBaggage() {
		// GIVEN
		Map<String, String> carrier = new HashMap<>();

		Span span = tracer.nextSpan().start();
		try (Tracer.SpanInScope spanInScope = tracer.withSpan(span)) {
			try (BaggageInScope baggage = this.tracer.createBaggage(KEY_1, VALUE_1)) {
				// WHEN
				this.propagator.inject(tracer.currentTraceContext().context(), carrier, Map::put);

				// THEN
				then(carrier.get(KEY_1)).isEqualTo(VALUE_1);
			}
		}

		then(Baggage.current()).isSameAs(Baggage.empty());

		// WHEN
		Span extractedSpan = propagator.extract(carrier, Map::get).start();

		// THEN
		try (Tracer.SpanInScope spanInScope = tracer.withSpan(extractedSpan)) {
			then(tracer.getBaggage(KEY_1).get(extractedSpan.context())).isEqualTo(VALUE_1);
			try (BaggageInScope baggageInScope = tracer.getBaggage(KEY_1).makeCurrent()) {
				then(baggageInScope.get()).isEqualTo(VALUE_1);
			}
		}

		then(Baggage.current()).isSameAs(Baggage.empty());
	}

}
