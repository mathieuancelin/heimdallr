/*
package kafka

import akka.Done
import akka.actor.{Actor, ActorSystem, Props}
import akka.kafka.ProducerSettings
import io.circe.Json
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.serialization.{ByteArraySerializer, StringSerializer}
import org.slf4j.LoggerFactory

import scala.concurrent.Promise
import scala.util.Try

case class KafkaConfig(servers: Seq[String],
                       keyPass: Option[String] = None,
                       keystore: Option[String] = None,
                       truststore: Option[String] = None,
                       eventsTopic: String = "heimdallr-events",
                       commandsTopic: String = "heimdallr-command")

object KafkaConfig {
  implicit val encoder = deriveEncoder[KafkaConfig]
}

object KafkaSettings {

  def producerSettings(config: KafkaConfig, system: ActorSystem): ProducerSettings[Array[Byte], String] = {
    val settings = ProducerSettings
      .create(system, new ByteArraySerializer(), new StringSerializer())
      .withBootstrapServers(config.servers.mkString(","))

    val s = for {
      ks <- config.keystore
      ts <- config.truststore
      kp <- config.keyPass
    } yield {
      settings
        .withProperty(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
        .withProperty(SslConfigs.SSL_CLIENT_AUTH_CONFIG, "required")
        .withProperty(SslConfigs.SSL_KEY_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_KEYSTORE_LOCATION_CONFIG, ks)
        .withProperty(SslConfigs.SSL_KEYSTORE_PASSWORD_CONFIG, kp)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, ts)
        .withProperty(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, kp)
    }

    s.getOrElse(settings)
  }
}

case class KafkaWrapperEvent(event: Json)
case class KafkaWrapperEventClose()

class KafkaWrapper(system: ActorSystem, topicFunction: KafkaConfig => String) {

  val kafkaWrapperActor = system.actorOf(KafkaWrapperActor.props(topicFunction))

  def publish(event: Json): Done = {
    kafkaWrapperActor ! KafkaWrapperEvent(event)
    Done
  }

  def close(): Unit = {
    kafkaWrapperActor ! KafkaWrapperEventClose()
  }
}

class KafkaWrapperActor(topicFunction: KafkaConfig => String) extends Actor {

  var config: Option[KafkaConfig]               = None
  var eventProducer: Option[KafkaEventProducer] = None

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  override def receive: Receive = {
    case event: KafkaWrapperEvent =>
      eventProducer.get.publish(event.event)
    case KafkaWrapperEventClose() =>
      eventProducer.foreach(_.close())
      config = None
      eventProducer = None
    case _ =>
  }
}

object KafkaWrapperActor {
  def props(topicFunction: KafkaConfig => String) = Props(new KafkaWrapperActor(topicFunction))
}

class KafkaEventProducer(config: KafkaConfig, topicFunction: KafkaConfig => String) {

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  lazy val topic = topicFunction(config)

  logger.info(s"Initializing kafka event store on topic ${topic}")

  private lazy val producerSettings                             = KafkaSettings.producerSettings(config)
  private lazy val producer: KafkaProducer[Array[Byte], String] = producerSettings.createKafkaProducer

  def publish(event: Json): Try[Done] = {
    Try {
      val message = Json.stringify(event)
      producer.send(new ProducerRecord[Array[Byte], String](topic, message), callback(promise))
    }
  }

  def close() =
    producer.close()

  private def callback(promise: Promise[RecordMetadata]) = new Callback {
    override def onCompletion(metadata: RecordMetadata, exception: Exception) =
      if (exception != null) {
        promise.failure(exception)
      } else {
        promise.success(metadata)
      }

  }
}
 */
