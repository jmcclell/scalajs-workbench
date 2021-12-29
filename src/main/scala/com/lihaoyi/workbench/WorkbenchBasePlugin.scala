package com.lihaoyi.workbench

import autowire._
import org.apache.logging.log4j.message._
import org.scalajs.sbtplugin.ScalaJSPlugin
import sbt.Keys._
import sbt._

import sbt.internal.util.ConsoleAppender

object WorkbenchBasePlugin extends AutoPlugin {
  import scala.concurrent.ExecutionContext.Implicits.global

  override def requires: AutoPlugin = ScalaJSPlugin

  object autoImport {

    sealed trait StartMode

    object WorkbenchStartModes {

      case object OnCompile extends StartMode

      case object OnSbtLoad extends StartMode

      case object Manual extends StartMode

    }

    val localUrl = settingKey[(String, Int)]("localUrl")
    val workbenchDefaultRootObject = settingKey[Option[(String, String)]]("path to defaultRootObject served on `/` and rootDirectory")
    val workbenchCompression = settingKey[Boolean]("use gzip compression on HTTP responses")
    val workbenchStartMode = settingKey[StartMode](
      "should the web server start on sbt load, on compile, or only by manually running `startWorkbenchServer`")
    val startWorkbenchServer = taskKey[Unit]("start local web server manually")
  }
  import autoImport._
  import WorkbenchStartModes._

  val server = settingKey[Server]("local websocket server")

  val workbenchSettings = Seq(
    localUrl := ("localhost", 12345),
    workbenchDefaultRootObject := None,
    workbenchCompression := false,
    ThisBuild / extraAppenders := {
      val clientLogger = new ConsoleAppender(
        "WorkbenchFakeAppender",
        null,
        ConsoleAppender.noSuppressedMessage
      ) {
        override def appendLog(level: Level.Value, message: => String): Unit = {
          server.value.Wire[WorkbenchApi].print(level.toString, message).call()
        }

        override def control(event: ControlEvent.Value, message: => String): Unit = {
          server.value.Wire[WorkbenchApi].print(Level.Info.toString, message).call()
        }
      }

      val currentFunction = extraAppenders.value
      key: ScopedKey[_] => clientLogger +: currentFunction(key)
    },
    server := {
      val server = new Server(localUrl.value._1, localUrl.value._2,
        workbenchDefaultRootObject.value.map(_._1), workbenchDefaultRootObject.value.map(_._2), workbenchCompression.value)
      if (workbenchStartMode.value == OnSbtLoad) server.startServer()
      server
    },
    workbenchStartMode := OnSbtLoad,
    startWorkbenchServer := server.value.startServer(),
    (Compile / compile) := (Compile / compile)
      .dependsOn(
        Def.task {
          if (workbenchStartMode.value == OnCompile) server.value.startServer()
        })
      .value,
    (Global / onUnload) := {
      (Global / onUnload).value.compose { state =>
        server.value.kill()
        state
      }
    }
  )

  private def getScopeId(scope: ScopeAxis[sbt.Reference]): String = s"${scope.hashCode()}"
  override def projectSettings: Seq[Setting[_]] = workbenchSettings
}
