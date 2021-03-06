/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.ml.multiclass;

import org.apache.ignite.ml.IgniteModel;
import org.apache.ignite.ml.composition.CompositionUtils;
import org.apache.ignite.ml.dataset.Dataset;
import org.apache.ignite.ml.dataset.DatasetBuilder;
import org.apache.ignite.ml.dataset.PartitionDataBuilder;
import org.apache.ignite.ml.dataset.feature.extractor.Vectorizer;
import org.apache.ignite.ml.dataset.feature.extractor.impl.FeatureLabelExtractorWrapper;
import org.apache.ignite.ml.dataset.primitive.context.EmptyContext;
import org.apache.ignite.ml.math.functions.IgniteBiFunction;
import org.apache.ignite.ml.math.primitives.vector.Vector;
import org.apache.ignite.ml.structures.partition.LabelPartitionDataBuilderOnHeap;
import org.apache.ignite.ml.structures.partition.LabelPartitionDataOnHeap;
import org.apache.ignite.ml.trainers.SingleLabelDatasetTrainer;

import java.io.Serializable;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * This is a common heuristic trainer for multi-class labeled models.
 *
 * NOTE: The current implementation suffers from unbalanced training over the dataset due to unweighted approach during
 * the process of reassign labels from all range of labels to 0,1.
 */
public class OneVsRestTrainer<M extends IgniteModel<Vector, Double>>
    extends SingleLabelDatasetTrainer<MultiClassModel<M>> {
    /** The common binary classifier with all hyper-parameters to spread them for all separate trainings . */
    private SingleLabelDatasetTrainer<M> classifier;

    /** */
    public OneVsRestTrainer(SingleLabelDatasetTrainer<M> classifier) {
        this.classifier = classifier;
    }

    /** {@inheritDoc} */
    @Override public <K, V, C extends Serializable> MultiClassModel<M> fit(DatasetBuilder<K, V> datasetBuilder,
        Vectorizer<K, V, C, Double> extractor) {

        return updateModel(null, datasetBuilder, extractor);
    }

    /** {@inheritDoc} */
    @Override protected <K, V, C extends Serializable> MultiClassModel<M> updateModel(MultiClassModel<M> newMdl,
        DatasetBuilder<K, V> datasetBuilder, Vectorizer<K, V, C, Double> extractor) {

        IgniteBiFunction<K, V, Vector> featureExtractor = CompositionUtils.asFeatureExtractor(extractor);
        IgniteBiFunction<K, V, Double> lbExtractor = CompositionUtils.asLabelExtractor(extractor);

        List<Double> classes = extractClassLabels(datasetBuilder, extractor);

        if (classes.isEmpty())
            return getLastTrainedModelOrThrowEmptyDatasetException(newMdl);

        MultiClassModel<M> multiClsMdl = new MultiClassModel<>();

        classes.forEach(clsLb -> {
            IgniteBiFunction<K, V, Double> lbTransformer = (k, v) -> {
                Double lb = lbExtractor.apply(k, v);

                if (lb.equals(clsLb))
                    return 1.0;
                else
                    return 0.0;
            };

            FeatureLabelExtractorWrapper<K, V, C, Double> vectorizer = FeatureLabelExtractorWrapper.wrap(featureExtractor, lbTransformer);
            M mdl = Optional.ofNullable(newMdl)
                .flatMap(multiClassModel -> multiClassModel.getModel(clsLb))
                .map(learnedModel -> classifier.update(learnedModel, datasetBuilder, vectorizer))
                .orElseGet(() -> classifier.fit(datasetBuilder, vectorizer));

            multiClsMdl.add(clsLb, mdl);
        });

        return multiClsMdl;
    }

    /** {@inheritDoc} */
    @Override public boolean isUpdateable(MultiClassModel<M> mdl) {
        return true;
    }

    /** Iterates among dataset and collects class labels. */
    private <K, V, C extends Serializable> List<Double> extractClassLabels(DatasetBuilder<K, V> datasetBuilder,
        Vectorizer<K, V, C, Double> vectorizer) {
        assert datasetBuilder != null;

        PartitionDataBuilder<K, V, EmptyContext, LabelPartitionDataOnHeap> partDataBuilder =
            new LabelPartitionDataBuilderOnHeap<>(vectorizer);

        List<Double> res = new ArrayList<>();

        try (Dataset<EmptyContext, LabelPartitionDataOnHeap> dataset = datasetBuilder.build(
            envBuilder,
            (env, upstream, upstreamSize) -> new EmptyContext(),
            partDataBuilder
        )) {
            final Set<Double> clsLabels = dataset.compute(data -> {
                final Set<Double> locClsLabels = new HashSet<>();

                final double[] lbs = data.getY();

                for (double lb : lbs)
                    locClsLabels.add(lb);

                return locClsLabels;
            }, (a, b) -> {
                if (a == null)
                    return b == null ? new HashSet<>() : b;
                if (b == null)
                    return a;
                return Stream.of(a, b).flatMap(Collection::stream).collect(Collectors.toSet());
            });

            if (clsLabels != null)
                res.addAll(clsLabels);

        }
        catch (Exception e) {
            throw new RuntimeException(e);
        }
        return res;
    }
}
