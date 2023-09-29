package server

import scala.concurrent.duration._

import de.unruh.isabelle.control.IsabelleMLException

class SledgehammerTests extends TestEnvironment {
  test("hammer simple") {
    var lemma = """
      lemma effect_to_assignments_i:
        assumes "as = effect_to_assignments op"
        shows "as = (map (\<lambda>v. (v, True)) (add_effects_of op) @ map (\<lambda>v. (v, False)) (delete_effects_of op))"
    """
    withTheory(afpThysDir / "Verified_SAT_Based_AI_Planning" / "STRIPS_Semantics.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        val sledgehammer      = new Sledgehammer(15.seconds, 20.seconds, 20.seconds)

        var state = parsedTheory.executeUntil(lemma, inclusive = true, nDebug = 2)
        val (outcome, proofTextOrMsg) = sledgehammer.run(state)
        assert(outcome == Sledgehammer.Outcomes.Some)
        assert(proofTextOrMsg.startsWith("by "))
        // "by (simp add: assms effect__strips_def effect_to_assignments_def)"
        state = session.parseAndExecute(proofTextOrMsg, state)
        assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }

  test("hammer shouldn't see unintroduced lemmas") {
    val firstLemma  = """
      lemma uniqify_xlate:
        "bsmap (uniqify q) = SkewBinomialHeapStruc.uniqify (bsmap q)"
    """
    val firstProof  = """
      by (cases q) (simp_all add: ins_xlate)
    """
    val secondLemma = """
      lemma meldUniq_xlate:
        "bsmap (meldUniq q q') = SkewBinomialHeapStruc.meldUniq (bsmap q) (bsmap q')"
    """
    val secondProof = """
      apply (induct q q' rule: meldUniq.induct)
      apply (auto simp add: link_xlate proj_xlate uniqify_xlate ins_xlate)
      done
    """
    val hammerLemma = """
      lemma meld_xlate:
        "bsmap (meld q q') = SkewBinomialHeapStruc.meld (bsmap q) (bsmap q')"
    """

    withTheory(afpThysDir / "Binomial-Heaps" / "SkewBinomialHeap.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        val sledgehammer      = new Sledgehammer(15.seconds, 20.seconds, 20.seconds)

        var state = parsedTheory.executeUntil(firstLemma, inclusive = false, nDebug = 2)

        state = session.parseAndExecute(firstLemma, state)
        state = session.parseAndExecute(firstProof, state)

        if (true) {
          var stateBad = session.parseAndExecute(hammerLemma, state)
          assert(stateBad.isProofMode && stateBad.proofStateDescription.contains("(1 subgoal"))
          val (outcome, proofTextOrMsg) = sledgehammer.run(stateBad)
          assert(outcome == Sledgehammer.Outcomes.Timeout)
        }

        state = session.parseAndExecute(secondLemma, state)
        state = session.parseAndExecute(secondProof, state)
        state = session.parseAndExecute(hammerLemma, state)

        val (outcome, proofTextOrMsg) = sledgehammer.run(state)
        assert(outcome == Sledgehammer.Outcomes.Some)
        assert(proofTextOrMsg.startsWith("by "))
        // by (simp add: meld_def meldUniq_xlate uniqify_xlate SkewBinomialHeapStruc.meld_def)
        state = session.parseAndExecute(proofTextOrMsg, state)
        assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }

  test("hammer with added name") {
    var lemma = """
      lemma permsI [Pure.intro]:
        assumes "bij f" and "MOST x. f x = x"
        shows "f \<in> perms"
    """
    withTheory(isabelleDir / "src" / "HOL" / "Examples" / "Adhoc_Overloading_Examples.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        val sledgehammer      = new Sledgehammer(35.seconds, 40.seconds, 40.seconds)

        var state = parsedTheory.executeUntil(lemma, inclusive = true, nDebug = 10000)
        state = session.parseAndExecute("using assms", state)
        // state = session.step("proof (auto simp: perms_def)", state)
        val (outcome, proofTextOrMsg) =
          sledgehammer.run(state, addedFacts = List("MOST_iff_finiteNeg"))
        assert(outcome == Sledgehammer.Outcomes.Some)
        assert(proofTextOrMsg.startsWith("by "))
        // "by (simp add: MOST_iff_finiteNeg perms_def)"
        state = session.parseAndExecute(proofTextOrMsg, state)
        assert(state.isTheoryMode && state.proofStateDescription == "")

    }
  }
}
