package util

import java.io.{File, FileInputStream, InputStream}
import java.security.{KeyStore, SecureRandom}
import java.util.Optional
import javax.net.ssl.{KeyManagerFactory, SSLContext, TrustManagerFactory}

import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import io.circe.Json

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success}

object HttpsSupport {
  def context(certificatePath: String, pass: String, keyStoreType: String = "PKCS12"): HttpsConnectionContext = {
    val password: Array[Char] = pass.toCharArray

    val ks: KeyStore          = KeyStore.getInstance(keyStoreType)
    val keystore: InputStream = new FileInputStream(new File(certificatePath))

    require(keystore != null, "Keystore required!")
    ks.load(keystore, password)

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    ConnectionContext.https(sslContext)
  }
}

object Retry {

  private[this] def retryPromise[T](times: Int, promise: Promise[T], failure: Option[Throwable], f: => Future[T])(
      implicit ec: ExecutionContext
  ): Unit = {
    (times, failure) match {
      case (0, Some(e)) =>
        promise.tryFailure(e)
      case (0, None) =>
        promise.tryFailure(new RuntimeException("Failure, but lost track of exception :-("))
      case (_, _) =>
        f.onComplete {
          case Success(t) =>
            promise.trySuccess(t)
          case Failure(e) =>
            retryPromise[T](times - 1, promise, Some(e), f)
        }(ec)
    }
  }

  def retry[T](times: Int)(f: => Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val promise = Promise[T]()
    retryPromise[T](times, promise, None, f)
    promise.future
  }
}

object HttpResponses {

  def NotFound(path: String) = HttpResponse(
    404,
    entity =
      HttpEntity(ContentTypes.`application/json`, Json.obj("error" -> Json.fromString(s"$path not found")).noSpaces)
  )

  def GatewayTimeout() = HttpResponse(
    504,
    entity = HttpEntity(ContentTypes.`application/json`,
                        Json.obj("error" -> Json.fromString(s"Target servers timeout")).noSpaces)
  )

  def BadGateway(message: String) = HttpResponse(
    502,
    entity = HttpEntity(ContentTypes.`application/json`, Json.obj("error" -> Json.fromString(message)).noSpaces)
  )

  def BadRequest(message: String) = HttpResponse(
    400,
    entity = HttpEntity(ContentTypes.`application/json`, Json.obj("error" -> Json.fromString(message)).noSpaces)
  )

  def Unauthorized(message: String) = HttpResponse(
    401,
    entity = HttpEntity(ContentTypes.`application/json`, Json.obj("error" -> Json.fromString(message)).noSpaces)
  )

  def Ok(json: Json) = HttpResponse(
    200,
    entity = HttpEntity(ContentTypes.`application/json`, json.noSpaces)
  )
}

object Implicits {
  implicit class BetterOptional[A](val opt: Optional[A]) extends AnyVal {
    def asOption: Option[A] = {
      if (opt.isPresent) {
        Some(opt.get())
      } else {
        None
      }
    }
  }
}

import java.util.regex.Pattern

case class Regex(originalPattern: String, compiledPattern: Pattern) {
  def matches(value: String): Boolean = compiledPattern.matcher(value).matches()
}

object RegexPool {

  private val pool = new java.util.concurrent.ConcurrentHashMap[String, Regex]()

  def apply(originalPattern: String): Regex = {
    if (!pool.containsKey(originalPattern)) {
      val processedPattern: String = originalPattern.replace(".", "\\.").replaceAll("\\*", ".*")
      pool.putIfAbsent(originalPattern, Regex(originalPattern, Pattern.compile(processedPattern)))
    }
    pool.get(originalPattern)
  }

  def regex(originalPattern: String): Regex = {
    if (!pool.containsKey(originalPattern)) {
      pool.putIfAbsent(originalPattern, Regex(originalPattern, Pattern.compile(originalPattern)))
    }
    pool.get(originalPattern)
  }
}

trait Startable[A] {
  def start(): Stoppable[A]
}

trait Stoppable[A] {
  def stop(): Unit
}
