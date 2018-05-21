package test

import akka.http.scaladsl.model.Uri
import org.scalatest.{MustMatchers, WordSpec}
import org.slf4j.LoggerFactory

class UtilsSpec extends WordSpec with MustMatchers with HeimdallrTestCaseHelper {

  lazy val logger = LoggerFactory.getLogger("heimdallr-test")

  "Heimdallr uri" should {

    "works" in {

      val uri1 = Uri("https://www.foo.bar/foo/bar?foo=bar")
      val uri2 = Uri("http://www.foo.bar/foo/bar?foo=bar")
      val uri3 = Uri("//www.foo.bar/foo/bar?foo=bar")
      val uri5 = Uri("www.foo.bar/foo/bar?foo=bar")
      val uri4 = Uri("/foo/bar?foo=bar")

      logger.info(s"uri1: $uri1 - ${uri1.toRelative}")
      logger.info(s"uri2: $uri2 - ${uri2.toRelative}")
      logger.info(s"uri3: $uri3 - ${uri3.toRelative}")
      logger.info(s"uri4: $uri4 - ${uri4.toRelative}")
      logger.info(s"uri5: $uri5 - ${uri5.toRelative}")
    }
  }
}
