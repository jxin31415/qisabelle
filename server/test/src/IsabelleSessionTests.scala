package server

import de.unruh.isabelle.control.IsabelleMLException
import de.unruh.isabelle.pure.{Theory, ToplevelState}

class IsabelleSessionTests extends TestEnvironment {
  test("Simple new theory") {
    withIsabelleSession("HOL", Seq()) { session =>
      implicit val isabelle = session.isabelle

      // Start a new theory with minimal imports.
      val imports = Seq("Main")
      val importedTheories =
        ParsedTheory.loadImports(imports, "HOL", onlyFromSessionHeap = true, debug = true)
      val theory = Theory.mergeTheories("Foo", endTheory = false, theories = importedTheories)
      var state  = ToplevelState(theory)
      assert(state.isTheoryMode)

      // Execute a few definitions and a lemma statement.
      val statements = """
        |datatype 'a seq = Empty | Seq 'a "'a seq"
        |
        |fun conc :: "'a seq \<Rightarrow> 'a seq \<Rightarrow> 'a seq"
        |where
        |"conc Empty ys = ys"
        | |"conc (Seq x xs) ys = Seq x (conc xs ys)"
        |
        |lemma conc_empty: "conc xs Empty = xs"
        """.stripMargin
      state = session.parseAndExecute(statements, state)
      assert(state.isProofMode)

      // Prove the lemma.
      val proof = "by (induct xs) simp_all"
      state = session.parseAndExecute(proof, state)
      assert(state.isTheoryMode)
    }
  }

  test("New theory with imports not in heap") {
    withIsabelleSession("HOL", Seq()) { session =>
      implicit val isabelle = session.isabelle

      val imports =
        Seq(
          "Complex_Main",
          "HOL-Computational_Algebra.Primes"
        )
      val importedTheories =
        ParsedTheory.loadImports(imports, "HOL", onlyFromSessionHeap = false, debug = true)
      val theory = Theory.mergeTheories("Foo", endTheory = false, theories = importedTheories)
      var state  = ToplevelState(theory)
      assert(state.isTheoryMode)

      val lemma = "lemma foo: \"prime p \\<Longrightarrow> p > (1::nat)\""
      state = session.parseAndExecute(lemma, state)
      assert(state.isProofMode)
      println(state.proofStateDescription)

      val proof = "using prime_gt_1_nat by simp"
      state = session.parseAndExecute(proof, state)
      assert(state.isTheoryMode)
    }
  }

  test("AFP imports not in heap") {
    withIsabelleSession("HOL", Seq(afpThysDir)) { session =>
      implicit val isabelle = session.isabelle

      val imports =
        Seq(
          "Main",
          "Graph_Theory.Digraph"
        )
      val importedTheories =
        ParsedTheory.loadImports(imports, "HOL", onlyFromSessionHeap = false, debug = true)
      val theory = Theory.mergeTheories("Foo", endTheory = false, theories = importedTheories)
      var state  = ToplevelState(theory)
      assert(state.isTheoryMode)
    }
  }

  test("AFP imports in heap") {
    withIsabelleSession("Graph_Theory", Seq(afpThysDir)) { session =>
      implicit val isabelle = session.isabelle

      val imports =
        Seq(
          "Main",
          "Graph_Theory.Digraph"
        )
      val importedTheories =
        ParsedTheory.loadImports(imports, "HOL", onlyFromSessionHeap = false, debug = true)
      val theory = Theory.mergeTheories("Foo", endTheory = false, theories = importedTheories)
      var state  = ToplevelState(theory)
      assert(state.isTheoryMode)
    }
  }

  test("Execute all theory: Graph_Theory/Digraph") {
    withTheory(afpThysDir / "Graph_Theory" / "Digraph.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle

        val state = parsedTheory.executeAll()
        assert(state.isEndTheory)
    }
  }

  test("Execute all theory: Coinductive/Examples/CCPO_Topology") {
    // One with a relative '../' import.
    withTheory(afpThysDir / "Coinductive" / "Examples" / "CCPO_Topology.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle

        val state = parsedTheory.executeAll()
        assert(state.isEndTheory)
    }
  }

  test("Execute all theory: QR_Decomposition/Generalizations2") {
    // var p = afpThysDir / "Real_Impl" / "Real_Impl.thy"
    // var p = afpThysDir / "Real_Impl" / "Real_Impl_Auxiliary.thy"
    // var p = afpThysDir / "Abstract-Rewriting" / "SN_Order_Carrier.thy"
    // var p = afpThysDir / "Formula_Derivatives" / "Abstract_Formula.thy"

    // A much longer theory.
    var p = afpThysDir / "QR_Decomposition" / "Generalizations2.thy"

    withTheory(p) { (session: IsabelleSession, parsedTheory: ParsedTheory) =>
      implicit val isabelle = session.isabelle

      val state = parsedTheory.executeAll()
      assert(state.isEndTheory)
    }
  }

  test("Execute all theory: HOL/Example/Seq") {
    withTheory(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle

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

    withTheory(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle

        val stateCommon = parsedTheory.executeUntil(firstLemma, inclusive = false)
        assert(stateCommon.isTheoryMode)

        // Right: prove first lemma.
        var stateR = session.parseAndExecute(firstLemma, stateCommon)
        assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
        stateR = session.parseAndExecute(firstProof, stateR)
        assert(stateR.isTheoryMode && stateR.proofStateDescription == "")

        // Left: attempt to prove second lemma with undefined reference to first.
        var stateL = session.parseAndExecute(secondLemma, stateCommon)
        assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
        val thrown = intercept[IsabelleMLException] {
          stateL = session.parseAndExecute(secondProof, stateL).force
        }
        val msg = thrown.getMessage()
        assert(msg.contains("Undefined fact") && msg.contains("reverse_conc"))

        // Right: prove second lemma from first lemma.
        stateR = session.parseAndExecute(secondLemma, stateR)
        assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
        stateR = session.parseAndExecute(secondProof, stateR)
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

    withTheory(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle

        val stateCommon = parsedTheory.executeUntil(firstLemma, inclusive = false)
        assert(stateCommon.isTheoryMode)

        // Left: prove dummy first lemma under same name.
        var stateL = session.parseAndExecute(firstLemmaAlt, stateCommon)
        assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
        stateL = session.parseAndExecute(firstProofAlt, stateL)
        assert(stateL.isTheoryMode && stateL.proofStateDescription == "")

        // Right: prove true first lemma.
        var stateR = session.parseAndExecute(firstLemma, stateCommon)
        stateR = session.parseAndExecute(firstProof, stateR)
        assert(stateR.isTheoryMode && stateR.proofStateDescription == "")

        // Left: attempt to prove second lemma from dummy first lemma should fail.
        stateL = session.parseAndExecute(secondLemma, stateL)
        assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
        val thrown = intercept[IsabelleMLException] {
          stateL = session.parseAndExecute(secondProof, stateL).force
        }
        val msg = thrown.getMessage()
        assert(msg.contains("Failed to finish proof"))

        // Right: prove second lemma from first lemma.
        stateR = session.parseAndExecute(secondLemma, stateR)
        assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
        stateR = session.parseAndExecute(secondProof, stateR)
        assert(stateR.isTheoryMode && stateR.proofStateDescription == "")
    }
  }
}
