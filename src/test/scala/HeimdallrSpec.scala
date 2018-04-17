package test

import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger

import akka.http.scaladsl.model.headers.RawHeader
import io.heimdallr.models._
import org.scalatest.{MustMatchers, WordSpec}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

class HeimdallrSpec extends WordSpec with MustMatchers with HeimdallrTestCaseHelper {

  import FutureImplicits._

  lazy val logger = LoggerFactory.getLogger("heimdallr-test")

  "Heimdallr" should {

    "route basic calls" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetServer = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => expectedBody).await()

      import targetServer.ec
      import targetServer.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer.port}")
          ),
          publicPatterns = Set("/*")
        )
      ))

      val (status, body, _) = HttpCall(targetServer.http, httpPort, "simple.foo.bar", "/api").await()

      status mustEqual 200
      body mustEqual expectedBody

      heimdallr.stop()

    }

    "route basic calls with apikey" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetServer = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => expectedBody).await()

      import targetServer.ec
      import targetServer.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer.port}")
          ),
          publicPatterns = Set("/*"),
          apiKeys = Seq(
            ApiKey(
              clientId = "1234",
              clientSecret = "4321",
              name = "apikey",
              enabled = true
            )
          )
        )
      ))

      val encoder = Base64.getUrlEncoder

      HttpCall(targetServer.http, httpPort, "simple.foo.bar", "/api", Seq(RawHeader("Authorization", s"Basic ${encoder.encodeToString(s"1234:4321".getBytes())}"))).map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      HttpCall(targetServer.http, httpPort, "simple.foo.bar", "/api", Seq(RawHeader("Proxy-Authorization", s"Basic ${encoder.encodeToString(s"1234:4321".getBytes())}"))).map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      heimdallr.stop()

    }

    "return heimdallr specific headers" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetServer = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => expectedBody).await()

      import targetServer.ec
      import targetServer.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer.port}")
          ),
          publicPatterns = Set("/*")
        )
      ))

      val (status, body, headers) = HttpCall(targetServer.http, httpPort, "simple.foo.bar", "/api").await()

      status mustEqual 200
      body mustEqual expectedBody

      headers.exists {
        case header if header.name() == "X-Proxy-Latency" => true
        case _ => false
      } mustBe true

      headers.exists {
        case header if header.name() == "X-Target-Latency" => true
        case _ => false
      } mustBe true

      heimdallr.stop()

    }

    "send heimdallr specific headers" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetServer = TargetService(Some("simple.foo.bar"), "/api", "application/json", req => {
        req.headers.exists {
          case header if header.name() == "X-Request-Id" => true
          case _ => false
        } mustBe true
        req.headers.exists {
          case header if header.name() == "X-Fowarded-Host" && header.value() == "simple.foo.bar" => true
          case _ => false
        } mustBe true
        req.headers.exists {
          case header if header.name() == "X-Fowarded-Scheme" && header.value() == "http" => true
          case _ => false
        } mustBe true
        expectedBody
      }).await()

      import targetServer.ec
      import targetServer.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer.port}")
          ),
          publicPatterns = Set("/*")
        )
      ))

      val (status, body, _) = HttpCall(targetServer.http, httpPort, "simple.foo.bar", "/api").await()

      status mustEqual 200
      body mustEqual expectedBody

      heimdallr.stop()

    }

    "route wildcard calls" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetCounter = new AtomicInteger(0)

      val targetServer = TargetService(None, "/api", "application/json", _ => {
        targetCounter.incrementAndGet()
        expectedBody
      }).await()

      import targetServer.ec
      import targetServer.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test",
          domain = "simple-*.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer.port}")
          ),
          publicPatterns = Set("/*")
        )
      ))

      HttpCall(targetServer.http, httpPort, "simple-foo.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      HttpCall(targetServer.http, httpPort, "simple-bar.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      HttpCall(targetServer.http, httpPort, "simle-bar.foo.bar", "/api").map {
        case (status, _, _) =>
          status mustEqual 404
      }.await()

      targetCounter.get() mustEqual 2

      heimdallr.stop()

    }

    "route calls using matching headers" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetServer = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => expectedBody).await()

      import targetServer.ec
      import targetServer.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer.port}")
          ),
          publicPatterns = Set("/*"),
          matchingHeaders = Map(
            "X-Foo" -> "Bar"
          )
        )
      ))

      HttpCall(targetServer.http, httpPort, "simple.foo.bar", "/api").map {
        case (status, _, _) =>
          status mustEqual 404
      }.await()

      HttpCall(targetServer.http, httpPort, "simple.foo.bar", "/api", Seq(RawHeader("X-Foo", "Bar"))).map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      heimdallr.stop()

    }

    "route calls using matching root" in {

      val expectedBody1 = """{"message":"hello world 1"}"""
      val expectedBody2 = """{"message":"hello world 2"}"""

      val targetServer1 = TargetService(Some("simple.foo.bar"), "/foo/api", "application/json", req => {
        logger.info("server 1: " + req.uri.path)
        expectedBody1
      }).await()
      val targetServer2 = TargetService(Some("simple.foo.bar"), "/bar/api", "application/json", req => {
        logger.info("server 2: " + req.uri.path)
        expectedBody2
      }).await()

      import targetServer1.ec
      import targetServer2.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer1.port}")
          ),
          publicPatterns = Set("/*"),
          root = Some("/foo"),
          targetRoot = "/api"
        ),
        Service(
          id = "simple-test",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer2.port}")
          ),
          publicPatterns = Set("/*"),
          root = Some("/bar"),
          targetRoot = "/api"
        )
      ))

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/baz").map {
        case (status, _, _) =>
          status mustEqual 404
      }.await()

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/foo/api").map {
        case (status, body, _) =>
          logger.info(body)
          status mustEqual 200
          body mustEqual expectedBody1
      }.await()

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/bar/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody2
      }.await()

      heimdallr.stop()

    }

    "loadbalance calls" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetCounter1 = new AtomicInteger(0)
      val targetCounter2 = new AtomicInteger(0)
      val targetCounter3 = new AtomicInteger(0)

      val targetServer1 = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => {
        targetCounter1.incrementAndGet()
        expectedBody
      }).await()
      val targetServer2 = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => {
        targetCounter2.incrementAndGet()
        expectedBody
      }).await()
      val targetServer3 = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => {
        targetCounter3.incrementAndGet()
        expectedBody
      }).await()

      import targetServer1.ec
      import targetServer1.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test-1",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer1.port}"),
            Target(s"http://127.0.0.1:${targetServer2.port}"),
            Target(s"http://127.0.0.1:${targetServer3.port}")
          ),
          publicPatterns = Set("/*")
        )
      ))

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      targetCounter1.get() mustEqual 1
      targetCounter2.get() mustEqual 1
      targetCounter3.get() mustEqual 1

      heimdallr.stop()

    }

    "loadbalance calls with weighted targets" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetCounter1 = new AtomicInteger(0)
      val targetCounter2 = new AtomicInteger(0)
      val targetCounter3 = new AtomicInteger(0)

      val targetServer1 = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => {
        targetCounter1.incrementAndGet()
        expectedBody
      }).await()
      val targetServer2 = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => {
        targetCounter2.incrementAndGet()
        expectedBody
      }).await()
      val targetServer3 = TargetService(Some("simple.foo.bar"), "/api", "application/json", _ => {
        targetCounter3.incrementAndGet()
        expectedBody
      }).await()

      import targetServer1.ec
      import targetServer1.mat

      val httpPort = freePort

      val heimdallr = HeimdallrInstance(httpPort, Seq(
        Service(
          id = "simple-test-1",
          domain = "simple.foo.bar",
          targets = Seq(
            Target(s"http://127.0.0.1:${targetServer1.port}", weight = 2),
            Target(s"http://127.0.0.1:${targetServer2.port}"),
            Target(s"http://127.0.0.1:${targetServer3.port}")
          ),
          publicPatterns = Set("/*")
        )
      ))

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").map {
        case (status, body, _) =>
          status mustEqual 200
          body mustEqual expectedBody
      }.await()

      targetCounter1.get() mustEqual 2
      targetCounter2.get() mustEqual 1
      targetCounter3.get() mustEqual 1

      heimdallr.stop()

    }
  }
}