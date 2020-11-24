/*
 * Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"). You may not use this file except in compliance
 * with the License. A copy of the License is located at
 *
 * http://aws.amazon.com/apache2.0/
 *
 * or in the "license" file accompanying this file. This file is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES
 * OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions
 * and limitations under the License.
 */
package com.djl.examples.training;

import ai.djl.Device;
import ai.djl.Model;
import ai.djl.basicdataset.Mnist;
import ai.djl.training.DefaultTrainingConfig;
import ai.djl.training.EasyTrain;
import ai.djl.training.Trainer;
import ai.djl.training.TrainingResult;
import com.djl.examples.training.util.Arguments;
import ai.djl.metric.Metrics;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.types.Shape;
import ai.djl.nn.Block;
import ai.djl.nn.SequentialBlock;
import ai.djl.nn.norm.BatchNorm;
import ai.djl.nn.recurrent.LSTM;
import ai.djl.training.dataset.Dataset;
import ai.djl.training.dataset.RandomAccessDataset;
import ai.djl.training.evaluator.Accuracy;
import ai.djl.training.initializer.XavierInitializer;
import ai.djl.training.listener.CheckpointsTrainingListener;
import ai.djl.training.listener.TrainingListener;
import ai.djl.training.loss.Loss;
import ai.djl.training.util.ProgressBar;
import java.io.IOException;

import org.apache.commons.cli.ParseException;

/**
 * LSTM模型训练实例.
 *.
 */
public final class TrainMnistWithLSTM {

    private TrainMnistWithLSTM() {}

    public static void main(String[] args) throws Exception, ParseException {
        TrainMnistWithLSTM.runExample(args);
    }

    public static TrainingResult runExample(String[] args) throws Exception, ParseException {
        Arguments arguments = Arguments.parseArgs(args);

        try (Model model = Model.newInstance("lstm")) {
            model.setBlock(getLSTMModel());

            // 获取培训和验证数据集
            RandomAccessDataset trainingSet = getDataset(Dataset.Usage.TRAIN, arguments);
            RandomAccessDataset validateSet = getDataset(Dataset.Usage.TEST, arguments);

            // 设置培训配置
            DefaultTrainingConfig config = setupTrainingConfig(arguments);

            try (Trainer trainer = model.newTrainer(config)) {
                trainer.setMetrics(new Metrics());

                /*
                 * MNIST是28x28灰度图像，预处理成28*28ndarray.
                 * 第一个轴是批处理轴, 可以使用1进行初始化.
                 */
                Shape inputShape = new Shape(32, 28, 28);

                //使用正确的输入形状初始化培训器
                trainer.initialize(inputShape);

                EasyTrain.fit(trainer, arguments.getEpoch(), trainingSet, validateSet);

                return trainer.getTrainingResult();
            }
        }
    }

    private static Block getLSTMModel() {
        SequentialBlock block = new SequentialBlock();
        block.add(
                inputs -> {
                    NDArray input = inputs.singletonOrThrow();
                    Shape inputShape = input.getShape();
                    long batchSize = inputShape.get(0);
                    long channel = inputShape.get(3);
                    long time = inputShape.size() / (batchSize * channel);
                    return new NDList(input.reshape(new Shape(batchSize, time, channel)));
                });
        block.add(
                new LSTM.Builder().setStateSize(64).setNumStackedLayers(1).optDropRate(0).build());
        block.add(BatchNorm.builder().optEpsilon(1e-5f).optMomentum(0.9f).build());
        return block;
    }

    public static DefaultTrainingConfig setupTrainingConfig(Arguments arguments) {
        String outputDir = arguments.getOutputDir();
        CheckpointsTrainingListener listener = new CheckpointsTrainingListener(outputDir);
        listener.setSaveModelCallback(
                trainer -> {
                    TrainingResult result = trainer.getTrainingResult();
                    Model model = trainer.getModel();
                    float accuracy = result.getValidateEvaluation("Accuracy");
                    model.setProperty("Accuracy", String.format("%.5f", accuracy));
                    model.setProperty("Loss", String.format("%.5f", result.getValidateLoss()));
                });

        return new DefaultTrainingConfig(Loss.softmaxCrossEntropyLoss())
                .addEvaluator(new Accuracy())
                .optInitializer(new XavierInitializer())
                .optDevices(Device.getDevices(arguments.getMaxGpus()))
                .addTrainingListeners(TrainingListener.Defaults.logging(outputDir))
                .addTrainingListeners(listener);
    }

    public static RandomAccessDataset getDataset(Dataset.Usage usage, Arguments arguments)
            throws IOException {
        Mnist mnist =
                Mnist.builder()
                        .optUsage(usage)
                        .setSampling(arguments.getBatchSize(), false, true)
                        .optLimit(arguments.getLimit())
                        .build();
        mnist.prepare(new ProgressBar());
        return mnist;
    }
}