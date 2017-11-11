package com.lihaoyi.workbench

import sbt._
import sbt.Keys._
import autowire._
import org.scalajs.sbtplugin.ScalaJSPlugin

import org.apache.logging.log4j.message._

import org.apache.logging.log4j.core.{LogEvent => Log4JLogEvent, _}
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.layout.PatternLayout

import scala.concurrent.ExecutionContext.Implicits.global

object WorkbenchBasePlugin extends AutoPlugin {

  override def requires = ScalaJSPlugin

  object autoImport {
    val localUrl = settingKey[(String, Int)]("localUrl")
    val workbenchDefaultRootObject = settingKey[Option[(String, String)]]("path to defaultRootObject served on `/` and rootDirectory")
    val workbenchCompression = settingKey[Boolean]("use gzip compression on HTTP responses")
  }
  import autoImport._

  val server = settingKey[Server]("local websocket server")

  lazy val replHistory = collection.mutable.Buffer.empty[String]

  val workbenchSettings = Seq(
    localUrl := ("localhost", 12345),
    workbenchDefaultRootObject := None,
    workbenchCompression := false,
    (extraLoggers in ThisBuild) := {
      val clientLogger = new AbstractAppender(
        "FakeAppender",
        null,
        PatternLayout.createDefaultLayout()) {
        override def append(event: Log4JLogEvent): Unit = {

          val level = sbt.internal.util.ConsoleAppender.toLevel(event.getLevel)
          val message = event.getMessage

          message match {
            case o: ObjectMessage => {
              o.getParameter match {
                case e: sbt.internal.util.StringEvent => server.value.Wire[Api].print(level.toString, e.message).call()
                case e: sbt.internal.util.ObjectEvent[_] => server.value.Wire[Api].print(level.toString, e.message.toString).call()
                case _ => server.value.Wire[Api].print(level.toString, message.getFormattedMessage).call()
              }
            }
            case _ => server.value.Wire[Api].print(level.toString, message.getFormattedMessage).call()
          }
        }
      }
      clientLogger.start()
      val currentFunction = extraLoggers.value
      (key: ScopedKey[_]) => clientLogger +: currentFunction(key)
    },
    server := new Server(localUrl.value._1, localUrl.value._2,
      workbenchDefaultRootObject.value.map(_._1), workbenchDefaultRootObject.value.map(_._2), workbenchCompression.value),
    (onUnload in Global) := {
      (onUnload in Global).value.compose { state =>
        server.value.kill()
        state
      }
    }
  )

  private def getScopeId(scope: ScopeAxis[sbt.Reference]): String = {
    "" + scope.hashCode()
  }
  override def projectSettings = workbenchSettings
}
