package util

import java.io.{File, FileInputStream, InputStream}
import java.net.Socket
import java.security.cert.X509Certificate
import java.security._
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import java.util.regex.Pattern
import javax.net.ssl._

import akka.actor.{
  Actor,
  ActorRef,
  ActorRefFactory,
  OneForOneStrategy,
  PoisonPill,
  Props,
  Status,
  SupervisorStrategy,
  Terminated
}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse}
import akka.http.scaladsl.{ConnectionContext, HttpsConnectionContext}
import akka.stream.scaladsl.{Flow, Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import io.circe.Json
import org.slf4j.LoggerFactory
import ssl.PemReader

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Random, Success}

class DynamicKeyManager(manager: X509KeyManager) extends X509KeyManager {

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  logger.info("DynamicKeyManager")

  override def getCertificateChain(alias: String): Array[X509Certificate] = {
    logger.info(s"getCertificateChain($alias)")
    manager.getCertificateChain(alias)
  }

  override def chooseServerAlias(keyType: String, issuers: Array[Principal], socket: Socket): String = {
    logger.info(s"chooseServerAlias($keyType, ${issuers.mkString("[", ",", "]")}, $socket)")
    manager.chooseServerAlias(keyType, issuers, socket)
  }

  override def getClientAliases(keyType: String, issuers: Array[Principal]): Array[String] = {
    logger.info(s"getClientAliases($keyType, ${issuers.mkString("[", ",", "]")})")
    manager.getClientAliases(keyType, issuers)
  }

  override def chooseClientAlias(keyType: Array[String], issuers: Array[Principal], socket: Socket): String = {
    logger.info(s"chooseClientAlias($keyType, ${issuers.mkString("[", ",", "]")}, $socket)")
    manager.chooseClientAlias(keyType, issuers, socket)
  }

  override def getServerAliases(keyTypes: String, issuers: Array[Principal]): Array[String] = {
    logger.info(s"getServerAliases($keyTypes, ${issuers.mkString("[", ",", "]")})")
    manager.getServerAliases(keyTypes, issuers)
  }

  override def getPrivateKey(alias: String): PrivateKey = {
    logger.info(s"getPrivateKey($alias)")
    manager.getPrivateKey(alias)
  }
}

class DynamicTrustManager(manager: X509TrustManager) extends X509TrustManager {

  lazy val logger = LoggerFactory.getLogger("heimdallr")

  logger.info("DynamicTrustManager")

  override def checkServerTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {
    logger.info(s"checkServerTrusted($x509Certificates, $s)")
    manager.checkServerTrusted(x509Certificates, s)
  }

  override def checkClientTrusted(x509Certificates: Array[X509Certificate], s: String): Unit = {
    logger.info(s"checkClientTrusted($x509Certificates, $s)")
    manager.checkServerTrusted(x509Certificates, s)
  }

  override def getAcceptedIssuers: Array[X509Certificate] = {
    logger.info(s"getAcceptedIssuers()")
    manager.getAcceptedIssuers()
  }
}

object HttpsSupport {

  import org.bouncycastle.jce.provider.BouncyCastleProvider
  import java.security.Security

  Security.addProvider(new BouncyCastleProvider)

  def context(certificatePath: String,
              keyPath: Option[String],
              pass: String,
              keyStoreType: String): HttpsConnectionContext = {

    val password: Array[Char] = pass.toCharArray

    val ks: KeyStore = if (keyStoreType == "PEM") {
      val cert    = PemReader.certificateFromCrt(certificatePath)
      val keypair = PemReader.keyPairFromPem(keyPath.get)
      PemReader.loadKeystore("private-key", keypair, cert, pass)
    } else {
      val ks: KeyStore          = KeyStore.getInstance(keyStoreType)
      val keystore: InputStream = new FileInputStream(new File(certificatePath))
      require(keystore != null, "Keystore required!")
      ks.load(keystore, password)
      ks
    }

    val keyManagerFactory: KeyManagerFactory = KeyManagerFactory.getInstance("SunX509")
    keyManagerFactory.init(ks, password)

    val tmf: TrustManagerFactory = TrustManagerFactory.getInstance("SunX509")
    tmf.init(ks)

    val sslContext: SSLContext = SSLContext.getInstance("TLS")
    sslContext.init(keyManagerFactory.getKeyManagers, tmf.getTrustManagers, new SecureRandom)
    ConnectionContext.https(sslContext)
  }
}

/*

https://jcalcote.wordpress.com/2010/06/22/managing-a-dynamic-java-trust-store/
http://codyaray.com/2013/04/java-ssl-with-multiple-keystores
https://gist.github.com/xkr47/457fd13c45bd84764fcd80151f19ffa3
https://gist.github.com/dain/29ce5c135796c007f9ec88e82ab21822

class ReloadableX509TrustManager
    implements X509TrustManager {
  private final String trustStorePath;
  private X509TrustManager trustManager;
  private List tempCertList
      = new List();

  public ReloadableX509TrustManager(String tspath)
      throws Exception {
    this.trustStorePath = tspath;
    reloadTrustManager();
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain,
      String authType) throws CertificateException {
    trustManager.checkClientTrusted(chain, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain,
      String authType) throws CertificateException {
    try {
      trustManager.checkServerTrusted(chain, authType);
    } catch (CertificateException cx) {
      addServerCertAndReload(chain[0], true);
      trustManager.checkServerTrusted(chain, authType);
    }
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    X509Certificate[] issuers
        = trustManager.getAcceptedIssuers();
    return issuers;
  }

  private void reloadTrustManager() throws Exception {

    // load keystore from specified cert store (or default)
    KeyStore ts = KeyStore.getInstance(
	    KeyStore.getDefaultType());
    InputStream in = new FileInputStream(trustStorePath);
    try { ts.load(in, null); }
    finally { in.close(); }

    // add all temporary certs to KeyStore (ts)
    for (Certificate cert : tempCertList) {
      ts.setCertificateEntry(UUID.randomUUID(), cert);
    }

    // initialize a new TMF with the ts we just loaded
    TrustManagerFactory tmf
	    = TrustManagerFactory.getInstance(
            TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(ts);

    // acquire X509 trust manager from factory
    TrustManager tms[] = tmf.getTrustManagers();
    for (int i = 0; i < tms.length; i++) {
      if (tms[i] instanceof X509TrustManager) {
        trustManager = (X509TrustManager)tms[i];
        return;
      }
    }

    throw new NoSuchAlgorithmException(
        "No X509TrustManager in TrustManagerFactory");
  }

  private void addServerCertAndReload(Certificate cert,
      boolean permanent) {
    try {
      if (permanent) {
        // import the cert into file trust store
        // Google "java keytool source" or just ...
        Runtime.getRuntime().exec("keytool -importcert ...");
      } else {
        tempCertList.add(cert);
      }
      reloadTrustManager();
    } catch (Exception ex) { /* ... */ }
  }
}
 */

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

case object CloseMessage

object ActorFlow {

  def actorRef[In, Out](
      props: ActorRef => Props,
      bufferSize: Int = 16,
      overflowStrategy: OverflowStrategy = OverflowStrategy.dropNew
  )(implicit factory: ActorRefFactory, mat: Materializer): Flow[In, Out, _] = {

    val (outActor, publisher) = Source
      .actorRef[Out](bufferSize, overflowStrategy)
      .toMat(Sink.asPublisher(false))(Keep.both)
      .run()

    Flow.fromSinkAndSource(
      Sink.actorRef(
        factory.actorOf(Props(new Actor {
          val flowActor = context.watch(context.actorOf(props(outActor), "flowActor"))

          def receive = {
            case Status.Success(_) | Status.Failure(_) =>
              flowActor ! CloseMessage
              flowActor ! PoisonPill
            case Terminated(_) => context.stop(self)
            case other         => flowActor ! other
          }

          override def supervisorStrategy = OneForOneStrategy() {
            case _ => SupervisorStrategy.Stop
          }
        })),
        Status.Success(())
      ),
      Source.fromPublisher(publisher)
    )
  }
}

class IdGenerator(generatorId: Long) {
  def nextId(): Long = IdGenerator.nextId(generatorId)
}

object IdGenerator {

  private[this] val CHARACTERS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray.map(_.toString)
  private[this] val EXTENDED_CHARACTERS =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789*$%)([]!=+-_:/;.><&".toCharArray.map(_.toString)
  private[this] val INIT_STRING = for (i <- 0 to 15) yield Integer.toHexString(i)

  private[this] val minus         = 1288834974657L
  private[this] val counter       = new AtomicLong(-1L)
  private[this] val lastTimestamp = new AtomicLong(-1L)

  def apply(generatorId: Long) = new IdGenerator(generatorId)

  def nextId(generatorId: Long): Long = synchronized {
    if (generatorId > 1024L) throw new RuntimeException("Generator id can't be larger than 1024")
    val timestamp = System.currentTimeMillis
    if (timestamp < lastTimestamp.get()) throw new RuntimeException("Clock is running backward. Sorry :-(")
    lastTimestamp.set(timestamp)
    counter.compareAndSet(4095, -1L)
    ((timestamp - minus) << 22L) | (generatorId << 10L) | counter.incrementAndGet()
  }

  def uuid: String =
    (for {
      c <- 0 to 36
    } yield
      c match {
        case i if i == 9 || i == 14 || i == 19 || i == 24 => "-"
        case i if i == 15                                 => "4"
        case i if c == 20                                 => INIT_STRING((Random.nextDouble() * 4.0).toInt | 8)
        case i                                            => INIT_STRING((Random.nextDouble() * 15.0).toInt | 0)
      }).mkString("")

  def token(characters: Array[String], size: Int): String =
    (for {
      i <- 0 to size - 1
    } yield characters(Random.nextInt(characters.size))).mkString("")

  def token(size: Int): String         = token(CHARACTERS, size)
  def token: String                    = token(64)
  def extendedToken(size: Int): String = token(EXTENDED_CHARACTERS, size)
  def extendedToken: String            = token(EXTENDED_CHARACTERS, 64)
}
