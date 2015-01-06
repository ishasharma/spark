/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.examples.mllib

import scala.language.reflectiveCalls

import scopt.OptionParser

import org.apache.spark.{Logging, SparkConf, SparkContext}
import org.apache.spark.SparkContext._
import org.apache.spark.mllib.evaluation.MulticlassMetrics
import org.apache.spark.mllib.linalg.Vector
import org.apache.spark.mllib.regression.LabeledPoint
import org.apache.spark.mllib.tree.{GradientBoostedTrees, RandomForest, impurity}
import org.apache.spark.mllib.tree.configuration.{BoostingStrategy, Algo, Strategy}
import org.apache.spark.mllib.tree.configuration.Algo._
import org.apache.spark.mllib.tree.loss.{SquaredError, LogLoss}
import org.apache.spark.mllib.util.MLUtils
import org.apache.spark.rdd.RDD
import org.apache.spark.util.Utils

/**
 * Run RandomForest, GBTs with
 *  - varying # training instances
 *  - varying # trees
 * Record:
 *  - training time
 *  - training, test metrics
 */
object EnsembleTiming extends Logging {

  val numIterations = 5
  val sampleSeedsRandom = new scala.util.Random()
  val sampleSeeds = Array.fill[Long](numIterations)(sampleSeedsRandom.nextLong())

  object ImpurityType extends Enumeration {
    type ImpurityType = Value
    val Gini, Entropy, Variance = Value
  }

  import ImpurityType._

  case class Params(
      input: String = null,
      testInput: String = "",
      dataFormat: String = "libsvm",
      algo: Algo = Classification,
      maxDepth: Int = 5,
      impurity: ImpurityType = Gini,
      maxBins: Int = 32,
      numPartitions: Int = -1,
      minInstancesPerNode: Int = 1,
      minInfoGain: Double = 0.0,
      featureSubsetStrategy: String = "auto",
      ensemble: String = "rf",
      fracTest: Double = 0.2,
      numTreess: Array[Int] = Array(1, 5, 10),
      trainFracs: Array[Double] = Array(0.001, 0.01, 0.1, 0.2, 0.5, 1.0),
      useNodeIdCache: Boolean = false,
      checkpointDir: Option[String] = None,
      checkpointInterval: Int = 10) extends AbstractParams[Params]

  def main(args: Array[String]) {
    val defaultParams = Params()

    val parser = new OptionParser[Params]("EnsembleTiming") {
      head("EnsembleTiming: an example decision tree app.")
      opt[String]("algo")
        .text(s"algorithm (${Algo.values.mkString(",")}), default: ${defaultParams.algo}")
        .action((x, c) => c.copy(algo = Algo.withName(x)))
      opt[String]("impurity")
        .text(s"impurity type (${ImpurityType.values.mkString(",")}), " +
          s"default: ${defaultParams.impurity}")
        .action((x, c) => c.copy(impurity = ImpurityType.withName(x)))
      opt[Int]("maxDepth")
        .text(s"max depth of the tree, default: ${defaultParams.maxDepth}")
        .action((x, c) => c.copy(maxDepth = x))
      opt[Int]("maxBins")
        .text(s"max number of bins, default: ${defaultParams.maxBins}")
        .action((x, c) => c.copy(maxBins = x))
      opt[Int]("numPartitions")
        .text(s"number of data partitions (-1 = ignore), default: ${defaultParams.numPartitions}")
        .action((x, c) => c.copy(numPartitions = x))
      opt[Int]("minInstancesPerNode")
        .text(s"min number of instances required at child nodes to create the parent split," +
          s" default: ${defaultParams.minInstancesPerNode}")
        .action((x, c) => c.copy(minInstancesPerNode = x))
      opt[Double]("minInfoGain")
        .text(s"min info gain required to create a split, default: ${defaultParams.minInfoGain}")
        .action((x, c) => c.copy(minInfoGain = x))
      opt[String]("featureSubsetStrategy")
        .text(s"feature subset sampling strategy" +
          s" (${RandomForest.supportedFeatureSubsetStrategies.mkString(", ")}}), " +
          s"default: ${defaultParams.featureSubsetStrategy}")
        .action((x, c) => c.copy(featureSubsetStrategy = x))
      opt[String]("ensemble")
        .text(s"ensemble type (rf, gbt), " + s"default: ${defaultParams.ensemble}")
        .action((x, c) => c.copy(ensemble = x))
      opt[Double]("fracTest")
        .text(s"fraction of data to hold out for testing.  If given option testInput, " +
          s"this option is ignored. default: ${defaultParams.fracTest}")
        .action((x, c) => c.copy(fracTest = x))
      opt[String]("numTreess")
        .text(s"number of trees in ensemble (array, space-separated). default: ${defaultParams.numTreess}")
        .action((x, c) => c.copy(numTreess = x.split(" ").map(_.toInt)))
      opt[String]("trainFracs")
        .text(s"fractions of training data to test (array, space-separated). default: ${defaultParams.trainFracs}")
        .action((x, c) => c.copy(trainFracs = x.split(" ").map(_.toDouble)))
      opt[Boolean]("useNodeIdCache")
        .text(s"whether to use node Id cache during training, " +
          s"default: ${defaultParams.useNodeIdCache}")
        .action((x, c) => c.copy(useNodeIdCache = x))
      opt[String]("checkpointDir")
        .text(s"checkpoint directory where intermediate node Id caches will be stored, " +
         s"default: ${defaultParams.checkpointDir match {
           case Some(strVal) => strVal
           case None => "None"
         }}")
        .action((x, c) => c.copy(checkpointDir = Some(x)))
      opt[Int]("checkpointInterval")
        .text(s"how often to checkpoint the node Id cache, " +
         s"default: ${defaultParams.checkpointInterval}")
        .action((x, c) => c.copy(checkpointInterval = x))
      opt[String]("testInput")
        .text(s"input path to test dataset.  If given, option fracTest is ignored." +
          s" default: ${defaultParams.testInput}")
        .action((x, c) => c.copy(testInput = x))
      opt[String]("<dataFormat>")
        .text("data format: libsvm (default), dense (deprecated in Spark v1.1)")
        .action((x, c) => c.copy(dataFormat = x))
      arg[String]("<input>")
        .text("input path to labeled examples")
        .required()
        .action((x, c) => c.copy(input = x))
      checkConfig { params =>
        if (params.fracTest < 0 || params.fracTest > 1) {
          failure(s"fracTest ${params.fracTest} value incorrect; should be in [0,1].")
        } else {
          if (params.algo == Classification &&
            (params.impurity == Gini || params.impurity == Entropy)) {
            success
          } else if (params.algo == Regression && params.impurity == Variance) {
            success
          } else {
            failure(s"Algo ${params.algo} is not compatible with impurity ${params.impurity}.")
          }
        }
      }
    }

    parser.parse(args, defaultParams).map { params =>
      run(params)
    }.getOrElse {
      sys.exit(1)
    }
  }

  /**
   * Load training and test data from files.
   * @param input  Path to input dataset.
   * @param dataFormat  "libsvm" or "dense"
   * @param testInput  Path to test dataset.
   * @param algo  Classification or Regression
   * @param fracTest  Fraction of input data to hold out for testing.  Ignored if testInput given.
   * @return  (training dataset, test dataset, number of classes),
   *          where the number of classes is inferred from data (and set to 0 for Regression)
   */
  private[mllib] def loadDatasets(
      sc: SparkContext,
      input: String,
      dataFormat: String,
      testInput: String,
      algo: Algo,
      fracTest: Double,
      numPartitions: Int): (RDD[LabeledPoint], RDD[LabeledPoint], Int) = {
    // Load training data and cache it.
    val origExamples = dataFormat match {
      case "dense" => MLUtils.loadLabeledPoints(sc, input).cache()
      case "libsvm" => MLUtils.loadLibSVMFile(sc, input).cache()
    }
    // For classification, re-index classes if needed.
    val (examples, classIndexMap, numClasses) = algo match {
      case Classification => {
        // classCounts: class --> # examples in class
        val classCounts = origExamples.map(_.label).countByValue()
        val sortedClasses = classCounts.keys.toList.sorted
        val numClasses = classCounts.size
        // classIndexMap: class --> index in 0,...,numClasses-1
        val classIndexMap = {
          if (classCounts.keySet != Set(0.0, 1.0)) {
            sortedClasses.zipWithIndex.toMap
          } else {
            Map[Double, Int]()
          }
        }
        val examples = {
          if (classIndexMap.isEmpty) {
            origExamples
          } else {
            origExamples.map(lp => LabeledPoint(classIndexMap(lp.label), lp.features))
          }
        }
        val numExamples = examples.count()
        println(s"numClasses = $numClasses.")
        println(s"Per-class example fractions, counts:")
        println(s"Class\tFrac\tCount")
        sortedClasses.foreach { c =>
          val frac = classCounts(c) / numExamples.toDouble
          println(s"$c\t$frac\t${classCounts(c)}")
        }
        (examples, classIndexMap, numClasses)
      }
      case Regression =>
        (origExamples, null, 0)
      case _ =>
        throw new IllegalArgumentException("Algo ${params.algo} not supported.")
    }

    // Create training, test sets.
    val splits = if (testInput != "") {
      // Load testInput.
      val numFeatures = examples.take(1)(0).features.size
      val origTestExamples = dataFormat match {
        case "dense" => MLUtils.loadLabeledPoints(sc, testInput)
        case "libsvm" => MLUtils.loadLibSVMFile(sc, testInput, numFeatures)
      }
      algo match {
        case Classification => {
          // classCounts: class --> # examples in class
          val testExamples = {
            if (classIndexMap.isEmpty) {
              origTestExamples
            } else {
              origTestExamples.map(lp => LabeledPoint(classIndexMap(lp.label), lp.features))
            }
          }
          Array(examples, testExamples)
        }
        case Regression =>
          Array(examples, origTestExamples)
      }
    } else {
      // Split input into training, test.
      examples.randomSplit(Array(1.0 - fracTest, fracTest))
    }
    val training = if (numPartitions > 0) {
      splits(0).repartition(numPartitions)
    } else {
      splits(0)
    }.cache()
    val test = splits(1).cache()

    examples.unpersist(blocking = false)

    (training, test, numClasses)
  }

  def run(params: Params): Unit = {

    val conf = new SparkConf().setAppName(s"EnsembleTiming with $params")
    val sc = new SparkContext(conf)

    println(s"EnsembleTiming with parameters:\n$params")

    // Load training and test data and cache it.
    val (training, test, numClasses) = loadDatasets(sc, params.input, params.dataFormat,
      params.testInput, params.algo, params.fracTest, params.numPartitions)
    val totalTrain = training.count()

    val impurityCalculator = params.impurity match {
      case Gini => impurity.Gini
      case Entropy => impurity.Entropy
      case Variance => impurity.Variance
    }

    val strategy
      = new Strategy(
          algo = params.algo,
          impurity = impurityCalculator,
          maxDepth = params.maxDepth,
          maxBins = params.maxBins,
          numClasses = numClasses,
          minInstancesPerNode = params.minInstancesPerNode,
          minInfoGain = params.minInfoGain,
          useNodeIdCache = params.useNodeIdCache,
          checkpointDir = params.checkpointDir,
          checkpointInterval = params.checkpointInterval)

    println()
    println("ALL RESULTS")
    println()
    println("alg\tntrain\tnumTrees\ttime\ttrainMetric\ttestMetric")
    var allResults = Array.empty[FullResults]
    for (trainFrac <- params.trainFracs) {
      val ntrain = (totalTrain * trainFrac).round
      for (numTrees <- params.numTreess) {
        var results = Array.empty[Results]
        var iter = 0
        while (iter < numIterations) {
          val seed = sampleSeeds(iter)
          val res = if (params.ensemble == "rf") {
            testRandomForest(training, test, strategy, trainFrac, numTrees, params, seed)
          } else {
            testGBT(training, test, strategy, trainFrac, numTrees, params, seed)
          }
          results = results :+ res
          iter += 1
        }
        val fr = FullResults("rf", ntrain, numTrees, median(results))
        allResults = allResults :+ fr
        println(fr.toString)
      }
    }
    println()

    sc.stop()
  }

  case class Results(time: Double, train: Double, test: Double)
  case class FullResults(alg: String, ntrain: Long, numTrees: Int, res: Results) {
    override def toString: String =
      s"$alg\t$ntrain\t$numTrees\t${res.time}\t${res.train}\t${res.test}"
  }

  def median(a: Array[Double]): Double = {
    val b = a.sorted
    if (a.size % 2 == 0) {
      val i2 = a.size / 2
      val i1 = i2 - 1
      (b(i1) + b(i2)) / 2.0
    } else {
      val i = a.size / 2
      b(i)
    }
  }

  def median(results: Array[Results]): Results = {
    val time = median(results.map(_.time))
    val train = median(results.map(_.train))
    val test = median(results.map(_.test))
    Results(time, train, test)
  }

  /**
   * For the given data and numTrees, find median training time and metrics from numIterations.
   */
  def testRandomForest(trainingTMP: RDD[LabeledPoint], test: RDD[LabeledPoint], strategy: Strategy,
                       trainFrac: Double, numTrees: Int, params: Params, seed: Long): Results = {
    val training = if (trainFrac == 1.0) {
      trainingTMP
    } else {
      trainingTMP.sample(withReplacement = false, fraction = trainFrac, seed)
    }.cache()
    logWarning(s"now testing ${training.count()} instances")

    val randomSeed = Utils.random.nextInt()
    val startTime = System.nanoTime()
    val model = if (params.algo == Classification) {
      RandomForest.trainClassifier(training, strategy, numTrees,
        params.featureSubsetStrategy, randomSeed)
    } else {
      RandomForest.trainRegressor(training, strategy, numTrees,
        params.featureSubsetStrategy, randomSeed)
    }
    val elapsedTime = (System.nanoTime() - startTime) / 1e9
    logWarning(s"Training time: $elapsedTime seconds")
    getResults(model, training, test, params.algo, elapsedTime)
  }

  /**
   * For the given data and numTrees, find median training time and MSE from numIterations.
   */
  def testGBT(trainingTMP: RDD[LabeledPoint], test: RDD[LabeledPoint], treeStrategy: Strategy,
              trainFrac: Double, numTrees: Int, params: Params, seed: Long): Results = {
    val training = if (trainFrac == 1.0) {
      trainingTMP
    } else {
      trainingTMP.sample(withReplacement = false, fraction = trainFrac, seed)
    }.cache()
    logWarning(s"now testing ${training.count()} instances")

    val strategy = if (params.algo == Classification) {
      BoostingStrategy(treeStrategy, LogLoss, numTrees)
    } else {
      BoostingStrategy(treeStrategy, SquaredError, numTrees)
    }
    val randomSeed = Utils.random.nextInt()
    val startTime = System.nanoTime()
    val model = GradientBoostedTrees.train(training, strategy)
    val elapsedTime = (System.nanoTime() - startTime) / 1e9
    logWarning(s"Training time: $elapsedTime seconds")
    getResults(model, training, test, params.algo, elapsedTime)
  }

  private def getResults(
      model: { def predict(features: Vector): Double },
      training: RDD[LabeledPoint],
      test: RDD[LabeledPoint],
      algo: Algo,
      elapsedTime: Double): Results = {
    val trainMetric = getMetric(model, training, algo)
    logWarning(s"Train Metric = $trainMetric")
    val testMetric = getMetric(model, test, algo)
    logWarning(s"Test MSE = $testMetric")
    Results(elapsedTime, trainMetric, testMetric)
  }

  private def getMetric(
      model: { def predict(features: Vector): Double },
      data: RDD[LabeledPoint],
      algo: Algo): Double = {
    if (algo == Classification) {
      accuracy(model, data)
    } else {
      meanSquaredError(model, data)
    }
  }

  private def accuracy(
      model: { def predict(features: Vector): Double },
      data: RDD[LabeledPoint]): Double = {
    new MulticlassMetrics(data.map(lp => (model.predict(lp.features), lp.label))).precision
  }

  /**
   * Calculates the mean squared error for regression.
   */
  private[mllib] def meanSquaredError(
      model: { def predict(features: Vector): Double },
      data: RDD[LabeledPoint]): Double = {
    data.map { y =>
      val err = model.predict(y.features) - y.label
      err * err
    }.mean()
  }

}
