package proxies

import java.util.concurrent.atomic.AtomicReference

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.http.scaladsl.model.ws.{Message, WebSocketRequest}
import akka.http.scaladsl.model.{HttpHeader, Uri}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source, SourceQueueWithComplete}
import akka.stream.{Materializer, OverflowStrategy}
import akka.util.ByteString
import org.slf4j.LoggerFactory
import util.CloseMessage

import scala.util.{Failure, Success}

object WebSocketProxyActor {
  def props(url: Uri, mat: Materializer, out: ActorRef, http: akka.http.scaladsl.HttpExt, headers: Seq[HttpHeader]) =
    Props(new WebSocketProxyActor(url, mat, out, http, headers))
}

class WebSocketProxyActor(uri: Uri,
                          materializer: Materializer,
                          out: ActorRef,
                          http: akka.http.scaladsl.HttpExt,
                          headers: Seq[HttpHeader])
    extends Actor {

  lazy val logger = LoggerFactory.getLogger("heimdallr-ws")
  lazy val source = Source.queue[Message](50000, OverflowStrategy.dropTail)

  implicit val ec  = context.dispatcher
  implicit val mat = materializer

  val queueRef = new AtomicReference[SourceQueueWithComplete[Message]]

  val avoid =
    Seq("Upgrade", "Connection", "Sec-WebSocket-Key", "Sec-WebSocket-Version", "Sec-WebSocket-Extensions")

  override def preStart() =
    try {
      val request = WebSocketRequest(
        uri = uri,
        extraHeaders = headers.filterNot(h => avoid.contains(h.name())).toList
      )
      logger.debug(
        "[WEBSOCKET] initializing client call ... " + request.uri.toString() + " " + request.extraHeaders.mkString(", ")
      )
      val (connected, materialized) = http.singleWebSocketRequest(
        request,
        Flow
          .fromSinkAndSourceMat(
            Sink.foreach[Message] { msq =>
              logger.debug(s"[WEBSOCKET] message from target: ${msq.toString}")
              out ! msq
            },
            source
          )(Keep.both)
          .alsoTo(Sink.onComplete { _ =>
            logger.debug(s"[WEBSOCKET] target stopped")
            Option(queueRef.get()).foreach(_.complete())
            out ! PoisonPill
          })
      )
      queueRef.set(materialized._2)
      connected.andThen {
        case Success(r) => {
          logger.debug(
            s"[WEBSOCKET] connected to target ${r.response.status} :: ${r.response.headers.map(h => h.toString()).mkString(", ")}"
          )
          r.response.entity.dataBytes.runFold(ByteString.empty)(_ ++ _).map { bs =>
            logger.debug(s"[WEBSOCKET] connected to target with response '${bs.utf8String}'")
          }
        }
        case Failure(e) => logger.error(s"[WEBSOCKET] error", e)
      }(context.dispatcher)
    } catch {
      case e: Exception => logger.error("[WEBSOCKET] error during call", e)
    }

  override def postStop() = {
    logger.debug(s"[WEBSOCKET] client stopped")
    Option(queueRef.get()).foreach(_.complete())
    out ! PoisonPill
  }

  def receive = {
    case msg: Message => {
      logger.debug(s"[WEBSOCKET] message from client: ${msg}")
      Option(queueRef.get()).foreach(_.offer(msg))
    }
    case CloseMessage => {
      Option(queueRef.get()).foreach(_.complete())
      out ! PoisonPill
    }
    case e => logger.error(s"[WEBSOCKET] Bad message type: $e")
  }
}
