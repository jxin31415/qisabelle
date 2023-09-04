package server

import de.unruh.isabelle.control.IsabelleMLException
import org.scalatest.funsuite.AnyFunSuite

class IsabelleSessionTests extends AnyFunSuite {
  val afpDir      = os.Path("/home/mwrochna/projects/play/afp-2023-03-16/thys/")
  val isabelleDir = os.Path("/home/mwrochna/projects/play/Isabelle2022")
  // "/home/isabelle/Isabelle/" in container

  def withIsabelle(theoryPath: os.Path)(f: IsabelleSession => Unit) = {
    // val port = sys.env.getOrElse("QISABELLE_PORT", "17000").toInt
    val session = new IsabelleSession(
      isabelleDir = isabelleDir,
      workingDir = theoryPath / os.up, // / os.up,
      theoryPath = theoryPath,
      debug = true
    )
    try
      f(session)
    catch {
      case e: IsabelleMLException => {
        e.getMessage() // Await exception details before closing Isabelle.
        throw e
      }
    } finally
      session.close()
  }

  def exceptionMsg(e: Throwable): String = {
    e.toString() + "\n" + e.getStackTrace().mkString("\n")
  }

  test("Execute all theory: Graph_Theory/Digraph") {
    withIsabelle(afpDir / "Graph_Theory" / "Digraph.thy") { session =>
      implicit val isabelle = session.isabelle

      val state = session.execute(session.parsedTheory.transitions)
      assert(state.isEndTheory)
    }
  }

  test("Execute all theory: Coinductive/Examples/CCPO_Topology") {
    // One with a relative '../' import.
    withIsabelle(afpDir / "Coinductive" / "Examples" / "CCPO_Topology.thy") { session =>
      implicit val isabelle = session.isabelle

      val state = session.execute(session.parsedTheory.transitions)
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

    withIsabelle(p) { session =>
      implicit val isabelle = session.isabelle

      val state = session.execute(session.parsedTheory.transitions)
      assert(state.isEndTheory)
    }
  }

  test("Execute all theory: HOL/Example/Seq") {
    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") { session =>
      implicit val isabelle = session.isabelle

      val state = session.execute(session.parsedTheory.transitions, nDebug = 10000)
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

    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") { session =>
      implicit val isabelle = session.isabelle
      val stateCommon = session.execute(
        session.parsedTheory.takeUntil(firstLemma, inclusive = false)
      )
      assert(stateCommon.isTheoryMode)

      // Right: prove first lemma.
      var stateR = session.step(firstLemma, stateCommon)
      assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
      stateR = session.step(firstProof, stateR)
      assert(stateR.isTheoryMode && stateR.proofStateDescription == "")

      // Left: attempt to prove second lemma with undefined reference to first.
      var stateL = session.step(secondLemma, stateCommon)
      assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
      val thrown = intercept[IsabelleMLException] {
        stateL = session.step(secondProof, stateL).force
      }
      val msg = thrown.getMessage()
      assert(msg.contains("Undefined fact") && msg.contains("reverse_conc"))

      // Right: prove second lemma from first lemma.
      stateR = session.step(secondLemma, stateR)
      assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
      stateR = session.step(secondProof, stateR)
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

    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Seq.thy") { session =>
      implicit val isabelle = session.isabelle
      val stateCommon = session.execute(
        session.parsedTheory.takeUntil(firstLemma, inclusive = false)
      )
      assert(stateCommon.isTheoryMode)

      // Left: prove dummy first lemma under same name.
      var stateL = session.step(firstLemmaAlt, stateCommon)
      assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
      stateL = session.step(firstProofAlt, stateL)
      assert(stateL.isTheoryMode && stateL.proofStateDescription == "")

      // Right: prove true first lemma.
      var stateR = session.step(firstLemma, stateCommon)
      stateR = session.step(firstProof, stateR)
      assert(stateR.isTheoryMode && stateR.proofStateDescription == "")

      // Left: attempt to prove second lemma from dummy first lemma should fail.
      stateL = session.step(secondLemma, stateL)
      assert(stateL.isProofMode && stateL.proofStateDescription.contains("(1 subgoal"))
      val thrown = intercept[IsabelleMLException] {
        stateL = session.step(secondProof, stateL).force
      }
      val msg = thrown.getMessage()
      assert(msg.contains("Failed to finish proof"))

      // Right: prove second lemma from first lemma.
      stateR = session.step(secondLemma, stateR)
      assert(stateR.isProofMode && stateR.proofStateDescription.contains("(1 subgoal"))
      stateR = session.step(secondProof, stateR)
      assert(stateR.isTheoryMode && stateR.proofStateDescription == "")
    }
  }

  test("hammer with added name") {
    var lemma = """
      lemma permsI [Pure.intro]:
        assumes "bij f" and "MOST x. f x = x"
        shows "f \<in> perms"
    """
    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Adhoc_Overloading_Examples.thy") {
      session =>
        implicit val isabelle = session.isabelle

        var state = session.execute(
          session.parsedTheory.takeUntil(
            lemma,
            // "lemma perms_imp_bij: \"f \\<in> perms \\<Longrightarrow> bij f\"",
            inclusive = true
          ),
          nDebug = 10000
        )
        state = session.step("using assms", state)
        // state = session.step("proof (auto simp: perms_def)", state)
        val (outcome, proofTextOrMsg) =
          session.normalWithHammer(state, addedNames = List("MOST_iff_finiteNeg"))
        assert(outcome == SledgehammerOutcomes.Some)
        assert(proofTextOrMsg.startsWith("by "))
        // "by (simp add: MOST_iff_finiteNeg perms_def)"
        state = session.step(proofTextOrMsg, state)
        assert(state.isTheoryMode && state.proofStateDescription == "")
        // state = session.step("qed  (metis MOST_iff_finiteNeg)", state)
        // assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }

  test("hammer simple") {
    var lemma = """
      lemma effect_to_assignments_i:
        assumes "as = effect_to_assignments op"
        shows "as = (map (\<lambda>v. (v, True)) (add_effects_of op) @ map (\<lambda>v. (v, False)) (delete_effects_of op))"
    """
    withIsabelle(afpDir / "Verified_SAT_Based_AI_Planning" / "STRIPS_Semantics.thy") { session =>
      implicit val isabelle = session.isabelle

      var state = session.execute(
        session.parsedTheory.takeUntil(lemma, inclusive = true),
        nDebug = 2
      )
      val (outcome, proofTextOrMsg) = session.normalWithHammer(state)
      assert(outcome == SledgehammerOutcomes.Some)
      assert(proofTextOrMsg.startsWith("by "))
      // "by (simp add: assms effect__strips_def effect_to_assignments_def)"
      state = session.step(proofTextOrMsg, state)
      assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }

  test("hammer foo") {
    var firstLemma  = """
      lemma uniqify_xlate:
        "bsmap (uniqify q) = SkewBinomialHeapStruc.uniqify (bsmap q)"
    """
    var firstProof  = """
      by (cases q) (simp_all add: ins_xlate)
    """
    var secondLemma = """
      lemma meldUniq_xlate:
        "bsmap (meldUniq q q') = SkewBinomialHeapStruc.meldUniq (bsmap q) (bsmap q')"
    """
    var secondProof = """
      apply (induct q q' rule: meldUniq.induct)
      apply (auto simp add: link_xlate proj_xlate uniqify_xlate ins_xlate)
      done
    """
    var hammerLemma = """
      lemma meld_xlate:
        "bsmap (meld q q') = SkewBinomialHeapStruc.meld (bsmap q) (bsmap q')"
    """
    // by (simp add: meld_def meldUniq_xlate uniqify_xlate SkewBinomialHeapStruc.meld_def)

    withIsabelle(afpDir / "Binomial-Heaps" / "SkewBinomialHeap.thy") { session =>
      implicit val isabelle = session.isabelle

      var state = session.execute(
        session.parsedTheory.takeUntil(firstLemma, inclusive = false),
        nDebug = 2
      )

      state = session.step(firstLemma, state)
      state = session.step(firstProof, state)
      // state = session.step(secondLemma, state)
      // state = session.step(secondProof, state)

      state = session.step(hammerLemma, state)
      assert(state.isProofMode && state.proofStateDescription.contains("(1 subgoal"))

      val (outcome, proofTextOrMsg) = session.normalWithHammer(state)
      assert(outcome == SledgehammerOutcomes.Some)
      assert(proofTextOrMsg.startsWith("by "))
      state = session.step(proofTextOrMsg, state)
      assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }
}
