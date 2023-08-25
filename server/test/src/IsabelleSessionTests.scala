package server
import io.undertow.Undertow

import utest._

object IsabelleSessionTests extends TestSuite {
  val afpDir = os.Path("/home/mwrochna/projects/play/afp-2023-03-16/thys/")
  val isabelleDir =
    os.Path("/home/mwrochna/projects/play/Isabelle2022") // "/home/isabelle/Isabelle/" in container
  val tests = Tests {
    test("IsabelleSession Test 1") {
      // var p = afpDir / "Coinductive" / "Examples" / "CCPO_Topology.thy" // One with a .. import.
      var p = afpDir / "QR_Decomposition" / "Generalizations2.thy" // A much longer one.
      // var p = afpDir / "Graph_Theory" / "Digraph.thy"
      // var p = afpDir / "Real_Impl" / "Real_Impl.thy"
      // var p = afpDir / "Real_Impl" / "Real_Impl_Auxiliary.thy"
      // var p = afpDir / "Abstract-Rewriting" / "SN_Order_Carrier.thy"
      // var p = afpDir / "Formula_Derivatives" / "Abstract_Formula.thy"

      val session = new IsabelleSession(
        isabelleDir = isabelleDir,
        workingDir = p / os.up, // / os.up,
        theoryPath = p,
        debug = true
      )

      val s = session.execute(session.parsedTheory.transitions)
      assert(s.isEndTheory(session.isabelle))
      session.close()
      "success"
    }

    test("IsabelleSession Test 2") {
      var p = afpDir / "Graph_Theory" / "Digraph.thy"

      val session = new IsabelleSession(
        isabelleDir = isabelleDir,
        workingDir = p / os.up, // / os.up,
        theoryPath = p,
        debug = true
      )

      val s = session.execute(session.parsedTheory.transitions)
      assert(s.isEndTheory(session.isabelle))
      session.close()
      "success"
    }
  }
}
