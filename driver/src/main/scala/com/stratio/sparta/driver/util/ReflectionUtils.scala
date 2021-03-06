/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.stratio.sparta.driver.util

import java.io.Serializable
import java.net.URLClassLoader
import akka.event.slf4j.SLF4JLogging

import scala.collection.JavaConversions._

import org.reflections.Reflections

import com.stratio.sparta.driver.exception.DriverException
import com.stratio.sparta.sdk._

import scala.util.Try

class ReflectionUtils extends SLF4JLogging {

  def tryToInstantiate[C](classAndPackage: String, block: Class[_] => C): C = {
    val clazMap: Map[String, String] = getClasspathMap
    val finalClazzToInstance = clazMap.getOrElse(classAndPackage, classAndPackage)
    try {
      val clazz = Class.forName(finalClazzToInstance)
      block(clazz)
    } catch {
      case cnfe: ClassNotFoundException =>
        throw DriverException.create("Class with name " + classAndPackage + " Cannot be found in the classpath.", cnfe)
      case ie: InstantiationException =>
        throw DriverException.create(
          "Class with name " + classAndPackage + " cannot be instantiated", ie)
      case e: Exception => throw DriverException.create(
        "Generic error trying to instantiate " + classAndPackage, e)
    }
  }

  def instantiateParameterizable[C](clazz: Class[_], properties: Map[String, Serializable]): C =
    clazz.getDeclaredConstructor(classOf[Map[String, Serializable]]).newInstance(properties).asInstanceOf[C]

  def printClassPath(cl: ClassLoader): Unit = {
    val urls = cl.asInstanceOf[URLClassLoader].getURLs()
    urls.foreach(url => log.info(url.getFile))
  }

  lazy val getClasspathMap: Map[String, String] = {
    val reflections = new Reflections("com.stratio.sparta")

    try {
      log.info("#######")
      log.info("####### SPARK MUTABLE_URL_CLASS_LOADER:")
      log.info(getClass.getClassLoader.toString)
      printClassPath(getClass.getClassLoader)
      log.info("#######")
      log.info("####### APP_CLASS_LOADER / SYSTEM CLASSLOADER:")
      log.info(ClassLoader.getSystemClassLoader().toString)
      printClassPath(ClassLoader.getSystemClassLoader())
      log.info("#######")
      log.info("####### EXTRA_CLASS_LOADER:")
      log.info(getClass.getClassLoader.getParent.getParent.toString)
      printClassPath(getClass.getClassLoader.getParent.getParent)
    } catch {
      case e: Exception => //nothing
    }

    val inputs = reflections.getSubTypesOf(classOf[Input]).toList
    val dimensionTypes = reflections.getSubTypesOf(classOf[DimensionType]).toList
    val operators = reflections.getSubTypesOf(classOf[Operator]).toList
    val outputs = reflections.getSubTypesOf(classOf[Output]).toList
    val parsers = reflections.getSubTypesOf(classOf[Parser]).toList
    val plugins = inputs ++ dimensionTypes ++ operators ++ outputs ++ parsers
    val result = plugins map (t => t.getSimpleName -> t.getCanonicalName) toMap

    log.info("#######")
    log.info("####### Plugins to be loaded:")
    result.foreach {
      case (simpleName: String, canonicalName: String) => log.info(s"${canonicalName}")
    }

    result
  }
}
