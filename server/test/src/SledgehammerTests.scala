package server

import de.unruh.isabelle.control.IsabelleMLException

class SledgehammerTests extends TestEnvironment {
  test("hammer simple") {
    var lemma = """
      lemma effect_to_assignments_i:
        assumes "as = effect_to_assignments op"
        shows "as = (map (\<lambda>v. (v, True)) (add_effects_of op) @ map (\<lambda>v. (v, False)) (delete_effects_of op))"
    """
    withIsabelle(afpDir / "Verified_SAT_Based_AI_Planning" / "STRIPS_Semantics.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        implicit val ec       = session.ec

        var state = parsedTheory.executeUntil(lemma, inclusive = true, nDebug = 2)
        val (outcome, proofTextOrMsg) = Sledgehammer.run(state)
        assert(outcome == Sledgehammer.Outcomes.Some)
        assert(proofTextOrMsg.startsWith("by "))
        // "by (simp add: assms effect__strips_def effect_to_assignments_def)"
        state = IsabelleSession.parseAndExecute(proofTextOrMsg, state)
        assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }

  test("hammer foo") {
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
    // val hammerProof = """
    //   by (simp add: meld_def meldUniq_xlate uniqify_xlate SkewBinomialHeapStruc.meld_def)
    // """

    withIsabelle(afpDir / "Binomial-Heaps" / "SkewBinomialHeap.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        implicit val ec       = session.ec

        var state = parsedTheory.executeUntil(firstLemma, inclusive = false, nDebug = 2)

        state = IsabelleSession.parseAndExecute(firstLemma, state)
        state = IsabelleSession.parseAndExecute(firstProof, state)

        if (true) {
          var stateBad = IsabelleSession.parseAndExecute(hammerLemma, state)
          assert(stateBad.isProofMode && stateBad.proofStateDescription.contains("(1 subgoal"))
          val (outcome, proofTextOrMsg) = Sledgehammer.run(stateBad)
          assert(outcome == Sledgehammer.Outcomes.Timeout)
        }

        state = IsabelleSession.parseAndExecute(secondLemma, state)
        state = IsabelleSession.parseAndExecute(secondProof, state)
        state = IsabelleSession.parseAndExecute(hammerLemma, state)

        val (outcome, proofTextOrMsg) = Sledgehammer.run(state)
        assert(outcome == Sledgehammer.Outcomes.Some)
        assert(proofTextOrMsg.startsWith("by "))
        state = IsabelleSession.parseAndExecute(proofTextOrMsg, state)
        assert(state.isTheoryMode && state.proofStateDescription == "")
    }
  }

  test("hammer with added name") {
    var lemma = """
      lemma permsI [Pure.intro]:
        assumes "bij f" and "MOST x. f x = x"
        shows "f \<in> perms"
    """
    withIsabelle(isabelleDir / "src" / "HOL" / "Examples" / "Adhoc_Overloading_Examples.thy") {
      (session: IsabelleSession, parsedTheory: ParsedTheory) =>
        implicit val isabelle = session.isabelle
        implicit val ec       = session.ec

        var state = parsedTheory.executeUntil(lemma, inclusive = true, nDebug = 10000)
        state = IsabelleSession.parseAndExecute("using assms", state)
        // state = session.step("proof (auto simp: perms_def)", state)
        val (outcome, proofTextOrMsg) =
          Sledgehammer.run(state, addedFacts = List("MOST_iff_finiteNeg"))
        assert(outcome == Sledgehammer.Outcomes.Some)
        assert(proofTextOrMsg.startsWith("by "))
        // "by (simp add: MOST_iff_finiteNeg perms_def)"
        state = IsabelleSession.parseAndExecute(proofTextOrMsg, state)
        assert(state.isTheoryMode && state.proofStateDescription == "")

    }
  }
}
