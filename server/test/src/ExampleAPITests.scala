package server
import io.undertow.Undertow

import org.scalatest.funsuite.AnyFunSuite

class ExampleTests extends AnyFunSuite {
  def withServer[T](server_class: cask.main.Main)(f: String => T): T = {
    val port = sys.env("QISABELLE_PORT").toInt
    val server = Undertow.builder
      .addHttpListener(port, "localhost")
      .setHandler(server_class.defaultHandler)
      .build
    server.start()
    val res =
      try f(s"http://localhost:$port")
      finally server.stop()
    res
  }

  test("exampleAPITest1") {
    withServer(QISabelleServer) { host =>
      val success = requests.get(host)
      assert(success.text() == "Hello World!")
      assert(success.statusCode == 200)
    }
  }
  test("exampleAPITest2") {
    withServer(QISabelleServer) { host =>
      assert(requests.get(s"$host/doesnt-exist", check = false).statusCode == 404)
    }
  }
}
