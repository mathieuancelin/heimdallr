package test

import java.util.concurrent.atomic.AtomicInteger

import io.heimdallr.models._
import io.heimdallr.util.Timeout
import org.scalatest.{MustMatchers, WordSpec}

import scala.concurrent.duration._

class HeimdallrSpec extends WordSpec with MustMatchers with HeimdallrTestCaseHelper {

  import FutureImplicits._

  "Heimdallr" should {

    "route basic call using Host header" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetServer = TargetService("simple.foo.bar", "/api", "application/json", _ => expectedBody).await()

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

      val (status, body) = HttpCall(targetServer.http, httpPort, "simple.foo.bar", "/api").await()

      status mustEqual 200
      body mustEqual expectedBody

      heimdallr.stop()

    }

    "loadbalance calls" in {

      val expectedBody = """{"message":"hello world"}"""

      val targetCounter1 = new AtomicInteger(0)
      val targetCounter2 = new AtomicInteger(0)
      val targetCounter3 = new AtomicInteger(0)

      val targetServer1 = TargetService("simple.foo.bar", "/api", "application/json", _ => {
        targetCounter1.incrementAndGet()
        expectedBody
      }).await()
      val targetServer2 = TargetService("simple.foo.bar", "/api", "application/json", _ => {
        targetCounter2.incrementAndGet()
        expectedBody
      }).await()
      val targetServer3 = TargetService("simple.foo.bar", "/api", "application/json", _ => {
        targetCounter3.incrementAndGet()
        expectedBody
      }).await()

      import targetServer1.ec
      import targetServer1.mat
      import targetServer1.system

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

      Timeout(2.seconds).await()

      val (status1, body1) = HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").await()
      val (status2, body2) = HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").await()
      val (status3, body3) = HttpCall(targetServer1.http, httpPort, "simple.foo.bar", "/api").await()

      status1 mustEqual 200
      body1 mustEqual expectedBody

      status2 mustEqual 200
      body2 mustEqual expectedBody

      status3 mustEqual 200
      body3 mustEqual expectedBody
      
      targetCounter1.get() mustEqual 1
      targetCounter2.get() mustEqual 1
      targetCounter3.get() mustEqual 1

      heimdallr.stop()

    }
  }
}