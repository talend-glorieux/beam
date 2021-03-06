/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.runners.flink.translation.functions;

import java.util.Map;
import org.apache.beam.runners.flink.translation.utils.SerializedPipelineOptions;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.CombineFnBase;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.IntervalWindow;
import org.apache.beam.sdk.util.WindowedValue;
import org.apache.beam.sdk.util.WindowingStrategy;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.flink.api.common.functions.RichGroupCombineFunction;
import org.apache.flink.util.Collector;

/**
 * This is is the first step for executing a {@link org.apache.beam.sdk.transforms.Combine.PerKey}
 * on Flink. The second part is {@link FlinkReduceFunction}. This function performs a local
 * combine step before shuffling while the latter does the final combination after a shuffle.
 *
 * <p>The input to {@link #combine(Iterable, Collector)} are elements of the same key but
 * for different windows. We have to ensure that we only combine elements of matching
 * windows.
 */
public class FlinkPartialReduceFunction<K, InputT, AccumT, W extends BoundedWindow>
    extends RichGroupCombineFunction<WindowedValue<KV<K, InputT>>, WindowedValue<KV<K, AccumT>>> {

  protected final CombineFnBase.PerKeyCombineFn<K, InputT, AccumT, ?> combineFn;

  protected final WindowingStrategy<Object, W> windowingStrategy;

  protected final SerializedPipelineOptions serializedOptions;

  protected final Map<PCollectionView<?>, WindowingStrategy<?, ?>> sideInputs;

  public FlinkPartialReduceFunction(
      CombineFnBase.PerKeyCombineFn<K, InputT, AccumT, ?> combineFn,
      WindowingStrategy<Object, W> windowingStrategy,
      Map<PCollectionView<?>, WindowingStrategy<?, ?>> sideInputs,
      PipelineOptions pipelineOptions) {

    this.combineFn = combineFn;
    this.windowingStrategy = windowingStrategy;
    this.sideInputs = sideInputs;
    this.serializedOptions = new SerializedPipelineOptions(pipelineOptions);

  }

  @Override
  public void combine(
      Iterable<WindowedValue<KV<K, InputT>>> elements,
      Collector<WindowedValue<KV<K, AccumT>>> out) throws Exception {

    PipelineOptions options = serializedOptions.getPipelineOptions();

    FlinkSideInputReader sideInputReader =
        new FlinkSideInputReader(sideInputs, getRuntimeContext());

    AbstractFlinkCombineRunner<K, InputT, AccumT, AccumT, W> reduceRunner;

    if (!windowingStrategy.getWindowFn().isNonMerging()
        && !windowingStrategy.getWindowFn().windowCoder().equals(IntervalWindow.getCoder())) {
      reduceRunner = new HashingFlinkCombineRunner<>();
    } else {
      reduceRunner = new SortingFlinkCombineRunner<>();
    }

    reduceRunner.combine(
        new AbstractFlinkCombineRunner.PartialFlinkCombiner<>(combineFn),
        windowingStrategy,
        sideInputReader,
        options,
        elements,
        out);

  }
}
