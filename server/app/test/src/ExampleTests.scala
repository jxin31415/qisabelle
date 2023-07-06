package app
import io.undertow.Undertow

import utest._

object ExampleTests extends TestSuite {
  def withServer[T](server: cask.main.Main)(f: String => T): T = {
    val port = sys.env("QISABELLE_PORT").toInt
    val server = Undertow.builder
      .addHttpListener(port, "localhost")
      .setHandler(server.defaultHandler)
      .build
    server.start()
    val res =
      try f(s"http://localhost:$port")
      finally server.stop()
    res
  }

  val tests = Tests {
    test("exampleTest1") - withServer(QISabelleServer) { host =>
      val success = requests.get(host)
      success.text() ==> "Hello World!"
      success.statusCode ==> 200
    }
    test("exampleTest2") - withServer(QISabelleServer) { host =>
      requests.get(s"$host/doesnt-exist", check = false).statusCode ==> 404
    }
  }
}

