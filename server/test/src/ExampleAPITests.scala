package server
import io.undertow.Undertow

import org.scalatest.funsuite.AnyFunSuite

class ExampleTests extends AnyFunSuite {
  def withServer(f: String => Unit) = {
    val port = sys.env.getOrElse("QISABELLE_PORT", "17000").toInt
    val server = Undertow.builder
      .addHttpListener(port, "localhost")
      .setHandler(QISabelleServer.defaultHandler)
      .build
    server.start()
    val res =
      try f(s"http://localhost:$port")
      finally server.stop()
    res
  }

  test("exampleAPITest1") {
    withServer { hostUrl =>
      val success = requests.get(hostUrl)
      assert(success.text() == "Hello World!")
      assert(success.statusCode == 200)
    }
  }
  test("exampleAPITest2") {
    withServer { hostUrl =>
      assert(requests.get(s"$hostUrl/doesnt-exist", check = false).statusCode == 404)
    }
  }
}
