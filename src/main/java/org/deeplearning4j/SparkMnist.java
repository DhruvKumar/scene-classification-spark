package org.deeplearning4j;

import com.github.fommil.jni.JniLoader;
import com.github.fommil.netlib.BLAS;
import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.canova.api.conf.Configuration;
import org.canova.api.records.reader.RecordReader;
import org.canova.api.records.reader.impl.SVMLightRecordReader;
import org.deeplearning4j.datasets.iterator.impl.MnistDataSetIterator;
import org.deeplearning4j.eval.Evaluation;
import org.deeplearning4j.nn.api.OptimizationAlgorithm;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.ConvolutionLayer;
import org.deeplearning4j.nn.conf.layers.DenseLayer;
import org.deeplearning4j.nn.conf.layers.RBM;
import org.deeplearning4j.nn.conf.layers.SubsamplingLayer;
import org.deeplearning4j.nn.conf.layers.setup.ConvolutionLayerSetup;
import org.deeplearning4j.nn.conf.override.ConfOverride;
import org.deeplearning4j.nn.layers.OutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.deeplearning4j.spark.canova.RecordReaderFunction;
import org.deeplearning4j.spark.impl.multilayer.SparkDl4jMultiLayer;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.lossfunctions.LossFunctions;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.logging.Level;

/**
 * Created by agibsonccc on 9/23/15.
 */
public class SparkMnist {

    public static void main(String[] args) throws Exception {
        // set to test mode
        SparkConf sparkConf = new SparkConf().set(SparkDl4jMultiLayer.AVERAGE_EACH_ITERATION, "false")
                .set("spark.executor.extraJavaOptions", "-Dorg.nd4j.parallel.enabled=false -Dcom.github.fommil.netlib.NativeSystemBLAS.natives=/opt/OpenBLAS/lib/libopenblas.so")
                .set("spark.executor.extraLibraryPath","/usr/lib64")
                .setAppName("sparktest");

        final int numRows = 28;
        final int numColumns = 28;
        int outputNum = 10;
        int numSamples = 60000;
        int nChannels = 1;
        int batchSize = 1000;
        int iterations = 10;
        int seed = 123;
        int listenerFreq = batchSize / 5;
        INDArray arr = Nd4j.create(5).mmul(Nd4j.create(5).transpose());
        System.out.println("Called native blas");
        JavaSparkContext sc = new JavaSparkContext(sparkConf);
        MultiLayerConfiguration.Builder builder = new NeuralNetConfiguration.Builder()
                .seed(seed)
                .batchSize(batchSize)
                .iterations(iterations)
              .regularization(true)
                .l2(2e-3)
                .optimizationAlgo(OptimizationAlgorithm.LINE_GRADIENT_DESCENT)
                .list(6)
                .layer(0, new ConvolutionLayer.Builder(5, 5)
                        .nIn(nChannels)
                        .nOut(20)
                        .weightInit(WeightInit.XAVIER)
                        .activation("relu")
                        .build())
                .layer(1, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX, new int[]{2, 2})
                        .build())
                .layer(2, new ConvolutionLayer.Builder(5, 5)
                        .nIn(nChannels)
                        .nOut(50)
                        .weightInit(WeightInit.XAVIER)
                        .activation("relu")
                        .build())
                .layer(3, new SubsamplingLayer.Builder(SubsamplingLayer.PoolingType.MAX, new int[] {2,2})
                        .build())
                .layer(4, new DenseLayer.Builder().activation("sigmoid")
                        .nOut(100).build())
                .layer(5, new org.deeplearning4j.nn.conf.layers.OutputLayer.Builder(LossFunctions.LossFunction.NEGATIVELOGLIKELIHOOD)
                        .nOut(outputNum)
                        .weightInit(WeightInit.XAVIER)
                        .activation("softmax")
                        .build())
                .backprop(true).pretrain(false);

        new ConvolutionLayerSetup(builder,28,28,1);


        MultiLayerConfiguration conf = builder.build();
        MultiLayerNetwork model = new MultiLayerNetwork(conf);
        model.init();


        System.out.println("Initializing network");
        SparkDl4jMultiLayer master = new SparkDl4jMultiLayer(sc,conf);
        //number of partitions should be partitioned by batch size
        JavaRDD<String> lines = sc.textFile("s3n://dl4j-distribution/mnist_svmlight.txt",60000 / conf.getConf(0).getBatchSize());
        RecordReader svmLight = new SVMLightRecordReader();
        Configuration canovaConf = new Configuration();
        //number of features + label
        canovaConf.setInt(SVMLightRecordReader.NUM_ATTRIBUTES,784);
        svmLight.setConf(canovaConf);

        JavaRDD<DataSet> data = lines.map(new RecordReaderFunction(svmLight, 784, 10));
        MultiLayerNetwork network2 = master.fitDataSet(data);
        FileOutputStream fos  = new FileOutputStream("params.txt");
        DataOutputStream dos = new DataOutputStream(fos);
        Nd4j.write(dos, network2.params());
        dos.flush();
        dos.close();

        org.nd4j.linalg.dataset.api.iterator.DataSetIterator iter = new MnistDataSetIterator(1000,60000);
        Evaluation eval = new Evaluation(10);
        while(iter.hasNext()) {
            DataSet next = iter.next();
            eval.eval(next.getLabels(),model.output(next.getFeatureMatrix(), true));
        }

        System.out.println(eval.stats());

    }

}
