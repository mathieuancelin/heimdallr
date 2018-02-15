package io.heimdallr.statsd

import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Try}
import scala.util.control.NonFatal

import akka.{Done, NotUsed}
import akka.actor.{Actor, ActorSystem, Props}
import org.slf4j.LoggerFactory

import io.heimdallr.models._
import io.heimdallr.statsd._
import io.heimdallr.util._

import github.gphat.censorinus._

case class TimeCtx(start: Long, name: String, statsd: Statsd) {
  def close(): Unit = {
    statsd.timer(name, System.currentTimeMillis - start)
  }
}

class Statsd(config: ProxyConfig, actorSystem: ActorSystem) extends Startable[Statsd] with Stoppable[Statsd] {

  lazy val statsdActor = actorSystem.actorOf(StatsdActor.props())

  lazy val defaultSampleRate: Double = 1.0

  val optConfig = config.statsd

  override def start(): Statsd = {
    this
  }

  override def stop(): Unit = {
    close()
  }

  def close(): Unit = {
    statsdActor ! StatsdEventClose()
  }

  def counter(
      name: String,
      value: Double,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  ): Unit = {
    optConfig.foreach(
      config => statsdActor ! StatsdEvent("counter", name, value, "", sampleRate, bypassSampler, config)
    )
    if (optConfig.isEmpty) close()
  }

  def decrement(
      name: String,
      value: Double = 1,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  ): Unit = {
    optConfig.foreach(
      config => statsdActor ! StatsdEvent("decrement", name, value, "", sampleRate, bypassSampler, config)
    )
    if (optConfig.isEmpty) close()
  }

  def gauge(
      name: String,
      value: Double,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  ): Unit = {
    optConfig.foreach(config => statsdActor ! StatsdEvent("gauge", name, value, "", sampleRate, bypassSampler, config))
    if (optConfig.isEmpty) close()
  }

  def increment(
      name: String,
      value: Double = 1,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  ): Unit = {
    optConfig.foreach(
      config => statsdActor ! StatsdEvent("increment", name, value, "", sampleRate, bypassSampler, config)
    )
    if (optConfig.isEmpty) close()
  }

  def meter(
      name: String,
      value: Double,
      sampleRate: Double = defaultSampleRate,
      bypassSampler: Boolean = false
  ): Unit = {
    optConfig.foreach(config => statsdActor ! StatsdEvent("meter", name, value, "", sampleRate, bypassSampler, config))
    if (optConfig.isEmpty) close()
  }

  def set(name: String, value: String): Unit = {
    optConfig.foreach(config => statsdActor ! StatsdEvent("set", name, 0.0, value, 0.0, false, config))
    if (optConfig.isEmpty) close()
  }

  def timeCtx(name: String): TimeCtx = {
    TimeCtx(System.currentTimeMillis, name, this)
  }

  def timer(name: String,
            milliseconds: Double,
            sampleRate: Double = defaultSampleRate,
            bypassSampler: Boolean = false): Unit = {
    optConfig.foreach(
      config => statsdActor ! StatsdEvent("timer", name, milliseconds, "", sampleRate, bypassSampler, config)
    )
    if (optConfig.isEmpty) close()
  }
}

class StatsdActor() extends Actor {

  var config: Option[StatsdConfig]           = None
  var statsdclient: Option[StatsDClient]     = None
  var datadogclient: Option[DogStatsDClient] = None

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  override def receive: Receive = {
    case StatsdEventClose() => {
      config = None
      statsdclient.foreach(_.shutdown())
      datadogclient.foreach(_.shutdown())
      statsdclient = None
      datadogclient = None
    }
    case event: StatsdEvent if config.isEmpty => {
      config = Some(event.config)
      statsdclient.foreach(_.shutdown())
      datadogclient.foreach(_.shutdown())
      event.config.datadog match {
        case true =>
          logger.warn("Metrics - Running statsd for DataDog")
          datadogclient = Some(new DogStatsDClient(event.config.host, event.config.port, "heimdallr"))
        case false =>
          logger.warn("Metrics - Running statsd")
          statsdclient = Some(new StatsDClient(event.config.host, event.config.port, "heimdallr"))
      }
      self ! event
    }
    case event: StatsdEvent if config.isDefined && config.get != event.config => {
      config = Some(event.config)
      statsdclient.foreach(_.shutdown())
      datadogclient.foreach(_.shutdown())
      event.config.datadog match {
        case true =>
          logger.warn("Metrics - Reconfiguring statsd for DataDog")
          datadogclient = Some(new DogStatsDClient(event.config.host, event.config.port, "heimdallr"))
        case false =>
          logger.warn("Metrics - Reconfiguring statsd")
          statsdclient = Some(new StatsDClient(event.config.host, event.config.port, "heimdallr"))
      }
      self ! event
    }
    case StatsdEvent("counter", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.counter(name, value, sampleRate, bypassSampler)
    case StatsdEvent("decrement", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.decrement(name, value, sampleRate, bypassSampler)
    case StatsdEvent("gauge", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.gauge(name, value, sampleRate, bypassSampler)
    case StatsdEvent("increment", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.increment(name, value, sampleRate, bypassSampler)
    case StatsdEvent("meter", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.meter(name, value, sampleRate, bypassSampler)
    case StatsdEvent("timer", name, value, _, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.timer(name, value, sampleRate, bypassSampler)
    case StatsdEvent("set", name, _, value, sampleRate, bypassSampler, StatsdConfig(false, _, _)) =>
      statsdclient.get.set(name, value)

    case StatsdEvent("counter", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.counter(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("decrement", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.decrement(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("gauge", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.gauge(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("increment", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.increment(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("meter", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.histogram(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("timer", name, value, _, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.timer(name, value, sampleRate, Seq.empty[String], bypassSampler)
    case StatsdEvent("set", name, _, value, sampleRate, bypassSampler, StatsdConfig(true, _, _)) =>
      datadogclient.get.set(name, value, Seq.empty[String])

    case _ =>
  }
}

object StatsdActor {
  def props() = Props(new StatsdActor())
}
