/**
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.stratio.benchmark.generator.runners

import java.io.File
import java.util.UUID

import com.stratio.benchmark.generator.constants.BenchmarkConstants
import com.stratio.benchmark.generator.models.{AvgStatisticalModel, StatisticalElementModel}
import com.stratio.benchmark.generator.threads.GeneratorThread
import com.stratio.benchmark.generator.utils.HttpUtil
import com.stratio.kafka.benchmark.generator.kafka.KafkaProducer
import com.typesafe.config.{Config, ConfigFactory}
import org.apache.commons.io.FileUtils
import org.apache.log4j.Logger
import org.json4s._
import org.json4s.native.Serialization.{read, writePretty}

import scala.io.Source
import scala.util.{Failure, Success, Try}

object GeneratorRunner {

  private val logger = Logger.getLogger(this.getClass)

  implicit val formats = DefaultFormats

  /**
   * Entry point of the application.
   * @param args where you must pass the path of the config file.
   */
  def main(args: Array[String]) {
    if (args.size == 0) {
      logger.info("Use: java -jar benchmark.jar <config file>")
      System.exit(1)
    }

    Try(ConfigFactory.parseFile(new File(args(0)))) match {
      case Success(config) =>
        generatePost(config)
        generateEvents(config)
        generateReports(config)
      case Failure(exception) =>
        logger.error(exception.getLocalizedMessage, exception)
    }
  }

  /**
   * Looks for a policy and send a post to Sparkta.
   * @param config with the needed parameters.
   */
  def generatePost(config: Config): Unit = {
    val sparktaEndPoint = config.getString("sparktaEndPoint")
    val postTimeout = config.getLong("postTimeout")
    val policyPath = config.getString("policyPath")

    logger.info(s">> Sending policy $policyPath to $sparktaEndPoint")
    HttpUtil.post(policyPath, sparktaEndPoint)

    logger.info(s">> Waiting $postTimeout milliseconds to run the policy.")
    Thread.sleep(postTimeout)
  }

  /**
   * Wakes up n threads and it starts to queue events in Kafka.
   * @param config with the needed parameters.
   */
  def generateEvents(config: Config): Unit = {
    val numberOfThreads = config.getInt("numberOfThreads")
    val threadTimeout = config.getLong("threadTimeout")
    val kafkaTopic = config.getString("kafkaTopic")
    val stoppedThreads = new StoppedThreads(numberOfThreads, 0)

    logger.info(s">> Event generator started. Number of threads: $numberOfThreads($threadTimeout milliseconds)")

    (1 to numberOfThreads).foreach(i =>
      new Thread(
        new GeneratorThread(KafkaProducer.getInstance(config), threadTimeout, stoppedThreads, kafkaTopic)).start()
    )

    while(stoppedThreads.numberOfThreads == numberOfThreads) {
      Thread.sleep(BenchmarkConstants.PoolingManagerGeneratorActorTimeout)
    }

    logger.info(s">> Event generator finished. Number of generated events: ${stoppedThreads.numberOfEvents}")
  }

  /**
   * It generates two reports:
   * [timestamp]-fullReports.json: a report that contains information per executed spark's batch.
   * averageReport.json: a report that contains global averages of the previous report.
   * @param config with the needed parameters.
   */
  def generateReports(config: Config): Unit = {
    val sparkMetricsPath = config.getString("sparkMetricsPath")
    val resultMetricsPath = config.getString("resultMetricsPath")
    val metricsTimeout = config.getLong("metricsTimeout")

    logger.info(s">> Waiting $metricsTimeout  to generate spark's reports.")

    val statisticalElementModels = StatisticalElementModel.parsePathToStatisticalElementModels(sparkMetricsPath)
    val avgStatisticalModel = StatisticalElementModel.parseTotals(statisticalElementModels)

    val fileFullReport = new File(s"$resultMetricsPath/${UUID.randomUUID().toString}-fullReport.json")

    logger.info(s">> Full Report generator started: ${fileFullReport.getAbsolutePath}")
    FileUtils.writeStringToFile((fileFullReport), writePretty(statisticalElementModels))
    logger.info(s">> Full Report generator finished")

    val fileAverageReport = new File(s"$resultMetricsPath/averageReport.json")

    val avgStatisticalModels  = if(fileAverageReport.exists) {
      (read[Seq[AvgStatisticalModel]](Source.fromFile(fileAverageReport).mkString)) :+ avgStatisticalModel
    } else {
      Seq(avgStatisticalModel)
    }

    logger.info(s">> Average Report generator started: ${fileAverageReport.getAbsolutePath}")
    FileUtils.writeStringToFile((fileAverageReport), writePretty(avgStatisticalModels))
    logger.info(s">> Average Report generator finished")
    logger.info("===========================")
    logger.info("G L O B A L   R E S U L T S")
    logger.info("===========================")
    avgStatisticalModels.foreach(element => {
      logger.info(s">> Id: ${element.id}")
      logger.info(s"   ReceivedRecords: ${element.receivedRecords}")
      logger.info(s"   CompletedBatches: ${element.completedBatches}")
      logger.info(s"   ProcessedRecords: ${element.processedRecords}")
      logger.info(s"   ProcessingTime: ${element.processingTime}")
      logger.info(s"   SchedulingDelay: ${element.schedulingDelay}")
      logger.info(s"   ProcessingDelay: ${element.processingDelay}")
      logger.info(s"   TotalDelay : ${element.totalDelay}")
      logger.info("")
    })

    logger.info(s">> Sparkta Benchmark Ended!")
  }
}

/**
 * Class used to know the state of the executed threads.
 * @param numberOfThreads with information about number of stopped threads.
 * @param numberOfEvents with information about number of processed events.
 */
class StoppedThreads(var numberOfThreads: Int, var numberOfEvents: BigInt) {

  def incrementNumberOfThreads: Unit = {
    this.synchronized(numberOfThreads = numberOfThreads + 1)
  }

  def incrementNumberOfEvents(offset: BigInt): Unit = {
    this.synchronized(numberOfEvents= numberOfEvents + offset)
  }
}