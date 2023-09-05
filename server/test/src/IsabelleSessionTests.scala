package server

import de.unruh.isabelle.control.IsabelleMLException

class IsabelleSessionTests extends TestEnvironment {
  test("Execute all theory: Graph_Theory/Digraph") {
    withIsabelle(afpDir / "Graph_Theory" / "Digraph.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        implicit val ec       = session.ec

        val state = parsedTheory.executeAll()
        assert(state.isEndTheory)
    }
  }

  test("Execute all theory: Coinductive/Examples/CCPO_Topology") {
    // One with a relative '../' import.
    withIsabelle(afpDir / "Coinductive" / "Examples" / "CCPO_Topology.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        implicit val ec       = session.ec

        val state = parsedTheory.executeAll()
        assert(state.isEndTheory)
    }
  }

  test("Execute all theory: QR_Decomposition/Generalizations2") {
    // var p = afpDir / "Real_Impl" / "Real_Impl.thy"
    // var p = afpDir / "Real_Impl" / "Real_Impl_Auxiliary.thy"
    // var p = afpDir / "Abstract-Rewriting" / "SN_Order_Carrier.thy"
    // var p = afpDir / "Formula_Derivatives" / "Abstract_Formula.thy"

    // A much longer theory.
    var p = afpDir / "QR_Decomposition" / "Generalizations2.thy"

    withIsabelle(p) { (session: IsabelleSession, parsedTheory: ParsedTheory) =>
      implicit val isabelle = session.isabelle
      implicit val ec       = session.ec

      val state = parsedTheory.executeAll()
      assert(state.isEndTheory)
    }
  }

  test("Execute all theory: HOL/Example/Seq") {
    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        implicit val ec       = session.ec

        val state = parsedTheory.executeAll(nDebug = 1000)
        assert(state.isEndTheory)
    }
  }

  test("Missing fact") {
    // We fork states to check facts proved in one are not visible in the other.
    // Right state will prove first lemma.
    // Left state will try to prove second lemma with an undefined reference to first.
    // This should fail, even if the right state is already computed.
    val firstLemma =
      "lemma reverse_conc: \"reverse (conc xs ys) = conc (reverse ys) (reverse xs)\""
    val firstProof  = "by (induct xs) (simp_all add: conc_empty conc_assoc)"
    val secondLemma = "lemma reverse_reverse: \"reverse (reverse xs) = xs\""
    val secondProof = "by (induct xs) (simp_all add: reverse_conc)"

    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        implicit val ec       = session.ec

        val stateCommon = parsedTheory.executeUntil(firstLemma, inclusive = false)
        assert(stateCommon.isTheoryMode)

        // Right: prove first lemma.
        var stateR = IsabelleSession.step(firstLemma, stateCommon)
        assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
        stateR = IsabelleSession.step(firstProof, stateR)
        assert(stateR.isTheoryMode && stateR.proofStateDescription == "")

        // Left: attempt to prove second lemma with undefined reference to first.
        var stateL = IsabelleSession.step(secondLemma, stateCommon)
        assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
        val thrown = intercept[IsabelleMLException] {
          stateL = IsabelleSession.step(secondProof, stateL).force
        }
        val msg = thrown.getMessage()
        assert(msg.contains("Undefined fact") && msg.contains("reverse_conc"))

        // Right: prove second lemma from first lemma.
        stateR = IsabelleSession.step(secondLemma, stateR)
        assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
        stateR = IsabelleSession.step(secondProof, stateR)
        assert(stateR.isTheoryMode && stateR.proofStateDescription == "")
    }
  }

  test("Bad fact") {
    // We fork states to check facts proved in one are not visible in the other.
    // Right state will prove first lemma.
    // Left state will try to prove a different first lemma under the same name,
    // and a second lemma with an undefined reference to first.
    // This should fail, even if the right state is already computed.
    val firstLemma =
      "lemma reverse_conc: \"reverse (conc xs ys) = conc (reverse ys) (reverse xs)\""
    val firstProof = "by (induct xs) (simp_all add: conc_empty conc_assoc)"
    val firstLemmaAlt =
      "lemma reverse_conc: \"conc xs Empty = xs\""
    val firstProofAlt = "by (simp_all add: conc_empty)"
    val secondLemma   = "lemma reverse_reverse: \"reverse (reverse xs) = xs\""
    val secondProof   = "by (induct xs) (simp_all add: reverse_conc)"

    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        implicit val ec       = session.ec

        val stateCommon = parsedTheory.executeUntil(firstLemma, inclusive = false)
        assert(stateCommon.isTheoryMode)

        // Left: prove dummy first lemma under same name.
        var stateL = IsabelleSession.step(firstLemmaAlt, stateCommon)
        assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
        stateL = IsabelleSession.step(firstProofAlt, stateL)
        assert(stateL.isTheoryMode && stateL.proofStateDescription == "")

        // Right: prove true first lemma.
        var stateR = IsabelleSession.step(firstLemma, stateCommon)
        stateR = IsabelleSession.step(firstProof, stateR)
        assert(stateR.isTheoryMode && stateR.proofStateDescription == "")

        // Left: attempt to prove second lemma from dummy first lemma should fail.
        stateL = IsabelleSession.step(secondLemma, stateL)
        assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
        val thrown = intercept[IsabelleMLException] {
          stateL = IsabelleSession.step(secondProof, stateL).force
        }
        val msg = thrown.getMessage()
        assert(msg.contains("Failed to finish proof"))

        // Right: prove second lemma from first lemma.
        stateR = IsabelleSession.step(secondLemma, stateR)
        assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
        stateR = IsabelleSession.step(secondProof, stateR)
        assert(stateR.isTheoryMode && stateR.proofStateDescription == "")
    }
  }
}
